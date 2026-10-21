package app.gymquest.core.platform

import app.gymquest.core.exercise.ExerciseType
import app.gymquest.core.exercise.WorkoutSession
import app.gymquest.core.sim.Camera
import app.gymquest.core.sim.Motions
import app.gymquest.core.sim.RepTimeline
import app.gymquest.core.sim.Sequences
import app.gymquest.core.tracking.ActivityRecorder
import app.gymquest.core.tracking.ActivityType
import app.gymquest.core.tracking.Geo
import app.gymquest.core.tracking.GeoPoint
import app.gymquest.core.tracking.TrackPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun healthy(frameMs: Double = 10.0) = DeviceState(80, false, false, ThermalState.NORMAL, frameMs, 2_000)

class AdaptiveQualityTest {
    @Test
    fun startsBalancedAndStaysPutWhenAllIsWell() {
        val q = AdaptiveQuality()
        repeat(20) { assertEquals(QualityProfile.BALANCED, q.update(healthy(25.0))) }
    }

    @Test
    fun upgradesToFullQualityWhenThePhoneHasPlentyOfHeadroom() {
        val q = AdaptiveQuality(recoverAfterChecks = 5)
        repeat(4) { assertEquals(QualityProfile.BALANCED, q.update(healthy(8.0))) }
        assertEquals(QualityProfile.FULL, q.update(healthy(8.0)))
    }

    @Test
    fun neverFlapsEvenWhenFullQualityIsTooHeavyForThisPhone() {
        // A mid-range phone: comfortable at BALANCED, but FULL (accurate model, 30 fps) is too slow for it.
        fun frameMs(p: QualityProfile) = when (p) {
            QualityProfile.FULL -> 40.0
            QualityProfile.BALANCED -> 15.0
            else -> 10.0
        }
        val q = AdaptiveQuality(recoverAfterChecks = 5)
        val tiers = mutableListOf<QualityProfile>()
        repeat(400) { tiers += q.update(healthy(frameMs(q.current))) }
        val changes = tiers.zipWithNext().withIndex().filter { it.value.first != it.value.second }.map { it.index }
        assertTrue(changes.size <= 14, "changed tier ${changes.size} times")
        // It learns: the last stretch is calm, with at most one more attempt.
        assertTrue(changes.count { it > 250 } <= 2, "still flapping late: ${changes.filter { it > 250 }}")
    }

    @Test
    fun dropsImmediatelyWhenFramesTakeTooLong() {
        val q = AdaptiveQuality()
        // 20 fps needs <50 ms per frame; 60 ms is too slow.
        assertEquals(QualityProfile.LIGHT, q.update(healthy(60.0)))
        assertEquals(QualityProfile.MINIMAL, q.update(healthy(90.0)))
        assertEquals(QualityProfile.MINIMAL, q.update(healthy(200.0))) // cannot go lower
    }

    @Test
    fun heatLowBatteryAndPowerSaverReduceQuality() {
        assertEquals(QualityProfile.LIGHT, AdaptiveQuality().update(healthy().copy(thermal = ThermalState.MODERATE)))
        assertEquals(QualityProfile.MINIMAL, AdaptiveQuality().update(healthy().copy(thermal = ThermalState.SEVERE)))
        assertEquals(QualityProfile.LIGHT, AdaptiveQuality().update(healthy().copy(powerSaver = true)))
        assertEquals(QualityProfile.LIGHT, AdaptiveQuality().update(healthy().copy(batteryPercent = 12)))
        assertEquals(QualityProfile.MINIMAL, AdaptiveQuality().update(healthy().copy(batteryPercent = 4)))
        // Charging cancels the low-battery rule.
        assertEquals(QualityProfile.BALANCED, AdaptiveQuality().update(healthy().copy(batteryPercent = 4, isCharging = true)))
        assertEquals(QualityProfile.LIGHT, AdaptiveQuality().update(healthy().copy(freeMemoryMb = 100)))
    }

    @Test
    fun recoversSlowlyOnceThePhoneCoolsDown() {
        val q = AdaptiveQuality(recoverAfterChecks = 5)
        q.update(healthy().copy(thermal = ThermalState.SEVERE))
        assertEquals(QualityProfile.MINIMAL, q.current)
        repeat(4) { assertEquals(QualityProfile.MINIMAL, q.update(healthy(5.0))) }
        assertEquals(QualityProfile.LIGHT, q.update(healthy(5.0)))        // fifth good check: one step up
        repeat(4) { q.update(healthy(5.0)) }
        assertEquals(QualityProfile.BALANCED, q.update(healthy(5.0)))
    }

