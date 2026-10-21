package app.gymquest.core.wellness

import app.gymquest.core.routes.PostResult
import app.gymquest.core.routes.ReportBook
import app.gymquest.core.routes.ReportType
import app.gymquest.core.routes.Reputation
import app.gymquest.core.routes.RouteAhead
import app.gymquest.core.routes.RouteReport
import app.gymquest.core.routes.TravelMode
import app.gymquest.core.routes.TripPlanner
import app.gymquest.core.routes.VoteResult
import app.gymquest.core.tracking.Geo
import app.gymquest.core.tracking.GeoPoint
import app.gymquest.core.tracking.MapDetail
import app.gymquest.core.tracking.OfflineMapPlanner
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val UTC: ZoneId = ZoneOffset.UTC
private const val H = 3_600_000L
private const val MIN = 60_000L
private val DAY0 = LocalDate.of(2026, 10, 21).atStartOfDay(UTC).toInstant().toEpochMilli() // wake-up day

/** Screen events at the given hour offsets from midnight of the wake-up day (negative = night before). */
private fun ev(hours: Double, on: Boolean) = ScreenEvent(DAY0 + (hours * H).toLong(), on)

class SleepNeedTest {
    @Test
    fun needDependsOnAge() {
        assertEquals(SleepNeed(9.0, 11.0), SleepNeed.forAge(13))
        assertEquals(SleepNeed(8.0, 10.0), SleepNeed.forAge(16))
        assertEquals(SleepNeed(7.0, 9.0), SleepNeed.forAge(30))
        assertEquals(SleepNeed(7.0, 8.0), SleepNeed.forAge(70))
        assertTrue(SleepNeed.forAge(15).idealHours > SleepNeed.forAge(35).idealHours)
        assertTrue(SleepNeed.forAge(35).idealHours > SleepNeed.forAge(70).idealHours)
    }

    @Test
    fun hardTrainingAddsUpToFortyFiveMinutes() {
        val base = SleepNeed.forAge(30)
        assertEquals(base, SleepNeed.withTraining(base, 30))
        assertEquals(base.idealHours + 0.25, SleepNeed.withTraining(base, 75).idealHours, 1e-9)
        assertEquals(base.idealHours + 0.75, SleepNeed.withTraining(base, 600).idealHours, 1e-9)
    }
}

class SleepEstimatorTest {
    @Test
    fun findsASimpleNightFromScreenOffToScreenOn() {
        // Phone down at 23:00, picked up at 07:00.
        val s = SleepEstimator.infer(listOf(ev(-1.0, false), ev(7.0, true)), DAY0, UTC)!!
        assertEquals(SleepSource.SCREEN_INFERRED, s.source)
        assertEquals(7.75, s.asleepHours, 0.05) // 8 h in bed minus 15 min to fall asleep
        assertEquals(DAY0 + 7 * H, s.endMs)
        assertTrue(s.confidence in 0.7..0.9)
    }

    @Test
    fun aNotificationBlipDoesNotBreakTheNight() {
        val events = listOf(ev(-1.0, false), ev(3.0, true), ev(3.0 + 40.0 / 3600, false), ev(7.0, true))
        val s = SleepEstimator.infer(events, DAY0, UTC)!!
        assertEquals(0L, s.awakeMs)
        assertEquals(7.75, s.asleepHours, 0.05)
    }

    @Test
    fun aShortCheckOfThePhoneCountsAsAnAwakening() {
        val events = listOf(ev(-1.0, false), ev(3.0, true), ev(3.0 + 8.0 / 60, false), ev(7.0, true))
        val s = SleepEstimator.infer(events, DAY0, UTC)!!
        assertTrue(s.awakeMs >= 15 * MIN, "awake ${s.awakeMs / MIN} min")
        assertTrue(s.asleepHours < 7.5)
    }

    @Test
    fun aLongSessionOnThePhoneSplitsTheNight() {
        // Awake scrolling for an hour at 02:00; the longer block (after) is the sleep.
        val events = listOf(ev(-1.0, false), ev(2.0, true), ev(3.0, false), ev(8.0, true))
        val s = SleepEstimator.infer(events, DAY0, UTC)!!
        assertEquals(DAY0 + 8 * H, s.endMs)
        assertEquals(4.75, s.asleepHours, 0.05)
    }

    @Test
    fun daytimeIdlePhoneIsNotSleep() {
        val events = listOf(ev(9.0, false), ev(13.0, true))
        assertNull(SleepEstimator.infer(events, DAY0, UTC))
        assertNull(SleepEstimator.infer(emptyList(), DAY0, UTC))
        assertNull(SleepEstimator.infer(listOf(ev(1.0, false)), DAY0, UTC))
    }

