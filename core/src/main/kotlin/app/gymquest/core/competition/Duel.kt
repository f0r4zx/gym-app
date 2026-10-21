package app.gymquest.core.competition

import kotlin.math.max
import kotlin.math.sqrt

enum class DuelMode { TIMED, RACE_TO_TARGET }

/** What one player submits after doing their set in front of the camera. */
data class DuelSubmission(
    val userId: String,
    /** Milliseconds since the set started, one per counted repetition. */
    val repTimesMs: List<Long>,
    /** Quality 0..1 of each repetition, same order. */
    val qualities: List<Double>,
    val ageYears: Int,
) {
    init {
        require(repTimesMs.size == qualities.size) { "Una calidad por repetición" }
    }

    val reps: Int get() = repTimesMs.size
}

data class Verification(val valid: Boolean, val reasons: List<String>)

/**
 * Checks that a submitted set is humanly plausible. The camera already counts honestly, but a
 * submission travels through a phone the cheater controls, so the server re-checks the timeline:
 * impossible speeds, metronome-perfect timing (a looped video) and sloppy form are all rejected.
 */
object DuelVerifier {
    fun verify(discipline: Discipline, s: DuelSubmission, maxDurationMs: Long): Verification {
        val reasons = mutableListOf<String>()
        val t = s.repTimesMs
        if (t.isEmpty()) return Verification(true, emptyList())

        if (t.zipWithNext().any { (a, b) -> b <= a }) reasons += "Las repeticiones no están en orden."
        if (t.first() < 0) reasons += "Hay repeticiones antes de empezar."
        if (t.last() > maxDurationMs + 2_000) reasons += "Hay repeticiones fuera del tiempo del duelo."
        if (s.qualities.any { it !in 0.0..1.0 }) reasons += "Calidad fuera de rango."

        val intervals = t.zipWithNext { a, b -> (b - a).toDouble() }
        val min = discipline.minRepIntervalMs
        if (intervals.isNotEmpty() && min > 0) {
            val tooFast = intervals.count { it < min }
            if (tooFast > max(1, (intervals.size * 0.03).toInt())) reasons += "Ritmo imposible para ${discipline.label.lowercase()}."
            val median = intervals.sorted()[intervals.size / 2]
            if (median < min * 1.2) reasons += "Ritmo sostenido demasiado rápido."
        }
        if (intervals.size >= 12) {
            val mean = intervals.average()
            val std = sqrt(intervals.sumOf { (it - mean) * (it - mean) } / intervals.size)
            // People vary by a few percent rep to rep; a metronome (looped video, script) does not.
            if (mean > 0 && std / mean < 0.02) reasons += "Ritmo sospechosamente uniforme."
        }
        if (s.qualities.isNotEmpty() && s.qualities.average() < 0.45) reasons += "Forma demasiado pobre para contar."
        return Verification(reasons.isEmpty(), reasons)
    }
}

object Handicap {
    /**
     * Age fairness multiplier: people past 35 get a small bonus, and teenagers a smaller one, so a
     * 55-year-old can fairly face a 25-year-old. Capped so it can never decide a duel alone.
     */
    fun factor(age: Int): Double = when {
        age < 16 -> 1.0 + (16 - age).coerceAtLeast(0) * 0.04
        age > 35 -> (1.0 + (age - 35) * 0.007).coerceAtMost(1.35)
        else -> 1.0
    }
}

sealed interface DuelOutcome {
    data class Winner(val userId: String, val scoreA: Double, val scoreB: Double) : DuelOutcome
    data object Draw : DuelOutcome
    /** The other player never submitted (or was caught cheating) before the deadline. */
    data class Forfeit(val winnerId: String, val reason: String) : DuelOutcome
    data object Cancelled : DuelOutcome
    data object Pending : DuelOutcome
}

