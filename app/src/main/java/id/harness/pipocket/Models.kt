package id.harness.pipocket

import org.json.JSONArray
import org.json.JSONObject

/**
 * 会话里渲染的一行。对齐 pi 终端（TUI）的口径：
 * **默认紧凑，细节靠展开看**（pi 自带文档 tui.md 的写法：keep the default view compact and reveal detail
 * through expansion）。所以：
 * - assistant 的 `thinking` 默认折叠，点开看全文；
 * - 每次工具调用单独一行（`名字 · 参数摘要`），**结果默认折叠**（只报「N 行」/错误），点开看输出。
 *
 * 数据形状来自 pi docs/message-types.md：assistant 的 content 是 [text|thinking|toolCall]，
 * 工具结果在单独的 `role:"toolResult"` 消息里（`toolCallId` 关联）。
 */
data class ChatItem(
    val key: String,
    val role: String,               // user | assistant | tool | notice | system
    val text: String = "",
    val thinking: String = "",
    val footer: String = "",
    val streaming: Boolean = false,
    // tool 专用
    val isTool: Boolean = false,
    val toolName: String = "",
    val toolBrief: String = "",
    val toolResult: String = "",
    val toolIsError: Boolean = false,
    val toolDurationMs: Long? = null,
    val toolRunning: Boolean = false,
    /** 工具**运行中**的实时输出（pi 的 `tool_execution_update` → partialResult）。跑长命令/构建时能直接看到进程。 */
    val toolLive: String = "",
    /** 工具调用的完整参数（pretty JSON）。 */
    val toolArgsJson: String = "",
    // ↓ **终端显示文本**：桥用 pi 自己的 render-utils 算好后随事件/历史一起送来（app 只负责画），
    //   这样终端改了什么，app 自动跟随，不用在 Kotlin 里再抄一遍排版规则。
    val dispTitle: String = "",
    val dispArgsCollapsed: String = "",
    val dispArgsExpanded: String = "",
    val dispPreviewLines: Int = 0,
    /** pi 自己的**终端渲染**出来的 HTML（内置工具才有：edit 的 diff 块、bash 的输出框…）。
     *  口径同 dispTitle：能继承终端就继承终端，app 只把它放进 WebView，不重写排版规则。 */
    val dispHtmlCollapsed: String = "",
    val dispHtmlExpanded: String = "",
    // pi 渲染的**终端原样行**（ANSI）：call=头行（`edit <path>`），result*=按列宽排好的结果块。
    // 有它就用它画（1:1 对比终端），没有才退回 disp* 文本或 HTML。
    val termCall: List<String> = emptyList(),
    val termResultCollapsed: List<String> = emptyList(),
    val termResultExpanded: List<String> = emptyList(),
    /** 桥到底有没有送「收起版结果」：`read` 的收起渲染本来就是空（终端收起也不露正文）。
     *  区分“没渲染”（可以拿 expanded 兜底）与“渲染了但就是空”（照终端不露正文）。 */
    val termHasCollapsed: Boolean = false,
    val termHasExpanded: Boolean = false,
    /** 这一行工具**开始跑**的墙钟时间（ms）。终端里运行中会每秒重画 `Elapsed 12s`，
     *  app 靠它自己 tick（不不必为此每秒轰一次 SSE）。 0 = 不在跑/不知道。 */
    val toolStartedAt: Long = 0L,
    /** 工具结果里的图（如 read 一张图片）——可以点开看原图。 */
    val toolImages: List<ImageRef> = emptyList(),
    /**
     * 「read 了一张图但数据没随事件下来」时的原文件路径（异步按需拉，见 [ChatScreen.ReadImageThumbs]）。
     *
     * 为什么需要这条：桥给历史消息剥掉了 base64（`omitted:true`，手机扛不住几十 MB 的 JSON），
     * 而实时的 tool_execution_end 也不保证带图 ⇒ 只看 `toolImages` 就永远只剩一行
     * 「Read image file [image/png]」，图看不到（2026-10-24 用户真机报的）。
     */
    val toolImagePaths: List<String> = emptyList(),
    // notice 专用：pi 的报错 / 提醒 / 状态（默认折叠到一行，点开看详情）
    val isNotice: Boolean = false,
    val noticeLevel: String = "info",   // info | warn | error
    val noticeDetail: String = "",
    val noticeNeedsTerminal: Boolean = false,
    /** 同类通知的合并标记（非空时同 kind 只保留一条，比如压缩上下文）。 */
    val noticeKind: String = "",
    // 用户消息带的图（base64 原图，不带 data: 前缀）+ 历史里被桥省掉的张数 + 在 get_messages 里的下标
    val images: List<ImageRef> = emptyList(),
    val imagesOmitted: Int = 0,
    val msgIndex: Int = -1,
) {
    val isUser: Boolean get() = role == "user"

    /** pi 终端那行的显示文本：`name · brief`。 */
    val toolLabel: String
        get() = if (toolBrief.isBlank()) toolName else "$toolName · $toolBrief"
}

