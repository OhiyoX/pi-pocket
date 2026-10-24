package id.harness.pipocket.ui

import org.json.JSONArray

/**
 * 会话**树**——把 pi 的 `get_tree` 结果变成可以直接画成树的行。
 *
 * 这里的规则**全部照抄 pi 的 TUI**（`src/modes/interactive/components/tree-selector.ts`，
 * 用 `tools/pi-src-extract.mjs` 从 `.js.map` 的 sourcesContent 还原出来的真源码）：
 *
 *  · 过滤档（`tree-selector.ts:340-400`）
 *      - `default`：隐藏「记账类」条目（`label` / `context_edit` / `custom` / `model_change` /
 *        `thinking_level_change` / `session_info`）与 `usage`；**还要**隐藏"只有工具调用、没有正文"的
 *        assistant 消息（错误/中断的除外；当前 leaf 永远显示，否则看不出自己在哪）。
 *      - `no-tools`：在 default 基础上再去掉 toolResult。
 *      - `user-only`：只剩用户消息。
 *      - `labeled-only`：只剩打了标签的。
 *      - `all`：全显示。
 *  · 连接线（`tree-selector.ts:432-552`）：父节点有多个可见子节点时才画 `├─` / `└─`；
 *    祖先那一位该画 `│` 还是空格，由祖先是不是「最后一个孩子」决定。
 *  · 条目文案（`tree-selector.ts:777-870`）：`user: …` / `assistant: …` / `[bash]: …` /
 *    `[compaction: Nk tokens]` / `[model: …]` / `[thinking: …]` / `[title: …]` …
 *
 * 桥只搬数据（`GET /v1/tree`），**显示规则在 app 这边**（跟终端一致）。
 */
internal object TreeEntryType {
    const val MESSAGE = "message"
    const val CUSTOM_MESSAGE = "custom_message"
    const val COMPACTION = "compaction"
    const val BRANCH_SUMMARY = "branch_summary"
    const val MODEL_CHANGE = "model_change"
    const val THINKING_CHANGE = "thinking_level_change"
    const val CUSTOM = "custom"
    const val CONTEXT_EDIT = "context_edit"
    const val LABEL = "label"
    const val SESSION_INFO = "session_info"
    const val USAGE = "usage"
}

/**
 * 缩进最多画几层。
 *
 * 真实会话是几千层的长链（3000 层 × 14dp = 42000dp，内容早跑出屏幕了），
 * 所以缩进必须**封顶**：超过这个层数的行一律按最深层画，并在行首标 `⋯`。
 * （同时也是性能边界：每行只处理 ≤ 10 个祖先，而不是几千个。）
 */
internal const val TREE_MAX_INDENT = 10

/** 过滤档，取值与 pi 的 `FilterMode` 一致（顺序也一致：设置里选的就是这个）。 */
internal val TREE_FILTERS = listOf("default", "no-tools", "user-only", "labeled-only", "all")

internal fun treeFilterLabel(mode: String): String = when (mode) {
    "no-tools" -> "无工具"
    "user-only" -> "仅我"
    "labeled-only" -> "仅标签"
    "all" -> "全部"
    else -> "默认"
}

/** assistant 消息里的一个工具调用（给 toolResult 行配对用，pi 的 `toolCallMap`）。 */
internal data class TreeToolCall(val id: String?, val name: String, val argsText: String)

