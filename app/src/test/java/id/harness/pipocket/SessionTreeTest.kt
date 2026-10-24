package id.harness.pipocket

import id.harness.pipocket.ui.TREE_MAX_INDENT
import id.harness.pipocket.ui.TreeEntry
import id.harness.pipocket.ui.TreeToolCall
import id.harness.pipocket.ui.buildTreeRows
import id.harness.pipocket.ui.canForkAt
import id.harness.pipocket.ui.entryDisplay
import id.harness.pipocket.ui.treeFilterLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话树的行构造（过滤 / 连接线 / 文案）。
 *
 * 规则来自 pi 的**真源码** `src/modes/interactive/components/tree-selector.ts`
 * （用 `tools/pi-src-extract.mjs` 从 `.js.map` 的 sourcesContent 还原），不是照着截图猜的。
 */
class SessionTreeTest {

    private fun msg(
        id: String,
        parent: String?,
        role: String,
        text: String = "",
        stopReason: String? = null,
        label: String? = null,
        toolName: String? = null,
        argsText: String? = null,
        command: String? = null,
        ts: String = "2026-10-22T10:00:00.000Z",
    ) = TreeEntry(
        id = id, parentId = parent, type = "message", role = role, text = text,
        stopReason = stopReason, label = label, toolName = toolName, argsText = argsText,
        command = command, timestamp = ts,
    )

    private fun bookkeeping(id: String, parent: String?, type: String) =
        TreeEntry(id = id, parentId = parent, type = type, timestamp = "2026-10-22T10:00:00.000Z")

    // ── 过滤 ───────────────────────────────────────────────────────────────

    @Test
    fun defaultHidesBookkeepingEntries() {
        val rows = buildTreeRows(
            listOf(
                msg("u1", null, "user", "你好"),
                bookkeeping("m1", "u1", "model_change"),
                bookkeeping("t1", "m1", "thinking_level_change"),
                bookkeeping("s1", "t1", "session_info"),
                msg("a1", "s1", "assistant", "在"),
            ),
            leafId = "a1",
        )
        assertEquals(listOf("u1", "a1"), rows.map { it.entry.id })
    }

    @Test
    fun defaultHidesAssistantWithOnlyToolCalls() {
        val rows = buildTreeRows(
            listOf(
                msg("u1", null, "user", "读一下"),
                // 只有工具调用、没正文 ⇒ 默认不显示
                msg("a1", "u1", "assistant", ""),
                msg("r1", "a1", "toolResult", "output", toolName = "read"),
            ),
            leafId = "r1",
        )
        assertEquals(listOf("u1", "r1"), rows.map { it.entry.id })
    }

    @Test
    fun assistantWithOnlyToolCallsShowsIfItIsTheLeaf() {
        // 当前 leaf 永远显示（否则看不出自己在哪）—— tree-selector.ts:346
        val rows = buildTreeRows(
            listOf(msg("u1", null, "user", "x"), msg("a1", "u1", "assistant", "")),
            leafId = "a1",
        )
        assertEquals(listOf("u1", "a1"), rows.map { it.entry.id })
    }

    @Test
    fun assistantErrorOrAbortedStillShows() {
        val rows = buildTreeRows(
            listOf(
                msg("a1", null, "assistant", "", stopReason = "aborted"),
                msg("a2", "a1", "assistant", "", stopReason = "error"),
            ),
            leafId = null,
        )
        assertEquals(listOf("a1", "a2"), rows.map { it.entry.id })
    }

    @Test
    fun noToolsDropsToolResults() {
        val entries = listOf(
            msg("u1", null, "user", "跑一下"),
            msg("r1", "u1", "toolResult", "ok", toolName = "bash"),
            msg("a1", "r1", "assistant", "好了"),
        )
        assertEquals(listOf("u1", "r1", "a1"), buildTreeRows(entries, "a1").map { it.entry.id })
        assertEquals(listOf("u1", "a1"), buildTreeRows(entries, "a1", filter = "no-tools").map { it.entry.id })
    }

    @Test
    fun userOnlyKeepsOnlyUserMessages() {
        val entries = listOf(
            msg("u1", null, "user", "一"),
            msg("a1", "u1", "assistant", "回"),
            msg("u2", "a1", "user", "二"),
        )
        assertEquals(listOf("u1", "u2"), buildTreeRows(entries, "u2", filter = "user-only").map { it.entry.id })
    }

