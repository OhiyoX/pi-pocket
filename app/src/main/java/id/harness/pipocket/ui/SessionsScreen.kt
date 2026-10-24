@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
)

package id.harness.pipocket.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import id.harness.pipocket.AppState
import id.harness.pipocket.Connection
import id.harness.pipocket.R
import id.harness.pipocket.SessionGroup
import id.harness.pipocket.SessionInfo
import id.harness.pipocket.SessionSort
import id.harness.pipocket.SessionTime

/**
 * 会话列表＝主角页：expressive 大号 flexible top bar（标题随滚动从 headlineMedium 缩到 titleLarge）。
 *
 * 组织方式（用户 2026-10-18 要求「分目录 + 排序/过滤完备」）：
 * - **分组**：按项目目录（sticky 分组头）／按时间（今天/昨天/本周/更早）
 * - **排序**：最近更新 / 创建时间 / 名称
 * - **过滤**：时间窗口（全部/今天/7 天） + 项目多选
 * 这些选择都写进 prefs，下次进来保持。
 */
@Composable
fun SessionsScreen() {
    // 每次进入列表（从会话返回 / 切回前台）都静默对账一次：
    // 不置 loading、不清列表，所以看不到进度条闪烁，但标题与「工作中」会自己对上。
    LaunchedEffect(Unit) { AppState.refreshSessions(silent = true) }
    val listState = rememberLazyListState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var menuOpen by remember { mutableStateOf(false) }
    var showConnection by remember { mutableStateOf(false) }
    var showMachines by remember { mutableStateOf(false) }
    var showNewSession by remember { mutableStateOf(false) }
    var showProjects by remember { mutableStateOf(false) }
    var showAppearance by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var timeMenu by remember { mutableStateOf(false) }
    val atTop by remember { derivedStateOf { !listState.canScrollBackward } }
    val spatialSpec = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
    val effectSpecIn = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val effectSpecOut = MaterialTheme.motionScheme.fastEffectsSpec<Float>()

    val visible = AppState.visibleSessions()
    val grouped = visible.groupBy { it.cwd ?: "(未记录目录)" }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("Pocket Pi") },
                subtitle = {
                    Text(connectionLabel(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                scrollBehavior = scrollBehavior,
                actions = {
                    IconButton(onClick = { showMachines = true }) {
                        Icon(painterResource(R.drawable.ic_machine), contentDescription = "选择机器")
                    }
                    IconButton(onClick = { AppState.refreshSessions(); AppState.refreshInfo() }) {
                        Icon(painterResource(R.drawable.ic_refresh), contentDescription = "刷新")
                    }
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(painterResource(R.drawable.ic_more), contentDescription = "更多")
                    }
                    DropdownMenu(shape = PopSurface.shape(), containerColor = PopSurface.container(), expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("设置") },
                            onClick = { menuOpen = false; AppState.settingsOpen = true },
                        )
                        DropdownMenuItem(
                            text = { Text("选择机器") },
                            onClick = { menuOpen = false; showMachines = true },
                        )
                        DropdownMenuItem(
                            text = { Text("连接设置") },
                            onClick = { menuOpen = false; showConnection = true },
                        )
                        DropdownMenuItem(
                            text = { Text("外观与字号") },
                            onClick = { menuOpen = false; showAppearance = true },
                        )
                        DropdownMenuItem(
                            text = { Text("SSH 管理") },
                            onClick = { menuOpen = false; AppState.openSsh() },
                        )
                        DropdownMenuItem(
                            text = { Text("文件（SFTP）") },
                            onClick = { menuOpen = false; AppState.openFiles() },
                        )
                        DropdownMenuItem(
                            text = { Text("命令执行（Shell）") },
                            onClick = { menuOpen = false; AppState.openShell() },
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            AnimatedContent(
                targetState = atTop,
                transitionSpec = {
                    (fadeIn(animationSpec = effectSpecIn) +
                        scaleIn(animationSpec = spatialSpec, initialScale = 0.85f))
                        .togetherWith(
                            fadeOut(animationSpec = effectSpecOut) +
                                scaleOut(animationSpec = spatialSpec, targetScale = 0.85f),
                        )
                },
                label = "new-session-fab",
            ) { expanded ->
                if (expanded) {
                    ExtendedFloatingActionButton(
                        onClick = { showNewSession = true },
                        icon = { Icon(painterResource(R.drawable.ic_add), contentDescription = null) },
                        text = { Text("新会话") },
                    )
                } else {
                    FloatingActionButton(onClick = { AppState.newSession() }) {
                        Icon(painterResource(R.drawable.ic_add), contentDescription = "新会话")
                    }
                }
            }
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            PendingPromptBanner(
                canOpen = AppState.sessions.isNotEmpty(),
                onOpenSession = { AppState.sessions.firstOrNull()?.let { s -> AppState.openSession(s) } },
            )
            // 切机器 / 刷新：顶部即刻出现进度条（过渡感），列表加载完自动消失
            androidx.compose.animation.AnimatedVisibility(
                visible = AppState.sessionsLoading || AppState.busy,
                enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.expandVertically(),
                exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.shrinkVertically(),
            ) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 2.dp),
                )
            }

            FilterRow(
                onToggleGroup = {
                    AppState.updateSessionGroup(
                        if (AppState.sessionGroup == SessionGroup.Project) SessionGroup.Timeline else SessionGroup.Project,
                    )
                },
                onSortClick = { sortMenu = true },
                onTimeClick = { timeMenu = true },
                onProjectClick = { showProjects = true },
                sortMenu = sortMenu,
                onSortDismiss = { sortMenu = false },
                timeMenu = timeMenu,
                onTimeDismiss = { timeMenu = false },
                visibleCount = visible.size,
                totalCount = AppState.sessions.size,
            )

            Box(modifier = Modifier.fillMaxSize()) {
                when {
                    AppState.connection == Connection.Failed -> Hint(
                        title = if (AppState.connectFailures > 0) "正在重连 pi 桥…（第 ${AppState.connectFailures} 次）" else "连不上 pi 桥",
                        body = buildString {
                            append("会自动重试并逐次拉长间隔（1→30s）；本机桥连续失败时会自动拉起 pi，不用你动手。\n")
                            if (AppState.error != null) append("\n原因：${AppState.error}\n")
                            append("\n当前地址：${AppState.baseUrl}")
                        },
                        actionLabel = "立即重连",
                        onAction = { AppState.connect() },
                        secondaryLabel = "自动探测",
                        onSecondary = { AppState.autoDetect(); showMachines = true },
                    )

                    // 切机器 / 刷新：列表还没来的时候给**动态进度**（用户口径），别显示"还没有会话"
                    AppState.sessions.isEmpty() && (AppState.busy || AppState.sessionsLoading) -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            LoadingIndicator()
                            Text(
                                "正在加载会话…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                AppState.currentHit?.host?.ifBlank { null } ?: AppState.baseUrl,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            // 别让它无限转圈：超过 8s 或已报错 → 显示原因 + 重试
                            var waited by remember(AppState.sessionsLoading) { mutableStateOf(0) }
                            LaunchedEffect(AppState.sessionsLoading) {
                                while (AppState.sessionsLoading && waited < 8) {
                                    kotlinx.coroutines.delay(1000)
                                    waited += 1
                                }
                            }
                            val err = AppState.sessionsError
                            if (err != null || waited >= 8) {
                                Text(
                                    err ?: "加载超过 8 秒，可能是桥在读大盘/网络慢",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TerminalColors.danger(),
                                    modifier = Modifier.padding(horizontal = 24.dp),
                                )
                                TextButton(onClick = { AppState.refreshSessions() }) { Text("重试") }
                            }
                        }
                    }

                    AppState.sessions.isEmpty() -> Hint(
                        title = "还没有会话",
                        body = "点右下角「新会话」在这台手机上开一个 pi 会话；桥把它记在 ~/.pi/agent/sessions。",
                        actionLabel = "刷新",
                        onAction = { AppState.refreshSessions() },
                    )

                    visible.isEmpty() -> Hint(
                        title = "当前过滤下没有会话",
                        body = "时间窗口或项目过滤把 ${AppState.sessions.size} 个会话都挡掉了。",
                        actionLabel = "清空过滤",
                        onAction = {
                            AppState.updateSessionTime(SessionTime.All)
                            AppState.clearProjectFilter()
                        },
                    )

                    else -> LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        // 底部留出 FAB 的高度，免得它压住最后一张卡
                        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 96.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (AppState.sessionGroup == SessionGroup.Project) {
                            grouped.forEach { (project, sessions) ->
                                item(key = "head-$project") {
                                    GroupHeader(
                                        title = shortenPath(project),
                                        count = sessions.size,
                                        collapsed = AppState.collapsedGroups.contains(project),
                                        onToggle = { AppState.toggleGroup(project) },
                                    )
                                }
                                if (!AppState.collapsedGroups.contains(project)) {
                                    items(sessions, key = { it.file }) { SessionCard(it) }
                                }
                            }
                        } else {
                            timelineSections(visible).forEach { (bucket, sessions) ->
                                item(key = "time-$bucket") {
                                    GroupHeader(title = bucket, count = sessions.size, collapsed = false, onToggle = null)
                                }
                                items(sessions, key = { it.file }) { SessionCard(it) }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showConnection) ConnectionSheet(onDismiss = { showConnection = false })
    if (showMachines) MachinesSheet(onDismiss = { showMachines = false })
    if (showNewSession) NewSessionSheet(onDismiss = { showNewSession = false })
    if (showProjects) ProjectsSheet(onDismiss = { showProjects = false })
    if (showAppearance) AppearanceSheet(onDismiss = { showAppearance = false })
}

/** 过滤/排序控件行：横向可滚，M3 FilterChip。 */
@Composable
private fun FilterRow(
    onToggleGroup: () -> Unit,
    onSortClick: () -> Unit,
    onTimeClick: () -> Unit,
    onProjectClick: () -> Unit,
    sortMenu: Boolean,
    onSortDismiss: () -> Unit,
    timeMenu: Boolean,
    onTimeDismiss: () -> Unit,
    visibleCount: Int,
    totalCount: Int,
) {
    val chipsPadding = PaddingValues(horizontal = 14.dp, vertical = 2.dp)
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(chipsPadding),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(
                selected = AppState.sessionGroup == SessionGroup.Project,
                onClick = onToggleGroup,
                label = { Text(if (AppState.sessionGroup == SessionGroup.Project) "分组·目录" else "分组·时间") },
            )
            Box {
                FilterChip(
                    selected = AppState.sessionSort != SessionSort.Updated,
                    onClick = onSortClick,
                    label = {
                        Text(
                            when (AppState.sessionSort) {
                                SessionSort.Updated -> "排序·更新"
                                SessionSort.Created -> "排序·创建"
                                SessionSort.Name -> "排序·名称"
                            },
                        )
                    },
                )
                DropdownMenu(shape = PopSurface.shape(), containerColor = PopSurface.container(), expanded = sortMenu, onDismissRequest = onSortDismiss) {
                    DropdownMenuItem(text = { Text("最近更新") }, onClick = { AppState.updateSessionSort(SessionSort.Updated); onSortDismiss() })
                    DropdownMenuItem(text = { Text("创建时间") }, onClick = { AppState.updateSessionSort(SessionSort.Created); onSortDismiss() })
                    DropdownMenuItem(text = { Text("名称") }, onClick = { AppState.updateSessionSort(SessionSort.Name); onSortDismiss() })
                }
            }
            Box {
                FilterChip(
                    selected = AppState.sessionTime != SessionTime.All,
                    onClick = onTimeClick,
                    label = {
                        Text(
                            when (AppState.sessionTime) {
                                SessionTime.All -> "时间·全部"
                                SessionTime.Day -> "时间·今天"
                                SessionTime.Week -> "时间·7 天"
                            },
                        )
                    },
                )
                DropdownMenu(shape = PopSurface.shape(), containerColor = PopSurface.container(), expanded = timeMenu, onDismissRequest = onTimeDismiss) {
                    DropdownMenuItem(text = { Text("全部") }, onClick = { AppState.updateSessionTime(SessionTime.All); onTimeDismiss() })
                    DropdownMenuItem(text = { Text("最近 24 小时") }, onClick = { AppState.updateSessionTime(SessionTime.Day); onTimeDismiss() })
                    DropdownMenuItem(text = { Text("最近 7 天") }, onClick = { AppState.updateSessionTime(SessionTime.Week); onTimeDismiss() })
                }
            }
            FilterChip(
                selected = AppState.projectFilter.isNotEmpty(),
                onClick = onProjectClick,
                label = {
                    Text(
                        if (AppState.projectFilter.isEmpty()) "项目·全部"
                        else "项目·${AppState.projectFilter.size}",
                    )
                },
            )
        }
        Text(
            text = if (visibleCount == totalCount) "共 $totalCount 个会话" else "共 $visibleCount / $totalCount 个会话",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 18.dp, bottom = 4.dp),
        )
    }
}

