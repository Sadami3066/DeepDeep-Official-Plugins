package io.github.whywood.superpi.plugin.hotel

import io.github.whywood.superpi.plugin.api.LinuxChunkKind
import io.github.whywood.superpi.plugin.api.LinuxService
import io.github.whywood.superpi.plugin.api.PluginHost
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal enum class HotelOperation { NONE, INSTALL, UPDATE, LOGIN, LOGOUT, CHECK }

internal data class HotelPageState(
    val snapshot: HotelSnapshot? = null,
    val operation: HotelOperation = HotelOperation.NONE,
    val failedOperation: HotelOperation? = null,
    val stage: String? = null,
    val loginUrl: String? = null,
    val error: String? = null,
    val checking: Boolean = true,
    val latestVersion: String? = null,
    val updateChecking: Boolean = false,
    val lastUpdateCheckedAt: Long = 0L,
)

internal class HotelRuntime {
    val pageState = MutableStateFlow(HotelPageState())

    @Volatile
    private var memory: HotelSnapshot? = null

    private val operationLock = Mutex()
    private var operationJob: Job? = null

    fun currentSnapshot(): HotelSnapshot? = memory

    suspend fun refresh(host: PluginHost) {
        val linux = linuxOrNull(host)
        if (linux == null) {
            val snapshot = HotelSnapshot(linuxAvailable = false)
            memory = snapshot
            pageState.update {
                it.copy(snapshot = snapshot, checking = false, error = null)
            }
            return
        }
        val versionResult = linux.exec(HotelCli.versionCommand(), timeoutMs = 20_000)
        val version = HotelCli.parseVersion(versionResult.stdout)
        if (version == null) {
            val snapshot = HotelSnapshot(checkedAt = System.currentTimeMillis(), linuxAvailable = true)
            memory = snapshot
            pageState.update {
                it.copy(snapshot = snapshot, checking = false)
            }
            return
        }
        val statusResult = linux.exec(HotelCli.statusCommand(), timeoutMs = 20_000)
        val status = HotelCli.parseStatus(statusResult.stdout)
        val snapshot = HotelSnapshot(
            installedVersion = version,
            loggedIn = status.loggedIn,
            account = status.account,
            checkedAt = System.currentTimeMillis(),
            linuxAvailable = true,
        )
        memory = snapshot
        pageState.update {
            it.copy(
                snapshot = snapshot,
                checking = false,
                error = if (it.operation == HotelOperation.NONE) null else it.error,
            )
        }
        applyUpdateState(host)
    }

    fun check(host: PluginHost) {
        if (pageState.value.operation != HotelOperation.NONE) return
        host.scope.launch {
            pageState.update { it.copy(checking = true, failedOperation = null) }
            runCatching { refresh(host) }
                .onFailure { error ->
                    pageState.update {
                        it.copy(
                            checking = false,
                            error = error.message ?: host.text(ERROR_UNKNOWN),
                            failedOperation = HotelOperation.CHECK,
                        )
                    }
                }
        }
    }

    fun startInstall(host: PluginHost) {
        start(host, HotelOperation.INSTALL) { install(host) }
    }

    fun startUpdate(host: PluginHost) {
        start(host, HotelOperation.UPDATE) { update(host) }
    }

    fun requestUpdateCheck(host: PluginHost) {
        if (pageState.value.operation != HotelOperation.NONE || pageState.value.updateChecking) return
        host.scope.launch {
            val settings = runCatching { host.settings() }.getOrNull() ?: return@launch
            runCatching { checkLatest(host, settings, notifyFailure = true) }
        }
    }

    fun startLogin(host: PluginHost) {
        start(host, HotelOperation.LOGIN) { login(host) }
    }

    fun startLogout(host: PluginHost) {
        start(host, HotelOperation.LOGOUT) {
            val linux = requireLinux(host)
            pageState.update { it.copy(stage = host.text(STAGE_LOGOUT)) }
            linux.exec(HotelCli.logoutCommand(), timeoutMs = 20_000)
            HotelReadyCache.reset()
            refresh(host)
        }
    }

