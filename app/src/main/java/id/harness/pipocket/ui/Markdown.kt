package id.harness.pipocket.ui

import android.graphics.BitmapFactory
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * 一个够用的 Markdown 渲染器（不引第三方库，避免和 Compose/material3 的版本打架）。
 *
 * 支持：标题（#..######）、围栏代码块 ```lang、行内 `code`、**粗**、*斜*、~~删除线~~、
 * [链接](url)、无序/有序列表、引用 `>`、分割线 `---`、段落。
 * 渲染口径：代码块给独立容器 + 横向滚动 + 等宽字体；正文用对话页传入的 TextStyle，不自己定字号
 * （字号由「外观与字号」里的全局/对话倍率统一控制）。
 */
sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class Code(val language: String, val code: String) : MdBlock
    data class Bullet(val text: String, val number: Int?) : MdBlock
    data class Quote(val text: String) : MdBlock

    /** 表格：终端里是**画框表格**（`┌─┬─┐ │ 单元 │ ├─┼─┤`），表头加粗，两边各留一个空格。 */
    data class Table(val header: List<String>, val rows: List<List<String>>) : MdBlock

    /** 独占一行的 `![alt](src)`：渲染成缩图，点开走全屏预览（ZCode `markdown-image.tsx` 口径）。 */
    data class Image(val alt: String, val src: String) : MdBlock

    /** ```mermaid 围栏：交给 WebView 里的 mermaid 画（ZCode `mermaid-block.tsx` 口径）。 */
    data class Mermaid(val code: String) : MdBlock

    /** ```html-visual 围栏：app 内置作图技能的交互通道（沙箱 WebView 渲染 HTML 片段）。 */
    data class HtmlVisual(val code: String) : MdBlock
    data object Rule : MdBlock
}

/** `![alt](src)` → (alt, src)；不是整行图片就返回 null。 */
private fun imageLine(line: String): Pair<String, String>? {
    if (!line.startsWith("![") || !line.endsWith(")")) return null
    val close = line.indexOf("](")
    if (close <= 2) return null
    val src = line.substring(close + 2, line.length - 1).trim()
    if (src.isEmpty()) return null
    return line.substring(2, close).trim() to src
}

/**
 * 行内样式用到的颜色（终端实测 RGB）。
 *
 * `Markdown.inline()` 是纯函数（不依赖 Compose 运行时），所以不能直接读 `@Composable` 的 TerminalColors ——
 * 由调用方（MarkdownText）把颜色解好传进来；默认值给的是终端 dark 口径。
 */
data class InlineColors(
    val code: Color = Color(0xFFA798D7),
    val link: Color = Color(0xFF69ADD0),
    val muted: Color = Color(0xFF9DA5A9),
)

object Markdown {

