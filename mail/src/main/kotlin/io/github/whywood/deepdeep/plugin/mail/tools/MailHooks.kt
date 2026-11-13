package io.github.whywood.deepdeep.plugin.mail.tools

import io.github.whywood.deepdeep.plugin.api.BackgroundTaskEvent
import io.github.whywood.deepdeep.plugin.api.NotificationActionEvent
import io.github.whywood.deepdeep.plugin.api.PluginHost
import io.github.whywood.deepdeep.plugin.api.PluginHooks
import io.github.whywood.deepdeep.plugin.api.PluginNotificationAction
import io.github.whywood.deepdeep.plugin.api.localizedText
import io.github.whywood.deepdeep.plugin.api.notificationAction
import io.github.whywood.deepdeep.plugin.mail.MailRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

internal const val MAIL_SYNC_TASK_ID = "sync"
internal const val MAIL_OPEN_ACTION_ID = "open-mail"
internal const val MAIL_PAGE_ID = "mail"
internal const val MAIL_ARG_THREAD_ID = "threadId"
internal const val MAIL_ARG_ACCOUNT_ID = "accountId"
internal const val MAIL_ARG_MESSAGE_ID = "messageId"

internal fun mailNotificationPayload(threadId: String, accountId: String, messageId: String): JsonObject =
    buildJsonObject {
        put(MAIL_ARG_THREAD_ID, threadId)
        put(MAIL_ARG_ACCOUNT_ID, accountId)
        put(MAIL_ARG_MESSAGE_ID, messageId)
    }

internal fun mailPageArguments(payload: JsonObject): JsonObject? {
    val threadId = payload.stringOrNull(MAIL_ARG_THREAD_ID) ?: return null
    return buildJsonObject {
        put(MAIL_ARG_THREAD_ID, threadId)
        payload.stringOrNull(MAIL_ARG_ACCOUNT_ID)?.let { put(MAIL_ARG_ACCOUNT_ID, it) }
        payload.stringOrNull(MAIL_ARG_MESSAGE_ID)?.let { put(MAIL_ARG_MESSAGE_ID, it) }
    }
}

internal fun mailOpenAction(host: PluginHost): PluginNotificationAction = notificationAction(
    MAIL_OPEN_ACTION_ID,
    host.text(localizedText(default = "打开邮件", zh = "打开邮件", en = "Open mail")),
)

internal fun mailHooks(host: PluginHost, runtime: MailRuntime): PluginHooks = object : PluginHooks {
    override suspend fun backgroundTask(event: BackgroundTaskEvent) {
        if (event.taskId != MAIL_SYNC_TASK_ID) return
        host.scope.launch {
            try {
                runtime.runBackgroundSync()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                runCatching { host.logs().warn("mail background sync failed", error) }
            }
        }
    }

    override suspend fun notificationAction(event: NotificationActionEvent) {
        if (event.actionId != MAIL_OPEN_ACTION_ID) return
        val arguments = mailPageArguments(event.payload) ?: return
        runCatching { host.ui().openPageWithArguments(MAIL_PAGE_ID, arguments, newTask = false) }
    }
}

private fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
