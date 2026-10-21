package app.gymquest.core.music

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Finds the tempo of a song from its audio, entirely on the phone. */
object BpmEstimator {
    const val MIN_BPM = 75.0
    const val MAX_BPM = 170.0
    private const val FRAME = 1024
    private const val HOP = 256

    /**
     * Energy-change envelope of mono PCM [samples]: a spike wherever the music "hits". Returns the
     * envelope and its sample rate in Hz.
     */
    fun onsetEnvelope(samples: DoubleArray, sampleRate: Int): Pair<DoubleArray, Double> {
        if (samples.size < FRAME * 4) return DoubleArray(0) to 0.0
        val frames = (samples.size - FRAME) / HOP
        val energy = DoubleArray(frames)
        for (f in 0 until frames) {
            var sum = 0.0
            val start = f * HOP
            for (i in 0 until FRAME) {
                val s = samples[start + i]
                sum += s * s
            }
            energy[f] = sqrt(sum / FRAME)
        }
        // Half-wave rectified difference of the (log-compressed) energy.
        val flux = DoubleArray(frames)
        for (f in 1 until frames) flux[f] = max(0.0, ln1p(energy[f] * 50) - ln1p(energy[f - 1] * 50))
        return flux to sampleRate.toDouble() / HOP
    }

    private fun ln1p(x: Double) = Math.log1p(x)

    /**
     * Tempo in BPM, folded into 75–170 so a 60 BPM song reads as 120. Null if there is no clear beat.
     *
     * The envelope is smoothed first (its spikes are only a frame wide, so an integer lag would
     * otherwise miss them by half a frame), then autocorrelated. Each candidate period is scored by how
     * well it repeats at 1x, 2x and 4x: only powers of two, so a steady 128 BPM beat cannot be mistaken
     * for the 85 BPM that a "three half-beats" grid would also produce.
     */
    fun fromEnvelope(envelope: DoubleArray, rateHz: Double): Double? {
        if (envelope.size < rateHz * 6) return null
        val smooth = smooth(envelope, (rateHz * 0.03).toInt().coerceAtLeast(1))
        val mean = smooth.average()
        val x = DoubleArray(smooth.size) { smooth[it] - mean }
        val variance = x.sumOf { it * it } / x.size
        if (variance < 1e-12) return null

        val minLag = (rateHz * 60.0 / MAX_BPM).toInt().coerceAtLeast(2)
        val maxLag = (rateHz * 60.0 / MIN_BPM).toInt() + 1
        val top = maxLag * 4 + 2
        val acf = DoubleArray(top + 2)
        for (lag in 1..top) {
            if (lag >= x.size / 2) break
            var sum = 0.0
            for (i in 0 until x.size - lag) sum += x[i] * x[i + lag]
            acf[lag] = sum / (x.size - lag) / variance // unbiased: long lags are not penalised
        }

        var bestLag = -1
        var best = 0.0
        for (lag in minLag..maxLag) {
            if (acf[lag] < acf[lag - 1] || acf[lag] < acf[lag + 1]) continue // only peaks
            val score = acf[lag] + 0.5 * acf[lag * 2] + 0.25 * acf[(lag * 4).coerceAtMost(top)]
            if (score > best) {
                best = score
                bestLag = lag
            }
        }
        if (bestLag < 0 || acf[bestLag] < 0.1) return null

        // Parabolic interpolation around the peak for sub-lag precision.
        val a = acf[bestLag - 1]
        val b = acf[bestLag]
        val c = acf[bestLag + 1]
        val denom = a - 2 * b + c
        val shift = if (abs(denom) > 1e-12) 0.5 * (a - c) / denom else 0.0
        var bpm = rateHz * 60.0 / (bestLag + shift)
        while (bpm < MIN_BPM) bpm *= 2
        while (bpm > MAX_BPM) bpm /= 2
        return bpm
    }

    private fun smooth(x: DoubleArray, radius: Int): DoubleArray {
        val out = DoubleArray(x.size)
        for (i in x.indices) {
            var sum = 0.0
            var weight = 0.0
            for (k in -radius..radius) {
                val j = i + k
                if (j !in x.indices) continue
                val w = (radius + 1 - abs(k)).toDouble() // triangular window
                sum += x[j] * w
                weight += w
            }
            out[i] = sum / weight
        }
        return out
    }

    fun fromPcm(samples: DoubleArray, sampleRate: Int): Double? {
        val (env, rate) = onsetEnvelope(samples, sampleRate)
        return if (env.isEmpty()) null else fromEnvelope(env, rate)
    }
}

/**
 * Your running or walking cadence in steps per minute, from the phone's step sensor timestamps.
 * Uses the median step interval so a missed or doubled step does not throw it off.
 */
class CadenceTracker(private val window: Int = 12) {
    private val steps = ArrayDeque<Long>()

    /** Steps per minute, or null until there are enough steps for a trustworthy reading. */
    var spm: Double? = null
        private set

    var isStable: Boolean = false
        private set

    fun onStep(timeMs: Long) {
        val last = steps.lastOrNull()
        if (last != null) {
            val gap = timeMs - last
            if (gap <= 0) return
            if (gap > RESET_GAP_MS) clear() // stopped or paused
        }
        steps.addLast(timeMs)
        while (steps.size > window + 1) steps.removeFirst()
        recompute()
    }

    fun reset() = clear()

    private fun clear() {
        steps.clear()
        spm = null
        isStable = false
    }

    private fun recompute() {
        if (steps.size < MIN_STEPS) {
            spm = null
            isStable = false
            return
        }
        val intervals = steps.zipWithNext { a, b -> (b - a).toDouble() }.filter { it in MIN_INTERVAL_MS..MAX_INTERVAL_MS }
        if (intervals.size < MIN_STEPS - 1) {
            spm = null
            isStable = false
            return
        }
        val sorted = intervals.sorted()
        val median = sorted[sorted.size / 2]
        spm = 60_000.0 / median
        val deviation = intervals.sumOf { abs(it - median) } / intervals.size / median
        isStable = deviation < 0.08
    }

    private companion object {
        const val MIN_STEPS = 5
        const val RESET_GAP_MS = 2_000L
        const val MIN_INTERVAL_MS = 250.0
        const val MAX_INTERVAL_MS = 1_500.0
    }
}

object CadenceTarget {
    /** A sensible cadence for [speedMps] when the step sensor is not available. */
    fun suggest(running: Boolean, speedMps: Double): Double {
        val kmh = speedMps * 3.6
        return if (running) (150.0 + (kmh - 8.0) * 3.0).coerceIn(145.0, 190.0)
        else (100.0 + kmh * 2.5).coerceIn(90.0, 130.0)
    }

    fun roundForSync(spm: Double): Int = (spm / 2.0).roundToInt() * 2

}
