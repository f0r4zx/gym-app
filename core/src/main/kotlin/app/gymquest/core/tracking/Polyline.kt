package app.gymquest.core.tracking

import kotlin.math.roundToLong
import kotlin.math.sqrt

/** Google "encoded polyline" format: a whole route in a few hundred bytes, easy to store and sync. */
object Polyline {
    fun encode(points: List<GeoPoint>, precision: Int = 5): String {
        val factor = Math.pow(10.0, precision.toDouble())
        val sb = StringBuilder()
        var prevLat = 0L
        var prevLon = 0L
        for (p in points) {
            val lat = (p.lat * factor).roundToLong()
            val lon = (p.lon * factor).roundToLong()
            encodeValue(lat - prevLat, sb)
            encodeValue(lon - prevLon, sb)
            prevLat = lat
            prevLon = lon
        }
        return sb.toString()
    }

    fun decode(encoded: String, precision: Int = 5): List<GeoPoint> {
        val factor = Math.pow(10.0, precision.toDouble())
        val out = mutableListOf<GeoPoint>()
        var index = 0
        var lat = 0L
        var lon = 0L
        while (index < encoded.length) {
            val (dLat, next1) = decodeValue(encoded, index)
            val (dLon, next2) = decodeValue(encoded, next1)
            index = next2
            lat += dLat
            lon += dLon
            out += GeoPoint(lat / factor, lon / factor)
        }
        return out
    }

    private fun encodeValue(value: Long, sb: StringBuilder) {
        var v = if (value < 0) (value shl 1).inv() else value shl 1
        while (v >= 0x20) {
            sb.append((((0x20L or (v and 0x1f)) + 63).toInt()).toChar())
            v = v shr 5
        }
        sb.append(((v + 63).toInt()).toChar())
    }

    private fun decodeValue(s: String, start: Int): Pair<Long, Int> {
        var result = 0L
        var shift = 0
        var i = start
        while (true) {
            require(i < s.length) { "Polyline truncated" }
            val b = s[i++].code - 63
            result = result or ((b and 0x1f).toLong() shl shift)
            shift += 5
            if (b < 0x20) break
        }
        return (if (result and 1L != 0L) (result shr 1).inv() else result shr 1) to i
    }

    /**
     * Douglas–Peucker simplification: drops points that deviate less than [toleranceM] from the
     * line between their neighbours. Cuts a 1 Hz GPS track by ~90% with no visible change.
     */
    fun simplify(points: List<GeoPoint>, toleranceM: Double = 5.0): List<GeoPoint> {
        if (points.size < 3) return points
        val origin = points.first()
        val xy = points.map { Geo.toLocalMeters(it, origin) }
        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.lastIndex] = true
        val stack = ArrayDeque<Pair<Int, Int>>()
        stack.addLast(0 to points.lastIndex)
        while (stack.isNotEmpty()) {
            val (a, b) = stack.removeLast()
            var maxD = 0.0
            var idx = -1
            for (i in a + 1 until b) {
                val d = perpendicular(xy[i], xy[a], xy[b])
                if (d > maxD) {
                    maxD = d
                    idx = i
                }
            }
            if (idx >= 0 && maxD > toleranceM) {
                keep[idx] = true
                stack.addLast(a to idx)
                stack.addLast(idx to b)
            }
        }
        return points.filterIndexed { i, _ -> keep[i] }
    }

    private fun perpendicular(p: Pair<Double, Double>, a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
        val dx = b.first - a.first
        val dy = b.second - a.second
        val len2 = dx * dx + dy * dy
        if (len2 == 0.0) return sqrt((p.first - a.first).let { it * it } + (p.second - a.second).let { it * it })
        val t = (((p.first - a.first) * dx + (p.second - a.second) * dy) / len2).coerceIn(0.0, 1.0)
        val px = a.first + t * dx
        val py = a.second + t * dy
        return sqrt((p.first - px) * (p.first - px) + (p.second - py) * (p.second - py))
    }
}

/** Walks along a route by distance; used for map playback, "report ahead" warnings and ETAs. */
class RouteCursor(val points: List<GeoPoint>) {
    private val cumulative: DoubleArray = DoubleArray(points.size).also { c ->
        for (i in 1 until points.size) c[i] = c[i - 1] + Geo.distance(points[i - 1], points[i])
    }

    val totalM: Double get() = cumulative.lastOrNull() ?: 0.0

    /** The point [distanceM] metres from the start (clamped to the route). */
    fun pointAt(distanceM: Double): GeoPoint {
        if (points.isEmpty()) error("Empty route")
        val d = distanceM.coerceIn(0.0, totalM)
        var lo = 0
        var hi = points.lastIndex
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (cumulative[mid] < d) lo = mid + 1 else hi = mid
        }
        if (lo == 0) return points[0]
        val seg = cumulative[lo] - cumulative[lo - 1]
        val t = if (seg > 0) (d - cumulative[lo - 1]) / seg else 0.0
        return Geo.interpolate(points[lo - 1], points[lo], t)
    }

    /**
     * Where along the route (metres from the start) the closest point to [p] is, together with the
     * sideways distance to the route. Null if the route has fewer than two points.
     */
    fun project(p: GeoPoint): Projection? {
        if (points.size < 2) return null
        var best: Projection? = null
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            val (bx, by) = Geo.toLocalMeters(b, a)
            val (px, py) = Geo.toLocalMeters(p, a)
            val len2 = bx * bx + by * by
            val t = if (len2 == 0.0) 0.0 else ((px * bx + py * by) / len2).coerceIn(0.0, 1.0)
            val cx = bx * t
            val cy = by * t
            val off = sqrt((px - cx) * (px - cx) + (py - cy) * (py - cy))
            if (best == null || off < best.offRouteM) {
                best = Projection(cumulative[i - 1] + t * (cumulative[i] - cumulative[i - 1]), off)
            }
        }
        return best
    }

    data class Projection(val alongM: Double, val offRouteM: Double)
}
