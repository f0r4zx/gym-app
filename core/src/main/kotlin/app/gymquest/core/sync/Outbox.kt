package app.gymquest.core.sync

import kotlin.math.min

/** One change made on this phone that the server has not seen yet. */
data class Operation(
    val id: String,
    /** e.g. "workout.upload", "chat.send", "territory.claim". */
    val kind: String,
    val payload: String,
    val createdMs: Long,
    val attempts: Int = 0,
    val nextAttemptMs: Long = 0,
    /** This operation waits until the one it depends on has been delivered (e.g. a chat message after its image). */
    val dependsOn: String? = null,
)

enum class SyncResult {
    /** Delivered. */
    SUCCESS,

    /** Network or server trouble: try again later. */
    RETRY,

    /** The server refused it for good (validation failed): drop it. */
    REJECTED,
}

object Backoff {
    private const val BASE_MS = 5_000L
    private const val MAX_MS = 60 * 60_000L

    /** 5 s, 10 s, 20 s ... capped at one hour; [jitter] in 0..1 spreads out retries from many phones. */
    fun delayMs(attempts: Int, jitter: Double = 0.0): Long {
        val exp = BASE_MS shl min(attempts, 20)
        val capped = min(exp, MAX_MS)
        return (capped * (1.0 - 0.25 * jitter.coerceIn(0.0, 1.0))).toLong()
    }
}

/**
 * Everything the user does while offline is written here first and delivered when there is a
 * connection. Operations are delivered in the order they were created, an operation waits for the
 * one it depends on, and failures back off instead of hammering the network.
 */
class Outbox(private val maxAttempts: Int = 12) {
    private val queue = LinkedHashMap<String, Operation>()
    private val delivered = HashSet<String>()
    private val dropped = mutableListOf<Operation>()

    val pending: List<Operation> get() = queue.values.toList()
    val droppedOperations: List<Operation> get() = dropped
    val size: Int get() = queue.size

    fun enqueue(op: Operation): Boolean {
        if (op.id in queue || op.id in delivered) return false // idempotent
        queue[op.id] = op
        return true
    }

    /** Operations ready to send now, oldest first. Nothing is returned while offline. */
    fun due(nowMs: Long, online: Boolean, limit: Int = 20): List<Operation> {
        if (!online) return emptyList()
        return queue.values.filter { op ->
            op.nextAttemptMs <= nowMs && (op.dependsOn == null || op.dependsOn in delivered)
        }.take(limit)
    }

    fun report(id: String, result: SyncResult, nowMs: Long, jitter: Double = 0.0) {
        val op = queue[id] ?: return
        when (result) {
            SyncResult.SUCCESS -> {
                queue.remove(id)
                delivered += id
            }
            SyncResult.REJECTED -> {
                queue.remove(id)
                dropped += op
                // Anything waiting on a rejected operation can never be delivered either.
                queue.values.filter { it.dependsOn == id }.forEach { report(it.id, SyncResult.REJECTED, nowMs) }
            }
            SyncResult.RETRY -> {
                val attempts = op.attempts + 1
                if (attempts >= maxAttempts) {
                    queue.remove(id)
                    dropped += op.copy(attempts = attempts)
                } else {
                    queue[id] = op.copy(attempts = attempts, nextAttemptMs = nowMs + Backoff.delayMs(attempts - 1, jitter))
                }
            }
        }
    }

    fun ofKind(kind: String): List<Operation> = queue.values.filter { it.kind == kind }

    /** When to wake up and try again, or null if there is nothing waiting. */
    fun nextWakeMs(): Long? = queue.values.minOfOrNull { it.nextAttemptMs }
}

/**
 * Data types that merge without conflicts, so two phones that edited offline never fight over
 * which version is "right": whatever order the changes arrive in, everyone ends up equal.
 */
data class LwwRegister<T>(val value: T, val timestampMs: Long, val deviceId: String) {
    /** Last write wins; ties are broken by device id so every phone picks the same winner. */
    fun merge(other: LwwRegister<T>): LwwRegister<T> = when {
        other.timestampMs > timestampMs -> other
        other.timestampMs < timestampMs -> this
        other.deviceId > deviceId -> other
        else -> this
    }
}

/** A counter that only grows, with one slot per device (total reps, glasses of water, km...). */
data class GCounter(val perDevice: Map<String, Long> = emptyMap()) {
    val value: Long get() = perDevice.values.sum()

    fun increment(deviceId: String, by: Long = 1): GCounter {
        require(by >= 0) { "Un contador solo crece" }
        return GCounter(perDevice + (deviceId to (perDevice[deviceId] ?: 0) + by))
    }

    fun merge(other: GCounter): GCounter =
        GCounter((perDevice.keys + other.perDevice.keys).associateWith { maxOf(perDevice[it] ?: 0, other.perDevice[it] ?: 0) })
}

/** A set that only grows (achievements unlocked, days trained, items discovered). */
data class GrowSet<T>(val items: Set<T> = emptySet()) {
    fun add(item: T) = GrowSet(items + item)
    fun merge(other: GrowSet<T>) = GrowSet(items + other.items)
}

/** A value that only ever increases (best streak, highest level). */
data class MaxRegister(val value: Long = 0) {
    fun merge(other: MaxRegister) = MaxRegister(maxOf(value, other.value))
}
