package app.gymquest.core.tracking

import java.util.Random
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Builds synthetic GPS tracks at 1 Hz so the recorder can be checked against known distances. */
private class TrackBuilder(
    private var pos: GeoPoint = GeoPoint(19.4326, -99.1332),
    private val noiseM: Double = 3.0,
    seed: Long = 42,
    private val accuracy: Double = 6.0,
) {
    val points = mutableListOf<TrackPoint>()
    private val rnd = Random(seed)
    private var t = 0L
    private var alt = 2240.0

    fun move(bearing: Double, speed: Double, seconds: Int, climbPerSec: Double = 0.0, altNoise: Double = 0.0) {
        repeat(seconds) {
            pos = Geo.destination(pos, bearing, speed)
            alt += climbPerSec
            t += 1000
            points += fix(altNoise)
        }
    }

    fun stand(seconds: Int) = move(0.0, 0.0, seconds)

    private fun fix(altNoise: Double): TrackPoint {
        val noisy = if (noiseM > 0) {
            Geo.destination(pos, rnd.nextDouble() * 360, abs(rnd.nextGaussian()) * noiseM)
        } else pos
        return TrackPoint(t, noisy.lat, noisy.lon, alt + rnd.nextGaussian() * altNoise, accuracy)
    }

    fun raw(timeMs: Long, p: GeoPoint, accuracy: Double = 5.0, alt: Double? = null) = TrackPoint(timeMs, p.lat, p.lon, alt, accuracy)
}

private fun record(type: ActivityType, points: List<TrackPoint>, weight: Double = 70.0, config: RecorderConfig = RecorderConfig()): ActivityRecorder {
    val r = ActivityRecorder(type, weight, config)
    points.forEach { r.add(it) }
    return r
}

class ActivityRecorderTest {
    @Test
    fun noisyStraightRunMeasuresCloseToTrueDistance() {
        val b = TrackBuilder()
        b.move(45.0, 3.0, 600) // 1,800 m at 3 m/s
        val r = record(ActivityType.RUN, b.points)
        assertTrue(abs(r.distanceM - 1800.0) / 1800.0 < 0.04, "distance ${r.distanceM}")
        assertEquals(RecorderState.RECORDING, r.state)
    }

    @Test
    fun cleanTrackIsAlmostExact() {
        val b = TrackBuilder(noiseM = 0.0)
        b.move(120.0, 4.0, 500)
        val r = record(ActivityType.RUN, b.points)
        assertTrue(abs(r.distanceM - 2000.0) < 20.0, "distance ${r.distanceM}")
        assertTrue(abs(r.summary().avgSpeedMps - 4.0) < 0.1)
    }

    @Test
    fun standingStillGainsNoDistanceAndAutoPauses() {
        val b = TrackBuilder(noiseM = 4.0, accuracy = 8.0)
        b.move(0.0, 3.0, 30)
        b.stand(120)
        val r = record(ActivityType.RUN, b.points)
        assertTrue(r.distanceM < 120.0 + 25.0, "drifted to ${r.distanceM}")
        assertEquals(RecorderState.AUTO_PAUSED, r.state)
        assertTrue(r.movingMs < 60_000, "moving ${r.movingMs}")
        assertTrue(r.elapsedMs >= 149_000)
    }

    @Test
    fun resumesAfterAStop() {
        val b = TrackBuilder(noiseM = 2.0)
        b.move(10.0, 3.0, 30)
        b.stand(30)
        b.move(10.0, 3.0, 30)
        val r = record(ActivityType.RUN, b.points)
        assertEquals(RecorderState.RECORDING, r.state)
        // 60 s of real running plus a few seconds of pause detection lag.
        assertTrue(r.movingMs in 55_000..75_000, "moving ${r.movingMs}")
        assertTrue(abs(r.elapsedMs - 90_000L) <= 1_000L, "elapsed ${r.elapsedMs}")
        assertTrue(abs(r.distanceM - 180.0) < 30.0, "distance ${r.distanceM}")
    }

    @Test
    fun autoPauseCanBeDisabled() {
        val b = TrackBuilder(noiseM = 0.0)
        b.move(0.0, 3.0, 20)
        b.stand(60)
        val r = record(ActivityType.RUN, b.points, config = RecorderConfig(autoPause = false))
        assertEquals(RecorderState.RECORDING, r.state)
        assertTrue(r.movingMs >= 79_000)
    }

