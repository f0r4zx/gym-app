package app.gymquest.core.exercise

import app.gymquest.core.sim.Motions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FormFeedbackTest {
    @Test
    fun flagsSaggingHipsInPushUps() {
        val result = run(RepCounters.create(ExerciseType.PUSH_UP), repsFrames(5, 2000, 90.0) { Motions.pushUp(it, hipSag = 0.07) })
        assertEquals(5, result.count)
        assertTrue(FormIssue.HIPS_SAGGING in result.issues, "issues=${result.issues}")
    }

    @Test
    fun flagsPikedHipsInPushUps() {
        val result = run(RepCounters.create(ExerciseType.PUSH_UP), repsFrames(5, 2000, 90.0) { Motions.pushUp(it, hipSag = -0.10) })
        assertEquals(5, result.count)
        assertTrue(FormIssue.HIPS_PIKED in result.issues, "issues=${result.issues}")
    }

    @Test
    fun cleanPushUpsHaveNoPostureIssues() {
        val result = run(RepCounters.create(ExerciseType.PUSH_UP), repsFrames(5, 2200, 90.0) { Motions.pushUp(it) })
        assertTrue(result.issues.none { it != FormIssue.TOO_FAST }, "issues=${result.issues}")
        assertTrue(result.events.all { it.quality > 0.75 }, "qualities=${result.events.map { it.quality }}")
    }

    @Test
    fun postureProblemsLowerQuality() {
        val clean = run(RepCounters.create(ExerciseType.PUSH_UP), repsFrames(5, 2200, 90.0) { Motions.pushUp(it) })
        val sag = run(RepCounters.create(ExerciseType.PUSH_UP), repsFrames(5, 2200, 90.0) { Motions.pushUp(it, hipSag = 0.08) })
        assertTrue(clean.events.map { it.quality }.average() > sag.events.map { it.quality }.average())
    }

    @Test
    fun eventsCarryRunningCountAndDuration() {
        val result = run(RepCounters.create(ExerciseType.SQUAT), repsFrames(4, 2800, 90.0, motion = Motions::squat))
        assertEquals(listOf(1, 2, 3, 4), result.events.map { it.count })
        assertTrue(result.events.all { it.durationMs in 1500..3200 }, "durations=${result.events.map { it.durationMs }}")
        assertTrue(result.events.all { it.depth >= 0.8 })
    }

    @Test
    fun deeperSquatsScoreHigher() {
        val shallow = run(RepCounters.create(ExerciseType.SQUAT), repsFrames(4, 2800, 90.0) { Motions.squat(it * 0.5) })
        val deep = run(RepCounters.create(ExerciseType.SQUAT), repsFrames(4, 2800, 90.0) { Motions.squat(it) })
        assertTrue(shallow.count == 4 && deep.count == 4, "shallow=${shallow.count} deep=${deep.count}")
        assertTrue(deep.events.map { it.quality }.average() > shallow.events.map { it.quality }.average())
    }

    @Test
    fun strictModeRejectsWhatRelaxedAccepts() {
        val frames = repsFrames(5, 2800, 90.0) { Motions.squat(it * 0.34) }
        val relaxed = run(RepCounters.create(ExerciseType.SQUAT, Strictness.RELAXED), frames).count
        val strict = run(RepCounters.create(ExerciseType.SQUAT, Strictness.STRICT), frames).count
        assertTrue(relaxed > strict, "relaxed=$relaxed strict=$strict")
    }
}
