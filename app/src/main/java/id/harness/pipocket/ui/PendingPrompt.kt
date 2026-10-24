@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package id.harness.pipocket.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import id.harness.pipocket.AppState
import id.harness.pipocket.R
import id.harness.pipocket.UiPrompt

/**
 * 这条请求是不是当前会话发出来的（null 归属 = 旧数据，当“当前会话”处理）。
 */
private fun promptBelongsToCurrentSession(prompt: UiPrompt): Boolean =
    prompt.file == null || prompt.file == AppState.current?.file

/** 归属会话的可读名字（标题 → 文件名 → 短 id）。 */
private fun promptOwnerLabel(prompt: UiPrompt): String {
    val file = prompt.file ?: return "当前会话"
    val hit = AppState.sessions.firstOrNull { it.file == file }
    return hit?.name?.takeIf { it.isNotBlank() }
        ?: file.substringAfterLast('/').removeSuffix(".jsonl").take(24)
}

/**
 * pi 扩展的交互请求：**M3 卡片**形态，就地加载在会话下方（输入区上方），不用模态弹窗。
 *
 * 组件选型按 Material 3：`Card`（surfaceContainerHigh）+ `ListItem` 选项 + `FilledTonalButton` 动作 +
 * `OutlinedTextField` 输入 + `IconButton` 关闭。协议见 pi `docs/rpc-extension-ui.md`。
 */
@Composable
fun PendingPromptSection() {
    val prompt = AppState.uiPrompts.firstOrNull() ?: return
    val more = (AppState.uiPrompts.size - 1).coerceAtLeast(0)
    var text by remember(prompt.id) { mutableStateOf(prompt.prefill) }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        // 同心圆角：容器 20dp（shapes.large），内边距 12dp ⇒ 内部元素用 8dp（20-12）才不别扭
        Column(modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        prompt.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (prompt.message.isNotBlank()) {
                        Text(
                            prompt.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // 请求来自**别的会话**时要说清：app 只有一个交互区，但应答会回给它自己的 worker
                    if (!promptBelongsToCurrentSession(prompt)) {
                        Text(
                            "来自另一个会话：" + promptOwnerLabel(prompt) + "（应答会回给那个会话）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
                IconButton(onClick = { AppState.cancelPrompt(prompt) }) {
                    Icon(painterResource(R.drawable.ic_close), contentDescription = "取消")
                }
            }

            when (prompt.method) {
                // 选项用 M3 的 ListItem 列表：一行一项，点哪行选哪项（文字用 primary，一眼看出可选）
                "select" -> LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp)) {
                    itemsIndexed(prompt.options) { index, option ->
                        ListItem(
                            leadingContent = {
                                Text(
                                    "${index + 1}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                            headlineContent = {
                                Text(
                                    option,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.extraSmall)
                                .clickable { AppState.answerValue(prompt, option) },
                        )
                    }
                }

                // confirm 也做成上下选项（像终端那样），不是两个横着的按钮
                "confirm" -> Column(modifier = Modifier.fillMaxWidth()) {
                    listOf(true to "确定", false to "取消").forEachIndexed { index, (value, label) ->
                        ListItem(
                            leadingContent = {
                                Text(
                                    "${index + 1}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                            headlineContent = {
                                Text(
                                    label,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            },
                            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.extraSmall)
                                .clickable { AppState.answerConfirm(prompt, value) },
                        )
                    }
                }

                else -> Column(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp, end = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = {
                            Text(prompt.placeholder.ifBlank { if (prompt.method == "editor") "多行输入…" else "输入…" })
                        },
                        singleLine = prompt.method != "editor",
                        maxLines = if (prompt.method == "editor") 8 else 1,
                        shape = MaterialTheme.shapes.medium,
                    )
                    FilledTonalButton(
                        onClick = { AppState.answerValue(prompt, text) },
                        modifier = Modifier.align(Alignment.End),
                    ) { Text("提交") }
                }
            }

            val note = buildList {
                prompt.timeoutMs?.let { add("若不回应，约 ${it / 1000} 秒后 pi 会按默认值继续") }
                if (more > 0) add("还有 $more 个待应答")
            }.joinToString(" · ")
            if (note.isNotBlank()) {
                Text(
                    note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp, end = 8.dp),
                )
            }
        }
    }
}

/**
 * 不在会话页时（会话列表 / Shell 页）只放一条**非模态**提示条：说明有请求在等。
 * @param canOpen 当前有没有会话可打开；没有就只说清情况，不给无效按钮
 */
@Composable
fun PendingPromptBanner(canOpen: Boolean, onOpenSession: () -> Unit) {
    val prompt = AppState.uiPrompts.firstOrNull() ?: return
    // 请求来自别的会话时，"去回应"要开**那个**会话（以前一律开列表第一条）
    val openOwner: () -> Unit = {
        val hit = prompt.file?.let { f -> AppState.sessions.firstOrNull { it.file == f } }
        if (hit != null) AppState.openSession(hit) else onOpenSession()
    }
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (canOpen) "pi 在等你回应：${prompt.title}" else "pi 在等你回应：${prompt.title}（先开一个会话）",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (canOpen) {
                TextButton(onClick = openOwner) { Text("去回应") }
            } else {
                TextButton(onClick = { AppState.newSession(null) }) { ButtonLabel("开新会话") }
            }
        }
    }
}
