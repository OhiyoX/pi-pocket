package id.harness.pipocket

import id.harness.pipocket.ui.ChatRow
import id.harness.pipocket.ui.buildChatRows
import id.harness.pipocket.ui.compactionInsertIndex
import id.harness.pipocket.ui.keyTimestamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 压缩卡在聊天列表里的位置。
 *
 * 回归（用户 2026-10-22）：「上下文已压缩一直贴底显示」—— 卡片当初无条件挂在 LazyColumn 末尾，
 * 压缩之后新来的消息全排在它**上面**，于是它永远停在列表底部。现在按时间线插。
 */
class ChatRowsTest {

    private val t0 = 1_759_000_000_000L

    private fun msg(key: String) = ChatItem(key = key, role = "user")

    private fun card(afterKey: String) =
        CompactionCardInfo(title = "上下文已压缩", detail = "tokens：1 → 2", afterKey = afterKey)

    @Test
    fun noCardIsJustMessages() {
        val rows = buildChatRows(listOf(msg("user-$t0-0"), msg("assistant-$t0-abc")), null)
        assertEquals(2, rows.size)
        assertTrue(rows.all { it is ChatRow.Message })
        assertEquals(listOf("m-user-$t0-0", "m-assistant-$t0-abc"), rows.map { it.key })
    }

    @Test
    fun cardGoesRightAfterItsAnchorNotAtTheEnd() {
        val items = listOf(msg("user-$t0-0"), msg("assistant-$t0-abc"), msg("user-${t0 + 1000}-1"))
        // 卡是在第一条 assistant 之后建的
        val rows = buildChatRows(items, card(afterKey = "assistant-$t0-abc"))
        assertEquals(
            listOf("m-user-$t0-0", "m-assistant-$t0-abc", "compaction-card", "m-user-${t0 + 1000}-1"),
            rows.map { it.key },
        )
    }

    @Test
    fun laterMessagesPushTheCardUp() {
        // 回归本体：建卡后新消息要排在卡**下面**（卡不再贴底）
        val items = listOf(
            msg("user-$t0-0"),
            msg("assistant-$t0-abc"),
            msg("user-${t0 + 5000}-1"),
            msg("assistant-${t0 + 5000}-def"),
        )
        val rows = buildChatRows(items, card(afterKey = "assistant-$t0-abc"))
        val cardAt = rows.indexOfFirst { it is ChatRow.Compaction }
        val lastMessageAt = rows.indexOfLast { it is ChatRow.Message }
        assertTrue("卡片必须在旧消息后、所有新消息前", cardAt in 1 until lastMessageAt)
        assertEquals(2, cardAt)
    }

    @Test
    fun anchorSurvivesReloadWhenIndicesChanged() {
        // 压缩会重写历史：同一条消息的下标可能变（key 变了），但时间戳不变 ⇒ 仍能定位
        val items = listOf(
            msg("user-${t0 + 1000}-0"),
            msg("assistant-${t0 + 1000}-xyz"),
            msg("user-${t0 + 9000}-1"),
        )
        // 锚点是压缩前那条 assistant（下标变了、时间戳还在）
        val rows = buildChatRows(items, card(afterKey = "assistant-${t0 + 1000}-old-hash"))
        assertEquals(
            listOf("m-user-${t0 + 1000}-0", "m-assistant-${t0 + 1000}-xyz", "compaction-card", "m-user-${t0 + 9000}-1"),
            rows.map { it.key },
        )
    }

    @Test
    fun missingAnchorFallsBackToTimeline() {
        // 连时间戳都不在了 ⇒ 插在第一条更新的消息之前
        val items = listOf(msg("user-${t0 + 5000}-0"), msg("user-${t0 + 6000}-1"))
        assertEquals(0, compactionInsertIndex(items, "assistant-$t0-gone"))
        // 都比锚点旧 ⇒ 挂末尾
        assertEquals(2, compactionInsertIndex(items, "assistant-${t0 + 99_000}-gone"))
    }

    @Test
    fun emptyListPutsTheCardAlone() {
        val rows = buildChatRows(emptyList(), card(afterKey = ""))
        assertEquals(1, rows.size)
        assertTrue(rows[0] is ChatRow.Compaction)
    }

    @Test
    fun timestampIsParsedFromPiKeys() {
        assertEquals(t0, keyTimestamp("user-$t0-0"))
        assertEquals(t0, keyTimestamp("assistant-$t0-9f2c1a"))
        assertEquals(t0, keyTimestamp("tool-$t0-3-1"))
        assertEquals(null, keyTimestamp("notice-abc"))
        assertEquals(null, keyTimestamp(""))
    }
}
