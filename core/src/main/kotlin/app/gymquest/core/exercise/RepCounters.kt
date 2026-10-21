package app.gymquest.core.exercise

import app.gymquest.core.exercise.ExerciseType.BICEP_CURL
import app.gymquest.core.exercise.ExerciseType.BURPEE
import app.gymquest.core.exercise.ExerciseType.DEADLIFT
import app.gymquest.core.exercise.ExerciseType.GLUTE_BRIDGE
import app.gymquest.core.exercise.ExerciseType.HIGH_KNEES
import app.gymquest.core.exercise.ExerciseType.JUMPING_JACK
import app.gymquest.core.exercise.ExerciseType.JUMP_ROPE
import app.gymquest.core.exercise.ExerciseType.LATERAL_RAISE
import app.gymquest.core.exercise.ExerciseType.LUNGE
import app.gymquest.core.exercise.ExerciseType.MOUNTAIN_CLIMBER
import app.gymquest.core.exercise.ExerciseType.PLANK
import app.gymquest.core.exercise.ExerciseType.PULL_UP
import app.gymquest.core.exercise.ExerciseType.PUSH_UP
import app.gymquest.core.exercise.ExerciseType.SHOULDER_PRESS
import app.gymquest.core.exercise.ExerciseType.SIT_UP
import app.gymquest.core.exercise.ExerciseType.SQUAT
import app.gymquest.core.exercise.ExerciseType.TRICEPS_EXTENSION
import app.gymquest.core.exercise.ExerciseType.WALL_SIT
import app.gymquest.core.pose.Geometry
import app.gymquest.core.pose.Geometry.normalize
import app.gymquest.core.pose.Joint
import kotlin.math.abs

/**
 * Factory for the camera-based counters. Each exercise is reduced to a 0..1 progress signal built
 * from joint angles and body-relative distances, so it works wherever the phone is placed and at
 * any distance. See [ExerciseType.view] for the camera positions that give the best accuracy.
 */
object RepCounters {

    fun create(type: ExerciseType, strictness: Strictness = Strictness.NORMAL): RepCounter = when (type) {
        SQUAT -> squat(strictness)
        PUSH_UP -> pushUp(strictness)
        PULL_UP -> pullUp(strictness)
        LUNGE -> lunge(strictness)
        JUMPING_JACK -> jumpingJack(strictness)
        BICEP_CURL -> bicepCurl(strictness)
        SHOULDER_PRESS -> shoulderPress(strictness)
        LATERAL_RAISE -> lateralRaise(strictness)
        TRICEPS_EXTENSION -> tricepsExtension(strictness)
        SIT_UP -> sitUp(strictness)
        DEADLIFT -> deadlift(strictness)
        GLUTE_BRIDGE -> gluteBridge(strictness)
        MOUNTAIN_CLIMBER -> mountainClimber(strictness)
        HIGH_KNEES -> highKnees(strictness)
        BURPEE -> burpee(strictness)
        JUMP_ROPE -> JumpRopeCounter()
        PLANK -> plank()
        WALL_SIT -> wallSit()
    }

    /** Hip deviation from the shoulder–ankle line (fraction of torso length) that counts as bad form. */
    private const val HIP_SAG = 0.14
    private const val HIP_PIKE = 0.18

    // ---- Squat ---------------------------------------------------------------------------

    private fun squat(s: Strictness): RepCounter {
        val standing = StandingReference()
        return ProgressRepCounter(SQUAT, RepConfig(peak = 0.8, rest = 0.3, partial = 0.45), s) { f ->
            val hip = PoseMath.hipMid(f)
            val ankle = PoseMath.ankleMid(f)
            if (hip == null || ankle == null) return@ProgressRepCounter null

            val yaw = PoseMath.viewYaw(f)
            val front = yaw != null && yaw < 35
            // Face-on, a knee bend is mostly depth along the camera axis, so lean on 3D there.
            val knee = PoseMath.bilateralKnee(f, use3d = front)
            val kneeProgress = knee?.let { normalize(it, 170.0, 85.0) } ?: 0.0

            // Hip height above the ankles, in shin lengths: independent of the viewing angle and of
            // how far the person stands from the camera, so walking back and forth is not a squat.
            val shin = PoseMath.shinLength(f)
            val hipProgress = if (shin != null) {
                val ratio = (ankle.y - hip.y) / shin
                val ref = standing.update(ratio, f.timestampMs)
                if (ref > 1.0) normalize(ratio, ref, ref * 0.55) else 0.0
            } else {
                0.0
            }

            val progress = maxOf(kneeProgress, hipProgress)
            val issues = mutableSetOf<FormIssue>()
            if (progress > 0.6) {
                if (front) {
                    val kl = f.posIfVisible(Joint.LEFT_KNEE)
                    val kr = f.posIfVisible(Joint.RIGHT_KNEE)
                    val al = f.posIfVisible(Joint.LEFT_ANKLE)
                    val ar = f.posIfVisible(Joint.RIGHT_ANKLE)
                    if (kl != null && kr != null && al != null && ar != null) {
                        val ankleWidth = abs(al.x - ar.x)
                        if (ankleWidth > 0.05 && abs(kl.x - kr.x) < ankleWidth * 0.72) issues += FormIssue.KNEES_IN
                    }
                } else if (yaw != null && yaw > 55) {
                    val lean = PoseMath.torsoLean(f)
                    if (lean != null && lean > 62) issues += FormIssue.LEANING_FORWARD
                }
            }
            Reading(progress, issues)
        }
    }

