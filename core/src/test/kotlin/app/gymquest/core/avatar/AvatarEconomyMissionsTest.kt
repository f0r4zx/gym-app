package app.gymquest.core.avatar

import app.gymquest.core.economy.Exchange
import app.gymquest.core.economy.Inventory
import app.gymquest.core.economy.ItemCatalog
import app.gymquest.core.economy.Ledger
import app.gymquest.core.economy.LedgerEntry
import app.gymquest.core.economy.LedgerResult
import app.gymquest.core.economy.MarketError
import app.gymquest.core.economy.MarketResult
import app.gymquest.core.economy.MonetaryPolicy
import app.gymquest.core.economy.NpcShop
import app.gymquest.core.economy.Reason
import app.gymquest.core.exercise.ExerciseType
import app.gymquest.core.fitness.Equipment
import app.gymquest.core.fitness.Experience
import app.gymquest.core.fitness.Sex
import app.gymquest.core.game.BattleState
import app.gymquest.core.game.BossBattle
import app.gymquest.core.game.Bosses
import app.gymquest.core.exercise.RepEvent
import app.gymquest.core.missions.DailyMissions
import app.gymquest.core.missions.GameEvent
import app.gymquest.core.missions.MissionBoard
import app.gymquest.core.missions.MissionCategory
import app.gymquest.core.missions.MissionContext
import app.gymquest.core.missions.MissionKind
import app.gymquest.core.missions.MissionStreak
import app.gymquest.core.tracking.ActivityType
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val DAY = 86_400_000L
private const val T0 = 1_800_000_000_000L

class CharacterStatsTest {
    @Test
    fun repsRaiseTheRightStats() {
        val s = CharacterStats()
        s.addAll(StatSources.fromExercise(ExerciseType.PULL_UP, 100, 1.0), T0)
        s.addAll(StatSources.fromExercise(ExerciseType.JUMP_ROPE, 500, 0.9), T0)
        val v = s.all(T0)
        assertTrue(v.getValue(Stat.STRENGTH) >= 10, "str ${v[Stat.STRENGTH]}")
        assertTrue(v.getValue(Stat.AGILITY) > v.getValue(Stat.VITALITY))
        assertTrue(v.getValue(Stat.VITALITY) == 1)
    }

    @Test
    fun valuesHaveDiminishingReturnsAndNeverExceed100() {
        val s = CharacterStats()
        var last = 0
        for (i in 1..40) {
            s.addAll(StatSources.fromExercise(ExerciseType.DEADLIFT, 500, 1.0), T0)
            val now = s.value(Stat.STRENGTH, T0)
            assertTrue(now >= last && now <= 100)
            last = now
        }
        s.addAll(StatSources.fromExercise(ExerciseType.DEADLIFT, 1_000_000, 1.0), T0)
        assertEquals(100, s.value(Stat.STRENGTH, T0))
        val a = CharacterStats().also { it.addAll(StatSources.fromExercise(ExerciseType.PUSH_UP, 500, 1.0), T0) }.value(Stat.STRENGTH, T0)
        val b = CharacterStats().also { it.addAll(StatSources.fromExercise(ExerciseType.PUSH_UP, 1_000, 1.0), T0) }.value(Stat.STRENGTH, T0)
        assertTrue(b > a && b < a * 2)
    }

    @Test
    fun statsFadeIfYouStopTraining() {
        val s = CharacterStats(halfLifeDays = 60.0)
        s.addAll(StatSources.fromExercise(ExerciseType.SQUAT, 1_500, 1.0), T0)
        val fresh = s.value(Stat.STRENGTH, T0)
        val later = s.value(Stat.STRENGTH, T0 + 120 * DAY)
        assertTrue(later < fresh * 0.6, "fresh $fresh later $later")
        assertEquals(1, s.value(Stat.SPEED, T0))
    }

    @Test
    fun consistencyBeatsABurst() {
        val burst = CharacterStats()
        burst.addAll(StatSources.fromExercise(ExerciseType.SQUAT, 3_000, 1.0), T0)
        val steady = CharacterStats()
        for (d in 0 until 60) steady.addAll(StatSources.fromExercise(ExerciseType.SQUAT, 50, 1.0), T0 + d * DAY)
        // Same total reps, but spread out and recent beats a pile done 60 days ago.
        val now = T0 + 60 * DAY
        assertTrue(steady.value(Stat.STRENGTH, now) > burst.value(Stat.STRENGTH, now))
    }

    @Test
    fun qualityMatters() {
        val clean = StatSources.fromExercise(ExerciseType.PUSH_UP, 100, 1.0).first().points
        val sloppy = StatSources.fromExercise(ExerciseType.PUSH_UP, 100, 0.1).first().points
        assertTrue(clean > sloppy && sloppy > clean * 0.5)
    }

