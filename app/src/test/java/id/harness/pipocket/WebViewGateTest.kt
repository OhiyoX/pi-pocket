package id.harness.pipocket

import id.harness.pipocket.ui.WebViewGate
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * WebView 创建闸门（ANR 根因的守门测试）。
 *
 * 背景：一屏多个 mermaid / 工具块时，WebView 的创建发生在组合+布局里（主线程），
 * 冷启动每个几百毫秒到 2 秒 ⇒ 叠加超过 5s 就 ANR。闸门做两件事：**延迟** + **串行**。
 * 这里测的是"串行"那条（延迟是 UI 手感，不在这里测）。
 */
class WebViewGateTest {

    @Test
    fun `同一个时刻只有一个创建者`() = runBlocking {
        val inside = AtomicInteger(0)
        val maxInside = AtomicInteger(0)
        val done = AtomicInteger(0)
        val jobs = (1..6).map {
            launch {
                WebViewGate.awaitSlot()
                val now = inside.incrementAndGet()
                maxInside.updateAndGet { m -> maxOf(m, now) }
                delay(5)
                inside.decrementAndGet()
                done.incrementAndGet()
            }
        }
        jobs.forEach { it.join() }
        assertEquals(6, done.get())
        assertEquals("同时有多个 WebView 在创建 ⇒ 主线程会被叠死", 1, maxInside.get())
    }

    @Test
    fun `相邻创建之间有最小间隔`() = runBlocking {
        val stamps = mutableListOf<Long>()
        repeat(4) {
            WebViewGate.awaitSlot()
            stamps += System.currentTimeMillis()
        }
        val gaps = stamps.zipWithNext { a, b -> b - a }
        // 第一次可能不等（没有上一个），之后三gap都要 ≥ 间隔（给 10ms 抖动余量）
        gaps.drop(1).forEach { gap ->
            assertTrue("间隔 ${gap}ms 小于 ${WebViewGate.INTERVAL_MS}ms", gap >= WebViewGate.INTERVAL_MS - 10)
        }
    }

    @Test
    fun `闸门常量在合理范围`() {
        // 延迟太长 = 图出现太慢（用户以为坏了）；太短 = 快速滚动时白建
        assertTrue(WebViewGate.DELAY_MS in 60..300)
        // 间隔必须大于一帧（16ms），否则"逐帧建"退化成"同帧建"
        assertTrue(WebViewGate.INTERVAL_MS >= 16)
    }
}
