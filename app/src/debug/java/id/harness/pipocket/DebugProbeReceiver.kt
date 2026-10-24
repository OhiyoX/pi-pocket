package id.harness.pipocket

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * **debug 构建专用**的验证探针（release 里不存在这个类）。
 *
 * 为什么要它：app 的取数路径（PiClient / PiLocator / AppState.launchLocalBridge）只有在 app 进程里跑才算数，
 * 而用 adb 点屏幕既慢又会打到别人的 App 上。这个接收器让同一条代码路径可以被 adb 触发，结果写 logcat：
 *
 *   adb shell am broadcast -a id.harness.pipocket.DEBUG_PROBE --es action scan
 *   adb shell am broadcast -a id.harness.pipocket.DEBUG_PROBE --es action prompt --es text "hi" [--es url ...] [--es token ...]
 *   adb shell am broadcast -a id.harness.pipocket.DEBUG_PROBE --es action startbridge
 */
class DebugProbeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.getStringExtra("action") ?: "scan"
        val appContext = context.applicationContext
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val text = intent.getStringExtra("text").orEmpty()
        val urlOverride = intent.getStringExtra("url")
        val tokenOverride = intent.getStringExtra("token")

        Log.i(TAG, "probe start action=$action url=${urlOverride ?: "(prefs)"}")

        when (action) {
            "health" -> scope.launch {
                val api = PiClient({ urlOverride ?: AppState.baseUrl }, { tokenOverride ?: AppState.token })
                val data = runCatching { api.health() }.getOrElse {
                    Log.i(TAG, "RESULT health FAILED: ${it.message}")
                    return@launch
                }
                Log.i(TAG, "RESULT health ok=${data.optBoolean("ok")} host=${data.optString("host")} pi=${data.optString("pi")} auth=${data.optBoolean("auth")}")
            }

            "scan" -> scope.launch {
                AppState.init(appContext)
                val hits = runCatching {
                    PiLocator.scan(
                        seed = AppState.baseUrl,
                        token = tokenOverride ?: AppState.token,
                        savedHosts = AppState.savedHosts,
                        includeLan = true,
                        context = appContext,
                        onProgress = { Log.i(TAG, "progress: $it") },
                    )
                }.getOrElse {
                    Log.i(TAG, "RESULT scan FAILED: ${it.message}")
                    return@launch
                }
                Log.i(TAG, "lanPrefixes=${PiLocator.lanPrefixes(appContext)}")
                hits.forEach { Log.i(TAG, "hit: host=${it.host} url=${it.url} scope=${it.scope} auth=${it.authRequired} pi=${it.piPath}") }
                Log.i(TAG, "RESULT scan hits=${hits.size} (${hits.joinToString(",") { it.url }})")
            }

            "runshell" -> {
                // 诊断用：确认 Termux 到底有没有执行 RUN_COMMAND（写一个带时间戳的标记文件）
                val command = intent.getStringExtra("cmd")
                    ?: "date -u +%FT%TZ > /data/data/com.termux/files/home/run-command-probe.txt"
                val i = Intent("com.termux.RUN_COMMAND").apply {
                    setClassName("com.termux", "com.termux.app.RunCommandService")
                    putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
                    putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-lc", command))
                    putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home")
                    putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
                    putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "1")
                }
                val result = runCatching { context.startService(i) }
                Log.i(TAG, "RESULT runshell accepted=${result.isSuccess} err=${result.exceptionOrNull()?.message ?: "-"} cmd=$command")
            }

            // 会话树探针：不点屏幕，直接走 app 的数据路径，把条数/耗时写 logcat。
            // 用法：am broadcast -a id.harness.pipocket.DEBUG_PROBE -p id.harness.pipocket --es action tree
            "tree" -> scope.launch {
                Handler(Looper.getMainLooper()).post { AppState.init(appContext) }
                delay(300)
                val t0 = System.currentTimeMillis()
                val (snap, err) = AppState.fetchTreeNow()
                val fetchMs = System.currentTimeMillis() - t0
                if (snap == null) {
                    Log.i(TAG, "RESULT tree FAILED ${fetchMs}ms err=$err")
                    return@launch
                }
                val t1 = System.currentTimeMillis()
                Log.i(TAG, "tree: buildTreeRows start (nodes=${snap.nodes.size})")
                val rows = id.harness.pipocket.ui.buildTreeRows(snap.nodes, snap.leafId, "default")
                Log.i(TAG, "tree: buildTreeRows done ${System.currentTimeMillis() - t1}ms rows=${rows.size}")
                val foldRows = id.harness.pipocket.ui.buildTreeRows(
                    snap.nodes,
                    snap.leafId,
                    "default",
                    "",
                    setOfNotNull(snap.nodes.firstOrNull()?.id),
                )
                val rowsMs = System.currentTimeMillis() - t1
                Log.i(
                    TAG,
                    "RESULT tree fetch=${fetchMs}ms rows=${rowsMs}ms nodes=${snap.nodes.size} " +
                        "default=${rows.size} folded=${foldRows.size} leaf=${snap.leafId?.take(8)}",
                )
            }

            // 交付/快切探针（不点屏幕）：--es action copy|export|cyclethink
            "copy" -> scope.launch {
                Handler(Looper.getMainLooper()).post { AppState.init(appContext) }
                delay(300)
                val text = runCatching { PiClient({ AppState.baseUrl }, { AppState.token }).lastAssistantText() }
                    .getOrElse { Log.i(TAG, "RESULT copy FAILED: ${it.message}"); return@launch }
                val outcome = id.harness.pipocket.ui.copyLastAnswerOutcome(text)
                Log.i(TAG, "RESULT copy ok=${outcome.ok} msg=${outcome.message} chars=${text.length}")
            }

            "export" -> scope.launch {
                Handler(Looper.getMainLooper()).post { AppState.init(appContext) }
                delay(300)
                val t0 = System.currentTimeMillis()
                val r = runCatching {
                    PiClient({ AppState.baseUrl }, { AppState.token }).exportHtml()
                }.getOrElse { Log.i(TAG, "RESULT export FAILED: ${it.message}"); return@launch }
                Log.i(
                    TAG,
                    "RESULT export path=${r.path} bytes=${r.bytes} ms=${System.currentTimeMillis() - t0} " +
                        "summary=${id.harness.pipocket.ui.exportSummary(r.path ?: "", r.bytes)}",
                )
            }

            "cyclethink" -> scope.launch {
                Handler(Looper.getMainLooper()).post { AppState.init(appContext) }
                delay(300)
                val before = runCatching { PiClient({ AppState.baseUrl }, { AppState.token }).info() }
                    .getOrNull()?.optJSONObject("state")?.optString("thinkingLevel")
                val level = runCatching { PiClient({ AppState.baseUrl }, { AppState.token }).cycleThinking() }
                    .getOrElse { Log.i(TAG, "RESULT cyclethink FAILED: ${it.message}"); return@launch }
                Log.i(TAG, "RESULT cyclethink before=$before after=$level")
            }

            // 状态探针：看 app 眼里的"最后一条"是不是流式、正文多长（验证逐字上屏用）
            "state" -> {
                Handler(Looper.getMainLooper()).post { AppState.init(appContext) }
                Thread.sleep(400)
                val last = AppState.items.lastOrNull()
                Log.i(
                    TAG,
                    "RESULT state streaming=${AppState.streaming} items=${AppState.items.size} " +
                        "lastRole=${last?.role} lastStreaming=${last?.streaming} lastLen=${last?.text?.length} " +
                        "lastKey=${last?.key}",
                )
            }

            // 模拟"点通知进来"：openfile + --es file <会话文件>
            "openfile" -> {
                val file = intent.getStringExtra("file").orEmpty()
                Log.i(TAG, "probe start action=openfile file=$file")
                AppState.init(appContext)
                Thread { runCatching { AppState.openSessionByFile(file) } }
            }

            "openfirst" -> {
                Handler(Looper.getMainLooper()).post {
                    AppState.init(appContext)
                }
                scope.launch {
                    delay(500)
                    val session = AppState.sessions.firstOrNull()
                    if (session == null) {
                        Log.i(TAG, "RESULT openfirst FAILED: 没有会话")
                        return@launch
                    }
                    Handler(Looper.getMainLooper()).post { AppState.openSession(session) }
                    delay(2500)
                    Log.i(TAG, "RESULT openfirst ${session.title} items=${AppState.items.size}")
                }
            }

            "shell" -> {
                val cmd = intent.getStringExtra("cmd") ?: "uname -a; node -v"
                Handler(Looper.getMainLooper()).post {
                    AppState.init(appContext)
                    AppState.openShell()
                    AppState.runShell(cmd, excludeFromContext = true)
                }
                scope.launch {
                    repeat(20) {
                        delay(1500)
                        if (!AppState.shellRunning && AppState.shellOutput.isNotBlank()) {
                            Log.i(TAG, "RESULT shell exit=${AppState.shellExit} bytes=${AppState.shellOutput.length}")
                            Log.i(TAG, "shell output: ${AppState.shellOutput.take(300).replace("\n", " | ")}")
                            return@launch
                        }
                    }
                    Log.i(TAG, "RESULT shell TIMEOUT bytes=${AppState.shellOutput.length}")
                }
            }

            "font" -> {
                val scale = intent.getStringExtra("scale")?.toFloatOrNull() ?: 1.3f
                val chat = intent.getStringExtra("chat")?.toFloatOrNull() ?: scale
                Handler(Looper.getMainLooper()).post {
                    AppState.init(appContext)
                    AppState.updateFontScale(scale)
                    AppState.updateChatFontScale(chat)
                }
                scope.launch {
                    delay(1200)
                    Log.i(TAG, "RESULT font global=${AppState.fontScale} chat=${AppState.chatFontScale}")
                }
            }

            "machines" -> {
                Handler(Looper.getMainLooper()).post { AppState.init(appContext) }
                scope.launch {
                    delay(800)
                    Log.i(TAG, "knownMachines before: ${AppState.knownMachines.joinToString { it.host + "/" + it.url + "/" + it.scope }}")
                    val hits = runCatching { AppState.probeKnownForProbe() }.getOrDefault(emptyList())
                    hits.forEach { Log.i(TAG, "probeKnown hit: host=${it.host} url=${it.url} scope=${it.scope} auth=${it.authRequired}") }
                    Log.i(TAG, "RESULT machines known=${AppState.knownMachines.size} onlineNow=${hits.size} (${hits.joinToString(",") { it.url }})")
                }
            }

            "sse" -> {
                // 探针：开 8 秒 SSE，回报收到了多少事件 / 失败原因（诊断“拉得到会话但事件流断”）
                val url = urlOverride ?: AppState.baseUrl
                val token = tokenOverride ?: AppState.token
                val api = PiClient({ url }, { token })
                var count = 0
                val source = api.connectEvents(object : okhttp3.sse.EventSourceListener() {
                    override fun onOpen(eventSource: okhttp3.sse.EventSource, response: okhttp3.Response) {
                        Log.i(TAG, "sse onOpen code=${response.code}")
                    }

                    override fun onEvent(eventSource: okhttp3.sse.EventSource, id: String?, type: String?, data: String) {
                        count++
                        if (count <= 3) Log.i(TAG, "sse event: ${data.take(120)}")
                    }

                    override fun onClosed(eventSource: okhttp3.sse.EventSource) {
                        Log.i(TAG, "sse onClosed after $count events")
                    }

                    override fun onFailure(eventSource: okhttp3.sse.EventSource, t: Throwable?, response: okhttp3.Response?) {
                        Log.i(TAG, "sse onFailure code=${response?.code} err=${t?.message}")
                    }
                })
                scope.launch {
                    delay(8000)
                    source.cancel()
                    Log.i(TAG, "RESULT sse url=$url events=$count")
                }
            }

            "stream" -> {
                // 实时流式取证：发一轮会跑一阵的任务，每 1.5s 记一次「累计字符数 / 是否有工具在跑 / 是否还在流」
                Handler(Looper.getMainLooper()).post { AppState.init(appContext) }
                val prompt = intent.getStringExtra("text")
                    ?: "Run bash: for k in 1 2 3 4 5 6; do echo LINE-\$k; sleep 1; done  然后简短汇报"
                val started = System.currentTimeMillis()
                scope.launch {
                    delay(800)
                    Handler(Looper.getMainLooper()).post { AppState.send(prompt, emptyList()) }
                    var last = 0
                    repeat(24) {
                        delay(1500)
                        val snap = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            Triple(
                                AppState.items.joinToString("") { i -> i.text + i.thinking + i.toolResult }.length,
                                AppState.items.count { i -> i.toolRunning },
                                AppState.streaming,
                            )
                        }
                        Log.i(TAG, "stream t=" + (System.currentTimeMillis() - started) / 1000 + "s chars=" + snap.first + " grew=" + (snap.first - last) + " runningTools=" + snap.second + " streaming=" + snap.third)
                        last = snap.first
                    }
                    Log.i(TAG, "RESULT stream end chars=" + last)
                }
            }

            "answerui" -> {
                // 端到端验证扩展 UI：发 /uitest，然后自动按 app 的应答路径依次回应
                Handler(Looper.getMainLooper()).post { AppState.init(appContext) }
                val prompt = intent.getStringExtra("text") ?: "/uitest"
                scope.launch {
                    delay(800)
                    Handler(Looper.getMainLooper()).post { AppState.send(prompt, emptyList()) }
                    repeat(30) {
                        delay(1200)
                        val pending = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            AppState.uiPrompts.firstOrNull()
                        }
                        if (pending == null) {
                            Log.i(TAG, "answerui waiting… (no prompt)")
                            return@repeat
                        }
                        Log.i(TAG, "prompt: method=" + pending.method + " title=" + pending.title +
                            " options=" + pending.options.joinToString(",") + " timeout=" + pending.timeoutMs)
                        when (pending.method) {
                            "select" -> {
                                val pick = pending.options.getOrNull(1) ?: pending.options.firstOrNull() ?: ""
                                Log.i(TAG, "answering select -> " + pick)
                                AppState.answerValue(pending, pick)
                            }
                            "confirm" -> {
                                Log.i(TAG, "answering confirm -> true")
                                AppState.answerConfirm(pending, true)
                            }
                            else -> {
                                Log.i(TAG, "answering " + pending.method + " -> hello-from-app")
                                AppState.answerValue(pending, "hello-from-app")
                            }
                        }
                        kotlinx.coroutines.delay(300)
                    }
                    Log.i(TAG, "RESULT answerui done, remaining=" + AppState.uiPrompts.size)
                }
            }

            "promptinnew" -> {
                // 在「新建会话」后立刻发 prompt，以便看到会话内的 inline 交互面板
                Handler(Looper.getMainLooper()).post { AppState.init(appContext) }
                val text = intent.getStringExtra("text") ?: "/uitest"
                Handler(Looper.getMainLooper()).post { AppState.newSession(null) }
                scope.launch {
                    delay(3000)
                    Handler(Looper.getMainLooper()).post { AppState.send(text, emptyList()) }
                    delay(4000)
                    Log.i(TAG, "RESULT promptinnew pending=" + AppState.uiPrompts.size + " inSession=" + (AppState.current != null))
                }
            }

            "theme" -> {
                val mode = intent.getStringExtra("mode") ?: "Dark"
                Handler(Looper.getMainLooper()).post {
                    AppState.init(appContext)
                    runCatching { AppState.updateThemeMode(id.harness.pipocket.ThemeMode.valueOf(mode)) }
                }
                scope.launch {
                    delay(1200)
                    Log.i(TAG, "RESULT theme mode=" + AppState.themeMode.name + " darkNow=" + AppState.isDark(false))
                }
            }

            "commands" -> {
                Handler(Looper.getMainLooper()).post { AppState.init(appContext); AppState.loadCommands() }
                scope.launch {
                    delay(2500)
                    Log.i(TAG, "RESULT commands=" + AppState.commands.size + " sample=" + AppState.commands.take(5).joinToString(",") { it.name })
                }
            }

            "args" -> {
                // 验证斜杠「参数补全」：/model fl、/mode ed、/skill hard… 应各出候选
                Handler(Looper.getMainLooper()).post {
                    AppState.init(appContext)
                    AppState.loadCommands()
                    AppState.loadModels()
                }
                val cmd = intent.getStringExtra("cmd") ?: "/model"
                val q = intent.getStringExtra("q").orEmpty()
                scope.launch {
                    delay(3000)
                    val opts = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        id.harness.pipocket.ui.filterArgumentOptions(cmd, q, AppState.models, AppState.commands, AppState.sessions)
                    }
                    Log.i(TAG, "RESULT args cmd=" + cmd + " q=" + q + " n=" + opts.size +
                        " -> " + opts.take(5).joinToString(" | ") { it.first })
                }
            }

            "setconfig" -> {
                Handler(Looper.getMainLooper()).post {
                    AppState.init(appContext)
                    urlOverride?.let { AppState.updateBaseUrl(it) }
                    if (intent.getBooleanExtra("cleartoken", false)) AppState.updateToken("") else tokenOverride?.let { AppState.updateToken(it) }
                }
                scope.launch {
                    delay(1500)
                    Log.i(TAG, "RESULT setconfig baseUrl=${AppState.baseUrl} tokenLen=${AppState.token.length} hosts=${AppState.savedHosts.size}")
                }
            }

            // SFTP 视觉验收：从设备上读一个私钥文件 → 存成 SSH 目标（复用同一套账号）
            "sshkey" -> {
                Handler(Looper.getMainLooper()).post {
                    AppState.init(appContext)
                    val path = intent.getStringExtra("keyfile") ?: "/sdcard/Download/pp-key"
                    val keyText = runCatching { java.io.File(path).readText() }.getOrNull()
                    if (keyText == null) {
                        Log.i(TAG, "RESULT sshkey FAILED: 读不到 $path")
                        return@post
                    }
                    AppState.saveSshTarget(
                        id.harness.pipocket.SshTarget(
                            host = intent.getStringExtra("host") ?: "127.0.0.1",
                            port = intent.getStringExtra("port")?.toIntOrNull() ?: 8022,
                            user = intent.getStringExtra("user") ?: "u0_a816",
                            authKind = "key",
                            privateKey = keyText,
                            startCommand = "",
                        )
                    )
                }
                scope.launch {
                    delay(1200)
                    Log.i(TAG, "RESULT sshkey saved=${AppState.sshTargets.map { it.user + "@" + it.host }}")
                }
            }

            "addhost" -> {
                Handler(Looper.getMainLooper()).post {
                    AppState.init(appContext)
                    urlOverride?.let { AppState.addSavedHost(it) }
                }
                scope.launch {
                    delay(1200)
                    Log.i(TAG, "RESULT addhost saved=${AppState.savedHosts.joinToString(",")}")
                }
            }

            "prompt" -> scope.launch {
                AppState.init(appContext)
                val url = urlOverride ?: AppState.baseUrl
                val token = tokenOverride ?: AppState.token
                val api = PiClient({ url }, { token })

                val probe = PiLocator.probe(url, token)
                if (probe == null) {
                    Log.i(TAG, "RESULT prompt FAILED: probe 不通（$url，token ${if (token.isBlank()) "空" else "非空"}）")
                    return@launch
                }
                Log.i(TAG, "connected: host=${probe.host} sessionsRoot=${probe.sessionsRoot}")

                val created = runCatching { api.newSession(null) }.getOrElse {
                    Log.i(TAG, "RESULT prompt FAILED: newSession ${it.message}")
                    return@launch
                }
                val file = created.optJSONObject("state")?.optString("sessionFile").orEmpty()
                Log.i(TAG, "new session: $file")

                val accepted = runCatching { api.prompt(text, emptyList()) }.getOrElse {
                    Log.i(TAG, "RESULT prompt FAILED: prompt ${it.message}")
                    return@launch
                }
                Log.i(TAG, "prompt accepted: ${accepted.toString().take(120)}")

                repeat(30) { attempt ->
                    delay(2000)
                    val messages = runCatching { api.messages() }.getOrElse { return@repeat }
                    val assistant = (0 until messages.size)
                        .map { messages[it] }
                        .lastOrNull { it.role == "assistant" && it.text.isNotBlank() }
                    if (assistant != null) {
                        Log.i(TAG, "RESULT prompt answered after ${(attempt + 1) * 2}s: ${assistant.text.replace("\n", " ").take(200)}")
                        Log.i(TAG, "RESULT session=$file")
                        return@launch
                    }
                }
                Log.i(TAG, "RESULT prompt TIMEOUT（60s 内没有助手文本）")
            }

            "startbridge" -> {
                Handler(Looper.getMainLooper()).post {
                    AppState.init(appContext)
                    AppState.launchLocalBridge(appContext)
                }
                scope.launch {
                    delay(3000)
                    Log.i(TAG, "launchMessage=${AppState.launchMessage}")
                    val hit = runCatching {
                        PiLocator.scan(AppState.baseUrl, AppState.token, AppState.savedHosts, includeLan = false, context = appContext)
                    }.getOrDefault(emptyList()).firstOrNull { it.scope == BridgeScope.Local }
                    Log.i(TAG, "RESULT startbridge ${if (hit != null) "OK ${hit.url} host=${hit.host}" else "FAILED（3s 内没探到本机桥）"}")
                }
            }

            "appprofile" -> scope.launch {
                AppState.init(appContext)
                val api = PiClient({ urlOverride ?: AppState.baseUrl }, { tokenOverride ?: AppState.token })
                val p = runCatching { api.appProfile() }.getOrElse {
                    Log.i(TAG, "RESULT appprofile FAILED: ${it.message}")
                    return@launch
                }
                Log.i(TAG, "RESULT appprofile bridge=${p.bridgeEnabled} appendPrompt=${p.appendPrompt}(present=${p.appendPromptPresent}) skills=${p.skills.size} mcp=${p.mcp.size}")
                p.skills.forEach { Log.i(TAG, "  skill ${it.id} enabled=${it.enabled} name=${it.name}") }
            }

            // 注一条排队（steer / followUp）——验证排队条的展开/编辑/取消交互（真桥对应 queue_update）
            "queue" -> {
                Handler(Looper.getMainLooper()).post { AppState.init(appContext) }
                val steer = intent.getStringExtra("text")
                val follow = intent.getStringExtra("follow")
                Handler(Looper.getMainLooper()).post { AppState.debugQueue(steer, follow) }
            }

            // 让 Termux 直接跑一条命令（走 RUN_COMMAND，app 有这个权限）——用于远程/无 adb 场景的落地
            "termuxrun" -> {
                val cmd = intent.getStringExtra("cmd") ?: return
                Handler(Looper.getMainLooper()).post {
                    val i = android.content.Intent("com.termux.RUN_COMMAND").apply {
                        setClassName("com.termux", "com.termux.app.RunCommandService")
                        putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
                        putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-lc", cmd))
                        putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home")
                        putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "2")
                        putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
                    }
                    runCatching { appContext.startService(i) }
                        .onSuccess { Log.i(TAG, "RESULT termuxrun dispatched") }
                        .onFailure { Log.i(TAG, "RESULT termuxrun FAILED: ${it.message}") }
                }
            }

            // 配置 Gitea 自更新（主机/仓库/token）
            "setgitea" -> {
                Handler(Looper.getMainLooper()).post {
                    AppState.init(appContext)
                    intent.getStringExtra("host")?.let { host ->
                        AppState.updateGitea(host, intent.getStringExtra("repo") ?: AppState.giteaRepo)
                    }
                    intent.getStringExtra("token")?.let { AppState.updateGiteaToken(it) }
                }
                scope.launch {
                    delay(1200)
                    Log.i(TAG, "RESULT setgitea host=${AppState.giteaHost} repo=${AppState.giteaRepo} tokenLen=${AppState.giteaToken.length}")
                }
            }

            "info" -> scope.launch {
                AppState.init(appContext)
                val api = PiClient({ urlOverride ?: AppState.baseUrl }, { tokenOverride ?: AppState.token })
                val data = runCatching { api.info() }.getOrElse {
                    Log.i(TAG, "RESULT info FAILED: ${it.message}")
                    return@launch
                }
                val state = data.optJSONObject("state") ?: JSONObject()
                Log.i(TAG, "RESULT info session=${state.optString("sessionId")} model=${state.optJSONObject("model")?.optString("id")} thinking=${state.optString("thinkingLevel")}")
            }

            "items" -> {
                // 探针：看解析出来的行（尤其图片：images / imagesOmitted / msgIndex）
                Handler(Looper.getMainLooper()).post { AppState.init(appContext) }
                scope.launch {
                    delay(600)
                    val snapshot = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { AppState.items.toList() }
                    snapshot.forEach { item ->
                        Log.i(
                            TAG,
                            "item key=${item.key} role=${item.role} text=${item.text.length} " +
                                "thinking=${item.thinking.length} tool=${item.isTool} " +
                                "toolName=${item.toolName} brief=${item.toolBrief.take(28)} " +
                                "args=${item.toolArgsJson.length} imgs=${item.toolImages.size} " +
                                "images=${item.images.size} omitted=${item.imagesOmitted} msgIndex=${item.msgIndex}",
                        )
                    }
                    Log.i(TAG, "RESULT items=${snapshot.size} streaming=${AppState.streaming}")
                }
            }

            else -> Log.i(TAG, "RESULT unknown action=$action")
        }
    }

    private companion object {
        const val TAG = "PiPocketDebug"
    }
}
