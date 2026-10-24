@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package id.harness.pipocket.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import id.harness.pipocket.AppState
import id.harness.pipocket.R

/**
 * 命令执行页（“完整执行面”）。
 *
 * 它跑在**当前连接的那台机器**上：桥把命令交给 pi 的官方 RPC `bash`，输出经 SSE 的
 * `bash_execution_update` 流式回来（所以远端机器也能跑，不需要另开 SSH）。
 * 本机（phone）额外还有 app→Termux 的 `RUN_COMMAND` 通道，用来起桥这类「要活下来的进程」。
 */
@Composable
fun ShellScreen() {
    var command by remember { mutableStateOf("") }
    var excludeFromContext by remember { mutableStateOf(true) }
    val outputScroll = rememberScrollState()

    LaunchedEffect(AppState.shellOutput) {
        if (AppState.shellOutput.isNotEmpty()) outputScroll.animateScrollTo(outputScroll.maxValue)
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
                navigationIcon = {
                    IconButton(onClick = { AppState.closeShell() }) {
                        Icon(painterResource(R.drawable.ic_back), contentDescription = "返回")
                    }
                },
                title = {
                    // 标题保持**一行**（用户口径：终端页顶部标题很乱）。
                    // 后端与目标机器合并成一行副标题，长的中间截断；机器名优先用 host。
                    val backend = when (AppState.termBackend) {
                        "termux" -> "Termux"
                        "ssh" -> "SSH"
                        else -> "桥"
                    }
                    val where = AppState.currentHit?.host?.ifBlank { null } ?: AppState.baseUrl
                    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                        Text("终端", style = MaterialTheme.typography.titleMedium, maxLines = 1)
                        Text(
                            text = backend + " · " + where,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                actions = {
                    var menu by remember { mutableStateOf(false) }
                    IconButton(onClick = { menu = true }) {
                        Icon(painterResource(R.drawable.ic_more), contentDescription = "更多")
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("清屏") },
                            onClick = { menu = false; AppState.clearShell() },
                        )
                        DropdownMenuItem(
                            text = { Text("在此目录开 pi 会话") },
                            onClick = { menu = false; AppState.newSessionInTermCwd() },
                        )
                        HorizontalDivider()
                        listOf(
                            "bridge" to "后端：桥（pi bash，远端也行）",
                            "termux" to "后端：直接 Termux（本机）",
                            "ssh" to "后端：直接 SSH",
                        ).forEach { (kind, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = { menu = false; AppState.chooseTermBackend(kind) },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLowest,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) {
                SelectionContainer {
                    Text(
                        text = AppState.shellOutput.ifBlank {
                            "在这里输入命令，回车执行。\n\n" +
                                "• 走 pi 的 RPC bash：输出流式返回，默认勾选「不进上下文」以免污染对话。\n" +
                                "• 远端机器同样可用（桥把命令交给那台机器上的 pi）。\n" +
                                "• 要起「长期活着」的进程（比如桥本身），本机走 app→Termux 的 RUN_COMMAND。"
                        },
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = if (AppState.shellOutput.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(outputScroll)
                            .padding(12.dp),
                    )
                }
            }

            if (AppState.shellHistory.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppState.shellHistory.take(8).forEach { past ->
                        FilterChip(
                            selected = false,
                            onClick = { command = past },
                            label = {
                                Text(
                                    past.replace("\n", " ").take(24),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                        )
                    }
                }
            }

            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars).imePadding()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(
                            selected = excludeFromContext,
                            onClick = { excludeFromContext = !excludeFromContext },
                            label = { Text("不进上下文") },
                        )
                        if (AppState.shellRunning) {
                            LoadingIndicator(modifier = Modifier.size(18.dp))
                            Text("运行中…", style = MaterialTheme.typography.labelSmall)
                        } else if (AppState.shellExit != null) {
                            Text(
                                "exit ${AppState.shellExit}",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (AppState.shellExit == 0) MaterialTheme.colorScheme.onSurfaceVariant
                                else MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    TextField(
                        value = command,
                        onValueChange = { command = it },
                        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 12.dp),
                        placeholder = { Text("ls -la ~/projects") },
                        shape = MaterialTheme.shapes.extraLarge,
                        colors = TextFieldDefaults.colors(
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            disabledIndicatorColor = Color.Transparent,
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        ),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        maxLines = 4,
                        trailingIcon = {
                            if (AppState.shellRunning) {
                                FilledIconButton(
                                    onClick = { AppState.abortShell() },
                                    shapes = IconButtonDefaults.shapes(),
                                    colors = IconButtonDefaults.filledIconButtonColors(
                                        containerColor = MaterialTheme.colorScheme.errorContainer,
                                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                                    ),
                                ) { Icon(painterResource(R.drawable.ic_stop), contentDescription = "中止") }
                            } else {
                                FilledIconButton(
                                    onClick = {
                                        // 统一走 sendTerminalLine：`cd xxx` 会被截获记下目录（供「在此开 pi 会话」用）
                                        if (AppState.termBackend == "bridge") AppState.runShell(command, excludeFromContext)
                                        else AppState.sendTerminalLine(command)
                                        command = ""
                                    },
                                    shapes = IconButtonDefaults.shapes(),
                                ) { Icon(painterResource(R.drawable.ic_return), contentDescription = "运行") }
                            }
                        },
                    )
                }
            }
        }
    }
}

@Suppress("unused")
private val shellPaddingAnchor: PaddingValues = PaddingValues(0.dp)
