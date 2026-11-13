package io.github.whywood.deepdeep.plugin.mail

import android.content.Context
import io.github.whywood.deepdeep.plugin.api.AccessibilityServicePlugin
import io.github.whywood.deepdeep.plugin.api.AppInfoService
import io.github.whywood.deepdeep.plugin.api.BackgroundService
import io.github.whywood.deepdeep.plugin.api.CalendarService
import io.github.whywood.deepdeep.plugin.api.ChatAttachment
import io.github.whywood.deepdeep.plugin.api.ChatDelivery
import io.github.whywood.deepdeep.plugin.api.ChatMessageOptions
import io.github.whywood.deepdeep.plugin.api.ChatSendKind
import io.github.whywood.deepdeep.plugin.api.ChatSendResult
import io.github.whywood.deepdeep.plugin.api.ChatService
import io.github.whywood.deepdeep.plugin.api.ClipboardService
import io.github.whywood.deepdeep.plugin.api.ComponentService
import io.github.whywood.deepdeep.plugin.api.ContactsService
import io.github.whywood.deepdeep.plugin.api.CredentialService
import io.github.whywood.deepdeep.plugin.api.DEFAULT_LANGUAGE
import io.github.whywood.deepdeep.plugin.api.DeviceService
import io.github.whywood.deepdeep.plugin.api.FileService
import io.github.whywood.deepdeep.plugin.api.ForegroundServiceOptions
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
import io.github.whywood.deepdeep.plugin.api.PluginBackgroundKind
import io.github.whywood.deepdeep.plugin.api.PluginBackgroundTaskInfo
import io.github.whywood.deepdeep.plugin.api.PluginBus
import io.github.whywood.deepdeep.plugin.api.PluginExperimentalApi
import io.github.whywood.deepdeep.plugin.api.PluginHost
import io.github.whywood.deepdeep.plugin.api.PluginLogLevel
import io.github.whywood.deepdeep.plugin.api.PluginManifest
import io.github.whywood.deepdeep.plugin.api.PluginNotification
import io.github.whywood.deepdeep.plugin.api.PluginNotificationHandle
import io.github.whywood.deepdeep.plugin.api.PluginNotificationRecord
import io.github.whywood.deepdeep.plugin.api.PluginPageContext
import io.github.whywood.deepdeep.plugin.api.PluginPickedFile
import io.github.whywood.deepdeep.plugin.api.PluginServiceUnavailable
import io.github.whywood.deepdeep.plugin.api.PluginSettingsService
import io.github.whywood.deepdeep.plugin.api.PrivilegeService
import io.github.whywood.deepdeep.plugin.api.ScreenService
import io.github.whywood.deepdeep.plugin.api.SecretService
import io.github.whywood.deepdeep.plugin.api.SessionService
import io.github.whywood.deepdeep.plugin.api.ShareRequest
import io.github.whywood.deepdeep.plugin.api.SmsService
import io.github.whywood.deepdeep.plugin.api.SpeechService
import io.github.whywood.deepdeep.plugin.api.TimerService
import io.github.whywood.deepdeep.plugin.api.UiChooseRequest
import io.github.whywood.deepdeep.plugin.api.UiConfirmRequest
import io.github.whywood.deepdeep.plugin.api.UiDatePickRequest
import io.github.whywood.deepdeep.plugin.api.UiFilePickRequest
import io.github.whywood.deepdeep.plugin.api.UiImagePickRequest
import io.github.whywood.deepdeep.plugin.api.UiPromptRequest
import io.github.whywood.deepdeep.plugin.api.UiService
import io.github.whywood.deepdeep.plugin.api.UiTimePickRequest
import io.github.whywood.deepdeep.plugin.api.WebService
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.json.JsonObject

