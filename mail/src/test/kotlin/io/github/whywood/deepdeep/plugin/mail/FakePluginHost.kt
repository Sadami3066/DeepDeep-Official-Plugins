package io.github.whywood.deepdeep.plugin.mail

import android.content.Context
import io.github.whywood.deepdeep.plugin.api.AppInfoService
import io.github.whywood.deepdeep.plugin.api.ChatService
import io.github.whywood.deepdeep.plugin.api.DEFAULT_LANGUAGE
import io.github.whywood.deepdeep.plugin.api.FileService
import io.github.whywood.deepdeep.plugin.api.GoalService
import io.github.whywood.deepdeep.plugin.api.JobService
import io.github.whywood.deepdeep.plugin.api.LinuxService
import io.github.whywood.deepdeep.plugin.api.LocalizedText
import io.github.whywood.deepdeep.plugin.api.LogService
import io.github.whywood.deepdeep.plugin.api.McpService
import io.github.whywood.deepdeep.plugin.api.MiniappService
import io.github.whywood.deepdeep.plugin.api.NetworkService
import io.github.whywood.deepdeep.plugin.api.NotificationService
import io.github.whywood.deepdeep.plugin.api.PluginBus
import io.github.whywood.deepdeep.plugin.api.PluginHost
import io.github.whywood.deepdeep.plugin.api.PluginLogLevel
import io.github.whywood.deepdeep.plugin.api.PluginManifest
import io.github.whywood.deepdeep.plugin.api.PluginNotification
import io.github.whywood.deepdeep.plugin.api.PluginServiceUnavailable
import io.github.whywood.deepdeep.plugin.api.ScreenService
import io.github.whywood.deepdeep.plugin.api.SecretService
import io.github.whywood.deepdeep.plugin.api.SessionService
import io.github.whywood.deepdeep.plugin.api.TimerService
import io.github.whywood.deepdeep.plugin.api.UiService
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

internal class FakePluginHost(dataDirectory: File) : PluginHost {
    override val manifest: PluginManifest get() = throw PluginServiceUnavailable("manifest")

    override val context: Context get() = throw PluginServiceUnavailable("context")

    override val dataDir: File = dataDirectory

    override val assetsDir: File get() = throw PluginServiceUnavailable("assets")

    override val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val secretValues = java.util.concurrent.ConcurrentHashMap<String, String>()
    val notifications = CopyOnWriteArrayList<PluginNotification>()
    val logMessages = CopyOnWriteArrayList<String>()

    override fun text(source: LocalizedText, vararg args: Any): String =
        source.resolve("zh") ?: source.resolve() ?: source.values[DEFAULT_LANGUAGE].orEmpty()

    override fun secrets(): SecretService = object : SecretService {
        override suspend fun get(key: String): String? = secretValues[key]

        override suspend fun put(key: String, value: String) {
            secretValues[key] = value
        }

        override suspend fun remove(key: String) {
            secretValues.remove(key)
        }

        override suspend fun clear() {
            secretValues.clear()
        }
    }

    override fun notifications(): NotificationService = object : NotificationService {
        override val enabled: Boolean = true

        override fun notify(notification: PluginNotification) {
            notifications += notification
        }

        override fun updateProgress(id: String, progress: Int?, title: String?) = Unit

        override fun cancel(id: String) = Unit
    }

    override fun logs(): LogService = object : LogService {
        override fun log(level: PluginLogLevel, message: String, error: Throwable?) {
            logMessages += message
        }
    }

    override fun linux(): LinuxService = unavailable()

    override fun screen(): ScreenService = unavailable()

    override fun network(): NetworkService = unavailable()

    override fun files(): FileService = unavailable()

    override fun sessions(): SessionService = unavailable()

    override fun jobs(): JobService = unavailable()

    override fun timers(): TimerService = unavailable()

    override fun goals(): GoalService = unavailable()

    override fun miniapp(): MiniappService = unavailable()

    override fun mcp(): McpService = unavailable()

    override fun ui(): UiService = unavailable()

    override fun app(): AppInfoService = unavailable()

    override fun bus(): PluginBus = unavailable()

    override fun chat(): ChatService = unavailable()

    override fun requestContributionsRefresh() = Unit

    fun close() {
        scope.cancel()
    }

    private fun <T> unavailable(): T = throw PluginServiceUnavailable("test")
}
