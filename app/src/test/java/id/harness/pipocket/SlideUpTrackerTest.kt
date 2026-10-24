package id.harness.pipocket

import id.harness.pipocket.ui.SlideUpTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「按住主操作键上滑」的手势判定（用户 2026-10-22）。
 *
 * 流式中：点一下 = 发送（插到本轮）；按住上滑 = 停止，**上滑后原地松手即触发**；
 * 滑回原位置就恢复（不触发）。`progress` 供界面做跟手拉伸。
 */
class SlideUpTrackerTest {

    private val threshold = 56f

    @Test
    fun shortDragDoesNotArm() {
        val t = SlideUpTracker(threshold)
        t.drag(-20f)
        assertFalse("没到阈值不算上滑", t.armed)
        assertEquals("进度按 20/56 跟手", 20f / 56f, t.progress, 0.001f)
        assertFalse("松手不该触发（那就是普通点击）", t.release())
        assertEquals("松手后复位", 0f, t.progress, 0.001f)
    }

    @Test
    fun upwardDragArmsAtThresholdAndFiresOnRelease() {
        val t = SlideUpTracker(threshold)
        t.drag(-30f)
        assertEquals(30f / 56f, t.progress, 0.001f)
        assertFalse(t.armed)
        t.drag(-30f)
        assertTrue("攒够 60 > 56 该 armed", t.armed)
        assertEquals("过阈值后进度封顶 1", 1f, t.progress, 0.001f)
        assertTrue("原地松手即触发", t.release())
        assertFalse("触发后复位，不能再触发第二次", t.release())
        assertEquals(0f, t.offsetUpPx, 0.001f)
        assertEquals(0f, t.progress, 0.001f)
    }

    @Test
    fun slideBackDisarmsImmediately() {
        // **不加迟滞带**：看到红色/⏹ 就一定触发得了；滑回去就立刻恢复，不会"看着红却不停"
        val t = SlideUpTracker(threshold)
        t.drag(-80f)
        assertTrue(t.armed)
        t.drag(30f) // 回到 50 < 56
        assertFalse("滑回阈值下立刻解除", t.armed)
        assertEquals(50f / 56f, t.progress, 0.001f)
        assertFalse("解除后松手不触发", t.release())
    }

    @Test
    fun backToOriginRestoresEverything() {
        val t = SlideUpTracker(threshold)
        t.drag(-80f)
        t.drag(120f) // 滑回原位还多滑了一截
        assertEquals(0f, t.offsetUpPx, 0.001f)
        assertEquals(0f, t.progress, 0.001f)
        assertFalse(t.armed)
    }

    @Test
    fun downDragNeverCountsAsUp() {
        val t = SlideUpTracker(threshold)
        t.drag(30f) // 先往下
        assertEquals(0f, t.offsetUpPx, 0.001f)
        t.drag(-60f)
        assertTrue(t.armed)
    }

    @Test
    fun resetClearsBoth() {
        val t = SlideUpTracker(threshold)
        t.drag(-100f)
        t.reset()
        assertFalse(t.armed)
        assertEquals(0f, t.offsetUpPx, 0.001f)
        assertEquals(0f, t.progress, 0.001f)
    }
}
