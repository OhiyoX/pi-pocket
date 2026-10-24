@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package id.harness.pipocket.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import id.harness.pipocket.AppState

/**
 * 「行为开关」面板 —— pi 终端 `/settings` 里那几项在 app 侧的入口。
 *
 * 以前这些在 app 里**完全看不到**（协议面缺 `set_auto_compaction` / `set_auto_retry` /
 * `set_steering_mode` / `set_follow_up_mode` / `abort_retry`）。
 *
 * 取值口径全部照 pi 源码：
 *  · 队列模式 `"one-at-a-time"` | `"all"`（`core/agent-session.ts:1642`）；
 *  · `autoCompactionEnabled` 来自 `get_state`；
 *  · `autoRetryEnabled` 不在 session state 里 —— pi 存在 `settings.json` 的 `retry.enabled`。
 */
@Composable
fun BehaviorSheet(onDismiss: () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val behavior = AppState.behavior
    val busy = AppState.behaviorBusy

    LaunchedEffect(Unit) { if (AppState.behavior == null) AppState.loadBehavior() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("行为开关", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.width(10.dp))
                Text(
                    if (busy) "保存中…" else "改了就生效（同终端 /settings）",
                    style = MaterialTheme.typography.bodySmall,
                    color = TerminalColors.muted(),
                    modifier = Modifier.weight(1f),
                )
                if (busy) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            }

            // 正在自动重试：给出「中断」按钮（终端里是倒计时中按 Esc）
            val retrying = AppState.retrying
            if (retrying != null) {
                Surface(
                    color = TerminalColors.warning().copy(alpha = 0.14f),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            retryBanner(retrying.attempt, retrying.maxAttempts, retrying.delayMs),
                            style = MaterialTheme.typography.bodyMedium,
                            color = TerminalColors.warning(),
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { AppState.abortRetry() }) { Text("中断重试") }
                    }
                }
            }

            if (behavior == null) {
                Column(modifier = Modifier.fillMaxWidth().padding(24.dp)) {
                    Text(
                        "正在读取会话状态…",
                        style = MaterialTheme.typography.bodySmall,
                        color = TerminalColors.muted(),
                    )
                }
            } else {
                BehaviorSwitch(
                    title = "自动压缩上下文",
                    subtitle = "上下文快满时自动压缩（pi 的 auto-compaction）",
                    checked = behavior.autoCompaction,
                    enabled = !busy,
                    onChange = { AppState.setBehavior(autoCompaction = it) },
                )
                BehaviorSwitch(
                    title = "自动重试",
                    subtitle = buildString {
                        append("模型出错时自动重来")
                        behavior.maxRetries?.let { append("（pi 配的上限：$it 次）") }
                    },
                    checked = behavior.autoRetry,
                    enabled = !busy,
                    onChange = { AppState.setBehavior(autoRetry = it) },
                )

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 4.dp),
                    color = TerminalColors.muted().copy(alpha = 0.25f),
                )

                // steering：流式中发消息 = 插进本轮
                ModeRow(
                    title = "插入方式（steering）",
                    hint = queueModeHint(behavior.steeringMode, followUp = false),
                    current = behavior.steeringMode,
                    enabled = !busy,
                    onPick = { AppState.setBehavior(steeringMode = it) },
                )
                // follow-up：跑完再做
                ModeRow(
                    title = "排队方式（follow-up）",
                    hint = queueModeHint(behavior.followUpMode, followUp = true),
                    current = behavior.followUpMode,
                    enabled = !busy,
                    onPick = { AppState.setBehavior(followUpMode = it) },
                )

                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun BehaviorSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = TerminalColors.muted(),
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun ModeRow(
    title: String,
    hint: String,
    current: String,
    enabled: Boolean,
    onPick: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(hint, style = MaterialTheme.typography.bodySmall, color = TerminalColors.muted())
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (mode in QUEUE_MODES) {
                val on = mode == current
                Surface(
                    onClick = { if (enabled && !on) onPick(mode) },
                    enabled = enabled,
                    shape = PopSurface.shape(),
                    color = if (on) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                    contentColor = if (on) MaterialTheme.colorScheme.onSecondaryContainer else TerminalColors.muted(),
                ) {
                    Text(
                        queueModeLabel(mode),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (on) FontWeight.Medium else FontWeight.Normal,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                    )
                }
            }
        }
    }
}