    @Test
    fun labeledOnlyRequiresLabel() {
        val entries = listOf(
            msg("u1", null, "user", "一", label = "起点"),
            msg("a1", "u1", "assistant", "回"),
        )
        assertEquals(listOf("u1"), buildTreeRows(entries, "a1", filter = "labeled-only").map { it.entry.id })
    }

    @Test
    fun allKeepsBookkeeping() {
        val entries = listOf(msg("u1", null, "user", "一"), bookkeeping("m1", "u1", "model_change"))
        assertEquals(2, buildTreeRows(entries, "m1", filter = "all").size)
    }

    @Test
    fun usageEntriesAreAlwaysDropped() {
        // tree-selector.ts:342 —— 连 "all" 档也不显示
        val entries = listOf(msg("u1", null, "user", "一"), bookkeeping("g1", "u1", "usage"))
        assertEquals(listOf("u1"), buildTreeRows(entries, "g1", filter = "all").map { it.entry.id })
    }

    // ── 连接线 ─────────────────────────────────────────────────────────────

    @Test
    fun linearChainHasNoConnectors() {
        val rows = buildTreeRows(
            listOf(msg("u1", null, "user", "一"), msg("a1", "u1", "assistant", "回"), msg("u2", "a1", "user", "二")),
            leafId = "u2",
        )
        // 单孩子不画连接线，但缩进宽度保持 depth*2（终端也是靠缩进区分层级）
        assertEquals(listOf("", "  ", "    "), rows.map { it.gutterText })
        assertEquals(listOf(0, 1, 2), rows.map { it.depth })
        assertEquals(listOf(false, false, false), rows.map { it.showConnector })
    }

    @Test
    fun branchDrawsConnectorsOnlyAtBranchingLevel() {
        // u1 有两个孩子（a1 / a2）⇒ 这一层画 ├─ / └─
        val rows = buildTreeRows(
            listOf(
                msg("u1", null, "user", "根"),
                msg("a1", "u1", "assistant", "左边的分支"),
                msg("a2", "u1", "assistant", "右边的分支"),
            ),
            leafId = "a2",
        )
        assertEquals(listOf("", "├─", "└─"), rows.map { it.gutterText })
        assertTrue(rows[1].showConnector)
        assertFalse(rows[1].isLast)
        assertTrue(rows[2].isLast)
    }

    @Test
    fun deepBranchKeepsAncestorGutter() {
        // 根 →(两个孩子) [a1 → a1x] / [a2]
        val rows = buildTreeRows(
            listOf(
                msg("u1", null, "user", "根"),
                msg("a1", "u1", "assistant", "左"),
                msg("a1x", "a1", "user", "左的孩子"),
                msg("a2", "u1", "assistant", "右"),
            ),
            leafId = "a1x",
        )
        // a1 不是最后一个 ⇒ 它的孩子那行要画 │；
        // 但 a1x 是 a1 的唯一孩子 ⇒ 本层不画连接线（pi: "show connector - true if parent has
        // multiple children"），只补空格保持缩进
        assertEquals("", rows[0].gutterText)
        assertEquals("├─", rows[1].gutterText)
        assertEquals("│   ", rows[2].gutterText)
        assertEquals("└─", rows[3].gutterText)
    }

    @Test
    fun filteredOutParentReattachesToNearestVisibleAncestor() {
        // model_change 被过滤掉，a1 应该挂回 u1 下面（层级 = u1 的下一层）
        val rows = buildTreeRows(
            listOf(
                msg("u1", null, "user", "根"),
                bookkeeping("m1", "u1", "model_change"),
                msg("a1", "m1", "assistant", "回"),
            ),
            leafId = "a1",
        )
        assertEquals(listOf("u1", "a1"), rows.map { it.entry.id })
        assertEquals(listOf(0, 1), rows.map { it.depth })
    }

    @Test
    fun leafIsMarked() {
        val rows = buildTreeRows(listOf(msg("u1", null, "user", "一"), msg("a1", "u1", "assistant", "二")), "u1")
        assertEquals(listOf(true, false), rows.map { it.isLeaf })
    }

