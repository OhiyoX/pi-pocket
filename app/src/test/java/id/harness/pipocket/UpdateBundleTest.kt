package id.harness.pipocket

import org.junit.Assert.*
import org.junit.Test

class UpdateBundleTest {
    @Test fun applyRunsExactlyTheDownloadedScriptUsingBash() {
        val dir = "/sdcard/Download/" + UpdateBundle.directory("a".repeat(40))
        val script = UpdateBundle.files.single { it.endsWith("/apply-update.sh") }
        assertTrue(UpdateBundle.command(dir).contains("bash '${UpdateBundle.flat(script)}'"))
        assertFalse(UpdateBundle.command(dir).contains("./apply-update.sh"))
    }
    @Test fun relativeBridgeImportsAreIncludedAndNamesDoNotCollide() {
        assertTrue(UpdateBundle.files.containsAll(listOf("termux/tree-from-file.mjs", "termux/rpc-channel.mjs", "termux/event-stream.mjs")))
        assertEquals(UpdateBundle.files.size, UpdateBundle.files.map(UpdateBundle::flat).distinct().size)
    }
    @Test fun mutableOrUnsafeReferencesCannotBecomeShellPaths() {
        for (sha in listOf("main", "../escape", "a'; echo x", "abc")) {
            assertTrue(runCatching { UpdateBundle.directory(sha) }.isFailure)
        }
        assertTrue(runCatching { UpdateBundle.command("/tmp/unsafe'path") }.isFailure)
    }
}
