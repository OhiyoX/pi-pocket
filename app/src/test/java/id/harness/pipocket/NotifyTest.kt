package id.harness.pipocket

import id.harness.pipocket.ui.notifyIdFor
import id.harness.pipocket.ui.notifyTitle
import id.harness.pipocket.ui.isTurnEndNotice
import id.harness.pipocket.ui.turnEndNoticeAllowed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「会话结束发通知」（用户 2026-10-23；开关口径 2026-10-24）。
 *
 * 通知的两个隐蔽点都在这里钉住：
 *  · **同一会话只占一条**（连续几轮结束不该叠成三条）；
 *  · **不同会话不能撞 id**（撞了点通知会跳到别人的会话）。
 */
class NotifyTest {

    @Test
    fun `同一条会话的通知 id 稳定`() {
        val a = "/storage/emulated/0/.pi/sessions/2026-10-23_abc.jsonl"
        assertEquals(notifyIdFor(a), notifyIdFor(a))
    }

    @Test
    fun `不同会话的 id 不同`() {
        assertNotEquals(notifyIdFor("/a/x.jsonl"), notifyIdFor("/a/y.jsonl"))
    }

    @Test
    fun `没有会话文件也有一个合法 id`() {
        assertTrue(notifyIdFor(null) >= 1000)
    }

    @Test
    fun `三种结局的标题能分开`() {
        assertEquals("回答完成", notifyTitle("turn-end"))
        assertEquals("已停止", notifyTitle("stopped"))
        assertEquals("出错了", notifyTitle("error"))
        // 桥没给 kind 时按"答完了"，不能弹一条空标题的通知
        assertEquals("回答完成", notifyTitle(null))
    }

    @Test
    fun `等回应与 agent 主动通知也有标题`() {
        // pi 弹 confirm/select/input/editor 时桥发 needs-input（旧版本只有前台看得到卡片）
        assertEquals("需要你确认", notifyTitle("needs-input"))
        // app 会话里注入的 pocket_notify 工具默认 kind=note
        assertEquals("pi 通知", notifyTitle("note"))
        assertEquals("跑完了", notifyTitle("done"))
    }

    @Test
    fun `id 落在短整数范围（通知 id 不能为负）`() {
        listOf(null, "", "/x", "/y/z.jsonl").forEach {
            val id = notifyIdFor(it)
            assertTrue("id=$id", id in 1000..2000)
        }
    }

    // ── 「回答完成提醒」开关（用户 2026-10-24：每次都显示且报错也显示 ⇒ 默认关）──

    @Test
    fun `回合结束类提醒默认不发`() {
        // prefs 缺省值就是关：没主动打开时，前台不落行、后台不弹通知
        listOf("turn-end", "stopped", "error", "done", "", null).forEach {
            assertTrue("kind=$it", isTurnEndNotice(it))
            assertFalse("kind=$it", turnEndNoticeAllowed(it, turnEndEnabled = false))
            assertTrue("kind=$it", turnEndNoticeAllowed(it, turnEndEnabled = true))
        }
    }

    @Test
    fun `等人回应与 agent 主动通知不受开关影响`() {
        // needs-input = pi 在等你确认/选/输入/编辑；note = agent 自己调 pocket_notify
        listOf("needs-input", "note").forEach {
            assertFalse("kind=$it", isTurnEndNotice(it))
            assertTrue("kind=$it", turnEndNoticeAllowed(it, turnEndEnabled = false))
            assertTrue("kind=$it", turnEndNoticeAllowed(it, turnEndEnabled = true))
        }
    }

    @Test
    fun `将来新增的 kind 不会被开关顺手吞掉`() {
        // 用显式清单而不是「除 needs-input/note 都算」：新提醒默认仍然会送到
        assertFalse(isTurnEndNotice("needs-approval"))
        assertTrue(turnEndNoticeAllowed("needs-approval", turnEndEnabled = false))
    }
}
