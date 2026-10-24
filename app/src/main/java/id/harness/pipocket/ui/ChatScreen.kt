@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package id.harness.pipocket.ui

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.togetherWith
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.expandVertically
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Card
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.ripple
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import id.harness.pipocket.CompactionCardInfo
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import id.harness.pipocket.AppState
import id.harness.pipocket.Connection
import id.harness.pipocket.Attachment
import id.harness.pipocket.ChatItem
import id.harness.pipocket.CommandRef
import id.harness.pipocket.ImageRef
import id.harness.pipocket.MessageParser
import id.harness.pipocket.R
import id.harness.pipocket.Outbox
import id.harness.pipocket.SessionInfo

private data class PickedImage(val attachment: Attachment, val bitmap: ImageBitmap?)

/** 输入区 pill 里的圆按钮尺寸与内边距：pill 圆角 = 半径 + 内边距 ⇒ 曲线同心（用户口径）。 */
private var lastSentText: String? = null
private var lastSentAt: Long = 0L

private val composerIconSize = 40.dp
private val composerInset = 6.dp

/**
 * 发送口径（用户 2026-10-22 改）：**Enter = 换行**，发送只走右下角的发送键
 * （流式中发送 = steer，插到本轮工具执行之后）；
 * **alt+enter = followUp（排队，跑完再做）**。之前流式时只剩一个停止按钮，等于没法 steer。
 */
private fun sendComposer(
    composer: String,
    picked: List<PickedImage>,
    commandMode: Boolean,
    followUp: Boolean,
    onSent: () -> Unit,
) {
    val raw = composer.trim()
    val text = if (commandMode && raw.isNotEmpty() && !raw.startsWith("/")) "/" + raw else raw
    if (text.isEmpty() && picked.isEmpty()) return

    // 防重复发送：输入法回车与发送键常常同一毫秒各触发一次（真机上出现过"发两条"）。
    // 同样的内容在 800ms 内只发一次；并且**先清空输入框**再发，避免第二次读到同一份内容。
    val now = System.currentTimeMillis()
    if (text == lastSentText && now - lastSentAt < 800) return
    lastSentText = text
    lastSentAt = now
    onSent()

    // `!cmd` / `!!cmd` = 终端里的 bash 模式（跑 shell，不进对话上下文的那档是 `!!`）
    parseBashInput(text)?.let { bash ->
        AppState.runBashInChat(bash.command, bash.excludeFromContext)
        return
    }

    val images = picked.map { it.attachment }
    when {
        // 命令（/xxx：扩展命令、技能、提示模板）：pi 的 RPC 里**只有 prompt 会执行它们**。
        // 但流式中 pi 又硬性要求带 streamingBehavior，否则直接报错（docs/rpc-commands.md：
        // "If the agent is streaming and no streamingBehavior is specified, the command returns an error"）。
        // 所以：跑着的时候用 prompt + streamingBehavior=steer —— 扩展命令仍会被立即执行，
        // 普通指令则排在本轮工具之后（等于终端里的 Enter）。
        text.startsWith("/") && AppState.streaming ->
            AppState.send(text, images, streamingBehavior = "steer")

        text.startsWith("/") -> AppState.send(text, images)
        !AppState.streaming -> AppState.send(text, images)
        followUp -> AppState.send(text, images, streamingBehavior = "followUp")
        else -> AppState.send(text, images, steer = true)
    }
}

/**
 * 按需解码 base64 图，并按下采样因子缩到 `maxDim` 以内。
 * 不做下采样的话，一张手机拍的图就是几十 MB 的位图，流式里每帧解一遍直接卡死。
 */
private fun decodeImage(base64: String, maxDim: Int): ImageBitmap? {
    val bytes = runCatching { Base64.decode(base64, Base64.DEFAULT) }.getOrNull() ?: return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (bounds.outWidth / sample > maxDim || bounds.outHeight / sample > maxDim) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    return runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.asImageBitmap() }.getOrNull()
}

private enum class ChatSheet { None, Models, Thinking, Commands, Info, Connection, Tree, Behavior, More }

/**
 * 对话页。三条口径：
 * 1. 顶栏用**小号标准 TopAppBar**（工作页不抢戏，大标题留给会话页）。
 * 2. 内容块：气泡 / 工具 / 提醒都是内容容器 → 容器圆角刻度；**形状形变只出现在可交互元素**上。
 * 3. 底部输入区按 M3 消息页的做法：**单一“填充式 pill 输入框”，图标内嵌，无边框、无分隔线**，
 *    用 surfaceContainerLow 轻轻分层（之前用描边款 + 分隔线 + 三层色调，视觉上显得突兀）。
 */
