package id.harness.pipocket

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「Pi Provider」表单与解析的纯逻辑测试（不碰设备、不碰桥）。
 *
 * 注意：这里**不写转义字面量**（源码里那种写法容易被写坏），换行一律用 [NL]。
 */
class ProviderFormTest {

    private val NL = Character.toString(10)

    @Test
    fun providerNameFollowsBridgeCharset() {
        assertNull(ProviderForm.nameError("juapi"))
        assertNull(ProviderForm.nameError("openai.gpt-4_1"))
        assertNull(ProviderForm.nameError("a-b_c.9"))
        assertEquals("name 不能为空", ProviderForm.nameError("   "))
        // 桥的规则是 ^[A-Za-z0-9._-]+$：空格 / 斜杠 / 中文 / 冒号都不行
        listOf("has space", "a/b", "中文", "a:b", "a@b").forEach { bad ->
            assertEquals("bad name should be rejected: " + bad, "name 只能包含字母、数字和 . _ -", ProviderForm.nameError(bad))
        }
    }

    @Test
    fun parseModelsAcceptsCommasSpacesAndCjkComma() {
        assertEquals(listOf("a", "b", "c"), ProviderForm.parseModels("a, b ,c"))
        assertEquals(listOf("a", "b"), ProviderForm.parseModels("a，b"))
        assertEquals(
            listOf("deepseek-flash", "gpt-6.1-sol"),
            ProviderForm.parseModels("deepseek-flash" + NL + "gpt-6.1-sol"),
        )
        // 去重保序 + 忽略空白项
        assertEquals(listOf("x", "y"), ProviderForm.parseModels(" x , , x , y ,"))
        assertEquals(emptyList<String>(), ProviderForm.parseModels("   "))
    }

    @Test
    fun formatModelsRoundTrips() {
        val models = listOf("deepseek-flash", "gpt-6.1-sol")
        assertEquals("deepseek-flash, gpt-6.1-sol", ProviderForm.formatModels(models))
        assertEquals(models, ProviderForm.parseModels(ProviderForm.formatModels(models)))
    }

    @Test
    fun tailLinesKeepsLastLinesOnly() {
        val text = (1..30).joinToString(NL) { "line" + it } + NL
        val tail = ProviderForm.tailLines(text, 20)
        val lines = tail.split(NL)
        assertEquals(20, lines.size)
        assertEquals("line11", lines.first())
        assertEquals("line30", lines.last())
        // 空行被丢掉（bootstrap 日志里 === 段落之间那种）
        assertEquals(listOf("a", "b"), ProviderForm.tailLines("a" + NL + NL + "b", 20).split(NL))
        assertEquals("", ProviderForm.tailLines("", 20))
    }

    @Test
    fun providerInfoParsesBridgePayload() {
        val json = JSONObject()
            .put("name", "opencode-go")
            .put("baseUrl", "https://opencode.ai/inference/go/v1")
            .put("api", "openai-completions")
            .put("models", JSONArray(listOf("deepseek-flash", "deepseek-flash-2", "x3", "x4")))
            .put("hasKey", true)
        val info = ProviderInfo.from(json)
        assertTrue(info != null)
        info!!
        assertEquals("opencode-go", info.name)
        assertEquals(4, info.models.size)
        assertTrue(info.hasKey)
        // 列表里的摘要：个数 + 最多 3 个 id（多了省略）
        assertTrue(info.modelsSummary.startsWith("4 个模型 · deepseek-flash"))
        assertTrue(info.modelsSummary.endsWith("…"))
        // 没有 name 的行直接丢掉（坏数据不该把面板搞崩）
        assertNull(ProviderInfo.from(JSONObject()))
    }

    @Test
    fun providerInfoWithoutKeyOrModels() {
        val info = ProviderInfo.from(
            JSONObject().put("name", "juapi").put("models", JSONArray()),
        )!!
        assertFalse(info.hasKey)
        assertEquals("没有模型", info.modelsSummary)
        assertNull(info.authType)
    }
}
