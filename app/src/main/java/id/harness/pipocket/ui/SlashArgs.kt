package id.harness.pipocket.ui

import id.harness.pipocket.CommandRef
import id.harness.pipocket.ModelRef
import id.harness.pipocket.SessionInfo

/**
 * 斜杠命令的**参数候选**（值 + 说明）。纯函数：数据由调用方传入，便于单元测试。
 *
 * 没把握的命令就返回空，不乱猜。
 */
internal fun argumentOptions(
    command: String,
    models: List<ModelRef>,
    commands: List<CommandRef>,
    sessions: List<SessionInfo>,
): List<Pair<String, String>> = when (command) {
    "/model" -> {
        // 同一个 model id 可能被**多个 provider** 提供（实例：`deepseek-flash` 在 opencode-go-2 和
        // opencode-go 各有一份）——
        // ① 裸 id 让 pi 报 `Model "x" is ambiguous across providers`；
        // ② 也会在抽屉的 LazyColumn 里撞 key（`Key "deepseek-flash" was already used` → **app 闪退**）。
        // 所以：重名的用 `provider/model` 消歧，唯一的保持裸 id（手感不变）。
        val byId = models.groupBy { it.model }
        models.map { ref ->
            val value = if ((byId[ref.model]?.size ?: 0) > 1) "${ref.provider}/${ref.model}" else ref.model
            value to ref.provider
        }.distinctBy { it.first }
    }
    "/mode" -> listOf(
        "build" to "只读提问",
        "edit" to "可改文件",
        "plan" to "先出计划",
        "yolo" to "全自动",
        "guarded" to "关键操作要确认",
    )
    "/skill" -> commands
        .map { it.name }
        .filter { it.startsWith("/skill:") }
        .map { it.removePrefix("/skill:") to "skill" }
    "/resume", "/fork", "/switch", "/open" -> sessions.take(20).map { s ->
        s.id to (s.name?.takeIf { it.isNotBlank() } ?: s.cwd.orEmpty())
    }
    "/thinking", "/thought" -> listOf(
        "off" to "不思考",
        "low" to "低",
        "high" to "高",
        "max" to "最大",
    )
    else -> emptyList()
}

/** 按参数前缀/包含过滤候选（纯函数）。 */
internal fun filterArgumentOptions(
    command: String,
    argQuery: String,
    models: List<ModelRef>,
    commands: List<CommandRef>,
    sessions: List<SessionInfo>,
): List<Pair<String, String>> {
    val options = argumentOptions(command, models, commands, sessions)
    return if (argQuery.isBlank()) options
    else options.filter { (value, _) -> value.contains(argQuery, ignoreCase = true) }
}

/** 命令候选的排序：前缀优先 → 命令名包含 → 说明包含。纯函数，便于测试。 */
internal fun rankCommands(commands: List<CommandRef>, needle: String): List<CommandRef> =
    commands
        .mapNotNull { c ->
            val name = c.name.removePrefix("/")
            val rank = when {
                needle.isEmpty() -> 0
                name.startsWith(needle, ignoreCase = true) -> 0
                name.contains(needle, ignoreCase = true) -> 1
                c.description.contains(needle, ignoreCase = true) -> 2
                else -> return@mapNotNull null
            }
            rank to c
        }
        .sortedWith(compareBy({ it.first }, { it.second.name }))
        .map { it.second }

/** 把输入拆成「命令 + 参数」两段（命令模式下的输入可能不带 "/"）。 */
internal data class SlashInput(val command: String, val argQuery: String, val hasArg: Boolean)

internal fun parseSlashInput(raw: String, commandMode: Boolean): SlashInput {
    val q = raw.replace('／', '/').replace('\u3000', ' ')
    val body = q.removePrefix("/")
    val spaceAt = body.indexOf(' ')
    return if (spaceAt >= 0) {
        SlashInput("/" + body.substring(0, spaceAt), body.substring(spaceAt + 1).trim(), true)
    } else {
        SlashInput(q, "", false)
    }
}
