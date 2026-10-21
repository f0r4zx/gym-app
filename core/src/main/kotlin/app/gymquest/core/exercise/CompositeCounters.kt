package app.gymquest.core.exercise

import app.gymquest.core.pose.PoseFrame

/**
 * Combines two independent counters (one per arm or leg). Reps that complete within
 * [mergeWindowMs] of each other count once, so a two-arm curl is one rep while alternating
 * curls count every arm. With a window of 0 every event counts (mountain climbers, high knees).
 */
class MergedCounter(
    override val type: ExerciseType,
    private val left: RepCounter,
    private val right: RepCounter,
    private val mergeWindowMs: Long,
) : RepCounter {

    override var count = 0
        private set
    private var lastRepMs = Long.MIN_VALUE / 2

    override fun update(frame: PoseFrame): CounterUpdate {
        val l = left.update(frame)
        val r = right.update(frame)
        val now = frame.timestampMs

        var best: RepEvent? = null
        for (candidate in listOf(l.rep, r.rep)) {
            if (candidate == null) continue
            if (best == null || candidate.quality > best.quality) best = candidate
        }

        var emitted: RepEvent? = null
        if (best != null) {
            val isDuplicate = mergeWindowMs > 0 && now - lastRepMs <= mergeWindowMs
            if (!isDuplicate) {
                count++
                lastRepMs = now
                emitted = best.copy(count = count)
            }
        }

        val progress = listOfNotNull(l.progress, r.progress).maxOrNull()
        return CounterUpdate(
            rep = emitted,
            partial = if (emitted == null) l.partial ?: r.partial else null,
            liveIssues = l.liveIssues + r.liveIssues,
            progress = progress,
            phase = if (l.phase.ordinal >= r.phase.ordinal) l.phase else r.phase,
        )
    }

    override fun reset() {
        left.reset()
        right.reset()
        count = 0
        lastRepMs = Long.MIN_VALUE / 2
    }
}

/**
 * Counts how long a position is held. [measure] returns form quality 0..1 (null = not visible);
 * time only accumulates while quality is at least [goodEnough]. Emits one event per second.
 */
class HoldCounter(
    override val type: ExerciseType,
    private val goodEnough: Double = 0.5,
    private val graceMs: Long = 600,
    private val measure: (PoseFrame) -> Reading?,
) : RepCounter {

    private var holdMs = 0L
    private var lastTs = -1L
    private var lastGoodMs = Long.MIN_VALUE / 2
    private var emittedSeconds = 0

    override val count: Int get() = emittedSeconds

    val heldMs: Long get() = holdMs

    override fun update(frame: PoseFrame): CounterUpdate {
        val now = frame.timestampMs
        val dt = if (lastTs < 0) 0L else (now - lastTs).coerceIn(0L, 250L)
        lastTs = now

        val reading = measure(frame)
            ?: return CounterUpdate(phase = Phase.LOST)

        val good = reading.progress >= goodEnough
        if (good) lastGoodMs = now
        // Tolerate brief wobbles without losing the hold, but only count real good-form time.
        if (good) holdMs += dt

        var rep: RepEvent? = null
        val seconds = (holdMs / 1000).toInt()
        if (seconds > emittedSeconds) {
            emittedSeconds = seconds
            rep = RepEvent(
                count = seconds,
                timestampMs = now,
                quality = reading.progress.coerceIn(0.2, 1.0),
                durationMs = 1000,
                depth = reading.progress,
                issues = reading.issues,
                isHoldTick = true,
            )
        }

        val holding = good || now - lastGoodMs <= graceMs
        return CounterUpdate(
            rep = rep,
            liveIssues = reading.issues,
            progress = reading.progress,
            phase = if (holding) Phase.AT_PEAK else Phase.READY,
        )
    }

    override fun reset() {
        holdMs = 0
        lastTs = -1
        lastGoodMs = Long.MIN_VALUE / 2
        emittedSeconds = 0
    }
}

/**
 * Jump-rope counter. Rope jumps are only a few centimetres, so instead of thresholds on absolute
 * position it runs a zig-zag peak detector on the trunk height. The required swing
 * ([prominence]) is a fraction of the torso length, tracked slowly so frame noise in the scale
 * does not leak into the signal.
 */
class JumpRopeCounter(
    private val prominence: Double = 0.07,
    private val minGapMs: Long = 220,
) : RepCounter {
    override val type = ExerciseType.JUMP_ROPE

    override var count = 0
        private set

    private var rising = true
    private var extreme = Double.NaN
    private var lastCountMs = Long.MIN_VALUE / 2
    private var scale = Double.NaN

    override fun update(frame: PoseFrame): CounterUpdate {
        val s = PoseMath.shoulderMid(frame)
        val h = PoseMath.hipMid(frame)
        val torso = PoseMath.torsoLength(frame)
        if (s == null || h == null || torso == null) return CounterUpdate(phase = Phase.LOST)

        scale = if (scale.isNaN()) torso else scale + (torso - scale) * SCALE_SMOOTHING
        val threshold = prominence * scale

        // Height of the trunk, positive = higher.
        val height = -(s.y + h.y) / 2
        val now = frame.timestampMs
        var rep: RepEvent? = null

        if (extreme.isNaN()) extreme = height
        if (rising) {
            if (height > extreme) {
                extreme = height
            } else if (extreme - height >= threshold) {
                // Apex confirmed: that was one jump.
                if (now - lastCountMs >= minGapMs) {
                    count++
                    lastCountMs = now
                    rep = RepEvent(count, now, 1.0, 0, 1.0, emptySet())
                }
                rising = false
                extreme = height
            }
        } else {
            if (height < extreme) {
                extreme = height
            } else if (height - extreme >= threshold) {
                rising = true
                extreme = height
            }
        }

        return CounterUpdate(rep = rep, progress = null, phase = Phase.READY)
    }

    override fun reset() {
        count = 0
        rising = true
        extreme = Double.NaN
        lastCountMs = Long.MIN_VALUE / 2
        scale = Double.NaN
    }

    private companion object {
        const val SCALE_SMOOTHING = 0.02
    }
}