    // ── 解析结果缓存 ──────────────────────────────────────────────────
    // 流式输出时列表每次变化都会让可见行重组，而每行重组都要重新解析整段 Markdown；
    // 不缓存就是每秒几十次全量重解析（手机上就是卡顿）。
    private val parseCache = object : LinkedHashMap<String, List<MdBlock>>(24, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<MdBlock>>?) = size > 24
    }
    private val inlineCache = object : LinkedHashMap<String, AnnotatedString>(96, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, AnnotatedString>?) = size > 96
    }

    fun parseCached(source: String): List<MdBlock> =
        synchronized(parseCache) { parseCache.getOrPut(source) { parse(source) } }

    fun parse(source: String): List<MdBlock> {
        val blocks = mutableListOf<MdBlock>()
        val lines = source.replace("\r\n", "\n").split("\n")
        val paragraph = StringBuilder()
        val code = StringBuilder()
        var inCode = false
        var codeLang = ""

        fun flushParagraph() {
            if (paragraph.isNotBlank()) blocks += MdBlock.Paragraph(paragraph.toString().trim())
            paragraph.clear()
        }

        var lineIndex = 0
        while (lineIndex < lines.size) {
            val line = lines[lineIndex]
            val fence = line.trimStart().startsWith("```")
            if (inCode) {
                if (fence) {
                    val lang = codeLang.lowercase()
                    val body = code.toString().trimEnd('\n')
                    blocks += when (lang) {
                        // 静态结构 / 流程图
                        "mermaid" -> MdBlock.Mermaid(body)
                        // 交互 / 动态 / 图表（app 内置作图技能的约定名）
                        "html-visual", "htmlvisual" -> MdBlock.HtmlVisual(body)
                        // 模型经常直接给 ```html / ```svg（没走约定名）：也当片段渲染。
                        // 它跑在沙箱 WebView 里（CSP default-src 'none'，无网络、无文件访问），
                        // 渲染不出来会自动退回代码块，所以宽容一点不会白屏。
                        "html", "svg", "xhtml" -> MdBlock.HtmlVisual(body)
                        else -> MdBlock.Code(codeLang, body)
                    }
                    code.clear(); codeLang = ""; inCode = false
                    lineIndex++
                } else {
                    code.append(line).append('\n')
                    lineIndex++
                }
                continue
            }
            if (fence) {
                flushParagraph()
                inCode = true
                codeLang = line.trim().removePrefix("```").trim()
                lineIndex++
                continue
            }

            // 表格：本行以 | 开头，下一行是 |---|---| 分隔行（终端里画框渲染）
            if (line.trim().startsWith("|") && looksLikeTableSeparator(lines.getOrNull(lineIndex + 1))) {
                flushParagraph()
                val header = splitRow(line)
                val rows = mutableListOf<List<String>>()
                var cursor = lineIndex + 2
                while (cursor < lines.size && lines[cursor].trim().startsWith("|")) {
                    rows += splitRow(lines[cursor])
                    cursor++
                }
                blocks += MdBlock.Table(header, rows)
                lineIndex = cursor
                continue
            }

            // 独占一行的图片：`![alt](src)` → 渲染成图（否则只会被当普通链接，什么也看不到）
            val image = imageLine(line.trim())
            if (image != null) {
                flushParagraph()
                blocks += MdBlock.Image(image.first, image.second)
                lineIndex++
                continue
            }

            val trimmed = line.trim()
            when {
                trimmed.isEmpty() -> flushParagraph()

                trimmed == "---" || trimmed == "***" || trimmed == "___" -> {
                    flushParagraph(); blocks += MdBlock.Rule
                }

                trimmed.startsWith("#") -> {
                    flushParagraph()
                    val level = trimmed.takeWhile { it == '#' }.length.coerceIn(1, 6)
                    blocks += MdBlock.Heading(level, trimmed.drop(level).trim())
                }

                trimmed.startsWith("> ") || trimmed == ">" -> {
                    flushParagraph()
                    blocks += MdBlock.Quote(trimmed.removePrefix(">").trim())
                }

                trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("+ ") -> {
                    flushParagraph()
                    blocks += MdBlock.Bullet(trimmed.drop(2).trim(), null)
                }

                Regex("^\\d+[.)]\\s+.*").matches(trimmed) -> {
                    flushParagraph()
                    val number = trimmed.takeWhile { it.isDigit() }.toIntOrNull()
                    blocks += MdBlock.Bullet(trimmed.dropWhile { it.isDigit() }.drop(1).trim(), number)
                }

                else -> paragraph.append(if (paragraph.isEmpty()) trimmed else " $trimmed")
            }
            lineIndex++
        }
        if (inCode && code.isNotEmpty()) blocks += MdBlock.Code(codeLang, code.toString().trimEnd('\n'))
        flushParagraph()
        return blocks
    }

    /** 表格分隔行：`|---|:--:|` 这种（终端里不画出来，只用来认表）。 */
    private fun looksLikeTableSeparator(line: String?): Boolean {
        val text = line?.trim() ?: return false
        if (!text.startsWith("|") && !text.contains('|')) return false
        return text.all { it == '|' || it == '-' || it == ':' || it == ' ' }
    }

    /**
     * `| a | b |` → [a, b]（首尾空单元丢掉，和终端一致：不画多余的空列）。
     *
     * **不能直接 split('|')**：单元格里的**行内码**与**转义竖线**不该被拆 ——
     * 实例（2026-10-21 真机截图）：`(isWorking || streaming)` 被拆成两列，
     * 多出一列 `\` 和一列 `streaming``，整张表变成五列且每列只有 7 个字宽。
     * 规则对齐 GFM：反引号中的 `|` 与 `\|` 都当普通字符。
     */
    private fun splitRow(line: String): List<String> {
        val src = line.trim().let { if (it.startsWith("|")) it.substring(1) else it }
        val cells = mutableListOf<String>()
        val sb = StringBuilder()
        var inCode = false
        var i = 0
        while (i < src.length) {
            val c = src[i]
            when {
                c == '\\' && i + 1 < src.length && src[i + 1] == '|' -> { sb.append('|'); i += 2 }
                c == '`' -> { inCode = !inCode; sb.append(c); i++ }
                c == '|' && !inCode -> { cells.add(sb.toString()); sb.setLength(0); i++ }
                else -> { sb.append(c); i++ }
            }
        }
        cells.add(sb.toString())
        val trimmed = cells.map { it.trim() }
        return if (trimmed.size > 1 && trimmed.last().isEmpty()) trimmed.dropLast(1) else trimmed
    }

    /**
     * 终端口径的显示宽度：CJK / 全角 / emoji 算 **2 格**。
     *
     * 不算这个的话，中文表头会被当成 1 格 ⇒ 表和竖线错位（终端里 pi 用的是 wcwidth）。
     */
    private fun displayWidth(text: String): Int {
        var width = 0
        var i = 0
        while (i < text.length) {
            val code = text.codePointAt(i)
            width += when {
                code >= 0x1F300 -> 2                                  // emoji / 符号
                code in 0x1100..0x115F -> 2                            // Hangul Jamo
                code in 0x2E80..0x303E -> 2                            // CJK 部首与标点
                code in 0x3041..0x33FF -> 2                            // 假名 / 注音 / CJK 兼容
                code in 0x3400..0x4DBF -> 2                            // CJK 扩展 A
                code in 0x4E00..0x9FFF -> 2                            // CJK 统一表意
                code in 0xA000..0xA4CF -> 2                            // 彝文
                code in 0xAC00..0xD7A3 -> 2                            // 韩文音节
                code in 0xF900..0xFAFF -> 2                            // CJK 兼容表意
                code in 0xFE30..0xFE6F -> 2                            // CJK 兼容形式
                code in 0xFF00..0xFF60 -> 2                            // 全角 ASCII
                code in 0xFFE0..0xFFE6 -> 2                            // 全角符号
                else -> 1
            }
            i += Character.charCount(code)
        }
        return width
    }

    /**
     * 按终端口径画表格（pi TUI 实测）：```┌─┬─┐ │ 头 │ ├─┼─┤ │ 值 │ └─┴─┘```。
     *
     * 注意：**手机 UI 上不用这个**（保留给测试/纯文本场景）。因为**字体度量不对齐**：
     * 终端靠 wcwidth，而安卓上的等宽字体 + CJK 回退字体的实际字宽不是“1:2”关系，
     * 于是中文表头那几列会相对 ASCII 行漂 "半格"（实测头部竖线比数据行左偏 ~20px）。
     * 所以 app 里的表格是 **Compose 实测宽度**摆的（见 TableBlock）。
     */
    fun tableLines(header: List<String>, rows: List<List<String>>): List<String> {
        val columns = maxOf(header.size, rows.maxOfOrNull { it.size } ?: 0)
        if (columns == 0) return emptyList()
        fun cell(row: List<String>, index: Int) = row.getOrNull(index).orEmpty().trim()
        val widths = (0 until columns).map { c ->
            maxOf(
                displayWidth(header.getOrNull(c).orEmpty().trim()),
                rows.maxOfOrNull { displayWidth(cell(it, c)) } ?: 0,
            )
        }
        fun rule(left: String, mid: String, right: String) =
            left + widths.joinToString(mid) { "─".repeat(it + 2) } + right
        fun pad(text: String, width: Int) = text + " ".repeat((width - displayWidth(text)).coerceAtLeast(0))
        fun row(values: List<String>) =
            "│ " + (0 until columns).joinToString(" │ ") { pad(cell(values, it), widths[it]) } + " │"
        return buildList {
            add(rule("┌", "┬", "┐"))
            add(row(header))
            add(rule("├", "┼", "┤"))
            rows.forEach { add(row(it)) }
            add(rule("└", "┴", "┘"))
        }
    }

    /** 行内样式：`code`、**粗**、*斜*、~~删~~、[文本](url)。
     *
     * 注意：**不能按单个 Char 追加**！emoji 是代理对（两个 code unit），拆开 append 会渲染成两个方框
     * （手机上的👋 乱码就是这么来的：其余字体都正常，只有走 Markdown 渲染的正文会出）。
     * 所以普通文本先攒成整段 flush。
     */
    fun inlineCached(source: String, base: TextStyle? = null, colors: InlineColors = InlineColors()): AnnotatedString {
        val key = source + "\u0000" + base?.fontWeight + "\u0000" + base?.fontStyle + "\u0000" + colors
        return synchronized(inlineCache) { inlineCache.getOrPut(key) { inline(source, base, colors) } }
    }

    fun inline(
        source: String,
        base: TextStyle? = null,
        colors: InlineColors = InlineColors(),
    ): AnnotatedString = buildAnnotatedString {
        val baseStyle = SpanStyle(fontWeight = base?.fontWeight, fontStyle = base?.fontStyle)
        val plain = StringBuilder()
        fun flushPlain() {
            if (plain.isNotEmpty()) {
                withStyle(baseStyle) { append(plain.toString()) }
                plain.clear()
            }
        }

        var i = 0
        while (i < source.length) {
            when {
                source.startsWith("`", i) -> {
                    val end = source.indexOf('`', i + 1)
                    if (end > i) {
                        flushPlain()
                        withStyle(SpanStyle(fontFamily = FontFamily.Monospace, color = colors.code)) {
                            append(source.substring(i + 1, end))
                        }
                        i = end + 1
                    } else { plain.append(source[i]); i++ }
                }

                source.startsWith("**", i) || source.startsWith("__", i) -> {
                    val marker = source.substring(i, i + 2)
                    val end = source.indexOf(marker, i + 2)
                    if (end > i) {
                        flushPlain()
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(source.substring(i + 2, end)) }
                        i = end + 2
                    } else { plain.append(marker); i += 2 }
                }

                source.startsWith("~~", i) -> {
                    val end = source.indexOf("~~", i + 2)
                    if (end > i) {
                        flushPlain()
                        withStyle(SpanStyle(textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough)) {
                            append(source.substring(i + 2, end))
                        }
                        i = end + 2
                    } else { plain.append("~~"); i += 2 }
                }

                source[i] == '*' || source[i] == '_' -> {
                    val marker = source[i]
                    val end = source.indexOf(marker, i + 1)
                    if (end > i + 1) {
                        flushPlain()
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(source.substring(i + 1, end)) }
                        i = end + 1
                    } else { plain.append(marker); i++ }
                }

                source[i] == '[' -> {
                    val close = source.indexOf(']', i)
                    val open = if (close > 0 && close + 1 < source.length && source[close + 1] == '(') close + 1 else -1
                    val end = if (open > 0) source.indexOf(')', open) else -1
                    if (close > 0 && open > 0 && end > open) {
                        val label = source.substring(i + 1, close)
                        val url = source.substring(open + 1, end)
                        flushPlain()
                        runCatching {
                            withLink(LinkAnnotation.Url(url)) {
                                withStyle(
                                    SpanStyle(
                                        color = colors.link,
                                        textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                                    ),
                                ) { append(label) }
                            }
                        }.onFailure { append("$label ($url)") }
                        // 终端里链接后面还跟着灰色的 (url)
                        withStyle(SpanStyle(color = colors.muted)) { append(" ($url)") }
                        i = end + 1
                    } else { plain.append('['); i++ }
                }

                else -> { plain.append(source[i]); i++ }
            }
        }
        flushPlain()
    }
}