@Composable
fun ChatScreen(session: SessionInfo) {
    val context = LocalContext.current
    // 草稿：进会话先回填上次没发出去的内容；改动即落盘（闪退也不丢）
    var composer by remember(session.file) { mutableStateOf(AppState.draftFor(session.file)) }
    // 主操作键「按住上滑」时借用占位那格显示手势提示（null = 不显示）
    var slideHint by remember { mutableStateOf<ComposerHint?>(null) }
    LaunchedEffect(session.file) { composer = AppState.draftFor(session.file) }
    LaunchedEffect(composer, session.file) { AppState.saveDraft(session.file, composer) }
    // 「命令模式」：点 / 按钮后不往输入框塞字符，只把按钮点亮 + 出候选（用户 2026-10-18 口径）
    var commandMode by remember { mutableStateOf(false) }
    val composerFocus = remember { FocusRequester() }
    // 进入命令模式就把焦点放回输入框，否则接着打字会没反应（实测踩到）
    LaunchedEffect(commandMode) {
        if (commandMode) runCatching { composerFocus.requestFocus() }
    }
    var picked by remember { mutableStateOf<List<PickedImage>>(emptyList()) }
    var sheet by remember { mutableStateOf(ChatSheet.None) }
    var expandedOpen by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var renameOpen by remember { mutableStateOf(false) }

    // 菜单动作的唯一分发点：锚定菜单和「更多」面板都走这里。
    // 好处是同一入口只有一个实现（以前两处各写一遍就会漂），也方便以后加动作。
    val runChatAction: (ChatAction) -> Unit = remember(session.file) {
        { action ->
            when (action) {
                ChatAction.Models -> { AppState.loadModels(); sheet = ChatSheet.Models }
                ChatAction.Thinking -> { AppState.refreshInfo(); sheet = ChatSheet.Thinking }
                ChatAction.Tree -> { AppState.loadTree(); sheet = ChatSheet.Tree }
                ChatAction.Compact -> AppState.compact()
                ChatAction.CopyLast -> AppState.copyLastAnswer()
                ChatAction.Export -> AppState.exportSession()
                ChatAction.More -> sheet = ChatSheet.More
                ChatAction.Rename -> renameOpen = true
                ChatAction.Reload -> AppState.reloadSession(session.file)
                ChatAction.AppInject -> AppState.openSettingsPage(id.harness.pipocket.SettingsPage.AppProfile, fromSettings = false)
                ChatAction.Commands -> { AppState.loadCommands(); sheet = ChatSheet.Commands }
                ChatAction.Shell -> AppState.openShell()
                ChatAction.Files -> AppState.openFilesForSession(session.cwd)
                ChatAction.Behavior -> { AppState.loadBehavior(); sheet = ChatSheet.Behavior }
                ChatAction.Connection -> sheet = ChatSheet.Connection
                ChatAction.Settings -> { AppState.settingsOpen = true }
            }
        }
    }

    // 斜杠命令补全需要命令清单
    LaunchedEffect(session.file) {
        AppState.loadCommands()
        AppState.loadModels()
    }

    // 扩展可能预填输入框（set_editor_text）
    LaunchedEffect(AppState.editorPrefill) {
        AppState.consumeEditorPrefill()?.let { composer = it }
    }

    // app 重启后可能"恢复了会话但没真正打开它"（桥那边没有绑定会话 → /v1/messages 返回空）：
    // 进会话时若当前显示的不是这个会话，主动 open 一次（同一会话不会 switch，也不打断正在跑的轮）。
    // 注意判断条件是「当前会话」而不是「items 是否为空」—— items 可能还装着上一个会话的内容。
    LaunchedEffect(session.file) {
        if (AppState.current?.file != session.file) AppState.openSession(session)
        else if (AppState.items.isEmpty()) AppState.openSession(session)
    }

    // 进会话就拉一次 pi 的命令清单（扩展命令 / 技能 / 提示模板）：
    // 不拉的话斜杠预测只有内置兜底那几条，`/rel` 这种当然没预测。
    LaunchedEffect(session.file) { AppState.loadCommands() }

    // 「工作中」与 pi 真实状态对齐：**轮询搬到 AppState 的 watchdog**（markStreaming(true) 起每 5s 问一次
    // get_state）。以前放在这里用 `isWorking` 当 key：状态一抖协程就重启，4s 永远等不到 ⇒
    // `info.isStreaming` 一直是旧值，闪烁自己持续下去。而且它只在会话页跑，离开页面就没兜底了。

    // 列数变了（字号/转屏）⇒ pi 的折行点变了：重拉一次历史。不重拉就会"pi 按旧列数折、
    // app 按新宽度再折" ⇒ 双层折行（用户 2026-10-21：「内容显示还是有问题」）。
    var lastCols by remember { mutableIntStateOf(0) }
    LaunchedEffect(AppState.termCols) {
        val c = AppState.termCols
        if (c > 0 && lastCols != 0 && c != lastCols) AppState.reloadForCols()
        if (c > 0) lastCols = c
    }

    val items = AppState.items
    val listState = rememberLazyListState()
    val tailKey = items.lastOrNull()?.key
    val tailLength = items.lastOrNull()?.text?.length ?: 0
    // 输出时自然向下滚动（用户口径）：
    // · 手指在拖 → 不抢（"能往上翻"）
    // · 离尾巴近（≤1 条）→ `animateScrollToItem` 平滑跟随：流式每 50ms 一次的增量会一直"顺"下去，
    //   而不是一条条硬跳（这正是"输出时窗口自然向下滚动"的关键）
    // · 离得远（新消息/刚打开会话）→ 立即到位，不让人等动画
    // 贴底用 Int.MAX_VALUE/2 偏移，是因为 scrollToItem 默认把行首对齐顶部，长消息会看不到尾巴。
    val tailDragging by listState.interactionSource.collectIsDraggedAsState()
    // 除了「新行 / 正文变长」，**工具运行中的实时输出**也会让行高变化（tool_execution_update）。
    // 只按 tailKey/tailLength/size 做 key 的话，工具在跑的时候列表不会跟着上移 ⇒ 底部内容被遮住。
    // 所以把整个 items 列表（每次更新都是新实例）也算进 key，任何内容变化都重新贴底。
    val liveLen = (items.lastOrNull()?.toolLive?.length ?: 0) +
        // 运行中的终端实时块（poll / write_stdin 这类）也在长高：行数变化要算进 key，
        // 否则列表不会跟着上移（用户：动态显示的工具要像终端一样同步）
        (items.lastOrNull()?.termResultCollapsed?.size ?: 0)
    // 底部块（状态行 / 扩展交互请求卡 / 附件预览 / 输入区）**变高**时也要重新贴底：
    // 它一变高，列表视口就变矮，不重新滚就会把最后几行吃掉（用户报的“提示区域增高时和消息重叠”）。
    var bottomBlockHeight by remember { mutableIntStateOf(0) }

    // 回到最新（终端口径，用户 2026-10-21 深夜）：
    // 规则：用户自己滑动到**非底部** ⇒ 立刻停止自动跟随，直到点「回到最新」（或自己发一条消息）。
    // 旧实现用「可见的最后一条离尾巴 ≤ 1 条」判底部 —— 流式一条长消息能长过一屏，
    // 用户往上划一点、最后一条仍然可见 ⇒ 判成“还在底部” ⇒ 下一个增量又把视图拽回去（抢滚动）。
    // 现在：① 按**像素**判底部（`canScrollForward`：还能往下滚就不是底部）；
    //       ② 用 selfScrolling 区分“谁在滚”，不把自己发起的滚动当成用户干预。
    val scope = rememberCoroutineScope()
    var followTail by remember(session.file) { mutableStateOf(true) }
    var selfScrolling by remember { mutableStateOf(false) }

    suspend fun scrollToTail(animate: Boolean) {
        if (items.isEmpty()) return
        selfScrolling = true
        try {
            if (animate) listState.animateScrollToItem(items.lastIndex, Int.MAX_VALUE / 2)
            else listState.scrollToItem(items.lastIndex, Int.MAX_VALUE / 2)
        } finally {
            selfScrolling = false
        }
    }

    // 用户手指**按住拖动**中（不含程序化滚动、也不含惯性）：一按下就停跟随。
    // 旧实现只在**松手**时才判 ⇒ 流式期间内容一直在变，拖动过程中自动贴底照样抢
    // （用户 2026-10-22：「偶发自动贴底还在抢用户滑动会话」；真机复现：连上滑 6 屏被拉回底部）。
    val userDragging by listState.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(userDragging) {
        if (userDragging) {
            followTail = false
        } else if (!listState.canScrollForward) {
            // 松手时本来就在底部（往下拖不动）⇒ 继续跟随，别让用户每次都要点「回到最新」
            followTail = true
        }
    }

    var lastBottomBlockHeight by remember { mutableIntStateOf(0) }
    LaunchedEffect(tailKey, tailLength, items.size, items, liveLen, bottomBlockHeight) {
        if (items.isEmpty() || tailDragging || !followTail) return@LaunchedEffect
        // 用户正在拖 / 惯性还在滑（且不是我们自己发起的滚动）⇒ 这一拍别抢
        if (userDragging || (listState.isScrollInProgress && !selfScrolling)) return@LaunchedEffect
        val last = items.lastIndex
        val visibleLast = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: last
        // 底部块高度正在变（输入区展开/收起、pill 多行变高）⇒ 动画每帧都会触发这里，
        // 每帧起一个**动画**滚动 = 自己跟自己打架（真机/模拟器都会卡）。改成瞬时贴底。
        val resizing = bottomBlockHeight != lastBottomBlockHeight
        lastBottomBlockHeight = bottomBlockHeight
        scrollToTail(animate = !resizing && last - visibleLast <= 1)
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null && picked.size < 4) {
            runCatching {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
                if (bytes.isNotEmpty()) {
                    val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                    picked = picked + PickedImage(
                        Attachment(mime, Base64.encodeToString(bytes, Base64.NO_WRAP), bytes.size),
                        bitmap,
                    )
                }
            }
        }
    }

    Scaffold(
        // 系统栏 inset 自己管：顶栏自带 status bar inset，底部输入区单独处理 IME + 导航栏
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
                navigationIcon = {
                    IconButton(onClick = { AppState.closeSession() }) {
                        Icon(painterResource(R.drawable.ic_back), contentDescription = "返回")
                    }
                },
                title = {
                    Column {
                        Text(
                            // 标题与主页/pi 自带标题保持一致（pi 的 session_info.name 优先），字号收小一档
                            text = AppState.info?.sessionName?.takeIf { it.isNotBlank() }
                                ?: session.title.ifBlank { "会话" },
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            subtitle(),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            // pi 终端 footer 那串（含 `→ 真正应答的模型`）比一行宽：允许两行，别把信息截掉
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { AppState.refreshInfo(); sheet = ChatSheet.Info }) {
                        Icon(painterResource(R.drawable.ic_info), contentDescription = "信息")
                    }
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(painterResource(R.drawable.ic_more), contentDescription = "更多")
                    }
                    DropdownMenu(shape = PopSurface.shape(), containerColor = PopSurface.container(), expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        // 菜单只放**高频**的 3 组 + 「更多」；低频项在 MoreSheet 里（用户 2026-10-22：
                        // 原先把 14 项平铺，什么都找不到）。分组用分隔线，不加标题（锚定菜单加标题更乱）。
                        val groups = quickMenu(
                            modelDisplay = AppState.info?.modelDisplay,
                            thinkingLevel = AppState.info?.thinking,
                        )
                        groups.forEachIndexed { index, group ->
                            if (index > 0) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                )
                            }
                            group.rows.forEach { row ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(row.label, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                            row.hint?.let {
                                                Text(it, style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            }
                                        }
                                    },
                                    trailingIcon = {
                                        if (row.chevron) {
                                            Icon(
                                                painter = painterResource(R.drawable.ic_chevron_right),
                                                contentDescription = null,
                                                tint = TerminalColors.muted(),
                                                modifier = Modifier.size(18.dp),
                                            )
                                        }
                                    },
                                    onClick = { menuOpen = false; runChatAction(row.action) },
                                )
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {

            // 查看交付（文件/目录/图片）由根布局的 ViewerOverlays 负责（overlay 才能真隐藏系统栏）

            // 导出完成：给出路径（可复制）、可直接打开看
            ExportDoneDialog()

            // 连接状态条：**桥断了必须看得见**。
            // 以前只有会话列表首页显示"已连接/重连中"，进到对话页就完全看不到了 ——
            // 桥一挂，界面停在那儿像"卡住的正常页面"，用户只能猜。
            // 文案口径（`connectionLabel`）：重连中不算故障，明确告诉用户"自己在恢复，不用动手"。
            ConnectionStrip()

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                // 消息 + 「压缩上下文」卡合成行清单：卡按**时间线**插（建卡时那条消息之后），
                // 不固定挂末尾 —— 否则压缩之后新消息都排在卡上面，卡片永远贴底。
                val rows = remember(items, AppState.compactionCard) {
                    buildChatRows(items, AppState.compactionCard)
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(vertical = 10.dp),
                ) {
                    itemsIndexed(rows, key = { _, row -> row.key }) { index, row ->
                        // 对话字号：只放大/缩小消息区，不动顶栏与输入区
                        val density = LocalDensity.current
                        CompositionLocalProvider(
                            LocalDensity provides Density(density.density, density.fontScale * AppState.chatFontScale),
                        ) {
                            // 用户口径：会话信息要能**划选复制** —— 整条消息包一层，thinking/footer/
                            // 工具名与输出/提醒文本都在里面（LazyColumn 的每个 item 各自成选区）
                            // 选区：**小块才包 SelectionContainer**。
                            // 为什么要设上限：`SelectionContainer` 会把 Text 换成 SelectableText，
                            // 布局时要多算一遍 intrinsic 宽度；几千行的工具输出（每行还有几十个颜色 span）
                            // 在模拟器上能把这个测量拖到秒级 —— ANR 堆栈里就是这么卡住的
                            // （`SpannableStringInternal.getSpans` → `Layout.getDesiredWidth`）。
                            // 大块不给选区：能选中几千行终端输出的场景本来就不多，卡住更糟。
                            SelectableIfSmall(row) {
                                when (row) {
                                    is ChatRow.Message -> ItemView(
                                        row.item,
                                        isLast = index == rows.lastIndex,
                                        // 逐字显现每帧会让最后一行变高：跟着微调一次贴底
                                        // （不然被顶到屏幕外的就是刚写出来的那几个字）。
                                        onStreamingGrow = {
                                            if (followTail) scope.launch { scrollToTail(animate = false) }
                                        },
                                    )
                                    is ChatRow.Compaction -> CompactionCard(row.info)
                                }
                            }
                        }
                    }
                }

                // 回到最新：用户滚离底部时悬浮在**底部中间**（用户 2026-10-22 口径；
                // 原来放右下角，跟输入区的发送键挤在一块、容易被误点）
                if (!followTail) {
                    FilledTonalIconButton(
                        onClick = {
                            followTail = true
                            scope.launch { scrollToTail(animate = true) }
                        },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(16.dp),
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        ),
                    ) {
                        Icon(painterResource(R.drawable.ic_expand), contentDescription = "回到最新")
                    }
                }
            }

            // 命令候选：命令模式下把输入框内容当作命令名；手动打 "/" 开头也认
            val slashQuery = when {
                commandMode && !composer.startsWith("/") -> "/" + composer
                else -> composer
            }
            SlashSuggestions(
                query = slashQuery,
                onPick = { picked ->
                    composer = picked + " "
                    commandMode = false
                },
            )

            // ── 底部：状态/提醒 + 附件预览 + 输入框，**同一个块** ─────────────────
            // 状态栏和交互请求当初是列表下面两个独立兄弟，流式时它们一变，列表就跟着重排；
            // 而且看起来像浮在正文上面（"重叠/遮挡"）。全部收进同一个 Surface 后，
            // 列表永远是 surface 以上、完整可见，不再被压/被遮。
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth().onSizeChanged { bottomBlockHeight = it.height },
            ) {
                Column(
                    modifier = Modifier
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .imePadding(),
                ) {
                    // 「按了发送但没确认成功」的那条：提示 + 恢复编辑 / 丢弃（用户 2026-10-23）
                    PendingDraftBar(sessionFile = session.file) { text, images ->
                        composer = Outbox.merge(composer, text)
                        if (images.isNotEmpty()) {
                            picked = picked + images.map { PickedImage(it, decodeImage(it.base64, 512)) }
                        }
                        runCatching { composerFocus.requestFocus() }
                    }

                    // 本轮在跑时的 working 行（TUI 的 working 的 app 形式）
                    WorkingBar()

                    // 正在自动重试（pi 的 auto_retry_start）：终端是 `Retrying (1/3) in 4s... (Esc to cancel)`。
                    // app 里没有 Esc ⇒ 同一句话 + 「中断」按钮（走 pi 的 abort_retry）。
                    // 放在**输入框上方**而不是只有面板里：重试时人就在看聊天，不该还要开面板才能停。
                    AppState.retrying?.let { r ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            LoadingIndicator(
                                modifier = Modifier.size(14.dp),
                                color = TerminalColors.warning(),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                retryBanner(r.attempt, r.maxAttempts, r.delayMs),
                                style = MaterialTheme.typography.bodySmall,
                                color = TerminalColors.warning(),
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(
                                onClick = { AppState.abortRetry() },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                            ) { Text("中断", style = MaterialTheme.typography.labelLarge) }
                        }
                    }

                    // 排队中（steer / followUp）的**待上屏内容**。
                    // 口径（用户定）：**点行 = 展开/收起**看全文；要编辑必须点 ✎（取回输入框），✕ 取消这一条。
                    // 之前是"点一下就把文字抽回输入框"，容易误触把排队内容弄丢，改成显式编辑按钮。
                    // 跨会话：这两份文本属于**当前会话**（AppState 切会话就清），另外 pi 的
                    // `pendingMessageCount` 是权威值 —— 它说还有排队而本地没有文本时，
                    // 也要给一行（否则「排队了但看不见」）。
                    val pendingCount = AppState.info?.pendingCount ?: 0
                    val knownQueued = AppState.queuedSteering.size + AppState.queuedFollowUp.size
                    if (knownQueued > 0 || pendingCount > knownQueued) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            // 哪几条被展开（key = kind+文本：pi 重发队列时文本不变 ⇒ 展开态不丢）
                            val expandedKeys = remember { mutableStateMapOf<String, Boolean>() }

                            @Composable
                            fun queueRow(kind: String, label: String, items: List<String>) {
                                items.forEachIndexed { index, text ->
                                    val key = kind + "|" + text
                                    val open = expandedKeys[key] == true
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(MaterialTheme.shapes.small)
                                            .clickable { expandedKeys[key] = !open }
                                            .padding(vertical = 2.dp, horizontal = 6.dp),
                                        verticalAlignment = if (open) Alignment.Top else Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        Text(
                                            label,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = TerminalColors.warning(),
                                            modifier = Modifier.padding(top = if (open) 2.dp else 0.dp),
                                        )
                                        Text(
                                            if (open) text else text.lines().joinToString(" "),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (open) MaterialTheme.colorScheme.onSurface else TerminalColors.muted(),
                                            maxLines = if (open) Int.MAX_VALUE else 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier
                                                .weight(1f)
                                                .then(
                                                    // 展开也不许把正文挤没：上限 160dp，内部滚动（与工具实时输出同一档）
                                                    if (open) {
                                                        Modifier
                                                            .heightIn(max = 160.dp)
                                                            .verticalScroll(rememberScrollState())
                                                    } else {
                                                        Modifier
                                                    },
                                                ),
                                        )
                                        // 编辑：显式按钮（取回输入框，仅此路径会"抽走"排队内容）
                                        Box(
                                            modifier = Modifier
                                                .size(28.dp)
                                                .clip(MaterialTheme.shapes.small)
                                                .clickable {
                                                    AppState.takeQueued(kind, index, forEdit = true) { composer = it }
                                                },
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Icon(
                                                painter = painterResource(R.drawable.ic_edit),
                                                contentDescription = "编辑这条排队",
                                                tint = TerminalColors.muted(),
                                                modifier = Modifier.size(16.dp),
                                            )
                                        }
                                        // 取消这一条
                                        Box(
                                            modifier = Modifier
                                                .size(28.dp)
                                                .clip(MaterialTheme.shapes.small)
                                                .clickable { AppState.takeQueued(kind, index, forEdit = false) {} },
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Icon(
                                                painter = painterResource(R.drawable.ic_close),
                                                contentDescription = "取消这条排队",
                                                tint = TerminalColors.danger(),
                                                modifier = Modifier.size(14.dp),
                                            )
                                        }
                                    }
                                }
                            }
                            queueRow("steer", "插入本轮", AppState.queuedSteering)
                            queueRow("followUp", "排队", AppState.queuedFollowUp)
                            // 权威计数 > 本地已知文本：说明这几条是在别处（终端/上一个会话）排进来的，
                            // 内容还没随事件同步过来。先给一行“可取消”，不装作没排队。
                            // 宽限期（见 Format.unknownPendingVisible）：steer 刚上屏时 pi 的计数
                            // 还会多算一秒半秒，那不算"有未知排队"，别吓人。
                            val unknown = unknownPendingCount(pendingCount, knownQueued)
                            var unknownWaited by remember(unknown) { mutableStateOf(0L) }
                            LaunchedEffect(unknown) {
                                if (unknown > 0) {
                                    delay(UNKNOWN_PENDING_GRACE_MS)
                                    unknownWaited = UNKNOWN_PENDING_GRACE_MS
                                }
                            }
                            if (unknownPendingVisible(unknown, unknownWaited)) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(MaterialTheme.shapes.small)
                                        .padding(vertical = 2.dp, horizontal = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Text(
                                        "排队",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TerminalColors.warning(),
                                    )
                                    Text(
                                        "另有 $unknown 条（内容未同步）",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TerminalColors.muted(),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Box(
                                        modifier = Modifier
                                            .size(28.dp)
                                            .clip(MaterialTheme.shapes.small)
                                            .clickable { AppState.clearQueueAll() },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_close),
                                            contentDescription = "清空排队",
                                            tint = TerminalColors.danger(),
                                            modifier = Modifier.size(14.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // 扩展的状态（setStatus）在终端里是 footer：贴在输入区上方一行小字
                    StatusLine()

                    // 扩展的交互请求：像终端那样就地加载在输入区上方（不用模态弹窗）
                    PendingPromptSection()

                    if (picked.isNotEmpty()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            picked.forEachIndexed { index, image ->
                                Box {
                                    if (image.bitmap != null) {
                                        Image(
                                            bitmap = image.bitmap,
                                            contentDescription = null,
                                            modifier = Modifier.size(56.dp).clip(MaterialTheme.shapes.medium),
                                        )
                                    } else {
                                        Surface(
                                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                            shape = MaterialTheme.shapes.medium,
                                            modifier = Modifier.size(56.dp),
                                        ) {
                                            Text(
                                                "${image.attachment.approxKb}KB",
                                                modifier = Modifier.padding(6.dp),
                                                style = MaterialTheme.typography.labelSmall,
                                            )
                                        }
                                    }
                                    // 固定 40dp 槽位：M3 Expressive 的按压形变不能改变布局（否则会把输入框挤窄）
                                    Box(modifier = Modifier.size(composerIconSize), contentAlignment = Alignment.Center) {
    // 固定 40dp 槽位：M3 Expressive 的按压形变不能改变布局（否则会把输入框挤窄）
    Box(modifier = Modifier.size(composerIconSize), contentAlignment = Alignment.Center) {
    FilledIconButton(
                                                onClick = { picked = picked.filterIndexed { i, _ -> i != index } },
                                                modifier = Modifier.align(Alignment.TopEnd).size(22.dp),
                                                colors = IconButtonDefaults.filledIconButtonColors(
                                                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                                ),
                                            ) { Icon(painterResource(R.drawable.ic_close), contentDescription = "移除", modifier = Modifier.size(12.dp)) }
    }
                                    }
                                }
                            }
                            Text(
                                "共 ${picked.size} 张",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    // 上下文环：一眼看用了多少；**点它在原地弹出详情**（语义色：绿<60% / 黄 60–85% / 红>85%）
                    AppState.info?.let { info ->
                        // pi 有时给不出 contextUsage（tokens/percent 为 null，压缩过的会话常见）。
                        // 之前这里直接 `if (percent != null)` ⇒ 整行（环 + 已用/总量文字）消失。
                        // 现在：拿不到百分比也照常显示这一行，环画成灰色 0%、文字写「—」。
                        val percentOrNull = info.contextPercent
                        val percent = percentOrNull ?: 0.0
                        val hasPercent = percentOrNull != null
                        run {
                            // 上下文抽屉：**原地上升**（用户 2026-10-23 第三版口径）。
                            // 不再用 Popup（那是"飘出来的浮层"），改成在工具栏上方展开的抽屉：
                            // 展开时把消息区顶上去，收起时落回原处 —— 视觉上就是"从这里长出来"。
                            var showContextDrawer by remember { mutableStateOf(false) }
                            val ringColor = when {
                                !hasPercent -> TerminalColors.muted()
                                percent >= 85 -> TerminalColors.danger()
                                percent >= 60 -> TerminalColors.warning()
                                else -> TerminalColors.success()
                            }
                            // 上下文抽屉（原地上升）：在工具栏**上方**展开，展开时把消息区顶上去。
                            // 关闭方式：再点一次上下文 pill / 返回键 / 点抽屉里的「收起」。
                            AnimatedVisibility(
                                visible = showContextDrawer,
                                enter = expandVertically(tween(170)) + fadeIn(tween(120)),
                                exit = shrinkVertically(tween(150)) + fadeOut(tween(90)),
                            ) {
                                BackHandler { showContextDrawer = false }
                                ContextDrawer(
                                    percent = percent,
                                    hasPercent = hasPercent,
                                    ringColor = ringColor,
                                    info = info,
                                    onCompact = { showContextDrawer = false; AppState.compact() },
                                    onAllInfo = { showContextDrawer = false; sheet = ChatSheet.Info },
                                    onCollapse = { showContextDrawer = false },
                                )
                            }

                            Box(modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    // 工具按钮放**左边**（用户 2026-10-21）：命令模式(`>_`) + 展开输入框(`^`)；
                                    // 「上下文」移到**右边**（原来的顺序是反的）。
                                    // 比主操作小一档：32dp 槽位 / 18dp 图标。
                                    // **整行定高 28dp**：不管排队 pill 在不在、字号倍率多大，工具栏高度不跳。
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.height(28.dp),
                                    ) {
                                        // 「排队」（followUp）：**只在真需要排队时**才出现——
                                        // 即：本轮在跑 + 输入框里有东西（没内容排队没意义）。
                                        // 带 pill 容器（用户口径），但只能是**小号**：labelSmall + 紧凑内边距，
                                        // 不能像上一版 tonal button 那样把旁边 18dp 图标压成小弟。
                                        val canQueue = (AppState.isWorking || AppState.streaming) &&
                                            (composer.isNotBlank() || picked.isNotEmpty() || commandMode)
                                        val slashActive = commandMode || composer.startsWith("/")
                                        // 斜杠图标（命令模式）：就是 `/` 本身（不是 `>_`）——圆形 pill，激活态用 primary 容器
                                        CircleIconAction(
                                            iconRes = R.drawable.ic_slash,
                                            contentDescription = "斜杠命令（命令模式）",
                                            active = slashActive,
                                            onClick = {
                                                if (slashActive && composer.startsWith("/")) {
                                                    composer = composer.removePrefix("/")
                                                } else {
                                                    commandMode = !commandMode
                                                }
                                            },
                                        )
                                        // 输入框的展开/收起：**就在工具按钮栏原地旋转**（用户 2026-10-21）。
                                        // 收起指上、展开指下，同一个按钮，不另开一个位置。
                                        ComposerExpandArrow(
                                            expanded = expandedOpen,
                                            onClick = { expandedOpen = !expandedOpen },
                                        )
                                        if (canQueue) {
                                            // **定高 24dp + 内容居中**：不让文字行高（受字号倍率影响）把这一行撞高。
                                            // 之前用 padding 撑高度 ⇒ 排队一出现/消失，工具栏高度就跳一下（用户报）。
                                            Surface(
                                                onClick = {
                                                    sendComposer(composer, picked, commandMode, followUp = true) {
                                                        composer = ""; picked = emptyList(); commandMode = false
                                                        // 自己发消息 = 终端里敲 Enter ⇒ 回到尾巴并恢复跟随
                                                        followTail = true
                                                    }
                                                },
                                                shape = CircleShape,
                                                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                                // **高度与旁边两枚圆形 pill 一致（28dp）**：用户两次反馈——
                                                // 先是对不齐，再说“圆还是太大” ⇒ 三者一起收到 28dp。
                                                modifier = Modifier.height(28.dp),
                                            ) {
                                                Box(
                                                    // **不要 fillMaxSize**：Row 里那样会把 max width 拿来填，pill 会被拉成一长条
                                                    // （用户：排队按钮变得特别长）。宽度按内容（文字 + 左右 10dp），高度由 Surface 定 24dp。
                                                    modifier = Modifier.padding(horizontal = 9.dp),
                                                    contentAlignment = Alignment.Center,
                                                ) {
                                                    Text(
                                                        "排队",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        maxLines = 1,
                                                        softWrap = false,
                                                    )
                                                }
                                            }
                                        }
                                    }
                                Row(
                                    modifier = Modifier
                                        // 按压效果走 Surface(onClick)：容器透明、形状是**真圆**，
                                        // 不会出现"方形阴影/方角水波纹"（用户 2026-10-23）。
                                        .padding(horizontal = 2.dp)
                                        .clip(CircleShape)
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = androidx.compose.material3.ripple(bounded = true),
                                        ) { showContextDrawer = !showContextDrawer }
                                        .padding(horizontal = 4.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    CircularProgressIndicator(
                                        progress = { (percent / 100.0).toFloat().coerceIn(0f, 1f) },
                                        modifier = Modifier.size(18.dp),
                                        color = ringColor,
                                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                                        strokeWidth = 2.5.dp,
                                    )
                                    Text(
                                        "上下文 " + (if (hasPercent) "%.0f".format(percent) + "%" else "—") +
                                            " · " + formatTokens(info.contextTokens) + "/" + formatTokens(info.contextWindow),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                }

                                // 「上下文」弹层 —— 按用户 2026-10-22 的三条意见重做：
                                //  ① **更大**：宽度几乎满屏（左右各留 16dp），内容按 M3 的间距尺标排；
                                //  ② **底部按钮靠右**：M3 对话框/卡片的动作区就是右对齐文本按钮，不再用整行菜单项；
                                //  ③ **更 M3**：容器色 surfaceContainerHigh + 28dp 大圆角（extraLarge）+ 3dp 阴影，
                                //     大号百分比（headlineMedium）+ 细分隔线 + 标签/值两栏。
                                // 居中的 M3 卡片弹层（用户 2026-10-22「窗口要居中」）：
                                // 用 Popup + Alignment.Center 而不是 DropdownMenu —— 后者锚定在按钮上，
                                // 偏移永远偏向锚点那侧。遮罩 + 点外面关闭，行为与对话框一致。

                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                    // 展开/收起做成动画（原来硬切：底部块高度突变 ⇒ 列表整块重排，用户 2026-10-22
                    // 报「展开动画有点卡」）。用 AnimatedContent 让高度与透明度一起过渡。
                    AnimatedContent(
                        targetState = expandedOpen,
                        transitionSpec = {
                            (fadeIn(tween(120)) + expandVertically(tween(170)))
                                .togetherWith(fadeOut(tween(90)) + shrinkVertically(tween(150)))
                        },
                        contentAlignment = Alignment.BottomCenter,
                        label = "composer-expand",
                    ) { open ->
                    if (open) {
                        ExpandedComposer(
                            value = composer,
                            onChange = { composer = it },
                            streaming = AppState.streaming,
                            // 展开态也要能加附件（用户 2026-10-22：这个按钮**不许消失**）
                            onPickImage = {
                                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            },
                            onSend = { followUp ->
                                sendComposer(composer, picked, commandMode, followUp = followUp) {
                                    composer = ""; picked = emptyList(); commandMode = false
                                    // 自己发消息 = 终端里敲 Enter ⇒ 回到尾巴并恢复跟随
                                    followTail = true
                                }
                                expandedOpen = false
                            },
                        )
                    } else {
                    // 输入区外层 pill：**圆角 = 内圆按钮半径 + 内边距** ⇒ 曲线同心；四周内边距一律 composerInset（边距一致）
                    val composerShape = RoundedCornerShape(composerIconSize / 2 + composerInset)
                    // 思考等级环（对齐 pi：editor 边框色 = getThinkingBorderColor(level)）。
                    // 只在非 off 时画：app 的输入区本来无边框，off 时没有信息可传达就保持原样。
                    // 与终端同一条优先级：**bash 模式优先**（`updateEditorBorderColor()`:
                    // `if (isBashMode) bashMode else thinkingBorder`）。
                    val bashMode = parseBashInput(composer) != null
                    val levelNow = AppState.info?.thinking
                    val ringColor = when {
                        bashMode -> TerminalColors.bashMode().copy(alpha = 0.55f)
                        showThinkingRing(levelNow) ->
                            TerminalColors.thinkingFamily(thinkingFamily(levelNow)).copy(alpha = 0.55f)
                        else -> null
                    }
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        shape = composerShape,
                        // 内容变多时向上变高（把消息列表顶上去），按钮始终贴在底部原地
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = composerIconSize + composerInset * 2, max = 160.dp)
                            .then(
                                if (ringColor != null) {
                                    Modifier.border(1.5.dp, ringColor, composerShape)
                                } else {
                                    Modifier
                                },
                            ),
                    ) {
                        Row(
                            modifier = Modifier.padding(composerInset),
                            verticalAlignment = Alignment.Bottom,
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            // 附件：M3 Expressive tonal 图标按钮（与上排工具按钮同规格）
                            // 固定 40dp 槽位：M3 Expressive 的按压形变不能改变布局（否则会把输入框挤窄）
                            Box(modifier = Modifier.size(composerIconSize), contentAlignment = Alignment.Center) {
    FilledTonalIconButton(
                                    onClick = {
                                        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                    },
                                    shapes = IconButtonDefaults.shapes(),
                                    modifier = Modifier.size(composerIconSize),
                                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                                    containerColor = Color.Transparent,
                                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                ),
                                ) {
                                    Icon(
                                        painterResource(R.drawable.ic_add),
                                        contentDescription = "传图",
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            }

                            // 文字块给最小高度 = 按钮高度，并在其中垂直居中：单行时和左右按钮同一条中线；
                            // 多行时 Box 随内容长高、自然向上出血（Row 是 Bottom 对齐 ⇒ 按钮原地不动）。
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 4.dp)
                                    .heightIn(min = composerIconSize),
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                if (composer.isEmpty()) {
                                    // 上滑反馈：普通态用主色；**armed 要停止时文字也变红**（跟按钮同色）
                                    val hint = slideHint
                                    Text(
                                        hint?.text ?: when {
                                            bashMode -> if (composer.startsWith("!!")) "运行命令（不进上下文）…" else "运行命令…"
                                            AppState.streaming -> "插入本轮…"
                                            else -> "说点什么…"
                                        },
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = when {
                                            hint == null -> MaterialTheme.colorScheme.onSurfaceVariant
                                            hint.danger -> MaterialTheme.colorScheme.error
                                            else -> MaterialTheme.colorScheme.primary
                                        },
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        // 与 BasicTextField 的文字垂直对齐（否则 placeholder 贴顶、输入文字居中，切换时"歪"）
                                        modifier = Modifier.align(Alignment.CenterStart),
                                    )
                                }
                                BasicTextField(
                                    value = composer,
                                    onValueChange = { composer = it },
                                    modifier = Modifier.fillMaxWidth().focusRequester(composerFocus),
                                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                                        color = MaterialTheme.colorScheme.onSurface,
                                    ),
                                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                    // 内容变多时软换行、pill 向上变高（最多 8 行，超出内部滚动）。
                                    // 用户 2026-10-22：**回车 = 换行**，不再回车即发（要发按右下角发送键）。
                                    // ImeAction.Default ⇒ 输入法给的是换行键；multiline 的 BasicTextField
                                    // 收到 \n 就换行。硬件键盘同理（Compose 不拦 Enter）。
                                    maxLines = 8,
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                                )
                            }

                            // 主操作键：**点 = 发送**（流式中 = 插到本轮）；**按住上滑 = 停止**（流式中）。
                            // 「排队」挪到上方工具按钮行，不再挤输入框文字。
                            ComposerMainButton(
                                streaming = AppState.streaming,
                                onSend = {
                                    sendComposer(composer, picked, commandMode, followUp = false) {
                                        composer = ""; picked = emptyList(); commandMode = false
                                        // 自己发消息 = 终端里敲 Enter ⇒ 回到尾巴并恢复跟随
                                        followTail = true
                                    }
                                },
                                onStop = { AppState.abort() },
                                onHint = { slideHint = it },
                            )
                        }
                    }   // Surface（pill）
                    }   // if (open)
                    }   // AnimatedContent
                    }   // Row（传图按钮 + pill）
                }
            }
        }
    }

    when (sheet) {
        ChatSheet.Models -> ModelSheet(onDismiss = { sheet = ChatSheet.None })
        ChatSheet.Thinking -> ThinkingSheet(onDismiss = { sheet = ChatSheet.None })
        ChatSheet.Commands -> CommandsSheet(
            onPick = {
                composer = if (composer.isBlank()) "$it " else composer + " " + it + " "
                sheet = ChatSheet.None
            },
            onDismiss = { sheet = ChatSheet.None },
        )

        ChatSheet.Tree -> SessionTreeSheet(onDismiss = { sheet = ChatSheet.None })
        ChatSheet.Behavior -> BehaviorSheet(onDismiss = { sheet = ChatSheet.None })
        ChatSheet.Info -> InfoSheet(onDismiss = { sheet = ChatSheet.None })
        ChatSheet.Connection -> ConnectionSheet(onDismiss = { sheet = ChatSheet.None })
        ChatSheet.More -> MoreSheet(
            modelDisplay = AppState.info?.modelDisplay,
            thinkingLevel = AppState.info?.thinking,
            onPick = { action -> sheet = ChatSheet.None; runChatAction(action) },
            onDismiss = { sheet = ChatSheet.None },
        )

        ChatSheet.None -> Unit
    }

    if (renameOpen) {
        RenameDialog(
            initial = AppState.info?.sessionName.orEmpty(),
            onConfirm = { AppState.rename(it); renameOpen = false },
            onDismiss = { renameOpen = false },
        )
    }

}

