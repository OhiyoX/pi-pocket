package id.harness.pipocket

import net.i2p.crypto.eddsa.EdDSASecurityProvider
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.Security
import java.security.PublicKey
import java.util.concurrent.TimeUnit

/** 一台可以 SSH 过去的机器（用来把桥拉起来 / app 内自建的文件连接）。 */
data class SshTarget(
    val host: String,
    val port: Int = 22,
    val user: String,
    val authKind: String = "password",   // password | key
    val password: String = "",
    val privateKey: String = "",          // **PEM 或 OpenSSH 新格式都行**（sshj 解析）
    val passphrase: String = "",
    val startCommand: String = DEFAULT_START,
) {
    val key: String get() = "$user@$host:$port"

    companion object {
        /** 2026-10-18 起被持久化过的旧默认命令：读取已存目标时迁移到当前默认，避免继续绕过启动器。 */
        const val LEGACY_DEFAULT_START =
            "command -v pi-pocket-bridge >/dev/null && { ss -lptn 2>/dev/null | grep -q ':8789' || (nohup pi-pocket-bridge >> ~/.pi/agent/pi-pocket-bridge.log 2>&1 &); } ; sleep 1; curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8789/v1/health"

        /**
         * 默认启动命令：经 `pi-pocket-start` 拉起（它读 `~/.pi/agent/pi-pocket.env` 里的 token/bind，
         * bind/auth 不匹配时会自己重启）。不直接起裸桥：那样会丢掉 token 与 bind，远端拉起来也连不上。
         * 显式 `sh`：启动器 shebang 是 Termux 路径，Ubuntu 等远端机器不能直接 exec。
         */
        const val DEFAULT_START =
            "mkdir -p \"\$HOME/.pi/agent\"; export PATH=\"\$HOME/.local/bin:\$PATH\"; " +
                "command -v pi-pocket-start >/dev/null || { echo 'pi-pocket-start not found; run termux/install.sh first' >&2; exit 127; }; " +
                "nohup sh \"\$(command -v pi-pocket-start)\" >> \"\$HOME/.pi/agent/pi-pocket-start.log\" 2>&1 & " +
                "i=0; code=000; " +
                "while [ \$i -lt 10 ]; do code=\$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8789/v1/health); [ \"\$code\" != 000 ] && break; i=\$((i+1)); sleep 0.5; done; " +
                "echo \"\$code\"; " +
                "[ \"\$code\" = 000 ] && { echo 'bridge did not become healthy; tail of pi-pocket-start.log:' >&2; tail -n 5 \"\$HOME/.pi/agent/pi-pocket-start.log\" 2>/dev/null >&2; exit 7; }; " +
                "exit 0"
    }
}

/** 解析已保存的 SSH 目标；旧的默认启动命令迁移到当前默认（自定义命令原样保留）。 */
internal fun parseSshTargets(raw: String?): List<SshTarget> {
    if (raw.isNullOrBlank()) return emptyList()
    return runCatching {
        org.json.JSONArray(raw).let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val stored = o.optString("startCommand")
                SshTarget(
                    host = o.optString("host"),
                    port = o.optInt("port", 22),
                    user = o.optString("user"),
                    authKind = o.optString("authKind", "password"),
                    password = o.optString("password"),
                    privateKey = o.optString("privateKey"),
                    passphrase = o.optString("passphrase"),
                    startCommand = if (stored.isBlank() || stored == SshTarget.LEGACY_DEFAULT_START) {
                        SshTarget.DEFAULT_START
                    } else {
                        stored
                    },
                )
            }
        }
    }.getOrDefault(emptyList())
}

/** 一次 SSH 执行的结果。 */
data class SshResult(val ok: Boolean, val output: String, val error: String? = null)

/**
 * SSH 连接工厂（[SshLauncher] / [SshShell] / [SftpClient] 共用）。
 *
 * 库 = **sshj**（2026-10-21 从 jsch 换过来）：jsch 只认 PEM，遇到 OpenSSH 新格式（ed25519）就 `Auth fail`；
 * sshj 原生支持新格式、ed25519（配 `net.i2p.crypto:eddsa` + BouncyCastle）、带口令私钥。
 */
object Ssh {
    @Volatile private var hostKeyPins: HostKeyPins? = null
    @Volatile private var privateDirectory: File? = null

    fun init(context: android.content.Context) {
        val directory = File(context.applicationContext.filesDir, "ssh")
        synchronized(this) {
            if (hostKeyPins == null) {
                privateDirectory = directory
                hostKeyPins = HostKeyPins(File(directory, "host-key-pins.properties"))
            }
        }
    }

    init {
        runCatching { Security.removeProvider("BC") }
        runCatching { Security.addProvider(BouncyCastleProvider()) }
        runCatching { Security.addProvider(EdDSASecurityProvider()) }
    }