/** 一条消息里的一张图：base64 原图（不带 `data:` 前缀）。 */
data class ImageRef(val mimeType: String, val base64: String)

/** 全屏看图的请求（图 + 起始页 + 每张的名字）。 */
data class ImageViewerRequest(
    val images: List<ImageRef>,
    val initial: Int = 0,
    val titles: List<String> = emptyList(),
)

/**
 * app 会话注入项（桥 `GET /v1/app-profile`）。
 *
 * 口径（用户 2026-10-19）：app 内置一批预设 skills / MCP / 附加系统提示，
 * **只在通过 app 连上的会话里注入**（桥 spawn pi 时加 `--skill` / `--append-system-prompt` / `-e`），
 * 终端与其它 harness 的 pi 完全不受影响；这里给 app 里的开关用。
 */
data class AppProfileItem(
    val id: String,
    val name: String,
    val description: String,
    val enabled: Boolean,
    val tag: String = "",
)

data class AppProfile(
    val bridgeEnabled: Boolean = false,
    val appendPrompt: Boolean = true,
    val appendPromptPresent: Boolean = false,
    val skills: List<AppProfileItem> = emptyList(),
    val mcp: List<AppProfileItem> = emptyList(),
) {
    companion object {
        fun from(data: JSONObject): AppProfile {
            fun items(key: String): List<AppProfileItem> {
                val arr = data.optJSONArray(key) ?: return emptyList()
                return (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    AppProfileItem(
                        id = o.optString("id"),
                        name = o.optString("name").ifBlank { o.optString("id") },
                        description = o.optString("description"),
                        enabled = o.optBoolean("enabled", true),
                        tag = o.optString("tag"),
                    )
                }
            }
            val ap = data.optJSONObject("appendPrompt")
            return AppProfile(
                bridgeEnabled = data.optBoolean("enabled", false),
                appendPrompt = ap?.optBoolean("enabled", true) ?: true,
                appendPromptPresent = ap?.optBoolean("present", false) ?: false,
                skills = items("skills"),
                mcp = items("mcp"),
            )
        }
    }
}

/**
 * 一个 pi provider（桥 `GET /v1/providers` 的一行）。
 *
 * 桥**不回传 key 原文**，只给 `hasKey` —— 所以 app 里「编辑已有 provider」时 key 输入框留空 = 不改。
 * 一手配置位置见 HANDOFF §15：`~/.pi/agent/models.json` 的 `providers[name]` + `auth.json` 的 `[name]`。
 */
data class ProviderInfo(
    val name: String,
    val baseUrl: String,
    val api: String,
    val models: List<String>,
    val hasKey: Boolean,
    val authType: String?,
    /** pi 自带的 provider（catalog 里就有，不需要 models.json）。列表里只读，不能删。 */
    val builtin: Boolean = false,
) {
    /** 列表里那行小字：`3 个模型 · deepseek-flash, gpt-6.1-sol`（模型多就省略）。 */
    val modelsSummary: String
        get() = when {
            models.isEmpty() -> "没有模型"
            else -> models.size.toString() + " 个模型 · " + models.take(3).joinToString(", ") +
                (if (models.size > 3) " …" else "")
        }

    companion object {
        fun from(obj: JSONObject): ProviderInfo? {
            val name = obj.optString("name").takeIf { it.isNotBlank() } ?: return null
            val arr = obj.optJSONArray("models") ?: JSONArray()
            val models = (0 until arr.length()).mapNotNull { i ->
                arr.optString(i).takeIf { it.isNotBlank() }
            }
            return ProviderInfo(
                name = name,
                baseUrl = obj.optString("baseUrl"),
                api = obj.optString("api"),
                models = models,
                hasKey = obj.optBoolean("hasKey", false),
                authType = obj.optString("authType").takeIf { it.isNotBlank() && it != "null" },
                builtin = obj.optBoolean("builtin", false),
            )
        }
    }
}

