package id.harness.pipocket

import android.content.Context
import android.content.Intent
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * 「从零部署（本机 Termux）」的 app 侧实现。脚本与桥的契约见 HANDOFF §15 / `docs/BOOTSTRAP.md`，
 * **这里不改脚本的 CLI 契约**（仍然是 `REPO_TOKEN=… bash bootstrap.sh`，`--check` 只体检）。
 *
 * 链路：
 * ```
 * app 从 Gitea 取 termux/bootstrap.sh（带 token）→ base64
 *   → Termux RUN_COMMAND：bash -c "echo <b64> | base64 -d > ~/.pi-pocket-bootstrap.sh && REPO_TOKEN=… bash 它"
 *   → 脚本自己装依赖 / 装 pi / clone / install.sh / 生成 token / 起桥 / 自检
 *   → app 轮询 http://127.0.0.1:8789/v1/health，成功后把机器切过去
 * ```
 *
 * 两个刻意的设计：
 * 1. **base64 传脚本**：RUN_COMMAND 只有一段命令行参数，脚本内容直接拼进去必被引号/换行搞坏。
 * 2. **预置 token**：脚本只在 env 文件**没有** `PI_POCKET_TOKEN` 时才生成新 token。
 *    app 先把手里已有的 token 写进去（不存在才写），部署完 app 就能直接连上，不用让人再抄 token。
 */
object TermuxBootstrap {

    /** 本机桥的固定地址（脚本默认端口 8789）。 */
    const val DEFAULT_BASE = "http://127.0.0.1:8789"

    private const val HOME = "/data/data/com.termux/files/home"
    private const val ENV_PATH = HOME + "/.pi/agent/pi-pocket.env"
    private const val LOG_PATH = HOME + "/pi-pocket-bootstrap.log"
    private const val SCRIPT_PATH = HOME + "/.pi-pocket-bootstrap.sh"
    private const val BASH = "/data/data/com.termux/files/usr/bin/bash"

    /** 一次性只读服务（把日志尾部吐给 app；25 秒自关）。 */
    private const val LOG_HTTP_PORT = 8793

    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    // ── 脚本 ────────────────────────────────────────────────────────────────

    /**
     * 从 Gitea 取 `termux/bootstrap.sh` 原文。
     *
     * 先按网页 raw 路径取（brief 推荐的那条），不行再退回 Gitea API 的 raw（私有仓更稳）。
     * 两条都带 `Authorization: token …`；返回内容会做一次"像不像 bootstrap 脚本"的粗校验，
     * 免得把登录页 HTML 当脚本塞给 Termux（那会跑出一个看不懂的语法错误）。
     */
    fun fetchScript(host: String, repo: String, token: String): String {
        val base = host.trim().trimEnd('/')
        val candidates = listOf(
            base + "/" + repo + "/raw/branch/main/termux/bootstrap.sh",
            base + "/api/v1/repos/" + repo + "/raw/termux/bootstrap.sh?ref=main",
        )
        val errors = mutableListOf<String>()
        for (url in candidates) {
            try {
                val body = run {
                    val request = Request.Builder().url(url)
                        .apply { if (token.isNotBlank()) header("Authorization", "token " + token) }
                        .build()
                    client.newCall(request).execute().use { response ->
                        val text = response.body?.string().orEmpty()
                        if (!response.isSuccessful) throw IllegalStateException("HTTP " + response.code)
                        text
                    }
                }
                if (body.contains("pi-pocket") && body.contains("PI_POCKET_TOKEN")) return body
                errors += url + " → 返回的不像 bootstrap.sh（" + body.length + " 字节）"
            } catch (e: Exception) {
                errors += url + " → " + (e.message ?: "失败")
            }
        }
        throw IllegalStateException("取 bootstrap.sh 失败：" + errors.joinToString("；"))
    }

    /**
     * 拼 RUN_COMMAND 的那条 bash 命令（`-lc` 执行）。
     *
     * 顺序：预置 token（不存在才写）→ 解 base64 落脚本 → 跑脚本并把输出写进日志 → 尽力往 Download 再存一份。
     * 注意：日志**不丢**给 /dev/null，失败时 app 还要靠它显示尾部 20 行。
     */
    fun buildCommand(scriptBase64: String, appToken: String, repoToken: String): String {
        val seed = if (appToken.isBlank()) {
            // 没有 app token 就不预置，让脚本自己生成（部署完在 env 文件里看）
            ""
        } else {
            "if ! grep -q '^PI_POCKET_TOKEN=' " + ENV_PATH + " 2>/dev/null; then mkdir -p " + HOME + "/.pi/agent; " +
                "umask 077; printf '%s\\n' 'PI_POCKET_TOKEN=" + appToken + "' 'PI_POCKET_PORT=8789' > " + ENV_PATH + "; " +
                "chmod 600 " + ENV_PATH + "; fi; "
        }
        val repoEnv = if (repoToken.isBlank()) "" else "REPO_TOKEN=" + repoToken + " "
        return seed +
            "echo " + scriptBase64 + " | base64 -d > " + SCRIPT_PATH + " && " +
            "chmod 700 " + SCRIPT_PATH + " && " +
            "echo '[开始] pi-pocket bootstrap' > " + LOG_PATH + " && " +
            repoEnv + "bash " + SCRIPT_PATH + " >> " + LOG_PATH + " 2>&1; " +
            "echo \"[退出码] $?\" >> " + LOG_PATH + "; " +
            "cp " + LOG_PATH + " /sdcard/Download/pi-pocket-bootstrap.log 2>/dev/null; " +
            "true"
    }

