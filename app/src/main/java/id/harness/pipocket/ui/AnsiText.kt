package id.harness.pipocket.ui

import androidx.compose.material3.Text
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * 把 pi 渲染出来的 **ANSI 行**原样画出来（工具块的「1:1 对比终端」用）。
 *
 * 为什么不用 WebView：终端块就是"等宽 + 颜色 + 固定换行"，用 AnnotatedString 画更轻、滚动更顺，
 * 也不会每张卡一个 WebView。颜色来自 pi 的 truecolor（`\\x1b[38;2;r;g;bm`）/ 256 色 / 基本色。
 *
 * 实现的 SGR：0 全重置、1 粗 / 2 暗 / 22、3 斜 / 23、4 下划线 / 24、9 删除线 / 29、
 * 7 反显 / 27、39 前景重置、49 背景重置、38;2;r;g;b / 38;5;n、48 系列背景、30-37/90-97 基本色。
 * （用户 2026-10-23：「tool call 是有丰富的字体样式的」——终端里加粗/斜体/下划线/删除线都用，
 *  app 之前只认粗和暗，剩下的样式全丢。）
 */
@Composable
fun AnsiText(
    lines: List<String>,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 11.sp,
    color: Color = TerminalColors.muted(),
    /** 是否画 ANSI 背景色。
     *  pi 给工具块头行带了 `48;2;52;56;58`（工具块底），但终端里整块是一个底色、
     * 看不出“头行色带”。app 若真画出来，就是一条只盖住文字的浅灰带（与终端不一致）⇒
     *  工具块一律传 false，让块底色由卡片自己给（= 终端的 toolBlock 色）。 */
    applyBackground: Boolean = true,
) {
    if (lines.isEmpty()) return
    // 浅色模式：pi 的 ANSI 是深色主题的 truecolor（灰白），在浅色工具卡上读不清
    // ⇒ 交给 TerminalColors 压暗到 ≥4.6:1；深色模式映射器恒等（颜色完全不变）。
    val remap = TerminalColors.ansiMapper()
    val text = remember(lines, applyBackground, remap) {
        ansiToAnnotated(lines.joinToString("\n"), color, applyBackground, remap)
    }
    // 字号**固定**（用户 2026-10-21 明确：不许因为列宽缩字）。
    // 桥按 80 列排好（避免 pi 把命令截成 `...`），卡片放不下时**软换行**续到下一行 —
    // 视觉上像把终端窗口拉窄，比缩字号/裁切都好读。
    Text(
        text = text,
        maxLines = Int.MAX_VALUE,
        softWrap = true,
        style = TextStyle(
            fontFamily = FontFamily.Monospace,
            fontSize = fontSize,
            lineHeight = fontSize * 1.15f,
            color = color,
        ),
        modifier = modifier,
    )
}