    @Test
    fun gpsJumpsAreRejected() {
        val b = TrackBuilder(noiseM = 0.0)
        b.move(90.0, 3.0, 20)
        val r = ActivityRecorder(ActivityType.RUN, 70.0)
        b.points.forEach { r.add(it) }
        val last = b.points.last()
        val far = Geo.destination(GeoPoint(last.lat, last.lon), 0.0, 800.0)
        assertEquals(Rejection.GPS_JUMP, r.add(TrackPoint(last.timeMs + 1000, far.lat, far.lon, accuracyM = 5.0)))
        val before = r.distanceM
        // Back on the real path afterwards.
        val next = Geo.destination(GeoPoint(last.lat, last.lon), 90.0, 3.0)
        assertNull(r.add(TrackPoint(last.timeMs + 2000, next.lat, next.lon, accuracyM = 5.0)))
        assertTrue(r.distanceM - before < 10.0)
    }

    @Test
    fun poorAccuracyAndOutOfOrderFixesAreIgnored() {
        val r = ActivityRecorder(ActivityType.WALK, 70.0)
        val p = GeoPoint(19.0, -99.0)
        assertNull(r.add(TrackPoint(1000, p.lat, p.lon, accuracyM = 5.0)))
        assertEquals(Rejection.LOW_ACCURACY, r.add(TrackPoint(2000, p.lat, p.lon + 0.0001, accuracyM = 120.0)))
        assertEquals(Rejection.OUT_OF_ORDER, r.add(TrackPoint(1000, p.lat, p.lon + 0.0001, accuracyM = 5.0)))
        assertEquals(0.0, r.distanceM)
    }

    @Test
    fun waitsForAFixBeforeRecording() {
        val r = ActivityRecorder(ActivityType.RUN, 70.0)
        assertEquals(RecorderState.WAITING_FOR_FIX, r.state)
        assertEquals(Rejection.LOW_ACCURACY, r.add(TrackPoint(0, 19.0, -99.0, accuracyM = 200.0)))
        assertEquals(RecorderState.WAITING_FOR_FIX, r.state)
    }

    @Test
    fun manualPauseFreezesStatsAndResumeDoesNotCountTheGap() {
        val b = TrackBuilder(noiseM = 0.0)
        b.move(0.0, 3.0, 30)
        val r = record(ActivityType.RUN, b.points)
        val d = r.distanceM
        r.pause()
        assertEquals(RecorderState.MANUAL_PAUSED, r.state)
        assertEquals(Rejection.PAUSED, r.add(b.raw(60_000, GeoPoint(19.5, -99.1))))
        r.resume()
        // The first fix after resuming is far away (you walked on the bus); it must not become distance.
        val far = Geo.destination(GeoPoint(19.4326, -99.1332), 0.0, 5000.0)
        assertNull(r.add(b.raw(200_000, far)))
        assertTrue(r.distanceM - d < 1.0, "gap counted: ${r.distanceM - d}")
        val next = Geo.destination(far, 0.0, 3.0)
        r.add(b.raw(201_000, next))
        assertTrue(r.distanceM - d < 10.0)
    }

    @Test
    fun splitsEveryKilometreWithInterpolatedTimes() {
        val b = TrackBuilder(noiseM = 0.0)
        b.move(60.0, 5.0, 650) // 3,250 m at exactly 5 m/s -> 200 s per km
        val r = record(ActivityType.RUN, b.points)
        assertEquals(3, r.splits.size)
        for (s in r.splits) assertEquals(200_000.0, s.durationMs.toDouble(), 3_000.0, "split ${s.index}")
        assertEquals(5.0, r.splits.first().speedMps, 0.1)
        assertEquals(listOf(1, 2, 3), r.splits.map { it.index })
    }

    @Test
    fun slowerSecondKilometreShowsInSplits() {
        val b = TrackBuilder(noiseM = 0.0)
        b.move(60.0, 4.0, 250)  // 1 km @ 4 m/s
        b.move(60.0, 2.5, 440)  // 1.1 km @ 2.5 m/s
        val r = record(ActivityType.RUN, b.points)
        assertTrue(r.splits.size >= 2)
        assertTrue(r.splits[1].durationMs > r.splits[0].durationMs * 1.4)
    }

