package id.harness.pipocket

import id.harness.pipocket.ui.cacheHitPercent
import id.harness.pipocket.ui.footerContext
import id.harness.pipocket.ui.footerStats
import id.harness.pipocket.ui.modelWithProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 顶栏副标题 = pi 终端 footer 那几段（`components/footer.ts`）。
 *
 * 规则全部来自源码，不是设计师拍的：
 *  · 统计项为 0 就不出现；
 *  · 缓存命中率只在有缓存读写时给，一位小数；
 *  · 花费三位小数；
 *  · 上下文百分比一位小数，拿不到是 `?`，自动压缩开着跟 `(auto)`。
 */
class FooterTest {

    @Test
    fun `完整统计串按 pi 的顺序和符号`() {
        assertEquals(
            "↑12k ↓4.1k R8.0k W1.2k CH42.3% $0.123",
            footerStats(12_300, 4_100, 8_000, 1_200, 42.34, 0.1234),
        )
    }

    @Test
    fun `为零的项不出现`() {
        // 新会话只有输入：不能出现 ↓0 R0 W0 $0.000 这种噪音
        assertEquals("↑824", footerStats(824, 0, 0, 0, null, 0.0))
    }

    @Test
    fun `没有缓存读写就不报命中率`() {
        // pi: 只有 cacheRead/cacheWrite > 0 才 push CH
        assertEquals("↑100 ↓50", footerStats(100, 50, 0, 0, 55.5, 0.0))
    }

    @Test
    fun `有缓存但命中率未知时也不编造`() {
        assertEquals("R500", footerStats(0, 0, 500, 0, null, 0.0))
    }

    @Test
    fun `花费三位小数`() {
        assertEquals("$1.500", footerStats(0, 0, 0, 0, null, 1.5))
    }

    @Test
    fun `全部为空的会话给空串`() {
        assertEquals("", footerStats(0, 0, 0, 0, null, 0.0))
    }

    @Test
    fun `上下文一段带窗口与自动压缩标记`() {
        assertEquals("13.0%/500k (auto)", footerContext(13.0, 500_000, autoCompaction = true))
        assertEquals("13.0%/500k", footerContext(13.0, 500_000, autoCompaction = false))
    }

    @Test
    fun `拿不到百分比时是问号而不是隐藏`() {
        // 压缩过的会话常见：pi 也是 `?/window`
        assertEquals("?/200k", footerContext(null, 200_000, autoCompaction = false))
    }

    @Test
    fun `没有窗口信息就不给斜杠尾巴`() {
        assertEquals("42.0%", footerContext(42.0, 0, autoCompaction = false))
        assertEquals("?", footerContext(null, 0, autoCompaction = false))
    }

    @Test
    fun `缓存命中率只算最近一条`() {
        // input 100 + read 800 + write 100 ⇒ 800/1000 = 80%
        assertEquals(80.0, cacheHitPercent(100, 800, 100)!!, 0.0001)
    }

    @Test
    fun `没有 prompt 就不给命中率`() {
        assertNull(cacheHitPercent(0, 0, 0))
    }

    @Test
    fun `命中率上限不会超过一百`() {
        // 理论上 read 不该超过 prompt 总量；真出现了也不能显示 120%
        val pct = cacheHitPercent(0, 1_000, 0)!!
        assertTrue("$pct", pct <= 100.0)
    }

    // 用户 2026-10-23：「上下文窗口的模型要带上 provider」——
    // 桥给的 modelDisplay 只有 id（终端 footer 口径），同名跨厂商时看不出来。
    @Test
    fun `模型显示名带 provider`() {
        assertEquals("anthropic/claude-x", modelWithProvider("anthropic", "claude-x"))
        assertEquals("anthropic/claude-x · high", modelWithProvider("anthropic", "claude-x", "high"))
    }

    @Test
    fun `没有 provider 才退回桥给的显示名`() {
        assertEquals("claude-x • high", modelWithProvider("", "claude-x", "high", "claude-x • high"))
        assertEquals("—", modelWithProvider("", "", "", ""))
    }
}
