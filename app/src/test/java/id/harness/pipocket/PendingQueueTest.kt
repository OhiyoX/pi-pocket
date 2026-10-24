package id.harness.pipocket

import id.harness.pipocket.ui.UNKNOWN_PENDING_GRACE_MS
import id.harness.pipocket.ui.unknownPendingCount
import id.harness.pipocket.ui.unknownPendingVisible
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「另有 N 条排队（内容未同步）」的显示条件。
 *
 * 用户 2026-10-23 报的 bug：**steer 已经上屏了，界面还挂着一行"另有 1 条排队"**。
 * 根因是 pi 的 `pendingMessageCount` 滞后于本地队列变化（steer 已取走，计数还是 1）。
 */
class PendingQueueTest {

    @Test
    fun `只在权威计数多于本地文本时才有多余`() {
        assertEquals(0, unknownPendingCount(0, 0))
        assertEquals(0, unknownPendingCount(1, 1))
        assertEquals(0, unknownPendingCount(0, 2)) // 本地比权威多：不算"未同步"
        assertEquals(2, unknownPendingCount(3, 1))
    }

    @Test
    fun `宽限期内不显示`() {
        // steer 刚上屏：unknown=1 但还没等够 ⇒ 不显示（这就是那个 bug 的修法）
        assertFalse(unknownPendingVisible(1, 0))
        assertFalse(unknownPendingVisible(1, UNKNOWN_PENDING_GRACE_MS - 1))
    }

    @Test
    fun `等够了还多出来才显示`() {
        assertTrue(unknownPendingVisible(1, UNKNOWN_PENDING_GRACE_MS))
        assertTrue(unknownPendingVisible(3, UNKNOWN_PENDING_GRACE_MS * 3))
    }

    @Test
    fun `没有多余就永远不显示`() {
        assertFalse(unknownPendingVisible(0, Long.MAX_VALUE))
    }

    @Test
    fun `宽限期在 2 秒以上（要盖住 info 刷新的滞后）`() {
        assertTrue(UNKNOWN_PENDING_GRACE_MS >= 2_000)
    }
}
