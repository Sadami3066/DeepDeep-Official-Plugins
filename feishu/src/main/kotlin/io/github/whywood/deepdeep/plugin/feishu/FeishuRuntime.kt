package io.github.whywood.deepdeep.plugin.feishu

import io.github.whywood.deepdeep.plugin.api.LinuxChunkKind
import io.github.whywood.deepdeep.plugin.api.LinuxService
import io.github.whywood.deepdeep.plugin.api.LocalizedText
import io.github.whywood.deepdeep.plugin.api.NetworkService
import io.github.whywood.deepdeep.plugin.api.PluginHost
import io.github.whywood.deepdeep.plugin.api.notificationAction
import io.github.whywood.deepdeep.plugin.api.pluginNotificationWithActions
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal enum class FeishuOperation {
    NONE,
    INSTALL,
    UPDATE,
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
    val errorOperation: FeishuOperation? = null,
    val checking: Boolean = true,
    val cancelling: Boolean = false,
    val latestVersion: String? = null,
    val lastCheckedAt: Long = 0,
    val updateChecking: Boolean = false,
    val updateFailed: Boolean = false,
)

internal data class FeishuUpdateResult(
    val latestVersion: String?,
    val release: FeishuRelease?,
    val failed: Boolean,
)

internal class FeishuRuntime {
    val pageState = MutableStateFlow(FeishuPageState())

    @Volatile
    private var memory: FeishuSnapshot? = null

    @Volatile
    private var availableRelease: FeishuRelease? = null

    private val operationLock = Mutex()

    private val refreshLock = Mutex()

    private val updateCheckLock = Mutex()

    private var operationJob: Job? = null

    private var updateCheckJob: Job? = null

    fun currentSnapshot(): FeishuSnapshot? = memory

    fun currentAvailableRelease(): FeishuRelease? = availableRelease

    suspend fun ensureLoaded(host: PluginHost) {
        if (memory == null) memory = FeishuStateStore.load(host)
    }

    suspend fun refresh(host: PluginHost, forceUpdateCheck: Boolean = false) {
        refreshLock.withLock {
            pageState.update { it.copy(checking = true) }
            try {
                ensureLoaded(host)
                val cached = memory
                if (cached != null) {
                    pageState.update { if (it.snapshot == null) it.copy(snapshot = cached) else it }
                }
                val snapshot = readSnapshot(host)
                memory = snapshot
                syncAvailableRelease(snapshot)
                FeishuStateStore.save(host, snapshot)
                pageState.update {
                    it.copy(
                        snapshot = snapshot,
                        checking = false,
                        latestVersion = snapshot.latestVersion,
                        lastCheckedAt = snapshot.lastUpdateCheckAt,
                        updateFailed = snapshot.lastUpdateCheckAt > 0 && snapshot.latestVersion == null,
                        error = if (it.errorOperation == null) null else it.error,
                    )
                }
                checkUpdate(host, snapshot, force = forceUpdateCheck)
            } catch (error: CancellationException) {
                pageState.update { it.copy(checking = false) }
                throw error
            } catch (error: Exception) {
                pageState.update { current ->
                    if (current.errorOperation != null) {
                        current.copy(checking = false)
                    } else {
                        current.copy(
                            checking = false,
                            error = error.message ?: host.text(ERROR_UNKNOWN),
                            errorOperation = null,
                        )
                    }
                }
            }
        }
    }

    fun startInstall(host: PluginHost) {
        val release = availableRelease ?: FeishuUpdate.pinnedRelease()
        startOperation(host, FeishuOperation.INSTALL) { install(host, release, update = false) }
    }

    fun startUpdate(host: PluginHost) {
        val installed = memory?.installedVersion
        val release = availableRelease ?: run {
            val pinned = FeishuUpdate.pinnedRelease()
            pinned.takeIf { installed != null && FeishuUpdate.isNewer(pinned.version, installed) }
        } ?: return
        startOperation(host, FeishuOperation.UPDATE) { install(host, release, update = true) }
    }

    fun startConnect(host: PluginHost) {
        startOperation(host, FeishuOperation.CONNECT) { connect(host) }
    }

    fun startLogout(host: PluginHost) {
        startOperation(host, FeishuOperation.LOGOUT) { logout(host) }
    }

    fun cancelOperation(host: PluginHost) {
        val state = pageState.value
        if (state.operation == FeishuOperation.NONE || state.cancelling) return
        pageState.update {
            it.copy(cancelling = true, stage = STAGE_CANCELLING, progress = null, authUrl = null)
        }
        operationJob?.cancel()
        runCatching { host.ui().snackbar(host.text(SNACKBAR_CANCELLED)) }
    }

