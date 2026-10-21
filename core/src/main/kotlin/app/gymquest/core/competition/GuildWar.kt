package app.gymquest.core.competition

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

data class WarSide(val guildId: String, val name: String, val rating: Int, val memberCount: Int)

data class WarResult(
    val winnerGuildId: String?,
    val scoreA: Double,
    val scoreB: Double,
    val mvpUserId: String?,
    val coinsToWinner: Long,
    val refundEach: Long,
    val newRatingA: Int,
    val newRatingB: Int,
    /** Territory cells the loser must hand to the winner (capped, weakest land first). */
    val cellsToTransfer: Int,
)

/**
 * A guild-vs-guild war over one [Discipline]. To keep big guilds from simply out-numbering small
 * ones, each side only counts its best N members, where N is the smaller guild's active roster,
 * and nobody can score more than [dailyCapPerMember] points a day.
 */
class GuildWar(
    val id: String,
    val a: WarSide,
    val b: WarSide,
    val discipline: Discipline,
    val startMs: Long,
    val endMs: Long,
    val stakePerGuild: Long,
    private val dailyCapPerMember: Double = 400.0,
) {
    private val points = HashMap<String, HashMap<String, HashMap<Long, Double>>>() // guild -> user -> day -> points

    init {
        require(a.guildId != b.guildId) { "Un gremio no puede pelear contra sí mismo" }
        require(endMs > startMs)
    }

    fun isRunning(nowMs: Long) = nowMs in startMs until endMs

    /** Adds points for a member; ignored outside the war window or for other guilds. */
    fun addScore(guildId: String, userId: String, amount: Double, timeMs: Long): Boolean {
        if (guildId != a.guildId && guildId != b.guildId) return false
        if (timeMs < startMs || timeMs >= endMs || amount <= 0) return false
        val day = (timeMs - startMs) / DAY_MS
        points.getOrPut(guildId) { HashMap() }.getOrPut(userId) { HashMap() }.merge(day, amount, Double::plus)
        return true
    }

    private fun memberScores(guildId: String): Map<String, Double> =
        points[guildId].orEmpty().mapValues { (_, days) -> days.values.sumOf { min(it, dailyCapPerMember) } }

    /** Number of members on each side that count: the smaller side's active roster. */
    fun countedMembers(): Int {
        val activeA = memberScores(a.guildId).count { it.value > 0 }
        val activeB = memberScores(b.guildId).count { it.value > 0 }
        return maxOf(1, min(activeA, activeB))
    }

    fun scoreOf(guildId: String): Double {
        val n = countedMembers()
        return memberScores(guildId).values.sortedDescending().take(n).sum()
    }

    fun mvp(): String? = (memberScores(a.guildId) + memberScores(b.guildId)).maxByOrNull { it.value }?.key

    /** Final result, or null while the war is still on. */
    fun result(nowMs: Long, loserCells: (String) -> Int = { 0 }): WarResult? {
        if (nowMs < endMs) return null
        val sa = scoreOf(a.guildId)
        val sb = scoreOf(b.guildId)
        val draw = abs(sa - sb) < 1e-6
        val winner = if (draw) null else if (sa > sb) a else b
        val loser = if (winner == null) null else if (winner === a) b else a

        val scoreForA = if (draw) 0.5 else if (winner === a) 1.0 else 0.0
        val newA = Elo.update(a.rating, b.rating, scoreForA)
        val newB = Elo.update(b.rating, a.rating, 1.0 - scoreForA)

        val pot = stakePerGuild * 2
        val prize = pot - pot * FEE_PERCENT / 100
        val cells = if (loser == null) 0 else min(MAX_SPOILS, (loserCells(loser.guildId) * SPOILS_FRACTION).roundToInt())
        return WarResult(
            winnerGuildId = winner?.guildId,
            scoreA = sa, scoreB = sb, mvpUserId = mvp(),
            coinsToWinner = if (winner == null) 0 else prize,
            refundEach = if (winner == null) stakePerGuild else 0,
            newRatingA = newA, newRatingB = newB, cellsToTransfer = cells,
        )
    }

    companion object {
        const val DAY_MS = 86_400_000L
        const val FEE_PERCENT = 5L
        const val MAX_SPOILS = 50
        const val SPOILS_FRACTION = 0.10
    }
}

object Matchmaker {
    /**
     * Pairs guilds of similar strength and size. The accepted rating gap widens by 50 for every
     * hour a guild has been waiting, so nobody queues forever.
     */
    fun pair(
        queue: List<WarSide>,
        waitedHours: (String) -> Int = { 0 },
        baseRatingGap: Int = 200,
        maxSizeRatio: Double = 1.5,
    ): Pair<List<Pair<WarSide, WarSide>>, List<WarSide>> {
        val remaining = queue.sortedBy { it.rating }.toMutableList()
        val pairs = mutableListOf<Pair<WarSide, WarSide>>()
        val unmatched = mutableListOf<WarSide>()
        while (remaining.isNotEmpty()) {
            val g = remaining.removeAt(0)
            val allowed = baseRatingGap + 50 * waitedHours(g.guildId)
            val opp = remaining
                .filter { abs(it.rating - g.rating) <= allowed && sizeRatio(g, it) <= maxSizeRatio }
                .minByOrNull { abs(it.rating - g.rating) }
            if (opp == null) {
                unmatched += g
            } else {
                remaining.remove(opp)
                pairs += g to opp
            }
        }
        return pairs to unmatched
    }

    private fun sizeRatio(x: WarSide, y: WarSide): Double {
        val hi = maxOf(x.memberCount, y.memberCount).toDouble()
        val lo = maxOf(1, minOf(x.memberCount, y.memberCount)).toDouble()
        return hi / lo
    }
}
