@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package id.harness.pipocket.ui

import androidx.compose.foundation.clickable
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Checkbox
import androidx.compose.material3.IconButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import id.harness.pipocket.AppState
import id.harness.pipocket.BridgeHit
import id.harness.pipocket.BridgeReach
import id.harness.pipocket.MachineMerge
import id.harness.pipocket.R

/**
 * 机器选择面板（M3 卡片化）。
 *
 * 可读性口径（2026-10-18 用户反馈「可读性很差」后定的）：
 * - 一台机器一张卡：**前置图标 + 标题（titleMedium）+ 地址（bodyMedium）**；
 * - 状态只留 **2 个徽标**（范围 / 在线）+ 一句文字说明 token，不再把小徽标挤成一排；
 * - 「忘记」放右上角的文字按钮，不跟状态混在一起；
 * - 区块标题用 titleSmall，说明用 bodySmall，字号不再低于 12sp。
 */
@Composable
fun MachinesSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val current = AppState.baseUrl
    SheetScaffold(title = "选择机器", onDismiss = onDismiss) {
        // 打开面板就把「记住的机器」探一遗：以前 online 只在启动/扫描时刷，没扫过就一直「未应答」
        LaunchedEffect(Unit) { AppState.refreshKnownMachines() }
        // 内容可能很长（扫描结果 + 记住的机器 + 配置里的机器）：必须能滚，否则下面划不出来
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 640.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            // 当前连接
            val currentKnown = AppState.knownMachines.firstOrNull { it.url == current }
            MachineCard(
                title = currentKnown?.host?.ifBlank { null } ?: "当前连接",
                url = current,
                scope = currentKnown?.scope ?: "Local",
                authRequired = currentKnown?.authRequired ?: AppState.token.isNotBlank(),
                online = true,
                isCurrent = true,
                onClick = null,
                onForget = null,
                onRemember = if (AppState.isSaved(current)) null else ({ AppState.saveCurrentMachine(currentKnown?.host) }),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )

            // 扫描动作：单列（按钮一行；进度条与文字另起一行），不再按钮+文字并排
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilledTonalButton(
                    onClick = { AppState.scanAll(context, adoptFirst = false) },
                    enabled = !AppState.scanning,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    ButtonLabel(if (AppState.scanning) "扫描中…" else "扫描本机 + 局域网")
                }
                // 勾选后：扫描时**顺带读 Termux 的 SSH 配置**，SSH 机器一起扫进来
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Checkbox(
                        checked = AppState.scanIncludeSshConfig,
                        onCheckedChange = { AppState.toggleScanIncludeSshConfig() },
                    )
                    Text(
                        "同时读本机 SSH 配置（Termux）",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // 后台提醒：靠一个前台服务守住 SSE（远程机器的提醒必须这样才送得到）
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Checkbox(
                        checked = AppState.bgNotify,
                        onCheckedChange = { AppState.updateBgNotify(it) },
                    )
                    Text(
                        "后台提醒（保持连接；远端机器的「等确认 / 跑完」靠它送到）",
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // 名称说清楚"读谁的配置"：左=手机本机（Termux），右=当前桥所在的那台机器。
                // 用户口径：**从 Termux 读本机配置里的机器**（ssh config / pi-pocket-hosts.json），
                // 即使桥没在跑也能扫（走 RUN_COMMAND + 一次性本地服务）。也走桥的同一份接口做对照。
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { AppState.scanConfigViaTermux(context) },
                        enabled = !AppState.scanning,
                        modifier = Modifier.weight(1f),
                    ) { ButtonLabel("读本机配置") }
                    OutlinedButton(
                        onClick = { AppState.scanConfigMachines() },
                        enabled = !AppState.scanning,
                        modifier = Modifier.weight(1f),
                    ) { ButtonLabel("读桥机配置") }
                }

                Text(
                    "「读本机配置」= 手机 Termux 上的 ~/.ssh/config；「读桥机配置」= 当前桥所在机器的同一份文件。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // 用户口径：**本地 Termux 的桥，不管有没有扫到 / 有没有记住，都能强制拉起**
                // （RUN_COMMAND 直接让 Termux 跑 pi-pocket-start；不依赖扫描结果与"记住"）
                OutlinedButton(
                    onClick = { AppState.launchLocalBridge(context) },
                    enabled = !AppState.scanning,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    ButtonLabel("强制拉起本机 pi（Termux）")
                }
                AppState.launchMessage?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // ── 从零部署：空 Termux → 装好 pi + 桥（HANDOFF §15）──────────────────
                // 重度动作（会 pkg install、全局 npm 装 pi）：必须先确认，再把工作交给 Termux。
                var deployConfirm by remember { mutableStateOf(false) }
                val runPermissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) { granted ->
                    if (granted) AppState.deployLocal(context)
                    else AppState.updateLaunchMessage(
                        "没拿到 com.termux.permission.RUN_COMMAND（要在系统设置里给），app 启不了 Termux 里的部署",
                    )
                }
                OutlinedButton(
                    onClick = { deployConfirm = true },
                    enabled = !AppState.bootstrapRunning && !AppState.scanning,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    ButtonLabel(
                        if (AppState.bootstrapRunning) "正在部署… 已等 " + AppState.bootstrapElapsed + "s"
                        else "从零部署（本机 Termux）",
                    )
                }
                Text(
                    "空白 Termux 也能跑：装 nodejs/git → 全局装 pi → clone 仓库 → 装桥 → 生成 token → 起桥并自检。" +
                        "幂等，重跑只是更新。需要设置里的 Gitea token。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (AppState.bootstrapRunning) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                AppState.bootstrapMessage?.let { message ->
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (AppState.bootstrapOk) MaterialTheme.colorScheme.primary
                        else if (message.startsWith("部署完成")) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
                    )
                }
                // 日志尾部（最多 20 行）：失败时就在这里看 bootstrap 卡在哪
                AppState.bootstrapLog?.takeIf { it.isNotBlank() }?.let { tail ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        SelectionContainer {
                            Text(
                                tail,
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(10.dp).heightIn(max = 260.dp).verticalScroll(rememberScrollState()),
                            )
                        }
                    }
                }
                if (AppState.bootstrapOk) {
                    FilledTonalButton(
                        onClick = { AppState.connectLocalBridge(); onDismiss() },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("把当前机器切到 127.0.0.1:8789") }
                }
                if (!AppState.bootstrapRunning && (AppState.bootstrapMessage != null || AppState.bootstrapLog != null)) {
                    TextButton(onClick = { AppState.clearBootstrap() }) { ButtonLabel("清掉部署提示") }
                }

                if (deployConfirm) {
                    AlertDialog(
                        onDismissRequest = { deployConfirm = false },
                        title = { Text("从零部署到本机 Termux？") },
                        text = {
                            Text(
                                """
会在 Termux 里装 nodejs/git、全局 npm 安装 pi、clone 仓库、装桥并起桥（幂等，已装的跳过）。
可能要几分钟，期间最好别退出 Termux。
需要设置里的 Gitea token（私有仓）。
""".trim(),
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                deployConfirm = false
                                if (AppState.termuxRunPermissionGranted(context)) AppState.deployLocal(context)
                                else runPermissionLauncher.launch("com.termux.permission.RUN_COMMAND")
                            }) { Text("开始部署") }
                        },
                        dismissButton = { TextButton(onClick = { deployConfirm = false }) { Text("取消") } },
                    )
                }

            SectionTitle("记住的机器")
            if (AppState.knownMachines.none { it.url != current }) {
                Text(
                    "还没有记住的机器：**连上会自动记住**，也可以在卡片上点卡上的「记住」手动存（手动加地址在「连接设置」）。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
            } else {
                // 能直连的排前面；同一台机器（桥报同一个 host）的 LAN/Tailscale 多条入口合成一行
                val knownRows = MachineMerge.merge(
                    AppState.knownMachines.filterNot { it.url == current },
                    identity = { AppState.machineHostById[it.url] ?: it.url },
                    rankKey = { reachRankOf(AppState.knownReach[it.url]) * 10 + MachineMerge.netRank(it.url) },
                )
                LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                    items(knownRows, key = { it.primary.url }) { row ->
                        val machine = row.primary
                        MachineCard(
                            title = machine.host.ifBlank { machine.url },
                            url = machine.url,
                            scope = machine.scope,
                            authRequired = machine.authRequired,
                            online = machine.online,
                            reach = AppState.knownReach[machine.url],
                            otherPaths = row.others.map { it.url },
                            isCurrent = machine.url == current,
                            onClick = { AppState.connectToUrl(machine.url); onDismiss() },
                            onForget = { AppState.forgetMachine(machine.url) },
                            onLaunch = { AppState.launchBridgeFor(context, machine.host, machine.url) },
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 5.dp),
                        )
                    }
                }
            }

            if (AppState.hits.isNotEmpty()) {
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
                SectionTitle("本次扫描到（${AppState.hits.size}）")
                LazyColumn(modifier = Modifier.heightIn(max = 220.dp)) {
                    items(AppState.hits, key = { it.url }) { hit: BridgeHit ->
                        MachineCard(
                            title = hit.host.ifBlank { hit.url },
                            url = hit.url,
                            scope = hit.scope.name,
                            authRequired = hit.authRequired,
                            online = true,
                            isCurrent = hit.url == current,
                            onClick = { AppState.connectTo(hit); onDismiss() },
                            onForget = null,
                            onRemember = if (AppState.isSaved(hit.url)) null else ({ AppState.saveMachine(hit) }),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 5.dp),
                        )
                    }
                }
            }

            if (AppState.savedHosts.isNotEmpty()) {
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
                SectionTitle("手加的主机")
                AppState.savedHosts.forEach { host ->
                    MachineCard(
                        title = host,
                        url = host,
                        scope = "Lan",
                        authRequired = AppState.token.isNotBlank(),
                        online = false,
                        reach = AppState.savedHostReach[host],
                        isCurrent = host == current,
                        onClick = { AppState.connectToUrl(host); onDismiss() },
                        onForget = null,
                        onLaunch = { AppState.launchBridgeFor(context, host, null) },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 5.dp),
                    )
                }
            }
            if (AppState.configMachines.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 10.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "配置里的机器（${AppState.configMachines.size}）",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = { AppState.refreshConfigMachinesReach() },
                        enabled = !AppState.scanning,
                    ) { Text("再探一次") }
                }
                Text(
                    "名字 = SSH 配置里的 Host，徽标 = 桥现在能不能直接连（可直连 / 需口令 / 桥未起）；右侧 ⋮ 里有连接、拉起 pi、装/更新桥。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
                )
                // 能直连的排前面（用户口径：面板上要一眼看出哪台能连）；
                // 同一台机器（桥报同一个 host）的 LAN/Tailscale 多条入口合成一行。
                val ordered = MachineMerge.merge(
                    AppState.configMachines,
                    identity = { AppState.machineHostById[it.url] ?: it.url },
                    rankKey = { reachRankOf(AppState.configBridgeReach[it.url]) * 10 + MachineMerge.netRank(it.url) },
                )
                ordered.forEach { row ->
                    val hit = row.primary
                    val ssh = AppState.sshTargetFor(hit.host)
                    val sshOnline = AppState.configSshOnline[hit.host]
                    val sshPort = AppState.configSshPort[hit.host] ?: 22
                    var menu by remember { mutableStateOf(false) }
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        shape = MaterialTheme.shapes.large,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            shape = CircleShape,
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_machine),
                                contentDescription = null,
                                modifier = Modifier.padding(10.dp).size(20.dp),
                            )
                        }
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(hit.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                hit.url,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Badge(text = "ssh:$sshPort")
                                when (sshOnline) {
                                    true -> Badge(text = "SSH 在线", tone = "ok")
                                    false -> Badge(text = "SSH 未响应", tone = "warn")
                                    else -> Badge(text = "探测中…")
                                }
                                ReachBadge(AppState.configBridgeReach[hit.url])
                            }
                            if (row.others.isNotEmpty()) {
                                Text(
                                    "另 " + row.others.size + " 条路径：" + row.others.joinToString("、") { it.url },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        // 三个动作收进溢出菜单：之前平铺成三个文字按钮，把标题/地址/徽标挤到
                        // 换行成竖条（2026-10-24 用户截图指出）
                        Box {
                            IconButton(onClick = { menu = true }, modifier = Modifier.size(36.dp)) {
                                Icon(painterResource(R.drawable.ic_more), contentDescription = "更多", modifier = Modifier.size(18.dp))
                            }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(
                                    text = { Text("连接") },
                                    onClick = { menu = false; AppState.connectToUrl(hit.url); onDismiss() },
                                )
                                DropdownMenuItem(
                                    text = { Text(if (AppState.sshBusy) "拉起中…" else "拉起 pi") },
                                    enabled = !AppState.sshBusy && sshOnline != false,
                                    onClick = { menu = false; AppState.launchBridgeForConfigHit(context, hit) },
                                )
                                if (ssh != null) {
                                    // 安装/更新桥：SSH 到那台机器 git pull → ./termux/install.sh → 重启桥
                                    DropdownMenuItem(
                                        text = { Text(if (AppState.sshBusy) "处理中…" else "装/更新桥") },
                                        enabled = !AppState.sshBusy && sshOnline != false,
                                        onClick = { menu = false; AppState.installBridgeOn(ssh) },
                                    )
                                }
                            }
                        }
                    }
                    }
                }
                AppState.sshMessage?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.size(24.dp))
        }
    }
}
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(start = 20.dp, top = 10.dp, bottom = 6.dp),
    )
}

