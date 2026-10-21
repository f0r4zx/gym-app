package app.gymquest.core.exercise

import app.gymquest.core.pose.Geometry
import app.gymquest.core.pose.Joint
import app.gymquest.core.pose.PoseFrame
import app.gymquest.core.pose.Vec3
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.exp

enum class Side(
    val shoulder: Joint, val elbow: Joint, val wrist: Joint,
    val hip: Joint, val knee: Joint, val ankle: Joint,
) {
    LEFT(Joint.LEFT_SHOULDER, Joint.LEFT_ELBOW, Joint.LEFT_WRIST, Joint.LEFT_HIP, Joint.LEFT_KNEE, Joint.LEFT_ANKLE),
    RIGHT(Joint.RIGHT_SHOULDER, Joint.RIGHT_ELBOW, Joint.RIGHT_WRIST, Joint.RIGHT_HIP, Joint.RIGHT_KNEE, Joint.RIGHT_ANKLE),
}

/** Body measurements shared by the exercise counters. All lengths are in uniform image units. */
internal object PoseMath {

    fun mid(f: PoseFrame, a: Joint, b: Joint): Vec3? {
        val pa = f.posIfVisible(a)
        val pb = f.posIfVisible(b)
        return when {
            pa != null && pb != null -> Vec3.mid(pa, pb)
            // In a side view the far limb is often occluded; the near one is a fine stand-in.
            pa != null -> pa
            pb != null -> pb
            else -> null
        }
    }

    fun shoulderMid(f: PoseFrame) = mid(f, Joint.LEFT_SHOULDER, Joint.RIGHT_SHOULDER)
    fun hipMid(f: PoseFrame) = mid(f, Joint.LEFT_HIP, Joint.RIGHT_HIP)
    fun kneeMid(f: PoseFrame) = mid(f, Joint.LEFT_KNEE, Joint.RIGHT_KNEE)
    fun ankleMid(f: PoseFrame) = mid(f, Joint.LEFT_ANKLE, Joint.RIGHT_ANKLE)
    fun wristMid(f: PoseFrame) = mid(f, Joint.LEFT_WRIST, Joint.RIGHT_WRIST)

    /** Angle at [b] using only visible joints; null if one is missing. */
    fun angleAt(f: PoseFrame, a: Joint, b: Joint, c: Joint, use3d: Boolean = false): Double? {
        val pa = f.posIfVisible(a) ?: return null
        val pb = f.posIfVisible(b) ?: return null
        val pc = f.posIfVisible(c) ?: return null
        return Geometry.angle(pa, pb, pc, use3d)
    }

    /**
     * Joint angle averaged over the visible sides, weighted by tracking confidence. Returns null
     * only when neither side can be seen.
     */
    fun bilateralAngle(
        f: PoseFrame,
        a: (Side) -> Joint, b: (Side) -> Joint, c: (Side) -> Joint,
        use3d: Boolean = false,
    ): Double? {
        var sum = 0.0
        var weight = 0.0
        for (s in Side.entries) {
            val ang = angleAt(f, a(s), b(s), c(s), use3d) ?: continue
            val w = minOf(f.likelihood(a(s)), f.likelihood(b(s)), f.likelihood(c(s)))
            sum += ang * w
            weight += w
        }
        return if (weight > 0) sum / weight else null
    }

    fun elbowAngle(f: PoseFrame, side: Side, use3d: Boolean = false) =
        angleAt(f, side.shoulder, side.elbow, side.wrist, use3d)

    fun kneeAngle(f: PoseFrame, side: Side, use3d: Boolean = false) =
        angleAt(f, side.hip, side.knee, side.ankle, use3d)

    fun hipAngle(f: PoseFrame, side: Side, use3d: Boolean = false) =
        angleAt(f, side.shoulder, side.hip, side.knee, use3d)

    fun bilateralKnee(f: PoseFrame, use3d: Boolean = false) =
        bilateralAngle(f, { it.hip }, { it.knee }, { it.ankle }, use3d)

    fun bilateralElbow(f: PoseFrame, use3d: Boolean = false) =
        bilateralAngle(f, { it.shoulder }, { it.elbow }, { it.wrist }, use3d)

