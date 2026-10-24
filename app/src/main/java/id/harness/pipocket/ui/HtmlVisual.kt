package id.harness.pipocket.ui

import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import id.harness.pipocket.R
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * ```` ```html-visual ```` —— app 内置作图技能的第二条通道（ZCode `visualize` 口径的交互式图）。
 *
 * 约定（写进了桥注入的 append-system.md，模型那边照这个交付）：
 * · 内容是 **HTML 片段**（没有 doctype/html/head/body），app 负责包进沙箱 WebView；
 * · **允许 CDN 图表库**（Chart.js / D3 / ECharts 等，来自 jsdelivr / unpkg / cdnjs），但 **fetch/XHR 仍被 CSP 拦掉**
 *   （`connect-src https: http: data: blob:`）——模型画的图能用库，但拿不到外网数据；
 * · 高度由片段自己长（JS 回报 scrollHeight），app 按回报值给高度，不写死也不留大片空白。
 *
 * 渲染不出来（空片段/JS 报错到没内容）就退回原代码块，不留白。
 */
@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
fun HtmlVisualBlock(code: String, fallback: @Composable () -> Unit) {
    var heightDp by remember(code) { mutableStateOf(180.dp) }
    var failed by remember(code) { mutableStateOf(false) }
    val main = remember { Handler(Looper.getMainLooper()) }
    // 明暗在组合期取好（isDark() 读主题，不能进 WebView 的 factory lambda）
    val light = !TerminalColors.isDark()
    // 内联高度 = **内容真实高度**（用户 2026-10-23：正文里的 html 底部被截了）。
    // 之前是"限高 + WebView 内部滚动"，但嵌套滚动在聊天流里很难发现（手指一扫滑的是列表），
    // 用户看到的就是"底部没了"。现在干脆整段铺开，只是给个 2400dp 的安全上限防跑飞。
    val density = LocalDensity.current
    val maxInlineDp = 2400

    if (failed || code.isBlank()) {
        fallback()
        return
    }

    // 全屏查看（用户 2026-10-23）：图表在气泡里只能看个大概，点开占满屏；还能切横屏看宽图。
    var fullscreen by remember(code) { mutableStateOf(false) }

    // 延迟 + 串行建 WebView（见 WebViewGate）：一屏多个 html 块时不再把主线程按死（ANR 根因）。
    DeferWebView(key = code, placeholderHeight = heightDp) {
    Box(modifier = Modifier.fillMaxWidth()) {
    AndroidView(
        modifier = Modifier.fillMaxWidth().height(heightDp.coerceAtMost(maxInlineDp.dp)),
        factory = { ctx ->
            WebView(ctx).apply {
                setBackgroundColor(0x00000000)
                settings.javaScriptEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.blockNetworkLoads = false
                addJavascriptInterface(
                    object {
                        @JavascriptInterface
                        fun reportHeight(px: Int) {
                            main.post {
                                if (px > 0) heightDp = htmlVisualHeightDp(px, maxInlineDp).dp
                            }
                        }

                        @JavascriptInterface
                        fun reportEmpty() {
                            main.post { failed = true }
                        }
                    },
                    "PiPocket",
                )
                loadDataWithBaseURL(
                    null,
                    htmlVisualPage(code, light = light),
                    "text/html",
                    "utf-8",
                    null,
                )
            }
        },
    )
    // 全屏入口：右上角一个小圆钮（不抢内容、不占高度）
    Surface(
        onClick = { fullscreen = true },
        shape = CircleShape,
        color = TerminalColors.muted().copy(alpha = 0.22f),
        contentColor = TerminalColors.body(),
        modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(26.dp),
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(R.drawable.ic_expand),
                contentDescription = "全屏查看",
                modifier = Modifier.size(15.dp),
            )
        }
    }
    }
    }

    if (fullscreen) HtmlVisualFullscreen(code = code, onDismiss = { fullscreen = false })
}

/**
 * html-visual 全屏查看器（用户 2026-10-23：「渲染的 html 支持全屏，并支持切换方向」）。
 *
 * · 占满屏（`usePlatformDefaultWidth = false`），内容按视口铺开，纵向自己滚；
 * · 「横屏 / 竖屏」按钮直接改 Activity 的 `requestedOrientation` —— 宽图（时间轴、宽表）横过来才看得清；
 *   退出时恢复成 `UNSPECIFIED`，不把 app 的旋转策略改死；
 * · 顺手开双指缩放（`setSupportZoom`）：看不清的细节能放大。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun HtmlVisualFullscreen(code: String, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val activity = remember(ctx) { ctx.findActivity() }
    val light = !TerminalColors.isDark()
    var landscape by remember { mutableStateOf(false) }
    // 旋转后 WebView 的视口变了，但里面的图（Chart.js/ECharts）不会自己重排 ⇒ 按新视口整页重画一次。
    // 不做这件事，横屏里还是竖屏那张小图，四周全是空白（实测）。
    var webView by remember { mutableStateOf<WebView?>(null) }
    var lastSize by remember { mutableStateOf(0 to 0) }
    // 退出全屏（含返回键 / 划掉）时把旋转交还给系统，并释放这个 WebView
    DisposableEffect(Unit) {
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            runCatching { webView?.destroy() }
            webView = null
        }
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "html-visual",
                        style = MaterialTheme.typography.labelLarge,
                        color = TerminalColors.muted(),
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = {
                        landscape = !landscape
                        activity?.requestedOrientation = if (landscape) {
                            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                        } else {
                            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                        }
                    }) { Text(if (landscape) "竖屏" else "横屏") }
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }
                AndroidView(
                    modifier = Modifier.fillMaxSize().onSizeChanged { box ->
                        if (box.width <= 0 || box.height <= 0) return@onSizeChanged
                        val size = box.width to box.height
                        val prev = lastSize
                        lastSize = size
                        // 第一次布局不重画（那时页面刚加载），之后尺寸变了才重画 —— 避免死循环
                        if (prev.first > 0 && prev != size) runCatching { webView?.reload() }
                    },
                    factory = { c ->
                        WebView(c).apply {
                            webView = this
                            setBackgroundColor(0x00000000)
                            settings.javaScriptEnabled = true
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            settings.blockNetworkLoads = false
                            settings.setSupportZoom(true)
                            settings.builtInZoomControls = true
                            settings.displayZoomControls = false
                            loadDataWithBaseURL(
                                null,
                                htmlVisualPage(code, fullscreen = true, light = light),
                                "text/html",
                                "utf-8",
                                null,
                            )
                        }
                    },
                )
            }
        }
    }
}

/** 从 Compose 的 Context 往上找 Activity（改 `requestedOrientation` 必须用 Activity）。 */
private fun android.content.Context.findActivity(): android.app.Activity? {
    var c = this
    while (c is android.content.ContextWrapper) {
        if (c is android.app.Activity) return c
        c = c.baseContext
    }
    return null
}

/**
 * WebView 回报的内容高度（**CSS px**）→ app 的 dp。
 *
 * **不要再除以 density**：宿主页写了 `<meta name="viewport" content="width=device-width, initial-scale=1">`，
 * WebView 的设备缩放比就是 density，所以 1 CSS px 已经等于 1 dp。之前除了一次（模拟器 1.75、手机 4），
 * 结果块高度只有内容的 1/1.75 / 1/4 ⇒ 手机上 html 只露出顶部一小条（用户 2026-10-23 两次截图报的就是这个）。
 */
internal fun htmlVisualHeightDp(cssPx: Int, maxDp: Int): Int =
    cssPx.coerceIn(48, maxDp.coerceAtLeast(48))

/**
 * 包一层：CSP 断网 + 主题跟随 app（透明底、继承文字色）+ 量高回报。
 *
 * [fullscreen] = 全屏查看：这时不回报高度（视口就是高度，内容自己滚），并给一点内边距。
 */
internal fun htmlVisualPage(
    fragment: String,
    fullscreen: Boolean = false,
    light: Boolean = false,
): String = """
<!doctype html><html><head><meta charset="utf-8">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline' https://cdn.jsdelivr.net https://unpkg.com https://cdnjs.cloudflare.com; script-src 'unsafe-inline' 'self' file: https://cdn.jsdelivr.net https://unpkg.com https://cdnjs.cloudflare.com https://fastly.jsdelivr.net; img-src data: https:; font-src data: https://cdn.jsdelivr.net; connect-src 'none'">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<style>
  html, body { margin: 0; padding: 0; background: transparent;
    color: ${if (light) "#2F3437" else "#e6e4e0"};
    font: 14px/1.5 system-ui, sans-serif; }
  * { max-width: 100%; }
  ${if (fullscreen) "html, body { height: 100%; } #pi-pocket-root { padding: 12px; box-sizing: border-box; }" else ""}
  table { border-collapse: collapse; }
  td, th { border: 1px solid ${if (light) "#C2C9CC" else "#4a4a4a"}; padding: 4px 8px; font-size: 12px; }
</style>
</head><body>
<div id="pi-pocket-root">
$fragment
</div>
<script>
(function () {
  var FULLSCREEN = ${if (fullscreen) "true" else "false"};
  var LIGHT = ${if (light) "true" else "false"};

  /**
   * 浅色模式下的**自动对比度**（用户 2026-10-23 第二轮反馈：白字落在彩条上被压成灰、看不清）。
   *
   * 之前的做法是"凡是偏亮的颜色一律压暗"——这只对「浅色底 + 浅色字」有效，
   * 但语义色块里的白字（`color:#fff` on 深红条）也被压成灰了，反而更糟。
   * 现在按**每个元素自己的背景**算：
   *   · 背景亮（白/浅）而字太浅 → 压暗；
   *   · 背景暗（彩条）而字太暗 → 提亮；
   *   · 已经 ≥4.2:1 → 不动。
   * 半透明背景按 50% 以上近似为不透明，更淡的就往上找父级（渐变/阴影没法算，跳过）。
   */
  function lin(v) { v /= 255; return v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4); }
  function inv(v) { v = Math.max(0, Math.min(1, v)); return Math.round(255 * (v <= 0.0031308 ? v * 12.92 : 1.055 * Math.pow(v, 1 / 2.4) - 0.055)); }
  function lum(p) { return 0.2126 * lin(p[0]) + 0.7152 * lin(p[1]) + 0.0722 * lin(p[2]); }
  function ratio(a, b) {
    var la = lum(a), lb = lum(b);
    var hi = Math.max(la, lb), lo = Math.min(la, lb);
    return (hi + 0.05) / (lo + 0.05);
  }
  function parse(color) {
    var m = String(color || '').match(/rgba?\(([^)]+)\)/);
    if (!m) return null;
    var p = m[1].split(',').map(Number);
    if (p.length < 3) return null;
    return { rgb: [p[0], p[1], p[2]], a: p.length > 3 ? p[3] : 1 };
  }
  /** 往上找"实际底色"；白色当兜底（浅色模式页面是白底）。 */
  function bgOf(el) {
    var node = el;
    while (node && node.nodeType === 1) {
      var c = parse(getComputedStyle(node).backgroundColor);
      if (c && c.a >= 0.5) return c.rgb;
      node = node.parentElement;
    }
    return [255, 255, 255];
  }
  /** 把颜色沿"往黑/往白"方向等比缩放，直到对比度够（保持色相）。 */
  function fit(fg, bg, minRatio) {
    if (ratio(fg, bg) >= minRatio) return null;
    var bgL = lum(bg);
    // 0.179 是"黑字和白字对比度相等"的分界点：往上压暗、往下提亮。
    // 之前用 0.35 当阈值，中绿底(#3fb27f, L≈0.34)被当成暗底 ⇒ 白字原样保留 ⇒ 2.5:1 看不清。
    var target = bgL > 0.179
      ? (bgL + 0.05) / minRatio - 0.05      // 亮底：字要更黑
      : minRatio * (bgL + 0.05) - 0.05;     // 暗底：字要更白
    target = Math.max(0, Math.min(1, target));
    var curL = lum(fg);
    if (curL <= 0.0001) return [255, 255, 255];
    var k = target / curL;
    return [inv(lin(fg[0]) * k), inv(lin(fg[1]) * k), inv(lin(fg[2]) * k)];
  }
  function fixAll() {
    if (!LIGHT) return;
    try {
      var root = document.getElementById('pi-pocket-root');
      if (!root) return;
      var nodes = root.querySelectorAll('[style*="color"]');
      for (var i = 0; i < nodes.length; i++) {
        var el = nodes[i];
        var cs = getComputedStyle(el);
        var c = parse(cs.color);
        if (!c) continue;
        var bg = bgOf(el);
        var next = fit(c.rgb, bg, 4.2);
        if (next) el.style.color = 'rgb(' + next[0] + ',' + next[1] + ',' + next[2] + ')';
      }
      // SVG 里用 fill 的（mermaid 之外的图）
      var fills = root.querySelectorAll('[fill]');
      for (var j = 0; j < fills.length; j++) {
        var raw = fills[j].getAttribute('fill');
        if (!raw || raw === 'none' || raw.indexOf('url(') === 0) continue;
        var rgb = parse(raw);
        if (!rgb) {
          var hex = /^#([0-9a-f]{3}|[0-9a-f]{6})$/i.exec(raw);
          if (!hex) continue;
          var h = hex[1];
          if (h.length === 3) h = h[0] + h[0] + h[1] + h[1] + h[2] + h[2];
          rgb = { rgb: [parseInt(h.slice(0, 2), 16), parseInt(h.slice(2, 4), 16), parseInt(h.slice(4, 6), 16)], a: 1 };
        }
        var parentBg = bgOf(fills[j].parentElement || root);
        var nf = fit(rgb.rgb, parentBg, 3.0);
        if (nf) fills[j].setAttribute('fill', 'rgb(' + nf[0] + ',' + nf[1] + ',' + nf[2] + ')');
      }
    } catch (e) { /* 调色失败不影响内容 */ }
  }

  /**
   * canvas 里画的字（Chart.js 的刻度/图例）没法用 DOM 改色 ⇒ 改库默认值再让它重画。
   * 图表底色在浅色模式下就是 app 的白/浅底，所以统一给深灰。
   */
  function fixCharts() {
    if (!LIGHT || !window.Chart) return;
    try {
      var CH = window.Chart;
      var DARK = '#2F3437';
      if (CH.defaults) CH.defaults.color = DARK;
      var inst = CH.instances || {};
      Object.keys(inst).forEach(function (k) {
        var c = inst[k];
        try {
          var o = c.options || {};
          if (o.plugins && o.plugins.legend && o.plugins.legend.labels) o.plugins.legend.labels.color = DARK;
          if (o.scales) Object.keys(o.scales).forEach(function (name) {
            var sc = o.scales[name] || {};
            sc.ticks = sc.ticks || {};
            sc.ticks.color = DARK;
            if (sc.title) sc.title.color = DARK;
            if (sc.pointLabels) sc.pointLabels.color = DARK;
          });
          c.update('none');
        } catch (e) { /* 单张图失败不影响别的 */ }
      });
    } catch (e) { /* 没有 Chart.js 就算了 */ }
  }

  function afterRender() { fixAll(); fixCharts(); if (!FULLSCREEN) report(); }
  if (FULLSCREEN) {
    // 全屏没有量高这一步，但同样要修浅色对比；图是异步画的，所以几帧后再来一次
    window.addEventListener('load', function () {
      afterRender();
      setTimeout(afterRender, 300);
      setTimeout(afterRender, 1200);
    });
    return;
  }
  var lastH = -1;
  function report() {
    try {
      var h = Math.ceil(Math.max(
        (document.body && document.body.scrollHeight) || 0,
        document.documentElement.scrollHeight || 0
      ));
      var root = document.getElementById('pi-pocket-root');
      var text = root ? (root.innerText || '').trim() : '';
      if (!root || (!text && root.children.length === 0)) { PiPocket.reportEmpty(); return; }
      // 只报**内容高度**（scrollHeight 含溢出部分）；app 侧再按屏幕夹一次，超了内部滚动。
      if (Math.abs(h - lastH) > 2) { lastH = h; PiPocket.reportHeight(h); }
    } catch (e) { PiPocket.reportEmpty(); }
  }
  // 图/表/字体是异步长出来的（CDN 图表、图片、webfont）：只靠 load + 两次定时会有"长高没报"⇒ 底部被截。
  // ResizeObserver 盯着内容，真长高了就再报一次。
  try {
    if (window.ResizeObserver) {
      new ResizeObserver(function () { if (!FULLSCREEN) report(); })
        .observe(document.getElementById('pi-pocket-root') || document.body);
    }
  } catch (e) { /* 老 WebView 没有 ResizeObserver：退回定时回报 */ }
  window.addEventListener('load', function () {
    afterRender();
    setTimeout(afterRender, 120);
    setTimeout(afterRender, 600);
  });
  window.addEventListener('error', function () {
    // 已经量到高度 = 内容画出来了；之后库内部的偶发异常（Chart.js 的 update 之类）不该把整块退回代码块
    if (lastH < 0) PiPocket.reportEmpty();
  });
})();
</script>
</body></html>
""".trimIndent()

/**
 * 工具结果的**终端原样渲染**块（继承 pi）。
 *
 * 桥用 pi 自己的 `renderResult` + ANSI→HTML 把这行工具结果渲染成一段 HTML（`ansi-line` 的 div，
 * 颜色/缩进/重排都是终端的），这里只负责把它放进一个受控 WebView：
 * · 等宽字体、`white-space: pre`（保住终端对齐）；
 * · 高度按内容长，**超过 maxHeight 就内部滚动**（而不是把卡片撑成整屏）；
 * · 透明底、跟随 app 深色。
 */
@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
fun ToolHtmlBlock(html: String, maxHeight: Int = 360) {
    var heightDp by remember(html) { mutableStateOf(48.dp) }
    var failed by remember(html) { mutableStateOf(false) }
    val main = remember { Handler(Looper.getMainLooper()) }
    val light = !TerminalColors.isDark()
    if (failed || html.isBlank()) {
        Text("（这条工具结果没有可渲染的内容）", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        return
    }
    // 工具结果同样是"一个块一个 WebView"：真实会话里一屏可能十几张，必须过闸门。
    DeferWebView(key = html, placeholderHeight = heightDp) {
    AndroidView(
        modifier = Modifier.fillMaxWidth().height(heightDp),
        factory = { ctx ->
            WebView(ctx).apply {
                setBackgroundColor(0x00000000)
                settings.javaScriptEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.blockNetworkLoads = true
                addJavascriptInterface(
                    object {
                        @JavascriptInterface
                        fun reportHeight(px: Int) {
                            main.post {
                                if (px > 0) {
                                    val dp = px / resources.displayMetrics.density
                                    heightDp = dp.coerceIn(24f, maxHeight.toFloat()).dp
                                }
                            }
                        }

                        @JavascriptInterface
                        fun reportEmpty() {
                            main.post { failed = true }
                        }
                    },
                    "PiPocket",
                )
                loadDataWithBaseURL(null, toolHtmlPage(html, maxHeight, light), "text/html", "utf-8", null)
            }
        },
    )
    }
}

/** 工具 HTML 的宿主页：等宽 + pre + 限高内滚 + 断网。 */
private fun toolHtmlPage(fragment: String, maxHeight: Int, light: Boolean): String = """
<!doctype html><html><head><meta charset="utf-8">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; img-src data:">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<style>
  html, body { margin: 0; padding: 0; background: transparent; color: ${if (light) "#33393b" else "#dcdcdc"};
    font: 11px/1.35 ui-monospace, monospace; }
  body { max-height: ${maxHeight}px; overflow: auto; }
  .ansi-line { white-space: pre; }
  #pi-pocket-root { padding: 0; }
