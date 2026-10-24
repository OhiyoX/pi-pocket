package id.harness.pipocket

import id.harness.pipocket.ModelRef
import id.harness.pipocket.ui.filterModels
import id.harness.pipocket.ui.isCurrentModel
import id.harness.pipocket.ui.neighborModel
import id.harness.pipocket.ui.sameModel
import id.harness.pipocket.ui.modelSearchEmptyHint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 模型选择器：搜索 + "当前"标记。
 *
 * 两个都是用户报过的问题：
 *  · 上百条模型没有搜索 ⇒ 只能瞎翻；
 *  · "当前"只比 model id ⇒ 同一个 id 在多个 provider 下会给好几行同时打"当前"。
 */
class ModelPickerTest {

    private val models = listOf(
        ModelRef("anthropic", "claude-sonnet-4-5", "Claude Sonnet 4.5"),
        ModelRef("anthropic", "claude-haiku-4", "Claude Haiku 4"),
        ModelRef("openai", "gpt-5", "GPT-5"),
        ModelRef("openai", "gpt-5-mini", "GPT-5 mini"),
        ModelRef("deepseek", "deepseek-v4.1-flash", "DeepSeek V4.1 Flash"),
        ModelRef("moonshot", "kimi-k2", "Kimi K2"),
    )

    @Test
    fun `空查询返回全部`() {
        assertEquals(models.size, filterModels(models, "").size)
        assertEquals(models.size, filterModels(models, "   ").size)
    }

    @Test
    fun `按型号片段过滤`() {
        val hit = filterModels(models, "gpt")
        assertEquals(listOf("gpt-5", "gpt-5-mini"), hit.map { it.model })
    }

    @Test
    fun `按厂商过滤`() {
        assertEquals(2, filterModels(models, "anthropic").size)
        assertEquals(listOf("kimi-k2"), filterModels(models, "moonshot").map { it.model })
    }

    @Test
    fun `空格分词要求每个词都命中`() {
        // "gpt mini" 应该只命中 gpt-5-mini（两个词都在 provider/model 里）
        assertEquals(listOf("gpt-5-mini"), filterModels(models, "gpt mini").map { it.model })
        // 拆开也要能命中连写：`deep seek` → deepseek
        assertEquals(listOf("deepseek-v4.1-flash"), filterModels(models, "deep seek").map { it.model })
    }

    @Test
    fun `大小写与首尾空白都不影响`() {
        assertEquals(2, filterModels(models, "  GPT  ").size)
        assertEquals(2, filterModels(models, "Anthropic").size)
    }

    @Test
    fun `搜不到就是空`() {
        assertTrue(filterModels(models, "qwen").isEmpty())
    }

    @Test
    fun `当前模型要求 provider 也对上`() {
        val flash = models.first { it.model == "deepseek-v4.1-flash" }
        assertTrue(isCurrentModel(flash, "deepseek-v4.1-flash", "deepseek"))
        // 同名但 provider 不同 ⇒ 不是当前（这正是以前会重复打标的情况）
        assertFalse(isCurrentModel(flash, "deepseek-v4.1-flash", "openai"))
    }

    @Test
    fun `不知道 provider 时退化成只比 id`() {
        val gpt = models.first { it.model == "gpt-5" }
        assertTrue(isCurrentModel(gpt, "gpt-5", ""))
        assertFalse(isCurrentModel(gpt, "gpt-5-mini", ""))
    }

    @Test
    fun `空提示区分三种情况`() {
        assertEquals("正在读取模型列表…", modelSearchEmptyHint(0, ""))
        assertEquals("没有可用模型", modelSearchEmptyHint(5, "  "))
        assertEquals("没有匹配「qwen」的模型", modelSearchEmptyHint(5, " qwen "))
    }

    // ── 切换模型要"先选、再确认"（用户 2026-10-23） ─────────────────────
    // 快切按钮不再直接生效，只把相邻那条填成待选项，所以"相邻是谁"必须钉住。

    private val quickModels = listOf(
        ModelRef("anthropic", "claude-a", "Claude A"),
        ModelRef("anthropic", "claude-b", "Claude B"),
        ModelRef("openai", "gpt-c", "GPT C"),
    )

    @Test
    fun `下一个模型是列表里的后一条`() {
        val next = neighborModel(quickModels, "claude-a", "anthropic", backward = false)
        assertEquals("claude-b", next?.model)
    }

    @Test
    fun `上一个模型是列表里的前一条`() {
        val prev = neighborModel(quickModels, "claude-b", "anthropic", backward = true)
        assertEquals("claude-a", prev?.model)
    }

    @Test
    fun `快切到头会绕回去`() {
        assertEquals("claude-a", neighborModel(quickModels, "gpt-c", "openai", backward = false)?.model)
        assertEquals("gpt-c", neighborModel(quickModels, "claude-a", "anthropic", backward = true)?.model)
    }

    @Test
    fun `当前模型不在列表里时退回第一条或最后一条`() {
        assertEquals("claude-a", neighborModel(quickModels, "gone", "gone", backward = false)?.model)
        assertEquals("gpt-c", neighborModel(quickModels, "gone", "gone", backward = true)?.model)
        assertNull(neighborModel(emptyList(), "x", "y", backward = false))
    }

    @Test
    fun `同一个 id 不同厂商不算同一条`() {
        assertTrue(sameModel(ModelRef("a", "m", ""), ModelRef("a", "m", "")))
        assertFalse(sameModel(ModelRef("a", "m", ""), ModelRef("b", "m", "")))
        assertFalse(sameModel(null, ModelRef("a", "m", "")))
    }
}
