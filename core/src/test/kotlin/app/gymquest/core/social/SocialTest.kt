package app.gymquest.core.social

import app.gymquest.core.competition.Discipline
import app.gymquest.core.exercise.ExerciseType
import app.gymquest.core.game.BossTier
import app.gymquest.core.tracking.ActivityType
import app.gymquest.core.tracking.GeoPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LeaderboardTest {
    @Test
    fun ranksByPointsWithSharedRanksOnTies() {
        val rows = Leaderboards.rank(mapOf("ana" to 300.0, "beto" to 500.0, "carla" to 300.0, "dan" to 100.0))
        assertEquals(listOf("beto", "ana", "carla", "dan"), rows.map { it.userId })
        assertEquals(listOf(1, 2, 2, 4), rows.map { it.rank })
    }

    @Test
    fun showsWhoMovedUp() {
        val before = mapOf("ana" to 1, "beto" to 2, "carla" to 3)
        val rows = Leaderboards.rank(mapOf("ana" to 100.0, "beto" to 90.0, "carla" to 200.0), before)
        assertEquals(2, rows.first { it.userId == "carla" }.change)
        assertEquals(-1, rows.first { it.userId == "ana" }.change)
        assertNull(Leaderboards.rank(mapOf("new" to 5.0)).single().change)
    }

    @Test
    fun detectsWhoOvertookYou() {
        val before = mapOf("me" to 1, "ana" to 2, "beto" to 3)
        val after = mapOf("ana" to 1, "me" to 2, "beto" to 3)
        assertEquals(listOf("ana"), Leaderboards.overtakenBy("me", before, after))
        assertTrue(Leaderboards.overtakenBy("me", before, before).isEmpty())
        assertEquals(after, Leaderboards.snapshot(Leaderboards.rank(mapOf("ana" to 9.0, "me" to 5.0, "beto" to 1.0))))
    }

    @Test
    fun pointsFormulaRewardsEffortAndConsistency() {
        assertTrue(WeeklyPoints.reps(ExerciseType.PULL_UP, 10, 1.0) > WeeklyPoints.reps(ExerciseType.PUSH_UP, 10, 1.0))
        assertTrue(WeeklyPoints.distance(ActivityType.RUN, 5.0) > WeeklyPoints.distance(ActivityType.RIDE, 5.0))
        assertTrue(WeeklyPoints.boss(BossTier.EPIC, true) > WeeklyPoints.boss(BossTier.EASY, false))
        assertEquals(425.0, WeeklyPoints.boss(BossTier.EPIC, true))
        assertEquals(60.0, WeeklyPoints.streakBonus(1000))
        assertEquals(12.0, WeeklyPoints.territory(12))
    }

    @Test
    fun streakBoardOrdersByCurrentStreakFirst() {
        val board = StreakBoard.rank(
            listOf(StreakBoard.Entry("a", "Ana", 5, 40), StreakBoard.Entry("b", "Beto", 12, 12), StreakBoard.Entry("c", "Carla", 5, 90)),
        )
        assertEquals(listOf("b", "c", "a"), board.map { it.userId })
    }
}

class ChallengeAndCoupleTest {
    private fun challenge() = Challenge("c1", "Reto de lagartijas", ChallengeMetric.REPS, Discipline.PUSH_UPS, 1_000, 10_000, "ana", setOf("ana", "beto", "carla"))

    @Test
    fun onlyParticipantsScoreInsideTheWindow() {
        val b = ChallengeBoard(challenge())
        assertTrue(b.add("ana", 30.0, 2_000))
        assertTrue(!b.add("zoe", 30.0, 2_000))
        assertTrue(!b.add("ana", 30.0, 500))
        assertTrue(!b.add("ana", 30.0, 10_000))
        assertTrue(!b.add("ana", 0.0, 2_000))
        assertTrue(b.isOpen(2_000) && !b.isOpen(11_000))
        assertEquals(4_000, b.timeLeftMs(6_000))
    }

    @Test
    fun winnersAreOnlyKnownAtTheEnd() {
        val b = ChallengeBoard(challenge())
        b.add("ana", 30.0, 2_000); b.add("beto", 50.0, 2_000); b.add("carla", 50.0, 3_000)
        assertTrue(b.winners(5_000).isEmpty())
        assertEquals(setOf("beto", "carla"), b.winners(10_000).toSet())
        assertEquals(3, b.standings().size)
        assertTrue(ChallengeBoard(challenge()).winners(10_000).isEmpty())
    }

    @Test
    fun challengeValidatesItself() {
        assertFailsWith<IllegalArgumentException> { Challenge("x", "x", ChallengeMetric.KM, null, 10, 5, "a", setOf("a")) }
        assertFailsWith<IllegalArgumentException> { Challenge("x", "x", ChallengeMetric.KM, null, 5, 10, "a", setOf("b")) }
    }