    @Test
    fun runningBuildsEnduranceAndSpeedOnlyWhenFast() {
        val jog = StatSources.fromActivity(ActivityType.RUN, 5_000.0, 2.0)  // 7.2 km/h
        val fast = StatSources.fromActivity(ActivityType.RUN, 5_000.0, 3.6) // 13 km/h
        assertTrue(jog.any { it.stat == Stat.ENDURANCE })
        assertTrue(fast.first { it.stat == Stat.SPEED }.points > (jog.firstOrNull { it.stat == Stat.SPEED }?.points ?: 0.0))
        val walk = StatSources.fromActivity(ActivityType.WALK, 5_000.0, 1.3)
        assertTrue(walk.none { it.stat == Stat.SPEED })
        assertTrue(fast.first { it.stat == Stat.ENDURANCE }.points > walk.first { it.stat == Stat.ENDURANCE }.points)
    }

    @Test
    fun healthyHabitsBuildVitality() {
        assertTrue(StatSources.fromHabits(true, true, true, 3, true).first().points > StatSources.fromHabits(false, false, false, 0, false).sumOf { it.points } + 20)
        assertTrue(StatSources.fromHabits(false, false, false, 0, false).isEmpty())
    }

    @Test
    fun ranksAndPowerAreOrdered() {
        assertEquals(StatRank.E, CharacterStats.rank(5))
        assertEquals(StatRank.C, CharacterStats.rank(40))
        assertEquals(StatRank.S, CharacterStats.rank(95))
        val weak = CharacterStats()
        val strong = CharacterStats().also { s ->
            s.addAll(StatSources.fromExercise(ExerciseType.PULL_UP, 800, 1.0), T0)
            s.addAll(StatSources.fromActivity(ActivityType.RUN, 100_000.0, 3.5), T0)
        }
        assertTrue(strong.power(T0) > weak.power(T0) + 30)
    }

    @Test
    fun statsMakeBossFightsEasierWithinLimits() {
        val boss = Bosses.byId("slime_squat")!!
        val maxed = BattleModifiers.fromStats(Stat.entries.associateWith { 100 })
        assertEquals(1.25, maxed.damageMultiplier, 1e-9)
        assertEquals(150, maxed.playerMaxHp)
        assertEquals(1.2, maxed.bossIntervalMultiplier, 1e-9)
        val none = BattleModifiers.fromStats(Stat.entries.associateWith { 1 })
        assertTrue(none.damageMultiplier < 1.01 && none.playerMaxHp <= 101)

        fun repsToWin(mods: BattleModifiers): Int {
            val b = BossBattle(boss, 0, mods)
            var n = 0
            while (b.state == BattleState.IN_PROGRESS && n < 500) {
                n++
                b.onRep(RepEvent(n, n * 2_000L, 0.9, 2000, 1.0, emptySet()), ExerciseType.SQUAT)
            }
            return n
        }
        assertTrue(repsToWin(maxed) < repsToWin(BattleModifiers.NONE))
        assertEquals(150, BossBattle(boss, 0, maxed).playerMaxHp)
    }

    @Test
    fun strongerCharactersSurviveLongerRests() {
        val boss = Bosses.byId("slime_squat")!!
        val mods = BattleModifiers.fromStats(Stat.entries.associateWith { 100 })
        val plain = BossBattle(boss, 0)
        val strong = BossBattle(boss, 0, mods)
        plain.tick((boss.attackIntervalMs * 1.1).toLong())
        strong.tick((boss.attackIntervalMs * 1.1).toLong())
        assertTrue(plain.playerHp < 100)
        assertEquals(150, strong.playerHp) // boss is slower against a fast, agile character
    }
}

class CosmeticsTest {
    @Test
    fun idsAreUniqueAndEveryBossLootExists() {
        assertEquals(Cosmetics.all.size, Cosmetics.all.map { it.id }.toSet().size)
        for (boss in Bosses.all) {
            val item = Cosmetics.byId(boss.lootId)
            assertNotNull(item, "no cosmetic for loot ${boss.lootId}")
            assertEquals(Unlock.Boss(boss.id), item.unlock, "${boss.lootId} must unlock from ${boss.id}")
        }
    }

    @Test
    fun unlocksFollowProgress() {
        val start = Cosmetics.unlocked(UnlockProgress())
        assertTrue(setOf("starter_shirt", "starter_shorts", "starter_shoes").all { it in start })
        assertTrue("sport_shirt" !in start && "slime_helmet" !in start)

        val p = UnlockProgress(level = 10, bossesDefeated = setOf("slime_squat"), bestStreak = 7, territoryCells = 150, purchased = setOf("sunglasses"))
        val u = Cosmetics.unlocked(p)
        assertTrue(setOf("sport_shirt", "hoodie", "slime_helmet", "flame_aura", "explorer_cape", "sunglasses").all { it in u })
        assertTrue("aura_blue" !in u && "iron_will_belt" !in u)
    }