/**
 * 表格：与终端信息结构一致（表头加粗、单元格两側一空格、细线分隔），
 * 但列宽用 `TextMeasurer` **实测**，而不是猜格子宽度。
 *
 * 为什么不用字形画框（`┌─┬─┐`）：安卓的等宽字体对 CJK 的实际字宽不是 ASCII 的整数倍，
 * 中文表头那几列会相对 ASCII 行漂“半格”（手机上实测到 ~20px 错位）。
 */
@Composable
private fun TableBlock(
    block: MdBlock.Table,
    style: TextStyle,
    color: Color,
    inlineColors: InlineColors,
) {    val columns = maxOf(block.header.size, block.rows.maxOfOrNull { it.size } ?: 0)
    if (columns == 0) return
    val cellStyle = style.copy(fontFamily = FontFamily.Monospace)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val lineColor = TerminalColors.muted().copy(alpha = 0.45f)

    fun cellsOf(row: List<String>): List<AnnotatedString> = (0 until columns).map { c ->
        Markdown.inlineCached(row.getOrNull(c).orEmpty().trim(), cellStyle, inlineColors)
    }

    val header = remember(block, cellStyle) { cellsOf(block.header) }
    val rows = remember(block, cellStyle) { block.rows.map { cellsOf(it) } }
    // 每列的**自然宽度（内容本身）**：不换行、不加内边距量一次
    val naturalContent = remember(block, cellStyle) {
        val all = listOf(header) + rows
        (0 until columns).map { c ->
            with(density) { all.maxOf { row -> measurer.measure(row[c], cellStyle, maxLines = 1).size.width }.toDp() }
        }
    }
    // 每列**最长的不可断片段**（按空格/全角空格切词），单位 dp。
    //
    // 为什么要它：只用「最长一格的自然宽」定列宽会被**键列里最长的那个标识符**骗到 ——
    // 用户 2026-10-21 的 `位置 | 改动` 表：col1 最长格 `app AppState.onPiRecord` ≈ 204dp，
    // 恰好比“均分额” 203.5dp 大 0.5dp ⇒ 水位法判定“这列本来就装得下” ⇒ 两列又拉平 50/50。
    // 改成「先保每列最长的词不被剁开，再把剩余宽度按“还差多少才不换行”分配」。
    val minContent = remember(block, cellStyle) {
        val all = listOf(header) + rows
        (0 until columns).map { c ->
            all.maxOfOrNull { row ->
                row[c].text.split(' ', '\u3000', '\t')
                    .filter { it.isNotBlank() }
                    .maxOfOrNull { tok ->
                        with(density) {
                            measurer.measure(AnnotatedString(tok), cellStyle, maxLines = 1).size.width.toDp()
                        }
                    } ?: 0.dp
            } ?: 0.dp
        }
    }

    val cellPadH = 16.dp            // 每列左右 8dp + 8dp
    val borderW = 1.dp              // 左右两条竖线各 1dp（外框也算在可用宽里）
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val available = maxWidth
        val usable = (available - borderW * 2 - cellPadH * columns).coerceAtLeast(24.dp)
        val naturalSum = naturalContent.fold(0.dp) { acc, w -> acc + w }

        // **可读列宽 + 换行 + 左右滑动**（2026-10-21 离线验证后的最终口径）：
        // 以「每列至少能读」为准，列宽不再平均分：
        //   · 装得下        → 按自然宽比例补齐撑满（两侧对齐），不滚；
        //   · 装不下        → 词底线做一次水位（短列拿满、宽列分剩下的），
        //                    剩余再按「还差多少才不换行」做第二次水位；
        //                    只有**整张表本来就摆不下**（均分额 < 44dp）才回退自然宽 + 横滚。
        //                    判据必须是结构性的，不能是「某一列 < 44dp」——见 tableWidths() 的注释。
        //  选型依据：termux/table-width-lab.py（10 case + 200 fuzz，代价 = 剁词×5 + 行数），
        //  方案 D 只在 oracle 之上 1.2%，而「平均分配」191 / 「等比缩」214。
        //
        // ⚠️ 三个已踩过的坑：
        //   1. `share = usable / columns` 再分剩余 ⇒ 剩余恒为 0 ⇒ 就是**平均分配**；
        //   2. 只看「最长一格的自然宽」⇒ 键列里一个长标识符就能占去一半宽度；
        //   3. 词底线超预算时整体等比缩 ⇒ 短列被拖下水，剁词最多（代价 214 vs 163）。
        val minReadable = 44.dp
        val layout = tableWidths(
            natural = naturalContent.map { it.value },
            minContent = minContent.map { it.value },
            usable = usable.value,
            minReadable = minReadable.value,
        )
        // layout.scroll=true 时 widths 已经是「自然宽（夹在 [minReadable, 3×minReadable]）」，
        // 总宽本就超过可用宽 ⇒ 外层 horizontalScroll 生效（装得下时横滚自然无效）。
        val contentWidths: List<Dp> = layout.widths.map { it.dp }
        val widths = contentWidths.map { it + cellPadH }
        // 排查用：一张表组合时打一行（表格很少，成本可忽），
        // 以后「列宽不对」先看 `logcat -s PPTable`，别再用截图猜。
        androidx.compose.runtime.LaunchedEffect(block) {
            android.util.Log.d(
                "PPTable",
                "cols=$columns usable=${usable.value.toInt()} scroll=${layout.scroll}" +
                    " cells=[" + header.joinToString("|") { it.text } + "]" +
                    " natural=" + naturalContent.joinToString(",") { it.value.toInt().toString() } +
                    " min=" + minContent.joinToString(",") { it.value.toInt().toString() } +
                    " widths=" + widths.joinToString(",") { it.value.toInt().toString() },
            )
        }

        // 表格**不带圆角**（用户口径）：方角细线框，跟终端的表格一样。
        // 统一一条渲染路径：单元格**换行** + 外层**横向滚动**（宽度装得下时横滚自然无效）。
        val totalWidth = widths.fold(0.dp) { acc, w -> acc + w } + borderW * 2
        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            Column(modifier = Modifier.width(totalWidth).border(borderW, lineColor)) {
                TableRow(header, widths, cellStyle, color, lineColor, bold = true, topLine = false, wrap = true)
                rows.forEach { row ->
                    TableRow(row, widths, cellStyle, color, lineColor, bold = false, topLine = true, wrap = true)
                }
            }
        }
    }
}