    @Test
    fun coupleModeTalksAboutWhoIsAhead() {
        val ana = WeekSummary("Ana", 600.0, 4, 12.0, 8)
        val beto = WeekSummary("Beto", 400.0, 3, 5.0, 5)
        val text = CoupleMode.compare(ana, beto)
        assertTrue(text.first().contains("Ana va ganando") && text.first().contains("50%"))
        assertTrue(text.any { it.contains("4 veces") })
        assertTrue(text.any { it.contains("12.0 km") })
        assertTrue(text.any { it.contains("5 días") })
        assertTrue(CoupleMode.compare(ana.copy(points = 0.0), beto.copy(points = 0.0)).first().contains("nadie"))
        assertTrue(CoupleMode.compare(ana, ana.copy(name = "Beto")).first().contains("Empate"))
    }
}

class PrivacyTest {
    private val data = ProfileData("ana", "Ana", "Atleta", 12, 21, 40, 310, 820.0, "GYM", 62.5, 1_000, 6)
    private val defaults = PrivacySettings()

    @Test
    fun strangersSeeTheStreakAndLevelByDefaultButNothingPersonal() {
        val v = Privacy.view(data, defaults, Relation.STRANGER)!!
        assertEquals(21, v.currentStreak)
        assertEquals(40, v.longestStreak)
        assertEquals(12, v.level)
        assertEquals("Atleta", v.title)
        assertNull(v.power)
        assertNull(v.weeklyPoints)
        assertNull(v.weightKg)
        assertNull(v.lastActiveMs)
    }

    @Test
    fun friendsAndGuildmatesSeeMore() {
        for (rel in listOf(Relation.FRIEND, Relation.GUILDMATE)) {
            val v = Privacy.view(data, defaults, rel)!!
            assertEquals(310, v.power)
            assertEquals(820.0, v.weeklyPoints)
            assertEquals(1_000L, v.lastActiveMs)
            assertNull(v.weightKg) // body data stays private unless the owner opens it
        }
    }

    @Test
    fun ownersSeeEverythingAndBlockedUsersSeeNothing() {
        val me = Privacy.view(data, defaults, Relation.SELF)!!
        assertEquals(62.5, me.weightKg)
        assertNull(Privacy.view(data, defaults, Relation.BLOCKED))
    }

    @Test
    fun theStreakCanBeHiddenOrOpenedUp() {
        val hidden = defaults.copy(streak = Visibility.PRIVATE)
        assertNull(Privacy.view(data, hidden, Relation.FRIEND)!!.currentStreak)
        assertEquals(21, Privacy.view(data, hidden, Relation.SELF)!!.currentStreak)
        val friendsOnly = defaults.copy(streak = Visibility.FRIENDS)
        assertNull(Privacy.view(data, friendsOnly, Relation.STRANGER)!!.currentStreak)
        assertEquals(21, Privacy.view(data, friendsOnly, Relation.GUILDMATE)!!.currentStreak)
        val open = defaults.copy(bodyData = Visibility.PUBLIC)
        assertEquals(62.5, Privacy.view(data, open, Relation.STRANGER)!!.weightKg)
    }

    @Test
    fun friendRequestsRespectSettings() {
        assertTrue(Privacy.canSendFriendRequest(defaults, Relation.STRANGER))
        assertTrue(!Privacy.canSendFriendRequest(defaults, Relation.FRIEND))
        assertTrue(!Privacy.canSendFriendRequest(defaults.copy(allowFriendRequests = false), Relation.STRANGER))
        assertTrue(!Privacy.canSendFriendRequest(defaults, Relation.BLOCKED))
    }

    @Test
    fun liveLocationIsTimeLimitedAndAudienceLimited() {
        val share = LocationShare("ana", 1_000, 1_000 + 3_600_000, setOf("beto"))
        assertTrue(share.canSee("beto", 2_000) && share.canSee("ana", 2_000))
        assertTrue(!share.canSee("carla", 2_000))
        assertTrue(!share.canSee("beto", 1_000 + 3_600_001))
        assertFailsWith<IllegalArgumentException> { LocationShare("ana", 0, 5 * 3_600_000L, setOf("beto")) }
    }

    @Test
    fun outingsFillUpAndTrackRsvps() {
        val outing = PlannedOuting("o1", "ana", "Carrera del domingo", ActivityType.RUN, GeoPoint(19.43, -99.13), null, 1_000, maxParticipants = 2)
        val a = outing.respond("ana", Rsvp.GOING)!!.respond("beto", Rsvp.GOING)!!
        assertTrue(a.isFull())
        assertNull(a.respond("carla", Rsvp.GOING))
        assertNotNull(a.respond("carla", Rsvp.MAYBE))
        assertEquals(setOf("ana", "beto"), a.going.toSet())
        // Someone already going can change their answer even when full.
        assertEquals(listOf("ana"), a.respond("beto", Rsvp.NOT_GOING)!!.going)
    }
}

class MessagingTest {
    private fun check(
        type: MessageType, text: String? = null, attachment: String? = null, online: Boolean = true,
        recent: List<Long> = emptyList(), rel: Relation = Relation.FRIEND,
    ) = MessagePolicy.check(type, text, attachment, online, recent, 100_000, rel)

