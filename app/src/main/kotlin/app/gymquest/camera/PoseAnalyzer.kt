package app.gymquest.camera

import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import app.gymquest.core.platform.FramePacer
import app.gymquest.core.platform.PoseModel
import app.gymquest.core.pose.Joint
import app.gymquest.core.pose.Landmark
import app.gymquest.core.pose.PoseFrame
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseDetector
import com.google.mlkit.vision.pose.accurate.AccuratePoseDetectorOptions
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import java.util.concurrent.Executor

/**
 * Turns camera frames into [PoseFrame]s using ML Kit's on-device pose model (no internet needed).
 * Frames are skipped to match the quality tier, and the camera only ever hands over the newest
 * picture, so a slow phone drops frames instead of building up lag.
 */
class PoseAnalyzer(
    private val executor: Executor,
    private val pacer: FramePacer,
    private val mirror: Boolean,
    private val model: () -> PoseModel,
    private val onPose: (PoseFrame?, Double) -> Unit,
) : ImageAnalysis.Analyzer {

    private var detector: PoseDetector? = null
    private var detectorModel: PoseModel? = null

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(image: ImageProxy) {
        val started = SystemClock.elapsedRealtime()
        val media = image.image
        if (media == null || !pacer.shouldProcess(started)) {
            image.close()
            return
        }
        val rotation = image.imageInfo.rotationDegrees
        val sideways = rotation % 180 != 0
        val width = if (sideways) image.height else image.width
        val height = if (sideways) image.width else image.height

        try {
            val input = InputImage.fromMediaImage(media, rotation)
            detectorFor(model()).process(input)
                .addOnSuccessListener(executor) { pose ->
                    val took = (SystemClock.elapsedRealtime() - started).toDouble()
                    onPose(toFrame(pose, width, height, SystemClock.elapsedRealtime()), took)
                }
                .addOnFailureListener(executor) { onPose(null, 0.0) }
                .addOnCompleteListener(executor) { image.close() }
        } catch (e: Exception) {
            // A broken detector must never crash the camera; skip this frame.
            image.close()
            onPose(null, 0.0)
        }
    }

    private fun toFrame(pose: Pose, width: Int, height: Int, nowMs: Long): PoseFrame? {
        val all = pose.allPoseLandmarks
        if (all.isEmpty()) return null
        val byType = arrayOfNulls<Landmark>(Joint.COUNT)
        for (p in all) {
            val i = p.landmarkType
            if (i !in 0 until Joint.COUNT) continue
            val pos = p.position
            var x = (pos.x / width).toDouble()
            if (mirror) x = 1.0 - x
            // Depth is not used by the counters (they work from 2D angles), so it is left at zero.
            byType[i] = Landmark(x, (pos.y / height).toDouble(), 0.0, p.inFrameLikelihood.toDouble())
        }
        return PoseFrame(nowMs, byType.toList(), aspect = width.toDouble() / height)
    }

    private fun detectorFor(m: PoseModel): PoseDetector {
        val existing = detector
        if (existing != null && detectorModel == m) return existing
        existing?.close()
        val created = when (m) {
            PoseModel.ACCURATE -> PoseDetection.getClient(
                AccuratePoseDetectorOptions.Builder().setDetectorMode(AccuratePoseDetectorOptions.STREAM_MODE).build(),
            )
            PoseModel.FAST -> PoseDetection.getClient(
                PoseDetectorOptions.Builder().setDetectorMode(PoseDetectorOptions.STREAM_MODE).build(),
            )
        }
        detector = created
        detectorModel = m
        return created
    }

    fun close() {
        detector?.close()
        detector = null
    }
}
