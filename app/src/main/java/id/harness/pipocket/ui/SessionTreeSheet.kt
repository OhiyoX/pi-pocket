@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package id.harness.pipocket.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.harness.pipocket.AppState

/**
 * 「会话树」面板。
 *
 * ## 设计口径（照 `docs/UI-DESIGN-GUIDELINES.md` + `docs/UI-DESIGN-GUIDELINES.md` 重做）
 *
 * 上一版的可读性问题是踩了规范里明写的几条：
 *  · **每行两行文字**（正文 + 单独一行时间）—— 规范说「先减冗余支持文本，不要先减字号」，
 *    时间改成**同一行右对齐的元信息**，行数从 ~10 行变成 ~20 行；
 *  · 相邻行之间还留了 7dp —— 规范说「相邻列表行**不加间距**」，现在 0 间距 + 44dp 行高；
 *  · 连接线 1.2dp@45% 太淡，几乎看不见 —— 结构线索不能靠猜，现在 1.5dp@70%；
 *  · 类型只靠颜色区分 —— 现在每行带 **类型短标签**（我/AI/工具/压缩/标题…），
 *    正文不再重复 `user: `/`assistant: ` 这种终端前缀（那是正文的措辞，不是导航的）；
 *  · 大树（真会话 2000+ 条）没有折叠 —— 现在可以折叠，折叠行带 `+N`。
 *
 * 行高 44dp、字号 body-small(12sp)/行高 16sp 起（规范：元信息最紧只到 12sp）。
 */