    @Test
    fun levellingUpReportsExactlyTheNewItems() {
        val before = UnlockProgress(level = 4)
        val after = UnlockProgress(level = 5)
        assertEquals(listOf("sport_shirt"), Cosmetics.newlyUnlocked(before, after).map { it.id })
        assertTrue(Cosmetics.newlyUnlocked(after, after).isEmpty())
        assertTrue(LevelRewards.forLevel(10).coins > LevelRewards.forLevel(9).coins + 50)
    }

    @Test
    fun onlyBossLootAndShopItemsAreTradable() {
        assertTrue(Cosmetics.byId("slime_helmet")!!.tradable)
        assertTrue(Cosmetics.byId("sunglasses")!!.tradable)
        assertTrue(!Cosmetics.byId("starter_shirt")!!.tradable)
        assertTrue(!Cosmetics.byId("flame_aura")!!.tradable)
        assertTrue(!Cosmetics.byId("conqueror_crown")!!.tradable)
        assertTrue(ItemCatalog.byId("slime_helmet")!!.tradable && !ItemCatalog.byId("hoodie")!!.tradable)
    }

    @Test
    fun outfitsAllowOneItemPerSlotAndOnlyOwnedItems() {
        val owned = setOf("starter_shirt", "hoodie", "headband")
        var o = Outfit.starter()
        o = o.equip("hoodie", owned)
        assertEquals("hoodie", o.equipped[Slot.TORSO])
        o = o.equip("sunglasses", owned) // not owned: ignored
        assertNull(o.equipped[Slot.HEAD])
        o = o.equip("headband", owned)
        assertEquals("headband", o.equipped[Slot.HEAD])
        o = o.equip("no_such_item", owned)
        assertEquals(setOf(Slot.TORSO, Slot.LEGS, Slot.FEET, Slot.HEAD), o.equipped.keys)
        assertEquals(setOf(Slot.LEGS, Slot.FEET, Slot.HEAD), o.unequip(Slot.TORSO).equipped.keys)
        // Sell the hoodie and it comes off.
        assertNull(o.cleaned(setOf("headband")).equipped[Slot.TORSO])
    }

    @Test
    fun shopHasAffordablePriced() {
        assertTrue(Cosmetics.shopItems().isNotEmpty())
        assertTrue(Cosmetics.shopItems().all { (it.unlock as Unlock.Shop).price > 0 })
    }
}

class AvatarMorphTest {
    @Test
    fun theCharacterGetsLeanerAsYouLoseWeight() {
        var previous = Double.MAX_VALUE
        for (kg in listOf(110.0, 100.0, 90.0, 80.0, 72.0, 65.0)) {
            val f = AvatarMorph.fatness(Sex.MALE, 178.0, kg, null)
            assertTrue(f <= previous, "$kg kg -> $f")
            previous = f
        }
        assertTrue(AvatarMorph.fatness(Sex.MALE, 178.0, 110.0, null) > 0.7)
        assertTrue(AvatarMorph.fatness(Sex.MALE, 178.0, 65.0, null) < 0.3)
    }

    @Test
    fun bodyFatBeatsBmiWhenKnown() {
        // A muscular person: high BMI but low body fat.
        val bmiOnly = AvatarMorph.fatness(Sex.MALE, 180.0, 95.0, null)
        val withFat = AvatarMorph.fatness(Sex.MALE, 180.0, 95.0, 12.0)
        assertTrue(withFat < bmiOnly)
    }

    @Test
    fun sameBodyFatLooksDifferentBySex() {
        assertTrue(AvatarMorph.fatness(Sex.FEMALE, 165.0, 60.0, 24.0) < AvatarMorph.fatness(Sex.MALE, 178.0, 80.0, 24.0))
    }

    @Test
    fun muscleComesFromStrength() {
        val weak = AvatarMorph.muscle(mapOf(Stat.STRENGTH to 5, Stat.ENDURANCE to 5))
        val strong = AvatarMorph.muscle(mapOf(Stat.STRENGTH to 90, Stat.ENDURANCE to 40))
        assertTrue(strong > weak + 0.5)
        assertEquals(0.0 + 0.01 * 0.85 + 0.01 * 0.15, AvatarMorph.muscle(emptyMap()), 1e-9)
    }

    @Test
    fun heightMapsToTheSlider() {
        assertEquals(0.0, AvatarMorph.shape(Sex.OTHER, 140.0, 60.0, null, emptyMap()).height)
        assertEquals(0.5, AvatarMorph.shape(Sex.OTHER, 170.0, 60.0, null, emptyMap()).height, 1e-9)
        assertEquals(1.0, AvatarMorph.shape(Sex.OTHER, 200.0, 60.0, null, emptyMap()).height)
    }

