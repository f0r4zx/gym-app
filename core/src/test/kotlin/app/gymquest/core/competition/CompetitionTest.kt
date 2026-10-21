package app.gymquest.core.competition

import app.gymquest.core.exercise.ExerciseType
import app.gymquest.core.game.Bosses
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val DAY = 86_400_000L

/** A believable human set: [reps] repetitions about [everyMs] apart with natural variation. */
private fun humanSet(user: String, reps: Int, everyMs: Long, quality: Double = 0.85, age: Int = 28, seed: Long = 5): DuelSubmission {
    val rnd = Random(seed)
    var t = 2_000L
    val times = List(reps) { i ->
        if (i > 0) t += (everyMs * (1.0 + rnd.nextGaussian() * 0.08)).toLong().coerceAtLeast(1)
        t
    }
    return DuelSubmission(user, times, List(reps) { (quality + rnd.nextGaussian() * 0.03).coerceIn(0.0, 1.0) }, age)
}

class DisciplineTest {
    @Test
    fun everyExerciseHasAWeightAndDisciplinesAcceptTheirExercise() {
        for (t in ExerciseType.entries) assertTrue(Discipline.weight(t) > 0)
        assertTrue(Discipline.PUSH_UPS.accepts(ExerciseType.PUSH_UP))
        assertTrue(!Discipline.PUSH_UPS.accepts(ExerciseType.SQUAT))
        assertTrue(Discipline.ANY_REPS.accepts(ExerciseType.BURPEE))
        assertTrue(Discipline.JUMP_ROPE.isDuelable && !Discipline.DISTANCE.isDuelable)
    }

    @Test
    fun harderExercisesAreWorthMore() {
        assertTrue(Discipline.points(ExerciseType.PULL_UP, 10, 1.0) > Discipline.points(ExerciseType.PUSH_UP, 10, 1.0))
        assertTrue(Discipline.points(ExerciseType.PUSH_UP, 10, 1.0) > Discipline.points(ExerciseType.JUMP_ROPE, 10, 1.0))
        assertEquals(0.0, Discipline.points(ExerciseType.PUSH_UP, 50, 0.2))
    }

    @Test
    fun eloMath() {
        assertEquals(0.5, Elo.expected(1000, 1000), 1e-9)
        assertTrue(Elo.expected(1200, 1000) > 0.75)
        assertEquals(1016, Elo.update(1000, 1000, 1.0))
        assertEquals(984, Elo.update(1000, 1000, 0.0))
        assertEquals(1000, Elo.update(1000, 1000, 0.5))
        // Beating a much stronger opponent pays more.
        assertTrue(Elo.update(1000, 1400, 1.0) - 1000 > 25)
        assertTrue(Elo.update(1400, 1000, 1.0) - 1400 < 8)
    }
}

class DuelTest {
    private fun timed(stake: Long = 100) = Duel("d1", "ana", "beto", Discipline.PUSH_UPS, DuelMode.TIMED, 60_000, stake, 0, 24 * 3_600_000L)

    @Test
    fun moreQualityRepsWins() {
        val d = timed()
        d.submit(humanSet("ana", 35, 1_500), 100)
        d.submit(humanSet("beto", 28, 1_900, seed = 9), 200)
        val outcome = d.decide(300)
        assertEquals("ana", (outcome as DuelOutcome.Winner).userId)
    }

    @Test
    fun repsAfterTheTimeLimitDoNotCount() {
        val d = timed()
        // Beto does 60 reps but 25 of them after the 60-second window.
        val beto = humanSet("beto", 60, 1_000)
        d.submit(humanSet("ana", 45, 1_300), 100)
        d.submit(beto.copy(repTimesMs = beto.repTimesMs, qualities = beto.qualities), 200)
        val outcome = d.decide(300) as DuelOutcome.Winner
        assertTrue(outcome.userId == "ana" || outcome.scoreB < 60.0)
    }

