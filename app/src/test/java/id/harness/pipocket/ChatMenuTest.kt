package id.harness.pipocket

import id.harness.pipocket.ui.ChatAction
import id.harness.pipocket.ui.MENU_HINT_MAX
import id.harness.pipocket.ui.MenuGroup
import id.harness.pipocket.ui.flatRows
import id.harness.pipocket.ui.menuHint
import id.harness.pipocket.ui.moreMenu
import id.harness.pipocket.ui.quickMenu
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话菜单的信息架构守门（用户 2026-10-22：菜单 14 项、什么都找不到）。
 *
 * 这些断言不是"为了覆盖率"，而是几条真实会回归的规则：
 *  - 锚定菜单行数一多就变字墙，且下拉菜单在手机上滚不动 ⇒ 强制上限；
 *  - 拆成两层时最容易**丢入口**（重构时漏掉某一项，肉眼很难发现）⇒ 全量覆盖断言；
 *  - 同一动作出现两次会让"同一个开关两个真相" ⇒ 唯一性断言。
 */
class ChatMenuTest {

    private val quick = quickMenu("mock-flash", "high")
    private val more = moreMenu

    @Test
    fun `锚定菜单不超过 7 行`() {
        val rows = quick.flatRows()
        assertTrue("quick menu 有 ${rows.size} 行，超了就是字墙", rows.size <= 7)
    }

    @Test
    fun `锚定菜单分组数不超过 4`() {
        assertTrue("分组 ${quick.size} 个，太多了", quick.size <= 4)
    }

    @Test
    fun `锚定菜单不带分组标题`() {
        // 锚定菜单里加标题会更乱（分组靠分隔线），标题属于「更多」面板
        quick.forEach { assertNull(it.title) }
    }

    @Test
    fun `更多面板每组都有标题`() {
        more.forEach { group ->
            assertTrue("「更多」里的分组必须有标题", !group.title.isNullOrBlank())
        }
    }

    @Test
    fun `每个动作恰好出现一次`() {
        val all = (quick.flatRows() + more.flatRows()).map { it.action }
        val dup = all.groupingBy { it }.eachCount().filterValues { it > 1 }
        assertTrue("重复的动作：$dup", dup.isEmpty())
    }

    @Test
    fun `重构后没丢任何入口`() {
        val all = (quick.flatRows() + more.flatRows()).map { it.action }.toSet()
        // 拆层前 ⋮ 菜单里的 14 项 + 「更多」本身，一项都不能少
        val expected = setOf(
            ChatAction.Models, ChatAction.Thinking, ChatAction.Tree, ChatAction.Compact,
            ChatAction.CopyLast, ChatAction.Export, ChatAction.More,
            ChatAction.Rename, ChatAction.Reload, ChatAction.AppInject,
            ChatAction.Commands, ChatAction.Shell, ChatAction.Files,
            ChatAction.Behavior, ChatAction.Connection, ChatAction.Settings,
        )
        assertEquals("缺了：${expected - all}", expected, all)
    }

    @Test
    fun `只有更多带下箭头`() {
        val chevrons = (quick.flatRows() + more.flatRows()).filter { it.chevron }.map { it.action }
        assertEquals(listOf(ChatAction.More), chevrons)
    }

    @Test
    fun `标签不为空且不超长`() {
        (quick.flatRows() + more.flatRows()).forEach {
            assertTrue("空标签：${it.action}", it.label.isNotBlank())
            // 菜单行是单行文字，超过 12 个中文字符就会被截断
            assertTrue("「${it.label}」太长（${it.label.length}）", it.label.length <= 12)
        }
    }

    @Test
    fun `当前值给到模型和思考等级两行`() {
        val rows = quick.flatRows().associateBy { it.action }
        assertEquals("mock-flash", rows[ChatAction.Models]?.hint)
        assertEquals("high", rows[ChatAction.Thinking]?.hint)
    }

    @Test
    fun `没拿到状态时不编造当前值`() {
        val rows = quickMenu(null, "").flatRows().associateBy { it.action }
        assertNull(rows[ChatAction.Models]?.hint)
        assertNull(rows[ChatAction.Thinking]?.hint)
    }

    @Test
    fun `长模型名截断到上限`() {
        val long = "anthropic/claude-sonnet-4-5-20250929"
        val hint = menuHint(long)!!
        assertEquals(MENU_HINT_MAX, hint.length)
        assertTrue(hint.endsWith("…"))
    }

    @Test
    fun `短名原样保留 provider 前缀`() {
        // 同名模型在不同 provider 下行为不同，前缀不能丢
        assertEquals("openai/gpt-5", menuHint("openai/gpt-5"))
        assertEquals("high", menuHint("  high  "))
        assertNull(menuHint("   "))
    }

    @Test
    fun `扁平化保留分组顺序`() {
        val groups = listOf(
            MenuGroup("A", listOf(id.harness.pipocket.ui.MenuRow(ChatAction.Models, "一"))),
            MenuGroup("B", listOf(id.harness.pipocket.ui.MenuRow(ChatAction.Shell, "二"))),
        )
        assertEquals(listOf(ChatAction.Models, ChatAction.Shell), groups.flatRows().map { it.action })
    }
}
