package app.gymquest.core.tracking

import kotlin.math.abs
import kotlin.math.max

/** One location fix as delivered by the phone's GPS (works without internet). */
data class TrackPoint(
    val timeMs: Long,
    val lat: Double,
    val lon: Double,
    val altitudeM: Double? = null,
    /** Horizontal accuracy radius in metres (smaller is better). */
    val accuracyM: Double = 5.0,
    val speedMps: Double? = null,
)

enum class RecorderState { WAITING_FOR_FIX, RECORDING, AUTO_PAUSED, MANUAL_PAUSED }

enum class Rejection { LOW_ACCURACY, OUT_OF_ORDER, GPS_JUMP, PAUSED }

data class Split(val index: Int, val durationMs: Long, val distanceM: Double = 1000.0) {
    val speedMps: Double get() = distanceM / (durationMs / 1000.0)
}

data class RecorderConfig(
    val maxAccuracyM: Double = 35.0,
    val autoPause: Boolean = true,
    /** Minimum time without real movement before auto-pausing (longer for slow activities). */
    val pauseAfterMs: Long = 6_000,
    val elevationThresholdM: Double = 3.0,
    val splitLengthM: Double = 1_000.0,
    /** Smallest displacement that counts as movement, regardless of how good the fix is. */
    val minStepM: Double = 4.0,
)

data class ActivitySummary(
    val type: ActivityType,
    val distanceM: Double,
    val elapsedMs: Long,
    val movingMs: Long,
    val elevationGainM: Double,
    val elevationLossM: Double,
    val avgSpeedMps: Double,
    val maxSpeedMps: Double,
    val calories: Int,
    val splits: List<Split>,
)

/**
 * Turns a stream of noisy GPS fixes into trustworthy workout stats. Everything here runs on the
 * phone: GPS needs no internet, and recordings are stored locally until they can be synced.
 *
 * Pipeline per fix: reject bad fixes → smooth the position (Kalman) → count distance only when
 * you move away from the last "anchor" by more than the fix's own uncertainty (stops GPS wobble
 * from inflating distance) → auto-pause/resume → elevation and per-km splits.
 */
