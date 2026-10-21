package app.gymquest.data

import app.gymquest.core.avatar.Cosmetics
import app.gymquest.core.avatar.StatSources
import app.gymquest.core.avatar.LevelRewards
import app.gymquest.core.avatar.Unlock
import app.gymquest.core.exercise.ExerciseType
import app.gymquest.core.fitness.Hydration
import app.gymquest.core.game.Bosses
import app.gymquest.core.game.Progression
import app.gymquest.core.game.XpRewards
import app.gymquest.core.missions.GameEvent
import app.gymquest.core.tracking.ActivityType
import app.gymquest.core.wellness.SkincareDay
import java.time.LocalDate

/** What an action earned, for the "+20 XP / ¡Subiste de nivel!" popups. */
data class Reward(
    val xp: Int = 0,
    val coins: Long = 0,
    val newLevel: Int? = null,
    val achievements: List<String> = emptyList(),
    val items: List<String> = emptyList(),
) {
    operator fun plus(o: Reward) = Reward(xp + o.xp, coins + o.coins, o.newLevel ?: newLevel, achievements + o.achievements, items + o.items)
    val isEmpty: Boolean get() = xp == 0 && coins == 0L && newLevel == null && achievements.isEmpty() && items.isEmpty()

    companion object {
        val NONE = Reward()
    }
}

data class Result(val state: AppState, val reward: Reward = Reward.NONE)

/** Every change to the player's data goes through here, so XP, missions, achievements and stats stay consistent. */
object Actions {

    // ---- Onboarding ----------------------------------------------------------------------

    fun completeOnboarding(state: AppState, profile: ProfileDto, today: LocalDate): AppState {
        val key = today.toEpochDay()
        return state.copy(
            profile = profile,
            settings = state.settings.copy(onboardingDone = true, editProfile = false),
            weights = (state.weights.filter { it.epochDay != key } + WeightDto(key, profile.weightKg)).sortedBy { it.epochDay },
        )
    }

    fun startProfileEdit(state: AppState): AppState = state.copy(settings = state.settings.copy(editProfile = true))

    fun cancelProfileEdit(state: AppState): AppState = state.copy(settings = state.settings.copy(editProfile = false))

    // ---- Experience, levels and achievements ------------------------------------------------

    fun grantXp(state: AppState, amount: Int, today: LocalDate, coins: Long = 0): Result {
        if (amount <= 0 && coins <= 0) return Result(state)
        val before = Derived.unlockProgress(state, today)
        val levelBefore = Derived.level(state)
        var s = state.copy(game = state.game.copy(xp = state.game.xp + amount.coerceAtLeast(0), coins = state.game.coins + coins))
        var reward = Reward(xp = amount.coerceAtLeast(0), coins = coins)

        val levelAfter = Derived.level(s)
        if (levelAfter > levelBefore) {
            val bonus = (levelBefore + 1..levelAfter).sumOf { LevelRewards.forLevel(it).coins.toLong() }
            s = s.copy(game = s.game.copy(coins = s.game.coins + bonus))
            reward += Reward(coins = bonus, newLevel = levelAfter)
        }
        // Achievements can themselves give XP, which can unlock more; settle in a few rounds.
        repeat(3) {
            val fresh = Derived.newAchievements(s, today)
            if (fresh.isEmpty()) return@repeat
            val xpFromAchievements = fresh.sumOf { it.xp }
            s = s.copy(game = s.game.copy(achievements = s.game.achievements + fresh.map { it.id }, xp = s.game.xp + xpFromAchievements))
            reward += Reward(xp = xpFromAchievements, achievements = fresh.map { it.name })
            val lvl = Derived.level(s)
            if (lvl > levelAfter && reward.newLevel != lvl) reward += Reward(newLevel = lvl)
        }
        val items = Cosmetics.newlyUnlocked(before, Derived.unlockProgress(s, today)).map { it.name }
        return Result(s, reward + Reward(items = items))
    }

    private fun markActive(state: AppState, today: LocalDate): AppState =
        state.copy(game = state.game.copy(activeDays = state.game.activeDays + today.toEpochDay()))

    private fun withStatGains(state: AppState, gains: List<app.gymquest.core.avatar.StatGain>, today: LocalDate): AppState {
        if (gains.isEmpty()) return state
        val buckets = state.game.statBuckets.toMutableMap()
        for (g in gains) {
            val days = (buckets[g.stat.name] ?: emptyMap()).toMutableMap()
            days.merge(today.toEpochDay(), g.points, Double::plus)
            buckets[g.stat.name] = days
        }
        return state.copy(game = state.game.copy(statBuckets = buckets))
    }