/** 树里的一个条目（桥的 `GET /v1/tree` 投影；字段是 pi `SessionEntry` 的子集）。 */
internal data class TreeEntry(
    val id: String,
    val parentId: String?,
    val type: String,
    val timestamp: String? = null,
    val role: String? = null,
    val text: String = "",
    val label: String? = null,
    /** pi 的 `label` 条目本身带的值（`[label: ...]` 行用）。 */
    val entryLabel: String? = null,
    val targetId: String? = null,
    val stopReason: String? = null,
    val errorMessage: String? = null,
    val toolName: String? = null,
    val toolCallId: String? = null,
    /** 工具调用的参数摘要（紧凑 JSON，已截断）—— 用来画 `[read(path=…)]`。 */
    val argsText: String? = null,
    val command: String? = null,
    val tokensBefore: Long = 0L,
    val summary: String = "",
    val modelId: String? = null,
    val thinkingLevel: String? = null,
    val customType: String? = null,
    val sessionName: String? = null,
    val omitted: Boolean = false,
    val toolCalls: List<TreeToolCall> = emptyList(),
) {
    companion object {
        fun from(o: org.json.JSONObject): TreeEntry? {
            val id = o.optString("id").ifBlank { return null }
            return TreeEntry(
                id = id,
                parentId = o.optString("parentId").ifBlank { null },
                type = o.optString("type"),
                timestamp = o.optString("timestamp").ifBlank { null },
                role = o.optString("role").ifBlank { null },
                text = o.optString("text", ""),
                label = if (o.has("label") && !o.isNull("label")) o.optString("label").ifBlank { null } else null,
                entryLabel = if (o.has("entryLabel") && !o.isNull("entryLabel")) o.optString("entryLabel") else null,
                targetId = o.optString("targetId").ifBlank { null },
                stopReason = o.optString("stopReason").ifBlank { null },
                errorMessage = o.optString("errorMessage").ifBlank { null },
                toolName = o.optString("toolName").ifBlank { null },
                toolCallId = o.optString("toolCallId").ifBlank { null },
                argsText = o.optString("argsText").ifBlank { null },
                command = o.optString("command").ifBlank { null },
                tokensBefore = o.optLong("tokensBefore", 0L),
                summary = o.optString("summary", ""),
                modelId = o.optString("modelId").ifBlank { null },
                thinkingLevel = o.optString("thinkingLevel").ifBlank { null },
                customType = o.optString("customType").ifBlank { null },
                sessionName = if (o.has("sessionName") && !o.isNull("sessionName")) o.optString("sessionName") else null,
                omitted = o.optBoolean("omitted", false),
                toolCalls = parseToolCalls(o.optJSONArray("toolCalls")),
            )
        }

        private fun parseToolCalls(array: JSONArray?): List<TreeToolCall> {
            if (array == null) return emptyList()
            return (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                val name = o.optString("name")
                if (name.isBlank()) return@mapNotNull null
                TreeToolCall(
                    id = o.optString("id").ifBlank { null },
                    name = name,
                    argsText = o.optString("argsText"),
                )
            }
        }

        fun list(array: JSONArray?): List<TreeEntry> {
            if (array == null) return emptyList()
            return (0 until array.length()).mapNotNull { from(array.optJSONObject(it) ?: return@mapNotNull null) }
        }
    }
}

/** 画一行树需要的全部信息（纯数据，方便单测）。 */
internal data class TreeRow(
    val entry: TreeEntry,
    /** 层级（根 = 0）。 */
    val depth: Int,
    /** 该位要不要画 `│`（祖先层级上）。长度 = depth。 */
    val gutters: List<Boolean>,
    /** 是不是父节点的最后一个孩子（决定 `└─` 还是 `├─`；根节点/独生孩子不画连接线）。 */
    val isLast: Boolean,
    /** 父节点是否有多个**可见**孩子（false ⇒ 这一层不画连接线，跟终端一样）。 */
    val showConnector: Boolean,
    /** 是不是当前 leaf（终端会标出来："你在这"）。 */
    val isLeaf: Boolean,
    /** 该行的人类可读时间（设备本地时间，来自 `Format.localTimeLabel`）。 */
    val timeLabel: String = "",
    /** 有没有子条目（决定能不能折叠）。 */
    val hasChildren: Boolean = false,
    /** 折叠起来时藏了多少个后代（0 = 没折叠）。 */
    val hiddenCount: Int = 0,
    /** 角色短标签：我 / AI / 工具 / 压缩 / 标题…（可读性：扫一眼就知道这行是什么）。 */
    val tag: String = "",
    /** 这一行能不能分叉（用户消息）。 */
    val forkable: Boolean = false,
    /** 因为缩进封顶被"省略"的层数（>0 时行首画 `⋯`）。 */
    val deepLevels: Int = 0,
) {
    /** 终端口径的完整文案（含 `user: ` 前缀 / `[compaction: …]`）。
     *  **按需计算**：界面画的是 `entrySummary()`；这里只给测试/调试对照终端措辞用，
     *  每行都提前算一遍会白做一次 JSON 解析（3000 行就是几秒）。 */
    val display: String get() = entryDisplay(entry)

    /** 左侧连接线文本，例如 `"│ ├─"` / `"  └─"` / `""`。
     *  宽度恒为 `depth * 2` 个等宽字符（该层不画连接线时补空格）⇒ 缩进对齐。 */
    val gutterText: String
        get() = buildString {
            for (g in gutters) append(if (g) "│ " else "  ")
            // 根节点没有「本层位置」；其它层即使不画连接线也补两个空格（缩进对齐）
            if (depth > 0) {
                if (showConnector) append(if (isLast) "└─" else "├─") else append("  ")
            }
        }
}

