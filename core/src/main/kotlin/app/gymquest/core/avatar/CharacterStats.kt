package app.gymquest.core.avatar

import app.gymquest.core.exercise.CountingMode
import app.gymquest.core.exercise.ExerciseType
import app.gymquest.core.tracking.ActivityType
import java.util.TreeMap
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt

enum class Stat(val label: String, val short: String, val scale: Double) {
    STRENGTH("Fuerza", "FUE", 1_500.0),
    ENDURANCE("Resistencia", "RES", 1_400.0),
    SPEED("Velocidad", "VEL", 900.0),
    AGILITY("Agilidad", "AGI", 1_000.0),
    VITALITY("Vitalidad", "VIT", 700.0),
}

data class StatGain(val stat: Stat, val points: Double)

/** Where stat points come from. Everything must be earned by really moving, resting and eating well. */
object StatSources {
    private data class Weights(val str: Double = 0.0, val end: Double = 0.0, val spd: Double = 0.0, val agi: Double = 0.0)

    private fun weights(type: ExerciseType): Weights = when (type) {
        ExerciseType.SQUAT -> Weights(str = 1.0, end = 0.3)
        ExerciseType.PUSH_UP -> Weights(str = 1.0)
        ExerciseType.PULL_UP -> Weights(str = 1.6)
        ExerciseType.LUNGE -> Weights(str = 0.7, agi = 0.5)
        ExerciseType.JUMPING_JACK -> Weights(end = 0.4, spd = 0.2, agi = 0.4)
        ExerciseType.BICEP_CURL -> Weights(str = 0.8)
        ExerciseType.SHOULDER_PRESS -> Weights(str = 1.0)
        ExerciseType.LATERAL_RAISE -> Weights(str = 0.6)
        ExerciseType.TRICEPS_EXTENSION -> Weights(str = 0.8)
        ExerciseType.SIT_UP -> Weights(str = 0.6, end = 0.3)
        ExerciseType.DEADLIFT -> Weights(str = 1.8)
        ExerciseType.GLUTE_BRIDGE -> Weights(str = 0.6)
        ExerciseType.MOUNTAIN_CLIMBER -> Weights(end = 0.4, spd = 0.3, agi = 0.2)
        ExerciseType.HIGH_KNEES -> Weights(spd = 0.5, agi = 0.4)
        ExerciseType.BURPEE -> Weights(str = 0.6, end = 0.8, agi = 0.4)
        ExerciseType.JUMP_ROPE -> Weights(end = 0.3, spd = 0.4, agi = 0.5)
        ExerciseType.PLANK, ExerciseType.WALL_SIT -> Weights(str = 0.3, end = 0.3)
    }

    /** [amount] is reps, or seconds for holds. Sloppy reps are worth about half. */
    fun fromExercise(type: ExerciseType, amount: Int, quality: Double): List<StatGain> {
        val w = weights(type)
        val q = 0.5 + 0.5 * quality.coerceIn(0.0, 1.0)
        val n = amount * q * (if (type.mode == CountingMode.HOLD) 1.0 else 1.0)
        return listOfNotNull(
            w.str.takeIf { it > 0 }?.let { StatGain(Stat.STRENGTH, n * it) },
            w.end.takeIf { it > 0 }?.let { StatGain(Stat.ENDURANCE, n * it) },
            w.spd.takeIf { it > 0 }?.let { StatGain(Stat.SPEED, n * it) },
            w.agi.takeIf { it > 0 }?.let { StatGain(Stat.AGILITY, n * it) },
        )
    }

    fun fromActivity(type: ActivityType, distanceM: Double, avgSpeedMps: Double): List<StatGain> {
        val km = distanceM / 1000.0
        val endPerKm = when (type) {
            ActivityType.RUN -> 20.0
            ActivityType.HIKE -> 15.0
            ActivityType.WALK -> 10.0
            ActivityType.RIDE -> 7.0
        }
        val kmh = avgSpeedMps * 3.6
        // Speed only grows when you actually push the pace.
        val fast = when (type) {
            ActivityType.RUN -> (kmh - 7.0).coerceAtLeast(0.0) * 4.0
            ActivityType.RIDE -> (kmh - 18.0).coerceAtLeast(0.0) * 1.5
            ActivityType.WALK -> (kmh - 5.0).coerceAtLeast(0.0) * 3.0
            ActivityType.HIKE -> 0.0
        }
        return listOfNotNull(
            StatGain(Stat.ENDURANCE, km * endPerKm),
            (km * fast).takeIf { it > 0 }?.let { StatGain(Stat.SPEED, it) },
        )
    }

