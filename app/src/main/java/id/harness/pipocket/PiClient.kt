package id.harness.pipocket

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 与 Termux 侧桥（pi-pocket-bridge）说话的唯一入口。
 *
 * 桥的契约见 termux/README.md：所有端点返回 `{ok, data}` / `{ok:false, error}`，
 * 事件走 `/v1/events` 的 SSE。这里只做搬运与解析，不缓存业务状态（状态在 AppState）。
 */
class PiClient(private val baseUrl: () -> String, private val token: () -> String? = { null }) {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private fun url(path: String) = baseUrl().trimEnd('/') + path

    private fun authed(builder: Request.Builder): Request.Builder {
        val t = token()?.trim().orEmpty()
        return if (t.isEmpty()) builder else builder.header("Authorization", "Bearer $t")
    }

    private fun get(path: String, timeoutSeconds: Long = 20): JSONObject {
        val request = authed(Request.Builder().url(url(path))).get().build()
        client.newBuilder().readTimeout(timeoutSeconds, TimeUnit.SECONDS).build().newCall(request).execute().use { response ->
            return unwrap(response)
        }
    }

    private fun post(path: String, body: JSONObject?, timeoutSeconds: Long = 120): JSONObject {
        val payload = (body ?: JSONObject()).toString().toRequestBody(jsonType)
        val request = authed(Request.Builder().url(url(path))).post(payload).build()
        client.newBuilder().readTimeout(timeoutSeconds, TimeUnit.SECONDS).build().newCall(request).execute().use { response ->
            return unwrap(response)
        }
    }

    /** 裸 JSON 调用（给 [RemoteFs] 这类"走桥但不属于 pi RPC"的接口用）。 */
    fun getJson(path: String, timeoutSeconds: Long = 20): JSONObject {
        val r = get(path, timeoutSeconds)
        return r.optJSONObject("data") ?: r
    }

    fun postJson(path: String, body: JSONObject?, timeoutSeconds: Long = 60): JSONObject {
        val r = post(path, body, timeoutSeconds)
        return r.optJSONObject("data") ?: r
    }

    private fun unwrap(response: Response): JSONObject {
        val text = response.body?.string().orEmpty()
        val parsed = if (text.isBlank()) JSONObject() else JSONObject(text)
        if (!response.isSuccessful) {
            throw IllegalStateException(parsed.optString("error", "HTTP ${response.code}"))
        }
        if (!parsed.optBoolean("ok", false)) {
            throw IllegalStateException(parsed.optString("error", "bridge returned ok=false"))
        }
        return parsed.optJSONObject("data") ?: JSONObject()
    }

    // ── 读 ────────────────────────────────────────────────────────────────
    fun health(): JSONObject = get("/v1/health", 8)
    fun info(): JSONObject = get("/v1/info", 60)
    fun sessions(limit: Int = 40): List<SessionInfo> {
        // 会话多/文件大时桥要读盘，给足超时（原来默认 20s 会误判为空列表）
        val array = get("/v1/sessions?limit=$limit", 90).optJSONArray("sessions") ?: JSONArray()
        return (0 until array.length()).map { SessionInfo.from(array.getJSONObject(it)) }
    }

    /**
     * 会话历史。
     *
     * `full = false`（默认）时桥会把图片剥成占位符（省流量）；要回看历史图时用 `full = true` 再拉一次，
     * 拿到的 `msgIndex` 用来把原图贴回对应的那一行。
     */
    fun messages(full: Boolean = false, cols: Int = 0): List<ChatItem> {
        // cols = app 屏幕能放下的等宽字符数。桥按这个宽度调 pi 的渲染器 ⇒ 工具块的换行点与终端一致（1:1）。
        val q = buildString {
            if (full) append("full=1")
            if (cols > 10) {
                if (isNotEmpty()) append('&')
                append("cols=").append(cols)
            }
        }
        val array = get(if (q.isEmpty()) "/v1/messages" else "/v1/messages?" + q, if (full) 180 else 60)
            .optJSONArray("messages") ?: JSONArray()
        return MessageParser.history(array)
    }

