package id.harness.pipocket

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「从零部署」拼出来的那条命令的纯逻辑测试（不起 Termux、不联网）。
 *
 * 这里守的是几个实测踩过的点：
 * - 脚本必须 **base64 解回去落成文件** 再跑（不能把整段脚本拼进命令行）；
 * - **app 的 token 先预置**进 env（脚本只在缺失时生成），这样部署完 app 能直接连；
 * - 输出**不能丢**：要落进日志文件（失败时要显示尾部 20 行）；
 * - 命令必须是**单行**（RUN_COMMAND 只给一段命令行参数）。
 */
class TermuxBootstrapTest {

    private val b64 = "IyEvYmluL2Jhc2gKZWNobyBoaQo="

    @Test
    fun commandDecodesScriptAndKeepsLog() {
        val cmd = TermuxBootstrap.buildCommand(scriptBase64 = b64, appToken = "apptok", repoToken = "repotok")
        assertTrue(cmd.contains("echo " + b64 + " | base64 -d >"))
        assertTrue(cmd.contains("REPO_TOKEN=repotok bash "))
        assertTrue(cmd.contains(">> /data/data/com.termux/files/home/pi-pocket-bootstrap.log 2>&1"))
        assertTrue(cmd.contains("退出码"))
        // 单行（RUN_COMMAND 的参数里不能带换行）
        assertFalse(cmd.contains(Character.toString(10)))
        // 结尾必须成功返回，不然 Termux 会把整条会话标红
        assertTrue(cmd.trimEnd().endsWith("true"))
    }

    @Test
    fun appTokenIsSeededButNeverOverwritten() {
        val cmd = TermuxBootstrap.buildCommand(b64, appToken = "apptok", repoToken = "repotok")
        // 只在文件里还没有 PI_POCKET_TOKEN 时才写：`if ! grep -q ... ; then ... > env; fi`
        assertTrue(cmd.contains("if ! grep -q '^PI_POCKET_TOKEN='"))
        assertTrue(cmd.contains("'PI_POCKET_TOKEN=apptok'"))
        assertTrue(cmd.contains("chmod 600 /data/data/com.termux/files/home/.pi/agent/pi-pocket.env"))
        // 没有 app token 时不要预置（让脚本自己生成，免得写个空 token 进去）
        val noSeed = TermuxBootstrap.buildCommand(b64, appToken = "", repoToken = "repotok")
        assertFalse(noSeed.contains("printf"))
        assertTrue(noSeed.contains("base64 -d"))
        // 没有 REPO_TOKEN 时也不能拼出 `REPO_TOKEN= bash`
        val noRepo = TermuxBootstrap.buildCommand(b64, appToken = "apptok", repoToken = "")
        assertFalse(noRepo.contains("REPO_TOKEN="))
    }

    @Test
    fun logPathIsUnderTermuxHomeSoBridgeCanReadIt() {
        val cmd = TermuxBootstrap.buildCommand(b64, appToken = "t", repoToken = "r")
        // 桥的 /v1/file 只允许 home 或工作目录下的路径 ⇒ 日志必须在 $HOME 里
        assertTrue(cmd.contains("/data/data/com.termux/files/home/pi-pocket-bootstrap.log"))
        assertTrue(cmd.contains("/sdcard/Download/pi-pocket-bootstrap.log"))
    }
}