/**
 * 工具栏里的**圆形 pill 图标按钮**（用户 2026-10-21：斜杆图标与展开图标都用圆形 pill）。
 * 用 Surface(onClick) 而不是 FilledTonalIconButton —— 后者在 Expressive 下是 squircle 形变，
 * 用户这里要的是正圆。
 */
@Composable
private fun CircleIconAction(
    iconRes: Int,
    contentDescription: String,
    onClick: () -> Unit,
    active: Boolean = false,
    iconModifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (active) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = if (active) MaterialTheme.colorScheme.onPrimary
        else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(28.dp),
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(
                painterResource(iconRes),
                contentDescription = contentDescription,
                modifier = Modifier.size(16.dp).then(iconModifier),
            )
        }
    }
}

/**
 * 输入区右侧的主操作键（用户 2026-10-22 口径）：
 *
 * · **点一下 = 发送**：非流式就是发送；流式中 = 把输入框这段插到本轮（steer）。所以流式中它也是
 *   ⏎ 而不是 ⏹ —— 回车改成换行之后，点这里是唯一的 steer 入口。
 * · **按住往上滑**：按钮**跟手拉长**（圆形拉成胶囊，位移 1:1 映射到高度、到阈值封顶）——
 *   流式中到阈值 = **停止**（整体变红、符号换成 ⏹、提示文字也变红）；非流式 = 发送。
 *   滑回原位置就跟着缩回去，颜色/符号/文字一起恢复；松手用弹簧回弹。
 *
 * 为什么不做迟滞带：**看到红 + ⏹ 就必须停得动**。破坏性动作不能出现"看着已经变了、松手却没停"。
 * 上滑提示借用输入框的占位那格（`onHint`）—— 气泡会被 pill 的圆角裁掉。
 */
