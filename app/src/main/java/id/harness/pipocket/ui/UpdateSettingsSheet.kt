package id.harness.pipocket.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import id.harness.pipocket.AppState

@Composable
fun UpdateSettingsSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var token by remember { mutableStateOf(AppState.giteaToken) }
    var host by remember { mutableStateOf(AppState.giteaHost) }
    var repo by remember { mutableStateOf(AppState.giteaRepo) }
    SheetScaffold(title = "组件更新", onDismiss = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("更新本机 Termux 组件", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 20.dp))
            Text(
                "下载桥、启动器和会话配置，不改变运行中的桥。App 内安装暂不可用，需通过受控部署流程安装。",
                style = MaterialTheme.typography.bodySmall,
                color = TerminalColors.muted(),
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            OutlinedTextField(
                value = host,
                onValueChange = { host = it },
                label = { Text("Gitea 主机") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            )
            OutlinedTextField(
                value = repo,
                onValueChange = { repo = it },
                label = { Text("仓库（owner/repo）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            )
            SecretField(
                value = token,
                onValueChange = { token = it },
                label = "访问 token（私有仓必填）",
                // 和上面两个输入框同一条左边线：之前漏了 horizontal = 20.dp，token 那一行是通到底的（用户指的对不齐）
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            )
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        AppState.updateGitea(host, repo)
                        AppState.updateGiteaToken(token)
                        AppState.fetchUpdate(context)
                    },
                    enabled = !AppState.updateBusy,
                    modifier = Modifier.fillMaxWidth(),
                ) { ButtonLabel("保存并下载更新") }
            }
            AppState.updateMessage?.let {
                SelectionContainer {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = TerminalColors.muted(),
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                }
            }

        }
    }
}
