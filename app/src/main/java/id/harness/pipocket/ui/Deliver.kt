package id.harness.pipocket.ui

/**
 * 「导出会话」与「复制最后一条回答」的纯逻辑（可单测，不碰 Android）。
 *
 * 对应 pi 终端的 `/export` 与 `/copy`：
 *  · `/export` → RPC `export_html`，pi 默认写到 worker 的 cwd，
 *    文件名 `pi-session-<会话名>.html`（`core/export-html/index.ts:274-281`）；
 *  · `/copy` → RPC `get_last_assistant_text`，pi 已经跳过「中断且无内容」的消息
 *    （`core/agent-session.ts:4377`），app 只负责把它放进剪贴板。
 */

/** 导出成功后的说明文案（给状态行/对话框用）。 */
internal fun exportSummary(path: String, bytes: Long?, home: String? = null): String {
    val short = shortenHome(path, home)
    if (bytes == null || bytes <= 0) return short
    return short + " · " + humanBytes(bytes)
}

/** 人类可读体积（1024 进制，保留一位小数；够用就好，不做 i18n 单位）。 */
internal fun humanBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
    else -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
}

/** 把 home 前缀缩成 `~`（终端也是这么显示的，长路径在手机上一行放不下）。 */
internal fun shortenHome(path: String, home: String?): String {
    val h = home?.trim().orEmpty()
    if (h.isEmpty()) return path
    return if (path.startsWith(h)) "~" + path.substring(h.length) else path
}

/**
 * 「复制最后一条回答」的**副作用描述**：复制多少字、第一行是什么。
 *
 * 为什么不直接复制完就完事：手机上没有反馈的复制等于没发生（用户不知道成没成）。
 * 这里返回一句能显示的话 + 是否成功（pi 那边可能压根没有回答）。
 */
/** 提示里最多显示多少字（一行放得下，又不至于把状态行撑爆）。 */
private const val COPY_PREVIEW_CHARS = 24

internal data class CopyOutcome(val ok: Boolean, val message: String)

internal fun copyLastAnswerOutcome(text: String): CopyOutcome {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return CopyOutcome(false, "还没有可复制的回答")
    val rawFirst = trimmed.lineSequence().first().trim()
    val clipped = rawFirst.length > COPY_PREVIEW_CHARS
    val firstLine = rawFirst.take(COPY_PREVIEW_CHARS) + if (clipped) "…" else ""
    return CopyOutcome(true, "已复制 ${trimmed.length} 字：$firstLine")
}

/**
 * 给「导出」挑一个默认文件名。
 *
 * pi 自己也会起名，但那个名字落在**桥的 cwd**（Termux 里的目录），手机上找起来麻烦。
 * app 侧优先导到 `~/projects/exports/` 之类的固定位置 ⇒ 一眼能找到、也能在文件页里翻。
 */
internal fun exportPathFor(sessionFile: String, sessionName: String?, home: String): String {
    val base = (sessionName?.trim()?.takeIf { it.isNotBlank() }
        ?: sessionFile.substringAfterLast('/').removeSuffix(".jsonl"))
        .replace(Regex("[^\\p{L}\\p{N}._-]+"), "-")
        .trim('-')
        .take(60)
        .ifBlank { "session" }
    val dir = "$home/workspace/exports"
    return "$dir/pi-session-$base.html"
}
