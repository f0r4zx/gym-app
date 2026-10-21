package app.gymquest.core.exercise

import app.gymquest.core.sim.Camera
import app.gymquest.core.sim.Motions
import app.gymquest.core.sim.Noise
import app.gymquest.core.sim.Sequences
import app.gymquest.core.sim.World
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CardioAndHoldTest {

    /** Phase of a cyclic motion: 0..1, shifted by [offset] cycles. */
    private fun cycle(ms: Long, periodMs: Long, offset: Double = 0.0): Double =
        Motions.repProfile((((ms.toDouble() / periodMs) + offset) % 1.0 + 1.0) % 1.0)

    private fun frames(totalMs: Long, yaw: Double, lying: World? = null, world: (Long) -> World) =
        Sequences.frames(totalMs, Camera(yawDeg = yaw).let { if (lying != null) it.fittedTo(lying) else it }, Noise(0.003), world = world)

    // ---- Alternating limbs --------------------------------------------------------------

    @Test
    fun simultaneousCurlsCountOncePerRep() {
        val period = 2600L
        val f = frames(1_500 + 8 * period, 0.0) { ms -> if (ms < 1_500) Motions.curl(0.0) else Motions.curl(cycle(ms - 1_500, period)) }
        assertEquals(8, run(RepCounters.create(ExerciseType.BICEP_CURL), f).count)
    }

    @Test
    fun alternatingCurlsCountEveryArm() {
        val period = 2600L
        // Left arm curls while the right is down, then swap: 8 curls per arm cycle pair = 8 reps total.
        val f = frames(1_500 + 8 * period, 0.0) { ms ->
            if (ms < 1_500) {
                Motions.curl(0.0)
            } else {
                Motions.curl(cycle(ms - 1_500, period * 2), cycle(ms - 1_500, period * 2, 0.5))
            }
        }
        val result = run(RepCounters.create(ExerciseType.BICEP_CURL), f)
        assertTrue(result.count in 7..9, "alternating curls: expected ~8, got ${result.count}")
    }

    @Test
    fun oneArmedCurlsCount() {
        val period = 2600L
        val f = frames(1_500 + 6 * period, 0.0) { ms ->
            if (ms < 1_500) Motions.curl(0.0, 0.0) else Motions.curl(cycle(ms - 1_500, period), 0.0)
        }
        assertEquals(6, run(RepCounters.create(ExerciseType.BICEP_CURL), f).count)
    }

    @Test
    fun mountainClimbersCountEachKneeDrive() {
        val period = 1_200L // one drive per leg per cycle -> 2 reps / cycle
        val cycles = 10
        val f = frames(1_500 + cycles * period, 90.0, Motions.plank()) { ms ->
            if (ms < 1_500) Motions.mountainClimber(0.0, 0.0)
            else Motions.mountainClimber(cycle(ms - 1_500, period), cycle(ms - 1_500, period, 0.5))
        }
        val count = run(RepCounters.create(ExerciseType.MOUNTAIN_CLIMBER), f).count
        assertTrue(count in 18..20, "expected ~20 knee drives, got $count")
    }

    @Test
    fun highKneesCountEachKnee() {
        val period = 1_000L
        val cycles = 12
        val f = frames(1_500 + cycles * period, 0.0) { ms ->
            if (ms < 1_500) Motions.highKnees(0.0, 0.0)
            else Motions.highKnees(cycle(ms - 1_500, period), cycle(ms - 1_500, period, 0.5))
        }
        val count = run(RepCounters.create(ExerciseType.HIGH_KNEES), f).count
        assertTrue(count in 22..24, "expected ~24 knees, got $count")
    }

    @Test
    fun marchingInPlaceIsNotHighKnees() {
        val period = 1_000L
        val f = frames(12_000, 0.0) { ms ->
            Motions.highKnees(cycle(ms, period) * 0.28, cycle(ms, period, 0.5) * 0.28)
        }
        assertEquals(0, run(RepCounters.create(ExerciseType.HIGH_KNEES), f).count)
    }

    @Test
    fun jumpRopeCountsJumps() {
        val jumpsPerSecond = 2.5
        val seconds = 10
        val f = Sequences.frames(2_000L + seconds * 1000L, Camera(), Noise(0.002)) { ms ->
            val t = (ms - 2_000).coerceAtLeast(0) / 1000.0
            val h = if (ms < 2_000) 0.0 else 0.035 * max(0.0, sin(2 * PI * jumpsPerSecond * t))
            Motions.jumpRope(h)
        }
        val count = run(RepCounters.create(ExerciseType.JUMP_ROPE), f).count
        assertTrue(count in 24..26, "expected 25 jumps, got $count")
    }

    @Test
    fun fastJumpingJacksStillCount() {
        val f = frames(2_000 + 20 * 700L, 0.0) { ms ->
            if (ms < 2_000) Motions.jumpingJack(0.0) else Motions.jumpingJack(cycle(ms - 2_000, 700))
        }
        val count = run(RepCounters.create(ExerciseType.JUMPING_JACK), f).count
        assertTrue(count in 19..20, "expected 20 jumping jacks, got $count")
    }

    // ---- Holds ------------------------------------------------------------------------------

    @Test
    fun plankCountsSecondsOfGoodForm() {
        val f = frames(14_000, 90.0, Motions.plank()) { Motions.plank() }
        val counter = RepCounters.create(ExerciseType.PLANK)
        val result = run(counter, f)
        assertTrue(result.count in 12..14, "expected ~13 s held, got ${result.count}")
        assertTrue(result.events.all { it.isHoldTick })
    }

    @Test
    fun sagginPlankDoesNotEarnTime() {
        val f = frames(10_000, 90.0, Motions.plank()) { Motions.plank(hipSag = 0.13) }
        val result = run(RepCounters.create(ExerciseType.PLANK), f)
        assertTrue(result.count <= 1, "a collapsed plank should not score, got ${result.count}s")
    }

    @Test
    fun plankPausesWhenYouDropAndResumes() {
        // 5 s good, 5 s collapsed, 5 s good again.
        val f = frames(15_000, 90.0, Motions.plank()) { ms -> if (ms in 5_000..9_999) Motions.plank(hipSag = 0.15) else Motions.plank() }
        val count = run(RepCounters.create(ExerciseType.PLANK), f).count
        assertTrue(count in 8..10, "expected ~9-10 s of good form, got $count")
    }

    @Test
    fun wallSitCountsSeconds() {
        val f = frames(12_000, 90.0) { Motions.wallSit() }
        val count = run(RepCounters.create(ExerciseType.WALL_SIT), f).count
        assertTrue(count in 10..12, "expected ~11 s, got $count")
    }

    @Test
    fun standingIsNotAWallSit() {
        val f = frames(8_000, 90.0) { Motions.standing() }
        assertEquals(0, run(RepCounters.create(ExerciseType.WALL_SIT), f).count)
    }
}
