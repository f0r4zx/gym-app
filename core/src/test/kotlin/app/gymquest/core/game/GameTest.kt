package app.gymquest.core.game

import app.gymquest.core.exercise.ExerciseType
import app.gymquest.core.exercise.PartialRep
import app.gymquest.core.exercise.RepEvent
import app.gymquest.core.exercise.FormIssue
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertIs

class BossBattleTest {
    private val slime = Bosses.byId("slime_squat")!!

    private fun rep(count: Int, t: Long, quality: Double = 1.0, hold: Boolean = false) =
        RepEvent(count, t, quality, 2000, 1.0, emptySet(), hold)

    @Test
    fun perfectRepsBuildComboAndDefeatTheBoss() {
        val battle = BossBattle(slime)
        var t = 0L
        var count = 0
        val events = mutableListOf<BattleEvent>()
        while (battle.state == BattleState.IN_PROGRESS && count < 100) {
            t += 2_000
            count++
            events += battle.onRep(rep(count, t), ExerciseType.SQUAT)
        }
        assertEquals(BattleState.VICTORY, battle.state)
        assertTrue(count in 8..14, "a good set should beat the slime in ~12 reps, took $count")
        val victory = events.filterIsInstance<BattleEvent.Victory>().single()
        assertTrue(victory.flawless)
        assertEquals(slime.xpReward + slime.xpReward / 4, victory.xp)
        assertEquals(slime.lootId, victory.lootId)
    }

    @Test
    fun comboMultipliesDamage() {
        val battle = BossBattle(slime)
        val hits = (1..4).map { i -> battle.onRep(rep(i, i * 2_000L), ExerciseType.SQUAT).filterIsInstance<BattleEvent.Hit>().single() }
        assertTrue(hits[1].damage > hits[0].damage)
        assertTrue(hits[3].damage > hits[1].damage)
        assertEquals(listOf(1, 2, 3, 4), hits.map { it.combo })
    }

    @Test
    fun everyFifthGoodRepIsCritical() {
        val battle = BossBattle(slime)
        val crit = (1..5).map { i -> battle.onRep(rep(i, i * 2_000L), ExerciseType.SQUAT).filterIsInstance<BattleEvent.Hit>().single().critical }
        assertEquals(listOf(false, false, false, false, true), crit)
    }

    @Test
    fun sloppyRepsBreakTheComboAndHitSofter() {
        val battle = BossBattle(slime)
        battle.onRep(rep(1, 2_000), ExerciseType.SQUAT)
        battle.onRep(rep(2, 4_000), ExerciseType.SQUAT)
        val sloppy = battle.onRep(rep(3, 6_000, quality = 0.4), ExerciseType.SQUAT).filterIsInstance<BattleEvent.Hit>().single()
        assertEquals(0, battle.combo)
        assertTrue(sloppy.damage <= 5, "damage ${sloppy.damage}")
        assertEquals(2, battle.bestCombo)
    }

    @Test
    fun partialRepResetsCombo() {
        val battle = BossBattle(slime)
        battle.onRep(rep(1, 2_000), ExerciseType.SQUAT)
        battle.onRep(rep(2, 4_000), ExerciseType.SQUAT)
        battle.onPartial(PartialRep(5_000, 0.5, FormIssue.NOT_DEEP_ENOUGH))
        assertEquals(0, battle.combo)
    }

    @Test
    fun wrongExerciseDoesNothing() {
        val battle = BossBattle(slime)
        val out = battle.onRep(rep(1, 2_000), ExerciseType.PUSH_UP)
        assertTrue(BattleEvent.Ignored in out)
        assertEquals(slime.maxHp, battle.bossHp)
    }

    @Test
    fun bossAttacksWhenYouRest() {
        val battle = BossBattle(slime)
        battle.onRep(rep(1, 1_000), ExerciseType.SQUAT)
        assertTrue(battle.tick(1_000 + slime.attackIntervalMs - 1).isEmpty())
        val attack = battle.tick(1_000 + slime.attackIntervalMs).single()
        assertIs<BattleEvent.BossAttack>(attack)
        assertEquals(100 - slime.attackDamage, battle.playerHp)
        assertEquals(0, battle.combo)
    }

    @Test
    fun repsHoldOffTheBossAttack() {
        val battle = BossBattle(slime)
        var t = 0L
        repeat(10) { i ->
            // Faster than even the enraged attack interval (70% of normal).
            t += (slime.attackIntervalMs * 0.6).toLong()
            battle.tick(t)
            battle.onRep(rep(i + 1, t, quality = 0.9), ExerciseType.SQUAT)
        }
        assertEquals(100, battle.playerHp)
    }

    @Test
    fun longRestCoversSeveralAttacksAndCanDefeatYou() {
        val battle = BossBattle(slime)
        val out = battle.tick(slime.attackIntervalMs * 20)
        assertEquals(BattleState.DEFEAT, battle.state)
        assertTrue(out.filterIsInstance<BattleEvent.BossAttack>().size >= 12)
        assertEquals(BattleEvent.Defeat(DefeatReason.OUT_OF_HEALTH), out.last())
        assertEquals(0, battle.playerHp)
    }

