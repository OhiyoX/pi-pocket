package id.harness.pipocket.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import id.harness.pipocket.AppState
import id.harness.pipocket.R
import id.harness.pipocket.SftpEntry

/**
 * SFTP 文件管理（用户 2026-10-21：app 内集成）。
 *
 * **容器选择有规范依据**（2026-10-21 查 M3/NN-g 后确认，与 §13 的一般规则不同）：
 * · M3 Bottom sheets 明确「承载**次要/补充**内容与短流程，慎用、别塞长内容」；
 *   NN/g 同口径：长内容 / 页面级流程不要用抽屉。
 * · 文件管理 = 长列表 + 页面级导航 ⇒ **整页**（[Scaffold]，与会话列表同级），
 *   不用高抽屉；层级靠「返回」表达（与 §13「不叠抽屉」一致）。
 *
 * 视觉口径：
 * · 图标一律**真矢量**（folder/file/upload/arrow_up），禁止 `Text("⟳")`／`Text("📁")`／`Text("⋮")`；
 * · 行用 M3 `ListItem`（two-line = headline + 一行 supporting），文件名 16sp / 元信息 11sp；
 * · 面包屑逐级可点、可横滑；点目录进去，点文件拉下来交给「查看交付」渲染。
 *
 * 凭据复用「SSH 启动远端桥」的 [id.harness.pipocket.SshTarget]，不另存一套。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen() {
    val context = LocalContext.current
    var menuFor by remember { mutableStateOf<SftpEntry?>(null) }
    var renaming by remember { mutableStateOf<SftpEntry?>(null) }
    var newDirOpen by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) AppState.filesUpload(context, uri)
    }

    // 目标 = Termux `~/.ssh/config` 里的别名（app 不存任何 SSH 凭据，见 RemoteFs 注释）
    val subtitle = AppState.fsTargetLabel
    var hostPicker by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column(modifier = Modifier.clickable { hostPicker = true }) {
                        Text(
                            "文件",
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                        )
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { AppState.closeFiles() }) {
                        Icon(painterResource(R.drawable.ic_back), contentDescription = "返回")
                    }
                },
                actions = {
                    // 显式的机器选择（之前只能点标题，用户找不到）
                    AssistChip(
                        onClick = { hostPicker = true },
                        label = {
                            Text(
                                AppState.fsAlias,
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        trailingIcon = {
                            Icon(painterResource(R.drawable.ic_expand), null, Modifier.size(18.dp))
                        },
                        shape = RoundedCornerShape(12.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    IconButton(onClick = { AppState.filesRefresh() }) {
                        Icon(painterResource(R.drawable.ic_refresh), contentDescription = "刷新")
                    }
                    IconButton(onClick = { newDirOpen = true }) {
                        Icon(painterResource(R.drawable.ic_folder_new), contentDescription = "新建目录")
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { picker.launch(arrayOf("*/*")) },
                icon = { Icon(painterResource(R.drawable.ic_upload), contentDescription = null) },
                text = { ButtonLabel("上传", maxSp = 14, minSp = 11) },
            )
        },
    ) { pad ->
        Column(modifier = Modifier.fillMaxSize().padding(pad)) {
            Breadcrumb()
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

            if (AppState.filesLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(2.dp))
            }
            if (AppState.sshTargets.isNotEmpty()) AppState.filesError?.let { msg ->
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text(
                        msg,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
            AppState.filesMessage?.let { msg ->
                Text(
                    msg,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                )
            }

            if (AppState.filesEntries.isEmpty() && !AppState.filesLoading) {
                EmptyState()
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 104.dp),
                ) {
                    items(AppState.filesEntries, key = { it.path }) { entry ->
                        FileRow(
                            entry = entry,
                            onOpen = {
                                if (entry.isDir) AppState.filesNavigate(entry.path)
                                else AppState.filesOpenEntry(context, entry)
                            },
                            onMenu = { menuFor = entry },
                            menuOpen = menuFor?.path == entry.path,
                            onDismissMenu = { menuFor = null },
                            onDownload = { menuFor = null; AppState.filesOpenEntry(context, entry) },
                            onRename = { menuFor = null; renaming = entry },
                            onDelete = { menuFor = null; AppState.filesDelete(entry) },
                        )
                    }
                }
            }
        }
    }

    if (hostPicker) {
        QuickSheet(title = "选择连接（Termux 别名 / App 内连接）", onDismiss = { hostPicker = false }) {
            Column(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                if (AppState.fsHosts.isEmpty()) {
                    Text(
                        "还没读到别名。点右上角刷新重试，或确认本机桥连着 Termux。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                }
                // ① Termux 的 ~/.ssh/config（零凭据，桥在 Termux 里跑 ssh）
                Text(
                    "Termux（~/.ssh/config）",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 20.dp, top = 4.dp, bottom = 2.dp),
                )
                AppState.fsHosts.forEach { h ->
                    ChoiceRow(
                        title = h.alias,
                        subtitle = h.label,
                        selected = h.alias == AppState.fsAlias,
                    ) { hostPicker = false; AppState.filesSelectHost(h.alias) }
                }
                // ② App 内自建的连接（自带 key/密码，jsch 直连）
                Text(
                    "App 内连接",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 20.dp, top = 10.dp, bottom = 2.dp),
                )
                AppState.sshTargets.forEach { t ->
                    ChoiceRow(
                        title = t.key,
                        subtitle = if (t.authKind == "key") "私钥（PEM）" else "密码",
                        selected = AppState.fsAlias == "ssh:" + t.key,
                    ) { hostPicker = false; AppState.filesSelectHost("ssh:" + t.key) }
                }
                ListItem(
                    headlineContent = { Text("管理 App 内连接 / 新增…", style = MaterialTheme.typography.bodySmall) },
                    leadingContent = { Icon(painterResource(R.drawable.ic_add), null, Modifier.size(20.dp)) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable {
                        hostPicker = false
                        AppState.filesWantBack = true
                        AppState.openSsh()
                    },
                )
            }
        }
    }

    renaming?.let { entry ->
        var name by remember(entry.path) { mutableStateOf(entry.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("重命名") },
            text = {
                Column {
                    Text(
                        "原名：" + entry.name,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("新名字") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val n = name
                    renaming = null
                    AppState.filesRename(entry, n)
                }) { ButtonLabel("确定") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { ButtonLabel("取消") } },
        )
    }

    if (newDirOpen) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { newDirOpen = false },
            title = { Text("新建目录") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("目录名") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val n = name
                    newDirOpen = false
                    AppState.filesMkdir(n)
                }) { ButtonLabel("创建") }
            },
            dismissButton = { TextButton(onClick = { newDirOpen = false }) { ButtonLabel("取消") } },
        )
    }
}

