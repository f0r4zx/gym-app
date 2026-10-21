package app.gymquest.core.fitness

import kotlin.math.roundToInt

object Hydration {
    const val GLASS_ML = 250

    /** ~35 ml per kg plus 500 ml per half hour of training; rounded to the nearest 50 ml. */
    fun targetMl(weightKg: Double, exerciseMinutes: Int = 0, hotDay: Boolean = false): Int {
        var ml = weightKg * 35.0 + exerciseMinutes / 30.0 * 500.0
        if (hotDay) ml += 500.0
        return ((ml / 50.0).roundToInt() * 50).coerceIn(1_500, 6_000)
    }

    fun glasses(ml: Int): Int = (ml.toDouble() / GLASS_ML).roundToInt()

    /**
     * Evenly spaced reminder hours between waking and sleeping, one every ~2 hours (at least 4
     * reminders), skipping the last hour before bed.
     */
    fun reminderHours(wakeHour: Int, sleepHour: Int): List<Int> {
        val end = if (sleepHour > wakeHour) sleepHour else sleepHour + 24
        val hours = mutableListOf<Int>()
        var h = wakeHour + 1
        while (h < end - 1) {
            hours += h % 24
            h += 2
        }
        return hours
    }

    enum class Pace { AHEAD, ON_TRACK, BEHIND }

    /** Compares what you drank with what you should have by this hour of the day. */
    fun pace(consumedMl: Int, targetMl: Int, hourNow: Double, wakeHour: Int, sleepHour: Int): Pace {
        val end = if (sleepHour > wakeHour) sleepHour else sleepHour + 24
        val now = if (hourNow < wakeHour) hourNow + 24 else hourNow
        val dayFraction = ((now - wakeHour) / (end - wakeHour)).coerceIn(0.0, 1.0)
        val expected = targetMl * dayFraction
        return when {
            consumedMl >= expected * 1.15 -> Pace.AHEAD
            consumedMl >= expected * 0.8 -> Pace.ON_TRACK
            else -> Pace.BEHIND
        }
    }
}