    /** 看交付物：读桥所在机器上的一个路径（文本/图片/目录）。 */
    fun file(path: String): JSONObject =
        get("/v1/file?path=" + java.net.URLEncoder.encode(path, "UTF-8"), 60)

    /** app 会话注入清单（哪些 skills / MCP / 附加提示会被注入到 app 起的会话里）。 */
    /** 强制重启桥（桥自己派 detach 脚本重启，随后这次连接会断）。 */
    /** 清空排队并取回文本（pi 的 clear_queue）。 */
    fun clearQueue(): JSONObject = post("/v1/queue/clear", JSONObject(), 30)

    fun restartBridge(): JSONObject = post("/v1/restart", JSONObject(), 30)

    /**
     * 上报屏幕/排版信息（桥把它拼进注入给 pi 的提示，见 /v1/device）。
     * 超时给短一点：这是"锦上添花"的一次调用，别为了它把连接卡住。
     */
    fun device(info: DeviceInfo): JSONObject = post("/v1/device", info.toJson(), 15)

    fun appProfile(): AppProfile = AppProfile.from(get("/v1/app-profile", 20))

    /** 改开开关（桥会持久化并回收当前 pi 子进程，下次请求按新选择重新拉起）。 */
    fun setAppProfile(body: JSONObject): AppProfile = AppProfile.from(post("/v1/app-profile", body, 30))

    fun models(): List<ModelRef> {
        val data = get("/v1/models", 60)
        val array = data.optJSONArray("models") ?: JSONArray()
        return (0 until array.length()).mapNotNull { ModelRef.from(array.optJSONObject(it) ?: return@mapNotNull null) }
    }

    /** 从桥所在机器的配置（~/.ssh/config、pi-pocket-hosts.json）扫到的机器。 */
    fun configMachines(): org.json.JSONArray =
        get("/v1/config/machines", 30).optJSONArray("machines") ?: org.json.JSONArray()

    /**
     * pi 的 provider 清单（`~/.pi/agent/models.json` 的 providers + `auth.json` 有没有 key）。
     *
     * 桥**不回传 key 原文**，只有 `hasKey`；app 侧永远拿不到、也不应该缓存密钥。
     */
    fun providers(): List<ProviderInfo> {
        val array = get("/v1/providers", 30).optJSONArray("providers") ?: JSONArray()
        return (0 until array.length()).mapNotNull { i ->
            ProviderInfo.from(array.optJSONObject(i) ?: return@mapNotNull null)
        }
    }

    /**
     * upsert 一个 provider（桥写 models.json 的 providers[name] + auth.json 的 `{type:'api_key',key}`，都是 600）。
     *
     * `apiKey` 为空 = **只改配置、不动已有 key**（编辑时表单留空的语义）。
     */
    fun saveProvider(name: String, baseUrl: String, api: String, models: List<String>, apiKey: String?): JSONObject {
        val body = JSONObject().put("name", name).put("baseUrl", baseUrl).put("api", api)
        val arr = JSONArray()
        models.forEach { arr.put(it) }
        body.put("models", arr)
        if (!apiKey.isNullOrBlank()) body.put("apiKey", apiKey.trim())
        return post("/v1/providers", body, 30)
    }

    /** 删除 provider：桥会从 models.json 与 auth.json **同时**删。 */
    fun removeProvider(name: String): JSONObject =
        post("/v1/providers/remove", JSONObject().put("name", name), 30)