    /** 让 Termux 跑 bootstrap（`SESSION_ACTION=2`：新建会话但**不切前台**，Android 10+ 不许后台起 Activity）。 */
    fun run(context: Context, command: String) {
        val intent = Intent("com.termux.RUN_COMMAND").apply {
            setClassName("com.termux", "com.termux.app.RunCommandService")
            putExtra("com.termux.RUN_COMMAND_PATH", BASH)
            putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-lc", command))
            putExtra("com.termux.RUN_COMMAND_WORKDIR", HOME)
            putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "2")
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
        }
        context.startService(intent)
    }

    /** 关掉已经跑完的部署进程（RUN_COMMAND 没有回执，这里只清一次性日志服务，杀进程留给用户）。 */

    // ── 探测 / 日志 ─────────────────────────────────────────────────────────

    /**
     * 探本机桥：返回 HTTP 状态码，连不上返回 -1。
     *
     * 200 = 桥在且 token 对；401 = 桥在但 token 和 app 里的不一致（也算"部署成功"，只是 app 要换 token）。
     */
    fun healthCode(base: String, token: String): Int = try {
        val request = Request.Builder().url(base.trimEnd('/') + "/v1/health")
            .apply { if (token.isNotBlank()) header("Authorization", "Bearer " + token) }
            .build()
        client.newCall(request).execute().use { it.code }
    } catch (e: Exception) {
        -1
    }

    /**
     * 经由**桥**读 bootstrap 日志（桥已就绪时最省事：不用再起 Termux 进程、也不需要存储权限）。
     */
    fun tailViaBridge(base: String, token: String): String? = try {
        val path = java.net.URLEncoder.encode(LOG_PATH, "UTF-8")
        val request = Request.Builder().url(base.trimEnd('/') + "/v1/file?path=" + path)
            .apply { if (token.isNotBlank()) header("Authorization", "Bearer " + token) }
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) return null
            ProviderForm.tailLines(org.json.JSONObject(text).optJSONObject("data")?.optString("text").orEmpty(), 20)
                .takeIf { it.isNotBlank() }
        }
    } catch (e: Exception) {
        null
    }

    /**
     * 桥没起来时读日志：让 Termux 起一个**一次性只读**本地服务（127.0.0.1:8793，25 秒自关），
     * 把日志尾部 20 行吐出来。与「读本机配置」走的是同一套做法（不需要存储权限）。
     *
     * 依赖 node —— 而 node 正是 bootstrap 的第一步，所以"走到一半失败"时它一定在。
     */
    fun tailViaTermux(context: Context): String? {
        val script = LOG_SERVER_JS
        val b64 = Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_8))
        val intent = Intent("com.termux.RUN_COMMAND").apply {
            setClassName("com.termux", "com.termux.app.RunCommandService")
            putExtra("com.termux.RUN_COMMAND_PATH", BASH)
            putExtra(
                "com.termux.RUN_COMMAND_ARGUMENTS",
                arrayOf("-lc", "echo " + b64 + " | base64 -d > " + HOME + "/.pi-pocket-log.js && node " + HOME + "/.pi-pocket-log.js"),
            )
            putExtra("com.termux.RUN_COMMAND_WORKDIR", HOME)
            putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "2")
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
        }
        runCatching { context.startService(intent) }.getOrElse { return null }
        repeat(8) {
            Thread.sleep(700)
            val text = runCatching {
                val request = Request.Builder().url("http://127.0.0.1:" + LOG_HTTP_PORT + "/log").build()
                OkHttpClient.Builder().callTimeout(2, TimeUnit.SECONDS).build()
                    .newCall(request).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
            }.getOrNull()
            if (text != null) return ProviderForm.tailLines(text, 20).ifBlank { "(日志是空的)" }
        }
        return null
    }

    /** 部署失败且拿不到日志时，给用户一条能自己去看的路径。 */
    fun logHint(): String = "日志：" + LOG_PATH + "（已尽力复制到 /sdcard/Download/pi-pocket-bootstrap.log）"

    /**
     * 一次性日志服务（Termux 里用 node 跑）。
     *
     * 全部用 `String.fromCharCode(10)` 拼换行，不写成转义字面量（源码里那些转义容易被写坏）。
     */
    private val LOG_SERVER_JS = """
const http = require('http');
const fs = require('fs');
const path = require('path');
const os = require('os');
const NL = String.fromCharCode(10);
const file = path.join(os.homedir(), 'pi-pocket-bootstrap.log');
let text = '';
try { text = fs.readFileSync(file, 'utf8'); } catch (e) { text = '(还没有日志：' + file + ' / ' + e.message + ')'; }
const all = text.split(NL).filter((l) => l.length > 0);
const tail = all.slice(Math.max(0, all.length - 20)).join(NL);
const server = http.createServer((req, res) => {
  res.writeHead(200, { 'content-type': 'text/plain; charset=utf-8' });
  res.end(tail);
});
server.listen(8793, '127.0.0.1');
setTimeout(() => process.exit(0), 25000);
"""
}
