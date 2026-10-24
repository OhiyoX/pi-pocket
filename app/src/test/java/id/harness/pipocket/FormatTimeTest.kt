package id.harness.pipocket

import id.harness.pipocket.ui.localTimeLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 「关于」里那两行时间必须显示**设备本地时间**。
 *
 * 回归背景（用户 2026-10-22）：构建时间曾经直接回显构建机 JVM 的时区 —— Termux 里没设 TZ 时
 * Java 落回 UTC，于是比本地慢 8 小时。
 */
class FormatTimeTest {

    private fun expected(instant: Instant): String =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault())
            .format(instant)

    @Test
    fun utcInstantRendersInLocalZone() {
        val iso = "2026-10-22T11:14:17.123Z"
        val out = localTimeLabel(iso)
        assertEquals(expected(Instant.parse(iso)), out)
        // 形状固定：yyyy-MM-dd HH:mm
        assertTrue("got: $out", Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}""").matches(out))
        // 不能把 UTC 原样丢出来（除非本机时区就是 UTC）
        if (ZoneId.systemDefault().rules.getOffset(Instant.parse(iso)).totalSeconds != 0) {
            assertNotEquals("2026-10-22 11:14", out)
        }
    }

    @Test
    fun epochMillisRendersInLocalZone() {
        val ms = 1_790_000_000_000L
        assertEquals(expected(Instant.ofEpochMilli(ms)), localTimeLabel(ms))
    }

    @Test
    fun offsetDateTimeStillParsed() {
        // 老构建产物里可能是带偏移的 ISO（如 …+08:00）
        val out = localTimeLabel("2026-10-22T19:14:17+08:00")
        assertEquals(expected(Instant.parse("2026-10-22T11:14:17Z")), out)
    }

    @Test
    fun garbageFallsBackToRaw() {
        assertEquals("nogit", localTimeLabel("nogit"))
        assertEquals("", localTimeLabel(null as String?))
        assertEquals("", localTimeLabel(""))
        assertEquals("", localTimeLabel(0L))
        assertEquals("", localTimeLabel(-1L))
    }
}
