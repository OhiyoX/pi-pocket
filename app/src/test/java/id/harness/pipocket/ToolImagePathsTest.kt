package id.harness.pipocket

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `read` 图片后「图看不到」的两条补图路径（用户 2026-10-24 真机：
 * 卡片上只有 `Read image file [image/png]` + 一行占位，图打不开）。
 *
 * 1. 结果里带 base64 → 直接当图；
 * 2. 结果里没数据（桥给历史剥掉了 omitted:true）→ 靠入参 path 走 `/v1/file` 补。
 */
class ToolImagePathsTest {

    @Test
    fun `带前缀且有 path 才算图片读取`() {
        assertEquals(
            listOf("~/projects/scenario/zone4.png"),
            MessageParser.readImagePaths("Read image file [image/png]\n[Image: 720x1600]", "~/projects/scenario/zone4.png"),
        )
    }

    @Test
    fun `前面有空白也认`() {
        assertEquals(listOf("/tmp/a.png"), MessageParser.readImagePaths("\n  Read image file [image/webp]", "/tmp/a.png"))
    }

    @Test
    fun `文本文件不误伤`() {
        assertTrue(MessageParser.readImagePaths("1\tpackage foo\n2\t...", "/tmp/A.kt").isEmpty())
    }

    @Test
    fun `前缀只认开头——正文里提到这句话不算`() {
        assertTrue(
            MessageParser.readImagePaths("grep 结果：Read image file [image/png]", "/tmp/a.png").isEmpty(),
        )
    }

    @Test
    fun `没有入参 path 就没法补图`() {
        assertTrue(MessageParser.readImagePaths("Read image file [image/png]", null).isEmpty())
        assertTrue(MessageParser.readImagePaths("Read image file [image/png]", "   ").isEmpty())
    }

    @Test
    fun `结果里真的带图就直接取 base64`() {
        val content = JSONArray()
            .put(JSONObject().put("type", "text").put("text", "Read image file [image/png]"))
            .put(JSONObject().put("type", "image").put("mimeType", "image/png").put("data", "AAAA"))
            .put(JSONObject().put("type", "image").put("mimeType", "image/png").put("data", ""))
        val images = MessageParser.imagesOfToolResult(content)
        assertEquals(1, images.size)                      // 空 data 的不算
        assertEquals("image/png", images[0].mimeType)
        assertEquals("AAAA", images[0].base64)
    }

    @Test
    fun `桥剥掉的图（omitted）不会当成可用图`() {
        val content = JSONArray().put(
            JSONObject().put("type", "image").put("mimeType", "image/png").put("omitted", true).put("bytes", 12345),
        )
        assertTrue(MessageParser.imagesOfToolResult(content).isEmpty())
    }

    @Test
    fun `工具行会带上补图路径`() {
        val result = JSONObject().put("content", JSONArray().put(
            JSONObject().put("type", "text").put("text", "Read image file [image/png]"),
        ))
        val item = MessageParser.toolItem(
            id = "t1",
            name = "read",
            args = JSONObject().put("path", "/home/x/a.png"),
            result = result,
            fallbackKey = "k1",
        )
        assertEquals(listOf("/home/x/a.png"), item.toolImagePaths)
        assertTrue(item.toolImages.isEmpty())
    }
}
