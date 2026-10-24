package id.harness.pipocket.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.harness.pipocket.AppState
import id.harness.pipocket.ImageRef
import id.harness.pipocket.R
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.math.ceil

/**
 * 「查看交付」：文件 / 目录 / 图片的**只读**查看器。用户 2026-10-24：「看图看字都太简陋」。
 *
 * 参照：
 * - **Material 3 · Full-screen dialog**（m3.material.io/components/dialogs/guidelines）：
 *   ✕ 是唯一导航；其余放动作（复制、换行、搜索）。
 * - **sora-editor**（github.com/Rosemoe/sora-editor）里阅读代码该有的东西，挑了只读能用的：
 *   **行号 gutter**、**自动换行开关**、**搜索定位**、**sticky 行号范围**（这里做成底部一行
 *   「第 X–Y 行 / 共 N 行」）、等宽 + 语法高亮；编辑类功能（自动缩进/撤销/补全）不适用。
 *
 * 数据来自桥 `GET /v1/file?path=…`（桥侧仅允许 home 或工作目录，>8MB 直接拒），
 * 读不到时走「当前机器」的 SSH 别名兜底（`AppState.openPath` 里做）。
 */
@Composable
fun FileViewerOverlay() {
    val view = AppState.fileView ?: return
    val path = view.optString("path")
    val ok = view.optBoolean("ok", false)
    val kind = view.optString("kind")
    val name = view.optString("name").ifBlank { path.substringAfterLast('/').ifBlank { path } }
    val clipboard = LocalClipboardManager.current
    var copied by remember(view) { mutableStateOf(false) }

    // 复制：文本复制正文，图片/目录复制路径（比给个哑按钮有用）
    fun copy() {
        val payload = when {
            kind == "text" -> view.optString("text")
            else -> path
        }
        if (payload.isNotBlank()) {
            clipboard.setText(AnnotatedString(payload))
            copied = true
        }
    }

    ImmersiveSystemBars()
    BackHandler { AppState.closeFileView() }
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(modifier = Modifier.fillMaxSize()) {
                ViewerBar(
                    title = name,
                    subtitle = path,
                    onClose = { AppState.closeFileView() },
                    onCopy = { copy() },
                    copied = copied,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

                when {
                    !ok -> ErrorState(
                        message = view.optString("error"),
                        path = path,
                        onRetry = { AppState.openPath(path) },
                    )
                    kind == "dir" -> DirList(view)
                    kind == "image" -> ImagePane(view, name, path)
                    else -> TextPane(view, path)
                }
        }
    }
}

/** 顶部条：✕ + 文件名 + 路径 + 复制（M3 全屏对话框的导航口径）。 */
@Composable
private fun ViewerBar(
    title: String,
    subtitle: String,
    onClose: () -> Unit,
    onCopy: () -> Unit,
    copied: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 8.dp, top = 10.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) {
            Icon(painterResource(R.drawable.ic_close), contentDescription = "关闭", modifier = Modifier.size(22.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotBlank() && subtitle != title) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = TerminalColors.muted(),
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                )
            }
        }
        TextButton(onClick = onCopy) {
            Icon(
                painterResource(if (copied) R.drawable.ic_check else R.drawable.ic_file),
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(if (copied) "已复制" else "复制", style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** 小徽标（语言 / 体积 / 行数…）：M3 里相当于 assist chip 的轻量版。 */
@Composable
private fun MetaChip(text: String, onClick: (() -> Unit)? = null) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = if (onClick != null) Modifier.clip(RoundedCornerShape(8.dp)).clickable { onClick() } else Modifier,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun ErrorState(message: String, path: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painterResource(R.drawable.ic_info),
            contentDescription = null,
            modifier = Modifier.size(28.dp),
            tint = MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.height(10.dp))
        Text("打不开这个文件", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(6.dp))
        Text(
            message.ifBlank { "未知原因" },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            path,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = TerminalColors.muted(),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(14.dp))
        TextButton(onClick = onRetry) { Text("重试") }
    }
}

/** 目录：可点进去继续看的列表（文件夹 / 文件各有图标与箭头）。 */
@Composable
private fun DirList(view: JSONObject) {
    val path = view.optString("path")
    val entries = view.optJSONArray("entries")
    val list = buildList {
        if (entries != null) {
            for (i in 0 until entries.length()) {
                val entry = entries.optJSONObject(i) ?: continue
                add(entry.optString("name") to entry.optBoolean("dir"))
            }
        }
    }
    if (list.isEmpty()) {
        EmptyHint("这个目录是空的（或没有可读的条目）")
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp)) {
        items(list) { (name, dir) ->
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable {
                        AppState.openPath((if (path.endsWith("/")) path else "$path/") + name)
                    },
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painterResource(if (dir) R.drawable.ic_folder else R.drawable.ic_file),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = if (dir) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        name,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (dir) {
                        Icon(
                            painterResource(R.drawable.ic_chevron_right),
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = TerminalColors.muted(),
                        )
                    }
                }
            }
        }
    }
}