    fun retry(host: PluginHost) {
        when (pageState.value.errorOperation) {
            FeishuOperation.INSTALL -> startInstall(host)
            FeishuOperation.UPDATE -> startUpdate(host)
            FeishuOperation.CONNECT -> startConnect(host)
            FeishuOperation.LOGOUT -> startLogout(host)
            FeishuOperation.NONE, null -> check(host)
        }
    }

    fun check(host: PluginHost) {
        if (pageState.value.operation != FeishuOperation.NONE) return
        host.scope.launch { refresh(host, forceUpdateCheck = true) }
    }

    fun requestUpdateCheck(host: PluginHost, force: Boolean) {
        val snapshot = memory ?: return
        checkUpdate(host, snapshot, force)
    }

    fun openAppScopes(host: PluginHost) {
        val appId = (currentSnapshot() ?: pageState.value.snapshot)?.appId?.takeIf { it.isNotBlank() } ?: return
        openUrl(host, "$APP_CONSOLE_URL/$appId/auth")
    }

    fun openAuthUrl(host: PluginHost) {
        val url = pageState.value.authUrl ?: return
        openUrl(host, url)
    }

    fun copyAuthUrl(host: PluginHost) {
        val url = pageState.value.authUrl ?: return
        val copied = runCatching { host.clipboard().setText(url) }.isSuccess
        runCatching { host.ui().snackbar(host.text(if (copied) SNACKBAR_LINK_COPIED else ERROR_COPY)) }
    }

    suspend fun reset(host: PluginHost) {
        operationJob?.cancel()
        operationJob = null
        updateCheckJob?.cancel()
        updateCheckJob = null
        availableRelease = null
        memory = null
        FeishuStateStore.delete(host)
        pageState.update { FeishuPageState() }
    }

    private fun openUrl(host: PluginHost, url: String) {
        runCatching { host.ui().openUrl(url) }
    }

    private fun startOperation(host: PluginHost, operation: FeishuOperation, block: suspend () -> Unit) {
        if (pageState.value.operation != FeishuOperation.NONE) return
        pageState.update {
            it.copy(
                operation = operation,
                stage = null,
                progress = null,
                authUrl = null,
                error = null,
                errorOperation = null,
                checking = false,
                cancelling = false,
            )
        }
        val job = host.scope.launch {
            operationLock.withLock {
                try {
                    block()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    pageState.update {
                        it.copy(
                            error = error.message ?: host.text(ERROR_UNKNOWN),
                            errorOperation = operation,
                        )
                    }
                }
            }
        }
        operationJob = job
        if (pageState.value.cancelling) job.cancel()
        job.invokeOnCompletion {
            pageState.update { it.copy(operation = FeishuOperation.NONE) }
            if (operationJob === job) operationJob = null
        }
    }

    private suspend fun readSnapshot(host: PluginHost): FeishuSnapshot {
        val checkedAt = System.currentTimeMillis()
        val previous = memory ?: FeishuSnapshot()
        val linux = linuxOrNull(host) ?: return FeishuSnapshot(
            checkedAt = checkedAt,
            latestVersion = previous.latestVersion,
            latestSha256 = previous.latestSha256,
            lastUpdateCheckAt = previous.lastUpdateCheckAt,
        )
        val versionResult = linux.exec(FeishuCli.versionCommand(), timeoutMs = VERSION_TIMEOUT_MS)
        val version = FeishuCli.parseVersion(versionResult.stdout)
        if (versionResult.exitCode != 0 || version == null) {
            return FeishuSnapshot(
                checkedAt = checkedAt,
                latestVersion = previous.latestVersion,
                latestSha256 = previous.latestSha256,
                lastUpdateCheckAt = previous.lastUpdateCheckAt,
            )
        }
        val statusResult = linux.exec(FeishuCli.statusCommand(), timeoutMs = STATUS_TIMEOUT_MS)
        val parsed = FeishuCli.parseAuthStatus(statusResult.stdout)
        return if (parsed == null) {
            previous.copy(
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
                latestVersion = previous.latestVersion,
                latestSha256 = previous.latestSha256,
                lastUpdateCheckAt = previous.lastUpdateCheckAt,
            )
        }
    }

    private fun syncAvailableRelease(snapshot: FeishuSnapshot?) {
        val installed = snapshot?.installedVersion ?: return
        val latest = snapshot.latestVersion ?: return
        val sha = snapshot.latestSha256 ?: return
        availableRelease = if (FeishuUpdate.isNewer(latest, installed)) {
            FeishuUpdate.release(latest, sha)
        } else {
            null
        }
    }