    @Test
    fun shapeProportionsRespondToFatAndMuscle() {
        val lean = BodyShape(0.1, 0.1, 0.5)
        val heavy = BodyShape(0.9, 0.1, 0.5)
        val buff = BodyShape(0.2, 0.9, 0.5)
        assertTrue(heavy.waist > lean.waist && heavy.belly > 0.5 && lean.belly == 0.0)
        assertTrue(buff.shoulders > lean.shoulders && buff.limbThickness > lean.limbThickness)
    }

    @Test
    fun blendAndTimelineAnimateBetweenStates() {
        val a = BodyShape(0.9, 0.1, 0.5)
        val b = BodyShape(0.3, 0.6, 0.5)
        assertEquals(a, AvatarMorph.blend(a, b, 0.0))
        assertEquals(b, AvatarMorph.blend(a, b, 1.0))
        assertEquals(0.6, AvatarMorph.blend(a, b, 0.5).fatness, 1e-9)
        val frames = AvatarMorph.timeline(listOf(a, BodyShape(0.6, 0.3, 0.5), b), 11)
        assertEquals(11, frames.size)
        assertEquals(a, frames.first())
        assertEquals(b, frames.last())
        assertTrue(frames.zipWithNext().all { (x, y) -> y.fatness <= x.fatness + 1e-9 })
        assertTrue(AvatarMorph.describe(a).contains("corpulento") && AvatarMorph.describe(BodyShape(0.1, 0.8, 0.5)).contains("musculoso"))
    }
}

class LedgerAndShopTest {
    private fun entry(id: String, user: String, delta: Long, reason: Reason, t: Long = T0) = LedgerEntry(id, user, delta, reason, t)

    @Test
    fun entriesAreIdempotent() {
        val l = Ledger()
        assertEquals(LedgerResult.Applied(100), l.record(entry("e1", "ana", 100, Reason.MISSION)))
        assertEquals(LedgerResult.Duplicate, l.record(entry("e1", "ana", 100, Reason.MISSION)))
        assertEquals(100, l.balance("ana"))
    }

    @Test
    fun noOverdrafts() {
        val l = Ledger()
        l.record(entry("e1", "ana", 50, Reason.MISSION))
        assertEquals(LedgerResult.InsufficientFunds, l.record(entry("e2", "ana", -80, Reason.SHOP_BUY)))
        assertEquals(50, l.balance("ana"))
        assertEquals(LedgerResult.Applied(-30), l.record(entry("e3", "ana", -30, Reason.SHOP_BUY)))
    }

    @Test
    fun dailyFaucetCapStopsRewardInflationButNotTrades() {
        val l = Ledger(dailyFaucetCap = 500)
        assertEquals(LedgerResult.Applied(400), l.record(entry("a", "ana", 400, Reason.BOSS)))
        assertEquals(LedgerResult.Applied(100), l.record(entry("b", "ana", 300, Reason.MISSION))) // only 100 left today
        assertEquals(LedgerResult.Applied(0), l.record(entry("c", "ana", 50, Reason.RAID)))
        assertEquals(500, l.balance("ana"))
        // Money from other players is not new money, so it is not capped.
        assertEquals(LedgerResult.Applied(900), l.record(entry("d", "ana", 900, Reason.MARKET_SELL)))
        // A new day resets the allowance.
        assertEquals(LedgerResult.Applied(300), l.record(entry("e", "ana", 300, Reason.MISSION, T0 + DAY)))
        assertEquals(500, l.faucetToday("ana", T0))
    }

    @Test
    fun inventoryOperations() {
        val inv = Inventory().add("xp_potion", 3)
        assertEquals(3, inv.count("xp_potion"))
        assertEquals(1, inv.remove("xp_potion", 2)!!.count("xp_potion"))
        assertNull(inv.remove("xp_potion", 4))
        assertTrue("xp_potion" !in inv.remove("xp_potion", 3)!!.owned)
    }

    @Test
    fun shopPricesFollowDemand() {
        val item = ItemCatalog.byId("xp_potion")!!
        val normal = NpcShop.price(item, 10, 10)
        val hot = NpcShop.price(item, 40, 10)
        val cold = NpcShop.price(item, 0, 10)
        assertEquals(item.baseValue, normal)
        assertTrue(hot > normal && cold < normal)
        assertTrue(hot <= item.baseValue * 1.26 && cold >= item.baseValue * 0.74)
        assertTrue(NpcShop.stock().isNotEmpty())
    }

    @Test
    fun monetaryPolicyCoolsAndWarmsTheEconomy() {
        assertEquals(0.95, MonetaryPolicy.adjust(1.0, 1_500.0, 1_000.0), 1e-9)
        assertEquals(1.05, MonetaryPolicy.adjust(1.0, 500.0, 1_000.0), 1e-9)
        assertEquals(1.0, MonetaryPolicy.adjust(1.0, 1_000.0, 1_000.0))
        assertEquals(MonetaryPolicy.MIN, MonetaryPolicy.adjust(0.5, 5_000.0, 1_000.0))
        assertEquals(MonetaryPolicy.MAX, MonetaryPolicy.adjust(1.5, 10.0, 1_000.0))
        assertEquals(75L, MonetaryPolicy.reward(100, 0.75))
    }
}