    /** pi 内置命令（继承终端）。 */
    fun slashCommands(): List<SlashCmd> {
        val arr = get("/v1/slash", 30).optJSONArray("commands") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { SlashCmd.from(it) } }
    }

    fun commands(): List<CommandRef> {
        val data = get("/v1/commands", 30)
        val array = data.optJSONArray("commands") ?: JSONArray()
        return (0 until array.length()).mapNotNull { CommandRef.from(array.optJSONObject(it) ?: return@mapNotNull null) }
    }

    // ── 写 ────────────────────────────────────────────────────────────────
    /** 重载会话：桥重启 pi 子进程并重开这个会话文件（扩展/技能/注入配置重新加载）。 */
    fun reload(file: String?): JSONObject =
        post("/v1/reload", JSONObject().apply { file?.let { put("file", it) } }, 180)

    fun openSession(file: String) = post("/v1/sessions/open", JSONObject().put("file", file), 120)
    fun newSession(cwd: String?) = post("/v1/sessions/new", JSONObject().apply { cwd?.let { put("cwd", it) } }, 120)

    fun prompt(
        text: String,
        images: List<Attachment>,
        steer: Boolean = false,
        streamingBehavior: String? = null,
    ): JSONObject {
        val body = JSONObject().put("text", text)
        if (images.isNotEmpty()) {
            val array = JSONArray()
            images.forEach {
                array.put(JSONObject().put("type", "image").put("mimeType", it.mimeType).put("data", it.base64))
            }
            body.put("images", array)
        }
        if (steer) body.put("steer", true)
        // 流式中必须给 streamingBehavior（pi 的 rpc-commands.md：steer = 插到本轮工具执行后；followUp = 跑完再做）
        streamingBehavior?.let { body.put("streamingBehavior", it) }
        return post("/v1/prompt", body, 180)
    }

    fun abort() = post("/v1/abort", null, 300)
    fun setModel(provider: String, model: String) =
        post("/v1/model", JSONObject().put("provider", provider).put("model", model), 120)

    fun setThinking(level: String) = post("/v1/thinking", JSONObject().put("level", level), 120)

    /**
     * 快切思考等级（pi 的 `cycle_thinking_level`；终端是 `app.thinking.cycle` 键）。
     * 模型没有可选等级时 pi 返回 null ⇒ 这里回 null。
     */
    fun cycleThinking(): String? {
        val data = post("/v1/thinking/cycle", JSONObject(), 120)
        return data.optString("thinking").ifBlank { null }
    }

    /** 快切模型（pi 的 `cycle_model`；终端是 cycleForward/Backward 两个键）。 */
    fun cycleModel(backward: Boolean = false): JSONObject =
        post("/v1/model", JSONObject().put("cycle", if (backward) "backward" else "forward"), 120)
    fun compact(instructions: String? = null) = post(
        "/v1/compact",
        instructions?.takeIf { it.isNotBlank() }?.let { JSONObject().put("instructions", it) },
        900,
    )
    fun rename(name: String) = post("/v1/rename", JSONObject().put("name", name), 60)

    // ── 会话树 / 分叉（pi 的 get_tree · get_fork_messages · fork · clone）──────

    /** 会话树（扁平 DFS 序，每行带 `parentId`）。app 侧自己算过滤/连接线/文案。 */
    internal fun tree(): SessionTreeSnapshot {
        val data = get("/v1/tree", 120)
        return SessionTreeSnapshot(
            leafId = data.optString("leafId").ifBlank { null },
            nodes = id.harness.pipocket.ui.TreeEntry.list(data.optJSONArray("nodes")),
        )
    }

    /** 可分叉的候选（pi 的 `get_fork_messages`：只有用户消息）。 */
    fun forkMessages(): List<ForkPoint> {
        val arr = get("/v1/fork-messages", 60).optJSONArray("messages") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val entryId = o.optString("entryId").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            ForkPoint(entryId, o.optString("text"))
        }
    }

    /**
     * 从某个用户消息处**分叉**（pi 的 `fork`，默认 `position: "before"`）。
     *
     * 返回里带 `file`：pi 分叉是**开一个新会话文件** ⇒ app 必须把当前会话切过去，
     * 否则后面的消息会落回分叉前的会话（桥那边已经 rebind 好了）。
     */
    fun fork(entryId: String): ForkResult =
        ForkResult.from(post("/v1/fork", JSONObject().put("entryId", entryId), 300))

    /** 复制当前会话（pi 的 `clone` = 在 leaf 处 `fork({position:"at"})`）。 */
    fun cloneSession(): ForkResult = ForkResult.from(post("/v1/clone", JSONObject(), 300))

    // ── 行为开关（pi 的 /settings 那几项）────────────────────────────

    /** 读当前会话的行为开关（含 autoRetry —— 它存在 pi 的 settings.json 里）。 */
    internal fun behavior(): id.harness.pipocket.ui.BehaviorState =
        id.harness.pipocket.ui.BehaviorState.from(get("/v1/behavior", 60))

    /** 改行为开关；只传要改的项（其余保持）。 */
    internal fun setBehavior(
        autoCompaction: Boolean? = null,
        autoRetry: Boolean? = null,
        steeringMode: String? = null,
        followUpMode: String? = null,
    ): id.harness.pipocket.ui.BehaviorState {
        val body = JSONObject()
        autoCompaction?.let { body.put("autoCompaction", it) }
        autoRetry?.let { body.put("autoRetry", it) }
        steeringMode?.let { body.put("steeringMode", it) }
        followUpMode?.let { body.put("followUpMode", it) }
        return id.harness.pipocket.ui.BehaviorState.from(post("/v1/behavior", body, 120))
    }

    // ── 交付 / 导出（pi 的 export_html · get_last_assistant_text）──────────────

    /**
     * 导出当前会话为自包含 HTML（终端 `/export`）。
     *
     * [path] 为空时交给 pi 决定（写到 worker 的 cwd，文件名 `pi-session-<会话名>.html`，
     * 见 `core/export-html/index.ts:274-281`）。
     */
    fun exportHtml(path: String? = null): ExportResult {
        val body = JSONObject()
        if (!path.isNullOrBlank()) body.put("path", path.trim())
        return ExportResult.from(post("/v1/export", body, 300))
    }

    /** 最后一条回答的纯文本（终端 `/copy`）；没有就空串。 */
    fun lastAssistantText(): String = get("/v1/last-text", 60).optString("text", "")

    /** 中断自动重试（pi 的 `abort_retry`；终端是倒计时里按 Esc）。 */
    fun abortRetry() = post("/v1/retry/abort", JSONObject(), 60)
    /**
     * 应答扩展 UI 请求。
     *
     * [file] = 发出请求的会话文件：pi 的 UI 请求是 per-worker 的，必须回给那个 worker。
     * 不传就退回桥的“当前会话”（旧行为，可能把应答写进另一个会话的 pi 进程）。
     */
    fun answerUi(id: String, response: JSONObject, file: String? = null) =
        post(
            "/v1/ui",
            JSONObject().put("id", id).put("response", response).apply { if (file != null) put("file", file) },
            30,
        )

    /** 完整执行面：调用 pi 的 RPC `bash`（输出同步经 SSE 的 bash_execution_update 流回）。 */
    fun bash(id: String, command: String, excludeFromContext: Boolean): JSONObject {
        val body = JSONObject().put("id", id).put("command", command)
        if (excludeFromContext) body.put("excludeFromContext", true)
        return post("/v1/bash", body, 660)
    }

    fun bashAbort() = post("/v1/bash/abort", null, 60)

    // ── 事件流 ────────────────────────────────────────────────────────────
    fun connectEvents(listener: EventSourceListener): EventSource {
        val request = authed(Request.Builder().url(url("/v1/events"))).build()
        return EventSources.createFactory(client).newEventSource(request, listener)
    }
}