class ActivityRecorder(
    val type: ActivityType,
    private val weightKg: Double,
    private val config: RecorderConfig = RecorderConfig(),
) {
    var state = RecorderState.WAITING_FOR_FIX
        private set

    var distanceM = 0.0
        private set
    var movingMs = 0L
        private set
    var elevationGainM = 0.0
        private set
    var elevationLossM = 0.0
        private set
    var maxSpeedMps = 0.0
        private set

    /** Speed over the last few seconds, for the live display. */
    var currentSpeedMps = 0.0
        private set

    private val acceptedPoints = mutableListOf<GeoPoint>()
    val track: List<GeoPoint> get() = acceptedPoints

    private val splitList = mutableListOf<Split>()
    val splits: List<Split> get() = splitList

    val elapsedMs: Long get() = if (startMs < 0) 0 else lastMs - startMs

    private var startMs = -1L
    private var lastMs = 0L
    private var rawLast: TrackPoint? = null

    // Kalman state
    private var filtered: GeoPoint? = null
    private var variance = -1.0

    // Anchor: the last position at which distance was added.
    private var anchor: GeoPoint? = null
    private var anchorTimeMs = 0L
    private var anchorMovingMs = 0L

    private val recent = ArrayDeque<Pair<Long, Double>>() // time, metres counted
    private val altitudes = ArrayDeque<Double>()
    private var altitudeAnchor = Double.NaN
    private var nextSplitM = config.splitLengthM
    private var splitStartMovingMs = 0L

    /** Returns null if the fix was used, or the reason it was ignored. */
    fun add(p: TrackPoint): Rejection? {
        if (state == RecorderState.MANUAL_PAUSED) return Rejection.PAUSED
        if (p.accuracyM > config.maxAccuracyM) return Rejection.LOW_ACCURACY

        val prevRaw = rawLast
        if (prevRaw != null) {
            if (p.timeMs <= prevRaw.timeMs) return Rejection.OUT_OF_ORDER
            val dtRaw = (p.timeMs - prevRaw.timeMs) / 1000.0
            val rawD = Geo.distance(prevRaw.lat, prevRaw.lon, p.lat, p.lon)
            // A physically impossible jump (tunnel, bad fix): drop it and keep the last good one.
            if (dtRaw < 60 && (rawD - p.accuracyM - prevRaw.accuracyM) / dtRaw > type.maxSpeedMps * 1.6) return Rejection.GPS_JUMP
        }
        rawLast = p

        val smooth = smooth(p)
        filtered = smooth
        trackAltitudeIfRecording(p)

        if (startMs < 0) {
            startMs = p.timeMs
            lastMs = p.timeMs
            state = RecorderState.RECORDING
            startAnchor(smooth, p.timeMs)
            acceptedPoints += smooth
            return null
        }

        val dt = p.timeMs - lastMs
        lastMs = p.timeMs
        val from = anchor ?: smooth.also { startAnchor(it, p.timeMs) }
        val fromAnchor = Geo.distance(from, smooth)
        val threshold = max(config.minStepM, p.accuracyM)
        val moved = fromAnchor >= threshold
        // Device-reported speed is a good second opinion when the fix is accurate.
        val deviceSpeed = p.speedMps?.takeIf { it >= 0 && p.accuracyM <= 20 }

        when (state) {
            RecorderState.RECORDING -> {
                movingMs += dt
                var counted = 0.0
                if (moved) {
                    counted = fromAnchor
                    addDistance(counted, from, smooth, p.timeMs)
                    anchor = smooth
                    anchorTimeMs = p.timeMs
                    anchorMovingMs = movingMs
                    acceptedPoints += smooth
                }
                pushRecent(p.timeMs, counted)
                currentSpeedMps = recentSpeed()
                // Anchored distance arrives in bursts, so the top speed is measured over a longer window.
                val sustained = speedOver(MAX_SPEED_WINDOW_MS, MAX_SPEED_WINDOW_MS - 3_000)
                val candidate = deviceSpeed?.takeIf { recent.size >= 3 } ?: sustained
                if (candidate != null && candidate > maxSpeedMps && candidate <= type.maxSpeedMps) maxSpeedMps = candidate
                maybeAutoPause(p, threshold, deviceSpeed)
            }
            RecorderState.AUTO_PAUSED -> {
                pushRecent(p.timeMs, 0.0)
                currentSpeedMps = 0.0
                val resumed = moved && (deviceSpeed == null || deviceSpeed >= type.stoppedBelowMps)
                if (resumed) {
                    state = RecorderState.RECORDING
                    addDistance(fromAnchor, from, smooth, p.timeMs)
                    anchor = smooth
                    anchorTimeMs = p.timeMs
                    anchorMovingMs = movingMs
                    acceptedPoints += smooth
                    recent.clear()
                }
            }
            else -> Unit
        }
        return null
    }

    fun pause() {
        if (state == RecorderState.RECORDING || state == RecorderState.AUTO_PAUSED) state = RecorderState.MANUAL_PAUSED
    }

    fun resume() {
        if (state != RecorderState.MANUAL_PAUSED) return
        state = RecorderState.RECORDING
        recent.clear()
        // The phone may have moved anywhere while paused: start over from the next fix.
        filtered = null
        variance = -1.0
        rawLast = null
        anchor = null
        currentSpeedMps = 0.0
    }

    fun summary(): ActivitySummary {
        val avg = if (movingMs > 0) distanceM / (movingMs / 1000.0) else 0.0
        return ActivitySummary(
            type, distanceM, elapsedMs, movingMs, elevationGainM, elevationLossM, avg, maxSpeedMps,
            type.calories(weightKg, movingMs, avg).toInt(), splitList.toList(),
        )
    }

    // ---- internals --------------------------------------------------------------------------

    private fun startAnchor(p: GeoPoint, timeMs: Long) {
        anchor = p
        anchorTimeMs = timeMs
        anchorMovingMs = movingMs
        if (acceptedPoints.isEmpty() || acceptedPoints.last() != p) acceptedPoints += p
    }

    /** Kalman filter on lat/lon: trusts the fix less when accuracy is poor. */
    private fun smooth(p: TrackPoint): GeoPoint {
        val acc = max(p.accuracyM, 1.0)
        val prev = filtered
        if (prev == null || variance < 0) {
            variance = acc * acc
            return GeoPoint(p.lat, p.lon)
        }
        val dtSec = max(((p.timeMs - lastMs).coerceAtLeast(0)) / 1000.0, 1.0)
        val q = 1.5 + 0.3 * currentSpeedMps
        variance += dtSec * q * q
        val k = variance / (variance + acc * acc)
        variance *= (1 - k)
        return GeoPoint(prev.lat + k * (p.lat - prev.lat), prev.lon + k * (p.lon - prev.lon))
    }

    private fun pushRecent(timeMs: Long, metres: Double) {
        recent.addLast(timeMs to metres)
        while (recent.isNotEmpty() && timeMs - recent.first().first > RECENT_WINDOW_MS) recent.removeFirst()
    }

    /** Average speed over the trailing [windowMs]; null until there is enough history to trust it. */
    private fun speedOver(windowMs: Long, minSpanMs: Long): Double? {
        if (recent.size < 2) return null
        val now = recent.last().first
        val inWindow = recent.filter { now - it.first <= windowMs }
        if (inWindow.size < 2) return null
        val spanMs = inWindow.last().first - inWindow.first().first
        if (spanMs < minSpanMs) return null
        // The first entry's metres happened before the window started, so leave them out.
        return inWindow.drop(1).sumOf { it.second } / (spanMs / 1000.0)
    }

    private fun recentSpeed(): Double = speedOver(DISPLAY_WINDOW_MS, 2_000) ?: 0.0

    /**
     * Pauses when there has been no real movement for long enough. The wait scales with how slow
     * the activity can be: at 0.4 m/s it takes 15 s to cover a 6 m accuracy radius, which is not a stop.
     */
    private fun maybeAutoPause(p: TrackPoint, threshold: Double, deviceSpeed: Double?) {
        if (!config.autoPause) return
        val waitMs = max(config.pauseAfterMs, (threshold / type.stoppedBelowMps * 1000).toLong())
        val still = p.timeMs - anchorTimeMs
        val deviceSaysMoving = deviceSpeed != null && deviceSpeed >= type.stoppedBelowMps * 1.5
        if (still >= waitMs && !deviceSaysMoving) {
            state = RecorderState.AUTO_PAUSED
            // The time spent waiting to be sure was not moving time.
            movingMs = (movingMs - still).coerceAtLeast(anchorMovingMs)
            currentSpeedMps = 0.0
            recent.clear()
        }
    }

    private fun addDistance(metres: Double, from: GeoPoint, to: GeoPoint, nowMs: Long) {
        val before = distanceM
        distanceM += metres
        recordSplits(before, metres, anchorTimeMs, nowMs, from, to)
    }

    /** Emits a split each time a boundary is crossed, interpolating the moment inside the segment. */
    private fun recordSplits(distBefore: Double, segment: Double, fromMs: Long, toMs: Long, @Suppress("UNUSED_PARAMETER") from: GeoPoint, @Suppress("UNUSED_PARAMETER") to: GeoPoint) {
        if (segment <= 0) return
        // Moving time at the start of the segment (anchor) and now.
        val movingAtStart = anchorMovingMs
        val movingAtEnd = movingMs
        while (distBefore + segment >= nextSplitM - 1e-9) {
            val fraction = ((nextSplitM - distBefore) / segment).coerceIn(0.0, 1.0)
            val atBoundary = movingAtStart + (fraction * (movingAtEnd - movingAtStart)).toLong()
            splitList += Split(splitList.size + 1, atBoundary - splitStartMovingMs, config.splitLengthM)
            splitStartMovingMs = atBoundary
            nextSplitM += config.splitLengthM
        }
        // keep parameters used for documentation/debugging symmetry
        check(toMs >= fromMs)
    }

    /** Smooths altitude and counts climbs/descents only when they exceed a threshold (kills GPS noise). */
    private fun trackAltitudeIfRecording(p: TrackPoint) {
        if (state == RecorderState.MANUAL_PAUSED) return
        val alt = p.altitudeM ?: return
        altitudes.addLast(alt)
        if (altitudes.size > ALTITUDE_WINDOW) altitudes.removeFirst()
        val smoothed = altitudes.average()
        if (altitudeAnchor.isNaN()) {
            altitudeAnchor = smoothed
            return
        }
        val diff = smoothed - altitudeAnchor
        if (abs(diff) >= config.elevationThresholdM) {
            if (diff > 0) elevationGainM += diff else elevationLossM += -diff
            altitudeAnchor = smoothed
        }
    }

    private companion object {
        const val ALTITUDE_WINDOW = 5
        const val DISPLAY_WINDOW_MS = 10_000L
        const val MAX_SPEED_WINDOW_MS = 15_000L
        const val RECENT_WINDOW_MS = MAX_SPEED_WINDOW_MS
    }
}