@OptIn(PluginExperimentalApi::class)
internal class FakePluginHost(
    dataDirectory: File,
    settingsSnapshot: Map<String, String> = emptyMap(),
) : PluginHost {
    override val manifest: PluginManifest get() = throw PluginServiceUnavailable("manifest")

    override val context: Context get() = throw PluginServiceUnavailable("context")

    override val dataDir: File = dataDirectory

    override val assetsDir: File get() = throw PluginServiceUnavailable("assets")

    override val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val secretValues = ConcurrentHashMap<String, String>()
    val settingsValues = ConcurrentHashMap(settingsSnapshot)
    val notifications = CopyOnWriteArrayList<PluginNotification>()
    val logMessages = CopyOnWriteArrayList<String>()
    val backgroundSchedules = CopyOnWriteArrayList<BackgroundSchedule>()
    val backgroundCancelled = CopyOnWriteArrayList<String>()
    val mediaSaved = CopyOnWriteArrayList<MediaSave>()
    val mediaOpened = CopyOnWriteArrayList<String>()
    val uiPages = CopyOnWriteArrayList<UiPageOpen>()
    val sharedRequests = CopyOnWriteArrayList<ShareRequest>()
    val chatAttachments = CopyOnWriteArrayList<ChatAttachmentSend>()
    val chatCustomMessages = CopyOnWriteArrayList<String>()
    private val settingsFlow = MutableStateFlow(settingsSnapshot.toMap())

    data class BackgroundSchedule(
        val id: String,
        val kind: PluginBackgroundKind,
        val intervalMs: Long?,
        val requiresNetwork: Boolean,
    )

    data class MediaSave(val path: String, val name: String?, val mimeType: String?, val result: String)

    data class UiPageOpen(val pageId: String, val arguments: JsonObject, val newTask: Boolean)

    data class ChatAttachmentSend(
        val sessionId: String,
        val text: String,
        val attachments: List<ChatAttachment>,
        val triggerTurn: Boolean,
    )

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

    override fun settings(): PluginSettingsService = object : PluginSettingsService {
        override suspend fun get(key: String): String? = settingsValues[key]

        override suspend fun put(key: String, value: String) {
            settingsValues[key] = value
            settingsFlow.value = settingsValues.toMap()
        }

        override suspend fun remove(key: String) {
            settingsValues.remove(key)
            settingsFlow.value = settingsValues.toMap()
        }

        override suspend fun clear() {
            settingsValues.clear()
            settingsFlow.value = emptyMap()
        }

        override suspend fun keys(): Set<String> = settingsValues.keys.toSet()

        override suspend fun snapshot(): Map<String, String> = settingsValues.toMap()

        override suspend fun getJson(key: String): JsonObject? = null

        override suspend fun putJson(key: String, value: JsonObject) = Unit

        override fun observe(key: String?): Flow<Map<String, String>> = settingsFlow
    }

    override fun notifications(): NotificationService = object : NotificationService {
        override val enabled: Boolean = true

        override fun notify(notification: PluginNotification) {
            notifications += notification
        }

        override fun notifyChecked(notification: PluginNotification): PluginNotificationHandle {
            notifications += notification
            return PluginNotificationHandle(notification.id, "fake:${notification.id}")
        }

        override fun updateProgress(id: String, progress: Int?, title: String?) = Unit

        override fun cancel(id: String) = Unit

        override val listenerGranted: Boolean = false

        override suspend fun recent(limit: Int): List<PluginNotificationRecord> = emptyList()

        override fun received(): Flow<PluginNotificationRecord> = emptyFlow()

        override fun removed(): Flow<String> = emptyFlow()

        override suspend fun dismiss(key: String): Boolean = false
    }

    override fun background(): BackgroundService = object : BackgroundService {
        override suspend fun schedule(
            id: String,
            kind: PluginBackgroundKind,
            triggerAtMs: Long?,
            intervalMs: Long?,
            requiresNetwork: Boolean,
            requiresCharging: Boolean,
        ): Boolean {
            backgroundSchedules += BackgroundSchedule(id, kind, intervalMs, requiresNetwork)
            return true
        }

        override suspend fun cancel(id: String): Boolean {
            backgroundCancelled += id
            return true
        }

        override suspend fun tasks(): List<PluginBackgroundTaskInfo> = emptyList()

        override fun startForeground(options: ForegroundServiceOptions): Boolean = false

        override fun updateForeground(options: ForegroundServiceOptions): Boolean = false

        override fun stopForeground(id: String): Boolean = false

        override val batteryOptimizationIgnored: Boolean = false

        override fun requestIgnoreBatteryOptimizations(): Boolean = false

        override fun openBatteryOptimizationSettings(): Boolean = false
    }

    override fun media(): MediaService = object : MediaService {
        override suspend fun saveToDownloads(fileName: String, mimeType: String, bytes: ByteArray): String? =
            "content://fake/$fileName"

        override suspend fun saveFileToDownloads(path: String, fileName: String?, mimeType: String?): String? {
            val result = "content://fake/downloads/${fileName ?: File(path).name}"
            mediaSaved += MediaSave(path, fileName, mimeType, result)
            return result
        }

        override suspend fun saveImageToGallery(fileName: String, mimeType: String, bytes: ByteArray): String? = null

        override suspend fun scanFile(path: String): Boolean = true

        override suspend fun open(path: String): Boolean {
            mediaOpened += path
            return true
        }

        override suspend fun share(path: String, mimeType: String?): Boolean = true

        override suspend fun preview(path: String): Boolean = true

        override suspend fun readImage(path: String, maxSizePx: Int): ByteArray? = null

        override suspend fun thumbnail(path: String, maxSizePx: Int): ByteArray? = null

        override suspend fun importUri(uri: String, fileName: String?): String? = null
    }

    override fun ui(): UiService = object : UiService {
        override fun openUrl(url: String) = Unit

        override fun share(request: ShareRequest) {
            sharedRequests += request
        }

        override fun shareFiles(paths: List<String>, mimeType: String?) = Unit

        override fun toast(message: String) = Unit

        override fun openPage(pageId: String) {
            uiPages += UiPageOpen(pageId, JsonObject(emptyMap()), false)
        }

        override fun openPageWithArguments(pageId: String, arguments: JsonObject, newTask: Boolean) {
            uiPages += UiPageOpen(pageId, arguments, newTask)
        }

        override fun closePage(result: JsonObject?) = Unit

        override fun pageContext(): PluginPageContext? = null

        override val isDark: Boolean = false

        override suspend fun confirm(request: UiConfirmRequest): Boolean = false

        override suspend fun prompt(request: UiPromptRequest): String? = null

        override suspend fun choose(request: UiChooseRequest): String? = null

        override suspend fun pickDate(request: UiDatePickRequest): Long? = null

        override suspend fun pickTime(request: UiTimePickRequest): Int? = null

        override suspend fun pickFiles(request: UiFilePickRequest): List<PluginPickedFile> = emptyList()

        override suspend fun pickImage(request: UiImagePickRequest): PluginPickedFile? = null

        override suspend fun scanQr(): String? = null

        override suspend fun pickDirectory(): String? = null

        override fun snackbar(message: String, actionLabel: String?, action: (() -> Unit)?) = Unit
    }

    override fun chat(): ChatService = object : ChatService {
        override suspend fun postCustomMessage(
            sessionId: String,
            customType: String,
            payload: JsonObject,
            text: String,
            display: Boolean,
        ): Boolean {
            chatCustomMessages += customType
            return true
        }

        override suspend fun sendMessage(
            sessionId: String,
            text: String,
            options: ChatMessageOptions,
        ): ChatSendResult = result(sessionId)

        override suspend fun sendMessageWithAttachments(
            sessionId: String,
            text: String,
            attachments: List<ChatAttachment>,
            options: ChatMessageOptions,
        ): ChatSendResult {
            chatAttachments += ChatAttachmentSend(sessionId, text, attachments, options.triggerTurn)
            return result(sessionId)
        }

        private fun result(sessionId: String): ChatSendResult = object : ChatSendResult {
            override val kind: ChatSendKind = ChatSendKind.DISPLAYED
            override val sessionId: String = sessionId
            override val runId: String? = null
            override val delivery: ChatDelivery? = null
        }
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

    override fun app(): AppInfoService = unavailable()

    override fun bus(): PluginBus = unavailable()

    override fun device(): DeviceService = unavailable()

    override fun clipboard(): ClipboardService = unavailable()

    override fun permissions(): PermissionService = unavailable()

    override fun packages(): PackageService = unavailable()

    override fun privilege(): PrivilegeService = unavailable()

    override fun intents(): IntentsService = unavailable()

    override fun components(): ComponentService = unavailable()

    override fun location(): LocationService = unavailable()

    override fun contacts(): ContactsService = unavailable()

    override fun sms(): SmsService = unavailable()

    override fun calendar(): CalendarService = unavailable()

    override fun speech(): SpeechService = unavailable()

    override fun web(): WebService = unavailable()

    override fun credentials(): CredentialService = unavailable()

    override fun accessibility(): AccessibilityServicePlugin = unavailable()

    override fun requestContributionsRefresh() = Unit

    fun close() {
        scope.cancel()
    }

    private fun <T> unavailable(): T = throw PluginServiceUnavailable("test")
}
