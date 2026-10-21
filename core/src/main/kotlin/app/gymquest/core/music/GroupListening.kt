package app.gymquest.core.music

import kotlin.math.abs

/**
 * Estimates how far this phone's clock is from the host's, so everyone can start the same song at the
 * same instant. It uses the ping with the shortest round trip, because a fast ping has the least
 * uncertainty about how long the reply took.
 */
class ClockSync(private val keep: Int = 8) {
    private data class Sample(val rttMs: Long, val offsetMs: Long)

    private val samples = ArrayDeque<Sample>()

    /** [sentMs] and [receivedMs] are on this phone's clock; [hostMs] is the host's clock when it replied. */
    fun addPing(sentMs: Long, hostMs: Long, receivedMs: Long) {
        val rtt = receivedMs - sentMs
        if (rtt < 0) return
        val offset = hostMs + rtt / 2 - receivedMs
        samples.addLast(Sample(rtt, offset))
        while (samples.size > keep) samples.removeFirst()
    }

    val isSynced: Boolean get() = samples.isNotEmpty()

    /** Host time minus local time. */
    val offsetMs: Long get() = samples.minByOrNull { it.rttMs }?.offsetMs ?: 0

    val uncertaintyMs: Long get() = (samples.minOfOrNull { it.rttMs } ?: Long.MAX_VALUE / 4) / 2

    fun hostNow(localMs: Long): Long = localMs + offsetMs
}

data class TrackRef(val id: String, val durationMs: Long)

sealed class PlaybackEvent(val seq: Int, val atHostMs: Long) {
    class Start(seq: Int, atHostMs: Long, val trackIndex: Int = 0) : PlaybackEvent(seq, atHostMs)
    class Pause(seq: Int, atHostMs: Long) : PlaybackEvent(seq, atHostMs)
    class Resume(seq: Int, atHostMs: Long) : PlaybackEvent(seq, atHostMs)
    class Skip(seq: Int, atHostMs: Long, val toIndex: Int) : PlaybackEvent(seq, atHostMs)
    class Seek(seq: Int, atHostMs: Long, val positionMs: Long) : PlaybackEvent(seq, atHostMs)
}

data class PlaybackState(val trackIndex: Int, val positionMs: Long, val playing: Boolean, val finished: Boolean)

/**
 * A playlist everyone in the group hears in sync. The host's commands (play, pause, skip) are
 * numbered events; each phone replays them and works out, from the shared clock, which song and
 * second the group is at — so late joiners and reconnecting phones land on the same beat.
 */
class GroupPlayback(private val queue: List<TrackRef>, private val loop: Boolean = false) {
    private val events = java.util.TreeMap<Int, PlaybackEvent>()

    init {
        require(queue.isNotEmpty() && queue.all { it.durationMs > 0 }) { "La cola necesita canciones con duración" }
    }

    /** Events may arrive late or twice; order and repeats do not matter. */
    fun apply(e: PlaybackEvent): Boolean = events.putIfAbsent(e.seq, e) == null

    fun stateAt(hostNowMs: Long): PlaybackState? {
        if (events.isEmpty()) return null
        var index = 0
        var position = 0L
        var anchor = 0L
        var playing = false
        var started = false
        for (e in events.values) {
            if (started && playing) {
                val s = advance(index, position + (e.atHostMs - anchor))
                index = s.first
                position = s.second
            }
            anchor = e.atHostMs
            when (e) {
                is PlaybackEvent.Start -> { index = e.trackIndex.coerceIn(queue.indices); position = 0; playing = true; started = true }
                is PlaybackEvent.Pause -> playing = false
                is PlaybackEvent.Resume -> playing = true
                is PlaybackEvent.Skip -> { index = e.toIndex.coerceIn(queue.indices); position = 0 }
                is PlaybackEvent.Seek -> position = e.positionMs.coerceIn(0, queue[index].durationMs - 1)
            }
        }
        if (!started) return null
        if (!playing) return PlaybackState(index, position, false, false)
        val s = advance(index, position + (hostNowMs - anchor).coerceAtLeast(0))
        val finished = !loop && s.first >= queue.size
        return if (finished) PlaybackState(queue.lastIndex, queue.last().durationMs, false, true)
        else PlaybackState(s.first, s.second, true, false)
    }

