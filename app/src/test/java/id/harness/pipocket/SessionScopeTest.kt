package id.harness.pipocket

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话态归属（`ownerMatchesSession`）单测。
 *
 * 为什么值得单测：这个判据已经被用户用三张截图逼着修了三次（排队条 / 工作状态 / 压缩卡），
 * 每次都是「全局字段 + 不记归属 ⇒ 上个会话的临时状态漂到下一个会话」。
 * 判据本身是纯函数，所以把口径钉在这里，谁要“简化”它先看这组断言。
 */
class SessionScopeTest {

    private val a = "/sessions/proj/2026-10-21T01-03-17_sessionA.jsonl"
    private val b = "/sessions/proj/2026-10-21T15-55-45_sessionB.jsonl"

    /** 与会话无关的状态（桥重启、扫描、连接）：谁看都该看到。 */
    @Test
    fun globalOwnerAlwaysShows() {
        assertTrue(ownerMatchesSession(null, a, globalOwnerAllowed = true))
        assertTrue(ownerMatchesSession(null, null, globalOwnerAllowed = true))
    }

    /** 会话自己的东西：归属必须等于当前会话。 */
    @Test
    fun sessionScopedOwnerMustMatch() {
        assertTrue(ownerMatchesSession(a, a, globalOwnerAllowed = false))
        assertFalse("别的会话的压缩卡不许上屏", ownerMatchesSession(a, b, globalOwnerAllowed = false))
        assertFalse("归属为 null 的旧数据也不许上屏", ownerMatchesSession(null, a, globalOwnerAllowed = false))
    }

    /** 当前会话未知（首页 / 已关闭会话）时，会话态的东西一律不上屏。 */
    @Test
    fun noCurrentSessionShowsNothingScoped() {
        assertFalse(ownerMatchesSession(a, null, globalOwnerAllowed = false))
        assertFalse(ownerMatchesSession(a, null, globalOwnerAllowed = true))
        // 但“与会话无关”的状态行仍然可以显示（比如“桥已重启并连上”）
        assertTrue(ownerMatchesSession(null, null, globalOwnerAllowed = true))
    }
}