    fun retry(host: PluginHost) {
        when (pageState.value.failedOperation) {
            HotelOperation.INSTALL -> startInstall(host)
            HotelOperation.UPDATE -> startUpdate(host)
            HotelOperation.LOGIN -> startLogin(host)
            HotelOperation.LOGOUT -> startLogout(host)
            HotelOperation.CHECK, null -> check(host)
            HotelOperation.NONE -> check(host)
        }
    }

    fun openAuthUrl(host: PluginHost) {
        pageState.value.loginUrl?.let { url -> runCatching { host.ui().openUrl(url) } }
    }

    fun copyAuthUrl(host: PluginHost) {
        val url = pageState.value.loginUrl ?: return
        val copied = runCatching { host.clipboard().setText(url) }.isSuccess
        runCatching { host.ui().snackbar(host.text(if (copied) SNACKBAR_COPIED else ERROR_COPY)) }
    }

    fun cancel(host: PluginHost) {
        operationJob?.cancel()
        operationJob = null
        pageState.update {
            it.copy(
                operation = HotelOperation.NONE,
                stage = null,
                loginUrl = null,
                failedOperation = null,
                error = null,
            )
        }
        host.requestContributionsRefresh()
    }

    suspend fun reset(host: PluginHost) {
        operationJob?.cancel()
        operationJob = null
        memory = null
        HotelReadyCache.reset()
        pageState.value = HotelPageState()
        runCatching {
            val linux = linuxOrNull(host)
            linux?.exec(HotelCli.uninstallCommand(), timeoutMs = 120_000)
        }
    }

    private fun start(host: PluginHost, operation: HotelOperation, block: suspend () -> Unit) {
        if (pageState.value.operation != HotelOperation.NONE) return
        pageState.update {
            it.copy(
                operation = operation,
                stage = null,
                loginUrl = null,
                error = null,
                failedOperation = null,
                checking = false,
            )
        }
        val job = host.scope.launch {
            operationLock.withLock {
                try {
                    block()
                    pageState.update {
                        it.copy(stage = null, operation = HotelOperation.NONE, loginUrl = null)
                    }
                } catch (cancelled: CancellationException) {
                    pageState.update {
                        it.copy(stage = null, operation = HotelOperation.NONE, loginUrl = null)
                    }
                    throw cancelled
                } catch (error: Exception) {
                    pageState.update {
                        it.copy(
                            operation = HotelOperation.NONE,
                            failedOperation = operation,
                            stage = null,
                            error = error.message ?: host.text(ERROR_UNKNOWN),
                        )
                    }
                }
            }
        }
        operationJob = job
    }

    private suspend fun install(host: PluginHost) {
        val linux = requireLinux(host)
        pageState.update { it.copy(stage = host.text(STAGE_INSTALL)) }
        val result = linux.exec(HotelCli.installCommand(), timeoutMs = 300_000)
        val versionResult = linux.exec(HotelCli.versionCommand(), timeoutMs = 20_000)
        val version = HotelCli.parseVersion(versionResult.stdout)
        if (result.exitCode != 0 || version == null) {
            host.logs().warn("hotel install failed: ${(result.stdout + versionResult.stdout).takeLast(800)}")
            throw IllegalStateException(host.t("安装没有完成，请检查网络后重试。", "Install didn't finish. Check your connection and try again."))
        }
        HotelReadyCache.reset()
        markUpdateCheckDue(host)
        pageState.update { it.copy(stage = host.text(STAGE_CHECK)) }
        refresh(host)
        pageState.update { it.copy(failedOperation = null, error = null) }
        host.requestContributionsRefresh()
        host.ui().snackbar(host.text(SNACKBAR_INSTALLED))
    }

    private suspend fun update(host: PluginHost) {
        val linux = requireLinux(host)
        pageState.update { it.copy(stage = host.text(STAGE_UPDATE)) }
        val result = linux.exec(HotelCli.updateCommand(), timeoutMs = 300_000)
        val versionResult = linux.exec(HotelCli.versionCommand(), timeoutMs = 20_000)
        val version = HotelCli.parseVersion(versionResult.stdout)
        if (result.exitCode != 0 || version == null) {
            host.logs().warn("hotel update failed: ${(result.stdout + versionResult.stdout).takeLast(800)}")
            throw IllegalStateException(host.t("更新没有完成，请检查网络后重试。", "Update didn't finish. Check your connection and try again."))
        }
        HotelReadyCache.reset()
        markUpdateCheckDue(host)
        pageState.update { it.copy(stage = host.text(STAGE_CHECK)) }
        refresh(host)
        pageState.update { it.copy(failedOperation = null, error = null) }
        host.requestContributionsRefresh()
        host.ui().snackbar(host.text(SNACKBAR_UPDATED))
    }