class ExchangeTest {
    private fun market(): Exchange {
        val ex = Exchange()
        ex.ledger.record(LedgerEntry("s1", "seller", 1_000, Reason.MISSION, T0))
        ex.ledger.record(LedgerEntry("s2", "buyer", 1_000, Reason.MISSION, T0))
        ex.grant("seller", "slime_helmet", 3)
        return ex
    }

    private fun <T> MarketResult<T>.value(): T = (this as MarketResult.Ok).value
    private fun MarketResult<*>.error(): MarketError = (this as MarketResult.Rejected).reason

    private val fair = ItemCatalog.byId("slime_helmet")!!.baseValue

    @Test
    fun listingEscrowsTheItemAndChargesAFee() {
        val ex = market()
        val l = ex.list("seller", 10, "slime_helmet", 2, fair, T0).value()
        assertEquals(1, ex.inventory("seller").count("slime_helmet"))
        assertEquals(1_000 - maxOf(1, fair * 2 * 2 / 100), ex.ledger.balance("seller"))
        assertEquals(listOf(l), ex.activeListings())
    }

    @Test
    fun aSaleMovesItemAndCoinsAndTaxesTheSeller() {
        val ex = market()
        val l = ex.list("seller", 10, "slime_helmet", 1, fair, T0).value()
        val before = ex.ledger.balance("seller")
        assertEquals(fair, ex.buy("buyer", 10, l.id, 1, T0 + 1_000).value())
        assertEquals(1, ex.inventory("buyer").count("slime_helmet"))
        assertEquals(1_000 - fair, ex.ledger.balance("buyer"))
        assertEquals(before + fair - fair * 5 / 100, ex.ledger.balance("seller"))
        assertTrue(ex.activeListings().isEmpty())
        // Coins vanished from the economy: that is the point of fees and taxes.
        assertTrue(ex.ledger.totalSupply() < 2_000)
    }

    @Test
    fun partialPurchasesLeaveTheRest() {
        val ex = market()
        val l = ex.list("seller", 10, "slime_helmet", 3, fair, T0).value()
        ex.buy("buyer", 10, l.id, 2, T0 + 1)
        assertEquals(1, ex.activeListings().single().quantity)
        assertEquals(MarketError.QUANTITY_UNAVAILABLE, ex.buy("buyer", 10, l.id, 5, T0 + 2).error())
    }

    @Test
    fun priceBandBlocksDumpingAndGouging() {
        val ex = market()
        assertEquals(MarketError.PRICE_OUT_OF_BAND, ex.list("seller", 10, "slime_helmet", 1, 1, T0).error())
        assertEquals(MarketError.PRICE_OUT_OF_BAND, ex.list("seller", 10, "slime_helmet", 1, fair * 10, T0).error())
        assertTrue(ex.list("seller", 10, "slime_helmet", 1, fair * 3, T0) is MarketResult.Ok)
    }

    @Test
    fun fairPriceTracksSalesButOneSaleBarelyMovesIt() {
        val ex = market()
        val start = ex.fairPrice("slime_helmet")
        val l = ex.list("seller", 10, "slime_helmet", 1, start * 3, T0).value()
        ex.buy("buyer", 10, l.id, 1, T0 + 1)
        val after = ex.fairPrice("slime_helmet")
        assertTrue(after > start && after < start * 1.5, "start=$start after=$after")
        assertEquals(1, ex.tradedVolume("slime_helmet"))
    }

    @Test
    fun washTradingDoesNotMovePrices() {
        val ex = Exchange()
        ex.ledger.record(LedgerEntry("a", "x", 100_000, Reason.MISSION, T0))
        ex.ledger.record(LedgerEntry("b", "y", 100_000, Reason.MISSION, T0))
        ex.grant("x", "slime_helmet", 20)
        val start = ex.fairPrice("slime_helmet")
        repeat(10) { i ->
            val l = ex.list("x", 10, "slime_helmet", 1, start * 3, T0 + i).value()
            ex.buy("y", 10, l.id, 1, T0 + i + 1)
            ex.grant("x", "slime_helmet", 0.coerceAtLeast(0) + 1)
        }
        // Only the first three trades between the same pair count toward the price.
        val bound = (start * 1.0 + 3 * 0.2 * (start * 3 - start)).toLong() + 2
        assertTrue(ex.fairPrice("slime_helmet") <= bound, "fair ${ex.fairPrice("slime_helmet")} bound $bound")
    }

