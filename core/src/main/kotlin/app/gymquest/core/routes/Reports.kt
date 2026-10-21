package app.gymquest.core.routes

import app.gymquest.core.tracking.Geo
import app.gymquest.core.tracking.GeoPoint
import app.gymquest.core.tracking.OfflineMapPlanner
import app.gymquest.core.tracking.OfflineRegionPlan
import app.gymquest.core.tracking.RouteCursor
import kotlin.math.floor
import kotlin.math.roundToInt

/** How you travel; sets the speed used for arrival estimates. */
enum class TravelMode(val label: String, val speedMps: Double) {
    WALK("A pie", 1.4), RUN("Corriendo", 2.8), BIKE("En bici", 5.5), CAR("En auto", 11.0)
}

enum class ReportType(
    val label: String,
    val ttlMs: Long,
    /** 0 = informative, 5 = serious. Higher severity is announced first. */
    val severity: Int,
    val delayMinutes: Int = 0,
) {
    TRAFFIC("Tráfico", 30 * 60_000L, 3, 3),
    ACCIDENT("Accidente", 2 * 3_600_000L, 5, 8),
    ROADWORK("Obras", 3 * 86_400_000L, 3, 2),
    HAZARD("Peligro en el camino", 3 * 3_600_000L, 4),
    POTHOLE("Bache", 30 * 86_400_000L, 2),
    CLOSED_ROAD("Calle cerrada", 12 * 3_600_000L, 5, 10),
    DANGER_ZONE("Zona insegura", 12 * 3_600_000L, 4),
    WEATHER("Lluvia o mal clima", 3 * 3_600_000L, 2),
    BIKE_LANE_BLOCKED("Ciclovía bloqueada", 6 * 3_600_000L, 2),
    WATER_FOUNTAIN("Bebedero de agua", 365 * 86_400_000L, 0),
    SCENIC("Vista bonita", 365 * 86_400_000L, 0),
}

data class RouteReport(
    val id: String,
    val type: ReportType,
    val location: GeoPoint,
    val createdMs: Long,
    val reporterId: String,
    val confirmations: Double = 0.0,
    val dismissals: Double = 0.0,
    val voters: Set<String> = emptySet(),
    val note: String = "",
) {
    /** Each confirmation keeps the report alive longer (up to double its normal life). */
    fun expiresMs(): Long = createdMs + (type.ttlMs * (1.0 + (confirmations * 0.25).coerceAtMost(1.0))).toLong()

    /** 0..1: a fresh report is trusted at 0.5 until people confirm or dismiss it. */
    fun confidence(): Double = (confirmations + 1.0) / (confirmations + dismissals + 2.0)

    fun isActive(nowMs: Long): Boolean = nowMs < expiresMs() && confidence() >= MIN_CONFIDENCE

    companion object {
        const val MIN_CONFIDENCE = 0.35
    }
}

/** Trust in a reporter, learned from how the community votes on their reports. */
data class Reputation(val confirmed: Int = 0, val dismissed: Int = 0) {
    val score: Double get() = (confirmed + 2.0) / (confirmed + dismissed + 4.0)

    /** How much weight this person's votes carry: 0.5 for the worst, 1.5 for the best. */
    val voteWeight: Double get() = 0.5 + score
}

enum class VoteResult { OK, TOO_FAR, OWN_REPORT, ALREADY_VOTED }

enum class PostResult { CREATED, MERGED, RATE_LIMITED }

/**
 * Community road reports (traffic, accidents, roadworks, potholes, water fountains...). Reports expire,
 * die when people dismiss them, and are merged when several people report the same thing. Everything
 * works from a local copy, so reports already downloaded are available with no connection.
 */
class ReportBook {
    private val reports = LinkedHashMap<String, RouteReport>()
    private val postTimes = HashMap<String, MutableList<Long>>()
    private val grid = HashMap<Pair<Int, Int>, MutableSet<String>>()

