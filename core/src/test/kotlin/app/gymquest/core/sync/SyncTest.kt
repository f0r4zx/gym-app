package app.gymquest.core.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun op(id: String, kind: String = "chat.send", t: Long = 0, dependsOn: String? = null) = Operation(id, kind, "{}", t, dependsOn = dependsOn)

class OutboxTest {
    @Test
    fun nothingIsSentWhileOffline() {
        val box = Outbox()
        box.enqueue(op("a"))
        assertTrue(box.due(1_000, online = false).isEmpty())
        assertEquals(listOf("a"), box.due(1_000, online = true).map { it.id })
    }

    @Test
    fun operationsAreDeliveredOldestFirst() {
        val box = Outbox()
        listOf("c", "a", "b").forEachIndexed { i, id -> box.enqueue(op(id, t = i.toLong())) }
        assertEquals(listOf("c", "a", "b"), box.due(0, true).map { it.id })
        assertEquals(2, box.due(0, true, limit = 2).size)
    }

    @Test
    fun duplicatesAreIgnoredEvenAfterDelivery() {
        val box = Outbox()
        assertTrue(box.enqueue(op("a")))
        assertTrue(!box.enqueue(op("a")))
        box.report("a", SyncResult.SUCCESS, 0)
        assertTrue(!box.enqueue(op("a")))
        assertEquals(0, box.size)
    }

    @Test
    fun failuresBackOffExponentiallyAndWaitForTheirTime() {
        val box = Outbox()
        box.enqueue(op("a"))
        box.report("a", SyncResult.RETRY, nowMs = 1_000)
        assertTrue(box.due(1_000, true).isEmpty())
        assertTrue(box.due(1_000 + 4_999, true).isEmpty())
        assertEquals(1, box.due(1_000 + 5_000, true).size)
        box.report("a", SyncResult.RETRY, nowMs = 10_000)
        assertEquals(10_000 + 10_000, box.pending.single().nextAttemptMs)
        assertEquals(20_000L, box.nextWakeMs())
    }

    @Test
    fun backoffIsCappedAndJitterShortensIt() {
        assertEquals(5_000, Backoff.delayMs(0))
        assertEquals(10_000, Backoff.delayMs(1))
        assertEquals(60 * 60_000L, Backoff.delayMs(30))
        assertTrue(Backoff.delayMs(5, jitter = 1.0) < Backoff.delayMs(5, jitter = 0.0))
        assertTrue(Backoff.delayMs(5, jitter = 0.0) / Backoff.delayMs(5, jitter = 1.0).toDouble() <= 1.34)
    }

    @Test
    fun givesUpAfterTooManyAttempts() {
        val box = Outbox(maxAttempts = 3)
        box.enqueue(op("a"))
        repeat(3) { box.report("a", SyncResult.RETRY, it * 1_000_000L) }
        assertEquals(0, box.size)
        assertEquals("a", box.droppedOperations.single().id)
    }

    @Test
    fun rejectedOperationsAreDroppedWithTheirDependents() {
        val box = Outbox()
        box.enqueue(op("image", "chat.image"))
        box.enqueue(op("message", dependsOn = "image"))
        box.enqueue(op("other"))
        assertEquals(listOf("image", "other"), box.due(0, true).map { it.id })
        box.report("image", SyncResult.REJECTED, 0)
        assertEquals(listOf("other"), box.pending.map { it.id })
        assertEquals(setOf("image", "message"), box.droppedOperations.map { it.id }.toSet())
    }

    @Test
    fun dependentsWaitForTheirPrerequisite() {
        val box = Outbox()
        box.enqueue(op("image", "chat.image"))
        box.enqueue(op("message", dependsOn = "image"))
        assertEquals(listOf("image"), box.due(0, true).map { it.id })
        box.report("image", SyncResult.SUCCESS, 0)
        assertEquals(listOf("message"), box.due(0, true).map { it.id })
    }

    @Test
    fun filtersByKind() {
        val box = Outbox()
        box.enqueue(op("1", "territory.claim")); box.enqueue(op("2", "chat.send")); box.enqueue(op("3", "territory.claim"))
        assertEquals(listOf("1", "3"), box.ofKind("territory.claim").map { it.id })
        assertNull(Outbox().nextWakeMs())
    }
}

class MergeableTypesTest {
    @Test
    fun lwwKeepsTheNewestWriteWhateverTheOrder() {
        val a = LwwRegister("light", 100, "phone")
        val b = LwwRegister("dark", 200, "tablet")
        assertEquals(b, a.merge(b))
        assertEquals(b, b.merge(a))
        // Same timestamp: every device picks the same winner.
        val c = LwwRegister("x", 300, "aaa")
        val d = LwwRegister("y", 300, "zzz")
        assertEquals(c.merge(d), d.merge(c))
        assertEquals("y", c.merge(d).value)
    }

    @Test
    fun counterMergeIsCommutativeAssociativeAndIdempotent() {
        val a = GCounter().increment("phone", 5).increment("phone", 2)
        val b = GCounter().increment("tablet", 10)
        val c = GCounter().increment("phone", 3).increment("watch", 1)
        assertEquals(a.merge(b), b.merge(a))
        assertEquals(a.merge(b).merge(c), a.merge(b.merge(c)))
        assertEquals(a, a.merge(a))
        // The phone's stale copy (5) and fresh copy (7) both merged: the larger wins, nothing is double counted.
        assertEquals(7 + 10 + 1, a.merge(b).merge(c).value)
    }

    @Test
    fun countersOnlyGrow() {
        assertFailsWith<IllegalArgumentException> { GCounter().increment("p", -1) }
    }

    @Test
    fun setsAndMaxRegistersMergeByUnion() {
        val a = GrowSet(setOf("first_rep")).add("streak_7")
        val b = GrowSet(setOf("streak_7", "boss_first"))
        assertEquals(setOf("first_rep", "streak_7", "boss_first"), a.merge(b).items)
        assertEquals(a.merge(b), b.merge(a))
        assertEquals(12, MaxRegister(7).merge(MaxRegister(12)).value)
        assertEquals(12, MaxRegister(12).merge(MaxRegister(7)).value)
    }
}
