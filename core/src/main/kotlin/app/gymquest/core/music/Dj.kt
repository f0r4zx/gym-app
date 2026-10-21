package app.gymquest.core.music

import app.gymquest.core.exercise.ExerciseType
import kotlin.math.abs
import kotlin.math.tanh

enum class TrackSource { LOCAL, SHARED, STREAMING }

data class Track(
    val id: String,
    val title: String,
    val artist: String,
    /** Beats per minute, from the file's tag or from [BpmEstimator]; null if unknown. */
    val bpm: Double?,
    /** How intense the track feels, 0 (calm) .. 1 (hard). */
    val energy: Double = 0.5,
    val genre: String? = null,
    val durationMs: Long,
    val source: TrackSource = TrackSource.LOCAL,
    val fileHash: String? = null,
)

enum class PlaylistKind(val label: String) { RUN("Correr"), GYM("Gimnasio"), WARMUP("Calentamiento"), COOLDOWN("Enfriamiento"), REST("Descanso"), CUSTOM("Personalizada") }

data class Playlist(val id: String, val name: String, val kind: PlaylistKind, val trackIds: List<String>) {
    fun add(trackId: String) = if (trackId in trackIds) this else copy(trackIds = trackIds + trackId)
    fun remove(trackId: String) = copy(trackIds = trackIds - trackId)
    fun move(from: Int, to: Int): Playlist {
        if (from !in trackIds.indices || to !in trackIds.indices) return this
        val list = trackIds.toMutableList()
        list.add(to, list.removeAt(from))
        return copy(trackIds = list)
    }
}

enum class ListenEvent(val weight: Double) { PLAYED_FULL(0.10), SKIPPED_EARLY(-0.15), LIKED(0.30), DISLIKED(-0.50), REPLAYED(0.20) }

/** Learns what you like from what you skip, finish, replay or heart. */
class TasteProfile {
    private val genres = HashMap<String, Double>()
    private val artists = HashMap<String, Double>()

    fun record(track: Track, event: ListenEvent) {
        track.genre?.let { genres.merge(it.lowercase(), event.weight, Double::plus) }
        artists.merge(track.artist.lowercase(), event.weight * 1.5, Double::plus)
    }

    /** 0..1, 0.5 when nothing is known about this track. */
    fun score(track: Track): Double {
        val g = track.genre?.let { genres[it.lowercase()] } ?: 0.0
        val a = artists[track.artist.lowercase()] ?: 0.0
        return 0.5 + 0.5 * tanh(g + a)
    }
}

object BpmMatch {
    data class Match(val track: Track, val effectiveBpm: Double, val error: Double)

    /**
     * The tempo a track will feel like at [target] steps per minute: a 85 BPM song works for a
     * 170 cadence (one beat per two steps) and a 340 BPM one is never useful. Null if unknown.
     */
    fun effectiveBpm(bpm: Double?, target: Double): Double? =
        bpm?.let { listOf(it, it * 2, it / 2).minByOrNull { c -> abs(c - target) } }

    fun matches(tracks: List<Track>, target: Double, tolerance: Double = 0.06): List<Match> =
        tracks.mapNotNull { t ->
            val eff = effectiveBpm(t.bpm, target) ?: return@mapNotNull null
            val err = abs(eff - target) / target
            if (err <= tolerance) Match(t, eff, err) else null
        }.sortedBy { it.error }
}

enum class DjPhase { WARMUP, WORK, REST, COOLDOWN }

data class DjSettings(
    /** Pick songs that match your cadence. Off by default; the user turns it on. */
    val bpmSync: Boolean = false,
    val restMusic: Boolean = true,
    val tolerance: Double = 0.06,
)

data class DjContext(
    val phase: DjPhase,
    /** Current cadence (running) or target tempo; used only when [DjSettings.bpmSync] is on. */
    val targetSpm: Double? = null,
    val exercise: ExerciseType? = null,
)

data class DjPick(val track: Track, val synced: Boolean, val reason: String)

/**
 * The personal DJ: chooses the next song from your playlist for what you are doing right now —
 * energetic during sets, calm during rests, matching your cadence if you asked it to — and
 * learns your taste from what you skip and replay.
 */
class WorkoutDj(
    tracks: List<Track>,
    private val taste: TasteProfile = TasteProfile(),
    var settings: DjSettings = DjSettings(),
) {
    private val library = tracks.associateBy { it.id }
    private val history = ArrayDeque<String>()
    private var lastBpm: Double? = null

    fun next(playlist: Playlist, ctx: DjContext): DjPick? {
        val all = playlist.trackIds.mapNotNull { library[it] }
        if (all.isEmpty()) return null
        val fresh = all.filter { it.id !in history }.ifEmpty { all }

        if (ctx.phase == DjPhase.REST && !settings.restMusic) return null

        var pool = fresh
        var synced = false
        if (settings.bpmSync && ctx.phase == DjPhase.WORK && ctx.targetSpm != null) {
            val tight = BpmMatch.matches(fresh, ctx.targetSpm, settings.tolerance)
            val loose = if (tight.isEmpty()) BpmMatch.matches(fresh, ctx.targetSpm, settings.tolerance * 2) else tight
            if (loose.isNotEmpty()) {
                pool = loose.map { it.track }
                synced = true
            }
        }

        val targetEnergy = when (ctx.phase) {
            DjPhase.WARMUP -> 0.45
            DjPhase.WORK -> if (ctx.exercise == null || ctx.exercise.secondsPerRep > 1.5) 0.75 else 0.85
            DjPhase.REST -> 0.2
            DjPhase.COOLDOWN -> 0.15
        }
        val best = pool.maxWithOrNull(
            compareBy<Track> { score(it, targetEnergy, ctx.phase == DjPhase.REST) }.thenByDescending { it.id },
        ) ?: return null

        history.addLast(best.id)
        while (history.size > HISTORY) history.removeFirst()
        lastBpm = best.bpm
        val reason = when {
            synced -> "Va a tu ritmo (${ctx.targetSpm!!.toInt()} pasos/min)"
            ctx.phase == DjPhase.REST -> "Música tranquila para descansar"
            ctx.phase == DjPhase.WORK -> "Energía para la serie"
            ctx.phase == DjPhase.WARMUP -> "Calentando motores"
            else -> "Para enfriar"
        }
        return DjPick(best, synced, reason)
    }

    private fun score(t: Track, targetEnergy: Double, resting: Boolean): Double {
        val energyFit = 1.0 - abs(t.energy - targetEnergy)
        val tasteFit = taste.score(t)
        // Smooth transitions: avoid a sudden jump in tempo between consecutive songs.
        val smooth = lastBpm?.let { prev -> t.bpm?.let { 1.0 - (abs(it - prev) / prev).coerceAtMost(1.0) } } ?: 0.5
        val calm = if (resting && t.bpm != null && t.bpm < 100) 0.1 else 0.0
        return 0.45 * energyFit + 0.35 * tasteFit + 0.2 * smooth + calm
    }

    fun feedback(track: Track, event: ListenEvent) = taste.record(track, event)

    private companion object {
        const val HISTORY = 10
    }
}
