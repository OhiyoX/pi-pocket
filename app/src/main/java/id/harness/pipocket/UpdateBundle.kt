package id.harness.pipocket

/** One contract for downloaded filenames, immutable staging directories and apply. */
internal object UpdateBundle {
    val files = listOf(
        "termux/pi-pocket-bridge.mjs", "termux/tree-from-file.mjs",
        "termux/rpc-channel.mjs", "termux/event-stream.mjs",
        "termux/tool-render/index.mjs", "termux/tool-render/adapters/web-access.mjs",
        "termux/tool-render/adapters/unified-exec.mjs", "termux/app-profile/pocket-term.mjs",
        "termux/pi-pocket-start", "termux/tools/droid/droid",
        "termux/skills/droid-phone-ops/SKILL.md", "termux/apply-update.sh",
        "termux/app-profile/append-system.md", "termux/app-profile/extension.mjs",
        "termux/app-profile/mcp-presets.json", "termux/skills/zcode-visualize/SKILL.md",
    )
    fun flat(path: String): String = path.replace("/", "__")
    fun directory(sha: String): String {
        require(Regex("[a-fA-F0-9]{40}").matches(sha)) { "更新必须绑定完整 commit SHA" }
        return "pi-pocket-update/$sha/" + java.util.UUID.randomUUID().toString()
    }
    fun command(dir: String): String {
        require(Regex("/sdcard/Download/pi-pocket-update/[a-fA-F0-9]{40}/[a-f0-9-]{36}").matches(dir))
        // Shared storage is noexec; invoke bash explicitly instead of chmod + ./script.
        return "cd '$dir' && bash '${flat("termux/apply-update.sh")}' > apply.log 2>&1"
    }
}
