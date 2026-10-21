package app.gymquest.core.game

import app.gymquest.core.exercise.ExerciseType
import app.gymquest.core.exercise.PartialRep
import app.gymquest.core.exercise.RepEvent
import kotlin.math.roundToInt

enum class BattleState { IN_PROGRESS, VICTORY, DEFEAT }

enum class DefeatReason { OUT_OF_HEALTH, TIME_UP }

sealed interface BattleEvent {
    /** The player landed a hit. */
    data class Hit(val damage: Int, val critical: Boolean, val combo: Int, val bossHp: Int) : BattleEvent

    /** A rep that did not count against the boss (wrong exercise). */
    data object Ignored : BattleEvent

    data class BossAttack(val damage: Int, val playerHp: Int) : BattleEvent

    /** The boss dropped below half health and got faster and angrier. */
    data object Enraged : BattleEvent

    data class Victory(val xp: Int, val lootId: String, val flawless: Boolean) : BattleEvent

    data class Defeat(val reason: DefeatReason) : BattleEvent
}

/**
 * Turn-free boss fight driven by repetitions. Feed it the [RepEvent]s from a workout session and
 * call [tick] with the clock so the boss can punish long rests.
 *
 * - Each rep deals up to [BASE_DAMAGE], scaled by rep quality and the current combo.
 * - Every [CRIT_EVERY]th consecutive good rep is a critical hit.
 * - Below half HP the boss is enraged: attacks come 30% sooner and hurt 30% more.
 */
class BossBattle(
    val boss: Boss,
    private val startMs: Long = 0,
    val playerMaxHp: Int = PLAYER_HP,
) {
    var state = BattleState.IN_PROGRESS
        private set
    var bossHp = boss.maxHp
        private set
    var playerHp = playerMaxHp
        private set
    var combo = 0
        private set
    var bestCombo = 0
        private set
    var enraged = false
        private set
    var totalDamage = 0
        private set
    var hits = 0
        private set
    var lastExercise: ExerciseType? = null
        private set

    private var lastActivityMs = startMs
    private var lastNow = startMs
    private var tookDamage = false

    val bossHpFraction: Double get() = bossHp.toDouble() / boss.maxHp
    val playerHpFraction: Double get() = playerHp.toDouble() / playerMaxHp
    val timeLeftMs: Long get() = (boss.timeLimitMs - (lastNow - startMs)).coerceAtLeast(0)

    /** Milliseconds until the boss's next attack if the player stops now. */
    fun msUntilAttack(nowMs: Long): Long = (attackInterval() - (nowMs - lastActivityMs)).coerceAtLeast(0)

    fun onRep(event: RepEvent, type: ExerciseType, nowMs: Long = event.timestampMs): List<BattleEvent> {
        if (state != BattleState.IN_PROGRESS) return emptyList()
        val out = tick(nowMs).toMutableList()
        if (state != BattleState.IN_PROGRESS) return out
        if (type !in boss.exercises) {
            out += BattleEvent.Ignored
            return out
        }

        lastActivityMs = nowMs
        val good = event.quality >= GOOD_QUALITY
        if (event.isHoldTick) {
            // Holding builds no combo but never breaks it either.
        } else if (good) {
            combo++
            if (combo > bestCombo) bestCombo = combo
        } else {
            combo = 0
        }

        val critical = !event.isHoldTick && good && combo > 0 && combo % CRIT_EVERY == 0
        // Switching exercises against the multi-exercise final boss is rewarded.
        val variety = if (boss.exercises.size > 1 && lastExercise != null && lastExercise != type) VARIETY_BONUS else 1.0
        lastExercise = type

        val base = if (event.isHoldTick) HOLD_DAMAGE else BASE_DAMAGE
        val comboMultiplier = 1.0 + 0.1 * minOf(combo, MAX_COMBO_STACK)
        var damage = base * event.quality * comboMultiplier * variety
        if (critical) damage *= CRIT_MULTIPLIER
        val dealt = damage.roundToInt().coerceAtLeast(1)

        bossHp = (bossHp - dealt).coerceAtLeast(0)
        totalDamage += dealt
        hits++
        out += BattleEvent.Hit(dealt, critical, combo, bossHp)

        if (!enraged && bossHp > 0 && bossHp * 2 <= boss.maxHp) {
            enraged = true
            out += BattleEvent.Enraged
        }
        if (bossHp == 0) {
            state = BattleState.VICTORY
            out += BattleEvent.Victory(boss.xpReward + (if (!tookDamage) boss.xpReward / 4 else 0), boss.lootId, flawless = !tookDamage)
        }
        return out
    }

    /** A shallow/failed rep breaks the combo. */
    fun onPartial(@Suppress("UNUSED_PARAMETER") partial: PartialRep) {
        if (state == BattleState.IN_PROGRESS) combo = 0
    }

    /** Advance the clock: boss attacks after long rests and the time limit can run out. */
    fun tick(nowMs: Long): List<BattleEvent> {
        if (state != BattleState.IN_PROGRESS) return emptyList()
        val out = mutableListOf<BattleEvent>()
        lastNow = maxOf(lastNow, nowMs)

        // A long pause can cover several attack windows; apply each one in order.
        while (state == BattleState.IN_PROGRESS && nowMs - lastActivityMs >= attackInterval()) {
            lastActivityMs += attackInterval()
            val damage = if (enraged) (boss.attackDamage * ENRAGE_DAMAGE).roundToInt() else boss.attackDamage
            playerHp = (playerHp - damage).coerceAtLeast(0)
            tookDamage = true
            combo = 0
            out += BattleEvent.BossAttack(damage, playerHp)
            if (playerHp == 0) {
                state = BattleState.DEFEAT
                out += BattleEvent.Defeat(DefeatReason.OUT_OF_HEALTH)
            }
        }
        if (state == BattleState.IN_PROGRESS && nowMs - startMs >= boss.timeLimitMs) {
            state = BattleState.DEFEAT
            out += BattleEvent.Defeat(DefeatReason.TIME_UP)
        }
        return out
    }

    private fun attackInterval(): Long =
        if (enraged) (boss.attackIntervalMs * ENRAGE_SPEED).toLong() else boss.attackIntervalMs

    companion object {
        const val PLAYER_HP = 100
        const val BASE_DAMAGE = 10.0
        const val HOLD_DAMAGE = 3.0
        const val GOOD_QUALITY = 0.7
        const val CRIT_EVERY = 5
        const val CRIT_MULTIPLIER = 1.5
        const val MAX_COMBO_STACK = 10
        const val VARIETY_BONUS = 1.25
        const val ENRAGE_SPEED = 0.7
        const val ENRAGE_DAMAGE = 1.3
    }
}
