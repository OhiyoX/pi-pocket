package id.harness.pipocket

import org.json.JSONArray
import org.json.JSONObject

/**
 * 一台机器上的桥「现在能不能直接连」——面板用它区分**能直连**与**只能 SSH**。
 *
 * 依据是一次只读的 `GET /v1/health`（带用户已配置的 token）：
 * - [Ok]       200 + `ok:true` + `data.pi`：桥在跑，当前 token 可用（或桥没开鉴权）
 * - [NeedsToken] 401/403：桥在跑，但要口令（token 缺失或不匹配）
 * - [Down]     其余（端口没人应答、不是我们的桥、超时/异常）
 */
enum class BridgeReach {
    Ok,
    NeedsToken,
    Down;

    /** 排序权重：能直连（含只需口令）的排前面，「桥未起」排后面。 */
    val rank: Int
        get() = when (this) {
            Ok -> 0
            NeedsToken -> 1
            Down -> 3
        }
}

/** 一次 `GET /v1/health` 的结论：能不能直连 + 桥自己有没有开鉴权 + 桥报的主机名。 */
data class BridgeProbe(
    val reach: BridgeReach,
    /** 只在 [BridgeReach.Ok] 时有值（从 health 的 `data.auth` 读）；其余情况 null = 不知道。 */
    val authRequired: Boolean?,
    /** 桥自报的主机名（health 的 `data.host`）：用来认出「同一台机器的多条路径」。 */
    val host: String? = null,
)

/** `/v1/config/machines` 的一条：桥侧解析出的 SSH 配置机器（带 alias，供面板显示可读名）。 */
data class ConfigMachine(
    val url: String,
    val alias: String,
    val host: String,
    val port: Int,
    val hasToken: Boolean,
) {
    /** 面板标题：alias 优先，退回 IP，再退回 url（用户口径：有 host-a 这种名字就别只显示 IP）。 */
    val title: String get() = alias.ifBlank { host.ifBlank { url } }
}

object ConfigMachines {

    /** 解析 `/v1/config/machines` 的数组（桥与 Termux 一次性服务两条路同一个形状）。 */
    fun parse(arr: JSONArray?): List<ConfigMachine> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val host = o.optString("host").trim()
            if (host.isBlank()) return@mapNotNull null
            val port = o.optInt("port", 22).takeIf { it > 0 } ?: 22
            ConfigMachine(
                url = o.optString("url").ifBlank { "http://$host:8789" },
                alias = o.optString("alias").trim(),
                host = host,
                port = port,
                hasToken = o.optBoolean("hasToken", false),
            )
        }
    }

    /** 解析结果 → 扫描候选用 [BridgeHit]（与自动扫描出来的条目同构）。 */
    fun toHit(machine: ConfigMachine, authFallback: Boolean) = BridgeHit(
        url = machine.url,
        host = machine.host,
        scope = BridgeScope.Lan,
        piPath = "",
        sessionsRoot = "",
        authRequired = machine.hasToken || authFallback,
        workdir = "",
        alias = machine.alias,
    )

    /** 与 [classifyHealth] 同一次响应，但额外带回「桥自己有没有开鉴权」与它报的主机名。 */
    fun healthProbe(status: Int, body: String?): BridgeProbe {
        val reach = classifyHealth(status, body)
        if (reach != BridgeReach.Ok) return BridgeProbe(reach, null, null)
        val data = runCatching { JSONObject(body.orEmpty()).optJSONObject("data") }.getOrNull()
        return BridgeProbe(
            reach = reach,
            authRequired = data?.optBoolean("auth", false),
            host = data?.optString("host")?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    /** `GET /v1/health` 的结论 → 三态。纯函数，单测直接覆盖。 */
    fun classifyHealth(status: Int, body: String?): BridgeReach {
        if (status == 401 || status == 403) return BridgeReach.NeedsToken
        if (status !in 200..299) return BridgeReach.Down
        val json = runCatching { JSONObject(body.orEmpty()) }.getOrNull() ?: return BridgeReach.Down
        if (!json.optBoolean("ok", false)) return BridgeReach.Down
        val data = json.optJSONObject("data") ?: return BridgeReach.Down
        // 只认我们这座桥的指纹（与 PiLocator.probe 一致）：别的服务开了 200 也不算。
        return if (data.optString("pi").isBlank()) BridgeReach.Down else BridgeReach.Ok
    }
}