    // ---- Push-up -------------------------------------------------------------------------

    private fun pushUp(s: Strictness): RepCounter =
        ProgressRepCounter(PUSH_UP, RepConfig(peak = 0.74, rest = 0.28, partial = 0.45), s) { f ->
            val lean = PoseMath.torsoLean(f) ?: return@ProgressRepCounter null
            // Only track when the body is roughly horizontal, so standing around never counts.
            if (lean < 50) return@ProgressRepCounter null
            val elbow = PoseMath.bilateralElbow(f) ?: return@ProgressRepCounter null

            val issues = mutableSetOf<FormIssue>()
            PoseMath.hipOffsetFromLine(f)?.let { offset ->
                if (offset > HIP_SAG) issues += FormIssue.HIPS_SAGGING
                if (offset < -HIP_PIKE) issues += FormIssue.HIPS_PIKED
            }
            Reading(normalize(elbow, 160.0, 80.0), issues)
        }

    // ---- Pull-up -------------------------------------------------------------------------

    private fun pullUp(s: Strictness): RepCounter =
        ProgressRepCounter(PULL_UP, RepConfig(peak = 0.8, rest = 0.3, partial = 0.5, minRepMs = 600, idealMinMs = 1200), s) { f ->
            val shoulder = PoseMath.shoulderMid(f) ?: return@ProgressRepCounter null
            val wrist = PoseMath.wristMid(f) ?: return@ProgressRepCounter null
            val nose = f.posIfVisible(Joint.NOSE) ?: return@ProgressRepCounter null
            // Hands must be on the bar, above the shoulders.
            if (wrist.y > shoulder.y) return@ProgressRepCounter null
            val elbow = PoseMath.bilateralElbow(f) ?: return@ProgressRepCounter null

            val elbowProgress = normalize(elbow, 165.0, 55.0)
            val scale = PoseMath.bodyScale(f) ?: return@ProgressRepCounter null
            val chinProgress = normalize((nose.y - wrist.y) / scale, 0.75, -0.1)
            val issues = if (elbowProgress > 0.8 && chinProgress < 0.7) setOf(FormIssue.NOT_DEEP_ENOUGH) else emptySet()
            Reading(0.5 * elbowProgress + 0.5 * chinProgress, issues)
        }

    // ---- Lunge ---------------------------------------------------------------------------

    private fun lunge(s: Strictness): RepCounter =
        ProgressRepCounter(LUNGE, RepConfig(peak = 0.72, rest = 0.3, partial = 0.45), s) { f ->
            val l = PoseMath.kneeAngle(f, Side.LEFT)
            val r = PoseMath.kneeAngle(f, Side.RIGHT)
            val knee = listOfNotNull(l, r).minOrNull() ?: return@ProgressRepCounter null
            val issues = mutableSetOf<FormIssue>()
            val la = f.posIfVisible(Joint.LEFT_ANKLE)
            val ra = f.posIfVisible(Joint.RIGHT_ANKLE)
            val yaw = PoseMath.viewYaw(f)
            val scale = PoseMath.bodyScale(f)
            if (la != null && ra != null && yaw != null && scale != null && yaw > 55 && abs(la.x - ra.x) < 0.85 * scale) {
                issues += FormIssue.SHORT_STRIDE
            }
            Reading(normalize(knee, 170.0, 95.0), issues)
        }

    // ---- Jumping jack --------------------------------------------------------------------

    private fun jumpingJack(s: Strictness): RepCounter =
        ProgressRepCounter(
            JUMPING_JACK,
            RepConfig(peak = 0.7, rest = 0.3, partial = 0.45, minRepMs = 250, idealMinMs = 0, lostGraceMs = 800),
            s,
        ) { f ->
            val scale = PoseMath.bodyScale(f) ?: return@ProgressRepCounter null
            val shoulder = PoseMath.shoulderMid(f) ?: return@ProgressRepCounter null
            val wrist = PoseMath.wristMid(f) ?: return@ProgressRepCounter null
            val la = f.posIfVisible(Joint.LEFT_ANKLE) ?: return@ProgressRepCounter null
            val ra = f.posIfVisible(Joint.RIGHT_ANKLE) ?: return@ProgressRepCounter null

            val armsUp = normalize((shoulder.y - wrist.y) / scale, -0.4, 0.8)
            val legsApart = normalize(abs(la.x - ra.x) / scale, 0.75, 1.35)
            Reading(minOf(armsUp, legsApart))
        }