@Composable
private fun TableRow(
    cells: List<AnnotatedString>,
    widths: List<Dp>,
    cellStyle: TextStyle,
    color: Color,
    lineColor: Color,
    bold: Boolean,
    topLine: Boolean,
    /** true = 单元格允许换行（压缩列宽时）；false = 单行 + 横滚（保留自然宽时）。 */
    wrap: Boolean,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        if (topLine) HorizontalDivider(color = lineColor, thickness = 1.dp)
        Row(
            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
            verticalAlignment = Alignment.Top,
        ) {
            cells.forEachIndexed { index, cell ->
                if (index > 0) VerticalDivider(color = lineColor, thickness = 1.dp, modifier = Modifier.fillMaxHeight())
                Text(
                    text = cell,
                    style = if (bold) cellStyle.copy(fontWeight = FontWeight.Bold) else cellStyle,
                    color = color,
                    // 压缩列宽时**单元格自己换行**；保留自然宽的横滚模式则单行（行对齐才好读）。
                    softWrap = wrap,
                    maxLines = if (wrap) Int.MAX_VALUE else 1,
                    overflow = if (wrap) TextOverflow.Clip else TextOverflow.Visible,
                    modifier = Modifier.width(widths[index]).padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }
}

/**
 * 代码围栏（含语言标注）——三处复用：普通代码块、图/HTML 的**流式占位**、以及它们渲染失败时的回退。
 * 单独抽出来是因为"回退长得跟原来一样"是条硬要求：占位态切回真图时不能跳版。
 */
@Composable
private fun CodeFence(
    lang: String,
    code: String,
    codeStyle: TextStyle,
    color: androidx.compose.ui.graphics.Color,
    muted: androidx.compose.ui.graphics.Color,
) {
    Column(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        Text("```" + lang, style = codeStyle, color = muted)
        Text(text = code, style = codeStyle, color = color, modifier = Modifier.padding(start = 12.dp))
        Text("```", style = codeStyle, color = muted)
    }
}

@Composable
fun MarkdownText(
    text: String,
    style: TextStyle,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
    /** 正在流式输出且这是最后一条：最后一块文字上扫一道光（ZCode `shimmer.tsx` 口径）。 */
    shimmerTail: Boolean = false,
    /**
     * 这条消息**还在流式输出**：图（mermaid / html-visual）先按代码块显示，等写完再画。
     *
     * 口径来自 pi 的 `components/mermaid.ts`：`context.isStreaming && mode !== "streaming"` 时
     * **完全不渲染**，直接把原 markdown 交回去。手机上这条尤其重要：
     * 流式时每个 token 都会改围栏内容 ⇒ WebView 被反复销毁重建（一次几百毫秒），
     * 长回答后面跟着图的场景会直接把主线程拖死（我们就是这么踩到 ANR 的）。
     */
    deferDiagrams: Boolean = false,
    /**
     * 标题色。默认用终端的 heading 色；传值 = **整块同色**（pi 渲染 thinking 时就是
     * `color: (t) => theme.fg("thinkingText", t)`，标题也不例外）。
     */
    headingColor: androidx.compose.ui.graphics.Color? = null,
) {
    // 解析结果按文本缓存：流式时列表一变所有可见行都会重组，不缓存就是每秒几十次全量重解析
    val blocks = remember(text) { Markdown.parseCached(text) }
    val heading = headingColor ?: TerminalColors.heading()
    val muted = TerminalColors.muted()
    val inlineColors = InlineColors(
        code = TerminalColors.inlineCode(),
        link = TerminalColors.link(),
        muted = muted,
    )
    val codeStyle = style.copy(fontFamily = FontFamily.Monospace)
    // 流式尾巴的扫光：一条横向渐变的亮带从头走到尾（颜色就是正文色，只把一段调淡，不抢眼）
    val shimmerBrush: Brush? = if (shimmerTail) {
        val transition = rememberInfiniteTransition(label = "shimmer")
        val x by transition.animateFloat(
            initialValue = -240f,
            targetValue = 1200f,
            animationSpec = infiniteRepeatable(tween(durationMillis = 1500, easing = LinearEasing)),
            label = "shimmerX",
        )
        Brush.linearGradient(
            colors = listOf(color, color.copy(alpha = 0.35f), color),
            start = Offset(x, 0f),
            end = Offset(x + 200f, 0f),
        )
    } else {
        null
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEachIndexed { index, block ->
            // 只有最后一块（正在长的那块）上扫光
            val tail = shimmerBrush != null && index == blocks.lastIndex
            val ts = if (tail) style.copy(brush = shimmerBrush) else style
            when (block) {
                // 终端口径（实测）：H1 = 粗+下划线+琥珀；H2 = 粗+琥珀；H3 及以上连 `###` 一起原样显示
                is MdBlock.Heading -> Text(
                    text = if (block.level <= 2) Markdown.inlineCached(block.text, style, inlineColors)
                    else Markdown.inlineCached("#".repeat(block.level) + " " + block.text, style, inlineColors),
                    style = ts.copy(
                        fontWeight = FontWeight.Bold,
                        textDecoration = if (block.level == 1) androidx.compose.ui.text.style.TextDecoration.Underline else null,
                    ),
                    color = heading,
                )

                is MdBlock.Paragraph -> Text(
                    text = Markdown.inlineCached(block.text, style, inlineColors),
                    style = ts,
                    color = color,
                )

                // 终端里代码块**没有底色**：只有灰色的三反引号行 + 整体缩进的等宽代码
                is MdBlock.Code -> Column(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                ) {
                    Text("```" + block.language, style = codeStyle, color = muted)
                    Text(
                        text = block.code,
                        style = codeStyle,
                        color = color,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                    Text("```", style = codeStyle, color = muted)
                }

                // 表格：对齐终端的信息结构（表头加粗、单元格两側一空格、细线分隔），
                // 但列宽用 **TextMeasurer 实测** —— 中英文混排也不会错位（字形画框做不到，见 tableLines 注释）
                is MdBlock.Table -> TableBlock(block, style, color, inlineColors)

                // 正文里的图：缩图 + 点开全屏预览
                is MdBlock.Image -> MarkdownImage(block.src, block.alt)

                // html-visual：沙箱 WebView（CSP 断网）+ 按内容高度；失败退回代码块
                is MdBlock.HtmlVisual -> if (deferDiagrams) {
                    CodeFence("html-visual", block.code, codeStyle, color, muted)
                } else HtmlVisualBlock(block.code) {
                    Column(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                        Text("```html-visual", style = codeStyle, color = muted)
                        Text(block.code, style = codeStyle, color = color, modifier = Modifier.padding(start = 12.dp))
                        Text("```", style = codeStyle, color = muted)
                    }
                }

                // mermaid：能画就画，画不出来就退回原来的代码块（围栏 + 等宽代码）
                is MdBlock.Mermaid -> if (deferDiagrams) {
                    CodeFence("mermaid", block.code, codeStyle, color, muted)
                } else MermaidBlock(block.code) {
                    Column(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                        Text("```mermaid", style = codeStyle, color = muted)
                        Text(
                            text = block.code,
                            style = codeStyle,
                            color = color,
                            modifier = Modifier.padding(start = 12.dp),
                        )
                        Text("```", style = codeStyle, color = muted)
                    }
                }

                // 列表：ZCode 口径——无序用 disc、有序用十进制，marker 用最淡的灰（不再用终端那套紫色前缀）
                is MdBlock.Bullet -> Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = block.number?.let { "$it. " } ?: "• ",
                        style = ts,
                        color = muted,
                    )
                    Text(text = Markdown.inlineCached(block.text, style, inlineColors), style = ts, color = color)
                }

                // 引用：ZCode 口径——左侧 2dp 细线 + 淡灰正文（不再整段斜体）
                is MdBlock.Quote -> Row(modifier = Modifier.fillMaxWidth()) {
                    Box(
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .width(2.dp)
                            .fillMaxHeight()
                            .background(muted),
                    )
                    Text(
                        text = Markdown.inlineCached(block.text, style, inlineColors),
                        style = ts,
                        color = muted,
                    )
                }

                MdBlock.Rule -> HorizontalDivider(
                    color = muted,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
        }
    }
}

// ── 正文里的图片（ZCode `markdown-image.tsx` 口径：缩图 + 点开全屏预览）────────────────
//
// 只做两件事：① 拿到字节（`data:` 直接 base64，`http(s)` 拉一次，内存 LRU 防重复拉）；
// ② 解码成缩图显出来，点击进全屏预览。失败就退回 alt/URL 文本，不让消息变成空白。
private object MarkdownImageLoader {
    private const val MAX_ENTRIES = 12
    private val client = okhttp3.OkHttpClient()
    private val cache = object : LinkedHashMap<String, ByteArray>(MAX_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?) =
            size > MAX_ENTRIES
    }

    suspend fun bytes(src: String): ByteArray? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            synchronized(cache) { cache[src] }?.let { return@withContext it }
            val data = when {
                src.startsWith("data:") -> runCatching {
                    android.util.Base64.decode(src.substringAfter(','), android.util.Base64.DEFAULT)
                }.getOrNull()

                src.startsWith("http") -> runCatching {
                    client.newCall(okhttp3.Request.Builder().url(src).build()).execute().use { resp ->
                        if (resp.isSuccessful) resp.body?.bytes() else null
                    }
                }.getOrNull()

                else -> null
            }
            if (data != null && data.isNotEmpty()) synchronized(cache) { cache[src] = data }
            data
        }
}

/** 按最大边长采样解码（不直接把原图丢给 GPU，否则大图一水就爆内存）。 */
private fun decodeImageBytes(raw: ByteArray, maxDim: Int): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / sample > maxDim || bounds.outHeight / sample > maxDim) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    return runCatching {
        BitmapFactory.decodeByteArray(raw, 0, raw.size, opts)?.asImageBitmap()
    }.getOrNull()
}

