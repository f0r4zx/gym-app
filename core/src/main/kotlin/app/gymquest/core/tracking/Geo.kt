package app.gymquest.core.tracking

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class GeoPoint(val lat: Double, val lon: Double) {
    init {
        require(lat in -90.0..90.0) { "Latitud inválida: $lat" }
        require(lon in -180.0..180.0) { "Longitud inválida: $lon" }
    }
}

object Geo {
    const val EARTH_RADIUS_M = 6_371_008.8
    private const val DEG = PI / 180.0

    /** Great-circle distance in metres. */
    fun distance(a: GeoPoint, b: GeoPoint): Double = distance(a.lat, a.lon, b.lat, b.lon)

    fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = (lat2 - lat1) * DEG
        val dLon = (lon2 - lon1) * DEG
        val h = sin(dLat / 2) * sin(dLat / 2) + cos(lat1 * DEG) * cos(lat2 * DEG) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS_M * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    /** Initial compass bearing from [a] to [b], 0..360 (0 = north, 90 = east). */
    fun bearing(a: GeoPoint, b: GeoPoint): Double {
        val dLon = (b.lon - a.lon) * DEG
        val y = sin(dLon) * cos(b.lat * DEG)
        val x = cos(a.lat * DEG) * sin(b.lat * DEG) - sin(a.lat * DEG) * cos(b.lat * DEG) * cos(dLon)
        return (atan2(y, x) / DEG + 360.0) % 360.0
    }

    /** The point reached by travelling [meters] from [p] along [bearingDeg]. */
    fun destination(p: GeoPoint, bearingDeg: Double, meters: Double): GeoPoint {
        val ang = meters / EARTH_RADIUS_M
        val brg = bearingDeg * DEG
        val lat1 = p.lat * DEG
        val lon1 = p.lon * DEG
        val lat2 = asin(sin(lat1) * cos(ang) + cos(lat1) * sin(ang) * cos(brg))
        val lon2 = lon1 + atan2(sin(brg) * sin(ang) * cos(lat1), cos(ang) - sin(lat1) * sin(lat2))
        return GeoPoint(lat2 / DEG, ((lon2 / DEG + 540.0) % 360.0) - 180.0)
    }

    fun interpolate(a: GeoPoint, b: GeoPoint, t: Double) = GeoPoint(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)

    /** Total length of a polyline in metres. */
    fun length(points: List<GeoPoint>): Double = points.zipWithNext().sumOf { (a, b) -> distance(a, b) }

    /** Shortest signed difference between two compass angles, -180..180. */
    fun angleDiff(from: Double, to: Double): Double = ((to - from + 540.0) % 360.0) - 180.0

    /** Equirectangular projection to local metres around [origin]; accurate for routes under ~50 km. */
    fun toLocalMeters(p: GeoPoint, origin: GeoPoint): Pair<Double, Double> {
        val x = (p.lon - origin.lon) * DEG * EARTH_RADIUS_M * cos(origin.lat * DEG)
        val y = (p.lat - origin.lat) * DEG * EARTH_RADIUS_M
        return x to y
    }
}

object Formats {
    /** Pace as m:ss per km from a speed in m/s ("--:--" when stopped). */
    fun pace(speedMps: Double): String {
        if (speedMps < 0.3) return "--:--"
        val secPerKm = (1000.0 / speedMps).toInt().coerceAtMost(59 * 60 + 59)
        return "%d:%02d".format(secPerKm / 60, secPerKm % 60)
    }

    fun speedKmh(speedMps: Double): String = "%.1f".format(speedMps * 3.6)

    fun duration(ms: Long): String {
        val s = ms / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }

    fun distance(meters: Double): String =
        if (meters < 1000) "${meters.toInt()} m" else "%.2f km".format(meters / 1000.0)
}
