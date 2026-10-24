package id.harness.pipocket

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import org.json.JSONArray
import org.json.JSONObject

/** 桥给的一份「此刻状态」（/v1/info 的收敛形状），UI 只读它。 */
data class PiInfo(
    val model: String = "",
    val provider: String = "",
    /** 桥按 pi 终端 footer 口径算好的模型那行：`id • thinking [→ 真正应答的 id]`。 */
    val modelDisplay: String = "",
    val thinking: String = "",
    val levels: List<String> = emptyList(),
    val cwd: String = "",
    val sessionId: String = "",
    val sessionName: String = "",
    val contextPercent: Double? = null,
    val contextTokens: Long = 0,
    val contextWindow: Long = 0,
    val totalTokens: Long = 0,
    // ↓ pi 终端 footer 那串统计（`↑in ↓out Rcache Wcache CHxx% $cost`）的四项原始值。
    //   以前 app 只留了 total：用户看不到"这一轮花了多少/缓存命中多少"。
    val tokensInput: Long = 0,
    val tokensOutput: Long = 0,
    val cacheRead: Long = 0,
    val cacheWrite: Long = 0,
    val cost: Double = 0.0,
    /** 自动压缩开着（`get_state.autoCompactionEnabled`）—— footer 里给 `(auto)` 标记。 */
    val autoCompaction: Boolean = false,
    /** 当前工作目录的 git 分支（终端 footer 的 `~/p (main)`）；不是仓库就是空。 */
    val gitBranch: String = "",
    val userMessages: Int = 0,
    val assistantMessages: Int = 0,
    val toolCalls: Int = 0,
    val isStreaming: Boolean = false,
    /** pi 说「这个会话里还有几条排队的消息」（`get_state.pendingMessageCount`，权威）。 */
    val pendingCount: Int = 0,
)

/** pi 扩展发来的**需应答**对话框（见 pi docs/rpc-extension-ui.md）。 */
data class UiPrompt(
    val id: String,
    val method: String,
    val title: String,
    val message: String,
    val options: List<String>,
    val placeholder: String,
    val prefill: String,
    val timeoutMs: Long?,
    /**
     * 这条请求来自**哪个会话**（pi 的扩展 UI 请求是 per-worker 的）。
     *
     * 为什么要记：pi 的 `/v1/ui` 应答**必须回给发出请求的那个 worker**，否则
     * 在 A 会话弹的 confirm、用户在 B 会话点「确定」→ 应答写进了 B 的 pi 进程，
     * A 那边一直等到超时（`HANDOFF` §22）。
     */
    val file: String? = null,
)

/**
 * 会话态归属校验（**同一个坑踩过三次，别再各写一份**）。
 *
 * 背景：pi 的事件流是**所有 worker 混在一起**的（桥按会话标 `sessionFile`），而 app 里不少界面状态
 * 是**全局字段**：排队条（`queueFile`）、工作状态（`streamingFile`）、状态行（`statusLineFile`）、
 * 压缩卡（`compactionCardFile`）。这些字段以前都不带归属 ⇒ A 会话的临时状态漂到 B 会话，
 * 用户三次截图分别报过：排队消息跨会话 / 工作状态跨会话 / 「上下文已压缩」跨会话。
 *
 * 口径：
 * - [globalOwnerAllowed] = true 时 `owner == null` 表示「与会话无关」（桥重启/扫描/连接），谁看都该看到；
 * - 会话自己的东西（压缩卡、排队条）必须 `owner == 当前会话`；归属为 null 的旧数据一律不显示
 *   —— **宁可不显示，也不显示错的**。
 */
internal fun ownerMatchesSession(
    owner: String?,
    currentFile: String?,
    globalOwnerAllowed: Boolean,
): Boolean = when {
    owner == null -> globalOwnerAllowed
    currentFile == null -> false
    else -> owner == currentFile
}

/** 主题模式：跟随系统 / 强制浅色 / 强制深色。 */
enum class ThemeMode { System, Light, Dark }

enum class Connection { Unknown, Ok, Failed }

/** 会话列表的分组 / 排序 / 过滤维度（都是用户 2026-10-18 要的「完备」那一套）。 */
enum class SessionGroup { Project, Timeline }

enum class SessionSort { Updated, Created, Name }

enum class SessionTime { All, Day, Week }

/** 记住的机器（探测过就留下，下次直接探它，不再全量子网扫描）。 */
data class KnownMachine(
    val url: String,
    val host: String,
    val scope: String,
    val authRequired: Boolean,
    val lastSeenMs: Long,
    val online: Boolean = false,
)

/**
 * 进程级状态：连接（本机 / 局域网 / 远端）、会话列表、当前会话、转录、SSE 流、状态行。
 * 单例而非 ViewModel：这个 app 只有一个「当前会话」，且旋转/重建后流不能断。
 */
object AppState {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var prefs: android.content.SharedPreferences? = null
    private var eventSource: EventSource? = null
    private val eventGeneration = EventGeneration()

    var baseUrl by mutableStateOf("http://127.0.0.1:8789")
        private set
    var token by mutableStateOf("")
        private set
    var savedHosts by mutableStateOf<List<String>>(emptyList())
        private set

    // 会话列表视图状态（持久化）
    var sessionGroup by mutableStateOf(SessionGroup.Project)
        private set
    var sessionSort by mutableStateOf(SessionSort.Updated)
        private set
    var sessionTime by mutableStateOf(SessionTime.All)
        private set
    var projectFilter by mutableStateOf<Set<String>>(emptySet())
        private set
    var collapsedGroups by mutableStateOf<Set<String>>(emptySet())
        private set

    /**
     * 展开/收起状态：`会话文件|item key` → 是否展开（thinking / 工具块 / 提醒 / 状态条 共用）。
     *
     * 刻意放在这里，而不是各 Composable 里的 `remember(item.key)`：LazyColumn 回收 item 后 remember 就丢了，
     * 于是**滑动一下开合状态就变回默认**（用户 2026-10-18 报的）。放 AppState 后：滑动、切页、重启都保持。
     */
    var openStates by mutableStateOf<Map<String, Boolean>>(emptyMap())
        private set

    /** 字号：全局倍率 + 对话（消息）额外倍率，都是 1.0 为基准。 */
    /** 可 SSH 过去的机器（用来把桥拉起来）。 */
    var sshTargets by mutableStateOf<List<SshTarget>>(emptyList())
        private set
    var sshBusy by mutableStateOf(false)
        private set
    var sshMessage by mutableStateOf<String?>(null)
        private set

    var themeMode by mutableStateOf(ThemeMode.System)
        private set

    var fontScale by mutableStateOf(1.0f)
        private set
    var chatFontScale by mutableStateOf(1.0f)
        private set

    // ── 杂项（Miscellaneous）：显示类可调参数，全部存 prefs，改完立即生效 ──────────────
    /** 工具卡**收起**时露几行（用户 2026-10-21：默认 3）。 */
    var toolCollapsedLines by mutableStateOf(3)
        private set
    /** 工具卡**展开**时的最大高度（dp），超出内部滚动。 */
    var toolExpandedMaxDp by mutableStateOf(420)
        private set
    /** 工具卡**收起**时的最大高度（dp）。 */
    var toolCollapsedMaxDp by mutableStateOf(220)
        private set
    /** 终端渲染列数：0 = 按屏幕宽度自动算；>0 = 手动指定（30..200）。 */
    var termColsOverride by mutableStateOf(0)
        private set

    // ── 字号（四档，用户 2026-10-21：也做成可配）──────────────────────────────────
    /** 正文（助手回复 / thinking / 用户消息）。 */
    var fontBodySp by mutableStateOf(12)
        private set
    /** 工具卡：工具名 / 标题行。 */
    var fontToolNameSp by mutableStateOf(12)
        private set
    /** 工具卡：参数 / 输出 / 终端行。 */
    var fontToolSp by mutableStateOf(11)
        private set
    /** 次要信息（footer / 计数 / 提示）。 */
    var fontMetaSp by mutableStateOf(11)
        private set
    /** 输入框 / 按钮。 */
    var fontInputSp by mutableStateOf(14)
        private set

    private fun loadSshTargets(p: android.content.SharedPreferences) {
        val raw = p.getString("sshTargets", null) ?: return
        sshTargets = parseSshTargets(raw)
    }

    fun saveSshTarget(target: SshTarget) {
        sshTargets = (listOf(target) + sshTargets.filterNot { it.key == target.key }).take(10)
        persistSshTargets()
    }

    fun removeSshTarget(key: String) {
        sshTargets = sshTargets.filterNot { it.key == key }
        persistSshTargets()
    }

    private fun persistSshTargets() {
        val arr = JSONArray()
        sshTargets.forEach { t ->
            arr.put(
                JSONObject()
                    .put("host", t.host).put("port", t.port).put("user", t.user)
                    .put("authKind", t.authKind).put("password", t.password)
                    .put("privateKey", t.privateKey).put("passphrase", t.passphrase)
                    // 默认命令属于代码，不落盘：DEFAULT_START 升级时已存目标自动跟上
                    .put("startCommand", if (t.startCommand == SshTarget.DEFAULT_START) "" else t.startCommand),
            )
        }
        prefs?.edit()?.putString("sshTargets", arr.toString())?.apply()
    }

    fun clearSshMessage() {
        sshMessage = null
    }

    /**
     * 通过 SSH 在目标机器上把桥拉起来，然后自动探测并连过去。
     * 场景：桥没起 / 机器重启过 / 只想让 app 顺手把服务拉起来。
     */
    /** 扫到的机器能不能"顺手拉一把"：有配好 SSH 的这台机器就能。 */
    fun sshTargetFor(host: String?): SshTarget? {
        if (host.isNullOrBlank()) return null
        return sshTargets.firstOrNull { it.host == host }
    }

    /** 扫描卡上的「拉起 pi」：远端起桥连过去；本机就走 Termux RUN_COMMAND。 */
    /** 配置里扫到的机器：拉一次桥（有 SSH 目标就 SSH 上去起；本机走 Termux）。 */
    fun launchBridgeForConfigHit(context: Context, hit: BridgeHit) =
        launchBridgeFor(context, hit.host, hit.url)

    fun launchBridgeFor(context: Context, host: String?, url: String?) {
        val ssh = sshTargetFor(host)
        if (ssh != null) {
            launchRemoteBridge(ssh, thenConnectUrl = url)
            return
        }
        // 本机（127.0.0.1 / 10.0.2.2 / 机器名）没有 SSH 目标 → 用 Termux 的 RUN_COMMAND
        launchLocalBridge(context)
    }

    fun launchRemoteBridge(target: SshTarget, thenConnectUrl: String? = null) {
        if (sshBusy) return
        scope.launch {
            sshBusy = true
            sshMessage = "SSH 连接 " + target.key + " …"
            val result = withContext(Dispatchers.IO) { SshLauncher.exec(target) }
            sshBusy = false
            if (!result.ok) {
                sshMessage = "SSH 失败：" + (result.error ?: result.output)
                return@launch
            }
            sshMessage = "远端已执行（" + result.output.take(60) + "），正在探测桥…"
            val url = thenConnectUrl?.takeIf { it.isNotBlank() } ?: ("http://" + target.host + ":8789")
            val hit = withContext(Dispatchers.IO) { PiLocator.probe(url, token) }
            if (hit != null) {
                adopt(hit)
                saveMachine(hit)
                sshMessage = "桥已在 " + hit.url + " 就绪"
            } else {
                sshMessage = "命令执行成功但探不到桥（" + url + "）；检查端口 / token / 桥是否在跑"
            }
        }
    }

    fun updateThemeMode(value: ThemeMode) {
        themeMode = value
        prefs?.edit()?.putString("themeMode", value.name)?.apply()
    }

    /** 结合系统当前深浅决定实际明暗。 */
    fun isDark(systemDark: Boolean): Boolean = when (themeMode) {
        ThemeMode.System -> systemDark
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }

    fun updateFontScale(value: Float) {
        fontScale = value.coerceIn(0.8f, 1.6f)
        prefs?.edit()?.putFloat("fontScale", fontScale)?.apply()
    }

    fun updateChatFontScale(value: Float) {
        chatFontScale = value.coerceIn(0.8f, 1.6f)
        prefs?.edit()?.putFloat("chatFontScale", chatFontScale)?.apply()
    }

    fun resetFontScales() {
        updateFontScale(1f)
        updateChatFontScale(1f)
    }

    // ── 杂项 setters（都在 SettingsSheet 的「杂项」里）────────────────────────────
    fun updateToolCollapsedLines(v: Int) {
        toolCollapsedLines = v.coerceIn(1, 20)
        prefs?.edit()?.putInt("toolCollapsedLines", toolCollapsedLines)?.apply()
    }

    fun updateToolExpandedMaxDp(v: Int) {
        toolExpandedMaxDp = v.coerceIn(160, 1200)
        prefs?.edit()?.putInt("toolExpandedMaxDp", toolExpandedMaxDp)?.apply()
    }

    fun updateToolCollapsedMaxDp(v: Int) {
        toolCollapsedMaxDp = v.coerceIn(80, 600)
        prefs?.edit()?.putInt("toolCollapsedMaxDp", toolCollapsedMaxDp)?.apply()
    }

    /** 0 = 自动（按屏幕宽度）；>0 = 手动列数。改完让下次拉消息带上新列数。 */
    fun updateTermColsOverride(v: Int) {
        termColsOverride = v.coerceIn(0, 200)
        prefs?.edit()?.putInt("termColsOverride", termColsOverride)?.apply()
        if (termColsOverride > 0) termCols = termColsOverride
    }

    fun updateFontBodySp(v: Int) {
        fontBodySp = v.coerceIn(9, 24); prefs?.edit()?.putInt("fontBodySp", fontBodySp)?.apply()
    }

    fun updateFontToolNameSp(v: Int) {
        fontToolNameSp = v.coerceIn(9, 24); prefs?.edit()?.putInt("fontToolNameSp", fontToolNameSp)?.apply()
    }

    fun updateFontToolSp(v: Int) {
        fontToolSp = v.coerceIn(8, 24); prefs?.edit()?.putInt("fontToolSp", fontToolSp)?.apply()
    }

    fun updateFontMetaSp(v: Int) {
        fontMetaSp = v.coerceIn(8, 24); prefs?.edit()?.putInt("fontMetaSp", fontMetaSp)?.apply()
    }

    fun updateFontInputSp(v: Int) {
        fontInputSp = v.coerceIn(10, 26); prefs?.edit()?.putInt("fontInputSp", fontInputSp)?.apply()
    }

    fun resetMiscDefaults() {
        updateToolCollapsedLines(3)
        updateToolExpandedMaxDp(420)
        updateToolCollapsedMaxDp(220)
        updateTermColsOverride(0)
        updateFontBodySp(12)
        updateFontToolNameSp(12)
        updateFontToolSp(11)
        updateFontMetaSp(11)
        updateFontInputSp(14)
    }

    fun toggleGroup(cwd: String) {
        collapsedGroups = if (collapsedGroups.contains(cwd)) collapsedGroups - cwd else collapsedGroups + cwd
        prefs?.edit()?.putStringSet("collapsedGroups", collapsedGroups)?.apply()
    }

    var connection by mutableStateOf(Connection.Unknown)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var statusLine by mutableStateOf<String?>(null)
        private set

    /**
     * 这行状态**属于哪个会话**（null = 与会话无关：桥重启、扫描、连接……）。
     *
     * 为什么要有归属：`statusLine` 是**一个全局字段**，而写它的地方有 20 多处，
     * 其中一半是**会话事件**（工具失败、压缩、重试、扩展 setStatus…）。以前不记归属、
     * 切会话也不清 ⇒ A 会话的「工具失败：exec_command」直接漂到 B 会话的输入区上方
     * （用户 2026-10-21 报的「上下文已压缩跨会话」同一类脏数据）。
     * 口径：**会话事件的归属 = 当前会话；全局事件归属 = null**（谁看都该看到）。
     */
    private var statusLineFile: String? = null

    /** 界面读的状态行：只显示「属于当前会话」或「与会话无关」的那份。 */
    val statusText: String?
        get() = statusLine?.takeIf { ownerMatchesSession(statusLineFile, current?.file, globalOwnerAllowed = true) }

    /** 写状态行。[owner] = 这行属于哪个会话；null = 与会话无关（桥级/扫描级）。 */
    /**
     * 扩展状态行（终端 footer 那行）。[clearAfterMs] > 0 表示**过一会儿自动消失**：
     * 「上下文已压缩」「已清空排队」这类一次性结果以前会一直挂在输入区上方 —— 用户 2026-10-22
     * 报「上下文已压缩一直贴底显示，也是bug」。进行中的状态（正在压缩…、排队中…）则一直留着。
     */
    private fun setStatus(text: String?, owner: String? = null, clearAfterMs: Long = 0L) {
        statusLine = text
        statusLineFile = if (text == null) null else owner
        statusClearJob?.cancel()
        statusClearJob = null
        if (text != null && clearAfterMs > 0) {
            statusClearJob = scope.launch {
                delay(clearAfterMs)
                // 期间若已被别的状态顶掉，就别再动它
                if (statusLine == text) {
                    statusLine = null
                    statusLineFile = null
                }
            }
        }
    }

    /**
     * 「压缩上下文」专用卡片（title to detail）。
     *
     * 不放在 items 里：压缩完 pi 会重发历史/重载会话，items 会被整体替换，
     * 卡片就跟着消失了（用户报的「压缩过的卡片也没了」）。放在独立状态里，
     * 由界面渲染成列表末尾的一张卡，**本会话**内重载/切回都在。
     *
     * ⚠️ 2026-10-21 23:56 用户截图：这张卡漂到了**新建的空会话**里。
     * 真因不是事件串会话（pi 加载会话不会重发 compaction，旧会话文件 mtime 也没动），
     * 而是它**没有归属**：`newSession()` 不清它、`openSession()` 只在换会话时清，
     * 于是上一次压缩（20:30，`tokensBefore=401145`）留下的卡在 3.5 小时后新建会话时还在。
     * 现在带归属（`compactionCardFile`），归属对不上当前会话就**不显示**——
     * 和排队条（`queueFile`）、工作状态（`streamingFile`）同一套口径。
     */
    private var compactionCardState by mutableStateOf<CompactionCardInfo?>(null)
    private var compactionCardFile: String? = null

    /** 状态行「自动消失」的定时器（见 [setStatus]）。 */
    private var statusClearJob: Job? = null

    /** 只有属于当前会话的压缩卡才上屏（归属为 null 的旧数据也不显示，宁可不显示）。 */
    val compactionCard: CompactionCardInfo?
        get() = compactionCardState?.takeIf {
            ownerMatchesSession(compactionCardFile, current?.file, globalOwnerAllowed = false)
        }

    /**
     * 一张卡从**开始压缩**就在（`running = true`，界面上转圈），压完就地改成结果 —— 不再叠通知。
     *
     * [CompactionCardInfo.afterKey] = 建卡时列表最后一条的 key：界面据此把卡插回**时间线**上。
     * 以前卡片固定挂列表末尾 ⇒ 压缩之后新来的消息全排在它上面，卡片永远“贴底”
     * （用户 2026-10-22 报的 bug）。
     */
    private fun showCompactionCard(
        title: String,
        detail: String,
        file: String?,
        running: Boolean = false,
        summary: String = "",
    ) {
        // 同一会话再压：保留原来的时间线位置（免得卡片跟着跳）
        val keep = compactionCardState?.afterKey
            ?.takeIf { compactionCardFile != null && compactionCardFile == file }
        compactionCardState = CompactionCardInfo(
            title = title,
            detail = detail,
            running = running,
            afterKey = keep ?: items.lastOrNull()?.key.orEmpty(),
            summary = summary,
        )
        compactionCardFile = file
    }

    /** 哪些会话正在跑（多 worker 后每会话独立；列表用它打「工作中」）。 */
    var runningFiles by mutableStateOf<Set<String>>(emptySet())
        private set

    /** 排队中的 steer / followUp **文本**（来自 pi 的 queue_update，它给的是完整队列）。 */
    var queuedSteering by mutableStateOf<List<String>>(emptyList())
        private set
    var queuedFollowUp by mutableStateOf<List<String>>(emptyList())
        private set

    /**
     * 上面两份排队文本**属于哪个会话**。
     *
     * pi 的队列是 per-session 的，而 app 的这两份是全局字段 —— 以前切会话不清 ⇒
     * **A 会话排的队直接显在 B 会话里**（用户报的「排队消息跨会话显示」）。
     * 现在记住归属，切会话/关闭会话一律清干净；归属对不上就当没有。
     */
    private var queueFile: String? = null

    /** 清掉排队条的本地状态（不动 pi 的队列）。 */
    private fun clearQueueState() {
        queuedSteering = emptyList()
        queuedFollowUp = emptyList()
        queueFile = null
        if (statusLine?.startsWith("排队中") == true) setStatus(null)
    }

    /** 清空 pi 的队列（不清内容也要能取消）。 */
    fun clearQueueAll() = io {
        api.clearQueue()
        clearQueueState()
        setStatus("已清空排队", current?.file, clearAfterMs = 5_000)
    }

    /** debug 探针专用：直接塞一条排队（验证排队条的展开/编辑/取消；生产路径只来自 `queue_update`）。
     *  `|` 会换成换行（adb broadcast 的 `--es` 遇空格会拆参数，多行文本没法直传）。 */
    fun debugQueue(steering: String?, followUp: String?) {
        if (!steering.isNullOrBlank()) queuedSteering = listOf(steering.replace("|", "\n").replace("\\n", "\n"))
        if (!followUp.isNullOrBlank()) queuedFollowUp = listOf(followUp.replace("|", "\n").replace("\\n", "\n"))
        queueFile = current?.file
        val n = queuedSteering.size + queuedFollowUp.size
        setStatus(if (n > 0) "排队中：steer ${queuedSteering.size} / follow-up ${queuedFollowUp.size}" else null, current?.file)
    }

    var scanning by mutableStateOf(false)
        private set
    var scanProgress by mutableStateOf<String?>(null)

    /** 扫描时是否顺带读 Termux 上的 SSH 配置（~/.ssh/config 等），把 SSH 机器也扫进来。 */
    var scanIncludeSshConfig by mutableStateOf(true)
        private set

    fun toggleScanIncludeSshConfig() { scanIncludeSshConfig = !scanIncludeSshConfig }

    var hits by mutableStateOf<List<BridgeHit>>(emptyList())
        private set
    var knownMachines by mutableStateOf<List<KnownMachine>>(emptyList())
        private set
    var currentHit by mutableStateOf<BridgeHit?>(null)
        private set
    var launchMessage by mutableStateOf<String?>(null)
        private set

    var sessions by mutableStateOf<List<SessionInfo>>(emptyList())

    /** 正在拉会话列表（切机器 / 刷新时用它显示进度，而不是空白页）。 */
    var sessionsLoading by mutableStateOf(false)

    /** 会话列表加载失败/超时的原因（界面据此显示重试，而不是无限转圈）。 */
    var sessionsError by mutableStateOf<String?>(null)
        private set
    var current by mutableStateOf<SessionInfo?>(null)
        private set
    var items by mutableStateOf<List<ChatItem>>(emptyList())
        private set
    var streaming by mutableStateOf(false)

    /** streaming 这个真值的生效起点（本轮开始时间；界面不再用它做过期判断，只做诊断/展示）。 */
    var streamingSince by mutableStateOf(0L)
        private set

