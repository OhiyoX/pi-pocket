package id.harness.pipocket

import id.harness.pipocket.ui.maxMinFair
import id.harness.pipocket.ui.tableWidths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 表格列宽（方案 D：词底线 + 两次水位法）单测。
 *
 * 期望值不是拍脑袋写的：同一个算法先在 `termux/table-width-lab.py` 里跑
 * （10 个固定 case + 200 例 fuzz，代价 = 剁词×5 + 总行数），选出方案 D 之后
 * 再把它的输出抄成断言。数字对不上 = Kotlin 与 Python 两个实现不一致。
 *
 * 演进史（每一版都在用户真机截图上翻过车）：
 * 1. 平均分配：`share = usable/columns` 已经分完，剩余恒为 0；
 * 2. 只按自然宽水位：键列里一个长标识符（`app AppState.onPiRecord`≈204dp）就能吃掉一半；
 * 3. 词底线超预算时整体等比缩：短列被拖下水，剁词最多（代价 214 vs 163）；
 * 4. 保险丝判据写成「某一列 < 44dp」：把正解误判成放不下（见 `reportedTimestampPathTable`）。
 */
class TableWidthsTest {

    private fun round(x: List<Float>) = x.map { Math.round(it * 10) / 10f }

    /** 用户那张 `位置 | 改动`（真实的 natural/词宽，可用 298dp）：内容列必须明显更宽。 */
    @Test
    fun keyColumnDoesNotEatHalfTheTable() {
        val r = tableWidths(
            natural = listOf(164.2f, 719.4f),
            minContent = listOf(135.7f, 235.6f),
            usable = 298f,
        )
        assertFalse(r.scroll)
        assertEquals(listOf(135.7f, 162.3f), round(r.widths))
        assertTrue("内容列要比键列宽", r.widths[1] > r.widths[0])
        assertEquals(298f, r.widths.sum(), 0.5f)
    }

    /** 用户那张 4 列表（列/自然宽/旧算法给/结果，可用 266dp）——D 与 oracle 同分。 */
    @Test
    fun fourColumnRealCase() {
        val r = tableWidths(
            natural = listOf(22.8f, 68.4f, 45.6f, 289.7f),
            minContent = listOf(22.8f, 68.4f, 45.6f, 164.2f),
            usable = 266f,
        )
        assertFalse(r.scroll)
        assertEquals(listOf(22.8f, 68.4f, 45.6f, 129.2f), round(r.widths))
        assertEquals(266f, r.widths.sum(), 0.5f)
    }

    /** 3 列表（截图/列宽实测/结论，可用 282dp）：长列拿自然宽，短列只拿词宽。 */
    @Test
    fun threeColumnRealCase() {
        val r = tableWidths(
            natural = listOf(152.7f, 199.9f, 159.6f),
            minContent = listOf(52.7f, 57.0f, 159.6f),
            usable = 282f,
        )
        assertFalse(r.scroll)
        assertEquals(listOf(59.1f, 63.4f, 159.6f), round(r.widths))
        assertEquals(282f, r.widths.sum(), 0.5f)
    }

    /** 装得下：按自然宽比例补齐撑满（两侧对齐，不滚）。 */
    @Test
    fun fitsProportionallyExpands() {
        val r = tableWidths(
            natural = listOf(100f, 200f),
            minContent = listOf(90f, 90f),
            usable = 500f,
        )
        assertFalse(r.scroll)
        assertEquals(listOf(166.7f, 333.3f), round(r.widths))
        assertEquals(500f, r.widths.sum(), 0.01f)
    }

    /** 装下且内容全空：按自然宽比例分 ⇒ 等分，不崩。 */
    @Test
    fun emptyContentSplitsEqually() {
        val r = tableWidths(listOf(0f, 0f), listOf(0f, 0f), usable = 400f)
        assertFalse(r.scroll)
        assertEquals(listOf(200f, 200f), round(r.widths))
    }

    /** 单列：独占可用宽度。 */
    @Test
    fun singleColumnTakesAll() {
        val r = tableWidths(listOf(9999f), listOf(40f), usable = 300f)
        assertFalse(r.scroll)
        assertEquals(listOf(300f), round(r.widths))
    }

