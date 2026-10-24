package id.harness.pipocket

import id.harness.pipocket.ui.showThinkingRing
import id.harness.pipocket.ui.thinkingFamily
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「工作中」指示色跟 thinking 等级走（用户 2026-10-22 口径：跟终端一致）。
 *
 * 映射不是猜的：**逆向** pi 的 `src/modes/interactive/theme/system-theme.ts` 得到
 * （从 `dist/modes/interactive/theme/system-theme.js.map` 的 sourcesContent 还原）——
 * 那里逐档写着 thinkingOff/Minimal/Low/Medium/High/Xhigh/Max 对应的色族。
 */
class ThinkingColorTest {

    @Test
    fun matchesPiSystemThemeTable() {
        // 与 pi 的 system-theme.ts 逐条对齐
        assertEquals("neutral", thinkingFamily("off"))
        assertEquals("slate", thinkingFamily("minimal"))
        assertEquals("blue", thinkingFamily("low"))
        assertEquals("periwinkle", thinkingFamily("medium"))
        assertEquals("violet", thinkingFamily("high"))
        assertEquals("magenta", thinkingFamily("xhigh"))
        assertEquals("red", thinkingFamily("max"))
    }

    @Test
    fun nullAndEmptyAreOff() {
        assertEquals("neutral", thinkingFamily(null))
        assertEquals("neutral", thinkingFamily(""))
        assertEquals("neutral", thinkingFamily("  "))
        assertEquals("neutral", thinkingFamily("none"))
    }

    @Test
    fun caseAndAliasesAreTolerated() {
        assertEquals(thinkingFamily("high"), thinkingFamily("HIGH"))
        assertEquals(thinkingFamily("high"), thinkingFamily(" High "))
        assertEquals("magenta", thinkingFamily("x-high"))
        assertEquals("red", thinkingFamily("maximum"))
        assertEquals("slate", thinkingFamily("min"))
    }

    @Test
    fun sevenDistinctSteps() {
        val steps = listOf("off", "minimal", "low", "medium", "high", "xhigh", "max").map { thinkingFamily(it) }
        assertEquals("七档不许重色（不然强度表达不出来）", steps.size, steps.toSet().size)
    }

    @Test
    fun unknownLevelFallsBackToOff() {
        // pi 的 getThinkingBorderColor() 的 default 分支 = thinkingOff ⇒ 这里照抄
        assertEquals("neutral", thinkingFamily("turbo"))
    }

    @Test
    fun thinkingRingOnlyForNonOffLevels() {
        // 输入区边框 = pi 的 getThinkingBorderColor：off/未知 不画（app 输入区本来无边框），其余要画
        org.junit.Assert.assertFalse(showThinkingRing("off"))
        org.junit.Assert.assertFalse(showThinkingRing(null))
        org.junit.Assert.assertFalse(showThinkingRing(""))
        org.junit.Assert.assertFalse(showThinkingRing("turbo"))
        listOf("minimal", "low", "medium", "high", "xhigh", "max").forEach { level ->
            org.junit.Assert.assertTrue("$level 应该画环", showThinkingRing(level))
        }
    }
}
