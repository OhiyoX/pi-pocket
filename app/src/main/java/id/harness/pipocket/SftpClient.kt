package id.harness.pipocket

import net.schmizz.sshj.sftp.RemoteResourceInfo
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.xfer.FileSystemFile
import java.io.File
import java.io.InputStream

/** 一个远端目录项（SFTP）。 */
data class SftpEntry(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val size: Long,
    val mtimeSec: Long,
)

/**
 * SFTP（App 内自建连接用；Termux 别名走桥的 `/v1/fs`，见 [RemoteFs]）。
 *
 * **库选型（2026-10-21 用户：「jsch 不认 OpenSSH 新格式？那你要用先进的新库」）**：
 * 换成 [sshj]（`com.hierynomus:sshj`），原因：
 * · jsch 只吃 PEM（PKCS#1/PKCS#8），`ssh-keygen` 默认产出的 **OpenSSH 新格式**（`BEGIN OPENSSH PRIVATE KEY`，
 *   ed25519/ECDSA）直接 `Auth fail` —— 这是 Termux / 现代发行版的常态；
 * · sshj 原生支持 OpenSSH 新格式 + ed25519（需要 `net.i2p.crypto:eddsa` + BouncyCastle，已在 build.gradle 声明），
 *   也支持带口令的私钥、ssh-agent、known_hosts。
 *
 * 主机指纹由共用的 [Ssh] 连接工厂校验。
 *
 * 线程约定：全是**阻塞调用**，调用方必须在 `Dispatchers.IO` 里跑。
 */
object SftpClient {

    private fun <T> withSftp(target: SshTarget, block: (SFTPClient) -> T): T {
        val client = Ssh.connect(target)
        try {
            val sftp = client.newSFTPClient()
            try {
                return block(sftp)
            } finally {
                runCatching { sftp.close() }
            }
        } finally {
            runCatching { client.disconnect() }
        }
    }

    /** 列目录（不含 `.` / `..`；目录在前、名字排序）。 */
    fun list(target: SshTarget, path: String): List<SftpEntry> = withSftp(target) { sftp ->
        sftp.ls(path).mapNotNull { info: RemoteResourceInfo ->
            val name = info.name
            if (name == "." || name == "..") return@mapNotNull null
            val attrs = info.attributes
            SftpEntry(
                name = name,
                path = if (path.endsWith("/")) path + name else "$path/$name",
                isDir = attrs.type == net.schmizz.sshj.sftp.FileMode.Type.DIRECTORY,
                size = attrs.size,
                mtimeSec = attrs.mtime,
            )
        }.sortedWith(compareByDescending<SftpEntry> { it.isDir }.thenBy { it.name.lowercase() })
    }

    /** 读一个小文件（文本/图片预览用；超过 [maxBytes] 直接报错，别把手机内存打满）。 */
    fun readBytes(target: SshTarget, path: String, maxBytes: Int = 4 * 1024 * 1024): ByteArray =
        withSftp(target) { sftp ->
            val remote = sftp.open(path)
            remote.use { h ->
                readBoundedRemote(maxBytes) { offset, buffer, length -> h.read(offset, buffer, 0, length) }
            }
        }

    /** 上传（覆盖）。 */
    fun writeBytes(target: SshTarget, path: String, input: InputStream) = withSftp(target) { sftp ->
        val dir = File(System.getProperty("java.io.tmpdir") ?: "/data/local/tmp", "pp-ssh")
        dir.mkdirs()
        val tmp = File(dir, "up_" + System.nanoTime())
        try {
            tmp.outputStream().use { o -> input.copyTo(o) }
            sftp.put(FileSystemFile(tmp), path)
        } finally {
            runCatching { tmp.delete() }
        }
    }

    fun mkdir(target: SshTarget, path: String) = withSftp(target) { sftp -> sftp.mkdirs(path) }

    fun remove(target: SshTarget, path: String, isDir: Boolean) = withSftp(target) { sftp ->
        if (isDir) sftp.rmdir(path) else sftp.rm(path)
    }

    fun rename(target: SshTarget, from: String, to: String) = withSftp(target) { sftp -> sftp.rename(from, to) }

    /** 当前登录用户的 home（首次打开定位用）。 */
    fun home(target: SshTarget): String = withSftp(target) { sftp -> sftp.canonicalize(".") }
}
