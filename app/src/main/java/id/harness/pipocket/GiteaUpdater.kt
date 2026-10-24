package id.harness.pipocket

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 从 Gitea 自更新「相关代码/脚本」（用户口径）。
 *
 * 为什么要它：桥 / 启动器 / skills / app-profile 都跑在 **Termux** 里，app 装不了它们；
 * 以前只能让用户在 Termux 里手敲 `git pull && ./termux/install.sh`。这里把这条路自动化：
 *
 *   1. 用 Gitea API 问默认分支最新 commit（对比 app 里记的上次应用过的 SHA → 有没有更新）；
 *   2. 按清单把需要的文件从 Gitea raw 接口下载下来；
 *   3. 把文件写进 /sdcard/Download/pi-pocket-update/（走 MediaStore，不需要额外权限）；
 *   4. 用 `com.termux.RUN_COMMAND` 让 Termux 跑那个目录里的 `apply-update.sh`
 *      （脚本负责：备份旧桥 → 覆盖 $PREFIX/bin → 重启桥 → 回显版本）。
 *
 * 安全：只覆盖清单里的文件；旧桥会先备份成 `pi-pocket-bridge.bak-<时间>`；失败不影响正在跑的桥。
 */
object GiteaUpdater {

    data class Status(
        val sha: String,
        val message: String,
        val dir: String,
        val files: List<String>,
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private fun apiBase(host: String) = host.trimEnd('/') + "/api/v1"

    /** 仓库默认分支最新 commit（顺带把 message 带回来给人看）。 */
    fun latestCommit(host: String, repo: String, token: String): Pair<String, String> {
        val request = Request.Builder()
            .url(apiBase(host) + "/repos/$repo/branches/main")
            .apply { if (token.isNotBlank()) header("Authorization", "token $token") }
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IllegalStateException("Gitea ${response.code}: " + text.take(160))
            val commit = JSONObject(text).optJSONObject("commit") ?: JSONObject()
            return commit.optString("id") to commit.optJSONObject("commit")?.optString("message").orEmpty()
        }
    }

    private fun raw(host: String, repo: String, path: String, ref: String, token: String): ByteArray {
        val request = Request.Builder()
            .url(apiBase(host) + "/repos/$repo/raw/" + path + "?ref=" + ref)
            .apply { if (token.isNotBlank()) header("Authorization", "token $token") }
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("raw $path → HTTP ${response.code}")
            return response.body?.bytes() ?: ByteArray(0)
        }
    }

    /**
     * 拉一份更新到 /sdcard/Download/pi-pocket-update/，返回落点信息。
     * 只下载不应用 —— 应用由 Termux 侧脚本完成（见 [applyViaTermux]）。
     */
    fun download(context: Context, host: String, repo: String, token: String, ref: String?): Status {
        val (sha, message) = if (ref.isNullOrBlank()) latestCommit(host, repo, token) else ref to "(pinned)"
        val dir = UpdateBundle.directory(sha)
        val written = mutableListOf<String>()
        for (path in UpdateBundle.files) {
            val bytes = raw(host, repo, path, sha, token)
            // 文件名拍平（termux__skills__x__SKILL.md）：否则两个 SKILL.md 会互相覆盖
            val flat = UpdateBundle.flat(path)
            writeDownload(context, "$dir/$flat", bytes)
            written += flat
        }
        // 附带一个清单，方便排查（apply 脚本不强依赖它）
        writeDownload(context, "$dir/MANIFEST.txt", ("$sha\n$message\n" + written.joinToString("\n")).toByteArray())
        return Status(sha = sha, message = message, dir = "/sdcard/Download/$dir", files = written)
    }

    /** 让 Termux 执行落点目录里的 apply-update.sh（备份 → 覆盖 → 重启桥）。 */
    fun applyViaTermux(context: Context, dir: String) {
        val intent = Intent("com.termux.RUN_COMMAND").apply {
            setClassName("com.termux", "com.termux.app.RunCommandService")
            putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
            putExtra(
                "com.termux.RUN_COMMAND_ARGUMENTS",
                arrayOf(
                    "-lc",
                    UpdateBundle.command(dir) + "; " +
                        "cat apply.log > /sdcard/Download/pi-pocket-update/apply.log 2>/dev/null",
                ),
            )
            putExtra("com.termux.RUN_COMMAND_WORKDIR", dir)
            // 2 = 新建会话但不切前台（Android 10+ 不许后台切 Activity；跑完用户想看日志再去 /sdcard/…）
            putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "2")
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
        }
        context.startService(intent)
    }

    /** 读回 apply 日志（Termux 把结果写到落点目录）。 */
    fun readApplyLog(): String = runCatching {
        java.io.File("/sdcard/Download/pi-pocket-update/apply.log").readText().takeLast(2000)
    }.getOrDefault("(还没有 apply.log)")

    private fun writeDownload(context: Context, relative: String, bytes: ByteArray) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, relative.substringAfterLast('/'))
                put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + relative.substringBeforeLast('/') + "/")
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("写不进去：$relative")
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
        } else {
            val file = java.io.File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), relative)
            file.parentFile?.mkdirs()
            file.writeBytes(bytes)
        }
    }
}
