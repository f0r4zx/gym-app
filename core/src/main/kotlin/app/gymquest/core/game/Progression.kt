package app.gymquest.core.game

import app.gymquest.core.exercise.CountingMode
import app.gymquest.core.exercise.ExerciseCategory
import app.gymquest.core.exercise.ExerciseType
import kotlin.math.pow
import kotlin.math.roundToInt

data class LevelProgress(
    val level: Int,
    val xpIntoLevel: Int,
    val xpForNext: Int,
    val title: String,
) {
    val fraction: Double get() = if (xpForNext == 0) 1.0 else xpIntoLevel.toDouble() / xpForNext
}

/** Experience, levels and rank titles. Early levels come fast, later ones take real consistency. */
object Progression {
    const val MAX_LEVEL = 99

    /** Total XP needed to *reach* [level] (level 1 = 0). */
    fun xpToReach(level: Int): Int {
        if (level <= 1) return 0
        return (100.0 * (level - 1).toDouble().pow(1.6)).roundToInt()
    }

    fun levelFor(xp: Int): Int {
        var level = 1
        while (level < MAX_LEVEL && xp >= xpToReach(level + 1)) level++
        return level
    }

    fun progress(xp: Int): LevelProgress {
        val level = levelFor(xp.coerceAtLeast(0))
        val base = xpToReach(level)
        val next = if (level >= MAX_LEVEL) base else xpToReach(level + 1)
        return LevelProgress(level, xp - base, next - base, titleFor(level))
    }

    fun titleFor(level: Int): String = when {
        level < 5 -> "Novato"
        level < 10 -> "Aprendiz"
        level < 20 -> "Atleta"
        level < 30 -> "Guerrero"
        level < 45 -> "Campeón"
        level < 70 -> "Maestro"
        else -> "Leyenda"
    }
}

/** How much XP each activity earns. Quality matters: sloppy reps earn about half. */
object XpRewards {
    fun forReps(type: ExerciseType, reps: Int, averageQuality: Double): Int {
        val base = when {
            type.mode == CountingMode.HOLD -> 0.5 // per second
            type.category == ExerciseCategory.CARDIO -> 1.0
            else -> 2.0
        }
        return (reps * base * (0.5 + 0.5 * averageQuality.coerceIn(0.0, 1.0))).roundToInt()
    }

    fun forDistance(activity: ActivityKind, km: Double): Int = (km * activity.xpPerKm).roundToInt()

    const val WATER_GLASS = 2
    const val SKINCARE_ROUTINE = 10
    const val MEAL_LOGGED = 3
    const val WORKOUT_LOGGED = 20
    const val DAILY_GOALS_COMPLETE = 40

    /** Bonus for keeping a streak alive, capped so it never dominates. */
    fun streakBonus(streakDays: Int): Int = (streakDays * 2).coerceAtMost(50)
}

/** Outdoor activity kinds with their XP rate. (Full activity types live in the tracking package.) */
enum class ActivityKind(val xpPerKm: Double) {
    WALK(8.0), RUN(15.0), RIDE(5.0), HIKE(12.0)
}