    @Test
    fun cleanFormBeatsHigherSloppyCount() {
        val d = timed()
        d.submit(humanSet("ana", 30, 1_800, quality = 0.95), 1)
        d.submit(humanSet("beto", 34, 1_700, quality = 0.62, seed = 8), 2)
        assertEquals("ana", (d.decide(3) as DuelOutcome.Winner).userId)
    }

    @Test
    fun handicapHelpsOlderPlayersAMeasuredAmount() {
        assertEquals(1.0, Handicap.factor(30))
        assertTrue(Handicap.factor(55) > 1.1)
        assertEquals(1.35, Handicap.factor(100))
        assertTrue(Handicap.factor(13) > 1.1)
        val d = timed()
        d.submit(humanSet("ana", 30, 1_800, age = 60), 1)
        d.submit(humanSet("beto", 33, 1_700, age = 25, seed = 8), 2)
        // 60-year-old doing 30 beats a 25-year-old doing 33 once the handicap is applied.
        assertEquals("ana", (d.decide(3) as DuelOutcome.Winner).userId)
        val noHandicap = Duel("d2", "ana", "beto", Discipline.PUSH_UPS, DuelMode.TIMED, 60_000, 0, 0, DAY, useHandicap = false)
        noHandicap.submit(humanSet("ana", 30, 1_800, age = 60), 1)
        noHandicap.submit(humanSet("beto", 33, 1_700, age = 25, seed = 8), 2)
        assertEquals("beto", (noHandicap.decide(3) as DuelOutcome.Winner).userId)
    }

    @Test
    fun raceToTargetIsWonByTheFasterFinisher() {
        val d = Duel("r", "ana", "beto", Discipline.SQUATS, DuelMode.RACE_TO_TARGET, 20, 0, 0, DAY)
        d.submit(humanSet("ana", 20, 2_000), 1)
        d.submit(humanSet("beto", 22, 1_700, seed = 3), 2)
        assertEquals("beto", (d.decide(3) as DuelOutcome.Winner).userId)
    }

    @Test
    fun raceWhereNobodyReachesTheTargetIsADraw() {
        val d = Duel("r", "ana", "beto", Discipline.SQUATS, DuelMode.RACE_TO_TARGET, 50, 0, 0, DAY)
        d.submit(humanSet("ana", 20, 2_000), 1)
        d.submit(humanSet("beto", 22, 1_700, seed = 3), 2)
        assertEquals(DuelOutcome.Draw, d.decide(3))
    }

    @Test
    fun noShowForfeitsAfterTheDeadline() {
        val d = timed()
        d.submit(humanSet("ana", 30, 1_800), 100)
        assertEquals(DuelOutcome.Pending, d.decide(1_000))
        val outcome = d.decide(25 * 3_600_000L)
        assertEquals("ana", (outcome as DuelOutcome.Forfeit).winnerId)
    }

    @Test
    fun nobodyShowingUpCancelsAndRefunds() {
        val d = timed(stake = 50)
        val outcome = d.decide(DAY * 2)
        assertEquals(DuelOutcome.Cancelled, outcome)
        assertEquals(mapOf("ana" to 50L, "beto" to 50L), d.payouts(outcome))
    }

    @Test
    fun winnerTakesThePotMinusTheFee() {
        val d = timed(stake = 100)
        d.submit(humanSet("ana", 35, 1_500), 1)
        d.submit(humanSet("beto", 20, 2_500, seed = 4), 2)
        val payouts = d.payouts(d.decide(3))
        assertEquals(mapOf("ana" to 190L), payouts)
        assertEquals(emptyMap(), timed(stake = 0).payouts(DuelOutcome.Draw))
        assertEquals(mapOf("ana" to 100L, "beto" to 100L), timed().payouts(DuelOutcome.Draw))
    }

