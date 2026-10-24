package id.harness.pipocket.ui

/**
 * 连接自恢复的**纯逻辑**（可单测，不碰 Android）。
 *
 * 背景（用户 2026-10-22）：「app 老是掉桥，每次都要点强制拉起 pi」。
 * 证据：`~/.pi/agent/pi-pocket-start.log` 里 84 次拉起，每次都写着
 * 「已有桥在跑且 token 匹配（HTTP 200），不重启」⇒ **桥没死，是 app 自己放弃了重连**：
 * 老代码在 SSE 断开后只补一次 5s 重试，那次失败（切后台/锁屏/切网都会）就永远停在 Failed。
 *
 * 现在：指数退避无限重试 + 本机桥自动拉起。这里只放两个判定，方便测。
 */

/** 退避表（毫秒）：1s → 2s → 4s → 8s → 15s → 30s 封顶。 */
internal val RECONNECT_DELAYS_MS = longArrayOf(1_000, 2_000, 4_000, 8_000, 15_000, 30_000)

/**
 * 第 [failures] 次失败之后该等多久（`failures` 从 1 开始）。
 *
 * 用**封顶**而不是无限增长：手机场景下网络恢复是分钟级的，等到 5 分钟毫无意义。
 */
internal fun reconnectDelayMs(failures: Int): Long =
    RECONNECT_DELAYS_MS[(failures - 1).coerceIn(0, RECONNECT_DELAYS_MS.lastIndex)]

/**
 * 连续失败到几次才该**自动拉起本机桥**。
 *
 * 3 次 ≈ 1+2+4 = 7 秒：足够穿过一次网络抖动/锁屏瞬间，又不至于让用户干等。
 */
internal const val AUTO_LAUNCH_AFTER_FAILURES = 3

/** 自动拉起的最小间隔（毫秒）：连不上时别每 30 秒就 spawn 一次 Termux。 */
internal const val AUTO_LAUNCH_MIN_INTERVAL_MS = 90_000L

/**
 * 启动时要不要「回落到别的机器」——用户口径 2026-10-24：**选定的机器优先**。
 *
 * 以前一律“谁先应答就用谁”（`probeKnown().firstOrNull()`），远端探不通时手机就连回本机，
 * 还把 `baseUrl` 覆写成 `127.0.0.1` ⇒ 看起来就是「切了机器又被踢回本机」。
 * 现在：记住的是**远端**机器 ⇒ 只认它，连不上就显示离线并重试（不换机器、不改地址）；
 * 记住的是本机（或空） ⇒ 保留原来的自动发现（首次启动 / 本机桥没起来时还要能自愈）。
 */
internal fun sticksToChosenMachine(savedUrl: String): Boolean =
    savedUrl.isNotBlank() && !isLocalBridgeUrl(savedUrl)

/**
 * 这个地址是不是「本机桥」（只有本机才自动拉起 —— 远端机器归用户管）。
 *
 * 认：`127.0.0.1` / `localhost` / `::1`。
 * ⚠️ **不认 `10.0.2.2`**：那是 Android 模拟器里的宿主机别名（QA 用），
 * 在模拟器上自动「拉起 Termux」只会弹一句“没装 Termux”，纯噪音。
 */
internal fun isLocalBridgeUrl(url: String): Boolean {
    val authority = url.substringAfter("://", url).substringBefore('/')
    // 去掉 userinfo（http://user:pass@host:port 这种理论上不该出现，防御一下）
    val hostPort = authority.substringAfterLast('@')
    val host = when {
        hostPort.startsWith("[") -> hostPort.substringAfter('[').substringBefore(']')   // [::1]:8789
        hostPort.count { it == ':' } >= 2 -> hostPort                                    // 裸 IPv6
        else -> hostPort.substringBefore(':')
    }
    return host in setOf("127.0.0.1", "localhost", "::1")
}

/**
 * 顶部状态文案：区分「正在自己恢复」和「真连不上」。
 *
 * 用户口径：重连是常态动作，不该用「未连接」这种大字吓人 —— 也提示用户不用动手。
 */
internal fun connectionLabel(state: String, failures: Int, machine: String): String = when (state) {
    "ok" -> "已连接 · $machine"
    "failed" -> if (failures > 0) "重连中（$failures） · $machine" else "未连接 · $machine"
    else -> "连接中 · $machine"
}
