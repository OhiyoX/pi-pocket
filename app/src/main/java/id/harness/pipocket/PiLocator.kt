package id.harness.pipocket

import android.content.Context
import android.net.ConnectivityManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.TimeUnit
import kotlin.math.min

/** 探测到的一座桥（本机 loopback / 局域网 / 保存过的远端）。 */
data class BridgeHit(
    val url: String,
    val host: String,
    val scope: BridgeScope,
    val piPath: String,
    val sessionsRoot: String,
    val authRequired: Boolean,
    val workdir: String,
    /** 配置里的机器带的 alias（如 `host-a`）；空 = 没名字，标题退回 host。 */
    val alias: String = "",
) {
    val label: String get() = if (host.isBlank()) url else "$host · $url"

    /** 面板标题：alias → host → url（用户口径：有名字就别只显示 IP）。 */
    val title: String get() = alias.ifBlank { host.ifBlank { url } }
}

enum class BridgeScope { Local, Lan, Remote }

/**
 * 自动探测 pi 桥，三档：
 * 1. **本机**：loopback 一小段端口（8780–8800，桥默认 8789）。
 * 2. **局域网**：当前网络的实际网段（`ConnectivityManager` 的 link prefix；兜底按网卡的 /24）里扫默认端口。
 *    **不依赖 Tailscale**：走的是系统给的网卡/网段，Tailscale 开关与它无关；Tailscale 开着且把 LAN 流量
 *    引走时扫不到也不会拖垮本机与保存主机这两档。
 * 3. **远端（Tailscale / 跨网）**：tailnet 是 100.64/10，无法盲扫 ⇒ 用**保存过的主机列表**（在 app 里加 主机:端口）。
 *
 * 只认我们这座桥的指纹（`ok:true` + `data.pi`），不会把别的本地 HTTP 服务误当桥。
 */
object PiLocator {

    const val DEFAULT_PORT = 8789
    val LOCAL_PORTS: List<Int> = (8780..8800).toList()
    private const val MAX_LAN_HOSTS = 1024

    private val client: OkHttpClient = OkHttpClient.Builder()
        // 手机被系统冻结/刚唤醒时，首包经常要 1s 以上；放宽一点，拷不下来的地段靠并发度补
        .connectTimeout(1500, TimeUnit.MILLISECONDS)
        .readTimeout(2500, TimeUnit.MILLISECONDS)
        .build()

    /**
     * 单点可达性（面板区分「能直连」与「只能 SSH」用）：只探 `GET /v1/health`，不解会话。
     *
     * 与 [probe] 的区别：probe 只回「是不是我们那座桥」；这里要区分出 401/403（桥在、要口令），
     * 否则面板只能把「需要 token」和「桥没起」混在一起。超时收得比扫描短：面板上要一次探一串机器。
     */
    fun reach(baseUrl: String, token: String?): BridgeReach = probeHealth(baseUrl, token).reach

    /**
     * 与 [reach] 同一次请求，但额外把桥的 `auth` 带回来（面板据此决定说不说「免 token」）。
     * 以前「免 token」用的是保存机器时记下的旧值，桥开了鉴权也不会变（用户 2026-10-24 指出）。
     */
    fun probeHealth(baseUrl: String, token: String?): BridgeProbe = runCatching {
        val builder = Request.Builder().url(baseUrl.trimEnd('/') + "/v1/health").get()
        token?.trim()?.takeIf { it.isNotEmpty() }?.let { builder.header("Authorization", "Bearer $it") }
        client.newBuilder()
            .connectTimeout(1200, TimeUnit.MILLISECONDS)
            .readTimeout(1500, TimeUnit.MILLISECONDS)
            .build()
            .newCall(builder.build()).execute().use { response ->
                ConfigMachines.healthProbe(response.code, response.body?.string())
            }
    }.getOrDefault(BridgeProbe(BridgeReach.Down, null))