    @Test
    fun veryShortNapsAreIgnored() {
        assertNull(SleepEstimator.infer(listOf(ev(-1.0, false), ev(1.5, true)), DAY0, UTC))
    }

    @Test
    fun wearableDataBeatsGuessing() {
        val stages = SleepStages(deepMin = 90, remMin = 100, lightMin = 260, awakeMin = 30)
        val s = SleepEstimator.fromStages(DAY0 - H, stages)
        assertEquals(SleepSource.WEARABLE, s.source)
        assertEquals(450.0, s.asleepMs / MIN.toDouble(), 0.1)
        assertTrue(s.confidence > 0.9)
    }
}

class SleepScoreAndBatteryTest {
    private val need = SleepNeed.forAge(30)
    private fun night(hours: Double, awakeMin: Long = 0) =
        SleepSession(0, ((hours * 60).toLong() + awakeMin) * MIN, awakeMin * MIN, SleepSource.MANUAL, 0.8)

    @Test
    fun scoresReflectDurationAndInterruptions() {
        val great = SleepScore.score(night(8.0), need)
        val short = SleepScore.score(night(5.0), need)
        val broken = SleepScore.score(night(8.0, awakeMin = 120), need)
        assertTrue(great >= 95, "great $great")
        assertTrue(short < great - 20)
        assertTrue(broken < great - 10)
        assertTrue(SleepScore.score(night(14.0), need) < great)
    }

    @Test
    fun restorativeStagesRaiseTheScore() {
        val s = night(8.0)
        val restful = SleepScore.score(s, need, SleepStages(100, 110, 270, 0))
        val shallow = SleepScore.score(s, need, SleepStages(20, 30, 430, 0))
        assertTrue(restful > shallow)
    }

    @Test
    fun sleepDebtAddsUpOverAWeekAndIsCapped() {
        val nights = (0 until 7).map { SleepSession(it * 24 * H, it * 24 * H + 6 * H, 0, SleepSource.MANUAL, 0.8) }
        assertEquals(14.0, SleepScore.debtHours(nights, need), 1e-9) // 7 nights x 2 h short = 14 h
        assertEquals(0.0, SleepScore.debtHours(emptyList(), need))
        val wellRested = (0 until 7).map { SleepSession(it * 24 * H, it * 24 * H + 8 * H, 0, SleepSource.MANUAL, 0.8) }
        assertEquals(0.0, SleepScore.debtHours(wellRested, need))
        assertTrue(SleepScore.tonightAdvice(0.5, need).contains("bien"))
        assertTrue(SleepScore.tonightAdvice(8.0, need).contains("deuda"))
    }

    @Test
    fun irregularBedtimesAreMeasured() {
        fun at(hour: Int, day: Int) = SleepSession(day * 24 * H + hour * H, day * 24 * H + hour * H + 7 * H, 0, SleepSource.MANUAL, 0.8)
        val regular = listOf(at(23, 0), at(23, 1), at(23, 2), at(23, 3))
        val chaotic = listOf(at(21, 0), at(2, 1), at(23, 2), at(4, 3))
        assertTrue(SleepScore.bedtimeSpreadMinutes(regular, UTC) < 1.0)
        assertTrue(SleepScore.bedtimeSpreadMinutes(chaotic, UTC) > 90.0)
        assertEquals(0.0, SleepScore.bedtimeSpreadMinutes(regular.take(2), UTC))
    }

    @Test
    fun aGoodNightMeansAFullBatteryAndTheDayDrainsIt() {
        val wake = 7 * H
        val rested = BodyBattery.current(95, 0.0, wake, wake + 1 * H, emptyList())
        assertTrue(rested.level >= 90, "morning ${rested.level}")
        assertEquals(Readiness.PUSH_HARD, rested.readiness)
        val evening = BodyBattery.current(95, 0.0, wake, wake + 14 * H, emptyList())
        assertTrue(evening.level < rested.level - 25)
        assertEquals(rested.morningLevel, evening.morningLevel)
    }

    @Test
    fun badSleepAndDebtLeaveYouDrained() {
        val wake = 7 * H
        val tired = BodyBattery.current(35, 8.0, wake, wake + 1 * H, emptyList())
        assertTrue(tired.level < 45, "level ${tired.level}")
        assertTrue(tired.readiness == Readiness.LIGHT || tired.readiness == Readiness.REST)
        assertTrue(BodyBattery.morning(0, 14.0) >= 5)
        assertEquals(100, BodyBattery.morning(100, 0.0))
    }

