package id.harness.pipocket

import org.json.JSONObject

/**
 * 手机当前这块屏 + 排版参数（用户 2026-10-23：要让 agent 知道屏幕多大、正文字号多少，
 * 才能画出"适合手机上看的"内容，而不是按桌面宽度画一张手机上塞不下的图）。
 *
 * 上报路径：app → (POST /v1/device) → 桥落盘 → 桥把这段拼进注入给 pi 的提示里。
 * 只有 [signature] 变了才重发（旋转/改字号/换主题），不做定时心跳。
 */
data class DeviceInfo(
    val screenWdp: Int,
    val screenHdp: Int,
    val screenWpx: Int,
    val screenHpx: Int,
    val density: Float,
    val densityDpi: Int,
    /** 系统字体缩放 × app 全局字号倍率 */
    val fontScale: Float,
    /** "dark" / "light" */
    val theme: String,
    val bodySp: Int,
    val toolNameSp: Int,
    val toolSp: Int,
    val metaSp: Int,
    val inputSp: Int,
    val chatScale: Float,
    /** 对话正文可用宽度（屏幕宽 - 左右留白） */
    val contentWidthDp: Int,
    /** html-visual 内联最大高度（只是防跑飞的安全上限；正常按内容铺开） */
    val htmlInlineMaxDp: Int,
) {

    fun toJson(): JSONObject = JSONObject().apply {
        put("screenWdp", screenWdp)
        put("screenHdp", screenHdp)
        put("screenWpx", screenWpx)
        put("screenHpx", screenHpx)
        put("density", density.toDouble())
        put("densityDpi", densityDpi)
        put("fontScale", fontScale.toDouble())
        put("theme", theme)
        put("bodySp", bodySp)
        put("toolNameSp", toolNameSp)
        put("toolSp", toolSp)
        put("metaSp", metaSp)
        put("inputSp", inputSp)
        put("chatScale", chatScale.toDouble())
        put("contentWidthDp", contentWidthDp)
        put("htmlInlineMaxDp", htmlInlineMaxDp)
    }

    /**
     * 去重签名。故意**不含**任何"每次都会变"的东西（时间戳之类），
     * 这样重组/重连都不会把桥的 pi 进程反复回收。
     */
    fun signature(): String = listOf(
        screenWdp, screenHdp, screenWpx, screenHpx, densityDpi, theme,
        bodySp, toolNameSp, toolSp, metaSp, inputSp,
        contentWidthDp, htmlInlineMaxDp,
        "%.2f".format(fontScale), "%.2f".format(chatScale), "%.3f".format(density),
    ).joinToString("|")

    companion object {
        /** html-visual 内联的绝对上限（dp）：超过就把内容截到这么高并给出全屏入口。 */
        const val INLINE_HTML_MAX_DP = 2400

        /** 从屏幕参数算出 app 侧的几个派生值（纯函数，好测）。 */
        fun of(
            screenWdp: Int,
            screenHdp: Int,
            density: Float,
            densityDpi: Int,
            fontScale: Float,
            theme: String,
            bodySp: Int,
            toolNameSp: Int,
            toolSp: Int,
            metaSp: Int,
            inputSp: Int,
            chatScale: Float,
        ): DeviceInfo {
            val w = screenWdp.coerceAtLeast(1)
            val h = screenHdp.coerceAtLeast(1)
            val d = density.coerceAtLeast(0.1f)
            return DeviceInfo(
                screenWdp = w,
                screenHdp = h,
                screenWpx = Math.round(w * d).coerceAtLeast(1),
                screenHpx = Math.round(h * d).coerceAtLeast(1),
                density = d,
                densityDpi = densityDpi,
                fontScale = fontScale,
                theme = theme,
                bodySp = bodySp,
                toolNameSp = toolNameSp,
                toolSp = toolSp,
                metaSp = metaSp,
                inputSp = inputSp,
                chatScale = chatScale,
                // 消息区左右各 16dp（ChatRows 的 padding）；比浏览器窄不少，表格列数要按这个算
                contentWidthDp = (w - 32).coerceAtLeast(120),
                // 内联 html 整段铺开（不嵌套滚动）；这个值只是防跑飞的上限
                htmlInlineMaxDp = INLINE_HTML_MAX_DP,
            )
        }
    }
}
