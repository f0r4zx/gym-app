package app.gymquest.core.exercise

import app.gymquest.core.pose.PoseFrame

/**
 * Tunables of the rep state machine. All thresholds are on a normalised 0..1 "progress" signal
 * where 0 is the start position and 1 is the end of the range of motion.
 */
data class RepConfig(
    /** Progress that must be reached for the rep to count. */
    val peak: Double = 0.75,
    /** Progress below which a rep that reached [peak] is considered finished. */
    val rest: Double = 0.30,
    /** Reaching this without reaching [peak] raises a "not deep enough" hint. */
    val partial: Double = 0.45,
    /** Anything faster than this is jitter, not a rep. */
    val minRepMs: Long = 350,
    /** A movement that takes longer than this is abandoned. */
    val maxRepMs: Long = 12_000,
    /** Tempo window considered "controlled". */
    val idealMinMs: Long = 900,
    /** After this long without a readable body mid-rep, the rep is dropped. */
    val lostGraceMs: Long = 1_200,
    val peakIssue: FormIssue = FormIssue.NOT_DEEP_ENOUGH,
)

/** Per-frame measurement produced by an exercise's geometry. */
class Reading(
    val progress: Double,
    /** Posture problems observed in this frame. */
    val issues: Set<FormIssue> = emptySet(),
)

/**
 * Generic repetition counter: a hysteresis state machine over a 0..1 progress signal. Exercise
 * specific subclasses only describe how to turn a [PoseFrame] into a [Reading].
 */
class ProgressRepCounter(
    final override val type: ExerciseType,
    private val config: RepConfig,
    strictness: Strictness = Strictness.NORMAL,
    private val measure: (PoseFrame) -> Reading?,
) : RepCounter {

    private val peak = (config.peak + strictness.peakShift).coerceIn(0.4, 0.95)
    private val partial = minOf(config.partial, peak - 0.1)

    override var count = 0
        private set

    var phase = Phase.LOST
        private set

    private var repStartMs = 0L
    private var maxProgress = 0.0
    private var lastSeenMs = 0L

    /** Posture problems seen at the deepest point reached so far in the current rep. */
    private var deepestIssues: Set<FormIssue> = emptySet()

    override fun update(frame: PoseFrame): CounterUpdate {
        val now = frame.timestampMs
        val reading = measure(frame)

        if (reading == null) {
            if (phase != Phase.LOST && phase != Phase.READY && now - lastSeenMs > config.lostGraceMs) {
                abandonRep()
            }
            if (now - lastSeenMs > config.lostGraceMs) phase = Phase.LOST
            return CounterUpdate(phase = phase)
        }

        lastSeenMs = now
        val p = reading.progress.coerceIn(0.0, 1.0)
        if (phase == Phase.LOST) phase = Phase.READY

        var rep: RepEvent? = null
        var partialRep: PartialRep? = null

        when (phase) {
            Phase.READY -> if (p > config.rest) startRep(now, p)

            Phase.MOVING, Phase.AT_PEAK, Phase.RETURNING -> {
                if (p > maxProgress) {
                    maxProgress = p
                    // Posture is judged where it matters: at the bottom/top of the movement.
                    if (p >= peak) deepestIssues = reading.issues
                }
                if (p >= peak) {
                    phase = Phase.AT_PEAK
                } else if (phase == Phase.AT_PEAK && p < peak - 0.05) {
                    phase = Phase.RETURNING
                }

                val duration = now - repStartMs
                if (p <= config.rest) {
                    if (maxProgress >= peak) {
                        if (duration >= config.minRepMs) {
                            count++
                            rep = buildEvent(now, duration)
                        }
                    } else if (maxProgress >= partial && duration >= config.minRepMs) {
                        partialRep = PartialRep(now, maxProgress, config.peakIssue)
                    }
                    resetRep()
                    phase = Phase.READY
                } else if (duration > config.maxRepMs) {
                    abandonRep()
                }
            }

            Phase.LOST -> Unit
        }

        return CounterUpdate(
            rep = rep,
            partial = partialRep,
            liveIssues = reading.issues,
            progress = p,
            phase = phase,
        )
    }

    private fun startRep(now: Long, p: Double) {
        phase = Phase.MOVING
        repStartMs = now
        maxProgress = p
        deepestIssues = emptySet()
    }

    private fun buildEvent(now: Long, duration: Long): RepEvent {
        val issues = deepestIssues.toMutableSet()
        if (duration < config.idealMinMs) issues += FormIssue.TOO_FAST

        val depthScore = 0.6 + 0.4 * ((maxProgress - peak) / (1.0 - peak)).coerceIn(0.0, 1.0)
        val tempoScore = (duration.toDouble() / config.idealMinMs).coerceIn(0.5, 1.0)
        val posturePenalty = (issues - FormIssue.TOO_FAST).size * 0.15
        val quality = (depthScore * tempoScore - posturePenalty).coerceIn(0.2, 1.0)

        return RepEvent(
            count = count,
            timestampMs = now,
            quality = quality,
            durationMs = duration,
            depth = maxProgress,
            issues = issues,
        )
    }

    private fun abandonRep() {
        resetRep()
        phase = Phase.READY
    }

    private fun resetRep() {
        maxProgress = 0.0
        deepestIssues = emptySet()
    }

    override fun reset() {
        count = 0
        phase = Phase.LOST
        resetRep()
        lastSeenMs = 0L
    }
}