    /** 超长 token（URL）：宽列拿大头，但不能把窄列压到 0。 */
    @Test
    fun longUrlColumnGetsTheRoom() {
        val r = tableWidths(
            natural = listOf(28.6f, 378.4f),
            minContent = listOf(28.6f, 378.4f),
            usable = 298f,
        )
        assertFalse(r.scroll)
        assertEquals(listOf(28.6f, 269.4f), round(r.widths))
        assertTrue("短列不能被压没", r.widths[0] >= 28f)
    }

    /** 五列（窄列 + 一列宽）：短列各拿自己的自然宽，宽的吃掉剩下的。 */
    @Test
    fun fiveColumns() {
        val r = tableWidths(
            natural = listOf(100f, 14.3f, 21.4f, 28.6f, 35.7f),
            minContent = listOf(28.6f, 14.3f, 21.4f, 28.6f, 35.7f),
            usable = 250f,
        )
        assertFalse(r.scroll)
        assertEquals(listOf(125f, 17.9f, 26.8f, 35.8f, 44.6f), round(r.widths))
        assertEquals(250f, r.widths.sum(), 1f)
    }

    /** 保险丝：整张表本来就摆不下（均分额 < 44dp）⇒ 自然宽（封顶）+ 横滚。 */
    @Test
    fun starvedColumnFallsBackToScroll() {
        // 8 列、每列都有长词：均分只有 300/8 = 37.5dp < 44dp ⇒ 本来就摆不下
        val r = tableWidths(
            natural = List(8) { 200f },
            minContent = List(8) { 120f },
            usable = 300f,
        )
        assertTrue(r.scroll)
        assertTrue("回退后每列至少可读", r.widths.all { it >= 44f })
        assertTrue("封顶别失控", r.widths.all { it <= 132f })
    }

    /**
     * 用户 2026-10-21 23:56 截图那张 `时间 | 路径`（可用 298dp）：**不许**触发保险丝。
     *
     * 旧判据（「某一列 < 44dp 且有长内容」）会把这张表判成放不下 ⇒ 夹成 79/132
     * （共 211dp，白扔 87dp 可用宽）⇒ 长路径挤在 132dp 里疯狂换行（真机实测 30 行、11 个词被剁）。
     * 新判据（`usable/n = 149 ≥ 44`）下就是水填法的结果 = oracle：`36 / 262`（真机 26 行、4 个词被剁）。
     */
    @Test
    fun reportedTimestampPathTable() {
        val r = tableWidths(
            natural = listOf(79f, 872f),
            minContent = listOf(36f, 407f),
            usable = 298f,
        )
        assertFalse("这张表装得下，不该横滚", r.scroll)
        assertEquals(listOf(36f, 262f), round(r.widths))
        assertEquals("必须把可用宽用满", 298f, r.widths.sum(), 0.5f)
        assertTrue("路径列要远比时间列宽", r.widths[1] > 3 * r.widths[0])
    }

    /** 不横滚时就**不许**溢出：总宽必须恰好等于可用宽（两侧对齐的根基）。 */
    @Test
    fun neverOverflowsWithoutScroll() {
        val cases = listOf(
            Triple(listOf(164.2f, 719.4f), listOf(135.7f, 235.6f), 298f),
            Triple(listOf(22.8f, 68.4f, 45.6f, 289.7f), listOf(22.8f, 68.4f, 45.6f, 164.2f), 266f),
            Triple(listOf(152.7f, 199.9f, 159.6f), listOf(52.7f, 57.0f, 159.6f), 282f),
            Triple(List(5) { 200f }, List(5) { 100f }, 300f),
            Triple(listOf(7.1f, 42.8f, 42.8f, 34.2f), listOf(7.1f, 42.8f, 42.8f, 34.2f), 266f),
        )
        for ((natural, minContent, usable) in cases) {
            val r = tableWidths(natural, minContent, usable)
            if (!r.scroll) assertEquals("总宽要等于可用宽", usable, r.widths.sum(), 1.0f)
            assertTrue("不许出现负宽", r.widths.all { it >= 0f })
        }
    }

    /** maxMinFair：需求小的先满足，剩下的在需求大的之间等分。 */
    @Test
    fun maxMinFairSatisfiesSmallDemandsFirst() {
        assertEquals(listOf(10f, 45f, 45f), round(maxMinFair(listOf(10f, 200f, 200f), 100f)))
        assertEquals(listOf(10f, 20f, 30f), round(maxMinFair(listOf(10f, 20f, 30f), 500f)))
        assertEquals(listOf(50f, 50f), round(maxMinFair(listOf(100f, 100f), 100f)))
    }
}
