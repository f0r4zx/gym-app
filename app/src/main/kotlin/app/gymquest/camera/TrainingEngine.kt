package app.gymquest.camera

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.gymquest.core.avatar.BattleModifiers
import app.gymquest.core.exercise.ExerciseType
import app.gymquest.core.exercise.SessionState
import app.gymquest.core.exercise.Strictness
import app.gymquest.core.exercise.WorkoutSession
import app.gymquest.core.game.BattleEvent
import app.gymquest.core.game.BattleState
import app.gymquest.core.game.Boss
import app.gymquest.core.game.BossBattle
import app.gymquest.core.game.DefeatReason
import app.gymquest.core.pose.PoseFrame
import app.gymquest.core.voice.CuePriority
import app.gymquest.core.voice.SpokenCue
import app.gymquest.core.voice.VoiceCoach
import app.gymquest.core.voice.VoiceConfig
import app.gymquest.core.voice.Verbosity

/** What the on-screen display shows. Changes only when something visible changes, to keep drawing cheap. */
data class Hud(
    val state: SessionState = SessionState.SETUP,
    val message: String = "",
    val countdown: Int? = null,
    val reps: Int = 0,
    val combo: Int = 0,
    val lastQuality: Double = 0.0,
    val liveIssue: String? = null,
    val bossHp: Double? = null,
    val playerHp: Double? = null,
    val timeLeftSec: Int? = null,
    /** A short popup such as "-12" or "¡CRÍTICO! -18"; [popupId] changes so the UI can replay its animation. */
    val popup: String? = null,
    val popupId: Int = 0,
    val outcome: Outcome? = null,
)

enum class Outcome { VICTORY, DEFEAT_HP, DEFEAT_TIME, FINISHED }

data class TrainingSummary(
    val type: ExerciseType,
    val reps: Int,
    val avgQuality: Double,
    val durationMs: Long,
    val bossId: String?,
    val victory: Boolean?,
    val flawless: Boolean,
    val bestCombo: Int,
    val xpFromBoss: Int,
    val lootId: String?,
)

/**
 * Glue between the camera and the tested game logic: it feeds each pose to the workout session,
 * the boss fight and the voice coach, and publishes a small [Hud] for the screen.
 */
