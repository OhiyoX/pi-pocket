package id.harness.pipocket.ui

import org.json.JSONObject

/**
 * pi 的**行为开关**（终端 `/settings` 里的那几项）。
 *
 * 取值与语义全部照 pi 源码，不是猜的：
 *  · `steeringMode` / `followUpMode` = `"all"` | `"one-at-a-time"`
 *    （`core/agent-session.ts:1642`；说明文案取自 `components/settings-selector.ts:494-509`）
 *  · `autoCompactionEnabled` 在 `RpcSessionState` 里（`modes/rpc/rpc-types.ts:106`）
 *  · `autoRetryEnabled` **不在** session state 里 —— pi 存在 `~/.pi/agent/settings.json`
 *    的 `retry.enabled`（`core/settings-manager.ts:893`），桥读文件后一并回给 app
 *  · `abort_retry` = 中断正在倒计时的自动重试（终端里是倒计时中按 Esc）
 */
internal data class BehaviorState(
    val steeringMode: String = "one-at-a-time",
    val followUpMode: String = "one-at-a-time",
    val autoCompaction: Boolean = true,
    val autoRetry: Boolean = true,
    val isStreaming: Boolean = false,
    val isCompacting: Boolean = false,
    val pendingMessageCount: Int = 0,
    val messageCount: Int = 0,
    val maxRetries: Int? = null,
) {
    companion object {
        fun from(o: JSONObject) = BehaviorState(
            steeringMode = o.optString("steeringMode").ifBlank { "one-at-a-time" },
            followUpMode = o.optString("followUpMode").ifBlank { "one-at-a-time" },
            autoCompaction = o.optBoolean("autoCompaction", true),
            autoRetry = o.optBoolean("autoRetry", true),
            isStreaming = o.optBoolean("isStreaming"),
            isCompacting = o.optBoolean("isCompacting"),
            pendingMessageCount = o.optInt("pendingMessageCount"),
            messageCount = o.optInt("messageCount"),
            maxRetries = o.optJSONObject("retry")?.let { r ->
                if (r.has("maxRetries") && !r.isNull("maxRetries")) r.optInt("maxRetries") else null
            },
        )
    }
}

/** 两个模式的取值（顺序也照 pi 的 `values` 数组：`one-at-a-time` 在前）。 */
internal val QUEUE_MODES = listOf("one-at-a-time", "all")

/**
 * 模式 → 中文标签。措辞照 pi 的说明（“送一条，等回复” / “一起送”）。
 */
internal fun queueModeLabel(mode: String): String = when (mode) {
    "all" -> "一起送"
    else -> "一次一条"
}

/** 模式 → 一句话解释（对应 pi settings 里的 description）。 */
internal fun queueModeHint(mode: String, followUp: Boolean): String = when (mode) {
    "all" -> if (followUp) {
        "排队的话一起送去（agent 停下后全部执行）"
    } else {
        "排队的话一起插进本轮，不等上一条回复"
    }
    else -> if (followUp) {
        "一次送一条，等 agent 回完再送下一条"
    } else {
        "一次插一条，等它回完再插下一条"
    }
}

/**
 * 「正在自动重试」的提示文案：终端是 `Retrying (1/3) in 4s... (Esc to cancel)`。
 *
 * app 里没有 Esc，改成同一句话 + 一个「中断」按钮（调 `abort_retry`）。
 */
internal fun retryBanner(attempt: Int, maxAttempts: Int, delayMs: Long): String {
    val seconds = (delayMs / 1000.0)
    val shown = if (seconds >= 10) seconds.toInt().toString() else String.format("%.1f", seconds)
    return "重试中（$attempt/$maxAttempts），${shown}s 后自动重来"
}
