package app.gymquest.core.economy

import app.gymquest.core.avatar.Cosmetics
import app.gymquest.core.avatar.Rarity
import kotlin.math.max
import kotlin.math.roundToLong
import kotlin.math.tanh

enum class ItemKind { COSMETIC, CONSUMABLE }

data class ItemDef(
    val id: String,
    val name: String,
    val kind: ItemKind,
    val baseValue: Long,
    val tradable: Boolean,
    val rarity: Rarity,
    val description: String = "",
)

/** Everything that can sit in a backpack: outfit pieces plus consumables. */
object ItemCatalog {
    private val consumables = listOf(
        ItemDef("xp_potion", "Poción de XP", ItemKind.CONSUMABLE, 120, true, Rarity.UNCOMMON, "+25% XP durante 1 hora."),
        ItemDef("streak_freeze", "Escudo de racha", ItemKind.CONSUMABLE, 300, true, Rarity.RARE, "Salva tu racha si faltas un día."),
        ItemDef("territory_shield", "Escudo de territorio", ItemKind.CONSUMABLE, 200, true, Rarity.UNCOMMON, "Protege tus zonas 24 horas."),
        ItemDef("mission_reroll", "Cambio de misión", ItemKind.CONSUMABLE, 80, true, Rarity.COMMON, "Cambia una misión diaria por otra."),
        ItemDef("boss_ticket", "Boleto de jefe", ItemKind.CONSUMABLE, 150, true, Rarity.UNCOMMON, "Reintenta un jefe sin esperar."),
    )

    private val cosmeticDefs: List<ItemDef> = Cosmetics.all.map { c ->
        val value = when (val u = c.unlock) {
            is app.gymquest.core.avatar.Unlock.Shop -> u.price
            else -> (60 * c.rarity.valueMultiplier).roundToLong()
        }
        ItemDef(c.id, c.name, ItemKind.COSMETIC, value, c.tradable, c.rarity)
    }

    val all: List<ItemDef> = cosmeticDefs + consumables

    private val byId = all.associateBy { it.id }

    fun byId(id: String): ItemDef? = byId[id]
}

/** An immutable backpack. */
data class Inventory(val items: Map<String, Int> = emptyMap()) {
    fun count(itemId: String): Int = items[itemId] ?: 0

    fun add(itemId: String, qty: Int = 1): Inventory {
        require(qty > 0)
        return Inventory(items + (itemId to count(itemId) + qty))
    }

    fun remove(itemId: String, qty: Int = 1): Inventory? {
        require(qty > 0)
        val have = count(itemId)
        if (have < qty) return null
        return Inventory(if (have == qty) items - itemId else items + (itemId to have - qty))
    }

    val owned: Set<String> get() = items.keys
}

enum class Reason(val isFaucet: Boolean) {
    MISSION(true), BOSS(true), RAID(true), TERRITORY(true), LEVEL_UP(true), ACHIEVEMENT(true), DUEL_WIN(false),
    WAR_PRIZE(false), SHOP_BUY(false), MARKET_BUY(false), MARKET_SELL(false), FEE(false), TRADE(false), STAKE(false), REFUND(false),
}

data class LedgerEntry(val id: String, val userId: String, val delta: Long, val reason: Reason, val timeMs: Long)

sealed interface LedgerResult {
    data class Applied(val delta: Long) : LedgerResult
    data object Duplicate : LedgerResult
    data object InsufficientFunds : LedgerResult
}

/**
 * Append-only coin ledger. Balances are the sum of entries, every entry has a unique id (so a
 * retry after a lost connection is harmless), and coins from "faucets" (rewards created out of
 * thin air) are capped per day. That cap is what keeps the economy from inflating.
 */
class Ledger(private val dailyFaucetCap: Long = 2_000) {
    private val seen = HashSet<String>()
    private val balances = HashMap<String, Long>()
    private val faucetByUserDay = HashMap<Pair<String, Long>, Long>()
    private val entries = mutableListOf<LedgerEntry>()

    val history: List<LedgerEntry> get() = entries

    fun balance(userId: String): Long = balances[userId] ?: 0

    fun totalSupply(): Long = balances.values.sum()

    fun faucetToday(userId: String, nowMs: Long): Long = faucetByUserDay[userId to nowMs / DAY_MS] ?: 0

    fun record(e: LedgerEntry): LedgerResult {
        if (e.id in seen) return LedgerResult.Duplicate
        var delta = e.delta
        if (delta < 0 && balance(e.userId) + delta < 0) return LedgerResult.InsufficientFunds
        if (delta > 0 && e.reason.isFaucet) {
            val key = e.userId to e.timeMs / DAY_MS
            val used = faucetByUserDay[key] ?: 0
            delta = delta.coerceAtMost(max(0, dailyFaucetCap - used))
            faucetByUserDay[key] = used + delta
        }
        seen += e.id
        balances.merge(e.userId, delta, Long::plus)
        entries += e.copy(delta = delta)
        return LedgerResult.Applied(delta)
    }

    companion object {
        const val DAY_MS = 86_400_000L
    }
}

/** Shop prices drift with demand: when everyone buys one item it gets pricier, when nobody does it gets cheaper. */
object NpcShop {
    fun price(item: ItemDef, purchasesLast24h: Int, expectedPurchases: Int): Long {
        val expected = max(expectedPurchases, 1).toDouble()
        val pressure = tanh((purchasesLast24h - expected) / expected)
        val multiplier = 1.0 + 0.25 * pressure
        return max(1L, (item.baseValue * multiplier).roundToLong())
    }

    /** Only cosmetics with a shop price are sold by the NPC; everything else comes from play or other players. */
    fun stock(): List<ItemDef> = ItemCatalog.all.filter { def ->
        Cosmetics.byId(def.id)?.unlock is app.gymquest.core.avatar.Unlock.Shop || def.kind == ItemKind.CONSUMABLE
    }
}

/**
 * Nudges how generous rewards are so the economy stays healthy without manual tuning: if players
 * are sitting on too many coins, rewards shrink a little each day; if coins are scarce, they grow.
 */
object MonetaryPolicy {
    const val MIN = 0.5
    const val MAX = 1.5

    fun adjust(current: Double, averageBalance: Double, targetAverage: Double): Double {
        val next = when {
            averageBalance > targetAverage * 1.2 -> current - 0.05
            averageBalance < targetAverage * 0.8 -> current + 0.05
            else -> current
        }
        return next.coerceIn(MIN, MAX)
    }

    fun reward(base: Long, multiplier: Double): Long = (base * multiplier).roundToLong().coerceAtLeast(0)
}