@Composable
private fun ComposerMainButton(
    streaming: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onHint: (ComposerHint?) -> Unit,
) {
    val density = LocalDensity.current
    val thresholdPx = with(density) { 56.dp.toPx() }
    val tracker = remember { SlideUpTracker(thresholdPx) }
    val haptics = LocalHapticFeedback.current
    var dragging by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var armed by remember { mutableStateOf(false) }
    val armedStop = armed && streaming

    // 跟手：拖动中是即时（snap，不然"跟手"是假的）；松手用弹簧回弹
    val stretch by animateFloatAsState(
        targetValue = if (dragging) progress else 0f,
        animationSpec = if (dragging) snap() else spring(dampingRatio = 0.6f, stiffness = 900f),
        label = "composer-slide-stretch",
    )
    val height = composerIconSize + 44.dp * stretch

    LaunchedEffect(dragging, armedStop) {
        onHint(
            when {
                !dragging -> null
                armedStop -> ComposerHint("松手停止", danger = true)
                armed -> ComposerHint("松手发送", danger = false)
                else -> ComposerHint(if (streaming) "上滑停止" else "上滑发送", danger = false)
            },
        )
    }
    LaunchedEffect(armedStop) {
        if (armedStop) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }
    DisposableEffect(Unit) { onDispose { onHint(null) } }

    Box(modifier = Modifier.width(composerIconSize).height(height), contentAlignment = Alignment.BottomCenter) {
        FilledIconButton(
            onClick = onSend,
            shapes = IconButtonDefaults.shapes(),
            modifier = Modifier
                .width(composerIconSize)
                .height(height)
                .pointerInput(streaming) {
                    detectDragGestures(
                        onDragStart = {
                            dragging = true
                            tracker.reset()
                            progress = 0f
                            armed = false
                        },
                        onDragEnd = {
                            val fire = tracker.release()
                            dragging = false
                            progress = 0f
                            armed = false
                            // armed 时松手才触发；没到阈值 = 普通点击（onClick 那条路）
                            if (fire) {
                                if (streaming) onStop() else onSend()
                            }
                        },
                        onDragCancel = {
                            dragging = false
                            progress = 0f
                            armed = false
                            tracker.reset()
                        },
                        onDrag = { change, delta ->
                            change.consume()
                            tracker.drag(delta.y)
                            progress = tracker.progress
                            armed = tracker.armed
                        },
                    )
                },
            colors = if (armedStop) {
                IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                )
            } else {
                IconButtonDefaults.filledIconButtonColors()
            },
        ) {
            Icon(
                painterResource(
                    when {
                        armedStop -> R.drawable.ic_stop
                        armed -> R.drawable.ic_arrow_up
                        else -> R.drawable.ic_return
                    },
                ),
                contentDescription = if (streaming) {
                    "发送 / 插入本轮（按住上滑 = 停止）"
                } else {
                    "发送（按住上滑 = 发送）"
                },
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * 上滑手势的提示（放在输入框占位那格）。
 *
 * @param danger true = 这一松手会**停止**（文字用红色，跟按钮同色）
 */
private data class ComposerHint(val text: String, val danger: Boolean)

/**
 * 输入框的**展开/收起箭头**——用户 2026-10-21 说的「可旋转箭头」就是这一枚
 * （不是发送键、也不是工具卡的折叠箭头）。
 * 收起态指上（点它把抽屉拉起来），展开态旋转 180° 指下（点它收回去），同一个箭头连续旋转。
 */
@Composable
private fun ComposerExpandArrow(expanded: Boolean, onClick: () -> Unit) {
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 0f else 180f,
        animationSpec = tween(durationMillis = 220),
        label = "composer-expand-arrow",
    )
    CircleIconAction(
        iconRes = R.drawable.ic_expand,
        contentDescription = if (expanded) "收起" else "展开",
        onClick = onClick,
        iconModifier = Modifier.rotate(rotation),
    )
}

/**
 * 长文输入：**原地抽屉展开**（用户 2026-10-21 口径），不再是模态对话框。
 * 抽屉从输入区的位置向上长出来，与展开前的输入区视觉连贯（同一份文字、同一水平位置、顶部把手 + 圆角）。
 * 提交口径与输入框一致：非流式=发送；流式中=插到本轮，或「排队」跑完再做。
 */
@Composable
private fun ExpandedComposer(
    value: String,
    onChange: (String) -> Unit,
    streaming: Boolean,
    onSend: (followUp: Boolean) -> Unit,
    onPickImage: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    // 展开态**无边框**（用户 2026-10-23 看过实物后的口径：「之前的无边框就挺好啊」）。
    // 2026-10-22 曾要求"展开态也要有边框"，加了一圈等级色环；实物上是一块大色块浮在消息上，
    // 反而更重 ⇒ 撤回。收起态的胶囊仍然保留等级色环（那里是状态提示，需要）。
    val shape = RoundedCornerShape(22.dp)
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = shape,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 4.dp)) {
            // 头行：只留中间的拖拽把手（展开/收起按钮**不在这里**，它就在工具按钮栏原地旋转）
            Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(width = 32.dp, height = 3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)),
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .heightIn(min = 160.dp, max = 280.dp)
                    .focusRequester(focus),
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                // 左 = 附件（跟收起态同一个键，展开态不许消失）；右 = 排队 / 发送
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                FilledTonalIconButton(
                    onClick = onPickImage,
                    shapes = IconButtonDefaults.shapes(),
                    modifier = Modifier.size(composerIconSize),
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = Color.Transparent,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                ) {
                    Icon(
                        painterResource(R.drawable.ic_add),
                        contentDescription = "传图",
                        modifier = Modifier.size(20.dp),
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (streaming) {
                        TextButton(
                            onClick = { onSend(true) },
                            enabled = value.isNotBlank(),
                        ) { Text("排队") }
                    }
                    // 主操作：全屏唯一的实心圆（与收起后的发送/停止一致）
                    FilledIconButton(
                        onClick = { onSend(false) },
                        enabled = value.isNotBlank(),
                        shapes = IconButtonDefaults.shapes(),
                        modifier = Modifier.size(composerIconSize),
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_return),
                            contentDescription = if (streaming) "插入本轮" else "发送",
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * 折叠态按「收起行数」截，但**保住 footer**（`took … · exit_code=… · log: …`）。
 *
 * 两个坑（都踩过）：
 * 1) 不能直接 take(N)：终端收起时块末尾就是 footer，砍掉会丢「成功/失败 + 日志路径」；
 * 2) footer 只能在**末尾几条**里找、且必须形如 `took …` / `exit_code=…` 开头 ——
 *    输出正文里也可能出现 `exit_code=`（比如正在看这段代码），早期版本因此把一大段 diff
 *    当成 footer 全保留了，折叠态反而更长。
 */
private fun collapseTermLines(lines: List<String>, maxLines: Int): List<String> {
    val n = maxLines.coerceAtLeast(1)
    if (lines.size <= n) return lines
    val ansi = Regex("\u001B\\[[0-9;]*m")
    fun plain(s: String) = s.replace(ansi, "").trimStart()
    val searchFrom = (lines.size - 4).coerceAtLeast(n)
    val footerAt = (searchFrom until lines.size).firstOrNull { i ->
        val p = plain(lines[i])
        p.startsWith("took ") || p.startsWith("exit_code=")
    }
    val head = lines.take(n)
    return if (footerAt != null) head + lines.drop(footerAt).take(3) else head
}

private fun subtitle(): String {
    val info = AppState.info ?: return "读取状态中…"
    // **照抄 pi 终端 footer 的那串**（桥 import pi 自己的语义算好 modelDisplay.text）：
    // `model.id • thinking → 最新一次真正应答的 model.id`。app 不另定规则；宽度不够就让它
    // 换行/省略（下面 Text 给两行），要完整看就去信息面板。
    val model = info.modelDisplay.ifBlank {
        val short = info.model.substringAfterLast('/').ifBlank { "未选模型" }
        val thinking = info.thinking.takeIf { it.isNotBlank() }?.let { " • " + it } ?: ""
        short + thinking
    }
    // 顶栏只放**一眼要用到的**：在哪（分支）/ 什么模型+等级 / 上下文用了多少。
    // 用量细节（`↑↓ R/W CH% $`）不放这儿 —— 手机上一行放不下会折成两行，把标题挤没
    // （用户 2026-10-22：「ch、金额这些为什么不放在上下文窗口里」）。它们现在在「上下文」弹层里。
    val context = footerContext(info.contextPercent, info.contextWindow, info.autoCompaction)
    val branch = gitBranchLabel(info.gitBranch)
    // 顶栏只留两段：模型+等级 / 上下文用了多少（一行放得下）。
    // 分支属于"我在哪"，和 cwd 一起放在上下文弹层里 —— 顶栏塞四段必然折行，把标题挤没。
    return listOfNotNull(model.takeIf { it.isNotBlank() }, context.takeIf { it.isNotBlank() })
        .joinToString(" · ")
}
/**
 * 连接状态条（只在**不是已连接**时出现）。
 *
 * 为什么放对话页顶部而不是只在首页：用户报过"掉桥了完全没提示，只能自己点强制拉起"。
 * 桥断在这儿，用户正在看的也是这儿，提示就得在这儿。
 *
 * 三条口径：
 *  1. **不吓人**：重连是常态（切后台/锁屏/切网都会），文案写「重连中（n）」，不写「连接失败」；
 *  2. **不催人**：明确写"会自动恢复"，避免用户去点强制拉起（那反而会杀进程）；
 *  3. **失败才给按钮**：连续多次失败且不是本机桥（远端机器 app 无法自己拉起），
 *     才显示「重试」，本机桥自动拉起由 AppState 负责。
 */
@Composable
private fun ConnectionStrip() {
    val state = AppState.connection
    if (state == Connection.Ok) return
    val label = AppState.connectionText()
    Surface(
        color = if (state == Connection.Failed) {
            TerminalColors.warning().copy(alpha = 0.14f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state != Connection.Failed) {
                CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 1.5.dp,
                    color = TerminalColors.muted(),
                )
            }
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = if (state == Connection.Failed) TerminalColors.warning() else TerminalColors.muted(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                // 本机桥会自己拉起（AppState 连续失败 3 次后触发），远端桥只能等网络回来
                if (AppState.isLocalBridge) "正在自动恢复…" else "网络恢复后自动重连…",
                style = MaterialTheme.typography.labelSmall,
                color = TerminalColors.muted(),
                maxLines = 1,
            )
        }
    }
}

/**
 * 会话页的 **working 状态条**（用户口径：像 TUI 的 working 行，但用 app 的形式）。
 *
 * 一行：转圈 + 「工作中」+ 在干什么（正在跑的工具名 / 思考生成中）+ 已跑秒数 + 「停止」。
 * 只在本轮真的在跑（agent_start → agent_settled）时出现，跑完立刻消失。
 */
/**
 * 输入框上方的「没发出去」提示条。
 *
 * 用户 2026-10-23：「发送失败的消息（app 异常退出 / bridge 出错等）要能够保留恢复编辑」。
 * 为什么不做成自动回填输入框：用户可能正在写别的；自动塞进去会把他正在写的东西顶掉。
 * 所以这里明说一条 + 两个按钮：「恢复编辑」（把原文放回输入框，已有的内容接在后面）和「丢弃」。
 */
