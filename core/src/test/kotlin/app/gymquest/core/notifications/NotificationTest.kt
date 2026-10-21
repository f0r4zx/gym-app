package app.gymquest.core.notifications

import app.gymquest.core.nutrition.MealType
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// 2026-10-21 is a Wednesday, 2026-10-25 a Sunday.
private val WED = LocalDate.of(2026, 10, 21)
private val SUN = LocalDate.of(2026, 10, 25)

private fun state(
    date: LocalDate = WED, active: Boolean = false, streak: Int = 5, water: Int = 500, target: Int = 2500,
    amDone: Boolean = false, pmDone: Boolean = false, meals: Set<MealType> = emptySet(), weighIn: Boolean = false,
) = DayState(date, active, streak, water, target, amDone, pmDone, meals, weighIn)

class NotificationPlannerTest {
    private val prefs = NotificationPrefs()
    private val morning = LocalTime.of(6, 0)

    @Test
    fun warnsAboutTheStreakInTheEveningWhenYouHaveNotTrained() {
        val plan = NotificationPlanner.planDay(prefs, state(streak = 6), morning)
        val warn = plan.single { it.type == NotificationType.STREAK_AT_RISK }
        assertEquals(LocalTime.of(20, 0), warn.time)
        assertTrue(warn.title.contains("6"))
    }

    @Test
    fun noStreakWarningOnceYouTrainedOrWithoutAStreak() {
        assertTrue(NotificationPlanner.planDay(prefs, state(active = true), morning).none { it.type == NotificationType.STREAK_AT_RISK })
        assertTrue(NotificationPlanner.planDay(prefs, state(streak = 1), morning).none { it.type == NotificationType.STREAK_AT_RISK })
        assertTrue(NotificationPlanner.planDay(prefs, state(streak = 0), morning).none { it.type == NotificationType.STREAK_AT_RISK })
    }

    @Test
    fun longStreaksGetALastCallBeforeQuietHours() {
        val plan = NotificationPlanner.planDay(prefs, state(streak = 20), morning).filter { it.type == NotificationType.STREAK_AT_RISK }
        assertEquals(2, plan.size)
        assertEquals(LocalTime.of(21, 30), plan.maxOf { it.time })
    }

    @Test
    fun pastRemindersAreNotScheduled() {
        val plan = NotificationPlanner.planDay(prefs, state(streak = 6), LocalTime.of(20, 30))
        assertTrue(plan.none { it.type == NotificationType.STREAK_AT_RISK && it.id.endsWith("evening") })
        assertTrue(plan.all { it.time.isAfter(LocalTime.of(20, 30)) })
    }

    @Test
    fun workoutReminderOnlyOnTrainingDays() {
        assertTrue(NotificationPlanner.planDay(prefs, state(WED), morning).any { it.type == NotificationType.WORKOUT_REMINDER })
        assertTrue(NotificationPlanner.planDay(prefs, state(LocalDate.of(2026, 10, 22)), morning).none { it.type == NotificationType.WORKOUT_REMINDER })
        assertTrue(NotificationPlanner.planDay(prefs, state(WED, active = true), morning).none { it.type == NotificationType.WORKOUT_REMINDER })
    }

    @Test
    fun turnedOffTypesAreNeverScheduled() {
        val off = prefs.copy(enabled = prefs.enabled - NotificationType.WATER - NotificationType.STREAK_AT_RISK)
        val plan = NotificationPlanner.planDay(off, state(), morning)
        assertTrue(plan.none { it.type == NotificationType.WATER || it.type == NotificationType.STREAK_AT_RISK })
        val nothing = NotificationPlanner.planDay(prefs.copy(enabled = emptySet()), state(), morning)
        assertTrue(nothing.isEmpty())
    }

