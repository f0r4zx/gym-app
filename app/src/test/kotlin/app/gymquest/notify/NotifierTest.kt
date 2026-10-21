package app.gymquest.notify

import app.gymquest.core.notifications.NotificationType
import app.gymquest.core.notifications.NotificationPlanner
import app.gymquest.core.exercise.ExerciseType
import app.gymquest.data.Actions
import app.gymquest.data.AppState
import app.gymquest.data.ProfileDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class NotifierTest {
    private val today = LocalDate.of(2026, 10, 21)

    private fun started(): AppState = Actions.completeOnboarding(
        AppState(),
        ProfileDto(
            name = "Ana", birthEpochDay = today.minusYears(30).toEpochDay(), sex = "FEMALE", heightCm = 165.0, weightKg = 60.0,
            goal = "MAINTAIN", activity = "LIGHT", experience = "BEGINNER", daysPerWeek = 3, equipment = listOf("BODYWEIGHT"),
        ),
        today,
    )

    @Test
    fun noProfileMeansNoDayState() {
        assertEquals(null, Notifier.dayState(AppState(), today))
    }

    @Test
    fun theDayStateReflectsWhatWasDone() {
        var s = started()
        val before = Notifier.dayState(s, today)!!
        assertFalse(before.hasActivityToday)
        assertEquals(0, before.waterMl)

        s = Actions.addWater(s, 2, today).state
        s = Actions.finishSet(s, ExerciseType.SQUAT, 10, 0.9, 30_000, 1L, today).state
        val after = Notifier.dayState(s, today)!!
        assertTrue(after.hasActivityToday)
        assertEquals(500, after.waterMl)
        assertEquals(1, after.streakDays)
    }

    @Test
    fun defaultsEnableTheDefaultTypesAndTogglesAreRespected() {
        val s = started()
        assertTrue(NotificationType.WATER in Notifier.prefsFrom(s).enabled)
        val custom = s.copy(settings = s.settings.copy(notificationTypes = listOf(NotificationType.WATER.name)))
        val prefs = Notifier.prefsFrom(custom)
        assertEquals(setOf(NotificationType.WATER), prefs.enabled)
    }

    @Test
    fun plannedRemindersSkipWhatIsAlreadyDone() {
        val s = started()
        val plan = NotificationPlanner.planDay(Notifier.prefsFrom(s), Notifier.dayState(s, today)!!, LocalTime.of(7, 0))
        assertNotNull(plan)
        assertTrue("a fresh day has reminders", plan.isNotEmpty())
        assertTrue(plan.all { it.date == today })
    }
}
