package app.gymquest.core.wellness

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A screen turning on or off, as reported by the phone. Collected on-device only, with the user's permission. */
data class ScreenEvent(val timeMs: Long, val on: Boolean)

enum class SleepSource { SCREEN_INFERRED, WEARABLE, MANUAL }

data class SleepSession(
    val startMs: Long,
    val endMs: Long,
    val awakeMs: Long,
    val source: SleepSource,
    /** How sure we are this was real sleep (screen-based guesses never reach 1.0). */
    val confidence: Double,
) {
    val asleepMs: Long get() = (endMs - startMs - awakeMs).coerceAtLeast(0)
    val asleepHours: Double get() = asleepMs / 3_600_000.0
    val efficiency: Double get() = if (endMs > startMs) asleepMs.toDouble() / (endMs - startMs) else 0.0
}

data class SleepStages(val deepMin: Int, val remMin: Int, val lightMin: Int, val awakeMin: Int) {
    val asleepMin: Int get() = deepMin + remMin + lightMin
}

/** Recommended hours of sleep (National Sleep Foundation ranges). */
data class SleepNeed(val minHours: Double, val maxHours: Double) {
    val idealHours: Double get() = (minHours + maxHours) / 2

    companion object {
        fun forAge(age: Int): SleepNeed = when {
            age < 14 -> SleepNeed(9.0, 11.0)
            age < 18 -> SleepNeed(8.0, 10.0)
            age < 65 -> SleepNeed(7.0, 9.0)
            else -> SleepNeed(7.0, 8.0)
        }

        /** Hard training days ask for a bit more sleep: +15 min per 30 min over the first 45, up to +45 min. */
        fun withTraining(base: SleepNeed, hardTrainingMinutes: Int): SleepNeed {
            val extra = (((hardTrainingMinutes - 45).coerceAtLeast(0)) / 30.0 * 0.25).coerceAtMost(0.75)
            return SleepNeed(base.minHours + extra, base.maxHours + extra)
        }
    }
}

object SleepEstimator {
    private const val NOTIFICATION_BLIP_MS = 90_000L
    private const val BREAK_MS = 20 * 60_000L
    private const val LATENCY_MS = 15 * 60_000L
    private const val MIN_SLEEP_MS = 3 * 3_600_000L
    private const val NIGHT_AWAKE_PENALTY_MS = 10 * 60_000L

    private data class Interval(val start: Long, val end: Long)

    /**
     * Guesses last night's sleep from when the screen was off. The longest stretch without real phone
     * use that starts in the evening and ends before the afternoon is taken as sleep. Brief screen
     * wake-ups count as awakenings; a 90-second notification does not.
     */
    fun infer(events: List<ScreenEvent>, wakeDayStartMs: Long, zone: ZoneId): SleepSession? {
        val sorted = events.sortedBy { it.timeMs }
        if (sorted.size < 2) return null

        // Window: previous day 18:00 .. this day 14:00 in local time.
        val dayStart = Instant.ofEpochMilli(wakeDayStartMs).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
        val windowStart = dayStart - 6 * 3_600_000L
        val windowEnd = dayStart + 14 * 3_600_000L

        // Build "screen off" intervals.
        val off = mutableListOf<Interval>()
        var offSince: Long? = null
        for (e in sorted) {
            if (!e.on) {
                if (offSince == null) offSince = e.timeMs
            } else if (offSince != null) {
                off += Interval(offSince, e.timeMs)
                offSince = null
            }
        }
        if (off.isEmpty()) return null

        // Merge across brief screen-on moments; remember them as awakenings.
        val blocks = mutableListOf<Triple<Long, Long, Long>>() // start, end, awakeMs
        var cur = off.first()
        var awake = 0L
        for (next in off.drop(1)) {
            val gap = next.start - cur.end
            when {
                gap < NOTIFICATION_BLIP_MS -> cur = Interval(cur.start, next.end)
                gap < BREAK_MS -> {
                    awake += gap + NIGHT_AWAKE_PENALTY_MS
                    cur = Interval(cur.start, next.end)
                }
                else -> {
                    blocks += Triple(cur.start, cur.end, awake)
                    cur = next
                    awake = 0L
                }
            }
        }
        blocks += Triple(cur.start, cur.end, awake)

        val best = blocks
            .filter { (s, e, _) -> s >= windowStart && s <= dayStart + 5 * 3_600_000L && e <= windowEnd + 2 * 3_600_000L && e - s >= MIN_SLEEP_MS }
            .maxByOrNull { it.second - it.first } ?: return null

        val (start, end, awakeMs) = best
        val sleepStart = start + LATENCY_MS
        val hours = (end - sleepStart) / 3_600_000.0
        val startHour = Instant.ofEpochMilli(start).atZone(zone).toLocalTime().hour
        var confidence = 0.45
        if (hours in 5.0..11.0) confidence += 0.2
        if (startHour >= 21 || startHour <= 2) confidence += 0.15
        if (awakeMs <= 30 * 60_000L) confidence += 0.1
        return SleepSession(sleepStart, end, awakeMs, SleepSource.SCREEN_INFERRED, min(confidence, 0.9))
    }