    fun withMissionEvent(state: AppState, event: GameEvent, today: LocalDate): AppState {
        val board = Derived.missionBoard(state, today).apply(event)
        val week = Derived.weeklyBoard(state, today).apply(event)
        return state.copy(
            game = state.game.copy(
                missionDay = today.toString(),
                missionProgress = board.missions.associate { it.mission.id to it.progress },
                missionClaimed = board.missions.filter { it.claimed }.map { it.mission.id }.toSet(),
                missionBonusClaimed = board.bonusClaimed,
                weekKey = week.periodKey,
                weekProgress = week.missions.associate { it.mission.id to it.progress },
                weekClaimed = week.missions.filter { it.claimed }.map { it.mission.id }.toSet(),
            ),
        )
    }

    // ---- Water, skincare, food, weight, sleep ------------------------------------------------

    fun addWater(state: AppState, glasses: Int, today: LocalDate): Result {
        if (glasses == 0) return Result(state)
        val key = today.toEpochDay()
        val day = state.day(key)
        val ml = (day.waterMl + glasses * Hydration.GLASS_ML).coerceAtLeast(0)
        var s = state.copy(days = state.days + (key to day.copy(waterMl = ml)))
        if (glasses > 0) {
            s = s.copy(game = s.game.copy(waterGlasses = s.game.waterGlasses + glasses))
            s = withMissionEvent(s, GameEvent.Water(glasses), today)
            s = withStatGains(s, StatSources.fromHabits(false, false, false, 0, false), today)
            return grantXp(s, XpRewards.WATER_GLASS * glasses, today).let { it.copy(state = it.state) }
        }
        return Result(s)
    }

    fun toggleSkincare(state: AppState, stepId: String, today: LocalDate): Result {
        val key = today.toEpochDay()
        val day = state.day(key)
        val routine = Derived.skincareRoutine(state)
        val before = SkincareDay(today, day.skincareSteps.toSet()).isComplete(routine)
        val updated = SkincareDay(today, day.skincareSteps.toSet()).toggle(stepId)
        val after = updated.isComplete(routine)
        var s = state.copy(days = state.days + (key to day.copy(skincareSteps = updated.completed.toList())))
        if (!before && after) {
            s = s.copy(game = s.game.copy(skincareDays = s.game.skincareDays + 1))
            s = withMissionEvent(s, GameEvent.SkincareDone, today)
            s = withStatGains(s, StatSources.fromHabits(false, true, false, 0, false), today)
            return grantXp(s, XpRewards.SKINCARE_ROUTINE, today)
        }
        return Result(s)
    }

    fun washFace(state: AppState, today: LocalDate): AppState {
        val key = today.toEpochDay()
        val day = state.day(key)
        return state.copy(days = state.days + (key to day.copy(faceWashes = day.faceWashes + 1)))
    }

    fun undoFaceWash(state: AppState, today: LocalDate): AppState {
        val key = today.toEpochDay()
        val day = state.day(key)
        return state.copy(days = state.days + (key to day.copy(faceWashes = (day.faceWashes - 1).coerceAtLeast(0))))
    }

    fun logFood(state: AppState, entry: FoodEntryDto, today: LocalDate): Result {
        val key = today.toEpochDay()
        val day = state.day(key)
        var s = state.copy(
            days = state.days + (key to day.copy(foods = day.foods + entry)),
            game = state.game.copy(mealsLogged = state.game.mealsLogged + 1),
        )
        s = withMissionEvent(s, GameEvent.MealLogged, today)
        return grantXp(s, XpRewards.MEAL_LOGGED, today)
    }

    fun removeFood(state: AppState, index: Int, today: LocalDate): AppState {
        val key = today.toEpochDay()
        val day = state.day(key)
        if (index !in day.foods.indices) return state
        return state.copy(days = state.days + (key to day.copy(foods = day.foods.filterIndexed { i, _ -> i != index })))
    }

    fun logWeight(state: AppState, kg: Double, today: LocalDate): AppState {
        val key = today.toEpochDay()
        val weights = state.weights.filter { it.epochDay != key } + WeightDto(key, kg)
        return withMissionEvent(
            state.copy(
                weights = weights.sortedBy { it.epochDay }.takeLast(800),
                profile = state.profile?.copy(weightKg = kg),
                days = state.days + (key to state.day(key).copy(weightKg = kg)),
            ),
            GameEvent.WeighedIn, today,
        )
    }

