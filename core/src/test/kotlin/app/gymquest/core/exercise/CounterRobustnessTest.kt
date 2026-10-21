package app.gymquest.core.exercise

import app.gymquest.core.pose.Joint
import app.gymquest.core.pose.PoseFrame
import app.gymquest.core.pose.derolled
import app.gymquest.core.sim.Camera
import app.gymquest.core.sim.Motions
import app.gymquest.core.sim.Noise
import app.gymquest.core.sim.RepTimeline
import app.gymquest.core.sim.Sequences
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CounterRobustnessTest {

    private fun standingStill(yaw: Double, ms: Long = 20_000): List<PoseFrame> =
        Sequences.frames(ms, Camera(yawDeg = yaw), Noise(0.003)) { Motions.standing() }

    @TestFactory
    fun standingStillNeverCounts(): List<DynamicTest> =
        ExerciseType.entries.flatMap { type ->
            listOf(0.0, 90.0).map { yaw ->
                DynamicTest.dynamicTest("$type @ ${yaw.toInt()}°") {
                    val result = run(RepCounters.create(type), standingStill(yaw))
                    assertEquals(0, result.count, "$type should not count while standing still")
                }
            }
        }

    @Test
    fun walkingBackAndForthIsNotASquat() {
        // Walk away from the camera and back: the person shrinks and grows, nothing else.
        val frames = Sequences.frames(14_000, Camera()) { ms ->
            Motions.standing()
        }.mapIndexed { i, f ->
            val scale = 1.0 - 0.45 * (0.5 - 0.5 * kotlin.math.cos(2 * Math.PI * i / 200.0))
            f.mapLandmarks { _, l -> l.copy(x = 0.5 + (l.x - 0.5) * scale, y = 0.93 + (l.y - 0.93) * scale) }
        }
        assertEquals(0, run(RepCounters.create(ExerciseType.SQUAT), frames).count)
        assertEquals(0, run(RepCounters.create(ExerciseType.BURPEE), frames).count)
    }

    @Test
    fun halfSquatsAreNotCountedButProduceFeedback() {
        val frames = repsFrames(6, 2800, 90.0) { t -> Motions.squat(t * 0.22) }
        val result = run(RepCounters.create(ExerciseType.SQUAT), frames)
        assertEquals(0, result.count)
        assertTrue(result.partials.isNotEmpty(), "shallow squats should trigger a 'go deeper' hint")
        assertEquals(FormIssue.NOT_DEEP_ENOUGH, result.partials.first().issue)
    }

    @Test
    fun shallowPushUpsAreNotCounted() {
        val frames = repsFrames(6, 2000, 90.0) { t -> Motions.pushUp(t * 0.35) }
        assertEquals(0, run(RepCounters.create(ExerciseType.PUSH_UP), frames).count)
    }

    @Test
    fun mixedFullAndPartialRepsCountOnlyTheFullOnes() {
        val timeline = RepTimeline(reps = 6, repMs = 2800)
        // Reps 2 and 4 (0-based 1, 3) are shallow.
        val frames = Sequences.frames(timeline.totalMs, Camera(yawDeg = 90.0), Noise(0.003)) { ms ->
            val repIndex = ((ms - timeline.leadInMs) / (timeline.repMs + timeline.gapMs)).toInt()
            val depth = if (repIndex == 1 || repIndex == 3) 0.22 else 1.0
            Motions.squat(timeline.phase(ms) * depth)
        }
        assertEquals(4, run(RepCounters.create(ExerciseType.SQUAT), frames).count)
    }

    @Test
    fun leavingTheFrameMidRepDropsThatRepOnly() {
        val timeline = RepTimeline(reps = 6, repMs = 2800)
        val camera = Camera(yawDeg = 90.0)
        val frames = Sequences.frames(timeline.totalMs, camera, Noise(0.003)) { ms -> Motions.squat(timeline.phase(ms)) }
            .map { f ->
                // Between 6.0s and 9.5s the person walks out of view.
                if (f.timestampMs in 6_000..9_500) PoseFrame.empty(f.timestampMs, f.aspect) else f
            }
        val count = run(RepCounters.create(ExerciseType.SQUAT), frames).count
        assertTrue(count in 3..5, "expected the rep during the blackout to be lost, got $count")
    }

    @Test
    fun tiltedPhoneCanBeCorrectedWithTheAccelerometerRoll() {
        val frames = repsFrames(6, 2800, 90.0, roll = 28.0, motion = Motions::squat)
        val corrected = frames.map { it.derolled(28.0) }
        assertEquals(6, run(RepCounters.create(ExerciseType.SQUAT), corrected).count)
    }

    @Test
    fun fastRepsScoreLowerThanControlledOnes() {
        val controlled = run(RepCounters.create(ExerciseType.SQUAT), repsFrames(5, 3200, 90.0, motion = Motions::squat))
        val rushed = run(RepCounters.create(ExerciseType.SQUAT), repsFrames(5, 750, 90.0, noise = 0.0, motion = Motions::squat))
        assertEquals(5, controlled.count)
        assertTrue(rushed.count >= 3, "rushed reps should still mostly count, got ${rushed.count}")
        val qc = controlled.events.map { it.quality }.average()
        val qr = rushed.events.map { it.quality }.average()
        assertTrue(qc > qr, "controlled $qc should beat rushed $qr")
        assertTrue(rushed.events.any { FormIssue.TOO_FAST in it.issues })
    }

    @Test
    fun resetClearsTheCount() {
        val counter = RepCounters.create(ExerciseType.SQUAT)
        run(counter, repsFrames(3, 2800, 90.0, motion = Motions::squat))
        assertEquals(3, counter.count)
        counter.reset()
        assertEquals(0, counter.count)
        assertEquals(4, run(counter, repsFrames(4, 2800, 90.0, motion = Motions::squat)).count)
    }

    @Test
    fun missingLowerBodyMeansNoTrackingInsteadOfWrongCounts() {
        val frames = repsFrames(4, 2800, 90.0, motion = Motions::squat).map { f ->
            f.mapLandmarks { j, l ->
                if (j == Joint.LEFT_ANKLE || j == Joint.RIGHT_ANKLE) l.copy(likelihood = 0.05) else l
            }
        }
        val result = run(RepCounters.create(ExerciseType.SQUAT), frames)
        assertEquals(0, result.count)
    }
}