    // ── 文案 ───────────────────────────────────────────────────────────────

    @Test
    fun displayTextMatchesTerminalWording() {
        assertEquals("user: 你好", entryDisplay(msg("u", null, "user", "你好")))
        assertEquals("assistant: 在", entryDisplay(msg("a", null, "assistant", "在")))
        assertEquals("assistant: (aborted)", entryDisplay(msg("a", null, "assistant", "", stopReason = "aborted")))
        assertEquals("assistant: (no content)", entryDisplay(msg("a", null, "assistant", "")))
        assertEquals("[bash]: ls -al", entryDisplay(msg("b", null, "bashExecution", command = "ls -al")))
        assertEquals("[read: src/Main.kt]", entryDisplay(msg("t", null, "toolResult", toolName = "read", argsText = "{\"path\":\"src/Main.kt\"}")))
        assertEquals("[tool]", entryDisplay(msg("t", null, "toolResult")))
        assertEquals(
            "[compaction: 12k tokens]",
            entryDisplay(TreeEntry(id = "c", parentId = null, type = "compaction", tokensBefore = 12_400)),
        )
        assertEquals(
            "[thinking: high]",
            entryDisplay(TreeEntry(id = "t", parentId = null, type = "thinking_level_change", thinkingLevel = "high")),
        )
        assertEquals(
            "[title: 修 mermaid]",
            entryDisplay(TreeEntry(id = "s", parentId = null, type = "session_info", sessionName = "修 mermaid")),
        )
        assertEquals(
            "[title: empty]",
            entryDisplay(TreeEntry(id = "s", parentId = null, type = "session_info")),
        )
    }

    @Test
    fun labelIsPrefixedBeforeBody() {
        val text = entryDisplay(msg("u", null, "user", "从这里改", label = "起点"))
        assertTrue(text, text.startsWith("\uD83C\uDFF7 起点"))
        assertTrue(text, text.endsWith("user: 从这里改"))
    }

    @Test
    fun multilineContentIsCollapsedToOneLine() {
        assertEquals("user: 一 二", entryDisplay(msg("u", null, "user", "一\n二")))
    }

    // ── 搜索 ───────────────────────────────────────────────────────────────

    @Test
    fun searchRequiresAllTokens() {
        val entries = listOf(
            msg("u1", null, "user", "修 mermaid 字号"),
            msg("a1", "u1", "assistant", "改好了"),
        )
        assertEquals(listOf("u1"), buildTreeRows(entries, "a1", search = "mermaid").map { it.entry.id })
        assertEquals(listOf("u1"), buildTreeRows(entries, "a1", search = "修 字号").map { it.entry.id })
        assertTrue(buildTreeRows(entries, "a1", search = "mermaid 不存在").isEmpty())
        // 大小写不敏感（终端也是）
        assertEquals(listOf("u1"), buildTreeRows(entries, "a1", search = "MERMAID").map { it.entry.id })
    }

    @Test
    fun searchAlsoLooksAtLabels() {
        val entries = listOf(msg("u1", null, "user", "无关内容", label = "重要节点"))
        assertEquals(listOf("u1"), buildTreeRows(entries, "u1", search = "重要").map { it.entry.id })
    }

    // ── 工具调用配对（pi 的 toolCallMap）──────────────────────────────────

    @Test
    fun toolResultTakesNameAndArgsFromItsToolCall() {
        // assistant 里那个 toolCall 带名字+参数；toolResult 自己只有 toolName/toolCallId
        val assistant = TreeEntry(
            id = "a1", parentId = null, type = "message", role = "assistant",
            toolCalls = listOf(id.harness.pipocket.ui.TreeToolCall("c1", "read", "{\"path\":\"src/Main.kt\"}")),
        )
        val result = TreeEntry(
            id = "r1", parentId = "a1", type = "message", role = "toolResult",
            toolName = "read", toolCallId = "c1",
        )
        val rows = buildTreeRows(listOf(assistant, result), leafId = "r1")
        // assistant 没正文又不是 leaf ⇒ 被过滤；结果行要能画出 read 的路径
        assertEquals(listOf("r1"), rows.map { it.entry.id })
        assertEquals("[read: src/Main.kt]", rows[0].display)
    }

