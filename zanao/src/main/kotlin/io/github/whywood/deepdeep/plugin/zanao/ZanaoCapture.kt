package io.github.whywood.deepdeep.plugin.zanao

import io.github.whywood.deepdeep.plugin.api.HostAccessLevel
import io.github.whywood.deepdeep.plugin.api.PluginHost
import io.github.whywood.deepdeep.plugin.api.PrivilegeLevel
import io.github.whywood.deepdeep.plugin.api.foregroundServiceOptions
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal sealed interface CaptureState {
    data object Idle : CaptureState
    data class Running(val message: String) : CaptureState
    data class Success(val message: String) : CaptureState
    data class Failed(val message: String) : CaptureState
}

internal class ZanaoCapture(private val scope: CoroutineScope) {
    private val _state = MutableStateFlow<CaptureState>(CaptureState.Idle)
    val state: StateFlow<CaptureState> = _state.asStateFlow()
    @Volatile
    private var job: Job? = null

    fun start(host: PluginHost) {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            capture(host)
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    suspend fun cleanupStale(host: PluginHost) {
        val marker = runCatching { host.settings().get(SETTING_CAPTURE_ACTIVE) }.getOrNull()
        if (marker != "1") return
        val previous = runCatching { host.settings().get(SETTING_PREVIOUS_PROXY) }.getOrNull()
        restoreProxy(host, previous)
        runCatching { host.settings().remove(SETTING_CAPTURE_ACTIVE) }
        runCatching { host.settings().remove(SETTING_PREVIOUS_PROXY) }
        _state.value = CaptureState.Idle
    }

    private suspend fun capture(host: PluginHost) {
        runCatching { cleanupStale(host) }
        val privilege = runCatching { host.privilege() }.getOrNull()
        if (privilege == null || privilege.access == HostAccessLevel.LOCAL) {
            _state.value = CaptureState.Failed(host.t("需要把系统访问切到无线调试（ADB）或 Root", "Switch system access to ADB or root"))
            return
        }

        var proxy: ZanaoProxy? = null
        var foreground = false
        var previous: String? = null
        val pending = CompletableDeferred<CapturedSession>()
        try {
            _state.value = CaptureState.Running(host.t("正在准备本地代理…", "Preparing local proxy…"))
            previous = readProxy(host)
            runCatching { host.settings().put(SETTING_PREVIOUS_PROXY, previous ?: "null") }
            runCatching { host.settings().put(SETTING_CAPTURE_ACTIVE, "1") }

            proxy = ZanaoProxy { session -> pending.complete(session) }
            val port = proxy.start()
            setProxy(host, "127.0.0.1:$port")
            foreground = runCatching {
                host.background().startForeground(
                    foregroundServiceOptions(
                        CAPTURE_SERVICE_ID,
                        host.t("正在等待校园集市登录", "Waiting for Zanao sign-in"),
                        host.t("登录成功后会自动导入账号", "The account will be imported after sign-in"),
                    ),
                )
            }.getOrDefault(false)

            runCatching { host.packages().launch(ZANAO_PACKAGE) }
            _state.value = CaptureState.Running(host.t("请在校园集市里完成微信登录", "Complete WeChat sign-in in Zanao"))
            val session = withTimeoutOrNull(CAPTURE_TIMEOUT_MS) { pending.await() }
            if (session == null) {
                _state.value = CaptureState.Failed(host.t("等待超时，请重试", "Timed out, please retry"))
            } else {
                saveSession(host, session)
                delay(800)
                _state.value = CaptureState.Success(host.t("账号已导入", "Account imported"))
            }
        } catch (error: Throwable) {
            _state.value = CaptureState.Failed(error.message ?: host.t("导入失败", "Import failed"))
        } finally {
            proxy?.stop()
            restoreProxy(host, previous)
            runCatching { host.settings().remove(SETTING_CAPTURE_ACTIVE) }
            runCatching { host.settings().remove(SETTING_PREVIOUS_PROXY) }
            if (foreground) runCatching { host.background().stopForeground(CAPTURE_SERVICE_ID) }
        }
    }

    private suspend fun saveSession(host: PluginHost, session: CapturedSession) {
        host.secrets().put(SECRET_TOKEN, session.token)
        session.device?.takeIf { it.isNotBlank() }?.let { host.secrets().put(SECRET_DEVICE, it) }
        val alias = session.alias?.takeIf { it.isNotBlank() } ?: DEFAULT_ALIAS
        host.settings().put(SETTING_ALIAS, alias)
    }

    private suspend fun readProxy(host: PluginHost): String? =
        runCatching { host.privilege().exec("settings get global http_proxy", PrivilegeLevel.AUTO).stdout.trim() }
            .getOrNull()
            ?.takeUnless { it.isBlank() || it == "null" || it == ":0" }

    private suspend fun setProxy(host: PluginHost, value: String) {
        clearProxy(host)
        host.privilege().exec("settings put global http_proxy " + quote(value), PrivilegeLevel.AUTO)
    }

    private suspend fun clearProxy(host: PluginHost) {
        runCatching { host.privilege().exec("settings delete global global_http_proxy_host", PrivilegeLevel.AUTO) }
        runCatching { host.privilege().exec("settings delete global global_http_proxy_port", PrivilegeLevel.AUTO) }
        runCatching { host.privilege().exec("settings delete global http_proxy", PrivilegeLevel.AUTO) }
    }

    private suspend fun restoreProxy(host: PluginHost, previous: String?) {
        clearProxy(host)
        if (!previous.isNullOrBlank() && previous != "null" && previous != ":0") {
            runCatching { host.privilege().exec("settings put global http_proxy " + quote(previous), PrivilegeLevel.AUTO) }
        }
    }

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
