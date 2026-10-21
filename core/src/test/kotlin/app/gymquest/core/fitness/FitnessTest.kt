package app.gymquest.core.fitness

import app.gymquest.core.exercise.ExerciseType
import app.gymquest.core.exercise.MuscleGroup
import java.time.LocalDate
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val TODAY = LocalDate.of(2026, 10, 21)

private fun profile(
    sex: Sex = Sex.MALE,
    weight: Double = 80.0,
    height: Double = 178.0,
    age: Int = 30,
    goal: Goal = Goal.MAINTAIN,
    level: ActivityLevel = ActivityLevel.MODERATE,
    exp: Experience = Experience.INTERMEDIATE,
    days: Int = 4,
    equipment: Set<Equipment> = setOf(Equipment.BODYWEIGHT),
    target: Double? = null,
) = UserProfile(
    name = "Test", birthDate = TODAY.minusYears(age.toLong()), sex = sex, heightCm = height, weightKg = weight,
    goal = goal, activityLevel = level, experience = exp, daysPerWeek = days, equipment = equipment, targetWeightKg = target,
)

class BodyMetricsTest {
    @Test
    fun bmiAndCategories() {
        assertEquals(25.2, BodyMetrics.bmi(80.0, 178.0), 0.05)
        assertEquals(BmiCategory.UNDERWEIGHT, BodyMetrics.bmiCategory(17.0))
        assertEquals(BmiCategory.NORMAL, BodyMetrics.bmiCategory(22.0))
        assertEquals(BmiCategory.OVERWEIGHT, BodyMetrics.bmiCategory(27.0))
        assertEquals(BmiCategory.OBESE, BodyMetrics.bmiCategory(33.0))
    }

    @Test
    fun healthyRangeMatchesBmiBounds() {
        val r = BodyMetrics.healthyWeightRangeKg(170.0)
        assertEquals(53.5, r.start, 0.1)
        assertEquals(72.0, r.endInclusive, 0.1)
    }

    @Test
    fun mifflinStJeorMatchesTextbookValues() {
        // Male 30y 80kg 178cm: 10*80 + 6.25*178 - 5*30 + 5 = 1767.5
        assertEquals(1767.5, BodyMetrics.bmr(Sex.MALE, 80.0, 178.0, 30), 0.01)
        // Female 25y 60kg 165cm: 600 + 1031.25 - 125 - 161 = 1345.25
        assertEquals(1345.25, BodyMetrics.bmr(Sex.FEMALE, 60.0, 165.0, 25), 0.01)
        val other = BodyMetrics.bmr(Sex.OTHER, 70.0, 170.0, 28)
        val m = BodyMetrics.bmr(Sex.MALE, 70.0, 170.0, 28)
        val f = BodyMetrics.bmr(Sex.FEMALE, 70.0, 170.0, 28)
        assertEquals((m + f) / 2, other, 0.01)
    }

    @Test
    fun tdeeScalesWithActivity() {
        val bmr = 1700.0
        assertEquals(2040.0, BodyMetrics.tdee(bmr, ActivityLevel.SEDENTARY), 0.01)
        assertTrue(BodyMetrics.tdee(bmr, ActivityLevel.ACTIVE) > BodyMetrics.tdee(bmr, ActivityLevel.LIGHT))
    }

    @Test
    fun bodyFatEstimatesAreSensible() {
        val male = BodyMetrics.bodyFatFromBmi(25.0, 30, Sex.MALE)
        val female = BodyMetrics.bodyFatFromBmi(25.0, 30, Sex.FEMALE)
        assertTrue(female > male + 8)
        assertTrue(male in 15.0..30.0)
    }

    @Test
    fun navyMethodNeedsHipsForWomenAndRejectsImpossibleInputs() {
        val m = BodyMetrics.bodyFatNavy(Sex.MALE, 178.0, 38.0, 90.0)
        assertNotNull(m)
        assertTrue(m in 10.0..25.0, "male navy bf $m")
        assertNull(BodyMetrics.bodyFatNavy(Sex.FEMALE, 165.0, 32.0, 75.0, null))
        val f = BodyMetrics.bodyFatNavy(Sex.FEMALE, 165.0, 32.0, 75.0, 98.0)
        assertNotNull(f)
        assertTrue(f in 15.0..40.0, "female navy bf $f")
        assertNull(BodyMetrics.bodyFatNavy(Sex.MALE, 178.0, 95.0, 90.0))
    }

