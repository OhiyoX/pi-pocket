package id.harness.pipocket.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import id.harness.pipocket.AppState
import kotlin.math.pow

/**
 * 语义色，**取自 pi 终端（TUI）的实测 ANSI RGB**（见 `docs/TERMINAL-PARITY.md`）。
 *
 * 终端口径（dark）：
 * - 正文 `rgb(222,224,225)`
 * - thinking：灰 `rgb(150,160,164)` + 斜体（终端里 thinking **不是**绿色）
 * - 工具块：**深绿底** `rgb(37,65,49)`；参数/输出灰 `rgb(157,165,169)`
 * - 失败：只把 `exit_code=N` 与错误消息标红 `rgb(234,127,129)`，整块仍是绿底
 * - 行内 code / 路径：紫 `rgb(167,152,215)`；分隔线：紫 `rgb(151,118,229)`
 * - footer：灰 `rgb(126,136,142)`
 *
 * 亮色主题沿用同色相的浅化版本（终端本身是 dark，未取证）。
 */
object TerminalColors {

    /** 当前是不是深色主题（AnsiText 等处要按深浅两套口径处理）。 */
    @Composable
    fun isDark(): Boolean = AppState.isDark(isSystemInDarkTheme())

    @Composable
    private fun dark(): Boolean = AppState.isDark(isSystemInDarkTheme())

    @Composable
    fun body(): Color = if (dark()) Color(0xFFDEE0E1) else MaterialTheme.colorScheme.onSurface

    @Composable
    fun muted(): Color = if (dark()) Color(0xFF9DA5A9) else MaterialTheme.colorScheme.onSurfaceVariant

    @Composable
    fun footer(): Color = if (dark()) Color(0xFF7E888E) else MaterialTheme.colorScheme.onSurfaceVariant

    /** thinking：灰 + 斜体（调用方负责 italic）。 */
    @Composable
    fun thinkingText(): Color = if (dark()) Color(0xFF96A0A4) else MaterialTheme.colorScheme.onSurfaceVariant

    /** 旧名保留（有些地方把 thinking 当容器色用）；终端里 thinking 无底色。 */
    @Composable
    fun thinkingSurface(): Color = Color.Transparent

    /** 工具块背景：终端里是深绿。 */
    @Composable
    fun toolBlock(): Color = if (dark()) Color(0xFF254131) else Color(0xFFDCEFE2)

    // 工具卡底色**三态**（用户 2026-10-23：「tool call 还会变色，由灰变绿」）。
    // 口径直接来自 pi 的 theme token（实测 dark 主题的 ANSI）：
    //   toolPendingBg = rgb(52,56,58) 灰  ·  toolSuccessBg = rgb(37,65,49) 绿  ·  toolErrorBg = rgb(91,40,42) 红
    // 以前 app 固定用 toolBlock()（绿）⇒ 运行中也是绿的，"由灰变绿"这个过程永远看不到。
    @Composable
    fun toolPendingBlock(): Color = if (dark()) Color(0xFF34383A) else Color(0xFFEDEDEF)

    @Composable
    fun toolSuccessBlock(): Color = if (dark()) Color(0xFF254131) else Color(0xFFDCEFE2)

    @Composable
    fun toolErrorBlock(): Color = if (dark()) Color(0xFF5B282A) else Color(0xFFF7DCDD)

    /** 工具卡底色：跑着=灰、失败=红、完成=绿（与终端一致）。 */
    @Composable
    fun toolStateBlock(running: Boolean, failed: Boolean): Color = when {
        running -> toolPendingBlock()
        failed -> toolErrorBlock()
        else -> toolSuccessBlock()
    }

    /** 用户消息块背景：终端实测 `rgb(33,59,73)` **深蓝**（前景 `rgb(222,224,225)`）。 */
    @Composable
    fun userBlock(): Color = if (dark()) Color(0xFF213B49) else Color(0xFFD6E7F2)

    /** 行内 code / 路径高亮：紫。 */
    @Composable
    fun inlineCode(): Color = if (dark()) Color(0xFFA798D7) else Color(0xFF6A55A8)

    /** 分隔线 / 强调边框：紫。 */
    @Composable
    fun rule(): Color = if (dark()) Color(0xFF9776E5) else Color(0xFF7B5BD6)

    /** 标题：终端实测标题是**琥珀** `rgb(205,154,34)`（H1 还带下划线）。 */
    @Composable
    fun heading(): Color = if (dark()) Color(0xFFCD9A22) else Color(0xFF8A6100)

    /** 链接：终端实测 `rgb(105,173,208)` + 下划线，后面跟灰色的 `(url)`。 */
    @Composable
    fun link(): Color = if (dark()) Color(0xFF69ADD0) else Color(0xFF1A6E96)

    /**
     * thinking 六档的色族（逆向 pi 的 `system-theme.ts` 得来，见 `thinkingFamily()`）。
     *
     * 数值 = 按 pi 给的 hue + saturation 区间中值算 HSL（dark L=0.72 / light L=0.48，与其余色齐平）；
     * `neutral` 与 `red` 直接用终端实测的 muted / danger（端点以实测为准）。
     */
    @Composable
    fun thinkingFamily(family: String): Color = when (family) {
        "slate" -> if (dark()) Color(0xFFAEB0C2) else Color(0xFF696E8C)
        "blue" -> if (dark()) Color(0xFFA0A7CF) else Color(0xFF535EA2)
        "periwinkle" -> if (dark()) Color(0xFFB097D8) else Color(0xFF6E43B1)
        "violet" -> if (dark()) Color(0xFFDA8FE1) else Color(0xFFB534C1)
        "magenta" -> if (dark()) Color(0xFFE887AC) else Color(0xFFCD2866)
        "red" -> danger()
        "neutral" -> muted()
        else -> accent()
    }

