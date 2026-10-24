package id.harness.pipocket

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「同一台机器的多条入口合并成一行」：host-a 的局域网地址与 Tailscale 地址会报同一个
 * `health.data.host`，面板应只显示一条最优路径，另外把其它路径写出来。
 */
class MachineMergeTest {

    private data class Entry(val url: String, val identity: String, val reach: BridgeReach?, val rank: Int)

    private fun row(entries: List<Entry>) = MachineMerge.merge(
        entries,
        identity = { it.identity },
        rankKey = { (it.reach?.rank ?: 2) * 10 + it.rank },
    )

    @Test
    fun sameHostKeepsOneRowAndPrefersLan() {
        val lan = Entry("http://192.0.2.10:8789", "linux-box", BridgeReach.Ok, MachineMerge.netRank("http://192.0.2.10:8789"))
        val tail = Entry("http://100.64.0.20:8789", "linux-box", BridgeReach.Ok, MachineMerge.netRank("http://100.64.0.20:8789"))
        val rows = row(listOf(tail, lan))
        assertEquals(1, rows.size)
        // 局域网优先（同一台机器两条路都通，没必要走中转）
        assertEquals(lan.url, rows[0].primary.url)
        assertEquals(listOf(tail.url), rows[0].others.map { it.url })
        assertEquals(2, rows[0].all.size)
    }

    @Test
    fun differentHostsStaySeparate() {
        val rows = row(
            listOf(
                Entry("http://192.0.2.10:8789", "linux-box", BridgeReach.Ok, 1),
                Entry("http://192.0.2.2:8789", "OpenWrt", BridgeReach.Down, 1),
            ),
        )
        assertEquals(2, rows.size)
        // 能直连的排前面
        assertEquals("linux-box", rows[0].primary.identity)
        assertEquals("OpenWrt", rows[1].primary.identity)
    }

    @Test
    fun reachableWinsOverUnreachableEvenOnSameHost() {
        val rows = row(
            listOf(
                Entry("http://192.0.2.10:8789", "linux-box", BridgeReach.Down, 1),
                Entry("http://100.64.0.20:8789", "linux-box", BridgeReach.Ok, 2),
            ),
        )
        assertEquals(1, rows.size)
        assertEquals("http://100.64.0.20:8789", rows[0].primary.url)
    }

    @Test
    fun unknownIdentityDoesNotMergeByAccident() {
        val rows = row(
            listOf(
                Entry("http://10.0.0.5:8789", "", BridgeReach.Down, 1),
                Entry("http://10.0.0.6:8789", "", BridgeReach.Down, 1),
            ),
        )
        assertEquals(2, rows.size)
    }

    @Test
    fun netRankRecognisesLoopbackLanAndTailscale() {
        assertEquals(0, MachineMerge.netRank("http://127.0.0.1:8789"))
        assertEquals(0, MachineMerge.netRank("http://localhost:8789"))
        assertEquals(1, MachineMerge.netRank("http://192.0.2.10:8789"))
        assertEquals(2, MachineMerge.netRank("http://100.64.0.20:8789"))
        assertEquals(2, MachineMerge.netRank("http://[fd7a:115c:a1e0::d538:4e49]:8789"))
        // 100.64/10 之外的 100.x 是普通公网地址，不当 Tailscale
        assertEquals(1, MachineMerge.netRank("http://100.20.30.40:8789"))
    }

    @Test
    fun equalRankKeepsInputOrder() {
        val rows = row(
            listOf(
                Entry("http://10.0.0.5:8789", "a", BridgeReach.Down, 1),
                Entry("http://10.0.0.6:8789", "b", BridgeReach.Down, 1),
            ),
        )
        assertEquals(listOf("a", "b"), rows.map { it.primary.identity })
        assertTrue(rows.all { it.others.isEmpty() })
    }
}
