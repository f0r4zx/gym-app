package app.gymquest.core.territory

import app.gymquest.core.tracking.ActivityType
import app.gymquest.core.tracking.Geo
import app.gymquest.core.tracking.GeoPoint
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val HOME = GeoPoint(19.4326, -99.1332)
private const val DAY = 86_400_000L
private const val T0 = 1_800_000_000_000L

/** A closed square track of the given side length, walked clockwise from [start]. */
private fun square(start: GeoPoint, sideM: Double, stepM: Double = 20.0): List<GeoPoint> {
    val out = mutableListOf(start)
    var cur = start
    for (bearing in listOf(90.0, 180.0, 270.0, 0.0)) {
        var done = 0.0
        while (done < sideM - 1e-6) {
            val s = minOf(stepM, sideM - done)
            cur = Geo.destination(cur, bearing, s)
            out += cur
            done += s
        }
    }
    return out
}

private fun loopOf(start: GeoPoint, side: Double) = LoopDetector.find(square(start, side)).single()

class GridTest {
    @Test
    fun cellsAreStableAndAboutOneHundredMetres() {
        val c = TerritoryGrid.cellOf(HOME)
        assertEquals(c, TerritoryGrid.cellOf(Geo.destination(HOME, 45.0, 2.0)))
        val east = TerritoryGrid.cellOf(Geo.destination(HOME, 90.0, 100.0))
        val far = TerritoryGrid.cellOf(Geo.destination(HOME, 90.0, 1000.0))
        assertTrue(east.lonIndex - c.lonIndex in 0..2)
        assertTrue(far.lonIndex - c.lonIndex in 8..11)
    }

    @Test
    fun cellCentreMapsBackToTheSameCell() {
        for (p in listOf(HOME, GeoPoint(60.17, 24.94), GeoPoint(-33.45, -70.66), GeoPoint(35.68, 139.69))) {
            val c = TerritoryGrid.cellOf(p)
            assertEquals(c, TerritoryGrid.cellOf(TerritoryGrid.center(c)))
        }
    }

    @Test
    fun cellsStayRoughlySquareAtHighLatitudes() {
        val p = GeoPoint(64.0, 20.0)
        val c = TerritoryGrid.cellOf(p)
        val centre = TerritoryGrid.center(c)
        val next = TerritoryGrid.center(CellId(c.latIndex, c.lonIndex + 1))
        assertEquals(100.0, Geo.distance(centre, next), 8.0)
    }

    @Test
    fun squareKilometreHoldsAboutAHundredCells() {
        val cells = TerritoryGrid.cellsInside(square(HOME, 1000.0))
        assertTrue(cells.size in 90..110, "cells ${cells.size}")
        assertEquals(cells.size, cells.toSet().size)
    }

    @Test
    fun degeneratePolygonsHaveNoCells() {
        assertTrue(TerritoryGrid.cellsInside(listOf(HOME)).isEmpty())
        assertTrue(TerritoryGrid.cellsInside(listOf(HOME, HOME, HOME)).isEmpty())
    }
}

class LoopDetectorTest {
    @Test
    fun detectsASimpleBlock() {
        val loops = LoopDetector.find(square(HOME, 400.0))
        assertEquals(1, loops.size)
        assertEquals(1600.0, loops[0].perimeterM, 40.0)
        assertEquals(160_000.0, loops[0].areaM2, 8_000.0)
        assertTrue(loops[0].compactness in 0.7..0.85)
    }

    @Test
    fun outAndBackIsNotALoop() {
        val there = mutableListOf(HOME)
        var cur = HOME
        repeat(100) { cur = Geo.destination(cur, 90.0, 20.0); there += cur }
        val track = there + there.reversed()
        assertTrue(LoopDetector.find(track).isEmpty())
    }

    @Test
    fun anOpenCurveIsNotALoop() {
        val track = square(HOME, 400.0).dropLast(35) // stops far from the start
        assertTrue(LoopDetector.find(track).isEmpty())
    }

    @Test
    fun tinyLoopsAreIgnored() {
        assertTrue(LoopDetector.find(square(HOME, 50.0, stepM = 5.0)).isEmpty())
    }

    @Test
    fun lapsAreSeparateLoops() {
        val one = square(HOME, 300.0)
        val laps = one + one + one
        assertEquals(3, LoopDetector.find(laps).size)
    }