/**
 * 分组头（按目录）：做成一个**tonal pill**——图标 + 项目名 + 完整路径 + 数量徽标 + 旋转箭头，
 * 整块可点折叠/展开（与会话卡片区分开：头部是 surfaceContainerHighest + primary 强调，卡片是低一层容器）。
 */
@Composable
private fun GroupHeader(title: String, count: Int, collapsed: Boolean, onToggle: (() -> Unit)?) {
    val angle by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (collapsed) 0f else 180f,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "group-chevron",
    )
    val name = title.trimEnd('/').substringAfterLast('/').ifBlank { title }
    val showPath = title != name && title != "~"

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = onToggle != null) { onToggle?.invoke() }
                .padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_folder),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (showPath) {
                    Text(
                        title,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                shape = androidx.compose.foundation.shape.CircleShape,
            ) {
                Text(
                    "$count",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
                )
            }
            if (onToggle != null) {
                Icon(
                    painter = painterResource(R.drawable.ic_expand),
                    contentDescription = if (collapsed) "展开" else "收起",
                    modifier = Modifier.size(20.dp).rotate(angle),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 时间轴分桶。 */
private fun timelineSections(sessions: List<SessionInfo>): List<Pair<String, List<SessionInfo>>> {
    val now = java.time.LocalDate.now()
    val buckets = linkedMapOf(
        "今天" to mutableListOf<SessionInfo>(),
        "昨天" to mutableListOf<SessionInfo>(),
        "本周" to mutableListOf<SessionInfo>(),
        "更早" to mutableListOf<SessionInfo>(),
    )
    for (session in sessions) {
        val date = session.updatedMillis?.let { java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).toLocalDate() }
        val bucket = when {
            date == null -> "更早"
            date == now -> "今天"
            date == now.minusDays(1) -> "昨天"
            java.time.temporal.ChronoUnit.DAYS.between(date, now) < 7 -> "本周"
            else -> "更早"
        }
        buckets.getValue(bucket) += session
    }
    return buckets.filterValues { it.isNotEmpty() }.map { it.key to it.value }
}

/** 会话＝内容容器：容器刻度里的 large 圆角 + surfaceContainer* 角色。 */
@Composable
private fun SessionCard(session: SessionInfo) {
    Card(
        onClick = { AppState.openSession(session) },
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val working = AppState.isSessionWorking(session.file)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = session.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                // 正在跑的会话：动效 + 「工作中」（离开会话也不停，所以列表里要能看出来）
                if (working) {
                    LoadingIndicator(modifier = Modifier.size(14.dp), color = TerminalColors.info())
                    Text(
                        "工作中",
                        style = MaterialTheme.typography.labelSmall,
                        color = TerminalColors.info(),
                    )
                }
            }
            val supporting = listOf(
                shortenPath(session.cwd),
                relativeTime(session.updatedAt),
                formatTokens(session.bytes) + "B",
            ).filter { it.isNotBlank() && it != "B" }.joinToString(" · ")
            Text(
                text = supporting,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 空态/错误态：标题用 Emphasized（主角位），说明用标准正文。 */
@Composable
private fun Hint(
    title: String,
    body: String,
    actionLabel: String,
    onAction: () -> Unit,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleLargeEmphasized)
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onAction) { Text(actionLabel) }
        if (secondaryLabel != null && onSecondary != null) {
            TextButton(onClick = onSecondary) { Text(secondaryLabel) }
        }
    }
}

// 文案口径统一在 AppState.connectionText()（对话页顶部状态条用的是同一个）
private fun connectionLabel(): String = AppState.connectionText()