    /** A wearable's stage data gives a much better picture than guessing from the screen. */
    fun fromStages(startMs: Long, stages: SleepStages): SleepSession =
        SleepSession(startMs, startMs + (stages.asleepMin + stages.awakeMin) * 60_000L, stages.awakeMin * 60_000L, SleepSource.WEARABLE, 0.95)

    fun manual(startMs: Long, endMs: Long) = SleepSession(startMs, endMs, 0, SleepSource.MANUAL, 0.8)
}

object SleepScore {
    /** 0..100. Rewards enough sleep, few awakenings and (when known) restorative stages. */
    fun score(s: SleepSession, need: SleepNeed, stages: SleepStages? = null): Int {
        val duration = (s.asleepHours / need.idealHours).coerceIn(0.0, 1.0)
        val oversleep = if (s.asleepHours > need.maxHours + 1.5) 0.92 else 1.0
        val efficiency = ((s.efficiency - 0.7) / 0.25).coerceIn(0.0, 1.0)
        var score = 70 * duration * oversleep + 30 * efficiency
        if (stages != null && stages.asleepMin > 0) {
            val restorative = (stages.deepMin + stages.remMin).toDouble() / stages.asleepMin
            // About 40% deep + REM is typical for healthy adults.
            score = score * 0.8 + 100 * (restorative / 0.4).coerceIn(0.0, 1.0) * 0.2
        }
        return score.roundToInt().coerceIn(0, 100)
    }

    /** Hours of sleep owed over the last [days] nights (capped), counting only nights we have data for. */
    fun debtHours(sessions: List<SleepSession>, need: SleepNeed, days: Int = 7): Double {
        if (sessions.isEmpty()) return 0.0
        val latest = sessions.maxOf { it.endMs }
        val recent = sessions.filter { latest - it.endMs <= days * 86_400_000L }
        return recent.sumOf { max(0.0, need.idealHours - it.asleepHours) }.coerceAtMost(14.0)
    }

    /** Bedtime variability in minutes over recent nights; irregular sleep hurts recovery. */
    fun bedtimeSpreadMinutes(sessions: List<SleepSession>, zone: ZoneId): Double {
        if (sessions.size < 3) return 0.0
        val minutes = sessions.map {
            val t = Instant.ofEpochMilli(it.startMs).atZone(zone).toLocalTime()
            val m = t.hour * 60 + t.minute
            if (m < 12 * 60) m + 24 * 60 else m // after midnight counts as "later", not "earlier"
        }
        val mean = minutes.average()
        return kotlin.math.sqrt(minutes.sumOf { (it - mean) * (it - mean) } / minutes.size)
    }

    fun tonightAdvice(debtHours: Double, need: SleepNeed): String = when {
        debtHours < 1.0 -> "Vas bien con tu sueño. Intenta dormir ${hm(need.idealHours)} esta noche."
        debtHours < 4.0 -> "Debes ${hm(debtHours)} de sueño. Duerme ${hm(need.idealHours + 0.5)} esta noche."
        else -> "Tienes una deuda de sueño importante (${hm(debtHours)}). Prioriza dormir temprano y baja la intensidad del entreno."
    }

    private fun hm(hours: Double): String {
        val total = (hours * 60).roundToInt()
        return if (total % 60 == 0) "${total / 60} h" else "${total / 60} h ${total % 60} min"
    }
}

data class WorkoutLoad(val startMs: Long, val minutes: Int, val met: Double)

enum class Readiness(val label: String) {
    PUSH_HARD("Listo para entrenar fuerte"),
    NORMAL("Entrenamiento normal"),
    LIGHT("Mejor algo ligero"),
    REST("Descansa hoy"),
}

data class BatteryReading(val level: Int, val morningLevel: Int, val readiness: Readiness, val message: String)

/**
 * A 0–100 "body battery": you wake up charged according to how well and how long you slept,
 * and it drains through the day with time awake, hard workouts and late-night screen use.
 */
object BodyBattery {
    private const val DRAIN_PER_AWAKE_HOUR = 2.5
    private const val LATE_SCREEN_DRAIN_PER_HOUR = 1.5

    fun morning(sleepScore: Int, debtHours: Double): Int =
        (30 + 70 * (sleepScore / 100.0) - 2.5 * debtHours).roundToInt().coerceIn(5, 100)

