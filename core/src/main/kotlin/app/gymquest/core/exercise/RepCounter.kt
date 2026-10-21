package app.gymquest.core.exercise

import app.gymquest.core.pose.PoseFrame

/** Things the camera can tell you are wrong with your form. */
enum class FormIssue(val message: String) {
    NOT_DEEP_ENOUGH("Baja un poco más"),
    NOT_LOCKED_OUT("Estira por completo"),
    TOO_FAST("Más control, no te apures"),
    HIPS_SAGGING("Sube la cadera: cuerpo en línea recta"),
    HIPS_PIKED("Baja la cadera: cuerpo en línea recta"),
    KNEES_IN("No metas las rodillas hacia adentro"),
    LEANING_FORWARD("Pecho arriba, no te inclines tanto"),
    ELBOW_SWING("Pega el codo al cuerpo"),
    SHORT_STRIDE("Da un paso más largo"),
}

/** A completed repetition (or one second of a hold). */
data class RepEvent(
    /** Running total after this event. */
    val count: Int,
    val timestampMs: Long,
    /** 0..1. Combines depth, tempo and posture; used as the damage multiplier against bosses. */
    val quality: Double,
    val durationMs: Long,
    /** Deepest/highest point reached, 0..1. */
    val depth: Double,
    val issues: Set<FormIssue>,
    val isHoldTick: Boolean = false,
)

/** A movement that started but did not reach the required range, so it was not counted. */
data class PartialRep(val timestampMs: Long, val depth: Double, val issue: FormIssue)

enum class Phase { LOST, READY, MOVING, AT_PEAK, RETURNING }

/** What the counter learned from one frame. */
data class CounterUpdate(
    val rep: RepEvent? = null,
    val partial: PartialRep? = null,
    /** Posture problems detected right now (shown live in the UI). */
    val liveIssues: Set<FormIssue> = emptySet(),
    /** 0..1 position within the movement, null when the body cannot be tracked. */
    val progress: Double? = null,
    val phase: Phase = Phase.LOST,
)

interface RepCounter {
    val type: ExerciseType

    /** Whole reps (or seconds for holds) so far. */
    val count: Int

    fun update(frame: PoseFrame): CounterUpdate

    fun reset()
}

/** How demanding the counter is about range of motion. */
enum class Strictness(val peakShift: Double) {
    RELAXED(-0.10),
    NORMAL(0.0),
    STRICT(0.08),
}
