package id.harness.pipocket

import id.harness.pipocket.ui.BashInput
import id.harness.pipocket.ui.gitBranchLabel
import id.harness.pipocket.ui.parseBashInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 终端 `!cmd` 的等价实现（bash 模式）。
 *
 * pi 的口径（`interactive-mode.ts` bash mode + `rpc-mode.ts` 的 `bash` 命令）：
 *  · `!cmd` 结果**进上下文**；
 *  · `!!cmd` 结果**不进上下文**（只看一眼）；命令从第 3 个字符开始；
 *  · 只有感叹号没内容时不算命令（用户还在打字）。
 */
class BashModeTest {

    @Test
    fun `单个感叹号是进上下文的命令`() {
        assertEquals(BashInput("ls -la", excludeFromContext = false), parseBashInput("!ls -la"))
    }

    @Test
    fun `两个感叹号不进上下文`() {
        assertEquals(BashInput("git status", excludeFromContext = true), parseBashInput("!!git status"))
    }

    @Test
    fun `只有感叹号不算命令`() {
        // 打 `!` 的瞬间不能把整条消息当空命令发出去
        assertNull(parseBashInput("!"))
        assertNull(parseBashInput("!!"))
        assertNull(parseBashInput("!   "))
    }

    @Test
    fun `普通消息不会被误判`() {
        assertNull(parseBashInput("你好"))
        assertNull(parseBashInput("真的吗！"))
        assertNull(parseBashInput(""))
        // `!` 不在开头（中文语境里常见）也不是命令
        assertNull(parseBashInput("先跑 ls !"))
    }

    @Test
    fun `命令首尾空白被清掉但内部保留`() {
        val b = parseBashInput("!  ls   -la  ")!!
        assertEquals("ls   -la", b.command)
    }

    @Test
    fun `感叹号后面紧跟惊叹号以外的内容不受影响`() {
        // `!echo "hi!"` 里的 `!` 属于命令本身
        assertEquals("echo \"hi!\"", parseBashInput("!echo \"hi!\"")!!.command)
    }

    @Test
    fun `分支标签带括号`() {
        assertEquals("(main)", gitBranchLabel("main"))
        assertEquals("(feature/login-ui)", gitBranchLabel("feature/login-ui"))
        // pi 在 detached HEAD 时给的就是这个字符串，要原样显示（不是"没有分支"）
        assertEquals("(detached)", gitBranchLabel("detached"))
    }

    @Test
    fun `没有仓库就不显示`() {
        assertNull(gitBranchLabel(null))
        assertNull(gitBranchLabel(""))
        assertNull(gitBranchLabel("   "))
    }

    @Test
    fun `分支名不截断`() {
        val long = "feature/very-long-branch-name-that-keeps-going-2026"
        val label = gitBranchLabel(long)!!
        assertTrue(label, label.contains(long))
    }
}