    @Test
    fun enragesBelowHalfHealth() {
        val battle = BossBattle(slime)
        var t = 0L
        var enragedEvents = 0
        var i = 0
        while (!battle.enraged && battle.state == BattleState.IN_PROGRESS) {
            t += 2_000
            i++
            enragedEvents += battle.onRep(rep(i, t), ExerciseType.SQUAT).count { it is BattleEvent.Enraged }
        }
        assertEquals(1, enragedEvents)
        assertTrue(battle.bossHp * 2 <= slime.maxHp)
        // Enraged bosses attack sooner and harder.
        val before = battle.playerHp
        val out = battle.tick(t + (slime.attackIntervalMs * 0.7).toLong())
        assertIs<BattleEvent.BossAttack>(out.first())
        assertTrue(before - battle.playerHp > slime.attackDamage)
    }

    @Test
    fun timeLimitEndsTheFight() {
        val battle = BossBattle(slime)
        var t = 0L
        var out = emptyList<BattleEvent>()
        // Keep hitting weakly (quality 0.2) so the boss never attacks but also never dies in time.
        while (battle.state == BattleState.IN_PROGRESS) {
            t += 5_000
            out = battle.onRep(rep(1, t, quality = 0.2), ExerciseType.SQUAT) + battle.tick(t)
        }
        assertEquals(BattleState.DEFEAT, battle.state)
        assertTrue(out.any { it == BattleEvent.Defeat(DefeatReason.TIME_UP) })
    }

    @Test
    fun takingDamageForfeitsTheFlawlessBonus() {
        val battle = BossBattle(slime)
        battle.tick(slime.attackIntervalMs) // one hit taken
        var t = slime.attackIntervalMs
        var victory: BattleEvent.Victory? = null
        var i = 0
        while (victory == null && i < 200) {
            t += 2_000; i++
            victory = battle.onRep(rep(i, t), ExerciseType.SQUAT).filterIsInstance<BattleEvent.Victory>().firstOrNull()
        }
        assertEquals(slime.xpReward, victory!!.xp)
        assertFalse(victory.flawless)
    }

    @Test
    fun holdsDamageTheKraken() {
        val kraken = Bosses.byId("kraken_plank")!!
        val battle = BossBattle(kraken)
        var t = 0L
        var seconds = 0
        while (battle.state == BattleState.IN_PROGRESS && seconds < 400) {
            t += 1_000; seconds++
            battle.onRep(rep(seconds, t, hold = true), ExerciseType.PLANK)
        }
        assertEquals(BattleState.VICTORY, battle.state)
        assertTrue(seconds in 45..70, "a ~minute plank should beat the kraken, took ${seconds}s")
    }

    @Test
    fun finalBossRewardsVarietyAndAcceptsSeveralExercises() {
        val titan = Bosses.byId("titan_gym")!!
        val mixed = BossBattle(titan)
        val single = BossBattle(titan)
        val types = listOf(ExerciseType.SQUAT, ExerciseType.PUSH_UP)
        repeat(8) { i ->
            mixed.onRep(rep(i + 1, i * 2_000L + 2_000), types[i % 2])
            single.onRep(rep(i + 1, i * 2_000L + 2_000), ExerciseType.SQUAT)
        }
        assertTrue(mixed.totalDamage > single.totalDamage, "mixed=${mixed.totalDamage} single=${single.totalDamage}")
    }

    @Test
    fun finishedFightsIgnoreFurtherInput() {
        val battle = BossBattle(slime)
        battle.tick(slime.attackIntervalMs * 30)
        assertTrue(battle.onRep(rep(1, 999_999), ExerciseType.SQUAT).isEmpty())
        assertTrue(battle.tick(1_000_000).isEmpty())
    }

    @Test
    fun rosterIsConsistent() {
        assertEquals(Bosses.all.size, Bosses.all.map { it.id }.toSet().size)
        assertEquals(Bosses.all.size, Bosses.all.map { it.lootId }.toSet().size)
        for (b in Bosses.all) {
            assertTrue(b.maxHp > 0 && b.attackIntervalMs >= 4_000 && b.exercises.isNotEmpty(), b.id)
            assertTrue(b.timeLimitMs > b.attackIntervalMs, b.id)
        }
        assertTrue(Bosses.unlockedAt(1).isNotEmpty())
        assertEquals(Bosses.all.size, Bosses.unlockedAt(99).size)
        assertTrue(Bosses.forExercise(ExerciseType.SQUAT).any { it.id == "titan_gym" })
    }

    @Test
    fun everyBossIsBeatableWithAReasonableSet() {
        for (boss in Bosses.all) {
            val battle = BossBattle(boss)
            var t = 0L
            var n = 0
            val type = boss.primaryExercise
            val hold = type.mode == app.gymquest.core.exercise.CountingMode.HOLD
            val step = if (hold) 1_000L else if (boss.exercises.size > 1) 2_500L else 2_000L
            while (battle.state == BattleState.IN_PROGRESS && n < 400) {
                t += step; n++
                val ex = boss.exercises[n % boss.exercises.size]
                battle.onRep(rep(n, t, quality = 0.85, hold = hold), ex)
            }
            assertEquals(BattleState.VICTORY, battle.state, "${boss.id} not beatable (n=$n)")
            assertTrue(n <= 80 || hold || boss.id == "rope_king", "${boss.id} needs too many reps: $n")
        }
    }
}

