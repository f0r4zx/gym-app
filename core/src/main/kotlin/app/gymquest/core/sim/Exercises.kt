package app.gymquest.core.sim

import app.gymquest.core.pose.Joint
import app.gymquest.core.pose.Vec3
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Kinematic models of each exercise, parameterised by a phase `t` in 0..1 (0 = start position,
 * 1 = end of the range of motion). They drive the unit tests and the app's camera-free demo mode.
 */
object Motions {

    private val floorAnkle = Body.ANKLE_HEIGHT

    private fun planted(z: Double = 0.0, y: Double = floorAnkle) = Vec3(0.0, y, z)

    fun standing(): World {
        val hip = Vec3(0.0, Body.STANDING_HIP, 0.0)
        return SkeletonBuilder.build(hip, sag(0.0), standingDirs().first, standingDirs().second)
    }

    /** Bodyweight squat with arms out front. t=1 is just below parallel. */
    fun squat(t: Double): World {
        val hip = Vec3(0.0, lerp(Body.STANDING_HIP, 0.235, t), lerp(0.0, -0.17, t))
        val arms = LimbDirs(sag(180.0), sag(180.0), sag(90.0), sag(90.0)).let { it to it }
        return SkeletonBuilder.buildIk(hip, sag(lerp(0.0, 38.0, t)), planted(), planted(), arms)
    }

    /** Push-up seen from the side (head toward +Z). t=1 is chest near the floor. */
    fun pushUp(t: Double, hipSag: Double = 0.0): World {
        val elbowAngle = lerp(176.0, 68.0, t)
        val wristY = 0.03
        val forearm = sag(-8.0) // wrist -> elbow, almost vertical, leaning slightly back
        val wrist = Vec3(0.0, wristY, 0.0)
        val elbow = wrist + forearm * Body.FOREARM
        val upperAngle = (180.0 - 8.0) - elbowAngle // elbow -> shoulder
        val shoulderOffset = sag(upperAngle) * Body.UPPER_ARM
        val shoulder = elbow + shoulderOffset
        return bodyLine(shoulder, hipSag, wristBelow = wrist, elbow = elbow)
    }

    /** Forearm plank (static). */
    fun plank(hipSag: Double = 0.0): World = pushUp(0.0, hipSag)

    private fun bodyLine(shoulder: Vec3, hipSag: Double, wristBelow: Vec3, elbow: Vec3): World {
        val ankleY = 0.04
        val total = Body.TORSO + Body.THIGH + Body.SHIN
        val psi = asin(((shoulder.y - ankleY) / total).coerceIn(-1.0, 1.0))
        val back = Vec3(0.0, -sin(psi), -cos(psi)) // shoulder -> ankle direction
        val hipC = shoulder + back * Body.TORSO + Vec3(0.0, -hipSag, 0.0)
        val ankleC = shoulder + back * total
        val toHead = Vec3(0.0, sin(psi), cos(psi))
        val straightLeg = LimbDirs(back, back, sag(0.0), sag(0.0))
        val joints = SkeletonBuilder.build(hipC, toHead, straightLeg, straightLeg)
        val hipHalf = Body.HIP_HALF
        for (l in listOf(true, false)) {
            val sign = if (l) 1 else -1
            joints[if (l) Joint.LEFT_ANKLE else Joint.RIGHT_ANKLE] = Vec3(sign * hipHalf, ankleC.y, ankleC.z)
            joints[if (l) Joint.LEFT_KNEE else Joint.RIGHT_KNEE] = Vec3(
                sign * hipHalf, lerp(hipC.y, ankleC.y, Body.THIGH / Body.LEG), lerp(hipC.z, ankleC.z, Body.THIGH / Body.LEG),
            )
            joints[if (l) Joint.LEFT_SHOULDER else Joint.RIGHT_SHOULDER] = shoulder + Vec3(sign * Body.SHOULDER_HALF, 0.0, 0.0)
            joints[if (l) Joint.LEFT_ELBOW else Joint.RIGHT_ELBOW] = elbow + Vec3(sign * Body.SHOULDER_HALF, 0.0, 0.0)
            joints[if (l) Joint.LEFT_WRIST else Joint.RIGHT_WRIST] = wristBelow + Vec3(sign * Body.SHOULDER_HALF, 0.0, 0.0)
        }
        joints[Joint.NOSE] = shoulder + toHead * Body.NECK
        return joints
    }

