package app.gymquest.core.economy

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

data class Listing(
    val id: String,
    val sellerId: String,
    val itemId: String,
    val quantity: Int,
    val unitPrice: Long,
    val createdMs: Long,
    val expiresMs: Long,
)

sealed interface MarketResult<out T> {
    data class Ok<T>(val value: T) : MarketResult<T>
    data class Rejected(val reason: MarketError) : MarketResult<Nothing>
}

enum class MarketError {
    UNKNOWN_ITEM, NOT_TRADABLE, LEVEL_TOO_LOW, PRICE_OUT_OF_BAND, TOO_MANY_LISTINGS, BAD_QUANTITY, NOT_ENOUGH_ITEMS,
    LISTING_NOT_FOUND, OWN_LISTING, NOT_ENOUGH_COINS, QUANTITY_UNAVAILABLE, EXPIRED, NOT_YOURS, SAME_USER, TRADE_LIMIT,
}

private fun <T> ok(v: T): MarketResult<T> = MarketResult.Ok(v)
private fun rejected(e: MarketError): MarketResult<Nothing> = MarketResult.Rejected(e)

/**
 * Player-to-player marketplace and trading, with rules that make prices police themselves:
 *
 * - Every item has a *fair price*, an average of recent sales; listings must be within 0.5x–3x of it,
 *   so nobody can dump an item for nothing or ask the moon, and one weird sale cannot move the price much.
 * - Listing costs 2% and each sale is taxed 5%: coins leave the economy so rewards do not inflate it.
 * - Items are held in escrow while listed, and every coin movement goes through the [Ledger].
 * - Repeated trades between the same two players do not count toward the fair price (wash-trading).
 */
