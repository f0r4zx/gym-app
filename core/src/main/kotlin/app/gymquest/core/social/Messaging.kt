package app.gymquest.core.social

import app.gymquest.core.platform.Feature
import app.gymquest.core.platform.FeatureGate
import kotlin.math.max
import kotlin.math.min

enum class MessageType { TEXT, STICKER, IMAGE, ROUTE, WORKOUT, SYSTEM }

enum class DeliveryState { QUEUED, SENT, DELIVERED, READ, FAILED }

enum class ChatKind { DIRECT, GROUP, GUILD }

data class Message(
    /** Created on the phone, so a message exists (and shows as "queued") before it reaches the server. */
    val id: String,
    val chatId: String,
    val fromId: String,
    val type: MessageType,
    val text: String? = null,
    /** Sticker id, uploaded image id, route/workout reference. */
    val attachment: String? = null,
    val createdMs: Long,
    val state: DeliveryState = DeliveryState.QUEUED,
)

enum class SendError { EMPTY, TOO_LONG, NEEDS_INTERNET, RATE_LIMITED, BLOCKED, BLOCKED_CONTENT, NO_ATTACHMENT }

sealed interface SendCheck {
    data object Allowed : SendCheck
    data class Denied(val error: SendError) : SendCheck
}

object MessagePolicy {
    const val MAX_TEXT = 1_000
    const val MAX_PER_MINUTE = 20

    private val urlPattern = Regex("""(https?://|www\.)\S+""", RegexOption.IGNORE_CASE)

    /**
     * Whether a message may be sent right now. Text, stickers, routes and workouts can be written
     * offline and are queued; photos need a connection to upload.
     */
    fun check(
        type: MessageType,
        text: String?,
        attachment: String?,
        online: Boolean,
        recentSendTimesMs: List<Long>,
        nowMs: Long,
        relationToRecipient: Relation = Relation.FRIEND,
    ): SendCheck {
        if (relationToRecipient == Relation.BLOCKED) return SendCheck.Denied(SendError.BLOCKED)
        when (type) {
            MessageType.TEXT -> {
                val t = text?.trim().orEmpty()
                if (t.isEmpty()) return SendCheck.Denied(SendError.EMPTY)
                if (t.length > MAX_TEXT) return SendCheck.Denied(SendError.TOO_LONG)
                if (isSpam(t)) return SendCheck.Denied(SendError.BLOCKED_CONTENT)
                // Strangers cannot send links (phishing); friends can.
                if (relationToRecipient == Relation.STRANGER && urlPattern.containsMatchIn(t)) return SendCheck.Denied(SendError.BLOCKED_CONTENT)
            }
            MessageType.IMAGE -> {
                if (attachment.isNullOrBlank()) return SendCheck.Denied(SendError.NO_ATTACHMENT)
                if (!FeatureGate.check(Feature.CHAT_IMAGES, online).enabled) return SendCheck.Denied(SendError.NEEDS_INTERNET)
            }
            MessageType.STICKER, MessageType.ROUTE, MessageType.WORKOUT ->
                if (attachment.isNullOrBlank()) return SendCheck.Denied(SendError.NO_ATTACHMENT)
            MessageType.SYSTEM -> Unit
        }
        val lastMinute = recentSendTimesMs.count { nowMs - it < 60_000 }
        if (lastMinute >= MAX_PER_MINUTE) return SendCheck.Denied(SendError.RATE_LIMITED)
        return SendCheck.Allowed
    }

    /** Catches the obvious: one character repeated many times, or the same word over and over. */
    fun isSpam(text: String): Boolean {
        if (text.length >= 12 && text.toSet().size <= 2) return true
        val words = text.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.size >= 8 && words.toSet().size <= 2) return true
        return false
    }

    /** How a queued message moves forward as connectivity and the server respond. */
    fun advance(m: Message, delivered: Boolean, failedForGood: Boolean = false): Message = when {
        failedForGood -> m.copy(state = DeliveryState.FAILED)
        delivered && m.state == DeliveryState.QUEUED -> m.copy(state = DeliveryState.SENT)
        else -> m
    }
}

/** Sticker rules, matching what chat apps expect. */
object StickerSpec {
    const val SIZE = 512
    const val MAX_BYTES = 100 * 1024
    const val MAX_PER_PACK = 30

    data class Check(val ok: Boolean, val problems: List<String>)

    fun validate(width: Int, height: Int, bytes: Int, hasAlpha: Boolean): Check {
        val problems = mutableListOf<String>()
        if (width != SIZE || height != SIZE) problems += "Debe medir ${SIZE}x$SIZE px."
        if (bytes > MAX_BYTES) problems += "Pesa ${bytes / 1024} KB; el máximo es ${MAX_BYTES / 1024} KB."
        if (!hasAlpha) problems += "Conviene recortar el fondo (transparencia)."
        return Check(problems.none { !it.startsWith("Conviene") }, problems)
    }

    /** A square crop of a photo to turn into a sticker; [focusX]/[focusY] (0..1) is the subject's centre. */
    data class Crop(val left: Int, val top: Int, val size: Int)

    fun cropFor(imageW: Int, imageH: Int, focusX: Double = 0.5, focusY: Double = 0.5): Crop {
        val size = min(imageW, imageH)
        val left = (focusX * imageW - size / 2.0).toInt().coerceIn(0, imageW - size)
        val top = (focusY * imageH - size / 2.0).toInt().coerceIn(0, imageH - size)
        return Crop(left, top, size)
    }
}

/** Shrinks photos before they are sent, so chat stays fast on mobile data. */
object ImagePolicy {
    const val MAX_UPLOAD_BYTES = 8 * 1024 * 1024
    const val TARGET_LONG_EDGE = 1280
    const val JPEG_QUALITY = 80

    data class Plan(val width: Int, val height: Int, val recompress: Boolean, val tooLarge: Boolean)

    fun plan(width: Int, height: Int, bytes: Int): Plan {
        val longEdge = max(width, height)
        val scale = if (longEdge > TARGET_LONG_EDGE) TARGET_LONG_EDGE.toDouble() / longEdge else 1.0
        val newW = (width * scale).toInt().coerceAtLeast(1)
        val newH = (height * scale).toInt().coerceAtLeast(1)
        return Plan(newW, newH, recompress = scale < 1.0 || bytes > 500 * 1024, tooLarge = bytes > MAX_UPLOAD_BYTES * 4)
    }
}
