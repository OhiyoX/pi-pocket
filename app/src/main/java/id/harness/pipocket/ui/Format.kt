package id.harness.pipocket.ui

/** UI 侧的小工具：路径缩写、相对时间、token 数字格式化。 */

fun shortenPath(path: String?): String {
    if (path.isNullOrBlank()) return ""
    val home = "/data/data/com.termux/files/home"
    return when {
        path == home -> "~"
        path.startsWith("$home/") -> "~" + path.removePrefix(home)
        path.length > 48 -> "…" + path.takeLast(47)
        else -> path
    }
}

fun relativeTime(iso: String?): String {
    if (iso.isNullOrBlank()) return ""
    return runCatching {
        val instant = java.time.Instant.parse(iso)
        val minutes = java.time.Duration.between(instant, java.time.Instant.now()).toMinutes()
        when {
            minutes < 1 -> "刚刚"
            minutes < 60 -> "${minutes} 分钟前"
            minutes < 60 * 24 -> "${minutes / 60} 小时前"
            minutes < 60 * 24 * 7 -> "${minutes / (60 * 24)} 天前"
            else -> iso.substring(0, 10)
        }
    }.getOrDefault(iso)
}

/**
 * token 数缩写 —— **逐档照抄 pi `components/footer.ts` 的 `formatTokens()`**：
 * ```
 * < 1e3        → "824"
 * < 1e4        → "12.3k"   （一位小数）
 * < 1e6        → "500k"    （取整！）
 * < 1e7        → "1.2M"    （一位小数）
 * else         → "12M"     （取整）
 * ```
 * 之前这里是"永远一位小数"（500.0k），跟终端不一致：同一个数字在 app 和终端显示不同，
 * 对账时看着像对不上。现在统一走这里。
 */
fun formatTokens(value: Long): String = when {
    value < 0 -> "0"
    value < 1_000 -> value.toString()
    value < 10_000 -> "%.1fk".format(value / 1_000.0)
    value < 1_000_000 -> Math.round(value / 1_000.0).toString() + "k"
    value < 10_000_000 -> "%.1fM".format(value / 1_000_000.0)
    else -> Math.round(value / 1_000_000.0).toString() + "M"
}

/**
 * 时间戳 → **设备本地时间** `yyyy-MM-dd HH:mm`。
 *
 * 为什么要有它：「关于」里的构建时间以前直接显示构建机 JVM 的时区（Termux 里没设 TZ ⇒ Java 落回
 * **UTC**），比本地慢 8 小时（用户 2026-10-22 报「安装时间要显示本地时间」）。现在构建只记 UTC
 * 瞬时，显示时一律走这里换成设备时区。
 */
/**
 * 缓存的时间格式器（**必须缓存**）。
 *
 * 以前每次调用都 `DateTimeFormatter.ofPattern(...)` + `ZoneId.systemDefault()`：
 * 在 Android 上这两步都不便宜（pattern 要编译、时区要查系统）。会话树一次要格式化
 * 2500 行 ⇒ 光这一项就吃掉好几秒（实测 3000 条的树 11s，缓存后见 `tools/tree-bench` 口径）。
 *
 * 时区缓存的风险：运行中改时区不会立刻生效（进程重启才刷新）。可接受——
 * 一次会话内换时区极罕见，而每行重新解时区的代价是实打实的。
 */
private val LOCAL_MINUTE_FMT: java.time.format.DateTimeFormatter by lazy {
    java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        .withZone(java.time.ZoneId.systemDefault())
}

fun localTimeLabel(iso: String?): String {
    val raw = iso?.trim().orEmpty()
    if (raw.isEmpty()) return ""
    val instant = runCatching { java.time.Instant.parse(raw) }.getOrNull()
        ?: runCatching { java.time.OffsetDateTime.parse(raw).toInstant() }.getOrNull()
        ?: return raw
    return LOCAL_MINUTE_FMT.format(instant)
}

/** 同上，入参是 epoch 毫秒（`PackageInfo.lastUpdateTime` 这类）。 */
fun localTimeLabel(millis: Long?): String {
    val ms = millis ?: return ""
    if (ms <= 0L) return ""
    return LOCAL_MINUTE_FMT.format(java.time.Instant.ofEpochMilli(ms))
}

