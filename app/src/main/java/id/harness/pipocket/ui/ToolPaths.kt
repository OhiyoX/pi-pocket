package id.harness.pipocket.ui

import org.json.JSONObject

/**
 * 工具卡上「能点开看」的路径 —— 用户口径（2026-10-22）：
 * **`read` 读到的内容，不管文本还是图像，用户都要能看**。
 *
 * 以前只有图片有「查看图片」链接（还是从**结果文本**里正则捞路径），而 `read` 读文本时
 * 根本没有入口 ⇒ 用户只能看到卡片里被截断的那两行。
 *
 * 现在的取值顺序（越靠前越可信）：
 *  1. **参数里的路径**（`read`/`write`/`edit`/`ls`/`grep`/`find` 的 `path`/`file_path`）——
 *     这是工具真正操作的目标，比从输出里猜可靠（相对路径也不怕）；
 *  2. 结果文本里出现的**绝对路径**（`log: /…`、`查看图片 /…` 这类）。
 */

/** 这些工具的参数里带「一个路径」，可以在 app 里点开看。 */
private val PATH_TOOLS = setOf("read", "write", "edit", "ls", "grep", "find", "view", "file")

/** 从工具参数 JSON 里取目标路径（取不到返回 null）。 */
internal fun toolTargetPath(toolName: String, argsJson: String?): String? {
    if (argsJson.isNullOrBlank()) return null
    val name = toolName.lowercase().substringAfterLast('.')
    if (name !in PATH_TOOLS) return null
    val obj = runCatching { JSONObject(argsJson) }.getOrNull() ?: return null
    for (key in listOf("path", "file_path", "filePath", "file", "target", "dir", "cwd")) {
        if (!obj.has(key) || obj.isNull(key)) continue
        val v = obj.opt(key)
        if (v is String && v.isNotBlank()) return v
    }
    return null
}

/** 只认图片扩展名（用来决定「查看图片」还是「查看内容」）。 */
internal fun isImagePath(path: String): Boolean {
    val p = path.lowercase().substringBefore('?')
    return p.endsWith(".png") || p.endsWith(".jpg") || p.endsWith(".jpeg") ||
        p.endsWith(".webp") || p.endsWith(".gif") || p.endsWith(".bmp") ||
        p.endsWith(".heic") || p.endsWith(".svg")
}

/**
 * 「点开看」那一行的文案。
 *
 * 刻意区分图片与文本：图片是“看”，文本/目录是“看内容/看目录”，
 * 用户截图里那张图显示的 `查看图片 /…/shot.png` 就是这条的图片分支。
 */
internal fun viewActionLabel(path: String): String = when {
    isImagePath(path) -> "查看图片"
    path.endsWith("/") -> "查看目录"
    else -> "查看内容"
}

/**
 * 这一条工具结果**要不要**给「点开看」入口，给了返回路径。
 *
 * @param toolName 工具名（`read` / `bash` / 扩展工具…）
 * @param argsJson 工具参数（`ChatItem.toolArgsJson`）
 * @param resultText 结果文本（`ChatItem.toolResult`）
 */
internal fun viewablePath(
    toolName: String,
    argsJson: String?,
    resultText: String,
): String? {
    toolTargetPath(toolName, argsJson)?.let { return it }
    return firstAbsolutePath(resultText)
}

/**
 * 工具卡底部的图片入口（用户 2026-10-24 两条口径叠加）。
 *
 * 口径：「图片**不要**塞进 toolcall 里直接显示，但要能点开看」⇒ 这里只产出**一行可点的入口**，
 * 永远不产出内联缩略图（以前的 `ToolImages` 已删，见 issue #40）。
 *
 *  · [ToolImageEntry.Path]：能从入参/结果认出路子 ⇒ `查看图片 <path>`（走 `/v1/file`，再点进全屏）；
 *  · [ToolImageEntry.Data]：只有 base64 数据、认不出路径 ⇒ `查看图片（N 张）`（直接开全屏查看器）；
 *  · [ToolImageEntry.None]：没图也没路径 ⇒ 什么都不显示。
 */
internal sealed interface ToolImageEntry {
    data class Path(val path: String) : ToolImageEntry
    data class Data(val count: Int) : ToolImageEntry
    data object None : ToolImageEntry
}

/**
 * 工具卡该画哪种图片入口（纯函数，单测锁住「有图数据也不内联」）。
 *
 * @param openable 能认出的路径（[viewablePath]）
 * @param imageCount 结果里自带的图片数量（`ChatItem.toolImages`）
 * @param running 工具还在跑（跑完之前不给入口）
 * @param showPath 结果文本有内容 / 读的是图 ⇒ 允许路径入口（read 图时正文本来就是空的）
 */
internal fun toolImageEntry(
    openable: String?,
    imageCount: Int,
    running: Boolean,
    showPath: Boolean,
): ToolImageEntry {
    if (running) return ToolImageEntry.None
    if (showPath && openable != null) return ToolImageEntry.Path(openable)
    if (imageCount > 0) return ToolImageEntry.Data(imageCount)
    return ToolImageEntry.None
}

/** 结果文本里第一个像路径的绝对路径（`~/x` 也认）。与 ChatScreen 的 `deliverablePathsIn` 同一套字符集。 */
internal fun firstAbsolutePath(text: String): String? {
    if (text.isBlank()) return null
    val re = Regex("(?:^|[\\s\"'(:=])((?:/[\\w.\\-]+)+|~/[\\w./\\-]+)")
    val raw = re.find(text)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return null
    // 句末标点不属于路径：工具输出常常是 “……README.md.”，以前原样当成路径 ⇒ sh: cannot open …README.md.
    return raw.trimEnd('.', ',', ')', ';', ':', '。', '，', '）', '、', '!', '?')
        .takeIf { it.count { c -> c == '/' } >= 2 }
}
