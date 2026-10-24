package id.harness.pipocket

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * 一条**没能确认发出去**的输入（用户 2026-10-23 要求："发送失败的消息要能够保留恢复编辑"）。
 *
 * 为什么需要它：pi 的 `prompt` 是"发出去就不管了"。为了即时反馈，输入框在发的那一刻就清空
 * （见 ChatScreen 的 `onSent()`），所以桥出错、HTTP 500、连接掉了、或者 app 被系统杀掉时，
 * 刚打的那段话就凭空消失了 —— 用户只能凭记忆重打。
 *
 * 做法：**发之前**先落一份到 prefs（[AppState.saveOutbox]），`prompt` 成功回来后立即清掉；
 * 失败（或下次启动时发现还在）就当作「没发出去的消息」交回输入框。
 *
 * 这个文件刻意只依赖 JDK（URLEncoder/Base64 都不用 android.*），好在 JVM 单测里跑。
 */
data class PendingDraft(
    val text: String,
    /** 目标会话文件（恢复时只在同一会话里提示，避免串台）。 */
    val file: String? = null,
    /** 失败原因（给提示行用；正常发出后为 null）。 */
    val reason: String? = null,
    /** 落盘时间（毫秒）。 */
    val ts: Long = 0L,
    /** 当时带了几张图（图不像文本那样落盘，恢复时要提示重贴）。 */
    val hadImages: Int = 0,
)

object Outbox {

    private const val VERSION = "v1"

    /**
     * 一行一个字段，值做 URL 编码（ASCII 安全，换行/中文/引号都不用担心）。
     *
     * 不用 JSON：`org.json` 在 JVM 单测里是 android.jar 的空壳（未 mock 会抛异常），
     * 而这个文件的价值就在于能被单测钉住。
     */
    fun encode(d: PendingDraft): String = buildString {
        append(VERSION).append('\n')
        append("text=").append(enc(d.text)).append('\n')
        append("file=").append(d.file?.let(::enc).orEmpty()).append('\n')
        append("reason=").append(d.reason?.let(::enc).orEmpty()).append('\n')
        append("ts=").append(d.ts).append('\n')
        append("images=").append(d.hadImages)
    }

    /** 解不出来就返回 null —— 宁可少一条提示，也不能让启动因为脏数据挂掉。 */
    fun decode(raw: String?): PendingDraft? {
        if (raw.isNullOrBlank()) return null
        val lines = raw.split('\n')
        if (lines.firstOrNull()?.trim() != VERSION) return null
        val map = lines.drop(1).mapNotNull { line ->
            val i = line.indexOf('=')
            if (i <= 0) null else line.substring(0, i) to line.substring(i + 1)
        }.toMap()
        val text = dec(map["text"].orEmpty())
        if (text.isBlank()) return null
        return PendingDraft(
            text = text,
            file = map["file"]?.takeIf { it.isNotBlank() }?.let(::dec),
            reason = map["reason"]?.takeIf { it.isNotBlank() }?.let(::dec),
            ts = map["ts"]?.toLongOrNull() ?: 0L,
            hadImages = map["images"]?.toIntOrNull() ?: 0,
        )
    }

    /** 提示行里的一句话预览：折成单行、超长截断（卡片/条不能因为一条长消息被撑爆）。 */
    fun preview(text: String, max: Int = 60): String {
        val flat = text.trim().replace(Regex("\\s+"), " ")
        if (flat.length <= max) return flat
        // 尽量在词边界截断，中文没有空格就直接硬截
        val cut = flat.take(max)
        val space = cut.lastIndexOf(' ')
        val head = if (space > max / 2) cut.substring(0, space) else cut
        return head.trimEnd() + "…"
    }

    /**
     * 恢复编辑时怎么和"用户正在写的内容"合并：**草稿在前**，正在写的接在后面。
     * 两者都不覆盖（丢掉任何一份都是数据丢失）。
     */
    fun merge(current: String, draft: String): String = when {
        draft.isBlank() -> current
        current.isBlank() -> draft
        else -> draft.trimEnd() + "\n\n" + current.trimStart()
    }

    /** 这条草稿是否属于当前会话（file 为空 = 不知道属于哪条，任何会话都提示）。 */
    fun matches(d: PendingDraft?, sessionFile: String?): Boolean {
        if (d == null) return false
        val f = d.file ?: return true
        if (sessionFile.isNullOrBlank()) return false
        return f == sessionFile
    }

    private fun enc(v: String): String = URLEncoder.encode(v, "UTF-8")

    private fun dec(v: String): String = runCatching { URLDecoder.decode(v, "UTF-8") }.getOrDefault(v)
}
