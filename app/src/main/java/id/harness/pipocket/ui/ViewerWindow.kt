package id.harness.pipocket.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * 查看器打开期间隐藏系统栏（沉浸式），关掉再还原。
 *
 * 2026-10-24 实测结论：**只有应用自己的窗口**能真正隐藏系统栏。
 * 在 Compose `Dialog` 的独立窗口上，`WindowInsetsControllerCompat.hide()` 与
 * `FLAG_FULLSCREEN` 都不生效（dumpsys 里那个 dialog 窗口 `vsysui`/`fl=` 完全没变，试过两次）。
 * 所以两个查看器都从 Dialog 改成「根布局上层 overlay」，这里操作的就是 Activity 窗口。
 */
@Composable
internal fun ImmersiveSystemBars() {
    val view = LocalView.current
    val activity = view.context.findActivity()
    DisposableEffect(activity) {
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }
}

private fun Context.findActivity(): Activity? {
    var cur: Context? = this
    while (cur is ContextWrapper) {
        if (cur is Activity) return cur
        cur = cur.baseContext
    }
    return null
}