    @Test
    fun noReminderFallsInQuietHours() {
        val plan = NotificationPlanner.planDay(prefs.copy(enabled = NotificationType.entries.toSet()), state(SUN, weighIn = true), LocalTime.of(0, 30))
        assertTrue(plan.all { !prefs.isQuiet(it.time) }, plan.joinToString { "${it.type}@${it.time}" })
    }

    @Test
    fun quietHoursWrapPastMidnight() {
        assertTrue(prefs.isQuiet(LocalTime.of(23, 0)))
        assertTrue(prefs.isQuiet(LocalTime.of(3, 0)))
        assertTrue(!prefs.isQuiet(LocalTime.of(7, 0)))
        assertTrue(!prefs.isQuiet(LocalTime.of(15, 0)))
        val day = NotificationPrefs(quietStart = LocalTime.of(13, 0), quietEnd = LocalTime.of(15, 0))
        assertTrue(day.isQuiet(LocalTime.of(14, 0)) && !day.isQuiet(LocalTime.of(16, 0)))
    }

    @Test
    fun waterRemindersStopWhenAheadOrDone() {
        val behind = NotificationPlanner.planDay(prefs, state(water = 0), morning).count { it.type == NotificationType.WATER }
        val done = NotificationPlanner.planDay(prefs, state(water = 2500), morning).count { it.type == NotificationType.WATER }
        val ahead = NotificationPlanner.planDay(prefs, state(water = 2000), morning).count { it.type == NotificationType.WATER }
        assertTrue(behind in 3..4, "water reminders: $behind")
        assertEquals(0, done)
        assertTrue(ahead < behind)
        assertTrue(NotificationPlanner.planDay(prefs, state(water = 0), morning).first { it.type == NotificationType.WATER }.body.contains("10 vasos"))
    }

    @Test
    fun skincareRemindersSkipWhatIsDone() {
        val none = NotificationPlanner.planDay(prefs, state(), morning).filter { it.type == NotificationType.SKINCARE }
        assertEquals(2, none.size)
        val am = NotificationPlanner.planDay(prefs, state(amDone = true), morning).filter { it.type == NotificationType.SKINCARE }
        assertEquals(1, am.size)
        assertTrue(am.single().id.endsWith("pm"))
    }

    @Test
    fun mealRemindersAreOptIn() {
        assertTrue(NotificationPlanner.planDay(prefs, state(), morning).none { it.type == NotificationType.MEAL_LOG })
        val on = prefs.copy(enabled = prefs.enabled + NotificationType.MEAL_LOG, maxPerDay = 30)
        val all = NotificationPlanner.planDay(on, state(), morning).filter { it.type == NotificationType.MEAL_LOG }
        assertEquals(3, all.size)
        val logged = NotificationPlanner.planDay(on, state(meals = setOf(MealType.BREAKFAST)), morning).filter { it.type == NotificationType.MEAL_LOG }
        assertEquals(2, logged.size)
    }

    @Test
    fun weighInAndSummaryOnSundaysOnly() {
        val sun = NotificationPlanner.planDay(prefs.copy(maxPerDay = 30), state(SUN, weighIn = true), morning)
        assertTrue(sun.any { it.type == NotificationType.WEIGH_IN } && sun.any { it.type == NotificationType.WEEKLY_SUMMARY })
        val wed = NotificationPlanner.planDay(prefs.copy(maxPerDay = 30), state(WED, weighIn = true), morning)
        assertTrue(wed.none { it.type == NotificationType.WEIGH_IN || it.type == NotificationType.WEEKLY_SUMMARY })
    }

    @Test
    fun dailyCapKeepsTheMostImportantAndStaysInTimeOrder() {
        val all = prefs.copy(enabled = NotificationType.entries.toSet(), maxPerDay = 4)
        val plan = NotificationPlanner.planDay(all, state(SUN, streak = 20, weighIn = true), morning)
        assertEquals(4, plan.size)
        assertTrue(plan.any { it.type == NotificationType.STREAK_AT_RISK })
        assertTrue(plan.none { it.type == NotificationType.WEEKLY_SUMMARY })
        assertEquals(plan.sortedBy { it.time }, plan)
    }

