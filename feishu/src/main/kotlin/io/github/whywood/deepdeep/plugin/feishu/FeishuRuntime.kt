package io.github.whywood.deepdeep.plugin.feishu

import io.github.whywood.deepdeep.plugin.api.LinuxChunk
import io.github.whywood.deepdeep.plugin.api.LinuxService
import io.github.whywood.deepdeep.plugin.api.LocalizedText
import io.github.whywood.deepdeep.plugin.api.NetworkService
import io.github.whywood.deepdeep.plugin.api.PluginHost
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal enum class FeishuOperation {
    NONE,
    INSTALL,
    CONNECT,
    LOGOUT,
}

internal data class FeishuPageState(
    val snapshot: FeishuSnapshot? = null,
    val operation: FeishuOperation = FeishuOperation.NONE,
    val stage: LocalizedText? = null,
    val progress: Int? = null,
    val authUrl: String? = null,
    val error: String? = null,
)

internal class FeishuRuntime {
    val pageState = MutableStateFlow(FeishuPageState())

    @Volatile
    private var memory: FeishuSnapshot? = null

    private val operationLock = Mutex()

    private val refreshLock = Mutex()

    fun snapshot(host: PluginHost): FeishuSnapshot? =
        memory ?: FeishuStateStore.load(host)?.also { memory = it }