/** 图片：内联显示 + 元信息；点图进全屏看图器（M3 里“先预览再全屏”的常见做法）。 */
@Composable
private fun ImagePane(view: JSONObject, name: String, path: String) {
    val base64 = view.optString("base64")
    val mime = view.optString("mime", "image/png")
    val bitmap = rememberDecodedBitmap(base64, 2048)
    var viewing by remember(path) { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 10.dp)) {
            bitmap?.let { MetaChip("${it.width}×${it.height}") }
            val bytes = view.optLong("size", 0)
            if (bytes > 0) MetaChip(ImageViewerMath.humanBytes(bytes))
            MetaChip(mime.substringAfter('/', "image"))
        }
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth().weight(1f),
        ) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                when {
                    base64.isBlank() -> Text(
                        "这个文件不像图片（或桥没有返回数据）",
                        style = MaterialTheme.typography.bodySmall,
                        color = TerminalColors.muted(),
                    )
                    bitmap == null -> LinearProgressIndicator(
                        modifier = Modifier.width(140.dp),
                        color = MaterialTheme.colorScheme.primary,
                    )
                    else -> InlineZoomableImage(
                        bitmap = bitmap,
                        contentDescription = name,
                        onTap = { viewing = true },
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(6.dp)
                            .clip(RoundedCornerShape(12.dp)),
                    )
                }
            }
        }
        Text(
            "点一下看全屏；这里就能捏合缩放（0.25×–4×）、双击放大、拖动",
            style = MaterialTheme.typography.labelSmall,
            color = TerminalColors.footer(),
            modifier = Modifier.padding(top = 8.dp),
        )
    }

    // 点图进全屏：走全局 overlay（FileViewer 本身也在 overlay 里，看图在它上面一层）
    if (viewing && bitmap != null) {
        AppState.openImageViewer(listOf(ImageRef(mime, base64)), 0, listOf(name))
        viewing = false
    }
}

/**
 * 文本/代码：行号 gutter + 换行开关 + 搜索定位 + 语法高亮 + 底部行号范围。
 *
 * 行号与正文共用一个纵向滚动状态：gutter 只做 `translationY = -scroll`（不做自己的滚动容器），
 * 这样换行/不换行都不会两栏错位；不换行时正文再叠一层横向滚动。
 */
