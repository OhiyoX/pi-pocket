@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package id.harness.pipocket.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import id.harness.pipocket.AppState
import id.harness.pipocket.ProviderForm
import id.harness.pipocket.ProviderInfo
import id.harness.pipocket.R

/**
 * 「Pi Provider」面板（HANDOFF §15 的那套配置：`~/.pi/agent/models.json` + `auth.json`）。
 *
 * 口径：
 * - **只跟桥的 `/v1/providers` 说话**，不自己读写文件；桥**不回传 key**，所以列表只有「有没有 key」。
 * - 编辑已有 provider 时 `apiKey` 留空 = **不改**（桥那边的 upsert 也是这个语义）。
 * - 列表/表单在**同一个抽屉里切换**（不叠抽屉，见 §13）；状态不用 chip/图标，用小字 + 颜色（§7）。
 */
@Composable
fun ProvidersSheet(onDismiss: () -> Unit) {
    // null = 列表；editing != null = 编辑；creating = 新建。同一时刻只有一个形态。
    var editing by remember { mutableStateOf<ProviderInfo?>(null) }
    var creating by remember { mutableStateOf(false) }

    SheetScaffold(title = "Pi Provider", onDismiss = onDismiss) {
        when {
            creating -> ProviderEditor(
                initial = null,
                onCancel = { creating = false },
                onSaved = { creating = false },
            )

            editing != null -> ProviderEditor(
                initial = editing,
                onCancel = { editing = null },
                onSaved = { editing = null },
            )

            else -> ProviderList(
                onNew = { creating = true },
                onEdit = { editing = it },
            )
        }
    }
}

@Composable
private fun ProviderList(onNew: () -> Unit, onEdit: (ProviderInfo) -> Unit) {
    var deleting by remember { mutableStateOf<ProviderInfo?>(null) }
    val providers = AppState.providers
    val error = AppState.providersError

    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            "pi 的 provider 配置：models.json 里自己加的（可改可删）+ pi 自带目录里已经配了 key 的（内置，只读）。" +
                "这里只经过桥读写，key 不会回传到 app，也不会进日志。",
            style = MaterialTheme.typography.bodySmall,
            color = TerminalColors.muted(),
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 4.dp),
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledTonalButton(onClick = onNew, modifier = Modifier.weight(1f)) { ButtonLabel("新增 provider") }
            TextButton(onClick = { AppState.loadProviders() }, enabled = !AppState.providersLoading) {
                Text(if (AppState.providersLoading) "读取中…" else "刷新")
            }
        }

        AppState.providersMessage?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }
        if (error != null) {
            SelectionContainer {
                Text(
                    "读/写失败：$error",
                    style = MaterialTheme.typography.bodySmall,
                    color = TerminalColors.danger(),
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
        }

        HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))

        if (providers.isEmpty()) {
            Text(
                if (AppState.providersLoading) "读取中…" else "还没有配置 provider（点上面的「新增 provider」加一个）。",
                style = MaterialTheme.typography.bodySmall,
                color = TerminalColors.muted(),
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        } else {
            providers.forEach { provider ->
                ProviderRow(
                    provider = provider,
                    // 内置 provider（官方 deepseek / openai …）不在这里改：没有 baseUrl/key 可编，也不允许删
                    onClick = { if (!provider.builtin) onEdit(provider) },
                    onDelete = { deleting = provider },
                )
            }
        }

        Text(
            "改完如果模型列表没变，去会话里点一下模型，或「设置 → 重连 / 刷新」。",
            style = MaterialTheme.typography.labelSmall,
            color = TerminalColors.footer(),
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp),
        )
        Spacer(modifier = Modifier.size(20.dp))
    }

    // 删除要二次确认：这会把 models.json 与 auth.json 里的同名片一起删掉
    deleting?.let { provider ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除 provider？") },
            text = {
                Text(
                    "会从 models.json 与 auth.json 同时删掉 " + provider.name +
                        "（连同它的 key）。pi 里用这个 provider 的模型会一起没了。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val name = provider.name
                    deleting = null
                    AppState.removeProvider(name)
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } },
        )
    }
}

/** 一行 provider：名字 / baseUrl / 模型摘要 / 有没有 key（小字 + 语义色，不用 chip）。 */
@Composable
private fun ProviderRow(provider: ProviderInfo, onClick: () -> Unit, onDelete: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(MaterialTheme.shapes.large)
            .clickable(enabled = !provider.builtin, onClick = onClick),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    provider.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    provider.baseUrl.ifBlank { if (provider.builtin) "pi 自带目录（catalog）" else "（没有 baseUrl）" },
                    style = MaterialTheme.typography.labelSmall,
                    color = TerminalColors.muted(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (provider.builtin) "内置" else provider.api.ifBlank { "api 未设" },
                        style = MaterialTheme.typography.labelSmall,
                        color = TerminalColors.footer(),
                        maxLines = 1,
                    )
                    KeyLabel(provider.hasKey)
                }
                Text(
                    provider.modelsSummary,
                    style = MaterialTheme.typography.labelSmall,
                    color = TerminalColors.muted(),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (provider.builtin) {
                Text(
                    "内置",
                    style = MaterialTheme.typography.labelSmall,
                    color = TerminalColors.footer(),
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            } else {
                TextButton(onClick = onDelete) { Text("删除") }
            }
        }
    }
}