@Composable
private fun MarkdownImage(src: String, alt: String) {
    var bytes by remember(src) { mutableStateOf<ByteArray?>(null) }
    var failed by remember(src) { mutableStateOf(false) }
    var viewing by remember(src) { mutableStateOf(false) }

    LaunchedEffect(src) {
        val loaded = MarkdownImageLoader.bytes(src)
        if (loaded == null || loaded.isEmpty()) failed = true else bytes = loaded
    }

    val bitmap = remember(bytes) { bytes?.let { decodeImageBytes(it, 1600) } }

    if (failed || (bytes != null && bitmap == null)) {
        Text(
            text = alt.ifBlank { src },
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = TerminalColors.muted(),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        return
    }
    if (bitmap == null) {
        Text("图片加载中…", style = MaterialTheme.typography.bodySmall, color = TerminalColors.muted())
        return
    }

    Image(
        bitmap = bitmap,
        contentDescription = alt.ifBlank { "图片" },
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 320.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable { viewing = true },
    )

    // 全屏预览：黑底、点任意处关掉（弹窗占满屏，不拿系统默认宽度免得被压小）
    if (viewing) {
        Dialog(
            onDismissRequest = { viewing = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .clickable { viewing = false },
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    bitmap = bitmap,
                    contentDescription = alt.ifBlank { "图片" },
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().clickable { viewing = false },
                )
            }
        }
    }
}

