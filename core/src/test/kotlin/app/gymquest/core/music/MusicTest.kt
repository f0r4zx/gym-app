package app.gymquest.core.music

import app.gymquest.core.exercise.ExerciseType
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val SR = 44_100

/** A synthetic dance track: kick on every beat, hi-hat on the off-beats, plus a little noise. */
private fun song(bpm: Double, seconds: Int, noise: Double = 0.01, seed: Long = 3): DoubleArray {
    val rnd = Random(seed)
    val out = DoubleArray(SR * seconds) { rnd.nextGaussian() * noise }
    val beat = 60.0 / bpm
    var t = 0.0
    var n = 0
    while (t < seconds - 0.2) {
        val start = (t * SR).toInt()
        for (i in 0 until (0.12 * SR).toInt()) {
            if (start + i >= out.size) break
            val env = exp(-i / (0.03 * SR))
            out[start + i] += 0.8 * env * sin(2 * PI * 60 * i / SR) // kick
        }
        val hat = ((t + beat / 2) * SR).toInt()
        for (i in 0 until (0.03 * SR).toInt()) {
            if (hat + i >= out.size) break
            out[hat + i] += 0.25 * exp(-i / (0.008 * SR)) * rnd.nextGaussian()
        }
        n++
        t = n * beat
    }
    return out
}

class BpmEstimatorTest {
    @Test
    fun detectsCommonTempos() {
        var exact = 0
        val tempos = listOf(90.0, 100.0, 120.0, 128.0, 140.0, 150.0, 160.0, 170.0)
        for (bpm in tempos) {
            val est = BpmEstimator.fromPcm(song(bpm, 20), SR)
            assertNotNull(est, "no tempo for $bpm")
            // Half/double time is the same groove (a 80 BPM song suits a 160 cadence), so judge by that.
            val feel = BpmMatch.effectiveBpm(est, bpm)!!
            assertEquals(bpm, feel, 2.5, "estimated $est for $bpm")
            if (abs(est - bpm) <= 2.5) exact++
        }
        assertTrue(exact >= 7, "only $exact of ${tempos.size} tempos were exact")
    }

    @Test
    fun slowSongsAreFoldedIntoTheUsefulRange() {
        val est = BpmEstimator.fromPcm(song(65.0, 24), SR)!!
        assertTrue(est in 75.0..170.0)
        assertEquals(130.0, est, 4.0)
    }

    @Test
    fun survivesNoise() {
        val est = BpmEstimator.fromPcm(song(128.0, 20, noise = 0.08, seed = 11), SR)!!
        assertEquals(128.0, est, 3.0)
    }

    @Test
    fun silenceAndTinyClipsHaveNoTempo() {
        assertNull(BpmEstimator.fromPcm(DoubleArray(SR * 10), SR))
        assertNull(BpmEstimator.fromPcm(DoubleArray(500), SR))
        assertNull(BpmEstimator.fromEnvelope(DoubleArray(10), 100.0))
    }

    @Test
    fun cadenceFromStepTimestamps() {
        val c = CadenceTracker()
        val rnd = Random(2)
        var t = 0L
        repeat(25) {
            t += (375 + rnd.nextGaussian() * 12).toLong() // 160 steps/min
            c.onStep(t)
        }
        assertEquals(160.0, c.spm!!, 4.0)
        assertTrue(c.isStable)
    }

    @Test
    fun cadenceNeedsAFewStepsAndForgivesMissedOnes() {
        val c = CadenceTracker()
        assertNull(c.spm)
        listOf(0L, 400, 800, 1200).forEach { c.onStep(it) }
        assertNull(c.spm)
        c.onStep(1600); c.onStep(2000)
        assertEquals(150.0, c.spm!!, 1.0)
        // One undetected step (a double interval) barely moves the median.
        c.onStep(2800); c.onStep(3200); c.onStep(3600)
        assertEquals(150.0, c.spm!!, 3.0)
    }

