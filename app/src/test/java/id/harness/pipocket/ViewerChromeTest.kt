package id.harness.pipocket

import id.harness.pipocket.ui.FileSearch
import id.harness.pipocket.ui.ImageViewerMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 看图器 / 文件查看器的纯逻辑（用户 2026-10-24：「看图看字都太简陋」重做后的口径）。
 *
 * 引用：
 * - M3 全屏对话框：✕ 是唯一导航（m3.material.io/components/dialogs/guidelines）
 * - PhotoView 手势集：多指/双击缩放、放大后拖动、点图外关闭
 * - sora-editor：行号 gutter、换行开关、搜索定位、行号范围
 */
class ViewerChromeTest {

    // ── 看图器信息行 ────────────────────────────────────────────────

    @Test
    fun `体积估算与人类可读`() {
        // 4 字符 base64 ≈ 3 字节
        assertEquals(3L, ImageViewerMath.approxBytes(4))
        assertEquals(0L, ImageViewerMath.approxBytes(0))
        assertEquals("158 KB", ImageViewerMath.humanBytes(161_925))   // 161925 / 1024 ≈ 158
        assertEquals("1.2 MB", ImageViewerMath.humanBytes(1_258_291))
        assertEquals("", ImageViewerMath.humanBytes(0))
    }

    @Test
    fun `顶部信息行按有则显示的顺序拼`() {
        assertEquals(
            "1440×3168 · 158 KB · png",
            ImageViewerMath.infoLine(1440, 3168, 215_900, "image/png"),
        )
        // 还没解码出尺寸时只显示体积与格式
        assertEquals("158 KB · png", ImageViewerMath.infoLine(0, 0, 215_900, "image/png"))
        // 什么都不知道就空串（不显示一条 "· ·"）
        assertEquals("", ImageViewerMath.infoLine(0, 0, 0, ""))
    }

    @Test
    fun `看图器缩放边界与缩略图跳页`() {
        assertEquals(ImageViewerMath.DOUBLE_TAP_SCALE, ImageViewerMath.doubleTapTarget(1f), 0.001f)
        assertEquals(ImageViewerMath.FIT_SCALE, ImageViewerMath.doubleTapTarget(2.5f), 0.001f)
        // 用户口径「缩小也要支持」：缩小到 0.4× 时双击回 1×，而不是再放大
        assertEquals(ImageViewerMath.FIT_SCALE, ImageViewerMath.doubleTapTarget(0.4f), 0.001f)
        assertEquals(ImageViewerMath.DOUBLE_TAP_SCALE, ImageViewerMath.doubleTapTarget(1f), 0.001f)
        // 可以缩到 fit 的 1/4
        assertEquals(0.25f, ImageViewerMath.MIN_SCALE, 0.0001f)
        assertTrue(ImageViewerMath.isFit(1f))
        assertTrue(!ImageViewerMath.isFit(0.6f))
        // 页码越界不崩
        assertEquals(2, ImageViewerMath.pageOf(5, 3))
        assertEquals(0, ImageViewerMath.pageOf(0, 0))
    }

    // ── 文件内查找 ─────────────────────────────────────────────────

    @Test
    fun `查找返回命中行号_同一行只算一次`() {
        val text = "alpha\nbeta\nALPHA again alpha\n"
        assertEquals(listOf(1, 3), FileSearch.lineHits(text, "alpha"))
        assertEquals(listOf(1, 2, 3), FileSearch.lineHits(text, "a").filter { it <= 3 }.distinct())
        assertTrue(FileSearch.lineHits(text, "  ").isEmpty())
        assertTrue(FileSearch.lineHits(text, "zzz").isEmpty())
    }

    @Test
    fun `查找是大小写不敏感`() {
        assertEquals(listOf(2), FileSearch.lineHits("one\nTwo\nthree", "TWO"))
    }

    @Test
    fun `行号按总行数右对齐`() {
        assertEquals("1\n2\n3", FileSearch.gutterNumbers(3))
        assertEquals(" 8\n 9\n10", FileSearch.gutterNumbers(10).lines().takeLast(3).joinToString("\n"))
        assertEquals("", FileSearch.gutterNumbers(0))
    }
}