    @Test
    fun heartRateZonesAreOrderedAndKarvonenIsHigherAtLowEnd() {
        assertEquals(187, BodyMetrics.maxHeartRate(30))
        val simple = BodyMetrics.heartRateZones(187)
        val karvonen = BodyMetrics.heartRateZones(187, restingHr = 60)
        assertEquals(5, simple.size)
        for (z in simple.zipWithNext()) assertTrue(z.first.maxBpm <= z.second.minBpm + 1)
        assertTrue(karvonen[0].minBpm > simple[0].minBpm)
        assertEquals(187, karvonen.last().maxBpm)
    }

    @Test
    fun metCaloriesFormula() {
        assertEquals(560.0, BodyMetrics.caloriesFromMet(8.0, 70.0, 60.0), 1e-9)
    }

    @Test
    fun profileValidatesRanges() {
        assertFailsWith<IllegalArgumentException> { profile(height = 50.0) }
        assertFailsWith<IllegalArgumentException> { profile(weight = 5.0) }
        assertFailsWith<IllegalArgumentException> { profile(days = 9) }
    }
}

class NutritionPlannerTest {
    @Test
    fun maintenanceEqualsTdee() {
        val t = NutritionPlanner.targets(profile(goal = Goal.MAINTAIN), TODAY)
        assertEquals(t.tdee, t.kcal)
        assertEquals(0.0, t.weeklyChangeKg, 0.01)
    }

    @Test
    fun cuttingCreatesAModestDeficitAndKeepsProteinHigh() {
        val p = profile(goal = Goal.LOSE_FAT)
        val t = NutritionPlanner.targets(p, TODAY)
        assertTrue(t.kcal < t.tdee)
        assertTrue(t.weeklyChangeKg < 0 && t.weeklyChangeKg > -0.01 * p.weightKg - 0.01, "weekly ${t.weeklyChangeKg}")
        assertEquals(160, t.proteinG)
    }

    @Test
    fun bulkingCreatesASurplus() {
        val t = NutritionPlanner.targets(profile(goal = Goal.BUILD_MUSCLE), TODAY)
        assertTrue(t.kcal > t.tdee)
        assertTrue(t.weeklyChangeKg in 0.05..0.6, "weekly ${t.weeklyChangeKg}")
    }

    @Test
    fun beginnersBulkMoreAggressivelyThanAdvanced() {
        val b = NutritionPlanner.targets(profile(goal = Goal.BUILD_MUSCLE, exp = Experience.BEGINNER), TODAY)
        val a = NutritionPlanner.targets(profile(goal = Goal.BUILD_MUSCLE, exp = Experience.ADVANCED), TODAY)
        assertTrue(b.kcal > a.kcal)
    }

    @Test
    fun macrosAddUpToCalories() {
        for (goal in Goal.entries) for (sex in Sex.entries) {
            val t = NutritionPlanner.targets(profile(sex = sex, goal = goal), TODAY)
            val sum = t.proteinG * 4 + t.carbsG * 4 + t.fatG * 9
            assertTrue(abs(sum - t.kcal) < t.kcal * 0.06, "$goal/$sex macros $sum vs ${t.kcal}")
        }
    }

    @Test
    fun neverGoesBelowASafeFloor() {
        val tiny = profile(sex = Sex.FEMALE, weight = 45.0, height = 150.0, age = 60, level = ActivityLevel.SEDENTARY, goal = Goal.LOSE_FAT)
        val t = NutritionPlanner.targets(tiny, TODAY)
        assertTrue(t.kcal >= 1200)
        assertTrue(t.notes.any { it.contains("1200") } || t.kcal > 1200)
    }

    @Test
    fun heavierPeopleCanCutFasterButNotRecklessly() {
        val heavy = profile(weight = 130.0, height = 180.0, goal = Goal.LOSE_FAT)
        val t = NutritionPlanner.targets(heavy, TODAY)
        assertTrue(t.weeklyChangeKg >= -0.011 * heavy.weightKg)
        assertTrue(t.tdee - t.kcal >= 400)
    }