    private fun checkUpdate(host: PluginHost, snapshot: FeishuSnapshot, force: Boolean) {
        val installed = snapshot.installedVersion ?: return
        if (updateCheckJob?.isActive == true) return
        val now = System.currentTimeMillis()
        if (!force && !FeishuUpdate.shouldCheck(snapshot.lastUpdateCheckAt, now)) return
        val cachedLatest = snapshot.latestVersion?.takeIf { FeishuUpdate.compareVersions(it, installed) >= 0 }
        pageState.update { it.copy(updateChecking = true) }
        updateCheckJob = host.scope.launch {
            updateCheckLock.withLock {
                val result = discoverLatest(host, installed)
                val checkedAt = System.currentTimeMillis()
                val latest = result.latestVersion ?: cachedLatest
                val updated = (memory ?: snapshot).copy(
                    latestVersion = latest,
                    latestSha256 = result.release?.sha256 ?: (memory ?: snapshot).latestSha256,
                    lastUpdateCheckAt = checkedAt,
                )
                memory = updated
                FeishuStateStore.save(host, updated)
                if (!result.failed) availableRelease = result.release
                pageState.update {
                    it.copy(
                        snapshot = updated,
                        latestVersion = latest,
                        lastCheckedAt = checkedAt,
                        updateChecking = false,
                        updateFailed = result.failed,
                    )
                }
                if (result.failed) {
                    runCatching { host.logs().warn("feishu update check failed") }
                }
            }
        }
    }

