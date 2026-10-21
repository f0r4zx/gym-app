package app.gymquest.core.sim

import app.gymquest.core.pose.Joint
import app.gymquest.core.pose.Vec3
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A joint map in world space. Units are "person heights" (a standing person is ~1.0 tall).
 * Axes: +X = the person's left, +Y = up, +Z = the direction they face when standing.
 */
typealias World = Map<Joint, Vec3>

/** Average human proportions (Drillis & Contini), as fractions of body height. */
object Body {
    const val THIGH = 0.245
    const val SHIN = 0.246
    const val TORSO = 0.288
    const val UPPER_ARM = 0.186
    const val FOREARM = 0.146
    const val SHOULDER_HALF = 0.1295
    const val HIP_HALF = 0.0955
    const val NECK = 0.11
    const val ANKLE_HEIGHT = 0.039
    const val FOOT = 0.12
    const val LEG = THIGH + SHIN
    const val ARM = UPPER_ARM + FOREARM

    /** Hip height above the floor when standing straight. */
    const val STANDING_HIP = LEG + ANKLE_HEIGHT
}

/** Unit vector in the sagittal plane: 0° up, 90° forward (+Z), 180° down, -90° backward. */
fun sag(deg: Double): Vec3 {
    val r = Math.toRadians(deg)
    return Vec3(0.0, cos(r), sin(r))
}

/**
 * Unit vector in the frontal plane, pointing away from the body midline on the given side:
 * 0° up, 90° straight out sideways, 180° down. [side] is +1 for the left and -1 for the right.
 */
fun lat(deg: Double, side: Int): Vec3 {
    val r = Math.toRadians(deg)
    return Vec3(side * sin(r), cos(r), 0.0)
}

/** Which side of the body a limb belongs to. */
enum class LimbSide(val sign: Int) { LEFT(1), RIGHT(-1) }

/** Direction of each limb segment, pointing away from the trunk. */
class LimbDirs(val thigh: Vec3, val shin: Vec3, val upperArm: Vec3, val foreArm: Vec3)

/** Which way a knee/elbow points when a two-link chain is folded. */
enum class Bend { FORWARD, BACKWARD, UP, DOWN }

/**
 * Two-link planar IK in the sagittal (Z, Y) plane. Returns the middle joint (knee/elbow) between
 * [root] and [target]; [bend] picks which of the two mirror solutions is used.
 */
fun ik2(root: Vec3, target: Vec3, l1: Double, l2: Double, bend: Bend = Bend.FORWARD): Vec3 {
    val dz = target.z - root.z
    val dy = target.y - root.y
    val dist = sqrt(dz * dz + dy * dy).coerceIn(1e-6, (l1 + l2) * 0.9999)
    val cosA = ((l1 * l1 + dist * dist - l2 * l2) / (2 * l1 * dist)).coerceIn(-1.0, 1.0)
    val a = acos(cosA)
    val base = kotlin.math.atan2(dz, dy) // angle of root->target measured from +Y toward +Z
    val one = Vec3(root.x, root.y + l1 * cos(base + a), root.z + l1 * sin(base + a))
    val two = Vec3(root.x, root.y + l1 * cos(base - a), root.z + l1 * sin(base - a))
    return when (bend) {
        Bend.FORWARD -> if (one.z >= two.z) one else two
        Bend.BACKWARD -> if (one.z <= two.z) one else two
        Bend.UP -> if (one.y >= two.y) one else two
        Bend.DOWN -> if (one.y <= two.y) one else two
    }
}

