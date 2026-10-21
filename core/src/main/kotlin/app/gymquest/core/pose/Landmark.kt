package app.gymquest.core.pose

/**
 * The 33 body landmarks produced by ML Kit / MediaPipe BlazePose. [index] matches the
 * `PoseLandmark.getLandmarkType()` value so the Android layer can map them directly.
 */
enum class Joint(val index: Int) {
    NOSE(0),
    LEFT_EYE_INNER(1), LEFT_EYE(2), LEFT_EYE_OUTER(3),
    RIGHT_EYE_INNER(4), RIGHT_EYE(5), RIGHT_EYE_OUTER(6),
    LEFT_EAR(7), RIGHT_EAR(8),
    LEFT_MOUTH(9), RIGHT_MOUTH(10),
    LEFT_SHOULDER(11), RIGHT_SHOULDER(12),
    LEFT_ELBOW(13), RIGHT_ELBOW(14),
    LEFT_WRIST(15), RIGHT_WRIST(16),
    LEFT_PINKY(17), RIGHT_PINKY(18),
    LEFT_INDEX(19), RIGHT_INDEX(20),
    LEFT_THUMB(21), RIGHT_THUMB(22),
    LEFT_HIP(23), RIGHT_HIP(24),
    LEFT_KNEE(25), RIGHT_KNEE(26),
    LEFT_ANKLE(27), RIGHT_ANKLE(28),
    LEFT_HEEL(29), RIGHT_HEEL(30),
    LEFT_FOOT_INDEX(31), RIGHT_FOOT_INDEX(32);

    companion object {
        const val COUNT = 33
        private val byIndex = entries.associateBy { it.index }
        fun fromIndex(index: Int): Joint? = byIndex[index]

        /** The joints that matter for counting; hands/face details are ignored for speed. */
        val BODY = listOf(
            NOSE, LEFT_SHOULDER, RIGHT_SHOULDER, LEFT_ELBOW, RIGHT_ELBOW, LEFT_WRIST, RIGHT_WRIST,
            LEFT_HIP, RIGHT_HIP, LEFT_KNEE, RIGHT_KNEE, LEFT_ANKLE, RIGHT_ANKLE,
        )

        /** Pairs drawn as the skeleton overlay in the UI. */
        val BONES: List<Pair<Joint, Joint>> = listOf(
            LEFT_SHOULDER to RIGHT_SHOULDER,
            LEFT_SHOULDER to LEFT_ELBOW, LEFT_ELBOW to LEFT_WRIST,
            RIGHT_SHOULDER to RIGHT_ELBOW, RIGHT_ELBOW to RIGHT_WRIST,
            LEFT_SHOULDER to LEFT_HIP, RIGHT_SHOULDER to RIGHT_HIP, LEFT_HIP to RIGHT_HIP,
            LEFT_HIP to LEFT_KNEE, LEFT_KNEE to LEFT_ANKLE,
            RIGHT_HIP to RIGHT_KNEE, RIGHT_KNEE to RIGHT_ANKLE,
            LEFT_ANKLE to LEFT_FOOT_INDEX, RIGHT_ANKLE to RIGHT_FOOT_INDEX,
        )
    }
}

/**
 * One landmark in normalised image coordinates: x/y in 0..1 (x by width, y by height, y grows
 * downwards), z on the same scale as x (smaller = closer to the camera) and [likelihood] in 0..1.
 */
data class Landmark(val x: Double, val y: Double, val z: Double = 0.0, val likelihood: Double = 1.0)

/**
 * A single pose estimate. [aspect] is image width / height of the *upright* image, so that
 * angles can be computed in a uniform (non-stretched) coordinate system via [pos].
 */
class PoseFrame(
    val timestampMs: Long,
    val landmarks: List<Landmark?>,
    val aspect: Double = 9.0 / 16.0,
) {
    init {
        require(landmarks.size == Joint.COUNT) { "A pose needs ${Joint.COUNT} landmarks, got ${landmarks.size}" }
    }

    fun landmark(j: Joint): Landmark? = landmarks[j.index]

    fun likelihood(j: Joint): Double = landmarks[j.index]?.likelihood ?: 0.0

    fun isVisible(j: Joint, min: Double = MIN_LIKELIHOOD): Boolean = likelihood(j) >= min

    /** Position in uniform units (1.0 = image height). Null when the landmark is missing. */
    fun pos(j: Joint): Vec3? {
        val l = landmarks[j.index] ?: return null
        return Vec3(l.x * aspect, l.y, l.z * aspect)
    }

    /** Like [pos] but only if the landmark is visible enough. */
    fun posIfVisible(j: Joint, min: Double = MIN_LIKELIHOOD): Vec3? = if (isVisible(j, min)) pos(j) else null

    fun withTimestamp(ts: Long) = PoseFrame(ts, landmarks, aspect)

    /** Applies [transform] to every landmark (used by smoothing and de-rolling). */
    fun mapLandmarks(transform: (Joint, Landmark) -> Landmark): PoseFrame {
        val out = ArrayList<Landmark?>(Joint.COUNT)
        for (j in Joint.entries) out.add(landmarks[j.index]?.let { transform(j, it) })
        return PoseFrame(timestampMs, out, aspect)
    }

    /** Horizontally mirrors the pose (front-camera selfie view). */
    fun mirrored(): PoseFrame = mapLandmarks { _, l -> l.copy(x = 1.0 - l.x) }

    companion object {
        const val MIN_LIKELIHOOD = 0.5

        fun empty(timestampMs: Long, aspect: Double = 9.0 / 16.0) =
            PoseFrame(timestampMs, List(Joint.COUNT) { null }, aspect)
    }
}