/** ANSI → AnnotatedString。未知/不关心的 SGR 直接跳过（不会漏字符，也不会崩）。 */
fun ansiToAnnotated(
    raw: String,
    defaultColor: Color,
    applyBackground: Boolean = true,
    /** 前景色映射（浅色模式用来把 pi 的深色主题色压暗到可读）；默认恒等。 */
    remap: (Color) -> Color = { it },
): AnnotatedString = buildAnnotatedString {
    var i = 0
    var fg: Color? = null
    var bg: Color? = null
    var bold = false
    var dim = false
    var italic = false
    var underline = false
    var strike = false
    var inverse = false

    fun currentStyle(): SpanStyle {
        val base = remap(fg ?: defaultColor)
        val c = if (dim) base.copy(alpha = 0.7f) else base
        // 反显（SGR 7）：终端是把前景/背景对调。没有背景色时退化成"用背景色当文字色"讲不通，
        // 所以只在真有背景色时交换，否则保持原样（不猜）。
        val textColor = if (inverse && bg != null && applyBackground) remap(bg!!) else c
        val bgColor = if (inverse && bg != null && applyBackground) c else (bg ?: Color.Transparent)
        val deco = buildList {
            if (underline) add(TextDecoration.Underline)
            if (strike) add(TextDecoration.LineThrough)
        }
        return SpanStyle(
            color = textColor,
            background = if (applyBackground) bgColor else Color.Transparent,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
            textDecoration = if (deco.isEmpty()) null else TextDecoration.combine(deco),
        )
    }

    while (i < raw.length) {
        val ch = raw[i]
        if (ch == '\u001b' && i + 1 < raw.length && raw[i + 1] == '[') {
            var j = i + 2
            while (j < raw.length && raw[j] != 'm') j++
            if (j >= raw.length) break
            val params = raw.substring(i + 2, j)
            val nums = params.split(';').mapNotNull { it.toIntOrNull() }
            var k = 0
            while (k < nums.size) {
                when (nums[k]) {
                    0 -> {
                        fg = null; bg = null; bold = false; dim = false
                        italic = false; underline = false; strike = false; inverse = false
                    }
                    1 -> bold = true
                    2 -> dim = true
                    22 -> { bold = false; dim = false }
                    3 -> italic = true
                    23 -> italic = false
                    4 -> underline = true
                    24 -> underline = false
                    7 -> inverse = true
                    27 -> inverse = false
                    9 -> strike = true
                    29 -> strike = false
                    39 -> fg = null
                    49 -> bg = null
                    38 -> {
                        // 38;2;r;g;b 或 38;5;n
                        if (k + 1 < nums.size && nums[k + 1] == 2 && k + 4 < nums.size) {
                            fg = Color(nums[k + 2], nums[k + 3], nums[k + 4]); k += 4
                        } else if (k + 1 < nums.size && nums[k + 1] == 5 && k + 2 < nums.size) {
                            fg = xterm256(nums[k + 2]); k += 2
                        }
                    }
                    48 -> {
                        if (k + 1 < nums.size && nums[k + 1] == 2 && k + 4 < nums.size) {
                            bg = Color(nums[k + 2], nums[k + 3], nums[k + 4]); k += 4
                        } else if (k + 1 < nums.size && nums[k + 1] == 5 && k + 2 < nums.size) {
                            bg = xterm256(nums[k + 2]); k += 2
                        }
                    }
                    in 30..37 -> fg = basicAnsi(nums[k] - 30)
                    in 90..97 -> fg = basicAnsi(nums[k] - 90 + 8)
                    else -> Unit
                }
                k++
            }
            i = j + 1
            continue
        }
        // 普通字符：连续取到下一个 ESC，整段上一次样式
        var end = i
        while (end < raw.length && raw[end] != '\u001b') end++
        withStyle(currentStyle()) { append(raw.substring(i, end)) }
        i = end
    }
}

/** xterm 256 色 → Color（16 基本 + 6×6×6 立方 + 24 灰阶）。 */
private fun xterm256(n: Int): Color {
    if (n < 16) return basicAnsi(n)
    if (n < 232) {
        val i = n - 16
        val r = i / 36
        val g = (i % 36) / 6
        val b = i % 6
        fun v(x: Int) = if (x == 0) 0 else 55 + x * 40
        return Color(v(r), v(g), v(b))
    }
    val g = 8 + (n - 232) * 10
    return Color(g, g, g)
}

/** 标准 16 色（pi 的 dark 主题用的就是这一套的近似值）。 */
private fun basicAnsi(i: Int): Color = when (i) {
    0 -> Color(0x1C1C1C)
    1 -> Color(0xE06C75)
    2 -> Color(0x98C379)
    3 -> Color(0xE5C07B)
    4 -> Color(0x61AFEF)
    5 -> Color(0xC678DD)
    6 -> Color(0x56B6C2)
    7 -> Color(0xABB2BF)
    8 -> Color(0x5C6370)
    9 -> Color(0xE06C75)
    10 -> Color(0x98C379)
    11 -> Color(0xE5C07B)
    12 -> Color(0x61AFEF)
    13 -> Color(0xC678DD)
    14 -> Color(0x56B6C2)
    else -> Color(0xDCDFE4)
}