/** 会话树快照（桥的 `GET /v1/tree`）。 */
internal data class SessionTreeSnapshot(val leafId: String?, val nodes: List<id.harness.pipocket.ui.TreeEntry>)

/** 导出结果（`POST /v1/export`）。 */
data class ExportResult(val path: String?, val bytes: Long?) {
    companion object {
        fun from(o: JSONObject) = ExportResult(
            path = o.optString("path").ifBlank { null },
            bytes = if (o.has("bytes") && !o.isNull("bytes")) o.optLong("bytes") else null,
        )
    }
}

/** 可分叉的位置（pi 的 `get_fork_messages` 一行）。 */
data class ForkPoint(val entryId: String, val text: String)

/** fork / clone 的结果。 */
data class ForkResult(
    val cancelled: Boolean,
    /** 分叉后**新会话**的文件路径（取消时为空）。 */
    val file: String?,
    /** fork 时带回来、应该填回输入框的那个用户消息文本（`position: "before"`）。 */
    val text: String,
    val kind: String,
) {
    companion object {
        fun from(obj: JSONObject) = ForkResult(
            cancelled = obj.optBoolean("cancelled"),
            file = obj.optString("file").ifBlank { null },
            text = obj.optString("text"),
            kind = obj.optString("kind", "fork"),
        )
    }
}