/**
 * 「工作中」指示色按 thinking 等级走（用户 2026-10-22 口径：跟终端一致）。
 *
 * **等级 → 色族**是**逆向** pi 得来的（不是猜的）：`@earendil-works/pi-coding-agent` 的
 * `dist/modes/interactive/theme/system-theme.js.map` 里带 `sourcesContent`，还原出原始
 * `src/modes/interactive/theme/system-theme.ts`，里面逐档写着：
 *
 * ```
 * thinkingOff: "neutral"          thinkingHigh:   "thinkingViolet"    (hue 295,   sat 0.40~0.75)
 * thinkingMinimal: "thinkingSlate"      (hue 231.49, sat 0.08~0.20)
 * thinkingLow: "thinkingBlue"           (hue 231.49, sat 0.20~0.45)
 * thinkingMedium: "thinkingPeriwinkle"  (hue 263.25, sat 0.30~0.60)
 * thinkingXhigh: "thinkingMagenta"      (hue 337.5,  sat 0.50~0.85)
 * thinkingMax: "thinkingRed"            (hue 20,     sat 0.95~1)
 * ```
 *
 * pi 那边是在 OKHSL 里算、hue/saturation 取自**终端调色板的 ANSI slot** ⇒ 真值跟终端主题有关；
 * 这里按 family 的 hue + sat 区间中值推（亮度跟本 app 其它色齐平），端点两档直接用终端实测的
 * neutral（`muted`）与 red（`danger`）。还原脚本：`tools/pi-src-extract.mjs`（输出不提交）。
 *
 * 返回的是**色族 key**；真颜色在 `TerminalColors.thinkingFamily()` 里按深浅主题给。
 */
/**
 * 输入区要不要画「思考等级环」（pi 的 editor 边框 = `getThinkingBorderColor(level)`）。
 *
 * app 的输入区本来是**无边框**的填充式 pill（设计口径，"单一填充式输入框、无边框"）。
 * pi 用边框颜色告诉你"这一轮按什么等级思考"，这条信息在手机上也有用（尤其切完快切之后），
 * 所以折中：**只有非 off 等级才画一圈细环**——没有信息可传达时保持原来的无边框观感。
 *
 * @return true = 画环（颜色取 `TerminalColors.thinkingFamily(thinkingFamily(level))`）
 */
internal fun showThinkingRing(level: String?): Boolean = thinkingFamily(level) != "neutral"

internal fun thinkingFamily(level: String?): String = when (level?.trim()?.lowercase()) {
    null, "", "off", "none" -> "neutral"
    "minimal", "min" -> "slate"
    "low" -> "blue"
    "medium", "med" -> "periwinkle"
    "high" -> "violet"
    "xhigh", "x-high", "xhigh " -> "magenta"
    "max", "maximum", "ultra" -> "red"
    // pi 的 `getThinkingBorderColor()` default 分支就是回落到 thinkingOff ⇒ 这里照抄
    else -> "neutral"
}

/**
 * pi 终端 footer 的统计串（`components/footer.ts` 逐项照抄）：
 * `↑12.3k ↓4.1k R8.0k W1.2k CH42.3% $0.123`。
 *
 * 口径细节（全部来自源码，不猜）：
 *  · 每一项**为 0 就不出现**（`if (usageTotals.input) …`）；
 *  · 缓存命中率只在有读写时才给，保留一位小数（`latestCacheHitRate.toFixed(1)`）；
 *  · 花费三位小数（`$0.123`），0 且非订阅时整项省略。
 *
 * @param cacheHitPercent 最近一次 assistant 的 `cacheRead / (input+read+write)`；未知传 null。
 */
