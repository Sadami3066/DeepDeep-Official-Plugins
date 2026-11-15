package io.github.whywood.deepdeep.plugin.cli.update

import io.github.whywood.deepdeep.plugin.api.LinuxService
import io.github.whywood.deepdeep.plugin.api.NetworkService
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

data class CliInstallTimeouts(
    val binaryCheckMs: Long = 20_000L,
    val extractMs: Long = 120_000L,
    val installMs: Long = 120_000L,
    val cleanupMs: Long = 30_000L,
)

data class CliInstallPlan(
    val binaryExistsCommand: String,
    val extractCommand: (archiveName: String) -> String,
    val installCommand: String,
    val restoreCommand: (hadBinary: Boolean) -> String,
    val parseVersion: (String) -> String?,
    val timeouts: CliInstallTimeouts = CliInstallTimeouts(),
)

enum class CliInstallStage {
    DOWNLOADING,
    VERIFYING,
    INSTALLING,
    FINISHING,
}

data class CliInstallEvent(
    val stage: CliInstallStage,
    val progress: Int? = null,
)

interface CliInstallMessages {
    fun downloadFailed(cause: Throwable?): String

    fun versionMismatch(output: String): String

    fun installFailed(output: String): String
}

class CliInstallRunner(private val plan: CliInstallPlan) {
    suspend fun run(
        linux: LinuxService,
        network: NetworkService,
        stagingDir: File,
        release: CliRelease,
        messages: CliInstallMessages,
        onEvent: (CliInstallEvent) -> Unit,
    ) {
        val hadBinary = hasExistingBinary(linux)
        var installed = false
        try {
            stagingDir.deleteRecursively()
            stagingDir.mkdirs()
            onEvent(CliInstallEvent(CliInstallStage.DOWNLOADING, progress = 0))
            val archive = File(stagingDir, release.archiveName)
            download(network, archive, release, messages) { percent ->
                onEvent(CliInstallEvent(CliInstallStage.DOWNLOADING, progress = percent))
            }
            onEvent(CliInstallEvent(CliInstallStage.VERIFYING))
            val extract = linux.exec(
                plan.extractCommand(release.archiveName),
                cwd = stagingDir.absolutePath,
                timeoutMs = plan.timeouts.extractMs,
            )
            val stagedVersion = plan.parseVersion(extract.stdout)
            if (extract.exitCode != 0 || stagedVersion != release.version) {
                throw IllegalStateException(messages.versionMismatch(extract.stdout.takeLast(400).trim()))
            }
            onEvent(CliInstallEvent(CliInstallStage.INSTALLING))
            val install = linux.exec(
                plan.installCommand,
                cwd = stagingDir.absolutePath,
                timeoutMs = plan.timeouts.installMs,
            )
            val installedVersion = plan.parseVersion(install.stdout)
            if (install.exitCode != 0 || installedVersion != release.version) {
                throw IllegalStateException(messages.installFailed(install.stdout.takeLast(400).trim()))
            }
            installed = true
            onEvent(CliInstallEvent(CliInstallStage.FINISHING))
        } catch (error: CancellationException) {
            withContext(NonCancellable) {
                if (!installed) restore(linux, hadBinary)
                stagingDir.deleteRecursively()
            }
            throw error
        } catch (error: Exception) {
            if (!installed) restore(linux, hadBinary)
            throw error
        } finally {
            stagingDir.deleteRecursively()
        }
    }

    private suspend fun download(
        network: NetworkService,
        target: File,
        release: CliRelease,
        messages: CliInstallMessages,
        onProgress: (Int?) -> Unit,
    ) {
        val sha512 = release.sha512Base64?.let { raw ->
            normalizeBase64(raw)
                ?: throw IllegalStateException(messages.downloadFailed(IllegalStateException("invalid sha512")))
        }
        var lastError: Exception? = null
        for (url in release.downloadUrls) {
            try {
                if (sha512 == null) {
                    network.download(url, target.absolutePath, release.sha256, onProgress)
                } else {
                    network.download(url, target.absolutePath, null, onProgress)
                    if (!matchesSha512(target, sha512)) {
                        target.delete()
                        lastError = IllegalStateException("sha512 mismatch")
                        continue
                    }
                }
                return
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                lastError = error
                target.delete()
            }
        }
        throw IllegalStateException(messages.downloadFailed(lastError))
    }

    private fun matchesSha512(file: File, expectedBase64: String): Boolean = runCatching {
        val digest = MessageDigest.getInstance("SHA-512")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        Base64.getEncoder().encodeToString(digest.digest()) == expectedBase64
    }.getOrDefault(false)

    private fun normalizeBase64(value: String): String? {
        val decoded = runCatching { Base64.getDecoder().decode(value.trim()) }.getOrNull() ?: return null
        return Base64.getEncoder().encodeToString(decoded)
    }

    private suspend fun hasExistingBinary(linux: LinuxService): Boolean = runCatching {
        linux.exec(plan.binaryExistsCommand, timeoutMs = plan.timeouts.binaryCheckMs).stdout.contains("yes")
    }.getOrDefault(false)

    private suspend fun restore(linux: LinuxService, hadBinary: Boolean) {
        runCatching {
            linux.exec(plan.restoreCommand(hadBinary), timeoutMs = plan.timeouts.cleanupMs)
        }
    }
}