    @Test
    fun rulesAreEnforced() {
        val ex = market()
        assertEquals(MarketError.NOT_TRADABLE, ex.list("seller", 10, "starter_shirt", 1, 10, T0).error())
        assertEquals(MarketError.UNKNOWN_ITEM, ex.list("seller", 10, "nope", 1, 10, T0).error())
        assertEquals(MarketError.LEVEL_TOO_LOW, ex.list("seller", 2, "slime_helmet", 1, fair, T0).error())
        assertEquals(MarketError.BAD_QUANTITY, ex.list("seller", 10, "slime_helmet", 0, fair, T0).error())
        assertEquals(MarketError.NOT_ENOUGH_ITEMS, ex.list("seller", 10, "slime_helmet", 9, fair, T0).error())
        val l = ex.list("seller", 10, "slime_helmet", 1, fair, T0).value()
        assertEquals(MarketError.OWN_LISTING, ex.buy("seller", 10, l.id, 1, T0 + 1).error())
        assertEquals(MarketError.LEVEL_TOO_LOW, ex.buy("buyer", 1, l.id, 1, T0 + 1).error())
        assertEquals(MarketError.LISTING_NOT_FOUND, ex.buy("buyer", 10, "ghost", 1, T0 + 1).error())
        assertEquals(MarketError.EXPIRED, ex.buy("buyer", 10, l.id, 1, T0 + 10 * DAY).error())
        val broke = Exchange().also { it.grant("s", "slime_helmet"); it.ledger.record(LedgerEntry("f", "s", 100, Reason.MISSION, T0)) }
        val bl = broke.list("s", 10, "slime_helmet", 1, fair, T0).value()
        assertEquals(MarketError.NOT_ENOUGH_COINS, broke.buy("poor", 10, bl.id, 1, T0 + 1).error())
        assertEquals(MarketError.NOT_YOURS, ex.cancel("buyer", l.id).error())
    }

    @Test
    fun cancellingOrExpiringReturnsTheItems() {
        val ex = market()
        val l = ex.list("seller", 10, "slime_helmet", 2, fair, T0).value()
        assertEquals(1, ex.inventory("seller").count("slime_helmet"))
        ex.cancel("seller", l.id)
        assertEquals(3, ex.inventory("seller").count("slime_helmet"))
        ex.list("seller", 10, "slime_helmet", 3, fair, T0)
        assertEquals(0, ex.inventory("seller").count("slime_helmet"))
        assertEquals(1, ex.sweepExpired(T0 + 5 * DAY))
        assertEquals(3, ex.inventory("seller").count("slime_helmet"))
        assertTrue(ex.activeListings().isEmpty())
    }

    @Test
    fun aSellerCannotHaveTooManyListings() {
        val ex = market()
        ex.grant("seller", "slime_helmet", 50)
        repeat(Exchange.MAX_LISTINGS) { assertTrue(ex.list("seller", 10, "slime_helmet", 1, fair, T0 + it) is MarketResult.Ok) }
        assertEquals(MarketError.TOO_MANY_LISTINGS, ex.list("seller", 10, "slime_helmet", 1, fair, T0 + 99).error())
    }

    @Test
    fun directTradesSwapItemsAndCoinsAtomically() {
        val ex = market()
        ex.grant("buyer", "stone_gloves", 1)
        val offer = Exchange.TradeOffer("seller", "buyer", mapOf("slime_helmet" to 1), 0)
        val counter = Exchange.TradeOffer("buyer", "seller", mapOf("stone_gloves" to 1), 100)
        assertTrue(ex.trade(offer, 10, 10, T0, counter) is MarketResult.Ok)
        assertEquals(1, ex.inventory("buyer").count("slime_helmet"))
        assertEquals(1, ex.inventory("seller").count("stone_gloves"))
        assertEquals(1_000 + 100 - 3, ex.ledger.balance("seller")) // 3% tax on the coin part
        assertEquals(900, ex.ledger.balance("buyer"))
    }

    @Test
    fun aFailedTradeChangesNothing() {
        val ex = market()
        val bad = Exchange.TradeOffer("seller", "buyer", mapOf("slime_helmet" to 1), 0)
        val impossible = Exchange.TradeOffer("buyer", "seller", mapOf("stone_gloves" to 1), 0) // buyer has none
        assertEquals(MarketError.NOT_ENOUGH_ITEMS, ex.trade(bad, 10, 10, T0, impossible).error())
        assertEquals(3, ex.inventory("seller").count("slime_helmet"))
        assertEquals(0, ex.inventory("buyer").count("slime_helmet"))
        assertEquals(1_000, ex.ledger.balance("seller"))
        assertEquals(MarketError.NOT_TRADABLE, ex.trade(Exchange.TradeOffer("seller", "buyer", mapOf("starter_shirt" to 1), 0), 10, 10, T0).error())
        assertEquals(MarketError.NOT_ENOUGH_COINS, ex.trade(Exchange.TradeOffer("seller", "buyer", emptyMap(), 5_000), 10, 10, T0).error())
        assertEquals(MarketError.SAME_USER, ex.trade(Exchange.TradeOffer("seller", "seller", emptyMap(), 0), 10, 10, T0).error())
        assertEquals(MarketError.LEVEL_TOO_LOW, ex.trade(bad, 2, 10, T0).error())
    }
}

