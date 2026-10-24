package id.harness.pipocket

import org.junit.Assert.*
import org.junit.Test

class ConnectionSafetyTest {
    private fun read(data: ByteArray, limit: Int, chunk: Int = 65536): ByteArray =
        readBoundedRemote(limit) { offset, buffer, requested ->
            if (offset >= data.size) -1 else {
                val count = minOf(chunk, requested, data.size - offset.toInt())
                data.copyInto(buffer, 0, offset.toInt(), offset.toInt() + count)
                count
            }
        }

    @Test fun `remote reads advance through short chunks without repeating prefix`() {
        val data = ByteArray(140000) { (it % 251).toByte() }
        assertArrayEquals(data, read(data, data.size, 997))
    }

    @Test fun `exact limit and empty file succeed`() {
        assertArrayEquals(byteArrayOf(1, 2), read(byteArrayOf(1, 2), 2, 1))
        assertArrayEquals(byteArrayOf(), read(byteArrayOf(), 0))
    }

    @Test fun `oversized file fails instead of truncating`() {
        assertThrows(IllegalStateException::class.java) { read(ByteArray(3), 2) }
    }

    @Test fun `non progressing remote fails instead of looping`() {
        assertThrows(IllegalStateException::class.java) { readBoundedRemote(12) { _, _, _ -> 0 } }
    }

    @Test fun `stale queue index requeues every removed message`() {
        val plan = planQueueRemoval("steer", 4, listOf("a"), listOf("b"))
        assertNull(plan.selected)
        assertEquals(listOf("a"), plan.steering)
        assertEquals(listOf("b"), plan.followUp)
    }

    @Test fun `remove only selected occurrence preserving order and queue kind`() {
        val plan = planQueueRemoval("followUp", 1, listOf("s"), listOf("a", "a", "b"))
        assertEquals("a", plan.selected)
        assertEquals(listOf("s"), plan.steering)
        assertEquals(listOf("a", "b"), plan.followUp)
    }

    @Test fun `replacement invalidates old event callbacks`() {
        val guard = EventGeneration()
        val first = guard.next()
        assertTrue(guard.isCurrent(first))
        val replacement = guard.next()
        assertFalse(guard.isCurrent(first))
        assertTrue(guard.isCurrent(replacement))
        guard.next()
        assertFalse(guard.isCurrent(replacement))
    }

    @Test fun `queue recovery never merges another sessions outbox`() {
        val old = PendingDraft("old secret task", "/a")
        val plan = planQueueRecovery(old, "/b", "recover b", "draft b")
        assertNull(plan.outboxText)
        assertEquals("recover b\n\ndraft b", plan.composerText)
        assertEquals("old secret task", old.text)
    }

    @Test fun `queue recovery merges only the exact owning session`() {
        val plan = planQueueRecovery(PendingDraft("old a", "/a"), "/a", "recover a", "composer")
        assertEquals("recover a\n\nold a", plan.outboxText)
        assertNull(plan.composerText)
        val unowned = planQueueRecovery(PendingDraft("unowned"), "/a", "recover a", "")
        assertNull(unowned.outboxText)
        assertEquals("recover a", unowned.composerText)
    }
}
