package app.gymquest.core.wellness

import app.gymquest.core.fitness.ActivityLevel
import app.gymquest.core.fitness.Experience
import app.gymquest.core.fitness.Goal
import app.gymquest.core.fitness.NutritionPlanner
import app.gymquest.core.fitness.Sex
import app.gymquest.core.fitness.UserProfile
import app.gymquest.core.nutrition.Macros
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val TODAY = LocalDate.of(2026, 10, 21)

class SkincareTest {
    @Test
    fun routinesAdaptToSkinType() {
        val oily = SkincareRoutines.forSkin(SkinType.OILY)
        val dry = SkincareRoutines.forSkin(SkinType.DRY)
        assertTrue(oily.first { it.id == "am_wash" }.description.contains("gel"))
        assertTrue(dry.first { it.id == "pm_moisturizer" }.description.contains("rica"))
        assertTrue(SkincareRoutines.forSkin(SkinType.SENSITIVE).none { it.id == "wk_exfoliate" })
        assertTrue(SkincareRoutines.forSkin(SkinType.NORMAL, acneProne = true).first { it.id == "pm_treatment" }.name.contains("acné"))
    }

    @Test
    fun everyRoutineHasSunscreenInTheMorningAndStableIds() {
        for (t in SkinType.entries) for (acne in listOf(true, false)) {
            val r = SkincareRoutines.forSkin(t, acne)
            assertTrue(r.any { it.id == "am_spf" && it.part == DayPart.MORNING && !it.optional }, "$t")
            assertEquals(r.size, r.map { it.id }.toSet().size)
        }
    }

    @Test
    fun completionIgnoresOptionalAndWeeklySteps() {
        val routine = SkincareRoutines.forSkin(SkinType.NORMAL)
        val required = routine.filter { !it.optional && !it.weekly }.map { it.id }
        var day = SkincareDay(TODAY)
        assertEquals(0.0, day.fraction(routine))
        required.dropLast(1).forEach { day = day.toggle(it) }
        assertTrue(day.fraction(routine) in 0.5..0.99)
        assertTrue(!day.isComplete(routine))
        day = day.toggle(required.last())
        assertTrue(day.isComplete(routine))
        day = day.toggle(required.last()) // un-check
        assertTrue(!day.isComplete(routine))
    }

    @Test
    fun faceWashCounterAndAdvice() {
        var d = SkincareDay(TODAY)
        assertTrue(SkincareStats.washAdvice(d.faceWashes).contains("Aún no"))
        d = d.washFace().washFace()
        assertEquals(2, d.faceWashes)
        assertTrue(SkincareStats.washAdvice(2).contains("Perfecto"))
        assertTrue(SkincareStats.washAdvice(3).contains("enjuaga"))
        assertTrue(SkincareStats.washAdvice(5).contains("Demasiadas"))
    }

    @Test
    fun streakCountsCompleteDaysOnly() {
        val routine = SkincareRoutines.forSkin(SkinType.NORMAL)
        val all = routine.filter { !it.optional && !it.weekly }.map { it.id }.toSet()
        fun full(offset: Long) = SkincareDay(TODAY.minusDays(offset), all)
        val days = listOf(full(1), full(2), SkincareDay(TODAY.minusDays(3), setOf("am_wash")), full(4))
        assertEquals(2, SkincareStats.streak(days, routine, TODAY))
        assertEquals(3, SkincareStats.streak(days + full(0), routine, TODAY))
        assertEquals(0, SkincareStats.streak(emptyList(), routine, TODAY))
    }
}

class WeightTrendTest {
    private fun series(start: Double, perWeek: Double, weeks: Int = 4, noise: Double = 0.0): List<WeightEntry> =
        (0..weeks * 7).map { d ->
            val wobble = if (d % 2 == 0) noise else -noise
            WeightEntry(TODAY.minusDays((weeks * 7 - d).toLong()), start + perWeek / 7.0 * d + wobble)
        }

    @Test
    fun slopeRecoversTheTrueRateDespiteDailyNoise() {
        val rate = WeightTrend.weeklyRateKg(series(85.0, -0.5, noise = 0.6))!!
        assertEquals(-0.5, rate, 0.1)
    }

    @Test
    fun needsEnoughDataSpanningAWeek() {
        assertNull(WeightTrend.weeklyRateKg(emptyList()))
        assertNull(WeightTrend.weeklyRateKg(listOf(WeightEntry(TODAY, 80.0), WeightEntry(TODAY.minusDays(1), 80.2))))
        val shortSpan = (0..4).map { WeightEntry(TODAY.minusDays(it.toLong()), 80.0 + it * 0.1) }
        assertNull(WeightTrend.weeklyRateKg(shortSpan))
        assertEquals(TrendStatus.INSUFFICIENT_DATA, WeightTrend.assess(shortSpan, Goal.LOSE_FAT).status)
    }

    @Test
    fun smoothingDampensSpikes() {
        val spiky = listOf(80.0, 80.0, 82.0, 80.0, 80.0).mapIndexed { i, kg -> WeightEntry(TODAY.minusDays((4 - i).toLong()), kg) }
        val s = WeightTrend.smoothed(spiky)
        assertTrue(s[2].kg < 81.0)
        assertEquals(5, s.size)
        assertEquals(spiky.first().date, s.first().date)
    }