    /** Chin-up seen from the front or side. t=1 is chin over the bar. */
    fun pullUp(t: Double): World {
        val elbowAngle = lerp(172.0, 58.0, t)
        val barY = 1.12
        val wrist = Vec3(0.0, barY, 0.0)
        val elbow = wrist + sag(180.0) * Body.FOREARM // wrist -> elbow, straight down
        val shoulder = elbow + sag(180.0 + (180.0 - elbowAngle)) * Body.UPPER_ARM
        val hipC = shoulder + sag(185.0) * Body.TORSO
        val legDir = sag(180.0)
        val arms = LimbDirs(legDir, legDir, legDir, legDir)
        val joints = SkeletonBuilder.build(hipC, sag(-5.0), arms, arms)
        for (l in listOf(true, false)) {
            val sign = if (l) 1 else -1
            joints[if (l) Joint.LEFT_SHOULDER else Joint.RIGHT_SHOULDER] = shoulder + Vec3(sign * Body.SHOULDER_HALF, 0.0, 0.0)
            joints[if (l) Joint.LEFT_ELBOW else Joint.RIGHT_ELBOW] = elbow + Vec3(sign * Body.SHOULDER_HALF, 0.0, 0.0)
            joints[if (l) Joint.LEFT_WRIST else Joint.RIGHT_WRIST] = wrist + Vec3(sign * Body.SHOULDER_HALF, 0.0, 0.0)
        }
        joints[Joint.NOSE] = shoulder + sag(-5.0) * Body.NECK
        return joints
    }

    /** Lunge from a standing start. t=1 is the deepest point; [leftForward] picks the lead leg. */
    fun lunge(t: Double, leftForward: Boolean = true): World {
        val stride = 0.20 * (t * 2.5).coerceAtMost(1.0)
        val hip = Vec3(0.0, lerp(Body.STANDING_HIP, 0.26, t), 0.0)
        val front = planted(stride)
        val rear = planted(-stride, lerp(floorAnkle, 0.07, (t * 2.5).coerceAtMost(1.0)))
        val arms = standingDirs()
        return SkeletonBuilder.buildIk(
            hip, sag(0.0),
            if (leftForward) front else rear,
            if (leftForward) rear else front,
            arms,
        )
    }

    /** Frontal-plane jumping jack. t=1 is arms overhead and feet wide. */
    fun jumpingJack(t: Double): World {
        val armAngle = lerp(8.0, 168.0, t)
        val legAngle = lerp(3.0, 20.0, t)
        val hip = Vec3(0.0, Body.STANDING_HIP, 0.0)
        val left = LimbDirs(lat(180.0 - legAngle, 1), lat(180.0 - legAngle, 1), lat(180.0 - armAngle, 1), lat(180.0 - armAngle, 1))
        val right = LimbDirs(lat(180.0 - legAngle, -1), lat(180.0 - legAngle, -1), lat(180.0 - armAngle, -1), lat(180.0 - armAngle, -1))
        val joints = SkeletonBuilder.build(hip, sag(0.0), left, right)
        return joints
    }

    /**
     * Biceps curl. [t] is the left-arm phase and [tRight] the right-arm phase; t=1 is the top of
     * the curl (elbow ~40°).
     */
    fun curl(t: Double, tRight: Double = t): World {
        fun forearm(p: Double) = sag(lerp(172.0, 40.0, p))
        val upper = sag(180.0)
        val hip = Vec3(0.0, Body.STANDING_HIP, 0.0)
        val leg = sag(180.0)
        val l = LimbDirs(leg, leg, upper, forearm(t))
        val r = LimbDirs(leg, leg, upper, forearm(tRight))
        return SkeletonBuilder.build(hip, sag(0.0), l, r)
    }