    /** Rolls [position] forward through songs; returns the index == size when a non-looping queue ends. */
    private fun advance(startIndex: Int, startPosition: Long): Pair<Int, Long> {
        var index = startIndex
        var position = startPosition
        while (index < queue.size && position >= queue[index].durationMs) {
            position -= queue[index].durationMs
            index++
            if (index >= queue.size && loop) index = 0
        }
        return index to position
    }
}

sealed interface DriftAction {
    data object None : DriftAction

    /** Speed the player up/down slightly (1.02 = 2% faster) to ease back into sync without an audible jump. */
    data class Rate(val factor: Double) : DriftAction

    data class Seek(val toMs: Long) : DriftAction
}

object DriftCorrector {
    private const val TOLERANCE_MS = 60L
    private const val SEEK_MS = 350L
    private const val CORRECTION_WINDOW_MS = 5_000.0
    private const val MAX_RATE_CHANGE = 0.04

    /** [actualMs] is where the local player is; [expectedMs] where the group is. */
    fun correct(actualMs: Long, expectedMs: Long): DriftAction {
        val drift = actualMs - expectedMs
        val a = abs(drift)
        return when {
            a < TOLERANCE_MS -> DriftAction.None
            a < SEEK_MS -> DriftAction.Rate((1.0 - drift / CORRECTION_WINDOW_MS).coerceIn(1 - MAX_RATE_CHANGE, 1 + MAX_RATE_CHANGE))
            else -> DriftAction.Seek(expectedMs)
        }
    }
}

/** Sharing the audio files themselves, so everyone plays a local copy and the music works with no internet. */
data class ManifestEntry(val trackId: String, val title: String, val sizeBytes: Long, val sha256: String)

data class ChunkRange(val index: Int, val offset: Long, val length: Int)

object FileTransfer {
    const val CHUNK_SIZE = 256 * 1024

    fun chunks(sizeBytes: Long, chunkSize: Int = CHUNK_SIZE): List<ChunkRange> {
        require(sizeBytes >= 0 && chunkSize > 0)
        val count = ((sizeBytes + chunkSize - 1) / chunkSize).toInt()
        return List(count) { i ->
            val offset = i.toLong() * chunkSize
            ChunkRange(i, offset, minOf(chunkSize.toLong(), sizeBytes - offset).toInt())
        }
    }

    fun sha256Hex(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** Which tracks of the manifest a member still needs. */
    fun missingFor(manifest: List<ManifestEntry>, has: Set<String>): List<ManifestEntry> = manifest.filter { it.trackId !in has }

    /** Share of the manifest each member already has, so the host can see who is ready to start. */
    fun readiness(manifest: List<ManifestEntry>, membersHave: Map<String, Set<String>>): Map<String, Double> =
        membersHave.mapValues { (_, have) -> if (manifest.isEmpty()) 1.0 else manifest.count { it.trackId in have }.toDouble() / manifest.size }

    fun everyoneReady(manifest: List<ManifestEntry>, membersHave: Map<String, Set<String>>): Boolean =
        readiness(manifest, membersHave).values.all { it >= 1.0 }

    /** Receives chunks in any order, resumes after a dropped connection, and verifies the whole file at the end. */
    class Assembler(private val entry: ManifestEntry, private val chunkSize: Int = CHUNK_SIZE) {
        private val plan = chunks(entry.sizeBytes, chunkSize)
        private val received = HashMap<Int, ByteArray>()

        val missing: List<Int> get() = plan.map { it.index }.filter { it !in received }
        val progress: Double get() = if (plan.isEmpty()) 1.0 else received.size.toDouble() / plan.size

        fun accept(index: Int, bytes: ByteArray): Boolean {
            val range = plan.getOrNull(index) ?: return false
            if (bytes.size != range.length) return false
            received[index] = bytes
            return true
        }

        /** The complete file if every chunk is here and the hash matches; null otherwise. */
        fun assemble(): ByteArray? {
            if (missing.isNotEmpty()) return null
            val out = ByteArray(entry.sizeBytes.toInt())
            for (r in plan) received.getValue(r.index).copyInto(out, r.offset.toInt())
            return if (sha256Hex(out).equals(entry.sha256, ignoreCase = true)) out else null
        }

        /** Throws away a corrupted download so it can be requested again. */
        fun reset() = received.clear()
    }
}

object SharingNotice {
    const val TEXT = "Comparte solo música que tengas derecho a compartir. Los archivos se envían directamente entre los teléfonos del grupo y no se guardan en nuestros servidores."
}
