package id.harness.pipocket

import id.harness.pipocket.ui.htmlVisualHeightDp
import id.harness.pipocket.ui.htmlVisualPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * html-visual 宿主页的两种形态（用户 2026-10-23：「渲染的 html 支持全屏，并支持切换方向」）。
 *
 * 内联：要量高回报（不然卡片高度不对）；全屏：不许再回报高度（视口就是高度），且铺满 + 留内边距。
 * 两种都必须带同一条 CSP —— 全屏不是"放开网络"的理由。
 */
class HtmlVisualPageTest {

    private val fragment = "<div id='chart'>图</div>"

    @Test
    fun inlinePageReportsHeightAndKeepsCsp() {
        val page = htmlVisualPage(fragment)
        assertTrue(page.contains("PiPocket.reportHeight"))
        assertTrue(page.contains("var FULLSCREEN = false;"))
        assertTrue(page.contains("connect-src 'none'"))
        assertTrue(page.contains(fragment))
        // 内联时不铺满视口（高度由内容长）
        assertFalse(page.contains("html, body { height: 100%; }"))
    }

    @Test
    fun fullscreenPageFillsViewportAndSkipsHeightReport() {
        val page = htmlVisualPage(fragment, fullscreen = true)
        assertTrue(page.contains("var FULLSCREEN = true;"))
        assertTrue(page.contains("if (FULLSCREEN) {"))
        assertTrue(page.contains("html, body { height: 100%; }"))
        assertTrue(page.contains("#pi-pocket-root { padding: 12px; box-sizing: border-box; }"))
        // 断网口径一致：全屏也能画，但拿不到外部数据
        assertTrue(page.contains("connect-src 'none'"))
    }

    @Test
    fun inlinePageNeverClipsContent() {
        // 用户 2026-10-23：正文里的 html 底部被截了。口径 = 整段铺开（不设 body 限高、不做嵌套滚动）：
        // 嵌套滚动在聊天流里会被手指当成"滑列表"，用户看到的就是"底部没了"。
        val page = htmlVisualPage(fragment, light = false)
        assertFalse(page.contains("max-height"))
        assertFalse(page.contains("overflow-y: auto"))
        // 图/表是异步长高的：要有 ResizeObserver 再报一次高度，否则下半截永远不显示
        assertTrue(page.contains("new ResizeObserver"))
        assertTrue(page.contains("Math.abs(h - lastH) > 2"))
    }

    @Test
    fun heightUsesBodyScrollHeightNotJustViewport() {
        // 用 documentElement.scrollHeight 量的是"窗口高"，内容比窗口高时量少了 ⇒ 直接截断（踩过）
        val page = htmlVisualPage(fragment)
        assertTrue(page.contains("document.body && document.body.scrollHeight"))
    }

    @Test
    fun lightPageUsesDarkTextAndRunsContrastPass() {
        val page = htmlVisualPage(fragment, light = true)
        // 深色底专用的近白字在浅色下必须换成深字
        assertTrue(page.contains("color: ##2F3437".replace("##", "#")))
        assertTrue(page.contains("#C2C9CC"))          // 表格细线也跟着变深
        assertTrue(page.contains("var LIGHT = true;"))
        assertTrue(page.contains("function fixAll()"))
        assertTrue(page.contains("function fixCharts()"))
    }

    @Test
    fun contrastPassPicksDirectionByBackgroundLuminance() {
        val page = htmlVisualPage(fragment, light = true)
        // 0.179 = 黑字/白字对比度相等的分界点；用 0.35 会把中绿底当成暗底、漏掉压暗（踩过）
        assertTrue(page.contains("bgL > 0.179"))
        assertTrue(page.contains("function bgOf("))
        assertTrue(page.contains("function fit("))
        // 内容已经画出来之后，库内部异常不该把整块退回代码块
        assertTrue(page.contains("if (lastH < 0) PiPocket.reportEmpty();"))
    }

    @Test
    fun darkPageKeepsOriginalPalette() {
        val page = htmlVisualPage(fragment, light = false)
        assertTrue(page.contains("#e6e4e0"))
        assertTrue(page.contains("#4a4a4a"))
        assertTrue(page.contains("var LIGHT = false;"))
    }

    @Test
    fun fragmentIsNotEscapedIntoOblivion() {
        // 片段原样注入（模型给的就是 SVG/HTML），不能被转义成文本
        val page = htmlVisualPage("<svg width='10' height='10'><rect/></svg>")
        assertTrue(page.contains("<svg width='10' height='10'><rect/></svg>"))
    }

    @Test
    fun reportedCssPixelsAreAlreadyDp() {
        // 用户 2026-10-23 两次报"html 不完整"：根因是拿 CSS px 又除了一次 density
        // （模拟器 1.75、手机 4）⇒ 块高度只剩内容的 1/1.75 / 1/4。
        assertEquals(1026, htmlVisualHeightDp(1026, 2400))   // 原样用，不再除 density
        assertEquals(48, htmlVisualHeightDp(0, 2400))        // 没内容时给最小高
        assertEquals(2400, htmlVisualHeightDp(5000, 2400))   // 超过上限才夹
    }
}