/**
 * 树 → 行。
 *
 * @param entries 桥给的扁平条目（DFS 顺序，`parentId` 指父亲）
 * @param leafId 当前 leaf（`get_tree` 的 `leafId`）
 * @param filter `default` / `no-tools` / `user-only` / `labeled-only` / `all`
 * @param search 搜索词（空格分词，全部命中才算；与终端的搜索一致）
 */
internal fun buildTreeRows(
    entries: List<TreeEntry>,
    leafId: String?,
    filter: String = "default",
    search: String = "",
    collapsed: Set<String> = emptySet(),
): List<TreeRow> {
    if (entries.isEmpty()) return emptyList()

    // ⓪ 工具调用 → 结果 配对（pi 的 `toolCallMap`）：toolResult 行要显示的事**调用**的名字+参数，
    //    而不是结果自己里的 toolName（pi 也是这么做的：找得到 toolCall 就用它，否则退回 `[toolName]`）
    val callMap = HashMap<String, TreeToolCall>()
    for (e in entries) for (c in e.toolCalls) if (c.id != null) callMap[c.id] = c
    val resolved = entries.map { e ->
        if (e.type == TreeEntryType.MESSAGE && e.role == "toolResult") {
            val call = e.toolCallId?.let { callMap[it] }
            if (call != null) e.copy(toolName = call.name.ifBlank { e.toolName }, argsText = call.argsText) else e
        } else {
            e
        }
    }

    // ① 索引：parentId → 孩子们；id → 条目（**全部 O(1) 查表**）。
    //    ⚠️ 这里以前每行都 `resolved.firstOrNull { it.id == p }` / `resolved.any { … }`，
    //    3000 条的大会话就是 900 万次比较 × 多层 ⇒ 直接卡死（实测 3000 条挂住 > 60s）。
    val childrenOf = LinkedHashMap<String?, MutableList<TreeEntry>>()
    for (e in resolved) childrenOf.getOrPut(e.parentId) { mutableListOf() }.add(e)
    val byId = HashMap<String, TreeEntry>(resolved.size * 2)
    for (e in resolved) byId[e.id] = e

    // ② 先按「过滤档」筛（不含连接线信息）
    val visible = resolved.filter { passesFilter(it, filter, leafId) }
    val visibleIds = visible.mapTo(HashSet()) { it.id }

    // ③ 搜索（在过滤之后、算连接线之前；终端也是这个顺序）
    val tokens = search.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    val shown = if (tokens.isEmpty()) visible else visible.filter { e ->
        val hay = searchableText(e).lowercase()
        tokens.all { hay.contains(it) }
    }
    if (shown.isEmpty()) return emptyList()

    // ④ 算连接线：pi 只在「父节点有多个可见孩子」时画连接线。
    //    父节点本身不可见时（比如被过滤掉的记账条目），沿 parentId 往上找最近的可见祖先。
    // ③·补 折叠：被折叠节点的所有后代都不画（大树 3000 条不折叠根本没法看）。
    //    被折叠的节点自己仍然显示，并带上「+N」告诉用户藏了多少。
    val hiddenByFold = HashMap<String, Int>()     // 折叠节点 id -> 藏起的可见后代数
    val foldedAway = HashSet<String>()
    if (collapsed.isNotEmpty()) {
        // ⚠️ **必须迭代**：真实会话是一条 3000 层的长链，递归会直接 StackOverflow
        //    （2026-10-22 在模拟器上崩过一次：stack 里 20 层全是 walkChildren）。
        val shownIdSet = shown.mapTo(HashSet()) { it.id }
        for (id in collapsed) {
            if (id !in shownIdSet) continue
            val stack = ArrayDeque<String>()
            stack.addLast(id)
            while (stack.isNotEmpty()) {
                val cur = stack.removeLast()
                for (e in childrenOf[cur].orEmpty()) {
                    if (foldedAway.add(e.id)) {
                        if (e.id in visibleIds) hiddenByFold[id] = (hiddenByFold[id] ?: 0) + 1
                        stack.addLast(e.id)
                    }
                }
            }
        }
    }
    val shownAfterFold = shown.filter { it.id !in foldedAway }

    val shownIds = shownAfterFold.mapTo(HashSet()) { it.id }
    val nearestVisibleParent = HashMap<String, String?>()
    for (e in shownAfterFold) {
        var p = e.parentId
        var firstVisible: String? = null
        val guard = HashSet<String>()
        while (p != null && guard.add(p)) {
            if (p in shownIds) { firstVisible = p; break }
            p = byId[p]?.parentId
        }
        nearestVisibleParent[e.id] = firstVisible
    }
    val shownChildren = LinkedHashMap<String?, MutableList<String>>()
    for (e in shownAfterFold) shownChildren.getOrPut(nearestVisibleParent[e.id]) { mutableListOf() }.add(e.id)

    // 兄弟索引表（避免每行 indexOf 造成 O(n²)）
    val siblingIndex = HashMap<String, Int>(shownAfterFold.size * 2)
    for ((_, list) in shownChildren) list.forEachIndexed { i, id -> siblingIndex[id] = i }

    val depthOf = HashMap<String, Int>()
    val rows = ArrayList<TreeRow>(shownAfterFold.size)
    for (e in shownAfterFold) {
        val parent = nearestVisibleParent[e.id]
        val siblings = shownChildren[parent] ?: listOf(e.id)
        val idx = siblingIndex[e.id] ?: 0
        val isLast = idx == siblings.size - 1
        val showConnector = siblings.size > 1
        val realDepth = if (parent == null) 0 else (depthOf[parent] ?: 0) + 1
        depthOf[e.id] = realDepth
        val depth = realDepth.coerceAtMost(TREE_MAX_INDENT)

        // 祖先层级该画 │ 还是空格：看祖先自己是不是「最后一个可见孩子」。
        // root 那一层没有 gutter（它上面什么都没有）⇒ gutter 数 = depth - 1，
        // 最后一个位置留给本行的连接线（没连接线时补两个空格，保持缩进对齐）。
        // 只往上取「缩进封顶」需要的层数（深链上这是 O(1) 而不是 O(depth)）
        val ancestors = ArrayList<TreeEntry>(TREE_MAX_INDENT)
        var cur = parent
        while (cur != null && ancestors.size <= TREE_MAX_INDENT) {
            val node = byId[cur] ?: break
            ancestors.add(node)
            cur = nearestVisibleParent[cur]
        }
        ancestors.reverse()
        val gutters = ArrayList<Boolean>(ancestors.size)
        for (i in 1 until ancestors.size) {  // i = 0 是 root，跳过
            val anc = ancestors[i]
            val ancParent = nearestVisibleParent[anc.id]
            val ancSiblings = shownChildren[ancParent] ?: listOf(anc.id)
            gutters.add(ancSiblings.lastOrNull() != anc.id)
        }

        rows.add(
            TreeRow(
                entry = e,
                depth = depth,
                gutters = gutters,
                isLast = isLast,
                showConnector = showConnector,
                isLeaf = e.id == leafId,
                timeLabel = e.timestamp?.let { localTimeLabel(it) } ?: "",
                hasChildren = !childrenOf[e.id].isNullOrEmpty(),
                hiddenCount = hiddenByFold[e.id] ?: 0,
                tag = entryTag(e),
                forkable = canForkAt(e),
                deepLevels = (realDepth - TREE_MAX_INDENT).coerceAtLeast(0),
            ),
        )    }
    return rows
}