class ProgressionTest {
    @Test
    fun levelsIncreaseMonotonically() {
        var prev = 0
        for (l in 1..99) {
            val x = Progression.xpToReach(l)
            assertTrue(x >= prev)
            prev = x
            assertEquals(l, Progression.levelFor(x), "level $l")
            if (l > 1) assertEquals(l - 1, Progression.levelFor(x - 1))
        }
    }

    @Test
    fun progressReportsPositionInLevel() {
        val p = Progression.progress(Progression.xpToReach(5) + 10)
        assertEquals(5, p.level)
        assertEquals(10, p.xpIntoLevel)
        assertEquals(Progression.xpToReach(6) - Progression.xpToReach(5), p.xpForNext)
        assertTrue(p.fraction in 0.0..1.0)
    }

    @Test
    fun maxLevelIsCapped() {
        val p = Progression.progress(Int.MAX_VALUE / 2)
        assertEquals(Progression.MAX_LEVEL, p.level)
        assertEquals(1.0, p.fraction)
    }

    @Test
    fun negativeXpIsLevelOne() {
        assertEquals(1, Progression.progress(-50).level)
    }

    @Test
    fun qualityScalesXp() {
        val good = XpRewards.forReps(ExerciseType.SQUAT, 20, 1.0)
        val sloppy = XpRewards.forReps(ExerciseType.SQUAT, 20, 0.3)
        assertEquals(40, good)
        assertTrue(sloppy < good && sloppy > good / 2)
    }

    @Test
    fun distanceXpDependsOnActivity() {
        assertTrue(XpRewards.forDistance(ActivityKind.RUN, 5.0) > XpRewards.forDistance(ActivityKind.RIDE, 5.0))
    }

    @Test
    fun streakBonusIsCapped() {
        assertEquals(10, XpRewards.streakBonus(5))
        assertEquals(50, XpRewards.streakBonus(1000))
    }

    @Test
    fun titlesRiseWithLevel() {
        assertEquals("Novato", Progression.titleFor(1))
        assertEquals("Leyenda", Progression.titleFor(99))
    }
}

class StreakAndAchievementTest {
    private val today = LocalDate.of(2026, 10, 21)

    @Test
    fun emptyHistoryHasNoStreak() {
        assertEquals(Streak(0, 0, false), Streaks.compute(emptySet(), today))
    }

    @Test
    fun countsConsecutiveDaysIncludingToday() {
        val days = (0..4).map { today.minusDays(it.toLong()) }.toSet()
        assertEquals(Streak(5, 5, true), Streaks.compute(days, today))
    }

    @Test
    fun streakSurvivesUntilMidnightIfYouHaveNotTrainedYet() {
        val days = (1..3).map { today.minusDays(it.toLong()) }.toSet()
        assertEquals(Streak(3, 3, false), Streaks.compute(days, today))
    }

    @Test
    fun missingADayBreaksTheCurrentStreakButKeepsTheRecord() {
        val days = setOf(today, today.minusDays(1), today.minusDays(3), today.minusDays(4), today.minusDays(5), today.minusDays(6))
        val s = Streaks.compute(days, today)
        assertEquals(2, s.current)
        assertEquals(4, s.longest)
    }

    @Test
    fun oldStreakIsDead() {
        val days = setOf(today.minusDays(5), today.minusDays(6))
        assertEquals(0, Streaks.compute(days, today).current)
    }

    @Test
    fun achievementsUnlockOnceAtTheirTarget() {
        val stats = PlayerStats(repsByExercise = mapOf(ExerciseType.SQUAT to 520, ExerciseType.PUSH_UP to 10))
        val first = Achievements.newlyUnlocked(stats, emptySet()).map { it.id }.toSet()
        assertTrue(setOf("first_rep", "reps_100", "squat_500") .all { it in first }, "$first")
        assertFalse("pushup_500" in first)
        val again = Achievements.newlyUnlocked(stats, first)
        assertTrue(again.isEmpty())
    }

    @Test
    fun progressFractionIsClamped() {
        val a = Achievements.byId("reps_100")!!
        assertEquals(0.5, a.fraction(PlayerStats(repsByExercise = mapOf(ExerciseType.SQUAT to 50))), 1e-9)
        assertEquals(1.0, a.fraction(PlayerStats(repsByExercise = mapOf(ExerciseType.SQUAT to 5000))), 1e-9)
    }

    @Test
    fun achievementIdsAreUnique() {
        assertEquals(Achievements.all.size, Achievements.all.map { it.id }.toSet().size)
        assertTrue(Achievements.byId("boss_all")!!.target == Bosses.all.size.toDouble())
    }
}