@Composable
private fun TextPane(view: JSONObject, path: String) {
    val text = view.optString("text").ifBlank { "（空文件，或不是可读的文本类型）" }
    val lang = CodeHighlight.languageOf(path)
    val colors = CodeColors.of()
    val highlighted = remember(text, lang, colors) { CodeHighlight.annotated(text, lang, colors) }
    val lineCount = remember(text) { text.count { it == '\n' } + 1 }
    val truncated = view.optInt("size", 0) > 0 && text.length >= CodeHighlight.MAX_HIGHLIGHT_CHARS

    var wrap by rememberSaveable(path) { mutableStateOf(false) }
    var searching by rememberSaveable(path) { mutableStateOf(false) }
    var query by rememberSaveable(path) { mutableStateOf("") }
    var matchIndex by remember(path) { mutableStateOf(0) }

    val matches = remember(text, query) {
        if (query.isBlank()) emptyList() else FileSearch.lineHits(text, query)
    }
    val vScroll = rememberScrollState()
    val hScroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val lineHeight = 17.sp
    val codeStyle = MaterialTheme.typography.bodySmall.copy(
        fontFamily = FontFamily.Monospace,
        fontSize = 12.5.sp,
        lineHeight = lineHeight,
    )
    val charsVisible = remember(matches, matchIndex, text, wrap) {
        // 用「行号 × 行高」估算滚动位置（行高固定，够准；换行模式下不跳）
        if (matches.isEmpty() || wrap) null
        else (matches[matchIndex.coerceIn(0, matches.lastIndex)] - 1).coerceAtLeast(0)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 徽标行 + 换行/搜索开关
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            MetaChip(if (lang != CodeLang.Plain) lang.displayName else "文本")
            if (lineCount > 1) MetaChip("$lineCount 行")
            val bytes = view.optLong("size", 0)
            if (bytes > 0) MetaChip(ImageViewerMath.humanBytes(bytes))
            Spacer(Modifier.weight(1f))
            MetaChip(if (wrap) "换行：开" else "换行：关") { wrap = !wrap }
            MetaChip("查找") {
                searching = !searching
                if (!searching) { query = ""; matchIndex = 0 }
            }
        }

        if (searching) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it; matchIndex = 0 },
                singleLine = true,
                textStyle = codeStyle,
                placeholder = { Text("在这个文件里找…", style = MaterialTheme.typography.bodySmall) },
                leadingIcon = {
                    Icon(painterResource(R.drawable.ic_search), contentDescription = null, modifier = Modifier.size(18.dp))
                },
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (matches.isEmpty()) "0 / 0" else "${matchIndex + 1} / ${matches.size}",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                            color = TerminalColors.muted(),
                        )
                        IconButton(
                            onClick = {
                                if (matches.isNotEmpty()) {
                                    matchIndex = (matchIndex + 1) % matches.size
                                    charsVisible?.let { line ->
                                        scope.launch { vScroll.animateScrollTo((line * 60)) }
                                    }
                                }
                            },
                            enabled = matches.isNotEmpty(),
                        ) {
                            Icon(
                                painterResource(R.drawable.ic_chevron_right),
                                contentDescription = "下一个匹配",
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
            )
        }

        Spacer(Modifier.height(6.dp))

        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 10.dp),
        ) {
            Row(modifier = Modifier.fillMaxSize()) {
                // 行号 gutter（不换行时才有意义；换行后行号会与视觉行错位，直接收起）
                if (!wrap) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .padding(vertical = 8.dp)
                            .clipToBounds(),
                    ) {
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 8.dp)
                                .graphicsLayer { translationY = -vScroll.value.toFloat() },
                        ) {
                            Text(
                                text = FileSearch.gutterNumbers(lineCount),
                                style = codeStyle.copy(color = TerminalColors.footer()),
                                textAlign = TextAlign.End,
                            )
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .verticalScroll(vScroll)
                        .then(if (wrap) Modifier else Modifier.horizontalScroll(hScroll)),
                ) {
                    SelectionContainer {
                        Text(
                            highlighted,
                            style = codeStyle.copy(color = TerminalColors.body()),
                            modifier = Modifier.padding(vertical = 8.dp),
                            softWrap = wrap,
                        )
                    }
                }
            }
        }

        // sticky 行号范围（滚动时按行高换算当前看到哪几行）
        val firstLine = (vScroll.value / 60).toInt() + 1
        val visibleLines = ceil((vScroll.viewportSize / 60f)).toInt().coerceAtLeast(1)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "第 ${firstLine.coerceAtMost(lineCount)}–${(firstLine + visibleLines - 1).coerceAtMost(lineCount)} 行 / 共 $lineCount 行",
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = TerminalColors.footer(),
            )
            if (truncated) {
                Text(
                    "大文件：已按纯文本显示前 ${CodeHighlight.MAX_HIGHLIGHT_CHARS} 字符",
                    style = MaterialTheme.typography.labelSmall,
                    color = TerminalColors.footer(),
                )
            }
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = TerminalColors.muted(), textAlign = TextAlign.Center)
    }
}

/** 文件内查找 + 行号生成（纯函数，JVM 单测覆盖）。 */
internal object FileSearch {
    /** 每一处命中的**行号**（1-based），同一行多次命中只算第一次。 */
    fun lineHits(text: String, query: String): List<Int> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val out = LinkedHashSet<Int>()
        var line = 1
        var index = 0
        while (true) {
            val at = text.indexOf(q, index, ignoreCase = true)
            if (at < 0) break
            line += text.substring(index, at).count { it == '\n' }
            out.add(line)
            index = at + 1
        }
        return out.toList()
    }

    /** 行号文本：右对齐、等宽，位数按总行数（`  9` / ` 10` / `100`）。 */
    fun gutterNumbers(lineCount: Int): String {
        if (lineCount <= 0) return ""
        val width = lineCount.toString().length
        return (1..lineCount).joinToString("\n") { it.toString().padStart(width) }
    }
}
