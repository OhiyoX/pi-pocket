package id.harness.pipocket

import id.harness.pipocket.ui.compactionDetail
import id.harness.pipocket.ui.compactionToggleHint
import id.harness.pipocket.ui.hasCompactionSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「压缩上下文」卡片的文案（对齐 pi 的 `compaction-summary-message.ts`）。
 *
 * pi 那张卡是两态的：折叠时 `Compacted from 18,240 tokens (ctrl+o to expand)`，
 * 展开时是摘要正文。app 之前只有 `tokens: N → M`，摘要看不到 —— 这里把口径钉住。
 */
class CompactionTest {

    // 用户 2026-10-23：「从 … 压缩而来，现在约 …，省 …」太啰嗦 ⇒ 只留两头数字 + 百分比。
    @Test
    fun `折叠态只给两头数字和百分比`() {
        assertEquals("18k → 4.1k tokens（省 78%）", compactionDetail(18_240, 4_100))
    }

    @Test
    fun `不到一千的 token 不缩写`() {
        assertEquals("824 → 300 tokens（省 64%）", compactionDetail(824, 300))
    }

    @Test
    fun `压缩后没变小就不报百分比`() {
        // pi 的 estimatedTokensAfter 是估算，偶尔会比 before 大一点（压缩本身也要花 token）
        val detail = compactionDetail(5_000, 5_200)
        assertFalse("不该出现省 -4% 这种怪话：$detail", detail.contains("省"))
        assertTrue(detail, detail.startsWith("5.0k → 5.2k tokens"))
    }

    @Test
    fun `只有 before 也给一句话`() {
        // 老桥/旧会话可能不带 estimatedTokensAfter
        assertEquals("12k tokens 压缩", compactionDetail(12_000, 0))
    }

    @Test
    fun `没有 before 就不编造`() {
        assertEquals("", compactionDetail(0, 0))
        assertEquals("", compactionDetail(0, 999))
    }

    @Test
    fun `文案里不出现压缩而来这种绕话`() {
        val detail = compactionDetail(18_240, 4_100)
        assertFalse("别啰嗦：$detail", detail.contains("压缩而来"))
        assertFalse("别啰嗦：$detail", detail.contains("现在约"))
        assertTrue("一行看完：$detail", detail.length <= 32)
    }

    @Test
    fun `省得多的时候百分比是整数`() {
        // 82,505 → 780：省 99.1% ⇒ 显示 99%（不能出现 99.055%）
        val detail = compactionDetail(82_505, 780)
        assertTrue(detail, detail.endsWith("（省 99%）"))
    }

    @Test
    fun `有摘要才给展开入口`() {
        assertTrue(hasCompactionSummary("## 摘要\n…"))
        assertFalse(hasCompactionSummary(""))
        assertFalse(hasCompactionSummary("   "))
        assertFalse(hasCompactionSummary(null))
    }

    @Test
    fun `展开提示随状态变`() {
        assertEquals("点开看摘要", compactionToggleHint(expanded = false, hasSummary = true))
        assertEquals("收起摘要", compactionToggleHint(expanded = true, hasSummary = true))
        assertNull(compactionToggleHint(expanded = false, hasSummary = false))
    }
}