    // ── 工具标签（照 pi 的 tree-selector.ts:947 formatToolCall）────────────────

    @Test
    fun toolCallLabelsMatchTerminalWording() {
        assertEquals("[read: src/Main.kt]", id.harness.pipocket.ui.toolCallLabel("read", "{\"path\":\"src/Main.kt\"}"))
        assertEquals(
            "[read: src/Main.kt:12-30]",
            id.harness.pipocket.ui.toolCallLabel("read", "{\"path\":\"src/Main.kt\",\"offset\":12,\"limit\":19}"),
        )
        assertEquals(
            "[read: src/Main.kt:12]",
            id.harness.pipocket.ui.toolCallLabel("read", "{\"path\":\"src/Main.kt\",\"offset\":12}"),
        )
        assertEquals("[write: out/a.txt]", id.harness.pipocket.ui.toolCallLabel("write", "{\"path\":\"out/a.txt\"}"))
        assertEquals("[edit: ui/Mermaid.kt]", id.harness.pipocket.ui.toolCallLabel("edit", "{\"path\":\"ui/Mermaid.kt\"}"))
        assertEquals("[bash: ls -al]", id.harness.pipocket.ui.toolCallLabel("bash", "{\"command\":\"ls -al\"}"))
        assertEquals(
            "[grep: /TODO/ in src]",
            id.harness.pipocket.ui.toolCallLabel("grep", "{\"pattern\":\"TODO\",\"path\":\"src\"}"),
        )
        assertEquals(
            "[find: *.kt in .]",
            id.harness.pipocket.ui.toolCallLabel("find", "{\"pattern\":\"*.kt\"}"),
        )
        assertEquals("[ls: /tmp]", id.harness.pipocket.ui.toolCallLabel("ls", "{\"path\":\"/tmp\"}"))
    }

    @Test
    fun toolCallLabelShortensHomeAndTruncatesLongBash() {
        assertEquals(
            "[read: ~/workspace/x.kt]",
            id.harness.pipocket.ui.toolCallLabel(
                "read",
                "{\"path\":\"/home/me/workspace/x.kt\"}",
                home = "/home/me",
            ),
        )
        val longCmd = "echo " + "a".repeat(80)
        val label = id.harness.pipocket.ui.toolCallLabel("bash", "{\"command\":\"$longCmd\"}")
        assertTrue(label, label.startsWith("[bash: echo aaa"))
        assertTrue(label, label.endsWith("...]"))
    }

    @Test
    fun unknownToolFallsBackToCompactJson() {
        val label = id.harness.pipocket.ui.toolCallLabel("mcp__x__do", "{\"a\":1}")
        assertEquals("[mcp__x__do: {\"a\":1}]", label)
    }

    @Test
    fun toolResultWithoutCallFallsBackToBracketedName() {
        // 历史里找不到对应调用时，终端退回 `[toolName]`（tree-selector.ts:800）
        val result = TreeEntry(id = "r1", parentId = null, type = "message", role = "toolResult", toolName = "bash")
        assertEquals("[bash]", buildTreeRows(listOf(result), "r1")[0].display)
    }

    // ── 可读性（2026-10-22 重做：类型标签 / 摘要 / 折叠）─────────────────────

    @Test
    fun tagsAreShortChineseWords() {
        assertEquals("我", id.harness.pipocket.ui.entryTag(msg("u", null, "user", "x")))
        assertEquals("AI", id.harness.pipocket.ui.entryTag(msg("a", null, "assistant", "x")))
        assertEquals("工具", id.harness.pipocket.ui.entryTag(msg("t", null, "toolResult", toolName = "read")))
        assertEquals("命令", id.harness.pipocket.ui.entryTag(msg("b", null, "bashExecution", command = "ls")))
        assertEquals(
            "压缩",
            id.harness.pipocket.ui.entryTag(TreeEntry(id = "c", parentId = null, type = "compaction", tokensBefore = 1000)),
        )
        assertEquals(
            "标题",
            id.harness.pipocket.ui.entryTag(TreeEntry(id = "s", parentId = null, type = "session_info")),
        )
        // 标签都不超过 3 个字（行首固定宽 34dp 要放得下）
        for (role in listOf("user", "assistant", "toolResult", "bashExecution")) {
            assertTrue(id.harness.pipocket.ui.entryTag(msg("x", null, role)).length <= 3)
        }
    }