@Composable
private fun PendingDraftBar(sessionFile: String?, onRestore: (String, List<Attachment>) -> Unit) {
    val draft = AppState.pendingDraft
    if (!Outbox.matches(draft, sessionFile)) return
    val d = draft ?: return
    Surface(
        color = TerminalColors.toolErrorBlock(),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (d.text.isBlank()) "有 ${d.hadImages} 张图没发出去" else "没发出去：" + Outbox.preview(d.text),
                    style = MaterialTheme.typography.bodySmall,
                    color = TerminalColors.body(),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val sub = buildString {
                    append(d.reason?.takeIf { it.isNotBlank() } ?: "上次没能确认送达")
                    if (d.hadImages > 0 && d.text.isNotBlank()) append(" · 含 ${d.hadImages} 张图")
                }
                Text(
                    text = sub,
                    style = MaterialTheme.typography.labelSmall,
                    color = TerminalColors.muted(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(onClick = {
                val images = AppState.takePendingDraftImages()
                val taken = AppState.takePendingDraft() ?: return@TextButton
                onRestore(taken.text, images)
            }) { Text("恢复编辑", style = MaterialTheme.typography.labelLarge) }
            TextButton(onClick = { AppState.discardPendingDraft() }) {
                Text("丢弃", style = MaterialTheme.typography.labelLarge, color = TerminalColors.muted())
            }
        }
    }
}

@Composable
private fun WorkingBar() {
    if (!AppState.isWorking) return
    val startedAt = AppState.workStartedAt ?: System.currentTimeMillis()
    var now by remember(startedAt) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(startedAt) {
        while (true) {
            now = System.currentTimeMillis()
            kotlinx.coroutines.delay(500)
        }
    }
    val seconds = ((now - startedAt) / 1000).coerceAtLeast(0)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 8.dp, top = 4.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // M3 Expressive 的 LoadingIndicator：本身就在动（用户口径：working 旁边那个动画要会动）
        // 颜色按 **thinking 等级**走（用户 2026-10-22：跟终端一致）——映射是**逆向 pi 的
        // system-theme.ts** 得来的（见 thinkingFamily()）：neutral → slate → blue → periwinkle
        // → violet → magenta → red。⚠️ 逆向证据：pi 的 .js.map 带 sourcesContent。
        LoadingIndicator(
            modifier = Modifier.size(16.dp),
            color = TerminalColors.thinkingFamily(thinkingFamily(AppState.info?.thinking)),
        )
        Text("工作中", style = MaterialTheme.typography.labelSmall, color = TerminalColors.muted())
        val activity = AppState.workActivity
        Text(
            if (activity.isBlank()) "" else "· $activity",
            style = MaterialTheme.typography.labelSmall,
            color = TerminalColors.muted(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text("${seconds}s", style = MaterialTheme.typography.labelSmall, color = TerminalColors.muted())
        TextButton(onClick = { AppState.abort() }, contentPadding = PaddingValues(horizontal = 8.dp)) {
            Text("停止", style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** 扩展状态（setStatus）：终端里就是 footer 一行小字；这里压在输入区上方，点一下可展开。 */
@Composable
private fun StatusLine() {
    // 只读带归属的那份：别的会话留下的状态行不上屏（见 AppState.statusText）
    val status = AppState.statusText ?: return
    val key = "status:$status"
    val expanded = AppState.isOpen(key, false)
    Text(
        status,
        style = MaterialTheme.typography.labelSmall,
        color = TerminalColors.footer(),
        maxLines = if (expanded) Int.MAX_VALUE else 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { AppState.toggleOpen(key, false) }
            .padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 2.dp),
    )
}

/** 提醒（失败/提醒/完成）：ZCode 工具卡片同一套——卡片 + 状态 chip + 标题行 + 可折叠详情。 */
@Composable
private fun NoticeRow(item: ChatItem) {
    val defaultExpanded = item.noticeLevel == "error"
    val expanded = AppState.isOpen(item.key, defaultExpanded)
    val color = when (item.noticeLevel) {
        "error" -> TerminalColors.danger()
        "warn" -> TerminalColors.warning()
        "success" -> TerminalColors.success()
        else -> TerminalColors.body()
    }
    val label = when (item.noticeLevel) {
        "error" -> "失败"
        "warn" -> "提醒"
        "success" -> "完成"
        else -> "提示"
    }
    val hasDetail = item.noticeDetail.isNotBlank() || item.noticeNeedsTerminal ||
        item.text.length > 40 || item.text.contains('\n')

    // ZCode 工具卡片同一套：**卡片 + 状态 chip + 标题行 + 可折叠详情**
    Card(
        // 状态用背景色表达（用户口径：不要「失败/提醒/完成」字样 chip）；
        // 「压缩上下文」按终端口径用**紫色**（inlineCode 紫），跑完也是同一张卡。
        colors = CardDefaults.cardColors(
            containerColor = if (item.noticeKind == "compaction") TerminalColors.inlineCode().copy(alpha = 0.22f)
            else color.copy(alpha = 0.14f),
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clickable(enabled = hasDetail) { AppState.toggleOpen(item.key, defaultExpanded) },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    item.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (item.noticeKind == "compaction") TerminalColors.inlineCode() else color,
                    maxLines = if (expanded) Int.MAX_VALUE else 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            if (expanded) {
                if (item.noticeDetail.isNotBlank()) {
                    SelectionContainer {
                        Text(
                            item.noticeDetail,
                            style = MaterialTheme.typography.bodySmall,
                            color = TerminalColors.muted(),
                        )
                    }
                }
                if (item.noticeNeedsTerminal) {
                    Text(
                        "这一项要在 Termux 的 pi 终端里回应",
                        style = MaterialTheme.typography.bodySmall,
                        color = TerminalColors.muted(),
                    )
                }
            }
        }
    }
}

private fun tailLines(text: String, count: Int): String {
    val lines = text.trimEnd().split('\n')
    return if (lines.size <= count) lines.joinToString("\n")
    else lines.subList(lines.size - count, lines.size).joinToString("\n")
}

/**
 * 工具调用：**卡片式**（用户 2026-10-19 口径：输出渲染改走 ZCode 那套）。
 * 状态 chip（运行/完成/失败）+ 工具名 + 参数摘要 + 行数；输出等宽；
 * 详情默认收起（1–3 行直接显示），展开后**限高 + 内部滚动**；开合仍是「点标题行」（不做箭头/按钮）。
 */
@Composable
private fun ToolRow(item: ChatItem) {
    val result = item.toolResult.trimEnd()
    val hasDetail = result.isNotBlank()
    // 预览行数：**格式**继承终端，但"收起露出几行"是 app 的可读性选择（用户定：最多 2 行）。
    // pi 的 MCP 渲染器是 5 视觉行；手机上 5 行太占屏，这里取 min(pi, 2)。
    val piPreview = if (item.dispPreviewLines > 0) item.dispPreviewLines else 5
    val previewLines = minOf(piPreview, 2)
    val allLines = if (hasDetail) result.lines() else emptyList()
    val truncated = allLines.size > previewLines
    // 默认**收起**（标题带参数摘要 + 前 10 行输出）；点一下展开 = 完整参数 + 全部输出
    val expanded = AppState.isOpen(item.key, false)
    val shown = if (expanded || !truncated) allLines else allLines.take(previewLines)
    // 状态**不用 chip/图标，也不用底色**（用户口径）：所有工具卡共一个终端底色。
    // 以前：有 term 行 → toolBlock() `rgb(37,65,50)`；
    //       没 term 行（扩展/MCP 工具：codemode、gitea…）→ statusColor×14% 混出 `rgb(26,43,35)`
    // ⇒ 真机上就是**两块明显不同的绿**（用户 2026-10-21 截图问「为什么底色不一样」）。
    // 现在统一走 toolBlock()：区分状态不靠底色（终端也不靠），靠 footer 的 `exit_code=`
    // 与失败 notice。
    // 三态底色（见 TerminalColors.toolStateBlock）：运行中灰 → 完成绿 / 失败红。
    // 终端里这个变化是"卡片整体变色"，很显眼；app 之前固定绿色，等于把状态信息丢了。
    val cardColor = TerminalColors.toolStateBlock(
        running = toolStateKind(item.toolRunning, item.toolIsError) == "pending",
        failed = toolStateKind(item.toolRunning, item.toolIsError) == "error",
    )
    // 有 pi 渲染的终端行（term*）就**照终端画整块**：头行 `edit <path>` + 结果块，宽度/换行/颜色全部来自 pi。
    // **与终端对齐的关键**：桥送了「收起版」就用收起版，哪怕它是空的——`read` 在终端收起时就是
    // 只有头行 ` read <path>:1-100`、不露正文；以前拿 expanded 兜底，于是收起态多出一块终端没有的正文。
    // 用户口径（反复强调）：内容显示**尽量和终端一致** ⇒ 收起时就用 pi 自己的 collapsed 渲染，
    // 不再由 app 砍成「3 行」（pi 的 collapsed 本身已经是它认定的预览长度，footer 也在里面）。
    // 折叠行数：普通工具按用户设置（默认 3）；**edit/write 的 diff 行不同** ——
    // pi 的 collapsed 渲染本身就是"终端里看到的预览"，再砍到 3 行会把绿/红 diff 全砍掉，
    // 看着就像"edit 的样式没了"（用户 2026-10-23）。所以这类工具按 [headPreviewLines]
    // 同一口径放宽（14 行），仍受用户设置上限约束（最多 20）。
    val collapsedBudget = when (item.toolName) {
        "edit", "write", "multi_edit" -> maxOf(AppState.toolCollapsedLines, headPreviewLines(item.toolName, false))
            .coerceAtMost(20)
        else -> AppState.toolCollapsedLines
    }
    val termLines = when {
        expanded && item.termResultExpanded.isNotEmpty() -> item.termResultExpanded
        // 折叠态：**按「收起行数」截**（杂项面板 →工具卡），但保住 footer
        !expanded && item.termHasCollapsed -> collapseTermLines(item.termResultCollapsed, collapsedBudget)
        // 桥没送收起版（旧会话 / 该工具没实现）才用 expanded 截前 N 行兜底
        !expanded && item.termResultExpanded.isNotEmpty() -> collapseTermLines(item.termResultExpanded, collapsedBudget)
        else -> emptyList()
    }
    val hasTerm = item.termCall.isNotEmpty() || termLines.isNotEmpty()
    // **头行一律跟终端**（用户口径：内容尽量继承终端）。以前头行带 "…" 时会退回 app 自拼的
    // 「工具名 + 参数」标题——那正是「linux 命令被显示成 exec_command」的来源。
    // pi 在某个宽度下截不截头行是它自己的排版决定，app 不再插一脚（要完整命令就展开卡片）。
    val useTermHead = hasTerm
    // 标题行的参数摘要按终端来：`name key=value key=value…`（截到 100 字符），不再另起一行写 brief
    // 终端口径由**桥**用 pi 自己的 render-utils 算好送过来（`display`）；拿不到才本地兜底。
    val fallbackArgs = remember(item.toolArgsJson, item.toolBrief) { toolArgsLine(item) }
    val argsPreview = when {
        expanded && item.dispArgsExpanded.isNotBlank() -> item.dispArgsExpanded
        !expanded && item.dispArgsCollapsed.isNotBlank() -> item.dispArgsCollapsed
        else -> fallbackArgs
    }
    val duration = item.toolDurationMs?.takeIf { it > 0 }?.let { fmtDuration(it) }

    // **点击态高光必须覆盖整张卡片（带圆角）**（用户 2026-10-21）。
    // 以前是 Card + 内部 `clip().clickable()`，而且**头行自己也挂了 clickable** ⇒
    // 内层那个点击区画出的是一个**方角矩形**高光，只盖住头行。
    // 现在：用 Card 自己的 onClick 重载（水波纹按 shape 裁），并去掉内层 clickable。
    Card(
        onClick = { AppState.toggleOpen(item.key, false) },
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = cardColor,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 3.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // ① 标题：**工具名一行，参数另起一行占满宽度**。
            // 之前是「名字 + 参数」同一行两列 —— 名字长（`mcp__obsidian_server__search_notes`）时
            // 参数被挤成右侧一条窄柱子，逐字换行（`contextLen gth=300 …`），卡片被撑成一大块。
            // 头部整块可点：展开后正文区是 verticalScroll，会吞掉点击（用户报的「收不起来」），
            // 所以把开合挂在**头部**这块稳定的区域上。
            if (useTermHead) {
                // ★ 1:1 终端：头行原样（` edit <path>`），不再用 app 自己的「工具名 + 参数」排版。
                // 但**折叠态也要受「露多少」约束**：多行 heredoc 命令（`cat > x <<'EOF'`）在终端里会
                // 铺 30+ 行，手机上直接把卡片撑爆 ⇒ 折叠只露前 3 行 + 一行 `…`，展开给全量。
                val headAll = item.termCall
                val limit = headPreviewLines(item.toolName, expanded)
                val headShown = if (limit >= headAll.size) headAll else headAll.take(limit)
                val headLines = if (headShown.size < headAll.size) headShown + "…" else headShown
                AnsiText(
                    lines = headLines,
                    fontSize = AppState.fontToolSp.sp,
                    // 头行不画 ANSI 背景：pi 给头行带的 48;2;52;56;58 在终端里看不出色带
                    // （整块一个底色），app 真画出来就是一条只盖文字的浅灰带。
                    applyBackground = false,
                    // 不要 horizontalScroll：它给无界宽度 ⇒ autoSize 不会缩字号，长行就被卡片裁掉
                    // **不加 clickable**：开合由整张卡片负责，否则头行会画出一个方角矩形高光
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            // 有 term 头行时**整行都不组合**。以前用 `Modifier.height(0.dp)` 压扁：Compose 不裁剪溢出
            // ⇒ 卡片里会多溢出一行原始工具名 `exec_command`（用户 2026-10-21 报的「内容显示 broken」）。
            if (!useTermHead) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 显示**短名**：pi 的 MCP 工具名是 `mcp__<server>__<tool>`（防重名用的内部全名），
                // 用户要看的是 `search_notes`；server 在前面淡色，保留"是哪个 MCP"的信息。
                // 标题：桥给了 display.title（MCP 是 `server/tool`，pi 的 createMcpToolRenderers 口径）就用它
                val dispTitle = item.dispTitle.takeIf { !it.contains('…') } ?: ""
                val shortPair = shortToolName(item.toolName)
                val titleServer = if (dispTitle.contains("/")) dispTitle.substringBeforeLast("/") + "/" else shortPair.second?.plus("/")
                val titleName = if (dispTitle.isNotBlank()) dispTitle.substringAfterLast("/") else shortPair.first
                // 名字要能看全：**一个 Text 里拼 server + 短名**，整段换行（拆成两个 Text 时
                // server 自己先折行、短名被挤到下一行，看起来像 "obsidian_server/" + 居中两行）
                Text(
                    buildAnnotatedString {
                        titleServer?.let { server ->
                            withStyle(SpanStyle(color = TerminalColors.footer())) { append(server) }
                        }
                        append(titleName)
                    },
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontSize = AppState.fontToolNameSp.sp,
                        // pi 的通用工具头是 `theme.fg("toolTitle", theme.bold(title))`：
                        // **工具名加粗**，参数才是 muted（与终端一致）
                        fontWeight = FontWeight.Bold,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 3,
                    overflow = TextOverflow.Visible,
                    modifier = Modifier.weight(1f),
                )
                // 工具卡不加任何折叠箭头：开合靠点整张卡片（用户 2026-10-21：加了箭头反而越改越坏）
            }
            }
            // 有 term（终端原样行）时不再画 app 自己的参数摘要/标题细节 —— 否则会和终端内容重复
            if (!useTermHead && argsPreview.isNotBlank()) {
                val briefPath = deliverablePathOf(item.toolBrief)
                // 参数换行铺满整宽：收起时最多 2 行（2 行内整段露出，不省略），展开时全给
                Text(
                    argsPreview,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = AppState.fontToolSp.sp),
                    color = if (briefPath != null) TerminalColors.link() else TerminalColors.muted(),
                    // 参数**永不省略**：整段换行铺开（用户：不要「…」，内容要能看全）
                    maxLines = Int.MAX_VALUE,
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (briefPath != null) Modifier.clickable { AppState.openPath(briefPath) } else Modifier,
                        ),
                )
            }

            // ��� pi 的特性：工具**跑的过程中**就能看到输出（partialResult）。
            // **与终端一致**：桥在 tool_execution_update 上也用 pi 自己的 renderResult(isPartial=true)
            // 渲染好了（poll / write_stdin 这类动态工具就靠它），所以这里优先画 term 行；
            // 拿不到才退回纯文本 partialResult。只显尾部几条，跟着长。
            if (item.toolRunning) {
                val liveTerm = if (item.termResultCollapsed.isNotEmpty())
                    item.termResultCollapsed.takeLast(AppState.toolCollapsedLines.coerceAtLeast(3))
                else emptyList()
                if (liveTerm.isNotEmpty()) {
                    AnsiText(
                        lines = liveTerm,
                        fontSize = AppState.fontToolSp.sp,
                        applyBackground = false,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                val live = item.toolLive.trimEnd()
                if (live.isNotBlank()) {
                    val liveScroll = rememberScrollState()
                    LaunchedEffect(live.length) { liveScroll.scrollTo(liveScroll.maxValue) }
                    Text(
                        live,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = AppState.fontToolSp.sp, fontFamily = toolFont(live)),
                        color = TerminalColors.muted(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp)
                            .verticalScroll(liveScroll),
                    )
                }
                }
                // 运行中的计时行：**终端就是每秒重画的 `Elapsed 12s`**
                // （pi 的 render-utils.toolTimerText 在 isPartial 时返回 Elapsed，并每秒 invalidate）。
                // poll / write_stdin 这类会阻塞很久的工具，没有这行就看不出“还在跑”。
                if (item.toolStartedAt > 0) {
                    var nowMs by remember(item.key, item.toolStartedAt) {
                        mutableLongStateOf(System.currentTimeMillis())
                    }
                    LaunchedEffect(item.key, item.toolStartedAt) {
                        while (true) {
                            nowMs = System.currentTimeMillis()
                            kotlinx.coroutines.delay(500)
                        }
                    }
                    Text(
                        fmtTimer((nowMs - item.toolStartedAt).coerceAtLeast(0), running = true),
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = AppState.fontToolSp.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        ),
                        color = TerminalColors.muted(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // ④ 结果：终端就是"输出原文"，不再套「参数 / 结果」小标题，也不列「查看 xxx」入口
            if (hasDetail && !item.toolRunning) {
                val text = shown.joinToString("\n")
                // 带围栏的输出（MCP 回 markdown / mermaid / html）交给 markdown 渲染器，否则图会变成一坨代码
                // ★ 终端原样渲染（继承 pi）：桥已用 pi 自己的 renderResult 把这条结果渲染成 HTML
                //   （edit 的 diff 块、bash 的输出框…）。展开时优先用它，app 不再重排一遍；
                //   收起仍用轻量文本（每行一个 WebView 太贵，滚动也卡）。
                if (termLines.isNotEmpty()) {
                    // 高度**按 app 自己的配置**（杂项面板：展开限高 / 收起限高 / 收起行数）。
                    // 口径（面板上写的就是这个意思）：内容仍然是「终端原样渲染」，
                    // 这几项只决定**露多少、多大** —— 所以限高 + 内部滚动保留。
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = if (expanded) AppState.toolExpandedMaxDp.dp else AppState.toolCollapsedMaxDp.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        AnsiText(
                            lines = termLines,
                            fontSize = AppState.fontToolSp.sp,
                            applyBackground = false,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                } else if (expanded && item.dispHtmlExpanded.isNotBlank()) {
                    ToolHtmlBlock(item.dispHtmlExpanded)
                } else if (result.contains("```")) {
                    MarkdownText(
                        text = if (expanded) result else text,
                        style = MaterialTheme.typography.bodySmall,
                        color = TerminalColors.muted(),
                        modifier = Modifier.fillMaxWidth().heightIn(max = AppState.toolExpandedMaxDp.dp).verticalScroll(rememberScrollState()),
                    )
                } else {
                    // 输出里的路径**行内可点**（像终端的超链接）：点一下就能「查看交付」。
                    // 收起用 **maxLines 按视觉行数**截（3 行）；展开给全文 + 限高内滚 ——
                    // 之前按"逻辑行"截 3 行，长英文一句就折成 4 行，卡片照样很高（用户说的"收起还是太大"）。
                    val annotated = annotatePaths(text)
                    SelectionContainer {
                        Text(
                            annotated,
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = AppState.fontToolSp.sp, fontFamily = toolFont(text)),
                            color = TerminalColors.muted(),
                            softWrap = true,
                            maxLines = if (expanded) Int.MAX_VALUE else previewLines,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(if (expanded) Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()) else Modifier),
                        )
                    }
                }
                // 终端那行灰字：`took 2.7s · exit_code=0 · log: <path>`（路径可点，点开就是查看交付）
                val foot = toolFooter(item, duration)
                if (foot.isNotBlank() && !hasTerm) {
                    Text(
                        annotatePaths(foot),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = AppState.fontMetaSp.sp),
                        color = TerminalColors.footer(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // 工具读到的图 / 操作过的文件：**只给一行可点入口，不往卡片里塞缩略图**
            // （用户口径 2026-10-24：图片不在 toolcall 里直接显示，点它才看图；见 issue #40）。
            val openable = viewablePath(
                toolName = item.toolName,
                argsJson = item.toolArgsJson,
                resultText = result,
            )
            // read 一张图时 pi 的收起渲染**本来就是空**（termHasCollapsed，正文不露）⇒ hasDetail 可能是 false，
            // 结果文本也可能只有那行占位 ⇒ 光看 hasDetail/结果都拿不到入口。所以「read + 参数是图」
            // 直接给入口（2026-10-24 用户截图里那张卡就是因为这个什么都不显示）。
            val readImage = item.toolName.lowercase().substringAfterLast('.') == "read" &&
                openable != null && isImagePath(openable)
            when (val entry = toolImageEntry(
                openable = openable,
                imageCount = item.toolImages.size,
                running = item.toolRunning,
                showPath = hasDetail || item.toolImagePaths.isNotEmpty() || readImage,
            )) {
                is ToolImageEntry.Path -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { AppState.openPath(entry.path) }
                        .padding(vertical = 2.dp),
                ) {
                    Text(
                        viewActionLabel(entry.path) + "  " + entry.path,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = AppState.fontMetaSp.sp),
                        color = TerminalColors.link(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                // 只有 base64、认不出路径（比如扩展工具返回截图）：直接开全屏看图器，同样不内联
                is ToolImageEntry.Data -> Text(
                    "查看图片（${entry.count} 张）",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = AppState.fontMetaSp.sp),
                    color = TerminalColors.link(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { AppState.openImageViewer(item.toolImages, 0) }
                        .padding(vertical = 2.dp),
                )

                ToolImageEntry.None -> Unit
            }
        }
    }
}

/**
 * 工具卡底部那行灰字，**按终端口径**拼：`took 2.7s · exit_code=0 · log: <path>`。
 * 只放用户真正关心的三段（耗时 / 退出码 / 日志位置），不再有「N 行」「收起」这类噪音。
 */
private fun toolFooter(item: ChatItem, duration: String?): String {
    val parts = mutableListOf<String>()
    duration?.let { parts += it.replaceFirstChar { c -> c.lowercase() } }
    val text = item.toolResult
    Regex("(?m)^\\s*exit_code\\\\s*[=:]\\s*(-?\\d+)").find(text)?.let {
        parts += "exit_code=" + it.groupValues[1]
    }
    Regex("(?m)^\\s*log(?:_path)?\\s*[=:]\\s*(\\S+)").find(text)?.let {
        parts += "log: " + it.groupValues[1]
    }
    return parts.joinToString(" · ")
}

/**
 * 工具输出的字体：**命令/代码类用等宽，含较多中文用正文字体**。
 *
 * 为什么：等宽字体没有中文，系统会回退到一个"半宽/全宽混排"的中文字体，中文会被拉成
 * `修复   host-a   pi   的` 这种一格一格的怪样子，还会把 `MCP` 挤到下一行（用户截图里的问题）。
 * 中文为主的内容（`session_title: 修复 …` 这类工具回显）直接用正文字体，换行按正常排版走。
 */
private fun toolFont(text: String): FontFamily {
    if (text.isEmpty()) return FontFamily.Monospace
    var cjk = 0
    var total = 0
    for (ch in text) {
        if (ch.isWhitespace()) continue
        total++
        val code = ch.code
        val isCjk = (code in 0x4E00..0x9FFF) || (code in 0x3400..0x4DBF) ||
            (code in 0x3000..0x303F) || (code in 0xFF00..0xFFEF)
        if (isCjk) cjk++
    }
    if (total == 0) return FontFamily.Monospace
    return if (cjk * 100 / total >= 10) FontFamily.Default else FontFamily.Monospace
}

/**
 * 展开后的参数块：**逐行 `key: value`**（字符串原样、多行缩进续行），对齐 pi 终端
 * `formatToolCallWithArgs(..., expanded = true)` 的形状。
 */
private fun argsDetail(argsJson: String): String {
    val obj = runCatching { org.json.JSONObject(argsJson) }.getOrNull() ?: return ""
    if (obj.length() == 0) return ""
    return obj.keys().asSequence().joinToString("\n") { k ->
        val v = obj.opt(k)
        val text = when (v) {
            is String -> v
            null -> "null"
            else -> v.toString()
        }
        "  " + k + ": " + text.replace("\r", "").replace("\n", "\n    ")
    }
}

/**
 * MCP 工具名的显示形态：`mcp__obsidian_server__search_notes` → (`search_notes`, `obsidian_server`)。
 * 不是 MCP 的（`bash` / `read` / `codemode` …）原样返回，第二个元素为 null。
 */
private fun shortToolName(name: String): Pair<String, String?> {
    if (!name.startsWith("mcp__")) return name to null
    val rest = name.removePrefix("mcp__")
    val cut = rest.indexOf("__")
    if (cut <= 0) return name to null
    return rest.substring(cut + 2) to rest.substring(0, cut)
}

/** 参数摘要：按 pi 终端 `key=JSON.stringify(value)` 拼到标题行，整体截到 100 字符。 */
private fun toolArgsLine(item: ChatItem): String {
    val json = item.toolArgsJson
    if (json.isNotBlank()) {
        val obj = runCatching { org.json.JSONObject(json) }.getOrNull()
        if (obj != null && obj.length() > 0) {
            val keys = obj.keys().asSequence().toList()
            // 终端就是这么显示的：`read D:/x.png`、`$ cd … && adb …` —— 单个字符串参数**原样**给，
            // 不加引号、不转义（之前 JSON.stringify 那条路会写出 /\/ 这种反人类的东西）
            // 一律 `key=value`（pi 终端也是 k=JSON.stringify(v)）：单参数若只给裸值，
            // 界面上会变成「工具名 + 一个孤零零的词」，看不出那是什么（用户截图里的 `pi`）。
            val only = keys.singleOrNull()?.let { obj.opt(it) }
            if (false && keys.size == 1 && only is String) return clip(only.replace("\n", " "))
            val pairs = keys.joinToString(" ") { k ->
                val v = obj.opt(k)
                k + "=" + when (v) {
                    is String -> v.replace("\n", " ")
                    null -> "null"
                    else -> v.toString()
                }
            }
            return clip(pairs)
        }
    }
    return clip(item.toolBrief)
}

private fun clip(text: String): String {
    // 把换行/制表/重复空格压成单空格；**不按长度截断**（截断会让参数显示不全）。
    // 不用 Regex / 转义字面量，避免源码被写坏。
    val sb = StringBuilder(text.length)
    var lastSpace = false
    for (ch in text) {
        val c = if (ch.isWhitespace()) ' ' else ch
        if (c == ' ' && lastSpace) continue
        sb.append(c)
        lastSpace = c == ' '
    }
    return sb.toString().trim()
}

/** 与 pi 终端一致：<60s 用 `Took 4.2s`，再长用 `1m 30s`。 */
private fun fmtDuration(ms: Long): String = fmtTimer(ms, running = false)

/** pi 的计时行：运行中 `Elapsed 4.2s`（每秒重画）、完成 `Took 4.2s`（口径同 render-utils.toolTimerText）。 */
private fun fmtTimer(ms: Long, running: Boolean): String {
    val prefix = if (running) "Elapsed " else "Took "
    val seconds = ms / 1000.0
    if (seconds < 60) return prefix + String.format("%.1fs", seconds)
    val total = ms / 1000
    val minutes = total / 60
    val rem = total % 60
    return if (minutes < 60) prefix + minutes + "m " + rem + "s"
    else prefix + (minutes / 60) + "h " + (minutes % 60) + "m " + rem + "s"
}

/**
 * 助手：thinking 走 ZCode 口径（**默认收起一行**：“思考 · 末行摘要”，点它就地展开；展开后限高 + 内部滚动）；
 * 正文按终端色/字号（bodyMedium，与 thinking/工具标签同号）。**不用 brain 图标**（用户口径）。
 */
@Composable
private fun AssistantBubble(
    item: ChatItem,
    shimmerTail: Boolean = false,
    streaming: Boolean = false,
    onGrow: () -> Unit = {},
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (item.thinking.isNotBlank()) {
            val open = AppState.isOpen(item.key, false)
            val lastLine = item.thinking.trimEnd().lineSequence().lastOrNull { it.isNotBlank() }?.trim().orEmpty()
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .clickable { AppState.toggleOpen(item.key, false) },
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        if (open) "思考" else "思考 · " + lastLine.ifBlank { "…" },
                        style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
                        color = TerminalColors.thinkingText(),
                        // 折叠时**恒定一行**：原来最多两行会让卡片高度在 1↔2 行之间跳（就是"闪烁"）
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (open) {
                        val thinkingScroll = rememberScrollState()
                        // 还在流式输出：卡片高度**钉死**，新内容在卡内往下滚（外面窗口纹丝不动，不再闪）
                        LaunchedEffect(item.thinking.length) {
                            thinkingScroll.scrollTo(thinkingScroll.maxValue)
                        }
                        // pi 的 thinking 是**按 Markdown 渲染**的（assistant-message.ts）：
                        // `new Markdown(thinking, …, { color: thinkingText, italic: true })`
                        // ⇒ 里面的 **加粗 / 行内代码 / 列表 / 引用** 都有样式，整块还是斜体。
                        // app 以前是一段纯 Text —— 于是"thinking 里面也是有样式的"全丢了（用户 2026-10-23）。
                        MarkdownText(
                            text = item.thinking.trimEnd(),
                            style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
                            color = TerminalColors.thinkingText(),
                            headingColor = TerminalColors.thinkingText(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(if (shimmerTail) Modifier.height(280.dp) else Modifier.heightIn(max = 320.dp))
                                .verticalScroll(thinkingScroll),
                        )
                    }
                }
            }
        }
        if (item.text.isNotBlank()) {
            TypewriterText(
                text = item.text,
                streaming = streaming && item.streaming,
                shimmerTail = shimmerTail,
                // 还在写这条回答 ⇒ 图先显示成代码块（pi 的 mermaid.ts 同口径）：
                // 否则每个 token 都会重建一次 WebView，长回答带图会直接把主线程拖死。
                deferDiagrams = item.streaming,
                onGrow = onGrow,
            )
        }
        if (item.streaming && item.text.isBlank() && item.thinking.isBlank()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LoadingIndicator(modifier = Modifier.size(16.dp), color = TerminalColors.muted())
                Text("…", style = MaterialTheme.typography.bodyMedium, color = TerminalColors.muted())
            }
        }
        if (item.footer.isNotBlank()) {
            Text(item.footer, style = MaterialTheme.typography.bodySmall, color = TerminalColors.footer())
        }
    }
}

/** 用户：终端里就是正文色、无前缀、无底色。 */
@Composable
private fun UserBubbleDocAnchor() { /* 文档锚点：真正的实现在 ItemView 的 isUser 分支 */ }

/** 摘要整条看起来就是个路径（read/write/edit 的 path）→ 返回它，否则 null。 */
private fun deliverablePathOf(brief: String): String? {
    val s = brief.trim()
    if (s.startsWith("/") && !s.contains(' ') ) return s
    if (s.startsWith("~/") && !s.contains(' ')) return s
    return null
}

/**
 * 从工具输出里挑出**可看的路径**（结果第一行是路径、或 `key: /path` 这种）。
 * 只认绝对路径/`~/` 开头，最多返回 3 个去重后的结果，避免刷屏。
 */
private fun deliverablePathsIn(text: String): List<String> {
    val out = LinkedHashSet<String>()
    val re = Regex("(?:^|[\\s\"'(:=])((?:/[\\w.\\-]+)+|~/[\\w./\\-]+)")
    for (match in re.findAll(text)) {
        val candidate = match.groupValues[1].trimEnd('.', ',', ')')
        if (candidate.length < 4 || candidate.count { it == '/' } < 2) continue
        if (candidate.startsWith("/dev/") || candidate.startsWith("/proc/") || candidate.startsWith("/sys/")) continue
        out += candidate
        if (out.size >= 3) break
    }
    return out.toList()
}

/**
 * 把文本里的**绝对路径**做成可点的行内链接（`LinkAnnotation`）：
 * 视觉与原文完全一致（不着色、不加行），点一下走「查看交付」。
 * 与 `deliverablePathsIn` 同一套匹配，但这里不截断数量（用户看到哪行都能点）。
 */
@Composable
private fun annotatePaths(text: String): AnnotatedString = buildAnnotatedString {
    val re = Regex("(?:^|[\\s\"'(:=])((?:/[\\w.\\-]+)+|~/[\\w./\\-]+)")
    var cursor = 0
    for (match in re.findAll(text)) {
        val candidate = match.groupValues[1].trimEnd('.', ',', ')')
        if (candidate.length < 4 || candidate.count { it == '/' } < 2) continue
        if (candidate.startsWith("/dev/") || candidate.startsWith("/proc/") || candidate.startsWith("/sys/")) continue
        val start = match.range.first + match.value.indexOf(candidate)
        if (start < cursor) continue
        append(text.substring(cursor, start))
        // pushLink/pop 是非 @Composable 的写法（withLink 在这里会要求 @Composable 上下文）
        pushLink(
            LinkAnnotation.Clickable(
                tag = candidate,
                styles = TextLinkStyles(style = SpanStyle(color = TerminalColors.link())),
                linkInteractionListener = { AppState.openPath(candidate) },
            ),
        )
        append(candidate)
        pop()
        cursor = start + candidate.length
    }
    append(text.substring(cursor))
}

@Composable
private fun ItemView(
    item: ChatItem,
    isLast: Boolean = false,
    onStreamingGrow: () -> Unit = {},
) {
    when {
        // 终端里 `!cmd` 跑的命令（pi 的 BashExecutionMessage）：卡片与工具卡同构 ——
        // 头行是命令本身，下面是输出（限高内滚），footnote 是 `exit 0 / 已截断 / 不进上下文`。
        item.role == "bash" -> CommandCard(item)

        // 分叉摘要（pi 的 BranchSummaryMessage）：和压缩卡同一套视觉，说明"这条分支从哪来"
        item.role == "branch" -> SummaryCard(
            title = "分支摘要",
            body = item.text,
            accent = TerminalColors.rule(),
            note = item.footer.takeIf { it.isNotBlank() && it != "分支摘要" },
        )

        // 压缩摘要（少数路径以消息形式给出）
        item.role == "compactionSummary" -> SummaryCard(
            title = "压缩摘要",
            body = item.text,
            accent = TerminalColors.inlineCode(),
            note = item.footer.takeIf { it.isNotBlank() && it != "压缩摘要" },
        )

        // 用户消息：**两边对齐、铺满宽度**（用户 2026-10-21 口径，取代 2026-10-19 的「靠右 w-fit 气泡」）。
        // 理由：手机上靠右窄气泡会让长消息频繁折行、与下方工具卡的左对齐关系被打断；铺满更好读。
        item.isUser -> Row(
            // 左右边距必须与工具卡一致（14dp）：之前 12dp，用户卡片比工具卡宽 2dp，看着"没对齐"。
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.Start,
        ) {
            Surface(
                // 用户消息的卡片：**用蓝色**（终端实测的 deep blue `rgb(33,59,73)`，用户 2026-10-19 口径）。
                // 之前改成 surfaceContainerHigh（中性灰）看着和助手分不开。
                color = TerminalColors.userBlock(),
                contentColor = TerminalColors.body(),
                shape = MaterialTheme.shapes.medium,
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    if (item.text.isNotBlank()) {
                        SelectionContainer {
                            MarkdownText(
                                text = item.text,
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = AppState.fontBodySp.sp),
                                color = TerminalColors.body(),
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            )
                        }
                    }
                    // 发的图发完也要能回看（历史里桥默认把原图剥了，点一下按需拉）
                    UserImages(item)
                }
            }
        }

        item.isTool -> ToolRow(item)
        item.isNotice -> NoticeRow(item)
        else -> AssistantBubble(
            item,
            shimmerTail = AppState.streaming && isLast,
            // 逐字显现只在"最后一条且确实在流式"时生效
            streaming = isLast,
            onGrow = onStreamingGrow,
        )
    }
}

/** 用户消息里的图：能直接拿到就画缩图，拿不到（历史被桥剥过）就给一个按需加载入口。 */
@Composable
private fun UserImages(item: ChatItem) {
    if (item.images.isEmpty() && item.imagesOmitted == 0) return
    // viewing = 正在预览第几张（可左右切，对齐 ZCode image-preview-dialog 的导航）
    var viewing by remember { mutableStateOf<Int?>(null) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        item.images.take(6).forEachIndexed { index, ref ->
            val bitmap = rememberDecodedBitmap(ref.base64, thumbSizeDp(ref.base64.length))
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = "消息图片",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(96.dp)
                        .clip(MaterialTheme.shapes.medium)
                        .clickable { viewing = index },
                )
            }
        }
        if (item.images.isEmpty() && item.imagesOmitted > 0) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.clip(MaterialTheme.shapes.medium)
                    .clickable(enabled = !AppState.imagesLoading) { AppState.loadHistoryImages() },
            ) {
                Text(
                    if (AppState.imagesLoading) "加载图片…" else "图片 ×${item.imagesOmitted} · 点开",
                    style = MaterialTheme.typography.labelMedium,
                    color = TerminalColors.muted(),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
    }
    viewing?.let { start ->
        AppState.openImageViewer(item.images, start)
        viewing = null
    }
}

/**
 * 斜杠补全，两段式：
 * 1. 只打了 `/xxx` → 命令候选（全部命中可滚动）；
 * 2. 已经带参数（`/model fl`）→ **参数候选**：
 *    `/model` 给模型名、`/mode` 给权限模式、`/skill` 给 skill、`/resume`·`/fork` 给会话 id、
 *    `/thinking` 给思考等级；其余命令不猜。
 */
@Composable
private fun SlashSuggestions(query: String, onPick: (String) -> Unit) {
    // 中文输入法常把 / 打成全角／，得归一化，否则没候选
    val q = query.replace('／', '/').replace('\u3000', ' ')
    if (!q.startsWith("/") || q.contains('\n')) return

    // 以第一个空格分成「命令」和「参数」
    val withoutSlash = q.removePrefix("/")
    val spaceAt = withoutSlash.indexOf(' ')
    val isArgStage = spaceAt >= 0
    val commandName = if (isArgStage) "/" + withoutSlash.substring(0, spaceAt) else q
    val argQuery = if (isArgStage) withoutSlash.substring(spaceAt + 1).trim() else ""

    // 参数候选需要数据：没拉过就先拉（否则 `/model ` 抽屉里空着，用户以为没做）
    LaunchedEffect(commandName) {
        if (commandName == "/model" && AppState.models.isEmpty()) AppState.loadModels()
        if ((commandName == "/resume" || commandName == "/fork") && AppState.sessions.isEmpty()) {
            AppState.refreshSessions(silent = true)
        }
    }

    val candidates: List<String>
    val descriptions: Map<String, String>
    if (!isArgStage) {
        val needle = q.removePrefix("/")
        // 清单 = pi 的**内置命令**（从 pi 交互模式继承，见桥 /v1/slash）+ 扩展/skill/模板。
        // 之前只要 bridge 返回了扩展命令就把内置命令全藏了 ⇒ `/reload` 这类压根看不到。
        val builtin = AppState.slashCommands.map {
            CommandRef(
                it.name,
                if (it.available) it.description else (it.description.ifBlank { "pi 终端专属" } + " · 仅终端"),
            )
        }
        // 排序：**能用的在前**（内置可用 + 扩展/skill），pi 终端专属的排到最后，
        // 否则 /arminsayshi、/bug 这类把真正能用的命令全挤到下面去了。
        val availableBuiltin = builtin.filter { !it.description.endsWith("仅终端") }
        val tuiOnly = builtin.filter { it.description.endsWith("仅终端") }
        val merged = (availableBuiltin + AppState.commands + tuiOnly).distinctBy { it.name }
        val source = if (merged.isNotEmpty()) merged else BUILTIN_SLASH_COMMANDS
        var ranked = rankCommands(source, needle)
        // rankCommands 会按名字/相关度重排 ⇒ **排序后再分区**：能用的在前，pi 终端专属的垫底
        ranked = ranked.filterNot { it.description.endsWith("仅终端") } +
            ranked.filter { it.description.endsWith("仅终端") }
        candidates = ranked.map { it.name }
        descriptions = ranked.associate { it.name to it.description }
    } else {
        val filtered = filterArgumentOptions(
            commandName,
            argQuery,
            AppState.models,
            AppState.commands,
            AppState.sessions,
        )
        candidates = filtered.map { it.first }
        descriptions = filtered.associate { it.first to it.second }
    }

    if (candidates.isEmpty()) return
    val shown = candidates.take(50)

    // 抽屉式候选（用户 2026-10-21：**要像从输入框延伸出去的**，不是浮在中间的卡片）。
    // 做法：全宽、只圆上面两角、底部与输入区贴合、顶部一根把手 —— 视觉上就是输入框长出来的一层。
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 4.dp)) {
            // 抽屉把手
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(bottom = 4.dp)
                    .size(width = 32.dp, height = 3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)),
            )
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp)) {
                // key 带下标：候选值理论上可能重名（模型 id 同名 / 会话同名……），
                // 而 LazyColumn 遇到重复 key 会直接抛 IllegalArgumentException ⇒ app 闪退。
                itemsIndexed(shown, key = { i, v -> "$i|$v" }) { _, value ->
                    val desc = descriptions[value].orEmpty()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(if (isArgStage) "$commandName $value" else value) }
                            .padding(horizontal = 20.dp, vertical = 9.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            value,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Medium,
                            ),
                            color = TerminalColors.inlineCode(),
                            maxLines = 1,
                        )
                        if (desc.isNotBlank()) {
                            Text(
                                desc,
                                style = MaterialTheme.typography.bodySmall,
                                color = TerminalColors.muted(),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
            Text(
                if (candidates.size > shown.size) "共 ${candidates.size} 个匹配（显示前 ${shown.size}）"
                else if (isArgStage) "${commandName} 的参数：共 ${candidates.size} 个"
                else "共 ${candidates.size} 个匹配",
                style = MaterialTheme.typography.labelSmall,
                color = TerminalColors.footer(),
                modifier = Modifier.padding(start = 20.dp, top = 4.dp, bottom = 2.dp),
            )
        }
    }
}

