package id.harness.pipocket

import id.harness.pipocket.ui.AUTO_LAUNCH_AFTER_FAILURES
import id.harness.pipocket.ui.connectionLabel
import id.harness.pipocket.ui.isLocalBridgeUrl
import id.harness.pipocket.ui.reconnectDelayMs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 掉桥自恢复的纯逻辑（用户 2026-10-22：「app 老是掉桥，每次都要点强制拉起 pi」）。
 *
 * 根因不是桥死（84 次拉起日志里每次都写着「已有桥在跑」），而是 app 只补一次 5s 重试就放弃。
 * 这里测的是新策略：指数退避 + 本机桥自动拉起 + 文案分级。
 */
class ReconnectTest {

    @Test
    fun backoffGrowsThenCaps() {
        assertEquals(1_000L, reconnectDelayMs(1))
        assertEquals(2_000L, reconnectDelayMs(2))
        assertEquals(4_000L, reconnectDelayMs(3))
        assertEquals(8_000L, reconnectDelayMs(4))
        assertEquals(15_000L, reconnectDelayMs(5))
        assertEquals(30_000L, reconnectDelayMs(6))
        // 封顶（等 5 分钟没有意义：手机上的网络问题都是分钟级）
        assertEquals(30_000L, reconnectDelayMs(7))
        assertEquals(30_000L, reconnectDelayMs(100))
    }

    @Test
    fun backoffIsMonotonicAndNeverZero() {
        var last = 0L
        for (n in 1..50) {
            val d = reconnectDelayMs(n)
            assertTrue("第 $n 次的等待不能是 0", d > 0)
            assertTrue("第 $n 次的等待不能变短（$last → $d）", d >= last)
            last = d
        }
    }

    @Test
    fun backoffToleratesNonPositiveInput() {
        // 防御：调用方传 0 / 负数也不该抛（否则整个重连循环会挂掉）
        assertEquals(1_000L, reconnectDelayMs(0))
        assertEquals(1_000L, reconnectDelayMs(-3))
    }

    @Test
    fun autoLaunchWaitsForEnoughFailures() {
        // 3 次 ≈ 1+2+4 = 7 秒：够穿过一次抖动，又不让人干等
        assertEquals(3, AUTO_LAUNCH_AFTER_FAILURES)
    }

    @Test
    fun localBridgeUrlsAreRecognized() {
        assertTrue(isLocalBridgeUrl("http://127.0.0.1:8789"))
        assertTrue(isLocalBridgeUrl("http://localhost:8789"))
        assertTrue(isLocalBridgeUrl("http://[::1]:8789"))
        assertTrue(isLocalBridgeUrl("127.0.0.1:8789"))
    }

    @Test
    fun remoteUrlsAreNotAutoLaunched() {
        // 远端机器归用户管：绝不能自动去 spawn 本机 Termux
        assertFalse(isLocalBridgeUrl("http://192.0.2.35:8789"))
        assertFalse(isLocalBridgeUrl("http://phone.local:8789"))
        assertFalse(isLocalBridgeUrl("https://pi.example.com"))
        // 模拟器宿主机别名：不是本机 Termux（自动拉起会弹“没装 Termux”）
        assertFalse(isLocalBridgeUrl("http://10.0.2.2:8799"))
        // 只有端口数字里带 127 不算本机
        assertFalse(isLocalBridgeUrl("http://198.51.100.127:8789"))
    }

    @Test
    fun connectionLabelDistinguishesRecoveringFromDead() {
        // 重连是常态动作：不要显示成"未连接"吓人（用户口径）
        assertEquals("重连中（2） · 本机", connectionLabel("failed", 2, "本机"))
        assertEquals("未连接 · 本机", connectionLabel("failed", 0, "本机"))
        assertEquals("已连接 · phone", connectionLabel("ok", 0, "phone"))
        assertEquals("连接中 · x", connectionLabel("unknown", 0, "x"))
    }
}
