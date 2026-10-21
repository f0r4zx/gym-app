package app.gymquest.core.sharing

import app.gymquest.core.fitness.ActivityLevel
import app.gymquest.core.fitness.Equipment
import app.gymquest.core.fitness.Experience
import app.gymquest.core.fitness.Goal
import app.gymquest.core.fitness.Sex
import app.gymquest.core.fitness.UserProfile
import app.gymquest.core.fitness.WorkoutPlanner
import app.gymquest.core.social.Relation
import app.gymquest.core.social.Visibility
import app.gymquest.core.tracking.ActivityType
import app.gymquest.core.wellness.Mood
import java.time.LocalDate
import java.time.YearMonth
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val TODAY = LocalDate.of(2026, 10, 21)

class RoutineShareTest {
    private val plan = WorkoutPlanner.plan(
        UserProfile("t", TODAY.minusYears(28), Sex.MALE, 178.0, 80.0, Goal.BUILD_MUSCLE, ActivityLevel.MODERATE, Experience.INTERMEDIATE, 4, Equipment.entries.toSet()),
        0, TODAY,
    )

    @Test
    fun aRoutineSurvivesTheRoundTrip() {
        val routine = RoutineShare.fromPlan(plan, "Mi rutina de volumen", "Ana")
        val code = RoutineShare.encode(routine)
        val back = RoutineShare.decode(code) as DecodeResult.Ok
        assertEquals(routine, back.routine)
        assertTrue(back.warnings.isEmpty())
        assertEquals(4, back.routine.days.size)
    }

    @Test
    fun codesAreShortEnoughForAQrCodeAndSafeInUrls() {
        val code = RoutineShare.encode(RoutineShare.fromPlan(plan, "Rutina", "Ana"))
        assertTrue(code.length < 1_500, "code is ${code.length} chars")
        assertTrue(code.all { it.isLetterOrDigit() || it == '-' || it == '_' })
        val link = RoutineShare.link(code)
        assertTrue(link.startsWith("gymquest://routine/"))
        assertEquals(code, RoutineShare.codeFromLink(link))
        assertEquals(code, RoutineShare.codeFromLink("https://gymquest.app/r/$code"))
        assertEquals(code, RoutineShare.codeFromLink(code))
    }

    @Test
    fun garbageIsRejectedPolitely() {
        for (bad in listOf("", "   ", "no es un codigo!!", "AAAA", "x".repeat(5_000))) {
            assertTrue(RoutineShare.decode(bad) is DecodeResult.Invalid, "accepted: ${bad.take(20)}")
        }
        // Valid base64 that is not compressed data.
        val notZip = Base64.getUrlEncoder().withoutPadding().encodeToString("hola mundo".toByteArray())
        assertTrue(RoutineShare.decode(notZip) is DecodeResult.Invalid)
    }

    @Test
    fun aCompressionBombIsRefused() {
        val bomb = java.io.ByteArrayOutputStream()
        val d = java.util.zip.Deflater(9)
        d.setInput(ByteArray(300_000) { 'a'.code.toByte() })
        d.finish()
        val buf = ByteArray(4096)
        while (!d.finished()) bomb.write(buf, 0, d.deflate(buf))
        val code = Base64.getUrlEncoder().withoutPadding().encodeToString(bomb.toByteArray())
        assertTrue(code.length < RoutineShare.MAX_CODE_CHARS, "bomb should fit the size limit on the wire: ${code.length}") // so the inflate guard is what stops it
        assertTrue(RoutineShare.decode(code) is DecodeResult.Invalid)
    }

    private fun encodeRaw(r: SharedRoutine) = RoutineShare.encode(r)

    @Test
    fun unknownExercisesAreDroppedAndNumbersAreClamped() {
        val tricky = SharedRoutine(
            name = "  Rutina\u0000 rara  ", author = "Eve\u0007",
            days = listOf(SharedDay("Día 1", listOf(SharedItem("squat", 99, 0, 9_999, 99_999), SharedItem("hack_the_planet", 3, 8, 12, 60)))),
        )
        val result = RoutineShare.decode(encodeRaw(tricky)) as DecodeResult.Ok
        val item = result.routine.days.single().items.single()
        assertEquals("squat", item.id)
        assertEquals(10, item.sets)
        assertEquals(1, item.min)
        assertEquals(200, item.max)
        assertEquals(600, item.rest)
        assertEquals("Rutina rara", result.routine.name)
        assertEquals("Eve", result.routine.author)
        assertTrue(result.warnings.any { it.contains("hack_the_planet") })
    }

    @Test
    fun routinesWithNothingUsableAreInvalid() {
        val empty = SharedRoutine(name = "x", days = emptyList())
        assertTrue(RoutineShare.decode(encodeRaw(empty)) is DecodeResult.Invalid)
        val unknownOnly = SharedRoutine(name = "x", days = listOf(SharedDay("d", listOf(SharedItem("nope", 3, 8, 12, 60)))))
        assertTrue(RoutineShare.decode(encodeRaw(unknownOnly)) is DecodeResult.Invalid)
        val tooManyDays = SharedRoutine(name = "x", days = List(9) { SharedDay("d", listOf(SharedItem("squat", 3, 8, 12, 60))) })
        assertTrue(RoutineShare.decode(encodeRaw(tooManyDays)) is DecodeResult.Invalid)
        val future = SharedRoutine(v = 99, name = "x", days = listOf(SharedDay("d", listOf(SharedItem("squat", 3, 8, 12, 60)))))
        assertTrue((RoutineShare.decode(encodeRaw(future)) as DecodeResult.Invalid).reason.contains("Actualiza"))
    }