@Composable
fun SessionTreeSheet(onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var filter by remember { mutableStateOf("default") }
    var search by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var collapsed by remember { mutableStateOf(setOf<String>()) }
    var confirmFork by remember { mutableStateOf<TreeRow?>(null) }

    val snapshot = AppState.treeSnapshot
    val loading = AppState.treeLoading
    val error = AppState.treeError
    val cachedDefault = AppState.treeRowsDefault
    // 默认档 + 无搜索 + 无折叠：直接用拉树时在 IO 线程建好的那份（首帧不等、也不占主线程）
    val useCache = filter == "default" && search.isBlank() && collapsed.isEmpty() && cachedDefault.isNotEmpty()
    val rows = remember(snapshot, filter, search, collapsed, useCache, cachedDefault) {
        when {
            useCache -> cachedDefault
            else -> snapshot?.let { buildTreeRows(it.nodes, it.leafId, filter, search, collapsed) } ?: emptyList()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        Column(modifier = Modifier.fillMaxWidth().fillMaxHeight(0.9f)) {
            TreeHeader(
                count = rows.size,
                total = snapshot?.nodes?.size ?: 0,
                loading = loading,
                error = error,
                searching = searching,
                search = search,
                onSearchToggle = {
                    searching = !searching
                    if (!searching) search = ""
                },
                onSearchChange = { search = it },
                onRefresh = { AppState.loadTree() },
                onClone = { AppState.cloneSession(); onDismiss() },
            )

            FilterRow(current = filter, onPick = { filter = it })

            if (!searching) {
                HorizontalDivider(color = TerminalColors.muted().copy(alpha = 0.2f))
            }

            if (rows.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text(
                        when {
                            snapshot == null && loading -> "正在拉会话树…"
                            error != null -> error
                            else -> "没有符合条件的条目"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = TerminalColors.muted(),
                    )
                }
            } else {
                LazyColumn(
                    state = rememberLazyListState(),
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    // 规范：相邻行不加间距（行高本身够呼吸）
                ) {
                    itemsIndexed(rows, key = { _, r -> r.entry.id }) { _, row ->
                        TreeRowItem(
                            row = row,
                            onFork = { confirmFork = row },
                            onToggleFold = {
                                collapsed = if (row.entry.id in collapsed) {
                                    collapsed - row.entry.id
                                } else {
                                    collapsed + row.entry.id
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    // 分叉确认（防误触：树很大时点错很贵）
    val target = confirmFork
    if (target != null) {
        AlertDialog(
            onDismissRequest = { confirmFork = null },
            title = { Text("从这条消息分叉？") },
            text = {
                Column {
                    Text(
                        target.entry.text.ifBlank { "(空消息)" },
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.size(10.dp))
                    Text(
                        "pi 会在这条消息之前切出一个新会话，并把它的内容填回输入框（和终端 /fork 一样）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = TerminalColors.muted(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmFork = null
                    AppState.forkAt(target.entry.id)
                    onDismiss()
                }) { Text("分叉") }
            },
            dismissButton = { TextButton(onClick = { confirmFork = null }) { Text("取消") } },
        )
    }
}

/** 顶部一行：标题 + 计数 + 搜索/刷新/复制三个图标按钮（都挤在一行，别占两行）。 */
@Composable
private fun TreeHeader(
    count: Int,
    total: Int,
    loading: Boolean,
    error: String?,
    searching: Boolean,
    search: String,
    onSearchToggle: () -> Unit,
    onSearchChange: (String) -> Unit,
    onRefresh: () -> Unit,
    onClone: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("会话树", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.width(8.dp))
        Text(
            text = when {
                loading -> "加载中…"
                error != null -> error
                count == total -> "$total 条"
                else -> "$count / $total 条"
            },
            style = MaterialTheme.typography.bodySmall,
            color = TerminalColors.muted(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
        } else {
            HeaderAction(if (searching) "关搜索" else "搜索", onSearchToggle)
            HeaderAction("刷新", onRefresh)
            HeaderAction("复制", onClone)
        }
    }
    if (searching) {
        SearchField(value = search, onValueChange = onSearchChange)
    }
}

/** 文字动作（不用图标：项目规范 R16/R21 明确不要装饰性图标）。 */
@Composable
private fun HeaderAction(label: String, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = TerminalColors.link(),
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    )
}

@Composable
private fun SearchField(value: String, onValueChange: (String) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Box(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                if (value.isEmpty()) {
                    Text(
                        "搜索（空格分词，全部命中）",
                        style = MaterialTheme.typography.bodySmall,
                        color = TerminalColors.muted(),
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(TerminalColors.accent()),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** 过滤档：一行小 chip（可横向滚），不再单占一大行。 */
@Composable
private fun FilterRow(current: String, onPick: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (mode in TREE_FILTERS) {
            val on = mode == current
            Surface(
                onClick = { onPick(mode) },
                shape = RoundedCornerShape(8.dp),
                color = if (on) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                contentColor = if (on) MaterialTheme.colorScheme.onSecondaryContainer else TerminalColors.muted(),
            ) {
                Text(
                    treeFilterLabel(mode),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                )
            }
        }
    }
}

/**
 * 一行树节点。
 *
 * 结构：`[连接线区][类型标签][正文 …… 时间  +N]`
 *  · 连接线用 Canvas 画（字符竖线只有字高，行间一有间距就断）；
 *  · 类型标签固定宽度 30dp（「换模型」三个字也放得下），正文才对得齐；
 *  · 时间右对齐、12sp、60% 透明（元信息，不抢正文）。
 */
@Composable
private fun TreeRowItem(row: TreeRow, onFork: () -> Unit, onToggleFold: () -> Unit) {
    val leaf = row.isLeaf
    val level = 14.dp
    val lineColor = TerminalColors.muted().copy(alpha = 0.7f)
    val leafLineColor = TerminalColors.accent().copy(alpha = 0.55f)
    val leafTint = TerminalColors.accent().copy(alpha = 0.10f)
    val rowHeight = 44.dp

    val onClick = when {
        row.forkable -> onFork
        row.hasChildren -> onToggleFold
        else -> null
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = rowHeight)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .drawBehind {
                val lw = level.toPx()
                val stroke = 1.5.dp.toPx()
                val midY = size.height / 2f
                // 当前所在行：非常淡的一层底（比整条色带克制，但一眼能找到）
                if (leaf) {
                    drawRect(
                        color = leafTint,
                        topLeft = Offset.Zero,
                        size = size,
                    )
                }
                // ① 祖先层：这一支下面还有兄弟 ⇒ 竖线贯穿整行
                row.gutters.forEachIndexed { i, hasLine ->
                    if (hasLine) {
                        val x = i * lw + lw / 2f
                        drawLine(lineColor, Offset(x, 0f), Offset(x, size.height), stroke)
                    }
                }
                // ② 本行连接线：从上一行下来到中心；`├` 延伸到下一行，`└` 止于中心
                if (row.showConnector && row.depth > 0) {
                    val x = (row.depth - 1) * lw + lw / 2f
                    val bottom = if (row.isLast) midY else size.height
                    drawLine(lineColor, Offset(x, 0f), Offset(x, bottom), stroke)
                    drawLine(lineColor, Offset(x, midY), Offset(x + lw / 2f, midY), stroke)
                }
                // ③ 当前所在的这一支：从顶部往下一条淡淡的竖线（把"我在哪条分支上"连起来）
                if (leaf) {
                    val x = row.depth * lw + lw / 2f
                    drawLine(
                        leafLineColor,
                        Offset(x, 0f),
                        Offset(x, size.height),
                        stroke,
                    )
                }
            },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = level * row.depth + 6.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (row.deepLevels > 0) {
                Text(
                    "⋯",
                    style = MaterialTheme.typography.labelMedium,
                    color = TerminalColors.muted().copy(alpha = 0.6f),
                    modifier = Modifier.width(12.dp),
                )
            }

            // 类型标签（固定宽，正文才对齐）
            Text(
                row.tag,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = tagColor(row),
                maxLines = 1,
                modifier = Modifier.width(34.dp),
            )

            Text(
                text = entrySummary(row.entry),
                style = MaterialTheme.typography.bodyMedium,
                color = bodyColor(row),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )

            if (row.hiddenCount > 0) {
                Spacer(Modifier.width(6.dp))
                Text(
                    "+${row.hiddenCount}",
                    style = MaterialTheme.typography.labelMedium,
                    color = TerminalColors.link(),
                )
            }

            if (row.timeLabel.isNotBlank()) {
                Spacer(Modifier.width(8.dp))
                Text(
                    row.timeLabel.substringAfter(' '),
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    color = TerminalColors.muted().copy(alpha = 0.75f),
                    maxLines = 1,
                )
            }

            if (leaf) {
                Spacer(Modifier.width(8.dp))
                Surface(
                    color = TerminalColors.accent().copy(alpha = 0.18f),
                    shape = RoundedCornerShape(6.dp),
                ) {
                    Text(
                        "当前",
                        style = MaterialTheme.typography.labelMedium,
                        color = TerminalColors.accent(),
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                }
            }
        }
    }
}

/** 类型标签的颜色（语义色，不靠装饰）。 */
@Composable
private fun tagColor(row: TreeRow): Color {
    val e = row.entry
    return when {
        e.type == "message" && e.role == "user" -> TerminalColors.accent()
        e.type == "message" && e.role == "assistant" -> TerminalColors.success()
        e.type == "compaction" -> TerminalColors.warning()
        e.type == "branch_summary" -> TerminalColors.warning()
        else -> TerminalColors.muted()
    }
}

/** 正文颜色：用户/AI 用正文色，其余（工具/记账）压成灰。 */
@Composable
private fun bodyColor(row: TreeRow): Color {
    val e = row.entry
    return when {
        e.type == "message" && e.role == "user" -> MaterialTheme.colorScheme.onSurface
        e.type == "message" && e.role == "assistant" -> MaterialTheme.colorScheme.onSurface
        else -> TerminalColors.muted()
    }
}