/**
 * provider 表单的**纯逻辑**（app 只做预校验，真正的规则在桥里：name 的字符集 `^[A-Za-z0-9._-]+$`）。
 *
 * 放在这里是为了能跑单元测试（UI 里只调用，不重复规则）。
 */
object ProviderForm {

    private val NAME_OK = Regex("^[A-Za-z0-9._-]+$")

    /** pi 常见的 `api` 取值（下拉里的常用项；可手填别的，桥不校验这个字段）。 */
    val API_TYPES = listOf(
        "openai-completions",
        "openai-responses",
        "anthropic-messages",
        "google-generative-ai",
    )

    fun nameError(name: String): String? = when {
        name.isBlank() -> "name 不能为空"
        !NAME_OK.matches(name) -> "name 只能包含字母、数字和 . _ -"
        else -> null
    }

    /**
     * `models` 文本框 → id 列表：逗号（中英文）、以及任意空白都算分隔，去重保序。
     *
     * 这里刻意用 `isWhitespace()` 而不是转义字面量：源码里写 `\n` / `\t` 容易被写坏。
     */
    fun parseModels(text: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        for (c in text) {
            if (c == ',' || c == '，' || c == '、' || c.isWhitespace()) {
                if (cur.isNotEmpty()) {
                    out += cur.toString()
                    cur.setLength(0)
                }
            } else {
                cur.append(c)
            }
        }
        if (cur.isNotEmpty()) out += cur.toString()
        return out.distinct()
    }

    fun formatModels(models: List<String>): String = models.joinToString(", ")

    /** 日志尾部：只留最后 [lines] 行（失败时要能一眼看到 bootstrap 卡在哪）。 */
    fun tailLines(text: String, lines: Int): String {
        val all = text.split(Character.toString(10)).filter { it.isNotBlank() }
        return all.takeLast(lines).joinToString(Character.toString(10))
    }
}

object MessageParser {

    fun textOf(content: Any?): String = when (content) {
        is String -> content
        is JSONArray -> buildString {
            for (i in 0 until content.length()) {
                val part = content.optJSONObject(i) ?: continue
                when (part.optString("type")) {
                    "text" -> append(part.optString("text"))
                    "image" -> append("[图片]")
                }
            }
        }
        else -> ""
    }

    /** 工具结果里**自带数据**的图（base64）。 */
    fun imagesOfToolResult(content: Any?): List<ImageRef> = buildList {
        if (content !is org.json.JSONArray) return@buildList
        for (i in 0 until content.length()) {
            val part = content.optJSONObject(i) ?: continue
            if (part.optString("type") != "image") continue
            val data = part.optString("data")
            if (data.isNotBlank()) add(ImageRef(part.optString("mimeType", "image/png"), data))
        }
    }

    /**
     * `read` 一张图时，结果里**没带数据**也能靠这个路径把图拉回来。
     *
     * pi 的 read 成功时给 `Read image file [image/png]` + 一个 image part；但桥给历史剥掉了
     * base64（`omitted:true`，手机扛不住几十 MB 的 JSON），实时事件的 result 也可能不带 ⇒
     * 只认 text 前缀 + 入参 path，由 UI 走桥 `/v1/file?path=` 异步补图。
     * 文本文件不会误伤（前缀必须恰好是 `Read image file`）。
     */
    fun readImagePaths(resultText: String, pathArg: String?): List<String> {
        if (!resultText.trimStart().startsWith("Read image file")) return emptyList()
        val p = pathArg?.trim().orEmpty()
        return if (p.isBlank()) emptyList() else listOf(p)
    }

    /** 只要 text 部分（用户消息的图单独渲染，正文里不再塞 `[图片]`）。 */
    fun textOnly(content: Any?): String = when (content) {
        is String -> content
        is JSONArray -> buildString {
            for (i in 0 until content.length()) {
                val part = content.optJSONObject(i) ?: continue
                if (part.optString("type") == "text") append(part.optString("text"))
            }
        }
        else -> ""
    }

