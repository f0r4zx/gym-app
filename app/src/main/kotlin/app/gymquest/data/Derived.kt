package app.gymquest.data

import app.gymquest.core.avatar.CharacterStats
import app.gymquest.core.avatar.Cosmetics
import app.gymquest.core.avatar.Outfit
import app.gymquest.core.avatar.Slot
import app.gymquest.core.avatar.Stat
import app.gymquest.core.avatar.StatGain
import app.gymquest.core.avatar.UnlockProgress
import app.gymquest.core.exercise.ExerciseType
import app.gymquest.core.fitness.NutritionPlanner
import app.gymquest.core.fitness.NutritionTargets
import app.gymquest.core.fitness.UserProfile
import app.gymquest.core.game.Achievements
import app.gymquest.core.game.PlayerStats
import app.gymquest.core.game.Progression
import app.gymquest.core.game.Streak
import app.gymquest.core.game.Streaks
import app.gymquest.core.missions.MissionBoard
import app.gymquest.core.missions.MissionContext
import app.gymquest.core.missions.MissionProgress
import app.gymquest.core.nutrition.Macros
import app.gymquest.core.wellness.SkincareRoutines
import app.gymquest.core.wellness.SkincareStep
import app.gymquest.core.wellness.SkinType
import java.time.LocalDate

/** Values worked out from the saved state with the shared game logic. Cheap to compute; never stored. */
object Derived {
    fun profile(state: AppState): UserProfile? = state.profile?.toProfile()

    fun age(state: AppState, today: LocalDate): Int = profile(state)?.ageOn(today) ?: 25

    fun level(state: AppState): Int = Progression.levelFor(state.game.xp)

    fun streak(state: AppState, today: LocalDate): Streak =
        Streaks.compute(state.game.activeDays.map { LocalDate.ofEpochDay(it) }.toSet(), today)

    fun targets(state: AppState, today: LocalDate): NutritionTargets? =
        profile(state)?.let { NutritionPlanner.targets(it, today, state.day(today.toEpochDay()).activeMinutes) }

    fun eatenToday(state: AppState, today: LocalDate): Macros =
        Macros.sum(state.day(today.toEpochDay()).foods.map { Macros(it.kcal, it.protein, it.carbs, it.fat) })

    fun skincareRoutine(state: AppState): List<SkincareStep> {
        val p = state.profile
        return SkincareRoutines.forSkin(enumOr(p?.skinType, SkinType.NORMAL), p?.acneProne ?: false)
    }

    /** The character's stats, rebuilt from the per-day point buckets. */
    fun stats(state: AppState): CharacterStats {
        val s = CharacterStats()
        for ((name, days) in state.game.statBuckets) {
            val stat = runCatching { Stat.valueOf(name) }.getOrNull() ?: continue
            for ((day, points) in days) s.add(StatGain(stat, points), day * CharacterStats.DAY_MS)
        }
        return s
    }

    fun statValues(state: AppState, nowMs: Long): Map<Stat, Int> = stats(state).all(nowMs)

    fun playerStats(state: AppState, today: LocalDate): PlayerStats {
        val g = state.game
        val streak = streak(state, today)
        return PlayerStats(
            repsByExercise = g.repsByExercise.mapNotNull { (k, v) -> ExerciseType.fromId(k)?.let { it to v } }.toMap(),
            totalKm = g.totalKm, longestRunKm = g.longestRunKm, longestRideKm = g.longestRideKm,
            bossesDefeated = g.bosses, flawlessWins = g.flawlessWins, bestCombo = g.bestCombo,
            longestStreak = streak.longest, currentStreak = streak.current, workouts = state.workouts.size,
            waterGlasses = g.waterGlasses, skincareDays = g.skincareDays, mealsLogged = g.mealsLogged,
            friends = 0, level = level(state),
        )
    }

    fun unlockProgress(state: AppState, today: LocalDate): UnlockProgress {
        val g = state.game
        return UnlockProgress(
            level = level(state), bossesDefeated = g.bosses, achievements = g.achievements,
            bestStreak = streak(state, today).longest, territoryCells = g.territoryCells,
            purchased = g.purchased, granted = g.inventory.keys,
        )
    }

    fun ownedItems(state: AppState, today: LocalDate): Set<String> = Cosmetics.unlocked(unlockProgress(state, today))

    fun outfit(state: AppState, today: LocalDate): Outfit {
        val owned = ownedItems(state, today)
        val saved = state.game.equipped.mapNotNull { (slot, item) -> runCatching { Slot.valueOf(slot) }.getOrNull()?.let { it to item } }.toMap()
        val base = if (saved.isEmpty()) Outfit.starter() else Outfit(saved)
        return base.cleaned(owned)
    }

    fun missionContext(state: AppState, today: LocalDate): MissionContext {
        val p = profile(state)
        return MissionContext(
            level = level(state), age = p?.ageOn(today) ?: 25,
            experience = p?.experience ?: app.gymquest.core.fitness.Experience.BEGINNER,
            equipment = p?.equipment ?: setOf(app.gymquest.core.fitness.Equipment.BODYWEIGHT),
        )
    }

    fun missionBoard(state: AppState, today: LocalDate): MissionBoard {
        val base = MissionBoard.daily(state.settings.userId, today, missionContext(state, today))
        val g = state.game
        val sameDay = g.missionDay == today.toString()
        val progress = if (sameDay) g.missionProgress else emptyMap()
        val claimed = if (sameDay) g.missionClaimed else emptySet()
        return MissionBoard(
            base.periodKey,
            base.missions.map { MissionProgress(it.mission, progress[it.mission.id] ?: 0.0, it.mission.id in claimed) },
            bonusClaimed = sameDay && g.missionBonusClaimed,
        )
    }

    fun newAchievements(state: AppState, today: LocalDate) =
        Achievements.newlyUnlocked(playerStats(state, today), state.game.achievements)
}