@Composable
private fun MachineCard(
    title: String,
    url: String,
    scope: String,
    authRequired: Boolean,
    online: Boolean,
    /** 实时可达性（记住的 / 配置里的机器会探）；null 时退回 online 的「在线 / 未应答」。 */
    reach: BridgeReach? = null,
    /** 同一台机器的其它入口（LAN / Tailscale）；非空时在卡片里告知，不隐藏。 */
    otherPaths: List<String> = emptyList(),
    isCurrent: Boolean,
    onClick: (() -> Unit)?,
    onForget: (() -> Unit)?,
    onRemember: (() -> Unit)? = null,
    /** 未应答时给「拉起 pi」用；为 null 就不显示这个入口。 */
    onLaunch: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    // MD3 口径：一张卡 = **leading 图标 + 标题/地址/状态 两行内容 + 尾部溢出菜单**。
    // 之前把「记住/忘记/拉起」平铺在卡片里，标题被挤断、卡片高度也不齐（用户截图指出）。
    var menu by remember { mutableStateOf(false) }
    val hasMenu = onRemember != null || onForget != null || (onLaunch != null && !online)

    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = if (isCurrent) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = if (isCurrent) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurface,
        ),
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = if (hasMenu) 4.dp else 16.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                color = if (isCurrent) MaterialTheme.colorScheme.surface
                else MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = if (isCurrent) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
                shape = CircleShape,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_machine),
                    contentDescription = null,
                    modifier = Modifier.padding(10.dp).size(20.dp),
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    url,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isCurrent) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Badge(text = scopeLabelOf(scope))
                    if (reach != null) {
                        ReachBadge(reach)
                    } else {
                        Badge(text = if (online) "在线" else "未应答", tone = if (online) "ok" else "warn")
                    }
                    // 2026-10-20 修正：桥一律要求 token（本机 loopback 也不例外，token 在 ~/.pi/agent/pi-pocket.env）。
                    // 所以只在桥**确实没开鉴权**时（/v1/health 的 auth=false，仅限手工无 token 启动的备份桥）才说免 token。
                    if (!authRequired) {
                        Text("免 token（桥未开鉴权）", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                    }
                }
                if (otherPaths.isNotEmpty()) {
                    Text(
                        "另 " + otherPaths.size + " 条路径：" + otherPaths.joinToString("、"),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isCurrent) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (hasMenu) {
                Box {
                    IconButton(onClick = { menu = true }, modifier = Modifier.size(36.dp)) {
                        Icon(painterResource(R.drawable.ic_more), contentDescription = "更多", modifier = Modifier.size(18.dp))
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        if (onLaunch != null && !online) {
                            DropdownMenuItem(
                                text = { Text(if (AppState.sshBusy) "拉起中…" else "拉起 pi") },
                                enabled = !AppState.sshBusy,
                                onClick = { menu = false; onLaunch() },
                            )
                        }
                        if (onClick != null) {
                            DropdownMenuItem(text = { Text("连接") }, onClick = { menu = false; onClick() })
                        }
                        if (onRemember != null) {
                            DropdownMenuItem(text = { Text("记住这台") }, onClick = { menu = false; onRemember() })
                        }
                        if (onForget != null) {
                            DropdownMenuItem(text = { Text("忘记") }, onClick = { menu = false; onForget() })
                        }
                    }
                }
            }
        }
    }
}