fun footerStats(
    input: Long,
    output: Long,
    cacheRead: Long,
    cacheWrite: Long,
    cacheHitPercent: Double?,
    cost: Double,
): String {
    val parts = mutableListOf<String>()
    if (input > 0) parts += "↑" + formatTokens(input)
    if (output > 0) parts += "↓" + formatTokens(output)
    if (cacheRead > 0) parts += "R" + formatTokens(cacheRead)
    if (cacheWrite > 0) parts += "W" + formatTokens(cacheWrite)
    if ((cacheRead > 0 || cacheWrite > 0) && cacheHitPercent != null) {
        parts += "CH" + "%.1f".format(cacheHitPercent) + "%"
    }
    if (cost > 0) parts += "$" + "%.3f".format(cost)
    return parts.joinToString(" ")
}

/**
 * 上下文那一段：`13.0%/500k` 或 `?/500k`，自动压缩开着再跟 `(auto)`。
 *
 * pi 的百分比固定一位小数（`toFixed(1)`）；拿不到百分比时是 `?` 而不是隐藏整段
 * （压缩过的会话常见 —— 与 app 平时那行"拿不到也照常显示"的口径一致）。
 */
fun footerContext(percent: Double?, window: Long, autoCompaction: Boolean): String {
    val head = if (percent == null) "?" else "%.1f".format(percent) + "%"
    val tail = if (window > 0) "/" + formatTokens(window) else ""
    return head + tail + if (autoCompaction) " (auto)" else ""
}

/**
 * 最近一次 assistant 的缓存命中率（%）——pi 用**最近一条**而不是累计值。
 *
 * @param input 该条的 `input`（不含缓存读写）
 */
fun cacheHitPercent(input: Long, cacheRead: Long, cacheWrite: Long): Double? {
    val prompt = input + cacheRead + cacheWrite
    if (prompt <= 0) return null
    return cacheRead.toDouble() / prompt * 100.0
}

/**
 * 解析输入框里的「bash 命令」写法（对齐终端的 `!cmd` / `!!cmd`）。
 *
 * 终端口径（`interactive-mode.ts` 的 bash mode）：
 *  · `!ls`  = 跑，结果**进上下文**；
 *  · `!!ls` = 跑，结果**不进上下文**（只是看一眼）；两个感叹号时命令从第 3 个字符开始。
 *  · 只有感叹号、后面没内容 ⇒ 不算（用户可能还要接着打）；返回 null。
 *
 * @return [BashInput] 或 null（null = 不是命令，照常当消息发）
 */
data class BashInput(val command: String, val excludeFromContext: Boolean)

fun parseBashInput(text: String): BashInput? {
    val exclude = text.startsWith("!!")
    if (!text.startsWith("!")) return null
    val body = text.removePrefix("!!").removePrefix("!")
    val command = body.trim()
    if (command.isEmpty()) return null
    return BashInput(command, exclude)
}

/**
 * 顶栏里的分支标记。终端是 `~/proj (main)`，手机上一行放不下就只留 `(main)`。
 * pi 在 detached HEAD 时给字符串 `detached`，这里显示成 `(detached)`（不隐藏：这不是"没有分支"）。
 */
fun gitBranchLabel(branch: String?): String? {
    val b = branch?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return "($b)"
}


/**
 * 模型显示名：**一律带 provider**（用户 2026-10-23：「上下文窗口的模型要带上 provider」）。
 *
 * 桥给的 `modelDisplay` 是终端 footer 口径（`id • thinking`），里面的 id 可能同名跨厂商
 * （`deepseek-v4-flash` 好几家都有）⇒ 只看 id 分不清是哪一家。这里统一拼成
 * `provider/model · thinking`；缺 provider 才退回 `modelDisplay`。
 */
internal fun modelWithProvider(
    provider: String,
    model: String,
    thinking: String = "",
    modelDisplay: String = "",
): String {
    if (provider.isBlank() || model.isBlank()) return modelDisplay.ifBlank { model.ifBlank { "—" } }
    val base = "$provider/$model"
    return if (thinking.isBlank()) base else "$base · $thinking"
}

/**
 * "另有 N 条排队（内容未同步）"的口径（用户 2026-10-23 报的 bug）。
 *
 * pi 的 `pendingMessageCount` 有**滞后**：steer 已经插进本轮、内容都上屏了，
 * 下一份 info 里计数可能还是 1 ⇒ 界面立刻蹦出"另有 1 条（内容未同步）"，
 * 看着像消息丢了。所以这条提示要**先等一会儿**：等够 [UNKNOWN_PENDING_GRACE_MS]
 * 还多出来，才认为真的在别处排了队。
 */