/** app 侧的会话条目（桥的 /v1/sessions 一行）。 */
data class SessionInfo(
    val file: String,
    val id: String,
    val cwd: String?,
    val name: String?,
    val updatedAt: String,
    val bytes: Long,
    val createdAt: String = "",
    /** 桥说这条会话正在跑（权威标记；app 的 runningFiles 只是补充）。 */
    val running: Boolean = false,
) {
    val title: String get() = name?.takeIf { it.isNotBlank() } ?: id.take(8)

    private fun parse(iso: String?): Long? = iso?.takeIf { it.isNotBlank() }?.let {
        runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull()
    }

    val updatedMillis: Long? get() = parse(updatedAt)
    val createdMillis: Long? get() = parse(createdAt)

    companion object {
        fun from(obj: JSONObject) = SessionInfo(
            file = obj.optString("file"),
            id = obj.optString("id"),
            cwd = obj.optString("cwd").takeIf { it.isNotBlank() && it != "null" },
            name = obj.optString("name").takeIf { it.isNotBlank() && it != "null" },
            updatedAt = obj.optString("updatedAt"),
            bytes = obj.optLong("bytes"),
            createdAt = obj.optString("createdAt"),
            running = obj.optBoolean("running"),
        )
    }
}

/** 可用模型（`provider/model` 对）。 */
data class ModelRef(val provider: String, val model: String, val label: String) {
    companion object {
        fun from(obj: JSONObject): ModelRef? {
            val model = obj.optString("id").takeIf { it.isNotBlank() }
                ?: obj.optString("model").takeIf { it.isNotBlank() }
                ?: obj.optJSONObject("model")?.optString("id")?.takeIf { it.isNotBlank() }
                ?: return null
            val provider = obj.optString("provider").takeIf { it.isNotBlank() }
                ?: obj.optJSONObject("model")?.optString("provider").orEmpty()
            return ModelRef(provider, model, if (provider.isBlank()) model else "$provider/$model")
        }
    }
}

/** pi 的内置斜杠命令（桥从 pi 自己的交互模式里抠出来，见 `/v1/slash`）。 */
data class SlashCmd(
    val name: String,
    val args: Boolean,
    val argsHint: String,
    val rpc: String?,
    val description: String,
    val available: Boolean,
) {
    companion object {
        fun from(obj: JSONObject): SlashCmd? {
            val name = obj.optString("name").takeIf { it.isNotBlank() } ?: return null
            return SlashCmd(
                name = if (name.startsWith("/")) name else "/$name",
                args = obj.optBoolean("args"),
                argsHint = obj.optString("argsHint"),
                rpc = obj.optString("rpc").takeIf { it.isNotBlank() },
                description = obj.optString("description"),
                available = obj.optBoolean("available"),
            )
        }
    }
}

/** 斜杠命令 / skill / 模板（桥的 /v1/commands）。 */
data class CommandRef(val name: String, val description: String) {
    companion object {
        fun from(obj: JSONObject): CommandRef? {
            val raw = obj.optString("name").takeIf { it.isNotBlank() } ?: obj.optString("command").takeIf { it.isNotBlank() }
                ?: return null
            val name = if (raw.startsWith("/")) raw else "/$raw"
            return CommandRef(name, obj.optString("description"))
        }
    }
}

/** 待发送的图片附件。 */
data class Attachment(val mimeType: String, val base64: String, val bytes: Int) {
    val approxKb: Int get() = bytes / 1024
}
