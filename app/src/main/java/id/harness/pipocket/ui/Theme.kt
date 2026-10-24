package id.harness.pipocket.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import id.harness.pipocket.AppState

/**
 * Material 3 Expressive 主题。**按 guideline 用，不滥用**：
 *
 * - **Color**：Android 12+ 直接用系统壁纸动态色（Material You，跟色），低版本回落到基线配色；
 *   代码里只用 color role（surfaceContainer* / primaryContainer / secondaryContainer…），不写死颜色。
 * - **Shape**：用 expressive 的圆角刻度（8/12/16/20/28 + increased 24/32 + extra-extra 48）。
 *   容器（卡片/弹层/输入框）用刻度内的形状；**形状形变（shape morph）只给可交互元素**——
 *   按钮/图标按钮走自带的 ButtonShapes / IconButtonShapes（按下换形），不随手改内容容器形状。
 * - **Type**：基准排版用标准缩放；**Emphasized 只出现在“主角位”**（页面大标题、信息页的大数字），
 *   正文/辅助文字保持标准样式。
 * - **Motion**：expressive motion scheme；位移动效取 spatial spring，颜色/透明度取 effects spring。
 */
private val PiPocketShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
    largeIncreased = RoundedCornerShape(24.dp),
    extraLargeIncreased = RoundedCornerShape(32.dp),
    extraExtraLarge = RoundedCornerShape(48.dp),
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PiPocketTheme(content: @Composable () -> Unit) {
    val dark = AppState.isDark(isSystemInDarkTheme())
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    // 全局字号：叠在系统 fontScale 上（与系统缩放相乘，而不是取代它）
    val density = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(density.density, density.fontScale * AppState.fontScale),
    ) {
        MaterialExpressiveTheme(
            colorScheme = scheme,
            motionScheme = MotionScheme.expressive(),
            shapes = PiPocketShapes,
            content = content,
        )
    }
}
