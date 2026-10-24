package id.harness.pipocket

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设备画布信息（用户 2026-10-23：让 agent 知道手机屏多大、正文多少 sp）。
 *
 * 这里钉两件事：
 * 1. 派生值算对（px / 正文宽度 / 内联上限）—— 桥那边的提示直接引用这些数；
 * 2. 签名只在"该重发"时变 —— 签名乱变会让桥不停回收 pi 子进程。
 */
class DeviceInfoTest {

    private fun phone(
        wdp: Int = 393,
        hdp: Int = 873,
        density: Float = 2.75f,
        theme: String = "dark",
        bodySp: Int = 12,
        chatScale: Float = 1f,
        fontScale: Float = 1f,
    ) = DeviceInfo.of(
        screenWdp = wdp, screenHdp = hdp, density = density, densityDpi = 440,
        fontScale = fontScale, theme = theme, bodySp = bodySp, toolNameSp = 12, toolSp = 11,
        metaSp = 11, inputSp = 14, chatScale = chatScale,
    )

    @Test
    fun derivesPixelsContentWidthAndInlineCap() {
        val d = phone()
        assertEquals(1081, d.screenWpx)            // 393 * 2.75 取整
        assertEquals(2401, d.screenHpx)            // 873 * 2.75 取整
        assertEquals(361, d.contentWidthDp)        // 左右各 16dp 的留白
        assertEquals(DeviceInfo.INLINE_HTML_MAX_DP, d.htmlInlineMaxDp)   // 固定安全上限
    }

    @Test
    fun inlineCapIsScreenIndependent() {
        // 内联不再按屏幕比例限高（那样会把内容截断）；上限只防跑飞
        assertEquals(DeviceInfo.INLINE_HTML_MAX_DP, phone(hdp = 400).htmlInlineMaxDp)
        assertEquals(DeviceInfo.INLINE_HTML_MAX_DP, phone(hdp = 1400).htmlInlineMaxDp)
    }

    @Test
    fun signatureChangesOnlyForMeaningfulChanges() {
        val base = phone()
        assertEquals(base.signature(), phone().signature())
        assertNotEquals(base.signature(), phone(theme = "light").signature())
        assertNotEquals(base.signature(), phone(bodySp = 14).signature())
        assertNotEquals(base.signature(), phone(chatScale = 1.2f).signature())
        assertNotEquals(base.signature(), phone(wdp = 800).signature())
    }

    @Test
    fun jsonCarriesEverythingTheBridgeNeeds() {
        val json = phone().toJson()
        for (key in listOf(
            "screenWdp", "screenHdp", "screenWpx", "screenHpx", "density", "densityDpi",
            "fontScale", "theme", "bodySp", "toolNameSp", "toolSp", "metaSp", "inputSp",
            "chatScale", "contentWidthDp", "htmlInlineMaxDp",
        )) {
            assertTrue("缺字段 $key", json.has(key))
        }
        assertEquals("dark", json.getString("theme"))
        assertEquals(361, json.getInt("contentWidthDp"))
    }

    @Test
    fun degenerateScreenValuesDoNotProduceZero() {
        val d = DeviceInfo.of(
            screenWdp = 0, screenHdp = 0, density = 0f, densityDpi = 0,
            fontScale = 1f, theme = "dark", bodySp = 12, toolNameSp = 12, toolSp = 11,
            metaSp = 11, inputSp = 14, chatScale = 1f,
        )
        assertEquals(1, d.screenWdp)
        assertTrue(d.screenWpx >= 1)
        assertTrue(d.contentWidthDp >= 120)
    }
}
