package id.harness.pipocket

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import id.harness.pipocket.ui.ImageViewerMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 看图器的纯数学：缩放/平移钳制、以点击点为锚的缩放、命中测试、页码归一。
 * 这些是「放大后能拖到边缘」「点黑底才关」两条口径的实现基础。
 */
class ImageViewerMathTest {

    private fun assertNear(expected: Float, actual: Float) = assertTrue("expected=$expected actual=$actual", kotlin.math.abs(expected - actual) < 0.01f)

    @Test
    fun fitSizeKeepsAspectAndFitsInsideViewport() {
        val wide = ImageViewerMath.fitSize(Size(4000f, 2000f), Size(1000f, 1000f))
        assertNear(1000f, wide.width)
        assertNear(500f, wide.height)

        val tall = ImageViewerMath.fitSize(Size(1000f, 2000f), Size(1000f, 1000f))
        assertNear(500f, tall.width)
        assertNear(1000f, tall.height)

        val degenerate = ImageViewerMath.fitSize(Size.Zero, Size(1000f, 1000f))
        assertNear(0f, degenerate.width)
        assertNear(0f, degenerate.height)
    }

    @Test
    fun maxPanIsZeroAtFitScaleAndGrowsWhenZoomed() {
        val display = Size(1000f, 500f)
        val viewport = Size(1000f, 1000f)

        val atFit = ImageViewerMath.maxPan(display, viewport, 1f)
        assertNear(0f, atFit.x)
        assertNear(0f, atFit.y)

        val zoomed = ImageViewerMath.maxPan(display, viewport, 2f)
        assertNear(500f, zoomed.x)   // (1000*2 - 1000) / 2
        assertNear(0f, zoomed.y)     // 高度仍小于视口，不能上下拖出空白
    }

    @Test
    fun clampPanKeepsImageInsideViewport() {
        val limit = Offset(500f, 0f)
        val clamped = ImageViewerMath.clampPan(Offset(900f, 40f), limit)
        assertNear(500f, clamped.x)
        assertNear(0f, clamped.y)
    }

    @Test
    fun zoomAroundKeepsPointUnderFinger() {
        // 手指在中心右侧 100px 处双击：内容该点应停在原地
        val focal = Offset(100f, 0f)
        val newOffset = ImageViewerMath.zoomAround(Offset.Zero, focal, 1f, 2f)
        assertNear(-100f, newOffset.x)

        // 一般化验证：focal 下的内容坐标在缩放前后一致
        val offsetBefore = Offset(30f, -20f)
        val scaleBefore = 1.5f
        val scaleAfter = 3f
        val after = ImageViewerMath.zoomAround(offsetBefore, focal, scaleBefore, scaleAfter)
        val contentBefore = Offset((focal.x - offsetBefore.x) / scaleBefore, (focal.y - offsetBefore.y) / scaleBefore)
        val contentAfter = Offset((focal.x - after.x) / scaleAfter, (focal.y - after.y) / scaleAfter)
        assertNear(contentBefore.x, contentAfter.x)
        assertNear(contentBefore.y, contentAfter.y)
    }

    @Test
    fun doubleTapTogglesBetweenFitAndDoubleTapScale() {
        assertNear(ImageViewerMath.DOUBLE_TAP_SCALE, ImageViewerMath.doubleTapTarget(1f))
        // 用户 2026-10-24「缩小也要支持」：MIN_SCALE 变成可缩到的下限(0.25)，双击的目标永远是 1×（FIT）
        assertNear(ImageViewerMath.FIT_SCALE, ImageViewerMath.doubleTapTarget(ImageViewerMath.DOUBLE_TAP_SCALE))
        assertNear(ImageViewerMath.FIT_SCALE, ImageViewerMath.doubleTapTarget(1.4f))
        assertNear(ImageViewerMath.FIT_SCALE, ImageViewerMath.doubleTapTarget(0.4f))
    }

    @Test
    fun hitTestDistinguishesImageFromBlackBackground() {
        // 小图：竖屏里上下左右都是黑边，方便断言
        val viewport = Size(1000f, 1000f)
        val display = Size(400f, 200f)

        assertTrue(ImageViewerMath.hitTestImage(Offset(500f, 500f), viewport, display, 1f, Offset.Zero))
        assertFalse(ImageViewerMath.hitTestImage(Offset(20f, 20f), viewport, display, 1f, Offset.Zero))

        // 放大到 2×：命中区变大，但仍不是整个视口
        assertTrue(ImageViewerMath.hitTestImage(Offset(150f, 500f), viewport, display, 2f, Offset.Zero))
        assertFalse(ImageViewerMath.hitTestImage(Offset(50f, 500f), viewport, display, 2f, Offset.Zero))

        // 再向右平移 200px：原位置反而变成黑底
        assertFalse(ImageViewerMath.hitTestImage(Offset(250f, 500f), viewport, display, 2f, Offset(200f, 0f)))
        assertTrue(ImageViewerMath.hitTestImage(Offset(500f, 500f), viewport, display, 2f, Offset(200f, 0f)))
    }

    @Test
    fun pageIndexIsAlwaysInRange() {
        assertEquals(0, ImageViewerMath.pageOf(-3, 3))
        assertEquals(2, ImageViewerMath.pageOf(9, 3))
        assertEquals(1, ImageViewerMath.pageOf(1, 3))
        assertEquals(0, ImageViewerMath.pageOf(1, 0))
    }
}