    // ---- Dumbbell / arm exercises (one machine per arm, merged) ----------------------------

    private fun perSide(
        type: ExerciseType,
        mergeWindowMs: Long,
        build: (Side) -> RepCounter,
    ): RepCounter = MergedCounter(type, build(Side.LEFT), build(Side.RIGHT), mergeWindowMs)

    private fun bicepCurl(s: Strictness): RepCounter = perSide(BICEP_CURL, 450) { side ->
        ProgressRepCounter(BICEP_CURL, RepConfig(peak = 0.78, rest = 0.3, partial = 0.5, peakIssue = FormIssue.NOT_DEEP_ENOUGH), s) { f ->
            val elbow = PoseMath.elbowAngle(f, side) ?: return@ProgressRepCounter null
            val shoulder = f.posIfVisible(side.shoulder)
            val elbowPos = f.posIfVisible(side.elbow)
            val issues = mutableSetOf<FormIssue>()
            if (shoulder != null && elbowPos != null && 180.0 - Geometry.angleFromVertical(shoulder, elbowPos) > 42) {
                issues += FormIssue.ELBOW_SWING
            }
            Reading(normalize(elbow, 160.0, 48.0), issues)
        }
    }

    private fun shoulderPress(s: Strictness): RepCounter = perSide(SHOULDER_PRESS, 450) { side ->
        ProgressRepCounter(
            SHOULDER_PRESS,
            RepConfig(peak = 0.8, rest = 0.3, partial = 0.5, peakIssue = FormIssue.NOT_LOCKED_OUT),
            s,
        ) { f ->
            val elbow = PoseMath.elbowAngle(f, side) ?: return@ProgressRepCounter null
            val shoulder = f.posIfVisible(side.shoulder) ?: return@ProgressRepCounter null
            val wrist = f.posIfVisible(side.wrist) ?: return@ProgressRepCounter null
            val scale = PoseMath.bodyScale(f) ?: return@ProgressRepCounter null
            val wristHeight = (shoulder.y - wrist.y) / scale
            Reading(0.5 * normalize(elbow, 100.0, 165.0) + 0.5 * normalize(wristHeight, 0.15, 0.95))
        }
    }

    private fun lateralRaise(s: Strictness): RepCounter = perSide(LATERAL_RAISE, 450) { side ->
        ProgressRepCounter(LATERAL_RAISE, RepConfig(peak = 0.8, rest = 0.3, partial = 0.5, peakIssue = FormIssue.NOT_DEEP_ENOUGH), s) { f ->
            val abduction = PoseMath.angleAt(f, side.hip, side.shoulder, side.elbow) ?: return@ProgressRepCounter null
            Reading(normalize(abduction, 25.0, 82.0))
        }
    }

    private fun tricepsExtension(s: Strictness): RepCounter = perSide(TRICEPS_EXTENSION, 450) { side ->
        ProgressRepCounter(
            TRICEPS_EXTENSION,
            RepConfig(peak = 0.8, rest = 0.3, partial = 0.5, peakIssue = FormIssue.NOT_LOCKED_OUT),
            s,
        ) { f ->
            val elbow = PoseMath.elbowAngle(f, side) ?: return@ProgressRepCounter null
            val shoulder = f.posIfVisible(side.shoulder) ?: return@ProgressRepCounter null
            val elbowPos = f.posIfVisible(side.elbow) ?: return@ProgressRepCounter null
            // The elbow stays above the shoulder in an overhead extension.
            if (elbowPos.y > shoulder.y) return@ProgressRepCounter null
            Reading(normalize(elbow, 75.0, 165.0))
        }
    }

    // ---- Core and hinge -------------------------------------------------------------------

    private fun sitUp(s: Strictness): RepCounter =
        ProgressRepCounter(SIT_UP, RepConfig(peak = 0.62, rest = 0.3, partial = 0.4), s) { f ->
            val angle = PoseMath.bilateralHipAngle(f) ?: return@ProgressRepCounter null
            Reading(normalize(angle, 140.0, 60.0))
        }

    private fun deadlift(s: Strictness): RepCounter =
        ProgressRepCounter(DEADLIFT, RepConfig(peak = 0.7, rest = 0.3, partial = 0.45, minRepMs = 600, idealMinMs = 1400), s) { f ->
            val angle = PoseMath.bilateralHipAngle(f) ?: return@ProgressRepCounter null
            Reading(normalize(angle, 172.0, 105.0))
        }

