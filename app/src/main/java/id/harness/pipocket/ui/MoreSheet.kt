@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package id.harness.pipocket.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import id.harness.pipocket.R

/** 分组的低频会话动作；七项快捷菜单是项目约定，长下拉菜单本身支持滚动。
 * 行高使用下限，允许大字号与多行标题增长；规范见 docs/UI-DESIGN-GUIDELINES.md。
 */
@Composable
fun MoreSheet(
    modelDisplay: String? = null,
    thinkingLevel: String? = null,
    onPick: (ChatAction) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetDensity = androidx.compose.ui.platform.LocalDensity.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides sheetDensity) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            Text(
                "更多",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
            // 当前会话的"参数带"：模型 / 思考等级放在面板顶上，
            // 这样从「更多」进来也能确认自己在什么状态（不用再退回锚定菜单看）。
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    menuHint(modelDisplay) ?: "模型 —",
                    style = MaterialTheme.typography.labelSmall,
                    color = TerminalColors.muted(),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text("·", style = MaterialTheme.typography.labelSmall, color = TerminalColors.muted())
                Text(
                    menuHint(thinkingLevel)?.let { "思考 $it" } ?: "思考 —",
                    style = MaterialTheme.typography.labelSmall,
                    color = TerminalColors.muted(),
                )
            }

            // 分组标题已经足够分隔，不再叠分隔线（项目视觉取舍）。
            // 标题与上一组最后一行的间距 > 标题与自身第一行的间距 ⇒ 视觉上"标题跟着它的一组"。
            moreMenu.forEach { group ->
                group.title?.let { title ->
                    Text(
                        title,
                        style = MaterialTheme.typography.labelLarge,
                        color = TerminalColors.muted(),
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 4.dp),
                    )
                }
                group.rows.forEach { row ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .clickable { onPick(row.action) }
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            row.label,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (row.action != ChatAction.Reload && row.action != ChatAction.Rename) {
                            Spacer(Modifier.size(4.dp))
                            Icon(
                                painter = painterResource(R.drawable.ic_chevron_right),
                                contentDescription = null,
                                tint = TerminalColors.muted(),
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
        }
        }
    }
}