    @Test
    fun cuttingAssessments() {
        assertEquals(TrendStatus.ON_TRACK, WeightTrend.assess(series(85.0, -0.5), Goal.LOSE_FAT).status)
        assertEquals(TrendStatus.TOO_FAST, WeightTrend.assess(series(85.0, -1.2), Goal.LOSE_FAT).status)
        assertEquals(TrendStatus.TOO_SLOW, WeightTrend.assess(series(85.0, -0.05), Goal.LOSE_FAT).status)
        assertEquals(TrendStatus.WRONG_DIRECTION, WeightTrend.assess(series(85.0, +0.4), Goal.LOSE_FAT).status)
    }

    @Test
    fun bulkingAssessments() {
        assertEquals(TrendStatus.ON_TRACK, WeightTrend.assess(series(70.0, +0.25), Goal.BUILD_MUSCLE).status)
        assertEquals(TrendStatus.TOO_FAST, WeightTrend.assess(series(70.0, +0.8), Goal.BUILD_MUSCLE).status)
        assertEquals(TrendStatus.WRONG_DIRECTION, WeightTrend.assess(series(70.0, -0.4), Goal.BUILD_MUSCLE).status)
        assertEquals(TrendStatus.TOO_SLOW, WeightTrend.assess(series(70.0, 0.0), Goal.BUILD_MUSCLE).status)
    }

    @Test
    fun maintenanceAssessments() {
        assertEquals(TrendStatus.ON_TRACK, WeightTrend.assess(series(75.0, 0.05, noise = 0.3), Goal.MAINTAIN).status)
        assertEquals(TrendStatus.WRONG_DIRECTION, WeightTrend.assess(series(75.0, 0.7), Goal.MAINTAIN).status)
    }
}

class DailyCoachTest {
    private val profile = UserProfile("t", TODAY.minusYears(30), Sex.MALE, 178.0, 80.0, Goal.LOSE_FAT, ActivityLevel.MODERATE, Experience.INTERMEDIATE, 4)
    private val targets = NutritionPlanner.targets(profile, TODAY)

    private fun snapshot(
        hour: Double = 15.0, eatenFraction: Double = 0.6, protein: Double = 0.6, water: Int = 1500,
        workouts: Int = 1, active: Int = 45, skin: Double = 1.0, streak: Int = 0,
    ) = DaySnapshot(
        goal = Goal.LOSE_FAT, targets = targets,
        eaten = Macros(targets.kcal * eatenFraction, targets.proteinG * protein, 100.0, 40.0),
        waterMl = water, workoutsToday = workouts, activeMinutes = active, skincareFraction = skin, hourNow = hour, streakDays = streak,
    )

    @Test
    fun aGreatDayFeelsGreat() {
        val r = DailyCoach.report(snapshot(eatenFraction = 0.55, protein = 0.6, water = 1600, workouts = 1))
        assertEquals(Mood.GREAT, r.mood)
        assertTrue(r.score >= 80, "score ${r.score}")
    }

    @Test
    fun aLazyDayFeelsWorseAndGetsActionableTips() {
        val r = DailyCoach.report(snapshot(hour = 20.0, eatenFraction = 0.2, protein = 0.15, water = 300, workouts = 0, active = 0, skin = 0.0))
        assertTrue(r.mood in setOf(Mood.TIRED, Mood.SAD, Mood.OK), "mood ${r.mood}")
        assertTrue(r.score < 50)
        assertTrue(r.tips.any { it.contains("agua") })
        assertTrue(r.tips.size <= 4)
    }

    @Test
    fun scoreRisesWithBetterHabits() {
        val bad = DailyCoach.report(snapshot(hour = 19.0, water = 200, workouts = 0, active = 0)).score
        val good = DailyCoach.report(snapshot(hour = 19.0, water = 2200, workouts = 1, active = 50)).score
        assertTrue(good > bad + 20, "good=$good bad=$bad")
    }

    @Test
    fun overshootingCaloriesIsHandledKindly() {
        val r = DailyCoach.report(snapshot(hour = 21.0, eatenFraction = 1.4, protein = 1.0, water = 2400))
        assertTrue(r.tips.any { it.contains("meta de calorías") })
        assertTrue(r.tips.none { it.lowercase().contains("mal") || it.lowercase().contains("culpa") })
    }

    @Test
    fun earlyMorningIsNotPunished() {
        val r = DailyCoach.report(snapshot(hour = 7.5, eatenFraction = 0.0, protein = 0.0, water = 0, workouts = 0, active = 0, skin = 0.0))
        assertTrue(r.score >= 40, "score ${r.score}")
    }

    @Test
    fun streakIsCelebrated() {
        assertTrue(DailyCoach.report(snapshot(streak = 5)).tips.any { it.contains("5 días") })
    }

    @Test
    fun alwaysProducesAtLeastOneTip() {
        for (h in listOf(6.0, 10.0, 14.0, 18.0, 22.0)) for (f in listOf(0.0, 0.5, 1.0, 1.5)) {
            val r = DailyCoach.report(snapshot(hour = h, eatenFraction = f))
            assertTrue(r.tips.isNotEmpty() && r.headline.isNotBlank() && r.score in 0..100)
        }
    }
}
