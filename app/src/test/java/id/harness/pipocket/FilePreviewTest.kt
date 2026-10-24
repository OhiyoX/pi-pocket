package id.harness.pipocket

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「查看交付」的本地预览分类：
 * 用户 2026-10-24 截图里两条失败（`/tmp/…log` 被桥拒、`README.md.` sh 报错）都绕这里走。
 */
class FilePreviewTest {

    private fun bytes(vararg values: Int) = values.map { it.toByte() }.toByteArray()

    @Test
    fun imageExtensionsAreRecognised() {
        listOf("/a/b.png", "/a/b.JPG", "/a/b.webp", "/a/b.HEIC").forEach {
            assertTrue(it, FilePreview.isImagePath(it))
        }
        listOf("/a/b.md", "/a/b.log", "/a/b").forEach {
            assertFalse(it, FilePreview.isImagePath(it))
        }
    }

    @Test
    fun textAndBinaryAreDistinguishedByNulBytes() {
        val text = "hello 世界\n".toByteArray()
        assertTrue(FilePreview.looksLikeText(text))
        assertEquals("text", FilePreview.kindOf("/tmp/a.log", text))

        val binary = bytes(0x89, 0x50, 0x4E, 0x47, 0x00, 0x1A)
        assertFalse(FilePreview.looksLikeText(binary))
        assertEquals("binary", FilePreview.kindOf("/tmp/a.bin", binary))

        // 扩展名像图片但超过上限 ⇒ 不当图片硬渲染
        val huge = ByteArray(FilePreview.MAX_IMAGE_BYTES + 1)
        assertEquals("binary", FilePreview.kindOf("/tmp/a.png", huge))
    }

    @Test
    fun emptyFileStaysText() {
        assertEquals("text", FilePreview.kindOf("/tmp/empty.log", ByteArray(0)))
        assertEquals("", FilePreview.textOf(ByteArray(0)))
    }

    @Test
    fun longTextIsTruncatedWithNotice() {
        val long = "x".repeat(FilePreview.MAX_TEXT_CHARS + 50)
        val out = FilePreview.textOf(long.toByteArray())
        assertTrue(out.startsWith("x".repeat(FilePreview.MAX_TEXT_CHARS)))
        assertTrue(out.contains("已截断"))
    }

    @Test
    fun viewJsonCarriesTheFieldsFileViewerNeeds() {
        val view = FilePreview.viewJson("/home/u/notes.md", "# hi\n".toByteArray())
        assertTrue(view.getBoolean("ok"))
        assertEquals("text", view.getString("kind"))
        assertEquals("notes.md", view.getString("name"))
        assertEquals("/home/u/notes.md", view.getString("path"))
        assertEquals("# hi\n", view.getString("text"))
        assertEquals("remote", view.getString("via"))
        assertEquals(5, view.getInt("size"))
    }

    @Test
    fun binaryPreviewDoesNotPretendToRender() {
        val view = FilePreview.viewJson("/tmp/x.bin", bytes(0x00, 0x01, 0x02))
        assertEquals("binary", view.getString("kind"))
        assertTrue(view.getString("text").contains("二进制文件"))
        assertFalse(view.has("base64"))
    }
}
