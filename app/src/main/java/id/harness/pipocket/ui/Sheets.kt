@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package id.harness.pipocket.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.clip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import id.harness.pipocket.AppState
import id.harness.pipocket.ModelRef
import id.harness.pipocket.BuildConfig
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.res.painterResource
import id.harness.pipocket.R

/**
 * 二级页面的统一外壳：**抽屉（ModalBottomSheet）但高度接近顶部**。
 *
 * 口径（用户 2026-10-19 二次确认）：不用整页，也不要用"只露半屏"的抽屉 ——
 * 打开就**直接展开到接近顶部**（`skipPartiallyExpanded = true` + 内容占 93% 高度），
 * 想要整页的视野、又保留抽屉「下滑/返回即可关掉、背景还看得见这是从哪冒出来的」的手感。
 *
 * NN/g 那几条仍然照做：Back 能关、有明确关闭按钮、不叠抽屉、不在抽屉里再做长表单的分页。
 */
/**
 * 敏感输入框（token / 密码）：**默认掩码**，右侧一个小按钮切换「显示/隐藏」。
 *
 * 用户 2026-10-21：Gitea token 之前是明文显示，看不出来但也容易被旁人看到；
 * 统一走这个组件，默认 hide，想核对时点一下再看。
 */
@Composable
internal fun SecretField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    minLines: Int = 1,
) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = singleLine,
        minLines = minLines,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Text(if (visible) "隐藏" else "显示", style = MaterialTheme.typography.labelSmall)
            }
        },
        modifier = modifier,
    )
}

/**
 * 按钮文字：**永远单行**，宽度不够就按 1sp 步长缩字号（16 → 11sp），实在不够才省略。
 *
 * 为什么不用默认 Text：中文标签（「从零部署（本机 Termux）」「强制拉起本机 pi（Termux）」这类）
 * 在按钮里会**换行**，于是按钮从 40dp 变两行高，一排按钮全部错位 —— 用户 2026-10-20 指出不美观。
 * 这里用 BasicText 的 autoSize 自动缩号，保证任何屏幕/字体缩放下都不换行。
 */
@Composable
internal fun ButtonLabel(text: String, maxSp: Int = 16, minSp: Int = 11) {
    BasicText(
        text = text,
        maxLines = 1,
        softWrap = false,
        autoSize = TextAutoSize.StepBased(
            minFontSize = minSp.sp,
            maxFontSize = maxSp.sp,
            stepSize = 1.sp,
        ),
        style = MaterialTheme.typography.labelLarge.copy(
            color = LocalContentColor.current,
            textAlign = TextAlign.Center,
        ),
    )
}

@Composable
internal fun SheetScaffold(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    BackHandler { onDismiss() }
    val sheetDensity = androidx.compose.ui.platform.LocalDensity.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides sheetDensity) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // 接近顶部：留一点点，让人一眼看出「这是从底部起来的层」，又基本是整屏可用
                .fillMaxHeight(0.93f),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (LocalSettingsChild.current) {
                    IconButton(onClick = onDismiss) {
                        Icon(painterResource(R.drawable.ic_back), contentDescription = "返回设置")
                    }
                }
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (!LocalSettingsChild.current) {
                    IconButton(onClick = onDismiss) {
                        Icon(painterResource(R.drawable.ic_close), contentDescription = "关闭")
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            SelectionContainer {
                Column(modifier = Modifier.fillMaxWidth().weight(1f)) { content() }
            }
        }
        }
    }
}

/**
 * **短选择器**专用的底部小抽屉（与 [SheetScaffold] 的高抽屉区分开）。
 *
 * 只在同时满足这几条时用：① 纯单选、无输入框；② 项数少（≤ ~12，最多露半屏）；
 * ③ 选完立刻关闭；④ 用户需要**边看聊天上下文边选**（模型/思考等级就是）。
 * 长表单、多节配置、长列表、还会再往下跳的，一律用 [SheetScaffold]。
 */