/** `tree-selector.ts:340-400` 的过滤规则（含 usage 与"只有工具调用"的 assistant 两条特例）。 */
private fun passesFilter(e: TreeEntry, filter: String, leafId: String?): Boolean {
    if (e.type == TreeEntryType.USAGE) return false
    val isCurrentLeaf = e.id == leafId

    // 只有工具调用、没有正文的 assistant 消息默认不显示（错误/中断的除外；当前 leaf 永远显示）
    if (e.type == TreeEntryType.MESSAGE && e.role == "assistant" && !isCurrentLeaf) {
        val hasText = e.text.isNotBlank()
        val isErrorOrAborted = e.stopReason != null && e.stopReason != "stop" && e.stopReason != "toolUse"
        if (!hasText && !isErrorOrAborted) return false
    }

    val isBookkeeping = e.type == TreeEntryType.LABEL ||
        e.type == TreeEntryType.CONTEXT_EDIT ||
        e.type == TreeEntryType.CUSTOM ||
        e.type == TreeEntryType.MODEL_CHANGE ||
        e.type == TreeEntryType.THINKING_CHANGE ||
        e.type == TreeEntryType.SESSION_INFO

    return when (filter) {
        "user-only" -> e.type == TreeEntryType.MESSAGE && e.role == "user"
        "no-tools" -> !isBookkeeping && !(e.type == TreeEntryType.MESSAGE && e.role == "toolResult")
        "labeled-only" -> e.label != null
        "all" -> true
        else -> !isBookkeeping
    }
}

