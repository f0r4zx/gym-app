package app.gymquest.tracking

import app.gymquest.core.tracking.ActivitySummary
import app.gymquest.core.tracking.ActivityType
import app.gymquest.core.tracking.GeoPoint
import app.gymquest.core.tracking.RecorderState
import app.gymquest.core.tracking.Split
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A snapshot of the activity being recorded; the service writes it, the map screen reads it. */
data class LiveTrack(
    val active: Boolean = false,
    val type: ActivityType = ActivityType.WALK,
    val state: RecorderState = RecorderState.WAITING_FOR_FIX,
    val startedAtMs: Long = 0,
    val distanceM: Double = 0.0,
    val movingMs: Long = 0,
    val speedMps: Double = 0.0,
    val maxSpeedMps: Double = 0.0,
    val gainM: Double = 0.0,
    val lossM: Double = 0.0,
    val calories: Int = 0,
    val lastPoint: GeoPoint? = null,
    val headingDeg: Double? = null,
    val accuracyM: Double? = null,
    val track: List<GeoPoint> = emptyList(),
    val splits: List<Split> = emptyList(),
    /** Changes whenever [track] does, so the map knows when to redraw the line. */
    val trackVersion: Int = 0,
)

/** A recording that has just ended and is waiting to be saved by the UI. */
data class FinishedTrack(
    val summary: ActivitySummary,
    val startedAtMs: Long,
    val polyline: String,
    val track: List<GeoPoint>,
)

/** Process-wide holder, so the screen and the foreground service share state without binding. */
object TrackingState {
    private val _live = MutableStateFlow(LiveTrack())
    val live: StateFlow<LiveTrack> = _live.asStateFlow()

    private val _finished = MutableStateFlow<FinishedTrack?>(null)
    val finished: StateFlow<FinishedTrack?> = _finished.asStateFlow()

    fun publish(track: LiveTrack) {
        _live.value = track
    }

    fun finish(result: FinishedTrack) {
        _finished.value = result
        _live.value = LiveTrack()
    }

    fun consumeFinished() {
        _finished.value = null
    }
}