    /** 建一条已认证的连接（调用方负责 `disconnect()`）。 */
    fun connect(target: SshTarget, timeoutMs: Int = 20000): SSHClient {
        val client = SSHClient()
        try {
            val pins = checkNotNull(hostKeyPins) { "SSH 尚未初始化" }
            client.addHostKeyVerifier(object : HostKeyVerifier {
                override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
                    check(pins.accept(target.host, target.port, key.encoded)) {
                        "SSH 主机密钥已变化，连接已拒绝。请核实远端机器和主机密钥后再处理已保存的信任记录。"
                    }
                    return true
                }
                override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()
            })
            client.connectTimeout = timeoutMs
            client.timeout = timeoutMs
            client.connect(target.host, target.port)
            if (target.authKind == "key") {
                if (target.user.isBlank()) throw IllegalStateException("需要用户名（私钥里没带）")
                client.authPublickey(target.user, keyProvider(client, target))
            } else {
                client.authPassword(target.user, target.password)
            }
            if (!client.isAuthenticated) throw IllegalStateException("认证失败（检查用户名 / 私钥 / 密码）")
            return client
        } catch (t: Throwable) {
            runCatching { client.disconnect() }
            throw t
        }
    }

    /**
     * 私钥文本 → [KeyProvider]。sshj 的 `loadKeys(path, passphrase)` 以文件路径为准，
     * 所以先落到 app 私有临时文件（仅本人可读），解析完立刻删。
     */
    fun keyProvider(client: SSHClient, target: SshTarget): KeyProvider {
        val text = target.privateKey.replace("\r\n", "\n").trim() + "\n"
        if (!text.contains("PRIVATE KEY")) {
            throw IllegalStateException("没有可用的私钥内容（请在连接设置里粘贴私钥，或改用密码）")
        }
        val dir = checkNotNull(privateDirectory) { "SSH 尚未初始化" }
        check(dir.isDirectory || dir.mkdirs()) { "无法创建 SSH 私有目录" }
        val f = File(dir, "id_" + System.nanoTime())
        return try {
            f.writeText(text)
            runCatching { f.setReadable(false, false); f.setReadable(true, true) }
            client.loadKeys(f.absolutePath, target.passphrase.ifBlank { null })
        } finally {
            runCatching { f.delete() }
        }
    }
}

/**
 * 通过 SSH 在远端机器上执行命令（用途：桥没起时把它拉起来，或查状态）。
 */
object SshLauncher {

    fun exec(target: SshTarget, command: String = target.startCommand, timeoutMs: Int = 25000): SshResult {
        var client: SSHClient? = null
        return try {
            client = Ssh.connect(target, timeoutMs)
            val session = client.startSession()
            try {
                val cmd = session.exec(command)
                val stdout = ByteArrayOutputStream()
                val stderr = ByteArrayOutputStream()
                val t1 = Thread { runCatching { cmd.inputStream.copyTo(stdout) } }.apply { isDaemon = true; start() }
                val t2 = Thread { runCatching { cmd.errorStream.copyTo(stderr) } }.apply { isDaemon = true; start() }
                cmd.join(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
                t1.join(1500)
                t2.join(1500)
                val exit = cmd.exitStatus ?: -1
                SshResult(
                    ok = exit == 0,
                    output = stdout.toString("UTF-8").trim(),
                    error = if (exit == 0) null else "exit=$exit " + stderr.toString("UTF-8").trim().take(400),
                )
            } finally {
                runCatching { session.close() }
            }
        } catch (e: Throwable) {
            SshResult(false, "", e.message ?: e::class.java.simpleName)
        } finally {
            runCatching { client?.disconnect() }
        }
    }
}

/**
 * 交互式 SSH shell（app 终端页「直接使用 ssh」用）。
 *
 * 一个长连接 + 一个 shell session；write() 把命令送进去，输出通过 onOutput 回调抛出来。
 * 用法与「桥的 bash」不同：**这条是真终端**（登录 shell、有 profile、能跑交互命令）。
 */
class SshShell(
    private val target: SshTarget,
    private val onOutput: (String) -> Unit,
    private val onClosed: (String?) -> Unit = {},
) {
    private var client: SSHClient? = null
    private var session: net.schmizz.sshj.connection.channel.direct.Session? = null
    @Volatile private var alive = false

    fun start() {
        val c = Ssh.connect(target, 20000)
        client = c
        val s = c.startSession()
        session = s
        s.allocatePTY("xterm-256color", 120, 40, 0, 0, emptyMap())
        s.startShell()
        alive = true
        Thread {
            val buf = ByteArray(4096)
            try {
                while (alive) {
                    val n = s.inputStream.read(buf)
                    if (n < 0) break
                    onOutput(String(buf, 0, n, Charsets.UTF_8))
                }
            } catch (t: Throwable) {
                if (alive) onClosed(t.message)
            } finally {
                alive = false
                onClosed(null)
            }
        }.apply { isDaemon = true; start() }
    }

    fun write(command: String) {
        val out = session?.outputStream ?: return
        runCatching {
            out.write((command + "\n").toByteArray())
            out.flush()
        }
    }

    fun close() {
        alive = false
        runCatching { session?.close() }
        runCatching { client?.disconnect() }
    }
}
