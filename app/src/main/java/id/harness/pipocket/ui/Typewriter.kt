package id.harness.pipocket.ui

/**
 * 流式输出的"逐字显现"（typewriter reveal）。
 *
 * 为什么要它：SSE 是按 delta 到的（真实机型上几十~几百毫秒一批），直接整块上屏就是"跳一下、
 * 停一下、再跳一下"（用户 2026-10-22：「streaming 不够流畅，要有逐字滚动输出动效」）。
 *
 * 设计取舍（重要，别改成固定速度）：
 *  · **固定每秒 N 字**在手机上必然追不上真实 token 速度，越跑越落后，最后"字还在慢慢吐，
 *    模型早就答完了"——比跳变更糟；
 *  · 所以速度按**积压量**决定：积压少时 1 字/帧（看着像在写），积压多时加速（最多 48 字/帧），
 *    保证"看起来在逐字写，但永远在 ~0.3 秒内追上真实内容"；
 *  · 积压超过 [REVEAL_BAIL_CHARS] 直接跳到最新（用户已经落后太多，动画没有意义）。
 *
 * 纯逻辑，方便单测；渲染在 `ChatScreen` 的 `TypewriterText`。
 */

/** 一次最多推进多少字（够快，又不会一帧闪完）。 */
internal const val REVEAL_MAX_STEP = 48

/** 积压超过这么多就不做动画了，直接显示全部（避免"字追不上模型"的观感）。 */
internal const val REVEAL_BAIL_CHARS = 1_200

/**
 * 这一帧该往前推几个字。
 *
 * @param backlog 还没显示的字数（`total - shown`）
 */
internal fun revealStep(backlog: Int): Int = when {
    backlog <= 0 -> 0
    // 1~3 字：正在写的感觉（60fps ≈ 60~180 字/秒，和人读速接近）
    backlog <= 12 -> 1
    backlog <= 40 -> 2
    backlog <= 120 -> 4
    backlog <= 400 -> 12
    else -> REVEAL_MAX_STEP
}

/**
 * 下一帧应该显示到第几个字。
 *
 * @param shown 当前显示长度
 * @param total 文本总长度
 */
internal fun nextReveal(shown: Int, total: Int): Int {
    if (shown >= total) return total
    val backlog = total - shown
    if (backlog > REVEAL_BAIL_CHARS) return total
    return (shown + revealStep(backlog)).coerceAtMost(total)
}

/**
 * 这条消息要不要做逐字动画。
 *
 * 只有**真正在流式的最后一条**才做：历史消息、非流式消息直接显示全文
 * （否则打开旧会话时每条都在"打字"，那是噪声）。
 */
internal fun shouldTypewrite(streaming: Boolean): Boolean = streaming
