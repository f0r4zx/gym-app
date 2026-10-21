package app.gymquest.camera

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import app.gymquest.core.voice.CuePriority
import app.gymquest.core.voice.SpokenCue
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Speaks the coach's cues through the phone's text-to-speech (works with no internet when the
 * Spanish voice is installed). Uses the "navigation guidance" audio stream and asks other apps to
 * lower their volume while it talks, so music from any app keeps playing underneath.
 */
class Speaker(context: Context) : TextToSpeech.OnInitListener {
    private val appContext = context.applicationContext
    private val audio = appContext.getSystemService(AudioManager::class.java)
    private var tts: TextToSpeech? = TextToSpeech(appContext, this)
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private var focus: AudioFocusRequest? = null
    private val queued = AtomicInteger(0)
    private val early = ArrayList<SpokenCue>()
    private var ready = false
    private var counter = 0

    @Volatile var enabled = true

    override fun onInit(status: Int) {
        val t = tts ?: return
        if (status != TextToSpeech.SUCCESS) return
        val locale = listOf(Locale("es", "MX"), Locale("es", "US"), Locale("es", "ES"), Locale("es"))
            .firstOrNull { t.isLanguageAvailable(it) >= TextToSpeech.LANG_AVAILABLE } ?: Locale.getDefault()
        t.language = locale
        t.setAudioAttributes(attrs)
        t.setSpeechRate(1.05f)
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) = finished()
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = finished()
        })
        ready = true
        early.forEach { speak(it) }
        early.clear()
    }

    fun say(cues: List<SpokenCue>) = cues.forEach { say(it) }

    fun say(cue: SpokenCue) {
        if (!enabled) return
        if (!ready) {
            if (early.size < 3) early += cue
            return
        }
        speak(cue)
    }

    private fun speak(cue: SpokenCue) {
        val t = tts ?: return
        val backlog = queued.get()
        // Never let speech fall behind the workout: drop low-value cues when busy.
        if (cue.priority == CuePriority.LOW && backlog >= 1) return
        if (cue.priority == CuePriority.NORMAL && backlog >= 3) return
        if (backlog == 0 && cue.duckMusic) requestFocus()
        val mode = if (cue.priority == CuePriority.HIGH) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        if (mode == TextToSpeech.QUEUE_FLUSH) queued.set(0)
        queued.incrementAndGet()
        t.speak(cue.text, mode, null, "gq${counter++}")
    }

    private fun finished() {
        if (queued.decrementAndGet() <= 0) {
            queued.set(0)
            abandonFocus()
        }
    }

    private fun requestFocus() {
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attrs)
            .setWillPauseWhenDucked(false)
            .build()
        focus = req
        audio.requestAudioFocus(req)
    }

    private fun abandonFocus() {
        focus?.let { audio.abandonAudioFocusRequest(it) }
        focus = null
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        abandonFocus()
    }
}
