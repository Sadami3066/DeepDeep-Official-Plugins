package io.github.whywood.deepdeep.plugin.wechat

import io.github.whywood.deepdeep.plugin.api.LocalizedText

internal object WechatCli {
    const val VERSION = "0.2.0"
    const val MIN_NODE_MAJOR = 24
    const val SKILL_ID = "deepdeep-wechat"

    const val ASSET_ROOT = "wechat-cli"
    const val ENTRY_RELATIVE = "bin/wechat.mjs"
    const val INSTALL_DIR = "/root/projects/wechat-cli"
    const val BIN_PATH = "/usr/local/bin/wechat"
    const val DATA_DIR = "/root/.wechat"

    val DATE_PATTERN = Regex("""^\d{4}-\d{2}-\d{2}$""")
    val NODE_VERSION = Regex("""v?\d+\.\d+\.\d+""")
    val SEMVER = Regex("""\b\d+\.\d+\.\d+\b""")
    val ANSI = Regex("\u001B\\[[0-9;]*[A-Za-z]")

    fun nodeVersionCommand(): String = "node --version"

    fun cliVersionCommand(): String = "$BIN_PATH version"

    fun sourceReadableCommand(): String =
        "test -r /data/data/com.tencent.mm && test -r /data/data/com.tencent.mm/MicroMsg"

    fun statusCommand(): String = buildString {
        appendLine("echo '@@node@@'")
        appendLine("node --version 2>&1 || true")
        appendLine("echo '@@cli@@'")
        appendLine("$BIN_PATH version 2>&1 || true")
        appendLine("echo '@@snapshot@@'")
        appendLine("test -f $DATA_DIR/current/plain.db && echo yes || echo no")
        appendLine("echo '@@normalized@@'")
        appendLine("test -f $DATA_DIR/wechat.db && echo yes || echo no")
        appendLine("echo '@@exports@@'")
        appendLine("ls -1t $DATA_DIR/exports 2>/dev/null | head -5 || true")
        appendLine("echo '@@end@@'")
    }

    fun installCommand(assetDir: String): String = buildString {
        appendLine("set -e")
        appendLine("mkdir -p /root/projects /usr/local/bin")
        appendLine("rm -rf $INSTALL_DIR")
        appendLine("cp -R ${shellQuote(linuxPath(assetDir))} $INSTALL_DIR")
        appendLine("chmod 755 $INSTALL_DIR/$ENTRY_RELATIVE")
        appendLine("ln -sf $INSTALL_DIR/$ENTRY_RELATIVE $BIN_PATH")
        appendLine("if ! command -v sqlcipher >/dev/null 2>&1; then apk add --no-cache sqlcipher; fi")
        appendLine("$BIN_PATH version")
    }

    fun syncCommand(): String = buildString {
        appendLine("set -e")
        appendLine("cd $INSTALL_DIR")
        appendLine("node $ENTRY_RELATIVE sync --data $DATA_DIR")
        appendLine("node $ENTRY_RELATIVE ingest --data $DATA_DIR")
    }

    fun exportCommand(from: String, to: String): String = buildString {
        appendLine("set -e")
        appendLine("cd $INSTALL_DIR")
        appendLine("node $ENTRY_RELATIVE export --from ${shellQuote(from)} --to ${shellQuote(to)} --format md --data $DATA_DIR")
    }

    fun cleanupCommand(): String = "rm -rf $INSTALL_DIR $BIN_PATH"

    fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    /**
     * App 的 filesDir 通常显示为 /data/user/0/...；Root chroot 里 /data/user/0
     * 是空目录，真实数据在 /data/data/...。Linux 命令统一用后者。
     */
    fun linuxPath(path: String): String =
        if (path.startsWith("/data/user/0/")) "/data/data/" + path.removePrefix("/data/user/0/") else path

    fun cleanOutput(value: String?): String = ANSI.replace(value.orEmpty(), "").trim()

    fun parseNodeVersion(output: String?): String? = NODE_VERSION.find(output.orEmpty())?.value

    fun parseVersion(output: String?): String? = SEMVER.find(output.orEmpty())?.value

    fun parseMajor(version: String): Int? = Regex("""\d+""").find(version)?.value?.toIntOrNull()

    fun parseStatus(output: String): WechatRawStatus {
        val sections = linkedMapOf<String, StringBuilder>()
        var current: StringBuilder? = null
        output.lineSequence().forEach { raw ->
            val line = raw.trim()
            val marker = MARKERS.firstOrNull { line == "@@$it@@" }
            if (marker != null) current = StringBuilder().also { sections[marker] = it }
            else current?.appendLine(raw)
        }
        fun body(name: String): String? = sections[name]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        return WechatRawStatus(
            node = body("node"),
            cli = body("cli"),
            snapshot = body("snapshot") == "yes",
            normalized = body("normalized") == "yes",
            exports = body("exports")?.lineSequence()?.map { it.trim() }?.filter { it.isNotEmpty() }?.toList().orEmpty(),
        )
    }

    private val MARKERS = listOf("node", "cli", "snapshot", "normalized", "exports", "end")
}

internal data class WechatRawStatus(
    val node: String?,
    val cli: String?,
    val snapshot: Boolean,
    val normalized: Boolean,
    val exports: List<String>,
)

internal fun literal(zh: String, en: String): LocalizedText =
    LocalizedText.Literal(mapOf("default" to zh, "zh" to zh, "en" to en))