/** 桥没返回命令清单时的兜底（pi 自带的一部分）。 */
private val BUILTIN_SLASH_COMMANDS = listOf(
    CommandRef("/help", "查看帮助"),
    CommandRef("/model", "切换模型"),
    CommandRef("/mode", "权限模式 build/edit/plan/yolo"),
    CommandRef("/compact", "压缩上下文"),
    CommandRef("/new", "新建会话"),
    CommandRef("/resume", "恢复会话"),
    CommandRef("/skill", "加载 skill"),
    CommandRef("/mcp", "MCP 服务管理"),
    CommandRef("/fork", "从检查点分叉"),
    CommandRef("/rewind", "回滚工作区"),
    CommandRef("/session", "会话信息"),
    CommandRef("/settings", "设置"),
)

/**
 * 「压缩上下文」卡片：终端里压缩行是紫色，这里用紫色淡底 + 紫色文字。
 *
 * 压缩**进行中**（`running`）时标题前带一个转圈 —— 用户口径：「正在压缩上下文要显示动画」
 * （压缩可能跑几十秒，只有一行静止文字看不出还在不在跑）。
 */
@Composable
private fun CompactionCard(info: CompactionCardInfo) {
    // 展开态是本地的：卡片自己记住，不写回 AppState（重进会话默认收起，与 pi 一致）
    var expanded by remember(info.afterKey, info.summary) { mutableStateOf(false) }
    val hasSummary = hasCompactionSummary(info.summary)
    val canExpand = hasSummary && !info.running
    Card(
        colors = CardDefaults.cardColors(
            containerColor = TerminalColors.inlineCode().copy(alpha = 0.22f),
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .then(if (canExpand) Modifier.clickable { expanded = !expanded } else Modifier),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (info.running) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = TerminalColors.inlineCode(),
                    )
                }
                Text(info.title, style = MaterialTheme.typography.bodySmall, color = TerminalColors.inlineCode())
            }
            // 详情**独占一行**：塞在标题右边会被挤成"省 …"（截断），而这一行正是用户要读的数字
            if (info.detail.isNotBlank()) {
                Text(
                    info.detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = TerminalColors.muted(),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // 摘要（pi 那张卡展开就是这段）：折叠时给一句可点的提示 —— 手机上没快捷键，得写出来
            if (canExpand) {
                if (expanded) {
                    MarkdownText(
                        text = info.summary,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = (AppState.fontBodySp - 1).sp),
                        color = TerminalColors.muted(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp)
                            .verticalScroll(rememberScrollState()),
                    )
                }
                Text(
                    compactionToggleHint(expanded, hasSummary).orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                    color = TerminalColors.link(),
                )
            }
        }
    }
}