    @Test
    fun aSingleBadMomentResetsRecoveryAndDoesNotFlap() {
        val q = AdaptiveQuality(recoverAfterChecks = 3)
        q.update(healthy(60.0)) // -> LIGHT
        repeat(2) { q.update(healthy(5.0)) }
        q.update(healthy(5.0).copy(thermal = ThermalState.MODERATE)) // equal to current tier: counter resets
        repeat(2) { assertEquals(QualityProfile.LIGHT, q.update(healthy(5.0))) }
        // The sequence of profiles never oscillates between two tiers on alternating checks.
        val seen = (0 until 30).map { i -> q.update(if (i % 2 == 0) healthy(5.0) else healthy(70.0)) }
        assertTrue(seen.zipWithNext().count { (a, b) -> a != b } <= 4)
    }

    @Test
    fun tiersGetCheaperInOrder() {
        val t = QualityProfile.TIERS
        assertTrue(t.zipWithNext().all { (a, b) -> a.analysisFps >= b.analysisFps })
        assertTrue(!QualityProfile.MINIMAL.drawSkeleton && QualityProfile.MINIMAL.mapMode == MapMode.MAP_MINIMAL)
        assertEquals(PoseModel.ACCURATE, QualityProfile.FULL.poseModel)
    }

    @Test
    fun framePacerHitsTheTargetRate() {
        val pacer = FramePacer(15)
        var processed = 0
        for (i in 0 until 300) if (pacer.shouldProcess(i * 33L)) processed++ // 30 fps camera for 10 s
        assertTrue(processed in 140..160, "processed $processed")
        val full = FramePacer(30)
        assertEquals(300, (0 until 300).count { full.shouldProcess(it * 33L) })
    }

    @Test
    fun gpsSamplesLessWhenStandingOrLowOnBattery() {
        assertEquals(1_000, GpsPolicy.intervalMs(3.0, 80, false))
        assertEquals(2_000, GpsPolicy.intervalMs(1.2, 80, false))
        assertEquals(5_000, GpsPolicy.intervalMs(0.0, 80, false))
        assertEquals(10_000, GpsPolicy.intervalMs(0.0, 10, false))
        assertEquals(5_000, GpsPolicy.intervalMs(0.0, 10, true))
    }
}

/** The counting logic must be negligible next to the pose model (typically 20-60 ms per frame). */
class LogicCostTest {
    @Test
    fun countingAFrameCostsAFractionOfAMillisecond() {
        val types = listOf(ExerciseType.SQUAT, ExerciseType.PUSH_UP, ExerciseType.BICEP_CURL, ExerciseType.JUMPING_JACK, ExerciseType.BURPEE)
        val motions: Map<ExerciseType, (Double) -> app.gymquest.core.sim.World> = mapOf(
            ExerciseType.SQUAT to Motions::squat, ExerciseType.PUSH_UP to { t -> Motions.pushUp(t) },
            ExerciseType.BICEP_CURL to { t -> Motions.curl(t) }, ExerciseType.JUMPING_JACK to Motions::jumpingJack, ExerciseType.BURPEE to Motions::burpee,
        )
        for (type in types) {
            val m = motions.getValue(type)
            val frames = Sequences.reps(RepTimeline(20, 2_000, leadInMs = 6_000), Camera(yawDeg = 60.0).fittedTo(m(0.0), m(1.0)), motion = m)
            // Warm up the JIT, then time a clean run.
            repeat(2) { val s = WorkoutSession(type); frames.forEach { s.onFrame(it) } }
            val session = WorkoutSession(type)
            val start = System.nanoTime()
            frames.forEach { session.onFrame(it) }
            val perFrameMs = (System.nanoTime() - start) / 1e6 / frames.size
            assertTrue(perFrameMs < 1.0, "$type takes $perFrameMs ms per frame")
        }
    }

    @Test
    fun gpsFilteringIsCheapToo() {
        var p = GeoPoint(19.4326, -99.1332)
        val r = ActivityRecorder(ActivityType.RUN, 70.0)
        val points = (1..3_600).map { i ->
            p = Geo.destination(p, 40.0, 3.0)
            TrackPoint(i * 1000L, p.lat, p.lon, 2240.0, 6.0)
        }
        val start = System.nanoTime()
        points.forEach { r.add(it) }
        val perFixMs = (System.nanoTime() - start) / 1e6 / points.size
        assertTrue(perFixMs < 0.5, "$perFixMs ms per fix")
        assertTrue(r.distanceM > 10_000)
    }
}
