package id.harness.pipocket

import id.harness.pipocket.ui.MermaidSize
import id.harness.pipocket.ui.mermaidFontSizeCss
import id.harness.pipocket.ui.mermaidLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自然尺寸 → 盒子尺寸（纯算法）。
 *
 * 口径（用户 2026-10-22）：**字跟正文一个量级** ⇒ 永不为了塞进容器缩字号；
 * 图比容器宽就**把 WebView 拉宽 + 外面横向滚**；高度必须比自然高多留一点（描边）。
 */
class MermaidLayoutTest {

    @Test
    fun narrowDiagramKeepsNaturalWidth() {
        val l = mermaidLayout(MermaidSize(240f, 600f), viewportDp = 344f)
        assertEquals("窄图不放大到容器宽（否则整张图被拉宽、字变大）", 344f, l.widthDp, 0.01f)
        assertEquals("高 = 自然高 + 描边余量", 604f, l.heightDp, 0.01f)
        assertFalse("装得下就不该有横向滚动", l.scrollable)
    }

    @Test
    fun wideDiagramWidensBoxAndScrollsInsteadOfShrinking() {
        val l = mermaidLayout(MermaidSize(900f, 400f), viewportDp = 344f)
        assertEquals("宽图：WebView 拉到自然宽，字保持 1:1", 900f, l.widthDp, 0.01f)
        assertEquals(404f, l.heightDp, 0.01f)
        assertTrue("宽图要横向滚动才看得到右边", l.scrollable)
    }

    @Test
    fun exactlyViewportWideIsNotScrollable() {
        val l = mermaidLayout(MermaidSize(344f, 200f), viewportDp = 344f)
        assertFalse("刚好一样宽不需要滚（浮点容差）", l.scrollable)
        assertEquals(344f, l.widthDp, 0.01f)
    }

    @Test
    fun unmeasuredUsesPlaceholder() {
        val l = mermaidLayout(null, viewportDp = 344f)
        assertEquals(344f, l.widthDp, 0.01f)
        assertTrue("还没量到先占位（别塌成 0 高）", l.heightDp >= 80f)
        assertFalse(l.scrollable)
        // 畸零值（0/负）也当没量到
        assertFalse(mermaidLayout(MermaidSize(0f, 0f), 344f).scrollable)
        assertTrue(mermaidLayout(MermaidSize(0f, 0f), 344f).heightDp >= 80f)
    }

    @Test
    fun fontSizeFollowsProseAndFontScale() {
        // 正文 fontBodySp = 12（默认），字号缩放 1.0 ⇒ 就是 12px（跟正文一样大）
        assertEquals("12px", mermaidFontSizeCss(12f, 1f))
        assertEquals("14px", mermaidFontSizeCss(14f, 1f))
        // 用户把聊天字号调到 0.85 ⇒ 图里也要 11.9px（否则图里的字比正文大一圈）
        assertEquals("10.2px", mermaidFontSizeCss(12f, 0.85f))
        assertEquals("16px", mermaidFontSizeCss(20f, 0.8f))
    }
}