    @Test
    fun textCanBeWrittenOffline() {
        assertEquals(SendCheck.Allowed, check(MessageType.TEXT, "¿Corremos mañana?", online = false))
        assertEquals(SendCheck.Denied(SendError.EMPTY), check(MessageType.TEXT, "   "))
        assertEquals(SendCheck.Denied(SendError.TOO_LONG), check(MessageType.TEXT, "a b".repeat(600)))
    }

    @Test
    fun photosNeedInternetButStickersDoNot() {
        assertEquals(SendCheck.Denied(SendError.NEEDS_INTERNET), check(MessageType.IMAGE, attachment = "img1", online = false))
        assertEquals(SendCheck.Allowed, check(MessageType.IMAGE, attachment = "img1", online = true))
        assertEquals(SendCheck.Allowed, check(MessageType.STICKER, attachment = "flex_01", online = false))
        assertEquals(SendCheck.Denied(SendError.NO_ATTACHMENT), check(MessageType.STICKER))
        assertEquals(SendCheck.Allowed, check(MessageType.ROUTE, attachment = "route42", online = false))
    }

    @Test
    fun blockedAndSpamAndRateLimitAreRefused() {
        assertEquals(SendCheck.Denied(SendError.BLOCKED), check(MessageType.TEXT, "hola", rel = Relation.BLOCKED))
        assertEquals(SendCheck.Denied(SendError.BLOCKED_CONTENT), check(MessageType.TEXT, "aaaaaaaaaaaaaaaaaaaa"))
        assertEquals(SendCheck.Denied(SendError.BLOCKED_CONTENT), check(MessageType.TEXT, "compra compra compra compra compra compra compra compra"))
        val burst = List(MessagePolicy.MAX_PER_MINUTE) { 99_000L }
        assertEquals(SendCheck.Denied(SendError.RATE_LIMITED), check(MessageType.TEXT, "hola", recent = burst))
        assertEquals(SendCheck.Allowed, check(MessageType.TEXT, "hola", recent = List(MessagePolicy.MAX_PER_MINUTE) { 10_000L }))
    }

    @Test
    fun strangersCannotSendLinks() {
        assertEquals(SendCheck.Denied(SendError.BLOCKED_CONTENT), check(MessageType.TEXT, "mira https://malo.example/x", rel = Relation.STRANGER))
        assertEquals(SendCheck.Allowed, check(MessageType.TEXT, "mira https://ejemplo.com", rel = Relation.FRIEND))
    }

    @Test
    fun messagesMoveFromQueuedToSent() {
        val m = Message("m1", "chat", "ana", MessageType.TEXT, "hola", null, 1)
        assertEquals(DeliveryState.QUEUED, m.state)
        assertEquals(DeliveryState.SENT, MessagePolicy.advance(m, delivered = true).state)
        assertEquals(DeliveryState.QUEUED, MessagePolicy.advance(m, delivered = false).state)
        assertEquals(DeliveryState.FAILED, MessagePolicy.advance(m, delivered = false, failedForGood = true).state)
    }

    @Test
    fun stickerRules() {
        assertTrue(StickerSpec.validate(512, 512, 60_000, true).ok)
        val big = StickerSpec.validate(512, 512, 300_000, true)
        assertTrue(!big.ok && big.problems.single().contains("KB"))
        assertTrue(!StickerSpec.validate(300, 512, 10_000, true).ok)
        // A missing transparent background is a suggestion, not an error.
        val noAlpha = StickerSpec.validate(512, 512, 10_000, false)
        assertTrue(noAlpha.ok && noAlpha.problems.isNotEmpty())
    }

    @Test
    fun stickerCropsAreSquareAndStayInsideThePhoto() {
        val centred = StickerSpec.cropFor(4000, 3000)
        assertEquals(3000, centred.size)
        assertEquals(500, centred.left)
        assertEquals(0, centred.top)
        val edge = StickerSpec.cropFor(4000, 3000, focusX = 0.02)
        assertEquals(0, edge.left)
        val far = StickerSpec.cropFor(4000, 3000, focusX = 0.99)
        assertEquals(1000, far.left)
        val portrait = StickerSpec.cropFor(1000, 2000, focusY = 0.9)
        assertEquals(1000, portrait.size)
        assertEquals(1000, portrait.top)
    }

    @Test
    fun photosAreShrunkBeforeSending() {
        val big = ImagePolicy.plan(4000, 3000, 6_000_000)
        assertEquals(1280, big.width)
        assertEquals(960, big.height)
        assertTrue(big.recompress && !big.tooLarge)
        val small = ImagePolicy.plan(800, 600, 100_000)
        assertEquals(800, small.width)
        assertTrue(!small.recompress)
        assertTrue(ImagePolicy.plan(800, 600, 900_000).recompress)
        assertTrue(ImagePolicy.plan(800, 600, 50_000_000).tooLarge)
    }
}
