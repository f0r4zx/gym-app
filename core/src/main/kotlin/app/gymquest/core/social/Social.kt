package app.gymquest.core.social

import app.gymquest.core.competition.Discipline
import app.gymquest.core.exercise.ExerciseType
import app.gymquest.core.game.BossTier
import app.gymquest.core.tracking.ActivityType
import kotlin.math.roundToInt

/** The single "weekly points" currency that friends and guilds are ranked by. */
object WeeklyPoints {
    fun reps(type: ExerciseType, reps: Int, quality: Double): Double = Discipline.points(type, reps, quality)

    fun distance(activity: ActivityType, km: Double): Double = km * when (activity) {
        ActivityType.WALK -> 10.0
        ActivityType.RUN -> 15.0
        ActivityType.HIKE -> 14.0
        ActivityType.RIDE -> 4.0
    }

    fun boss(tier: BossTier, flawless: Boolean): Double = when (tier) {
        BossTier.EASY -> 50.0
        BossTier.MEDIUM -> 100.0
        BossTier.HARD -> 200.0
        BossTier.EPIC -> 400.0
    } + if (flawless) 25.0 else 0.0

    fun territory(cells: Int): Double = cells.toDouble()

    /** A small bonus for showing up every day, so consistency can beat a single heroic day. */
    fun streakBonus(streakDays: Int): Double = (streakDays.coerceAtMost(30) * 2).toDouble()
}

data class RankRow(val userId: String, val rank: Int, val points: Double, val previousRank: Int?) {
    /** Positions gained since the last snapshot (+2 = moved up two places); null when new. */
    val change: Int? get() = previousRank?.let { it - rank }
}

object Leaderboards {
    /**
     * Standard competition ranking: equal points share a rank (1, 2, 2, 4). [previous] is the last
     * snapshot of ranks, used to show who moved up or down.
     */
    fun rank(points: Map<String, Double>, previous: Map<String, Int> = emptyMap()): List<RankRow> {
        val sorted = points.entries.sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key })
        val rows = mutableListOf<RankRow>()
        var lastPoints = Double.NaN
        var lastRank = 0
        sorted.forEachIndexed { i, (user, pts) ->
            val rank = if (pts == lastPoints) lastRank else i + 1
            lastPoints = pts
            lastRank = rank
            rows += RankRow(user, rank, pts, previous[user])
        }
        return rows
    }

    fun snapshot(rows: List<RankRow>): Map<String, Int> = rows.associate { it.userId to it.rank }

    /** Who overtook [userId] between two snapshots. */
    fun overtakenBy(userId: String, before: Map<String, Int>, after: Map<String, Int>): List<String> {
        val myBefore = before[userId] ?: return emptyList()
        val myAfter = after[userId] ?: return emptyList()
        return after.filter { (other, rank) -> other != userId && (before[other] ?: Int.MAX_VALUE) > myBefore && rank < myAfter }.keys.toList()
    }
}

/** A ranking by streak length, the "who is most consistent" board. */
object StreakBoard {
    data class Entry(val userId: String, val name: String, val current: Int, val longest: Int)

    fun rank(entries: List<Entry>): List<Entry> =
        entries.sortedWith(compareByDescending<Entry> { it.current }.thenByDescending { it.longest }.thenBy { it.name })
}

enum class ChallengeMetric { POINTS, KM, CELLS, STREAK_DAYS, REPS }

data class Challenge(
    val id: String,
    val name: String,
    val metric: ChallengeMetric,
    val discipline: Discipline? = null,
    val startMs: Long,
    val endMs: Long,
    val createdBy: String,
    val participants: Set<String>,
) {
    init {
        require(endMs > startMs) { "El reto debe terminar después de empezar" }
        require(createdBy in participants) { "El creador participa en su reto" }
    }
}

class ChallengeBoard(val challenge: Challenge) {
    private val progress = HashMap<String, Double>()

    fun isOpen(nowMs: Long) = nowMs in challenge.startMs until challenge.endMs

    fun add(userId: String, amount: Double, timeMs: Long): Boolean {
        if (userId !in challenge.participants || amount <= 0 || timeMs !in challenge.startMs until challenge.endMs) return false
        progress.merge(userId, amount, Double::plus)
        return true
    }

    fun standings(): List<RankRow> = Leaderboards.rank(challenge.participants.associateWith { progress[it] ?: 0.0 })

    /** Winners once the challenge is over (several on a tie); empty if nobody scored. */
    fun winners(nowMs: Long): List<String> {
        if (nowMs < challenge.endMs) return emptyList()
        val top = standings().filter { it.rank == 1 && it.points > 0 }
        return top.map { it.userId }
    }

    fun timeLeftMs(nowMs: Long): Long = (challenge.endMs - nowMs).coerceAtLeast(0)
}

data class WeekSummary(val name: String, val points: Double, val workouts: Int, val km: Double, val streak: Int)

/** Friendly head-to-head text for couples and workout buddies. */
object CoupleMode {
    fun compare(a: WeekSummary, b: WeekSummary): List<String> {
        val lines = mutableListOf<String>()
        val total = a.points + b.points
        when {
            total <= 0 -> lines += "Esta semana nadie ha entrenado todavía. ¡Reten a ${b.name}!"
            a.points > b.points -> lines += "${a.name} va ganando: ${pct(a.points, b.points)}% más puntos que ${b.name}."
            b.points > a.points -> lines += "${b.name} va ganando: ${pct(b.points, a.points)}% más puntos que ${a.name}."
            else -> lines += "¡Empate en puntos!"
        }
        if (a.workouts != b.workouts) {
            val (more, less) = if (a.workouts > b.workouts) a to b else b to a
            lines += "${more.name} entrenó ${more.workouts} veces, ${less.name} ${less.workouts}."
        }
        if (a.km != b.km && maxOf(a.km, b.km) > 0) {
            val lead = if (a.km > b.km) a else b
            lines += "${lead.name} recorrió ${"%.1f".format(lead.km)} km."
        }
        val both = minOf(a.streak, b.streak)
        if (both >= 3) lines += "Los dos llevan al menos $both días seguidos."
        return lines
    }

    private fun pct(big: Double, small: Double): Int = if (small <= 0) 100 else ((big / small - 1) * 100).roundToInt()
}
