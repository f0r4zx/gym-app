package app.gymquest.tracking

import android.content.Context
import app.gymquest.core.tracking.ActivityRecorder
import app.gymquest.core.tracking.ActivityType
import app.gymquest.core.tracking.Polyline
import app.gymquest.core.tracking.TrackPoint
import java.io.File

/**
 * A small crash-safe log of the fixes of the activity being recorded. If the phone dies, the app is
 * killed or the battery runs out in the middle of a run, the next launch can replay the log and
 * offer to save the activity instead of losing it.
 */
class TrackJournal(context: Context) {
    private val file = File(context.filesDir, FILE_NAME)
    private val pending = StringBuilder()
    private var lastFlushMs = 0L

    fun begin(type: ActivityType, weightKg: Double, startedAtMs: Long) {
        file.writeText("H,${type.name},$weightKg,$startedAtMs\n")
        lastFlushMs = System.currentTimeMillis()
    }

    fun add(p: TrackPoint) {
        pending.append("P,${p.timeMs},${p.lat},${p.lon},${p.altitudeM ?: ""},${p.accuracyM},${p.speedMps ?: ""}\n")
        val now = System.currentTimeMillis()
        if (now - lastFlushMs >= FLUSH_MS) flush(now)
    }

    private fun flush(now: Long = System.currentTimeMillis()) {
        if (pending.isEmpty()) return
        runCatching { file.appendText(pending.toString()) }
        pending.setLength(0)
        lastFlushMs = now
    }

    /** The activity ended normally: nothing to recover. */
    fun clear() {
        pending.setLength(0)
        file.delete()
    }

    companion object {
        private const val FILE_NAME = "active_track.csv"
        private const val FLUSH_MS = 20_000L

        fun exists(context: Context): Boolean = File(context.filesDir, FILE_NAME).exists()

        fun discard(context: Context) {
            File(context.filesDir, FILE_NAME).delete()
        }

        /** Replays an interrupted recording; null if there is nothing usable in the log. */
        fun recover(context: Context): FinishedTrack? = runCatching {
            val lines = File(context.filesDir, FILE_NAME).takeIf { it.exists() }?.readLines() ?: return null
            val header = lines.firstOrNull()?.takeIf { it.startsWith("H,") }?.split(",") ?: return null
            val type = ActivityType.valueOf(header[1])
            val recorder = ActivityRecorder(type, header[2].toDouble())
            val startedAt = header[3].toLong()
            for (line in lines.drop(1)) {
                val f = line.split(",")
                if (f.size < 7 || f[0] != "P") continue
                recorder.add(
                    TrackPoint(
                        timeMs = f[1].toLong(), lat = f[2].toDouble(), lon = f[3].toDouble(),
                        altitudeM = f[4].toDoubleOrNull(), accuracyM = f[5].toDoubleOrNull() ?: 20.0, speedMps = f[6].toDoubleOrNull(),
                    ),
                )
            }
            if (recorder.track.size < 2) return null
            FinishedTrack(recorder.summary(), startedAt, Polyline.encode(Polyline.simplify(recorder.track, 5.0)), recorder.track.toList())
        }.getOrNull()
    }
}