    @Test
    fun waterGrowsWithBodyWeightAndTraining() {
        val rest = Hydration.targetMl(70.0)
        val train = Hydration.targetMl(70.0, exerciseMinutes = 60)
        assertEquals(2450, rest)
        assertEquals(rest + 1000, train)
        assertTrue(Hydration.targetMl(70.0, hotDay = true) > rest)
        assertEquals(10, Hydration.glasses(2450))
    }

    @Test
    fun hydrationRemindersAvoidTheHourBeforeBed() {
        val hours = Hydration.reminderHours(7, 23)
        assertEquals(listOf(8, 10, 12, 14, 16, 18, 20), hours)
        val night = Hydration.reminderHours(14, 2)
        assertTrue(night.all { it in 15..23 || it in 0..0 })
    }

    @Test
    fun hydrationPace() {
        assertEquals(Hydration.Pace.BEHIND, Hydration.pace(200, 2500, 15.0, 7, 23))
        assertEquals(Hydration.Pace.ON_TRACK, Hydration.pace(1200, 2500, 15.0, 7, 23))
        assertEquals(Hydration.Pace.AHEAD, Hydration.pace(2000, 2500, 12.0, 7, 23))
    }

    @Test
    fun forecastDirectionMatters() {
        assertEquals(8, WeightForecast.weeksToReach(80.0, 76.0, -0.5))
        assertNull(WeightForecast.weeksToReach(80.0, 76.0, +0.3))
        assertEquals(0, WeightForecast.weeksToReach(80.0, 80.1, -0.5))
    }
}

class HealthAdvisorTest {
    @Test
    fun warnsAboutUnderweightTargets() {
        val notes = HealthAdvisor.review(profile(weight = 60.0, height = 170.0, goal = Goal.LOSE_FAT, target = 45.0), TODAY)
        assertTrue(notes.any { it.severity == Severity.WARNING && it.text.contains("rango saludable") })
    }

    @Test
    fun flagsGoalsThatContradictTheTargetWeight() {
        val cut = HealthAdvisor.review(profile(goal = Goal.LOSE_FAT, target = 90.0), TODAY)
        assertTrue(cut.any { it.severity == Severity.TIP })
        val bulk = HealthAdvisor.review(profile(goal = Goal.BUILD_MUSCLE, target = 70.0), TODAY)
        assertTrue(bulk.any { it.severity == Severity.TIP })
    }

    @Test
    fun givesATimelineForSensibleGoals() {
        val notes = HealthAdvisor.review(profile(weight = 90.0, goal = Goal.LOSE_FAT, target = 80.0), TODAY)
        assertTrue(notes.any { it.text.contains("semanas") })
    }

    @Test
    fun warnsMinorsAndAlwaysIncludesAMedicalDisclaimer() {
        val kid = HealthAdvisor.review(profile(age = 14), TODAY)
        assertTrue(kid.any { it.severity == Severity.WARNING && it.text.contains("16") })
        assertTrue(kid.any { it.text.contains("médico") })
    }

    @Test
    fun doesNotPushUnderweightPeopleToLoseWeight() {
        val notes = HealthAdvisor.review(profile(weight = 50.0, height = 175.0, goal = Goal.LOSE_FAT), TODAY)
        assertTrue(notes.any { it.severity == Severity.WARNING && it.text.contains("no es recomendable") })
    }
}

class WorkoutPlannerTest {
    private fun plan(days: Int, goal: Goal = Goal.BUILD_MUSCLE, equip: Set<Equipment> = setOf(Equipment.BODYWEIGHT), exp: Experience = Experience.INTERMEDIATE) =
        WorkoutPlanner.plan(profile(days = days, goal = goal, equipment = equip, exp = exp), 0, TODAY)

    @Test
    fun hasSevenDaysAndTheRequestedNumberOfTrainingDays() {
        for (d in 1..7) {
            val p = plan(d)
            assertEquals(7, p.days.size)
            val training = p.days.count { it.items.isNotEmpty() }
            assertEquals(minOf(d, 6), training, "days=$d")
        }
    }

