package app.gymquest.core.competition

import app.gymquest.core.game.Boss
import app.gymquest.core.game.BossTier
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

data class RaidReward(val xp: Int, val coins: Int, val lootId: String?, val isMvp: Boolean)

enum class RaidState { RUNNING, DEFEATED, EXPIRED }

/**
 * A boss the whole guild fights together over several days. Everyone's repetitions chip away at
 * one shared health bar; nobody can deal more than [PER_MEMBER_DAILY_CAP] of it per day so a
 * single athlete cannot carry the guild, and rewards follow each member's share of the damage.
 */
class GuildRaid(
    val boss: Boss,
    val guildId: String,
    memberCount: Int,
    val startMs: Long,
) {
    val maxHp: Long = hpFor(boss, memberCount)
    val endMs: Long = startMs + durationFor(boss.tier)

    var hp: Long = maxHp
        private set

    private val damageByUser = HashMap<String, Long>()
    private val damageByUserDay = HashMap<Pair<String, Long>, Long>()

    val contributions: Map<String, Long> get() = damageByUser

    fun state(nowMs: Long): RaidState = when {
        hp <= 0 -> RaidState.DEFEATED
        nowMs >= endMs -> RaidState.EXPIRED
        else -> RaidState.RUNNING
    }

    /** Applies [damage] from [userId]; returns the damage that actually counted (after the daily cap). */
    fun hit(userId: String, damage: Int, nowMs: Long): Long {
        if (state(nowMs) != RaidState.RUNNING || damage <= 0 || nowMs < startMs) return 0
        val day = (nowMs - startMs) / DAY_MS
        val cap = (maxHp * PER_MEMBER_DAILY_CAP).toLong()
        val used = damageByUserDay[userId to day] ?: 0
        val counted = min(damage.toLong(), min(cap - used, hp)).coerceAtLeast(0)
        if (counted == 0L) return 0
        damageByUserDay[userId to day] = used + counted
        damageByUser.merge(userId, counted, Long::plus)
        hp -= counted
        return counted
    }

    /** Rewards once the raid is over; empty while it is running. */
    fun rewards(nowMs: Long): Map<String, RaidReward> {
        val state = state(nowMs)
        if (state == RaidState.RUNNING) return emptyMap()
        val won = state == RaidState.DEFEATED
        val threshold = if (won) 0.015 else 0.03
        val mvp = damageByUser.maxByOrNull { it.value }?.key
        return damageByUser.filter { it.value >= maxHp * threshold }.mapValues { (user, dmg) ->
            val share = dmg.toDouble() / maxHp
            val isMvp = won && user == mvp
            RaidReward(
                xp = ((boss.xpReward * 2 * (if (won) 1.0 else 0.5)) * (0.6 + 0.4 * min(1.0, share * 5))).roundToInt(),
                coins = ((boss.xpReward / 2.0) * (if (won) 1.0 else 0.5) * (if (isMvp) 2.0 else 1.0)).roundToInt(),
                lootId = if (isMvp) boss.lootId else null,
                isMvp = isMvp,
            )
        }
    }

    companion object {
        const val DAY_MS = 86_400_000L
        const val PER_MEMBER_DAILY_CAP = 0.15

        /** Raid health grows with the roster, but less than linearly so small guilds can win too. */
        fun hpFor(boss: Boss, members: Int): Long = (boss.maxHp * 40.0 * members.coerceAtLeast(1).toDouble().pow(0.8)).toLong()

        fun durationFor(tier: BossTier): Long = DAY_MS * when (tier) {
            BossTier.EASY -> 2
            BossTier.MEDIUM -> 3
            BossTier.HARD -> 4
            BossTier.EPIC -> 7
        }
    }
}
