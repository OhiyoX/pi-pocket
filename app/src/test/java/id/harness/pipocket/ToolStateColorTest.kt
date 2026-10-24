package id.harness.pipocket

import id.harness.pipocket.ui.toolStateKind
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 工具卡三态（用户 2026-10-23：「tool call 还会变色，由灰变绿」）。
 *
 * 终端：跑着 = toolPendingBg（灰）、成功 = toolSuccessBg（绿）、失败 = toolErrorBg（红）。
 * 这里钉住优先级：**运行中优先**（一个正在跑但上一次报错的工具，应该显示"在跑"）。
 */
class ToolStateColorTest {

    @Test
    fun `运行中是灰`() {
        assertEquals("pending", toolStateKind(running = true, failed = false))
    }

    @Test
    fun `跑完是绿`() {
        assertEquals("done", toolStateKind(running = false, failed = false))
    }

    @Test
    fun `失败是红`() {
        assertEquals("error", toolStateKind(running = false, failed = true))
    }

    @Test
    fun `运行中优先于失败标记`() {
        assertEquals("pending", toolStateKind(running = true, failed = true))
    }
}