/** 表格列宽计算结果（单位 dp）。`scroll=true` 表示总宽超屏、需要横向滑动。 */
internal data class TableWidths(val widths: List<Float>, val scroll: Boolean)

/**
 * 表格列宽：**词底线 + 两次水位法**（方案 D，2026-10-21 离线验证定稿）。
 *
 * 纯函数，单测在 `TableWidthsTest`；选型依据见 `termux/table-width-lab.py`
 * （10 个固定 case + 200 例 fuzz，代价 = 剁词×5 + 总行数，方案 D 只在 oracle 之上 1.2%）。
 *
 * 规则：
 * 1. 自然宽装得下 → 按自然宽**比例补齐撑满**（两侧对齐，不滚）；
 * 2. 装不下 →
 *    a. `base = maxMinFair(minContent)`：词底线做水位（短列拿满自己的词宽，
 *       宽列分剩下的）——**不要**把底线整体等比缩（那会让短列白白挨饿，是最差的一版）；
 *    b. `extra = maxMinFair(natural − base)`：剩余宽度按「还差多少才不换行」再水位一次；
 * 3. 保险丝：**整张表本来就摆不下**（均分额 `usable/columns` < 44dp）⇒ 改回自然宽
 *    （夹到 [44, 132]）+ `scroll=true`，交给外层横滚。
 *
 * 四个已踩过的坑（别写回这几种）：
 * - `share = usable/columns` 再按自然宽分“剩余”：share 已把可用宽度分完 ⇒ 剩余恒为 0 ⇒ **平均分配**；
 * - 只用「最长一格的自然宽」定窄列：键列里一个长标识符就能占去一半
 *   （`app AppState.onPiRecord` ≈ 204dp，而均分额 203.5dp ⇒ 两列又拉平 50/50）；
 * - 词底线之和超预算时**整体等比缩**：短列被拖下水，剁词最多（代价 214 vs 163）；
 * - 保险丝判据写成「某一列 < 44dp」：**误判正解**。用户 2026-10-21 23:56 的
 *   `时间 | 路径` 表就是被它坑的：水填法给出 36/262（= oracle，代价 46），
 *   却因为第一列 36 < 44 触发保险丝，两列被夹成 79/132（共 211dp，白扔 87dp）
 *   ⇒ 长路径挤在 132dp 里疯狂换行（代价 85，11 个词被剁）。
 *   判据换成 `usable/columns < minReadable`（≈“列多到摆不下”）之后这张表不再触发。
 */
