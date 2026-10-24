package id.harness.pipocket.ui

/**
 * 「压缩上下文」卡片的文案与摘要可见性（对齐 pi 的 `compaction-summary-message.ts`）。
 *
 * pi 那张卡是**两态**的：
 *  · 折叠：`[compaction]` + `Compacted from 18,240 tokens (ctrl+o to expand)`；
 *  · 展开：`**Compacted from 18,240 tokens**` + 摘要正文（markdown）。
 * 也就是说摘要**是能看的**。app 之前只显示 `tokens: N → M`，用户看不到"压成了什么"
 * —— 压缩之后如果觉得摘要丢信息，唯一办法是去终端翻，这是真实缺口。
 *
 * 手机上：
 *  · 折叠态多给一句「点开看摘要」；
 *  · 展开态把摘要渲染成 markdown（与正文同一套解析，不另做一套）。
 */

/**
 * 折叠态那一行：`18.2k → 4.1k tokens（省 77%）`。
 *
 * 用户 2026-10-23：「从 xxx 压缩而来，现在约 xxx，省 xxx」太啰嗦，**语言要简洁或直接给信息**。
 * 所以只留两头数字 + 一个百分比；"压缩"这个动作由卡片标题（`压缩上下文`）交代，行内不再重复。
 *
 * 百分比只在**真的变小**时给：pi 的 `estimatedTokensAfter` 是估算值，偶尔会大一点点
 * （压缩本身也要花 token），那种情况不给百分比，免得出现"省 -3%"这种怪话。
 */
internal fun compactionDetail(tokensBefore: Long, tokensAfter: Long): String {
    if (tokensBefore <= 0) return ""
    val before = formatTokens(tokensBefore)
    if (tokensAfter <= 0) return "$before tokens 压缩"
    val saved = (1.0 - tokensAfter.toDouble() / tokensBefore).coerceAtLeast(0.0)
    val pair = "$before → ${formatTokens(tokensAfter)} tokens"
    return if (saved > 0.005) "$pair（省 ${(saved * 100).roundToInt()}%）" else pair
}

/** 有摘要才给展开入口（pi 里没有摘要时那张卡也只是一行字）。 */
internal fun hasCompactionSummary(summary: String?): Boolean =
    summary != null && summary.isNotBlank()

/** 展开/收起的提示文字（终端靠快捷键，手机只能写出来）。 */
internal fun compactionToggleHint(expanded: Boolean, hasSummary: Boolean): String? = when {
    !hasSummary -> null
    expanded -> "收起摘要"
    else -> "点开看摘要"
}

private fun Double.roundToInt(): Int = kotlin.math.round(this).toInt()