/** 「有没有 key」那行小字（§7：状态用小字/颜色，不用 chip/图标）。 */
@Composable
private fun KeyLabel(hasKey: Boolean) {
    Text(
        if (hasKey) "有 key" else "没有 key（用不了）",
        style = MaterialTheme.typography.labelSmall,
        color = if (hasKey) MaterialTheme.colorScheme.primary else TerminalColors.warning(),
    )
}

@Composable
private fun ProviderEditor(
    initial: ProviderInfo?,
    onCancel: () -> Unit,
    onSaved: () -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var baseUrl by remember { mutableStateOf(initial?.baseUrl.orEmpty()) }
    var api by remember {
        mutableStateOf(initial?.api?.takeIf { it.isNotBlank() } ?: "openai-completions")
    }
    var models by remember { mutableStateOf(initial?.models?.let { ProviderForm.formatModels(it) }.orEmpty()) }
    var apiKey by remember { mutableStateOf("") }
    var apiMenu by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val nameError = ProviderForm.nameError(name)
    val editing = initial != null

    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            if (editing) "编辑 " + (initial?.name ?: "") + "：名字是主键（改名请新建后再删旧的）。"
            else "新增 provider：写进 pi 的 models.json（providers）与 auth.json（api_key，600）。",
            style = MaterialTheme.typography.bodySmall,
            color = TerminalColors.muted(),
            modifier = Modifier.padding(horizontal = 20.dp),
        )

        OutlinedTextField(
            value = name,
            onValueChange = { name = it.trim() },
            label = { Text("name") },
            singleLine = true,
            enabled = !editing,
            isError = name.isNotBlank() && nameError != null,
            supportingText = {
                Text(
                    if (name.isNotBlank() && nameError != null) nameError!! else "字母、数字和 . _ -（pi 里就是这个 key）",
                    style = MaterialTheme.typography.labelSmall,
                )
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        )

        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it.trim() },
            label = { Text("baseUrl") },
            singleLine = true,
            placeholder = { Text("https://api.example.com/v1") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        )

        // api：下拉给常用值，也允许手填（桥不校验这个字段）
        Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            OutlinedTextField(
                value = api,
                onValueChange = { api = it.trim() },
                label = { Text("api") },
                singleLine = true,
                trailingIcon = {
                    IconButton(onClick = { apiMenu = true }) {
                        Icon(painterResource(R.drawable.ic_expand), contentDescription = "选常用 api")
                    }
                },
                supportingText = {
                    Text("常用：" + ProviderForm.API_TYPES.joinToString(" / "), style = MaterialTheme.typography.labelSmall)
                },
                modifier = Modifier.fillMaxWidth(),
            )
            DropdownMenu(expanded = apiMenu, onDismissRequest = { apiMenu = false }) {
                ProviderForm.API_TYPES.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option, style = MaterialTheme.typography.bodySmall) },
                        onClick = {
                            api = option
                            apiMenu = false
                        },
                    )
                }
            }
        }

        OutlinedTextField(
            value = models,
            onValueChange = { models = it },
            label = { Text("models") },
            supportingText = {
                val parsed = ProviderForm.parseModels(models)
                Text(
                    if (parsed.isEmpty()) "逗号分隔的模型 id（空 = 不改已有模型）"
                    else "将写入 " + parsed.size + " 个：" + parsed.take(3).joinToString(", ") +
                        (if (parsed.size > 3) " …" else ""),
                    style = MaterialTheme.typography.labelSmall,
                )
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        )

        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            label = { Text("apiKey") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            supportingText = {
                Text(
                    if (editing && initial?.hasKey == true) "这个 provider 已经有 key；留空 = 不改"
                    else "写进 auth.json 的 {type:api_key,key}（600）；不回传、不进日志",
                    style = MaterialTheme.typography.labelSmall,
                )
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        )

        AppState.providersError?.let {
            SelectionContainer {
                Text(
                    "没保存成功：$it",
                    style = MaterialTheme.typography.bodySmall,
                    color = TerminalColors.danger(),
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilledTonalButton(
                onClick = {
                    saving = true
                    AppState.saveProvider(name, baseUrl, api, models, apiKey) { ok ->
                        saving = false
                        if (ok) onSaved()
                    }
                },
                enabled = !saving && nameError == null,
                modifier = Modifier.weight(1f),
            ) { Text(if (saving) "保存中…" else "保存") }
            TextButton(onClick = onCancel) { Text("取消") }
        }

        SelectionContainer {
            Text(
                "保存后桥会写 models.json（providers[name] = {baseUrl, api, models[]}）与 auth.json（写前备份、原子写、600）。",
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = TerminalColors.footer(),
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }
        Spacer(modifier = Modifier.size(20.dp))
    }
}
