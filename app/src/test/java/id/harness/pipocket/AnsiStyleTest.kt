package id.harness.pipocket

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import id.harness.pipocket.ui.ansiToAnnotated
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 工具卡头行的样式（用户 2026-10-23：「调用字符加粗等样式被移除了」）。
 *
 * 加粗的**丢失点在桥**（chalk 在没有 TTY 时判成 level 0，把 `\x1b[1m` 剥掉），
 * 但 app 这边也必须真的把 `1` 画成粗体 —— 这里钉住解析规则，避免两边一起退化。
 */
class AnsiStyleTest {

    private fun spanWeight(raw: String): FontWeight? {
        val annotated = ansiToAnnotated(raw, Color.White)
        if (annotated.spanStyles.isEmpty()) return null
        return annotated.spanStyles.first().item.fontWeight
    }

    @Test
    fun `粗体被解析`() {
        // pi 的 theme.bold：`\x1b[1m…\x1b[22m`
        assertEquals(FontWeight.Bold, spanWeight("\u001b[1mbash\u001b[22m"))
    }

    @Test
    fun `彩色加粗同时生效`() {
        val annotated = ansiToAnnotated("\u001b[1m\u001b[38;2;1;2;3medit\u001b[39m\u001b[22m", Color.White)
        val span = annotated.spanStyles.first().item
        assertEquals(FontWeight.Bold, span.fontWeight)
        assertEquals(Color(1, 2, 3), span.color)
    }

    @Test
    fun `22 之后回到正常字重`() {
        val annotated = ansiToAnnotated("\u001b[1mA\u001b[22mB", Color.White)
        // 第二段（B）应该是 Normal
        val last = annotated.spanStyles.last().item
        assertEquals(FontWeight.Normal, last.fontWeight)
    }

    @Test
    fun `0 重置一切`() {
        val annotated = ansiToAnnotated("\u001b[1;38;2;9;9;9mA\u001b[0mB", Color.White)
        assertEquals(FontWeight.Normal, annotated.spanStyles.last().item.fontWeight)
        assertEquals(Color.White, annotated.spanStyles.last().item.color)
    }

    // 用户 2026-10-23：「tool call 是有丰富的字体样式的」——
    // 终端里 pi 会用斜体/下划线/删除线/反显，app 解析器以前只认"粗"和"暗"，其余全丢。
    @Test
    fun `斜体被解析`() {
        val a = ansiToAnnotated("\u001b[3mabc\u001b[23m", Color.White)
        assertEquals(FontStyle.Italic, a.spanStyles.first().item.fontStyle)
    }

    @Test
    fun `下划线被解析`() {
        val a = ansiToAnnotated("\u001b[4mabc\u001b[24m", Color.White)
        assertEquals(TextDecoration.Underline, a.spanStyles.first().item.textDecoration)
    }

    @Test
    fun `删除线被解析`() {
        val a = ansiToAnnotated("\u001b[9mabc\u001b[29m", Color.White)
        assertEquals(TextDecoration.LineThrough, a.spanStyles.first().item.textDecoration)
    }

    @Test
    fun `下划线加删除线可以叠加`() {
        val a = ansiToAnnotated("\u001b[4m\u001b[9mabc\u001b[0m", Color.White)
        val deco = a.spanStyles.first().item.textDecoration
        assertEquals(TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough)), deco)
    }

    @Test
    fun `重置之后样式清空`() {
        val a = ansiToAnnotated("\u001b[3;4;9mA\u001b[0mB", Color.White)
        val last = a.spanStyles.last().item
        assertEquals(FontStyle.Normal, last.fontStyle)
        assertEquals(null, last.textDecoration)
    }
}
