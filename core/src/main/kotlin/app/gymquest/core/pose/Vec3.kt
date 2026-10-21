package app.gymquest.core.pose

import kotlin.math.sqrt

/** Minimal 3D vector. In image space +x is right, +y is DOWN and +z points away from the camera. */
data class Vec3(val x: Double, val y: Double, val z: Double = 0.0) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(k: Double) = Vec3(x * k, y * k, z * k)
    operator fun div(k: Double) = Vec3(x / k, y / k, z / k)

    infix fun dot(o: Vec3): Double = x * o.x + y * o.y + z * o.z
    fun norm(): Double = sqrt(this dot this)
    fun norm2d(): Double = sqrt(x * x + y * y)
    fun dropZ() = Vec3(x, y, 0.0)

    companion object {
        val ZERO = Vec3(0.0, 0.0, 0.0)
        fun mid(a: Vec3, b: Vec3) = Vec3((a.x + b.x) / 2, (a.y + b.y) / 2, (a.z + b.z) / 2)
    }
}
