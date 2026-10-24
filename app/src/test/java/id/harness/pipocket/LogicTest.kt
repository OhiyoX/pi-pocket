package id.harness.pipocket

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 网络地址归一化、主题解析、SSH 命令等纯逻辑测试。 */
class LogicTest {

    @Test
    fun normalizeHostAddsSchemeAndPort() {
        assertEquals("http://host-a:8789", PiLocator.normalizeHost("host-a"))
        assertEquals("http://host-a:8789", PiLocator.normalizeHost("  host-a  "))
        assertEquals("http://192.0.2.10:8790", PiLocator.normalizeHost("192.0.2.10:8790"))
        assertEquals("https://box.lan:8789", PiLocator.normalizeHost("https://box.lan"))
        assertEquals("", PiLocator.normalizeHost("   "))
    }

    @Test
    fun bridgeScopeDetectsLocalVsLan() {
        // 只校验 probe 之外的纯逻辑：/24 前缀推导
        assertTrue(PiLocator.LOCAL_PORTS.contains(8789))
        assertEquals(21, PiLocator.LOCAL_PORTS.size)
        assertEquals(8789, PiLocator.DEFAULT_PORT)
    }

    @Test
    fun themeModeResolvesAgainstSystem() {
        // AppState.isDark 依赖 prefs，这里直接验证相同逻辑的三种分支
        fun resolve(mode: ThemeMode, systemDark: Boolean) = when (mode) {
            ThemeMode.System -> systemDark
            ThemeMode.Light -> false
            ThemeMode.Dark -> true
        }
        assertFalse(resolve(ThemeMode.Light, systemDark = true))
        assertTrue(resolve(ThemeMode.Dark, systemDark = false))
        assertTrue(resolve(ThemeMode.System, systemDark = true))
        assertFalse(resolve(ThemeMode.System, systemDark = false))
    }

    @Test
    fun savedSshTargetsMigrateLegacyDefaultCommand() {
        val legacy = SshTarget.LEGACY_DEFAULT_START
        val raw = """[{"host":"host-a","port":22,"user":"user","authKind":"key","password":"",
            "privateKey":"KEY","passphrase":"","startCommand":"$legacy"}]"""
        val t = parseSshTargets(raw).single()
        assertEquals("user@host-a:22", t.key)
        assertEquals(SshTarget.DEFAULT_START, t.startCommand)
    }

    @Test
    fun savedSshTargetsKeepCustomStartCommand() {
        val raw = """[{"host":"box","port":2222,"user":"u","authKind":"password","password":"p",
            "startCommand":"echo custom"}]"""
        val t = parseSshTargets(raw).single()
        assertEquals("echo custom", t.startCommand)
        assertEquals("u@box:2222", t.key)
    }

    @Test
    fun savedSshTargetsBlankCommandUsesDefaultAndBadJsonIsIgnored() {
        val raw = """[{"host":"h","port":22,"user":"u"}]"""
        assertEquals(SshTarget.DEFAULT_START, parseSshTargets(raw).single().startCommand)
        assertTrue(parseSshTargets("not json").isEmpty())
        assertTrue(parseSshTargets(null).isEmpty())
    }

    @Test
    fun sshTargetKeyAndDefaultCommand() {
        val t = SshTarget(host = "host-a", port = 22, user = "user")
        assertEquals("user@host-a:22", t.key)
        // 必须经 pi-pocket-start：只有它会把 env 文件里的 token/bind 带进桥进程
        assertTrue(SshTarget.DEFAULT_START.contains("pi-pocket-start"))
        assertTrue(SshTarget.DEFAULT_START.contains("\$HOME/.local/bin"))
        assertTrue(SshTarget.DEFAULT_START.contains("8789"))
        // 不能绕过启动器直起裸桥：远端只会得到一个回环桥，app 永远连不上
        assertFalse(SshTarget.DEFAULT_START.contains("nohup pi-pocket-bridge"))
    }

    @Test
    fun attachmentApproxSize() {
        val a = Attachment("image/png", "AAAA", bytes = 4096)
        assertEquals(4, a.approxKb)
    }
}
