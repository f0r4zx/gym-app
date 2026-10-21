package app.gymquest.core.territory

import app.gymquest.core.tracking.ActivityType
import app.gymquest.core.tracking.Geo
import app.gymquest.core.tracking.GeoPoint
import app.gymquest.core.tracking.Polyline
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/** One square (~100 m) of the world map. Cell ids are global and stable. */
data class CellId(val latIndex: Int, val lonIndex: Int)

object TerritoryGrid {
    const val CELL_M = 100.0
    private const val M_PER_DEG_LAT = 111_320.0
    private const val LAT_STEP = CELL_M / M_PER_DEG_LAT

    private fun lonStep(latIndex: Int): Double {
        val bandLat = (latIndex + 0.5) * LAT_STEP
        return CELL_M / (M_PER_DEG_LAT * max(cos(Math.toRadians(bandLat)), 0.01))
    }

    fun cellOf(p: GeoPoint): CellId {
        val li = floor(p.lat / LAT_STEP).toInt()
        return CellId(li, floor(p.lon / lonStep(li)).toInt())
    }

    fun center(c: CellId): GeoPoint {
        val lat = (c.latIndex + 0.5) * LAT_STEP
        val lon = ((c.lonIndex + 0.5) * lonStep(c.latIndex)).coerceIn(-180.0, 180.0)
        return GeoPoint(lat.coerceIn(-90.0, 90.0), lon)
    }

    /** Cells whose centre lies inside [polygon] (even-odd rule). */
    fun cellsInside(polygon: List<GeoPoint>): List<CellId> {
        if (polygon.size < 3) return emptyList()
        val minLat = polygon.minOf { it.lat }
        val maxLat = polygon.maxOf { it.lat }
        val out = mutableListOf<CellId>()
        for (li in floor(minLat / LAT_STEP).toInt()..floor(maxLat / LAT_STEP).toInt()) {
            val step = lonStep(li)
            val lat = (li + 0.5) * LAT_STEP
            val minLon = polygon.minOf { it.lon }
            val maxLon = polygon.maxOf { it.lon }
            for (lo in floor(minLon / step).toInt()..floor(maxLon / step).toInt()) {
                val lon = (lo + 0.5) * step
                if (contains(polygon, lat, lon)) out += CellId(li, lo)
            }
        }
        return out
    }

    private fun contains(polygon: List<GeoPoint>, lat: Double, lon: Double): Boolean {
        var inside = false
        var j = polygon.lastIndex
        for (i in polygon.indices) {
            val pi = polygon[i]
            val pj = polygon[j]
            if ((pi.lat > lat) != (pj.lat > lat) &&
                lon < (pj.lon - pi.lon) * (lat - pi.lat) / (pj.lat - pi.lat) + pi.lon
            ) inside = !inside
            j = i
        }
        return inside
    }
}

/** A closed circuit found inside a recorded track. */
data class Loop(
    val polygon: List<GeoPoint>,
    val perimeterM: Double,
    val areaM2: Double,
) {
    /** 1.0 for a circle, near 0 for a thin out-and-back line. */
    val compactness: Double get() = 4 * PI * areaM2 / (perimeterM * perimeterM)
}

data class LoopRules(
    val minPerimeterM: Double = 400.0,
    val minAreaM2: Double = 15_000.0,
    val maxAreaM2: Double = 50_000_000.0,
    val minCompactness: Double = 0.03,
    /** How close the end must come back to a previous point to close the loop. */
    val closeGapM: Double = 150.0,
    val closeGapFraction: Double = 0.08,
)