    @Test
    fun idsAreStableAndUnique() {
        val a = NotificationPlanner.planDay(prefs, state(streak = 20), morning)
        val b = NotificationPlanner.planDay(prefs, state(streak = 20), morning)
        assertEquals(a.map { it.id }, b.map { it.id })
        assertEquals(a.size, a.map { it.id }.toSet().size)
    }

    @Test
    fun everyNotificationHasADeepLink() {
        val all = prefs.copy(enabled = NotificationType.entries.toSet())
        assertTrue(NotificationPlanner.planDay(all, state(SUN, weighIn = true), morning).all { it.deepLink.startsWith("app://") })
    }
}

class NotificationEventTest {
    private val prefs = NotificationPrefs()
    private val noon = LocalDateTimeLike(WED, LocalTime.of(12, 0))

    @Test
    fun streakMilestonesAreCelebratedOthersAreNot() {
        val n = NotificationPlanner.onEvent(AppEvent.StreakExtended(7), prefs, noon)
        assertNotNull(n)
        assertTrue(n.title.contains("7"))
        assertEquals(NotificationType.STREAK_MILESTONE, n.type)
        assertNull(NotificationPlanner.onEvent(AppEvent.StreakExtended(8), prefs, noon))
        assertTrue(NotificationPlanner.isStreakMilestone(100) && !NotificationPlanner.isStreakMilestone(99))
    }

    @Test
    fun friendsPassingYouAndStreaksAreSocialPings() {
        val passed = NotificationPlanner.onEvent(AppEvent.FriendPassedYou("Ana", 3), prefs, noon)!!
        assertTrue(passed.title.contains("Ana") && passed.body.contains("3"))
        assertEquals("app://ranking", passed.deepLink)
        val friendStreak = NotificationPlanner.onEvent(AppEvent.FriendStreakMilestone("Luis", 30), prefs, noon)!!
        assertTrue(friendStreak.title.contains("Luis") && friendStreak.title.contains("30"))
    }

    @Test
    fun eventsRespectPreferencesAndQuietHours() {
        val off = prefs.copy(enabled = prefs.enabled - NotificationType.RANKING)
        assertNull(NotificationPlanner.onEvent(AppEvent.FriendPassedYou("Ana", 3), off, noon))
        val night = LocalDateTimeLike(WED, LocalTime.of(23, 30))
        assertNull(NotificationPlanner.onEvent(AppEvent.FriendPassedYou("Ana", 3), prefs, night))
    }

    @Test
    fun levelUpListsNewBosses() {
        val n = NotificationPlanner.onEvent(AppEvent.LevelUp(5, listOf("Minotauro")), prefs, noon)!!
        assertTrue(n.body.contains("Minotauro"))
        assertEquals("¡Nivel 5!", n.title)
        assertEquals("Sigue así.", NotificationPlanner.onEvent(AppEvent.LevelUp(6, emptyList()), prefs, noon)!!.body)
    }

    @Test
    fun challengeAndAchievementEvents() {
        assertTrue(NotificationPlanner.onEvent(AppEvent.ChallengeEnding("Reto de agosto", 3, 2), prefs, noon)!!.title.contains("3 h"))
        assertEquals("Primer paso", NotificationPlanner.onEvent(AppEvent.AchievementUnlocked("Primer paso"), prefs, noon)!!.body)
        assertTrue(NotificationPlanner.onEvent(AppEvent.RankImproved(2, "Los Gym Bros"), prefs, noon)!!.body.contains("Los Gym Bros"))
        assertTrue(NotificationPlanner.onEvent(AppEvent.FriendFinishedWorkout("Mau", "30 sentadillas"), prefs, noon)!!.body.contains("30 sentadillas"))
    }

    @Test
    fun trainingDaysDefaultToMonWedFri() {
        assertEquals(setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY), prefs.trainingDays)
    }
}
