package app.gymquest.core.voice

import app.gymquest.core.exercise.ExerciseType
import app.gymquest.core.exercise.FormIssue
import app.gymquest.core.exercise.WorkoutSession
import app.gymquest.core.sim.Camera
import app.gymquest.core.sim.Motions
import app.gymquest.core.sim.Noise
import app.gymquest.core.sim.RepTimeline
import app.gymquest.core.sim.Sequences
import app.gymquest.core.sim.World
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Runs a simulated set through the real session and counters and collects everything the coach says. */
private fun spoken(
    type: ExerciseType,
    reps: Int,
    repMs: Long,
    yaw: Double,
    config: VoiceConfig = VoiceConfig(),
    motion: (Double) -> World,
): List<Pair<Long, SpokenCue>> {
    val timeline = RepTimeline(reps, repMs, leadInMs = 6_000)
    val camera = Camera(yawDeg = yaw).fittedTo(motion(0.0), motion(1.0))
    val frames = Sequences.reps(timeline, camera, Noise(0.003), motion = motion)
    val session = WorkoutSession(type)
    val coach = VoiceCoach(type, config)
    val out = mutableListOf<Pair<Long, SpokenCue>>()
    for (f in frames) {
        val u = session.onFrame(f)
        coach.onSession(u, f.timestampMs).forEach { out += f.timestampMs to it }
    }
    return out
}

private fun List<Pair<Long, SpokenCue>>.texts() = map { it.second.text }

class VoiceCoachTest {
    @Test
    fun countsEachSquatAloud() {
        val cues = spoken(ExerciseType.SQUAT, 8, 2800, 60.0, motion = Motions::squat)
        val counts = cues.filter { it.second.key == "count" }.texts()
        assertEquals((1..8).map { it.toString() }, counts)
    }

    @Test
    fun announcesTheCountdownThenGo() {
        val texts = spoken(ExerciseType.SQUAT, 3, 2800, 60.0, motion = Motions::squat).texts()
        val start = texts.indexOf("3")
        assertTrue(start >= 0, "countdown missing: $texts")
        assertEquals(listOf("3", "2", "1", "¡Ya!"), texts.drop(start).take(4))
    }

    @Test
    fun praisesStreaksOfGoodRepsButNotEveryRep() {
        val praise = spoken(ExerciseType.SQUAT, 12, 3000, 60.0, motion = Motions::squat).filter { it.second.key == "praise" }
        assertTrue(praise.size in 1..4, "praise count ${praise.size}")
        // Never two compliments in quick succession.
        assertTrue(praise.zipWithNext().all { (a, b) -> b.first - a.first >= 8_000 })
    }

    @Test
    fun correctsSaggingHipsInPushUpsWithoutNagging() {
        val cues = spoken(ExerciseType.PUSH_UP, 12, 2200, 90.0) { Motions.pushUp(it, hipSag = 0.09) }
        val fixes = cues.filter { it.second.key == "correction" }
        assertTrue(fixes.isNotEmpty(), "expected a posture correction")
        assertTrue(fixes.all { it.second.text.contains("cadera") })
        assertTrue(fixes.size <= 5, "too chatty: ${fixes.size} corrections in 12 reps")
        assertTrue(fixes.zipWithNext().all { (a, b) -> b.first - a.first >= 3_000 })
    }

    @Test
    fun cleanReps_haveNoCorrections() {
        val cues = spoken(ExerciseType.PUSH_UP, 8, 2400, 90.0) { Motions.pushUp(it) }
        assertTrue(cues.none { it.second.key == "correction" }, cues.texts().toString())
    }

    @Test
    fun tellsYouToGoDeeperOnShallowReps() {
        val cues = spoken(ExerciseType.SQUAT, 6, 2800, 60.0) { t -> Motions.squat(t * 0.3) }
        val fixes = cues.filter { it.second.key == "correction" }.texts()
        assertTrue(fixes.isNotEmpty() && fixes.all { it == "Baja más" }, fixes.toString())
    }

    @Test
    fun silentModeSaysNothing() {
        val cues = spoken(ExerciseType.SQUAT, 6, 2800, 60.0, VoiceConfig(Verbosity.SILENT), Motions::squat)
        assertTrue(cues.isEmpty())
    }

    @Test
    fun minimalModeOnlyCountsEveryFifth() {
        val cues = spoken(ExerciseType.SQUAT, 10, 2800, 60.0, VoiceConfig(Verbosity.MINIMAL), Motions::squat)
        val counts = cues.filter { it.second.key == "count" }.texts()
        assertEquals(listOf("5", "10"), counts)
        assertTrue(cues.none { it.second.key == "praise" })
    }