    /**
     * 事件层那个 `streaming` 真值**属于哪个会话**。
     *
     * 以前不记归属：在 A 会话说了一句话就跑起来，再切到 B（空闲）—— `streaming` 还是 true，
     * 于是 B 会话里也顶着「工作中」+ 停止按钮，最长 20s 才自愈（用户报的「工作状态跨会话错误」）。
     * 现在只认「归属 = 当前会话」的那份真值。
     */
    private var streamingFile: String? = null

    /** 打一个事件层的「本轮在跑」标记（带会话归属）。
     *
     * ⚠️ **回合级，不是消息级**：只在 `agent_start` 打 true，只在 `agent_settled`（或 pi 权威状态说停了）
     * 打 false。终端就是这么做的（逆向 `dist/modes/interactive/interactive-mode.js`）：
     * `turn_start` 亮 working 指示器、`agent_end` 清掉，**中间每条 message_end / 工具事件都不动它**。
     * 以前 app 在每条 assistant 消息 end 时也打 false：一轮里有多条消息（assistant → 工具 → assistant…），
     * 于是「工作中」每步都灭一次、下一步又亮一次 ⇒ 用户看到的「一直闪烁」。
     */
    private fun markStreaming(on: Boolean, file: String?) {
        streaming = on
        if (on) {
            streamingSince = System.currentTimeMillis()
            streamingFile = file
            ensureTurnWatchdog()
        } else {
            streamingSince = 0L
            if (file == null || streamingFile == null || streamingFile == file) streamingFile = null
        }
    }

    /**
     * 兜底：本轮开着的时候每 5s 问一次 pi 的 `get_state`。
     *
     * 为什么需要：事件漏一条 `agent_settled` 就会永久挂着「工作中」。
     * 但**不用计时器猜**（旧代码是“20s 没动静就当停了”），而是**问 pi**：
     * pi 的 `isStreaming` 是回合级的（`_isAgentRunActive`，含 post-run continuation），
     * 它说不在跑才收 —— 这就是用户说的「交付时确认停下来」。
     */
    private fun ensureTurnWatchdog() {
        if (turnWatchdog) return
        turnWatchdog = true
        scope.launch {
            try {
                while (streaming) {
                    delay(5000)
                    if (!streaming) break
                    runCatching { refreshInfo() }   // refreshInfo → applyInfo → 以 pi 的 isStreaming 为准
                }
            } finally {
                turnWatchdog = false
            }
        }
    }

    private var turnWatchdog = false

    /**
     * 本轮开始时间（working 条算已跑多久）。
     *
     * 关键：**以 pi 的权威状态为准** —— `PiInfo.isStreaming`（来自 `get_state`）、
     * 还有"有工具正在跑"，任一为真就算在跑；事件（agent_start / agent_settled）只负责即时开关。
     * 这样切页面、重连、事件漏一条都不会出现"没在跑却挂着 working"或"在跑却不显示"。
     */
    var workStartedAt by mutableStateOf<Long?>(null)

    /**
     * 是不是在跑 —— 口径与终端 pi 的 working 行对齐：**pi 说在跑就算在跑**。
     *
     * `info.isStreaming` 来自 pi 自己的 `get_state`（权威）；`streaming` 是事件层的即时开关
     * （agent_start 立刻亮，比下一次 refreshInfo 更早）；有工具在跑也算（工具执行期间 pi 一定是有活的）。
     * 注意：**不限于 thinking** —— 生成正文、跑工具、等模型、压上下文，全都算工作。
     */
    val isWorking: Boolean
        get() {
            // ① pi 的权威状态（/v1/info 读的是**当前会话**的 worker）
            if (info?.isStreaming == true) return true
            // ② 真的有工具在跑（running 只由实时事件设置，历史行不算）
            if (items.any { it.toolRunning }) return true
            // ③ 事件层的回合开关（agent_start 亮 → agent_settled 收）。
            //    **没有过期时间**：旧代码用「20s 没动静就不算」防卡死，结果长回合跑到 20s 就灭一下，
            //    用户看到的就是闪烁。防卡死交给 watchdog（问 pi，不猜）。
            if (current == null) return false
            if (streamingFile != null && streamingFile != current?.file) return false
            return streaming
        }

    /** 把工作状态与时间戳对齐（每次 refreshInfo / 关键事件后调）。 */
    fun syncWorking() {
        if (isWorking) {
            if (workStartedAt == null) workStartedAt = System.currentTimeMillis()
        } else {
            workStartedAt = null
        }
    }

    /** working 条上的"在干什么"：正在跑的工具名 → 否则就是思考/生成。 */
    val workActivity: String
        get() {
            // 工具名用**短名**：MCP 的内部全名 `mcp__<server>__<tool>` 在这条一行状态里会被省略号截断
            items.lastOrNull { it.toolRunning }?.let { running ->
                // 优先用终端渲染的头行（$ cmd / ⟳ poll），strip ANSI 后比原始工具名友好
                // （write_stdin 的 args 没有 cmd/path ⇒ brief 为空，会退化成「正在跑 write_stdin」）
                val head = running.termCall.firstOrNull()
                    ?.replace(Regex("\u001B\\[[0-9;?]*[A-Za-z]"), "")
                    ?.trim()
                if (!head.isNullOrBlank()) return head
                val brief = running.toolBrief
                if (brief.isNotBlank()) return brief
                val n = running.toolName
                val short = if (n.startsWith("mcp__")) {
                    val rest = n.removePrefix("mcp__")
                    rest.substringAfter("__", rest)
                } else n
                return "正在跑 " + short
            }
            return if (isWorking) "思考 / 生成中" else ""
        }
    var busy by mutableStateOf(false)
        private set

    var info by mutableStateOf<PiInfo?>(null)
        private set

    /** 待应答的扩展对话框（队列，通常只有一个）。 */
    var uiPrompts by mutableStateOf<List<UiPrompt>>(emptyList())
        private set
    var editorPrefill by mutableStateOf<String?>(null)
        private set
    var models by mutableStateOf<List<ModelRef>>(emptyList())
        private set
    var commands by mutableStateOf<List<CommandRef>>(emptyList())
    /** pi 的**内置**命令（从 pi 交互模式继承；含能否在 app 里落地成 RPC）。 */
    var slashCommands by mutableStateOf<List<SlashCmd>>(emptyList())
        private set

    /** app 会话注入清单（连着 app 的会话才注入：skills / 附加系统提示 / 预设 MCP）。 */
    var appProfile by mutableStateOf<AppProfile?>(null)

    /** 读取注入清单失败的原因（老桥没有 /v1/app-profile 就是这里，别一直转圈）。 */
    var appProfileError by mutableStateOf<String?>(null)
    var appProfileLoading by mutableStateOf(false)
        private set
    var appProfileSaving by mutableStateOf(false)
        private set
    private val appProfileRequests = EventGeneration()

    /** 「查看交付」：正在看的文件/目录（null = 没开）。 */
    var fileView by mutableStateOf<org.json.JSONObject?>(null)
        private set

    /** 全屏看图请求（放在根布局上层，不再用 Dialog —— 只有 app 自己的窗口才能真隐藏系统栏）。 */
    var imageViewer by mutableStateOf<ImageViewerRequest?>(null)
        private set

    fun openImageViewer(images: List<ImageRef>, initial: Int = 0, titles: List<String> = emptyList()) {
        if (images.isEmpty()) return
        imageViewer = ImageViewerRequest(images, initial.coerceIn(0, images.lastIndex), titles)
    }

    fun closeImageViewer() { imageViewer = null }

    /** 从工具入参 JSON 里取 path（read 图片识别用；入参存的是 pretty JSON）。 */
    private fun argsPathOf(item: ChatItem): String? =
        runCatching { org.json.JSONObject(item.toolArgsJson).optString("path") }.getOrNull()?.takeIf { it.isNotBlank() }

    /** 打开一个路径看内容（桥侧 /v1/file）；失败原因直接进 fileView 的 error 字段。 */
    fun openPath(path: String) = io(busyFlag = false) {
        runCatching {
            fileView = api.file(path)
            fileView?.put("ok", true)
        }.recoverCatching {
            // 桥读不到（不在 home/工作目录里，或文件在**另一台机器**上）⇒ 用当前连接那台机器的
            // SSH 别名经 /v1/fs 拉下来，**在 app 内直接生成预览**。
            // 以前是写到 app 私有 cache 再把那个本地路径发给桥 → 桥必然拒绝（用户截图里的报错）。
            val alias = fileAliasForCurrentBridge()
            val bytes = readFileVia(alias, path)
            fileView = FilePreview.viewJson(path, bytes)
            fileView?.put("remote", path)
            fileView?.put("alias", alias)
        }.onFailure {
            fileView = org.json.JSONObject().put("ok", false).put("path", path).put("error", it.message ?: "读取失败")
        }
    }

    /**
     * 「查看交付」拉文件时用哪个 SSH 别名：跟着**当前连的机器**走。
     *
     * 以前一律用 `fsAlias`（文件页最后选的那个，默认 `local`）⇒ 连着 host-a 时却去手机上找
     * `/home/user/...`，报 `sh: 1: cannot open …`（用户 2026-10-24 截图）。
     */
    private fun fileAliasForCurrentBridge(): String {
        val host = baseUrl.substringAfter("://", baseUrl).substringBefore('/').substringBeforeLast(':')
        if (host.isBlank() || host == "127.0.0.1" || host == "localhost") return fsAlias.ifBlank { "local" }
        // 配置里的机器带 alias（host-a/host-b…）与 hostName（IP）：按 IP 或名字都能对回去
        configMachines.firstOrNull { it.host == host || it.alias == host }?.let { return it.alias.ifBlank { it.host } }
        sshTargets.firstOrNull { it.host == host }?.let { return "ssh:" + it.key }
        return fsAlias.ifBlank { "local" }
    }

    private fun readFileVia(alias: String, path: String): ByteArray {
        val target = sshTargets.firstOrNull { "ssh:" + it.key == alias }
        return if (target != null) SftpClient.readBytes(target, path) else RemoteFs.read(fsApi(), alias, path)
    }

    fun closeFileView() { fileView = null }

    // ── 组件下载（App 内安装暂关闭，使用受控部署）────────
    /** 最近一次自更新的进展/结果（面板直接显示）。 */
    var updateMessage by mutableStateOf<String?>(null)
        private set
    var updateBusy by mutableStateOf(false)
        private set
    var downloadedUpdate by mutableStateOf<GiteaUpdater.Status?>(null)
        private set

    /** Gitea 主机与仓库：默认就是本项目（可在「连接设置」里改）。 */
    var giteaHost by mutableStateOf("https://gitea.example.com")
        private set
    var giteaRepo by mutableStateOf("example/pi-pocket")
        private set
    /** Gitea 访问令牌（私有仓需要；空则按公开仓试）。 */
    var giteaToken by mutableStateOf("")
        private set

    fun updateGiteaToken(token: String) {
        giteaToken = token.trim()
        prefs?.edit()?.putString("giteaToken", giteaToken)?.apply()
    }

    fun updateGitea(host: String, repo: String) {
        giteaHost = host.trim().trimEnd('/')
        giteaRepo = repo.trim()
        prefs?.edit()?.putString("giteaHost", giteaHost)?.putString("giteaRepo", giteaRepo)?.apply()
    }