    @Test
    fun cheatersAreCaughtAndLoseByForfeit() {
        val d = timed()
        // A script: 80 push-ups exactly 600 ms apart.
        val bot = DuelSubmission("beto", List(80) { 2_000L + it * 600L }, List(80) { 0.9 }, 25)
        val v = d.submit(bot, 10)
        assertTrue(!v.valid)
        d.submit(humanSet("ana", 25, 2_000), 20)
        val outcome = d.decide(30)
        assertEquals("ana", (outcome as DuelOutcome.Forfeit).winnerId)
    }

    @Test
    fun verifierFlagsImpossibleRatesUniformTimingAndSloppyForm() {
        val tooFast = DuelSubmission("x", List(40) { it * 200L }, List(40) { 0.9 }, 30)
        assertTrue(!DuelVerifier.verify(Discipline.PUSH_UPS, tooFast, 60_000).valid)
        val metronome = DuelSubmission("x", List(30) { 1_000L + it * 1_500L }, List(30) { 0.9 }, 30)
        assertTrue(DuelVerifier.verify(Discipline.PUSH_UPS, metronome, 60_000).reasons.any { it.contains("uniforme") })
        val sloppy = DuelSubmission("x", humanSet("x", 20, 2_000).repTimesMs, List(20) { 0.3 }, 30)
        assertTrue(DuelVerifier.verify(Discipline.PUSH_UPS, sloppy, 60_000).reasons.any { it.contains("pobre") })
        val unordered = DuelSubmission("x", listOf(1_000, 900, 2_000), listOf(0.9, 0.9, 0.9), 30)
        assertTrue(!DuelVerifier.verify(Discipline.PUSH_UPS, unordered, 60_000).valid)
        assertTrue(DuelVerifier.verify(Discipline.PUSH_UPS, humanSet("x", 30, 1_800), 60_000).valid)
        assertTrue(DuelVerifier.verify(Discipline.PUSH_UPS, DuelSubmission("x", emptyList(), emptyList(), 30), 60_000).valid)
    }

    @Test
    fun jumpRopeAllowsFastButHumanRates() {
        val ok = humanSet("x", 150, 330)
        assertTrue(DuelVerifier.verify(Discipline.JUMP_ROPE, ok, 60_000).valid)
        val impossible = DuelSubmission("x", List(150) { it * 90L }, List(150) { 0.9 }, 30)
        assertTrue(!DuelVerifier.verify(Discipline.JUMP_ROPE, impossible, 60_000).valid)
    }

    @Test
    fun guardsAgainstMisuse() {
        assertFailsWith<IllegalArgumentException> { Duel("d", "a", "a", Discipline.SQUATS, DuelMode.TIMED, 1, 0, 0, 1) }
        assertFailsWith<IllegalArgumentException> { Duel("d", "a", "b", Discipline.DISTANCE, DuelMode.TIMED, 1, 0, 0, 1) }
        val d = timed()
        d.submit(humanSet("ana", 10, 2_000), 1)
        assertFailsWith<IllegalArgumentException> { d.submit(humanSet("ana", 10, 2_000), 2) }
        assertFailsWith<IllegalArgumentException> { d.submit(humanSet("carla", 10, 2_000), 2) }
        assertFailsWith<IllegalArgumentException> { d.submit(humanSet("beto", 10, 2_000), 2 * DAY) }
        assertFailsWith<IllegalArgumentException> { DuelSubmission("x", listOf(1), emptyList(), 30) }
    }
}

class GuildWarTest {
    private val a = WarSide("ga", "Los Gym Bros", 1000, 12)
    private val b = WarSide("gb", "Titanes", 1000, 5)

    private fun war(cap: Double = 400.0) = GuildWar("w1", a, b, Discipline.PUSH_UPS, 0, 3 * DAY, 500, cap)

    @Test
    fun onlyTheSmallerRostersBestMembersCount() {
        val w = war()
        // Guild A has 10 active members with 50 pts each; guild B has 3 with 120 pts each.
        for (i in 1..10) w.addScore("ga", "a$i", 50.0, 1_000)
        for (i in 1..3) w.addScore("gb", "b$i", 120.0, 1_000)
        assertEquals(3, w.countedMembers())
        assertEquals(150.0, w.scoreOf("ga"))   // top 3 of A: 3 x 50
        assertEquals(360.0, w.scoreOf("gb"))
    }

