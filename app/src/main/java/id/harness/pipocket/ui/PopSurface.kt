package id.harness.pipocket.ui

import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * 全局弹层/菜单规范（免得每个组件各用各的默认值）。
 *
 * **规范依据**（2026-10-18 查）：
 * - M3 **Expressive vertical menu**：容器约 16dp 圆角 + 标准/鲜艳色 + 选中态；旧 baseline 才是 4dp。
 * - Material 的 tooltip/menu **不带箭头**：方向感靠**从锚点发出的动效**传达，而不是画一个指向角。
 *
 * **本项目偏差（用户 2026-10-18 指定）**：允许把**朝向触发按钮的那一个角**收小（`anchored`），
 * 让 popup 看起来“指”着它自己弹出来的那个控件；其余三角仍按规范。
 */
object PopSurface {

    /** 规范形状：Expressive vertical menu 的 16dp 容器圆角。 */
    @Composable
    fun shape(): Shape = MaterialTheme.shapes.medium

    /** 容器色：M3 菜单色角色。 */
    @Composable
    fun container(): Color = MaterialTheme.colorScheme.surfaceContainer

    /**
     * 带“指向角”的弹层形状：贴近锚点的那一角 6dp，其余三角按 [shape]。
     *
     * @param anchoredCorner 哪一个角朝向锚点（如弹层在按钮上方、按钮在左侧 ⇒ BottomStart）
     */
    @Composable
    fun anchored(anchoredCorner: Corner): Shape {
        val big = 16.dp
        val small = 6.dp
        return when (anchoredCorner) {
            Corner.BottomStart -> RoundedCornerShape(topStart = big, topEnd = big, bottomEnd = big, bottomStart = small)
            Corner.BottomEnd -> RoundedCornerShape(topStart = big, topEnd = big, bottomEnd = small, bottomStart = big)
            Corner.TopStart -> RoundedCornerShape(topStart = small, topEnd = big, bottomEnd = big, bottomStart = big)
            Corner.TopEnd -> RoundedCornerShape(topStart = big, topEnd = small, bottomEnd = big, bottomStart = big)
        }
    }

    enum class Corner { TopStart, TopEnd, BottomStart, BottomEnd }
}
