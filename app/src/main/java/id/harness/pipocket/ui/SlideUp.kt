package id.harness.pipocket.ui

/**
 * 「按住主操作键往上滑」的手势判定（纯逻辑，JVM 单测见 SlideUpTrackerTest）。
 *
 * 用户 2026-10-22 口径：
 * · **非流式**：主操作键 = 发送（⏎）。点一下发送；按住上滑再松手也是发送。
 * · **流式中**：主操作键 = 发送（⏎，点一下 = 插到本轮 / steer）；**按住上滑 = 停止**，
 *   上滑后原地松手即触发停止。
 *
 * 判定/反馈细则（[progress] 给界面做**跟手**拉伸，armed 给颜色/图标/文字）：
 * · 位移只按**当前位置**算：往上累加、往下减少 —— 滑回原处就恢复（"回到原位置时变短、
 *   文字/颜色/符号都恢复"）；
 * · **不加迟滞带**：armed 用同一个阈值算 ⇒ **看到红/⏹ 就一定触发**，不会出现
 *   "看着已经变红、松手却没停"这种反直觉（破坏性动作尤其不能这样）。
 */
internal class SlideUpTracker(private val thresholdPx: Float) {

    var offsetUpPx = 0f
        private set

    var armed = false
        private set

    /** 0..1，上滑进度：界面用它做跟手拉伸（到阈值 = 1 = armed）。 */
    val progress: Float
        get() = if (thresholdPx <= 0f) 0f else (offsetUpPx / thresholdPx).coerceIn(0f, 1f)

    fun drag(deltaY: Float) {
        // deltaY < 0 = 手指往上
        offsetUpPx = (offsetUpPx - deltaY).coerceAtLeast(0f)
        armed = offsetUpPx >= thresholdPx
    }

    /** 松手：返回是否该触发（调用后自动复位）。 */
    fun release(): Boolean {
        val fire = armed
        reset()
        return fire
    }

    fun reset() {
        offsetUpPx = 0f
        armed = false
    }
}