/** Builds a full [World] from the trunk placement and limb directions. */
object SkeletonBuilder {
    fun build(hipCenter: Vec3, torsoDir: Vec3, left: LimbDirs, right: LimbDirs): MutableMap<Joint, Vec3> {
        val joints = mutableMapOf<Joint, Vec3>()
        val shoulderCenter = hipCenter + torsoDir * Body.TORSO
        for (side in LimbSide.entries) {
            val d = if (side == LimbSide.LEFT) left else right
            val hip = hipCenter + Vec3(side.sign * Body.HIP_HALF, 0.0, 0.0)
            val shoulder = shoulderCenter + Vec3(side.sign * Body.SHOULDER_HALF, 0.0, 0.0)
            val knee = hip + d.thigh * Body.THIGH
            val ankle = knee + d.shin * Body.SHIN
            val elbow = shoulder + d.upperArm * Body.UPPER_ARM
            val wrist = elbow + d.foreArm * Body.FOREARM
            val l = side == LimbSide.LEFT
            joints[if (l) Joint.LEFT_HIP else Joint.RIGHT_HIP] = hip
            joints[if (l) Joint.LEFT_KNEE else Joint.RIGHT_KNEE] = knee
            joints[if (l) Joint.LEFT_ANKLE else Joint.RIGHT_ANKLE] = ankle
            joints[if (l) Joint.LEFT_SHOULDER else Joint.RIGHT_SHOULDER] = shoulder
            joints[if (l) Joint.LEFT_ELBOW else Joint.RIGHT_ELBOW] = elbow
            joints[if (l) Joint.LEFT_WRIST else Joint.RIGHT_WRIST] = wrist
        }
        addHeadAndFeet(joints, shoulderCenter, torsoDir)
        return joints
    }

    /** Places the legs with IK instead of directions. Arms still come from [arms]. */
    fun buildIk(
        hipCenter: Vec3,
        torsoDir: Vec3,
        leftAnkle: Vec3,
        rightAnkle: Vec3,
        arms: Pair<LimbDirs, LimbDirs>,
        bend: Bend = Bend.FORWARD,
    ): MutableMap<Joint, Vec3> {
        val joints = build(hipCenter, torsoDir, arms.first, arms.second)
        for (side in LimbSide.entries) {
            val l = side == LimbSide.LEFT
            val hip = joints.getValue(if (l) Joint.LEFT_HIP else Joint.RIGHT_HIP)
            val ankleTarget = (if (l) leftAnkle else rightAnkle).let { Vec3(hip.x, it.y, it.z) }
            val knee = ik2(hip, ankleTarget, Body.THIGH, Body.SHIN, bend)
            joints[if (l) Joint.LEFT_KNEE else Joint.RIGHT_KNEE] = knee
            joints[if (l) Joint.LEFT_ANKLE else Joint.RIGHT_ANKLE] = ankleTarget
        }
        addFeet(joints)
        return joints
    }

    private fun addHeadAndFeet(joints: MutableMap<Joint, Vec3>, shoulderCenter: Vec3, torsoDir: Vec3) {
        joints[Joint.NOSE] = shoulderCenter + torsoDir * Body.NECK + Vec3(0.0, 0.0, 0.0)
        addFeet(joints)
    }

    private fun addFeet(joints: MutableMap<Joint, Vec3>) {
        for (l in listOf(true, false)) {
            val ankle = joints[if (l) Joint.LEFT_ANKLE else Joint.RIGHT_ANKLE] ?: continue
            joints[if (l) Joint.LEFT_FOOT_INDEX else Joint.RIGHT_FOOT_INDEX] = ankle + Vec3(0.0, -0.02, Body.FOOT)
            joints[if (l) Joint.LEFT_HEEL else Joint.RIGHT_HEEL] = ankle + Vec3(0.0, -0.02, -0.03)
        }
    }
}

/** Standing with arms hanging. */
fun standingDirs(): Pair<LimbDirs, LimbDirs> {
    val d = LimbDirs(sag(180.0), sag(180.0), sag(180.0), sag(180.0))
    return d to d
}

fun lerp(a: Double, b: Double, t: Double) = a + (b - a) * t

fun lerpPose(a: World, b: World, t: Double): World =
    a.mapValues { (j, p) -> b[j]?.let { q -> p + (q - p) * t } ?: p }