class MissionsTest {
    private val date = LocalDate.of(2026, 10, 21)
    private val ctx = MissionContext(level = 10, age = 28, experience = Experience.INTERMEDIATE, equipment = setOf(Equipment.BODYWEIGHT, Equipment.DUMBBELLS))

    @Test
    fun generationIsDeterministicPerUserAndDay() {
        val a = DailyMissions.generate("ana", date, ctx)
        assertEquals(a, DailyMissions.generate("ana", date, ctx))
        assertTrue(a != DailyMissions.generate("ana", date.plusDays(1), ctx))
        assertTrue(a != DailyMissions.generate("beto", date, ctx) || a.map { it.title } != DailyMissions.generate("zoe", date, ctx).map { it.title })
    }

    @Test
    fun aDayHasOneFitnessOneCardioOneWellnessAndABonus() {
        val m = DailyMissions.generate("ana", date, ctx)
        assertEquals(4, m.size)
        assertEquals(setOf(MissionCategory.FITNESS, MissionCategory.CARDIO, MissionCategory.WELLNESS), m.take(3).map { it.category }.toSet())
        assertEquals(m.size, m.map { it.id }.toSet().size)
        assertTrue(m[3].xp > m[0].xp)
    }

    @Test
    fun missionsRespectEquipmentAndAge() {
        val bodyweightOnly = ctx.copy(equipment = setOf(Equipment.BODYWEIGHT))
        val senior = ctx.copy(age = 70)
        for (i in 0 until 60) {
            val d = date.plusDays(i.toLong())
            val bw = DailyMissions.generate("u$i", d, bodyweightOnly)
            assertTrue(bw.none { it.exercise == ExerciseType.BICEP_CURL || it.exercise == ExerciseType.SHOULDER_PRESS || it.exercise == ExerciseType.PULL_UP })
            val old = DailyMissions.generate("u$i", d, senior)
            assertTrue(old.none { it.exercise in setOf(ExerciseType.BURPEE, ExerciseType.JUMP_ROPE, ExerciseType.JUMPING_JACK, ExerciseType.PULL_UP) })
        }
    }

    @Test
    fun newPlayersDoNotGetLateGameMissions() {
        val rookie = ctx.copy(level = 1)
        for (i in 0 until 60) {
            val m = DailyMissions.generate("u$i", date.plusDays(i.toLong()), rookie)
            assertTrue(m.none { it.kind in setOf(MissionKind.DUELS_PLAYED, MissionKind.TERRITORY_CELLS, MissionKind.FLAWLESS_BOSS) })
            assertTrue(m.none { it.exercise in setOf(ExerciseType.BURPEE, ExerciseType.PULL_UP, ExerciseType.JUMP_ROPE) })
        }
    }

    @Test
    fun targetsGrowWithLevelAndExperience() {
        fun repsTarget(c: MissionContext): Int {
            for (i in 0 until 200) {
                DailyMissions.generate("u$i", date.plusDays(i.toLong()), c).firstOrNull { it.kind == MissionKind.REPS_ANY }?.let { return it.target }
            }
            error("no reps mission found")
        }
        assertTrue(repsTarget(ctx.copy(level = 40)) > repsTarget(ctx.copy(level = 5)))
        assertTrue(repsTarget(ctx.copy(experience = Experience.ADVANCED)) > repsTarget(ctx.copy(experience = Experience.BEGINNER)))
        assertTrue(repsTarget(ctx.copy(age = 70)) < repsTarget(ctx.copy(age = 28)))
    }

    private fun board() = MissionBoard.daily("ana", date, ctx)

    @Test
    fun eventsAdvanceMatchingMissionsOnly() {
        var b = board()
        val reps = b.missions.first { it.mission.kind == MissionKind.REPS_EXERCISE || it.mission.kind == MissionKind.REPS_ANY }
        val type = reps.mission.exercise ?: ExerciseType.SQUAT
        b = b.apply(GameEvent.Reps(type, 10, 0.9))
        assertEquals(10.0, b.missions.first { it.mission.id == reps.mission.id }.progress)
        // A different exercise doesn't advance an exercise-specific mission.
        val other = if (type == ExerciseType.SQUAT) ExerciseType.PUSH_UP else ExerciseType.SQUAT
        val b2 = board().apply(GameEvent.Reps(other, 10, 0.9))
        if (reps.mission.exercise != null) assertEquals(0.0, b2.missions.first { it.mission.id == reps.mission.id }.progress)
    }