    fun logSleep(state: AppState, startMs: Long, endMs: Long, awakeMs: Long, source: String, today: LocalDate): Result {
        val key = today.toEpochDay()
        val day = state.day(key).copy(sleepStartMs = startMs, sleepEndMs = endMs, sleepAwakeMs = awakeMs, sleepSource = source)
        var s = state.copy(days = state.days + (key to day))
        s = withMissionEvent(s, GameEvent.SleepLogged, today)
        val slept = (endMs - startMs - awakeMs) / 3_600_000.0
        s = withStatGains(s, StatSources.fromHabits(false, false, slept >= 7.0, 0, false), today)
        return Result(s)
    }

    // ---- Training --------------------------------------------------------------------------

    /** A finished set of camera-counted reps (optionally against a boss). */
    fun finishSet(
        state: AppState,
        type: ExerciseType,
        reps: Int,
        avgQuality: Double,
        durationMs: Long,
        nowMs: Long,
        today: LocalDate,
        bossId: String? = null,
        victory: Boolean? = null,
        flawless: Boolean = false,
        bestCombo: Int = 0,
    ): Result {
        if (reps <= 0 && bossId == null) return Result(state)
        val weight = state.profile?.weightKg ?: 70.0
        val kcal = type.caloriesFor(reps, weight).toInt()
        val xp = XpRewards.forReps(type, reps, avgQuality)
        val key = today.toEpochDay()
        val day = state.day(key)

        var s = markActive(state, today)
        s = s.copy(
            game = s.game.copy(
                repsByExercise = s.game.repsByExercise + (type.id to ((s.game.repsByExercise[type.id] ?: 0) + reps)),
                bestCombo = maxOf(s.game.bestCombo, bestCombo),
                flawlessWins = s.game.flawlessWins + if (victory == true && flawless) 1 else 0,
                bosses = if (victory == true && bossId != null) s.game.bosses + bossId else s.game.bosses,
            ),
            days = s.days + (key to day.copy(activeMinutes = day.activeMinutes + (durationMs / 60_000).toInt().coerceAtLeast(if (reps > 0) 1 else 0), workouts = day.workouts + 1)),
            workouts = (s.workouts + WorkoutDto(nowMs, type.id, reps, avgQuality, durationMs, bossId, victory, xp, kcal)).takeLast(600),
        )
        s = withStatGains(s, StatSources.fromExercise(type, reps, avgQuality), today)
        s = withMissionEvent(s, GameEvent.Reps(type, reps, avgQuality), today)
        s = withMissionEvent(s, GameEvent.ActiveMinutes((durationMs / 60_000).toInt()), today)
        s = withMissionEvent(s, GameEvent.WorkoutFinished, today)

        var reward = Reward()
        var bossCoins = 0L
        var bossXp = 0
        if (victory == true && bossId != null) {
            val boss = Bosses.byId(bossId)
            if (boss != null) {
                bossXp = boss.xpReward + if (flawless) boss.xpReward / 4 else 0
                bossCoins = (boss.xpReward / 2).toLong()
                s = withMissionEvent(s, GameEvent.BossWin(bossId, flawless), today)
            }
        }
        val granted = grantXp(s, xp + bossXp, today, coins = bossCoins)
        return Result(granted.state, reward + granted.reward)
    }

    fun finishActivity(state: AppState, dto: ActivityDto, activity: ActivityType, today: LocalDate, loops: List<LoopDto> = emptyList()): Result {
        val km = dto.distanceM / 1000.0
        val key = today.toEpochDay()
        val day = state.day(key)
        var s = markActive(state, today)
        s = s.copy(
            activities = (s.activities + dto).takeLast(400),
            loops = (s.loops + loops).takeLast(120),
            days = s.days + (key to day.copy(activeMinutes = day.activeMinutes + (dto.movingMs / 60_000).toInt())),
            game = s.game.copy(
                totalKm = s.game.totalKm + km,
                longestRunKm = if (activity == ActivityType.RUN) maxOf(s.game.longestRunKm, km) else s.game.longestRunKm,
                longestRideKm = if (activity == ActivityType.RIDE) maxOf(s.game.longestRideKm, km) else s.game.longestRideKm,
                territoryCells = s.game.territoryCells + dto.cellsCaptured,
            ),
        )
        val avg = if (dto.movingMs > 0) dto.distanceM / (dto.movingMs / 1000.0) else 0.0
        s = withStatGains(s, StatSources.fromActivity(activity, dto.distanceM, avg), today)
        s = withMissionEvent(s, GameEvent.Distance(activity, dto.distanceM), today)
        s = withMissionEvent(s, GameEvent.ActiveMinutes((dto.movingMs / 60_000).toInt()), today)
        if (dto.cellsCaptured > 0) s = withMissionEvent(s, GameEvent.CellsCaptured(dto.cellsCaptured), today)
        val xp = XpRewards.forDistance(
            when (activity) {
                ActivityType.WALK -> app.gymquest.core.game.ActivityKind.WALK
                ActivityType.RUN -> app.gymquest.core.game.ActivityKind.RUN
                ActivityType.RIDE -> app.gymquest.core.game.ActivityKind.RIDE
                ActivityType.HIKE -> app.gymquest.core.game.ActivityKind.HIKE
            },
            km,
        ) + dto.cellsCaptured * 2
        return grantXp(s, xp, today)
    }

