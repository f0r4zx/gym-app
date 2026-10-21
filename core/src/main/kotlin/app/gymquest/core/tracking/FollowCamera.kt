package app.gymquest.core.tracking

import kotlin.math.abs

data class CameraState(
    val center: GeoPoint,
    /** Compass heading the camera looks toward. */
    val bearing: Double,
    /** Tilt from straight-down: 0 = top view, 60 = looking toward the horizon (3D). */
    val tilt: Double,
    val zoom: Double,
)

/**
 * Drives the 3D "chase" camera: it looks where you are heading, tilts to give a 3D view and zooms
 * out as you go faster. Smoothing stops the map from jittering with every GPS fix.
 */
class FollowCamera(
    private val bearingSmoothing: Double = 0.25,
    private val zoomSmoothing: Double = 0.1,
    private val maxTilt: Double = 60.0,
) {
    private var bearing = Double.NaN
    private var zoom = Double.NaN
    private var tilt = 45.0

    fun update(position: GeoPoint, speedMps: Double, headingDeg: Double?): CameraState {
        // At a walking pace and above the heading is reliable; standing still it jumps around.
        if (headingDeg != null && speedMps >= MIN_HEADING_SPEED) {
            bearing = if (bearing.isNaN()) headingDeg else normalize(bearing + Geo.angleDiff(bearing, headingDeg) * bearingSmoothing)
        } else if (bearing.isNaN()) {
            bearing = 0.0
        }
        val targetZoom = zoomFor(speedMps)
        zoom = if (zoom.isNaN()) targetZoom else zoom + (targetZoom - zoom) * zoomSmoothing
        val targetTilt = if (speedMps < MIN_HEADING_SPEED) 30.0 else maxTilt
        tilt += (targetTilt - tilt) * 0.1
        return CameraState(position, bearing, tilt, zoom)
    }

    fun reset() {
        bearing = Double.NaN
        zoom = Double.NaN
        tilt = 45.0
    }

    companion object {
        const val MIN_HEADING_SPEED = 0.8

        /** Walking 17.5, running 16.8, cycling 15.5, driving 14: closer when slow, wider when fast. */
        fun zoomFor(speedMps: Double): Double = (17.5 - speedMps * 0.25).coerceIn(13.5, 17.5)

        private fun normalize(deg: Double) = ((deg % 360.0) + 360.0) % 360.0
    }
}

/** Camera keyframes that fly along a finished route, for the 3D replay of an activity. */
object RoutePlayback {
    fun keyframes(route: List<GeoPoint>, frames: Int, lookAheadM: Double = 150.0): List<CameraState> {
        if (route.size < 2 || frames < 2) return emptyList()
        val cursor = RouteCursor(route)
        val total = cursor.totalM
        var smoothBearing = Double.NaN
        return (0 until frames).map { i ->
            val d = total * i / (frames - 1)
            val here = cursor.pointAt(d)
            val ahead = cursor.pointAt((d + lookAheadM).coerceAtMost(total))
            val raw = if (Geo.distance(here, ahead) > 5.0) Geo.bearing(here, ahead) else if (smoothBearing.isNaN()) 0.0 else smoothBearing
            smoothBearing = if (smoothBearing.isNaN()) raw else (smoothBearing + Geo.angleDiff(smoothBearing, raw) * 0.3 + 360.0) % 360.0
            CameraState(here, smoothBearing, 62.0, 16.2)
        }
    }

    /** Largest bearing change between consecutive frames; a sanity check for smoothness. */
    fun maxTurn(frames: List<CameraState>): Double =
        frames.zipWithNext().maxOfOrNull { (a, b) -> abs(Geo.angleDiff(a.bearing, b.bearing)) } ?: 0.0
}