    @Test
    fun sloppyRepsDoNotCount() {
        val b = board().apply(GameEvent.Reps(ExerciseType.SQUAT, 100, 0.2))
        assertTrue(b.missions.none { it.progress > 0 })
    }

    @Test
    fun completingAndClaimingPaysOnce() {
        var b = board()
        val first = b.missions.first()
        repeat(200) { b = b.apply(allEventsFor(first.mission)) }
        assertTrue(b.missions.first().complete)
        val (b2, reward) = b.claim(first.mission.id)
        assertNotNull(reward)
        assertEquals(first.mission.xp, reward.xp)
        assertNull(b2.claim(first.mission.id).second)
        assertNull(board().claim(first.mission.id).second) // not complete yet
        assertNull(b2.claim("nope").second)
    }

    private fun allEventsFor(m: app.gymquest.core.missions.Mission): GameEvent = when (m.kind) {
        MissionKind.REPS_ANY -> GameEvent.Reps(ExerciseType.SQUAT, 10, 0.9)
        MissionKind.REPS_EXERCISE -> GameEvent.Reps(m.exercise!!, 10, 0.9)
        MissionKind.DISTANCE_KM -> GameEvent.Distance(ActivityType.WALK, 2_000.0)
        MissionKind.ACTIVE_MINUTES -> GameEvent.ActiveMinutes(30)
        MissionKind.WATER_GLASSES -> GameEvent.Water(2)
        MissionKind.SKINCARE_ROUTINE -> GameEvent.SkincareDone
        MissionKind.MEALS_LOGGED -> GameEvent.MealLogged
        MissionKind.BOSS_WINS, MissionKind.FLAWLESS_BOSS -> GameEvent.BossWin("slime_squat", true)
        MissionKind.TERRITORY_CELLS -> GameEvent.CellsCaptured(20)
        MissionKind.DUELS_PLAYED -> GameEvent.DuelPlayed
        MissionKind.WORKOUTS -> GameEvent.WorkoutFinished
        MissionKind.SLEEP_LOGGED -> GameEvent.SleepLogged
        MissionKind.WEIGH_IN -> GameEvent.WeighedIn
    }

    @Test
    fun finishingEverythingUnlocksTheChestOnce() {
        var b = board()
        repeat(300) { for (m in b.missions.map { it.mission }) b = b.apply(allEventsFor(m)) }
        assertTrue(b.allComplete)
        assertNull(b.claimBonus().second) // must claim each mission first
        for (m in b.missions.map { it.mission.id }) b = b.claim(m).first
        val (after, chest) = b.claimBonus()
        assertNotNull(chest)
        assertTrue(chest.coins > 0 && chest.xp > 0)
        assertNull(after.claimBonus().second)
    }

    @Test
    fun streakBoostsCoinsButIsCapped() {
        assertEquals(1.0, MissionStreak.multiplier(0))
        assertEquals(1.3, MissionStreak.multiplier(3), 1e-9)
        assertEquals(1.5, MissionStreak.multiplier(30), 1e-9)
        var b = board()
        val m = b.missions.first().mission
        repeat(200) { b = b.apply(allEventsFor(m)) }
        val plain = b.claim(m.id).second!!.coins
        val boosted = b.claim(m.id, 1.5).second!!.coins
        assertTrue(boosted > plain)
    }

    @Test
    fun weeklyMissionsHaveBiggerRewardsAndFixedTargets() {
        val week = MissionBoard.weekly(date, ctx)
        assertEquals(4, week.missions.size)
        val daily = board()
        assertTrue(week.missions.first().mission.xp > daily.missions.first().mission.xp * 2)
        assertEquals(3, week.missions.first { it.mission.kind == MissionKind.BOSS_WINS }.mission.target)
        assertEquals(DailyMissions.weekKey(date), DailyMissions.weekKey(date.plusDays(2)))
        assertTrue(DailyMissions.weekKey(date) != DailyMissions.weekKey(date.plusDays(14)))
        assertTrue(MissionBoard.weekly(date, ctx.copy(level = 1)).missions.any { it.mission.kind == MissionKind.WATER_GLASSES })
    }

    @Test
    fun distanceMissionsDiscountBikes() {
        var walkBoard = board()
        val dist = walkBoard.missions.firstOrNull { it.mission.kind == MissionKind.DISTANCE_KM } ?: return
        walkBoard = walkBoard.apply(GameEvent.Distance(ActivityType.WALK, 1_000.0))
        val ride = board().apply(GameEvent.Distance(ActivityType.RIDE, 1_000.0))
        val w = walkBoard.missions.first { it.mission.id == dist.mission.id }.progress
        val r = ride.missions.first { it.mission.id == dist.mission.id }.progress
        assertEquals(1.0, w, 1e-9)
        assertEquals(0.4, r, 1e-9)
    }
}