internal const val UNKNOWN_PENDING_GRACE_MS = 2500L

/** 权威计数比本地已知文本多出来的条数（负数归零）。 */
internal fun unknownPendingCount(pendingCount: Int, knownQueued: Int): Int =
    (pendingCount - knownQueued).coerceAtLeast(0)

/** 这条"未同步"提示现在该不该显示（要熬过宽限期）。 */
internal fun unknownPendingVisible(unknown: Int, waitedMs: Long): Boolean =
    unknown > 0 && waitedMs >= UNKNOWN_PENDING_GRACE_MS

/**
 * 会话结束通知的 id：**同一条会话只占一个**（后来的覆盖前面的），不同会话各占一条。
 * 抽成纯函数是因为请求码/id 算错的表现很隐蔽（点通知跳到别的会话、或通知叠成一串）。
 */
internal fun notifyIdFor(file: String?): Int {
    val h = (file ?: "pi-pocket").hashCode()
    return 1000 + (if (h < 0) -h else h) % 1000
}

/** 通知标题：完成 / 被停止 / 出错，三种语气要能分开。 */
internal fun notifyTitle(kind: String?): String = when (kind) {
    "stopped" -> "已停止"
    "error" -> "出错了"
    // pi 在等你回应（confirm/select/input/editor）：以前只有前台看得到卡片
    "needs-input" -> "需要你确认"
    "note" -> "pi 通知"
    "done" -> "跑完了"
    else -> "回答完成"
}

/**
 * 这类提醒是不是「这一轮结束了」（[TURN_END_KINDS]）。
 *
 * 用户 2026-10-24：「每次都显示且报错也显示」⇒ 拆出这一类，好在设置里单独关掉。
 * 用**显式清单**而不是「除了 needs-input/note 都算」：将来桥新增的提醒（比如审批）默认仍然会提醒，
 * 不会被这里的开关顺手吞掉。
 */
internal fun isTurnEndNotice(kind: String?): Boolean = kind == null || kind in TURN_END_KINDS

/** 回合结束类提醒的 kind；`null`/空串按 `turn-end`（[NotifyReceiver] 的兜底就是它）。 */
private val TURN_END_KINDS = setOf("", "turn-end", "stopped", "error", "done")

/**
 * 这条提醒现在该不该真发（`turnEndEnabled` = 设置里的「回答完成提醒」，**默认 false**）。
 *
 * 关掉的只是回合结束这一类：`needs-input`（pi 在等你）与 `note`（agent 主动发的）照常提醒。
 */
internal fun turnEndNoticeAllowed(kind: String?, turnEndEnabled: Boolean): Boolean =
    turnEndEnabled || !isTurnEndNotice(kind)

/**
 * 折叠态头行露几行（用户 2026-10-23：edit 的样式——绿/红 diff——看不到了）。
 *
 * 原因不只桥没算 diff：app 折叠时把整个头行**砍到 3 行**，而 edit 的头就是
 * `edit <path>` + 若干 `+/-` 行；3 行只够留个头 + 两行 diff。
 * 终端对 edit 本来就给十几行预览 ⇒ 这里按工具给不同上限（别的工具仍然收紧，防止 heredoc 撑爆卡片）。
 */
internal fun headPreviewLines(toolName: String, expanded: Boolean): Int = when {
    expanded -> Int.MAX_VALUE
    toolName == "edit" || toolName == "write" || toolName == "multi_edit" -> 14
    // bash 的 heredoc 可能几十行；其余工具 3 行够看
    toolName == "bash" || toolName == "exec_command" -> 5
    else -> 3
}

/**
 * 工具卡底色的三态（用户 2026-10-23：「tool call 还会变色，由灰变绿」）。
 * 纯函数只是为了让"哪一态"能被单测钉住（颜色本身在 TerminalColors 里）。
 */
internal fun toolStateKind(running: Boolean, failed: Boolean): String = when {
    running -> "pending"
    failed -> "error"
    else -> "done"
}
