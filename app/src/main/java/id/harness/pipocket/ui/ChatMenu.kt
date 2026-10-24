package id.harness.pipocket.ui

/**
 * 会话菜单的信息架构（纯数据，方便单测）。
 *
 * 起因（用户 2026-10-22）：⋮ 菜单一路堆到 **14 项**，一屏全是等长文字，找不到东西。
 * 本项目的信息架构约定（并非 Material 官方数量限制；规范见 docs/UI-DESIGN-GUIDELINES.md）：
 *  1. **锚定菜单 ≤ 7 行**：菜单是从按钮弹出的，行数一多就变成"清单墙"，
 *     扫描成本高于收益；超出部分放**底部面板**（Bottom sheet，可滚动、可分组）。
 *  2. **分组用分隔线，不堆标题**：锚定菜单里加分组标题会显得更乱，只保留视觉分隔。
 *  3. **每行可带当前值**（模型名 / 思考等级）：菜单本身就是"状态 + 入口"，
 *     省掉"点进去才知道现在是什么"。
 *  4. **底部面板必须有分组标题**（会话 / 工具 / 设置）：8 项无标题同样是墙。
 *  5. 低频且**非会话**的东西（连接、注入、Shell）只在面板里，不占锚定菜单。
 *
 * 两个约束由 [ChatMenuTest] 守着：锚定菜单行数上限、以及"每个动作恰好出现一次"
 * （重构菜单时最容易丢掉某个入口，且不容易被肉眼发现）。
 */
enum class ChatAction {
    Models,
    Thinking,
    Tree,
    Compact,
    CopyLast,
    Export,
    More,
    Rename,
    Reload,
    AppInject,
    Commands,
    Shell,
    Files,
    Behavior,
    Connection,
    Settings,
}

/**
 * 一行菜单。
 *
 * @param hint 右侧的当前值（模型名 / 思考等级）。空串或 null = 不显示。
 * @param chevron 有下级（目前只有「更多…」）；画 `>` 而不是把 `…` 写进文案。
 */
data class MenuRow(
    val action: ChatAction,
    val label: String,
    val hint: String? = null,
    val chevron: Boolean = false,
)

/** 一组菜单项；[title] 为 null = 无标题（锚定菜单一律无标题）。 */
data class MenuGroup(val title: String? = null, val rows: List<MenuRow>)

/**
 * 面板里 hint 的长度上限。
 *
 * 14 太短了（用户 2026-10-22：模型名认不出来）——`claude-sonnet…` 和 `claude-sonnet-4-5` 是两个东西。
 * 给到 22：常见模型 id（`claude-sonnet-4-5`、`deepseek-v4.1-flash`）能完整显示；
 * 真更长的再截断，且菜单标签本身就短（"模型"两个字），一行放得下。
 */
const val MENU_HINT_MAX = 22

/**
 * 锚定菜单（3 组 + 「更多…」）：高频操作，一眼能扫完。
 *
 * 顺序按"改参数 → 改结构 → 拿走内容"排，与终端里手最常伸的方向一致。
 */
fun quickMenu(modelDisplay: String? = null, thinkingLevel: String? = null): List<MenuGroup> = listOf(
    MenuGroup(
        rows = listOf(
            MenuRow(ChatAction.Models, "模型", hint = menuHint(modelDisplay)),
            MenuRow(ChatAction.Thinking, "思考等级", hint = menuHint(thinkingLevel)),
        ),
    ),
    MenuGroup(
        rows = listOf(
            MenuRow(ChatAction.Tree, "会话树 / 分叉"),
            MenuRow(ChatAction.Compact, "压缩上下文"),
        ),
    ),
    MenuGroup(
        rows = listOf(
            MenuRow(ChatAction.CopyLast, "复制最后一条回答"),
            MenuRow(ChatAction.Export, "导出会话"),
        ),
    ),
    MenuGroup(rows = listOf(MenuRow(ChatAction.More, "更多", chevron = true))),
)

/**
 * 「更多」面板：低频 / 非会话的东西，**带分组标题**。
 *
 * 分组按"影响什么"分，不按"在哪儿实现"分：
 *  · 会话 = 只影响这个会话（改名、重载、注入配置）；
 *  · 工具 = 打开别的界面（命令、Shell、文件）；
 *  · 设置 = 全局偏好（行为开关、连接）。
 */
val moreMenu: List<MenuGroup> = listOf(
    MenuGroup(
        title = "会话",
        rows = listOf(
            MenuRow(ChatAction.Rename, "重命名会话"),
            MenuRow(ChatAction.Reload, "重载会话（重启 pi）"),
            MenuRow(ChatAction.AppInject, "App 会话注入"),
        ),
    ),
    MenuGroup(
        title = "工具",
        rows = listOf(
            MenuRow(ChatAction.Commands, "命令与技能"),
            MenuRow(ChatAction.Shell, "命令执行（Shell）"),
            MenuRow(ChatAction.Files, "文件（SFTP）"),
        ),
    ),
    MenuGroup(
        title = "设置",
        rows = listOf(
            MenuRow(ChatAction.Behavior, "行为开关"),
            MenuRow(ChatAction.Connection, "连接设置"),
            MenuRow(ChatAction.Settings, "全部设置"),
        ),
    ),
)

/**
 * 右侧提示值的整理：
 *  - 去掉 provider 前缀之外的空格（`anthropic/claude-x` 保留，方便区分同名的两家模型）；
 *  - 超长截断到 [MENU_HINT_MAX]（含 `…`）—— 菜单行不能因为模型名长就换行；
 *  - 空白 ⇒ null（不显示占位）。
 */
fun menuHint(raw: String?): String? {
    val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (text.length <= MENU_HINT_MAX) return text
    return text.take(MENU_HINT_MAX - 1) + "…"
}

/** 扁平化（渲染/测试都用它，避免各处再写一遍双层循环）。 */
fun List<MenuGroup>.flatRows(): List<MenuRow> = flatMap { it.rows }