@Composable
internal fun QuickSheet(
    title: String,
    onDismiss: () -> Unit,
    /** 内容很长的面板（模型列表）要**整屏展开**，否则底部动作条会被顶到屏幕外。 */
    fullHeight: Boolean = false,
    content: @Composable () -> Unit,
) {
    BackHandler { onDismiss() }
    // skipPartiallyExpanded：模态抽屉直接开到最高，把高度让给内容
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = fullHeight)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = onDismiss) {
                Icon(painterResource(R.drawable.ic_close), contentDescription = "关闭")
            }
        }
        // 注意：`SelectionContainer` 内部把**多个子元素叠在同一个位置**（它不是 Column）——
        // 直接把 content() 放进去，所有行会重叠（用户 2026-10-22 报的"文字重叠"就是这个）。
        // 先包一层 Column，再交给选区容器：既能整张面板长按复制，又不会叠在一起。
        SelectionContainer {
            Column(modifier = Modifier.fillMaxWidth()) { content() }
        }
    }
}

/**
 * 模型选择抽屉。
 *
 * 按 `docs/UI-DESIGN-GUIDELINES.md` 的规范重排（用户 2026-10-22：「UI 和文字不美观，重读设计规范重新设计」）：
 *  · **层次**：标题 → 当前模型（tonal 卡片，含快切）→ 搜索框 → 分组列表；每层用 M3 间距尺标（16/12/8）；
 *  · **字号**：模型名 `bodyLarge`，辅助信息 `bodySmall`+muted，分组标题 `labelLarge`+muted
 *    （规范：Emphasized 只给主角位，列表里不用）；
 *  · **"当前"用勾选图标，不用文字**（M3 列表的选择态就是 trailing check；文字会挤掉模型名）；
 *  · **点击先 clip 再 clickable**（R9：否则水波纹是矩形）；
 *  · **行高 56dp 起**、名字允许两行（长 id 不再只剩省略号）。
 */
@Composable
fun ModelSheet(onDismiss: () -> Unit) {
    val current = AppState.info?.model.orEmpty()
    val currentProvider = AppState.info?.provider.orEmpty()
    var query by remember { mutableStateOf("") }
    // 用户 2026-10-23：「切换模型的时候需要加一个确认按钮」——
    // 点一行只是**待选**（pending），要按底部「确认切换」才真的发给 pi。
    var pending by remember { mutableStateOf<ModelRef?>(null) }
    val filtered = remember(AppState.models, query) { filterModels(AppState.models, query) }
    val groups = remember(filtered) { filtered.groupBy { it.provider.ifBlank { "其它" } } }
    val countLabel = if (query.isBlank()) {
        "${AppState.models.size} 个"
    } else {
        "${filtered.size} / ${AppState.models.size} 个"
    }

    QuickSheet(title = "模型", onDismiss = onDismiss, fullHeight = true) {
        // ① 当前模型：tonal 卡片 + 快切（对齐终端的 app.model.cycleForward/Backward）
        if (AppState.models.isNotEmpty()) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                Row(
                    modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "当前模型",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            AppState.info?.modelDisplay?.takeIf { it.isNotBlank() }
                                ?: current.ifBlank { "未知" },
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    // 快切用图标按钮（文字按钮在标题旁边会显得"两个标题"）
                    FilledTonalIconButton(
                        onClick = {
                            pending = neighborModel(
                                AppState.models, current, currentProvider, backward = true,
                            )
                        },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_chevron_right),
                            contentDescription = "上一个模型",
                            modifier = Modifier.size(18.dp).rotate(180f),
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    FilledTonalIconButton(
                        onClick = {
                            pending = neighborModel(AppState.models, current, currentProvider, backward = false)
                        },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_chevron_right),
                            contentDescription = "下一个模型",
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }

        // ② 搜索：M3 搜索框（前置放大镜；有内容时后置清空）
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium,
            placeholder = {
                Text("搜索模型或厂商", style = MaterialTheme.typography.bodyMedium)
            },
            leadingIcon = {
                Icon(
                    painterResource(R.drawable.ic_search),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }, modifier = Modifier.size(40.dp)) {
                        Icon(
                            painterResource(R.drawable.ic_close),
                            contentDescription = "清空",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            },
            shape = MaterialTheme.shapes.large,
        )

        // ③ 计数（搜索时才有意义；不搜索时只是告诉总量）
        Text(
            countLabel,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 20.dp, bottom = 4.dp),
        )

        if (filtered.isEmpty()) {
            Text(
                modelSearchEmptyHint(AppState.models.size, query),
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // 列表高度要**给底部确认条留位置**：以前 max 480dp，确认条被顶到屏幕外，
        // 等于"选了模型但按钮看不到"（用户 2026-10-23 要的确认按钮就废了）。
        LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 340.dp)) {
            groups.forEach { (provider, models) ->
                item(key = "h-$provider") {
                    Text(
                        provider,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp),
                    )
                }
                items(models, key = { it.provider + "/" + it.model }) { ref ->
                    ModelRow(
                        ref = ref,
                        isCurrent = isCurrentModel(ref, current, currentProvider),
                        isPending = sameModel(pending, ref),
                        onClick = { pending = ref },
                    )
                }
            }
        }
        // ④ 确认条：**没有它不切**（用户 2026-10-23）。固定在列表下方，不用滚到底。
        ModelConfirmBar(
            current = current,
            currentProvider = currentProvider,
            pending = pending,
            onCancel = { pending = null },
            onConfirm = {
                pending?.let { AppState.chooseModel(it) }
                onDismiss()
            },
        )
        Spacer(Modifier.height(12.dp))
    }
}

