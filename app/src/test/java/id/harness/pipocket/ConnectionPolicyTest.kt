package id.harness.pipocket

import id.harness.pipocket.ui.sticksToChosenMachine
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用户 2026-10-24 口径：**选定的机器优先**——连不上就显示离线重试，不许偷偷回落。
 *
 * 背景：`connect()` 原来是「谁先应答就用谁」，host-a 探不通时手机连回本机并把 baseUrl 覆写成
 * 127.0.0.1（装包重启后看起来就是"切了机器又被踢回本机"）。
 */
class ConnectionPolicyTest {

    @Test
    fun `远端机器只认自己`() {
        assertTrue(sticksToChosenMachine("http://192.0.2.10:8789"))
        assertTrue(sticksToChosenMachine("http://100.64.0.20:8789"))
        assertTrue(sticksToChosenMachine("https://bridge.example.com:8789"))
    }

    @Test
    fun `本机或空地址保留自动发现`() {
        assertFalse(sticksToChosenMachine("http://127.0.0.1:8789"))
        assertFalse(sticksToChosenMachine("http://localhost:8789"))
        assertFalse(sticksToChosenMachine("http://[::1]:8789"))
        assertFalse(sticksToChosenMachine(""))
    }
}
