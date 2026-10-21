package app.gymquest.core.game

import java.time.LocalDate

data class Streak(val current: Int, val longest: Int, val activeToday: Boolean)

object Streaks {
    /**
     * [days] are the dates with at least one workout/activity. The current streak stays alive
     * through today even if you have not trained yet (you still have until midnight), and counts
     * back from yesterday in that case.
     */
    fun compute(days: Set<LocalDate>, today: LocalDate): Streak {
        if (days.isEmpty()) return Streak(0, 0, false)
        val activeToday = today in days

        var current = 0
        var cursor = if (activeToday) today else today.minusDays(1)
        while (cursor in days) {
            current++
            cursor = cursor.minusDays(1)
        }

        var longest = 0
        var run = 0
        var previous: LocalDate? = null
        for (d in days.sorted()) {
            run = if (previous != null && previous.plusDays(1) == d) run + 1 else 1
            if (run > longest) longest = run
            previous = d
        }
        return Streak(current, maxOf(longest, current), activeToday)
    }
}
