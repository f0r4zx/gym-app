package app.gymquest.core.fitness

import app.gymquest.core.exercise.ExerciseType
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val TODAY = LocalDate.of(2026, 10, 21)

private fun person(
    age: Int, goal: Goal = Goal.GENERAL_HEALTH, exp: Experience = Experience.BEGINNER,
    equip: Set<Equipment> = setOf(Equipment.BODYWEIGHT), days: Int = 4,
) = UserProfile("t", TODAY.minusYears(age.toLong()), Sex.OTHER, 170.0, 70.0, goal, ActivityLevel.LIGHT, exp, days, equip)

class AgeGuidanceTest {
    @Test
    fun everyAgeMapsToABand() {
        for (age in 10..100) {
            val band = AgeBand.of(age)
            assertTrue(age < 13 && band == AgeBand.TEEN || age in band.range, "age $age -> $band")
        }
        assertEquals(AgeBand.TEEN, AgeBand.of(13))
        assertEquals(AgeBand.YOUNG_ADULT, AgeBand.of(18))
        assertEquals(AgeBand.SENIOR, AgeBand.of(65))
    }

    @Test
    fun adviceGetsMoreCautiousWithAge() {
        val young = AgeGuidance.advice(25)
        val mature = AgeGuidance.advice(58)
        val senior = AgeGuidance.advice(72)
        assertTrue(young.warmupMinutes < mature.warmupMinutes)
        assertTrue(young.maxHardDaysPerWeek > mature.maxHardDaysPerWeek)
        assertTrue(senior.weekly.balanceDays >= 3 && young.weekly.balanceDays == 0)
        assertTrue(senior.cautions.any { it.contains("médico") })
        assertTrue(young.maxHeartRate > senior.maxHeartRate)
    }

    @Test
    fun teensGetSupervisionWarningAndNoDietAdvice() {
        val teen = AgeGuidance.advice(15)
        assertTrue(teen.cautions.any { it.contains("supervise") })
        assertTrue(teen.cautions.any { it.contains("dietas") })
        assertEquals(420, teen.weekly.moderateMinutes) // 60 minutes a day
    }

    @Test
    fun teensAvoidHeavyBarbellLifts() {
        val deadlift = GymCatalog.byId("deadlift")!!
        val squat = GymCatalog.byId("squat")!!
        assertTrue(!AgeGuidance.allows(deadlift, 15, Experience.ADVANCED))
        assertTrue(AgeGuidance.allows(squat, 15, Experience.BEGINNER))
        assertTrue(AgeGuidance.allows(deadlift, 25, Experience.INTERMEDIATE))
    }

    @Test
    fun seniorsGetLowImpactOnly() {
        for (ex in GymCatalog.all) {
            if (AgeGuidance.allows(ex, 70, Experience.INTERMEDIATE)) {
                assertEquals(Impact.LOW, ex.impact, ex.id)
                assertTrue(!ex.heavyLoad, ex.id)
            }
        }
        assertTrue(!AgeGuidance.allows(GymCatalog.byId("burpee")!!, 70, Experience.ADVANCED))
        assertTrue(AgeGuidance.allows(GymCatalog.byId("single_leg_stand")!!, 70, Experience.BEGINNER))
    }

    @Test
    fun matureAdultsSkipHighImpactUnlessAdvanced() {
        val burpee = GymCatalog.byId("burpee")!!
        assertTrue(!AgeGuidance.allows(burpee, 55, Experience.INTERMEDIATE))
        assertTrue(AgeGuidance.allows(burpee, 55, Experience.ADVANCED))
        assertTrue(AgeGuidance.allows(burpee, 30, Experience.INTERMEDIATE))
    }
}

class ExerciseRecommenderTest {
    @Test
    fun fatLossFavoursBigCompoundAndCardioMoves() {
        val recs = ExerciseRecommender.recommend(person(28, Goal.LOSE_FAT, Experience.INTERMEDIATE), TODAY, 6)
        assertEquals(6, recs.size)
        assertTrue(recs.count { it.exercise.compound || it.exercise.cardio } >= 5, recs.joinToString { it.exercise.name })
        assertTrue(recs.zipWithNext().all { it.first.score >= it.second.score })
    }