</style>
</head><body>
<div id="pi-pocket-root">
$fragment
</div>
<script>
(function () {
  /**
   * 浅色模式：这段 HTML 是桥用 pi 的 **dark 主题** 渲的（灰白字），放在浅色工具卡里
   * 对比度只有 ~2:1（真机截图量出来的“看不清”）。这里把每个 span 的 inline 颜色
   * **在线性 RGB 里等比压暗**到对浅绿底 ≥4.6:1 —— 色相/彩度比例不变（diff 绿还是绿、红还是红）。
   * 与 Kotlin 侧 TerminalColors.ansiOnLight() 同一算法，两处必须一起改。
   */
  var LIGHT = ${if (light) "true" else "false"};
  var BG_L = 0.8244; // #DCEFE2 的相对亮度
  function lin(v) { v /= 255; return v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4); }
  function inv(v) { v = Math.max(0, Math.min(1, v)); return Math.round(255 * (v <= 0.0031308 ? v * 12.92 : 1.055 * Math.pow(v, 1 / 2.4) - 0.055)); }
  function fix(el) {
    var c = el.style && el.style.color;
    var m = c && c.match(/rgba?\(([^)]+)\)/);
    if (!m) return;
    var p = m[1].split(',').map(Number);
    if (p.length < 3) return;
    var L = 0.2126 * lin(p[0]) + 0.7152 * lin(p[1]) + 0.0722 * lin(p[2]);
    var target = (BG_L + 0.05) / 4.6 - 0.05;
    if (L <= target || L <= 0.0001) return;
    var frac = Math.max(0, Math.min(1, (L - target) / Math.max(0.001, 1 - target)));
    var newL = target * (0.55 + 0.45 * frac);
    var k = newL / L;
    el.style.color = 'rgb(' + inv(lin(p[0]) * k) + ',' + inv(lin(p[1]) * k) + ',' + inv(lin(p[2]) * k) + ')';
  }
  function recolor() {
    if (!LIGHT) return;
    try {
      var root = document.getElementById('pi-pocket-root');
      if (!root) return;
      var nodes = root.querySelectorAll('[style*="color"]');
      for (var i = 0; i < nodes.length; i++) fix(nodes[i]);
    } catch (e) { /* 重着色失败不影响内容 */ }
  }
  function report() {
    try {
      recolor();
      var root = document.getElementById('pi-pocket-root');
      var text = root ? (root.innerText || '').trim() : '';
      if (!root || (!text && root.children.length === 0)) { PiPocket.reportEmpty(); return; }
      PiPocket.reportHeight(Math.ceil(document.documentElement.scrollHeight || document.body.scrollHeight || 0));
    } catch (e) { PiPocket.reportEmpty(); }
  }
  window.addEventListener('load', function () { report(); setTimeout(report, 120); });
})();
</script>
</body></html>
""".trimIndent()