/** 「待切换 → 确认」条：左边显示将要切到哪一个，右边取消 / 确认（M3 动作区右对齐）。 */
@Composable
private fun ModelConfirmBar(
    current: String,
    currentProvider: String,
    pending: ModelRef?,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    val unchanged = pending == null || isCurrentModel(pending, current, currentProvider)
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    if (pending == null) "选一个模型" else "将切换到",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    pending?.let { it.model } ?: "—",
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (pending != null) {
                TextButton(onClick = onCancel) { Text("取消") }
            }
            FilledTonalButton(
                onClick = onConfirm,
                enabled = !unchanged,
            ) { Text("确认切换") }
        }
    }
}

/**
 * 模型列表里的一行：名字（最多两行）+ 辅助信息（厂商 · 说明），选中态给勾 + 淡底。
 *
 * 不用 `ListItem`：它的 headline/trailing 不共享宽度，长模型名会压到右侧文字上
 * （用户 2026-10-22 的截图就是这个重叠）。这里自己排，名字用 `weight(1f)` 独占剩余宽度。
 */
@Composable
private fun ModelRow(
    ref: ModelRef,
    isCurrent: Boolean,
    isPending: Boolean,
    onClick: () -> Unit,
) {
    val shape = MaterialTheme.shapes.medium
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(shape)
            .background(
                when {
                    // 待选：比"当前"更亮一档，好和已有的勾区分开
                    isPending -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)
                    isCurrent -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                    else -> Color.Transparent
                },
            )
            .clickable(onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                ref.model,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            // label 有时就是 `provider/model`（等于把上一行又写一遍）—— 这种就别重复了
            val duplicateLabel = ref.label == ref.model ||
                ref.label == (ref.provider + "/" + ref.model) ||
                ref.label.isBlank()
            val sub = listOfNotNull(
                ref.provider.takeIf { it.isNotBlank() },
                ref.label.takeIf { !duplicateLabel },
            ).joinToString(" · ")
            if (sub.isNotBlank()) {
                Text(
                    sub,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        when {
            // 待选：给文字（勾是"当前"的语义，待选再用勾会分不清哪个是真的）
            isPending -> Text(
                "待切换",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 12.dp),
            )
            isCurrent -> Icon(
                painterResource(R.drawable.ic_check),
                contentDescription = "当前模型",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 12.dp).size(20.dp),
            )
        }
    }
}