    fun current(
        sleepScore: Int,
        debtHours: Double,
        wakeMs: Long,
        nowMs: Long,
        workouts: List<WorkoutLoad>,
        napMinutes: Int = 0,
        lateScreenMinutes: Int = 0,
    ): BatteryReading {
        val morning = morning(sleepScore, debtHours)
        val awakeHours = ((nowMs - wakeMs).coerceAtLeast(0)) / 3_600_000.0
        val exercise = workouts.filter { it.startMs in wakeMs..nowMs }.sumOf { it.minutes * (it.met - 1.0).coerceAtLeast(0.0) / 12.0 }
        val nap = min(napMinutes.coerceAtMost(30) * 0.4, 12.0)
        val screen = lateScreenMinutes / 60.0 * LATE_SCREEN_DRAIN_PER_HOUR
        val level = (morning - awakeHours * DRAIN_PER_AWAKE_HOUR - exercise - screen + nap).roundToInt().coerceIn(0, 100)
        return BatteryReading(level, morning, readiness(level), message(level))
    }

    fun readiness(level: Int): Readiness = when {
        level >= 75 -> Readiness.PUSH_HARD
        level >= 50 -> Readiness.NORMAL
        level >= 25 -> Readiness.LIGHT
        else -> Readiness.REST
    }

    private fun message(level: Int) = when (readiness(level)) {
        Readiness.PUSH_HARD -> "Tu batería está en $level%: buen día para subir la carga o pelear con un jefe grande."
        Readiness.NORMAL -> "Batería al $level%: entrenamiento normal, escucha a tu cuerpo."
        Readiness.LIGHT -> "Batería al $level%: mejor movilidad, caminata o una serie ligera."
        Readiness.REST -> "Batería al $level%: hoy toca recargar. Duerme temprano."
    }

    /** What you would wake up with if you slept [hours] tonight (assumes a decent night). */
    fun forecastMorning(hours: Double, need: SleepNeed, debtHours: Double): Int {
        val score = (100 * (hours / need.idealHours).coerceIn(0.0, 1.0) * 0.9).roundToInt()
        return morning(score, max(0.0, debtHours - max(0.0, hours - need.idealHours)))
    }

    /** When to go to bed to wake at [wake] with a full night, allowing 15 minutes to fall asleep. */
    fun bedtimeFor(wake: LocalTime, need: SleepNeed): LocalTime {
        val minutes = (need.idealHours * 60).roundToInt() + 15
        return wake.minusMinutes(minutes.toLong())
    }
}

data class DailyUsage(
    val totalMs: Long,
    val pickups: Int,
    /** Time with the screen on between 23:00 and 05:00. */
    val lateNightMs: Long,
    val longestSessionMs: Long = 0,
)

enum class UsageLevel { GOOD, WATCH, HIGH }

data class UsageAssessment(val level: UsageLevel, val tips: List<String>)

/** Gentle screen-time guidance by age. Never judgmental: it connects phone use to sleep and energy. */
object ScreenTimeAdvisor {
    fun assess(u: DailyUsage, age: Int): UsageAssessment {
        val hours = u.totalMs / 3_600_000.0
        val (watch, high) = when {
            age < 18 -> 4.0 to 6.0
            age < 65 -> 5.0 to 7.0
            else -> 4.0 to 6.0
        }
        val tips = mutableListOf<String>()
        var level = when {
            hours >= high -> UsageLevel.HIGH
            hours >= watch -> UsageLevel.WATCH
            else -> UsageLevel.GOOD
        }
        if (u.lateNightMs > 30 * 60_000L) {
            tips += "Usaste el teléfono ${u.lateNightMs / 60_000} min de madrugada: la luz de la pantalla retrasa el sueño y baja tu batería."
            if (level == UsageLevel.GOOD) level = UsageLevel.WATCH
        }
        if (u.pickups > 120) tips += "Desbloqueaste el teléfono ${u.pickups} veces. Prueba el modo no molestar durante tu entreno."
        if (u.longestSessionMs > 90 * 60_000L) tips += "Una sesión de más de 90 minutos seguidos: levántate y estírate."
        if (level != UsageLevel.GOOD && hours >= watch) tips += "Llevas ${"%.1f".format(hours)} h de pantalla. Cambiar 30 minutos por una caminata cuenta como actividad."
        if (tips.isEmpty()) tips += "Buen uso del teléfono hoy."
        return UsageAssessment(level, tips)
    }

    /** Minutes of battery the late-night screen time cost, for [BodyBattery.current]. */
    fun lateMinutes(u: DailyUsage): Int = (u.lateNightMs / 60_000).toInt()

}
