package id.harness.pipocket.ui

import id.harness.pipocket.ChatItem
import id.harness.pipocket.CompactionCardInfo

/** 聊天列表里的一行：一条消息，或插在**时间线**上的「压缩上下文」卡。 */
internal sealed interface ChatRow {
    val key: String

    data class Message(val index: Int, val item: ChatItem) : ChatRow {
        override val key: String get() = "m-" + item.key
    }

    data class Compaction(val info: CompactionCardInfo) : ChatRow {
        override val key: String get() = "compaction-card"
    }
}

/**
 * 消息列表 + 压缩卡 → LazyColumn 的行清单。
 *
 * 卡片按**时间线**插（[CompactionCardInfo.afterKey] 那条消息之后），不再固定挂列表末尾 ——
 * 固定挂末尾时，压缩之后新来的消息全排在卡片上面，卡片就永远“贴底”
 * （用户 2026-10-22 报的 bug：「上下文已压缩一直贴底显示」）。
 */
internal fun buildChatRows(items: List<ChatItem>, card: CompactionCardInfo?): List<ChatRow> {
    if (card == null) return items.mapIndexed { i, it -> ChatRow.Message(i, it) }
    val at = compactionInsertIndex(items, card.afterKey)
    val rows = ArrayList<ChatRow>(items.size + 1)
    items.forEachIndexed { i, it ->
        if (i == at) rows += ChatRow.Compaction(card)
        rows += ChatRow.Message(i, it)
    }
    // 插在末尾（at == size）或列表为空（at == 0）时上面那轮不会插，这里补
    if (at >= items.size) rows += ChatRow.Compaction(card)
    return rows
}

/** 压缩卡插在**哪一条消息之前**（= 锚点消息之后。返回 items 下标；等于 size = 挂末尾）。 */
internal fun compactionInsertIndex(items: List<ChatItem>, afterKey: String): Int {
    if (items.isEmpty()) return 0
    val exact = items.indexOfLast { it.key == afterKey }
    if (exact >= 0) return exact + 1
    // 锚点消息可能被「压缩重写」掉了：key 里的下标会变，但**时间戳不变** ⇒ 按时间戳找同一刻
    val ts = keyTimestamp(afterKey) ?: return items.size
    val sameMoment = items.indexOfLast { keyTimestamp(it.key) == ts }
    if (sameMoment >= 0) return sameMoment + 1
    // 连时间戳都没有：插在第一条比它新的消息之前；都比它旧就挂末尾
    val newer = items.indexOfFirst { (keyTimestamp(it.key) ?: Long.MAX_VALUE) > ts }
    return if (newer >= 0) newer else items.size
}

/**
 * 从 item key 里抠出 pi 的时间戳（epoch ms）。
 *
 * pi 侧的 key 形如 `user-<epochMs>-0` / `assistant-<epochMs>-<hash>` / `tool-<epochMs>-…`；
 * 取第一段能解析成「像毫秒的时间」的数字即可（hash 段是十六进制，解析不出来）。
 */
internal fun keyTimestamp(key: String): Long? = key.split('-').firstNotNullOfOrNull { part ->
    part.toLongOrNull()?.takeIf { it > 1_000_000_000_000L }
}