    @Test
    fun elevationGainIgnoresNoiseButCapturesRealClimbs() {
        val climb = TrackBuilder(noiseM = 1.0)
        climb.move(0.0, 2.0, 500, climbPerSec = 0.1, altNoise = 1.5) // +50 m over 1 km
        val r = record(ActivityType.HIKE, climb.points)
        assertTrue(r.elevationGainM in 38.0..60.0, "gain ${r.elevationGainM}")
        assertTrue(r.elevationLossM < 12.0, "loss ${r.elevationLossM}")

        val flat = TrackBuilder(noiseM = 1.0)
        flat.move(0.0, 2.0, 500, climbPerSec = 0.0, altNoise = 1.5)
        val f = record(ActivityType.HIKE, flat.points)
        assertTrue(f.elevationGainM < 12.0, "flat gain ${f.elevationGainM}")
    }

    @Test
    fun descentsAreCountedAsLoss() {
        val b = TrackBuilder(noiseM = 0.0)
        b.move(0.0, 3.0, 300, climbPerSec = -0.1)
        val r = record(ActivityType.RUN, b.points)
        assertTrue(r.elevationLossM in 22.0..32.0, "loss ${r.elevationLossM}")
        assertTrue(r.elevationGainM < 3.0)
    }

    @Test
    fun summaryReportsCaloriesSpeedAndMaxSpeed() {
        val b = TrackBuilder(noiseM = 0.0)
        b.move(10.0, 2.8, 1800) // ~10 km/h for 30 min
        val s = record(ActivityType.RUN, b.points, weight = 70.0).summary()
        assertEquals(ActivityType.RUN, s.type)
        assertTrue(s.calories in 300..400, "calories ${s.calories}")
        assertTrue(s.maxSpeedMps in 2.5..3.2)
        assertTrue(abs(s.distanceM - 5040.0) < 60)
    }

    @Test
    fun deviceSpeedIsOptional() {
        val b = TrackBuilder(noiseM = 0.0)
        b.move(10.0, 3.0, 60)
        val withSpeed = b.points.map { it.copy(speedMps = 3.0) }
        assertEquals(record(ActivityType.RUN, b.points).distanceM, record(ActivityType.RUN, withSpeed).distanceM, 1.0)
    }
}

class ActivityTypeAndFormatTest {
    @Test
    fun metIncreasesWithSpeed() {
        for (t in ActivityType.entries) {
            var prev = 0.0
            for (kmh in listOf(2.0, 4.0, 6.0, 8.0, 10.0, 14.0, 18.0, 24.0, 32.0)) {
                val met = t.met(kmh)
                assertTrue(met >= prev - 1e-9, "$t at $kmh")
                prev = met
            }
        }
        assertEquals(9.8, ActivityType.RUN.met(9.7), 0.01)
        assertTrue(ActivityType.RUN.met(10.5) in 9.8..11.0)
    }

    @Test
    fun caloriesFormula() {
        // 70 kg running 30 min at 9.7 km/h: MET 9.8 -> 343 kcal
        assertEquals(343.0, ActivityType.RUN.calories(70.0, 1_800_000, 9.7 / 3.6), 1.0)
    }

    @Test
    fun formats() {
        assertEquals("5:00", Formats.pace(3.3333))
        assertEquals("--:--", Formats.pace(0.0))
        assertEquals("10.8", Formats.speedKmh(3.0))
        assertEquals("1:05:09", Formats.duration(3_909_000))
        assertEquals("4:05", Formats.duration(245_000))
        assertEquals("850 m", Formats.distance(850.4))
        assertEquals("12.35 km", Formats.distance(12_345.0))
    }

    @Test
    fun geoMath() {
        val a = GeoPoint(19.4326, -99.1332)
        val b = Geo.destination(a, 90.0, 1000.0)
        assertEquals(1000.0, Geo.distance(a, b), 0.5)
        assertEquals(90.0, Geo.bearing(a, b), 0.1)
        assertEquals(0.0, Geo.distance(a, a), 1e-9)
        // London - Paris is ~343 km
        assertEquals(343_500.0, Geo.distance(GeoPoint(51.5074, -0.1278), GeoPoint(48.8566, 2.3522)), 2_000.0)
        assertEquals(-20.0, Geo.angleDiff(10.0, 350.0), 1e-9)
        assertEquals(20.0, Geo.angleDiff(350.0, 10.0), 1e-9)
        assertEquals(1000.0, Geo.length(listOf(a, Geo.destination(a, 0.0, 400.0), Geo.destination(a, 0.0, 1000.0))), 1.0)
    }

    @Test
    fun invalidCoordinatesAreRejected() {
        kotlin.test.assertFailsWith<IllegalArgumentException> { GeoPoint(100.0, 0.0) }
        kotlin.test.assertFailsWith<IllegalArgumentException> { GeoPoint(0.0, 200.0) }
    }
}