object LoopDetector {
    /**
     * Finds every closed circuit in [track]: places where you came back to an earlier point after
     * covering a real distance. A figure-eight gives two loops; running the same block twice gives two.
     */
    fun find(track: List<GeoPoint>, rules: LoopRules = LoopRules()): List<Loop> {
        val pts = Polyline.simplify(track, 8.0)
        if (pts.size < 4) return emptyList()
        val cum = DoubleArray(pts.size)
        for (i in 1 until pts.size) cum[i] = cum[i - 1] + Geo.distance(pts[i - 1], pts[i])

        val loops = mutableListOf<Loop>()
        var start = 0
        var j = 1
        while (j < pts.size) {
            var closedAt = -1
            for (i in start until j) {
                val perimeter = cum[j] - cum[i]
                if (perimeter < rules.minPerimeterM) break // earlier i only give longer perimeters, later ones shorter
                val gap = min(rules.closeGapM, max(40.0, perimeter * rules.closeGapFraction))
                if (Geo.distance(pts[i], pts[j]) <= gap) {
                    closedAt = i
                    break
                }
            }
            if (closedAt >= 0) {
                val poly = pts.subList(closedAt, j + 1)
                val loop = build(poly)
                if (loop != null && valid(loop, rules)) {
                    loops += loop
                    start = j
                }
            }
            j++
        }
        return loops
    }

    private fun build(poly: List<GeoPoint>): Loop? {
        if (poly.size < 4) return null
        val perimeter = Geo.length(poly) + Geo.distance(poly.last(), poly.first())
        return Loop(poly, perimeter, area(poly))
    }

    private fun valid(l: Loop, r: LoopRules) =
        l.perimeterM >= r.minPerimeterM && l.areaM2 in r.minAreaM2..r.maxAreaM2 && l.compactness >= r.minCompactness

    /** Polygon area in m² (shoelace on a local projection). */
    fun area(poly: List<GeoPoint>): Double {
        val origin = poly.first()
        val xy = poly.map { Geo.toLocalMeters(it, origin) }
        var sum = 0.0
        for (i in xy.indices) {
            val (x1, y1) = xy[i]
            val (x2, y2) = xy[(i + 1) % xy.size]
            sum += x1 * y2 - x2 * y1
        }
        return kotlin.math.abs(sum) / 2.0
    }
}

data class Owner(val userId: String, val guildId: String? = null)

data class CellState(val owner: Owner, val power: Double, val claimedAtMs: Long)

data class Claim(val owner: Owner, val loop: Loop, val type: ActivityType, val timeMs: Long)

data class ClaimResult(
    /** Cells nobody owned. */
    val captured: Int,
    /** Cells taken from other players, by previous owner id. */
    val stolen: Map<String, Int>,
    val refreshed: Int,
    /** Cells that held because the owner's power was higher or the cell was protected. */
    val defended: Int,
    val xp: Int,
    val coins: Int,
) {
    val stolenTotal: Int get() = stolen.values.sum()
    val gained: Int get() = captured + stolenTotal
}

/**
 * The conquest rules. A loop claims every cell inside it with a *power* equal to the distance you
 * walked (bikes count less, so they cannot dominate). A cell changes hands only if the new power
 * beats the owner's *current* power, which fades with time, so abandoned land can be retaken by
 * smaller loops. Fresh conquests are protected for a few hours to stop instant back-and-forth.
 */