/**
 * 弹层里的「标签 / 值」一行（M3 列表项的紧凑版：标签 muted、值 onSurface，值可换行）。
 * 用固定标签列而不是 `label: value` 拼接 —— 拼起来的长值会把整行读成一坨。
 */
/**
 * 「上下文」弹层（用户 2026-10-23：**要按钮上方的 popup，不要居中全屏**）。
 *
 * 位置由 [AboveAnchor] 精确算：贴住锚点（上下文 pill）上方 8dp，
 * 上方放不下就翻到下方，左右夹在屏幕内。内容比"居中大卡片"更紧凑 ——
 * 一眼要看的（百分比 / 进度 / 用量 / 花费）留在弹层，**完整信息进「会话信息」抽屉**。
 */
/**
 * 上下文抽屉（用户 2026-10-23：「上下文窗口改成原地抽屉上升」）。
 *
 * 不是浮层（Popup/Dialog），而是**布局内的抽屉**：长在工具栏上方，展开时消息区被顶上去。
 * 形状/排版沿用 M3（`shapes.large` 顶部圆角 + `surfaceContainer` + tonal 浮起 +
 * 4dp 细进度条 + 右对齐文本按钮），顶部给一条 M3 抽屉把手。
 */
@Composable
private fun ContextDrawer(
    percent: Double,
    hasPercent: Boolean,
    ringColor: Color,
    info: id.harness.pipocket.PiInfo,
    onCompact: () -> Unit,
    onAllInfo: () -> Unit,
    onCollapse: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 4.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 2.dp,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
            // M3 抽屉把手（32×4）：一眼看出"这是能收起的抽屉"
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 4.dp)
                    .height(4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 32.dp, height = 4.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)),
                )
            }
            Column(
                modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "上下文",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        if (hasPercent) "%.1f".format(percent) + "%" else "—",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { (percent / 100.0).toFloat().coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                    color = ringColor,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    gapSize = 2.dp,
                    drawStopIndicator = {},
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    formatTokens(info.contextTokens) + " / " + formatTokens(info.contextWindow) +
                        (if (info.autoCompaction) " (auto)" else ""),
                    style = MaterialTheme.typography.bodyMedium,
                )
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
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Spacer(Modifier.height(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ContextFact(
                        "模型",
                        modelWithProvider(info.provider, info.model, info.thinking, info.modelDisplay),
                    )
                    ContextFact(
                        "目录",
                        listOfNotNull(
                            info.cwd.takeIf { it.isNotBlank() }?.let { shortenPath(it) },
                            gitBranchLabel(info.gitBranch),
                        ).joinToString(" "),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onCollapse) {
                        Text("收起", style = MaterialTheme.typography.labelLarge)
                    }
                    TextButton(onClick = onCompact) {
                        Text("压缩", style = MaterialTheme.typography.labelLarge)
                    }
                    TextButton(onClick = onAllInfo) {
                        Text("会话信息", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}

@Composable
private fun ContextFact(label: String, value: String, mono: Boolean = false) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(52.dp),
        )
        Text(
            value,
            style = if (mono) {
                MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
            } else {
                MaterialTheme.typography.bodyMedium
            },
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * 流式正文的**逐字显现**（用户 2026-10-22：「streaming 不够流畅，要有逐字滚动输出动效」）。
 *
 * 做法：每帧按积压量推进若干个字（`nextReveal`），渲染 `text.take(shown)`。
 *  · 只对**最后一条正在流式**的消息生效；写完（streaming=false）立刻显示全文；
 *  · 每帧回调 [onGrow]，让外面把列表贴回底部 —— 否则新长出来的字会被顶到屏幕外；
 *  · 仍然复用 `MarkdownText`（同一套解析与样式），只是喂给它一个**截断后**的字符串。
 *
 * 代价说明：逐字意味着每帧解析一次 markdown。为控制成本：
 *  · 积压 > [REVEAL_BAIL_CHARS] 时直接跳全文（不做动画）；
 *  · 动画只在流式期间存在，结束后不再重算。
 */
@Composable
private fun TypewriterText(
    text: String,
    streaming: Boolean,
    shimmerTail: Boolean,
    deferDiagrams: Boolean,
    onGrow: () -> Unit,
) {
    // 目标长度用 rememberUpdatedState：**动画循环不随每个 delta 重启**。
    // 之前把 `text` 放进 LaunchedEffect 的 key，每来一个增量循环就被取消重建，
    // 帧计数清零 ⇒ 逐字效果时断时续（自证日志一条都打不出来，就是因为永远凑不满计数）。
    val target by rememberUpdatedState(text)
    // 起始：这条消息第一次出现时有多少字就从多少开始（中途进会话不会重放历史）
    var shown by remember { mutableIntStateOf(text.length) }

    LaunchedEffect(streaming) {
        if (!streaming) {
            shown = target.length
            return@LaunchedEffect
        }
        var ticks = 0
        while (true) {
            withFrameNanos { }
            val total = target.length
            if (shown > total) shown = total
            if (shown < total) {
                shown = nextReveal(shown, total)
                if (++ticks % 2 == 0) onGrow()
                if (ticks % 6 == 0) {
                    android.util.Log.d("PiPocketTypewriter", "shown=$shown total=$total")
                }
            } else if (ticks > 0) {
                // 追平了：坐着等下一个 delta（循环不退出，避免反复重建）
                ticks = 0
                android.util.Log.d("PiPocketTypewriter", "caught-up shown=$shown total=$total")
            }
        }
    }
    MarkdownText(
        text = if (shown >= text.length) text else text.take(shown.coerceIn(0, text.length)),
        style = MaterialTheme.typography.bodySmall.copy(fontSize = AppState.fontBodySp.sp),
        color = TerminalColors.body(),
        shimmerTail = shimmerTail,
        deferDiagrams = deferDiagrams,
    )
}

/** 超过这个字符数就不给选区（见调用处的注释）。 */
private const val SELECTABLE_MAX_CHARS = 6_000

/**
 * 小内容才包 [SelectionContainer]；大块直接画（避免 SelectableText 的 intrinsic 测量把主线程拖死）。
 */
@Composable
private fun SelectableIfSmall(row: ChatRow, content: @Composable () -> Unit) {
    val size = when (row) {
        is ChatRow.Message -> row.item.text.length + row.item.toolResult.length +
            row.item.termResultExpanded.sumOf { it.length }
        is ChatRow.Compaction -> row.info.summary.length
    }
    if (size <= SELECTABLE_MAX_CHARS) SelectionContainer { content() } else content()
}

/**
 * 命令卡（`!cmd`）—— 终端里那条 `$ ls -la` 的 app 版。
 *
 * 头行 `! 命令`（等宽、可选中），下面是输出；**默认收起**（与工具卡一致：终端也是收起一条），
 * 出错（exit != 0 / 取消）时头行给红色并自动展开，省一次点击。
 */
@Composable
private fun CommandCard(item: ChatItem) {
    var open by remember(item.key) { mutableStateOf(item.toolIsError) }
    val accent = if (item.toolIsError) TerminalColors.danger() else TerminalColors.bashMode()
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        // **整张卡**可点：之前只有头行 Row 可点，手机上一行文字的高度太窄、点不中（实测踩到）
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp)
            .clickable(enabled = item.toolResult.isNotBlank()) { open = !open },
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("$", style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), color = accent)
                Text(
                    item.text,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = if (open) Int.MAX_VALUE else 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (item.footer.isNotBlank()) {
                    Text(item.footer, style = MaterialTheme.typography.labelSmall, color = TerminalColors.muted())
                }
                // 有输出就提示可以点开（终端里是自动展开/快捷键，手机上得写出来）
                if (item.toolResult.isNotBlank()) {
                    Text(
                        if (open) "收起" else "展开",
                        style = MaterialTheme.typography.labelSmall,
                        color = TerminalColors.link(),
                    )
                }
            }
            if (open && item.toolResult.isNotBlank()) {
                Text(
                    item.toolResult.trimEnd(),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = TerminalColors.body(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                )
            }
        }
    }
}

/**
 * 摘要卡（分叉摘要 / 压缩摘要）：标题 + 正文（markdown，限高内滚）。
 * 与「压缩上下文」卡同一套形状：这类"系统给的一段话"在时间线上要能一眼认出来。
 */
@Composable
private fun SummaryCard(title: String, body: String, accent: androidx.compose.ui.graphics.Color, note: String?) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = accent.copy(alpha = 0.18f),
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = MaterialTheme.typography.bodySmall, color = accent)
                if (note != null) {
                    Text(note, style = MaterialTheme.typography.labelSmall, color = TerminalColors.muted())
                }
            }
            if (body.isNotBlank()) {
                SelectionContainer {
                    MarkdownText(
                        text = body,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = (AppState.fontBodySp - 1).sp),
                        color = TerminalColors.muted(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp)
                            .verticalScroll(rememberScrollState()),
                    )
                }
            }
        }
    }
}

