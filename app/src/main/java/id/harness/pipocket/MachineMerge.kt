package id.harness.pipocket

/**
 * 同一台机器的多条入口合并成一行（例：`host-a` = 192.0.2.10 与 `host-b` = Tailscale 100.64.0.20）。
 *
 * 身份怎么认：优先用桥 `/v1/health` 自报的 `data.host`（主机名），探不到才退回 URL 本身。
 * 所以「同一台机器换了网」能合并，而「两台不同机器」不会被错并。
 */
data class MergedRow<T>(
    /** 最优路径上的那一条（真正被点的那条）。 */
    val primary: T,
    /** 同一台机器的全部入口，已按优先级排好（primary 一定是第一个）。 */
    val all: List<T>,
    /** 其它入口（展示用；点不到但告诉用户“还有这条路”）。 */
    val others: List<T>,
)

object MachineMerge {

    /**
     * 按 [identity] 分组，每组按 [rankKey] 从小到大取最优路径，行之间也按 [rankKey] 排。
     *
     * [rankKey] 越小越优先（调用方把「可直连 / 需口令 / 桥未起」和网络范围编进去）。
     * 同一权重的保持输入顺序（`sortedBy` 是稳定排序）——结果可预测，不随刷新乱跳。
     */
    fun <T> merge(items: List<T>, identity: (T) -> String, rankKey: (T) -> Int): List<MergedRow<T>> {
        val groups = LinkedHashMap<String, MutableList<T>>()
        for (item in items) {
            val key = identity(item).trim().ifEmpty { "\u0000" + items.indexOf(item) }
            groups.getOrPut(key) { mutableListOf() } += item
        }
        val rows = groups.values.map { group ->
            val sorted = group.sortedBy(rankKey)
            MergedRow(primary = sorted.first(), all = sorted, others = sorted.drop(1))
        }
        return rows.sortedBy { rankKey(it.primary) }
    }

    /**
     * 网络范围权重：本机 0 < 局域网 1 < Tailscale 2。
     *
     * 同一台机器两条路都通时优先走局域网（少一次中转）；Tailscale 用网段认
     * （100.64.0.0/10 与 fd7a:115c:a1e0::/48），不靠主机名猜。
     */
    fun netRank(url: String): Int {
        val hostPort = url.substringAfter("://", url).substringBefore('/')
        val host = hostPort.substringBeforeLast(':', hostPort).removePrefix("[").removeSuffix("]")
        if (host == "localhost" || host.startsWith("127.")) return 0
        val parts = host.split('.')
        if (parts.size == 4) {
            val a = parts[0].toIntOrNull()
            val b = parts[1].toIntOrNull()
            if (a == 100 && b != null && b in 64..127) return 2
        }
        if (host.startsWith("fd7a:115c:a1e0", ignoreCase = true)) return 2
        return 1
    }
}