    private fun gluteBridge(s: Strictness): RepCounter =
        ProgressRepCounter(
            GLUTE_BRIDGE,
            RepConfig(peak = 0.75, rest = 0.3, partial = 0.5, peakIssue = FormIssue.NOT_LOCKED_OUT),
            s,
        ) { f ->
            val angle = PoseMath.bilateralHipAngle(f) ?: return@ProgressRepCounter null
            Reading(normalize(angle, 145.0, 175.0))
        }

    // ---- Cardio (one machine per leg, every event counts) ----------------------------------

    private fun mountainClimber(s: Strictness): RepCounter = perSide(MOUNTAIN_CLIMBER, 0) { side ->
        ProgressRepCounter(
            MOUNTAIN_CLIMBER,
            RepConfig(peak = 0.62, rest = 0.3, partial = 0.45, minRepMs = 180, idealMinMs = 0),
            s,
        ) { f ->
            val lean = PoseMath.torsoLean(f) ?: return@ProgressRepCounter null
            if (lean < 45) return@ProgressRepCounter null
            val hip = PoseMath.hipAngle(f, side) ?: return@ProgressRepCounter null
            Reading(normalize(hip, 165.0, 95.0))
        }
    }

    private fun highKnees(s: Strictness): RepCounter = perSide(HIGH_KNEES, 0) { side ->
        val reference = StandingReference()
        ProgressRepCounter(
            HIGH_KNEES,
            RepConfig(peak = 0.7, rest = 0.3, partial = 0.5, minRepMs = 150, idealMinMs = 0, lostGraceMs = 800),
            s,
        ) { f ->
            val hip = f.posIfVisible(side.hip) ?: return@ProgressRepCounter null
            val knee = f.posIfVisible(side.knee) ?: return@ProgressRepCounter null
            val scale = PoseMath.bodyScale(f) ?: return@ProgressRepCounter null
            val drop = (knee.y - hip.y) / scale
            val ref = reference.update(drop, f.timestampMs)
            if (ref < 0.3) return@ProgressRepCounter null
            Reading(normalize(drop, ref * 0.95, ref * 0.1))
        }
    }

    private fun burpee(s: Strictness): RepCounter {
        val standing = StandingReference()
        return ProgressRepCounter(
            BURPEE,
            RepConfig(peak = 0.7, rest = 0.25, partial = 0.5, minRepMs = 1_200, maxRepMs = 20_000, idealMinMs = 2_400, lostGraceMs = 2_500),
            s,
        ) { f ->
            val shoulder = PoseMath.shoulderMid(f) ?: return@ProgressRepCounter null
            val ankle = PoseMath.ankleMid(f) ?: return@ProgressRepCounter null
            val lean = PoseMath.torsoLean(f) ?: return@ProgressRepCounter null
            val scale = PoseMath.bodyScale(f) ?: return@ProgressRepCounter null
            val extent = (ankle.y - shoulder.y) / scale
            val ref = standing.update(extent, f.timestampMs)
            if (ref < 1.0) return@ProgressRepCounter null
            // Down on the floor = torso near horizontal AND the body compressed vertically. Needing
            // both keeps squats and toe-touches from counting as burpees.
            val low = normalize(extent, ref, ref * 0.45)
            val flat = normalize(lean, 30.0, 62.0)
            Reading(minOf(low, flat))
        }
    }

    // ---- Isometric holds -------------------------------------------------------------------

    private fun plank(): RepCounter = HoldCounter(PLANK) { f ->
        val s = PoseMath.shoulderMid(f)
        val h = PoseMath.hipMid(f)
        val a = PoseMath.ankleMid(f)
        if (s == null || h == null || a == null) return@HoldCounter null
        val lean = PoseMath.torsoLean(f) ?: return@HoldCounter null
        if (lean < 55) return@HoldCounter Reading(0.0)
        val alignment = Geometry.angle(s, h, a)
        val issues = mutableSetOf<FormIssue>()
        PoseMath.hipOffsetFromLine(f)?.let { offset ->
            if (offset > HIP_SAG) issues += FormIssue.HIPS_SAGGING
            if (offset < -HIP_PIKE) issues += FormIssue.HIPS_PIKED
        }
        Reading(normalize(alignment, 140.0, 172.0), issues)
    }

    private fun wallSit(): RepCounter = HoldCounter(WALL_SIT) { f ->
        val knee = PoseMath.bilateralKnee(f) ?: return@HoldCounter null
        val lean = PoseMath.torsoLean(f) ?: return@HoldCounter null
        // Thighs roughly parallel to the floor with the back upright against the wall.
        val kneeOk = knee in 70.0..118.0
        val backOk = lean < 28
        Reading(if (kneeOk && backOk) 1.0 else 0.0)
    }
}