    val all: Collection<RouteReport> get() = reports.values

    private fun cell(p: GeoPoint) = floor(p.lat / CELL_DEG).toInt() to floor(p.lon / CELL_DEG).toInt()

    fun get(id: String): RouteReport? = reports[id]

    fun post(report: RouteReport, nowMs: Long): Pair<PostResult, RouteReport?> {
        val times = postTimes.getOrPut(report.reporterId) { mutableListOf() }
        times.removeAll { nowMs - it > 3_600_000L }
        if (times.size >= MAX_POSTS_PER_HOUR) return PostResult.RATE_LIMITED to null

        val duplicate = near(report.location, MERGE_RADIUS_M, nowMs).firstOrNull { it.type == report.type }
        times += nowMs
        if (duplicate != null) {
            if (report.reporterId in duplicate.voters || report.reporterId == duplicate.reporterId) return PostResult.MERGED to duplicate
            val merged = duplicate.copy(confirmations = duplicate.confirmations + 1, voters = duplicate.voters + report.reporterId)
            reports[merged.id] = merged
            return PostResult.MERGED to merged
        }
        reports[report.id] = report
        grid.getOrPut(cell(report.location)) { mutableSetOf() }.add(report.id)
        return PostResult.CREATED to report
    }

    /**
     * Confirm ("sigue ahí") or dismiss ("ya no está") a report. You must be near it, so people cannot
     * vote on things they have not seen, and your reputation sets how much your vote weighs.
     */
    fun vote(id: String, voterId: String, voterPosition: GeoPoint, stillThere: Boolean, reputation: Reputation = Reputation()): VoteResult {
        val r = reports[id] ?: return VoteResult.TOO_FAR
        if (r.reporterId == voterId) return VoteResult.OWN_REPORT
        if (voterId in r.voters) return VoteResult.ALREADY_VOTED
        if (Geo.distance(voterPosition, r.location) > VOTE_RADIUS_M) return VoteResult.TOO_FAR
        val w = reputation.voteWeight
        reports[id] = if (stillThere) r.copy(confirmations = r.confirmations + w, voters = r.voters + voterId)
        else r.copy(dismissals = r.dismissals + w, voters = r.voters + voterId)
        return VoteResult.OK
    }

    fun near(p: GeoPoint, radiusM: Double, nowMs: Long): List<RouteReport> {
        val span = (radiusM / (CELL_DEG * 111_320.0)).toInt() + 1
        val (cy, cx) = cell(p)
        val out = mutableListOf<RouteReport>()
        for (dy in -span..span) for (dx in -span..span) {
            grid[(cy + dy) to (cx + dx)]?.forEach { id ->
                val r = reports[id]
                if (r != null && r.isActive(nowMs) && Geo.distance(p, r.location) <= radiusM) out += r
            }
        }
        return out
    }

    fun active(nowMs: Long): List<RouteReport> = reports.values.filter { it.isActive(nowMs) }

    /** Drops dead reports to keep the local copy small. */
    fun purge(nowMs: Long): Int {
        val dead = reports.values.filter { !it.isActive(nowMs) }
        for (r in dead) {
            reports.remove(r.id)
            grid[cell(r.location)]?.remove(r.id)
        }
        return dead.size
    }

    companion object {
        const val CELL_DEG = 0.005 // ~550 m
        const val MAX_POSTS_PER_HOUR = 10
        const val MERGE_RADIUS_M = 80.0
        const val VOTE_RADIUS_M = 500.0
    }
}

data class Warning(val report: RouteReport, val distanceAheadM: Double, val etaSeconds: Int, val message: String)

