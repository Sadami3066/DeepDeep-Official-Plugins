package io.github.whywood.deepdeep.plugin.wecom

import io.github.whywood.deepdeep.plugin.api.LinuxService
import io.github.whywood.deepdeep.plugin.api.NetworkService
import io.github.whywood.deepdeep.plugin.api.PluginHost
import io.github.whywood.deepdeep.plugin.cli.update.CliInstallEvent
import io.github.whywood.deepdeep.plugin.cli.update.CliInstallMessages
import io.github.whywood.deepdeep.plugin.cli.update.CliInstallPlan
import io.github.whywood.deepdeep.plugin.cli.update.CliInstallRunner
import io.github.whywood.deepdeep.plugin.cli.update.CliInstallTimeouts
import io.github.whywood.deepdeep.plugin.cli.update.CliRelease
import java.io.File

internal object WecomInstall {
    private val runner = CliInstallRunner(
        CliInstallPlan(
            binaryExistsCommand = "if [ -x ${WecomCli.INSTALL_PATH} ]; then echo yes; else echo no; fi",
            extractCommand = { archiveName -> WecomCli.extractCommand(archiveName) },
            installCommand = WecomCli.installCommand(),
            restoreCommand = { hadBinary -> WecomCli.restoreCommand(hadBinary) },
            parseVersion = WecomCli::parseVersion,
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
        release: CliRelease,
        onEvent: (CliInstallEvent) -> Unit,
    ) {
        val messages = object : CliInstallMessages {
            override fun downloadFailed(cause: Throwable?): String = when (cause?.message) {
                "sha512 mismatch" -> host.text(WecomTexts.errorIntegrityMismatch)
                else -> host.text(WecomTexts.errorDownload, cause?.message?.takeLast(300).orEmpty())
            }

            override fun versionMismatch(output: String): String = host.text(WecomTexts.errorVersion, output)

            override fun installFailed(output: String): String = host.text(WecomTexts.errorInstall, output)
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

    private const val STAGING_DIR = "cli-staging"
    private const val VERSION_TIMEOUT_MS = 20_000L
    private const val EXTRACT_TIMEOUT_MS = 120_000L
    private const val INSTALL_TIMEOUT_MS = 120_000L
    private const val CLEANUP_TIMEOUT_MS = 30_000L
}
