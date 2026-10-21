package app.gymquest.music

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.gymquest.core.music.DjContext
import app.gymquest.core.music.DjPhase
import app.gymquest.core.music.DjSettings
import app.gymquest.core.music.ListenEvent
import app.gymquest.core.music.Playlist
import app.gymquest.core.music.PlaylistKind
import app.gymquest.core.music.TasteProfile
import app.gymquest.core.music.Track
import app.gymquest.core.music.WorkoutDj
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Everything about music in one place: the songs on the phone, their tempo (worked out once and
 * remembered), the player, and the "personal DJ" that picks what plays next for the moment of the
 * workout. Tempo matching is only used when the user turns it on.
 */
class MusicModel(context: Context, private val scope: CoroutineScope) {
    private val app = context.applicationContext
    private val cacheFile = File(app.filesDir, "bpm_cache.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = MapSerializer(String.serializer(), Double.serializer())

    private val _tracks = MutableStateFlow<List<LocalTrack>>(emptyList())
    val tracks: StateFlow<List<LocalTrack>> = _tracks.asStateFlow()

    /** Track id → BPM. A missing key means "not analysed yet"; 0.0 means "no clear beat". */
    private val _bpm = MutableStateFlow(loadCache())
    val bpm: StateFlow<Map<String, Double>> = _bpm.asStateFlow()

    private val _analysis = MutableStateFlow<Pair<Int, Int>?>(null)
    val analysis: StateFlow<Pair<Int, Int>?> = _analysis.asStateFlow()

    private val _nowPlaying = MutableStateFlow<LocalTrack?>(null)
    val nowPlaying: StateFlow<LocalTrack?> = _nowPlaying.asStateFlow()

    private val _playing = MutableStateFlow(false)
    val playing: StateFlow<Boolean> = _playing.asStateFlow()

    private val _djOn = MutableStateFlow(false)
    val djOn: StateFlow<Boolean> = _djOn.asStateFlow()

    private val _phase = MutableStateFlow(DjPhase.WORK)
    val phase: StateFlow<DjPhase> = _phase.asStateFlow()

    private val _targetBpm = MutableStateFlow(130.0)
    val targetBpm: StateFlow<Double> = _targetBpm.asStateFlow()

    private val _djNote = MutableStateFlow("")
    val djNote: StateFlow<String> = _djNote.asStateFlow()

    private val taste = TasteProfile()
    private var dj: WorkoutDj? = null
    private var settings = DjSettings()
    private var controller: MediaController? = null
    private var analysisJob: Job? = null
    private var startedAtMs = 0L

    init {
        val token = SessionToken(app, ComponentName(app, MusicService::class.java))
        val future = MediaController.Builder(app, token).buildAsync()
        future.addListener({
            val c = runCatching { future.get() }.getOrNull() ?: return@addListener
            controller = c
            c.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    _playing.value = isPlaying
                }

                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED) onTrackEnded()
                }
            })
        }, ContextCompat.getMainExecutor(app))
    }

    fun configure(bpmSync: Boolean, restMusic: Boolean) {
        settings = DjSettings(bpmSync = bpmSync, restMusic = restMusic)
        dj?.settings = settings
    }

    fun setPhase(p: DjPhase) {
        _phase.value = p
    }

    fun setTargetBpm(bpm: Double) {
        _targetBpm.value = bpm
    }

    fun setDj(on: Boolean) {
        _djOn.value = on
        if (on && !_playing.value) playNextByDj()
    }

    fun refresh() {
        scope.launch {
            val found = withContext(Dispatchers.IO) { MusicLibrary.load(app) }
            _tracks.value = found
            dj = WorkoutDj(found.map { it.toCore() }, taste, settings)
        }
    }

    fun analyzeAll() {
        if (analysisJob?.isActive == true) return
        val pending = _tracks.value.filter { it.id.toString() !in _bpm.value }
        if (pending.isEmpty()) return
        analysisJob = scope.launch {
            pending.forEachIndexed { i, t ->
                _analysis.value = (i + 1) to pending.size
                val found = BpmAnalyzer.analyze(app, t) ?: 0.0
                _bpm.value = _bpm.value + (t.id.toString() to found)
                if (i % 5 == 4) saveCache()
                dj = WorkoutDj(_tracks.value.map { it.toCore() }, taste, settings)
            }
            saveCache()
            _analysis.value = null
            dj = WorkoutDj(_tracks.value.map { it.toCore() }, taste, settings)
        }
    }

    fun play(track: LocalTrack) {
        val c = controller ?: return
        val item = MediaItem.Builder()
            .setMediaId(track.id.toString())
            .setUri(track.uri)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(track.title).setArtist(track.artist).build())
            .build()
        c.setMediaItem(item)
        c.prepare()
        c.play()
        _nowPlaying.value = track
        startedAtMs = System.currentTimeMillis()
    }

    fun toggle() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else if (c.mediaItemCount > 0) c.play()
    }

    fun skip() {
        val current = _nowPlaying.value
        if (current != null && System.currentTimeMillis() - startedAtMs < 20_000) taste.record(current.toCore(), ListenEvent.SKIPPED_EARLY)
        if (_djOn.value) playNextByDj() else playNextInList()
    }

    fun like() {
        _nowPlaying.value?.let { taste.record(it.toCore(), ListenEvent.LIKED) }
        _djNote.value = ":heart: Anotado: te pondré más como esta"
    }

    private fun onTrackEnded() {
        _nowPlaying.value?.let { taste.record(it.toCore(), ListenEvent.PLAYED_FULL) }
        if (_djOn.value) playNextByDj() else playNextInList()
    }

    private fun playNextInList() {
        val list = _tracks.value
        if (list.isEmpty()) return
        val i = list.indexOfFirst { it.id == _nowPlaying.value?.id }
        play(list[(i + 1).mod(list.size)])
    }

    private fun playNextByDj() {
        val model = dj ?: return
        val all = _tracks.value
        val playlist = Playlist("all", "Mi música", PlaylistKind.GYM, all.map { it.id.toString() })
        val ctx = DjContext(_phase.value, if (settings.bpmSync) _targetBpm.value else null)
        val pick = model.next(playlist, ctx)
        if (pick == null) {
            _djNote.value = "El DJ descansa: música apagada en los descansos."
            controller?.pause()
            return
        }
        _djNote.value = pick.reason
        all.firstOrNull { it.id.toString() == pick.track.id }?.let { play(it) }
    }

    private fun LocalTrack.toCore(): Track {
        val b = _bpm.value[id.toString()]?.takeIf { it > 0 }
        return Track(id = id.toString(), title = title, artist = artist, bpm = b, energy = energyFor(b), durationMs = durationMs)
    }

    /** Without a loudness analysis, faster songs are treated as more energetic. */
    private fun energyFor(bpm: Double?): Double = if (bpm == null) 0.5 else ((bpm - 70.0) / 110.0).coerceIn(0.1, 1.0)

    private fun loadCache(): Map<String, Double> = runCatching {
        if (cacheFile.exists()) json.decodeFromString(serializer, cacheFile.readText()) else emptyMap()
    }.getOrDefault(emptyMap())

    private fun saveCache() {
        runCatching { cacheFile.writeText(json.encodeToString(serializer, _bpm.value)) }
    }
}