    /**
     * 从消息 content 里取图。
     *
     * 桥默认把历史图剥成 `{type:image, mimeType, omitted:true, bytes}` 给 app 省流量，
     * 所以返回「拿到的原图」与「被省掉的张数」两种：后者在 UI 里给一个「点开加载」的入口。
     */
    fun imagesOf(content: Any?): Pair<List<ImageRef>, Int> {
        val images = mutableListOf<ImageRef>()
        var omitted = 0
        if (content is JSONArray) {
            for (i in 0 until content.length()) {
                val part = content.optJSONObject(i) ?: continue
                if (part.optString("type") != "image") continue
                val data = part.optString("data")
                if (part.optBoolean("omitted") || data.isBlank()) omitted++
                else images += ImageRef(part.optString("mimeType", "image/jpeg"), data)
            }
        }
        return images to omitted
    }

    private fun lines(text: String): Int = if (text.isBlank()) 0 else text.count { it == '\n' } + 1

    /** 工具结果行的摘要（折叠时显示）。 */
    fun resultSummary(result: String, isError: Boolean): String = when {
        isError -> "失败"
        result.isBlank() -> "无输出"
        else -> "${lines(result)} 行"
    }

    /** 参数摘要：取最常见的几个键，避免把整个 JSON 塞进一行；**清掉换行**（MCP 的 JSON 参数常常带 \n）。 */
    fun briefOf(args: JSONObject?): String {
        val raw = args?.let { json ->
            // 键名覆盖到各家的工具：pi 自带的 read/write/edit 用 path/file_path，
            // unified-exec 扩展的 exec_command 用 **cmd**（之前漏了它 ⇒ 手机上的工具行 brief 全空，看着"都一样"）
            listOf(
                "command", "cmd", "path", "file_path", "pattern", "query", "url", "target",
                "branch", "prompt", "text", "name",
            )
                .firstNotNullOfOrNull { key -> json.optString(key).takeIf { it.isNotBlank() } }
        } ?: return ""
        val oneLine = raw.replace(Regex("[\\r\\n\\t]+"), " ").replace(Regex("\\s{2,}"), " ").trim()
        return if (oneLine.length > 60) oneLine.take(60) + "…" else oneLine
    }