/** `tree-selector.ts:883-1000` 的 `getSearchableText()`。 */
internal fun searchableText(e: TreeEntry): String = buildString {
    e.label?.let { append(it).append(' ') }
    append(e.type).append(' ')
    e.role?.let { append(it).append(' ') }
    append(e.text)
    e.customType?.let { append(' ').append(it) }
    e.summary.takeIf { it.isNotBlank() }?.let { append(" branch summary ").append(it) }
    e.modelId?.let { append(" model ").append(it) }
    e.thinkingLevel?.let { append(" thinking ").append(it) }
    e.sessionName?.let { append(" title ").append(it) }
    e.entryLabel?.let { append(" label ").append(it) }
    e.toolName?.let { append(' ').append(it) }
    e.command?.let { append(' ').append(it) }
}

/**
 * 一行的显示文案 —— 与 `tree-selector.ts:777-870 getEntryDisplayText()` 逐条对齐：
 * `user: …` / `assistant: …` / `[bash]: …` / `[compaction: Nk tokens]` / `[model: …]` …
 */
internal fun entryDisplay(e: TreeEntry): String {
    val normalize = { s: String -> s.replace('\n', ' ').replace('\t', ' ').trim() }
    val body = when (e.type) {
        TreeEntryType.MESSAGE -> when (e.role) {
            "user" -> "user: " + normalize(e.text)
            "assistant" -> when {
                normalize(e.text).isNotEmpty() -> "assistant: " + normalize(e.text)
                e.stopReason == "aborted" -> "assistant: (aborted)"
                !e.errorMessage.isNullOrBlank() -> "assistant: " + normalize(e.errorMessage).take(80)
                else -> "assistant: (no content)"
            }
            "toolResult" -> {
                val name = e.toolName
                when {
                    name == null -> "[tool]"
                    // 配对到 toolCall（有参数）⇒ 按终端 `formatToolCall()` 的口径画
                    e.argsText != null -> toolCallLabel(name, e.argsText)
                    else -> "[$name]"
                }
            }
            "bashExecution" -> "[bash]: " + normalize(e.command ?: "")
            else -> "[" + (e.role ?: "?") + "]"
        }
        TreeEntryType.CUSTOM_MESSAGE -> "[" + (e.customType ?: "custom") + "]: " + normalize(e.text)
        TreeEntryType.COMPACTION -> "[compaction: " + Math.round(e.tokensBefore / 1000.0) + "k tokens]"
        TreeEntryType.BRANCH_SUMMARY -> "[branch summary]: " + normalize(e.summary)
        TreeEntryType.MODEL_CHANGE -> "[model: " + (e.modelId ?: "") + "]"
        TreeEntryType.THINKING_CHANGE -> "[thinking: " + (e.thinkingLevel ?: "") + "]"
        TreeEntryType.CUSTOM -> "[custom: " + (e.customType ?: "") + "]"
        TreeEntryType.CONTEXT_EDIT -> "[context " + (if (e.omitted) "omit" else "replace") + ": " + (e.targetId ?: "") + "]"
        TreeEntryType.LABEL -> "[label: " + (e.entryLabel ?: "(cleared)") + "]"
        TreeEntryType.SESSION_INFO -> "[title: " + (e.sessionName ?: "empty") + "]"
        else -> ""
    }
    // 终端把标签画在正文**前面**（`tree-selector.ts:750`）
    val label = e.label?.let { "\uD83C\uDFF7 $it  " } ?: ""
    return label + body
}