    @Test
    fun restDaysSuggestEasyMovement() {
        val rest = plan(3).days.first { it.items.isEmpty() }
        assertEquals("Descanso", rest.title)
        assertNotNull(rest.cardioNote)
    }

    @Test
    fun bodyweightOnlyPlansUseOnlyBodyweightExercises() {
        val p = plan(4, equip = setOf(Equipment.BODYWEIGHT), exp = Experience.BEGINNER)
        for (day in p.days) for (item in day.items) {
            assertTrue(item.exercise.needs.all { it == Equipment.BODYWEIGHT }, "${item.exercise.name} needs ${item.exercise.needs}")
            assertTrue(item.exercise.minLevel == Experience.BEGINNER)
        }
    }

    @Test
    fun equipmentUnlocksMoreExercises() {
        val bw = plan(4, equip = setOf(Equipment.BODYWEIGHT)).days.flatMap { it.items }.map { it.exercise.id }.toSet()
        val gym = plan(4, equip = Equipment.entries.toSet()).days.flatMap { it.items }.map { it.exercise.id }.toSet()
        assertTrue(gym.any { GymCatalog.byId(it)!!.needs.any { n -> n != Equipment.BODYWEIGHT } })
        assertTrue(bw != gym)
    }

    @Test
    fun noExerciseRepeatsWithinADay() {
        for (d in 1..6) for (day in plan(d, equip = Equipment.entries.toSet()).days) {
            val ids = day.items.map { it.exercise.id }
            assertEquals(ids.size, ids.toSet().size, "duplicate in ${day.title}: $ids")
        }
    }

    @Test
    fun fourDaySplitIsUpperLowerTwice() {
        val titles = plan(4, equip = Equipment.entries.toSet()).days.filter { it.items.isNotEmpty() }.map { it.title }
        assertEquals(listOf("Tren superior", "Tren inferior", "Tren superior", "Tren inferior"), titles)
    }

    @Test
    fun repSchemesFollowTheGoal() {
        val bulk = plan(3, Goal.BUILD_MUSCLE, Equipment.entries.toSet()).days.flatMap { it.items }.first { it.exercise.compound && !it.exercise.timed }
        val cut = plan(3, Goal.LOSE_FAT, Equipment.entries.toSet()).days.flatMap { it.items }.first { it.exercise.compound && !it.exercise.timed }
        val endurance = plan(3, Goal.ENDURANCE, Equipment.entries.toSet()).days.flatMap { it.items }.first { it.exercise.compound && !it.exercise.timed }
        assertTrue(bulk.maxReps <= 10 && bulk.restSeconds >= 90)
        assertTrue(cut.restSeconds <= 60)
        assertTrue(endurance.minReps >= 15)
    }

    @Test
    fun fatLossAndEnduranceDaysEndWithConditioning() {
        val cut = plan(3, Goal.LOSE_FAT).days.first { it.items.isNotEmpty() }
        assertTrue(cut.items.last().exercise.cardio)
        assertNotNull(cut.cardioNote)
        val bulk = plan(3, Goal.BUILD_MUSCLE).days.first { it.items.isNotEmpty() }
        assertTrue(bulk.items.none { it.exercise.cardio })
    }

    @Test
    fun rotationSwapsInAlternatives() {
        val a = WorkoutPlanner.plan(profile(days = 3, equipment = Equipment.entries.toSet()), 0, TODAY).days.flatMap { it.items }.map { it.exercise.id }
        val b = WorkoutPlanner.plan(profile(days = 3, equipment = Equipment.entries.toSet()), 1, TODAY).days.flatMap { it.items }.map { it.exercise.id }
        assertTrue(a != b)
    }

    @Test
    fun timedExercisesUseSecondsRanges() {
        val plank = plan(3).days.flatMap { it.items }.firstOrNull { it.exercise.id == "plank" }
        if (plank != null) assertTrue(plank.minReps >= 30)
    }

    @Test
    fun everyTrackedExerciseHasACameraCounter() {
        for (ex in GymCatalog.all) {
            ex.tracked?.let { assertNotNull(ExerciseType.fromId(it.id)) }
        }
        assertTrue(GymCatalog.all.map { it.id }.toSet().size == GymCatalog.all.size)
        assertTrue(GymCatalog.forMuscle(MuscleGroup.CHEST).isNotEmpty())
    }