    @Test
    fun workoutsLateScreensAndNapsMoveTheLevel() {
        val wake = 7 * H
        val now = wake + 8 * H
        val base = BodyBattery.current(80, 0.0, wake, now, emptyList()).level
        val trained = BodyBattery.current(80, 0.0, wake, now, listOf(WorkoutLoad(wake + 2 * H, 60, 8.0))).level
        val scrolled = BodyBattery.current(80, 0.0, wake, now, emptyList(), lateScreenMinutes = 120).level
        val napped = BodyBattery.current(80, 0.0, wake, now, emptyList(), napMinutes = 30).level
        assertTrue(trained < base - 10)
        assertTrue(scrolled < base)
        assertTrue(napped > base)
        // Yesterday's workout does not count today.
        assertEquals(base, BodyBattery.current(80, 0.0, wake, now, listOf(WorkoutLoad(wake - 20 * H, 60, 8.0))).level)
    }

    @Test
    fun readinessBands() {
        assertEquals(Readiness.PUSH_HARD, BodyBattery.readiness(80))
        assertEquals(Readiness.NORMAL, BodyBattery.readiness(60))
        assertEquals(Readiness.LIGHT, BodyBattery.readiness(30))
        assertEquals(Readiness.REST, BodyBattery.readiness(10))
    }

    @Test
    fun bedtimeAndForecastHelpTonightsPlan() {
        assertEquals(LocalTime.of(22, 45), BodyBattery.bedtimeFor(LocalTime.of(7, 0), SleepNeed(7.0, 9.0)))
        assertEquals(LocalTime.of(21, 45), BodyBattery.bedtimeFor(LocalTime.of(7, 0), SleepNeed.forAge(15)))
        val short = BodyBattery.forecastMorning(5.0, need, 0.0)
        val full = BodyBattery.forecastMorning(8.0, need, 0.0)
        assertTrue(full > short + 15)
    }

    @Test
    fun screenTimeAdviceIsAgeAwareAndLinksToSleep() {
        val heavy = DailyUsage(totalMs = 6 * H, pickups = 150, lateNightMs = 50 * MIN, longestSessionMs = 100 * MIN)
        val teen = ScreenTimeAdvisor.assess(heavy, 15)
        val adult = ScreenTimeAdvisor.assess(heavy, 35)
        assertEquals(UsageLevel.HIGH, teen.level)
        assertEquals(UsageLevel.WATCH, adult.level)
        assertTrue(adult.tips.any { it.contains("madrugada") } && adult.tips.any { it.contains("150") })
        assertEquals(UsageLevel.GOOD, ScreenTimeAdvisor.assess(DailyUsage(2 * H, 40, 0), 30).level)
        assertEquals(listOf("Buen uso del teléfono hoy."), ScreenTimeAdvisor.assess(DailyUsage(2 * H, 40, 0), 30).tips)
        // Light usage but a late scroll still earns a gentle warning.
        assertEquals(UsageLevel.WATCH, ScreenTimeAdvisor.assess(DailyUsage(2 * H, 40, 45 * MIN), 30).level)
        assertEquals(50, ScreenTimeAdvisor.lateMinutes(heavy))
    }
}

class RouteReportsTest {
    private val home = GeoPoint(19.4326, -99.1332)
    private val t0 = 1_800_000_000_000L

    private fun report(id: String, type: ReportType, at: GeoPoint, by: String = "ana", t: Long = t0) = RouteReport(id, type, at, t, by)

    private fun road(lengthM: Double = 5_000.0) = (0..(lengthM / 50).toInt()).map { Geo.destination(home, 90.0, it * 50.0) }

    @Test
    fun reportsExpireAccordingToTheirType() {
        val traffic = report("t", ReportType.TRAFFIC, home)
        val pothole = report("p", ReportType.POTHOLE, home)
        assertTrue(traffic.isActive(t0 + 20 * MIN) && !traffic.isActive(t0 + 40 * MIN))
        assertTrue(pothole.isActive(t0 + 20 * 86_400_000L))
        assertTrue(report("w", ReportType.WATER_FOUNTAIN, home).isActive(t0 + 300 * 86_400_000L))
    }