    @Test
    fun aDailyCapStopsOneAthleteCarryingTheGuild() {
        val w = war(cap = 200.0)
        w.addScore("ga", "star", 900.0, 1_000)
        w.addScore("ga", "star", 900.0, DAY + 1_000) // next day: another 200 allowed
        w.addScore("gb", "solo", 150.0, 2_000)
        assertEquals(1, w.countedMembers())
        assertEquals(400.0, w.scoreOf("ga"))
        assertEquals(150.0, w.scoreOf("gb"))
    }

    @Test
    fun scoresOutsideTheWindowOrFromStrangersAreIgnored() {
        val w = war()
        assertTrue(!w.addScore("ga", "x", 10.0, -1))
        assertTrue(!w.addScore("ga", "x", 10.0, 3 * DAY))
        assertTrue(!w.addScore("zz", "x", 10.0, 1_000))
        assertTrue(!w.addScore("ga", "x", 0.0, 1_000))
        assertTrue(w.addScore("ga", "x", 10.0, 1_000))
        assertTrue(w.isRunning(1_000) && !w.isRunning(4 * DAY))
    }

    @Test
    fun resultIsOnlyAvailableAfterTheWar() {
        val w = war()
        w.addScore("ga", "x", 10.0, 1_000)
        assertNull(w.result(DAY))
        assertTrue(w.result(3 * DAY) != null)
    }

    @Test
    fun winnerGetsThePotRatingAndSpoils() {
        val w = war()
        w.addScore("ga", "x", 300.0, 1_000)
        w.addScore("gb", "y", 100.0, 1_000)
        val r = w.result(3 * DAY, loserCells = { if (it == "gb") 200 else 0 })!!
        assertEquals("ga", r.winnerGuildId)
        assertEquals(950L, r.coinsToWinner)
        assertEquals(1016, r.newRatingA)
        assertEquals(984, r.newRatingB)
        assertEquals(20, r.cellsToTransfer)
        assertEquals("x", r.mvpUserId)
    }

    @Test
    fun spoilsAreCapped() {
        val w = war()
        w.addScore("gb", "y", 300.0, 1_000)
        val r = w.result(3 * DAY, loserCells = { 10_000 })!!
        assertEquals("gb", r.winnerGuildId)
        assertEquals(GuildWar.MAX_SPOILS, r.cellsToTransfer)
    }

    @Test
    fun drawRefundsBothAndKeepsRatings() {
        val w = war()
        w.addScore("ga", "x", 100.0, 1_000)
        w.addScore("gb", "y", 100.0, 1_000)
        val r = w.result(3 * DAY)!!
        assertNull(r.winnerGuildId)
        assertEquals(500L, r.refundEach)
        assertEquals(0L, r.coinsToWinner)
        assertEquals(1000, r.newRatingA)
        assertEquals(0, r.cellsToTransfer)
    }

    @Test
    fun aGuildCannotFightItself() {
        assertFailsWith<IllegalArgumentException> { GuildWar("w", a, a, Discipline.SQUATS, 0, DAY, 0) }
    }

    @Test
    fun matchmakingPairsSimilarGuildsAndLeavesOutliers() {
        val q = listOf(
            WarSide("g1", "A", 1000, 10), WarSide("g2", "B", 1080, 12), WarSide("g3", "C", 1500, 10),
            WarSide("g4", "D", 1490, 11), WarSide("g5", "E", 1000, 40),
        )
        val (pairs, left) = Matchmaker.pair(q)
        val ids = pairs.map { setOf(it.first.guildId, it.second.guildId) }.toSet()
        assertTrue(setOf("g1", "g2") in ids && setOf("g3", "g4") in ids)
        assertEquals(listOf("g5"), left.map { it.guildId }) // a 40-person guild is too big for anyone here
    }