    fun probe(baseUrl: String, token: String?): BridgeHit? = runCatching {
        val builder = Request.Builder().url(baseUrl.trimEnd('/') + "/v1/health").get()
        token?.trim()?.takeIf { it.isNotEmpty() }?.let { builder.header("Authorization", "Bearer $it") }
        client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) return@use null
            val json = JSONObject(response.body?.string().orEmpty())
            if (!json.optBoolean("ok", false)) return@use null
            val data = json.optJSONObject("data") ?: return@use null
            val pi = data.optString("pi")
            if (pi.isBlank()) return@use null
            BridgeHit(
                url = baseUrl.trimEnd('/'),
                host = data.optString("host"),
                scope = scopeOf(baseUrl),
                piPath = pi,
                sessionsRoot = data.optString("sessionsRoot"),
                authRequired = data.optBoolean("auth", false),
                workdir = data.optString("cwd"),
            )
        }
    }.getOrNull()

    internal fun scopeOf(url: String): BridgeScope = when {
        url.contains("127.0.0.1") || url.contains("localhost") -> BridgeScope.Local
        else -> BridgeScope.Lan
    }

    /**
     * 当前网络里值得扫的 /24（或更小网段）前缀。来源按可信度：
     * 1. `ConnectivityManager` 的 `LinkProperties.linkAddresses`（真实前缀长度，Wi-Fi 常是 /24，也可能是 /23、/16）；
     * 2. 兜底：枚举网卡的 site-local 地址按 /24。
     */
    fun lanPrefixes(context: Context?): List<String> {
        val prefixes = LinkedHashSet<String>()

        if (context != null) {
            runCatching {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                cm.allNetworks.forEach { network ->
                    val props = runCatching { cm.getLinkProperties(network) }.getOrNull() ?: return@forEach
                    props.linkAddresses.forEach { link ->
                        val address = link.address
                        if (address !is Inet4Address) return@forEach
                        if (address.isLoopbackAddress || address.isLinkLocalAddress) return@forEach
                        // 只扫内网段：公网/运营商地址不盲扫（也是安全考虑）
                        if (!address.isSiteLocalAddress) return@forEach
                        prefixes += prefixOf(address.address, link.prefixLength)
                    }
                }
            }
        }

        if (prefixes.isEmpty()) {
            runCatching {
                NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { nif ->
                    if (!nif.isUp || nif.isLoopback) return@forEach
                    nif.inetAddresses.toList().forEach { address ->
                        val ip = address as? Inet4Address ?: return@forEach
                        if (ip.isLoopbackAddress || ip.isLinkLocalAddress || !ip.isSiteLocalAddress) return@forEach
                        prefixes += prefixOf(ip.address, 24)
                    }
                }
            }
        }
        return prefixes.toList()
    }

    /** 把 IPv4 + 前缀长度折成可枚举的前缀。比 /24 大的段按 /24 收窄，避免一下扫几千个地址。 */
    private fun prefixOf(bytes: ByteArray, prefixLength: Int): String {
        val effective = prefixLength.coerceAtLeast(24)
        var value = ((bytes[0].toInt() and 0xFF) shl 24) or
            ((bytes[1].toInt() and 0xFF) shl 16) or
            ((bytes[2].toInt() and 0xFF) shl 8) or
            (bytes[3].toInt() and 0xFF)
        val mask = if (effective >= 32) -1 else (-1 shl (32 - effective))
        value = value and mask
        return "${(value ushr 24) and 0xFF}.${(value ushr 16) and 0xFF}.${(value ushr 8) and 0xFF}"
    }

    /** 局域网候选地址：当前网段的 1..254（+ 可选的自定义网段）。 */
    fun lanCandidates(context: Context?, port: Int = DEFAULT_PORT, extraPrefixes: List<String> = emptyList()): List<String> {
        val prefixes = (lanPrefixes(context) + extraPrefixes.map { it.trim() }.filter { it.isNotBlank() }).distinct()
        val out = mutableListOf<String>()
        for (prefix in prefixes) {
            for (last in 1..254) {
                out += "http://$prefix.$last:$port"
                if (out.size >= MAX_LAN_HOSTS) return out
            }
        }
        return out
    }

    /**
     * 扫描：seed（上次地址）→ 本机 loopback → 局域网网段 → 保存过的远端主机。
     * 返回按 [本机, 局域网, 远端] 排序的全部命中。
     */
    suspend fun scan(
        seed: String?,
        token: String?,
        savedHosts: List<String> = emptyList(),
        includeLan: Boolean = true,
        context: Context? = null,
        onProgress: (String) -> Unit = {},
    ): List<BridgeHit> = withContext(Dispatchers.IO) {
        // 上次用过的地址先试一发：命中且没别的线索时直接返回，省一次全网扫
        seed?.takeIf { it.isNotBlank() }?.let { url ->
            probe(url, token)?.let { hit ->
                if (savedHosts.isEmpty() && !includeLan) return@withContext listOf(hit)
            }
        }

        // Credentials belong to the explicitly selected bridge, never to blind scans.
        val local = probeMany(LOCAL_PORTS.map { "http://127.0.0.1:$it" }, null, onProgress, "本机端口")
        val lan = if (includeLan) {
            probeMany(lanCandidates(context), null, onProgress, "局域网网段")
        } else emptyList()
        val remote = probeMany(savedHosts.map { normalizeHost(it) }, null, onProgress, "保存的远端主机")

        val all = (local + lan + remote).distinctBy { it.url }.toMutableList()
        // seed 命中但不在上面任何一档时也带上（比如手动填的地址）
        seed?.takeIf { it.isNotBlank() }?.let { url ->
            if (all.none { it.url == url.trimEnd('/') }) {
                probe(url, token)?.let { all += it }
            }
        }
        all.sortedBy { it.scope.ordinal }
    }

    /** 用户输入的远端主机 → 补全 scheme 与端口。 */
    fun normalizeHost(input: String): String {
        var value = input.trim()
        if (value.isEmpty()) return value
        if (!value.startsWith("http://") && !value.startsWith("https://")) value = "http://$value"
        val hasPort = Regex(":(\\d+)$").containsMatchIn(value)
        if (!hasPort) value = "$value:$DEFAULT_PORT"
        return value
    }

    private suspend fun probeMany(
        urls: List<String>,
        token: String?,
        onProgress: (String) -> Unit,
        stage: String,
    ): List<BridgeHit> = coroutineScope {
        if (urls.isEmpty()) return@coroutineScope emptyList()
        onProgress("$stage：扫 ${urls.size} 个地址…")
        val hits = mutableListOf<BridgeHit>()
        urls.chunked(48).forEach { chunk ->
            hits += chunk.map { url -> async { probe(url, token) } }.mapNotNull { it.await() }
        }
        onProgress("$stage：命中 ${hits.size}")
        hits
    }

    @Suppress("unused")
    private fun capped(count: Int) = min(count, MAX_LAN_HOSTS)
}
