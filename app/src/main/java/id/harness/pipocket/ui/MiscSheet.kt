package id.harness.pipocket.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import id.harness.pipocket.AppState

/**
 * 「杂项」面板（用户 2026-10-21：**单独一层**，不塞在设置里）。
 *
 * 收的全部是**显示类**参数，改完立即生效，存在 prefs：
 * · 工具卡露多少（收起行数 / 展开限高 / 收起限高）
 * · 终端列数（自动 = 按屏幕宽度算；手动 = 你自己定，桥按这个宽度渲染，换行点与终端 1:1）
 * · 字号四档（正文 / 工具名 / 工具参数·终端行 / 次要信息）
 *
 * 为什么放在独立页：这些是"调参"，跟「连接 / 自更新 / provider」这类配置不是一类；
 * 设置页只留一个入口（和「Pi Provider」「App 会话注入」同构，不叠抽屉）。
 */
@Composable
fun MiscSheet(onDismiss: () -> Unit) {
    SheetScaffold(title = "杂项", onDismiss = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "显示类参数，改完立即生效。工具卡是「终端原样渲染」，这几项只决定露多少、多大。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // ── 工具卡 ───────────────────────────────────────────────────────────
            Text("工具卡", style = MaterialTheme.typography.titleSmall)

            StepperRow("收起行数", AppState.toolCollapsedLines,
                onMinus = { AppState.updateToolCollapsedLines(AppState.toolCollapsedLines - 1) },
                onPlus = { AppState.updateToolCollapsedLines(AppState.toolCollapsedLines + 1) })

            Text("展开限高 ${AppState.toolExpandedMaxDp}dp", style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = AppState.toolExpandedMaxDp.toFloat(),
                onValueChange = { AppState.updateToolExpandedMaxDp(it.toInt()) },
                valueRange = 160f..1000f,
                steps = 41,
            )

            Text("收起限高 ${AppState.toolCollapsedMaxDp}dp", style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = AppState.toolCollapsedMaxDp.toFloat(),
                onValueChange = { AppState.updateToolCollapsedMaxDp(it.toInt()) },
                valueRange = 80f..600f,
                steps = 25,
            )

            // ── 终端列数 ─────────────────────────────────────────────────────────
            Spacer(Modifier.size(4.dp))
            Text("终端渲染", style = MaterialTheme.typography.titleSmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("列数按屏幕自动算", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Switch(
                    checked = AppState.termColsOverride == 0,
                    onCheckedChange = { auto ->
                        AppState.updateTermColsOverride(if (auto) 0 else AppState.termCols.coerceIn(30, 120))
                    },
                )
            }
            if (AppState.termColsOverride > 0) {
                Text("列数 ${AppState.termColsOverride}", style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = AppState.termColsOverride.toFloat(),
                    onValueChange = { AppState.updateTermColsOverride(it.toInt()) },
                    valueRange = 30f..120f,
                    steps = 89,
                )
                Text(
                    "桥按这个宽度调 pi 的渲染器 ⇒ 换行点和终端一致",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    "当前自动值：${AppState.termCols} 列",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // ── 字号 ─────────────────────────────────────────────────────────────
            Spacer(Modifier.size(4.dp))
            Text("字号（sp）", style = MaterialTheme.typography.titleSmall)
            StepperRow("正文 / 用户消息", AppState.fontBodySp,
                onMinus = { AppState.updateFontBodySp(AppState.fontBodySp - 1) },
                onPlus = { AppState.updateFontBodySp(AppState.fontBodySp + 1) })
            StepperRow("工具名 / 标题", AppState.fontToolNameSp,
                onMinus = { AppState.updateFontToolNameSp(AppState.fontToolNameSp - 1) },
                onPlus = { AppState.updateFontToolNameSp(AppState.fontToolNameSp + 1) })
            StepperRow("工具参数 / 终端行", AppState.fontToolSp,
                onMinus = { AppState.updateFontToolSp(AppState.fontToolSp - 1) },
                onPlus = { AppState.updateFontToolSp(AppState.fontToolSp + 1) })
            StepperRow("次要信息（footer）", AppState.fontMetaSp,
                onMinus = { AppState.updateFontMetaSp(AppState.fontMetaSp - 1) },
                onPlus = { AppState.updateFontMetaSp(AppState.fontMetaSp + 1) })

            TextButton(onClick = { AppState.resetMiscDefaults() }) { ButtonLabel("全部恢复默认") }
            Spacer(Modifier.size(24.dp))
        }
    }
}

/** 一行「标签 …… − 值 +」，点击即时保存（没有"保存"按钮，改完就是生效值）。 */
@Composable
private fun StepperRow(label: String, value: Int, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        TextButton(onClick = onMinus) { Text("−") }
        Text("$value", style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = onPlus) { Text("+") }
    }
}