    /**
     * bash 模式（输入 `!cmd`）的标识色 —— pi 的 `system-theme.ts:112` 里是 `bashMode: "green"`。
     * 取与 `success()` 同一档绿：终端里"命令"和"成功"本来就是同一个绿。
     */
    @Composable
    fun bashMode(): Color = if (dark()) Color(0xFF7BD88F) else Color(0xFF2E7D32)

    @Composable
    fun danger(): Color = if (dark()) Color(0xFFEA7F81) else Color(0xFFB3261E)

    @Composable
    fun warning(): Color = if (dark()) Color(0xFFE8C547) else Color(0xFF8A6100)

    @Composable
    fun success(): Color = if (dark()) Color(0xFF7BD88F) else Color(0xFF2E7D32)

    /** 终端 working 状态的 spinner 色（= theme accent）：紫 `rgb(167,152,215)`，与行内 code 同色。 */
    @Composable
    fun accent(): Color = if (dark()) Color(0xFFA798D7) else Color(0xFF6A55A8)

    @Composable
    fun info(): Color = MaterialTheme.colorScheme.primary

    /** 语义容器色（notice / 状态条用）。 */
    @Composable
    fun container(level: String): Color = when (level) {
        "error" -> danger().copy(alpha = if (dark()) 0.18f else 0.12f)
        "warn" -> warning().copy(alpha = if (dark()) 0.18f else 0.14f)
        "success" -> success().copy(alpha = if (dark()) 0.16f else 0.12f)
        else -> MaterialTheme.colorScheme.surfaceContainerHighest
    }

    // ── 浅色模式下 ANSI 颜色的可读化 ─────────────────────────────────────
    //
    // 工具卡的正文**不是 app 自己的颜色**，是桥用 pi 的 dark 主题渲染出来的 ANSI truecolor
    // （输出/元信息 `rgb(157,165,169)`、正文 `rgb(222,224,225)`…）。深色底上是灰白，好看；
    // 但浅色模式工具卡是浅绿底 `rgb(221,239,227)`，实测对比度只有 **2.1:1**（截图量出来的），
    // 肉眼看就是「tool call 内容看不清」。
    //
    // 这里不改桥（它继续按终端口径出 ANSI），而是在 app 侧把前景色**等比压暗到可读**：
    // 在线性 RGB 里乘一个系数 k ⇒ 亮度按比例降、色相/彩度比例不变（绿还是绿、红还是红），
    // 目标是对工具卡底色 ≥ 4.6:1。已经够暗的颜色原样返回（深色模式完全不参与）。

    /** 浅色工具卡底色（与 [toolBlock] 浅色分支同一个值）。 */
    val lightToolBlock: Color = Color(0xFFDCEFE2)

    /** 相对亮度（WCAG）。 */
    fun relativeLuminance(c: Color): Float {
        fun ch(v: Float): Float = if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).pow(2.4f)
        return 0.2126f * ch(c.red) + 0.7152f * ch(c.green) + 0.0722f * ch(c.blue)
    }

    fun contrastRatio(a: Color, b: Color): Float {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        val hi = maxOf(la, lb)
        val lo = minOf(la, lb)
        return (hi + 0.05f) / (lo + 0.05f)
    }

    /**
     * 把 pi 的（dark 主题）ANSI 前景色换算成**浅色底上可读**的同色相颜色。
     *
     * 只压不亮：对比度已经 ≥ [minRatio] 的原样返回。
     */
    fun ansiOnLight(fg: Color, bg: Color = lightToolBlock, minRatio: Float = 4.6f): Color {
        val lb = relativeLuminance(bg)
        // 需要的前景亮度上限：满足 (lb+0.05)/(lf+0.05) >= minRatio
        val targetL = ((lb + 0.05f) / minRatio - 0.05f).coerceAtLeast(0f)
        val lf = relativeLuminance(fg)
        if (lf <= targetL || lf <= 0.0001f) return fg
        // 压进 [0.55·target, target]：越亮压得越小，但保留「正文比元信息亮」的层次
        // （直接把所有颜色压到同一个 target 会把正文和灰字化成同一个深度，看不出主次）。
        val frac = ((lf - targetL) / (1f - targetL).coerceAtLeast(0.001f)).coerceIn(0f, 1f)
        val newL = targetL * (0.55f + 0.45f * frac)
        val k = (newL / lf).coerceIn(0f, 1f)
        fun ch(v: Float): Float = if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).pow(2.4f)
        fun inv(v: Float): Float =
            if (v <= 0.0031308f) v * 12.92f else 1.055f * v.pow(1f / 2.4f) - 0.055f
        return Color(
            red = inv(ch(fg.red) * k).coerceIn(0f, 1f),
            green = inv(ch(fg.green) * k).coerceIn(0f, 1f),
            blue = inv(ch(fg.blue) * k).coerceIn(0f, 1f),
            alpha = fg.alpha,
        )
    }

    /**
     * AnsiText 用的颜色映射器：深色模式恒等，浅色模式压暗到可读。
     * 用 [remember] 固定引用（AnsiText 里按它做 key 缓存 AnnotatedString）。
     */
    @Composable
    fun ansiMapper(): (Color) -> Color {
        val dark = dark()
        return remember(dark) {
            if (dark) {
                { c: Color -> c }
            } else {
                { c: Color -> ansiOnLight(c) }
            }
        }
    }

    @Composable
    fun onContainer(level: String): Color = when (level) {
        "error" -> danger()
        "warn" -> warning()
        "success" -> success()
        else -> MaterialTheme.colorScheme.onSurface
    }
}
