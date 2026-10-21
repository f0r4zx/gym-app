package app.gymquest.data

import app.gymquest.core.exercise.ExerciseType
import app.gymquest.core.fitness.Goal
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ActionsTest {
    private val today = LocalDate.of(2026, 10, 21)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; coerceInputValues = true }

    private fun profile() = ProfileDto(
        name = "Ana", birthEpochDay = today.minusYears(28).toEpochDay(), sex = "FEMALE", heightCm = 165.0, weightKg = 62.0,
        goal = Goal.BUILD_MUSCLE.name, activity = "LIGHT", experience = "BEGINNER", daysPerWeek = 3, equipment = listOf("BODYWEIGHT"),
    )

    private fun started() = Actions.completeOnboarding(AppState(), profile(), today)

    @Test
    fun onboardingSavesProfileAndFirstWeight() {
        val s = started()
        assertTrue(s.settings.onboardingDone)
        assertEquals(62.0, s.weights.single().kg, 0.001)
        assertEquals(28, Derived.age(s, today))
    }

    @Test
    fun savedStateSurvivesAJsonRoundTrip() {
        var s = started()
        s = Actions.logFood(s, FoodEntryDto("Arroz", "LUNCH", 200.0, 4.0, 44.0, 0.5, 1L, "rice_white", 150.0), today).state
        s = Actions.addWater(s, 3, today).state
        s = Actions.finishSet(s, ExerciseType.SQUAT, 20, 0.9, 60_000, 1_000L, today).state
        val text = json.encodeToString(AppState.serializer(), s)
        val back = json.decodeFromString(AppState.serializer(), text)
        assertEquals(s, back)
    }

    @Test
    fun unknownFieldsFromANewerVersionAreIgnored() {
        val text = json.encodeToString(AppState.serializer(), started()).replaceFirst("{", "{\"futureField\":123,")
        assertNotNull(json.decodeFromString(AppState.serializer(), text).profile)
    }

    @Test
    fun loggingFoodAddsTheEntryAndGivesXp() {
        val r = Actions.logFood(started(), FoodEntryDto("Manzana", "SNACK", 95.0, 0.5, 25.0, 0.3, 1L), today)
        assertEquals(1, r.state.day(today.toEpochDay()).foods.size)
        assertEquals(1, r.state.game.mealsLogged)
        assertTrue(r.reward.xp > 0)
    }

    @Test
    fun waterCountsGlassesAndCanBeUndone() {
        var s = Actions.addWater(started(), 2, today).state
        assertEquals(500, s.day(today.toEpochDay()).waterMl)
        s = Actions.addWater(s, -1, today).state
        assertEquals(250, s.day(today.toEpochDay()).waterMl)
        s = Actions.addWater(s, -5, today).state
        assertEquals(0, s.day(today.toEpochDay()).waterMl)
    }

    @Test
    fun aSetOfRepsGivesXpStatsAndExtendsTheStreak() {
        val day1 = Actions.finishSet(started(), ExerciseType.SQUAT, 15, 0.9, 45_000, 1_000L, today).state
        assertEquals(15, day1.game.repsByExercise[ExerciseType.SQUAT.id])
        assertTrue(day1.game.xp > 0)
        assertTrue(day1.game.statBuckets.isNotEmpty())
        assertEquals(1, Derived.streak(day1, today).current)

        val day2 = Actions.finishSet(day1, ExerciseType.PUSH_UP, 10, 0.8, 30_000, 2_000L, today.plusDays(1)).state
        assertEquals(2, Derived.streak(day2, today.plusDays(1)).current)
        assertEquals(2, day2.workouts.size)
    }

    @Test
    fun beatingABossRecordsItAndPaysCoins() {
        val before = started()
        val r = Actions.finishSet(
            before, ExerciseType.SQUAT, 30, 0.9, 120_000, 1_000L, today,
            bossId = "slime_squat", victory = true, flawless = true, bestCombo = 12,
        )
        assertTrue("slime_squat" in r.state.game.bosses)
        assertEquals(1, r.state.game.flawlessWins)
        assertEquals(12, r.state.game.bestCombo)
        assertTrue(r.state.game.coins > before.game.coins)
    }

    @Test
    fun aLostBossFightIsNotRecordedAsAWin() {
        val r = Actions.finishSet(started(), ExerciseType.SQUAT, 8, 0.7, 60_000, 1_000L, today, bossId = "slime_squat", victory = false)
        assertFalse("slime_squat" in r.state.game.bosses)
        assertEquals(8, r.state.game.repsByExercise[ExerciseType.SQUAT.id])
    }

    @Test
    fun enoughXpLevelsUpAndPaysACoinBonus() {
        val before = started()
        val r = Actions.grantXp(before, 5_000, today)
        assertNotNull(r.reward.newLevel)
        assertTrue(r.state.game.coins > before.game.coins)
        assertTrue(Derived.level(r.state) > 1)
    }

    @Test
    fun theShopChargesCoinsOnceAndOnlyWhenAffordable() {
        val poor = started().let { it.copy(game = it.game.copy(coins = 10)) }
        assertNull(Actions.buy(poor, "cap", today))

        val rich = started().let { it.copy(game = it.game.copy(coins = 500)) }
        val bought = Actions.buy(rich, "cap", today)
        assertNotNull(bought)
        assertEquals(500L - 120L, bought!!.game.coins)
        assertTrue("cap" in Derived.ownedItems(bought, today))
        assertNull("cannot buy twice", Actions.buy(bought, "cap", today))
    }

    @Test
    fun aCompletedMissionCanBeClaimedExactlyOnce() {
        val s0 = started()
        val mission = Derived.missionBoard(s0, today).missions.first().mission
        val ready = s0.copy(
            game = s0.game.copy(missionDay = today.toString(), missionProgress = mapOf(mission.id to mission.target.toDouble())),
        )
        val first = Actions.claimMission(ready, mission.id, today)
        assertTrue(first.reward.xp > 0)
        assertTrue(mission.id in first.state.game.missionClaimed)

        val second = Actions.claimMission(first.state, mission.id, today)
        assertEquals(first.state.game.xp, second.state.game.xp)
    }

    @Test
    fun equippingOnlyWorksForOwnedItems() {
        val s = started()
        val tried = Actions.equip(s, "aura_gold", today) // level 40 reward, not owned
        assertFalse(tried.game.equipped.containsValue("aura_gold"))
        val ok = Actions.equip(s, "headband", today) // level 3
        assertFalse("not owned yet at level 1", ok.game.equipped.containsValue("headband"))
    }

    @Test
    fun weightLoggingUpdatesTheProfileAndReplacesTheSameDay() {
        var s = Actions.logWeight(started(), 61.0, today)
        s = Actions.logWeight(s, 60.5, today)
        assertEquals(60.5, s.profile!!.weightKg, 0.001)
        assertEquals(1, s.weights.count { it.epochDay == today.toEpochDay() })
    }
}