    @Test
    fun muscleGainFavoursCompoundsOverCardio() {
        val gym = Equipment.entries.toSet()
        val recs = ExerciseRecommender.recommend(person(24, Goal.BUILD_MUSCLE, Experience.INTERMEDIATE, gym), TODAY, 8)
        assertTrue(recs.all { !it.exercise.cardio }, recs.joinToString { it.exercise.name })
        assertTrue(recs.take(5).all { it.exercise.compound })
    }

    @Test
    fun enduranceFavoursCardio() {
        val recs = ExerciseRecommender.recommend(person(27, Goal.ENDURANCE), TODAY, 4)
        assertTrue(recs.any { it.exercise.cardio })
    }

    @Test
    fun differentAgesGetDifferentRecommendations() {
        val gym = Equipment.entries.toSet()
        val young = ExerciseRecommender.recommend(person(24, Goal.GENERAL_HEALTH, Experience.INTERMEDIATE, gym), TODAY, 8).map { it.exercise.id }
        val senior = ExerciseRecommender.recommend(person(70, Goal.GENERAL_HEALTH, Experience.INTERMEDIATE, gym), TODAY, 8).map { it.exercise.id }
        assertTrue(young != senior)
        assertTrue(senior.all { GymCatalog.byId(it)!!.impact == Impact.LOW })
        assertTrue(senior.any { GymCatalog.byId(it)!!.balance })
    }

    @Test
    fun teenRecommendationsAreBodyweightFriendly() {
        val recs = ExerciseRecommender.recommend(person(15, Goal.BUILD_MUSCLE, Experience.INTERMEDIATE, Equipment.entries.toSet()), TODAY, 8)
        assertTrue(recs.none { it.exercise.heavyLoad })
    }

    @Test
    fun everyRecommendationExplainsItself() {
        for (age in listOf(15, 30, 50, 70)) for (goal in Goal.entries) {
            val recs = ExerciseRecommender.recommend(person(age, goal, Experience.INTERMEDIATE, Equipment.entries.toSet()), TODAY, 5)
            assertTrue(recs.isNotEmpty(), "$age/$goal")
            assertTrue(recs.all { it.reasons.isNotEmpty() && it.reasons.size <= 3 }, "$age/$goal")
        }
    }

    @Test
    fun cameraTrackedExercisesAreFlagged() {
        val recs = ExerciseRecommender.recommend(person(30, Goal.LOSE_FAT), TODAY, 8)
        val squat = recs.firstOrNull { it.exercise.tracked == ExerciseType.SQUAT }
        if (squat != null) assertTrue(squat.reasons.any { it.contains("cámara") })
    }

    @Test
    fun byMuscleGroupsResults() {
        val groups = ExerciseRecommender.byMuscle(person(30, Goal.MAINTAIN, equip = Equipment.entries.toSet()), TODAY, 2)
        assertTrue(groups.size >= 6)
        assertTrue(groups.values.all { it.size <= 2 })
    }

    @Test
    fun plannerCapsHardDaysForOlderLifters() {
        val plan = WorkoutPlanner.plan(person(68, Goal.MAINTAIN, days = 6, equip = Equipment.entries.toSet()), 0, TODAY)
        assertEquals(3, plan.days.count { it.items.isNotEmpty() })
        assertTrue(plan.notes.any { it.contains("máximo 3 días") })
        assertTrue(plan.days.flatMap { it.items }.all { it.exercise.impact == Impact.LOW && !it.exercise.heavyLoad })
    }

    @Test
    fun plannerWarmupFollowsAge() {
        val young = WorkoutPlanner.plan(person(22), 0, TODAY).notes.first()
        val older = WorkoutPlanner.plan(person(55), 0, TODAY).notes.first()
        assertTrue(young.contains("5 min"))
        assertTrue(older.contains("10 min"))
    }

    @Test
    fun plannerNeverBuildsAnEmptyPlanForAnyone() {
        for (age in listOf(13, 16, 30, 45, 60, 80)) for (exp in Experience.entries) for (days in 1..7) {
            val plan = WorkoutPlanner.plan(person(age, Goal.GENERAL_HEALTH, exp, setOf(Equipment.BODYWEIGHT), days), 0, TODAY)
            assertTrue(plan.days.any { it.items.isNotEmpty() }, "age=$age exp=$exp days=$days")
        }
    }
}
