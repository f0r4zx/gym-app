package app.gymquest.core.tracking

import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max

data class RouteSummary(
    val id: String,
    val ownerId: String,
    val type: ActivityType,
    val name: String,
    /** Simplified track, enough to draw and compare. */
    val points: List<GeoPoint>,
    val distanceM: Double,
    val movingMs: Long,
    val timestampMs: Long,
)

/**
 * Compares routes without caring about GPS wobble or direction: each route becomes the set of
 * ~120 m grid cells it passes through, and two routes are similar when those sets overlap.
 */
object RouteFingerprint {
    const val CELL_M = 120.0

    fun cells(points: List<GeoPoint>, cellM: Double = CELL_M): Set<Long> {
        if (points.isEmpty()) return emptySet()
        val out = HashSet<Long>()
        for (i in points.indices) {
            out += cell(points[i], cellM)
            if (i > 0) {
                // Fill in cells that sparse points would skip.
                val d = Geo.distance(points[i - 1], points[i])
                val steps = (d / (cellM / 2)).toInt()
                for (s in 1 until steps) out += cell(Geo.interpolate(points[i - 1], points[i], s / steps.toDouble()), cellM)
            }
        }
        return out
    }

    fun similarity(a: Set<Long>, b: Set<Long>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val inter = a.count { it in b }
        val union = a.size + b.size - inter
        return inter.toDouble() / union
    }

    fun similarity(a: List<GeoPoint>, b: List<GeoPoint>): Double = similarity(cells(a), cells(b))

    private fun cell(p: GeoPoint, cellM: Double): Long {
        val latStep = cellM / 111_320.0
        val ix = floor(p.lat / latStep).toLong()
        // Longitude cells shrink toward the poles; use the latitude band's own width so cells stay ~square.
        val bandLat = (ix + 0.5) * latStep
        val lonStep = cellM / (111_320.0 * max(cos(Math.toRadians(bandLat)), 0.01))
        val iy = floor(p.lon / lonStep).toLong()
        return (ix shl 32) xor (iy and 0xffffffffL)
    }
}

data class RouteCluster(
    val representative: RouteSummary,
    val members: List<RouteSummary>,
) {
    val distinctUsers: Int get() = members.map { it.ownerId }.toSet().size
    val avgDistanceM: Double get() = members.map { it.distanceM }.average()
    val bestTimeMs: Long get() = members.minOf { it.movingMs }
}

/** Finds the routes lots of people use, for the "popular routes near you" feed. */
object PopularRoutes {
    private const val DAY_MS = 86_400_000L

    fun cluster(routes: List<RouteSummary>, threshold: Double = 0.6): List<RouteCluster> {
        val clusters = mutableListOf<MutableList<RouteSummary>>()
        val fingerprints = mutableMapOf<String, Set<Long>>()
        fun fp(r: RouteSummary) = fingerprints.getOrPut(r.id) { RouteFingerprint.cells(r.points) }

        // Longest first, so the representative is the fullest version of the route.
        for (r in routes.sortedByDescending { it.distanceM }) {
            val home = clusters.firstOrNull { c ->
                c.first().type == r.type && RouteFingerprint.similarity(fp(c.first()), fp(r)) >= threshold
            }
            if (home != null) home += r else clusters += mutableListOf(r)
        }
        return clusters.map { RouteCluster(it.first(), it.toList()) }
    }

    /**
     * Ranks clusters: many different people beat one person repeating a route, and recent runs
     * count more than old ones.
     */
    fun rank(clusters: List<RouteCluster>, nowMs: Long, windowDays: Int = 60): List<RouteCluster> =
        clusters.sortedByDescending { c -> score(c, nowMs, windowDays) }

    fun score(c: RouteCluster, nowMs: Long, windowDays: Int): Double {
        val recent = c.members.filter { (nowMs - it.timestampMs) / DAY_MS <= windowDays }
        if (recent.isEmpty()) return 0.0
        val weighted = recent.sumOf { 1.0 / (1.0 + (nowMs - it.timestampMs) / DAY_MS / 14.0) }
        return recent.map { it.ownerId }.toSet().size * 2.0 + weighted
    }

    /** Routes whose start is within [radiusM] of [here]. */
    fun near(clusters: List<RouteCluster>, here: GeoPoint, radiusM: Double): List<RouteCluster> =
        clusters.filter { Geo.distance(it.representative.points.first(), here) <= radiusM }
}
