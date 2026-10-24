@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package id.harness.pipocket.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import id.harness.pipocket.AppState
import id.harness.pipocket.SshTarget

/**
 * SSH 拉起远端桥：桥没起的时候不用回电脑敲命令，app 自己 SSH 过去起。
 *
 * 认证：密码 或 私钥（PEM 粘贴）。主机指纹首次连接记住、变化则拒绝（JSch accept-new）。
 */
@Composable
fun SshSection() {
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("22") }
    var user by remember { mutableStateOf("") }
    var authKind by remember { mutableStateOf("password") }
    var password by remember { mutableStateOf("") }
    var privateKey by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
        Text("用 SSH 启动远端桥", style = MaterialTheme.typography.titleSmall)
        Text(
            "桥没起、或机器重启后，这里可以直接 SSH 过去把它拉起来（默认命令：没在 8789 监听就 nohup 起一个，然后 curl 健康检查）。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = host,
                onValueChange = { host = it },
                label = { Text("主机") },
                singleLine = true,
                modifier = Modifier.weight(1.6f),
            )
            OutlinedTextField(
                value = port,
                onValueChange = { port = it.filter { c -> c.isDigit() } },
                label = { Text("端口") },
                singleLine = true,
                modifier = Modifier.weight(0.7f),
            )
        }
        OutlinedTextField(
            value = user,
            onValueChange = { user = it },
            label = { Text("用户") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            listOf("password" to "密码", "key" to "私钥").forEachIndexed { index, (kind, label) ->
                SegmentedButton(
                    selected = authKind == kind,
                    onClick = { authKind = kind },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = 2),
                    label = { Text(label) },
                )
            }
        }

        if (authKind == "password") {
            // 默认掩码（用户 2026-10-21：敏感项默认 hide，需要核对时点「显示」）
            SecretField(
                value = password,
                onValueChange = { password = it },
                label = "密码",
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            OutlinedTextField(
                value = privateKey,
                onValueChange = { privateKey = it },
                label = { Text("私钥（PEM 全文）") },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 4,
            )
            SecretField(
                value = passphrase,
                onValueChange = { passphrase = it },
                label = "私钥口令（可选）",
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = {
                    val target = SshTarget(
                        host = host.trim(),
                        port = port.toIntOrNull() ?: 22,
                        user = user.trim(),
                        authKind = authKind,
                        password = password,
                        privateKey = privateKey,
                        passphrase = passphrase,
                    )
                    AppState.saveSshTarget(target)
                    AppState.launchRemoteBridge(target, thenConnectUrl = "http://${target.host}:8789")
                },
                enabled = !AppState.sshBusy && host.isNotBlank() && user.isNotBlank(),
            ) {
                if (AppState.sshBusy) ButtonLabel("执行中…") else ButtonLabel("保存并启动桥")
            }
            AppState.sshMessage?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // 已保存的 SSH 目标：一键再拉一次
        AppState.sshTargets.forEach { target ->
            Row(
                modifier = Modifier.fillMaxWidth().clickable {
                    AppState.launchRemoteBridge(target, thenConnectUrl = "http://${target.host}:8789")
                },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(target.key, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    AppState.launchRemoteBridge(target, thenConnectUrl = "http://${target.host}:8789")
                }) { Text("启动桥") }
                TextButton(onClick = { AppState.removeSshTarget(target.key) }) { Text("移除") }
            }
        }
    }
}

/**
 * 「SSH 管理」独立抽屉（用户 2026-10-21：藏在连接设置最底下，根本找不到）。
 *
 * 只装 [SshSection]（App 内自建连接：密码 / PEM / OpenSSH 新格式私钥，sshj 解析）。
 * Termux 的 `~/.ssh/config` 别名不在这里 —— 那些由桥自动带出来，见文件页的机器选择。
 */
@Composable
fun SshSheet(onDismiss: () -> Unit) {
    SheetScaffold(title = "SSH 管理", onDismiss = onDismiss) {
        androidx.compose.foundation.lazy.LazyColumn(modifier = Modifier.fillMaxWidth()) {
            item { SshSection() }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}