/** 面包屑：`↑` + `/` + 每级可点（当前级高亮，primary 加粗）；路径长就横滑。 */
@Composable
private fun ChoiceRow(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(subtitle, style = MaterialTheme.typography.bodySmall) },
        trailingContent = {
            if (selected) Icon(painterResource(R.drawable.ic_expand), null, Modifier.size(18.dp))
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun Breadcrumb() {
    val parts = AppState.filesPath.split('/').filter { it.isNotEmpty() }
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 12.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { AppState.filesUp() }) {
            Icon(painterResource(R.drawable.ic_arrow_up), contentDescription = "上一级")
        }
        Row(
            modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 只显示最后 3 级（长了就前面省略），当前级高亮 —— 比把整条 /data/data/... 铺满更可读
            val shown = if (parts.size <= 3) parts else parts.takeLast(3)
            val prefix = parts.dropLast(shown.size)
            if (prefix.isNotEmpty()) {
                Text(
                    "…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            } else {
                Crumb("/", parts.isEmpty()) { AppState.filesNavigate("/") }
            }
            var acc = if (prefix.isEmpty()) "" else "/" + prefix.joinToString("/")
            shown.forEachIndexed { i, seg ->
                acc += "/" + seg
                val target = acc
                Crumb(seg, i == shown.lastIndex) { AppState.filesNavigate(target) }
            }
        }
    }
}