    @Test
    fun cadenceResetsAfterAStop() {
        val c = CadenceTracker()
        (1..10).forEach { c.onStep(it * 400L) }
        assertNotNull(c.spm)
        c.onStep(4_000 + 5_000)
        assertNull(c.spm)
        (1..8).forEach { c.onStep(9_000 + it * 600L) }
        assertEquals(100.0, c.spm!!, 2.0)
    }

    @Test
    fun cadenceIgnoresImpossibleIntervals() {
        val c = CadenceTracker()
        (1..8).forEach { c.onStep(it * 100L) } // sensor noise: 600 steps/min
        assertNull(c.spm)
    }

    @Test
    fun suggestedCadenceGrowsWithPace() {
        assertTrue(CadenceTarget.suggest(true, 4.0) > CadenceTarget.suggest(true, 2.5))
        assertTrue(CadenceTarget.suggest(true, 2.5) in 145.0..190.0)
        assertTrue(CadenceTarget.suggest(false, 1.4) in 90.0..130.0)
        assertEquals(160, CadenceTarget.roundForSync(159.3))
    }
}

class DjTest {
    private fun t(id: String, bpm: Double?, energy: Double, genre: String = "pop", artist: String = id) =
        Track(id, id, artist, bpm, energy, genre, 200_000)

    private val library = listOf(
        t("run160", 160.0, 0.9), t("run80", 80.0, 0.8), t("run150", 150.0, 0.85), t("chill70", 70.0, 0.15, "lofi"),
        t("warm110", 110.0, 0.45), t("unknown", null, 0.7), t("slow320", 320.0, 0.5), t("rap140", 140.0, 0.9, "rap"),
    )
    private val playlist = Playlist("p", "Correr", PlaylistKind.RUN, library.map { it.id })

    @Test
    fun effectiveBpmUsesHalfOrDoubleTime() {
        assertEquals(160.0, BpmMatch.effectiveBpm(80.0, 160.0))
        assertEquals(160.0, BpmMatch.effectiveBpm(160.0, 160.0))
        assertEquals(160.0, BpmMatch.effectiveBpm(320.0, 160.0))
        assertNull(BpmMatch.effectiveBpm(null, 160.0))
    }

    @Test
    fun matchesAreCloseToTheCadence() {
        val m = BpmMatch.matches(library, 160.0, 0.06)
        assertEquals(setOf("run160", "run80", "slow320"), m.map { it.track.id }.toSet())
        assertTrue(m.zipWithNext().all { (a, b) -> a.error <= b.error })
        assertTrue(BpmMatch.matches(library, 160.0, 0.0001).any { it.track.id == "run160" })
    }

    @Test
    fun syncOnPicksSongsAtYourCadence() {
        val dj = WorkoutDj(library, settings = DjSettings(bpmSync = true))
        repeat(3) {
            val pick = dj.next(playlist, DjContext(DjPhase.WORK, targetSpm = 160.0))!!
            assertTrue(pick.synced, pick.track.id)
            assertTrue(abs(BpmMatch.effectiveBpm(pick.track.bpm, 160.0)!! - 160.0) / 160.0 <= 0.12)
        }
    }

    @Test
    fun syncOffIgnoresTempoEntirely() {
        val dj = WorkoutDj(library, settings = DjSettings(bpmSync = false))
        val picks = (1..5).map { dj.next(playlist, DjContext(DjPhase.WORK, targetSpm = 160.0))!! }
        assertTrue(picks.none { it.synced })
        assertTrue(picks.map { it.track.id }.toSet().size == 5) // keeps rotating through the playlist
    }

    @Test
    fun fallsBackWhenNothingMatchesYourPace() {
        val dj = WorkoutDj(library.filter { it.id in setOf("chill70", "warm110") }, settings = DjSettings(bpmSync = true))
        val pick = dj.next(Playlist("p", "x", PlaylistKind.RUN, listOf("chill70", "warm110")), DjContext(DjPhase.WORK, targetSpm = 190.0))!!
        assertTrue(!pick.synced)
    }

