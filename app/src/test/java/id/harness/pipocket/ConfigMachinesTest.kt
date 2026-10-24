package id.harness.pipocket

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 机器面板的两块纯逻辑：
 * 1. `/v1/config/machines` 的解析（alias 不能被丢、标题回退顺序）；
 * 2. `GET /v1/health` 的结论分类（可直连 / 需口令 / 桥未起），以及排序权重。
 */
class ConfigMachinesTest {

    private fun machines(json: String) = ConfigMachines.parse(JSONArray(json))

    @Test
    fun parseKeepsAliasAndPort() {
        val list = machines(
            """
            [
              {"host":"192.0.2.10","port":22,"alias":"host-a","user":"user","url":"http://192.0.2.10:8789"},
              {"host":"192.0.2.35","port":2222,"alias":"win"},
              {"host":"10.0.0.9"}
            ]
            """.trimIndent(),
        )
        assertEquals(3, list.size)
        assertEquals("host-a", list[0].alias)
        assertEquals("192.0.2.10", list[0].host)
        assertEquals(22, list[0].port)
        assertEquals("http://192.0.2.10:8789", list[0].url)
        // 没有 url 时按 host 补默认端口
        assertEquals("http://192.0.2.35:8789", list[1].url)
        assertEquals(2222, list[1].port)
        // 没有 alias / port 也要能用
        assertEquals("", list[2].alias)
        assertEquals(22, list[2].port)
    }

    @Test
    fun parseDropsBlankHostAndHandlesNull() {
        assertEquals(0, ConfigMachines.parse(null).size)
        val list = machines("""[{"host":"  ","alias":"ghost"},{"alias":"no-host"}]""")
        assertTrue(list.isEmpty())
    }

    @Test
    fun titlePrefersAliasThenHostThenUrl() {
        val aliased = machines("""[{"host":"192.0.2.10","alias":"host-a"}]""").single()
        assertEquals("host-a", aliased.title)
        // 没有 alias 时退回 IP（老行为），不会显示空标题
        val plain = ConfigMachine(url = "http://10.0.0.9:8789", alias = "", host = "10.0.0.9", port = 22, hasToken = false)
        assertEquals("10.0.0.9", plain.title)
        val blank = ConfigMachine(url = "http://10.0.0.9:8789", alias = "", host = "", port = 22, hasToken = false)
        assertEquals("http://10.0.0.9:8789", blank.title)
    }

    @Test
    fun toHitCarriesAliasAndAuthFlag() {
        val m = machines("""[{"host":"192.0.2.10","alias":"host-a","port":22,"hasToken":true}]""").single()
        val hit = ConfigMachines.toHit(m, authFallback = false)
        assertEquals("host-a", hit.alias)
        assertEquals("host-a", hit.title)
        assertEquals("http://192.0.2.10:8789", hit.url)
        assertTrue(hit.authRequired)
        // 桥侧说没 token 但 app 自己存了 token，也算「要口令」
        val withAppToken = ConfigMachines.toHit(m, authFallback = true)
        assertTrue(withAppToken.authRequired)
        val noToken = ConfigMachines.toHit(
            machines("""[{"host":"192.0.2.35","alias":"win"}]""").single(),
            authFallback = false,
        )
        assertFalse(noToken.authRequired)
    }

    @Test
    fun classifyHealthDistinguishesReachableFromNeedsToken() {
        val okBody = """{"ok":true,"data":{"ok":true,"pi":"pi","bind":"0.0.0.0:8789","auth":true}}"""
        assertEquals(BridgeReach.Ok, ConfigMachines.classifyHealth(200, okBody))
        // 桥在跑但要口令：这是「能连」的前提，不能算成桥没起
        assertEquals(BridgeReach.NeedsToken, ConfigMachines.classifyHealth(401, """{"ok":false,"error":"unauthorized"}"""))
        assertEquals(BridgeReach.NeedsToken, ConfigMachines.classifyHealth(403, null))
    }

    @Test
    fun classifyHealthRejectsForeignOrBrokenResponses() {
        // 200 但不是我们的桥（没有 data.pi）
        assertEquals(BridgeReach.Down, ConfigMachines.classifyHealth(200, """{"ok":true,"data":{"hello":"world"}}"""))
        assertEquals(BridgeReach.Down, ConfigMachines.classifyHealth(200, """{"ok":false}"""))
        assertEquals(BridgeReach.Down, ConfigMachines.classifyHealth(200, "not json"))
        assertEquals(BridgeReach.Down, ConfigMachines.classifyHealth(200, null))
        assertEquals(BridgeReach.Down, ConfigMachines.classifyHealth(502, "bad gateway"))
        assertEquals(BridgeReach.Down, ConfigMachines.classifyHealth(0, null))
    }

    @Test
    fun healthProbeReportsWhetherBridgeRequiresToken() {
        // 面板上的「免 token」只能按实时 health 的 auth 说，不能用保存机器时的旧值
        val authOn = ConfigMachines.healthProbe(
            200,
            """{"ok":true,"data":{"ok":true,"pi":"pi","auth":true}}""",
        )
        assertEquals(BridgeReach.Ok, authOn.reach)
        assertEquals(true, authOn.authRequired)

        val authOff = ConfigMachines.healthProbe(
            200,
            """{"ok":true,"data":{"ok":true,"pi":"pi","auth":false}}""",
        )
        assertEquals(BridgeReach.Ok, authOff.reach)
        assertEquals(false, authOff.authRequired)

        // 没见过成功响应就不该断言鉴权状态
        val unknown = ConfigMachines.healthProbe(401, """{"ok":false,"error":"unauthorized"}""")
        assertEquals(BridgeReach.NeedsToken, unknown.reach)
        assertEquals(null, unknown.authRequired)
        assertEquals(null, ConfigMachines.healthProbe(0, null).authRequired)
    }

    @Test
    fun reachRankPutsConnectableFirstAndDownLast() {
        assertTrue(BridgeReach.Ok.rank < BridgeReach.NeedsToken.rank)
        assertTrue(BridgeReach.NeedsToken.rank < BridgeReach.Down.rank)

        val order = listOf(
            "win" to BridgeReach.Down,
            "host-a" to BridgeReach.NeedsToken,
            "nas" to BridgeReach.Ok,
            "tx" to null,
        )
        val sorted = order.sortedBy { it.second?.rank ?: 2 }.map { it.first }
        assertEquals(listOf("nas", "host-a", "tx", "win"), sorted)
    }
}