internal fun tableWidths(
    natural: List<Float>,
    minContent: List<Float>,
    usable: Float,
    minReadable: Float = 44f,
): TableWidths {
    val n = natural.size
    if (n == 0) return TableWidths(emptyList(), false)
    if (n == 1) return TableWidths(listOf(usable), false)
    val naturalSum = natural.sum()
    if (naturalSum <= 0f) return TableWidths(List(n) { usable / n }, false)
    // ① 装得下：按自然宽比例补齐撑满
    if (naturalSum <= usable) {
        val extra = usable - naturalSum
        return TableWidths(natural.map { it + extra * (it / naturalSum) }, false)
    }
    // ② 词底线做一次水位（短列拿满、宽列分剩下的）
    val floors = (0 until n).map { minOf(minContent.getOrElse(it) { 0f }.coerceAtLeast(0f), natural[it]) }
    val base = maxMinFair(floors, usable)
    // ③ 剩余宽度按“还差多少才不换行”再水位一次
    val demand = (0 until n).map { (natural[it] - base[it]).coerceAtLeast(0f) }
    val extra = maxMinFair(demand, usable - base.sum())
    val widths = (0 until n).map { base[it] + extra[it] }
    // ④ 保险丝：**整张表本来就摆不下**（均分额都不到可读下限）⇒ 自然宽（封顶）+ 横滚。
    //
    // 判据是**结构性**的（`usable / n`），不是「某一列 < minReadable」：后者会把
    // 「短列只拿自己的词宽、宽列吃掉其余」这种**正解**当成放不下（见函数头注释里的真机案例）。
    // 注：走到这里就一定有 `n × minReadable > usable`（封顶后必然溢出）⇒ 不需要再补余量。
    if (usable / n >= minReadable) return TableWidths(widths, false)
    return TableWidths(natural.map { it.coerceIn(minReadable, minReadable * 3f) }, true)
}

/**
 * 把 [budget] 在若干「需求」之间做 max-min fair 分配（水位法）：
 * 需求小的先满足，剩下的在需求大的之间等分。返回实际分配量（每项 ≤ 其需求）。
 */
internal fun maxMinFair(demand: List<Float>, budget: Float): List<Float> {
    val out = MutableList(demand.size) { 0f }
    if (budget <= 0f || demand.isEmpty()) return out
    var remaining = budget
    var pending = demand.indices.filter { demand[it] > 1e-4f }
    while (pending.isNotEmpty() && remaining > 1e-4f) {
        val equal = remaining / pending.size
        val narrow = pending.filter { demand[it] <= equal }
        if (narrow.isEmpty()) {
            pending.forEach { out[it] = equal }
            break
        }
        narrow.forEach { out[it] = demand[it]; remaining -= demand[it] }
        val taken = narrow.toSet()
        pending = pending.filter { it !in taken }
    }
    return out
}
