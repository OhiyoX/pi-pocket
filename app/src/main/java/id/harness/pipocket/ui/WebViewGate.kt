package id.harness.pipocket.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * WebView 创建的节流闸门（用户 2026-10-22 报 ANR 后加的）。
 *
 * **症状**：一屏出现多个 mermaid / html-visual / 工具结果块时，主线程一次要建好几个 WebView
 * （`AndroidView` 的 factory 在**组合 + 布局**里跑），每个冷启动几百毫秒到 2 秒，
 * 叠起来就把输入事件饿死 ⇒ 系统弹「Pocket Pi isn't responding」。
 * ANR 堆栈实证（dropbox `data_app_anr`）：
 *   `MermaidKt.MermaidView` → `AndroidComposeView.addAndroidView` → `AndroidViewHolder.onLayout`
 * 以及 `AndroidViewHolder.onLayout` → `WebView` 首次布局。
 *
 * **两条对策**：
 *  1. **延迟**：进组合后先等 [DELAY_MS]，等不到（快速滚动、列表复用）就根本不建。
 *  2. **串行**：全局同一时刻只允许一个"新 WebView"创建，间隔 [INTERVAL_MS]。
 *     一屏 8 张图 ⇒ 逐帧建，每帧只吃一次创建成本。
 *
 * **代价**：图/工具块比文字晚 100–200ms 出现。相比系统弹无响应，这个代价可接受。
 *
 * 口径：占位高度由调用方给（[DeferWebView] 的 `placeholderHeight`），
 * 保证"占位多大，之后内容就从多大开始长"，不让下方内容跳。
 */
object WebViewGate {

    /**
     * 预热 Chromium —— **目前未启用**（2026-10-22 试过，在模拟器上导致 WebView 渲染进程崩溃）。
     *
     * 试过的事实：用 `applicationContext` 建 WebView 会走到
     * `aw_browser_terminator: Renderer process crash detected (code -1)`，
     * 且那一次主线程卡了 12.2s（Davey）。WebView 官方也不建议用 application context。
     * 正确做法是拿 Activity context、在**界面空闲**时预热（而不是 onCreate 同步做）。
     * 那需要 Activity 生命周期配合，且真机收益未知（phone 上 Chromium 初始化本来就快），
     * 所以先不接 —— 保留这个函数作为记录，调用方看清楚再用。
     *
     * 以下为原设计说明：
     *
     * 实测（模拟器）：第一次 `WebView(ctx)` 要 5–10 秒（加载 Trichrome 库 + 起渲染进程），
     * 之后就降到百毫秒级。如果等到用户打开会话才创建，这几秒会落在**那一帧的布局**里
     * ⇒ 掉帧甚至 ANR（日志里 `Davey! duration=11762ms`）。
     * 放到启动阶段做，用户看到的只是"启动稍慢一点"，而不是"打开会话卡十秒"。
     *
     * 只创建、加载一个空页就丢掉：目的不是复用实例（每个图仍需自己的 WebView），
     * 而是把**库的初始化**提前。
     */
    fun warmUp(context: android.content.Context) {
        if (warmed) return
        warmed = true
        android.util.Log.i(TAG, "warmUp start")
        val t0 = android.os.SystemClock.uptimeMillis()
        runCatching {
            val w = android.webkit.WebView(context.applicationContext)
            w.settings.javaScriptEnabled = false
            w.loadDataWithBaseURL(null, "<html><body></body></html>", "text/html", "utf-8", null)
            w.postDelayed({
                runCatching {
                    (w.parent as? android.view.ViewGroup)?.removeView(w)
                    w.destroy()
                }
                android.util.Log.i(TAG, "warmUp done ${android.os.SystemClock.uptimeMillis() - t0}ms")
            }, 1_200)
        }.onFailure { android.util.Log.w(TAG, "warmUp 失败（不影响使用）: ${it.message}") }
    }

    private const val TAG = "PiPocketWebView"
    private var warmed = false
    /** 进入组合后等这么久再建（毫秒）。快速滑过的块因此完全不建。 */
    const val DELAY_MS = 120L

    /** 相邻两次创建之间的最小间隔（毫秒）。一帧 16ms，这里给足余量。 */
    const val INTERVAL_MS = 80L

    private val mutex = Mutex()
    private var lastCreatedAt = 0L

    /**
     * 排队拿"可以创建"的许可。**挂起**而非阻塞主线程：轮到自己前把主线程让给输入事件。
     * 用单调时钟（[android.os.SystemClock.uptimeMillis]）而不是墙钟：改时间不影响节流。
     */
    suspend fun awaitSlot() {
        mutex.withLock {
            val wait = lastCreatedAt + INTERVAL_MS - android.os.SystemClock.uptimeMillis()
            if (wait > 0) delay(wait)
            lastCreatedAt = android.os.SystemClock.uptimeMillis()
        }
    }
}

/**
 * 把"建 WebView"推迟到 [WebViewGate] 放行之后。
 *
 * [key] 变化的语义 = 内容变了（换代码 / 换主题 / 换字号）⇒ 重新走一遍延迟+排队。
 *
 * 注意：**不要**把 `remember` 的状态塞进 key 里跟它自己比较；key 只放"决定这段内容是什么"的值。
 */
@Composable
fun DeferWebView(
    key: Any?,
    placeholderHeight: Dp,
    modifier: Modifier = Modifier,
    placeholder: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    var ready by remember(key) { mutableStateOf(false) }
    LaunchedEffect(key) {
        delay(WebViewGate.DELAY_MS)
        WebViewGate.awaitSlot()
        ready = true
    }
    if (ready) {
        content()
    } else {
        Box(modifier = modifier.fillMaxWidth().height(placeholderHeight)) { placeholder() }
    }
}
