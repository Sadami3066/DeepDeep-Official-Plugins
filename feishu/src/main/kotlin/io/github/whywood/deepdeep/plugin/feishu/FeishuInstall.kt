package io.github.whywood.deepdeep.plugin.feishu

import io.github.whywood.deepdeep.plugin.api.LinuxService
import io.github.whywood.deepdeep.plugin.api.NetworkService
import io.github.whywood.deepdeep.plugin.api.PluginHost
import io.github.whywood.deepdeep.plugin.cli.update.CliInstallEvent
import io.github.whywood.deepdeep.plugin.cli.update.CliInstallMessages
import io.github.whywood.deepdeep.plugin.cli.update.CliInstallPlan
import io.github.whywood.deepdeep.plugin.cli.update.CliInstallRunner
import io.github.whywood.deepdeep.plugin.cli.update.CliInstallTimeouts
import java.io.File

internal const val LARK_CLI_BACKUP_PATH = "/usr/local/bin/.lark-cli.old"
internal const val LARK_CLI_TEMP_PATH = "/usr/local/bin/.lark-cli.new"

internal fun feishuRestoreCommand(hadBinary: Boolean): String = buildString {
    appendLine("if [ -f $LARK_CLI_BACKUP_PATH ]; then")
    appendLine("mv -f $LARK_CLI_BACKUP_PATH ${FeishuCli.INSTALL_PATH}")
    appendLine("chmod 755 ${FeishuCli.INSTALL_PATH}")
    if (!hadBinary) {
        appendLine("else")
        appendLine("rm -f ${FeishuCli.INSTALL_PATH}")
    }
    appendLine("fi")
    appendLine("rm -f $LARK_CLI_TEMP_PATH")
    append("true")
}

internal object FeishuInstall {
    private val runner = CliInstallRunner(
        CliInstallPlan(
            binaryExistsCommand = "if [ -x ${FeishuCli.INSTALL_PATH} ]; then echo yes; else echo no; fi",
            extractCommand = { archiveName ->
                """
                set -e
                rm -rf extract
                mkdir -p extract
                tar -xzf $archiveName -C extract
                chmod 755 extract/lark-cli
                ./extract/lark-cli --version
                """.trimIndent()
            },
            installCommand = """
                set -e
                mkdir -p /usr/local/bin
                if [ -f ${FeishuCli.INSTALL_PATH} ]; then cp -f ${FeishuCli.INSTALL_PATH} $LARK_CLI_BACKUP_PATH; fi
                cp -f extract/lark-cli $LARK_CLI_TEMP_PATH
                chmod 755 $LARK_CLI_TEMP_PATH
                mv -f $LARK_CLI_TEMP_PATH ${FeishuCli.INSTALL_PATH}
                ${FeishuCli.INSTALL_PATH} --version
                rm -f $LARK_CLI_BACKUP_PATH
            """.trimIndent(),
            restoreCommand = { hadBinary -> feishuRestoreCommand(hadBinary) },
            parseVersion = FeishuCli::parseVersion,
            timeouts = CliInstallTimeouts(
                binaryCheckMs = VERSION_TIMEOUT_MS,
                extractMs = EXTRACT_TIMEOUT_MS,
                installMs = INSTALL_TIMEOUT_MS,
                cleanupMs = CLEANUP_TIMEOUT_MS,
            ),
        ),
    )

    suspend fun run(
        host: PluginHost,
        linux: LinuxService,
        network: NetworkService,
        release: FeishuRelease,
        onEvent: (CliInstallEvent) -> Unit,
    ) {
        val messages = object : CliInstallMessages {
            override fun downloadFailed(cause: Throwable?): String =
                host.text(ERROR_DOWNLOAD, cause?.message?.takeLast(300).orEmpty())

            override fun versionMismatch(output: String): String = host.text(ERROR_VERSION, output)

            override fun installFailed(output: String): String = host.text(ERROR_INSTALL, output)
        }
        runner.run(
            linux = linux,
            network = network,
            stagingDir = File(host.dataDir, STAGING_DIR),
            release = release,
            messages = messages,
            onEvent = onEvent,
        )
    }
}
