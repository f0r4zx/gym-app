package app.gymquest.core.pose

import kotlin.math.PI
import kotlin.math.abs

/**
 * One Euro filter (Casiez et al. 2012): heavy smoothing when the signal is slow (kills jitter while
 * you hold a position) and light smoothing when it moves fast (no visible lag during the rep).
 */
class OneEuroFilter(
    private val minCutoff: Double = 1.5,
    private val beta: Double = 0.8,
    private val dCutoff: Double = 1.0,
) {
    private var prevX = 0.0
    private var prevDx = 0.0
    private var prevT = 0L
    private var initialised = false

    fun filter(x: Double, timestampMs: Long): Double {
        if (!initialised) {
            initialised = true
            prevX = x
            prevDx = 0.0
            prevT = timestampMs
            return x
        }
        val dt = ((timestampMs - prevT).coerceAtLeast(1)) / 1000.0
        prevT = timestampMs
        val dx = (x - prevX) / dt
        val edx = lowPass(dx, prevDx, alpha(dCutoff, dt))
        prevDx = edx
        val cutoff = minCutoff + beta * abs(edx)
        val result = lowPass(x, prevX, alpha(cutoff, dt))
        prevX = result
        return result
    }

    fun reset() {
        initialised = false
    }

    private fun alpha(cutoff: Double, dt: Double): Double {
        val tau = 1.0 / (2 * PI * cutoff)
        return 1.0 / (1.0 + tau / dt)
    }

    private fun lowPass(x: Double, prev: Double, a: Double) = a * x + (1 - a) * prev
}

/**
 * Smooths every landmark of a stream of [PoseFrame]s. If a joint disappears for longer than
 * [dropoutMs] its filter is reset so stale values never leak into a new detection.
 */
class PoseSmoother(
    private val minCutoff: Double = 1.5,
    private val beta: Double = 0.8,
    private val dropoutMs: Long = 400,
) {
    private class Filters(minCutoff: Double, beta: Double) {
        val x = OneEuroFilter(minCutoff, beta)
        val y = OneEuroFilter(minCutoff, beta)
        val z = OneEuroFilter(minCutoff, beta)
        val lk = OneEuroFilter(minCutoff, 0.0)
        var lastSeen = Long.MIN_VALUE
    }

    private val filters = Array(Joint.COUNT) { Filters(minCutoff, beta) }

    fun smooth(frame: PoseFrame): PoseFrame {
        val t = frame.timestampMs
        return frame.mapLandmarks { j, l ->
            val f = filters[j.index]
            if (f.lastSeen != Long.MIN_VALUE && t - f.lastSeen > dropoutMs) {
                f.x.reset(); f.y.reset(); f.z.reset(); f.lk.reset()
            }
            f.lastSeen = t
            Landmark(
                x = f.x.filter(l.x, t),
                y = f.y.filter(l.y, t),
                z = f.z.filter(l.z, t),
                likelihood = f.lk.filter(l.likelihood, t).coerceIn(0.0, 1.0),
            )
        }
    }

    fun reset() {
        for (f in filters) {
            f.x.reset(); f.y.reset(); f.z.reset(); f.lk.reset()
            f.lastSeen = Long.MIN_VALUE
        }
    }
}
