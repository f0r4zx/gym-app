package app.gymquest.core.pose

import app.gymquest.core.sim.Camera
import app.gymquest.core.sim.Motions
import app.gymquest.core.sim.Noise
import app.gymquest.core.sim.Sequences
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GeometryAndSmoothingTest {
    @Test
    fun rightAngleIsNinetyDegrees() {
        assertEquals(90.0, Geometry.angle(Vec3(1.0, 0.0), Vec3(0.0, 0.0), Vec3(0.0, 1.0)), 1e-9)
    }

    @Test
    fun straightLineIsOneEighty() {
        assertEquals(180.0, Geometry.angle(Vec3(-1.0, 0.0), Vec3(0.0, 0.0), Vec3(1.0, 0.0)), 1e-9)
    }

    @Test
    fun degenerateSegmentsDoNotBlowUp() {
        assertEquals(180.0, Geometry.angle(Vec3(0.0, 0.0), Vec3(0.0, 0.0), Vec3(1.0, 0.0)))
    }

    @Test
    fun threeDAngleSeesDepthThatTwoDCannot() {
        val a = Vec3(0.0, 0.0, 1.0)
        val b = Vec3(0.0, 0.0, 0.0)
        val c = Vec3(1.0, 0.0, 0.0)
        assertEquals(90.0, Geometry.angle(a, b, c, use3d = true), 1e-9)
        assertEquals(180.0, Geometry.angle(a, b, c, use3d = false), 1e-9) // a collapses onto b in 2D
    }

    @Test
    fun angleFromVerticalUsesImageUp() {
        assertEquals(0.0, Geometry.angleFromVertical(Vec3(0.0, 1.0), Vec3(0.0, 0.0)), 1e-9) // moving up the image
        assertEquals(90.0, Geometry.angleFromVertical(Vec3(0.0, 0.0), Vec3(1.0, 0.0)), 1e-9)
        assertEquals(180.0, Geometry.angleFromVertical(Vec3(0.0, 0.0), Vec3(0.0, 1.0)), 1e-9)
    }

    @Test
    fun normalizeHandlesBothDirectionsAndClamps() {
        assertEquals(0.5, Geometry.normalize(5.0, 0.0, 10.0), 1e-9)
        assertEquals(0.5, Geometry.normalize(5.0, 10.0, 0.0), 1e-9)
        assertEquals(1.0, Geometry.normalize(99.0, 0.0, 10.0))
        assertEquals(0.0, Geometry.normalize(-99.0, 0.0, 10.0))
        assertEquals(0.0, Geometry.normalize(3.0, 4.0, 4.0))
    }

    @Test
    fun derollUndoesPhoneTilt() {
        val world = Motions.standing()
        val upright = Camera().project(world, 0)
        val tilted = Camera(rollDeg = 30.0).project(world, 0)
        val fixed = tilted.derolled(30.0)
        for (j in Joint.BODY) {
            val a = upright.landmark(j)!!
            val b = fixed.landmark(j)!!
            assertTrue(abs(a.x - b.x) < 1e-6 && abs(a.y - b.y) < 1e-6, "$j moved: $a vs $b")
        }
    }

    @Test
    fun frameRequiresAllLandmarks() {
        val ex = runCatching { PoseFrame(0, emptyList()) }.exceptionOrNull()
        assertNotNull(ex)
    }

    @Test
    fun missingJointsAreNullAndInvisible() {
        val f = PoseFrame.empty(0)
        assertNull(f.pos(Joint.NOSE))
        assertTrue(!f.isVisible(Joint.NOSE))
    }

    @Test
    fun mirroringFlipsX() {
        val f = Camera().project(Motions.standing(), 0)
        val m = f.mirrored()
        assertEquals(1.0 - f.landmark(Joint.NOSE)!!.x, m.landmark(Joint.NOSE)!!.x, 1e-9)
    }

    @Test
    fun jointIndicesMatchMlKit() {
        assertEquals(0, Joint.NOSE.index)
        assertEquals(11, Joint.LEFT_SHOULDER.index)
        assertEquals(24, Joint.RIGHT_HIP.index)
        assertEquals(32, Joint.RIGHT_FOOT_INDEX.index)
        assertEquals(Joint.COUNT, Joint.entries.size)
        for (j in Joint.entries) assertEquals(j, Joint.fromIndex(j.index))
    }

    @Test
    fun oneEuroKillsJitterWhenStill() {
        val rnd = Random(7)
        val filter = OneEuroFilter()
        val raw = List(300) { 0.5 + (rnd.nextDouble() - 0.5) * 0.02 }
        val out = raw.mapIndexed { i, v -> filter.filter(v, i * 33L) }
        fun std(xs: List<Double>) = xs.drop(60).let { v ->
            val mean = v.average()
            kotlin.math.sqrt(v.sumOf { (it - mean) * (it - mean) } / v.size)
        }
        assertTrue(std(out) < std(raw) * 0.5, "smoothed ${std(out)} vs raw ${std(raw)}")
    }

    @Test
    fun oneEuroFollowsFastMotionWithLittleLag() {
        val filter = OneEuroFilter()
        var lag = 0.0
        var n = 0
        for (i in 0 until 300) {
            val t = i * 33L
            val x = 0.5 + 0.2 * sin(2 * Math.PI * 0.5 * t / 1000.0)
            val y = filter.filter(x, t)
            if (i > 60) {
                lag += abs(y - x)
                n++
            }
        }
        assertTrue(lag / n < 0.03, "mean tracking error ${lag / n}")
    }

    @Test
    fun smootherResetsJointAfterDropout() {
        val smoother = PoseSmoother(dropoutMs = 400)
        val a = Camera().project(Motions.standing(), 0)
        smoother.smooth(a)
        val shifted = Camera(centerX = 0.8).project(Motions.standing(), 2_000)
        val out = smoother.smooth(shifted)
        // After a 2 s gap the filter must snap to the new location, not blend with the old one.
        assertEquals(shifted.landmark(Joint.NOSE)!!.x, out.landmark(Joint.NOSE)!!.x, 1e-9)
    }

    @Test
    fun smoothingKeepsLandmarksNullWhenMissing() {
        val smoother = PoseSmoother()
        val out = smoother.smooth(PoseFrame.empty(0))
        assertTrue(out.landmarks.all { it == null })
    }

    @Test
    fun noiseIsDeterministicPerSeed() {
        val a = Sequences.frames(500, Camera(), Noise(0.01, 3)) { Motions.standing() }
        val b = Sequences.frames(500, Camera(), Noise(0.01, 3)) { Motions.standing() }
        assertEquals(a.first().landmark(Joint.NOSE), b.first().landmark(Joint.NOSE))
    }
}