    /** Overhead press, front view. t=1 is full lock-out above the head. */
    fun shoulderPress(t: Double, tRight: Double = t): World {
        val hip = Vec3(0.0, Body.STANDING_HIP, 0.0)
        val joints = SkeletonBuilder.build(hip, sag(0.0), standingDirs().first, standingDirs().second)
        for ((side, p) in listOf(1 to t, -1 to tRight)) {
            val upperAngle = lerp(105.0, 12.0, p)
            val foreAngle = lerp(0.0, 8.0, p)
            val l = side == 1
            val shoulder = joints.getValue(if (l) Joint.LEFT_SHOULDER else Joint.RIGHT_SHOULDER)
            val elbow = shoulder + lat(upperAngle, side) * Body.UPPER_ARM
            val wrist = elbow + lat(foreAngle, side) * Body.FOREARM
            joints[if (l) Joint.LEFT_ELBOW else Joint.RIGHT_ELBOW] = elbow
            joints[if (l) Joint.LEFT_WRIST else Joint.RIGHT_WRIST] = wrist
        }
        return joints
    }

    /** Dumbbell lateral raise, front view. t=1 is arms parallel to the floor. */
    fun lateralRaise(t: Double, tRight: Double = t): World {
        val hip = Vec3(0.0, Body.STANDING_HIP, 0.0)
        val leg = sag(180.0)
        fun arm(p: Double, side: Int): LimbDirs {
            val a = lat(lerp(172.0, 92.0, p), side) // whole arm, straight, from hanging to horizontal
            return LimbDirs(leg, leg, a, a)
        }
        return SkeletonBuilder.build(hip, sag(0.0), arm(t, 1), arm(tRight, -1))
    }

    /** Overhead triceps extension, side view. t=1 is full extension. */
    fun tricepsExtension(t: Double): World {
        val elbowAngle = lerp(65.0, 168.0, t)
        val hip = Vec3(0.0, Body.STANDING_HIP, 0.0)
        val leg = sag(180.0)
        val arm = LimbDirs(leg, leg, sag(0.0), sag(elbowAngle - 180.0))
        return SkeletonBuilder.build(hip, sag(0.0), arm, arm)
    }

    /** Sit-up lying on the back with knees bent (head toward -Z). t=1 is fully up. */
    fun sitUp(t: Double): World {
        val hip = Vec3(0.0, 0.07, 0.0)
        val torso = sag(lerp(-90.0, -5.0, t))
        val thigh = sag(40.0)
        val shin = sag(135.0)
        val leg = LimbDirs(thigh, shin, sag(90.0), sag(90.0))
        val joints = SkeletonBuilder.build(hip, torso, leg, leg)
        return joints
    }

    /** Conventional deadlift, side view. t=1 is the bottom of the hinge. */
    fun deadlift(t: Double): World {
        val hip = Vec3(0.0, lerp(0.51, 0.47, t), lerp(0.0, -0.13, t))
        val arms = LimbDirs(sag(180.0), sag(180.0), sag(180.0), sag(180.0)).let { it to it }
        return SkeletonBuilder.buildIk(hip, sag(lerp(0.0, 70.0, t)), planted(), planted(), arms)
    }

    /** Glute bridge on the back (head toward -Z). t=1 is hips fully up. */
    fun gluteBridge(t: Double): World {
        val ankle = Vec3(0.0, floorAnkle, 0.347)
        val hipY = lerp(0.07, 0.185, t)
        val hipZ = lerp(0.0, 0.02, t)
        val shoulderY = 0.06
        // Shoulders stay on the floor; the torso rises toward the hips.
        val dz = sqrt(max(0.0, Body.TORSO * Body.TORSO - (hipY - shoulderY) * (hipY - shoulderY)))
        val toShoulder = Vec3(0.0, shoulderY - hipY, -dz) / Body.TORSO
        val arms = LimbDirs(sag(180.0), sag(180.0), sag(90.0), sag(90.0)).let { it to it }
        return SkeletonBuilder.buildIk(Vec3(0.0, hipY, hipZ), toShoulder, ankle, ankle, arms, Bend.UP)
    }