/** 状态小徽标（范围 / 在线）。 */
@Composable
private fun ReachBadge(reach: BridgeReach?) {
    when (reach) {
        BridgeReach.Ok -> Badge(text = "可直连", tone = "ok")
        BridgeReach.NeedsToken -> Badge(text = "需口令", tone = "ok")
        BridgeReach.Down -> Badge(text = "桥未起", tone = "warn")
        null -> Badge(text = "探桥中…")
    }
}

/** 排序权重：可直连（含需口令）在前，未探到的居中，「桥未起」排后。 */
private fun reachRankOf(reach: BridgeReach?): Int = reach?.rank ?: 2

@Composable
private fun Badge(text: String, tone: String = "plain") {
    val container = when (tone) {
        "ok" -> MaterialTheme.colorScheme.secondaryContainer
        "warn" -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val content = when (tone) {
        "ok" -> MaterialTheme.colorScheme.onSecondaryContainer
        "warn" -> MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(color = container, contentColor = content, shape = MaterialTheme.shapes.small) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            // 不换行：卡片变窄时徽标会被压成一个字一行的竖条，看着像排版坏了
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
        )
    }
}

private fun scopeLabelOf(scope: String): String = when (scope) {
    "Local" -> "本机"
    "Lan" -> "局域网"
    else -> "远端"
}
