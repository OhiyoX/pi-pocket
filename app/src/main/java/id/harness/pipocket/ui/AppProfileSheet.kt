package id.harness.pipocket.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import id.harness.pipocket.AppState

@Composable
fun AppProfileSheet(onDismiss: () -> Unit) {
    val profile = AppState.appProfile
    val busy = AppState.appProfileLoading || AppState.appProfileSaving
    var reset by remember { mutableStateOf(false) }
    SheetScaffold(title = "App 会话注入", onDismiss = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Text("选择 App 会话使用的技能、预设 MCP 和附加提示。终端里的 pi 不受影响。保存后，桥会重新加载当前会话进程。",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            if (busy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp))
                Text(if (AppState.appProfileSaving) "正在保存…" else "正在读取…",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 24.dp))
            }
            AppState.appProfileError?.let { error ->
                Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("未能完成请求", style = MaterialTheme.typography.titleSmall)
                        Text(error, style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = AppState::loadAppProfile, enabled = !busy) { Text("重新读取") }
                    }
                }
            }
            if (profile == null) {
                if (!busy && AppState.appProfileError == null) TextButton(
                    onClick = AppState::loadAppProfile, modifier = Modifier.padding(horizontal = 16.dp),
                ) { Text("读取注入设置") }
                return@Column
            }
            if (!profile.bridgeEnabled) Text("当前桥已关闭 App 注入。下面的选择暂不生效。",
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            val enabled = profile.bridgeEnabled && !busy
            SettingsGroup("附加提示") {
                ProfileToggle("App 附加系统提示",
                    if (profile.appendPromptPresent) "告诉模型 App 的作图通道与渲染方式" else "桥上未提供附加提示文件",
                    profile.appendPrompt, enabled && profile.appendPromptPresent,
                ) { AppState.setAppProfile("appendPrompt", null, it) }
            }
            SettingsGroup("技能 · ${profile.skills.count { it.enabled }}/${profile.skills.size}") {
                if (profile.skills.isEmpty()) EmptyProfileRow("暂无可用技能")
                profile.skills.forEach { item ->
                    ProfileToggle(item.name, item.description, item.enabled, enabled) { AppState.setAppProfile("skills", item.id, it) }
                }
            }
            SettingsGroup("预设 MCP · ${profile.mcp.count { it.enabled }}/${profile.mcp.size}") {
                if (profile.mcp.isEmpty()) EmptyProfileRow("暂无预设 MCP")
                profile.mcp.forEach { item ->
                    ProfileToggle(item.name, item.description, item.enabled, enabled) { AppState.setAppProfile("mcp", item.id, it) }
                }
            }
            TextButton(onClick = { reset = true }, enabled = enabled,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) { Text("恢复默认设置") }
        }
    }
    if (reset) AlertDialog(
        onDismissRequest = { reset = false },
        title = { Text("恢复默认注入？") },
        text = { Text("将重新开启全部技能、预设 MCP 和附加提示，并重新加载当前会话进程。") },
        confirmButton = { TextButton(onClick = { reset = false; AppState.resetAppProfile() }) { Text("恢复默认") } },
        dismissButton = { TextButton(onClick = { reset = false }) { Text("取消") } },
    )
}

@Composable
private fun EmptyProfileRow(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(16.dp))
}

/** One labeled switch target: label, supporting text and switch share one action. */
@Composable
private fun ProfileToggle(title: String, subtitle: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title, style = MaterialTheme.typography.bodyLarge) },
        supportingContent = if (subtitle.isNotBlank()) ({ Text(subtitle, style = MaterialTheme.typography.bodySmall) }) else null,
        trailingContent = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
    )
}
