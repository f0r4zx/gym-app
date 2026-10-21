package app.gymquest.core.tracking

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

data class LatLonBounds(val south: Double, val west: Double, val north: Double, val east: Double) {
    init {
        require(south < north && west < east) { "Región vacía" }
    }

    operator fun contains(p: GeoPoint) = p.lat in south..north && p.lon in west..east

    fun intersects(o: LatLonBounds) = south <= o.north && north >= o.south && west <= o.east && east >= o.west

    val center: GeoPoint get() = GeoPoint((south + north) / 2, (west + east) / 2)
}

/** Slippy-map (Web Mercator) tile arithmetic. */
object TileMath {
    const val MAX_LAT = 85.0511

    private fun clampLat(lat: Double) = lat.coerceIn(-MAX_LAT, MAX_LAT)

    fun tileX(lon: Double, zoom: Int): Int {
        val n = 1 shl zoom
        return floor((lon + 180.0) / 360.0 * n).toInt().coerceIn(0, n - 1)
    }

    fun tileY(lat: Double, zoom: Int): Int {
        val n = 1 shl zoom
        val rad = Math.toRadians(clampLat(lat))
        return floor((1.0 - ln(tan(rad) + 1.0 / cos(rad)) / PI) / 2.0 * n).toInt().coerceIn(0, n - 1)
    }

    /** Number of tiles covering [b] at one zoom level. */
    fun count(b: LatLonBounds, zoom: Int): Long {
        val x0 = tileX(b.west, zoom)
        val x1 = tileX(b.east, zoom)
        val y0 = tileY(b.north, zoom)
        val y1 = tileY(b.south, zoom)
        return (x1 - x0 + 1).toLong() * (y1 - y0 + 1).toLong()
    }

    fun count(b: LatLonBounds, minZoom: Int, maxZoom: Int): Long = (minZoom..maxZoom).sumOf { count(b, it) }
}

enum class MapDetail(val label: String, val maxZoom: Int) {
    OVERVIEW("Resumen (calles principales)", 13),
    STANDARD("Estándar (calles y edificios en 3D)", 15),
    DETAILED("Detallado (todo el barrio)", 16),
}

data class OfflineRegionPlan(
    val name: String,
    val bounds: LatLonBounds,
    val minZoom: Int,
    val maxZoom: Int,
    val tileCount: Long,
    val estimatedBytes: Long,
) {
    val estimatedMb: Double get() = estimatedBytes / 1_048_576.0
}

/**
 * Plans map downloads so GPS tracking and the map keep working with no internet. The numbers are
 * estimates for vector tiles; the actual download is done by the map SDK's offline manager.
 */
object OfflineMapPlanner {
    /** Refuse regions bigger than this: they would fill the phone and hammer the tile server. */
    const val MAX_TILES = 40_000L

    /** Average size of one vector tile, by zoom (bytes). Dense city tiles are the heavy ones. */
    private fun avgTileBytes(zoom: Int): Long = when {
        zoom <= 6 -> 25_000
        zoom <= 9 -> 45_000
        zoom <= 12 -> 60_000
        zoom == 13 -> 70_000
        zoom == 14 -> 60_000
        zoom == 15 -> 45_000
        else -> 35_000
    }

    /** Fonts, icons and the map style needed alongside tiles. */
    private const val OVERHEAD_BYTES = 3_000_000L

    fun boundsAround(center: GeoPoint, radiusKm: Double): LatLonBounds {
        val r = radiusKm * 1000.0
        val north = Geo.destination(center, 0.0, r).lat
        val south = Geo.destination(center, 180.0, r).lat
        val east = Geo.destination(center, 90.0, r).lon
        val west = Geo.destination(center, 270.0, r).lon
        return LatLonBounds(south.coerceAtLeast(-TileMath.MAX_LAT), west, north.coerceAtMost(TileMath.MAX_LAT), east)
    }

    fun plan(name: String, bounds: LatLonBounds, detail: MapDetail = MapDetail.STANDARD, minZoom: Int = 5): OfflineRegionPlan {
        val tiles = TileMath.count(bounds, minZoom, detail.maxZoom)
        val bytes = (minZoom..detail.maxZoom).sumOf { z -> TileMath.count(bounds, z) * avgTileBytes(z) } + OVERHEAD_BYTES
        return OfflineRegionPlan(name, bounds, minZoom, detail.maxZoom, tiles, bytes)
    }

    fun around(name: String, center: GeoPoint, radiusKm: Double, detail: MapDetail = MapDetail.STANDARD): OfflineRegionPlan =
        plan(name, boundsAround(center, radiusKm), detail)

    /** Whether the plan is small enough to download. */
    fun isAllowed(plan: OfflineRegionPlan): Boolean = plan.tileCount <= MAX_TILES

    /** The largest radius (in 1 km steps) that stays under the tile limit for this detail level. */
    fun maxRadiusKm(center: GeoPoint, detail: MapDetail): Int {
        var best = 1
        for (km in 1..200) {
            if (plan("x", boundsAround(center, km.toDouble()), detail).tileCount <= MAX_TILES) best = km else break
        }
        return best
    }

    fun fitsOnDevice(plan: OfflineRegionPlan, freeBytes: Long, reserveBytes: Long = 500L * 1024 * 1024): Boolean =
        plan.estimatedBytes + reserveBytes <= freeBytes

    /** Which downloaded regions (if any) contain [p]; the app shows "estás dentro de un mapa descargado". */
    fun coverage(p: GeoPoint, downloaded: List<OfflineRegionPlan>): List<OfflineRegionPlan> =
        downloaded.filter { p in it.bounds }

    /** Whether a planned route stays entirely inside downloaded maps. */
    fun routeCovered(route: List<GeoPoint>, downloaded: List<OfflineRegionPlan>): Boolean =
        route.all { p -> downloaded.any { p in it.bounds } }

    /** Share of [route] points that have a map downloaded, for a "92% de tu ruta está sin conexión" hint. */
    fun routeCoverage(route: List<GeoPoint>, downloaded: List<OfflineRegionPlan>): Double =
        if (route.isEmpty()) 0.0 else route.count { p -> downloaded.any { p in it.bounds } }.toDouble() / route.size

    /** The region that would cover a whole route with a margin, for "download maps for this trip". */
    fun boundsForRoute(route: List<GeoPoint>, marginKm: Double = 3.0): LatLonBounds? {
        if (route.isEmpty()) return null
        val minLat = route.minOf { it.lat }
        val maxLat = route.maxOf { it.lat }
        val minLon = route.minOf { it.lon }
        val maxLon = route.maxOf { it.lon }
        val sw = Geo.destination(Geo.destination(GeoPoint(minLat, minLon), 180.0, marginKm * 1000), 270.0, marginKm * 1000)
        val ne = Geo.destination(Geo.destination(GeoPoint(maxLat, maxLon), 0.0, marginKm * 1000), 90.0, marginKm * 1000)
        return LatLonBounds(sw.lat.coerceAtLeast(-TileMath.MAX_LAT), sw.lon, ne.lat.coerceAtMost(TileMath.MAX_LAT), ne.lon)
    }
}