/** 思考等级：选项少、互斥 ⇒ expressive 的 segmented button。 *//** 思考等级：选项少、互斥 ⇒ expressive 的 segmented button。 */
@Composable
fun ThinkingSheet(onDismiss: () -> Unit) {
    val info = AppState.info
    val levels = info?.levels.orEmpty()
    QuickSheet(title = "思考等级", onDismiss = onDismiss) {
        // 快切（对齐终端的 app.thinking.cycle：一个键就能往下轮）
        if (levels.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "当前：${info?.thinking?.takeIf { it.isNotBlank() } ?: "未知"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                androidx.compose.material3.TextButton(onClick = { AppState.cycleThinking() }) {
                    Text("下一个")
                }
            }
        }
        if (levels.isEmpty()) {
            Text(
                "当前模型没有可选的思考等级。",
                modifier = Modifier.padding(20.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    levels.forEachIndexed { index, level ->
                        SegmentedButton(
                            selected = level == info?.thinking,
                            onClick = {
                                AppState.setThinking(level)
                                onDismiss()
                            },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = levels.size),
                            label = { Text(level) },
                        )
                    }
                }
                Text(
                    "等级越高思考越久、越贵；拿不准就留在默认。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
fun CommandsSheet(onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val commands = AppState.commands
    SheetScaffold(title = "命令与技能（${commands.size}）", onDismiss = onDismiss) {
        if (commands.isEmpty()) {
            Text("正在读取命令列表…", modifier = Modifier.padding(20.dp), style = MaterialTheme.typography.bodyMedium)
        }
        LazyColumn(modifier = Modifier.heightIn(max = 520.dp)) {
            items(commands, key = { it.name }) { command ->
                ListItem(
                    headlineContent = { Text(command.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        if (command.description.isNotBlank()) {
                            Text(command.description, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    },
                    modifier = Modifier.fillMaxWidth().clickable { onPick(command.name) },
                )
            }
        }
    }
}

/**
 * 「会话信息」抽屉 —— 用户 2026-10-23：「应有完整上下文信息」。
 *
 * 之前这里只有机器/模型/会话 ID 这种"档案"，**上下文用量只看得到一个百分比**；
 * token 明细、缓存命中、花费全在另一个弹层里，来回点两遍才能拼出全貌。
 * 现在按 上下文 → 模型 → 会话 → 连接 四段排，弹层里出现的数字这里**都有**，还更全。
 */
@Composable
fun InfoSheet(onDismiss: () -> Unit) {
    val info = AppState.info
    SheetScaffold(title = "会话信息", onDismiss = onDismiss) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (info == null) {
                Text("读取中…", style = MaterialTheme.typography.bodyMedium)
            } else {
                // ── 上下文 ────────────────────────────────────────────────
                InfoSection("上下文")
                val percent = info.contextPercent
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        if (percent != null) "%.1f".format(percent) + "%" else "—",
                        style = MaterialTheme.typography.headlineMediumEmphasized,
                    )
                    Text(
                        "  " + formatTokens(info.contextTokens) + " / " + formatTokens(info.contextWindow) +
                            (if (info.autoCompaction) " (auto)" else ""),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
                if (percent != null) {
                    LinearProgressIndicator(
                        progress = { (percent / 100.0).toFloat().coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape),
                        gapSize = 0.dp,
                        drawStopIndicator = {},
                    )
                }
                // 终端 footer 那一串原样给（↑in ↓out R… W… CHxx% $cost）
                val usage = footerStats(
                    input = info.tokensInput,
                    output = info.tokensOutput,
                    cacheRead = info.cacheRead,
                    cacheWrite = info.cacheWrite,
                    cacheHitPercent = cacheHitPercent(info.tokensInput, info.cacheRead, info.cacheWrite),
                    cost = info.cost,
                )
                if (usage.isNotBlank()) {
                    Text(
                        usage,
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                InfoRow("输入 / 输出", "↑ " + formatTokens(info.tokensInput) + "  ↓ " + formatTokens(info.tokensOutput))
                InfoRow("缓存读 / 写", "R " + formatTokens(info.cacheRead) + "  W " + formatTokens(info.cacheWrite))
                cacheHitPercent(info.tokensInput, info.cacheRead, info.cacheWrite)?.let {
                    InfoRow("缓存命中", "%.1f".format(it) + "%")
                }
                InfoRow("累计 tokens", formatTokens(info.totalTokens))
                if (info.cost > 0) InfoRow("花费", "$" + "%.4f".format(info.cost))

                HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))

                // ── 模型 ──────────────────────────────────────────────────
                InfoSection("模型")
                InfoRow(
                    "当前",
                    modelWithProvider(info.provider, info.model, info.thinking, info.modelDisplay),
                )
                InfoRow("思考等级", info.thinking)
                if (info.levels.isNotEmpty()) InfoRow("可选等级", info.levels.joinToString(" · "))

                HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))

                // ── 会话 ──────────────────────────────────────────────────
                InfoSection("会话")
                if (info.sessionName.isNotBlank()) InfoRow("名称", info.sessionName)
                InfoRow("消息", "用户 ${info.userMessages} · 助手 ${info.assistantMessages} · 工具 ${info.toolCalls}")
                if (info.pendingCount > 0) InfoRow("排队", info.pendingCount.toString())
                InfoRow(
                    "目录",
                    listOfNotNull(
                        info.cwd.takeIf { it.isNotBlank() }?.let { shortenPath(it) },
                        gitBranchLabel(info.gitBranch),
                    ).joinToString(" "),
                )
                InfoRow("会话 ID", info.sessionId)

                HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))

                // ── 连接 ──────────────────────────────────────────────────
                InfoSection("连接")
                InfoRow("机器", AppState.currentHit?.let { "${it.host}（${scopeLabel(it.scope)}）" } ?: AppState.baseUrl)
                InfoRow("桥地址", AppState.baseUrl)
                InfoRow("状态", if (AppState.streaming) "正在跑" else "空闲")
                InfoRow("App 版本", BuildConfig.VERSION_NAME + " (" + BuildConfig.GIT_HASH + ")")
                Spacer(modifier = Modifier.size(16.dp))
            }
        }
    }
}

