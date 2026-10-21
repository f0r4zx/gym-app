package app.gymquest.camera

import app.gymquest.core.avatar.BattleModifiers
import app.gymquest.core.exercise.ExerciseType
import app.gymquest.core.exercise.SessionState
import app.gymquest.core.exercise.Strictness
import app.gymquest.core.game.Bosses
import app.gymquest.core.sim.Camera
import app.gymquest.core.sim.Motions
import app.gymquest.core.sim.Noise
import app.gymquest.core.sim.RepTimeline
import app.gymquest.core.sim.Sequences
import app.gymquest.core.voice.Verbosity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Runs the same glue the camera screen uses, fed with simulated skeletons instead of ML Kit output. */
class TrainingEngineTest {
    private fun squatFrames(reps: Int) = Sequences.reps(
        RepTimeline(reps = reps, repMs = 2800, leadInMs = 6_000),
        Camera(yawDeg = 60.0), Noise(0.003), motion = Motions::squat,
    )

    private fun engine(boss: String? = null, type: ExerciseType = ExerciseType.SQUAT) = TrainingEngine(
        type = type, boss = boss?.let { Bosses.byId(it) }, speaker = null, strictness = Strictness.NORMAL,
        verbosity = Verbosity.NORMAL, targetReps = null, modifiers = BattleModifiers.NONE,
    )

    @Test
    fun aFreeSetIsCountedAndSummarised() {
        val e = engine()
        squatFrames(8).forEach { e.onFrame(it, 0.0) }
        assertEquals(SessionState.ACTIVE, e.hud.state)
        assertEquals(8, e.hud.reps)
        val s = e.finish()
        assertEquals(8, s.reps)
        assertNull(s.bossId)
        assertTrue(s.avgQuality > 0.5)
        assertTrue(s.durationMs > 15_000)
    }

    @Test
    fun theBossLosesHealthWithEveryGoodRep() {
        val e = engine("slime_squat")
        val frames = squatFrames(6)
        frames.forEach { e.onFrame(it, 0.0) }
        val hp = e.hud.bossHp
        assertNotNull(hp)
        assertTrue("boss should be hurt, was $hp", hp!! < 1.0)
        assertTrue(e.hud.combo >= 1)
    }

    @Test
    fun defeatingTheBossEndsTheFightAndRecordsTheWin() {
        val e = engine("slime_squat")
        squatFrames(40).forEach { e.onFrame(it, 0.0) }
        assertEquals(Outcome.VICTORY, e.hud.outcome)
        val s = e.finish()
        assertEquals("slime_squat", s.bossId)
        assertEquals(true, s.victory)
        assertEquals("slime_helmet", s.lootId)
    }

    @Test
    fun leavingTheFrameBeforeStartingJustAsksYouToComeBack() {
        val e = engine()
        e.onNoPerson(1_000)
        assertEquals(SessionState.SETUP, e.hud.state)
        assertTrue(e.hud.message.isNotBlank())
        assertNull(e.pose)
    }

    @Test
    fun noRepsMeansAnEmptyButValidSummary() {
        val e = engine()
        val s = e.finish()
        assertEquals(0, s.reps)
        assertEquals(0.0, s.avgQuality, 0.0001)
    }
}