    fun bilateralHipAngle(f: PoseFrame) =
        bilateralAngle(f, { it.shoulder }, { it.hip }, { it.knee })

    /** Shoulder-midpoint to hip-midpoint distance. */
    fun torsoLength(f: PoseFrame): Double? {
        val s = shoulderMid(f) ?: return null
        val h = hipMid(f) ?: return null
        return Geometry.distance(s, h).takeIf { it > 1e-3 }
    }

    /** Knee-to-ankle distance (average of the visible sides). */
    fun shinLength(f: PoseFrame): Double? {
        val lengths = Side.entries.mapNotNull { side ->
            val k = f.posIfVisible(side.knee) ?: return@mapNotNull null
            val a = f.posIfVisible(side.ankle) ?: return@mapNotNull null
            Geometry.distance(k, a).takeIf { it > 1e-3 }
        }
        return if (lengths.isEmpty()) null else lengths.average()
    }

    /** Reference length for scale-free measurements; falls back to shoulder width without hips. */
    fun bodyScale(f: PoseFrame): Double? {
        torsoLength(f)?.let { return it }
        val l = f.posIfVisible(Joint.LEFT_SHOULDER) ?: return null
        val r = f.posIfVisible(Joint.RIGHT_SHOULDER) ?: return null
        return (Geometry.distance(l, r) * 1.1).takeIf { it > 1e-3 }
    }

    /** How far the torso is from upright, in degrees (0 = standing straight, 90 = lying flat). */
    fun torsoLean(f: PoseFrame): Double? {
        val s = shoulderMid(f) ?: return null
        val h = hipMid(f) ?: return null
        return Geometry.angleFromVertical(h, s)
    }

    /**
     * Estimated camera yaw relative to the body: 0° = facing the camera, 90° = side-on. Derived
     * from the apparent shoulder width compared with the torso length, so it works wherever the
     * phone is placed.
     */
    fun viewYaw(f: PoseFrame): Double? {
        val l = f.posIfVisible(Joint.LEFT_SHOULDER) ?: return null
        val r = f.posIfVisible(Joint.RIGHT_SHOULDER) ?: return null
        val torso = torsoLength(f) ?: return null
        val ratio = (Geometry.distance(l, r) / torso / FRONTAL_SHOULDER_TO_TORSO).coerceIn(0.0, 1.0)
        return acos(ratio) * 180.0 / Math.PI
    }

    /**
     * Hip sagging (+) or piking (-) relative to the shoulder–ankle line, as a fraction of the
     * torso length. Null unless the body is seen side-on and stretched out.
     */
    fun hipOffsetFromLine(f: PoseFrame): Double? {
        val s = shoulderMid(f) ?: return null
        val h = hipMid(f) ?: return null
        val a = ankleMid(f) ?: return null
        val torso = Geometry.distance(s, h).takeIf { it > 1e-3 } ?: return null
        val dx = a.x - s.x
        if (abs(dx) < 1.5 * torso) return null
        val t = (h.x - s.x) / dx
        val lineY = s.y + (a.y - s.y) * t
        return (h.y - lineY) / torso
    }

    private const val FRONTAL_SHOULDER_TO_TORSO = 0.9
}

/**
 * Tracks the "standing" value of a quantity that shrinks during the movement (hip height, body
 * extent). Jumps up immediately and, while the body is near the top of the movement, drifts toward
 * the current value with time constant [tauMs]. That absorbs stepping closer to or further from
 * the camera and rare upward noise spikes, without following the user down into the squat.
 */
internal class StandingReference(private val tauMs: Double = 3_000.0) {
    var value = Double.NaN
        private set
    private var lastMs = 0L

    fun update(current: Double, nowMs: Long): Double {
        val dt = (nowMs - lastMs).coerceIn(0L, 500L)
        lastMs = nowMs
        value = when {
            value.isNaN() || current > value -> current
            current >= value * NEAR_TOP -> value + (current - value) * (1.0 - exp(-dt / tauMs))
            else -> value
        }
        return value
    }

    fun reset() {
        value = Double.NaN
        lastMs = 0L
    }

    private companion object {
        const val NEAR_TOP = 0.88
    }
}
