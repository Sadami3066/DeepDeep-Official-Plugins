package io.github.whywood.deepdeep.plugin.hotel

import io.github.whywood.deepdeep.plugin.api.LinuxChunkKind
import io.github.whywood.deepdeep.plugin.api.LinuxService
import io.github.whywood.deepdeep.plugin.api.PluginHost
import io.github.whywood.deepdeep.plugin.api.localizedText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal enum class HotelOperation { NONE, INSTALL, LOGIN, LOGOUT }

internal data class HotelPageState(
    val snapshot: HotelSnapshot? = null,
    val operation: HotelOperation = HotelOperation.NONE,
    val stage: String? = null,
    val loginUrl: String? = null,
    val error: String? = null,
    val checking: Boolean = true,
)

internal class HotelRuntime {
    val pageState = MutableStateFlow(HotelPageState())

    @Volatile
    private var memory: HotelSnapshot? = null

    private val operationLock = Mutex()
    private var operationJob: Job? = null

    fun currentSnapshot(): HotelSnapshot? = memory

    suspend fun refresh(host: PluginHost) {
        val linux = linuxOrNull(host) ?: run {
            memory = HotelSnapshot()
            pageState.update { it.copy(snapshot = HotelSnapshot(), checking = false, error = null) }
            return
        }
        val versionResult = linux.exec(HotelCli.versionCommand(), timeoutMs = 20_000)
        val version = HotelCli.parseVersion(versionResult.stdout)
        if (version == null) {
            val snapshot = HotelSnapshot(checkedAt = System.currentTimeMillis())
            memory = snapshot
            pageState.update { it.copy(snapshot = snapshot, checking = false) }
            return
        }
        val statusResult = linux.exec(HotelCli.statusCommand(), timeoutMs = 20_000)
        val status = HotelCli.parseStatus(statusResult.stdout)
        val snapshot = HotelSnapshot(
            installedVersion = version,
            loggedIn = status.loggedIn,
            account = status.account,
            checkedAt = System.currentTimeMillis(),
        )
        memory = snapshot
        pageState.update {
            it.copy(
                snapshot = snapshot,
                checking = false,
                error = if (it.operation == HotelOperation.NONE) null else it.error,
            )
        }
    }

    fun check(host: PluginHost) {
        if (pageState.value.operation != HotelOperation.NONE) return
        host.scope.launch {
            pageState.update { it.copy(checking = true) }
            runCatching { refresh(host) }
                .onFailure { error ->
                    pageState.update {
                        it.copy(checking = false, error = error.message ?: host.text(ERROR_UNKNOWN))
                    }
                }
        }
    }

    fun startInstall(host: PluginHost) {
        start(host, HotelOperation.INSTALL) { install(host) }
    }

    fun startLogin(host: PluginHost) {
        start(host, HotelOperation.LOGIN) { login(host) }
    }

    fun startLogout(host: PluginHost) {
        start(host, HotelOperation.LOGOUT) {
            val linux = requireLinux(host)
            pageState.update { it.copy(stage = host.text(STAGE_LOGOUT)) }
            linux.exec(HotelCli.logoutCommand(), timeoutMs = 20_000)
            refresh(host)
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
            it.copy(operation = HotelOperation.NONE, stage = null, loginUrl = null)
        }
    }

    suspend fun reset(host: PluginHost) {
        operationJob?.cancel()
        operationJob = null
        memory = null
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
                checking = false,
            )
        }
        val job = host.scope.launch {
            operationLock.withLock {
                try {
                    block()
                    pageState.update { it.copy(stage = null, operation = HotelOperation.NONE, loginUrl = null) }
                } catch (cancelled: CancellationException) {
                    pageState.update { it.copy(stage = null, operation = HotelOperation.NONE, loginUrl = null) }
                    throw cancelled
                } catch (error: Exception) {
                    pageState.update {
                        it.copy(
                            operation = HotelOperation.NONE,
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
            throw IllegalStateException(host.text(ERROR_INSTALL, (result.stdout + versionResult.stdout).takeLast(400).trim()))
        }
        pageState.update { it.copy(stage = host.text(STAGE_CHECK)) }
        refresh(host)
        host.requestContributionsRefresh()
        host.ui().snackbar(host.text(SNACKBAR_INSTALLED))
    }

    private suspend fun login(host: PluginHost) {
        val linux = requireLinux(host)
        pageState.update { it.copy(stage = host.text(STAGE_LOGIN)) }
        val tail = StringBuilder()
        linux.stream(HotelCli.loginCommand()).collect { chunk ->
            if (chunk.kind == LinuxChunkKind.STDOUT) {
                val text = chunk.text.orEmpty()
                if (tail.length < 8000) tail.append(text).append('\n')
                if (pageState.value.loginUrl == null) {
                    HotelCli.parseLoginUrl(text)?.let { url ->
                        pageState.update { it.copy(loginUrl = url, stage = host.text(STAGE_WAITING)) }
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
        refresh(host)
        host.requestContributionsRefresh()
        host.ui().snackbar(host.text(SNACKBAR_LOGGED_IN))
    }

    private fun requireLinux(host: PluginHost): LinuxService =
        linuxOrNull(host) ?: throw IllegalStateException(host.text(ERROR_LINUX))

    private fun linuxOrNull(host: PluginHost): LinuxService? =
        runCatching { host.linux() }.getOrNull()?.takeIf { it.available }

}