    @Test
    fun restGetsCalmMusicAndWorkGetsEnergy() {
        val dj = WorkoutDj(library)
        assertEquals("chill70", dj.next(playlist, DjContext(DjPhase.REST))!!.track.id)
        val work = WorkoutDj(library).next(playlist, DjContext(DjPhase.WORK))!!.track
        assertTrue(work.energy >= 0.8)
        val warm = WorkoutDj(library).next(playlist, DjContext(DjPhase.WARMUP))!!.track
        assertEquals("warm110", warm.id)
    }

    @Test
    fun restMusicCanBeTurnedOff() {
        val dj = WorkoutDj(library, settings = DjSettings(restMusic = false))
        assertNull(dj.next(playlist, DjContext(DjPhase.REST)))
        assertNotNull(dj.next(playlist, DjContext(DjPhase.WORK)))
    }

    @Test
    fun doesNotRepeatSongsUntilThePlaylistIsUsedUp() {
        val dj = WorkoutDj(library)
        val first = (1..library.size).map { dj.next(playlist, DjContext(DjPhase.WORK))!!.track.id }
        assertEquals(library.size, first.toSet().size)
        assertNotNull(dj.next(playlist, DjContext(DjPhase.WORK))) // wraps around instead of going silent
    }

    @Test
    fun tasteSteersTheChoice() {
        val taste = TasteProfile()
        val rap = library.first { it.id == "rap140" }
        val pop = library.first { it.id == "run160" }
        repeat(4) { taste.record(rap, ListenEvent.LIKED) }
        repeat(4) { taste.record(pop, ListenEvent.SKIPPED_EARLY) }
        assertTrue(taste.score(rap) > 0.8 && taste.score(pop) < 0.3)
        assertEquals(0.5, taste.score(t("new", 100.0, 0.5, genre = "jazz")), 1e-9)
        val dj = WorkoutDj(library, taste)
        assertEquals("rap140", dj.next(playlist, DjContext(DjPhase.WORK))!!.track.id)
    }

    @Test
    fun feedbackFromTheDjFlowsIntoTaste() {
        val taste = TasteProfile()
        val dj = WorkoutDj(library, taste)
        val track = library.first { it.id == "run160" }
        dj.feedback(track, ListenEvent.DISLIKED)
        assertTrue(taste.score(track) < 0.4)
    }

    @Test
    fun emptyPlaylistsAndUnknownIdsAreSafe() {
        val dj = WorkoutDj(library)
        assertNull(dj.next(Playlist("e", "vacía", PlaylistKind.GYM, emptyList()), DjContext(DjPhase.WORK)))
        assertNull(dj.next(Playlist("e", "rota", PlaylistKind.GYM, listOf("ghost")), DjContext(DjPhase.WORK)))
    }

    @Test
    fun playlistEditing() {
        val p = Playlist("p", "x", PlaylistKind.CUSTOM, listOf("a", "b", "c"))
        assertEquals(listOf("a", "b", "c", "d"), p.add("d").trackIds)
        assertEquals(p, p.add("a"))
        assertEquals(listOf("b", "c"), p.remove("a").trackIds)
        assertEquals(listOf("b", "c", "a"), p.move(0, 2).trackIds)
        assertEquals(p, p.move(0, 9))
        assertNotNull(ExerciseType.SQUAT)
    }
}

class GroupListeningTest {
    @Test
    fun clockSyncTrustsTheFastestPing() {
        val clock = ClockSync()
        // True offset: host is 5,000 ms ahead. Symmetric delays: a slow ping (400 ms) and a fast one (20 ms).
        clock.addPing(sentMs = 1_000, hostMs = 1_000 + 5_000 + 200, receivedMs = 1_400)
        clock.addPing(sentMs = 2_000, hostMs = 2_000 + 5_000 + 10, receivedMs = 2_020)
        assertTrue(abs(clock.offsetMs - 5_000) <= 15, "offset ${clock.offsetMs}")
        assertEquals(10L, clock.uncertaintyMs)
        assertTrue(abs(clock.hostNow(12_000) - 17_000) <= 15)
    }

    @Test
    fun clockSyncStartsUnsyncedAndIgnoresNegativeTimes() {
        val c = ClockSync()
        assertTrue(!c.isSynced)
        assertEquals(0, c.offsetMs)
        c.addPing(100, 500, 50)
        assertTrue(!c.isSynced)
    }

