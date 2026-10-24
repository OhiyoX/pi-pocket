package id.harness.pipocket

import id.harness.pipocket.ui.headPreviewLines
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 折叠态头行露几行（用户 2026-10-23：「edit 工具这些样式我好像也没看到了」）。
 *
 * edit 的头行里就带着绿/红 diff 预览；以前统一砍到 3 行 ⇒ 只剩头 + 两行 diff，
 * 看着像"样式没了"。这里按工具给不同上限，并用测试钉住。
 */
class HeadPreviewTest {

    @Test
    fun `edit 折叠时给足 diff 预览行`() {
        assertTrue(headPreviewLines("edit", expanded = false) >= 10)
        assertEquals(14, headPreviewLines("edit", expanded = false))
    }

    @Test
    fun `write 与 edit 同口径`() {
        assertEquals(headPreviewLines("edit", false), headPreviewLines("write", false))
    }

    @Test
    fun `普通工具仍然收紧（不撑爆卡片）`() {
        assertEquals(3, headPreviewLines("read", false))
    }

    @Test
    fun `bash 给 5 行（长命令不至于只剩开头）`() {
        assertEquals(5, headPreviewLines("bash", false))
        assertEquals(5, headPreviewLines("exec_command", false))
    }

    @Test
    fun `展开时全给`() {
        assertEquals(Int.MAX_VALUE, headPreviewLines("edit", expanded = true))
    }
}
