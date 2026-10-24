package id.harness.pipocket

import id.harness.pipocket.ui.SlashInput
import id.harness.pipocket.ui.argumentOptions
import id.harness.pipocket.ui.filterArgumentOptions
import id.harness.pipocket.ui.parseSlashInput
import id.harness.pipocket.ui.rankCommands
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 斜杠命令/参数补全的纯逻辑测试（不依赖 Android 运行时）。 */
class SlashArgsTest {

    private val models = listOf(
        ModelRef("opencode-go", "deepseek-v4.1-flash", "opencode-go/deepseek-v4.1-flash"),
        ModelRef("auto-provider", "deepseek-v4.1-flash-auto", "auto-provider/deepseek-v4.1-flash-auto"),
        ModelRef("juapi", "gpt-6", "juapi/gpt-6"),
    )
    private val commands = listOf(
        CommandRef("/model", "切换模型"),
        CommandRef("/mode", "权限模式"),
        CommandRef("/skill:hardware-monitor", "读传感器"),
        CommandRef("/skill:pi-intercom", "跨会话"),
        CommandRef("/notify-test", "发测试通知"),
    )
    private val sessions = listOf(
        SessionInfo("f1.jsonl", "id-1", "/home/u", "第一个", "2026-10-18T00:00:00Z", 1),
        SessionInfo("f2.jsonl", "id-2", "/home/u", null, "2026-10-18T00:00:00Z", 1),
    )

    @Test
    fun modelArgFiltersBySubstring() {
        val hit = filterArgumentOptions("/model", "fl", models, commands, sessions)
        assertEquals(2, hit.size)
        assertTrue(hit.all { it.first.contains("flash") })
        // 全量（空查询）应给所有模型
        assertEquals(3, argumentOptions("/model", models, commands, sessions).size)
    }

    /**
     * 同名 model id 出现在多个 provider 下时，候选值要用 `provider/model` 消歧。
     * 背景（2026-10-21 真机闪退）：`deepseek-flash` 同时被 opencode-go-2 / opencode-go 提供 ⇒
     * 抽屉里两个相同字符串 ① 撞 LazyColumn key（IllegalArgumentException 闪退）
     * ② 即使点下去，pi 也会报 `Model x is ambiguous across providers`。
     */
    @Test
    fun modelArgDisambiguatesDuplicateIds() {
        val dup = listOf(
            ModelRef("opencode-go-2", "deepseek-flash", "deepseek-flash"),
            ModelRef("opencode-go", "deepseek-flash", "deepseek-flash"),
            ModelRef("deepseek", "deepseek-v4-pro", "deepseek-v4-pro"),
        )
        val values = argumentOptions("/model", dup, commands, sessions).map { it.first }
        assertEquals(listOf("opencode-go-2/deepseek-flash", "opencode-go/deepseek-flash", "deepseek-v4-pro"), values)
        // 不能有重复值（否则抽屉 key 会撞）
        assertEquals(values.size, values.distinct().size)
    }

    @Test
    fun modeArgHasAllModes() {
        val modes = argumentOptions("/mode", models, commands, sessions).map { it.first }
        assertEquals(listOf("build", "edit", "plan", "yolo", "guarded"), modes)
    }

    @Test
    fun skillArgComesFromSlashCommands() {
        val skills = argumentOptions("/skill", models, commands, sessions).map { it.first }
        assertEquals(listOf("hardware-monitor", "pi-intercom"), skills)
        assertEquals(listOf("pi-intercom"), filterArgumentOptions("/skill", "inter", models, commands, sessions).map { it.first })
    }

    @Test
    fun resumeArgUsesSessionIdAndNameFallback() {
        val opts = argumentOptions("/resume", models, commands, sessions)
        assertEquals("id-1", opts[0].first)
        assertEquals("第一个", opts[0].second)
        // 没名字时退回 cwd
        assertEquals("/home/u", opts[1].second)
    }

    @Test
    fun unknownCommandGivesNoGuesses() {
        assertTrue(argumentOptions("/compact", models, commands, sessions).isEmpty())
        assertTrue(argumentOptions("/whatever", models, commands, sessions).isEmpty())
    }

    @Test
    fun commandRankingPrefersPrefixThenNameThenDescription() {
        val ranked = rankCommands(commands, "model").map { it.name }
        assertEquals("/model", ranked.first())

        // "通知" 只在说明里出现 → 仍应命中
        assertTrue(rankCommands(commands, "通知").map { it.name }.contains("/notify-test"))
        // 大小写不敏感
        assertTrue(rankCommands(commands, "MODEL").map { it.name }.contains("/model"))
        // 完全不相干 → 空
        assertTrue(rankCommands(commands, "zzz").isEmpty())
    }

    @Test
    fun parseSlashInputSplitsCommandAndArg() {
        assertEquals(SlashInput("/model", "", false), parseSlashInput("/model", false))
        assertEquals(SlashInput("/model", "fl", true), parseSlashInput("/model fl", false))
        // 全角斜杠与全角空格要归一化（中文输入法常见）
        assertEquals(SlashInput("/mode", "ed", true), parseSlashInput("／mode　ed", true))
    }
}