    @Test
    fun targetsGetMilestones() {
        val cues = spoken(ExerciseType.SQUAT, 12, 2800, 60.0, VoiceConfig(targetReps = 12), Motions::squat).texts()
        assertTrue("Vas por la mitad" in cues)
        assertTrue("Tres más" in cues && "Dos más" in cues)
        assertTrue(cues.any { it.contains("última") })
        assertEquals("¡Serie completa!", cues.last { it.contains("completa") })
    }

    @Test
    fun fastExercisesAreNotCountedOneByOne() {
        val coach = VoiceCoach(ExerciseType.JUMP_ROPE)
        val said = (1..60).flatMap { n ->
            coach.onCounter(
                app.gymquest.core.exercise.CounterUpdate(
                    rep = app.gymquest.core.exercise.RepEvent(n, n * 400L, 1.0, 0, 1.0, emptySet()),
                ),
                n * 400L,
            )
        }.filter { it.key == "count" }.map { it.text }
        assertEquals(listOf("25", "50"), said)
    }

    @Test
    fun holdsAreAnnouncedEveryTenSeconds() {
        val coach = VoiceCoach(ExerciseType.PLANK)
        val said = (1..35).flatMap { s ->
            coach.onCounter(
                app.gymquest.core.exercise.CounterUpdate(
                    rep = app.gymquest.core.exercise.RepEvent(s, s * 1000L, 0.9, 1000, 0.9, emptySet(), isHoldTick = true),
                ),
                s * 1000L,
            )
        }
        assertEquals(listOf("10 segundos", "20 segundos", "30 segundos"), said.filter { it.key == "hold" }.map { it.text })
        // One compliment at 20 s (quality is high), not one every few seconds.
        assertEquals(1, said.count { it.key == "praise" })
    }

    @Test
    fun aPersistentPostureProblemIsCalledOutEvenWithoutAFinishedRep() {
        val coach = VoiceCoach(ExerciseType.PLANK)
        val update = { app.gymquest.core.exercise.CounterUpdate(liveIssues = setOf(FormIssue.HIPS_SAGGING)) }
        assertTrue(coach.onCounter(update(), 0).isEmpty())            // just started: give them a moment
        assertTrue(coach.onCounter(update(), 1_000).isEmpty())
        val cue = coach.onCounter(update(), 1_600).single()
        assertEquals("Sube la cadera, cuerpo recto", cue.text)
        assertTrue(coach.onCounter(update(), 3_000).isEmpty())         // no repeating right away
        assertTrue(coach.onCounter(update(), 10_000).isNotEmpty())     // but again if it persists
    }

    @Test
    fun correctionPhrasesAreSpecificToTheExercise() {
        assertEquals("Sube la barbilla por encima de la barra", Phrases.correction(FormIssue.NOT_DEEP_ENOUGH, ExerciseType.PULL_UP))
        assertEquals("Baja el pecho más", Phrases.correction(FormIssue.NOT_DEEP_ENOUGH, ExerciseType.PUSH_UP))
        assertEquals("Espalda recta, pecho arriba", Phrases.correction(FormIssue.LEANING_FORWARD, ExerciseType.SQUAT))
        for (type in ExerciseType.entries) for (issue in FormIssue.entries) {
            assertTrue(Phrases.correction(issue, type).isNotBlank())
        }
    }

    @Test
    fun restBetweenSetsCountsDown() {
        val coach = VoiceCoach(ExerciseType.SQUAT)
        val said = (60 downTo 0).flatMap { coach.onRestTick(it, nextSetLabel = "sentadillas") }.map { it.text }
        assertEquals("Descanso: 60 segundos", said.first())
        assertTrue("Descanso: 30 segundos" in said && "Prepárate" in said)
        assertTrue(said.any { it.contains("Siguiente: sentadillas") })
        assertEquals(listOf("3", "2", "1", "¡Vamos!"), said.takeLast(4))
        assertTrue(VoiceCoach(ExerciseType.SQUAT, VoiceConfig(Verbosity.SILENT)).onRestTick(10).isEmpty())
    }

    @Test
    fun finishSummaryMentionsCountAndQuality() {
        val coach = VoiceCoach(ExerciseType.PUSH_UP)
        val great = coach.onFinish(20, 0.93).single().text
        assertTrue(great.contains("20 repeticiones") && great.contains("93 por ciento") && great.contains("excelente"))
        assertTrue(coach.onFinish(10, 0.5).single().text.contains("forma"))
        assertTrue(VoiceCoach(ExerciseType.PLANK).onFinish(45, 0.8).single().text.contains("45 segundos"))
    }

    @Test
    fun everyCueIsShortEnoughToSpeakMidSet() {
        val all = spoken(ExerciseType.PUSH_UP, 10, 2200, 90.0, VoiceConfig(Verbosity.CHATTY, targetReps = 10)) { Motions.pushUp(it, hipSag = 0.09) }
        assertTrue(all.isNotEmpty())
        assertTrue(all.all { it.second.text.length <= 60 }, all.map { it.second.text }.filter { it.length > 60 }.toString())
    }
}