    /** Daily habits feed Vitality: recovery is part of being strong. */
    fun fromHabits(waterGoalMet: Boolean, skincareDone: Boolean, sleptEnough: Boolean, mealsLogged: Int, trained: Boolean): List<StatGain> {
        var pts = 0.0
        if (waterGoalMet) pts += 5
        if (skincareDone) pts += 3
        if (sleptEnough) pts += 8
        pts += mealsLogged.coerceIn(0, 4) * 2.0
        if (trained) pts += 4
        return if (pts > 0) listOf(StatGain(Stat.VITALITY, pts)) else emptyList()
    }
}

enum class StatRank(val letter: String) { E("E"), D("D"), C("C"), B("B"), A("A"), S("S") }

/**
 * The character's attributes. Points are bucketed per day and fade with a half-life, so stats
 * reflect how fit you are now: stop training and they slowly drop, keep going and they climb.
 */
class CharacterStats(private val halfLifeDays: Double = 60.0) {
    private val buckets = Stat.entries.associateWith { TreeMap<Long, Double>() }

    fun add(gain: StatGain, timeMs: Long) {
        if (gain.points <= 0) return
        buckets.getValue(gain.stat).merge(timeMs / DAY_MS, gain.points, Double::plus)
    }

    fun addAll(gains: List<StatGain>, timeMs: Long) = gains.forEach { add(it, timeMs) }

    fun points(stat: Stat, nowMs: Long): Double {
        val today = nowMs / DAY_MS
        return buckets.getValue(stat).entries.sumOf { (day, p) -> p * 0.5.pow((today - day).coerceAtLeast(0) / halfLifeDays) }
    }

    /** 1..100 with diminishing returns: the first gains are quick, the last points are hard. */
    fun value(stat: Stat, nowMs: Long): Int = (100.0 * (1.0 - exp(-points(stat, nowMs) / stat.scale))).roundToInt().coerceIn(1, 100)

    fun all(nowMs: Long): Map<Stat, Int> = Stat.entries.associateWith { value(it, nowMs) }

    /** Overall combat rating: a weighted sum, shown on profiles and used to match opponents. */
    fun power(nowMs: Long): Int {
        val v = all(nowMs)
        return (v.getValue(Stat.STRENGTH) * 1.4 + v.getValue(Stat.ENDURANCE) * 1.2 + v.getValue(Stat.SPEED) * 1.0 +
            v.getValue(Stat.AGILITY) * 1.0 + v.getValue(Stat.VITALITY) * 0.8).roundToInt()
    }

    companion object {
        const val DAY_MS = 86_400_000L

        fun rank(value: Int): StatRank = when {
            value >= 90 -> StatRank.S
            value >= 75 -> StatRank.A
            value >= 55 -> StatRank.B
            value >= 35 -> StatRank.C
            value >= 15 -> StatRank.D
            else -> StatRank.E
        }
    }
}

/** How stats help in boss fights. Capped so a veteran is stronger, never untouchable. */
data class BattleModifiers(
    val damageMultiplier: Double = 1.0,
    val playerMaxHp: Int = 100,
    /** >1 means the boss attacks less often. */
    val bossIntervalMultiplier: Double = 1.0,
) {
    companion object {
        val NONE = BattleModifiers()

        fun fromStats(stats: Map<Stat, Int>): BattleModifiers {
            fun v(s: Stat) = (stats[s] ?: 1).coerceIn(1, 100) / 100.0
            return BattleModifiers(
                damageMultiplier = 1.0 + v(Stat.STRENGTH) * 0.25,
                playerMaxHp = 100 + ((v(Stat.VITALITY) + v(Stat.ENDURANCE)) / 2.0 * 50).roundToInt(),
                bossIntervalMultiplier = 1.0 + (v(Stat.SPEED) + v(Stat.AGILITY)) / 2.0 * 0.2,
            )
        }
    }
}