class TrainingEngine(
    val type: ExerciseType,
    val boss: Boss?,
    private val speaker: Speaker?,
    strictness: Strictness,
    verbosity: Verbosity,
    targetReps: Int?,
    private val modifiers: BattleModifiers,
    /** For timed challenges: the set ends by itself this long after counting starts. */
    private val timeLimitMs: Long? = null,
) {
    private val session = WorkoutSession(type, strictness)
    private val voice = VoiceCoach(type, VoiceConfig(verbosity = verbosity, targetReps = targetReps))
    private var battle: BossBattle? = null
    private var done = false
    private var popupCounter = 0
    private var lastFrameMs = 0L
    private var bossXp = 0
    private var lootId: String? = null
    private var flawless = false
    private var activeStartMs = -1L

    var hud by mutableStateOf(Hud(bossHp = boss?.let { 1.0 }, playerHp = boss?.let { 1.0 }))
        private set

    /** The latest pose, for drawing the skeleton. Read only while drawing so it never triggers a recomposition. */
    var pose by mutableStateOf<PoseFrame?>(null)
        private set

    val isFinished: Boolean get() = done

    fun onNoPerson(nowMs: Long) {
        pose = null
        if (done) return
        // Time keeps running against the boss even if the camera loses you.
        if (session.state == SessionState.ACTIVE) advanceBattle(nowMs)
        if (session.state == SessionState.SETUP) hud = hud.copy(message = "No te veo. Colócate frente a la cámara.")
    }

    fun onFrame(frame: PoseFrame, rollDegrees: Double) {
        if (done) return
        pose = frame
        lastFrameMs = frame.timestampMs
        val update = session.onFrame(frame, rollDegrees)
        say(voice.onSession(update, frame.timestampMs))

        if (update.state == SessionState.ACTIVE && battle == null && boss != null) {
            battle = BossBattle(boss, frame.timestampMs, modifiers)
        }
        var popup: String? = null
        val b = battle
        if (b != null && update.state == SessionState.ACTIVE) {
            update.counter?.partial?.let { b.onPartial(it) }
            update.rep?.let { rep -> popup = handle(b.onRep(rep, type, frame.timestampMs)) ?: popup }
            handle(b.tick(frame.timestampMs))?.let { popup = it }
        }

        if (update.state == SessionState.ACTIVE && activeStartMs < 0) activeStartMs = frame.timestampMs
        val elapsed = if (activeStartMs >= 0) frame.timestampMs - activeStartMs else 0L
        val timeUp = timeLimitMs != null && activeStartMs >= 0 && elapsed >= timeLimitMs
        val outcome = outcomeNow() ?: if (timeUp) Outcome.FINISHED else null
        val challengeLeft = timeLimitMs?.let { ((it - elapsed).coerceAtLeast(0) / 1000).toInt() }
        val message = when (update.state) {
            SessionState.SETUP, SessionState.PAUSED -> update.framing.message
            SessionState.COUNTDOWN -> "Prepárate…"
            SessionState.ACTIVE -> ""
        }
        val rep = update.rep
        hud = hud.copy(
            state = update.state,
            message = message,
            countdown = update.countdown,
            reps = session.count,
            combo = update.combo,
            lastQuality = rep?.quality ?: hud.lastQuality,
            liveIssue = update.counter?.liveIssues?.firstOrNull()?.message,
            bossHp = b?.bossHpFraction ?: hud.bossHp,
            playerHp = b?.playerHpFraction ?: hud.playerHp,
            timeLeftSec = challengeLeft ?: b?.let { (it.timeLeftMs / 1000).toInt() },
            popup = popup ?: hud.popup,
            popupId = if (popup != null) ++popupCounter else hud.popupId,
            outcome = outcome,
        )
        if (outcome != null) done = true
    }

    private fun advanceBattle(nowMs: Long) {
        val b = battle ?: return
        val popup = handle(b.tick(nowMs))
        val outcome = outcomeNow()
        hud = hud.copy(
            bossHp = b.bossHpFraction, playerHp = b.playerHpFraction, timeLeftSec = (b.timeLeftMs / 1000).toInt(),
            popup = popup ?: hud.popup, popupId = if (popup != null) ++popupCounter else hud.popupId, outcome = outcome,
        )
        if (outcome != null) done = true
    }

    private var defeat: DefeatReason? = null

    private fun outcomeNow(): Outcome? = when {
        battle?.state == BattleState.VICTORY -> Outcome.VICTORY
        battle?.state == BattleState.DEFEAT -> if (defeat == DefeatReason.TIME_UP) Outcome.DEFEAT_TIME else Outcome.DEFEAT_HP
        else -> null
    }

    /** Reacts to what happened in the fight; returns text for the floating popup. */
    private fun handle(events: List<BattleEvent>): String? {
        var popup: String? = null
        for (e in events) {
            when (e) {
                is BattleEvent.Hit -> popup = if (e.critical) "¡CRÍTICO! -${e.damage}" else "-${e.damage}"
                is BattleEvent.BossAttack -> {
                    popup = "¡Te golpea! -${e.damage}"
                    say(listOf(SpokenCue("¡Cuidado, no pares!", CuePriority.HIGH, "boss_attack")))
                }
                BattleEvent.Enraged -> {
                    popup = "¡Enfurecido!"
                    say(listOf(SpokenCue("¡El jefe se enfureció!", CuePriority.HIGH, "enrage")))
                }
                is BattleEvent.Victory -> {
                    bossXp = e.xp
                    lootId = e.lootId
                    flawless = e.flawless
                    say(listOf(SpokenCue("¡Victoria! Derrotaste a ${boss?.name ?: "el jefe"}.", CuePriority.HIGH, "victory")))
                }
                is BattleEvent.Defeat -> {
                    defeat = e.reason
                    say(listOf(SpokenCue(if (e.reason == DefeatReason.TIME_UP) "Se acabó el tiempo." else "Has caído. Inténtalo de nuevo.", CuePriority.HIGH, "defeat")))
                }
                BattleEvent.Ignored -> Unit
            }
        }
        return popup
    }

    private fun say(cues: List<SpokenCue>) {
        if (cues.isNotEmpty()) speaker?.say(cues)
    }

    fun finish(): TrainingSummary {
        done = true
        val reps = session.count
        say(voice.onFinish(reps, session.averageQuality()))
        val b = battle
        return TrainingSummary(
            type = type,
            reps = reps,
            avgQuality = session.averageQuality(),
            durationMs = session.activeMs,
            bossId = if (b != null) boss?.id else null,
            victory = b?.let { it.state == BattleState.VICTORY },
            flawless = flawless,
            bestCombo = maxOf(session.bestCombo, b?.bestCombo ?: 0),
            xpFromBoss = bossXp,
            lootId = lootId,
        )
    }
}