    @Test
    fun summaryDropsTerminalPrefixes() {
        // 行首已经有类型标签了，正文不能再带一遍 `user: `
        assertEquals("你好", id.harness.pipocket.ui.entrySummary(msg("u", null, "user", "你好")))
        assertEquals("在", id.harness.pipocket.ui.entrySummary(msg("a", null, "assistant", "在")))
        // 没正文时给一句人话，而不是 `(no content)`
        assertEquals("(无正文)", id.harness.pipocket.ui.entrySummary(msg("a", null, "assistant", "")))
        assertEquals(
            "(已中断)",
            id.harness.pipocket.ui.entrySummary(msg("a", null, "assistant", "", stopReason = "aborted")),
        )
        // 压缩行说人话（不再用 `[compaction: 12k tokens]` 的括号格式）
        assertEquals(
            "12k tokens",
            id.harness.pipocket.ui.entrySummary(
                TreeEntry(id = "c", parentId = null, type = "compaction", tokensBefore = 12_400),
            ),
        )
        // 工具行仍用终端的 `[read: …]` 措辞（这是内容，不是前缀）
        assertEquals(
            "[read: src/Main.kt]",
            id.harness.pipocket.ui.entrySummary(
                msg("t", null, "toolResult", toolName = "read", argsText = "{\"path\":\"src/Main.kt\"}"),
            ),
        )
    }

    @Test
    fun foldingHidesDescendantsAndCountsThem() {
        // 根 → a1 → a2，a1 有两个孩子
        val entries = listOf(
            msg("u1", null, "user", "根"),
            msg("a1", "u1", "assistant", "中间"),
            msg("x1", "a1", "user", "孩子一"),
            msg("x2", "a1", "user", "孩子二"),
        )
        val open = buildTreeRows(entries, leafId = "x2")
        assertEquals(listOf("u1", "a1", "x1", "x2"), open.map { it.entry.id })

        val folded = buildTreeRows(entries, leafId = "x2", collapsed = setOf("a1"))
        // a1 自己还在，孩子全藏起来，并告诉用户藏了几个
        assertEquals(listOf("u1", "a1"), folded.map { it.entry.id })
        assertEquals(2, folded.last().hiddenCount)
        assertTrue("折叠行要知道自己有孩子（不然点不动）", folded.last().hasChildren)
    }

    @Test
    fun foldingRootHidesEverythingBelow() {
        val entries = listOf(msg("u1", null, "user", "根"), msg("a1", "u1", "assistant", "回"))
        val folded = buildTreeRows(entries, leafId = "a1", collapsed = setOf("u1"))
        assertEquals(listOf("u1"), folded.map { it.entry.id })
        assertEquals(1, folded[0].hiddenCount)
    }

    @Test
    fun rowsCarryForkableFlagOnlyForUserMessages() {
        val rows = buildTreeRows(
            listOf(msg("u1", null, "user", "我"), msg("a1", "u1", "assistant", "AI")),
            leafId = "a1",
        )
        assertEquals(listOf(true, false), rows.map { it.forkable })
    }

    // ── 分叉资格 ───────────────────────────────────────────────────────────

    @Test
    fun onlyUserMessagesCanBeForked() {
        assertTrue(canForkAt(msg("u", null, "user", "x")))
        assertFalse(canForkAt(msg("a", null, "assistant", "x")))
        assertFalse(canForkAt(msg("t", null, "toolResult", "x")))
        assertFalse(canForkAt(TreeEntry(id = "c", parentId = null, type = "compaction")))
    }

    @Test
    fun filterLabelsAreChinese() {
        assertEquals("默认", treeFilterLabel("default"))
        assertEquals("无工具", treeFilterLabel("no-tools"))
        assertEquals("仅我", treeFilterLabel("user-only"))
        assertEquals("仅标签", treeFilterLabel("labeled-only"))
        assertEquals("全部", treeFilterLabel("all"))
        assertEquals("默认", treeFilterLabel("unknown"))
    }

    @Test
    fun emptyTreeIsEmpty() {
        assertTrue(buildTreeRows(emptyList(), null).isEmpty())
    }