@Composable
private fun Crumb(text: String, current: Boolean, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
            color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .clickable(onClick = onClick)
                .padding(horizontal = 6.dp, vertical = 8.dp),
        )
        if (!current) {
            Icon(
                painterResource(R.drawable.ic_chevron_right),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

/**
 * 一行 = M3 two-line `ListItem`（headline=文件名，supporting=类型/大小），
 * leading = 圆角容器里的矢量图标（目录用 primaryContainer 表达"可进入"），trailing = ⋮ 菜单。
 */
@Composable
private fun FileRow(
    entry: SftpEntry,
    onOpen: () -> Unit,
    onMenu: () -> Unit,
    menuOpen: Boolean,
    onDismissMenu: () -> Unit,
    onDownload: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    Box {
        ListItem(
            headlineContent = {
                Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            // 目录不再显示「目录」二字（冗余）⇒ 变成 one-line 行，行高按 M3 收紧到 56dp；
            // 文件保留一行元信息（大小），是 two-line 行。依据见 docs/design/RESEARCH-list-density.md。
            supportingContent = if (entry.isDir) null else {
                {
                    Text(
                        humanSize(entry.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            leadingContent = {
                // 不套色块：整屏十几行同款方块的噪音太大，直接给 24dp 图标，
                // 目录用 primary 着色表达"可进入"（M3 允许 leading icon 带语义色）。
                Icon(
                    painterResource(if (entry.isDir) R.drawable.ic_folder else R.drawable.ic_file),
                    contentDescription = null,
                    tint = if (entry.isDir) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                )
            },
            trailingContent = {
                // 40dp 点击区（M3 最小触摸目标），比默认 48dp 更省竖向空间
                IconButton(onClick = onMenu, modifier = Modifier.size(40.dp)) {
                    Icon(
                        painterResource(R.drawable.ic_more),
                        contentDescription = "更多",
                        modifier = Modifier.size(20.dp),
                    )
                }
            },
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth().height(if (entry.isDir) 56.dp else 68.dp).clickable(onClick = onOpen),
        )
        DropdownMenu(expanded = menuOpen, onDismissRequest = onDismissMenu) {
            if (!entry.isDir) {
                DropdownMenuItem(
                    text = { Text("下载 / 查看", style = MaterialTheme.typography.bodySmall) },
                    leadingIcon = {
                        Icon(painterResource(R.drawable.ic_download), null, Modifier.size(18.dp))
                    },
                    onClick = onDownload,
                )
            }
            DropdownMenuItem(
                text = { Text("重命名", style = MaterialTheme.typography.bodySmall) },
                leadingIcon = {
                    Icon(painterResource(R.drawable.ic_rename), null, Modifier.size(18.dp))
                },
                onClick = onRename,
            )
            DropdownMenuItem(
                text = {
                    Text(
                        "删除",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                },
                leadingIcon = {
                    Icon(
                        painterResource(R.drawable.ic_delete), null, Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.error,
                    )
                },
                onClick = onDelete,
            )
        }
    }
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(20.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(if (AppState.fsHosts.isEmpty()) R.drawable.ic_machine else R.drawable.ic_folder),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            if (AppState.fsHosts.isEmpty()) "还没连上 Termux"
            else if (AppState.filesError != null) "连不上，或这个目录读不了" else "这个目录是空的",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            if (AppState.fsHosts.isEmpty()) "文件页走 Termux 的 ~/.ssh/config；先确认本机桥已连上"
            else "点下面重试，或点标题换一个 ssh 别名",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        FilledTonalButton(
            onClick = {
                if (AppState.fsHosts.isEmpty()) AppState.loadFsHosts() else AppState.filesRefresh()
            },
            shape = RoundedCornerShape(14.dp),
        ) {
            ButtonLabel(if (AppState.fsHosts.isEmpty()) "重新读取别名" else "重试", maxSp = 14, minSp = 11)
        }
    }
}

/** 人类可读大小（1024 进制，两位小数；仅显示用）。 */
private fun humanSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format("%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format("%.1f MB", mb)
    return String.format("%.2f GB", mb / 1024.0)
}
