package id.harness.pipocket

import id.harness.pipocket.ui.REVEAL_BAIL_CHARS
import id.harness.pipocket.ui.REVEAL_MAX_STEP
import id.harness.pipocket.ui.nextReveal
import id.harness.pipocket.ui.revealStep
import id.harness.pipocket.ui.shouldTypewrite
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 流式"逐字显现"的纯逻辑。
 *
 * 关键不是"动画好不好看"，而是**永远追得上真实输出**：
 * 固定速度在手机上必然越跑越落后（模型早答完、界面还在吐字），所以速度按积压量分级。
 */
class TypewriterTest {

    @Test
    fun `几乎没有积压时一个字一个字推`() {
        assertEquals(1, revealStep(1))
        assertEquals(1, revealStep(12))
    }

    @Test
    fun `积压越多推得越快（单调不减）`() {
        val steps = listOf(1, 5, 20, 60, 200, 800).map { revealStep(it) }
        assertEquals(steps.sorted(), steps)
        assertTrue("大积压要快", steps.last() >= 12)
    }

    @Test
    fun `单帧推进有上限`() {
        assertTrue(revealStep(100_000) <= REVEAL_MAX_STEP)
    }

    @Test
    fun `推进不会超过总长度`() {
        assertEquals(10, nextReveal(9, 10))
        assertEquals(10, nextReveal(10, 10))
        // 从头开始：按速度推进（10 字积压 ⇒ 1 字/帧），但绝不能越过总长
        val first = nextReveal(0, 10)
        assertTrue(first in 1..10)
    }

    @Test
    fun `积压过大直接跳到最新`() {
        // 落后太多（比如切后台回来）：动画没有意义，直接显示全文
        val shown = 0
        val total = REVEAL_BAIL_CHARS + 1
        assertEquals(total, nextReveal(shown, total))
    }

    @Test
    fun `已经显示完就不动`() {
        assertEquals(5, nextReveal(5, 5))
        assertEquals(0, nextReveal(0, 0))
    }

    @Test
    fun `从中间开始不会回退`() {
        // 流式中途进会话：从当前长度继续，不重放
        val next = nextReveal(500, 520)
        assertTrue(next >= 500)
        assertTrue(next <= 520)
    }

    @Test
    fun `只有流式才做动画`() {
        assertTrue(shouldTypewrite(true))
        assertFalse(shouldTypewrite(false))
    }

    @Test
    fun `小文本也能在一秒内追平`() {
        // 60fps × 1 字/帧 对 12 字以内的积压足够；这里验证 40 字积压两帧能推进
        var shown = 0
        repeat(2) { shown = nextReveal(shown, 40) }
        assertTrue("两帧应推进至少 4 字，实际 $shown", shown >= 4)
    }
}
