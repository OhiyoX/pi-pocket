package id.harness.pipocket

import id.harness.pipocket.ui.ToolImageEntry
import id.harness.pipocket.ui.firstAbsolutePath
import id.harness.pipocket.ui.isImagePath
import id.harness.pipocket.ui.toolImageEntry
import id.harness.pipocket.ui.toolTargetPath
import id.harness.pipocket.ui.viewablePath
import id.harness.pipocket.ui.viewActionLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「工具卡上点开看」的路径判定。
 *
 * 用户口径（2026-10-22）：**`read` 读到的内容，不管文本还是图像，用户都要能看**。
 * 以前只有图片有入口，而且是拿结果文本正则猜的。
 */
class ToolPathsTest {

    @Test
    fun readPathComesFromArguments() {
        // pi 的 read 参数是 {path|file_path, offset, limit}
        assertEquals("/a/b.txt", toolTargetPath("read", """{"path":"/a/b.txt"}"""))
        assertEquals("/a/b.txt", toolTargetPath("read", """{"file_path":"/a/b.txt"}"""))
        // 相对路径也要能用（走桥的 /v1/file 会按桥的 cwd 解析）
        assertEquals("ui/Mermaid.kt", toolTargetPath("read", """{"path":"ui/Mermaid.kt"}"""))
        // offset/limit 不影响取路径
        assertEquals("/a/b.txt", toolTargetPath("read", """{"path":"/a/b.txt","offset":10,"limit":20}"""))
    }

    @Test
    fun otherPathToolsAlsoRecognized() {
        assertEquals("/tmp/x", toolTargetPath("write", """{"path":"/tmp/x"}"""))
        assertEquals("/tmp/x", toolTargetPath("edit", """{"file_path":"/tmp/x"}"""))
        assertEquals("/src", toolTargetPath("ls", """{"path":"/src"}"""))
        assertEquals("src", toolTargetPath("grep", """{"pattern":"TODO","path":"src"}"""))
    }

    @Test
    fun nonPathToolsReturnNull() {
        assertNull(toolTargetPath("bash", """{"command":"ls -al"}"""))
        assertNull(toolTargetPath("read", null))
        assertNull(toolTargetPath("read", "not json"))
        // 路径字段存在但是空串/ null ⇒ 不算
        assertNull(toolTargetPath("read", """{"path":""}"""))
        assertNull(toolTargetPath("read", """{"path":null}"""))
    }

    @Test
    fun readTextFileGetsViewableEntry() {
        // 这条就是以前的 bug：read 读文本时没有任何可点入口
        val path = viewablePath(
            toolName = "read",
            argsJson = """{"path":"/home/me/notes.md"}""",
            resultText = "1→# 标题\n2→正文…",
        )
        assertEquals("/home/me/notes.md", path)
        assertEquals("查看内容", viewActionLabel(path!!))
    }

    @Test
    fun readImageGetsImageLabel() {
        val path = viewablePath("read", """{"path":"/tmp/shot.png"}""", "read image: /tmp/shot.png (96x96)")
        assertEquals("/tmp/shot.png", path)
        assertEquals("查看图片", viewActionLabel(path!!))
    }

    /**
     * 用户 2026-10-24 真机：read 一张图后卡片上什么都没有可点。
     *
     * 真因：`hasDetail` 取的是**结果文本**，而 read 图片在 pi 的收起渲染里正文是空的、
     * 实时事件里也可能只剩一行占位 ⇒ 入口被“没内容”这个条件挡掉。
     * 所以：**只要 read 的参数路径是图**，就算结果文本空也要给出入口。
     */
    @Test
    fun readImageWithEmptyResultStillGetsEntry() {
        val path = viewablePath("read", """{"path":"~/projects/scratch/zone4.png"}""", "")
        assertEquals("~/projects/scratch/zone4.png", path)
        assertEquals("查看图片", viewActionLabel(path!!))
    }