    private val queue = listOf(TrackRef("a", 100_000), TrackRef("b", 60_000), TrackRef("c", 80_000))

    @Test
    fun everyPhoneComputesTheSamePositionFromTheSameEvents() {
        val a = GroupPlayback(queue)
        val b = GroupPlayback(queue)
        val events = listOf(PlaybackEvent.Start(1, 1_000), PlaybackEvent.Pause(2, 31_000), PlaybackEvent.Resume(3, 41_000), PlaybackEvent.Skip(4, 61_000, 1))
        events.forEach { a.apply(it) }
        events.reversed().forEach { b.apply(it) } // arrives backwards on the other phone
        for (now in listOf(2_000L, 45_000L, 70_000L, 100_000L)) assertEquals(a.stateAt(now), b.stateAt(now), "at $now")
        // Track a ran 30 s, paused 10 s, ran 20 s (to 61 s), then skipped to b; 9 s into b at t=70 s.
        val s = a.stateAt(70_000)!!
        assertEquals(1, s.trackIndex)
        assertEquals(9_000, s.positionMs)
        assertTrue(s.playing)
    }

    @Test
    fun pausedPlaybackHoldsItsPosition() {
        val p = GroupPlayback(queue)
        p.apply(PlaybackEvent.Start(1, 0)); p.apply(PlaybackEvent.Pause(2, 12_000))
        assertEquals(PlaybackState(0, 12_000, false, false), p.stateAt(500_000))
        p.apply(PlaybackEvent.Resume(3, 600_000))
        assertEquals(PlaybackState(0, 22_000, true, false), p.stateAt(610_000))
    }

    @Test
    fun songsAdvanceOnTheirOwnAndTheQueueEnds() {
        val p = GroupPlayback(queue)
        p.apply(PlaybackEvent.Start(1, 0))
        assertEquals(0, p.stateAt(99_000)!!.trackIndex)
        val second = p.stateAt(100_500)!!
        assertEquals(1, second.trackIndex)
        assertEquals(500, second.positionMs)
        assertEquals(2, p.stateAt(170_000)!!.trackIndex)
        assertTrue(p.stateAt(300_000)!!.finished)
    }

    @Test
    fun loopingQueuesWrapAround() {
        val p = GroupPlayback(queue, loop = true)
        p.apply(PlaybackEvent.Start(1, 0))
        val s = p.stateAt(240_000 + 10_000)!!
        assertEquals(0, s.trackIndex)
        assertEquals(10_000, s.positionMs)
        assertTrue(!s.finished)
    }

    @Test
    fun repeatedAndSeekEventsBehave() {
        val p = GroupPlayback(queue)
        assertTrue(p.apply(PlaybackEvent.Start(1, 0)))
        assertTrue(!p.apply(PlaybackEvent.Start(1, 999))) // duplicate delivery ignored
        p.apply(PlaybackEvent.Seek(2, 10_000, 50_000))
        assertEquals(55_000, p.stateAt(15_000)!!.positionMs)
        assertNull(GroupPlayback(queue).stateAt(0))
    }

    @Test
    fun aLateJoinerLandsOnTheSameBeat() {
        val host = GroupPlayback(queue)
        host.apply(PlaybackEvent.Start(1, 1_000))
        // The joiner's clock is 2.5 s behind the host's; after syncing it asks for the state at "host now".
        val clock = ClockSync()
        clock.addPing(sentMs = 50_000, hostMs = 52_500 + 15, receivedMs = 50_030)
        val joiner = GroupPlayback(queue).also { it.apply(PlaybackEvent.Start(1, 1_000)) }
        val localNow = 80_000L
        assertEquals(host.stateAt(localNow + 2_500), joiner.stateAt(clock.hostNow(localNow)))
    }

