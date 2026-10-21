package app.gymquest.music

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import app.gymquest.core.music.BpmEstimator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteOrder
import kotlin.math.max

/**
 * Works out a song's tempo on the phone: decodes about 40 seconds from the middle of the file to raw
 * audio, shrinks it to a low sample rate (a beat does not need hi-fi) and hands it to the tempo estimator.
 */
object BpmAnalyzer {
    private const val SECONDS = 40
    private const val TARGET_RATE = 11_025

    suspend fun analyze(context: Context, track: LocalTrack): Double? = withContext(Dispatchers.Default) {
        runCatching { decodeAndEstimate(context, track) }.getOrNull()
    }

    private fun decodeAndEstimate(context: Context, track: LocalTrack): Double? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, track.uri, null)
            val index = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            extractor.selectTrack(index)
            val format = extractor.getTrackFormat(index)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            extractor.seekTo(max(0L, track.durationMs / 2 - SECONDS * 500L) * 1000L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val pcm = ArrayList<Double>(TARGET_RATE * SECONDS)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var accumulator = 0.0
            var accumulated = 0

            while (!outputDone && pcm.size < TARGET_RATE * SECONDS) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val buffer = codec.getInputBuffer(inIndex) ?: continue
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val out = codec.outputFormat
                        channels = out.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        rate = out.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    }
                    outIndex >= 0 -> {
                        val buffer = codec.getOutputBuffer(outIndex)
                        if (buffer != null && info.size > 0) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            val shorts = buffer.order(ByteOrder.nativeOrder()).asShortBuffer()
                            val factor = max(1, rate / TARGET_RATE)
                            while (shorts.remaining() >= channels) {
                                var mono = 0.0
                                for (ch in 0 until channels) mono += shorts.get()
                                accumulator += mono / channels / 32768.0
                                accumulated++
                                if (accumulated == factor) {
                                    pcm += accumulator / factor
                                    accumulator = 0.0
                                    accumulated = 0
                                }
                            }
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
            val outRate = rate / max(1, rate / TARGET_RATE)
            return BpmEstimator.fromPcm(pcm.toDoubleArray(), outRate)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }
}
