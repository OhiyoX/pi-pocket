package id.harness.pipocket

import id.harness.pipocket.ui.Markdown
import id.harness.pipocket.ui.MdBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject

/** Markdown 与消息解析的纯逻辑测试。 */
class ParsingTest {

    // ── Markdown 分块 ────────────────────────────────────────────────────
    @Test
    fun markdownSplitsBlocks() {
        val blocks = Markdown.parse(
            """
            # 标题
            段落一
            段落二

            - 列表项
            > 引用
            ```kotlin
            val a = 1
            ```
            ---
            """.trimIndent()
        )
        assertTrue(blocks.any { it is MdBlock.Heading && it.level == 1 })
        assertTrue(blocks.any { it is MdBlock.Bullet })
        assertTrue(blocks.any { it is MdBlock.Quote })
        assertTrue(blocks.any { it is MdBlock.Code && it.code.contains("val a = 1") })
        assertTrue(blocks.contains(MdBlock.Rule))
        // 连续两行应并成一个段落
        val paragraph = blocks.filterIsInstance<MdBlock.Paragraph>().first()
        assertEquals("段落一 段落二", paragraph.text)
    }

    @Test
    fun inlineKeepsEmojiSurrogatePairsIntact() {
        // 回归：按单个 Char 追加会把 👋 拆成两半 → 渲染成两个方框
        val text = Markdown.inline("你好 👋 完").text
        assertEquals("你好 👋 完", text)
        assertTrue(text.contains("\uD83D\uDC4B"))
    }

    @Test
    fun inlineStripsSimpleMarkers() {
        assertEquals("粗体", Markdown.inline("**粗体**").text)
        assertEquals("代码", Markdown.inline("`代码`").text)
        assertEquals("链接 (https://pi.dev)", Markdown.inline("[链接](https://pi.dev)").text)
        // 未闭合的标记原样保留（不吞字符）
        assertEquals("**没闭合", Markdown.inline("**没闭合").text)
    }

    // ── 表格（终端口径：画框） ──────────────────────────────────────────
    @Test
    fun markdownParsesTableIntoBoxLines() {
        val source = "| 月份 | 步数 |\n| --- | --- |\n| 1 月 | 182340 |"
        val table = Markdown.parseCached(source).single { it is MdBlock.Table } as MdBlock.Table
        assertEquals(listOf("月份", "步数"), table.header)
        assertEquals(listOf(listOf("1 月", "182340")), table.rows)

        val lines = Markdown.tableLines(table.header, table.rows)
        assertEquals(5, lines.size)
        assertTrue(lines.first().startsWith("┌"))
        assertTrue(lines.first().endsWith("┐"))
        assertTrue(lines[1].startsWith("│ 月份"))
        assertTrue(lines.last().startsWith("└"))
        // 列宽按**显示宽度**（中文算两格）：第 1 列 4（“1 月”），第 2 列 6（“182340”）
        assertEquals("┌──────┬────────┐", lines.first())
    }

    // ── assistant key：实时与历史重载必须一致 ────────────────────────────
    @Test
    fun assistantKeyMatchesBetweenLiveAndHistory() {
        val message = JSONObject()
            .put("role", "assistant")
            .put("timestamp", 42)
            .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "好")))
        val key = MessageParser.assistantKey(message)
        // 历史重载走的是同一个函数 ⇒ 展开/收起状态在重载后还在（之前用“时间戳-下标”会变）
        assertEquals(key, MessageParser.history(JSONArray().put(message)).single().key)
        // 内容不同 ⇒ key 不同
        val other = JSONObject(message.toString()).put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "别的")))
        assertTrue(key != MessageParser.assistantKey(other))
    }

    // ── 用户消息里的图 ────────────────────────────────────────────────
    @Test
    fun userMessageImagesParsedAndOmittedCounted() {
        val content = JSONArray()
            .put(JSONObject().put("type", "text").put("text", "看这张"))
            .put(JSONObject().put("type", "image").put("mimeType", "image/png").put("data", "AAAA"))
            .put(JSONObject().put("type", "image").put("mimeType", "image/png").put("omitted", true).put("bytes", 12))
        val (images, omitted) = MessageParser.imagesOf(content)
        assertEquals(1, images.size)
        assertEquals("image/png", images.first().mimeType)
        assertEquals(1, omitted)
        // 正文里不再塞 “[图片]”
        assertEquals("看这张", MessageParser.textOnly(content))
        val item = MessageParser.history(
            JSONArray().put(JSONObject().put("role", "user").put("timestamp", 7).put("content", content)),
        ).single()
        assertEquals(1, item.images.size)
        assertEquals(1, item.imagesOmitted)
        assertEquals(0, item.msgIndex)
    }

    // ── 消息解析 ────────────────────────────────────────────────────────
    @Test
    fun messageTextOfHandlesStringAndBlocks() {
        assertEquals("hi", MessageParser.textOf("hi"))
        val arr = JSONArray().put(JSONObject().put("type", "text").put("text", "a"))
            .put(JSONObject().put("type", "image"))
        assertEquals("a[图片]", MessageParser.textOf(arr))
    }

    @Test
    fun briefOfFlattensNewlinesAndTruncates() {
        val args = JSONObject().put("command", "line1\nline2\ttabbed")
        val brief = MessageParser.briefOf(args)
        assertTrue(brief.none { it == '\n' || it == '\t' })

        val long = MessageParser.briefOf(JSONObject().put("query", "x".repeat(200)))
        assertTrue(long.length <= 61) // 60 + 省略号
        assertTrue(long.endsWith("…"))
    }

    @Test
    fun resultSummaryReportsLinesAndEmpty() {
        assertEquals("无输出", MessageParser.resultSummary("", false))
        assertEquals("失败", MessageParser.resultSummary("boom", true))
        assertEquals("2 行", MessageParser.resultSummary("a\nb", false))
    }

    @Test
    fun historyPairsToolCallWithItsResult() {
        val messages = JSONArray()
            .put(JSONObject().put("role", "user").put("content", "跑一下").put("timestamp", 1))
            .put(
                JSONObject().put("role", "assistant").put("timestamp", 2).put(
                    "content",
                    JSONArray()
                        .put(JSONObject().put("type", "text").put("text", "好"))
                        .put(
                            JSONObject().put("type", "toolCall").put("id", "t1").put("name", "bash")
                                .put("arguments", JSONObject().put("command", "echo hi"))
                        ),
                ).put("usage", JSONObject().put("totalTokens", 10))
            )
            .put(
                JSONObject().put("role", "toolResult").put("toolCallId", "t1").put("toolName", "bash")
                    .put("isError", false).put("timestamp", 3)
                    .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "hi")))
            )
        val items = MessageParser.history(messages)
        val tool = items.single { it.isTool }
        assertEquals("bash", tool.toolName)
        assertEquals("echo hi", tool.toolBrief)
        assertEquals("hi", tool.toolResult)
        assertEquals(false, tool.toolIsError)
        // 只有一条 tool 行（结果没有重复成第二行）
        assertEquals(1, items.count { it.isTool })
    }
}
