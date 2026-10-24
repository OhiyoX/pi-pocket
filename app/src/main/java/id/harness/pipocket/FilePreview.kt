package id.harness.pipocket

import org.json.JSONObject
import java.nio.charset.StandardCharsets

/**
 * 「查看交付」里**本地**生成预览数据（文件在另一台机器上、桥读不到时用）。
 *
 * 以前的做法是把远端字节写到 app 私有 cache，再把那个**手机本地路径**发给桥去读 ——
 * 桥当然拒绝（`拒绝：只允许 home 或工作目录下的路径（/data/user/0/…）`），
 * 用户截图里的那条报错就是这么来的。现在直接在 app 内分类/解码。
 *
 * 纯函数（不碰网络/磁盘），JVM 单测覆盖分类与截断口径。
 */
internal object FilePreview {
    const val MAX_TEXT_CHARS = 200_000
    const val MAX_IMAGE_BYTES = 8 * 1024 * 1024

    private val IMAGE_EXT = setOf(
        "png", "jpg", "jpeg", "webp", "gif", "bmp", "heic", "heif", "avif",
    )

    fun extOf(path: String): String = path.substringAfterLast('/', path).substringAfterLast('.', "").lowercase()

    fun isImagePath(path: String): Boolean = extOf(path) in IMAGE_EXT

    /** 前 8KB 里没有 NUL、且能按 UTF-8 解出来 ⇒ 当文本预览（二进制/PDF 之类不硬渲染）。 */
    fun looksLikeText(bytes: ByteArray): Boolean {
        val probe = minOf(bytes.size, 8192)
        for (i in 0 until probe) if (bytes[i] == 0.toByte()) return false
        return runCatching { StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)) }.isSuccess
    }

    /** 预览种类：`image` / `text` / `binary`。 */
    fun kindOf(path: String, bytes: ByteArray): String = when {
        bytes.isEmpty() -> "text"
        isImagePath(path) && bytes.size <= MAX_IMAGE_BYTES -> "image"
        looksLikeText(bytes) -> "text"
        else -> "binary"
    }

    /** 文本预览：UTF-8，超过 [MAX_TEXT_CHARS] 截断并标注。 */
    fun textOf(bytes: ByteArray): String {
        val text = String(bytes, StandardCharsets.UTF_8)
        return if (text.length <= MAX_TEXT_CHARS) text
        else text.substring(0, MAX_TEXT_CHARS) + "\n\n…（已截断，原文件 ${text.length} 字符）"
    }

    /**
     * 生成与桥 `/v1/file` 同形状的 JSON（`ok/kind/name/path/text|base64/size`），
     * 供 [id.harness.pipocket.ui.FileViewerDialog] 直接渲染。
     */
    fun viewJson(path: String, bytes: ByteArray, source: String = "remote"): JSONObject {
        val kind = kindOf(path, bytes)
        val name = path.trimEnd('/').substringAfterLast('/')
        return JSONObject()
            .put("ok", true)
            .put("path", path)
            .put("name", name)
            .put("size", bytes.size)
            .put("via", source)
            .put("kind", kind)
            .apply {
                when (kind) {
                    "image" -> put("base64", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP))
                    "text" -> put("text", textOf(bytes))
                    else -> put(
                        "text",
                        "这是二进制文件（约 ${(bytes.size / 1024.0).roundToInt()} KB），app 里不做预览。",
                    )
                }
            }
    }

    private fun Double.roundToInt(): Int = Math.round(this).toInt()
}