    @Test
    fun driftIsCorrectedGentlyThenByJumping() {
        assertEquals(DriftAction.None, DriftCorrector.correct(10_030, 10_000))
        val behind = DriftCorrector.correct(10_000, 10_200) as DriftAction.Rate
        assertTrue(behind.factor > 1.0 && behind.factor <= 1.04)
        val ahead = DriftCorrector.correct(10_200, 10_000) as DriftAction.Rate
        assertTrue(ahead.factor < 1.0 && ahead.factor >= 0.96)
        assertEquals(DriftAction.Seek(20_000), DriftCorrector.correct(18_000, 20_000))
    }

    @Test
    fun filesAreSplitIntoChunksThatAddUp() {
        val chunks = FileTransfer.chunks(1_000_000)
        assertEquals(4, chunks.size)
        assertEquals(1_000_000L, chunks.sumOf { it.length.toLong() })
        assertEquals(0L, chunks.first().offset)
        assertEquals(1_000_000 - 3 * FileTransfer.CHUNK_SIZE, chunks.last().length)
        assertTrue(FileTransfer.chunks(0).isEmpty())
        assertEquals(1, FileTransfer.chunks(5).size)
    }

    @Test
    fun aSongArrivesInAnyOrderAndIsVerified() {
        val data = ByteArray(700_000) { (it * 31 % 251).toByte() }
        val entry = ManifestEntry("a", "Cancion", data.size.toLong(), FileTransfer.sha256Hex(data))
        val asm = FileTransfer.Assembler(entry)
        val plan = FileTransfer.chunks(entry.sizeBytes)
        assertNull(asm.assemble())
        for (r in plan.reversed()) assertTrue(asm.accept(r.index, data.copyOfRange(r.offset.toInt(), r.offset.toInt() + r.length)))
        assertEquals(1.0, asm.progress)
        assertTrue(data.contentEquals(asm.assemble()!!))
    }

    @Test
    fun corruptedOrWrongSizedChunksAreRejected() {
        val data = ByteArray(300_000) { it.toByte() }
        val entry = ManifestEntry("a", "C", data.size.toLong(), FileTransfer.sha256Hex(data))
        val asm = FileTransfer.Assembler(entry)
        assertTrue(!asm.accept(0, ByteArray(10)))      // wrong length
        assertTrue(!asm.accept(9, ByteArray(10)))      // no such chunk
        val plan = FileTransfer.chunks(entry.sizeBytes)
        val bad = data.copyOf().also { it[5] = (it[5] + 1).toByte() }
        for (r in plan) asm.accept(r.index, bad.copyOfRange(r.offset.toInt(), r.offset.toInt() + r.length))
        assertNull(asm.assemble()) // hash mismatch: nothing is handed to the player
        asm.reset()
        assertEquals(plan.map { it.index }, asm.missing)
    }

    @Test
    fun resumingOnlyAsksForTheMissingChunks() {
        val data = ByteArray(600_000) { it.toByte() }
        val entry = ManifestEntry("a", "C", data.size.toLong(), FileTransfer.sha256Hex(data))
        val asm = FileTransfer.Assembler(entry)
        val plan = FileTransfer.chunks(entry.sizeBytes)
        asm.accept(0, data.copyOfRange(0, plan[0].length))
        assertEquals(listOf(1, 2), asm.missing)
        assertTrue(asm.progress in 0.3..0.4)
    }

    @Test
    fun theHostSeesWhoIsReady() {
        val manifest = listOf(ManifestEntry("a", "A", 1, "x"), ManifestEntry("b", "B", 1, "y"))
        val have = mapOf("host" to setOf("a", "b"), "ana" to setOf("a"), "beto" to emptySet())
        assertEquals(mapOf("host" to 1.0, "ana" to 0.5, "beto" to 0.0), FileTransfer.readiness(manifest, have))
        assertTrue(!FileTransfer.everyoneReady(manifest, have))
        assertTrue(FileTransfer.everyoneReady(manifest, mapOf("host" to setOf("a", "b"), "ana" to setOf("a", "b"))))
        assertEquals(listOf("b"), FileTransfer.missingFor(manifest, setOf("a")).map { it.trackId })
        assertTrue(SharingNotice.TEXT.contains("derecho"))
    }
}