    // ── 性能回归（真实会话 2000+ 条，做不到 O(n) 就会卡死）────────────────────

    @Test
    fun largeTreeBuildsFastEnough() {
        // 造 3000 条：user / assistant / toolResult 三态循环，偶尔加个分支
        val entries = ArrayList<TreeEntry>(3000)
        var parent: String? = null
        for (i in 0 until 3000) {
            val id = "e" + i.toString().padStart(5, '0')
            val e = when (i % 3) {
                0 -> TreeEntry(id = id, parentId = parent, type = "message", role = "user", text = "第 $i 条")
                1 -> TreeEntry(
                    id = id, parentId = parent, type = "message", role = "assistant", text = "回 $i",
                    toolCalls = listOf(TreeToolCall("c$i", "read", "{\"path\":\"/f$i\"}")),
                )
                else -> TreeEntry(
                    id = id, parentId = parent, type = "message", role = "toolResult",
                    toolName = "read", toolCallId = "c" + (i - 1), text = "…",
                )
            }
            entries.add(e)
            parent = id
        }

        val t0 = System.nanoTime()
        val rows = buildTreeRows(entries, leafId = parent)
        val ms = (System.nanoTime() - t0) / 1_000_000

        assertEquals(3000, rows.size)
        // 门槛 1500ms：以前这里是 O(n²)（每行全表扫），3000 条直接挂住 > 60s；
        // 现在应该是几十毫秒，留足 CI 抖动余量。
        assertTrue("3000 条构建耗时 ${ms}ms，超出门槛（O(n²) 回来了？）", ms < 1500)
    }

    @Test
    fun largeTreeFoldedIsCheapToo() {
        val entries = ArrayList<TreeEntry>(2000)
        var parent: String? = null
        for (i in 0 until 2000) {
            val id = "e" + i.toString().padStart(5, '0')
            entries.add(TreeEntry(id = id, parentId = parent, type = "message", role = "user", text = "第 $i 条"))
            parent = id
        }
        val t0 = System.nanoTime()
        val folded = buildTreeRows(entries, leafId = parent, collapsed = setOf("e00000"))
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertEquals(1, folded.size)
        assertEquals(1999, folded[0].hiddenCount)
        assertTrue("折叠 2000 条耗时 ${ms}ms", ms < 1500)
    }

    @Test
    fun deepChainFoldDoesNotStackOverflow() {
        // 真实会话就是一条几千层的长链：折叠必须**迭代**（递归会爆栈，模拟器上崩过一次）
        val entries = ArrayList<TreeEntry>(5000)
        var parent: String? = null
        for (i in 0 until 5000) {
            val id = "d" + i.toString().padStart(5, '0')
            entries.add(TreeEntry(id = id, parentId = parent, type = "message", role = "user", text = "第 $i 层"))
            parent = id
        }
        val folded = buildTreeRows(entries, leafId = parent, collapsed = setOf("d00000"))
        assertEquals(1, folded.size)
        assertEquals(4999, folded[0].hiddenCount)
    }

    @Test
    fun indentIsCappedOnDeepChains() {
        // 3000 层长链：缩进必须封顶（否则 14dp×3000 把内容顶出屏幕，AND 每行 O(depth) 会卡）
        val entries = ArrayList<TreeEntry>(3000)
        var parent: String? = null
        for (i in 0 until 3000) {
            val id = "d" + i.toString().padStart(5, '0')
            entries.add(TreeEntry(id = id, parentId = parent, type = "message", role = "user", text = "第 $i 层"))
            parent = id
        }
        val t0 = System.nanoTime()
        val rows = buildTreeRows(entries, leafId = parent)
        val ms = (System.nanoTime() - t0) / 1_000_000

        assertEquals(3000, rows.size)
        assertTrue("深度封顶在 $TREE_MAX_INDENT", rows.all { it.depth <= TREE_MAX_INDENT })
        assertTrue("祖先 gutter 数量也要封顶", rows.all { it.gutters.size <= TREE_MAX_INDENT })
        assertEquals("最深的行要标出省略了多少层", 3000 - 1 - TREE_MAX_INDENT, rows.last().deepLevels)
        assertEquals(0, rows[0].deepLevels)
        assertTrue("3000 层构建 ${ms}ms 太慢", ms < 1500)
    }
}
