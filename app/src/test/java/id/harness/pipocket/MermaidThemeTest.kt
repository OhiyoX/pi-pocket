package id.harness.pipocket

import id.harness.pipocket.ui.mermaidHtml
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * mermaid 宿主页的回归点（2026-10-22 真机截图反复暴露）：
 *
 * 1. **主题**：以前写死 `theme:'dark'`，浅色模式下整张图是黑底块；
 * 2. **尺寸**：以前报「高 / 视口宽」这个**跟视口宽相关**的比例，内联量到的值拿到全屏就不成立
 *    （全屏上下对称被切），宽图被缩到容器宽还会把字号一起缩小（「字体大小不一致」）。
 *    现在只报**自然尺寸**（viewBox 宽高，与视口无关），原生按自然尺寸摆盒子。
 */
class MermaidThemeTest {

    private val code = "flowchart TD\n  A[手机 App] --> B[桥]\n"

    @Test
    fun lightPageUsesLightTheme() {
        val html = mermaidHtml(code, light = true)
        assertTrue("浅色必须声明 LIGHT", html.contains("var LIGHT = true"))
        assertTrue("浅色用 base 主题 + themeVariables", html.contains("theme: LIGHT ? 'base' : 'dark'"))
        assertTrue("浅色主题变量要在页里", html.contains("LIGHT_VARS"))
    }

    @Test
    fun darkPageKeepsDarkTheme() {
        val html = mermaidHtml(code, light = false)
        assertTrue(html.contains("var LIGHT = false"))
        assertFalse("深色不该带浅色变量", html.contains("primaryColor: '#F0EEF9'"))
    }

    @Test
    fun naturalSizeComesFromViewBox() {
        val html = mermaidHtml(code, light = true)
        // 尺寸只能来自 viewBox（mermaid 布局的真实宽高），**不许**再拿视口宽做换算
        assertTrue(html.contains("var nw = vb.width, nh = vb.height"))
        assertTrue(html.contains("PiPocket.reportSize(w, h, window.devicePixelRatio || 0, vw)"))
        assertFalse("不许再报「高/视口宽」的比例", html.contains("reportRatio"))
        assertTrue("量完要拉回顶部（内部滚动会把图拖切）", html.contains("window.scrollTo(0, 0)"))
        assertTrue("实测高只往上取", html.contains("if (measured > nh) nh = measured"))
        assertFalse("别给 html/body 定 height:100%（scrollHeight 会被视口撑高）", Regex("height:100%\\s*[;}]").containsMatchIn(html))
        assertFalse("不许再用视口宽当分母算高", html.contains("/ vw"))
    }

    @Test
    fun widthIsWrittenAsNaturalNotViewport() {
        val html = mermaidHtml(code, light = true)
        // 回归：曾用 width:100% !important / max-width:100% !important 顶掉 mermaid 的自然宽上限
        // → 整张图被拉到屏宽、字变大（真机两轮反馈）
        assertFalse("不许给 svg 加 width:100%", html.contains("svg{width:100%"))
        assertFalse("不许用 !important 覆盖 mermaid 的自然宽", html.contains("max-width:100% !important"))
        // 宽度写死成（缩放后的）自然宽；视口比图窄时靠 max-width:100% 过渡，原生随后把 WebView 拉宽
        assertTrue(html.contains("svg.setAttribute('width', String(w))"))
        assertTrue(html.contains("svg.style.maxWidth = '100%'"))
    }

    @Test
    fun malformedGiantDiagramIsCapped() {
        val html = mermaidHtml(code, light = true)
        // 畸形长图整体等比缩（上限由原生常量传进来），免得把 WebView 撑爆
        assertTrue(html.contains("var MAX_H = 2000.0"))
        assertTrue(html.contains("var s = nh > MAX_H ? MAX_H / nh : 1"))
        val custom = mermaidHtml(code, light = true, maxHeightCss = 1234f)
        assertTrue(custom.contains("var MAX_H = 1234.0"))
    }

    @Test
    fun fontSizeComesFromCallerAndMatchesBodyText() {
        val html = mermaidHtml(code, light = true)
        // 字号由原生按正文字号传进来（bodyMedium 的 sp × fontScale），不再写死
        assertTrue("默认字号 = 调用方传的 14px", html.contains("fontSize: '14px'"))
        assertTrue("flowchart 间距要收紧", html.contains("padding: 10, nodeSpacing: 28, rankSpacing: 34"))
        assertTrue("要限制标签换行宽度", html.contains("wrappingWidth: 180"))
        // 两套主题都要带上 COMMON_VARS（否则深色又回默认 16px）
        assertTrue(html.contains("themeVariables: Object.assign({}, COMMON_VARS"))
        // 调用方可以指定字号
        assertTrue(mermaidHtml(code, light = true, fontSizeCss = "11.5px").contains("fontSize: '11.5px'"))
    }

    @Test
    fun measureIsIdempotentAndRetried() {
        val html = mermaidHtml(code, light = true)
        // 值没变不重复报（多次测量 → 不来回重排原生布局）
        assertTrue(html.contains("if (w === lastW && h === lastH) return"))
        // 字体后到要补量：fonts.ready + 两次定时
        assertTrue(html.contains("document.fonts.ready"))
        assertTrue(html.contains("setTimeout(report, 300)"))
        assertTrue(html.contains("setTimeout(report, 900)"))
    }

    @Test
    fun codeIsHtmlEscaped() {
        val html = mermaidHtml("flowchart TD\n  A[\"a & b < c\"] --> B\n", light = true)
        assertTrue(html.contains("a &amp; b &lt; c"))
    }

    @Test
    fun escapingDoesNotBreakThePage() {
        // 恶意/畸形围栏不许把宿主页提前闭合掉
        val html = mermaidHtml("</pre></div><script>alert(1)</script>", light = false)
        assertFalse(html.contains("<script>alert(1)</script>"))
        assertEquals(1, Regex("PiPocket\\.reportSize").findAll(html).count())
    }
}