    /**
     * 用户 2026-10-24（issue #40）：**图片不要在 toolcall 里直接显示** —— 但得能点开看。
     *
     * 以前 `toolImages` 非空就排 96dp 缩略图（`ToolImages`），链接分支永远轮不到；真桥实时事件带图数据时必现。
     * 现在只产出「一行入口」：有路径 ⇒ Path，只有数据 ⇒ Data，**没有第三种（内联）**。
     */
    @Test
    fun toolImagesNeverRenderInline() {
        // 有图数据 + 认得出路径（read 一张图）：仍然只给 Path 入口，不画缩略图
        val entry = toolImageEntry(
            openable = "/tmp/shot.png",
            imageCount = 2,
            running = false,
            showPath = true,
        )
        assertTrue("应当走 Path 入口，不能内联：$entry", entry is ToolImageEntry.Path)
        assertEquals("/tmp/shot.png", (entry as ToolImageEntry.Path).path)

        // 有图数据但认不出路径（扩展工具直接回图）：给 Data 入口（开全屏查看器），也不是内联
        assertEquals(ToolImageEntry.Data(3), toolImageEntry(openable = null, imageCount = 3, running = false, showPath = false))

        // 跑的时候不给入口；只读文本又没有图 ⇒ 什么都不画
        assertEquals(ToolImageEntry.None, toolImageEntry("/tmp/x.md", 0, running = true, showPath = true))
        assertEquals(ToolImageEntry.None, toolImageEntry(null, 0, running = false, showPath = true))
        // read 图时 hasDetail 可能是 false，但只要认得出路径就要入口（showPath 由调用方算）
        assertEquals(ToolImageEntry.Path("/tmp/a.png"), toolImageEntry("/tmp/a.png", 0, running = false, showPath = true))
    }

    @Test
    fun fallsBackToPathInsideResultText() {
        // bash 这类没路径参数的工具：结果里有绝对路径就给入口（终端 footer 里的 log: 路径）
        val path = viewablePath("bash", """{"command":"make"}""", "log: /tmp/build.log\nexit_code=0")
        assertEquals("/tmp/build.log", path)
        assertEquals("查看内容", viewActionLabel(path!!))
        // 目录结尾的标签不一样（桥的 /v1/file 会回 dir）
        assertEquals("查看目录", viewActionLabel("/tmp/build/"))
    }

    @Test
    fun noPathNoEntry() {
        assertNull(viewablePath("bash", """{"command":"echo hi"}""", "hi\n"))
        assertNull(viewablePath("bash", null, ""))
    }

    @Test
    fun imageExtensionsAreRecognized() {
        assertTrue(isImagePath("/a.png"))
        assertTrue(isImagePath("/a.JPG"))
        assertTrue(isImagePath("/a.jpeg"))
        assertTrue(isImagePath("/a.webp"))
        assertTrue(isImagePath("/a.svg"))
        // 大小写与 query 都不影响
        assertTrue(isImagePath("/a.PNG?v=1"))
        assertNull(null)
        assertTrue(!isImagePath("/a.md"))
        assertTrue(!isImagePath("/a.png.txt"))
    }

    @Test
    fun firstAbsolutePathHandlesTildeAndRelative() {
        assertEquals("/tmp/a.log", firstAbsolutePath("see /tmp/a.log for details"))
        assertEquals("~/projects/x.log", firstAbsolutePath("log: ~/projects/x.log"))
        // 相对路径不认（桥解析不了，会指错文件）
        assertNull(firstAbsolutePath("see tmp/a.log"))
        assertNull(firstAbsolutePath(""))
    }

    @Test
    fun firstAbsolutePathStripsTrailingPunctuation() {
        // 用户 2026-10-24 截图：路径带句号 → sh: cannot open …README.md.
        assertEquals(
            "/home/user/.openclaw/workspace/main/projects/openclaw-maintenance/README.md",
            firstAbsolutePath("打开 /home/user/.openclaw/workspace/main/projects/openclaw-maintenance/README.md. 看看"),
        )
        assertEquals("/tmp/a.log", firstAbsolutePath("见 /tmp/a.log, 里面"))
        assertEquals("/tmp/a.log", firstAbsolutePath("（见 /tmp/a.log）"))
        assertEquals("/tmp/a.log", firstAbsolutePath("打开 /tmp/a.log。"))
        assertEquals("/tmp/a.log", firstAbsolutePath("看 /tmp/a.log；然后"))
    }
}
