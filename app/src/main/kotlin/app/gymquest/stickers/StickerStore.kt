package app.gymquest.stickers

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.min

/** Stickers made from your own pictures. They are created and stored on the phone, so no internet is needed. */
object StickerStore {
    private const val SIZE = 512

    private fun dir(context: Context) = File(context.filesDir, "stickers").apply { mkdirs() }

    fun list(context: Context): List<File> =
        dir(context).listFiles { f -> f.extension == "webp" }?.sortedByDescending { it.lastModified() } ?: emptyList()

    fun delete(file: File) {
        file.delete()
    }

    /** Crops [uri] to a square, rounds the corners, adds an optional caption and saves it as a small WebP. */
    suspend fun create(context: Context, uri: Uri, caption: String): File? = withContext(Dispatchers.Default) {
        runCatching {
            val source = decode(context, uri) ?: return@runCatching null
            val side = min(source.width, source.height)
            val square = Bitmap.createBitmap(source, (source.width - side) / 2, (source.height - side) / 2, side, side)
            val out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            val rect = RectF(0f, 0f, SIZE.toFloat(), SIZE.toFloat())
            val clip = Path().apply { addRoundRect(rect, 72f, 72f, Path.Direction.CW) }
            canvas.save()
            canvas.clipPath(clip)
            canvas.drawBitmap(Bitmap.createScaledBitmap(square, SIZE, SIZE, true), 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
            canvas.restore()

            val text = caption.trim().take(24)
            if (text.isNotEmpty()) {
                val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE; textSize = 64f; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
                }
                val stroke = Paint(fill).apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 10f }
                // Shrink long captions so they fit inside the sticker.
                while (fill.measureText(text) > SIZE - 48 && fill.textSize > 24f) {
                    fill.textSize -= 4f
                    stroke.textSize = fill.textSize
                }
                canvas.drawText(text, SIZE / 2f, SIZE - 40f, stroke)
                canvas.drawText(text, SIZE / 2f, SIZE - 40f, fill)
            }

            val file = File(dir(context), "sticker_${System.currentTimeMillis()}.webp")
            file.outputStream().use { stream ->
                @Suppress("DEPRECATION")
                val format = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
                out.compress(format, 85, stream)
            }
            file
        }.getOrNull()
    }

    /** Reads the picture at a reduced size so a 50-megapixel photo cannot run the phone out of memory. */
    private fun decode(context: Context, uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= SIZE * 2 && bounds.outHeight / (sample * 2) >= SIZE * 2) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    }
}
