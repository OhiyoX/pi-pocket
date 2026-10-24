package id.harness.pipocket

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「没发出去的消息」的持久化 + 恢复口径（用户 2026-10-23）。
 *
 * 这些断言钉的是**会丢数据的地方**：编码必须能扛住换行/中文/引号，
 * 坏数据不能把 app 带崩，恢复时不能把用户正在写的东西覆盖掉。
 */
class OutboxTest {

    @Test
    fun encodeDecodeRoundTripKeepsText() {
        val text = "第一行\n第二行 \"带引号\" & = 等号\n\t制表符 + emoji 🚀"
        val d = PendingDraft(text = text, file = "/work/a.jsonl", reason = "连接失败", ts = 1_700_000_000_000L, hadImages = 2)
        val back = Outbox.decode(Outbox.encode(d))
        assertEquals(text, back?.text)
        assertEquals("/work/a.jsonl", back?.file)
        assertEquals("连接失败", back?.reason)
        assertEquals(1_700_000_000_000L, back?.ts)
        assertEquals(2, back?.hadImages)
    }

    @Test
    fun decodeRejectsGarbageAndBlank() {
        assertNull(Outbox.decode(null))
        assertNull(Outbox.decode(""))
        assertNull(Outbox.decode("   "))
        assertNull(Outbox.decode("not-a-v1-header"))
        // 版本号对但正文空：算没有（空草稿不值得提示）
        assertNull(Outbox.decode("v1\ntext=\nfile=\nreason=\nts=0\nimages=0"))
        // 只有 text 的"半截"数据：能解出文本就保留（丢字段不影响救回内容），但不能抛
        assertEquals("你", Outbox.decode("v1\ntext=%E4%BD%A0")?.text)
    }

    @Test
    fun previewFlattensAndTruncates() {
        assertEquals("第一行 第二行", Outbox.preview("第一行\n\n第二行"))
        val long = "a".repeat(200)
        val p = Outbox.preview(long, max = 20)
        assertTrue(p.endsWith("…"))
        assertEquals(21, p.length)
    }

    @Test
    fun mergeNeverDropsWhatUserIsTyping() {
        // 输入框空 → 直接把草稿放回去
        assertEquals("草稿", Outbox.merge("", "草稿"))
        // 正在写 → 草稿在前、正在写的接在后面
        assertEquals("草稿\n\n半句", Outbox.merge("半句", "草稿"))
        // 草稿空 → 不动当前内容
        assertEquals("半句", Outbox.merge("半句", ""))
        assertEquals("", Outbox.merge("", ""))
    }

    @Test
    fun matchesIsScopedToSessionButToleratesUnknownFile() {
        val d = PendingDraft("x", file = "/a.jsonl")
        assertTrue(Outbox.matches(d, "/a.jsonl"))
        assertFalse(Outbox.matches(d, "/b.jsonl"))
        assertFalse(Outbox.matches(d, null))
        // 不知道属于哪条会话（老数据/上下文没了）→ 哪条会话都提示，总比丢掉好
        assertTrue(Outbox.matches(PendingDraft("x", file = null), "/b.jsonl"))
        assertFalse(Outbox.matches(null, "/b.jsonl"))
    }
}
