package io.github.whywood.superpi.plugin.cli.update

import io.github.whywood.superpi.plugin.api.DownloadResult
import io.github.whywood.superpi.plugin.api.LinuxChunk
import io.github.whywood.superpi.plugin.api.LinuxResult
import io.github.whywood.superpi.plugin.api.LinuxService
import io.github.whywood.superpi.plugin.api.NetworkResponse
import io.github.whywood.superpi.plugin.api.NetworkService
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CliInstallRunnerTest {
    private val release = CliRelease(
        version = "1.0.100",
        archiveName = "cli-1.0.100-linux-arm64.tar.gz",
        downloadUrls = listOf("https://mirror/archive", "https://github/archive"),
        sha256 = "a".repeat(64),
    )

    private val plan = CliInstallPlan(
        binaryExistsCommand = "check-binary",
        extractCommand = { archive -> "extract $archive" },
        installCommand = "install",
        restoreCommand = { hadBinary -> "restore $hadBinary" },
        parseVersion = { output -> Regex("""version (\S+)""").find(output)?.groupValues?.get(1) },
        timeouts = CliInstallTimeouts(binaryCheckMs = 1, extractMs = 1, installMs = 1, cleanupMs = 1),
    )

    private class FakeMessages : CliInstallMessages {
        val events = mutableListOf<String>()

        override fun downloadFailed(cause: Throwable?): String {
            events += "download:${cause?.message}"
            return "download-failed:${cause?.message}"
        }

        override fun versionMismatch(output: String): String {
            events += "version:$output"
            return "version-mismatch:$output"
        }

        override fun installFailed(output: String): String {
            events += "install:$output"
            return "install-failed:$output"
        }
    }

    private class FakeLinux(
        private val handler: (String) -> LinuxResult,
        private val existingBinary: Boolean = false,
    ) : LinuxService {
        val executed = mutableListOf<String>()

        override val available: Boolean = true

        override suspend fun exec(command: String, cwd: String?, timeoutMs: Long): LinuxResult {
            executed += command
            return when {
                command == "check-binary" -> LinuxResult(0, if (existingBinary) "yes\n" else "no\n", "")
                else -> handler(command)
            }
        }

        override fun stream(command: String, cwd: String?): Flow<LinuxChunk> = emptyFlow()
    }

    private class FakeNetwork(
        private val onDownload: suspend (url: String, onProgress: ((Int?) -> Unit)?) -> Unit,
    ) : NetworkService {
        val downloads = mutableListOf<String>()

        val expectedSha256Requests = mutableListOf<String?>()

        override suspend fun request(
            method: String,
            url: String,
            headers: Map<String, String>,
            body: String?,
            timeoutMs: Long,
        ): NetworkResponse = throw UnsupportedOperationException("not used")

        override suspend fun download(
            url: String,
            targetPath: String,
            expectedSha256: String?,
            onProgress: ((Int?) -> Unit)?,
        ): DownloadResult {
            downloads += url
            expectedSha256Requests += expectedSha256
            onDownload(url, onProgress)
            File(targetPath).writeText("archive-bytes")
            return DownloadResult(targetPath, 13, expectedSha256.orEmpty())
        }
    }

    private fun staging(): File = File(
        System.getProperty("java.io.tmpdir"),
        "cli-common-install-${System.nanoTime()}",
    )

    private fun successfulLinux(): FakeLinux = FakeLinux(
        handler = { command ->
            when {
                command.startsWith("extract") -> LinuxResult(0, "cli version 1.0.100", "")
                command == "install" -> LinuxResult(0, "cli version 1.0.100", "")
                command.startsWith("restore") -> LinuxResult(0, "true", "")
                else -> LinuxResult(1, "", "unexpected $command")
            }
        },
    )

    private fun successfulNetwork(progress: Int? = 50): FakeNetwork = FakeNetwork { _, onProgress ->
        onProgress?.invoke(progress)
    }

    @Test
    fun successfulInstallEmitsStagesAndCleansStaging() = runTest {
        val linux = successfulLinux()
        val network = successfulNetwork()
        val staging = staging()
        val events = mutableListOf<CliInstallEvent>()
        CliInstallRunner(plan).run(linux, network, staging, release, FakeMessages()) { events += it }
        assertEquals(
            listOf(
                CliInstallStage.DOWNLOADING,
                CliInstallStage.DOWNLOADING,
                CliInstallStage.VERIFYING,
                CliInstallStage.INSTALLING,
                CliInstallStage.FINISHING,
            ),
            events.map { it.stage },
        )
        assertEquals(0, events[0].progress)
        assertEquals(50, events[1].progress)
        assertEquals(listOf("https://mirror/archive"), network.downloads)
        assertTrue(linux.executed.contains("extract cli-1.0.100-linux-arm64.tar.gz"))
        assertTrue(linux.executed.contains("install"))
        assertFalse(linux.executed.any { it.startsWith("restore") })
        assertFalse(staging.exists())
    }

    @Test
    fun extractVersionMismatchThrowsAndRestores() = runTest {
        val linux = FakeLinux(
            handler = { command ->
                when {
                    command.startsWith("extract") -> LinuxResult(0, "cli version 9.9.9", "")
                    command.startsWith("restore") -> LinuxResult(0, "true", "")
                    else -> LinuxResult(0, "", "")
                }
            },
        )
        val messages = FakeMessages()
        val staging = staging()
        val error = runCatching {
            CliInstallRunner(plan).run(linux, successfulNetwork(), staging, release, messages) {}
        }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertEquals("version-mismatch:cli version 9.9.9", error!!.message)
        assertTrue(linux.executed.contains("restore false"))
        assertFalse(staging.exists())
    }

    @Test
    fun installVersionMismatchRestoresExistingBinary() = runTest {
        val linux = FakeLinux(
            handler = { command ->
                when {
                    command.startsWith("extract") -> LinuxResult(0, "cli version 1.0.100", "")
                    command == "install" -> LinuxResult(0, "cli version 8.8.8", "")
                    command.startsWith("restore") -> LinuxResult(0, "true", "")
                    else -> LinuxResult(0, "", "")
                }
            },
            existingBinary = true,
        )
        val error = runCatching {
            CliInstallRunner(plan).run(linux, successfulNetwork(), staging(), release, FakeMessages()) {}
        }.exceptionOrNull()
        assertEquals("install-failed:cli version 8.8.8", error!!.message)
        assertTrue(linux.executed.contains("restore true"))
    }

    @Test
    fun downloadRetriesNextUrlAfterFailure() = runTest {
        val network = FakeNetwork { url, onProgress ->
            if (url.contains("mirror")) throw IllegalStateException("mirror down")
            onProgress?.invoke(100)
        }
        CliInstallRunner(plan).run(successfulLinux(), network, staging(), release, FakeMessages()) {}
        assertEquals(listOf("https://mirror/archive", "https://github/archive"), network.downloads)
    }

    @Test
    fun allDownloadsFailWithLocalizedMessage() = runTest {
        val network = FakeNetwork { _, _ -> throw IllegalStateException("offline") }
        val messages = FakeMessages()
        val linux = successfulLinux()
        val error = runCatching {
            CliInstallRunner(plan).run(linux, network, staging(), release, messages) {}
        }.exceptionOrNull()
        assertEquals("download-failed:offline", error!!.message)
        assertTrue(linux.executed.contains("restore false"))
    }

    @Test
    fun verifiesSha512AfterDownloadWithoutSha256Expectation() = runTest {
        val payload = "archive-bytes".toByteArray()
        val expected = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-512").digest(payload))
        val sha512Release = release.copy(sha256 = "", sha512Base64 = expected)
        val network = successfulNetwork()
        CliInstallRunner(plan).run(successfulLinux(), network, staging(), sha512Release, FakeMessages()) {}
        assertEquals(listOf<String?>(null), network.expectedSha256Requests)
    }

    @Test
    fun sha512MismatchRetriesThenFailsWithDownloadMessage() = runTest {
        val wrong = Base64.getEncoder().encodeToString(ByteArray(64) { 7 })
        val sha512Release = release.copy(sha256 = "", sha512Base64 = wrong)
        val network = successfulNetwork()
        val messages = FakeMessages()
        val error = runCatching {
            CliInstallRunner(plan).run(successfulLinux(), network, staging(), sha512Release, messages) {}
        }.exceptionOrNull()
        assertEquals("download-failed:sha512 mismatch", error!!.message)
        assertEquals(listOf("https://mirror/archive", "https://github/archive"), network.downloads)
    }

    @Test
    fun cancellationRollsBackAndRethrows() = runTest {
        val network = FakeNetwork { _, _ -> throw CancellationException("user cancelled") }
        val linux = successfulLinux()
        val staging = staging()
        val error = runCatching {
            CliInstallRunner(plan).run(linux, network, staging, release, FakeMessages()) {}
        }.exceptionOrNull()
        assertTrue(error is CancellationException)
        assertTrue(linux.executed.contains("restore false"))
        assertFalse(staging.exists())
    }
}
