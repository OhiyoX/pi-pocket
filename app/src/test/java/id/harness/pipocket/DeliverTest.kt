package id.harness.pipocket

import id.harness.pipocket.ui.copyLastAnswerOutcome
import id.harness.pipocket.ui.exportPathFor
import id.harness.pipocket.ui.exportSummary
import id.harness.pipocket.ui.humanBytes
import id.harness.pipocket.ui.shortenHome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 交付类动作（导出会话 / 复制最后回答）。
 *
 * 对应 pi 终端：`/export` → RPC `export_html`（`core/export-html/index.ts:274`），
 * `/copy` → `get_last_assistant_text`（`core/agent-session.ts:4377`，已经跳过中断且无内容的消息）。
 */
class DeliverTest {

    @Test
    fun humanBytesUsesReadableUnits() {
        assertEquals("512 B", humanBytes(512))
        assertEquals("1.0 KB", humanBytes(1024))
        assertEquals("312.4 KB", humanBytes(319_898))
        assertEquals("1.5 MB", humanBytes(1_572_864))
    }

    @Test
    fun summaryShowsHomeAsTilde() {
        // 手机上长路径一行放不下，终端也把 home 缩成 ~
        assertEquals(
            "~/workspace/exports/pi-session-a.html · 1.0 KB",
            exportSummary("/home/me/workspace/exports/pi-session-a.html", 1024, home = "/home/me"),
        )
    }

    @Test
    fun summaryWithoutSizeIsJustPath() {
        assertEquals("/tmp/x.html", exportSummary("/tmp/x.html", null))
        assertEquals("/tmp/x.html", exportSummary("/tmp/x.html", 0))
    }

    @Test
    fun shortenHomeLeavesForeignPathsAlone() {
        assertEquals("/tmp/x.html", shortenHome("/tmp/x.html", "/home/me"))
        assertEquals("/tmp/x.html", shortenHome("/tmp/x.html", null))
    }

    @Test
    fun copyOutcomeReportsCharsAndFirstLine() {
        val out = copyLastAnswerOutcome("第一行答案\n第二行细节")
        assertTrue(out.ok)
        assertTrue(out.message, out.message.contains("已复制"))
        assertTrue("要说清多少字", out.message.contains("11"))
        assertTrue("要能看到第一行", out.message.contains("第一行答案"))
        // 第一行不该把换行带进提示
        assertFalse(out.message.contains("\n"))
    }

    @Test
    fun copyOutcomeTruncatesLongFirstLine() {
        val long = "这是很长很长很长很长很长很长很长很长很长很长很长很长很长很长很长很长很长的一行"
        val out = copyLastAnswerOutcome(long)
        assertTrue(out.ok)
        assertTrue(out.message, out.message.endsWith("…"))
        assertTrue("提示不能太长", out.message.length < 80)
    }

    @Test
    fun copyOutcomeHandlesEmptyAnswer() {
        assertFalse(copyLastAnswerOutcome("").ok)
        assertFalse(copyLastAnswerOutcome("   \n\n ").ok)
        assertEquals("还没有可复制的回答", copyLastAnswerOutcome("").message)
    }

    @Test
    fun exportPathUsesSessionNameAndSafeChars() {
        val p = exportPathFor(
            sessionFile = "/home/me/.pi/agent/sessions/x/2026-10-22T00-00-00_abc.jsonl",
            sessionName = "修 mermaid / 字号: 对齐",
            home = "/home/me",
        )
        assertEquals("/home/me/workspace/exports/pi-session-修-mermaid-字号-对齐.html", p)
    }

    @Test
    fun exportPathFallsBackToSessionId() {
        val p = exportPathFor(
            sessionFile = "/home/me/.pi/agent/sessions/x/2026-10-22T00-00-00_abc.jsonl",
            sessionName = null,
            home = "/home/me",
        )
        assertEquals("/home/me/workspace/exports/pi-session-2026-10-22T00-00-00_abc.html", p)
    }

    @Test
    fun exportPathNeverProducesEmptyName() {
        val p = exportPathFor(sessionFile = "/x/.jsonl", sessionName = "  ", home = "/home/me")
        assertTrue(p, p.startsWith("/home/me/workspace/exports/pi-session-"))
        assertTrue(p.endsWith(".html"))
    }
}