    @Test
    fun waitingWidensTheAcceptedGap() {
        val q = listOf(WarSide("g1", "A", 1000, 10), WarSide("g2", "B", 1400, 10))
        assertEquals(0, Matchmaker.pair(q).first.size)
        assertEquals(1, Matchmaker.pair(q, waitedHours = { 5 }).first.size)
    }
}

class GuildRaidTest {
    private val boss = Bosses.byId("slime_squat")!!

    @Test
    fun healthScalesSublinearlyWithMembers() {
        val small = GuildRaid.hpFor(boss, 5)
        val big = GuildRaid.hpFor(boss, 20)
        assertTrue(big > small && big < small * 4)
    }

    @Test
    fun theGuildDefeatsTheBossTogether() {
        val raid = GuildRaid(boss, "g1", 8, startMs = 0)
        var t = 1_000L
        var i = 0
        while (raid.state(t) == RaidState.RUNNING && i < 100_000) {
            raid.hit("u${i % 8}", 20, t)
            t += 20_000
            i++
        }
        assertEquals(RaidState.DEFEATED, raid.state(t))
        assertEquals(0, raid.hp)
        assertEquals(raid.maxHp, raid.contributions.values.sum())
    }

    @Test
    fun theDailyCapStopsOneMemberSoloingTheRaid() {
        val raid = GuildRaid(boss, "g1", 8, startMs = 0)
        var counted = 0L
        repeat(100_000) { counted += raid.hit("hero", 50, 1_000) }
        assertEquals((raid.maxHp * GuildRaid.PER_MEMBER_DAILY_CAP).toLong().toDouble(), counted.toDouble(), 60.0)
        assertEquals(RaidState.RUNNING, raid.state(2_000))
        // Next day the hero can hit again.
        assertTrue(raid.hit("hero", 50, GuildRaid.DAY_MS + 10) > 0)
    }

    @Test
    fun expiresIfNotFinishedAndPaysHalf() {
        val raid = GuildRaid(boss, "g1", 8, startMs = 0)
        repeat(2_000) { raid.hit("u${it % 4}", 50, 1_000L + it) }
        assertTrue(raid.rewards(1_000).isEmpty())
        val end = raid.endMs + 1
        assertEquals(RaidState.EXPIRED, raid.state(end))
        val rewards = raid.rewards(end)
        assertTrue(rewards.values.none { it.lootId != null })
        assertTrue(rewards.isNotEmpty() || raid.contributions.values.all { it < raid.maxHp * 0.03 })
    }

    @Test
    fun victoryRewardsFollowContributionAndMvpGetsTheLoot() {
        val raid = GuildRaid(boss, "g1", 4, startMs = 0)
        var t = 1_000L
        // Heavy hitter does more each day than the others.
        while (raid.state(t) == RaidState.RUNNING) {
            raid.hit("mvp", 100, t); raid.hit("b", 30, t); raid.hit("c", 30, t); raid.hit("d", 30, t)
            t += 1_000
            if (t > raid.endMs) break
        }
        assertEquals(RaidState.DEFEATED, raid.state(t))
        val rewards = raid.rewards(t)
        assertEquals(boss.lootId, rewards["mvp"]!!.lootId)
        assertTrue(rewards["mvp"]!!.isMvp && rewards["b"]?.isMvp != true)
        assertTrue(rewards["mvp"]!!.coins > rewards["b"]!!.coins)
        assertNull(rewards["b"]!!.lootId)
    }

    @Test
    fun hitsAfterTheEndOrBeforeTheStartDoNothing() {
        val raid = GuildRaid(boss, "g1", 4, startMs = 10_000)
        assertEquals(0, raid.hit("a", 50, 5_000))
        assertEquals(0, raid.hit("a", 50, raid.endMs + 1))
        assertEquals(0, raid.hit("a", -5, 11_000))
        assertEquals(raid.maxHp, raid.hp)
    }
}