    private suspend fun login(host: PluginHost) {
        val linux = requireLinux(host)
        pageState.update { it.copy(stage = host.text(STAGE_LOGIN)) }
        var opened = false
        linux.stream(HotelCli.loginCommand()).collect { chunk ->
            if (chunk.kind == LinuxChunkKind.STDOUT) {
                val text = chunk.text.orEmpty()
                HotelCli.parseLoginUrl(text)?.let { url ->
                    if (pageState.value.loginUrl == null) {
                        pageState.update { it.copy(loginUrl = url, stage = host.text(STAGE_WAITING)) }
                        if (!opened) {
                            opened = true
                            runCatching { host.ui().openUrl(url) }
                        }
                    }
                }
            }
        }
        val status = runCatching {
            val result = linux.exec(HotelCli.statusCommand(), timeoutMs = 20_000)
            HotelCli.parseStatus(result.stdout)
        }.getOrNull()
        if (status?.loggedIn != true) {
            throw IllegalStateException(host.text(ERROR_LOGIN))
        }
        HotelReadyCache.reset()
        refresh(host)
        pageState.update { it.copy(failedOperation = null, error = null) }
        host.requestContributionsRefresh()
        host.ui().snackbar(host.text(SNACKBAR_LOGGED_IN))
    }

    private suspend fun applyUpdateState(host: PluginHost) {
        if (pageState.value.operation != HotelOperation.NONE) return
        if (memory?.installedVersion == null) return
        val settings = runCatching { host.settings() }.getOrNull() ?: return
        val now = System.currentTimeMillis()
        val last = runCatching { settings.get(UPDATE_CHECKED_KEY) }.getOrNull()?.toLongOrNull() ?: 0L
        val cached = runCatching { settings.get(UPDATE_LATEST_KEY) }.getOrNull()
        pageState.update { it.copy(latestVersion = cached, lastUpdateCheckedAt = last) }
        if (now - last < UPDATE_INTERVAL_MS) return
        runCatching { checkLatest(host, settings, notifyFailure = false) }
    }

    private suspend fun checkLatest(
        host: PluginHost,
        settings: io.github.whywood.superpi.plugin.api.PluginSettingsService,
        notifyFailure: Boolean,
    ) {
        pageState.update { it.copy(updateChecking = true) }
        val linux = linuxOrNull(host)
        val result = linux?.let { runCatching { it.exec(HotelCli.latestVersionCommand(), timeoutMs = 30_000) }.getOrNull() }
        val latest = result?.let { HotelCli.parseLatestVersion(it.stdout) }
        val now = System.currentTimeMillis()
        runCatching { settings.put(UPDATE_CHECKED_KEY, now.toString()) }
        if (latest != null) runCatching { settings.put(UPDATE_LATEST_KEY, latest) }
        pageState.update {
            it.copy(
                updateChecking = false,
                latestVersion = latest ?: it.latestVersion,
                lastUpdateCheckedAt = now,
                error = if (notifyFailure && latest == null) host.text(ERROR_UNKNOWN) else it.error,
            )
        }
    }

    private suspend fun markUpdateCheckDue(host: PluginHost) {
        val settings = runCatching { host.settings() }.getOrNull() ?: return
        runCatching { settings.put(UPDATE_CHECKED_KEY, "0") }
    }

    private fun requireLinux(host: PluginHost): LinuxService =
        linuxOrNull(host) ?: throw IllegalStateException(host.text(ERROR_LINUX))

    private fun linuxOrNull(host: PluginHost): LinuxService? =
        runCatching { host.linux() }.getOrNull()?.takeIf { it.available }

    private companion object {
        const val UPDATE_CHECKED_KEY = "update.checked_at"
        const val UPDATE_LATEST_KEY = "update.latest_version"
        const val UPDATE_INTERVAL_MS = 24 * 60 * 60 * 1000L
    }
}