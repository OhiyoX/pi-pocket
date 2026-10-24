package id.harness.pipocket

import id.harness.pipocket.ui.MdBlock
import id.harness.pipocket.ui.Markdown
import org.junit.Assert.assertEquals
import org.junit.Test

/** 复现：用户 2026-10-21 截图里那张四列表的第一列（列/位置/改动）渲染成空白。 */
class TableParseReproTest {
    private val src = """
`sinkable` 恒等于 0 ⇒ 公式退化成每列 `usable/columns`。你的截图就是它：

| 列 | 自然宽（估） | 旧算法给 | 结果 |
| --- | --- | --- | --- |
| 位置 | ≈152dp | 209dp | 大片空白 |
| 改动 | ≈400dp+ | 209dp | 约 31 字符宽 → `record.parentToolCa`／`llId` 断词 |
""".trimIndent()

    @Test
    fun headerAndRowsKeepFirstColumn() {
        val table = Markdown.parse(src).filterIsInstance<MdBlock.Table>().single()
        assertEquals(listOf("列", "自然宽（估）", "旧算法给", "结果"), table.header)
        assertEquals(listOf("位置", "≈152dp", "209dp", "大片空白"), table.rows[0])
        assertEquals(2, table.rows.size)
        assertEquals("改动", table.rows[1][0])
    }
}
