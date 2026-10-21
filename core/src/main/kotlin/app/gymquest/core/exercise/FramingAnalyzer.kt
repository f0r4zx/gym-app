package app.gymquest.core.exercise

import app.gymquest.core.pose.Joint
import app.gymquest.core.pose.PoseFrame
import kotlin.math.hypot

enum class FramingIssue(val message: String) {
    NO_PERSON("No te veo. Colócate frente a la cámara."),
    TOO_CLOSE("Aléjate un poco del celular."),
    TOO_FAR("Acércate un poco al celular."),
    CUT_OFF_TOP("Se te corta la cabeza o las manos: baja o inclina el celular."),
    CUT_OFF_BOTTOM("Se te cortan los pies: aleja el celular o súbelo un poco."),
    CUT_OFF_LEFT("Te sales por la izquierda: muévete a la derecha."),
    CUT_OFF_RIGHT("Te sales por la derecha: muévete a la izquierda."),
    NEED_SIDE_VIEW("Gírate de lado respecto al celular (o mueve el celular a tu costado)."),
    NEED_FRONT_VIEW("Colócate de frente al celular."),
    MISSING_JOINTS("Que se vea todo tu cuerpo."),
}

enum class FramingStatus { NO_PERSON, ADJUST, READY }

data class FramingReport(
    val status: FramingStatus,
    val issues: List<FramingIssue>,
    /** Largest dimension of the visible body as a fraction of the frame, if a person was found. */
    val bodyFraction: Double? = null,
    /** 0 = facing the camera, 90 = side-on. */
    val yawDegrees: Double? = null,
) {
    val ready: Boolean get() = status == FramingStatus.READY

    /** The single most important thing to tell the user. */
    val message: String
        get() = when (status) {
            FramingStatus.READY -> "Listo, te veo perfecto."
            else -> issues.firstOrNull()?.message ?: FramingIssue.NO_PERSON.message
        }
}

/**
 * Checks that a camera placement works for an exercise: the whole body in view, a sensible
 * distance, and a viewing angle the exercise can be measured from.
 */
class FramingAnalyzer(private val type: ExerciseType) {

    fun analyze(frame: PoseFrame): FramingReport {
        val found = Joint.BODY.count { frame.isVisible(it) }
        if (found < 5) return FramingReport(FramingStatus.NO_PERSON, listOf(FramingIssue.NO_PERSON))

        val issues = mutableListOf<FramingIssue>()

        // Where do the joints we need sit relative to the frame edges?
        var missing = false
        var cutTop = false
        var cutBottom = false
        var cutLeft = false
        var cutRight = false
        for (j in type.requiredJoints) {
            val l = frame.landmark(j)
            if (l == null) {
                missing = true
                continue
            }
            if (l.likelihood >= PoseFrame.MIN_LIKELIHOOD && l.x in EDGE..(1 - EDGE) && l.y in EDGE..(1 - EDGE)) continue
            missing = true
            when {
                l.y < EDGE -> cutTop = true
                l.y > 1 - EDGE -> cutBottom = true
                l.x < EDGE -> cutLeft = true
                l.x > 1 - EDGE -> cutRight = true
            }
        }

        // Extent of the visible body.
        val visible = Joint.BODY.mapNotNull { j -> frame.landmark(j)?.takeIf { it.likelihood >= PoseFrame.MIN_LIKELIHOOD } }
        val spanY = (visible.maxOf { it.y } - visible.minOf { it.y })
        val spanX = (visible.maxOf { it.x } - visible.minOf { it.x })
        val fraction = maxOf(spanY, spanX)
        // Real size of the body in the image, independent of the phone's orientation.
        val bodyLength = hypot(spanX * frame.aspect, spanY)

        if (cutTop) issues += FramingIssue.CUT_OFF_TOP
        if (cutBottom) issues += FramingIssue.CUT_OFF_BOTTOM
        if (cutLeft) issues += FramingIssue.CUT_OFF_LEFT
        if (cutRight) issues += FramingIssue.CUT_OFF_RIGHT
        if (missing && issues.isEmpty()) issues += FramingIssue.MISSING_JOINTS
        if (!missing) {
            if (fraction > TOO_CLOSE) issues += FramingIssue.TOO_CLOSE
            if (bodyLength < TOO_FAR) issues += FramingIssue.TOO_FAR
        }

        val yaw = PoseMath.viewYaw(frame)
        if (yaw != null) {
            when (type.view) {
                ViewHint.SIDE -> if (yaw < SIDE_MIN_YAW) issues += FramingIssue.NEED_SIDE_VIEW
                ViewHint.FRONT -> if (yaw > FRONT_MAX_YAW) issues += FramingIssue.NEED_FRONT_VIEW
                ViewHint.ANY -> Unit
            }
        }

        val status = if (issues.isEmpty()) FramingStatus.READY else FramingStatus.ADJUST
        return FramingReport(status, issues, fraction, yaw)
    }

    private companion object {
        const val EDGE = 0.01
        const val TOO_CLOSE = 0.97
        const val TOO_FAR = 0.30
        const val SIDE_MIN_YAW = 40.0
        const val FRONT_MAX_YAW = 50.0
    }
}