    /** Plank with alternating knee drives. [tLeft]/[tRight] are 0 (leg back) .. 1 (knee in). */
    fun mountainClimber(tLeft: Double, tRight: Double): World {
        val base = plank()
        val joints = base.toMutableMap()
        for ((l, p) in listOf(true to tLeft, false to tRight)) {
            val hip = joints.getValue(if (l) Joint.LEFT_HIP else Joint.RIGHT_HIP)
            val restAnkle = joints.getValue(if (l) Joint.LEFT_ANKLE else Joint.RIGHT_ANKLE)
            val driven = Vec3(hip.x, 0.07, -0.15)
            val ankle = Vec3(hip.x, lerp(restAnkle.y, driven.y, p), lerp(restAnkle.z, driven.z, p))
            val knee = ik2(hip, ankle, Body.THIGH, Body.SHIN, Bend.UP)
            joints[if (l) Joint.LEFT_ANKLE else Joint.RIGHT_ANKLE] = ankle
            joints[if (l) Joint.LEFT_KNEE else Joint.RIGHT_KNEE] = knee
        }
        return joints
    }

    /** Running in place. [tLeft]/[tRight] are 0 (foot down) .. 1 (knee at hip height). */
    fun highKnees(tLeft: Double, tRight: Double): World {
        val hip = Vec3(0.0, Body.STANDING_HIP, 0.0)
        val arms = LimbDirs(sag(180.0), sag(180.0), sag(160.0), sag(110.0)).let { it to it }
        val joints = SkeletonBuilder.build(hip, sag(0.0), arms.first, arms.second)
        for ((l, p) in listOf(true to tLeft, false to tRight)) {
            val h = joints.getValue(if (l) Joint.LEFT_HIP else Joint.RIGHT_HIP)
            val thigh = sag(lerp(180.0, 95.0, p))
            val knee = h + thigh * Body.THIGH
            val ankle = knee + sag(lerp(180.0, 190.0, p)) * Body.SHIN
            joints[if (l) Joint.LEFT_KNEE else Joint.RIGHT_KNEE] = knee
            joints[if (l) Joint.LEFT_ANKLE else Joint.RIGHT_ANKLE] = ankle
        }
        return joints
    }

    /** Burpee: t=0 standing, ~0.45 hands-down crouch, 1 flat in the plank. */
    fun burpee(t: Double): World {
        val stand = standing()
        val crouch = squat(1.0)
        val plank = plank()
        return if (t < 0.45) lerpPose(stand, crouch, smooth(t / 0.45)) else lerpPose(crouch, plank, smooth((t - 0.45) / 0.35))
    }

    /** Wall sit: back vertical, thighs parallel to the floor. */
    fun wallSit(): World {
        val hip = Vec3(0.0, 0.285, -0.245)
        val ankle = planted(0.0)
        val arms = standingDirs()
        return SkeletonBuilder.buildIk(hip, sag(0.0), ankle, ankle, arms)
    }

    /** Standing jump-rope body bob: [height] is how far the body is off the floor. */
    fun jumpRope(height: Double): World = standing().mapValues { (_, p) -> p + Vec3(0.0, height, 0.0) }

    private fun smooth(x: Double) = 0.5 - 0.5 * cos(PI * x.coerceIn(0.0, 1.0))

    /** Smooth 0→1→0 profile for a rep, `u` in 0..1. */
    fun repProfile(u: Double): Double = 0.5 - 0.5 * cos(2 * PI * u)
}
