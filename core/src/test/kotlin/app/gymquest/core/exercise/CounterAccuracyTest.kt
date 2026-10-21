package app.gymquest.core.exercise

import app.gymquest.core.sim.Motions
import app.gymquest.core.sim.World
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import kotlin.test.assertEquals

/** Each rep-based exercise, performed 8 times from the camera angles the app recommends. */
class CounterAccuracyTest {

    private class Case(
        val type: ExerciseType,
        val repMs: Long,
        val yaws: List<Double>,
        val motion: (Double) -> World,
    )

    private val cases = listOf(
        Case(ExerciseType.SQUAT, 2800, listOf(0.0, 30.0, 60.0, 90.0), Motions::squat),
        Case(ExerciseType.PUSH_UP, 2000, listOf(90.0, 75.0), { Motions.pushUp(it) }),
        Case(ExerciseType.PULL_UP, 3000, listOf(0.0, 45.0, 90.0), Motions::pullUp),
        Case(ExerciseType.LUNGE, 2800, listOf(90.0, 75.0), { Motions.lunge(it) }),
        Case(ExerciseType.JUMPING_JACK, 1000, listOf(0.0, 20.0), Motions::jumpingJack),
        Case(ExerciseType.BICEP_CURL, 2500, listOf(0.0, 45.0, 90.0), { Motions.curl(it) }),
        Case(ExerciseType.SHOULDER_PRESS, 2800, listOf(0.0, 15.0), { Motions.shoulderPress(it) }),
        Case(ExerciseType.LATERAL_RAISE, 2800, listOf(0.0, 15.0), { Motions.lateralRaise(it) }),
        Case(ExerciseType.TRICEPS_EXTENSION, 2800, listOf(90.0, 75.0), Motions::tricepsExtension),
        Case(ExerciseType.SIT_UP, 2500, listOf(90.0, 75.0), Motions::sitUp),
        Case(ExerciseType.DEADLIFT, 3200, listOf(90.0, 75.0), Motions::deadlift),
        Case(ExerciseType.GLUTE_BRIDGE, 2800, listOf(90.0, 75.0), Motions::gluteBridge),
        Case(ExerciseType.BURPEE, 5000, listOf(90.0, 60.0), Motions::burpee),
    )

    @TestFactory
    fun countsEveryRepFromRecommendedAngles(): List<DynamicTest> =
        cases.flatMap { c ->
            c.yaws.map { yaw ->
                DynamicTest.dynamicTest("${c.type} @ ${yaw.toInt()}°") {
                    val result = run(RepCounters.create(c.type), repsFrames(8, c.repMs, yaw, motion = c.motion))
                    assertEquals(8, result.count, "${c.type} yaw=$yaw")
                }
            }
        }

    @TestFactory
    fun countsAtDifferentDistancesFromTheCamera(): List<DynamicTest> =
        listOf(ExerciseType.SQUAT, ExerciseType.PUSH_UP, ExerciseType.BICEP_CURL, ExerciseType.PULL_UP).flatMap { type ->
            val c = cases.first { it.type == type }
            // A chin-up bar sits above the head, so the person must be smaller to stay in frame.
            val heights = if (type == ExerciseType.PULL_UP) listOf(0.38, 0.7) else listOf(0.38, 0.85)
            heights.map { height ->
                DynamicTest.dynamicTest("$type with person at ${(height * 100).toInt()}% of frame") {
                    val frames = repsFrames(6, c.repMs, c.yaws.first(), personHeight = height, motion = c.motion)
                    assertEquals(6, run(RepCounters.create(type), frames).count)
                }
            }
        }

    @TestFactory
    fun toleratesStrongerSensorNoise(): List<DynamicTest> =
        listOf(ExerciseType.SQUAT, ExerciseType.PUSH_UP, ExerciseType.BICEP_CURL, ExerciseType.SHOULDER_PRESS).map { type ->
            val c = cases.first { it.type == type }
            DynamicTest.dynamicTest("$type with 2x noise") {
                val frames = repsFrames(8, c.repMs, c.yaws.first(), noise = 0.007, motion = c.motion)
                assertEquals(8, run(RepCounters.create(type), frames).count)
            }
        }

    @TestFactory
    fun countsEveryStrictness(): List<DynamicTest> =
        Strictness.entries.map { strictness ->
            DynamicTest.dynamicTest("squat, $strictness") {
                val frames = repsFrames(5, 2800, 90.0, motion = Motions::squat)
                assertEquals(5, run(RepCounters.create(ExerciseType.SQUAT, strictness), frames).count)
            }
        }
}
