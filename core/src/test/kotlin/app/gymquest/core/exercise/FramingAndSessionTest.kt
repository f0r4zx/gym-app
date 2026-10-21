package app.gymquest.core.exercise

import app.gymquest.core.pose.PoseFrame
import app.gymquest.core.sim.Camera
import app.gymquest.core.sim.Motions
import app.gymquest.core.sim.Noise
import app.gymquest.core.sim.RepTimeline
import app.gymquest.core.sim.Sequences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FramingAndSessionTest {

    private fun frame(camera: Camera, world: app.gymquest.core.sim.World = Motions.standing()) = camera.project(world, 0)

    @Test
    fun readyWhenWholeBodyIsInViewFromTheRightAngle() {
        val report = FramingAnalyzer(ExerciseType.SQUAT).analyze(frame(Camera(yawDeg = 30.0)))
        assertTrue(report.ready, report.message)
    }

    @Test
    fun emptyFrameMeansNoPerson() {
        val report = FramingAnalyzer(ExerciseType.SQUAT).analyze(PoseFrame.empty(0))
        assertEquals(FramingStatus.NO_PERSON, report.status)
    }

    @Test
    fun tellsYouToStepBackWhenTooClose() {
        val report = FramingAnalyzer(ExerciseType.SQUAT).analyze(frame(Camera(personHeight = 1.2, floorY = 1.1)))
        assertTrue(report.issues.any { it == FramingIssue.CUT_OFF_BOTTOM || it == FramingIssue.CUT_OFF_TOP }, "${report.issues}")
        assertTrue(!report.ready)
    }

    @Test
    fun tellsYouToComeCloserWhenTooFar() {
        val report = FramingAnalyzer(ExerciseType.SQUAT).analyze(frame(Camera(personHeight = 0.22, floorY = 0.6)))
        assertTrue(FramingIssue.TOO_FAR in report.issues, "${report.issues}")
    }

    @Test
    fun detectsCutOffFeet() {
        val report = FramingAnalyzer(ExerciseType.SQUAT).analyze(frame(Camera(floorY = 1.15)))
        assertTrue(FramingIssue.CUT_OFF_BOTTOM in report.issues, "${report.issues}")
    }

    @Test
    fun detectsBodyLeavingTheSide() {
        val report = FramingAnalyzer(ExerciseType.SQUAT).analyze(frame(Camera(centerX = 0.97)))
        assertTrue(FramingIssue.CUT_OFF_RIGHT in report.issues, "${report.issues}")
    }

    @Test
    fun pushUpsNeedASideView() {
        val analyzer = FramingAnalyzer(ExerciseType.PUSH_UP)
        val world = Motions.pushUp(0.0)
        val frontal = analyzer.analyze(Camera(yawDeg = 0.0).fittedTo(world).project(world, 0))
        val side = analyzer.analyze(Camera(yawDeg = 90.0).fittedTo(world).project(world, 0))
        assertTrue(FramingIssue.NEED_SIDE_VIEW in frontal.issues, "${frontal.issues}")
        assertTrue(side.ready, side.message)
    }

    @Test
    fun jumpingJacksNeedAFrontView() {
        val analyzer = FramingAnalyzer(ExerciseType.JUMPING_JACK)
        val side = analyzer.analyze(frame(Camera(yawDeg = 90.0)))
        val front = analyzer.analyze(frame(Camera(yawDeg = 10.0)))
        assertTrue(FramingIssue.NEED_FRONT_VIEW in side.issues, "${side.issues}")
        assertTrue(front.ready, front.message)
    }

    @Test
    fun viewAngleEstimateTracksTheCamera() {
        for (yaw in listOf(0.0, 25.0, 50.0, 75.0, 90.0)) {
            val est = FramingAnalyzer(ExerciseType.SQUAT).analyze(frame(Camera(yawDeg = yaw))).yawDegrees!!
            assertTrue(kotlin.math.abs(est - yaw) < 20, "yaw $yaw estimated as $est")
        }
    }

    // ---- Session ----------------------------------------------------------------------------

    @Test
    fun sessionRunsSetupCountdownThenCounts() {
        val timeline = RepTimeline(reps = 5, repMs = 2800, leadInMs = 6_000)
        val frames = Sequences.reps(timeline, Camera(yawDeg = 60.0), Noise(0.003), motion = Motions::squat)
        val session = WorkoutSession(ExerciseType.SQUAT)

        val states = mutableListOf<SessionState>()
        val countdowns = mutableSetOf<Int>()
        for (f in frames) {
            val u = session.onFrame(f)
            if (states.lastOrNull() != u.state) states += u.state
            u.countdown?.let { countdowns += it }
        }
        assertEquals(listOf(SessionState.SETUP, SessionState.COUNTDOWN, SessionState.ACTIVE), states)
        assertEquals(setOf(3, 2, 1), countdowns)
        assertEquals(5, session.count)
        assertTrue(session.activeMs > 10_000)
    }

    @Test
    fun repsDuringSetupAreNotCounted() {
        // The person is already squatting before the camera is "ready" (cut off at first).
        val frames = Sequences.reps(RepTimeline(3, 2800, leadInMs = 500), Camera(yawDeg = 60.0, floorY = 1.2), Noise(0.003), motion = Motions::squat)
        val session = WorkoutSession(ExerciseType.SQUAT)
        for (f in frames) session.onFrame(f)
        assertEquals(0, session.count)
        assertEquals(SessionState.SETUP, session.state)
    }

    @Test
    fun pausesWhenYouLeaveAndResumesWhenYouReturn() {
        val timeline = RepTimeline(reps = 12, repMs = 2800, leadInMs = 6_000)
        val frames = Sequences.reps(timeline, Camera(yawDeg = 60.0), Noise(0.003), motion = Motions::squat)
        val session = WorkoutSession(ExerciseType.SQUAT)
        val states = mutableListOf<SessionState>()
        var countAtPause = -1
        for (f in frames) {
            // Out of view from 20s to 30s.
            val input = if (f.timestampMs in 20_000..30_000) PoseFrame.empty(f.timestampMs, f.aspect) else f
            val u = session.onFrame(input)
            if (states.lastOrNull() != u.state) {
                states += u.state
                if (u.state == SessionState.PAUSED) countAtPause = session.count
            }
        }
        assertEquals(
            listOf(SessionState.SETUP, SessionState.COUNTDOWN, SessionState.ACTIVE, SessionState.PAUSED, SessionState.ACTIVE),
            states,
        )
        assertTrue(session.count > countAtPause, "counting should resume after the pause")
    }

    @Test
    fun comboGrowsWithGoodRepsAndBreaksOnPartials() {
        val timeline = RepTimeline(reps = 6, repMs = 3200, leadInMs = 6_000)
        val frames = Sequences.frames(timeline.totalMs, Camera(yawDeg = 60.0), Noise(0.003)) { ms ->
            val repIndex = ((ms - timeline.leadInMs) / (timeline.repMs + timeline.gapMs)).toInt()
            val depth = if (repIndex == 3) 0.22 else 1.0 // 4th rep is a shallow one
            Motions.squat(timeline.phase(ms) * depth)
        }
        val session = WorkoutSession(ExerciseType.SQUAT)
        val combos = mutableListOf<Int>()
        for (f in frames) session.onFrame(f).rep?.let { combos += session.combo }
        // Three good reps, a shallow one (not counted, resets combo), then two more.
        assertEquals(listOf(1, 2, 3, 1, 2), combos)
        assertEquals(3, session.bestCombo)
    }

    @Test
    fun derollsATiltedPhone() {
        val frames = Sequences.reps(RepTimeline(4, 2800, leadInMs = 6_000), Camera(yawDeg = 90.0, rollDeg = 25.0), Noise(0.003), motion = Motions::squat)
        val session = WorkoutSession(ExerciseType.SQUAT)
        for (f in frames) session.onFrame(f, rollDegrees = 25.0)
        assertEquals(4, session.count)
    }

    @Test
    fun caloriesScaleWithBodyWeight() {
        val a = ExerciseType.SQUAT.caloriesFor(20, 60.0)
        val b = ExerciseType.SQUAT.caloriesFor(20, 90.0)
        assertTrue(a > 0 && b > a)
        assertEquals(1.5, b / a, 1e-9)
    }
}