    @Test
    fun figureEightGivesTwoLoops() {
        val a = square(HOME, 300.0)
        val east = Geo.destination(HOME, 90.0, 300.0)
        // Second square shares the corner at HOME+300m east, walked the other way round.
        val b = square(east, 300.0).let { sq -> listOf(sq.first()) + sq.drop(1).reversed().let { it } }
        val loops = LoopDetector.find(a + b)
        assertTrue(loops.size >= 2, "found ${loops.size}")
    }

    @Test
    fun almostClosedLoopsStillCloseWithinTolerance() {
        val sq = square(HOME, 400.0)
        val almost = sq.dropLast(2) // stops ~40 m short of the start
        assertEquals(1, LoopDetector.find(almost).size)
    }

    @Test
    fun polygonAreaOfAKnownSquare() {
        assertEquals(1_000_000.0, LoopDetector.area(square(HOME, 1000.0)), 10_000.0)
    }
}

class TerritoryMapTest {
    private val ana = Owner("ana")
    private val beto = Owner("beto")

    private fun claim(owner: Owner, side: Double, at: GeoPoint = HOME, t: Long = T0, type: ActivityType = ActivityType.WALK) =
        Claim(owner, loopOf(at, side), type, t)

    @Test
    fun firstLoopCapturesEmptyLand() {
        val map = TerritoryMap()
        val r = map.apply(claim(ana, 500.0))
        assertTrue(r.captured in 20..30, "captured ${r.captured}")
        assertEquals(0, r.stolenTotal)
        assertEquals(r.captured, map.ownedBy("ana"))
        assertTrue(r.xp > 0)
    }

    @Test
    fun aBiggerLoopStealsFromASmallerOne() {
        val map = TerritoryMap()
        val small = map.apply(claim(ana, 300.0, t = T0))
        val big = map.apply(claim(beto, 1000.0, at = Geo.destination(HOME, 225.0, 300.0), t = T0 + 7 * 3_600_000L))
        assertTrue(big.stolen["ana"]!! > 0)
        assertTrue(big.xp > big.captured * 2)
        assertEquals(small.captured - big.stolenTotal, map.ownedBy("ana"))
    }

    @Test
    fun aSmallerLoopCannotTakeFromABiggerOne() {
        val map = TerritoryMap()
        val big = map.apply(claim(ana, 1000.0, t = T0))
        val small = map.apply(claim(beto, 300.0, at = Geo.destination(HOME, 135.0, 300.0), t = T0 + 7 * 3_600_000L))
        assertEquals(0, small.stolenTotal)
        assertTrue(small.defended > 0)
        assertEquals(big.captured, map.ownedBy("ana"))
    }

    @Test
    fun freshConquestsAreProtected() {
        val map = TerritoryMap()
        map.apply(claim(ana, 300.0, t = T0))
        val attack = map.apply(claim(beto, 1500.0, at = Geo.destination(HOME, 315.0, 300.0), t = T0 + 3_600_000L)) // 1 hour later
        assertEquals(0, attack.stolenTotal)
        val later = map.apply(claim(beto, 1500.0, at = Geo.destination(HOME, 315.0, 300.0), t = T0 + 7 * 3_600_000L))
        assertTrue(later.stolenTotal > 0)
    }

    @Test
    fun landFadesSoSmallLoopsCanRetakeIt() {
        val map = TerritoryMap(halfLifeDays = 14.0)
        map.apply(claim(ana, 1000.0, t = T0))
        val weakAttack = map.apply(claim(beto, 400.0, at = Geo.destination(HOME, 135.0, 200.0), t = T0 + 2 * DAY))
        assertEquals(0, weakAttack.stolenTotal)
        // After ~4 half-lives ana's 4 km of power has dropped below beto's 1.6 km.
        val retake = map.apply(claim(beto, 400.0, at = Geo.destination(HOME, 135.0, 200.0), t = T0 + 60 * DAY))
        assertTrue(retake.stolenTotal > 0, "stole ${retake.stolenTotal}")
    }

    @Test
    fun walkingYourOwnLandRenewsIt() {
        val map = TerritoryMap(halfLifeDays = 14.0)
        map.apply(claim(ana, 600.0, t = T0))
        val again = map.apply(claim(ana, 600.0, t = T0 + 20 * DAY))
        assertTrue(again.refreshed > 0 && again.captured == 0)
        // Renewed, so a modest rival at day 21 still cannot take it.
        val rival = map.apply(claim(beto, 500.0, at = Geo.destination(HOME, 135.0, 100.0), t = T0 + 21 * DAY))
        assertEquals(0, rival.stolenTotal)
    }