class TerritoryMap(
    private val halfLifeDays: Double = 14.0,
    private val protectionMs: Long = 6 * 3_600_000L,
    private val friendlyGuilds: Boolean = true,
) {
    private val cells = HashMap<CellId, CellState>()

    val size: Int get() = cells.size

    fun stateOf(cell: CellId): CellState? = cells[cell]

    fun ownerOf(cell: CellId): Owner? = cells[cell]?.owner

    fun cellsOf(userId: String): List<CellId> = cells.filter { it.value.owner.userId == userId }.keys.toList()

    fun cellsOfGuild(guildId: String): List<CellId> = cells.filter { it.value.owner.guildId == guildId }.keys.toList()

    fun effectivePower(state: CellState, nowMs: Long): Double {
        val days = (nowMs - state.claimedAtMs).coerceAtLeast(0) / 86_400_000.0
        return state.power * 0.5.pow(days / halfLifeDays)
    }

    fun apply(claim: Claim): ClaimResult {
        val power = power(claim.loop, claim.type)
        var captured = 0
        var refreshed = 0
        var defended = 0
        val stolen = HashMap<String, Int>()

        for (cell in TerritoryGrid.cellsInside(claim.loop.polygon)) {
            val current = cells[cell]
            when {
                current == null -> {
                    cells[cell] = CellState(claim.owner, power, claim.timeMs)
                    captured++
                }
                current.owner.userId == claim.owner.userId -> {
                    // Walking your own land again renews it (keeps the stronger of old and new).
                    val keep = max(effectivePower(current, claim.timeMs), power)
                    cells[cell] = CellState(claim.owner, keep, claim.timeMs)
                    refreshed++
                }
                friendlyGuilds && claim.owner.guildId != null && current.owner.guildId == claim.owner.guildId -> {
                    defended++ // allies do not take each other's land
                }
                claim.timeMs - current.claimedAtMs < protectionMs -> defended++
                power > effectivePower(current, claim.timeMs) -> {
                    stolen.merge(current.owner.userId, 1, Int::plus)
                    cells[cell] = CellState(claim.owner, power, claim.timeMs)
                }
                else -> defended++
            }
        }
        val stolenTotal = stolen.values.sum()
        return ClaimResult(
            captured, stolen, refreshed, defended,
            xp = captured * 2 + stolenTotal * 5 + refreshed / 4,
            coins = captured / 2 + stolenTotal * 2,
        )
    }

    fun ownedBy(userId: String): Int = cells.count { it.value.owner.userId == userId }

    fun ownedByGuild(guildId: String): Int = cells.count { it.value.owner.guildId == guildId }

    /** Removes land whose power has faded to nothing, so the map does not grow forever. */
    fun prune(nowMs: Long, minPower: Double = 5.0): Int {
        val dead = cells.filter { effectivePower(it.value, nowMs) < minPower }.keys
        dead.forEach { cells.remove(it) }
        return dead.size
    }

    /** Hand over a number of [from]'s cells to [to] (guild-war spoils). Weakest land goes first. */
    fun transfer(from: String, to: Owner, count: Int, nowMs: Long): Int {
        val victims = cells.filter { it.value.owner.userId == from }.entries
            .sortedBy { effectivePower(it.value, nowMs) }.take(count)
        for ((cell, state) in victims) cells[cell] = state.copy(owner = to, claimedAtMs = nowMs)
        return victims.size
    }

    companion object {
        /** Distance you walked, scaled by how hard the activity is. */
        fun power(loop: Loop, type: ActivityType): Double = loop.perimeterM * when (type) {
            ActivityType.WALK, ActivityType.RUN -> 1.0
            ActivityType.HIKE -> 1.2
            ActivityType.RIDE -> 0.4
        }

        fun areaKm2(cellCount: Int): Double = cellCount * TerritoryGrid.CELL_M * TerritoryGrid.CELL_M / 1_000_000.0

        /** Passive income from land; grows slower than area so big empires do not snowball. */
        fun dailyCoins(cellCount: Int): Int = min(400, (8 * sqrt(cellCount.toDouble())).toInt())
    }
}

/** Anti-cheat checks run on a finished activity before its loops may claim land. */
object ClaimValidator {
    enum class Verdict { OK, TOO_FAST, TOO_SHORT, DAILY_LIMIT }

    const val MAX_CLAIMS_PER_DAY = 5

    /**
     * Rejects activities that moved faster than a person on that kind of activity could (a car on the
     * "run" setting) and caps how many conquests one person can make per day.
     */
    fun check(type: ActivityType, avgSpeedMps: Double, movingMs: Long, claimsToday: Int): Verdict = when {
        claimsToday >= MAX_CLAIMS_PER_DAY -> Verdict.DAILY_LIMIT
        movingMs < 3 * 60_000 -> Verdict.TOO_SHORT
        avgSpeedMps > type.maxSpeedMps * 0.65 -> Verdict.TOO_FAST
        else -> Verdict.OK
    }
}