/**
 * 角色短标签（可读性决定性的一招）：把 `user: ` / `[compaction: …]` 这种终端英文前缀
 * 换成 **2~3 个字的类型词**，让用户扫一眼前就知道这行是什么。
 *
 * 为什么换：树是**导航界面**（不是正文），终端那套 `assistant: ` 前缀在手机上又长又重复；
 * 正文里保留终端措辞（`entryDisplay()` 管正文），标签只管“这是哪类条目”。
 */
internal fun entryTag(e: TreeEntry): String = when (e.type) {
    TreeEntryType.MESSAGE -> when (e.role) {
        "user" -> "我"
        "assistant" -> "AI"
        "toolResult" -> "工具"
        "bashExecution" -> "命令"
        "system" -> "系统"
        else -> e.role?.take(4) ?: "?"
    }
    TreeEntryType.CUSTOM_MESSAGE -> "扩展"
    TreeEntryType.COMPACTION -> "压缩"
    TreeEntryType.BRANCH_SUMMARY -> "摘要"
    TreeEntryType.MODEL_CHANGE -> "换模型"
    TreeEntryType.THINKING_CHANGE -> "思考"
    TreeEntryType.CUSTOM -> "自定义"
    TreeEntryType.CONTEXT_EDIT -> "上下文"
    TreeEntryType.LABEL -> "标签"
    TreeEntryType.SESSION_INFO -> "标题"
    else -> e.type.take(4)
}

/**
 * 行上显示的**正文摘要**（不再带终端前缀）。
 *
 * 与 `entryDisplay()`（保留完整终端措辞，用于测试/调试）分开：
 * 树里已经有 `tag` 列了，正文再带一遍 `user: ` 就是重复。
 */