class Duel(
    val id: String,
    val challengerId: String,
    val opponentId: String,
    val discipline: Discipline,
    val mode: DuelMode,
    /** TIMED: length of the set in ms. RACE: number of reps to reach. */
    val limit: Long,
    val stake: Long,
    val createdMs: Long,
    val expiresMs: Long,
    val useHandicap: Boolean = true,
) {
    init {
        require(discipline.isDuelable) { "${discipline.label} no es un duelo con cámara" }
        require(challengerId != opponentId) { "No puedes retarte a ti mismo" }
    }

    private val submissions = mutableMapOf<String, DuelSubmission>()
    private val rejected = mutableMapOf<String, List<String>>()

    val bothSubmitted: Boolean get() = submissions.size + rejected.size >= 2

    fun submit(s: DuelSubmission, nowMs: Long): Verification {
        require(s.userId == challengerId || s.userId == opponentId) { "No participas en este duelo" }
        require(nowMs <= expiresMs) { "El duelo ya expiró" }
        require(s.userId !in submissions && s.userId !in rejected) { "Ya enviaste tu resultado" }
        val maxDuration = if (mode == DuelMode.TIMED) limit else Long.MAX_VALUE / 4
        val v = DuelVerifier.verify(discipline, s, maxDuration)
        if (v.valid) submissions[s.userId] = s else rejected[s.userId] = v.reasons
        return v
    }

    private fun score(s: DuelSubmission): Double {
        val counted = when (mode) {
            DuelMode.TIMED -> s.repTimesMs.indices.filter { s.repTimesMs[it] <= limit }
            DuelMode.RACE_TO_TARGET -> s.repTimesMs.indices.take(limit.toInt())
        }
        val base = counted.sumOf { s.qualities[it] }
        return if (useHandicap) base * Handicap.factor(s.ageYears) else base
    }

    /** Time at which a player reached the target, or null if they did not. */
    private fun finishTime(s: DuelSubmission): Long? = s.repTimesMs.getOrNull(limit.toInt() - 1)

    fun decide(nowMs: Long): DuelOutcome {
        val a = submissions[challengerId]
        val b = submissions[opponentId]
        val over = nowMs > expiresMs || bothSubmitted
        if (!over) return DuelOutcome.Pending

        return when {
            a == null && b == null -> DuelOutcome.Cancelled
            a == null -> DuelOutcome.Forfeit(opponentId, if (challengerId in rejected) "Resultado inválido del retador." else "El retador no completó el duelo.")
            b == null -> DuelOutcome.Forfeit(challengerId, if (opponentId in rejected) "Resultado inválido del oponente." else "El oponente no completó el duelo.")
            else -> when (mode) {
                DuelMode.TIMED -> {
                    val sa = score(a)
                    val sb = score(b)
                    when {
                        kotlin.math.abs(sa - sb) < 1e-9 -> if (a.reps != b.reps) {
                            DuelOutcome.Winner(if (a.reps > b.reps) challengerId else opponentId, sa, sb)
                        } else DuelOutcome.Draw
                        sa > sb -> DuelOutcome.Winner(challengerId, sa, sb)
                        else -> DuelOutcome.Winner(opponentId, sa, sb)
                    }
                }
                DuelMode.RACE_TO_TARGET -> {
                    val fa = finishTime(a)
                    val fb = finishTime(b)
                    when {
                        fa == null && fb == null -> DuelOutcome.Draw
                        fa == null -> DuelOutcome.Winner(opponentId, score(a), score(b))
                        fb == null -> DuelOutcome.Winner(challengerId, score(a), score(b))
                        fa < fb -> DuelOutcome.Winner(challengerId, fa.toDouble(), fb.toDouble())
                        fb < fa -> DuelOutcome.Winner(opponentId, fa.toDouble(), fb.toDouble())
                        else -> DuelOutcome.Draw
                    }
                }
            }
        }
    }

    /** Coins paid to each player once the duel is decided: winner takes both stakes minus a 5% fee. */
    fun payouts(outcome: DuelOutcome): Map<String, Long> {
        if (stake <= 0) return emptyMap()
        val pot = stake * 2
        val prize = pot - pot * FEE_PERCENT / 100
        return when (outcome) {
            is DuelOutcome.Winner -> mapOf(outcome.userId to prize)
            is DuelOutcome.Forfeit -> mapOf(outcome.winnerId to prize)
            DuelOutcome.Draw, DuelOutcome.Cancelled -> mapOf(challengerId to stake, opponentId to stake)
            DuelOutcome.Pending -> emptyMap()
        }
    }

    companion object {
        const val FEE_PERCENT = 5L
    }
}