class Exchange(
    val ledger: Ledger = Ledger(),
    private val minLevel: Int = 5,
) {
    private val inventories = HashMap<String, Inventory>()
    private val listings = LinkedHashMap<String, Listing>()
    private val fairPrices = HashMap<String, Double>()
    private val volume = HashMap<String, Int>()
    private val recentPairTrades = HashMap<Triple<String, String, Long>, Int>()
    private val tradesPerUserDay = HashMap<Pair<String, Long>, Int>()
    private var nextId = 1

    fun inventory(userId: String): Inventory = inventories[userId] ?: Inventory()

    fun grant(userId: String, itemId: String, qty: Int = 1) {
        inventories[userId] = inventory(userId).add(itemId, qty)
    }

    fun activeListings(): List<Listing> = listings.values.toList()

    fun listingsOf(userId: String): List<Listing> = listings.values.filter { it.sellerId == userId }

    fun fairPrice(itemId: String): Long =
        (fairPrices[itemId] ?: ItemCatalog.byId(itemId)?.baseValue?.toDouble() ?: 0.0).roundToLong().coerceAtLeast(1)

    fun priceBand(itemId: String): LongRange {
        val fair = fairPrice(itemId)
        return max(1L, (fair * MIN_BAND).roundToLong())..max(2L, (fair * MAX_BAND).roundToLong())
    }

    fun tradedVolume(itemId: String): Int = volume[itemId] ?: 0

    // ---- Marketplace ----------------------------------------------------------------------

    fun list(sellerId: String, sellerLevel: Int, itemId: String, qty: Int, unitPrice: Long, nowMs: Long): MarketResult<Listing> {
        val def = ItemCatalog.byId(itemId) ?: return rejected(MarketError.UNKNOWN_ITEM)
        if (!def.tradable) return rejected(MarketError.NOT_TRADABLE)
        if (sellerLevel < minLevel) return rejected(MarketError.LEVEL_TOO_LOW)
        if (qty !in 1..MAX_QTY) return rejected(MarketError.BAD_QUANTITY)
        if (unitPrice !in priceBand(itemId)) return rejected(MarketError.PRICE_OUT_OF_BAND)
        if (listingsOf(sellerId).size >= MAX_LISTINGS) return rejected(MarketError.TOO_MANY_LISTINGS)
        val inv = inventory(sellerId).remove(itemId, qty) ?: return rejected(MarketError.NOT_ENOUGH_ITEMS)

        val fee = max(1L, unitPrice * qty * LISTING_FEE_PERCENT / 100)
        val id = "L${nextId++}"
        val charged = ledger.record(LedgerEntry("fee-$id", sellerId, -fee, Reason.FEE, nowMs))
        if (charged == LedgerResult.InsufficientFunds) return rejected(MarketError.NOT_ENOUGH_COINS)

        inventories[sellerId] = inv // escrow: the item leaves the backpack while listed
        val listing = Listing(id, sellerId, itemId, qty, unitPrice, nowMs, nowMs + LISTING_DURATION_MS)
        listings[id] = listing
        return ok(listing)
    }

    fun cancel(userId: String, listingId: String): MarketResult<Unit> {
        val l = listings[listingId] ?: return rejected(MarketError.LISTING_NOT_FOUND)
        if (l.sellerId != userId) return rejected(MarketError.NOT_YOURS)
        listings.remove(listingId)
        grant(l.sellerId, l.itemId, l.quantity)
        return ok(Unit)
    }

    fun buy(buyerId: String, buyerLevel: Int, listingId: String, qty: Int, nowMs: Long): MarketResult<Long> {
        val l = listings[listingId] ?: return rejected(MarketError.LISTING_NOT_FOUND)
        if (nowMs >= l.expiresMs) return rejected(MarketError.EXPIRED)
        if (l.sellerId == buyerId) return rejected(MarketError.OWN_LISTING)
        if (buyerLevel < minLevel) return rejected(MarketError.LEVEL_TOO_LOW)
        if (qty !in 1..l.quantity) return rejected(MarketError.QUANTITY_UNAVAILABLE)

        val total = l.unitPrice * qty
        val tax = total * SALE_TAX_PERCENT / 100
        val day = nowMs / DAY_MS
        if ((tradesPerUserDay[buyerId to day] ?: 0) >= MAX_TRADES_PER_DAY) return rejected(MarketError.TRADE_LIMIT)

        val paid = ledger.record(LedgerEntry("buy-$listingId-$nowMs-$buyerId", buyerId, -total, Reason.MARKET_BUY, nowMs))
        if (paid == LedgerResult.InsufficientFunds) return rejected(MarketError.NOT_ENOUGH_COINS)
        ledger.record(LedgerEntry("sell-$listingId-$nowMs-$buyerId", l.sellerId, total - tax, Reason.MARKET_SELL, nowMs))

        grant(buyerId, l.itemId, qty)
        if (qty == l.quantity) listings.remove(listingId) else listings[listingId] = l.copy(quantity = l.quantity - qty)

        val pairKey = Triple(minOf(buyerId, l.sellerId), maxOf(buyerId, l.sellerId), day)
        val pairTrades = (recentPairTrades[pairKey] ?: 0) + 1
        recentPairTrades[pairKey] = pairTrades
        tradesPerUserDay.merge(buyerId to day, 1, Int::plus)
        volume.merge(l.itemId, qty, Int::plus)
        if (pairTrades <= WASH_TRADE_LIMIT) updateFairPrice(l.itemId, l.unitPrice)
        return ok(total)
    }

    /** Returns expired listings' items to their sellers. */
    fun sweepExpired(nowMs: Long): Int {
        val expired = listings.values.filter { nowMs >= it.expiresMs }
        for (l in expired) {
            listings.remove(l.id)
            grant(l.sellerId, l.itemId, l.quantity)
        }
        return expired.size
    }

    private fun updateFairPrice(itemId: String, price: Long) {
        val fair = fairPrices[itemId] ?: ItemCatalog.byId(itemId)?.baseValue?.toDouble() ?: price.toDouble()
        // A single sale only nudges the average, and prices outside the band are clamped first.
        val clamped = price.toDouble().coerceIn(fair * MIN_BAND, fair * MAX_BAND)
        fairPrices[itemId] = fair + FAIR_PRICE_ALPHA * (clamped - fair)
    }

    // ---- Direct trades --------------------------------------------------------------------

    data class TradeOffer(val fromId: String, val toId: String, val items: Map<String, Int>, val coins: Long)

    /** Both sides are validated before anything moves, so a trade either happens completely or not at all. */
    fun trade(offer: TradeOffer, fromLevel: Int, toLevel: Int, nowMs: Long, counter: TradeOffer? = null): MarketResult<Unit> {
        if (offer.fromId == offer.toId) return rejected(MarketError.SAME_USER)
        if (fromLevel < minLevel || toLevel < minLevel) return rejected(MarketError.LEVEL_TOO_LOW)
        val day = nowMs / DAY_MS
        if ((tradesPerUserDay[offer.fromId to day] ?: 0) >= MAX_TRADES_PER_DAY ||
            (tradesPerUserDay[offer.toId to day] ?: 0) >= MAX_TRADES_PER_DAY
        ) return rejected(MarketError.TRADE_LIMIT)

        val reverse = counter ?: TradeOffer(offer.toId, offer.fromId, emptyMap(), 0)
        require(reverse.fromId == offer.toId && reverse.toId == offer.fromId) { "La contraoferta debe ir en sentido contrario" }

        var a = inventory(offer.fromId)
        var b = inventory(offer.toId)
        for ((id, q) in offer.items) {
            val def = ItemCatalog.byId(id) ?: return rejected(MarketError.UNKNOWN_ITEM)
            if (!def.tradable) return rejected(MarketError.NOT_TRADABLE)
            if (q <= 0) return rejected(MarketError.BAD_QUANTITY)
            a = a.remove(id, q) ?: return rejected(MarketError.NOT_ENOUGH_ITEMS)
            b = b.add(id, q)
        }
        for ((id, q) in reverse.items) {
            val def = ItemCatalog.byId(id) ?: return rejected(MarketError.UNKNOWN_ITEM)
            if (!def.tradable) return rejected(MarketError.NOT_TRADABLE)
            if (q <= 0) return rejected(MarketError.BAD_QUANTITY)
            b = b.remove(id, q) ?: return rejected(MarketError.NOT_ENOUGH_ITEMS)
            a = a.add(id, q)
        }
        if (ledger.balance(offer.fromId) < offer.coins || ledger.balance(offer.toId) < reverse.coins) return rejected(MarketError.NOT_ENOUGH_COINS)

        // Everything checks out: apply. A small tax on coins keeps trades from being a free money pipe.
        fun move(from: String, to: String, coins: Long, tag: String) {
            if (coins <= 0) return
            val tax = coins * TRADE_TAX_PERCENT / 100
            ledger.record(LedgerEntry("trade-$tag-$from-$nowMs", from, -coins, Reason.TRADE, nowMs))
            ledger.record(LedgerEntry("trade-$tag-$to-$nowMs", to, coins - tax, Reason.TRADE, nowMs))
        }
        move(offer.fromId, offer.toId, offer.coins, "a")
        move(reverse.fromId, reverse.toId, reverse.coins, "b")
        inventories[offer.fromId] = a
        inventories[offer.toId] = b
        tradesPerUserDay.merge(offer.fromId to day, 1, Int::plus)
        tradesPerUserDay.merge(offer.toId to day, 1, Int::plus)
        return ok(Unit)
    }

    companion object {
        const val DAY_MS = 86_400_000L
        const val LISTING_DURATION_MS = 3 * DAY_MS
        const val MAX_LISTINGS = 10
        const val MAX_QTY = 99
        const val MIN_BAND = 0.5
        const val MAX_BAND = 3.0
        const val LISTING_FEE_PERCENT = 2L
        const val SALE_TAX_PERCENT = 5L
        const val TRADE_TAX_PERCENT = 3L
        const val FAIR_PRICE_ALPHA = 0.2
        const val WASH_TRADE_LIMIT = 3
        const val MAX_TRADES_PER_DAY = 20
    }
}
