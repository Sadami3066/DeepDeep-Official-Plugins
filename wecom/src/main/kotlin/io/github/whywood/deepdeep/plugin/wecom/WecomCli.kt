package io.github.whywood.deepdeep.plugin.wecom

import java.io.File
import java.io.RandomAccessFile
import java.util.Locale

object WecomCli {
    const val BINARY = "wecom-cli"
    const val INSTALL_PATH = "/usr/local/bin/wecom-cli"
    const val INSTALL_TEMP_PATH = "/usr/local/bin/.wecom-cli.new"
    const val INSTALL_BACKUP_PATH = "/usr/local/bin/.wecom-cli.old"
    const val CONFIG_DIR = "/root/.config/wecom"
    const val CREDENTIALS_PATH = "$CONFIG_DIR/credentials.enc"
    const val QR_HOST_FILE = ".wecom-qr.png"
    const val PACKAGE_BINARY = "package/bin/wecom-cli"

    const val VERSION = "1.3.4"
    const val ARCHIVE_NAME = "wecom-cli-linux-arm64-$VERSION.tgz"
    const val ARCHIVE_SHA256 = "833ab8efe327d7194a0107696104c63e56af9221db624dc181bf4f6bf8d91eba"

    val DOWNLOAD_URLS: List<String> = listOf(
        "https://registry.npmjs.org/@wecom/cli-linux-arm64/-/cli-linux-arm64-$VERSION.tgz",
        "https://registry.npmmirror.com/@wecom/cli-linux-arm64/-/cli-linux-arm64-$VERSION.tgz",
    )

    private const val ENV = "HOME=/root"

    private fun command(arguments: String): String = "$ENV $BINARY $arguments 2>&1"

    fun versionCommand(): String = command("--version")

    fun authStatusCommand(): String = command("auth show --status")

    fun authShowCommand(): String = command("auth show")

    fun qrFile(workspaceDir: String): File = File(workspaceDir, QR_HOST_FILE)

    fun authInitCommand(qrFile: File): String {
        require(qrFile.isAbsolute) { "QR output path must be absolute" }
        val path = "'" + qrFile.absolutePath.replace("'", "'\\''") + "'"
        return command("auth init --noninteractive --no-browser --output-qrcode $path")
    }

    fun logoutCommand(): String = "rm -f $CREDENTIALS_PATH; true"

    fun uninstallCommand(): String = buildString {
        append("rm -f ").append(INSTALL_PATH).append(' ').append(INSTALL_TEMP_PATH).append(' ')
        append(INSTALL_BACKUP_PATH).append("; ")
        append("rm -rf ").append(CONFIG_DIR).append("; true")
    }

    fun extractCommand(): String = """
        set -e
        rm -rf extract
        mkdir -p extract
        tar -xzf $ARCHIVE_NAME -C extract
        chmod 755 extract/$PACKAGE_BINARY
        ./extract/$PACKAGE_BINARY --version
    """.trimIndent()

    fun installCommand(): String = """
        set -e
        mkdir -p /usr/local/bin
        if [ -f $INSTALL_PATH ]; then cp -f $INSTALL_PATH $INSTALL_BACKUP_PATH; fi
        cp -f extract/$PACKAGE_BINARY $INSTALL_TEMP_PATH
        chmod 755 $INSTALL_TEMP_PATH
        mv -f $INSTALL_TEMP_PATH $INSTALL_PATH
        $INSTALL_PATH --version
        rm -f $INSTALL_BACKUP_PATH
    """.trimIndent()

    fun restoreCommand(hadBinary: Boolean): String = buildString {
        appendLine("if [ -f $INSTALL_BACKUP_PATH ]; then")
        appendLine("mv -f $INSTALL_BACKUP_PATH $INSTALL_PATH")
        appendLine("chmod 755 $INSTALL_PATH")
        if (!hadBinary) {
            appendLine("else")
            appendLine("rm -f $INSTALL_PATH")
        }
        appendLine("fi")
        appendLine("rm -f $INSTALL_TEMP_PATH")
        append("true")
    }

    fun parseVersion(output: String): String? = VERSION_PATTERN.find(cleanOutput(output))
        ?.groupValues?.get(1)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: SEMVER_PATTERN.find(cleanOutput(output))?.groupValues?.get(1)

    fun parseAuthStatus(output: String): Boolean? {
        var authorized: Boolean? = null
        cleanOutput(output).lineSequence().forEach { line ->
            when (line.trim().removePrefix("Status:").trim().lowercase(Locale.US)) {
                "authorized" -> authorized = true
                "unauthorized" -> authorized = false
            }
        }
        return authorized
    }

    fun parseBotId(output: String): String? =
        BOT_ID_PATTERN.find(cleanOutput(output))
            ?.groupValues?.get(1)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    fun parseQrPageUrl(output: String): String? =
        QR_URL_PATTERN.find(cleanOutput(output))
            ?.value
            ?.trimEnd('.', ',', ')', ']', '\\')

    fun isCompleteQrCode(file: File): Boolean {
        if (!file.isFile || file.length() < PNG_HEADER.size + PNG_TRAILER.size) return false
        return runCatching {
            val header = ByteArray(PNG_HEADER.size)
            file.inputStream().use { input ->
                var read = 0
                while (read < header.size) {
                    val count = input.read(header, read, header.size - read)
                    if (count < 0) break
                    read += count
                }
                if (read < header.size) return@runCatching false
            }
            if (!header.contentEquals(PNG_HEADER)) return@runCatching false
            val trailer = ByteArray(PNG_TRAILER.size)
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(file.length() - trailer.size)
                raf.readFully(trailer)
            }
            trailer.contentEquals(PNG_TRAILER)
        }.getOrDefault(false)
    }

    private fun cleanOutput(value: String): String = value
        .replace(ANSI_PATTERN, "")
        .replace("\r", "")

    private val ANSI_PATTERN = Regex("\u001B\\[[0-9;?]*[A-Za-z]")
    private val VERSION_PATTERN = Regex("""(?im)^\s*wecom-cli\s+([0-9][^\s(]*)""")
    private val SEMVER_PATTERN = Regex("""([0-9]+\.[0-9]+\.[0-9]+[^\s]*)""")
    private val BOT_ID_PATTERN = Regex("""Bot ID:\s*(\S+)""")
    private val QR_URL_PATTERN = Regex("""https://work\.weixin\.qq\.com/ai/qc/gen\S*""")

    private val PNG_HEADER = byteArrayOf(
        0x89.toByte(),
        0x50,
        0x4E,
        0x47,
        0x0D,
        0x0A,
        0x1A,
        0x0A,
    )

    private val PNG_TRAILER = byteArrayOf(
        0x00,
        0x00,
        0x00,
        0x00,
        0x49,
        0x45,
        0x4E,
        0x44,
        0xAE.toByte(),
        0x42,
        0x60,
        0x82.toByte(),
    )
}
