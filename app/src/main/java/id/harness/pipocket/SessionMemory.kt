package id.harness.pipocket

import org.json.JSONObject

/**
 * 「每台机器上次在看哪个会话」的小记录。
 *
 * 用户 2026-10-24：「切换机器的时候，会话不要被 kill」——切走再切回来要回到原来那个会话，
 * 而不是把人踢回列表（更不是把正在跑的一轮停掉）。这里只做纯数据：记住/取回/存取 JSON，
 * 真正「打开会话」的动作在 AppState，且**不会**给机器发任何停止指令。
 */
internal object SessionMemory {
    /** 最多记 12 台机器，防止 prefs 越积越大。 */
    const val LIMIT = 12
    private const val KEY = "lastSessions"

    /** 记下某台机器的当前会话；返回新的映射（不可变，便于测试）。 */
    fun remember(map: Map<String, String>, machineUrl: String, file: String): Map<String, String> {
        val url = machineUrl.trim()
        val path = file.trim()
        if (url.isEmpty() || path.isEmpty()) return map
        val out = LinkedHashMap<String, String>()
        out[url] = path
        for ((k, v) in map) {
            if (k == url) continue
            if (out.size >= LIMIT) break
            out[k] = v
        }
        return out
    }

    fun recall(map: Map<String, String>, machineUrl: String): String? =
        map[machineUrl.trim()]?.takeIf { it.isNotBlank() }

    fun encode(map: Map<String, String>): String {
        val obj = JSONObject()
        for ((k, v) in map) obj.put(k, v)
        return obj.toString()
    }

    fun decode(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (key in obj.keys()) {
            val value = obj.optString(key)
            if (key.isNotBlank() && value.isNotBlank()) out[key] = value
        }
        return out
    }

    /** prefs 里存的键名（AppState 与测试共用）。 */
    fun prefsKey(): String = KEY
}
