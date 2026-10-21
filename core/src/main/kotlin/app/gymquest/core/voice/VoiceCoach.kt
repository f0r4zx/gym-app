package app.gymquest.core.voice

import app.gymquest.core.exercise.CounterUpdate
import app.gymquest.core.exercise.ExerciseType
import app.gymquest.core.exercise.FormIssue
import app.gymquest.core.exercise.FramingIssue
import app.gymquest.core.exercise.RepEvent
import app.gymquest.core.exercise.SessionState
import app.gymquest.core.exercise.SessionUpdate

enum class Verbosity {
    /** No speech at all. */
    SILENT,

    /** Only milestones, the last reps and serious form problems. */
    MINIMAL,

    /** Count every rep (every 5 for fast exercises) plus corrections and a little praise. */
    NORMAL,

    /** Everything above plus encouragement. */
    CHATTY,
}

enum class CuePriority { LOW, NORMAL, HIGH }

/**
 * One short thing to say. The audio layer speaks them in order and lowers the music while it talks
 * ([duckMusic]); [key] lets it drop an older, now-stale cue of the same kind.
 */
data class SpokenCue(val text: String, val priority: CuePriority, val key: String, val duckMusic: Boolean = true)

data class VoiceConfig(
    val verbosity: Verbosity = Verbosity.NORMAL,
    /** Planned reps for this set, enables "tres más" and "serie completa". */
    val targetReps: Int? = null,
    val corrections: Boolean = true,
    val praise: Boolean = true,
)

internal object Phrases {
    val praise = listOf("¡Excelente!", "¡Perfecto!", "¡Muy bien!", "¡Así se hace!", "¡Buena forma!", "¡Fuerte!")
    val encouragement = listOf("¡Vamos, tú puedes!", "¡Sigue así!", "¡No pares!", "¡Con ganas!")

    fun correction(issue: FormIssue, type: ExerciseType): String = when (issue) {
        FormIssue.NOT_DEEP_ENOUGH -> when (type) {
            ExerciseType.PULL_UP -> "Sube la barbilla por encima de la barra"
            ExerciseType.PUSH_UP -> "Baja el pecho más"
            ExerciseType.BICEP_CURL -> "Sube la mancuerna más"
            ExerciseType.LATERAL_RAISE -> "Sube los brazos hasta el hombro"
            ExerciseType.SIT_UP -> "Sube más"
            else -> "Baja más"
        }
        FormIssue.NOT_LOCKED_OUT -> when (type) {
            ExerciseType.GLUTE_BRIDGE -> "Sube la cadera del todo"
            else -> "Estira por completo"
        }
        FormIssue.TOO_FAST -> "Más despacio, controla el movimiento"
        FormIssue.HIPS_SAGGING -> "Sube la cadera, cuerpo recto"
        FormIssue.HIPS_PIKED -> "Baja la cadera, cuerpo recto"
        FormIssue.KNEES_IN -> "Rodillas hacia afuera"
        FormIssue.LEANING_FORWARD -> "Espalda recta, pecho arriba"
        FormIssue.ELBOW_SWING -> "Pega el codo al cuerpo"
        FormIssue.SHORT_STRIDE -> "Da un paso más largo"
    }

    /** Which problem to mention first when several happen at once. */
    val importance = listOf(
        FormIssue.LEANING_FORWARD, FormIssue.KNEES_IN, FormIssue.HIPS_SAGGING, FormIssue.HIPS_PIKED, FormIssue.ELBOW_SWING,
        FormIssue.NOT_DEEP_ENOUGH, FormIssue.NOT_LOCKED_OUT, FormIssue.SHORT_STRIDE, FormIssue.TOO_FAST,
    )

    fun framing(issue: FramingIssue): String = issue.message
}

/**
 * The spoken personal trainer. It turns what the camera sees into short, well-timed cues — counts,
 * "baja más", "espalda recta", "¡excelente!" — and holds back so it never chatters: each correction
 * has a cooldown, only one is given at a time, and praise is saved for streaks of good reps.
 */
