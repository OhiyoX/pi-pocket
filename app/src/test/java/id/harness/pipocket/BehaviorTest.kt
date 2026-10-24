package id.harness.pipocket

import id.harness.pipocket.ui.BehaviorState
import id.harness.pipocket.ui.QUEUE_MODES
import id.harness.pipocket.ui.queueModeHint
import id.harness.pipocket.ui.queueModeLabel
import id.harness.pipocket.ui.retryBanner
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 行为开关（pi 的 `/settings` 那几项）：取值/默认值/文案。
 *
 * 取值来源是 pi 源码：`steeringMode`/`followUpMode` = "all" | "one-at-a-time"
 * （`core/agent-session.ts:1642`），autoRetry 存在 `settings.json` 的 `retry.enabled`
 * （`core/settings-manager.ts:893`，不在 session state 里）。
 */
class BehaviorTest {

    @Test
    fun parsesBridgeSnapshot() {
        val s = BehaviorState.from(
            JSONObject(
                """
                {"steeringMode":"all","followUpMode":"one-at-a-time","autoCompaction":false,
                 "autoRetry":false,"isStreaming":true,"isCompacting":false,
                 "pendingMessageCount":2,"messageCount":40,"retry":{"maxRetries":5}}
                """.trimIndent(),
            ),
        )
        assertEquals("all", s.steeringMode)
        assertEquals("one-at-a-time", s.followUpMode)
        assertFalse(s.autoCompaction)
        assertFalse(s.autoRetry)
        assertTrue(s.isStreaming)
        assertEquals(2, s.pendingMessageCount)
        assertEquals(40, s.messageCount)
        assertEquals(5, s.maxRetries)
    }

    @Test
    fun defaultsFollowPi() {
        // pi 的 SETTINGS_DEFAULTS：retry.enabled = true，队列模式 one-at-a-time
        val s = BehaviorState.from(JSONObject("{}"))
        assertEquals("one-at-a-time", s.steeringMode)
        assertEquals("one-at-a-time", s.followUpMode)
        assertTrue("自动压缩默认开", s.autoCompaction)
        assertTrue("自动重试默认开", s.autoRetry)
        assertNull(s.maxRetries)
    }

    @Test
    fun nullMaxRetriesIsNull() {
        val s = BehaviorState.from(JSONObject("""{"retry":{"maxRetries":null}}"""))
        assertNull(s.maxRetries)
    }

    @Test
    fun queueModesMatchPiValues() {
        // 顺序也跟 pi 的 values 数组一致
        assertEquals(listOf("one-at-a-time", "all"), QUEUE_MODES)
    }

    @Test
    fun queueModeLabelsAreChinese() {
        assertEquals("一次一条", queueModeLabel("one-at-a-time"))
        assertEquals("一起送", queueModeLabel("all"))
        assertEquals("一次一条", queueModeLabel("unknown"))
    }

    @Test
    fun queueModeHintsDifferByKind() {
        // steering 是"插本轮"，follow-up 是"跑完再做"——文案不能混
        assertTrue(queueModeHint("one-at-a-time", followUp = false).contains("插"))
        assertTrue(queueModeHint("one-at-a-time", followUp = true).contains("回完"))
        assertTrue(queueModeHint("all", followUp = false).contains("一起"))
        assertTrue(queueModeHint("all", followUp = true).contains("一起"))
    }

    @Test
    fun retryBannerMatchesTerminalWording() {
        // 终端：`Retrying (1/3) in 4s...`（components/countdown-timer.ts）
        assertEquals("重试中（1/3），4.0s 后自动重来", retryBanner(1, 3, 4_000))
        assertEquals("重试中（2/5），1.5s 后自动重来", retryBanner(2, 5, 1_500))
        // 10s 以上不要小数点
        assertEquals("重试中（1/3），12s 后自动重来", retryBanner(1, 3, 12_000))
    }
}