internal fun entrySummary(e: TreeEntry): String {
    val normalize = { s: String -> s.replace('\n', ' ').replace('\t', ' ').trim() }
    val body = when (e.type) {
        TreeEntryType.MESSAGE -> when (e.role) {
            "user", "assistant" -> normalize(e.text).ifBlank {
                when {
                    e.stopReason == "aborted" -> "(已中断)"
                    !e.errorMessage.isNullOrBlank() -> normalize(e.errorMessage).take(120)
                    else -> "(无正文)"
                }
            }
            "toolResult" -> {
                val name = e.toolName ?: "工具"
                if (e.argsText != null) toolCallLabel(name, e.argsText) else "[$name]"
            }
            "bashExecution" -> "$ " + normalize(e.command ?: "")
            else -> normalize(e.text).ifBlank { "[" + (e.role ?: "?") + "]" }
        }
        TreeEntryType.CUSTOM_MESSAGE -> normalize(e.text)
        // 标签列已经写了「压缩」，这里别再重复"压缩了"——只给数字
        TreeEntryType.COMPACTION -> Math.round(e.tokensBefore / 1000.0).toString() + "k tokens"
        TreeEntryType.BRANCH_SUMMARY -> normalize(e.summary)
        TreeEntryType.MODEL_CHANGE -> e.modelId ?: ""
        TreeEntryType.THINKING_CHANGE -> e.thinkingLevel ?: ""
        TreeEntryType.CUSTOM -> e.customType ?: ""
        TreeEntryType.CONTEXT_EDIT -> (if (e.omitted) "省略 " else "替换 ") + (e.targetId ?: "")
        TreeEntryType.LABEL -> e.entryLabel ?: "(已清除)"
        TreeEntryType.SESSION_INFO -> e.sessionName ?: "(空)"
        else -> ""
    }
    return body.ifBlank { "(空)" }
}

/** 能不能从这个条目「分叉」。pi 的 `fork`（默认 `position: "before"`）只接受**用户消息**。 */
internal fun canForkAt(e: TreeEntry): Boolean =
    e.type == TreeEntryType.MESSAGE && e.role == "user"

/**
 * 工具调用的标签 —— 逐条照 pi 的 `tree-selector.ts:947-1005 formatToolCall()`：
 * `[read: 路径:12-30]` / `[write: 路径]` / `[edit: 路径]` / `[bash: 命令]` /
 * `[grep: /模式/ in 路径]` / `[find: 模式 in 路径]` / `[ls: 路径]` / 其它 → `[名字: 紧凑 JSON]`。
 *
 * 路径会把 home 缩成 `~`（同终端的 `shortenPath()`）。
 */
internal fun toolCallLabel(name: String, argsJson: String?, home: String? = null): String {
    val args = runCatching { org.json.JSONObject(argsJson ?: "{}") }.getOrNull()
        ?: return "[" + name + ": " + (argsJson ?: "").take(40) + "]"

    fun str(vararg keys: String): String {
        for (k in keys) {
            if (args.has(k) && !args.isNull(k)) {
                val v = args.opt(k)
                if (v is String) return v
            }
        }
        return ""
    }

    fun shorten(p: String): String {
        val h = home?.takeIf { it.isNotBlank() } ?: return p
        return if (h.isNotEmpty() && p.startsWith(h)) "~" + p.substring(h.length) else p
    }

    fun lineRange(offset: Any?, limit: Any?): String {
        if (offset == null && limit == null) return ""
        val start = (offset as? Number)?.toInt() ?: 1
        if (limit == null) return ":" + start
        val count = (limit as? Number)?.toInt() ?: return ""
        val end = start + count - 1
        return ":" + start + (if (end != 0) "-" + end else "")
    }

    fun numOrNull(k: String): Any? = if (args.has(k) && !args.isNull(k)) args.opt(k) else null

    return when (name) {
        "read" -> "[read: " + shorten(str("path", "file_path")) + lineRange(numOrNull("offset"), numOrNull("limit")) + "]"
        "write" -> "[write: " + shorten(str("path", "file_path")) + "]"
        "edit" -> "[edit: " + shorten(str("path", "file_path")) + "]"
        "bash" -> {
            val raw = str("command")
            val cmd = raw.replace('\n', ' ').replace('\t', ' ').trim()
            "[bash: " + cmd.take(50) + (if (raw.length > 50) "..." else "") + "]"
        }
        "grep" -> "[grep: /" + str("pattern") + "/ in " + shorten(str("path").ifBlank { "." }) + "]"
        "find" -> "[find: " + str("pattern") + " in " + shorten(str("path").ifBlank { "." }) + "]"
        "ls" -> "[ls: " + shorten(str("path").ifBlank { "." }) + "]"
        else -> {
            val compact = args.toString()
            "[" + name + ": " + compact.take(40) + (if (compact.length > 40) "..." else "") + "]"
        }
    }
}
