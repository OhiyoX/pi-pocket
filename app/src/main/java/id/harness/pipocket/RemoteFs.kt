package id.harness.pipocket

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/**
 * 远端文件（SFTP 语义）——**走 Termux 自己的 `~/.ssh/config`**，app 不再持有任何 SSH 凭据。
 *
 * 为什么改成这样（用户 2026-10-21 口径「连接设置直接走 termux 的 config」）：
 * · Termux 里已经有 `~/.ssh/config`（别名、IdentityFile、agent、known_hosts），机器上已能 `ssh host-a` 免密；
 * · app 是另一个 uid，读不到 Termux 的 .ssh 目录，硬塞一套就变成"两份凭据、两份真相"；
 * · 而且 Termux 的常规密钥是 OpenSSH 新格式（ed25519），jsch 根本不支持（只能 PEM），非常别扭。
 *
 * 所以：**桥在 Termux 里 spawn `ssh <alias> -- <cmd>`**，app 只发「别名 + 路径」。
 * 别名 `local` / `termux` 表示本机（不经过 ssh）。
 */
data class FsHost(
    val alias: String,
    val label: String,
    val hostName: String = "",
    val user: String = "",
) {
    val isLocal: Boolean get() = alias == "local" || alias == "termux"
}

object RemoteFs {

    private fun body(alias: String, op: String, path: String = ""): JSONObject = JSONObject()
        .put("target", alias)
        .put("op", op)
        .put("path", path)

    /** `~/.ssh/config` 里的 Host 别名（首个永远是 `local`）。 */
    fun hosts(api: PiClient): List<FsHost> {
        val arr = api.getJson("/v1/ssh/hosts").optJSONArray("hosts") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val alias = o.optString("alias").ifBlank { return@mapNotNull null }
            FsHost(
                alias = alias,
                label = o.optString("label").ifBlank { alias },
                hostName = o.optString("hostName"),
                user = o.optString("user"),
            )
        }
    }

    fun home(api: PiClient, alias: String): String =
        api.postJson("/v1/fs", body(alias, "home"), 25).optString("home").ifBlank { "/" }

    fun list(api: PiClient, alias: String, path: String): List<SftpEntry> {
        val arr = api.postJson("/v1/fs", body(alias, "list", path), 60).optJSONArray("entries") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            SftpEntry(
                name = o.optString("name"),
                path = o.optString("path"),
                isDir = o.optBoolean("isDir"),
                size = o.optLong("size"),
                mtimeSec = o.optLong("mtime") / 1000,
            )
        }
    }

    fun read(api: PiClient, alias: String, path: String): ByteArray {
        val b64 = api.postJson("/v1/fs", body(alias, "read", path), 120).optString("base64")
        return Base64.decode(b64, Base64.DEFAULT)
    }

    fun write(api: PiClient, alias: String, path: String, bytes: ByteArray) {
        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        api.postJson("/v1/fs", body(alias, "write", path).put("base64", b64), 180)
    }

    fun mkdir(api: PiClient, alias: String, path: String) {
        api.postJson("/v1/fs", body(alias, "mkdir", path), 30)
    }

    fun remove(api: PiClient, alias: String, path: String) {
        api.postJson("/v1/fs", body(alias, "remove", path), 60)
    }

    fun rename(api: PiClient, alias: String, from: String, to: String) {
        api.postJson("/v1/fs", body(alias, "rename", from).put("to", to), 30)
    }
}
