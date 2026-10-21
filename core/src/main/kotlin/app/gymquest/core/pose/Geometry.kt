package app.gymquest.core.pose

import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

object Geometry {
    private const val RAD_TO_DEG = 180.0 / PI
    private const val DEG_TO_RAD = PI / 180.0

    /** Interior angle at [b] formed by a-b-c, in degrees (0..180). */
    fun angle(a: Vec3, b: Vec3, c: Vec3, use3d: Boolean = false): Double {
        val u = if (use3d) a - b else (a - b).dropZ()
        val v = if (use3d) c - b else (c - b).dropZ()
        val nu = u.norm()
        val nv = v.norm()
        if (nu < 1e-9 || nv < 1e-9) return 180.0
        val cos = ((u dot v) / (nu * nv)).coerceIn(-1.0, 1.0)
        return acos(cos) * RAD_TO_DEG
    }

    /**
     * Angle of the segment from [from] to [to] relative to straight UP in the image, in degrees
     * (0 = pointing up, 90 = pointing right in the image, 180 = pointing down). Always 0..180.
     */
    fun angleFromVertical(from: Vec3, to: Vec3): Double {
        val dx = to.x - from.x
        val dy = from.y - to.y // flip so that up is positive
        return atan2(kotlin.math.abs(dx), dy) * RAD_TO_DEG
    }

    /** Signed version of [angleFromVertical]: positive when the segment leans to the image's right. */
    fun signedAngleFromVertical(from: Vec3, to: Vec3): Double {
        val dx = to.x - from.x
        val dy = from.y - to.y
        return atan2(dx, dy) * RAD_TO_DEG
    }

    /** Rotates a point around [pivot] by [degrees] (counter-clockwise on screen, y down). */
    fun rotate(p: Vec3, pivot: Vec3, degrees: Double): Vec3 {
        val r = degrees * DEG_TO_RAD
        val dx = p.x - pivot.x
        val dy = p.y - pivot.y
        return Vec3(
            pivot.x + dx * cos(r) + dy * sin(r),
            pivot.y - dx * sin(r) + dy * cos(r),
            p.z,
        )
    }

    fun distance(a: Vec3, b: Vec3, use3d: Boolean = false): Double =
        if (use3d) (a - b).norm() else (a - b).norm2d()

    /** Linear remap of [v] from [from] range to 0..1, clamped. Works for descending ranges too. */
    fun normalize(v: Double, start: Double, end: Double): Double {
        if (start == end) return 0.0
        return ((v - start) / (end - start)).coerceIn(0.0, 1.0)
    }
}

/** Applies a counter-rotation to a frame so that gravity points down even if the phone is tilted. */
fun PoseFrame.derolled(rollDegrees: Double): PoseFrame {
    if (kotlin.math.abs(rollDegrees) < 0.5) return this
    val pivot = Vec3(0.5 * aspect, 0.5, 0.0)
    return mapLandmarks { _, l ->
        val p = Geometry.rotate(Vec3(l.x * aspect, l.y, l.z * aspect), pivot, rollDegrees)
        l.copy(x = p.x / aspect, y = p.y)
    }
}