    @Test
    fun confirmationsKeepAReportAliveLongerDismissalsKillIt() {
        val base = report("t", ReportType.TRAFFIC, home)
        val confirmed = base.copy(confirmations = 4.0)
        assertTrue(confirmed.expiresMs() > base.expiresMs())
        assertEquals(base.createdMs + 2 * ReportType.TRAFFIC.ttlMs, confirmed.expiresMs())
        val disputed = base.copy(dismissals = 3.0)
        assertTrue(!disputed.isActive(t0 + MIN), "confidence ${disputed.confidence()}")
        assertEquals(0.5, base.confidence(), 1e-9)
    }

    @Test
    fun duplicateReportsMergeIntoAConfirmation() {
        val book = ReportBook()
        val (first, r1) = book.post(report("a", ReportType.TRAFFIC, home, "ana"), t0)
        assertEquals(PostResult.CREATED, first)
        val (second, merged) = book.post(report("b", ReportType.TRAFFIC, Geo.destination(home, 0.0, 30.0), "beto"), t0 + MIN)
        assertEquals(PostResult.MERGED, second)
        assertEquals(1.0, merged!!.confirmations)
        assertEquals(1, book.all.size)
        // Same person reporting again adds nothing.
        assertEquals(PostResult.MERGED, book.post(report("c", ReportType.TRAFFIC, home, "beto"), t0 + 2 * MIN).first)
        assertEquals(1.0, book.get(r1!!.id)!!.confirmations)
        // A different kind of thing at the same spot is separate.
        assertEquals(PostResult.CREATED, book.post(report("d", ReportType.POTHOLE, home, "carla"), t0).first)
    }

    @Test
    fun reportingIsRateLimited() {
        val book = ReportBook()
        repeat(ReportBook.MAX_POSTS_PER_HOUR) { i ->
            book.post(report("r$i", ReportType.POTHOLE, Geo.destination(home, 90.0, i * 500.0), "spammer"), t0 + i)
        }
        assertEquals(PostResult.RATE_LIMITED, book.post(report("x", ReportType.POTHOLE, Geo.destination(home, 0.0, 9_000.0), "spammer"), t0 + 100).first)
        assertEquals(PostResult.CREATED, book.post(report("y", ReportType.POTHOLE, Geo.destination(home, 0.0, 9_000.0), "spammer"), t0 + 2 * 3_600_000L).first)
    }

    @Test
    fun voteRulesRequireBeingThereAndOnlyOnce() {
        val book = ReportBook()
        book.post(report("a", ReportType.TRAFFIC, home, "ana"), t0)
        val id = book.all.first().id
        assertEquals(VoteResult.OWN_REPORT, book.vote(id, "ana", home, true))
        assertEquals(VoteResult.TOO_FAR, book.vote(id, "beto", Geo.destination(home, 0.0, 3_000.0), true))
        assertEquals(VoteResult.OK, book.vote(id, "beto", Geo.destination(home, 0.0, 200.0), true))
        assertEquals(VoteResult.ALREADY_VOTED, book.vote(id, "beto", home, false))
    }

    @Test
    fun trustedReportersCountMoreAndDismissalsRemoveReports() {
        val book = ReportBook()
        book.post(report("a", ReportType.TRAFFIC, home, "ana"), t0)
        val id = book.all.first().id
        val trusted = Reputation(confirmed = 50, dismissed = 2)
        val newbie = Reputation()
        assertTrue(trusted.voteWeight > newbie.voteWeight)
        for (u in listOf("u1", "u2", "u3")) book.vote(id, u, home, stillThere = false, reputation = trusted)
        assertTrue(!book.get(id)!!.isActive(t0 + MIN))
        assertTrue(book.near(home, 100.0, t0 + MIN).isEmpty())
        assertEquals(1, book.purge(t0 + MIN))
        assertTrue(book.all.isEmpty())
    }

    @Test
    fun nearbyLookupUsesTheGridAndRadius() {
        val book = ReportBook()
        book.post(report("a", ReportType.POTHOLE, Geo.destination(home, 0.0, 300.0), "ana"), t0)
        book.post(report("b", ReportType.ROADWORK, Geo.destination(home, 0.0, 5_000.0), "ana"), t0)
        assertEquals(listOf(ReportType.POTHOLE), book.near(home, 1_000.0, t0).map { it.type })
        assertEquals(2, book.near(home, 6_000.0, t0).size)
        assertEquals(2, book.active(t0).size)
    }