/** 抽屉里的分组标题（比正文小、比标签更"标题感"）。 */
@Composable
private fun InfoSection(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(0.28f),
        )
        Text(
            value.ifBlank { "—" },
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun scopeLabel(scope: id.harness.pipocket.BridgeScope): String = when (scope) {
    id.harness.pipocket.BridgeScope.Local -> "本机"
    id.harness.pipocket.BridgeScope.Lan -> "局域网"
    id.harness.pipocket.BridgeScope.Remote -> "远端"
}

/** 项目过滤：多选（选了就只看这些目录的会话）。 */
@Composable
fun ProjectsSheet(onDismiss: () -> Unit) {
    val projects = AppState.projects()
    SheetScaffold(title = "按项目过滤（已选 ${AppState.projectFilter.size}）", onDismiss = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { AppState.clearProjectFilter() }) { Text("清空") }
            }
            if (projects.isEmpty()) {
                Text("这台机器上还没有带目录的会话。", style = MaterialTheme.typography.bodyMedium)
            }
            LazyColumn(modifier = Modifier.heightIn(max = 380.dp)) {
                items(projects, key = { it }) { project ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { AppState.toggleProjectFilter(project) },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Checkbox(
                            checked = AppState.projectFilter.contains(project),
                            onCheckedChange = { AppState.toggleProjectFilter(project) },
                        )
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(shortenPath(project), style = MaterialTheme.typography.bodyLarge)
                            Text(
                                project,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
fun ConnectionSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var address by remember { mutableStateOf(AppState.baseUrl) }
    var token by remember { mutableStateOf(AppState.token) }
    var newHost by remember { mutableStateOf("") }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            AppState.launchLocalBridge(context)
        } else {
            AppState.updateLaunchMessage("没拿到 com.termux.permission.RUN_COMMAND，app 启不了 Termux 里的 pi；可在系统设置里手动授权")
        }
    }

    SheetScaffold(title = "连接设置", onDismiss = onDismiss) {
        LazyColumn(modifier = Modifier.heightIn(max = 560.dp)) {
            item {
                Column(
                    modifier = Modifier.padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        "本机＝这台手机的 Termux（127.0.0.1）。远端＝别的机器上的桥：必须用 0.0.0.0 起，并设 PI_POCKET_TOKEN。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = address,
                        onValueChange = { address = it },
                        label = { Text("桥地址") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    SecretField(
                        value = token,
                        onValueChange = { token = it; AppState.updateToken(it) },
                        label = "访问 token（本机也必填）",
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { AppState.updateBaseUrl(address); onDismiss() }) { ButtonLabel("保存并重连") }
                        TextButton(onClick = { AppState.reconnect() }) { ButtonLabel("重新连接") }
                    }
                }
            }

            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text(
                    "远端主机（Tailscale / 局域网）",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = newHost,
                        onValueChange = { newHost = it },
                        label = { Text("如 host-a:8789 或 100.x.y.z:8789") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { AppState.addSavedHost(newHost); newHost = "" }) { Text("添加") }
                }
            }
            items(AppState.savedHosts, key = { it }) { host ->
                ListItem(
                    headlineContent = { Text(host, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    trailingContent = { TextButton(onClick = { AppState.removeSavedHost(host) }) { Text("移除") } },
                    modifier = Modifier.fillMaxWidth().clickable { AppState.connectToUrl(host) },
                )
            }

            item { SshSection() }

            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Column(
                    modifier = Modifier.padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(onClick = {
                        if (AppState.termuxRunPermissionGranted(context)) {
                            AppState.launchLocalBridge(context)
                        } else {
                            permissionLauncher.launch("com.termux.permission.RUN_COMMAND")
                        }
                    }) { Text("启动本机桥（Termux）") }
                    AppState.launchMessage?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(
                        "手工起本机桥（本机也要 token，桥启动时从 ~/.pi/agent/pi-pocket.env 读）：\n" +
                            "termux-wake-lock\n" +
                            "nohup pi-pocket-bridge >> ~/.pi/agent/pi-pocket-bridge.log 2>&1 &\n\n" +
                            "远端机器：\nPI_POCKET_HOST=0.0.0.0 PI_POCKET_TOKEN=<token> nohup pi-pocket-bridge &",
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

@Composable
fun AppearanceSheet(onDismiss: () -> Unit) {
    SheetScaffold(title = "外观与字号", onDismiss = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "字号叠在系统字号之上（不取代它）。对话字号只影响消息区，顶栏 / 输入区不受影响。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // 主题：跟随系统 / 浅色 / 深色（用户 2026-10-18 要求 app 可自切）
            Text("主题", style = MaterialTheme.typography.titleSmall)
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                val modes = listOf(
                    id.harness.pipocket.ThemeMode.System to "跟随系统",
                    id.harness.pipocket.ThemeMode.Light to "浅色",
                    id.harness.pipocket.ThemeMode.Dark to "深色",
                )
                modes.forEachIndexed { index, (mode, label) ->
                    SegmentedButton(
                        selected = AppState.themeMode == mode,
                        onClick = { AppState.updateThemeMode(mode) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                        label = { Text(label) },
                    )
                }
            }

            Text("全局字号 · ${(AppState.fontScale * 100).toInt()}%", style = MaterialTheme.typography.titleSmall)
            Slider(
                value = AppState.fontScale,
                onValueChange = { AppState.updateFontScale(it) },
                valueRange = 0.8f..1.6f,
                steps = 7,
                modifier = Modifier.fillMaxWidth(),
            )

            Text("对话字号 · ${(AppState.chatFontScale * 100).toInt()}%", style = MaterialTheme.typography.titleSmall)
            Slider(
                value = AppState.chatFontScale,
                onValueChange = { AppState.updateChatFontScale(it) },
                valueRange = 0.8f..1.6f,
                steps = 7,
                modifier = Modifier.fillMaxWidth(),
            )

            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                MarkdownText(
                    text = "预览：**加粗**、`行内代码`、[链接](https://pi.dev)\n\n- 列表项\n\n```\ncode block\n```",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(12.dp),
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { AppState.resetFontScales() }) { Text("恢复默认") }
                TextButton(onClick = onDismiss) { Text("完成") }
            }
            Spacer(modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
fun RenameDialog(initial: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名会话") },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                label = { Text("名称") },
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(value.trim()) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
