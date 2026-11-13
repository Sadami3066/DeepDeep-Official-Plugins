package io.github.whywood.deepdeep.plugin.mail

import io.github.whywood.deepdeep.plugin.api.BackgroundTaskEvent
import io.github.whywood.deepdeep.plugin.api.NotificationActionEvent
import io.github.whywood.deepdeep.plugin.api.PluginBackgroundKind
import io.github.whywood.deepdeep.plugin.mail.tools.MAIL_OPEN_ACTION_ID
import io.github.whywood.deepdeep.plugin.mail.tools.MAIL_PAGE_ID
import io.github.whywood.deepdeep.plugin.mail.tools.MAIL_SYNC_TASK_ID
import io.github.whywood.deepdeep.plugin.mail.tools.mailHooks
import io.github.whywood.deepdeep.plugin.mail.tools.mailNotificationPayload
import io.github.whywood.deepdeep.plugin.mail.tools.mailPageArguments
import io.github.whywood.deepdeep.plugin.mail.transport.ImapClient
import io.github.whywood.deepdeep.plugin.mail.transport.ImapCredentials
import io.github.whywood.deepdeep.plugin.mail.transport.ImapEndpoint
import io.github.whywood.deepdeep.plugin.mail.transport.MailTransport
import io.github.whywood.deepdeep.plugin.mail.transport.SmtpClient
import io.github.whywood.deepdeep.plugin.mail.transport.SmtpCredentials
import io.github.whywood.deepdeep.plugin.mail.transport.SmtpEndpoint
import io.github.whywood.deepdeep.plugin.mail.transport.SmtpSecurity
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MailHooksTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun arguments(threadId: String?, accountId: String? = null, messageId: String? = null): JsonObject =
        buildJsonObject {
            if (threadId != null) put("threadId", threadId)
            if (accountId != null) put("accountId", accountId)
            if (messageId != null) put("messageId", messageId)
        }

    @Test
    fun notificationPayloadRoundTripsToPageArguments() {
        val payload = mailNotificationPayload("thread-1", "account-1", "message-1")
        val parsed = mailPageArguments(payload)
        assertEquals("thread-1", parsed?.get("threadId")?.jsonPrimitive?.contentOrNull)
        assertEquals("account-1", parsed?.get("accountId")?.jsonPrimitive?.contentOrNull)
        assertEquals("message-1", parsed?.get("messageId")?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun pageArgumentsRequireThreadId() {
        assertNull(mailPageArguments(arguments(null, accountId = "acc")))
        assertNull(mailPageArguments(arguments("")))
        assertNull(mailPageArguments(JsonObject(emptyMap())))
        assertEquals("t", mailPageArguments(arguments("t"))?.get("threadId")?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun notificationActionOpensMailPageWithArguments() = runBlocking {
        val host = FakePluginHost(temporaryFolder.newFolder("host"))
        val runtime = MailRuntime(host, PlainTextMailTransport)
        try {
            val hooks = mailHooks(host, runtime)
            hooks.notificationAction(FakeNotificationActionEvent("mail-unread-a", MAIL_OPEN_ACTION_ID, mailNotificationPayload("thread-9", "acc-9", "msg-9")))
            val opened = host.uiPages.single()
            assertEquals(MAIL_PAGE_ID, opened.pageId)
            assertEquals(false, opened.newTask)
            assertEquals("thread-9", opened.arguments["threadId"]?.jsonPrimitive?.contentOrNull)
            assertEquals("acc-9", opened.arguments["accountId"]?.jsonPrimitive?.contentOrNull)

            host.uiPages.clear()
            hooks.notificationAction(FakeNotificationActionEvent("mail-unread-a", "other-action", mailNotificationPayload("t", "a", "m")))
            hooks.notificationAction(FakeNotificationActionEvent("mail-unread-a", MAIL_OPEN_ACTION_ID, arguments(null, accountId = "a")))
            assertTrue(host.uiPages.isEmpty())
        } finally {
            runBlocking { runtime.stop() }
            host.close()
        }
    }

    @Test
    fun backgroundHookIgnoresUnknownTasks() = runBlocking {
        val host = FakePluginHost(temporaryFolder.newFolder("host-bg"))
        val runtime = MailRuntime(host, PlainTextMailTransport)
        try {
            val hooks = mailHooks(host, runtime)
            hooks.backgroundTask(FakeBackgroundTaskEvent("other", PluginBackgroundKind.PERIODIC, 1L))
            hooks.backgroundTask(FakeBackgroundTaskEvent(MAIL_SYNC_TASK_ID, PluginBackgroundKind.PERIODIC, 1L))
            assertTrue(!host.logMessages.any { it.contains("failed") })
        } finally {
            runBlocking { runtime.stop() }
            host.close()
        }
    }

    private class FakeBackgroundTaskEvent(
        override val taskId: String,
        override val kind: PluginBackgroundKind,
        override val firedAtMs: Long,
    ) : BackgroundTaskEvent

    private class FakeNotificationActionEvent(
        override val notificationId: String,
        override val actionId: String,
        override val payload: JsonObject,
    ) : NotificationActionEvent

    private object PlainTextMailTransport : MailTransport {
        override fun imap(host: String, port: Int, credentials: ImapCredentials): ImapClient =
            ImapClient(ImapEndpoint(host, port, tls = false), credentials)

        override fun smtp(host: String, port: Int, credentials: SmtpCredentials): SmtpClient =
            SmtpClient(SmtpEndpoint(host, port, SmtpSecurity.PLAIN), credentials)
    }
}