    @Test
    fun theTextVersionIsReadableInAnyChat() {
        val text = RoutineShare.toText(SharedRoutine(name = "Pierna", author = "Mau", days = listOf(SharedDay("Día de pierna", listOf(SharedItem("squat", 4, 8, 10, 90), SharedItem("lunge", 3, 12, 12, 60))))))
        assertTrue(text.contains("Pierna — por Mau") && text.contains("Sentadillas — 4×8-10, descanso 90s") && text.contains("Zancadas — 3×12"))
    }
}

class DiaryCalendarTest {
    private fun day(d: Int, minutes: Int, mood: Mood? = null) =
        DiaryEntry(LocalDate.of(2026, 10, d), workouts = if (minutes > 0) listOf(DiaryWorkout("Pierna", minutes, minutes * 6)) else emptyList(), mood = mood)

    @Test
    fun theGridIsMondayFirstAndPadded() {
        val m = DiaryCalendar.month(YearMonth.of(2026, 10), emptyList())
        // October 2026 starts on a Thursday: 3 blank squares first.
        assertEquals(3, m.cells.takeWhile { it.date == null }.size)
        assertEquals(31, m.cells.count { it.date != null })
        assertEquals(LocalDate.of(2026, 10, 1), m.cells[3].date)
        assertEquals(0, m.activeDays)
    }

    @Test
    fun intensityLevelsFollowTrainingTime() {
        assertEquals(listOf(0, 1, 2, 3, 4), listOf(0, 10, 20, 45, 90).map { DiaryCalendar.level(it) })
        val m = DiaryCalendar.month(YearMonth.of(2026, 10), listOf(day(5, 20), day(6, 90)))
        assertEquals(2, m.cells.first { it.date?.dayOfMonth == 5 }.level)
        assertEquals(4, m.cells.first { it.date?.dayOfMonth == 6 }.level)
    }

    @Test
    fun streaksLightUpAndTotalsAddUp() {
        val entries = listOf(day(1, 30), day(2, 30), day(3, 30), day(4, 0), day(10, 60), day(11, 60))
        val m = DiaryCalendar.month(YearMonth.of(2026, 10), entries)
        assertEquals(5, m.activeDays)
        assertEquals(210, m.totalMinutes)
        assertEquals(3, m.longestStreakInMonth)
        val flames = m.cells.filter { it.inStreak }.mapNotNull { it.date?.dayOfMonth }
        assertEquals(listOf(1, 2, 3, 10, 11), flames)
        assertTrue(m.cells.first { it.date?.dayOfMonth == 4 }.let { !it.inStreak && it.level == 0 })
    }

    @Test
    fun activitiesAndMoodsAppearInTheCalendar() {
        val e = DiaryEntry(LocalDate.of(2026, 10, 7), activities = listOf(DiaryActivity(ActivityType.RUN, 5.2, 31)), mood = Mood.GREAT)
        val m = DiaryCalendar.month(YearMonth.of(2026, 10), listOf(e))
        val cell = m.cells.first { it.date?.dayOfMonth == 7 }
        assertTrue(cell.hasActivity && !cell.hasWorkout && cell.mood == Mood.GREAT && cell.level == 3)
        assertEquals(5.2, m.totalKm, 1e-9)
    }

    @Test
    fun severalEntriesOnOneDayAreCombined() {
        val m = DiaryCalendar.month(YearMonth.of(2026, 10), listOf(day(8, 20), day(8, 25)))
        assertEquals(4 - 1, m.cells.first { it.date?.dayOfMonth == 8 }.level) // 45 min -> level 3
        assertEquals(1, m.activeDays)
    }

    @Test
    fun theSummaryReadsLikeAShareableLine() {
        val m = DiaryCalendar.month(YearMonth.of(2026, 10), listOf(day(1, 90), day(2, 60)))
        val text = DiaryCalendar.summaryText(m)
        assertTrue(text.startsWith("Octubre 2026: 2 días activos, 2 h 30 min"))
        assertTrue(text.contains("mejor racha del mes: 2 días"))
    }

    @Test
    fun friendsOnlySeeWhatTheOwnerAllows() {
        val entries = listOf(DiaryEntry(TODAY, workouts = listOf(DiaryWorkout("Pierna", 60, 400)), kcalEaten = 2200, weightKg = 80.0, waterMl = 2500, sleepHours = 7.5, note = "me duele la rodilla"))
        assertTrue(DiaryShare.visible(entries, Relation.FRIEND, Visibility.PRIVATE).isEmpty())
        assertTrue(DiaryShare.visible(entries, Relation.STRANGER, Visibility.FRIENDS).isEmpty())
        val friend = DiaryShare.visible(entries, Relation.FRIEND, Visibility.FRIENDS).single()
        assertEquals(1, friend.workouts.size)
        assertNull(friend.weightKg); assertNull(friend.kcalEaten); assertNull(friend.sleepHours)
        assertEquals("", friend.note)
        val opened = DiaryShare.visible(entries, Relation.FRIEND, Visibility.FRIENDS, showBodyAndFood = true).single()
        assertEquals(80.0, opened.weightKg)
        assertEquals("", opened.note) // notes are never shared
        assertEquals(entries, DiaryShare.visible(entries, Relation.SELF, Visibility.PRIVATE))
        assertTrue(DiaryShare.visible(entries, Relation.BLOCKED, Visibility.PUBLIC).isEmpty())
    }
}