    // ---- Community hazard reports (kept on the phone until a server can share them) -----------

    fun addReport(state: AppState, type: String, at: app.gymquest.core.tracking.GeoPoint, nowMs: Long): AppState {
        val live = state.reports.filter { r ->
            val ttl = runCatching { app.gymquest.core.routes.ReportType.valueOf(r.type).ttlMs }.getOrDefault(3_600_000L)
            nowMs - r.createdMs < ttl
        }
        val report = ReportDto(java.util.UUID.randomUUID().toString(), type, at.lat, at.lon, nowMs)
        return state.copy(reports = (live + report).takeLast(200))
    }

    // ---- Missions ---------------------------------------------------------------------------

    fun claimMission(state: AppState, missionId: String, today: LocalDate): Result {
        val board = Derived.missionBoard(state, today)
        val multiplier = app.gymquest.core.missions.MissionStreak.multiplier(state.game.missionStreak)
        val (after, reward) = board.claim(missionId, multiplier)
        if (reward == null) return Result(state)
        val s = state.copy(
            game = state.game.copy(
                missionDay = today.toString(),
                missionProgress = after.missions.associate { it.mission.id to it.progress },
                missionClaimed = after.missions.filter { it.claimed }.map { it.mission.id }.toSet(),
            ),
        )
        return grantXp(s, reward.xp, today, coins = reward.coins.toLong())
    }

    fun claimWeekly(state: AppState, missionId: String, today: LocalDate): Result {
        val board = Derived.weeklyBoard(state, today)
        val (after, reward) = board.claim(missionId, 1.0)
        if (reward == null) return Result(state)
        val s = state.copy(
            game = state.game.copy(
                weekKey = after.periodKey,
                weekProgress = after.missions.associate { it.mission.id to it.progress },
                weekClaimed = after.missions.filter { it.claimed }.map { it.mission.id }.toSet(),
            ),
        )
        return grantXp(s, reward.xp, today, coins = reward.coins.toLong())
    }

    fun claimMissionBonus(state: AppState, today: LocalDate): Result {
        val board = Derived.missionBoard(state, today)
        val multiplier = app.gymquest.core.missions.MissionStreak.multiplier(state.game.missionStreak)
        val (after, reward) = board.claimBonus(multiplier)
        if (reward == null) return Result(state)
        val yesterday = today.toEpochDay() - 1
        val streak = if (state.game.lastAllDoneDay == yesterday) state.game.missionStreak + 1 else 1
        val s = state.copy(game = state.game.copy(missionBonusClaimed = after.bonusClaimed, missionStreak = streak, lastAllDoneDay = today.toEpochDay()))
        return grantXp(s, reward.xp, today, coins = reward.coins.toLong())
    }

    // ---- Character and shop -------------------------------------------------------------------

    fun equip(state: AppState, itemId: String, today: LocalDate): AppState {
        val owned = Derived.ownedItems(state, today)
        val outfit = Derived.outfit(state, today).equip(itemId, owned)
        return state.copy(game = state.game.copy(equipped = outfit.equipped.mapKeys { it.key.name }))
    }

    fun unequip(state: AppState, slotName: String): AppState =
        state.copy(game = state.game.copy(equipped = state.game.equipped - slotName))

    /** Buys a shop cosmetic with coins. Returns null if it is not for sale, already owned, or too expensive. */
    fun buy(state: AppState, itemId: String, today: LocalDate): AppState? {
        val item = Cosmetics.byId(itemId) ?: return null
        val price = (item.unlock as? Unlock.Shop)?.price ?: return null
        if (itemId in Derived.ownedItems(state, today)) return null
        if (state.game.coins < price) return null
        return state.copy(game = state.game.copy(coins = state.game.coins - price, purchased = state.game.purchased + itemId))
    }

    fun progressLevel(state: AppState) = Progression.progress(state.game.xp)
}