    /** 拉一份最新代码到 /sdcard/Download/pi-pocket-update/（只下载，不动正在跑的桥）。 */
    fun fetchUpdate(context: Context) {
        if (updateBusy) return
        updateBusy = true
        downloadedUpdate = null
        updateMessage = "查询 " + giteaHost + " …"
        val host = giteaHost; val repo = giteaRepo; val token = giteaToken
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { GiteaUpdater.download(context, host, repo, token, null) } }
                .onSuccess { status ->
                    downloadedUpdate = status
                    updateMessage = "已下载 " + status.sha.take(8) + "（" + status.files.size + " 个文件）\n" + status.message.take(120)
                }.onFailure { updateMessage = "下载失败：" + (it.message ?: "未知") }
            updateBusy = false
        }
    }

    // ── 执行面（Shell）：走桥的 /v1/bash（底层是 pi 的 RPC bash） ──────────
    var shellOpen by mutableStateOf(false)
        private set
    var shellOutput by mutableStateOf("")
        private set
    var shellRunning by mutableStateOf(false)
        private set
    var shellExit by mutableStateOf<Int?>(null)
        private set
    var shellHistory by mutableStateOf<List<String>>(emptyList())
        private set
    private var shellRequestId: String? = null

    // ── 终端页（命令执行）三选一的后端 ─────────────────────────────────────
    /** bridge = 桥的 RPC bash（默认，远端也行）；termux = 直接对接本机 Termux；ssh = 直接走 SSH */
    var termBackend by mutableStateOf("bridge")
        private set
    private var sshShell: SshShell? = null

    fun chooseTermBackend(kind: String) {
        termBackend = kind
        if (kind != "ssh") { runCatching { sshShell?.close() }; sshShell = null }
        shellOutput = ""
        when (kind) {
            "termux" -> shellOutput = "直接对接 Termux（前台会话）。\n命令会在 Termux 里执行，输出回显到 app；" +
                "首次用它需要 Termux 侧 allow-external-apps=true。\n"
            "ssh" -> {
                val target = sshTargets.firstOrNull()
                if (target == null) shellOutput = "还没有配 SSH 目标：先去「连接设置 → SSH」加一台（本机也行）。\n"
                else startSshShell(target)
            }
            else -> shellOutput = ""
        }
    }

    /** 起一条交互式 SSH shell（app 直接使用 ssh）。 */
    fun startSshShell(target: SshTarget) {
        runCatching { sshShell?.close() }
        shellOutput = "SSH 连接 " + target.key + " …\n"
        val shell = SshShell(
            target = target,
            onOutput = { chunk -> shellOutput += chunk },
            onClosed = { err -> if (err != null) shellOutput += "\n[SSH 断开] $err\n" },
        )
        sshShell = shell
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { shell.start() }
                .onFailure { shellOutput += "[SSH 启动失败] ${it.message}\n" }
        }
    }

    /** 终端里最后切到的目录（`cd xxx` 会截获记下来）——「就在这儿开 pi 会话」用。 */
    var termCwd by mutableStateOf<String?>(null)
        private set

    /** 真实发给 ssh/termux 的命令：`cd X` 只用于记目录（不发），其余原样发。 */
    fun sendTerminalLine(command: String) {
        val line = command.trim()
        val cd = Regex("^cd\\s+(.+)$").find(line)
        if (cd != null) {
            val target = cd.groupValues[1].trim().trim('"', '\'')
            termCwd = resolveCd(termCwd, target)
            // 还是把它发下去，让终端真的切（用户会看到 shell 的反馈）
            writeTerminal("cd " + target)
            return
        }
        if (line == "~" || line == "cd") {
            writeTerminal("cd ~")
            termCwd = null
            return
        }
        writeTerminal(line)
    }

    /** 把相对 cd 目标算成绝对路径（app 侧只需要够用的规则：绝对路径 / ~ / 相对当前）。 */
    private fun resolveCd(current: String?, target: String): String {
        if (target.startsWith("/")) return target.trimEnd('/')
        if (target.startsWith("~/")) return "/data/data/com.termux/files/home/" + target.removePrefix("~/")
        val base = current ?: "/data/data/com.termux/files/home"
        val parts = (base + "/" + target).split('/').filter { it.isNotEmpty() }
        val out = mutableListOf<String>()
        for (part in parts) {
            when (part) {
                "." -> Unit
                ".." -> if (out.isNotEmpty()) out.removeAt(out.size - 1)
                else -> out += part
            }
        }
        return "/" + out.joinToString("/")
    }

    /** 在终端当前目录上开一个新的 pi 会话（cwd 传给 pi；远端机器同样有效）。 */
    fun newSessionInTermCwd() = io {
        val cwd = termCwd
        val data = api.newSession(cwd)
        val sid = data.optString("sessionId")
        if (sid.isNotBlank() || cwd != null) {
            setStatus("已在该目录开新会话" + (cwd?.let { "：$it" } ?: ""), current?.file, clearAfterMs = 8_000)
            refreshInfo()
            refreshSessions()
        }
    }

    fun writeTerminal(command: String) {
        when (termBackend) {
            "ssh" -> sshShell?.write(command) ?: run { shellOutput += "[没有活着的 SSH 会话]\n" }
            "termux" -> runTermuxCommand(command)
            else -> runShell(command, excludeFromContext = true)
        }
    }

    /** 直接对接 Termux：用前台会话跑一条命令（输出走 Termux，app 里只看到「已发送」）。 */
    fun runTermuxCommand(command: String) {
        val context = appContext ?: return
        val intent = android.content.Intent("com.termux.RUN_COMMAND").apply {
            setClassName("com.termux", "com.termux.app.RunCommandService")
            putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
            putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-lc", command))
            putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home")
            // 0 = 切到新会话（前台），这样用户能在 Termux 里看到输出；后台跑就没法回显
            putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0")
        }
        runCatching { context.startService(intent) }
            .onSuccess { shellOutput += "$ " + command + "\n（已发到 Termux 前台会话）\n" }
            .onFailure { shellOutput += "[Termux 启动失败] ${it.message}\n" }
    }

    /** 设置面板是否打开（复杂配置都收在这里）。 */
    var settingsNavigation by mutableStateOf(SettingsNavigation())
        private set
    var settingsOpen: Boolean
        get() = settingsNavigation.page == SettingsPage.Home
        set(value) { settingsNavigation = if (value) SettingsNavigation(SettingsPage.Home) else SettingsNavigation() }

    fun openSettingsPage(page: SettingsPage, fromSettings: Boolean = true) {
        settingsNavigation = settingsNavigation.open(page, fromSettings)
        if (page == SettingsPage.AppProfile) loadAppProfile()
    }
    fun backSettingsPage() { settingsNavigation = settingsNavigation.back() }

    // ── 远端文件页（用户 2026-10-21；**走 Termux 的 ~/.ssh/config**，app 不存 SSH 凭据）──
    var filesOpen by mutableStateOf(false)
        private set
    /** `~/.ssh/config` 里的 Host 别名（含首项 `local` = 本机 Termux）。 */
    var fsHosts by mutableStateOf<List<FsHost>>(emptyList())
        private set
    /** 当前用的别名（持久化，重开还在原机器上）。 */
    var fsAlias by mutableStateOf("local")
        private set
    var filesPath by mutableStateOf("")
        private set
    var filesEntries by mutableStateOf<List<SftpEntry>>(emptyList())
        private set
    var filesLoading by mutableStateOf(false)
        private set
    var filesError by mutableStateOf<String?>(null)
        private set
    var filesMessage by mutableStateOf<String?>(null)
        private set

    private fun fsApi() = PiClient({ baseUrl }, { token })

    // 目标可以是 **Termux 别名**（走桥 /v1/fs，零凭据）或 **App 内自建连接**（jsch，自带 key/密码）。
    // 用户 2026-10-21：「走 termux 的 config，但**也保留** app 内自己设置其他连接」。
    private fun fsSshTarget(): SshTarget? =
        if (!fsAlias.startsWith("ssh:")) null
        else sshTargets.firstOrNull { "ssh:" + it.key == fsAlias }

    private fun fsHomeNow(): String {
        val t = fsSshTarget()
        return if (t != null) SftpClient.home(t) else RemoteFs.home(fsApi(), fsAlias)
    }

    private fun fsListNow(path: String): List<SftpEntry> {
        val t = fsSshTarget()
        return if (t != null) SftpClient.list(t, path) else RemoteFs.list(fsApi(), fsAlias, path)
    }

    private fun fsReadNow(path: String): ByteArray {
        val t = fsSshTarget()
        return if (t != null) SftpClient.readBytes(t, path) else RemoteFs.read(fsApi(), fsAlias, path)
    }

    private fun fsWriteNow(path: String, bytes: ByteArray) {
        val t = fsSshTarget()
        if (t != null) SftpClient.writeBytes(t, path, bytes.inputStream()) else RemoteFs.write(fsApi(), fsAlias, path, bytes)
    }

    private fun fsMkdirNow(path: String) {
        val t = fsSshTarget()
        if (t != null) SftpClient.mkdir(t, path) else RemoteFs.mkdir(fsApi(), fsAlias, path)
    }

    private fun fsRemoveNow(path: String, isDir: Boolean) {
        val t = fsSshTarget()
        if (t != null) SftpClient.remove(t, path, isDir) else RemoteFs.remove(fsApi(), fsAlias, path)
    }

    private fun fsRenameNow(from: String, to: String) {
        val t = fsSshTarget()
        if (t != null) SftpClient.rename(t, from, to) else RemoteFs.rename(fsApi(), fsAlias, from, to)
    }

    /** 显示名（标题副行）：别名走 Termux；`ssh:` 前缀是 App 内连接。 */
    val fsTargetLabel: String
        get() = fsSshTarget()?.let { it.user + "@" + it.host } ?: fsAlias

    /** 拉一次别名列表（`local` 永远在第一位；其余来自 Termux 的 ssh config）。 */
    fun loadFsHosts() = io {
        runCatching { RemoteFs.hosts(fsApi()) }
            .onSuccess { list ->
                fsHosts = list
                if (list.none { it.alias == fsAlias }) {
                    fsAlias = list.firstOrNull()?.alias ?: "local"
                    prefs?.edit()?.putString("fsAlias", fsAlias)?.apply()
                }
            }
            .onFailure { filesError = "读不到 ~/.ssh/config：" + (it.message ?: "未知错误") }
    }

    /** 选目标：Termux 别名（原样）或 App 内连接（`ssh:<user@host:port>`）。 */
    fun filesSelectHost(alias: String) {
        fsAlias = alias
        prefs?.edit()?.putString("fsAlias", alias)?.apply()
        filesPath = ""
        loadFilesHome()
    }

    /** 会话内进入文件页：直接从**这个会话的工作目录**起步。 */
    fun openFilesForSession(cwd: String?) {
        filesOpen = true
        filesMessage = null
        filesError = null
        val start = cwd?.trim()?.takeIf { it.isNotBlank() }
        io {
            if (fsHosts.isEmpty()) runCatching { RemoteFs.hosts(fsApi()) }.onSuccess { fsHosts = it }
            if (start != null) fetchDir(start) else loadFilesHomeInner()
        }
    }

    fun openFiles() {
        filesOpen = true
        filesMessage = null
        filesError = null
        io {
            if (fsHosts.isEmpty()) runCatching { RemoteFs.hosts(fsApi()) }.onSuccess { fsHosts = it }
            if (filesPath.isBlank()) loadFilesHomeInner() else fetchDir(filesPath)
        }
    }

    fun closeFiles() {
        filesOpen = false
        filesMessage = null
    }

    private fun loadFilesHomeInner() {
        filesLoading = true
        filesError = null
        val home = runCatching { fsHomeNow() }.getOrElse { e ->
            filesLoading = false
            filesError = "连不上 " + fsAlias + "：" + (e.message ?: "未知错误")
            return
        }
        fetchDir(home)
    }

    private fun loadFilesHome() = io { loadFilesHomeInner() }

    /** 真正拉一次目录（调用方已在 IO 线程）。 */
    private fun fetchDir(path: String) {
        filesLoading = true
        filesError = null
        val list = runCatching { fsListNow(path) }.getOrElse { e ->
            filesLoading = false
            filesError = "读目录失败：" + (e.message ?: "未知错误")
            return
        }
        filesPath = path
        filesEntries = list
        filesLoading = false
    }

    fun filesNavigate(path: String) = io { fetchDir(path) }

    fun filesRefresh() = io { fetchDir(filesPath.ifBlank { "/" }) }

    fun filesUp() {
        val p = filesPath.trimEnd('/')
        if (p.isEmpty()) return
        val up = p.substringBeforeLast('/', "")
        filesNavigate(if (up.isEmpty()) "/" else up)
    }

    fun filesDelete(entry: SftpEntry) = io {
        filesMessage = "正在删除 " + entry.name + "…"
        runCatching { fsRemoveNow(entry.path, entry.isDir) }
            .onSuccess { filesMessage = "已删除 " + entry.name }
            .onFailure { filesMessage = "删除失败：" + (it.message ?: "未知错误") }
        fetchDir(filesPath)
    }

    fun filesRename(entry: SftpEntry, newName: String) = io {
        if (newName.isBlank() || newName == entry.name) return@io
        val to = filesPath.trimEnd('/') + "/" + newName
        filesMessage = "正在重命名…"
        runCatching { fsRenameNow(entry.path, to) }
            .onSuccess { filesMessage = "已改名为 " + newName }
            .onFailure { filesMessage = "改名失败：" + (it.message ?: "未知错误") }
        fetchDir(filesPath)
    }

    fun filesMkdir(name: String) = io {
        if (name.isBlank()) return@io
        val path = filesPath.trimEnd('/') + "/" + name
        filesMessage = "正在新建目录…"
        runCatching { fsMkdirNow(path) }
            .onSuccess { filesMessage = "已新建 " + name }
            .onFailure { filesMessage = "新建失败：" + (it.message ?: "未知错误") }
        fetchDir(filesPath)
    }

    /** 上传：content:// 读完转 base64 交给桥（桥在 Termux 里落到远端）。 */
    fun filesUpload(context: android.content.Context, uri: android.net.Uri) = io {
        filesMessage = "正在上传…"
        val name = queryDisplayName(context, uri) ?: "upload.bin"
        runCatching {
            val dest = filesPath.trimEnd('/') + "/" + name
            val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            fsWriteNow(dest, bytes)
        }.onSuccess { filesMessage = "已上传 " + name }
            .onFailure { filesMessage = "上传失败：" + (it.message ?: "未知错误") }
        fetchDir(filesPath)
    }

    /** 远端文件 → 本地缓存 → 交给既有的「查看交付」（文本/图片/PDF 都能看）。 */
    fun filesOpenEntry(context: android.content.Context, entry: SftpEntry) = io {
        filesMessage = "正在读取 " + entry.name + "…"
        runCatching {
            val bytes = fsReadNow(entry.path)
            val dir = java.io.File(context.cacheDir, "remote-fs")
            dir.mkdirs()
            val local = java.io.File(dir, entry.name.replace('/', '_'))
            local.writeBytes(bytes)
            local.absolutePath
        }.onSuccess { path ->
            filesMessage = null
            openPath(path)
        }.onFailure { filesMessage = "打开失败：" + (it.message ?: "未知错误") }
    }

    private fun queryDisplayName(context: android.content.Context, uri: android.net.Uri): String? = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
    }.getOrNull() ?: uri.lastPathSegment

    fun openShell() { shellOpen = true }
    fun closeShell() { shellOpen = false; runCatching { sshShell?.close() }; sshShell = null }
    fun clearShell() { shellOutput = ""; shellExit = null }

    fun runShell(command: String, excludeFromContext: Boolean) {
        if (command.isBlank()) return
        val id = "bash-${System.currentTimeMillis()}"
        shellRequestId = id
        shellOutput = ""
        shellExit = null
        shellRunning = true
        shellHistory = (listOf(command) + shellHistory.filter { it != command }).take(20)
        prefs?.edit()?.putString("shellHistory", JSONArray(shellHistory).toString())?.apply()
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { api.bash(id, command, excludeFromContext) }
            }
            shellRunning = false
            result.onSuccess { data ->
                val finalOutput = data.optString("output")
                if (finalOutput.isNotBlank() && shellOutput.isBlank()) shellOutput = finalOutput
                shellExit = data.optInt("exitCode", 0)
                if (data.optBoolean("truncated")) {
                    shellOutput += "\n…（输出被截断，完整输出：${data.optString("fullOutputPath")}）"
                }
            }.onFailure { shellOutput += "\n[失败] ${it.message}" }
        }
    }

    /**
     * 在会话里执行一条 shell 命令（= 终端输入 `!cmd`）。
     *
     * 为什么不复用 `runShell`：那个是**独立 Shell 页**的输出缓冲（有自己的滚动区）。
     * 这里的语义是"在对话时间线里插一条命令记录"——pi 会把结果记成 `bashExecution` 消息，
     * 所以跑完重新拉历史就会出现（终端/手机看到的是同一条）。
     *
     * @param excludeFromContext 对应终端的 `!!`（跑但不进上下文）
     */
    fun runBashInChat(command: String, excludeFromContext: Boolean) = ioSuspend {
        if (command.isBlank()) return@ioSuspend
        val id = "bash-${System.currentTimeMillis()}"
        setStatus("运行命令…", current?.file)
        val result = runCatching {
            withContext(Dispatchers.IO) { api.bash(id, command, excludeFromContext) }
        }
        result.onSuccess { data ->
            val exit = data.optInt("exitCode", 0)
            setStatus(
                "命令完成（exit $exit）" + if (data.optBoolean("truncated")) " · 输出已截断" else "",
                current?.file,
                clearAfterMs = 6_000,
            )
        }.onFailure {
            setStatus(null, current?.file)
            addNotice("命令执行失败", it.message ?: "", "error")
        }
        // 无论成败都重拉：pi 已经把 `bashExecution` 记进会话了（失败也有 exit code / 错误输出）
        runCatching { items = api.messages(cols = termCols) }
    }

    fun abortShell() = io(busyFlag = false) { api.bashAbort() }

    /** 用户主动中止（区分于外部中止）：回合结束时 notice 里能说清楚是谁停的。 */
    private var abortedByUser = false

    /** 图片按需加载中（历史图默认不在内存里）。 */
    var imagesLoading by mutableStateOf(false)
        private set

    /** 本机刚发出的图（SSE 的 user message_start 不一定带原图，用它兜底；回合结束就清）。 */
    private var pendingImages: List<ImageRef> = emptyList()

    /** 流式增量缓冲区（见 queueDelta）。 */
    private val deltaText = StringBuilder()
    private val deltaThinking = StringBuilder()
    private var deltaFlushPending = false

    // ── 连接自恢复（用户 2026-10-22：「app 老是掉桥，每次都要点强制拉起 pi」）──────────
    //
    // 旧行为（就是“掉桥”的真相）：SSE 断开后**只补一次** 5s 重试；那次如果也失败
    // （切后台、锁屏、切网都会），就永远停在 `Connection.Failed`，除非人手点一下。
    // 实测证据：`~/.pi/agent/pi-pocket-start.log` 里 84 次拉起，每一次都写着
    // 「已有桥在跑且 token 匹配（HTTP 200），不重启」—— 桥从来没死，是 app 自己放弃重连。
    //
    // 现在：指数退避无限重连 + 本机桥自动拉起 + 回前台立刻重探。

    /** 连续失败次数（退避用；连上就清零）。 */
    var connectFailures by mutableStateOf(0)
        private set
    private var reconnectJob: Job? = null
    private var heartbeatJob: Job? = null
    private var lastAutoLaunchMs = 0L

    /** 最后一次收到 SSE（含 onOpen、桥的 `ping`）的时间：判断事件流是不是已经僵死。 */
    private var lastEventAt = 0L

    private fun isLocalBridgeUrl(url: String): Boolean = id.harness.pipocket.ui.isLocalBridgeUrl(url)

    /** 当前桥是不是"这台手机自己"（只有它可以由 app 自动拉起）。 */
    val isLocalBridge: Boolean get() = isLocalBridgeUrl(baseUrl)

    /**
     * 连接状态文案 —— 会话列表和对话页**共用这一处**，免得两处说法不一致
     * （之前对话页干脆没有，断了看不出来）。
     */
    fun connectionText(): String {
        val short = baseUrl.removePrefix("http://").removePrefix("https://")
        val machine = currentHit?.host?.takeIf { it.isNotBlank() }?.let { "$it · " } ?: ""
        return when (connection) {
            // 文案口径走纯函数 `connectionLabel()`：重连是常态动作，不该说成"未连接"吓人
            Connection.Ok -> id.harness.pipocket.ui.connectionLabel("ok", connectFailures, machine + short)
            Connection.Failed -> id.harness.pipocket.ui.connectionLabel("failed", connectFailures, short)
            Connection.Unknown -> id.harness.pipocket.ui.connectionLabel("unknown", connectFailures, short)
        }
    }

    /**
     * 排一次重连（指数退避：1→2→4→8→15→30s 封顶）。
     *
     * 幂等：已有在等的就不重复排，避免一次网络抖动排出一堆并发 connect()。
     */
    private fun scheduleReconnect(reason: String? = null) {
        if (connection == Connection.Ok) return
        if (reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            connectFailures += 1
            val wait = id.harness.pipocket.ui.reconnectDelayMs(connectFailures)
            reason?.let { if (connectFailures == 1) error = it }
            delay(wait)
            if (connection == Connection.Ok) return@launch
            maybeAutoLaunchLocalBridge()
            connect()
        }
    }

    /**
     * 本机桥连续 3 次没应答 ⇒ **自己把它拉起来**（不再要人点「强制拉起 pi」）。
     * 限频 90s，且只对本机地址生效（远端机器由用户自己管）。
     */
    private fun maybeAutoLaunchLocalBridge() {
        if (connectFailures < id.harness.pipocket.ui.AUTO_LAUNCH_AFTER_FAILURES) return
        if (!isLocalBridgeUrl(baseUrl)) return
        val ctx = appContext ?: return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastAutoLaunchMs < id.harness.pipocket.ui.AUTO_LAUNCH_MIN_INTERVAL_MS) return
        lastAutoLaunchMs = now
        setStatus("桥没应答，正在自动拉起 pi…", clearAfterMs = 10_000)
        launchLocalBridge(ctx)
    }

    private fun markConnected() {
        connectFailures = 0
        reconnectJob?.cancel()
        reconnectJob = null
    }

    /** 回到前台：Android 会在后台把 socket 收掉，但状态不会自己刷新 —— 必须主动探一次。 */
    /** app 是否在前台：**前台不发系统通知**（会打断正在看的对话），只落一行提示。 */
    @Volatile
    var appInForeground: Boolean = false
        private set

    fun onAppForeground() {
        appInForeground = true
        // 回前台：收起后台守护（常驻通知不再占位）；后台提醒仍开着的话，下次进后台会再起
        if (!bgNotify) appContext?.let { LinkService.setRunning(it, false) }
        startHeartbeat()
        if (connection != Connection.Ok) {
            connectFailures = 0
            reconnectJob?.cancel()
            reconnectJob = null
            connect()
        } else {
            // 看着“已连接”也可能是僵的：轻量探一下（健康接口 8s 超时，失败就走重连）
            scope.launch {
                val ok = withContext(Dispatchers.IO) { runCatching { api.health() }.isSuccess }
                if (!ok) {
                    connection = Connection.Failed
                    error = "桥没应答（回到前台后探测失败）"
                    scheduleReconnect()
                }
            }
        }
    }

    /** 进入后台：停心跳与退避任务（省电）；回来时 [onAppForeground] 会重启。 */
    fun onAppBackground() {
        appInForeground = false
        heartbeatJob?.cancel()
        heartbeatJob = null
        reconnectJob?.cancel()
        reconnectJob = null
    }

    // ── 后台提醒（前台服务守住 SSE）────────────────────────────────────────
    /** 后台是否保持连接（= 远端机器的提醒能不能送到）。落盘在 prefs。 */
    var bgNotify by mutableStateOf(true)
        private set

    /** 打开/关闭后台守护：开着才起前台服务（常驻低优先级通知）。 */
    fun updateBgNotify(enabled: Boolean) {
        bgNotify = enabled
        prefs?.edit()?.putBoolean("bgNotify", enabled)?.apply()
        val ctx = appContext ?: return
        LinkService.setRunning(ctx, enabled && !appInForeground)
    }

    // ── 回合结束提醒（用户 2026-10-24：每轮都弹、报错也弹 ⇒ 改成开关，默认关）──────
    /**
     * 「回答完成 / 已停止 / 出错了」这类**每轮都发**的提醒是否真的发。**默认关**。
     *
     * 只管回合结束这一类；`needs-input`（pi 在等你确认/选/输入/编辑）与 `note`（agent 主动
     * `pocket_notify`）不受影响。判定在 [id.harness.pipocket.ui.turnEndNoticeAllowed]。
     */
    var notifyTurnEnd by mutableStateOf(false)
        private set

    fun updateNotifyTurnEnd(enabled: Boolean) {
        notifyTurnEnd = enabled
        prefs?.edit()?.putBoolean("notifyTurnEnd", enabled)?.apply()
    }

    /**
     * 前台心跳：每 30s 探一次健康。
     *
     * 为什么需要：SSE 的 socket 可能在后台被系统默默收掉（不报错），
     * 那样 app 会一直显示“已连接”却收不到任何事件。健康探测能把它拉回真实状态。
     */
    private fun startHeartbeat() {
        if (heartbeatJob?.isActive == true) return
        heartbeatJob = scope.launch {
            while (true) {
                delay(30_000)
                if (streaming) continue            // 正在跑一轮：别去抢（也不该判死）
                // ① 事件流僵死：HTTP 好好的、SSE 却早就没了（桥重启/切网都会）。
                //    桥每 20s 发一个 `ping` 事件，超过 75s 一个都没收到 ⇒ 拆掉重建。
                //    这就是“显示已连接却什么都不动、只能手点”的根治。
                if (connection == Connection.Ok &&
                    System.currentTimeMillis() - lastEventAt > SSE_STALE_MS
                ) {
                    restartEvents()
                }
                val ok = withContext(Dispatchers.IO) { runCatching { api.health() }.isSuccess }
                when {
                    ok -> if (connection != Connection.Ok) {
                        connection = Connection.Ok
                        error = null
                        markConnected()
                        startEvents()
                    }
                    connection != Connection.Ok -> scheduleReconnect()
                    else -> {
                        connection = Connection.Failed
                        error = "桥没应答（心跳探测失败）"
                        scheduleReconnect()
                    }
                }
            }
        }
    }

    /** 拆掉重连事件流（不动连接状态：HTTP 那边可能还是好的）。 */
    private fun restartEvents() {
        stopEvents()
        lastEventAt = System.currentTimeMillis()
        startEvents()
    }

    /** SSE 多久没动静就当僵死（桥每 20s 一个 `ping` ⇒ 75s 容 3 个丢包）。 */
    private val SSE_STALE_MS = 75_000L

    private var cachedApi: PiClient? = null
    private var cachedApiTarget: Pair<String, String>? = null
    private val api: PiClient
        @Synchronized get() {
            val target = baseUrl to token
            if (cachedApiTarget != target) {
                cachedApi = PiClient({ target.first }, { target.second })
                cachedApiTarget = target
            }
            return checkNotNull(cachedApi)
        }
    private var appContext: Context? = null

    /** 让后台守护按当前偏好跑起来（MainActivity 进前台时调；开前台服务只能在 app 在前台时做）。 */
    fun syncLinkService(context: Context) {
        LinkService.setRunning(context, bgNotify)
    }

    fun init(context: Context) {
        if (prefs != null) return
        appContext = context.applicationContext
        Ssh.init(context)
        val p = context.getSharedPreferences("pi-pocket", Context.MODE_PRIVATE)
        prefs = p
        p.getString("baseUrl", null)?.let { baseUrl = it }
        bgNotify = p.getBoolean("bgNotify", true)
        // 默认关：用户 2026-10-24「每次都显示且报错也显示」⇒ 回合结束提醒要主动打开
        notifyTurnEnd = p.getBoolean("notifyTurnEnd", false)
        lastSessions = SessionMemory.decode(p.getString(SessionMemory.prefsKey(), null))
        token = p.getString("token", "").orEmpty()
        savedHosts = p.getString("hosts", null)?.let { raw ->
            runCatching { JSONArray(raw).let { arr -> (0 until arr.length()).map { arr.getString(it) } } }.getOrNull()
        } ?: emptyList()
        p.getString("sessionGroup", null)?.let { raw ->
            runCatching { sessionGroup = SessionGroup.valueOf(raw) }
        }
        p.getString("sessionSort", null)?.let { raw ->
            runCatching { sessionSort = SessionSort.valueOf(raw) }
        }
        p.getString("sessionTime", null)?.let { raw ->
            runCatching { sessionTime = SessionTime.valueOf(raw) }
        }
        projectFilter = p.getStringSet("projectFilter", emptySet()) ?: emptySet()
        collapsedGroups = p.getStringSet("collapsedGroups", emptySet()) ?: emptySet()
        openStates = p.getString("openStates", null)?.let { raw ->
            runCatching {
                JSONArray(raw).let { arr ->
                    val map = LinkedHashMap<String, Boolean>()
                    for (i in 0 until arr.length()) {
                        val pair = arr.optJSONArray(i) ?: continue
                        map[pair.optString(0)] = pair.optBoolean(1)
                    }
                    map
                }
            }.getOrNull()
        } ?: emptyMap()
        shellHistory = p.getString("shellHistory", null)?.let { raw ->
            runCatching { JSONArray(raw).let { arr -> (0 until arr.length()).map { arr.getString(it) } } }.getOrNull()
        } ?: emptyList()
        knownMachines = p.getString("knownMachines", null)?.let { raw ->
            runCatching {
                JSONArray(raw).let { arr ->
                    (0 until arr.length()).mapNotNull { i ->
                        val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                        KnownMachine(
                            url = obj.optString("url"),
                            host = obj.optString("host"),
                            scope = obj.optString("scope"),
                            authRequired = obj.optBoolean("authRequired"),
                            lastSeenMs = obj.optLong("lastSeenMs"),
                        )
                    }
                }
            }.getOrNull()
        } ?: emptyList()
        loadSshTargets(p)
        p.getString("themeMode", null)?.let { raw -> runCatching { themeMode = ThemeMode.valueOf(raw) } }
        fontScale = p.getFloat("fontScale", 1f)
        chatFontScale = p.getFloat("chatFontScale", 1f)
        fsAlias = p.getString("fsAlias", "local") ?: "local"
        toolCollapsedLines = p.getInt("toolCollapsedLines", 3).coerceIn(1, 20)
        toolExpandedMaxDp = p.getInt("toolExpandedMaxDp", 420).coerceIn(160, 1200)
        toolCollapsedMaxDp = p.getInt("toolCollapsedMaxDp", 220).coerceIn(80, 600)
        termColsOverride = p.getInt("termColsOverride", 0).coerceIn(0, 200)
        // Gitea（自更新）配置：**必须在这里读回来**。之前只写了不读 ⇒ 重启后内存里是空串，
        // 设置页看着"token 是空的"、自更新也会退化成按公开仓试（私有仓 401）。
        giteaHost = p.getString("giteaHost", null)?.takeIf { it.isNotBlank() } ?: giteaHost
        giteaRepo = p.getString("giteaRepo", null)?.takeIf { it.isNotBlank() } ?: giteaRepo
        giteaToken = p.getString("giteaToken", "").orEmpty()
        fontBodySp = p.getInt("fontBodySp", 12).coerceIn(9, 24)
        fontToolNameSp = p.getInt("fontToolNameSp", 12).coerceIn(9, 24)
        fontToolSp = p.getInt("fontToolSp", 11).coerceIn(8, 24)
        fontMetaSp = p.getInt("fontMetaSp", 11).coerceIn(8, 24)
        fontInputSp = p.getInt("fontInputSp", 14).coerceIn(10, 26)
        // 上次有"按了发送但没等到确认"的内容（桥崩了/app 被杀）⇒ 起 app 就提示，别再丢一次
        pendingDraft = Outbox.decode(p.getString("outbox", null))
        connect()
    }

    // ── 连接设置 ──────────────────────────────────────────────────────────
    fun updateBaseUrl(value: String) {
        val clean = value.trim().ifBlank { "http://127.0.0.1:8789" }
        if (clean != baseUrl) clearMachineState()
        baseUrl = clean
        prefs?.edit()?.putString("baseUrl", clean)?.apply()
        reconnect()
    }

    fun updateToken(value: String) {
        if (token != value.trim()) clearAppProfile()
        token = value.trim()
        prefs?.edit()?.putString("token", token)?.apply()
    }

    // ── 展开/收起状态（跨滑动 / 跨重启保持） ─────────────────────

    /** item key 加会话前缀：不同会话里同名 key（如 `tool-3`）互不干扰。 */
    private fun scopedOpenKey(key: String): String = (current?.file ?: "none") + "|" + key

    fun isOpen(key: String, default: Boolean): Boolean = openStates[scopedOpenKey(key)] ?: default

    /** 点内容本身开合；只记用户手动改过的，没记过的用各处的默认值。 */
    fun toggleOpen(key: String, default: Boolean) {
        val scoped = scopedOpenKey(key)
        val next = !isOpen(key, default)
        val updated = LinkedHashMap(openStates)
        updated.remove(scoped)
        updated[scoped] = next
        // 上限：只留最近动过的，别让 prefs 无限长
        while (updated.size > OPEN_STATE_LIMIT) updated.remove(updated.keys.first())
        openStates = updated
        persistOpenStates()
    }

    /**
     * 流式占位行（`STREAM_KEY`）换成权威行时，把开合状态带过去；
     * 否则回合结束时行换了 key，用户刚折上的 thinking 会弹回默认（展开）。
     */
    private fun transferOpenState(fromKey: String, toKey: String) {
        val from = scopedOpenKey(fromKey)
        val value = openStates[from] ?: return
        val updated = LinkedHashMap(openStates)
        updated.remove(from)
        updated[scopedOpenKey(toKey)] = value
        openStates = updated
        persistOpenStates()
    }

    private fun persistOpenStates() {
        val arr = JSONArray()
        openStates.forEach { (k, v) -> arr.put(JSONArray().put(k).put(v)) }
        prefs?.edit()?.putString("openStates", arr.toString())?.apply()
    }

    fun addSavedHost(value: String) {
        val clean = PiLocator.normalizeHost(value)
        if (clean.isBlank() || savedHosts.contains(clean)) return
        savedHosts = savedHosts + clean
        persistHosts()
    }

    fun removeSavedHost(value: String) {
        savedHosts = savedHosts - value
        persistHosts()
    }

    private fun persistHosts() {
        prefs?.edit()?.putString("hosts", JSONArray(savedHosts).toString())?.apply()
    }

    fun reconnect() {
        stopEvents()
        connection = Connection.Unknown
        connect()
    }

    // ── 会话列表的视图维度 ──────────────────────────────────────────────
    fun updateSessionGroup(value: SessionGroup) {
        sessionGroup = value
        prefs?.edit()?.putString("sessionGroup", value.name)?.apply()
    }

    fun updateSessionSort(value: SessionSort) {
        sessionSort = value
        prefs?.edit()?.putString("sessionSort", value.name)?.apply()
    }

    fun updateSessionTime(value: SessionTime) {
        sessionTime = value
        prefs?.edit()?.putString("sessionTime", value.name)?.apply()
    }

    fun toggleProjectFilter(cwd: String) {
        projectFilter = if (projectFilter.contains(cwd)) projectFilter - cwd else projectFilter + cwd
        prefs?.edit()?.putStringSet("projectFilter", projectFilter)?.apply()
    }

    fun clearProjectFilter() {
        projectFilter = emptySet()
        prefs?.edit()?.putStringSet("projectFilter", emptySet())?.apply()
    }

    /** 应用时间/项目过滤 + 排序后的会话（分组交给 UI）。 */
    fun visibleSessions(): List<SessionInfo> {
        val now = System.currentTimeMillis()
        val windowMs = when (sessionTime) {
            SessionTime.All -> Long.MAX_VALUE
            SessionTime.Day -> 24L * 3600 * 1000
            SessionTime.Week -> 7L * 24 * 3600 * 1000
        }
        val filtered = sessions.filter { session ->
            (projectFilter.isEmpty() || session.cwd in projectFilter) &&
                (windowMs == Long.MAX_VALUE || (session.updatedMillis?.let { now - it <= windowMs } ?: true))
        }
        return when (sessionSort) {
            SessionSort.Updated -> filtered.sortedByDescending { it.updatedMillis ?: 0L }
            SessionSort.Created -> filtered.sortedByDescending { it.createdMillis ?: 0L }
            SessionSort.Name -> filtered.sortedBy { (it.name ?: it.id).lowercase() }
        }
    }

    /** 当前会话集合里的所有项目目录（供项目过滤面板用）。 */
    fun projects(): List<String> = sessions.mapNotNull { it.cwd }.distinct().sorted()

    // ── 连接与事件流 ──────────────────────────────────────────────────────
    /** 工具块渲染列数（等宽字符数）：由 UI 按屏幕宽度设定，桥据此排版 ⇒ 与终端 1:1。 */
    var termCols: Int = 0

    fun connect() {
        scope.launch {
            // 用户口径 2026-10-24：**选定的机器优先**。记住的是远端机器时只认它：
            // 连不上就显示离线并重试，**不偷偷换成别的机器、也不覆写记住的地址**。
            // （以前是“谁先应答就用谁” ⇒ host-a 探不通时手机连回本机并把 baseUrl 改成 127.0.0.1，
            //   看起来就像切机器被踢回本机。）
            val saved = baseUrl
            if (id.harness.pipocket.ui.sticksToChosenMachine(saved)) {
                val hit = withContext(Dispatchers.IO) { runCatching { PiLocator.probe(saved, token) }.getOrNull() }
                if (hit != null) {
                    adopt(hit)
                    return@launch
                }
                connection = Connection.Failed
                error = "连不上 $saved（离线，重试中）"
                knownMachines = knownMachines.map { it.copy(online = false) }
                scheduleReconnect()
                return@launch
            }
            // 先试「记住的机器」（含上次用的地址）：快，且不需要扫网段
            val remembered = runCatching { withContext(Dispatchers.IO) { probeKnown() } }.getOrDefault(emptyList())
            remembered.firstOrNull()?.let { hit ->
                adopt(hit)
                return@launch
            }
            runCatching { withContext(Dispatchers.IO) { api.health() } }
                .onSuccess { ready("") }
                .onFailure {
                    error = it.message
                    // 没有已知机器：只探本机 loopback + 保存的远端（不扫 /24）；全网扫要用户显式点「扫描」
                    autoDetect(keepCurrentOnFailure = true, includeLan = false)
                    scheduleReconnect()
                }
        }
    }

    /** 逐个探记住的机器（seed 优先），命中的顺手刷在线状态。 */
    private suspend fun probeKnown(): List<BridgeHit> = withContext(Dispatchers.IO) {
        val urls = (listOf(baseUrl) + knownMachines.map { it.url } + savedHosts.map { PiLocator.normalizeHost(it) })
            .filter { it.isNotBlank() }
            .distinct()
        val hits = urls.mapNotNull { url ->
            PiLocator.probe(url, token)
        }
        // 没命中的标为离线
        knownMachines = knownMachines.map { machine ->
            machine.copy(online = hits.any { it.url == machine.url })
        }
        hits
    }

    /** 探针用：把记住的机器探一遍并回报（供 debug 验证，release 无调用者）。 */
    suspend fun probeKnownForProbe(): List<BridgeHit> = probeKnown()

    /** 手动保存一台机器（用户口径：常驻列表由用户显式保存，不自动记住扫到的）。 */
    fun saveMachine(hit: BridgeHit) {
        val entry = KnownMachine(
            url = hit.url,
            host = hit.host,
            scope = hit.scope.name,
            authRequired = hit.authRequired,
            lastSeenMs = System.currentTimeMillis(),
            online = true,
        )
        knownMachines = (listOf(entry) + knownMachines.filter { it.url != hit.url }).take(30)
        persistKnown()
    }

    /**
     * 手动记住「当前连接」这台机器。
     *
     * 之前只走 `currentHit`，但启动时自动接上的那次探测不经过 `adopt()` ⇒ `currentHit` 常是 null，
     * 于是按了没反应。现在直接按 `baseUrl` 记一条；`currentHit` 有（探测过）就用它的真实主机名。
     */
    fun saveCurrentMachine(hostHint: String? = null) {
        val url = baseUrl
        if (url.isBlank()) return
        val hit = currentHit?.takeIf { it.url == url } ?: BridgeHit(
            url = url,
            host = hostHint?.takeIf { it.isNotBlank() }
                ?: url.removePrefix("http://").removePrefix("https://").substringBefore("/"),
            scope = PiLocator.scopeOf(url),
            piPath = "",
            sessionsRoot = "",
            authRequired = token.isNotBlank(),
            workdir = "",
        )
        saveMachine(hit)
    }

    fun isSaved(url: String): Boolean = knownMachines.any { it.url == url }

    /** 成功连上某台机器时静默记住它（不弹提示、不动当前列表顺序以外的状态）。 */
    private fun rememberMachineOnConnect(hit: BridgeHit) {
        if (hit.url.isBlank()) return
        val existing = knownMachines.firstOrNull { it.url == hit.url }
        val entry = KnownMachine(
            url = hit.url,
            host = existing?.host?.takeIf { it.isNotBlank() } ?: hit.host.ifBlank { hit.url },
            scope = hit.scope.name,
            authRequired = hit.authRequired,
            lastSeenMs = System.currentTimeMillis(),
            online = true,
        )
        knownMachines = (listOf(entry) + knownMachines.filterNot { it.url == hit.url }).take(30)
        persistKnown()
    }

    private fun persistKnown() {
        val array = JSONArray()
        knownMachines.forEach { machine ->
            array.put(
                JSONObject()
                    .put("url", machine.url)
                    .put("host", machine.host)
                    .put("scope", machine.scope)
                    .put("authRequired", machine.authRequired)
                    .put("lastSeenMs", machine.lastSeenMs),
            )
        }
        prefs?.edit()?.putString("knownMachines", array.toString())?.apply()
    }

    fun forgetMachine(url: String) {
        knownMachines = knownMachines.filterNot { it.url == url }
        persistKnown()
    }

    /**
     * 自动探测。
     *
     * @param adoptFirst 命中的第一台是否直接切过去：
     *   - 启动/重连路径：true（要自动接上）；
     *   - 用户在面板里按「扫描」：**false** —— 只填结果，**不动当前连接**（否则扫一次就把正在用的机器踢掉）。
     */
    /**
     * 一键扫描（用户口径）：本机 + 局域网，**并顺带读 Termux 的 SSH 配置**，
     * 让 SSH 机器也进结果；只要那台机器在线，就能为它 装/更新/拉起 bridge。
     */
    fun scanAll(context: android.content.Context, adoptFirst: Boolean = false) {
        autoDetect(keepCurrentOnFailure = false, includeLan = true, adoptFirst = adoptFirst)
        if (!scanIncludeSshConfig) return
        scope.launch {
            // 等局域网扫完（scanning 变回 false）再读配置，避免两路同时写 scanProgress
            var guard = 0
            while (scanning && guard++ < 40) kotlinx.coroutines.delay(500)
            scanConfigViaTermux(context)
        }
    }

    fun autoDetect(keepCurrentOnFailure: Boolean = false, includeLan: Boolean = true, adoptFirst: Boolean = true) {
        if (scanning) return
        scope.launch {
            scanning = true
            scanProgress = "开始扫描…"
            var scanError: String? = null
            val found = runCatching {
                PiLocator.scan(
                    seed = if (adoptFirst) baseUrl else null,
                    token = token,
                    savedHosts = savedHosts,
                    includeLan = includeLan,
                    context = appContext,
                    onProgress = { scanProgress = it },
                )
            }.onFailure { scanError = it.message ?: it::class.java.simpleName }.getOrDefault(emptyList())
            hits = found
            // 把命中的机器记下来并刷在线状态（记录本身不改连接）
            knownMachines = knownMachines.map { machine ->
                machine.copy(online = found.any { it.url == machine.url })
            }
            scanning = false
            scanProgress = null
            if (!adoptFirst) return@launch

            val first = found.firstOrNull()
            if (first != null) {
                adopt(first)
            } else if (!keepCurrentOnFailure) {
                connection = Connection.Failed
                error = buildString {
                    append("没扫到 pi 桥（本机 loopback / 局域网 /24 / 保存的远端）")
                    scanError?.let { append("；扫描错误：$it") }
                }
                scheduleReconnect()
            } else {
                connection = Connection.Failed
                scanError?.let { setStatus("扫描错误：$it") }
                // 扫描没扫到也要继续重试（以前这里就是死路）
                scheduleReconnect()
            }
        }
    }

    fun connectTo(hit: BridgeHit) = adopt(hit)

    /** 直接连一个地址（保存的远端主机 / 手动输入）：先探一下再切。 */
    fun connectToUrl(url: String) {
        val clean = PiLocator.normalizeHost(url)
        scope.launch {
            busy = true
            val hit = withContext(Dispatchers.IO) { PiLocator.probe(clean, token) }
            busy = false
            if (hit != null) adopt(hit) else error = "连不上 $clean（没应答，或 token 不对）"
        }
    }

    private fun adopt(hit: BridgeHit) {
        val sameTarget = hit.url == baseUrl
        if (!sameTarget) {
            // 切走前先记下这台机器正在看哪个会话：切回来要回到原地（不是把人踢回列表）。
            // 注意只记指针，**不给旧机器发任何停止/关闭指令** —— 在跑的一轮继续跑。
            current?.file?.takeIf { it.isNotBlank() }?.let { file ->
                rememberSessionForMachine(baseUrl, file)
            }
            clearMachineState()
            // 换机器：旧列表不该继续显示；立刻进 loading，让界面给出进度而不是"没有会话"
            sessions = emptyList()
            sessionsLoading = true
            scanProgress = null
        }
        baseUrl = hit.url
        prefs?.edit()?.putString("baseUrl", hit.url)?.apply()
        currentHit = hit
        // 能连上就是用户明确选过的目标：自动记住（以前只靠手点「记住」，切走后就没这条了）
        rememberMachineOnConnect(hit)
        connection = Connection.Ok
        error = null
        markConnected()
        refreshSessions()
        refreshInfo()
        // 目标是同一台就别把事件流拆了重连（拆了当次就会“掉线”）
        startEvents()
        if (!sameTarget) restoreSessionForMachine(hit.url)
    }

    // ── 每台机器「上次在看哪个会话」────────────────────────────────────────
    /** 机器地址 → 会话文件。落盘在 prefs（init() 里读回），重启 app 也记得。 */
    private var lastSessions: Map<String, String> = emptyMap()

    private fun rememberSessionForMachine(machineUrl: String, file: String) {
        val next = SessionMemory.remember(lastSessions, machineUrl, file)
        if (next == lastSessions) return
        lastSessions = next
        prefs?.edit()?.putString(SessionMemory.prefsKey(), SessionMemory.encode(next))?.apply()
    }

    /**
     * 切到某台机器后，把它上次那个会话重新打开。
     *
     * 只拉列表 + 恢 UI；桥那边若已绑在同一会话会返回 skipped（幂等，不打断在跑的一轮）。
     */
    private fun restoreSessionForMachine(machineUrl: String) {
        val file = SessionMemory.recall(lastSessions, machineUrl) ?: return
        scope.launch {
            val target = withContext(Dispatchers.IO) {
                runCatching { api.sessions().firstOrNull { it.file == file } }.getOrNull()
            } ?: return@launch
            // 用户又切走了 / 已经手动进了别的会话：不要抢
            if (baseUrl != machineUrl || current?.file == target.file) return@launch
            openSession(target)
        }
    }

    private fun clearMachineState() {
        stopEvents()
        clearAppProfile()
        loadToken++
        current = null
        lastOpenedFile = null
        items = emptyList()
        itemsCache.clear()
        sessions = emptyList()
        info = null
        uiPrompts = emptyList()
        runningFiles = emptySet()
        clearQueueState()
        markStreaming(false, null)
        dropPendingDeltas()
        compactionCardState = null
        compactionCardFile = null
        deviceSentSignature = null
        setStatus(null)
    }

    private fun ready(note: String) {
        connection = Connection.Ok
        error = null
        markConnected()
        if (note.isNotBlank()) setStatus(note)
        currentHit = currentHit?.takeIf { it.url == baseUrl }
        refreshSessions()
        refreshInfo()
        startEvents()
        // 刚连上/刚重连：把上次没报成功的设备信息补一次（桥要它来拼注入提示）
        lastDevice?.let { d -> if (deviceSentSignature != d.signature()) reportDevice(d, force = true) }
    }

    // ── 设备信息（屏幕 / 字号 / 主题）：桥把它拼进注入 pi 的提示 ──────────────────
    /** 最近一次算出来的设备信息（供重连后补发）。 */
    private var lastDevice: DeviceInfo? = null
    /** 已经成功上报过的签名（失败时不记，下次连上会再来）。 */
    private var deviceSentSignature: String? = null

    /**
     * 上报屏幕与排版参数。**签名没变就不发**：这个函数会在每次重组/旋转/改字号时被调用，
     * 无脑发会把桥的 pi 进程反复回收（桥收到变化会重启子进程让新提示生效）。
     */
    fun reportDevice(info: DeviceInfo, force: Boolean = false) {
        lastDevice = info
        val sig = info.signature()
        if (!force && sig == deviceSentSignature) return
        ioSuspend(busyFlag = false) {
            runCatching { api.device(info) }
                .onSuccess {
                    deviceSentSignature = sig
                    if (BuildConfig.DEBUG) android.util.Log.i("PiPocketDebug", "device 已上报 $sig")
                }
                .onFailure { android.util.Log.i("PiPocketDebug", "device 上报失败：${it.message}") }
        }
    }

    private fun stopEvents() {
        eventGeneration.next()
        val old = eventSource
        eventSource = null
        old?.cancel()
    }

    private fun startEvents() {
        if (eventSource != null) return
        val generation = eventGeneration.next()
        lastEventAt = System.currentTimeMillis()
        eventSource = api.connectEvents(object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: Response) {
                scope.launch {
                    if (!eventGeneration.isCurrent(generation)) return@launch
                    lastEventAt = System.currentTimeMillis()
                }
            }

            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                scope.launch {
                    if (!eventGeneration.isCurrent(generation)) return@launch
                    lastEventAt = System.currentTimeMillis()
                    runCatching { onEvent(JSONObject(data)) }
                }
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                scope.launch { disconnected(generation, t?.message ?: "事件流断开（HTTP ${response?.code}）") }
            }

            override fun onClosed(eventSource: EventSource) {
                scope.launch { disconnected(generation, "事件流已关闭") }
            }
        })
    }

    private fun disconnected(generation: Long, message: String) {
        if (!eventGeneration.isCurrent(generation)) return
        stopEvents()
        connection = Connection.Failed
        error = message
        scheduleReconnect()
    }

    /** Termux 的 RUN_COMMAND 是 dangerous 权限，得运行时申请；声明在 manifest 里不够。 */
    fun termuxRunPermissionGranted(context: Context): Boolean =
        context.checkSelfPermission("com.termux.permission.RUN_COMMAND") == android.content.pm.PackageManager.PERMISSION_GRANTED

    fun updateLaunchMessage(message: String?) {
        launchMessage = message
    }

    /**
     * 保证 app 有一个 token（没有就生成 32 hex 并存 prefs）。
     *
     * 用户口径：**本机桥也不能免 token** —— 拉起本机桥时把 token 用环境变量传给启动器，
     * 启动器落盘到 `~/.pi/agent/pi-pocket.env`，之后手工启动也复用同一个。
     */
    fun ensureToken(): String {
        if (token.isNotBlank()) return token
        val bytes = ByteArray(16)
        java.security.SecureRandom().nextBytes(bytes)
        val generated = bytes.joinToString("") { "%02x".format(it) }
        updateToken(generated)
        return generated
    }

    /** 让 Termux 把桥拉起来（`com.termux.RUN_COMMAND`，需要 Termux 侧 allow-external-apps=true）。 */
    fun launchLocalBridge(context: Context) {
        val installed = runCatching { context.packageManager.getPackageInfo("com.termux", 0) }.isSuccess
        if (!installed) {
            launchMessage = "这台手机上没装 Termux"
            return
        }
        val intent = android.content.Intent("com.termux.RUN_COMMAND").apply {
            setClassName("com.termux", "com.termux.app.RunCommandService")
            // 由项目提供的启动器负责：wake-lock → 默认 cwd → exec 桥（不把 shell 脚本内联进 app，可单独升级）
            putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
            // token：把 app 手里的 token 传给启动器（落盘后手工启动也复用），本机桥同样要鉴权
            val localToken = ensureToken()
            putExtra(
                "com.termux.RUN_COMMAND_ARGUMENTS",
                arrayOf("-lc", "PI_POCKET_TOKEN=" + localToken + " exec pi-pocket-start"),
            )
            putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home")
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
            // Termux 的语义：0=切到已有会话 1=新建会话并切过去 2=新建会话但**不切前台**。
            // 之前用 1 ⇒ 等于请求"把 Termux 拉到前台"，正是 Android 10+ 明令限制的
            // 「后台启动 Activity」，所以 Termux 没在跑时经常启不起来。用 2 才是不切换。
            putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "2")
        }
        val attempt = runCatching { context.startService(intent) }
        if (attempt.isFailure) {
            // 实测最容易踩的两条：Termux 侧 allow-external-apps=false；或 app 在后台时被禁止启其它 app 的服务
            launchMessage = "启动请求失败：${attempt.exceptionOrNull()?.message ?: "未知原因"}"
            return
        }
        launchMessage = "已请求 Termux 启动桥，等待就绪…"
        scope.launch {
            repeat(12) {
                delay(1000)
                val hit = runCatching { PiLocator.scan(baseUrl, token, savedHosts, includeLan = false, context = appContext) }
                    .getOrDefault(emptyList())
                    .firstOrNull { it.scope == BridgeScope.Local }
                if (hit != null) {
                    launchMessage = "桥已在 ${hit.url} 就绪"
                    adopt(hit)
                    return@launch
                }
            }
            // 兜底：把 Termux 拉到前台（用户主动点的按钮 ⇒ 允许切 Activity），
            // 让用户/日志直接看到桥为什么没起来（最常见：allow-external-apps=false）。
            runCatching {
                context.packageManager.getLaunchIntentForPackage("com.termux")?.let {
                    it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(it)
                }
            }
            launchMessage = "未探到桥，已打开 Termux：检查 ~/.termux/termux.properties 的 allow-external-apps=true（改完 termux-reload-settings）"
        }
    }

    fun clearLaunchMessage() {
        launchMessage = null
    }

    /**
     * 流式增量合并。
     *
     * 为什么不能逐条刷：每次 `items` 变化都会让可见行重组，而每行重组都要重新解析整段 Markdown
     * ⇒ 手机上一秒几十次全量重解析 = 流式卡顿（用户 2026-10-19 报的）。现在攒一个窗口刷一次。
     */
    private fun queueDelta(type: String, piece: String) {
        if (piece.isEmpty()) return
        val alreadyScheduled = synchronized(deltaText) {
            when (type) {
                "thinking_delta" -> deltaThinking.append(piece)
                "text_delta" -> deltaText.append(piece)
                else -> return
            }
            if (deltaFlushPending) true else { deltaFlushPending = true; false }
        }
        if (alreadyScheduled) return
        scope.launch {
            delay(DELTA_FLUSH_MS)
            flushDeltas()
        }
    }

    /** 把窗口里攒的增量一次写进 items（一窗一次重组 + 一次重解析）。 */
    private fun flushDeltas() {
        val text: String
        val thinking: String
        synchronized(deltaText) {
            text = deltaText.toString()
            thinking = deltaThinking.toString()
            deltaText.clear()
            deltaThinking.clear()
            deltaFlushPending = false
        }
        if (text.isEmpty() && thinking.isEmpty()) return
        items = items.map {
            if (it.key != STREAM_KEY) it
            else it.copy(text = it.text + text, thinking = it.thinking + thinking)
        }
        markStreaming(true, current?.file)
    }

    /** 丢掉还没落地的增量（权威消息到了 / 回合结束）。 */
    private fun dropPendingDeltas() {
        synchronized(deltaText) {
            deltaText.clear()
            deltaThinking.clear()
            deltaFlushPending = false
        }
    }

    /** 当前正在处理的那条 pi 事件属于哪个会话文件（onPiRecord 里给 queue_update 认领归属用）。 */
    private var eventFile: String? = null

    private fun onEvent(payload: JSONObject) {
        when (payload.optString("kind")) {
            "bridge" -> when (payload.optString("event")) {
                // 远端机器（host-a/WSL）发来的通知：pocket 通道 —— app 自己弹。
                // 分类字段是 notifyKind（信封的 kind 已被 'bridge' 占用，不能撞名）。
                "notify" -> {
                    val ctx = appContext
                    val kind = payload.optString("notifyKind").ifBlank { "note" }
                    val title = payload.optString("title").ifBlank { id.harness.pipocket.ui.notifyTitle(kind) }
                    val text = payload.optString("text")
                    val file = payload.optString("file").takeIf { it.isNotBlank() }
                    if (ctx != null) NotifyReceiver.post(ctx, kind, title, text, file)
                }

                "worker_started" -> setStatus("pi 已启动", clearAfterMs = 5_000)

                // SSE 每次（重）连上桥都会收到 hello：说明桥重启过/断过，此时所有 pi 子进程都没了，
                // 必须把界面状态对齐、重拉历史，否则会一直显示"工作中"却再也没有输出。
                "hello" -> {
                    markStreaming(false, null)
                    runningFiles = emptySet()
                    items = items.map { if (it.toolRunning) it.copy(toolRunning = false, toolLive = "", toolStartedAt = 0L) else it }
                    // 收到 hello 说明这条 SSE 是（重）连上的：桥可能刚重启过，服务端不一定还记着
                    // “当前会话”。**先把界面上这个会话交回给桥**，再拉历史 —— 否则紧接着发出的消息
                    // 会落进一个新会话里（用户 2026-10-19 实测：“在原地发消息，变成了新会话”）。
                    scope.launch { rehydrateAfterReconnect() }
                }

                "worker_exit" -> {
                    markStreaming(false, null)
                    // 进程没了：所有"运行中"的工具行都不再可信（否则会永久显示正在跑 write）
                    items = items.map { if (it.toolRunning) it.copy(toolRunning = false, toolLive = "", toolStartedAt = 0L) else it }
                    setStatus("pi 进程退出（下次操作会自动重启）", clearAfterMs = 8_000)
                    addNotice(
                        title = "pi 进程退出（code=${payload.opt("code")}）",
                        detail = payload.optString("stderr"),
                        level = "error",
                    )
                }
            }

            "pi" -> {
                val record = payload.optJSONObject("record") ?: return
                val sf = payload.optString("sessionFile").ifBlank { null }
                // 多 worker：只把**当前会话**的事件画到界面上；别的会话在跑也不干扰这里
                if (sf != null) {
                    when (record.optString("type")) {
                        "agent_start", "message_start", "tool_execution_start" -> runningFiles = runningFiles + sf
                        "agent_settled" -> runningFiles = runningFiles - sf
                    }
                    if (current?.file != null && sf != current?.file) return
                }
                eventFile = sf
                onPiRecord(record)
            }
        }
    }

    private fun onPiRecord(record: JSONObject) {
        when (record.optString("type")) {
            "message_start" -> {
                val message = record.optJSONObject("message") ?: return
                if (message.optString("role") == "user") {
                    val content = message.opt("content")
                    val (images, omitted) = MessageParser.imagesOf(content)
                    // 本机刚发的图：SSE 里不一定带原图，用发送时留下的副本兜底
                    val local = images.ifEmpty { pendingImages }
                    pendingImages = emptyList()
                    items = items + ChatItem(
                        key = "user-" + System.currentTimeMillis(),
                        role = "user",
                        text = MessageParser.textOnly(content).trim(),
                        images = local,
                        imagesOmitted = if (local.isNotEmpty()) 0 else omitted,
                    )
                } else if (message.optString("role") == "assistant") {
                    markStreaming(true, current?.file)
                    items = items.filterNot { it.streaming } + ChatItem(
                        key = STREAM_KEY, role = "assistant", streaming = true,
                    )
                }
            }

            "message_update" -> {
                val delta = record.optJSONObject("assistantMessageEvent") ?: return
                queueDelta(delta.optString("type"), delta.optString("delta"))
            }

            "message_end" -> {
                val message = record.optJSONObject("message") ?: return
                if (message.optString("role") != "assistant") return
                // 权威消息带着全部内容；还攒着没落地的增量直接丢掉
                dropPendingDeltas()
                pendingImages = emptyList()
                val finalKey = MessageParser.assistantKey(message)
                // 流式占位行 → 权威行：行换 key，但开合状态要跟着走
                transferOpenState(STREAM_KEY, finalKey)
                val final = MessageParser.assistantItem(message, finalKey)
                items = items.filterNot { it.key == STREAM_KEY } +
                    (final ?: ChatItem(key = finalKey, role = "assistant", text = ""))
                // ⚠️ 这里**不要** markStreaming(false)：一轮里可能有多条 assistant 消息（工具调用前后各一条），
                // 消息结束 ≠ 回合结束（终端也是 `agent_end` 才清 working）。以前就是这一行让「工作中」一直闪。
                // 回合级结局：pi 把原因放在 stopReason（pending|stop|length|toolUse|error|aborted|deferred）
                // 与 errorMessage。用户口径：**报错、中止原因等等都要能看到**，所以除了 error 之外，
                // aborted / length / deferred 以及任何未来新增的非正常值都各自成一条 notice，并把原始值写出来。
                val stopReason = message.optString("stopReason")
                val errorMessage = message.optString("errorMessage")
                when {
                    stopReason == "error" || (errorMessage.isNotBlank() && stopReason != "aborted") ->
                        addNotice(
                            "回合失败（stopReason=" + stopReason.ifBlank { "error" } + "）",
                            errorMessage.ifBlank { "模型没给出 errorMessage" },
                            "error",
                        )

                    stopReason == "aborted" -> addNotice(
                        "回合被中止（stopReason=aborted）",
                        errorMessage.ifBlank {
                            if (abortedByUser) "你在 app 里点停止 / 插话重发，pi 中止了这一轮"
                            else "外部中止（终端里 Ctrl-C、桥断开或 pi 自己决定）"
                        },
                        "warn",
                    )

                    stopReason == "length" -> addNotice(
                        "输出被截断（stopReason=length）",
                        "达到模型输出上限；让它「继续」就会接着写（不需要重新问）",
                        "warn",
                    )

                    stopReason == "deferred" -> addNotice(
                        "回合延后（stopReason=deferred）",
                        errorMessage,
                        "info",
                    )

                    stopReason.isNotBlank() && stopReason !in setOf("stop", "toolUse", "pending") ->
                        addNotice("回合结束（stopReason=" + stopReason + "）", errorMessage, "warn")
                }
                abortedByUser = false
                syncWorking()
            }

            // 工具调用：与 pi 终端一致——先占一行（正在跑），结束就补上结果/时长，展开才看输出
            "tool_execution_start" -> {
                // 嵌套调用（codemode 脚本里的 tools.*、MCP 子调用）：**终端也不画**，
                // 而且它不写进会话（只挂在父调用的 details 里）⇒ 建卡就是一张重载后消失的幽灵卡。
                if (record.optString("parentToolCallId").isNotBlank()) return
                val id = record.optString("toolCallId")
                val name = record.optString("toolName")
                if (name.isNotBlank()) {
                    val disp = record.optJSONObject("display")
                    // 终端原样头行（$ cmd）：桥在 start 时也渲染了（null result 只生成 call），
                    // 运行中就显示命令头，不用等结束才从 exec_command 变成 $ cmd。
                    val term = record.optJSONObject("term")
                    val item = ChatItem(
                        key = if (id.isNotBlank()) "tool-$id" else "tool-" + System.currentTimeMillis(),
                        role = "tool",
                        isTool = true,
                        toolName = name,
                        toolBrief = MessageParser.briefOf(record.optJSONObject("args")),
                        dispTitle = disp?.optString("title").orEmpty(),
                        dispArgsCollapsed = disp?.optString("argsCollapsed").orEmpty(),
                        dispArgsExpanded = disp?.optString("argsExpanded").orEmpty(),
                        dispPreviewLines = disp?.optInt("previewLines") ?: 0,
                        termCall = term?.optJSONArray("call")?.let { arr -> (0 until arr.length()).map { arr.optString(it) } } ?: emptyList(),
                        toolRunning = true,
                        // 运行中的计时基准（终端每秒重画 `Elapsed 12s`）
                        toolStartedAt = System.currentTimeMillis(),
                    )
                    items = items.filterNot { it.key == item.key } + item
                }
            }

            // pi 的特性：工具跑的过程中就报 partialResult（json.md：tool_execution_update）。
            // 不接的话，工具行在跑的时候只能显示“运行中…”，长命令看不到任何进展。
            // 桥还会给 partialResult 渲染好 term（pi 的 renderResult + isPartial=true），
            // 拿得到就用它 —— poll / write_stdin 这类动态工具的块就能像终端一样实时长。
            //
            // ⚠️ 这里以前是**两个** `"tool_execution_update" ->` 分支（编译器报 Duplicate branch），
            // 第二个（带 term 实时块的那个）永远不可达 ⇒ 动态工具的终端块其实一直没在长。
            // 现在合并成一个分支，两件事都做。
            "tool_execution_update" -> {
                val id = record.optString("toolCallId")
                if (id.isBlank()) return
                val partial = MessageParser.textOf(record.optJSONObject("partialResult")?.opt("content"))
                val term = record.optJSONObject("term")
                fun termLines(arr: org.json.JSONArray?): List<String> {
                    if (arr == null) return emptyList()
                    val ansi = Regex("\u001B\\[[0-9;]*m")
                    fun blank(s: String) = s.replace(ansi, "").isBlank()
                    val l = (0 until arr.length()).map { arr.optString(it).replace(Regex("[ \\t]+$"), "") }.toMutableList()
                    while (l.size > 1 && blank(l[0]) && blank(l[1])) l.removeAt(0)
                    while (l.isNotEmpty() && blank(l.last())) l.removeAt(l.lastIndex)
                    return l
                }
                val live = termLines(term?.optJSONArray("resultCollapsed"))
                items = items.map {
                    if (it.key != "tool-$id") it
                    else it.copy(
                        toolResult = partial.ifBlank { it.toolResult },
                        toolRunning = true,
                        toolLive = partial.trim().ifBlank { it.toolLive },
                        termResultCollapsed = live.ifEmpty { it.termResultCollapsed },
                        termHasCollapsed = term?.has("resultCollapsed") == true || it.termHasCollapsed,
                    )
                }
            }

            // 任何事件之后都对一遍工作状态（工具起停、pi 侧状态变化都能立刻反映）
            "tool_execution_end" -> {
                if (record.optString("parentToolCallId").isNotBlank()) return
                val id = record.optString("toolCallId")
                val text = MessageParser.textOf(record.optJSONObject("result")?.opt("content")).trim()
                val isError = record.optBoolean("isError")
                val duration = record.optLong("durationMs", -1L).takeIf { it >= 0 }
                if (id.isBlank()) return
                // 桥会用 pi 自己的渲染器把结果渲染成 HTML（edit 的 diff 块等）——有就用它，没有就纯文本
                val html = record.optJSONObject("html")
                // 终端原样行（term）：桥用 pi 的 renderCall/renderResult 算好送来（`$ cmd` 头行 + 结果块）。
                // 之前这里漏解析 term，导致实时工具卡永远退回「工具名」标题（用户报的「linux 命令被显示成 exec_command」）。
                val term = record.optJSONObject("term")
                fun termLines(arr: org.json.JSONArray?, keepLeadingBlank: Boolean): List<String> {
                    if (arr == null) return emptyList()
                    val ansi = Regex("\u001B\\[[0-9;]*m")
                    fun blank(s: String) = s.replace(ansi, "").isBlank()
                    val l = (0 until arr.length()).map { arr.optString(it).replace(Regex("[ \\t]+$"), "") }.toMutableList()
                    if (keepLeadingBlank) {
                        // 结果块开头的空行是 pi 在头行与正文之间的呼吸，删了就不一致
                        while (l.size > 1 && blank(l[0]) && blank(l[1])) l.removeAt(0)
                    } else {
                        while (l.isNotEmpty() && blank(l.first())) l.removeAt(0)
                    }
                    while (l.isNotEmpty() && blank(l.last())) l.removeAt(l.lastIndex)
                    return l
                }
                items = items.map {
                    if (it.key != "tool-$id") it
                    else it.copy(
                        toolResult = text.ifBlank { it.toolResult },
                        // read 图片：结果里带 base64 就直接用；不带就用入参 path 让 UI 自己拉
                        // （桥给历史剥了图，实时事件也不保证带 ⇒ 否则只能看到一行 Read image file）
                        toolImages = MessageParser.imagesOfToolResult(record.optJSONObject("result")?.opt("content"))
                            .ifEmpty { it.toolImages },
                        toolImagePaths = MessageParser.readImagePaths(text, argsPathOf(it))
                            .ifEmpty { it.toolImagePaths },
                        toolIsError = isError,
                        toolDurationMs = duration,
                        toolRunning = false,
                        toolStartedAt = 0L,
                        toolLive = "",
                        dispHtmlCollapsed = html?.optString("htmlCollapsed").orEmpty(),
                        dispHtmlExpanded = html?.optString("htmlExpanded").orEmpty(),
                        // end 事件里 term 可能缺（渲染失败/工具不是内置）⇒ 保留 start 时那份，
                        // 别把已经在显示的 `$ cmd` 头行洗成空（洗空后又退回 app 自拼标题）。
                        termCall = termLines(term?.optJSONArray("call"), false).ifEmpty { it.termCall },
                        termResultCollapsed = termLines(term?.optJSONArray("resultCollapsed"), true),
                        termResultExpanded = termLines(term?.optJSONArray("resultExpanded"), true),
                        termHasCollapsed = term?.has("resultCollapsed") == true,
                        termHasExpanded = term?.has("resultExpanded") == true,
                    )
                }
                if (isError) {
                    setStatus("工具失败：" + record.optString("toolName"), eventFile ?: current?.file, clearAfterMs = 8_000)
                    addNotice(
                        title = "工具失败：${record.optString("toolName")}",
                        detail = text.take(400),
                        level = "error",
                    )
                }
            }

            "agent_start" -> {
                markStreaming(true, current?.file)
                if (workStartedAt == null) workStartedAt = System.currentTimeMillis()
            }
            "agent_settled" -> {
                markStreaming(false, current?.file)
                // 收尾：任何还挂着"运行中"的工具行都清掉（事件丢失时的兜底）
                items = items.map { if (it.toolRunning) it.copy(toolRunning = false, toolLive = "", toolStartedAt = 0L) else it }
                refreshInfo()          // refreshInfo 里会 syncWorking()：以 pi 的 isStreaming 为准
                syncWorking()
            }

            "queue_update" -> {
                val steeringArr = record.optJSONArray("steering")
                val followArr = record.optJSONArray("followUp")
                // 队列是 per-session 的：先认领归属（事件不带 sessionFile 时才退回当前会话）
                val owner = eventFile ?: current?.file
                if (owner != null && current?.file != null && owner != current?.file) return
                queueFile = owner ?: current?.file
                queuedSteering = (0 until (steeringArr?.length() ?: 0)).map { steeringArr!!.optString(it) }
                queuedFollowUp = (0 until (followArr?.length() ?: 0)).map { followArr!!.optString(it) }
                val n = queuedSteering.size + queuedFollowUp.size
                setStatus(if (n > 0) "排队中：steer ${queuedSteering.size} / follow-up ${queuedFollowUp.size}" else null, owner)
            }

            "compaction_start" -> {
                setStatus("正在压缩上下文…", eventFile ?: current?.file)
                // 卡片从开始就在（转圈），压完就地改成结果 —— 不再往 items 里落一条重复的通知
                showCompactionCard(
                    "正在压缩上下文…",
                    record.optString("reason").takeIf { it.isNotBlank() }?.let { "原因：$it" } ?: "",
                    eventFile ?: current?.file,
                    running = true,
                )
            }

            "compaction_end" -> {
                val error = record.optString("errorMessage")
                val result = record.optJSONObject("result")
                when {
                    error.isNotBlank() -> {
                        setStatus("上下文压缩失败", eventFile ?: current?.file, clearAfterMs = 10_000)
                        addNotice("上下文压缩失败", error, "error", kind = "compaction")
                        // 卡片也要停转，否则一直转圈
                        showCompactionCard("上下文压缩失败", error, eventFile ?: current?.file)
                    }

                    record.optBoolean("aborted") -> {
                        addNotice("上下文压缩被取消", "", "warn", kind = "compaction")
                        showCompactionCard("压缩已取消", "", eventFile ?: current?.file)
                    }

                    else -> {
                        setStatus("上下文已压缩", eventFile ?: current?.file, clearAfterMs = 6_000)
                        showCompactionCard(
                            "上下文已压缩",
                            id.harness.pipocket.ui.compactionDetail(
                                result?.optLong("tokensBefore") ?: 0,
                                result?.optLong("estimatedTokensAfter") ?: 0,
                            ),
                            eventFile ?: current?.file,
                            summary = result?.optString("summary").orEmpty(),
                        )
                    }
                }
            }

            "summarization_retry_scheduled" -> addNotice(
                "摘要重试 ${record.optInt("attempt")}/${record.optInt("maxAttempts")}",
                record.optString("errorMessage"),
                "warn",
            )

            "extension_error" -> addNotice(
                "扩展报错：${record.optString("extensionPath")}",
                "${record.optString("event")}：${record.optString("error")}",
                "error",
            )

            "auto_retry_start" -> {
                // 除了一行状态，还要留住一份给顶部横幅（带“中断重试”按钮；终端是 Esc）
                retrying = RetryInfo(
                    attempt = record.optInt("attempt"),
                    maxAttempts = record.optInt("maxAttempts"),
                    delayMs = record.optLong("delayMs"),
                    atMs = System.currentTimeMillis(),
                )
                // 不在状态行再写一遍：输入框上方已经有横幅（同一句话 + 中断按钮），
                // 两处同时显示只会显得吵。
            }

            "auto_retry_end" -> {
                retrying = null
                if (!record.optBoolean("success", true)) {
                    setStatus("重试失败", eventFile ?: current?.file, clearAfterMs = 8_000)
                    addNotice("重试失败", record.optString("finalError").ifBlank { "模型连续失败" }, "error")
                }
            }

            "session_info_changed" -> {
                val name = record.optString("name")
                current = current?.copy(name = name.ifBlank { null })
            }

            "extension_ui_request" -> onExtensionUi(record)

            // 执行面的流式输出（只接自己那次请求的 id）
            "bash_execution_update" -> {
                val id = record.optString("id")
                if (id.isNotBlank() && id == shellRequestId) {
                    shellOutput += record.optString("delta")
                }
            }

            else -> Unit
        }
    }

    /** 扩展 UI：状态/通知直接显示；需要回答的一律先回 decline，避免 pi 挂住。 */
    /** 扩展 UI：状态/通知落 notice；**对话框类改为可在 app 里应答**（select/confirm/input/editor）。 */
    private fun onExtensionUi(record: JSONObject) {
        val id = record.optString("id")
        when (record.optString("method")) {
            "setStatus" -> {
                val text = record.optString("statusText").takeIf { it.isNotBlank() }
                // 扩展的 setStatus（如 auto-provider 的 `start primary …`）是**这个会话**的：
                // 不带归属的话切到别的会话还挂着（用户 2026-10-21 的跨会话脏数据一类）。
                setStatus(text?.let { "[${record.optString("statusKey")}] $it" }, eventFile ?: current?.file)
            }

            "notify" -> {
                val level = when (record.optString("notifyType")) {
                    "error" -> "error"
                    "warning", "warn" -> "warn"
                    else -> "info"
                }
                addNotice(record.optString("message"), record.optString("title"), level)
            }

            "setTitle" -> setStatus(record.optString("title").takeIf { it.isNotBlank() }, eventFile ?: current?.file)

            // 预填输入框（不自动发送）
            "set_editor_text" -> editorPrefill = record.optString("text")

            "select", "confirm", "input", "editor" -> {
                if (id.isBlank()) return
                val options = record.optJSONArray("options")?.let { arr ->
                    (0 until arr.length()).map { arr.optString(it) }
                } ?: emptyList()
                val prompt = UiPrompt(
                    id = id,
                    method = record.optString("method"),
                    title = record.optString("title").ifBlank { "pi 需要你回应" },
                    message = record.optString("message"),
                    options = options,
                    placeholder = record.optString("placeholder"),
                    prefill = record.optString("prefill"),
                    timeoutMs = record.optLong("timeout", -1L).takeIf { it > 0 },
                    // 记下**发出请求的那个会话**：应答必须回给它（见 UiPrompt.file 的注释）
                    file = eventFile ?: current?.file,
                )
                uiPrompts = uiPrompts.filterNot { it.id == id } + prompt
            }

            else -> if (id.isNotBlank()) {
                addNotice(
                    title = "pi 请求了 app 不支持的交互：${record.optString("method")}",
                    detail = record.optString("message").ifBlank { record.optString("title") },
                    level = "warn",
                    needsTerminal = true,
                )
                scope.launch { runCatching { answerRaw(id, JSONObject().put("cancelled", true)) } }
            }
        }
    }

    /** 应答 value 类（select / input / editor）。 */
    fun answerValue(prompt: UiPrompt, value: String) = io {
        answerRaw(prompt.id, JSONObject().put("value", value), prompt.file)
        uiPrompts = uiPrompts.filterNot { it.id == prompt.id }
    }

    /** 应答 confirm。 */
    fun answerConfirm(prompt: UiPrompt, confirmed: Boolean) = io {
        answerRaw(prompt.id, JSONObject().put("confirmed", confirmed), prompt.file)
        uiPrompts = uiPrompts.filterNot { it.id == prompt.id }
    }

    /** 取消（扩展收到 undefined / false）。 */
    fun cancelPrompt(prompt: UiPrompt) = io {
        answerRaw(prompt.id, JSONObject().put("cancelled", true), prompt.file)
        uiPrompts = uiPrompts.filterNot { it.id == prompt.id }
    }

    /** 应答要**回给发出请求的那个会话的 worker**（不传 file 时桥才退回“当前会话”）。 */
    private fun answerRaw(id: String, response: JSONObject, file: String? = null) {
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { api.answerUi(id, response, file) }
            }.onFailure { e ->
                addNotice("应答没发出去", e.message ?: "未知错误", "error")
            }
        }
    }

    fun consumeEditorPrefill(): String? {
        val value = editorPrefill
        editorPrefill = null
        return value
    }

    /** 把 pi 的报错/提醒/状态落成会话里的一行（默认折叠，点开看 detail）。 */
    fun addNotice(
        title: String,
        detail: String = "",
        level: String = "info",
        needsTerminal: Boolean = false,
        /** 非空 = 同类通知只保留一条（旧的被**替换**），用于「压缩上下文」这种开始/结束要合并成一张卡的场景。 */
        kind: String = "",
    ) {
        if (title.isBlank()) return
        val last = items.lastOrNull()
        // 连着重复的同一件事就不再落一行
        if (last != null && last.isNotice && last.text == title && last.noticeDetail == detail) return
        // 与顶部状态条同文时，把状态条收起来，避免同一个信息出现两遍
        if (statusLine != null && statusLine!!.trim() == title.trim()) setStatus(null)
        // 同类通知（kind 相同）：**就地替换**，不追加第二条
        if (kind.isNotBlank()) items = items.filterNot { it.noticeKind == kind }
        items = items + ChatItem(
            key = if (kind.isNotBlank()) "notice-kind-$kind" else "notice-${System.currentTimeMillis()}-${items.size}",
            role = "notice",
            isNotice = true,
            text = title,
            noticeLevel = level,
            noticeDetail = detail,
            noticeNeedsTerminal = needsTerminal,
            noticeKind = kind,
        )
    }

    // ── 动作 ──────────────────────────────────────────────────────────────
    /** 配置里扫到的机器（~/.ssh/config 等），扫描面板会并进候选。 */
    var configMachines by mutableStateOf<List<BridgeHit>>(emptyList())

    /** 配置里的机器：SSH 端口是否可达（22/自定义端口，单独探一次 TCP）。 */
    var configSshOnline by mutableStateOf<Map<String, Boolean>>(emptyMap())
    /** 配置里的机器各自的 SSH 端口（缺省 22）。 */
    var configSshPort by mutableStateOf<Map<String, Int>>(emptyMap())
        private set

    /**
     * 配置里的机器：桥**现在能不能直接连**（url → 三态）。
     *
     * 以前面板只探 SSH 端口，所以一排全是「SSH 在线」，看不出哪台能直连（用户 2026-10-24 指出）。
     * 缺项 = 还没探到。
     */
    var configBridgeReach by mutableStateOf<Map<String, BridgeReach>>(emptyMap())

    /** 记住的机器的实时可达性（url → 三态）；打开面板时探一遗，不再只靠启动/扫描时的旧值。 */
    var knownReach by mutableStateOf<Map<String, BridgeReach>>(emptyMap())

    /** 手加的主机（原始输入，如 `192.0.2.35:8789`）的实时可达性。 */
    var savedHostReach by mutableStateOf<Map<String, BridgeReach>>(emptyMap())

    /**
     * 桥自报的主机名（url → `health.data.host`）。
     *
     * 面板用它认出「同一台机器的多条入口」（host-a 的 LAN 地址与 Tailscale 地址会报同一个 host），
     * 探不到的条目不会带 host，因此不会被错并。
     */
    var machineHostById by mutableStateOf<Map<String, String>>(emptyMap())

    /**
     * **不经桥**、直接用 Termux 扫配置里的机器（用户口径：即使没有 bridge 也要能扫）。
     *
     * 做法：RUN_COMMAND 让 Termux 起一个只读的一次性 HTTP 服务（127.0.0.1:8791，30 秒自关），
     * 内容是它自己解析出来的 `~/.ssh/config` + `~/.pi/agent/pi-pocket-hosts.json`；
     * app 轮询这个小服务拿清单 —— 不需要存储权限、也不依赖桥在跑。
     */
    fun scanConfigViaTermux(context: Context) = io(busyFlag = false) {
        scanProgress = "让 Termux 读本机配置…"
        val script = java.util.Base64.getEncoder().encodeToString(TERMUX_CONFIG_SCRIPT.toByteArray())
        val intent = android.content.Intent("com.termux.RUN_COMMAND").apply {
            setClassName("com.termux", "com.termux.app.RunCommandService")
            putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
            putExtra(
                "com.termux.RUN_COMMAND_ARGUMENTS",
                arrayOf("-lc", "echo " + script + " | base64 -d > /tmp/pi-pocket-machines.js && node /tmp/pi-pocket-machines.js"),
            )
            putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home")
            putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "2")
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
        }
        runCatching { context.startService(intent) }
            .onFailure { scanProgress = "启动 Termux 失败：" + (it.message ?: "未知"); return@io }

        // 轮询那个一次性服务（Termux 起 node 要一两秒）
        repeat(12) {
            Thread.sleep(1000)
            val hit = runCatching {
                val req = okhttp3.Request.Builder().url("http://127.0.0.1:8791/machines").build()
                okhttp3.OkHttpClient.Builder().callTimeout(2, java.util.concurrent.TimeUnit.SECONDS).build()
                    .newCall(req).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
            }.getOrNull()
            if (hit != null) {
                val arr = org.json.JSONObject(hit).optJSONArray("machines") ?: org.json.JSONArray()
                val machines = ConfigMachines.parse(arr)
                configMachines = machines.map { ConfigMachines.toHit(it, authFallback = false) }
                probeSsh(machines.map { it.host to it.port })
                probeBridges(machines.map { it.url })
                scanProgress = "Termux 配置里找到 " + configMachines.size + " 台机器"
                return@io
            }
        }
        // Termux 的一次性服务没起来（RUN_COMMAND 没生效）：
        // 退一步用**当前桥**的 /v1/config/machines —— 桥就跑在同一台手机上，
        // 读的是同一份 ~/.ssh/config，效果等价（用户口径：勾了"读本机配置"就该扫到 win）。
        runCatching { applyConfigMachines(api.configMachines(), source = "桥") }
            .onSuccess { scanProgress = "Termux 没响应，已改从桥读本机配置：找到 " + configMachines.size + " 台机器" }
            .onFailure { scanProgress = "读配置失败：" + (it.message ?: "未知") + "（Termux 与桥都没回）" }
    }

    /**
     * 探一遍配置机器的 **SSH 端口**（用户口径：扫描要能显示 win 的 SSH 在线）。
     *
     * 只做一次 TCP connect（800ms 超时），不去认证；结果供面板显示「SSH 在线 / 未响应」，
     * 也决定「装/更新桥 / 拉起 pi」是否可用。
     */
    private fun probeSsh(hosts: List<Pair<String, Int>>) {
        val result = mutableMapOf<String, Boolean>()
        val ports = mutableMapOf<String, Int>()
        for ((host, port) in hosts) {
            ports[host] = port
            result[host] = runCatching {
                java.net.Socket().use { it.connect(java.net.InetSocketAddress(host, port), 800) }
                true
            }.getOrDefault(false)
        }
        configSshPort = ports
        configSshOnline = result
    }

    /**
     * 探一遗配置机器的**桥**（只读 `GET /v1/health`，带当前 token）：面板靠它区分
     * 「可直连 / 需口令 / 桥未起」。串行探（机器就十几台，每台最多 1.2s 超时）。
     */
    private fun probeBridges(urls: List<String>) {
        val distinct = urls.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (distinct.isEmpty()) {
            configBridgeReach = emptyMap()
            return
        }
        val out = LinkedHashMap<String, BridgeReach>()
        val hosts = LinkedHashMap(machineHostById)
        for (url in distinct) {
            val probe = PiLocator.probeHealth(url, token)
            out[url] = probe.reach
            probe.host?.let { hosts[url] = it }
        }
        configBridgeReach = out
        machineHostById = hosts
    }

    /** 面板上的「再探一次」：不重读配置，只重探桥与 SSH。 */
    fun refreshConfigMachinesReach() = io(busyFlag = false) {
        if (configMachines.isEmpty()) return@io
        probeSsh(configMachines.map { it.host to (configSshPort[it.host] ?: 22) })
        probeBridges(configMachines.map { it.url })
    }

    /**
     * 把「记住的机器」探一遗（打开面板时调）。
     *
     * 以前 online 只在启动/扫描时刷新，没扫过就一直显示「未应答」；而且「免 token」用的是保存时的
     * 旧值，桥后来开了鉴权也不会变。这里一并按实时 health 更新 online / authRequired。
     */
    fun refreshKnownMachines() = io(busyFlag = false) {
        // 手加的主机也一样：以前写死 online=false → 永远「未应答」
        val savedReach = LinkedHashMap<String, BridgeReach>()
        val hosts = LinkedHashMap(machineHostById)
        for (host in savedHosts) {
            val url = PiLocator.normalizeHost(host)
            if (url.isBlank()) continue
            val probe = PiLocator.probeHealth(url, token)
            savedReach[host] = probe.reach
            probe.host?.let { hosts[url] = it }
        }
        savedHostReach = savedReach

        if (knownMachines.isEmpty()) {
            machineHostById = hosts
            return@io
        }
        val reach = LinkedHashMap<String, BridgeReach>()
        knownMachines = knownMachines.map { machine ->
            val probe = PiLocator.probeHealth(machine.url, token)
            reach[machine.url] = probe.reach
            probe.host?.let { hosts[machine.url] = it }
            machine.copy(
                online = probe.reach == BridgeReach.Ok,
                authRequired = probe.authRequired ?: machine.authRequired,
            )
        }
        knownReach = reach
        machineHostById = hosts
        persistKnown()
    }

    /** 把 /v1/config/machines 的返回并进 configMachines（桥与本机两条路共用）。 */
    private fun applyConfigMachines(arr: org.json.JSONArray, source: String) {
        val machines = ConfigMachines.parse(arr)
        configMachines = machines.map { ConfigMachines.toHit(it, authFallback = token.isNotBlank()) }
        if (configMachines.isEmpty()) error("$source 没返回机器")
        probeSsh(machines.map { it.host to it.port })
        probeBridges(machines.map { it.url })
    }

    /** 从桥所在机器读配置里的机器清单（只读）。 */
    fun scanConfigMachines() = io(busyFlag = false) {
        scanProgress = "读配置里的机器…"
        runCatching {
            applyConfigMachines(api.configMachines(), source = "桥")
            scanProgress = "配置里找到 " + configMachines.size + " 台机器"
        }.onFailure { scanProgress = "读配置失败：" + (it.message ?: "未知") }
    }

    /**
     * 在远端机器上**安装 / 更新桥**（用户口径：扫描面板要支持安装配置/更新 bridge）。
     *
     * 走已配好的 SSH 目标：git 拉/更新仓库 → ./termux/install.sh → 重启桥，然后把输出显示出来。
     */
    fun installBridgeOn(target: SshTarget) = io(busyFlag = false) {
        sshBusy = true
        sshMessage = "SSH " + target.key + "：安装/更新桥…"
        val remote = """
            set -e
            DIR=${'$'}{PI_POCKET_REPO:-~/projects/pi-pocket}
            if [ -d "${'$'}DIR/.git" ]; then cd "${'$'}DIR" && git pull --ff-only; else
              mkdir -p "${'$'}(dirname ${'$'}DIR)" && git clone ${'$'}{PI_POCKET_GIT:-https://github.com/OhiyoX/pi-pocket.git} "${'$'}DIR" && cd "${'$'}DIR"
            fi
            ./termux/install.sh
            export PATH="${'$'}HOME/.local/bin:${'$'}PATH"
            mkdir -p "${'$'}HOME/.pi/agent"
            command -v pi-pocket-start >/dev/null || { echo 'pi-pocket-start 未安装'; exit 127; }
            FORCE=1 nohup sh "${'$'}(command -v pi-pocket-start)" >> "${'$'}HOME/.pi/agent/pi-pocket-start.log" 2>&1 &
            i=0; code=000
            while [ "${'$'}i" -lt 10 ]; do
              code=${'$'}(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8789/v1/health || true)
              [ "${'$'}code" != 000 ] && break
              i=${'$'}((i+1)); sleep 0.5
            done
            [ "${'$'}code" != 000 ] || { echo '桥未就绪，日志尾部:'; tail -n 5 "${'$'}HOME/.pi/agent/pi-pocket-start.log"; exit 7; }
            echo BRIDGE_UPDATED
        """.trimIndent()
        val result = SshLauncher.exec(target, command = "bash -lc " + "'" + remote.replace("'", "'\''") + "'", timeoutMs = 180000)
        sshBusy = false
        sshMessage = if (result.ok) "远端已安装/更新桥：" + result.output.takeLast(200)
        else "远端安装失败：" + (result.error ?: result.output).take(300)
    }

    fun refreshSessions(silent: Boolean = false) = io(busyFlag = false) {
        // silent：后台对账（回首页/切前台）。不置 loading → 不闪进度条、不清空列表。
        if (!silent) sessionsLoading = true
        if (!silent) sessionsError = null
        try {
            val fresh = api.sessions()
            sessions = fresh
            sessionsError = null
        } catch (e: Throwable) {
            // 失败要把原因留给界面（否则就是"一直转圈"）；silent 时保留旧列表，只记原因
            sessionsError = e.message ?: "加载失败"
        } finally {
            sessionsLoading = false
        }
    }

    /**
     * 就地回填列表里某条会话的标题/运行态（**本地、零延迟**）。
     *
     * 为什么需要：标题（pi 的 `session_info.name`）和「工作中」是会话内才知道的一手状态，
     * 回首页再等一次 `/v1/sessions` 会有可见延迟（还闪进度条）——用户报的"要刷新才更新"。
     * 这里先用本地已知值把列表改对，随后 `refreshSessions(silent = true)` 再和桥对账。
     */
    fun patchSessionRow(file: String?, name: String? = null, running: Boolean? = null) {
        val f = file?.takeIf { it.isNotBlank() } ?: return
        if (sessions.none { it.file == f }) return
        val bumped = running == true
        sessions = sessions.map { s ->
            if (s.file != f) s else s.copy(
                name = name?.takeIf { it.isNotBlank() } ?: s.name,
                running = running ?: s.running,
                updatedAt = if (bumped) java.time.Instant.now().toString() else s.updatedAt,
            )
        }
    }

    /**
     * 历史图默认被桥剥成占位符；用户点「加载图片」时拉一次 `full=1`，把原图贴回对应行。
     * 只存内存（不落 prefs），避免把几十 MB base64 写进本地配置。
     */
    fun loadHistoryImages() {
        if (imagesLoading) return
        imagesLoading = true
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { api.messages(full = true, cols = termCols) } }
                .onSuccess { loaded ->
                    val byIndex = loaded
                        .filter { it.msgIndex >= 0 && it.images.isNotEmpty() }
                        .associate { it.msgIndex to it.images }
                    items = items.map { item ->
                        val images = byIndex[item.msgIndex]
                        if (images != null) item.copy(images = images, imagesOmitted = 0) else item
                    }
                }
                .onFailure { error = "拉历史图失败：" + (it.message ?: "未知错误") }
            imagesLoading = false
        }
    }

    fun refreshInfo() = io(busyFlag = false) {
        // 权威状态拿到后立刻对齐 working（把 syncWorking 挂在 info 赋值之后）
        applyInfo(api.info(), expectFile = null)
    }

    /**
     * 把 /v1/info 的结果落成界面状态（refreshInfo 与 openSession 共用）。
     *
     * [expectFile] 非空时先校对会话：拿回来的数据不是这个会话的就丢掉
     * （切会话时上一个会话的 info 晚到，不能盖到新会话头上）。
     */
    private fun applyInfo(data: JSONObject, expectFile: String?) {
        if (expectFile != null && current?.file != expectFile) return
        val state = data.optJSONObject("state") ?: JSONObject()
        val stats = data.optJSONObject("stats") ?: JSONObject()
        val model = state.optJSONObject("model")
        val context = stats.optJSONObject("contextUsage")
        val tokens = stats.optJSONObject("tokens")
        info = PiInfo(
            model = model?.optString("id").orEmpty(),
            provider = model?.optString("provider").orEmpty(),
            modelDisplay = data.optJSONObject("modelDisplay")?.optString("text").orEmpty(),
            thinking = state.optString("thinkingLevel"),
            levels = data.optJSONArray("levels")?.let { array -> (0 until array.length()).map { array.optString(it) } }
                ?: emptyList(),
            cwd = data.optString("cwd"),
            sessionId = state.optString("sessionId"),
            sessionName = state.optString("sessionName"),
            contextPercent = context?.takeIf { !it.isNull("percent") }?.optDouble("percent"),
            contextTokens = context?.optLong("tokens") ?: 0,
            contextWindow = context?.optLong("contextWindow") ?: 0,
            totalTokens = tokens?.optLong("total") ?: 0,
            tokensInput = tokens?.optLong("input") ?: 0,
            tokensOutput = tokens?.optLong("output") ?: 0,
            cacheRead = tokens?.optLong("cacheRead") ?: 0,
            cacheWrite = tokens?.optLong("cacheWrite") ?: 0,
            cost = stats.optDouble("cost", 0.0),
            autoCompaction = state.optBoolean("autoCompactionEnabled", false),
            gitBranch = data.optString("gitBranch").takeIf { it != "null" }.orEmpty(),
            userMessages = stats.optInt("userMessages"),
            assistantMessages = stats.optInt("assistantMessages"),
            toolCalls = stats.optInt("toolCalls"),
            isStreaming = state.optBoolean("isStreaming"),
            pendingCount = state.optInt("pendingMessageCount"),
        )
        // working/streaming **严格以 pi 的 get_state 为准**（两个方向都同步）：
        // 之前只在 true 时赋值，跑完之后 streaming 卡在 true → 界面一直是"工作中"、
        // 停止按钮打到空处（用户报的"假状态、停不下来"）。
        info?.let { markStreaming(it.isStreaming, current?.file) }
        // 排队条归属校验：只有**归属对不上当前会话**时才清（这是跨会话那类脏数据）。
        // 注意不要用 pendingCount==0 去清：pi 的计数口径与 queue_update 不完全一致，
        // 以计数去清会把正在排队的 steer 误清（那样用户就取消不到了）。
        if (queueFile != null && current?.file != null && queueFile != current?.file) clearQueueState()
        syncWorking()
        // pi 侧标题变了（/auto-title 等）：立刻回填列表，回首页不再"旧标题"
        info?.sessionName?.let { nm -> patchSessionRow(current?.file, name = nm) }
    }

    /**
     * 闪退/重进会话后**立刻对齐实时状态**（用户口径）。
     *
     * 历史（get_messages）里可能已经有**进行中的 assistant 部分输出**；
     * 但没有结果的工具行在历史里无法区分"被打断"和"正在跑" —— 所以用 pi 的 `isStreaming` 判断：
     * 还在跑 ⇒ 把最后一条 assistant 标成流式、把没有结果的工具行标成"正在跑"，
     * 这样重进会话就能直接看到 thinking / 工具输出的当前状态，而不是等到下一次事件。
     */
    fun syncLiveState() {
        if (info?.isStreaming != true) return
        markStreaming(true, current?.file)
        val lastAssistant = items.indexOfLast { !it.isTool && !it.isNotice && !it.isUser }
        items = items.mapIndexed { index, item ->
            when {
                item.isTool && item.toolResult.isBlank() && !item.toolIsError -> item.copy(toolRunning = true)
                index == lastAssistant -> item.copy(streaming = true)
                else -> item
            }
        }
    }

    /**
     * **列数变了就重拉历史**（换字号 / 转屏 / 折叠屏展开）。
     *
     * 为什么必须做：工具卡的内容是**桥按 app 报的列数、用 pi 自己的渲染器折好行**发过来的。
     * 列数变了但历史不重拉 ⇒ 卡片里还是旧列数的行，app 再按新宽度软折一次 ⇒ 双层折行、
     * 断在词中间（用户 2026-10-21 报的「内容显示 broken」就是这个）。
     */
    fun reloadForCols() = io {
        if (current == null || termCols <= 0) return@io
        runCatching { items = api.messages(cols = termCols) }
        current?.file?.let { f -> itemsCache[f] = items }
    }

    /**
     * 按会话文件打开（通知点进来用）。
     *
     * 列表里没有就**先拉一次**（后台期间可能新建了会话）；还找不到就什么都不做，
     * 不能把用户带进一个空白的会话页。
     */
    fun openSessionByFile(file: String) = ioSuspend {
        if (file.isBlank()) return@ioSuspend
        android.util.Log.i("PiPocketDebug", "openSessionByFile 请求 file=$file 已知会话=${sessions.size}")
        var hit = sessions.firstOrNull { it.file == file }
        if (hit == null) {
            val r = runCatching { api.sessions() }
            r.onSuccess { sessions = it }
            android.util.Log.i(
                "PiPocketDebug",
                "openSessionByFile 重新拉取：ok=${r.isSuccess} 数量=${sessions.size} err=${r.exceptionOrNull()?.message}",
            )
            hit = sessions.firstOrNull { it.file == file }
        }
        android.util.Log.i("PiPocketDebug", "openSessionByFile 命中=${hit?.name}")
        if (hit != null) openSession(hit)
    }

    fun openSession(session: SessionInfo) = ioSuspend {
        // 用户口径：**退出会话不停止会话**。所以重新进入同一个会话时不要再 switch_session
        //（那会让 pi 切一次上下文，可能打断正在跑的一轮）；只把消息重新拉一遍即可。
        val prev = current?.file
        val same = prev == session.file && lastOpenedFile == session.file
        if (prev != session.file) {
            // ⓪ 把上一个会话的当下历史存成快照（下次点回来瞬间就能看到）
            if (prev != null && items.isNotEmpty()) itemsCache[prev] = items
            // ① 屏幕先只画**这个会话自己的东西**：有快照就立刻点亮（切回旧会话几乎瞬时），
            //    没有就清空 —— 绝不把上一个会话的正文留在屏幕上（那就是「内容串会话」）
            current = session
            items = itemsCache[session.file] ?: emptyList()
            // ② 清掉属于上一个会话的临时状态：排队条 / 工作状态 / 状态行 / 上一个会话的 info
            clearQueueState()
            queueFile = session.file
            markStreaming(false, null)
            workStartedAt = null
            info = null
            error = null
            // 压缩卡**不再在这里清**：它带归属（compactionCardFile），
            // 归属对不上就不上屏 ⇒ 回到原来那个会话还能看到（“切换/重载都在”的原始口径）
        } else {
            current = session
        }
        if (!same) {
            api.openSession(session.file)
            lastOpenedFile = session.file
        }
        // ③ 历史 + 权威状态**并行**拉（以前串行 4 个来回，大会话切一次就是“转很久”）
        val file = session.file
        val token = ++loadToken
        val (msgs, infoData) = coroutineScope {
            val m = async { runCatching { api.messages(cols = termCols) }.getOrNull() }
            val i = async { runCatching { api.info() }.getOrNull() }
            m.await() to i.await()
        }
        // ④ 中途用户又切走了：这次的结果直接丢弃（不盖到新会话上）
        if (token != loadToken || current?.file != file) return@ioSuspend
        msgs?.let {
            items = it
            itemsCache[file] = it
        }
        infoData?.let { applyInfo(it, expectFile = file) }
        if (slashCommands.isEmpty()) runCatching { slashCommands = api.slashCommands() }
        syncLiveState()   // 真的还在跑 → 立刻把 thinking/工具标成进行中
    }

    /** 最近一次真正 switch 过的会话文件（判断"回到同一个会话"时不要重复 switch）。 */
    private var lastOpenedFile: String? = null

    /**
     * 每个会话的历史快照（最多 6 个，LRU）。
     *
     * 作用：切回刚看过的会话时**先用快照把界面点亮**，再去拉最新的；
     * 同时保证「切过去的那一刻屏幕上不会还是上一个会话的正文」。
     */
    private val itemsCache = object : LinkedHashMap<String, List<ChatItem>>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<ChatItem>>?): Boolean = size > 6
    }

    /** 会话加载批次号：切会话就 +1，晚到的旧结果一律丢弃。 */
    private var loadToken = 0

    /**
     * SSE（重）连后，把**界面上的这个会话**重新交给桥。
     *
     * 为什么需要：会话归属由桥侧（activeFile）决定，桥一重启就忘了；app 这边 UI 还在那个会话上，
     * 直接发消息就会被桥当成“没开过会话” ⇒ pi 新建一个空会话、消息落进去。
     * 幂等：桥那边若已绑在这个会话上会返回 skipped，不会真的再 switch（不打断正在跑的一轮）。
     * 注意：即使 app 自己记得“已经 open 过”也要再发一次 —— 桥可能就是在重启后重新连上来的，
     * 它内存里的 activeFile 已经没了。
     */
    /**
     * （重）连上桥之后，把界面重新对齐到真实状态。
     *
     * 三件事，缺一个都会留下"看着正常、其实空的"界面：
     *  ① 把当前会话交回给桥（桥重启后它的 activeFile 没了，不发这一步后续消息会落进新会话）；
     *  ② 重拉 info（模型/等级/上下文/统计都在这里，窗口一空界面就停在「读取状态中…」）；
     *  ③ 重拉历史（断线期间 pi 可能又跑了几轮，不重拉就少了那几条）。
     *
     * 全部在 **IO** 上做：以前这段直接在主线程调 `api.messages()`（阻塞 OkHttp），
     * 桥刚重启时慢一点就把界面按死，而且异常会把后面的步骤一起吞掉。
     *
     * 幂等：桥已经绑在同一个会话上时 `openSession` 会返回 skipped，不会打断正在跑的一轮。
     */
    private suspend fun rehydrateAfterReconnect() = withContext(Dispatchers.IO) {
        val file = current?.file ?: return@withContext
        val client = api
        val reopened = runCatching { client.openSession(file) }
        if (reopened.isFailure) {
            withContext(Dispatchers.Main) {
                if (api === client && current?.file == file) {
                    addNotice("重连后没能恢复当前会话", reopened.exceptionOrNull()?.message ?: "未知错误", "warn")
                }
            }
            return@withContext
        }

        // ② info：失败也要继续往下走（桥可能还没起好 worker）
        val infoData = runCatching { client.info() }.getOrNull()

        // ③ 历史：只认**本次开始时**的那个会话，中途用户切走了就丢弃
        val msgs = runCatching { client.messages(cols = termCols) }.getOrNull()

        withContext(Dispatchers.Main) {
            if (api !== client || current?.file != file) return@withContext
            lastOpenedFile = file
            infoData?.let { applyInfo(it, expectFile = file) }
            msgs?.let {
                items = it
                itemsCache[file] = it
            }
            syncLiveState()
        }
        Log.i(
            "PiPocket",
            "RESULT rehydrate file=${file.substringAfterLast('/')} info=${infoData != null} messages=${msgs?.size ?: -1}",
        )
    }

    private suspend fun resendActiveSession() = withContext(Dispatchers.IO) {
        val file = current?.file ?: return@withContext
        runCatching { api.openSession(file) }
            .onSuccess { lastOpenedFile = file }
            .onFailure { addNotice("重连后没能把会话交回给桥", it.message ?: "未知错误", "warn") }
    }

    /**
     * 这条会话是不是正在跑（列表里给个"工作中"标记）。
     *
     * 以**桥上 worker 的真实 streaming**为准（`SessionInfo.running`），
     * 事件层的 runningFiles 只作补充 —— 之前 agent_settled 丢掉时会一直卡在"工作中"。
     */
    fun isSessionWorking(file: String?): Boolean {
        if (file == null) return false
        sessions.firstOrNull { it.file == file }?.running?.let { if (it) return true }
        if (file in runningFiles) return true
        return isWorking && (current?.file == file || lastOpenedFile == file)
    }

    /**
     * 重载当前（旧）会话：桥重启 pi 子进程并重开这个会话文件。
     *
     * 场景：换过 skills / app 注入配置 / 扩展之后，旧会话里跑的还是老进程；
     * 这条 = 终端里的「退出 pi 再 pi --continue」，会话内容不丢。
     */
    fun reloadSession(file: String?) = io(busyFlag = false) {
        setStatus("正在重载会话（重启 pi 进程）…", file ?: current?.file)
        runCatching {
            api.reload(file)
            setStatus("会话已重载", file ?: current?.file, clearAfterMs = 5_000)
            loadCommands(force = true)          // 扩展/技能可能变了，命令清单重新拉
            refreshInfo()
            runCatching { items = api.messages() }
        }.onFailure {
            setStatus("重载失败：" + (it.message ?: "未知"), file ?: current?.file)
            addNotice("重载会话失败", it.message ?: "", "error")
        }
    }

    fun newSession(cwd: String? = null) = io {
        current?.file?.let { f -> if (items.isNotEmpty()) itemsCache[f] = items }
        val data = api.newSession(cwd)
        val file = data.optJSONObject("state")?.optString("sessionFile").orEmpty()
        current = SessionInfo(file = file, id = "", cwd = cwd, name = null, updatedAt = "", bytes = 0)
        items = emptyList()
        clearQueueState()
        queueFile = file
        markStreaming(false, null)
        workStartedAt = null
        info = null
        error = null
        loadToken++
        refreshInfo()
        refreshSessions()
    }

    // ── 会话树 / 分叉（pi 的 get_tree · fork · clone）─────────────────────────────

    /** 会话树快照（打开树面板时拉一次）。 */
    internal var treeSnapshot by mutableStateOf<SessionTreeSnapshot?>(null)
        // debug 探针要能清空重拉（不点屏幕也能测）；业务代码只读
        internal set
    /** 默认过滤档的行（拉树时在 IO 线程建好，首帧直接用）。 */
    internal var treeRowsDefault by mutableStateOf<List<id.harness.pipocket.ui.TreeRow>>(emptyList())
        private set
    internal var treeLoading by mutableStateOf(false)
        private set
    internal var treeError by mutableStateOf<String?>(null)
        private set

    /**
     * 拉一次树并**返回结果**（给 debug 探针/基准用；UI 走 [loadTree]）。
     *
     * 为什么单独开一个：`loadTree()` 只是 `launch` 一个协程（非阻塞），探针要的是
     * 「拿到了什么 + 花了多久」，轮询 Compose 状态既绕又容易读到旧值。
     */
    internal suspend fun fetchTreeNow(): Pair<SessionTreeSnapshot?, String?> = withContext(Dispatchers.IO) {
        runCatching { api.tree() }.fold(
            onSuccess = { it to null },
            onFailure = { null to (it.message ?: "拉会话树失败") },
        )
    }

    fun loadTree() = io(busyFlag = false) {
        treeLoading = true
        treeError = null
        runCatching { api.tree() }
            .onSuccess { snap ->
                // **在这里就把默认行建好**（IO 线程）：
                //  ① 首帧不用等（构建放主线程会让 2000+ 条的会话明显卡）；
                //  ② 顺带把 ART 的类/JIT 预热掉（实测同一份数据：首次 ~7s，预热后 1ms）。
                treeRowsDefault = runCatching {
                    id.harness.pipocket.ui.buildTreeRows(snap.nodes, snap.leafId, "default")
                }.getOrDefault(emptyList())
                treeSnapshot = snap
                if (snap.nodes.isEmpty()) treeError = "这个会话还没有条目"
            }
            .onFailure {
                treeError = it.message ?: "拉会话树失败"
                treeSnapshot = null
            }
        treeLoading = false
    }

    /**
     * 从某个用户消息处分叉（pi 的 `fork`，默认 `position: "before"`）。
     *
     * ⚠️ 关键：pi 分叉会**开一个新会话文件**，桥已经把 worker 重绑到新文件上（`/v1/fork`）。
     * app 这边必须跟着把当前会话切过去 —— 否则界面还挂在旧会话上，下一条消息会落回分叉前的会话。
     *
     * 分叉后把那条用户消息的文本填回草稿（终端也是这么做的：`editor.setText(result.selectedText)`），
     * 方便直接改了再发。
     */
    fun forkAt(entryId: String) = io {
        setStatus("正在从这条消息分叉…", current?.file)
        runCatching { api.fork(entryId) }
            .onSuccess { r ->
                if (r.cancelled) {
                    setStatus("已取消分叉", current?.file, clearAfterMs = 3_000)
                    return@onSuccess
                }
                val newFile = r.file
                if (newFile == null) {
                    setStatus("分叉后没拿到新会话文件", current?.file)
                    return@onSuccess
                }
                adoptForkedSession(newFile, r)
            }
            .onFailure {
                setStatus("分叉失败：" + (it.message ?: "未知"), current?.file)
                addNotice("分叉失败", it.message ?: "", "error")
            }
    }

    /** 复制当前会话（pi 的 `clone` = 在 leaf 处 fork）。 */
    fun cloneSession() = io {
        setStatus("正在复制当前会话…", current?.file)
        runCatching { api.cloneSession() }
            .onSuccess { r ->
                if (r.cancelled) {
                    setStatus("已取消复制", current?.file, clearAfterMs = 3_000)
                    return@onSuccess
                }
                val newFile = r.file
                if (newFile == null) {
                    setStatus("复制后没拿到新会话文件", current?.file)
                    return@onSuccess
                }
                adoptForkedSession(newFile, r)
            }
            .onFailure {
                setStatus("复制失败：" + (it.message ?: "未知"), current?.file)
                addNotice("复制会话失败", it.message ?: "", "error")
            }
    }

    /**
     * fork / clone 之后把 app 的当前会话换成新文件。
     *
     * 不复用 [openSession]：那个会先发 `/v1/sessions/open`（switch_session）—— 而分叉出来的会话
     * **已经就在 pi 里了**（同一个 worker 刚 rebind 到它上面），再 switch 一次是多余的、还可能打断。
     */
    private fun adoptForkedSession(newFile: String, r: ForkResult) {
        val old = current
        // 旧会话的历史留在快照里（切回去还是瞬时的）；新会话从零开始画
        old?.file?.let { f -> if (items.isNotEmpty()) itemsCache[f] = items }
        current = SessionInfo(
            file = newFile,
            id = newFile.substringAfterLast('/').removeSuffix(".jsonl").ifBlank { "" },
            cwd = old?.cwd,
            name = null,
            updatedAt = java.time.Instant.now().toString(),
            bytes = 0,
        )
        items = emptyList()
        clearQueueState()
        queueFile = newFile
        markStreaming(false, null)
        workStartedAt = null
        info = null
        error = null
        loadToken++
        lastOpenedFile = newFile   // 已经绑好了，不用再 switch
        // 终端也是把分叉点那条用户消息放回输入框（`editor.setText(result.selectedText)`）
        if (r.kind == "fork" && r.text.isNotBlank()) saveDraft(newFile, r.text)
        setStatus(
            if (r.kind == "clone") "已复制成新会话" else "已分叉到新会话",
            newFile,
            clearAfterMs = 6_000,
        )
        runCatching { refreshInfo() }
        runCatching { items = api.messages(cols = termCols) }
        treeSnapshot = null        // 树变了（新会话从分叉点开始）
        refreshSessions()
    }

    // ── 行为开关（pi 的 /settings：自动压缩 / 自动重试 / steering·follow-up 模式）──────

    /** 当前会话的行为开关快照（打开面板时拉）。 */
    internal var behavior by mutableStateOf<id.harness.pipocket.ui.BehaviorState?>(null)
        private set
    internal var behaviorBusy by mutableStateOf(false)
        private set

    fun loadBehavior() = io(busyFlag = false) {
        runCatching { api.behavior() }
            .onSuccess { behavior = it }
            .onFailure { setStatus("读行为开关失败：" + (it.message ?: "未知"), current?.file, clearAfterMs = 6_000) }
    }

    /**
     * 改一个行为开关。
     *
     * 乐观更新：“开关拨过去就显示拨过去”（不闪），失败再回滚并提示。
     */
    internal fun setBehavior(
        autoCompaction: Boolean? = null,
        autoRetry: Boolean? = null,
        steeringMode: String? = null,
        followUpMode: String? = null,
    ) {
        if (behaviorBusy) return
        val before = behavior
        behavior = before?.copy(
            autoCompaction = autoCompaction ?: before.autoCompaction,
            autoRetry = autoRetry ?: before.autoRetry,
            steeringMode = steeringMode ?: before.steeringMode,
            followUpMode = followUpMode ?: before.followUpMode,
        )
        io(busyFlag = false) {
            behaviorBusy = true
            runCatching { api.setBehavior(autoCompaction, autoRetry, steeringMode, followUpMode) }
                .onSuccess { behavior = it }
                .onFailure {
                    behavior = before            // 回滚（pi 没接受就不能让界面说改了）
                    setStatus("改行为开关失败：" + (it.message ?: "未知"), current?.file, clearAfterMs = 8_000)
                    addNotice("改行为开关失败", it.message ?: "", "error")
                }
            behaviorBusy = false
        }
    }

    /**
     * 中断正在倒计时的自动重试（pi 的 `abort_retry`；终端是 Esc）。
     */
    fun abortRetry() = io(busyFlag = false) {
        runCatching { api.abortRetry() }
            .onSuccess {
                retrying = null
                setStatus("已中断自动重试", current?.file, clearAfterMs = 5_000)
            }
            .onFailure { setStatus("中断重试失败：" + (it.message ?: "未知"), current?.file, clearAfterMs = 6_000) }
    }

    // ── 交付 / 导出（pi 的 /export · /copy）─────────────────────────────────

    /** 导出结果（非空时弹出「已导出」对话框：能复制路径 / 直接打开看）。 */
    internal var exportResult by mutableStateOf<ExportResult?>(null)
        private set

    /**
     * 导出当前会话为自包含 HTML（终端 `/export`）。
     *
     * 默认导到 `~/projects/exports/`（见 `exportPathFor`）：pi 自己会写到 worker 的 cwd，
     * 手机上不好找；这里给个固定位置，文件页里能翻到。
     */
    fun exportSession() = io(busyFlag = false) {
        setStatus("正在导出会话…", current?.file)
        val home = appContext?.applicationInfo?.dataDir ?: "/data/data/" + (appContext?.packageName ?: "")
        // Termux 的 home 与 app 的 dataDir 不是一回事：用桥能访问到的路径（~/projects/exports）
        val target = id.harness.pipocket.ui.exportPathFor(
            sessionFile = current?.file ?: "",
            sessionName = current?.name,
            home = "~",
        )
        runCatching { api.exportHtml(target) }
            .onSuccess { r ->
                if (r.path == null) {
                    setStatus("导出失败：桥没返回文件路径", current?.file)
                    return@onSuccess
                }
                exportResult = r
                setStatus("已导出：" + id.harness.pipocket.ui.exportSummary(r.path, r.bytes, home), current?.file, clearAfterMs = 8_000)
            }
            .onFailure {
                setStatus("导出失败：" + (it.message ?: "未知"), current?.file)
                addNotice("导出会话失败", it.message ?: "", "error")
            }
    }

    internal fun dismissExport() { exportResult = null }

    /**
     * 复制最后一条回答（终端 `/copy`）。
     *
     * 剪贴板要走 ClipboardManager（appContext），且复制后必须给可见反馈——
     * 手机上“静默复制”等于没发生。
     */
    fun copyLastAnswer() = io(busyFlag = false) {
        runCatching { api.lastAssistantText() }
            .onSuccess { text ->
                val outcome = id.harness.pipocket.ui.copyLastAnswerOutcome(text)
                if (!outcome.ok) {
                    setStatus(outcome.message, current?.file, clearAfterMs = 4_000)
                    return@onSuccess
                }
                val ok = copyToClipboard(text.trim(), "pi 回答")
                setStatus(
                    if (ok) outcome.message else "复制失败（剪贴板不可用）",
                    current?.file,
                    clearAfterMs = 6_000,
                )
            }
            .onFailure { setStatus("取最后一条回答失败：" + (it.message ?: "未知"), current?.file, clearAfterMs = 6_000) }
    }

    /** 写剪贴板（有 appContext 才写得动；返回是否成功）。 */
    internal fun copyToClipboard(text: String, label: String): Boolean {
        val ctx = appContext ?: return false
        return runCatching {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText(label, text))
            true
        }.getOrDefault(false)
    }

    // ── 快切（pi 的 cycle_thinking_level · cycle_model）──────────────────────

    /**
     * 切到下一个思考等级（终端 `app.thinking.cycle`）。
     *
     * pi 在没有可选等级时返回 null ⇒ 这里给一句人话，不要静默失败。
     */
    fun cycleThinking() = io(busyFlag = false) {
        runCatching { api.cycleThinking() }
            .onSuccess { level ->
                if (level == null) {
                    setStatus("当前模型没有可切换的思考等级", current?.file, clearAfterMs = 5_000)
                    return@onSuccess
                }
                info = info?.copy(thinking = level)
                setStatus("思考等级 → $level", current?.file, clearAfterMs = 4_000)
            }
            .onFailure { setStatus("切思考等级失败：" + (it.message ?: "未知"), current?.file, clearAfterMs = 6_000) }
    }

    /** 切到下一个模型（终端 `app.model.cycleForward`）。 */
    fun cycleModel(backward: Boolean = false) = io(busyFlag = false) {
        runCatching { api.cycleModel(backward) }
            .onSuccess {
                refreshInfo()
                val label = info?.modelDisplay?.takeIf { it.isNotBlank() } ?: info?.model
                setStatus("模型 → " + (label ?: "已切换"), current?.file, clearAfterMs = 5_000)
            }
            .onFailure { setStatus("切模型失败：" + (it.message ?: "未知"), current?.file, clearAfterMs = 6_000) }
    }

    /** 正在自动重试的信息（由 `auto_retry_start` 事件维护；顶部横幅 + 中断按钮用）。 */
    internal var retrying by mutableStateOf<RetryInfo?>(null)
        private set

    internal data class RetryInfo(val attempt: Int, val maxAttempts: Int, val delayMs: Long, val atMs: Long)

    /**
     * 输入框草稿（用户口径：**app 闪退也不丢**）。     *
     * 每敲一次就落到 prefs（`draft:<会话文件>`），重启/闪退后进同一会话自动回填。
     * 只存最近 20 条会话的草稿，避免 prefs 无限长；发送成功后清空。
     */
    private val drafts = mutableMapOf<String, String>()

    /**
     * **没发出去的那条**（用户 2026-10-23：「发送失败的消息要能够保留恢复编辑」）。
     *
     * 与上面的输入框草稿是两件事：草稿是"还没按发送"，这里是"按了发送但没确认成功"
     * （桥返回错误、连接断了、或者 app 在等响应时被系统杀掉）。
     * 文本在**发之前**就落盘（[saveOutbox]），`prompt` 成功回来才清掉（[clearOutbox]）；
     * 失败时把 [PendingDraft.reason] 填上，交给对话页的提示条「恢复编辑」。
     */
    var pendingDraft by mutableStateOf<PendingDraft?>(null)
        private set

    /** 失败时带过的图（只在内存里：图可能有几 MB，不往 prefs 塞；提示条会说明要重贴）。 */
    private var pendingDraftImages: List<Attachment> = emptyList()

    /** 发之前落盘：进程被杀也能在下次启动找回这份文本。 */
    fun saveOutbox(draft: PendingDraft) {
        val store = prefs ?: return
        if (draft.text.isBlank()) return
        store.edit().putString("outbox", Outbox.encode(draft)).apply()
    }

    fun clearOutbox() {
        pendingDraft = null
        pendingDraftImages = emptyList()
        prefs?.edit()?.remove("outbox")?.apply()
    }

    /** 失败时调用：记下原因 + 图（图只在内存），并保持落盘（下次启动还能找回来）。 */
    fun markPendingDraft(text: String, images: List<Attachment>, reason: String?, file: String? = current?.file) {
        val draft = PendingDraft(
            text = text,
            file = file,
            reason = reason,
            ts = System.currentTimeMillis(),
            hadImages = images.size,
        )
        pendingDraft = draft
        pendingDraftImages = images
        saveOutbox(draft)
    }

    /** 「恢复编辑」：交出文本和当时带的图，并清掉这条（用户已经拿回去了）。 */
    fun takePendingDraft(): PendingDraft? {
        val d = pendingDraft ?: return null
        clearOutbox()
        return d
    }

    fun takePendingDraftImages(): List<Attachment> {
        val imgs = pendingDraftImages
        pendingDraftImages = emptyList()
        return imgs
    }

    fun discardPendingDraft() = clearOutbox()

    fun draftFor(file: String?): String {
        if (file.isNullOrBlank()) return ""
        drafts[file]?.let { return it }
        val saved = prefs?.getString("draft:$file", null).orEmpty()
        drafts[file] = saved
        return saved
    }

    fun saveDraft(file: String?, text: String) {
        if (file.isNullOrBlank()) return
        if (drafts[file] == text) return
        drafts[file] = text
        val store = prefs ?: return
        val edit = store.edit()
        if (text.isBlank()) edit.remove("draft:$file") else edit.putString("draft:$file", text)
        // 顺带控制条数：只留最近 20 个草稿
        val keys = store.all.keys.filter { it.startsWith("draft:") }
        if (keys.size > 20) keys.take(keys.size - 20).forEach { edit.remove(it) }
        edit.apply()
    }

    fun closeSession() {
        // 回首页前先把会话内的一手状态写回列表（标题 / 是否还在跑），再到后台静默对账；
        // 否则要等用户手点刷新才看到新标题和新状态。
        patchSessionRow(current?.file, name = info?.sessionName, running = isWorking)
        // 把当下这份历史存成快照：下次进这个会话直接点亮（不用等桥渲染整段历史）
        current?.file?.let { f -> if (items.isNotEmpty()) itemsCache[f] = items }
        // 离开会话：排队条与事件层工作状态都是**这个会话**的，不能带到首页/下一个会话
        clearQueueState()
        markStreaming(false, null)
        workStartedAt = null
        info = null
        current = null
        items = emptyList()
        setStatus(null)
        loadToken++
        refreshSessions(silent = true)
    }

    fun send(
        text: String,
        images: List<Attachment>,
        steer: Boolean = false,
        streamingBehavior: String? = null,
    ) {
        val client = api
        val file = current?.file
        io {
        if (text.isBlank() && images.isEmpty()) return@io
        if (client !== api || current?.file != file) {
            markPendingDraft(text, images, "会话已切换，请恢复编辑后重新发送", file)
            return@io
        }
        // 内置命令（/model /reload /compact …）在 pi 里是 **TUI 侧**行为，走 prompt 只会变成普通文本。
        // 能落地成 RPC 的这里直接执行掉；TUI 专属的给出明确提示（用户 2026-10-21 报的"不生效"）。
        if (images.isEmpty() && text.trim().startsWith("/") && runBuiltinSlash(text)) return@io
        // 本机发出的图先存一份：SSE 的 user message_start 不一定把原图带回来（消息里的图要能看到）
        pendingImages = images.map { ImageRef(it.mimeType, it.base64) }
        // 发之前先落一份：这一行之后无论桥报错、连接断、还是进程被杀，这段话都找得回来。
        // 成功（拿到 prompt 的响应）才清 —— 见下面的 clearOutbox()。
        runCatching { saveOutbox(PendingDraft(text, file, ts = System.currentTimeMillis(), hadImages = images.size)) }
        try {
            val data = client.prompt(text, images, steer, streamingBehavior)
            runCatching { clearOutbox() }
            if (client !== api || current?.file != file) return@io
            markStreaming(true, file)
            // pi 说"被谁吃了"：handled = 扩展命令/输入处理器已处理（不会起一轮），
            // queued = 排队了，started = 开始跑。命令没执行时必须能看见原因。
            val disposition = data.optString("disposition")
            if (text.trim().startsWith("/") && disposition == "started") {
                addNotice(
                    "「" + text.trim().take(30) + "」被当成普通消息发出去了",
                    "pi 里没有这个扩展命令/模板（已作为提示词发给了模型）。用「命令与技能」面板看真实清单。",
                    "warn",
                )
            }
        } catch (e: Throwable) {
            pendingImages = emptyList()
            // 保留 + 可恢复编辑：文本和图都留着，对话页会出现「没发出去」提示条。
            runCatching { markPendingDraft(text, images, e.message ?: "未知错误", file) }
            addNotice(
                "发送失败，内容已保留",
                "输入框上方多了一条「没发出去」，点「恢复编辑」改完可以重发。",
                "error",
            )
            throw e
        }
        }
    }

    /**
     * 取消或"取回编辑"排队里的某一条。
     *
     * pi 只提供整体 `clear_queue`（返回全部文本），没有"删第 N 条"：
     * 所以做法是 清空 → 把**其余**的按原语义重新排队；取回编辑时把该条文本交给调用方放回输入框。
     */
    fun takeQueued(kind: String, index: Int, forEdit: Boolean, restore: (String) -> Unit) = io {
        if (kind != "steer" && kind != "followUp" || index < 0) return@io
        val file = current?.file ?: return@io
        // 归属对不上就不动：那份文本属于别的会话（clear_queue 打在别的会话上会把队列弄乱）
        if (queueFile != null && current?.file != null && queueFile != current?.file) {
            clearQueueState()
            setStatus("排队条已跟随会话切换", current?.file)
            return@io
        }
        val client = api
        val removed = client.clearQueue()
        val steering = (0 until (removed.optJSONArray("steering")?.length() ?: 0))
            .map { removed.optJSONArray("steering")!!.optString(it) }
        val follow = (0 until (removed.optJSONArray("followUp")?.length() ?: 0))
            .map { removed.optJSONArray("followUp")!!.optString(it) }
        val plan = planQueueRemoval(kind, index, steering, follow)
        val failed = mutableListOf<String>()
        val sentSteer = mutableListOf<String>()
        val sentFollow = mutableListOf<String>()
        plan.steering.forEach { text ->
            runCatching { client.prompt(text, emptyList(), steer = true) }
                .onSuccess { sentSteer += text }.onFailure { failed += text }
        }
        plan.followUp.forEach { text ->
            runCatching { client.prompt(text, emptyList(), streamingBehavior = "followUp") }
                .onSuccess { sentFollow += text }.onFailure { failed += text }
        }
        // Preserve every unacknowledged message instead of showing it as queued and losing it.
        if (failed.isNotEmpty()) {
            preserveQueueRecovery(failed.joinToString("\n\n"), file, "恢复排队未获确认，请检查后重发")
        }
        if (client === api && current?.file == file) {
            queuedSteering = sentSteer
            queuedFollowUp = sentFollow
            if (forEdit) plan.selected?.let(restore)
            setStatus(when {
                failed.isNotEmpty() -> "部分排队未能恢复，内容已保留；重新打开原会话可恢复编辑"
                plan.selected == null -> "队列已变化，已恢复全部排队，请重试"
                forEdit -> "已取回到输入框"
                else -> "已取消一条排队"
            }, file, clearAfterMs = 5_000)
        } else if (forEdit && plan.selected != null) {
            preserveQueueRecovery(plan.selected, file, "会话已切换，取回的排队内容已保留")
        }
    }

    /** Keep an unrelated outbox intact; its text must never be merged into this session. */
    @Synchronized private fun preserveQueueRecovery(text: String, file: String, reason: String) {
        val existing = pendingDraft
        val plan = planQueueRecovery(existing, file, text, draftFor(file))
        if (plan.composerText != null) {
            // The composer already persists one draft per session. Reopening this session restores it.
            saveDraft(file, plan.composerText)
        } else {
            markPendingDraft(
                checkNotNull(plan.outboxText),
                if (existing == null) emptyList() else pendingDraftImages,
                reason,
                file,
            )
        }
    }

    fun abort() = io {
        abortedByUser = true
        api.abort()
        // 停止后立刻对齐状态：不再显示"工作中"，排队/停止按钮回到发送
        markStreaming(false, null)
        syncWorking()
        runCatching { refreshInfo() }
    }

    fun loadModels() = io(busyFlag = false) { models = api.models() }

    /** 命令清单（扩展命令 / 技能 / 模板）的拉取时间：切会话时不必每次都重拉（两个来回很贵）。 */
    private var commandsLoadedAt = 0L

    fun loadCommands(force: Boolean = false) = io(busyFlag = false) {
        if (!force && System.currentTimeMillis() - commandsLoadedAt < 120_000 && commands.isNotEmpty()) return@io
        commands = api.commands()
        runCatching { api.slashCommands() }.onSuccess { slashCommands = it }
        commandsLoadedAt = System.currentTimeMillis()
    }

    /**
     * 执行 pi 的内置命令（**终端语义，但落到 RPC**）。
     * 返回 true = 已处理（不要再当成提示词发给模型）。
     *
     * 为什么必须在这里做：`/model`、`/reload`、`/compact` 这些是 pi **TUI 侧**的命令，
     * 通过 RPC `prompt` 发过去只会被当成普通文本（用户报的"具体值不生效"就是这个）。
     */
    fun runBuiltinSlash(raw: String): Boolean {
        val text = raw.trim()
        if (!text.startsWith("/")) return false
        val body = text.removePrefix("/")
        val name = body.substringBefore(' ').trim()
        val arg = body.substringAfter(' ', "").trim()
        val known = slashCommands.firstOrNull { it.name == "/" + name }
        // 能落地成 RPC 的内置命令：**不依赖** /v1/slash 是否已经拉过（以前要先打开「命令与技能」面板才生效，
        // 用户直接敲 /model 就静默失败 —— 用户报的"具体值不生效"）。
        val rpcNames = setOf("model", "thinking", "compact", "reload", "new", "name", "resume", "quit")
        if (name !in rpcNames) {
            // 扩展 / skill / 模板命令仍然走 prompt（pi 自己执行）
            if (commands.any { it.name == "/$name" }) return false
            if (known == null) return false
        }

        when (name) {
            "model" -> {
                // 清单没拉过就先拉（否则 /model <x> 永远"没找到"）
                if (models.isEmpty()) runCatching { models = api.models() }
                val q = arg.trim()
                val idQuery = if (q.contains('/')) q.substringAfterLast('/') else q
                val hit = models.firstOrNull { it.model.equals(idQuery, true) || it.label.equals(q, true) }
                    ?: models.firstOrNull {
                        idQuery.isNotBlank() && (it.model.contains(idQuery, true) || it.label.contains(q, true))
                    }
                if (hit == null) {
                    val hint = models.take(6).joinToString(" · ") { it.label }
                    addNotice(
                        if (q.isBlank()) "用法：/model <名字片段>" else "没有匹配「$q」的模型",
                        if (hint.isBlank()) "模型清单为空（桥没连上？）" else "可用的例如：$hint",
                        "warn",
                    )
                } else {
                    chooseModel(hit)
                }
                return true
            }
            "thinking" -> {
                val levels = listOf("off", "minimal", "low", "medium", "high", "xhigh", "max")
                val lv = levels.firstOrNull { it.equals(arg, true) } ?: levels.firstOrNull { it.startsWith(arg.lowercase()) }
                if (lv == null) addNotice("思考等级不对", "可选：${levels.joinToString(" / ")}", "warn")
                else setThinking(lv)
                return true
            }
            "compact" -> { compact(arg.takeIf { it.isNotBlank() }); return true }
            "reload" -> { reloadSession(current?.file); return true }
            "new" -> { newSession(current?.cwd); return true }
            "name" -> {
                if (arg.isBlank()) addNotice("用法", "/name <新名字>", "warn") else rename(arg)
                return true
            }
            "resume" -> {
                val hit = sessions.firstOrNull { it.id == arg } ?: sessions.firstOrNull { arg.isNotBlank() && it.id.startsWith(arg) }
                if (hit == null) addNotice("会话没找到", if (arg.isBlank()) "用法：/resume <会话 id 前几位>" else "没有匹配「$arg」的会话", "warn")
                else openSession(hit)
                return true
            }
            "quit" -> {
                runCatching { api.restartBridge() }
                setStatus("已让桥重启 pi 进程")
                return true
            }
        }
        // 其余内置命令（/export /share /login /trust …）是 TUI 专属：如实说清楚，别装作成功
        addNotice(
            "「/$name」是 pi 终端专属",
            known?.description?.ifBlank { "这条命令只在 pi 的交互终端里可用，app 里没有等价动作。" }
                ?: "这条命令只在 pi 的交互终端里可用，app 里没有等价动作。",
            "warn",
        )
        return true
    }

    /** app 会话注入面板：读当前选择（失败原因落到 appProfileError，面板会显示出来）。 */
    /** 强制重启桥：让桥自重启，等几秒后重新探测并连上。 */
    fun restartBridge() = io(busyFlag = false) {
        setStatus("正在重启桥…")
        runCatching { api.restartBridge() }
        Thread.sleep(6000)
        val hit = runCatching { PiLocator.probe(baseUrl, token) }.getOrNull()
        if (hit != null) {
            adopt(hit)
            setStatus("桥已重启并连上")
        } else {
            autoDetect(keepCurrentOnFailure = true, includeLan = false, adoptFirst = true)
            setStatus("已请求重启；若仍连不上，去 Termux 看 pi-pocket-start.log")
        }
    }

    private fun clearAppProfile() {
        appProfileRequests.next()
        appProfile = null
        appProfileError = null
        appProfileLoading = false
        appProfileSaving = false
    }

    private fun requestAppProfile(saving: Boolean = false, body: JSONObject? = null) {
        if (appProfileLoading || appProfileSaving) return
        val client = api
        val generation = appProfileRequests.next()
        appProfileLoading = !saving
        appProfileSaving = saving
        appProfileError = null
        scope.launch {
            val result = runCatching { withContext(Dispatchers.IO) {
                if (body == null) client.appProfile() else client.setAppProfile(body)
            } }
            if (!appProfileRequests.isCurrent(generation) || api !== client) return@launch
            appProfileLoading = false
            appProfileSaving = false
            result.onSuccess { appProfile = it }
                .onFailure { appProfileError = it.message ?: "请求失败" }
        }
    }

    fun loadAppProfile() = requestAppProfile()

    /**
     * 改一个开关（kind = skills | mcp | appendPrompt）。
     *
     * 桥那边落地成 selection.json，并**回收当前 pi 子进程** —— 所以改完下一次请求才是新配置生效。
     */
    fun setAppProfile(kind: String, id: String?, enabled: Boolean) {
        val body = JSONObject()
        if (kind == "appendPrompt") body.put("appendPrompt", enabled)
        else {
            // ⚠️ 没有 id 就改不了这一项：`JSONObject().put(null, …)` 会 NPE，
            // 而这行以前在 runCatching **外面** ⇒ 异常直接逃出 io{}（编译器的 platform-type 警告就是这个）。
            val key = id ?: run {
                appProfileError = "这项配置缺少标识，无法保存"
                return
            }
            body.put(kind, JSONObject().put(key, enabled))
        }
        requestAppProfile(saving = true, body = body)
    }

    /** 全部恢复默认（全开）。 */
    fun resetAppProfile() = requestAppProfile(saving = true, body = JSONObject().put("reset", true))

    // ── pi provider 配置（桥 /v1/providers；key 不进 app 的任何状态/日志）──────────

    /** 「SSH 管理」面板（用户 2026-10-21：连接设置里找不到 ⇒ 升成独立一层）。 */
    var sshOpen by mutableStateOf(false)
        private set

    private var sshSettingsReturn: SettingsNavigation? = null
    fun openSsh() {
        sshSettingsReturn = settingsNavigation.takeIf { it.page != null }
        filesOpen = false
        settingsOpen = false
        sshOpen = true
    }

    fun closeSsh() {
        sshOpen = false
        sshSettingsReturn?.let {
            settingsNavigation = it
            sshSettingsReturn = null
            return
        }
        // 从文件页进来的，关掉回文件页；否则回会话列表
        if (filesWantBack) filesOpen = true
    }

    /** 从文件页进 SSH 管理时记住"回去"，避免关掉后落在会话列表上。 */
    var filesWantBack by mutableStateOf(false)

    /** 「杂项」面板（独立一层，和 provider 一样：开它先关设置，关它回设置）。 */
    val miscOpen: Boolean get() = settingsNavigation.page == SettingsPage.Misc

    fun openMisc() {
        openSettingsPage(SettingsPage.Misc)
    }

    fun closeMisc() {
        backSettingsPage()
    }

    /** provider 面板是否打开。
     *  口径（HANDOFF §13：**不叠抽屉**）：打开它就把设置关掉；关掉它回设置。
     */
    val providersOpen: Boolean get() = settingsNavigation.page == SettingsPage.Providers
    var providers by mutableStateOf<List<ProviderInfo>>(emptyList())
        private set
    var providersLoading by mutableStateOf(false)
        private set
    var providersError by mutableStateOf<String?>(null)
        private set
    var providersMessage by mutableStateOf<String?>(null)
        private set

    fun openProviders() {
        openSettingsPage(SettingsPage.Providers)
        providersMessage = null
        loadProviders()
    }

    fun closeProviders() {
        providersMessage = null
        // 从设置页进来的，关掉就回设置（不然用户会落在会话列表上，感觉像“退了两级”）
        backSettingsPage()
    }

    fun clearProvidersMessage() { providersMessage = null }

    /** 读 provider 清单（失败原因落到 providersError，面板直接显示）。 */
    fun loadProviders() {
        if (providersLoading) return
        providersLoading = true
        providersError = null
        scope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { api.providers() } }
            providersLoading = false
            result.onSuccess { providers = it }
                .onFailure { providersError = it.message ?: "读取失败" }
        }
    }

    /**
     * upsert 一个 provider。
     *
     * `apiKey` 为空 = **只改配置不改 key**（编辑已有 provider 时表单留空的语义，桥侧也是这样）。
     * 成功后会重读清单并刷新模型列表（新 provider 的模型要能直接出现在模型面板里）。
     * `onResult` 给表单用：`true` 才关表单，`false` 让用户带着报错改。
     */
    fun saveProvider(
        providerName: String,
        providerBaseUrl: String,
        apiKind: String,
        modelText: String,
        apiKey: String,
        onResult: (Boolean) -> Unit = {},
    ) {
        providersMessage = null
        providersError = null
        val ids = ProviderForm.parseModels(modelText)
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    api.saveProvider(providerName.trim(), providerBaseUrl.trim(), apiKind.trim(), ids, apiKey)
                }
            }
            result.onSuccess {
                providersMessage = "已保存 " + providerName.trim() +
                    (if (apiKey.isBlank()) "（沿用原 key）" else "（key 已写入）")
                onResult(true)
                loadProviders()
                runCatching { loadModels() }
            }.onFailure {
                providersError = it.message ?: "保存失败"
                onResult(false)
            }
        }
    }

    /** 删一个 provider（桥会从 models.json 与 auth.json 同时删；调用方要先二次确认）。 */
    fun removeProvider(name: String, onResult: (Boolean) -> Unit = {}) {
        providersMessage = null
        providersError = null
        scope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { api.removeProvider(name) } }
            result.onSuccess {
                providersMessage = "已删除 " + name
                onResult(true)
                loadProviders()
                runCatching { loadModels() }
            }.onFailure {
                providersError = it.message ?: "删除失败"
                onResult(false)
            }
        }
    }

    // ── 从零部署（本机 Termux 跑 termux/bootstrap.sh；实现见 TermuxBootstrap）──────
    var bootstrapRunning by mutableStateOf(false)
        private set
    /** 已经等了多少秒（面板上直接显示，不转圈骗人）。 */
    var bootstrapElapsed by mutableStateOf(0)
        private set
    var bootstrapOk by mutableStateOf(false)
        private set
    var bootstrapMessage by mutableStateOf<String?>(null)
        private set
    /** bootstrap 的日志尾部（最多 20 行；失败时就是靠它定位卡在哪）。 */
    var bootstrapLog by mutableStateOf<String?>(null)
        private set

    fun clearBootstrap() {
        if (bootstrapRunning) return
        bootstrapMessage = null
        bootstrapLog = null
        bootstrapOk = false
        bootstrapElapsed = 0
    }

    /** 把当前机器切到本机桥（部署成功后那一步；先过一遍 probe，连不上会报 token 不对）。 */
    fun connectLocalBridge() {
        val url = TermuxBootstrap.DEFAULT_BASE
        saveCurrentMachine("127.0.0.1")
        connectToUrl(url)
    }

    /**
     * 从零部署到**本机 Termux**（会装 nodejs/git、全局 npm 装 pi、clone 仓库、装桥、起桥）。
     *
     * 步骤：取 bootstrap.sh（带 Gitea token）→ base64 → RUN_COMMAND 跑它（日志进 ~/pi-pocket-bootstrap.log）
     * → 轮询 /v1/health 最多 180s（1.5s 一次）→ 读日志尾部 → 给结论与「切过去」入口。
     */
    fun deployLocal(context: Context) {
        if (bootstrapRunning) return
        if (!termuxInstalled(context)) {
            bootstrapOk = false
            bootstrapMessage = "这台手机上没装 Termux（bootstrap 要跑在 Termux 里）"
            return
        }
        if (giteaToken.isBlank()) {
            bootstrapOk = false
            bootstrapMessage = "先在「设置 → 自更新」里填 Gitea token：私有仓要它才能取 bootstrap.sh 和 clone"
            return
        }
        val host = giteaHost.ifBlank { "https://gitea.example.com" }
        val repo = giteaRepo.ifBlank { "example/pi-pocket" }
        bootstrapRunning = true
        bootstrapOk = false
        bootstrapElapsed = 0
        bootstrapLog = null
        bootstrapMessage = "取 bootstrap.sh…"
        scope.launch {
            // 部署前先看一眼：桥本来就在跑的话，这次只是一次幂等更新（别让人以为是新装的）
            val preCode = withContext(Dispatchers.IO) { TermuxBootstrap.healthCode(TermuxBootstrap.DEFAULT_BASE, token) }
            val start = runCatching {
                withContext(Dispatchers.IO) {
                    val script = TermuxBootstrap.fetchScript(host, repo, giteaToken)
                    val b64 = java.util.Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_8))
                    TermuxBootstrap.run(context, TermuxBootstrap.buildCommand(b64, token, giteaToken))
                    script.length
                }
            }
            if (start.isFailure) {
                bootstrapRunning = false
                bootstrapMessage = "没法开始部署：" + (start.exceptionOrNull()?.message ?: "未知")
                return@launch
            }
            bootstrapMessage = "已交给 Termux，正在装依赖 / 装 pi / 起桥…"

            // 轮询：看到「[退出码]」= bootstrap 进程真的结束了（最准）；桥 200 = 起来了（兜底）
            var code = preCode
            var done = false
            for (i in 1..120) {
                delay(1500)
                bootstrapElapsed = (i * 1500) / 1000
                code = withContext(Dispatchers.IO) { TermuxBootstrap.healthCode(TermuxBootstrap.DEFAULT_BASE, token) }
                if (code == 200 && i % 4 == 0) {
                    val tail = withContext(Dispatchers.IO) { TermuxBootstrap.tailViaBridge(TermuxBootstrap.DEFAULT_BASE, token) }
                    if (tail != null && tail.contains("退出码")) {
                        bootstrapLog = tail
                        done = true
                        break
                    }
                }
                // 桥还没起（很可能已经失败）：在 12s / 60s / 114s 各请 Termux 吐一次日志，
                // 一旦看到退出码就提前收工 —— 不然失败也得干等 180s 才知道原因。
                if (code != 200 && (i == 8 || i == 40 || i == 76)) {
                    val tail = withContext(Dispatchers.IO) { TermuxBootstrap.tailViaTermux(context) }
                    if (tail != null && tail.contains("退出码")) {
                        bootstrapLog = tail
                        done = true
                        break
                    }
                }
            }
            // 收尾：再探一次 + 拿日志尾部（桥读优先，桥不通就让 Termux 起一次性只读服务）
            val finalCode = withContext(Dispatchers.IO) { TermuxBootstrap.healthCode(TermuxBootstrap.DEFAULT_BASE, token) }
            if (bootstrapLog == null) {
                bootstrapLog = withContext(Dispatchers.IO) {
                    TermuxBootstrap.tailViaBridge(TermuxBootstrap.DEFAULT_BASE, token)
                        ?: TermuxBootstrap.tailViaTermux(context)
                }
            }
            bootstrapRunning = false
            val logOk = bootstrapLog?.contains("退出码] 0") == true
            bootstrapOk = finalCode == 200 && (logOk || done)
            bootstrapMessage = when {
                finalCode == 200 && bootstrapOk ->
                    (if (preCode == 200) "部署完成（桥本来就在跑，这次是幂等更新）" else "部署完成，桥已在 127.0.0.1:8789") +
                        "；token 在 ~/.pi/agent/pi-pocket.env（600）" + (if (logOk) "" else "；日志最后一行不是成功退出码，最好看一眼尾部")

                finalCode == 200 -> "桥在 127.0.0.1:8789 应答了，但没读到成功退出码（可能还在装）；看下面日志尾部确认"
                finalCode == 401 -> "桥起来了，但它要的 token 和 app 里的不一致：去 ~/.pi/agent/pi-pocket.env 看 PI_POCKET_TOKEN，填到「设置 → token」"
                else -> "部署没完成（等了 " + bootstrapElapsed + "s，本机桥没应答）。看下面日志尾部，或去 Termux 看 " +
                    "~/.pi/agent/pi-pocket-start.log"
            }
        }
    }

    private fun termuxInstalled(context: Context): Boolean =
        runCatching { context.packageManager.getPackageInfo("com.termux", 0) }.isSuccess

    fun chooseModel(ref: ModelRef) = io {
        // 切完**核实一次**：pi 拒绝（如 Model not found）时以前会被 io() 吞掉，
        // 界面上只是“没反应”，看着像切换无效。现在失败必给一条 error 提醒。
        api.setModel(ref.provider, ref.model)
        val state = runCatching { api.info().optJSONObject("state") }.getOrNull()
        val m = state?.optJSONObject("model")
        val nowProvider = m?.optString("provider").orEmpty()
        val nowModel = m?.optString("id").orEmpty()
        if (nowModel != ref.model || (nowProvider != ref.provider && nowProvider.isNotBlank())) {
            addNotice(
                "模型没切成",
                "要切到 ${ref.provider}/${ref.model}，pi 现在还是 $nowProvider/$nowModel",
                "error",
            )
        } else {
            setStatus("模型已切到 $nowProvider/$nowModel", current?.file)
        }
        refreshInfo()
    }

    fun setThinking(level: String) = io { api.setThinking(level); refreshInfo() }

    fun compact(instructions: String? = null) = io {
        api.compact(instructions)
        refreshInfo()
    }

    fun rename(name: String) = io {
        api.rename(name)
        current = current?.copy(name = name.ifBlank { null })
        refreshSessions()
    }

    fun dismissStatus() {
        setStatus(null)
    }

    private fun io(busyFlag: Boolean = true, block: () -> Unit) {
        scope.launch {
            if (busyFlag) busy = true
            runCatching { withContext(Dispatchers.IO) { block() } }
                .onFailure { error = it.message }
            if (busyFlag) busy = false
        }
    }

    /** 同 [io]，但允许在块里挂起（并发拉多个接口时用）。 */
    private fun ioSuspend(busyFlag: Boolean = true, block: suspend () -> Unit) {
        scope.launch {
            if (busyFlag) busy = true
            runCatching { withContext(Dispatchers.IO) { block() } }
                .onFailure { error = it.message }
            if (busyFlag) busy = false
        }
    }

    /** Termux 侧解析配置的脚本（base64 传进去跑，避免引号地狱）。 */
    private val TERMUX_CONFIG_SCRIPT = """
const fs = require('fs');
const os = require('os');
const http = require('http');
const path = require('path');
const agent = process.env.PI_CODING_AGENT_DIR || path.join(os.homedir(), '.pi', 'agent');
const out = [];
const sshConfig = path.join(os.homedir(), '.ssh', 'config');
if (fs.existsSync(sshConfig)) {
  let cur = null; const list = [];
  for (const raw of fs.readFileSync(sshConfig, 'utf8').split('\n')) {
    const line = raw.trim();
    if (!line || line.startsWith('#')) continue;
    const m = /^(\w+)\s+(.+)$/.exec(line);
    if (!m) continue;
    const k = m[1].toLowerCase(), v = m[2].trim();
    if (k === 'host') { if (cur) list.push(cur); cur = { alias: v, host: '', user: '', port: 22, source: 'ssh-config' }; }
    else if (cur && k === 'hostname') cur.host = v;
    else if (cur && k === 'user') cur.user = v;
    else if (cur && k === 'port') cur.port = Number(v) || 22;
  }
  if (cur) list.push(cur);
  for (const e of list) {
    const host = e.host || e.alias;
    if (!host || host.includes('*') || host.includes('?')) continue;
    out.push({ host, port: e.port, user: e.user, alias: e.alias, source: 'ssh-config', url: 'http://' + host + ':8789' });
  }
}
const hostsFile = path.join(agent, 'pi-pocket-hosts.json');
if (fs.existsSync(hostsFile)) {
  try {
    const data = JSON.parse(fs.readFileSync(hostsFile, 'utf8'));
    for (const it of (Array.isArray(data) ? data : data.hosts || [])) {
      if (!it || !it.host) continue;
      out.push({ host: it.host, port: Number(it.port) || 8789, user: it.user || '', alias: it.name || it.host,
        source: 'pi-pocket-hosts', url: it.url || ('http://' + it.host + ':' + (Number(it.port) || 8789)), hasToken: Boolean(it.token) });
    }
  } catch (e) {}
}
const body = JSON.stringify({ machines: out, at: Date.now(), host: os.hostname() });
const server = http.createServer((req, res) => {
  res.writeHead(200, { 'content-type': 'application/json; charset=utf-8', 'access-control-allow-origin': '*' });
  res.end(body);
});
server.listen(8791, '127.0.0.1', () => console.log('machines on 8791'));
setTimeout(() => { server.close(); process.exit(0); }, 30000);
"""

    private const val STREAM_KEY = "__streaming__"

    /** 流式增量合并窗口（毫秒）：一窗一次重组 + 一次 Markdown 重解析。 */
    private const val DELTA_FLUSH_MS = 50L

    /** 展开/收起状态最多记多少条（超出丢最早动过的）。 */
    private const val OPEN_STATE_LIMIT = 400
}

/**
 * 「压缩上下文」卡片的数据（一屏一张，带会话归属）。
 *
 * @param running 正在压缩（界面上转圈）；压完就地改成结果。
 * @param afterKey 建卡时列表里最后一条消息的 key —— 界面把卡插在**它后面**，后续消息自然排在
 *   卡下面 ⇒ 卡片跟着时间线往上走，不再固定贴底。
 */
data class CompactionCardInfo(
    val title: String,
    val detail: String,
    val running: Boolean = false,
    val afterKey: String = "",
    /**
     * 压缩摘要正文（pi 的 `CompactionResult.summary`）。
     *
     * pi 那张卡点开就能读摘要；app 之前只有 `tokens: N → M`，看不到"压成了什么"——
     * 压缩后觉得丢信息只能去终端翻。现在摘要随卡一起存，点开就在聊天里读。
     */
    val summary: String = "",
)
