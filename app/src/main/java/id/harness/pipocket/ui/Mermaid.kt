package id.harness.pipocket.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.os.Handler
import android.os.SystemClock
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import id.harness.pipocket.AppState
import id.harness.pipocket.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Mermaid 图（```mermaid 围栏）。口径照用户 Obsidian-web 那套（vault 笔记 #482）：
 *
 * · **懒加载**：只有正文里真的出现 mermaid 围栏才会建 WebView；没有围栏的会话一次都不加载渲染器。
 * · **失败保留原文**：渲染不出来就把原来的代码块照原样显示，绝不留白。
 * · **零外部请求**：`mermaid.min.js` 打包在 assets 里（MIT），不连 CDN。
 * · **主题跟随 app**：按 `TerminalColors.isDark()` 走 `dark` / `base` 两套 themeVariables。
 * · **尺寸**（四轮真机截图调下来的）——**只量自然尺寸，不做比例换算**：
 *   ① 宿主页把 mermaid 图的**自然尺寸**（viewBox 的宽高，单位 = CSS px）报回来，原生直接把它当
 *   dp 用（1 CSS px = 1dp，真机实测）。宽度取 `max(容器宽, 自然宽)`：**窄图保持自然宽居中、
 *   宽图把 WebView 拉宽 + 外层横向滚动 —— 两条路都不缩字号**（用户口径：字跟正文一个量级）。
 *   为什么彻底废掉老的「量比例 → 乘容器宽算高」：`h/vw` 是**跟视口宽相关**的量，内联量到的比例
 *   拿到全屏用就错（用户 2026-10-22 第四轮：全屏上下对称被切）；而且宽图被缩到容器宽会让字号
 *   变小、跟正文对不上（同轮报「字体大小还是不一致」）。自然尺寸**与视口无关**，量到就是准的。
 *   ② **字号 = 正文字号**（`AppState.fontBodySp`，默认 12 × 当前 fontScale）——以前写死 14px
 *   （= bodyMedium，比正文大一档），用户的 app 缩放又是 1.0，所以「改了半天没用」。现在直接
 *   读正文用的那个值，字号滑杆一动图里的字跟着变。
 *   ③ **不裁描边**：原生高度 = 自然高 + [MERMAID_PAD_DP]（最后一个节点的底边框曾被整条切掉）。
 * · **点一下全屏**：全屏里是**自绘画布**（Compose `graphicsLayer`，所以平移不受 WebView 滚动范围
 *   限制）：**进来先 fit-to-screen**（等比缩到整张看得全并居中），拖动=平移、捏合=缩放、单击=关闭、
 *   右上角关闭键；**宽图自动转横屏**（`SENSOR_LANDSCAPE`），别的时候允许随设备转（`SENSOR`），
 *   退出恢复原状。居中一律交给 Compose 的 `contentAlignment`，**原生侧不算 offset**（以前手算
 *   偏移 + 用错比例，是全屏那几轮 bug 的来源）。
 */
@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
fun MermaidBlock(code: String, fallback: @Composable () -> Unit) {
    val dark = TerminalColors.isDark()
    var failed by remember(code, dark) { mutableStateOf(false) }
    var expanded by remember(code, dark) { mutableStateOf(false) }
    var size by remember(code, dark) { mutableStateOf<MermaidSize?>(null) }
    // 字号 = **正文用的那个 sp** × 当前有效 fontScale。
    //
    // 正文（助手/用户消息）用的是 `bodySmall.copy(fontSize = AppState.fontBodySp.sp)`
    // （杂项 → 「正文 / 用户消息」，默认 **12**），**不是** bodyMedium(14) —— 以前写死 14px，
    // 于是图里的字永远比正文大一档（真机量：正文 CJK 墨迹 36px、图内 43px = 14/12 ≈ +17%），
    // 用户连着几轮报「字体大小不一致」。另外 LocalDensity 里已经把系统 fontScale 与 app 的
    // 「全局字号/聊天字号」相乘，WebView 的 CSS px 不吃这个缩放 ⇒ 必须自己乘上去。
    val proseSp = AppState.fontBodySp.toFloat()
    val fontScale = LocalDensity.current.fontScale
    val fsCss = remember(proseSp, fontScale) { mermaidFontSizeCss(proseSp, fontScale) }

    if (failed || code.isBlank()) {
        fallback()
        return
    }

    MermaidView(
        code = code,
        dark = dark,
        fontSizeCss = fsCss,
        onFailed = { failed = true },
        onTap = { expanded = true },
        onSize = { size = it },
        modifier = Modifier.fillMaxWidth(),
    )

    if (expanded) {
        // 宽图（宽高比 > 1.3）全屏时自动给横屏：mermaid 的 LR 图横过来才看得全。
        val wide = size?.let { it.heightDp > 0f && it.widthDp / it.heightDp > 1.3f } ?: false
        MermaidFullscreen(
            code = code,
            dark = dark,
            fontSizeCss = fsCss,
            natural = size,
            wide = wide,
            onClose = { expanded = false },
        )
    }
}

/**
 * 全屏画布：进来先 **fit-to-screen**（整张图等比缩到看得全）+ 拖动平移（无边界）+ 捏合缩放
 * + 单击关闭 + 宽图自动横屏。
 *
 * 居中一律交给 `contentAlignment`，原生侧**不算平移**；缩放走 `graphicsLayer`（原点 = 中心），
 * 「居中 + 等比缩放」天然同心。以前手算 offset + 用错比例，真机上就是「上下对称被切」。
 */
@Composable
private fun MermaidFullscreen(
    code: String,
    dark: Boolean,
    fontSizeCss: String,
    natural: MermaidSize?,
    wide: Boolean,
    onClose: () -> Unit,
) {
    // 优先用内联那张量到的自然尺寸（**与视口无关**，内联/全屏同一个值），全屏自己再量到就覆盖。
    var size by remember { mutableStateOf(natural) }
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    // 已经 fit 过的视口尺寸（px）：同一视口不重复复位，旋转（尺寸变了）才重新 fit。
    var fittedFor by remember { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current

    // 全屏期间改活动方向：宽图强制横屏，其它允许随设备转；退出恢复（含 null 的 UNSPECIFIED）
    val activity = LocalContext.current.findActivity()
    DisposableEffect(activity, wide) {
        val prev = activity?.requestedOrientation
        activity?.requestedOrientation =
            if (wide) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else ActivityInfo.SCREEN_ORIENTATION_SENSOR
        onDispose { activity?.requestedOrientation = prev ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .clipToBounds()
                // 给顶部关闭键与底部提示留安全边距：fit 用的是这里的 maxWidth/maxHeight
                // ⇒ 图不会被提示文字压住（真机/模拟器截图都能看到最后那个节点被压过）。
                .padding(top = 20.dp, bottom = 44.dp),
            // 内容居中：**不手算偏移**（手算 + 用错比例 = 全屏那几轮 bug）
            contentAlignment = Alignment.Center,
        ) {
            val vwPx = with(density) { maxWidth.toPx() }
            val vhPx = with(density) { maxHeight.toPx() }
            val layout = mermaidLayout(size, maxWidth.value)

            // fit-to-screen：拿**自然尺寸**算（与视口无关），装不下才等比缩到装得下。
            LaunchedEffect(size, layout.widthDp, layout.heightDp, vwPx, vhPx) {
                if (size == null) return@LaunchedEffect
                val key = Offset(vwPx, vhPx)
                if (fittedFor == key) return@LaunchedEffect
                fittedFor = key
                val wPx = with(density) { layout.widthDp.dp.toPx() }
                val hPx = with(density) { layout.heightDp.dp.toPx() }
                scale = minOf(1f, vwPx / wPx, vhPx / hPx)
                offset = Offset.Zero
            }

            Box(
                modifier = Modifier
                    // **requiredSize**：宽图时这个盒子比视口大，size() 会被父约束夹到视口宽，
                    // 内容就不在盒子中心了（缩放/居中全靠它同心）。requiredSize 顶掉父约束。
                    .requiredSize(layout.widthDp.dp, layout.heightDp.dp)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
            ) {
                MermaidView(
                    code = code,
                    dark = dark,
                    fontSizeCss = fontSizeCss,
                    onFailed = {},
                    onTap = null,
                    onSize = { size = it },
                    pinchZoom = false,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            // 触摸层盖在最上面：WebView 是原生 View 会吃掉所有触摸，所以手势由 Compose 接管
            // （顺带：拖动不受 WebView 滚动范围限制 = 无边界）
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(0.2f, 8f)
                            offset += pan
                        }
                    }
                    .pointerInput(Unit) { detectTapGestures { onClose() } },
            )
            IconButton(
                onClick = onClose,
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
            ) {
                Icon(painterResource(R.drawable.ic_close), contentDescription = "关闭")
            }
            Text(
                text = "拖动平移 · 捏合缩放 · 点击关闭" + if (wide) "（已转横屏）" else "",
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                color = TerminalColors.muted(),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
            )
        }
    }
}

private fun Context.findActivity(): android.app.Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is android.app.Activity) return c
        c = c.baseContext
    }
    return null
}

/** mermaid 图的**自然尺寸**（单位 = CSS px = dp，1:1）。取自 svg 的 viewBox ⇒ **与视口宽无关**，
 *  内联量到的值拿到全屏也成立。 */
internal data class MermaidSize(val widthDp: Float, val heightDp: Float)

/** 画布里那张图的盒子尺寸。 */
internal data class MermaidLayout(val widthDp: Float, val heightDp: Float, val scrollable: Boolean)

/** 描边余量：最后一个节点的底边框正好落在盒边界上，少几 px 会被整条切掉（真机截图实测）。 */
internal const val MERMAID_PAD_DP = 4f

/** 还没量到尺寸时的占位高。 */
private const val MERMAID_PLACEHOLDER_DP = 120f

/** 高度上限（dp）：只防畸形长图把 WebView 撑爆；正常流程图远够用。超了由宿主页整体等比缩。 */
internal const val MERMAID_MAX_HEIGHT_DP = 2000f

/**
 * 自然尺寸 → 盒子尺寸（纯函数，JVM 单测认它）。
 *
 * · 宽 = `max(视口宽, 自然宽)`：图比容器宽就把 **WebView 拉宽 + 外面横向滚**（[scrollable]），
 *   **不缩字号**（用户口径：字跟正文一个量级）；
 * · 高 = 自然高 + [MERMAID_PAD_DP]（留描边）。
 */
internal fun mermaidLayout(size: MermaidSize?, viewportDp: Float): MermaidLayout {
    val w = size?.widthDp ?: 0f
    val h = size?.heightDp ?: 0f
    if (w <= 0f || h <= 0f) return MermaidLayout(viewportDp, MERMAID_PLACEHOLDER_DP, scrollable = false)
    return MermaidLayout(
        widthDp = maxOf(viewportDp, w),
        heightDp = h + MERMAID_PAD_DP,
        // 0.5dp 容差：浮点上“刚好一样宽”不算要滚
        scrollable = w > viewportDp + 0.5f,
    )
}

/** 正文字号（sp）→ mermaid 的 `themeVariables.fontSize`（CSS px = dp）。 */
internal fun mermaidFontSizeCss(proseSp: Float, fontScale: Float): String {
    val px = proseSp * fontScale
    val rounded = Math.round(px * 10f) / 10f
    val s = if (rounded == rounded.toInt().toFloat()) {
        rounded.toInt().toString()
    } else {
        String.format(java.util.Locale.US, "%.1f", rounded)
    }
    return "${s}px"
}

/** 浅色主题变量（深色直接用 mermaid 自带 `dark`，所以只在浅色时注入）。 */
private val LIGHT_THEME_VARS = """
    primaryColor: '#F0EEF9',
    primaryTextColor: '#1F1B2E',
    primaryBorderColor: '#AFA3D9',
    secondaryColor: '#E7E4F2',
    tertiaryColor: '#F7F6FB',
    lineColor: '#8E86AC',
    textColor: '#1F1B2E',
    edgeLabelBackground: '#FFFFFF',
    fontFamily: 'system-ui, -apple-system, sans-serif'
""".trimIndent()

/** 还没建 WebView 时的占位高度：给个稳定的方块，免得图"蹦"出来把下面的内容推走。 */
private val MERMAID_PLACEHOLDER_HEIGHT = 96.dp

/**
 * 一张图 = 一个 WebView。
 *
 * @param onTap 非空时在 WebView 上盖一层透明点击区（内联态：点开全屏）。
 *   WebView 是原生 View，会把触摸全吃掉，所以「点开」不能靠父级 clickable。
 * @param onSize 量到图的**自然尺寸**（CSS px = dp）后回调；全屏拿它算 fit、拿它判宽图。
 * @param pinchZoom 开 WebView 自带双指缩放（默认关：全屏的缩放由 Compose 画布做，两套会打架）。
 */
@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
private fun MermaidView(
    code: String,
    dark: Boolean,
    fontSizeCss: String,
    onFailed: () -> Unit,
    onTap: (() -> Unit)?,
    modifier: Modifier = Modifier,
    onSize: ((MermaidSize) -> Unit)? = null,
    pinchZoom: Boolean = false,
) {
    var size by remember(code, dark, fontSizeCss) { mutableStateOf<MermaidSize?>(null) }
    val main = remember { Handler(Looper.getMainLooper()) }
    val scroll = rememberScrollState()

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val layout = mermaidLayout(size, maxWidth.value)
        // 自证：原生这把盒子摆成多大（跟上面的 viewportCss 一比就知道换算是几倍）
        if (size != null) {
            android.util.Log.i(
                "PiPocketMermaid",
                "box=${layout.widthDp}x${layout.heightDp}dp viewportDp=${maxWidth.value} density=${LocalDensity.current.density}",
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (layout.scrollable) Modifier.horizontalScroll(scroll) else Modifier),
            // 宽图（要滚）时靠左起滚；窄图居中
            contentAlignment = if (layout.scrollable) Alignment.TopStart else Alignment.Center,
        ) {
            // 延迟 + 串行建 WebView（见 WebViewGate）：一屏多图时不再把主线程按死。
            // 占位是"图表…"而不是空白：让人知道这里将会有图，且高度稳定不推挤下文。
            DeferWebView(
                key = Triple(code, dark, fontSizeCss),
                placeholderHeight = MERMAID_PLACEHOLDER_HEIGHT,
            ) {
                // key(dark, fontSizeCss, pinchZoom)：主题/字号/缩放能力都是建 WebView 时定死的，
                // 变了必须重建（否则调了「正文字号」滑杆，图里的字还是旧的）。
                key(dark, fontSizeCss, pinchZoom) {
                    AndroidView(
                    modifier = Modifier.width(layout.widthDp.dp).height(layout.heightDp.dp),
                    factory = { ctx ->
                        val t0 = SystemClock.uptimeMillis()
                        WebView(ctx).apply {
                            setBackgroundColor(0x00000000)
                            // 图必须**贴顶**画：内部一旦能滚，用户在图上拖一下就把图拖歪/拖切
                            isVerticalScrollBarEnabled = false
                            isHorizontalScrollBarEnabled = false
                            overScrollMode = android.view.View.OVER_SCROLL_NEVER
                            settings.javaScriptEnabled = true
                            settings.allowFileAccess = true
                            if (pinchZoom) {
                                settings.setSupportZoom(true)
                                settings.builtInZoomControls = true
                                settings.displayZoomControls = false
                            } else {
                                settings.setSupportZoom(false)
                            }
                            addJavascriptInterface(
                                object {
                                    @JavascriptInterface
                                    fun reportSize(w: Double, h: Double, dpr: Double, vw: Double) {
                                        if (w <= 0.0 || h <= 0.0) return
                                        // 自证用：CSS px ↔ dp 的换算一比就知道（dpr 应等于 density，
                                        // vw 应等于 WebView 的 dp 宽）；见 docs/HANDOFF.md §22.2
                                        android.util.Log.i(
                                            "PiPocketMermaid",
                                            "natural=${w}x${h} viewportCss=$vw dpr=$dpr",
                                        )
                                        main.post {
                                            val v = MermaidSize(w.toFloat(), h.toFloat())
                                            size = v
                                            onSize?.invoke(v)
                                        }
                                    }

                                    @JavascriptInterface
                                    fun reportError() {
                                        main.post(onFailed)
                                    }
                                },
                                "PiPocket",
                            )
                            loadDataWithBaseURL(
                                "file:///android_asset/",
                                mermaidHtml(code, light = !dark, fontSizeCss = fontSizeCss),
                                "text/html",
                                "utf-8",
                                null,
                            )
                            android.util.Log.i(
                                "PiPocketWebView",
                                "mermaid create ${SystemClock.uptimeMillis() - t0}ms (${code.length}B)",
                            )
                        }
                    },
                    )
                }
            }
        }
        if (onTap != null) {
            Box(modifier = Modifier.matchParentSize().clickable(onClick = onTap))
        }
    }
}

/** 只做两件事：把代码交给 mermaid 画出来；把图的**自然尺寸**报回原生。 */
internal fun mermaidHtml(
    code: String,
    light: Boolean,
    fontSizeCss: String = "14px",
    maxHeightCss: Float = MERMAID_MAX_HEIGHT_DP,
): String {
    val escaped = code
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
    return """
<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<style>
  /* ⚠️ 别给 html/body/#box 定 height:100%：那样 scrollHeight 会被**视口高**撑起来，
     量高就成了自己喂自己（真机上表现：图被从中间开始显示、上面半张不见了）。 */
  html,body{margin:0;padding:0;background:transparent;}
  pre.mermaid{margin:0;padding:0;font:inherit;white-space:normal;}
  #box{display:flex;justify-content:center;}
  /* svg 的实际宽高由 JS 按**自然尺寸**写死；max-width:100% 只在「视口还没被原生拉宽」的
     过渡帧兜底 —— 原生拿到尺寸后会把 WebView 拉到自然宽，字就回到 1:1。 */
  svg{max-width:100%;height:auto;display:block;}
</style>
</head><body>
<div id="box"><pre class="mermaid">$escaped</pre></div>
<script src="mermaid.min.js"></script>
<script>
(function () {
  var LIGHT = ${if (light) "true" else "false"};
  // 字号：**由原生按正文字号传进来**（fontBodySp 的 sp × fontScale），见 mermaidFontSizeCss；
  // flowchart 的 padding/nodeSpacing/rankSpacing 收紧，长图更紧凑；
  // wrappingWidth 180（默认 200）：长标签强制换行 ⇒ 图的**自然宽**变窄，宽图少一点横向滚动。
  var COMMON_VARS = {
    fontSize: '$fontSizeCss',
    fontFamily: 'system-ui, -apple-system, sans-serif'
  };
  // 浅色口径：节点浅紫底 + 深紫描边，配 app 的紫强调色（TerminalColors.rule()/inlineCode() 同色相）。
  // 深色继续用 mermaid 自带 dark（终端本来就是 dark，配色不用动）。
  var LIGHT_VARS = {
${if (light) LIGHT_THEME_VARS else ""}
  };
  function fail() { try { PiPocket.reportError(); } catch (e) {} }
  /**
   * 量**自然尺寸**并报回原生：mermaid 写在 svg 上的 viewBox 就是布局的真实宽高（单位 = CSS px），
   * **与视口宽无关** ⇒ 内联量到的值拿到全屏也成立。
   *
   * 老方案量的是「高 / 视口宽」这个**跟视口宽相关**的比例，换个宽度就不成立（真机：全屏上下对称
   * 被切）；而且宽图被缩到容器宽会把字号一起缩小。现在：图比视口宽就写死自然宽，原生随后把
   * WebView 拉到自然宽、外层横向滚，**字始终 1:1**；只有畸形长图（> MAX_H）才整体等比缩。
   */
  var MAX_H = $maxHeightCss;
  var lastW = -1, lastH = -1;
  function report() {
    try {
      // 贴顶渲染：WebView 内部一旦有滚动（内容比盒子高一点点就会被拖走），
      // 图看起来就是「被切了」（真机截图：上半张整个不见）。每次量完都拉回 0。
      window.scrollTo(0, 0);
      var box = document.querySelector('#box');
      var svg = box && box.querySelector('svg');
      if (!svg) return;
      var vb = svg.viewBox && svg.viewBox.baseVal;
      if (!vb || !(vb.width > 0) || !(vb.height > 0)) return;
      var nw = vb.width, nh = vb.height;
      // 实测高**只往上取**（含藏在 svg 外面、viewBox 量不到的东西：比如 <pre> 的边距）：
      // 按当前渲染比例折算回自然宽下，宁可多留几 px，绝不切。
      var rect = svg.getBoundingClientRect();
      var k = rect.width > 0.5 ? nw / rect.width : 1;
      var measured = Math.max(rect.height * k,
                              (document.documentElement.scrollHeight || 0) * k,
                              (box.scrollHeight || 0) * k);
      if (measured > nh) nh = measured;
      var s = nh > MAX_H ? MAX_H / nh : 1;
      var w = nw * s, h = nh * s;
      svg.setAttribute('width', String(w));
      svg.setAttribute('height', String(h));
      svg.style.maxWidth = '100%';
      svg.style.height = 'auto';
      if (w === lastW && h === lastH) return;   // 幂等：值没变不重复报
      lastW = w; lastH = h;
      var vw = document.documentElement.clientWidth || window.innerWidth || 0;
      PiPocket.reportSize(w, h, window.devicePixelRatio || 0, vw);
    } catch (e) { /* 量不到就不报，原生那边保持上一次的值 */ }
  }
  try {
    mermaid.initialize({
      startOnLoad: false,
      securityLevel: 'strict',
      theme: LIGHT ? 'base' : 'dark',
      themeVariables: Object.assign({}, COMMON_VARS, LIGHT ? LIGHT_VARS : {}),
      flowchart: { useMaxWidth: true, padding: 10, nodeSpacing: 28, rankSpacing: 34, wrappingWidth: 180 }
    });
    mermaid.run({ querySelector: '.mermaid' }).then(function () {
      if (!document.querySelector('#box svg')) { fail(); return; }
      report();
      // 中文字体后到时标签可能重排 ⇒ 再量两次（量的是幂等的自然尺寸，值不变不重复报）
      try { if (document.fonts && document.fonts.ready) document.fonts.ready.then(report); } catch (e) {}
      setTimeout(report, 300);
      setTimeout(report, 900);
    }).catch(fail);
  } catch (e) { fail(); }
})();
</script>
</body></html>
""".trimIndent()
}