    @Test
    fun bikesNeedMoreDistanceThanWalkers() {
        val walk = TerritoryMap.power(loopOf(HOME, 500.0), ActivityType.WALK)
        val ride = TerritoryMap.power(loopOf(HOME, 500.0), ActivityType.RIDE)
        val hike = TerritoryMap.power(loopOf(HOME, 500.0), ActivityType.HIKE)
        assertTrue(ride < walk && walk < hike)
        val map = TerritoryMap()
        map.apply(claim(ana, 600.0, t = T0, type = ActivityType.WALK))
        val byBike = map.apply(claim(beto, 700.0, at = Geo.destination(HOME, 135.0, 100.0), t = T0 + 8 * 3_600_000L, type = ActivityType.RIDE))
        assertEquals(0, byBike.stolenTotal) // 0.4 x 2.8 km < 1 x 2.4 km
    }

    @Test
    fun guildmatesDoNotStealFromEachOther() {
        val map = TerritoryMap()
        val a = Owner("ana", "g1")
        val b = Owner("beto", "g1")
        map.apply(claim(a, 400.0, t = T0))
        val r = map.apply(claim(b, 1500.0, at = Geo.destination(HOME, 225.0, 300.0), t = T0 + 8 * 3_600_000L))
        assertEquals(0, r.stolenTotal)
        assertTrue(map.ownedByGuild("g1") >= map.ownedBy("beto") + map.ownedBy("ana"))
        val enemy = map.apply(claim(Owner("carla", "g2"), 2500.0, at = Geo.destination(HOME, 225.0, 800.0), t = T0 + 16 * 3_600_000L))
        assertTrue(enemy.stolenTotal > 0)
    }

    @Test
    fun pruneRemovesDeadLand() {
        val map = TerritoryMap(halfLifeDays = 7.0)
        map.apply(claim(ana, 500.0, t = T0))
        assertEquals(0, map.prune(T0 + DAY))
        val removed = map.prune(T0 + 120 * DAY)
        assertTrue(removed > 0)
        assertEquals(0, map.size)
    }

    @Test
    fun warSpoilsTransferTheWeakestCellsFirst() {
        val map = TerritoryMap()
        map.apply(claim(ana, 500.0, t = T0))
        val before = map.ownedBy("ana")
        val moved = map.transfer("ana", Owner("beto", "g2"), 5, T0 + DAY)
        assertEquals(5, moved)
        assertEquals(before - 5, map.ownedBy("ana"))
        assertEquals(5, map.ownedByGuild("g2"))
    }

    @Test
    fun areaAndIncomeHelpers() {
        assertEquals(1.0, TerritoryMap.areaKm2(100), 1e-9)
        assertEquals(80, TerritoryMap.dailyCoins(100))
        assertEquals(400, TerritoryMap.dailyCoins(1_000_000))
        assertTrue(TerritoryMap.dailyCoins(400) < 4 * TerritoryMap.dailyCoins(100))
    }

    @Test
    fun rewardsScaleWithWhatYouTake() {
        val map = TerritoryMap()
        val first = map.apply(claim(ana, 400.0, t = T0))
        val steal = map.apply(claim(beto, 2000.0, at = Geo.destination(HOME, 225.0, 200.0), t = T0 + 8 * 3_600_000L))
        assertTrue(steal.coins > first.coins)
    }
}

class ClaimValidatorTest {
    @Test
    fun peopleOnFootAreFineButCarsAreNot() {
        assertEquals(ClaimValidator.Verdict.OK, ClaimValidator.check(ActivityType.RUN, 3.0, 1_800_000, 0))
        assertEquals(ClaimValidator.Verdict.TOO_FAST, ClaimValidator.check(ActivityType.WALK, 12.0, 1_800_000, 0))
        assertEquals(ClaimValidator.Verdict.TOO_FAST, ClaimValidator.check(ActivityType.RUN, 9.0, 1_800_000, 0))
        assertEquals(ClaimValidator.Verdict.OK, ClaimValidator.check(ActivityType.RIDE, 7.0, 1_800_000, 0))
        assertEquals(ClaimValidator.Verdict.TOO_FAST, ClaimValidator.check(ActivityType.RIDE, 22.0, 1_800_000, 0))
    }

    @Test
    fun shortActivitiesAndDailyLimit() {
        assertEquals(ClaimValidator.Verdict.TOO_SHORT, ClaimValidator.check(ActivityType.WALK, 1.3, 60_000, 0))
        assertEquals(ClaimValidator.Verdict.DAILY_LIMIT, ClaimValidator.check(ActivityType.WALK, 1.3, 1_800_000, 5))
        assertTrue(abs(ClaimValidator.MAX_CLAIMS_PER_DAY - 5) == 0)
    }
}