    @Test
    fun everyMuscleGroupHasABodyweightOrEquipmentOption() {
        val pool = GymCatalog.available(setOf(Equipment.BODYWEIGHT), Experience.BEGINNER)
        for (m in listOf(MuscleGroup.CHEST, MuscleGroup.QUADS, MuscleGroup.CORE, MuscleGroup.GLUTES)) {
            assertTrue(pool.any { it.primary == m }, "no bodyweight option for $m")
        }
    }
}

class StrengthTest {
    @Test
    fun oneRepMaxFormulas() {
        assertEquals(100.0, OneRepMax.estimate(100.0, 1))
        assertEquals(133.3, OneRepMax.estimate(100.0, 10), 0.1)
        assertEquals(0.0, OneRepMax.estimate(100.0, 0))
        assertTrue(OneRepMax.estimate(60.0, 15) > OneRepMax.estimate(60.0, 8))
    }

    @Test
    fun weightForRepsInvertsEstimate() {
        for (reps in listOf(3, 5, 8, 10, 12, 15)) {
            val one = OneRepMax.estimate(80.0, reps)
            assertEquals(80.0, OneRepMax.weightForReps(one, reps), 0.5, "reps=$reps")
        }
    }

    @Test
    fun platesPerSide() {
        val load = PlateCalculator.perSide(100.0)
        assertEquals(listOf(25.0, 15.0), load.platesPerSide)
        assertEquals(100.0, load.achievedKg)
        val odd = PlateCalculator.perSide(61.0)
        assertTrue(odd.achievedKg <= 61.0 && odd.achievedKg >= 60.0)
        assertTrue(PlateCalculator.perSide(15.0).platesPerSide.isEmpty())
    }

    @Test
    fun doubleProgression() {
        val full = OverloadAdvisor.next(listOf(SetLog(12, 40.0), SetLog(12, 40.0), SetLog(12, 40.0)), 8, 12)!!
        assertEquals(OverloadAction.ADD_WEIGHT, full.action)
        assertEquals(42.5, full.weightKg)
        assertEquals(8, full.reps)

        val mid = OverloadAdvisor.next(listOf(SetLog(10, 40.0), SetLog(9, 40.0), SetLog(9, 40.0)), 8, 12)!!
        assertEquals(OverloadAction.ADD_REPS, mid.action)
        assertEquals(10, mid.reps)

        val struggling = OverloadAdvisor.next(listOf(SetLog(5, 40.0), SetLog(4, 40.0)), 8, 12)!!
        assertEquals(OverloadAction.DELOAD, struggling.action)
        assertTrue(struggling.weightKg < 40.0)

        val almost = OverloadAdvisor.next(listOf(SetLog(7, 40.0), SetLog(7, 40.0)), 8, 12)!!
        assertEquals(OverloadAction.HOLD, almost.action)

        assertNull(OverloadAdvisor.next(emptyList(), 8, 12))
    }

    @Test
    fun highEffortBlocksWeightIncrease() {
        val s = OverloadAdvisor.next(listOf(SetLog(12, 40.0, rpe = 9.8), SetLog(12, 40.0, rpe = 9.9)), 8, 12)!!
        assertEquals(OverloadAction.ADD_REPS, s.action) // already at max reps but too hard to add weight
    }

    @Test
    fun personalRecords() {
        val history = listOf(SetLog(8, 60.0), SetLog(5, 70.0))
        val best = PersonalRecords.best(history)!!
        assertEquals(70.0, best.weightKg)
        assertTrue(PersonalRecords.isNewRecord(history, SetLog(5, 75.0)))
        assertTrue(!PersonalRecords.isNewRecord(history, SetLog(5, 65.0)))
        assertTrue(PersonalRecords.isNewRecord(emptyList(), SetLog(5, 10.0)))
        assertNull(PersonalRecords.best(listOf(SetLog(10, 0.0))))
        assertEquals(780.0, PersonalRecords.volume(listOf(SetLog(8, 60.0), SetLog(5, 60.0))))
    }
}
