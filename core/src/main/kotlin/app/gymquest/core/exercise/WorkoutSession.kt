package app.gymquest.core.exercise

import app.gymquest.core.pose.PoseFrame
import app.gymquest.core.pose.PoseSmoother
import app.gymquest.core.pose.derolled

enum class SessionState { SETUP, COUNTDOWN, ACTIVE, PAUSED }

data class SessionUpdate(
    val state: SessionState,
    val framing: FramingReport,
    /** Seconds left in the countdown (3, 2, 1) while [state] is COUNTDOWN. */
    val countdown: Int? = null,
    val counter: CounterUpdate? = null,
    /** A rep or hold-second completed on this frame. */
    val rep: RepEvent? = null,
    /** Consecutive good-quality reps so far. */
    val combo: Int = 0,
)

/**
 * One exercise set driven by the camera: setup check → countdown → counting, with a pause when
 * the person leaves the frame. Feed it every [PoseFrame]; it handles de-rolling and smoothing.
 */
class WorkoutSession(
    val type: ExerciseType,
    strictness: Strictness = Strictness.NORMAL,
    private val countdownMs: Long = 3_000,
    private val steadyFramesNeeded: Int = 20,
    private val pauseAfterLostMs: Long = 3_000,
    private val goodComboQuality: Double = 0.7,
) {
    private val framing = FramingAnalyzer(type)
    private val counter = RepCounters.create(type, strictness)
    private val smoother: PoseSmoother = Smoothers.forExercise(type)

    var state = SessionState.SETUP
        private set

    val count: Int get() = counter.count

    var combo = 0
        private set
    var bestCombo = 0
        private set

    private val reps = mutableListOf<RepEvent>()
    val events: List<RepEvent> get() = reps

    private var steadyFrames = 0
    private var countdownStartMs = 0L
    private var lastReadyMs = 0L
    private var activeStartMs = -1L
    private var lastFrameMs = 0L
    var activeMs = 0L
        private set

    fun onFrame(raw: PoseFrame, rollDegrees: Double = 0.0): SessionUpdate {
        val now = raw.timestampMs
        val frame = raw.derolled(rollDegrees)
        val report = framing.analyze(frame)
        if (report.ready) lastReadyMs = now

        when (state) {
            SessionState.SETUP, SessionState.PAUSED -> {
                steadyFrames = if (report.ready) steadyFrames + 1 else 0
                if (steadyFrames >= steadyFramesNeeded) {
                    // First start counts down; resuming after a pause goes straight back in.
                    if (state == SessionState.PAUSED) {
                        state = SessionState.ACTIVE
                    } else {
                        state = SessionState.COUNTDOWN
                        countdownStartMs = now
                    }
                    steadyFrames = 0
                }
                return SessionUpdate(state, report, combo = combo)
            }

            SessionState.COUNTDOWN -> {
                if (!report.ready && now - lastReadyMs > COUNTDOWN_GRACE_MS) {
                    state = SessionState.SETUP
                    steadyFrames = 0
                    return SessionUpdate(state, report, combo = combo)
                }
                val elapsed = now - countdownStartMs
                if (elapsed >= countdownMs) {
                    state = SessionState.ACTIVE
                    activeStartMs = now
                    lastFrameMs = now
                    return SessionUpdate(state, report, combo = combo)
                }
                val remaining = ((countdownMs - elapsed + 999) / 1000).toInt()
                return SessionUpdate(state, report, countdown = remaining, combo = combo)
            }

            SessionState.ACTIVE -> {
                activeMs += (now - lastFrameMs).coerceIn(0, 250)
                lastFrameMs = now

                val smoothed = smoother.smooth(frame)
                val update = counter.update(smoothed)
                val rep = update.rep
                if (rep != null) {
                    reps += rep
                    if (rep.quality >= goodComboQuality) {
                        combo++
                        if (combo > bestCombo) bestCombo = combo
                    } else {
                        combo = 0
                    }
                } else if (update.partial != null) {
                    combo = 0
                }

                if (!report.ready && now - lastReadyMs > pauseAfterLostMs) {
                    state = SessionState.PAUSED
                    steadyFrames = 0
                }
                return SessionUpdate(state, report, counter = update, rep = rep, combo = combo)
            }
        }
    }

    fun averageQuality(): Double = if (reps.isEmpty()) 0.0 else reps.map { it.quality }.average()

    fun caloriesBurned(weightKg: Double): Double = type.caloriesFor(count, weightKg)

    private companion object {
        /** A person can flicker out of detection for a moment during the countdown. */
        const val COUNTDOWN_GRACE_MS = 800L
    }
}