/** "En 800 m hay tráfico": what lies ahead on the route you are following. */
object RouteAhead {
    fun reportsOnRoute(route: List<GeoPoint>, reports: List<RouteReport>, nowMs: Long, bufferM: Double = 60.0): List<Pair<RouteReport, Double>> {
        if (route.size < 2) return emptyList()
        val cursor = RouteCursor(route)
        return reports.filter { it.isActive(nowMs) }.mapNotNull { r ->
            val p = cursor.project(r.location) ?: return@mapNotNull null
            if (p.offRouteM <= bufferM) r to p.alongM else null
        }.sortedBy { it.second }
    }

    /**
     * Warnings for reports within [horizonM] ahead of where you are. Serious ones come first, and
     * purely informative ones (fountains, views) are skipped so a driver is never distracted by them.
     */
    fun warnings(
        route: List<GeoPoint>,
        position: GeoPoint,
        mode: TravelMode,
        reports: List<RouteReport>,
        nowMs: Long,
        horizonM: Double = 3_000.0,
        alreadyWarned: Set<String> = emptySet(),
    ): List<Warning> {
        if (route.size < 2) return emptyList()
        val here = RouteCursor(route).project(position)?.alongM ?: return emptyList()
        return reportsOnRoute(route, reports, nowMs)
            .filter { (r, along) -> r.type.severity >= 2 && r.id !in alreadyWarned && along >= here - 20 && along - here <= horizonM }
            .map { (r, along) ->
                val ahead = (along - here).coerceAtLeast(0.0)
                Warning(r, ahead, (ahead / mode.speedMps).roundToInt(), speak(r, ahead))
            }
            .sortedWith(compareByDescending<Warning> { it.report.type.severity }.thenBy { it.distanceAheadM })
    }

    private fun speak(r: RouteReport, distanceM: Double): String {
        val dist = if (distanceM < 100) "Adelante" else if (distanceM < 1000) "En ${(distanceM / 50).roundToInt() * 50} metros" else "En ${"%.1f".format(distanceM / 1000)} kilómetros"
        return "$dist: ${r.type.label.lowercase()}."
    }
}

data class TripSummary(
    val distanceM: Double,
    val etaMinutes: Int,
    val reportsByType: Map<ReportType, Int>,
    val worstSeverity: Int,
    val blocked: Boolean,
    /** Share of the route covered by downloaded offline maps, 0..1. */
    val offlineCoverage: Double,
)

object TripPlanner {
    fun summarize(
        route: List<GeoPoint>,
        mode: TravelMode,
        reports: List<RouteReport>,
        nowMs: Long,
        offlineRegions: List<OfflineRegionPlan> = emptyList(),
    ): TripSummary {
        val distance = Geo.length(route)
        val onRoute = RouteAhead.reportsOnRoute(route, reports, nowMs).map { it.first }
        val delay = onRoute.sumOf { it.type.delayMinutes }
        val eta = (distance / mode.speedMps / 60.0).roundToInt() + if (mode == TravelMode.CAR || mode == TravelMode.BIKE) delay else 0
        return TripSummary(
            distanceM = distance,
            etaMinutes = eta,
            reportsByType = onRoute.groupingBy { it.type }.eachCount(),
            worstSeverity = onRoute.maxOfOrNull { it.type.severity } ?: 0,
            blocked = onRoute.any { it.type == ReportType.CLOSED_ROAD },
            offlineCoverage = OfflineMapPlanner.routeCoverage(route, offlineRegions),
        )
    }

    /**
     * Without internet there is no routing engine, so the app draws a guide line through the points the
     * user chose. The GPS and downloaded map still show where you are relative to it.
     */
    fun straightLine(origin: GeoPoint, destination: GeoPoint, waypoints: List<GeoPoint> = emptyList(), stepM: Double = 50.0): List<GeoPoint> {
        val stops = listOf(origin) + waypoints + destination
        val out = mutableListOf(stops.first())
        for ((a, b) in stops.zipWithNext()) {
            val steps = (Geo.distance(a, b) / stepM).toInt().coerceAtLeast(1)
            for (i in 1..steps) out += Geo.interpolate(a, b, i / steps.toDouble())
        }
        return out
    }
}