    @Test
    fun onlyReportsAlongTheRouteMatter() {
        val route = road()
        val onRoad = report("on", ReportType.TRAFFIC, Geo.destination(Geo.destination(home, 90.0, 2_000.0), 0.0, 20.0))
        val offRoad = report("off", ReportType.TRAFFIC, Geo.destination(Geo.destination(home, 90.0, 2_000.0), 0.0, 400.0))
        val found = RouteAhead.reportsOnRoute(route, listOf(onRoad, offRoad), t0)
        assertEquals(listOf("on"), found.map { it.first.id })
        assertEquals(2_000.0, found.single().second, 60.0)
    }

    @Test
    fun warningsLookAheadSortBySeverityAndSkipWhatIsBehind() {
        val route = road()
        val ahead = listOf(
            report("pothole", ReportType.POTHOLE, Geo.destination(home, 90.0, 1_400.0)),
            report("accident", ReportType.ACCIDENT, Geo.destination(home, 90.0, 2_500.0)),
            report("behind", ReportType.TRAFFIC, Geo.destination(home, 90.0, 300.0)),
            report("fountain", ReportType.WATER_FOUNTAIN, Geo.destination(home, 90.0, 1_000.0)),
            report("far", ReportType.TRAFFIC, Geo.destination(home, 90.0, 4_800.0)),
        )
        val position = Geo.destination(home, 90.0, 1_000.0)
        val w = RouteAhead.warnings(route, position, TravelMode.CAR, ahead, t0)
        assertEquals(listOf("accident", "pothole"), w.map { it.report.id })
        assertTrue(w[0].message.contains("accidente") && w[0].message.startsWith("En 1.5 kilómetros"))
        assertTrue(kotlin.math.abs(w[0].etaSeconds - (1_500 / 11.0).toInt()) <= 3, "eta ${w[0].etaSeconds}")
        assertTrue(RouteAhead.warnings(route, position, TravelMode.CAR, ahead, t0, alreadyWarned = setOf("accident")).map { it.report.id } == listOf("pothole"))
        assertTrue(RouteAhead.warnings(route, position, TravelMode.CAR, ahead, t0 + 40 * 86_400_000L).isEmpty())
    }

    @Test
    fun tripSummaryAddsDelaysAndFlagsClosures() {
        val route = road()
        val traffic = report("t", ReportType.TRAFFIC, Geo.destination(home, 90.0, 1_000.0))
        val closed = report("c", ReportType.CLOSED_ROAD, Geo.destination(home, 90.0, 3_000.0))
        val clear = TripPlanner.summarize(route, TravelMode.CAR, emptyList(), t0)
        val busy = TripPlanner.summarize(route, TravelMode.CAR, listOf(traffic, closed), t0)
        assertEquals(clear.etaMinutes + 13, busy.etaMinutes)
        assertTrue(busy.blocked && !clear.blocked)
        assertEquals(5, busy.worstSeverity)
        assertEquals(mapOf(ReportType.TRAFFIC to 1, ReportType.CLOSED_ROAD to 1), busy.reportsByType)
        // Walkers are not delayed by car traffic.
        assertEquals(TripPlanner.summarize(route, TravelMode.WALK, emptyList(), t0).etaMinutes, TripPlanner.summarize(route, TravelMode.WALK, listOf(traffic), t0).etaMinutes)
        assertTrue(TripPlanner.summarize(route, TravelMode.RUN, emptyList(), t0).etaMinutes > clear.etaMinutes)
    }

    @Test
    fun tripSummaryKnowsHowMuchOfTheRouteWorksOffline() {
        val route = road(30_000.0)
        val city = OfflineMapPlanner.around("CDMX", home, 10.0, MapDetail.STANDARD)
        val s = TripPlanner.summarize(route, TravelMode.BIKE, emptyList(), t0, listOf(city))
        assertTrue(s.offlineCoverage in 0.2..0.6, "coverage ${s.offlineCoverage}")
        assertEquals(0.0, TripPlanner.summarize(route, TravelMode.BIKE, emptyList(), t0).offlineCoverage)
    }

    @Test
    fun withoutInternetARouteIsAGuideLineThroughYourPoints() {
        val dest = Geo.destination(home, 45.0, 4_000.0)
        val mid = Geo.destination(home, 0.0, 1_500.0)
        val line = TripPlanner.straightLine(home, dest, listOf(mid))
        assertEquals(home, line.first())
        assertTrue(Geo.distance(line.last(), dest) < 1.0)
        assertTrue(line.any { Geo.distance(it, mid) < 1.0 })
        assertTrue(line.zipWithNext().all { (a, b) -> Geo.distance(a, b) <= 55.0 })
        assertNotNull(line.size)
    }
}
