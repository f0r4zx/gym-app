package app.gymquest.core.sim

import app.gymquest.core.pose.Joint
import app.gymquest.core.pose.Landmark
import app.gymquest.core.pose.PoseFrame
import app.gymquest.core.pose.Vec3
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Gaussian jitter that mimics the frame-to-frame wobble of an on-device pose model. */
class Noise(val sigma: Double = 0.004, seed: Long = 1L) {
    private val random = Random(seed)
    fun next(): Double = if (sigma == 0.0) 0.0 else random.nextGaussian() * sigma
}

/**
 * A virtual phone camera. [yawDeg] rotates the viewpoint around the person (0 = in front,
 * 90 = side-on), [rollDeg] tilts the phone, and [personHeight] is the fraction of the image height
 * a standing person occupies (smaller = further away).
 */
class Camera(
    val yawDeg: Double = 0.0,
    val rollDeg: Double = 0.0,
    val personHeight: Double = 0.62,
    val aspect: Double = 9.0 / 16.0,
    val centerX: Double = 0.5,
    val floorY: Double = 0.93,
    /** Horizontal world offset (along the camera's right axis) that is placed at [centerX]. */
    private val shiftRight: Double = 0.0,
) {
    /**
     * A copy of this camera re-aimed at the horizontal middle of everything the person will do
     * in [worlds]. If the body is wider than a portrait frame (lying exercises) the phone is
     * turned to landscape, which is what someone would do in practice.
     */
    fun fittedTo(vararg worlds: World): Camera {
        val yaw = yawDeg * PI / 180.0
        val right = Vec3(cos(yaw), 0.0, -sin(yaw))
        val xs = worlds.flatMap { w -> w.values.map { it dot right } }
        val mid = (xs.min() + xs.max()) / 2
        val extent = xs.max() - xs.min()
        val newAspect = if (extent * personHeight > 0.8 * aspect) 16.0 / 9.0 else aspect
        return Camera(yawDeg, rollDeg, personHeight, newAspect, centerX, floorY, mid)
    }

    fun project(world: World, timestampMs: Long, noise: Noise? = null): PoseFrame {
        val yaw = yawDeg * PI / 180.0
        val right = Vec3(cos(yaw), 0.0, -sin(yaw))
        val view = Vec3(-sin(yaw), 0.0, -cos(yaw))
        val hipC = listOfNotNull(world[Joint.LEFT_HIP], world[Joint.RIGHT_HIP]).let { hips ->
            if (hips.isEmpty()) Vec3.ZERO else Vec3.mid(hips.first(), hips.last())
        }
        val depthRef = hipC dot view
        val roll = rollDeg * PI / 180.0
        val cx = 0.5 * aspect
        val cy = 0.5

        val out = ArrayList<Landmark?>(Joint.COUNT)
        for (j in Joint.entries) {
            val p = world[j]
            if (p == null) {
                out.add(null)
                continue
            }
            var ux = centerX * aspect + personHeight * ((p dot right) - shiftRight)
            var uy = floorY - personHeight * p.y
            if (roll != 0.0) {
                val dx = ux - cx
                val dy = uy - cy
                // Positive roll turns the image clockwise on screen.
                ux = cx + dx * cos(roll) - dy * sin(roll)
                uy = cy + dx * sin(roll) + dy * cos(roll)
            }
            val depth = personHeight * ((p dot view) - depthRef)
            ux += noise?.next() ?: 0.0
            uy += noise?.next() ?: 0.0
            val x = ux / aspect
            val inFrame = x in 0.0..1.0 && uy in 0.0..1.0
            out.add(Landmark(x, uy, depth / aspect + (noise?.next() ?: 0.0), if (inFrame) 0.95 else 0.1))
        }
        return PoseFrame(timestampMs, out, aspect)
    }
}

/** Maps wall-clock milliseconds to the phase (0..1) of a series of repetitions. */
class RepTimeline(
    val reps: Int,
    val repMs: Long,
    val leadInMs: Long = 1_500,
    val gapMs: Long = 400,
    val tailMs: Long = 1_000,
) {
    val totalMs: Long get() = leadInMs + reps * repMs + (reps - 1).coerceAtLeast(0) * gapMs + tailMs

    fun phase(ms: Long): Double {
        var t = ms - leadInMs
        if (t < 0) return 0.0
        for (i in 0 until reps) {
            if (t < repMs) return Motions.repProfile(t.toDouble() / repMs)
            t -= repMs
            if (t < gapMs) return 0.0
            t -= gapMs
        }
        return 0.0
    }
}

object Sequences {
    /** Renders [totalMs] of video at [fps], asking [world] for the body at each timestamp. */
    fun frames(
        totalMs: Long,
        camera: Camera,
        noise: Noise? = null,
        fps: Int = 30,
        startMs: Long = 0,
        world: (Long) -> World,
    ): List<PoseFrame> {
        val step = 1000.0 / fps
        val n = (totalMs / step).toInt()
        return List(n) { i ->
            val ms = (i * step).toLong()
            camera.project(world(ms), startMs + ms, noise)
        }
    }

    /** A person doing [timeline] reps of an exercise driven by a 0..1 phase. */
    fun reps(timeline: RepTimeline, camera: Camera, noise: Noise? = null, fps: Int = 30, motion: (Double) -> World) =
        frames(timeline.totalMs, camera, noise, fps) { ms -> motion(timeline.phase(ms)) }
}