    suspend fun refresh(host: PluginHost) {
        refreshLock.withLock {
            try {
                val snapshot = readSnapshot(host)
                memory = snapshot
                FeishuStateStore.save(host, snapshot)
                pageState.update { it.copy(snapshot = snapshot, error = null) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                pageState.update { it.copy(error = error.message ?: host.text(ERROR_UNKNOWN)) }
            }
        }
    }

    fun startInstall(host: PluginHost) {
        startOperation(host, FeishuOperation.INSTALL) { install(host) }
    }

    fun startConnect(host: PluginHost) {
        startOperation(host, FeishuOperation.CONNECT) { connect(host) }
    }

    fun startLogout(host: PluginHost) {
        startOperation(host, FeishuOperation.LOGOUT) { logout(host) }
    }

    fun openAppScopes(host: PluginHost) {
        val appId = snapshot(host)?.appId?.takeIf { it.isNotBlank() } ?: return
        openUrl(host, "$APP_CONSOLE_URL/$appId/auth")
    }

    fun openAuthUrl(host: PluginHost) {
        val url = pageState.value.authUrl ?: return
        openUrl(host, url)
    }

    fun reset(host: PluginHost) {
        memory = null
        FeishuStateStore.delete(host)
        pageState.update { FeishuPageState() }
    }

    private fun openUrl(host: PluginHost, url: String) {
        runCatching { host.ui().openUrl(url) }
    }

    private fun startOperation(host: PluginHost, operation: FeishuOperation, block: suspend () -> Unit) {
        host.scope.launch {
            if (!operationLock.tryLock()) return@launch
            try {
                pageState.update {
                    it.copy(operation = operation, stage = null, progress = null, authUrl = null, error = null)
                }
                block()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                pageState.update { it.copy(error = error.message ?: host.text(ERROR_UNKNOWN)) }
            } finally {
                pageState.update {
                    it.copy(operation = FeishuOperation.NONE, stage = null, progress = null, authUrl = null)
                }
                operationLock.unlock()
            }
        }
    }

    private suspend fun readSnapshot(host: PluginHost): FeishuSnapshot {
        val checkedAt = System.currentTimeMillis()
        val linux = linuxOrNull(host) ?: return FeishuSnapshot(checkedAt = checkedAt)
        val versionResult = linux.exec(FeishuCli.versionCommand(), timeoutMs = VERSION_TIMEOUT_MS)
        val version = FeishuCli.parseVersion(versionResult.stdout)
        if (versionResult.exitCode != 0 || version == null) {
            return FeishuSnapshot(checkedAt = checkedAt)
        }
        val statusResult = linux.exec(FeishuCli.statusCommand(), timeoutMs = STATUS_TIMEOUT_MS)
        val parsed = FeishuCli.parseAuthStatus(statusResult.stdout)
        return if (parsed == null) {
            FeishuSnapshot(
                installedVersion = version,
                checkedAt = checkedAt,
                message = host.text(ERROR_STATUS, statusResult.stdout.takeLast(200).trim()),
            )
        } else {
            FeishuSnapshot(
                installedVersion = version,
                appId = parsed.appId,
                appConfigured = parsed.appConfigured,
                connected = parsed.appConfigured && parsed.userReady,
                account = parsed.account,
                scopeCount = parsed.scopeCount,
                missingScopes = memory?.missingScopes.orEmpty(),
                checkedAt = checkedAt,
            )
        }
    }

    private suspend fun install(host: PluginHost) {
        val linux = requireLinux(host)
        val staging = File(host.dataDir, STAGING_DIR)
        val hadBinary = hasExistingBinary(linux)
        try {
            staging.deleteRecursively()
            staging.mkdirs()
            pageState.update { it.copy(stage = STAGE_DOWNLOADING, progress = 0) }
            val archive = File(staging, ARCHIVE_NAME)
            downloadArchive(host, archive) { percent ->
                pageState.update { it.copy(progress = percent) }
            }
            pageState.update { it.copy(stage = STAGE_VERIFYING, progress = null) }
            val extract = linux.exec(EXTRACT_COMMAND, cwd = staging.absolutePath, timeoutMs = EXTRACT_TIMEOUT_MS)
            val stagedVersion = FeishuCli.parseVersion(extract.stdout)
            if (extract.exitCode != 0 || stagedVersion != FEISHU_VERSION) {
                throw IllegalStateException(host.text(ERROR_VERSION, extract.stdout.takeLast(400).trim()))
            }
            pageState.update { it.copy(stage = STAGE_INSTALLING) }
            val install = linux.exec(INSTALL_COMMAND, cwd = staging.absolutePath, timeoutMs = INSTALL_TIMEOUT_MS)
            val installedVersion = FeishuCli.parseVersion(install.stdout)
            if (install.exitCode != 0 || installedVersion != FEISHU_VERSION) {
                throw IllegalStateException(host.text(ERROR_INSTALL, install.stdout.takeLast(400).trim()))
            }
            pageState.update { it.copy(stage = STAGE_FINISHING) }
            refresh(host)
            host.requestContributionsRefresh()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            runCatching { linux.exec(feishuRestoreCommand(hadBinary), timeoutMs = CLEANUP_TIMEOUT_MS) }
            throw error
        } finally {
            staging.deleteRecursively()
        }
    }

    private suspend fun downloadArchive(host: PluginHost, target: File, onProgress: (Int?) -> Unit) {
        val network = networkOrNull(host) ?: throw IllegalStateException(host.text(ERROR_UNKNOWN))
        var lastError: Exception? = null
        for (url in DOWNLOAD_URLS) {
            try {
                network.download(url, target.absolutePath, ARCHIVE_SHA256, onProgress)
                return
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                lastError = error
                target.delete()
            }
        }
        throw IllegalStateException(host.text(ERROR_DOWNLOAD, lastError?.message?.takeLast(300).orEmpty()))
    }

    private suspend fun connect(host: PluginHost) {
        val linux = requireLinux(host)
        pageState.update { it.copy(stage = STAGE_CREATE_APP) }
        val probe = readAuthStatus(linux)
        if (probe?.appConfigured != true) {
            runConfigInit(host, linux)
        }
        pageState.update { it.copy(stage = STAGE_AUTH, authUrl = null) }
        val start = linux.exec(FeishuCli.loginStartCommand(), timeoutMs = LOGIN_START_TIMEOUT_MS)
        val challenge = FeishuCli.parseLoginChallenge(start.stdout)
            ?: throw IllegalStateException(host.text(ERROR_AUTH_OUTPUT, start.stdout.takeLast(300).trim()))
        pageState.update { it.copy(stage = STAGE_AUTH, authUrl = challenge.verificationUrl) }
        val poll = linux.exec(
            FeishuCli.loginPollCommand(challenge.deviceCode),
            timeoutMs = LOGIN_POLL_TIMEOUT_MS,
        )
        pageState.update { it.copy(authUrl = null) }
        val result = FeishuCli.parseLoginResult(poll.stdout)
        if (poll.exitCode != 0 && result?.account == null) {
            val current = readAuthStatus(linux)
            if (current?.userReady != true) {
                throw IllegalStateException(host.text(ERROR_AUTH_OUTPUT, poll.stdout.takeLast(300).trim()))
            }
            memory = memory?.copy(missingScopes = emptyList())
        } else {
            memory = (memory ?: FeishuSnapshot()).copy(missingScopes = result?.missingScopes.orEmpty())
        }
        pageState.update { it.copy(stage = STAGE_FINISHING) }
        refresh(host)
        host.requestContributionsRefresh()
    }

    private suspend fun runConfigInit(host: PluginHost, linux: LinuxService) {
        val tail = StringBuilder()
        var found: String? = null
        var exitCode: Int? = null
        linux.stream(FeishuCli.configInitCommand()).collect { chunk ->
            when (chunk) {
                is LinuxChunk.Stdout -> {
                    if (tail.length < 2000) tail.append(chunk.text).append('\n')
                    if (found == null) {
                        val url = FeishuCli.parseConfigInitUrl(chunk.text)
                        if (url != null) {
                            found = url
                            pageState.update { it.copy(stage = STAGE_CREATE_APP, authUrl = url) }
                        }
                    }
                }

                is LinuxChunk.Exit -> exitCode = chunk.exitCode
                is LinuxChunk.Stderr -> Unit
            }
        }
        pageState.update { it.copy(authUrl = null) }
        if (exitCode != 0) {
            throw IllegalStateException(host.text(ERROR_AUTH_OUTPUT, tail.toString().takeLast(300).trim()))
        }
        if (found == null) {
            throw IllegalStateException(host.text(ERROR_AUTH))
        }
    }

    private suspend fun logout(host: PluginHost) {
        val linux = requireLinux(host)
        pageState.update { it.copy(stage = STAGE_SIGNING_OUT) }
        val result = linux.exec(FeishuCli.logoutCommand(), timeoutMs = LOGOUT_TIMEOUT_MS)
        if (result.exitCode != 0) {
            throw IllegalStateException(host.text(ERROR_LOGOUT, result.stdout.takeLast(300).trim()))
        }
        memory = memory?.copy(connected = false, account = null, scopeCount = 0, missingScopes = emptyList())
        refresh(host)
        host.requestContributionsRefresh()
    }

    private suspend fun readAuthStatus(linux: LinuxService): FeishuCli.AuthStatus? {
        val result = linux.exec(FeishuCli.statusCommand(), timeoutMs = STATUS_TIMEOUT_MS)
        return FeishuCli.parseAuthStatus(result.stdout)
    }

    private suspend fun hasExistingBinary(linux: LinuxService): Boolean = runCatching {
        linux.exec(EXISTS_COMMAND, timeoutMs = VERSION_TIMEOUT_MS).stdout.contains("yes")
    }.getOrDefault(false)

    private fun linuxOrNull(host: PluginHost): LinuxService? =
        runCatching { host.linux() }.getOrNull()?.takeIf { it.available }

    private fun networkOrNull(host: PluginHost): NetworkService? = runCatching { host.network() }.getOrNull()

    private fun requireLinux(host: PluginHost): LinuxService =
        linuxOrNull(host) ?: throw IllegalStateException(host.text(ERROR_LINUX))

    private companion object {
        const val EXISTS_COMMAND = "if [ -x ${FeishuCli.INSTALL_PATH} ]; then echo yes; else echo no; fi"

        val EXTRACT_COMMAND = """
            set -e
            rm -rf extract
            mkdir -p extract
            tar -xzf $ARCHIVE_NAME -C extract
            chmod 755 extract/lark-cli
            ./extract/lark-cli --version
        """.trimIndent()

        val INSTALL_COMMAND = """
            set -e
            mkdir -p /usr/local/bin
            if [ -f ${FeishuCli.INSTALL_PATH} ]; then cp -f ${FeishuCli.INSTALL_PATH} $LARK_CLI_BACKUP_PATH; fi
            cp -f extract/lark-cli $LARK_CLI_TEMP_PATH
            chmod 755 $LARK_CLI_TEMP_PATH
            mv -f $LARK_CLI_TEMP_PATH ${FeishuCli.INSTALL_PATH}
            ${FeishuCli.INSTALL_PATH} --version
            rm -f $LARK_CLI_BACKUP_PATH
        """.trimIndent()
    }
}