class VoiceCoach(
    private val type: ExerciseType,
    private val config: VoiceConfig = VoiceConfig(),
) {
    private var goodStreak = 0
    private var phraseIndex = 0
    private var lastPraiseMs = Long.MIN_VALUE / 2
    private var lastCorrectionMs = Long.MIN_VALUE / 2
    private val lastIssueMs = HashMap<FormIssue, Long>()
    private val issueSinceMs = HashMap<FormIssue, Long>()
    private var lastFramingMs = Long.MIN_VALUE / 2
    private var lastFramingIssue: FramingIssue? = null
    private var lastCountdown: Int? = null
    private var lastState: SessionState? = null
    private var announcedHalf = false
    private var lastEncouragementMs = Long.MIN_VALUE / 2

    /** Rapid exercises would be exhausting to hear one by one. */
    private val countEvery: Int = when {
        config.verbosity == Verbosity.MINIMAL -> 5
        type.secondsPerRep <= 0.7 -> 25
        type.secondsPerRep <= 1.3 -> 10
        else -> 1
    }

    fun onSession(update: SessionUpdate, nowMs: Long): List<SpokenCue> {
        if (config.verbosity == Verbosity.SILENT) return emptyList()
        val cues = mutableListOf<SpokenCue>()

        if (update.state != lastState) {
            when {
                update.state == SessionState.PAUSED -> cues += cue("Te perdí. Vuelve al cuadro.", CuePriority.HIGH, "paused")
                lastState == SessionState.PAUSED && update.state == SessionState.ACTIVE -> cues += cue("Listo, continuamos.", CuePriority.NORMAL, "resumed")
                update.state == SessionState.ACTIVE && lastState == SessionState.COUNTDOWN -> cues += cue("¡Ya!", CuePriority.HIGH, "go")
            }
            lastState = update.state
        }

        when (update.state) {
            SessionState.SETUP -> framingCue(update, nowMs)?.let { cues += it }
            SessionState.COUNTDOWN -> {
                val n = update.countdown
                if (n != null && n != lastCountdown) {
                    lastCountdown = n
                    cues += cue(n.toString(), CuePriority.HIGH, "countdown")
                }
            }
            SessionState.ACTIVE -> update.counter?.let { cues += onCounter(it, nowMs) }
            SessionState.PAUSED -> Unit
        }
        return cues
    }

    /** Feed the counter's update directly when not using [WorkoutSession] (e.g. in a unit test or demo). */
    fun onCounter(update: CounterUpdate, nowMs: Long): List<SpokenCue> {
        if (config.verbosity == Verbosity.SILENT) return emptyList()
        val cues = mutableListOf<SpokenCue>()

        update.rep?.let { rep -> cues += onRep(rep, nowMs) }
        update.partial?.let { partial ->
            correction(partial.issue, nowMs, force = false)?.let { cues += it }
            goodStreak = 0
        }
        cues += liveIssueCues(update, nowMs)
        return cues
    }

    private fun onRep(rep: RepEvent, nowMs: Long): List<SpokenCue> {
        val cues = mutableListOf<SpokenCue>()
        val count = rep.count

        if (rep.isHoldTick) {
            // Every second is a "tick", so milestones every 10 s and a compliment only every 20 s.
            if (count % 10 == 0) cues += cue("$count segundos", CuePriority.NORMAL, "hold")
            val issue = Phrases.importance.firstOrNull { it in rep.issues }
            if (issue != null) correction(issue, nowMs, force = false)?.let { cues += it }
            else if (count % 20 == 0 && rep.quality >= 0.85 && config.praise && config.verbosity != Verbosity.MINIMAL) {
                cues += cue(Phrases.praise[phraseIndex++ % Phrases.praise.size], CuePriority.LOW, "praise")
            }
            return cues
        } else {
            val target = config.targetReps
            val remaining = target?.minus(count)
            val speakCount = count % countEvery == 0 || (remaining != null && remaining in 0..3)
            if (speakCount) cues += cue(count.toString(), CuePriority.NORMAL, "count")

            if (target != null) {
                if (!announcedHalf && count == target / 2 && target >= 10) {
                    announcedHalf = true
                    cues += cue("Vas por la mitad", CuePriority.NORMAL, "half")
                }
                when (remaining) {
                    3 -> cues += cue("Tres más", CuePriority.NORMAL, "left")
                    2 -> cues += cue("Dos más", CuePriority.NORMAL, "left")
                    1 -> cues += cue("Una más, ¡última!", CuePriority.HIGH, "left")
                    0 -> cues += cue("¡Serie completa!", CuePriority.HIGH, "done")
                }
            }
        }

        // Corrections: the single most important problem, rate-limited.
        val issue = Phrases.importance.firstOrNull { it in rep.issues }
        var corrected = false
        if (issue != null) {
            goodStreak = 0
            correction(issue, nowMs, force = false)?.let { cues += it; corrected = true }
        } else if (rep.quality >= 0.85) {
            goodStreak++
        } else {
            goodStreak = 0
        }

        if (!corrected && config.praise && goodStreak >= 3 && config.verbosity != Verbosity.MINIMAL && nowMs - lastPraiseMs > PRAISE_COOLDOWN_MS) {
            lastPraiseMs = nowMs
            goodStreak = 0
            cues += cue(Phrases.praise[phraseIndex++ % Phrases.praise.size], CuePriority.LOW, "praise")
        } else if (!corrected && config.verbosity == Verbosity.CHATTY && nowMs - lastEncouragementMs > ENCOURAGE_COOLDOWN_MS && count % 7 == 0) {
            lastEncouragementMs = nowMs
            cues += cue(Phrases.encouragement[phraseIndex++ % Phrases.encouragement.size], CuePriority.LOW, "encourage")
        }
        return cues
    }

    /** A posture problem that persists (a sagging plank) is worth saying even without a rep to attach it to. */
    private fun liveIssueCues(update: CounterUpdate, nowMs: Long): List<SpokenCue> {
        if (!config.corrections) return emptyList()
        val current = update.liveIssues
        issueSinceMs.keys.retainAll(current)
        for (issue in current) issueSinceMs.putIfAbsent(issue, nowMs)
        val persistent = Phrases.importance.firstOrNull { it in current && nowMs - (issueSinceMs[it] ?: nowMs) >= PERSISTENT_ISSUE_MS && it != FormIssue.TOO_FAST }
        return persistent?.let { correction(it, nowMs, force = false) }?.let { listOf(it) } ?: emptyList()
    }

    private fun correction(issue: FormIssue, nowMs: Long, force: Boolean): SpokenCue? {
        if (!config.corrections) return null
        if (config.verbosity == Verbosity.MINIMAL && issue == FormIssue.TOO_FAST) return null
        if (!force) {
            if (nowMs - lastCorrectionMs < GLOBAL_CORRECTION_GAP_MS) return null
            if (nowMs - (lastIssueMs[issue] ?: Long.MIN_VALUE / 2) < ISSUE_COOLDOWN_MS) return null
        }
        lastCorrectionMs = nowMs
        lastIssueMs[issue] = nowMs
        return cue(Phrases.correction(issue, type), CuePriority.HIGH, "correction")
    }

    private fun framingCue(update: SessionUpdate, nowMs: Long): SpokenCue? {
        val issue = update.framing.issues.firstOrNull() ?: return null
        val sameAsLast = issue == lastFramingIssue
        if (nowMs - lastFramingMs < (if (sameAsLast) FRAMING_REPEAT_MS else FRAMING_GAP_MS)) return null
        lastFramingMs = nowMs
        lastFramingIssue = issue
        return cue(Phrases.framing(issue), CuePriority.NORMAL, "framing")
    }

    /** Spoken during rest between sets: 30 s, 10 s, "prepárate" and a final countdown. */
    fun onRestTick(secondsLeft: Int, nextSetLabel: String? = null): List<SpokenCue> {
        if (config.verbosity == Verbosity.SILENT) return emptyList()
        return when (secondsLeft) {
            30, 60, 90 -> listOf(cue("Descanso: $secondsLeft segundos", CuePriority.NORMAL, "rest"))
            10 -> listOf(cue("Diez segundos${nextSetLabel?.let { ". Siguiente: $it" } ?: ""}", CuePriority.NORMAL, "rest"))
            5 -> listOf(cue("Prepárate", CuePriority.NORMAL, "rest"))
            3, 2, 1 -> listOf(cue(secondsLeft.toString(), CuePriority.HIGH, "rest"))
            0 -> listOf(cue("¡Vamos!", CuePriority.HIGH, "rest"))
            else -> emptyList()
        }
    }

    fun onFinish(count: Int, averageQuality: Double): List<SpokenCue> {
        if (config.verbosity == Verbosity.SILENT) return emptyList()
        val pct = (averageQuality * 100).toInt()
        val verdict = when {
            averageQuality >= 0.9 -> "Técnica excelente."
            averageQuality >= 0.75 -> "Buena técnica."
            averageQuality >= 0.55 -> "Cuida la técnica la próxima serie."
            else -> "Baja el ritmo y enfócate en la forma."
        }
        val unit = if (type.mode == app.gymquest.core.exercise.CountingMode.HOLD) "segundos" else "repeticiones"
        return listOf(cue("Serie terminada: $count $unit. Calidad $pct por ciento. $verdict", CuePriority.HIGH, "finish"))
    }

    private fun cue(text: String, priority: CuePriority, key: String) = SpokenCue(text, priority, key)

    private companion object {
        const val PRAISE_COOLDOWN_MS = 8_000L
        const val ENCOURAGE_COOLDOWN_MS = 15_000L
        const val ISSUE_COOLDOWN_MS = 8_000L
        const val GLOBAL_CORRECTION_GAP_MS = 3_000L
        const val PERSISTENT_ISSUE_MS = 1_500L
        const val FRAMING_GAP_MS = 3_000L
        const val FRAMING_REPEAT_MS = 8_000L
    }
}
