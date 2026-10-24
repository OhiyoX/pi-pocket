package id.harness.pipocket

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale
import java.util.Properties

/** Trust on first use: a host/port may acquire one key, never silently replace it. */
internal class HostKeyPins(private val file: File) {
    @Synchronized fun accept(host: String, port: Int, encodedKey: ByteArray): Boolean {
        val identity = "${host.trim().lowercase(Locale.ROOT)}:$port"
        val id = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val key = Base64.getEncoder().encodeToString(encodedKey)
        val pins = Properties()
        if (file.exists()) file.inputStream().use { pins.load(it) }
        pins.getProperty(id)?.let { return it == key }
        pins.setProperty(id, key)
        check(file.parentFile?.let { it.isDirectory || it.mkdirs() } == true) { "无法创建 SSH 主机密钥目录" }
        val pending = File.createTempFile("host-keys-", ".tmp", file.parentFile)
        try {
            pending.outputStream().use { pins.store(it, "SSH host key pins") }
            Files.move(pending.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            pending.delete()
        }
        return true
    }
}