/**
 * 「已导出」对话框 —— 对应终端 `/export` 之后的那句 `Exported to <path>`。
 *
 * 手机上多给两件事：**复制路径**（发给自己/粘到别处）和**直接打开**（看导出的效果）。
 */
@Composable
private fun ExportDoneDialog() {
    val result = AppState.exportResult ?: return
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val path = result.path ?: return
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { AppState.dismissExport() },
        title = { Text("已导出会话") },
        text = {
            Column {
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(
                        path,
                        style = MaterialTheme.typography.bodySmall,
                        color = TerminalColors.link(),
                    )
                }
                result.bytes?.let {
                    Spacer(Modifier.size(6.dp))
                    Text(
                        id.harness.pipocket.ui.humanBytes(it),
                        style = MaterialTheme.typography.labelSmall,
                        color = TerminalColors.muted(),
                    )
                }
                Spacer(Modifier.size(10.dp))
                Text(
                    "可以直接打开看；也可以复制路径发到别处。",
                    style = MaterialTheme.typography.bodySmall,
                    color = TerminalColors.muted(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                AppState.dismissExport()
                AppState.openPath(path)
            }) { Text("打开") }
        },
        dismissButton = {
            TextButton(onClick = {
                AppState.copyToClipboard(path, "导出路径")
                AppState.dismissExport()
            }) { Text("复制路径") }
        },
    )
}