    /** JSON 字符串数组 → List<String>（拿不到就给空表）。 */
    private fun strList(arr: org.json.JSONArray?): List<String> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).map { arr.optString(it) }
    }

    private fun termList(arr: org.json.JSONArray?): List<String> {
        if (arr == null) return emptyList()
        return trimTermLines((0 until arr.length()).map { arr.optString(it) })
    }

    /**
     * 终端的定宽排版会补行尾空格并在块首尾留空白行。
     * 头行（`call`）：首尾空行都属于**块间分隔**，去掉。
     * 结果块：**开头的空行要留**——pi 就是拿它在头行和正文之间隔一条（终端实拍），
     * 删了就和终端不一致了；只去行尾空白与结尾空行。
     */
    private fun trimTermLines(lines: List<String>, keepLeadingBlank: Boolean = false): List<String> {
        val ansi = Regex("\u001B\\[[0-9;]*m")
        fun blank(s: String) = s.replace(ansi, "").isBlank()
        val out = lines.map { it.replace(Regex("[ \\t]+$"), "") }.toMutableList()
        if (keepLeadingBlank) {
            while (out.size > 1 && blank(out[0]) && blank(out[1])) out.removeAt(0)
        } else {
            while (out.isNotEmpty() && blank(out.first())) out.removeAt(0)
        }
        while (out.isNotEmpty() && blank(out.last())) out.removeAt(out.lastIndex)
        return out
    }

    internal fun toolItem(
        id: String,
        name: String,
        args: JSONObject?,
        result: JSONObject?,
        fallbackKey: String,
        display: JSONObject? = null,
        html: JSONObject? = null,
        term: JSONObject? = null,
    ): ChatItem {
        val resultText = result?.let { textOf(it.opt("content")) }.orEmpty().trim()
        // 工具结果里的图（read 图片时 pi 会给 type:"image" 的 part）——不取出来就只能看到「[图片]」两个字
        val resultImages = imagesOfToolResult(result?.opt("content"))
        return ChatItem(
            key = if (id.isNotBlank()) "tool-$id" else fallbackKey,
            role = "tool",
            isTool = true,
            toolName = name,
            toolBrief = briefOf(args),
            toolArgsJson = args?.toString(2).orEmpty(),
            dispTitle = display?.optString("title").orEmpty(),
            dispArgsCollapsed = display?.optString("argsCollapsed").orEmpty(),
            dispHtmlCollapsed = html?.optString("htmlCollapsed").orEmpty(),
            dispHtmlExpanded = html?.optString("htmlExpanded").orEmpty(),
            termCall = termList(term?.optJSONArray("call")),
            termResultCollapsed = trimTermLines(termList(term?.optJSONArray("resultCollapsed")), keepLeadingBlank = true),
            termResultExpanded = trimTermLines(termList(term?.optJSONArray("resultExpanded")), keepLeadingBlank = true),
            termHasCollapsed = term?.has("resultCollapsed") == true || term?.optBoolean("hasCollapsed") == true,
            termHasExpanded = term?.has("resultExpanded") == true || term?.optBoolean("hasExpanded") == true,
            dispArgsExpanded = display?.optString("argsExpanded").orEmpty(),
            dispPreviewLines = display?.optInt("previewLines") ?: 0,
            toolImages = resultImages,
            toolImagePaths = readImagePaths(resultText, args?.optString("path")),
            toolResult = resultText,
            toolIsError = result?.optBoolean("isError") ?: false,
            // 历史里的工具行**不是"正在跑"**：running 只能由实时事件（tool_execution_start）设置。
            // 否则历史里没有结果的工具（被打断/进程重启）会永远显示"运行中"，
            // 连带着 working 条也假装在跑（用户截图里的「工作中 · 正在跑 write 16s」就是这么来的）。
            toolRunning = false,
        )
    }

    /** 把一条 assistant 消息（message_end 的权威版本）变成渲染行。 */
    fun assistantItem(message: JSONObject, fallbackKey: String): ChatItem? {
        val content = message.optJSONArray("content") ?: JSONArray()
        val text = StringBuilder()
        val thinking = StringBuilder()
        for (j in 0 until content.length()) {
            val part = content.optJSONObject(j) ?: continue
            when (part.optString("type")) {
                "text" -> text.append(part.optString("text"))
                "thinking" -> thinking.append(part.optString("thinking"))
            }
        }
        val usage = message.optJSONObject("usage")
        val footer = buildString {
            val model = message.optString("model")
            if (model.isNotBlank()) append(model)
            usage?.optLong("totalTokens", 0L)?.takeIf { it > 0 }?.let {
                if (isNotEmpty()) append(" · ")
                append("$it tok")
            }
            usage?.optJSONObject("cost")?.optDouble("total", 0.0)?.takeIf { it > 0 }?.let {
                append(" · $${ "%.4f".format(it) }")
            }
        }
        if (text.isBlank() && thinking.isBlank() && footer.isBlank()) return null
        return ChatItem(
            key = fallbackKey,
            role = "assistant",
            text = text.toString().trim(),
            thinking = thinking.toString().trim(),
            footer = footer,
        )
    }

    /**
     * assistant 行的 key：**内容定**（timestamp + 正文/思考的哈希）。
     *
     * 为什么不用 `timestamp-下标`：实时跑完的回合（`message_end`）拿不到消息数组下标，
     * 而消息历史重载时才有 ⇒ 同一个回合会算出两个不同的 key，展开/收起状态到重载就丢了（用户报的「滑动就变」的一类）。
     * 内容哈希两边都算得出，所以 key 稳定。
     */
    fun assistantKey(message: JSONObject): String {
        val content = message.optJSONArray("content") ?: JSONArray()
        val body = StringBuilder()
        for (j in 0 until content.length()) {
            val part = content.optJSONObject(j) ?: continue
            when (part.optString("type")) {
                "text" -> body.append(part.optString("text"))
                "thinking" -> body.append(part.optString("thinking"))
            }
        }
        val hash = body.toString().hashCode().toUInt().toString(16)
        return "assistant-" + message.optLong("timestamp", 0L) + "-" + hash
    }

    /**
     * 把 `get_messages` 的数组变成渲染行：assistant 的 toolCall 与后面的 toolResult 按 `toolCallId` 配对。
     */
    fun history(messages: JSONArray): List<ChatItem> {
        val results = HashMap<String, JSONObject>()
        for (i in 0 until messages.length()) {
            val message = messages.optJSONObject(i) ?: continue
            if (message.optString("role") == "toolResult") {
                results[message.optString("toolCallId")] = message
            }
        }

        val out = mutableListOf<ChatItem>()
        val pairedResults = HashSet<String>()
        for (i in 0 until messages.length()) {
            val message = messages.optJSONObject(i) ?: continue
            val timestamp = message.optLong("timestamp", 0L)
            when (message.optString("role")) {
                "user" -> {
                    val content = message.opt("content")
                    val (images, omitted) = imagesOf(content)
                    out += ChatItem(
                        key = "user-$timestamp-$i",
                        role = "user",
                        // 图单独渲染，正文只留文字
                        text = textOnly(content).trim(),
                        images = images,
                        imagesOmitted = omitted,
                        msgIndex = i,
                    )
                }

                "assistant" -> {
                    val content = message.optJSONArray("content") ?: JSONArray()
                    val text = StringBuilder()
                    val thinking = StringBuilder()
                    for (j in 0 until content.length()) {
                        val part = content.optJSONObject(j) ?: continue
                        when (part.optString("type")) {
                            "text" -> text.append(part.optString("text"))
                            "thinking" -> thinking.append(part.optString("thinking"))
                        }
                    }
                    val usage = message.optJSONObject("usage")
                    val footer = buildString {
                        val model = message.optString("model")
                        if (model.isNotBlank()) append(model)
                        usage?.optLong("totalTokens", 0L)?.takeIf { it > 0 }?.let {
                            if (isNotEmpty()) append(" · ")
                            append("$it tok")
                        }
                        usage?.optJSONObject("cost")?.optDouble("total", 0.0)?.takeIf { it > 0 }?.let {
                            append(" · $${"%.4f".format(it)}")
                        }
                    }
                    if (text.isNotBlank() || thinking.isNotBlank() || footer.isNotBlank()) {
                        out += ChatItem(
                            key = assistantKey(message),
                            role = "assistant",
                            text = text.toString().trim(),
                            thinking = thinking.toString().trim(),
                            footer = footer,
                        )
                    }
                    for (j in 0 until content.length()) {
                        val part = content.optJSONObject(j) ?: continue
                        if (part.optString("type") != "toolCall") continue
                        val id = part.optString("id")
                        pairedResults += id
                        out += toolItem(
                            id = id,
                            name = part.optString("name"),
                            args = part.optJSONObject("arguments"),
                            display = part.optJSONObject("display"),
                            html = results[id]?.optJSONObject("html") ?: part.optJSONObject("html"),
                            term = results[id]?.optJSONObject("term") ?: part.optJSONObject("term"),
                            result = results[id],
                            fallbackKey = "tool-$timestamp-$i-$j",
                        )
                    }
                }

                "toolResult" -> {
                    val id = message.optString("toolCallId")
                    if (id !in pairedResults) {
                        out += toolItem(
                            id = id,
                            name = message.optString("toolName"),
                            args = null,
                            result = message,
                            html = message.optJSONObject("html"),
                            term = message.optJSONObject("term"),
                            fallbackKey = "tool-$timestamp-$i",
                        )
                    }
                }

                // 终端里 `!cmd` 跑出来的命令记录（pi 的 BashExecutionMessage）。
                // 以前 app 直接丢掉了 —— 在终端敲过 `!` 的人，回到手机看不到那条命令。
                "bashExecution" -> {
                    out += ChatItem(
                        key = "bash-$timestamp-$i",
                        role = "bash",
                        text = message.optString("command"),
                        toolResult = message.optString("output"),
                        toolIsError = message.optInt("exitCode", 0) != 0 ||
                            message.optBoolean("cancelled"),
                        // 复用 footer 位置显示 `exit 1 · 已截断`
                        footer = buildString {
                            if (message.optBoolean("cancelled")) append("已取消")
                            else append("exit ${message.optInt("exitCode", 0)}")
                            if (message.optBoolean("truncated")) append(" · 输出已截断")
                            if (message.optBoolean("excludeFromContext")) append(" · 不进上下文")
                        },
                    )
                }

                // 分叉时带上摘要（pi 的 branchWithSummary）：终端会显示一张卡，app 以前完全不显示
                "branchSummary" -> {
                    out += ChatItem(
                        key = "branch-$timestamp-$i",
                        role = "branch",
                        text = message.optString("summary"),
                        footer = "分支摘要",
                    )
                }

                // 一次压缩的摘要（有些路径以消息形式出现，而不是事件）
                "compactionSummary" -> {
                    out += ChatItem(
                        key = "compaction-$timestamp-$i",
                        role = "compactionSummary",
                        text = message.optString("summary"),
                        footer = "压缩摘要 · 从 " +
                            id.harness.pipocket.ui.formatTokens(message.optLong("tokensBefore", 0)) + " tokens",
                    )
                }

                else -> Unit
            }
        }
        return out
    }
}
