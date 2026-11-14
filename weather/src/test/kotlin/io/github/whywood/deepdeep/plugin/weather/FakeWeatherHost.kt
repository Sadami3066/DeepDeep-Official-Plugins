package io.github.whywood.deepdeep.plugin.weather

import android.content.Context
import io.github.whywood.deepdeep.plugin.api.AccessibilityServicePlugin
import io.github.whywood.deepdeep.plugin.api.AppInfoService
import io.github.whywood.deepdeep.plugin.api.BackgroundService
import io.github.whywood.deepdeep.plugin.api.CalendarService
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
import io.github.whywood.deepdeep.plugin.api.NotificationService
import io.github.whywood.deepdeep.plugin.api.PackageService
import io.github.whywood.deepdeep.plugin.api.PermissionService
import io.github.whywood.deepdeep.plugin.api.PluginBus
import io.github.whywood.deepdeep.plugin.api.PluginExperimentalApi
import io.github.whywood.deepdeep.plugin.api.PluginHost
import io.github.whywood.deepdeep.plugin.api.PluginManifest
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

@OptIn(PluginExperimentalApi::class)
internal class FakeWeatherHost(private val language: String = "zh") : PluginHost {
    override val manifest: PluginManifest get() = error("unused")

    override val context: Context get() = error("unused")

    override val dataDir: File get() = error("unused")

    override val assetsDir: File get() = error("unused")

    override val scope: CoroutineScope get() = error("unused")

    override fun text(source: LocalizedText, vararg args: Any): String {
        val raw = source.resolve(language) ?: source.resolve("zh").orEmpty()
        return if (args.isEmpty()) raw else String.format(Locale.ROOT, raw, *args)
    }

    override fun linux(): LinuxService = error("unused")

    override fun screen(): ScreenService = error("unused")

    override fun notifications(): NotificationService = error("unused")

    override fun secrets(): SecretService = error("unused")

    override fun network(): io.github.whywood.deepdeep.plugin.api.NetworkService = error("unused")

    override fun files(): FileService = error("unused")

    override fun sessions(): SessionService = error("unused")

    override fun jobs(): JobService = error("unused")

    override fun timers(): TimerService = error("unused")

    override fun goals(): GoalService = error("unused")

    override fun miniapp(): MiniappService = error("unused")

    override fun mcp(): McpService = error("unused")

    override fun ui(): UiService = error("unused")

    override fun app(): AppInfoService = error("unused")

    override fun bus(): PluginBus = error("unused")

    override fun logs(): LogService = error("unused")

    override fun settings(): PluginSettingsService = error("unused")

    override fun device(): DeviceService = error("unused")

    override fun clipboard(): ClipboardService = error("unused")

    override fun permissions(): PermissionService = error("unused")

    override fun packages(): PackageService = error("unused")

    override fun privilege(): PrivilegeService = error("unused")

    override fun intents(): IntentsService = error("unused")

    override fun components(): ComponentService = error("unused")

    override fun background(): BackgroundService = error("unused")

    override fun location(): LocationService = error("unused")

    override fun contacts(): ContactsService = error("unused")

    override fun sms(): SmsService = error("unused")

    override fun calendar(): CalendarService = error("unused")

    override fun media(): MediaService = error("unused")

    override fun speech(): SpeechService = error("unused")

    override fun web(): WebService = error("unused")

    override fun credentials(): CredentialService = error("unused")

    override fun accessibility(): AccessibilityServicePlugin = error("unused")

    override fun requestContributionsRefresh(): Unit = error("unused")
}