    private suspend fun discoverLatest(host: PluginHost, installed: String): FeishuUpdateResult {
        val network = networkOrNull(host) ?: return FeishuUpdateResult(null, null, true)
        val github = try {
            val response = network.request(
                "GET",
                FeishuUpdate.GITHUB_LATEST_URL,
                mapOf("Accept" to "application/vnd.github+json", "User-Agent" to "DeepDeep-Feishu-Plugin"),
                timeoutMs = UPDATE_CHECK_TIMEOUT_MS,
            )
            val version = if (response.statusCode in 200..299) {
                FeishuUpdate.parseGithubLatestVersion(response.body)
            } else {
                null
            }
            if (version == null) {
                null
            } else {
                val checksums = fetchChecksums(network, FeishuUpdate.checksumsUrls(version)).orEmpty()
                FeishuUpdate.releaseFromChecksums(version, checksums)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            null
        }
        if (github != null) {
            return updateResult(github, installed)
        }
        val npmmirror = try {
            val response = network.request(
                "GET",
                FeishuUpdate.NPMMIRROR_INDEX_URL,
                timeoutMs = UPDATE_CHECK_TIMEOUT_MS,
            )
            val latest = if (response.statusCode in 200..299) {
                FeishuUpdate.latestVersion(FeishuUpdate.parseNpmmirrorVersions(response.body))
            } else {
                null
            }
            if (latest == null) {
                null
            } else {
                val checksums = fetchChecksums(network, FeishuUpdate.checksumsUrls(latest)).orEmpty()
                FeishuUpdate.releaseFromChecksums(latest, checksums)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            null
        }
        if (npmmirror != null) {
            return updateResult(npmmirror, installed)
        }
        return FeishuUpdateResult(null, null, true)
    }

    private fun updateResult(release: FeishuRelease, installed: String): FeishuUpdateResult {
        val latest = FeishuUpdate.latestVersion(listOf(release.version, installed))
        val available = release.takeIf { FeishuUpdate.isNewer(it.version, installed) }
        return FeishuUpdateResult(latest, available, failed = false)
    }

    private suspend fun fetchChecksums(network: NetworkService, urls: List<String>): Map<String, String>? {
        for (url in urls) {
            val checksums = try {
                val response = network.request("GET", url, timeoutMs = UPDATE_CHECK_TIMEOUT_MS)
                if (response.statusCode !in 200..299) {
                    null
                } else {
                    FeishuUpdate.parseChecksums(response.body).takeIf { it.isNotEmpty() }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                null
            }
            if (checksums != null) return checksums
        }
        return null
    }

    private suspend fun install(host: PluginHost, release: FeishuRelease, update: Boolean) {
        val linux = requireLinux(host)
        val staging = File(host.dataDir, STAGING_DIR)
        val hadBinary = hasExistingBinary(linux)
        var installed = false
        try {
            staging.deleteRecursively()
            staging.mkdirs()
            pageState.update { it.copy(stage = STAGE_DOWNLOADING, progress = 0) }
            val archive = File(staging, release.archiveName)
            downloadArchive(host, archive, release) { percent ->
                pageState.update { it.copy(progress = percent) }
            }
            pageState.update { it.copy(stage = STAGE_VERIFYING, progress = null) }
            val extract = linux.exec(extractCommand(release.archiveName), cwd = staging.absolutePath, timeoutMs = EXTRACT_TIMEOUT_MS)
            val stagedVersion = FeishuCli.parseVersion(extract.stdout)
            if (extract.exitCode != 0 || stagedVersion != release.version) {
                throw IllegalStateException(host.text(ERROR_VERSION, extract.stdout.takeLast(400).trim()))
            }
            pageState.update { it.copy(stage = if (update) STAGE_UPDATING else STAGE_INSTALLING) }
            val install = linux.exec(INSTALL_COMMAND, cwd = staging.absolutePath, timeoutMs = INSTALL_TIMEOUT_MS)
            val installedVersion = FeishuCli.parseVersion(install.stdout)
            if (install.exitCode != 0 || installedVersion != release.version) {
                throw IllegalStateException(host.text(ERROR_INSTALL, install.stdout.takeLast(400).trim()))
            }
            installed = true
            pageState.update { it.copy(stage = if (update) STAGE_UPDATE_FINISHING else STAGE_INSTALL_FINISHING) }
            memory = (memory ?: FeishuSnapshot()).copy(
                installedVersion = release.version,
                latestVersion = release.version,
                latestSha256 = release.sha256,
                lastUpdateCheckAt = System.currentTimeMillis(),
            )
            availableRelease = null
            refresh(host)
            host.requestContributionsRefresh()
            notifyInstalled(host)
            host.ui().snackbar(host.text(if (update) SNACKBAR_UPDATE_DONE else SNACKBAR_INSTALL_DONE))
        } catch (error: CancellationException) {
            withContext(NonCancellable) {
                if (!installed) {
                    runCatching { linux.exec(feishuRestoreCommand(hadBinary), timeoutMs = CLEANUP_TIMEOUT_MS) }
                }
                staging.deleteRecursively()
            }
            throw error
        } catch (error: Exception) {
            if (!installed) {
                runCatching { linux.exec(feishuRestoreCommand(hadBinary), timeoutMs = CLEANUP_TIMEOUT_MS) }
            }
            throw error
        } finally {
            staging.deleteRecursively()
        }
    }

    private suspend fun downloadArchive(
        host: PluginHost,
        target: File,
        release: FeishuRelease,
        onProgress: (Int?) -> Unit,
    ) {
        val network = networkOrNull(host) ?: throw IllegalStateException(host.text(ERROR_UNKNOWN))
        var lastError: Exception? = null
        for (url in release.downloadUrls) {
            try {
                network.download(url, target.absolutePath, release.sha256, onProgress)
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
        pageState.update { it.copy(stage = STAGE_LOGIN_FINISHING) }
        refresh(host)
        host.requestContributionsRefresh()
        host.ui().snackbar(host.text(SNACKBAR_CONNECTED))
    }

    private suspend fun runConfigInit(host: PluginHost, linux: LinuxService) {
        val tail = StringBuilder()
        var found: String? = null
        var exitCode: Int? = null
        linux.stream(FeishuCli.configInitCommand()).collect { chunk ->
            when (chunk.kind) {
                LinuxChunkKind.STDOUT -> {
                    val text = chunk.text.orEmpty()
                    if (tail.length < 2000) tail.append(text).append('\n')
                    if (found == null) {
                        val url = FeishuCli.parseConfigInitUrl(text)
                        if (url != null) {
                            found = url
                            pageState.update { it.copy(stage = STAGE_CREATE_APP, authUrl = url) }
                        }
                    }
                }

                LinuxChunkKind.EXIT -> exitCode = chunk.exitCode
                else -> Unit
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
        host.ui().snackbar(host.text(SNACKBAR_LOGGED_OUT))
    }

    private fun notifyInstalled(host: PluginHost) {
        runCatching {
            host.notifications().notifyChecked(
                pluginNotificationWithActions(
                    id = NOTIFY_CLI_ID,
                    title = host.text(NOTIFY_CLI_TITLE),
                    body = host.text(NOTIFY_CLI_BODY),
                    actions = listOf(notificationAction(ACTION_OPEN_PAGE, host.text(ACTION_OPEN_PAGE_LABEL))),
                ),
            )
        }
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

        fun extractCommand(archiveName: String): String = """
            set -e
            rm -rf extract
            mkdir -p extract
            tar -xzf $archiveName -C extract
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
