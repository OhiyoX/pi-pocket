package id.harness.pipocket.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import id.harness.pipocket.AppState
import id.harness.pipocket.BuildConfig
import id.harness.pipocket.SettingsPage

/** Overview first. Long forms live in one mutually exclusive child sheet. */
@Composable
fun SettingsSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var restart by remember { mutableStateOf(false) }
    var reload by remember { mutableStateOf(false) }
    val session = AppState.current
    SheetScaffold(title = "设置", onDismiss = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            SettingsGroup("连接与模型") {
                SettingsRow("连接设置", "桥地址、连接凭据与 SSH") { AppState.openSettingsPage(SettingsPage.Connection) }
                SettingsRow("Pi Provider", "管理模型来源和访问凭据") { AppState.openProviders() }
            }
            Text(AppState.connectionText() + (AppState.statusLine?.let { " · " + it } ?: ""),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp))
            SettingsGroup("App 与显示") {
                SettingsRow("App 会话注入", "技能、预设 MCP 与附加提示") { AppState.openSettingsPage(SettingsPage.AppProfile) }
                SettingsRow("外观与字号", "主题、全局字号与对话缩放") { AppState.openSettingsPage(SettingsPage.Appearance) }
                SettingsRow("杂项", "工具卡行数、输出高度与终端列数") { AppState.openMisc() }
            }
            SettingsGroup("通知") {
                // 用户 2026-10-24：每轮答完都弹、报错也弹 ⇒ 成开关，默认关。
                SettingsSwitchRow(
                    title = "回答完成提醒",
                    summary = "每轮答完 / 出错都提醒（默认关）；「需要你确认」和 agent 主动发的提醒不受影响",
                    checked = AppState.notifyTurnEnd,
                    onChange = { AppState.updateNotifyTurnEnd(it) },
                )
            }
            SettingsGroup("维护") {
                SettingsRow("组件更新", "下载本机 Termux 组件更新") { AppState.openSettingsPage(SettingsPage.Updates) }
                SettingsRow("重新连接", "重新建立事件连接并刷新状态", navigation = false) { AppState.reconnect() }
                SettingsRow("重启桥", "重新启动当前连接的桥", navigation = false) { restart = true }
            }
            if (session != null) SettingsGroup("当前会话") {
                SettingsRow("重载会话", session.title, navigation = false) { reload = true }
            }
            Text("关于", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 24.dp, top = 24.dp, bottom = 8.dp))
            Text("Pocket Pi " + BuildConfig.VERSION_NAME + " · " + BuildConfig.GIT_HASH,
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 24.dp))
            val installed = remember { runCatching {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
            }.getOrNull() }
            Text("构建 " + localTimeLabel(BuildConfig.BUILD_TIME) + "\n安装 " + localTimeLabel(installed).ifEmpty { "未知" },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
        }
    }
    if (restart || reload) AlertDialog(
        onDismissRequest = { restart = false; reload = false },
        title = { Text(if (restart) "重启当前桥？" else "重载这个会话？") },
        text = { Text(if (restart) "桥上的运行任务可能中断，App 会自动重新连接。" else "将重新加载“${session?.title.orEmpty()}”的配置，正在运行的任务可能中断。") },
        confirmButton = { TextButton(onClick = {
            if (restart) AppState.restartBridge() else session?.let { AppState.reloadSession(it.file) }
            restart = false; reload = false
        }) { Text("继续") } },
        dismissButton = { TextButton(onClick = { restart = false; reload = false }) { Text("取消") } },
    )
}
