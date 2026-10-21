package app.gymquest.routing

import app.gymquest.core.routes.TravelMode
import app.gymquest.core.tracking.Geo
import app.gymquest.core.tracking.GeoPoint
import app.gymquest.core.tracking.Polyline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class PlannedRoute(
    val points: List<GeoPoint>,
    val distanceM: Double,
    val durationS: Double,
    /** Where the route came from, shown to the user ("OpenStreetMap" or the offline guide line). */
    val source: String,
    val isGuideLine: Boolean = false,
)

data class Place(val name: String, val point: GeoPoint)

@Serializable
private data class OsrmResponse(val code: String = "", val routes: List<OsrmRoute> = emptyList())

@Serializable
private data class OsrmRoute(val geometry: String = "", val distance: Double = 0.0, val duration: Double = 0.0)

@Serializable
private data class NominatimPlace(val display_name: String = "", val lat: String = "0", val lon: String = "0")

/**
 * Plans a route to a destination with the public OpenStreetMap routing servers (walking, cycling
 * and driving profiles) and finds places by name. Both need internet; with no connection the planner
 * falls back to a straight guide line so the app still points you the right way.
 *
 * These are shared community servers meant for light use. For many users, point the app at your own
 * routing server instead (the base addresses are constants below).
 */
object RoutePlanning {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private const val USER_AGENT = "GymQuest/0.1 (fitness app; personal use)"

    private fun base(mode: TravelMode) = when (mode) {
        TravelMode.BIKE -> "https://routing.openstreetmap.de/routed-bike"
        TravelMode.CAR -> "https://routing.openstreetmap.de/routed-car"
        TravelMode.WALK, TravelMode.RUN -> "https://routing.openstreetmap.de/routed-foot"
    }

    suspend fun route(mode: TravelMode, from: GeoPoint, to: GeoPoint): PlannedRoute = withContext(Dispatchers.IO) {
        val online = runCatching {
            val url = "${base(mode)}/route/v1/driving/${from.lon},${from.lat};${to.lon},${to.lat}?overview=full&geometries=polyline"
            val parsed = json.decodeFromString(OsrmResponse.serializer(), get(url))
            val r = parsed.routes.firstOrNull()?.takeIf { parsed.code == "Ok" && it.geometry.isNotBlank() }
            r?.let { PlannedRoute(Polyline.decode(it.geometry), it.distance, it.duration, "OpenStreetMap") }
        }.getOrNull()
        online ?: guideLine(mode, from, to)
    }

    /** Straight line between the two points, with an arrival estimate from typical speed. */
    fun guideLine(mode: TravelMode, from: GeoPoint, to: GeoPoint): PlannedRoute {
        val d = Geo.distance(from, to) * 1.3 // roads are rarely straight
        return PlannedRoute(listOf(from, to), d, d / mode.speedMps, "Línea guía (sin internet)", isGuideLine = true)
    }

    suspend fun search(query: String): List<Place> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        runCatching {
            val q = URLEncoder.encode(query.trim(), "UTF-8")
            val text = get("https://nominatim.openstreetmap.org/search?q=$q&format=jsonv2&limit=5&accept-language=es")
            json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(NominatimPlace.serializer()), text)
                .mapNotNull { p ->
                    val lat = p.lat.toDoubleOrNull() ?: return@mapNotNull null
                    val lon = p.lon.toDoubleOrNull() ?: return@mapNotNull null
                    Place(p.display_name.split(",").take(3).joinToString(",").trim(), GeoPoint(lat, lon))
                }
        }.getOrDefault(emptyList())
    }

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 8_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("User-Agent", USER_AGENT)
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
