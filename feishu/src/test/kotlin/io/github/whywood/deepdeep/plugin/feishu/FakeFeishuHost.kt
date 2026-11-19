package io.github.whywood.deepdeep.plugin.feishu

import android.content.Context
import io.github.whywood.deepdeep.plugin.api.AccessibilityServicePlugin
import io.github.whywood.deepdeep.plugin.api.AppInfoService
import io.github.whywood.deepdeep.plugin.api.BackgroundService
import io.github.whywood.deepdeep.plugin.api.CalendarService
import io.github.whywood.deepdeep.plugin.api.ChatAttachment
import io.github.whywood.deepdeep.plugin.api.ChatMessageOptions
import io.github.whywood.deepdeep.plugin.api.ChatSendResult
import io.github.whywood.deepdeep.plugin.api.ChatService
import io.github.whywood.deepdeep.plugin.api.ClipboardService
import io.github.whywood.deepdeep.plugin.api.ComponentService
import io.github.whywood.deepdeep.plugin.api.ContactsService
import io.github.whywood.deepdeep.plugin.api.CredentialService
import io.github.whywood.deepdeep.plugin.api.DeviceService
import io.github.whywood.deepdeep.plugin.api.FileService
import io.github.whywood.deepdeep.plugin.api.GoalService
import io.github.whywood.deepdeep.plugin.api.IntentsService
import io.github.whywood.deepdeep.plugin.api.JobService
import io.github.whywood.deepdeep.plugin.api.LinuxService
import io.github.whywood.deepdeep.plugin.api.LocalizedText
import io.github.whywood.deepdeep.plugin.api.LocationService
import io.github.whywood.deepdeep.plugin.api.LogService
import io.github.whywood.deepdeep.plugin.api.McpService
import io.github.whywood.deepdeep.plugin.api.MediaService
import io.github.whywood.deepdeep.plugin.api.MiniappService
import io.github.whywood.deepdeep.plugin.api.NetworkService
import io.github.whywood.deepdeep.plugin.api.NotificationService
import io.github.whywood.deepdeep.plugin.api.PackageService
import io.github.whywood.deepdeep.plugin.api.PermissionService
import io.github.whywood.deepdeep.plugin.api.PluginBus
import io.github.whywood.deepdeep.plugin.api.PluginHost
import io.github.whywood.deepdeep.plugin.api.PluginManifest
import io.github.whywood.deepdeep.plugin.api.PluginServiceUnavailable
import io.github.whywood.deepdeep.plugin.api.PluginSettingsService
import io.github.whywood.deepdeep.plugin.api.PrivilegeService
import io.github.whywood.deepdeep.plugin.api.ScreenService
import io.github.whywood.deepdeep.plugin.api.SecretService
import io.github.whywood.deepdeep.plugin.api.SessionService
import io.github.whywood.deepdeep.plugin.api.SmsService
import io.github.whywood.deepdeep.plugin.api.SpeechService
import io.github.whywood.deepdeep.plugin.api.TimerService
import io.github.whywood.deepdeep.plugin.api.UiService
import io.github.whywood.deepdeep.plugin.api.WebService
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.JsonObject

internal class FakeFeishuHost(
    private val language: String = "zh",
    private val postSucceeds: Boolean = true,
) : PluginHost {
    private val chatService = object : ChatService {
        override suspend fun postCustomMessage(
            sessionId: String,
            customType: String,
            payload: JsonObject,
            text: String,
            display: Boolean,
        ): Boolean = postSucceeds

        override suspend fun sendMessage(
            sessionId: String,
            text: String,
            options: ChatMessageOptions,
        ): ChatSendResult = throw PluginServiceUnavailable("chat.sendMessage")

        override suspend fun sendMessageWithAttachments(
            sessionId: String,
            text: String,
            attachments: List<ChatAttachment>,
            options: ChatMessageOptions,
        ): ChatSendResult = throw PluginServiceUnavailable("chat.sendMessageWithAttachments")
    }

    override val manifest: PluginManifest get() = unavailable("manifest")
    override val context: Context get() = unavailable("context")
    override val dataDir: File = File(System.getProperty("java.io.tmpdir") ?: "/tmp")
    override val assetsDir: File = dataDir
    override val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    override fun text(source: LocalizedText, vararg args: Any): String {
        val resolved = source.resolve(language).orEmpty()
        return if (args.isEmpty()) resolved else String.format(Locale.getDefault(), resolved, *args)
    }

    override fun chat(): ChatService = chatService

    override fun linux(): LinuxService = unavailable("linux")
    override fun screen(): ScreenService = unavailable("screen")
    override fun notifications(): NotificationService = unavailable("notifications")
    override fun secrets(): SecretService = unavailable("secrets")
    override fun network(): NetworkService = unavailable("network")
    override fun files(): FileService = unavailable("files")
    override fun sessions(): SessionService = unavailable("sessions")
    override fun jobs(): JobService = unavailable("jobs")
    override fun timers(): TimerService = unavailable("timers")
    override fun goals(): GoalService = unavailable("goals")
    override fun miniapp(): MiniappService = unavailable("miniapp")
    override fun mcp(): McpService = unavailable("mcp")
    override fun ui(): UiService = unavailable("ui")
    override fun app(): AppInfoService = unavailable("app")
    override fun bus(): PluginBus = unavailable("bus")
    override fun logs(): LogService = unavailable("logs")
    override fun settings(): PluginSettingsService = unavailable("settings")
    override fun device(): DeviceService = unavailable("device")
    override fun clipboard(): ClipboardService = unavailable("clipboard")
    override fun permissions(): PermissionService = unavailable("permissions")
    override fun packages(): PackageService = unavailable("packages")
    override fun privilege(): PrivilegeService = unavailable("privilege")
    override fun intents(): IntentsService = unavailable("intents")
    override fun components(): ComponentService = unavailable("components")
    override fun background(): BackgroundService = unavailable("background")
    override fun location(): LocationService = unavailable("location")
    override fun contacts(): ContactsService = unavailable("contacts")
    override fun sms(): SmsService = unavailable("sms")
    override fun calendar(): CalendarService = unavailable("calendar")
    override fun media(): MediaService = unavailable("media")
    override fun speech(): SpeechService = unavailable("speech")
    override fun web(): WebService = unavailable("web")
    override fun credentials(): CredentialService = unavailable("credentials")
    override fun accessibility(): AccessibilityServicePlugin = unavailable("accessibility")

    override fun requestContributionsRefresh() = Unit

    private fun <T> unavailable(name: String): T = throw PluginServiceUnavailable(name)
}

internal fun loadFixture(name: String): String =
    FakeFeishuHost::class.java.getResourceAsStream("/fixtures/$name")?.bufferedReader()?.use { it.readText() }
        ?: error("missing fixture $name")
