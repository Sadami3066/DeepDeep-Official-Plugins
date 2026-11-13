package io.github.whywood.deepdeep.plugin.mail

import io.github.whywood.deepdeep.plugin.api.BackgroundTaskEvent
import io.github.whywood.deepdeep.plugin.api.NotificationActionEvent
import io.github.whywood.deepdeep.plugin.api.PluginBackgroundKind
import io.github.whywood.deepdeep.plugin.api.PluginRunSignal
import io.github.whywood.deepdeep.plugin.api.PluginToolCall
import io.github.whywood.deepdeep.plugin.api.PluginToolResult
import io.github.whywood.deepdeep.plugin.mail.mime.MailMimeParser
import io.github.whywood.deepdeep.plugin.mail.store.MailAccountIndex
import io.github.whywood.deepdeep.plugin.mail.store.MailFolderIndex
import io.github.whywood.deepdeep.plugin.mail.store.MailServerSettings
import io.github.whywood.deepdeep.plugin.mail.store.MailStore
import io.github.whywood.deepdeep.plugin.mail.transport.ImapClient
import io.github.whywood.deepdeep.plugin.mail.transport.ImapCredentials
import io.github.whywood.deepdeep.plugin.mail.transport.ImapEndpoint
import io.github.whywood.deepdeep.plugin.mail.transport.MailLocalizedException
import io.github.whywood.deepdeep.plugin.mail.transport.MailTransport
import io.github.whywood.deepdeep.plugin.mail.transport.SmtpClient
import io.github.whywood.deepdeep.plugin.mail.transport.SmtpCredentials
import io.github.whywood.deepdeep.plugin.mail.transport.SmtpEndpoint
import io.github.whywood.deepdeep.plugin.mail.tools.MAIL_OPEN_ACTION_ID
import io.github.whywood.deepdeep.plugin.mail.tools.MAIL_SYNC_TASK_ID
import io.github.whywood.deepdeep.plugin.mail.tools.mailHooks
import io.github.whywood.deepdeep.plugin.mail.tools.mailNotificationPayload
import io.github.whywood.deepdeep.plugin.mail.tools.mailTools
import io.github.whywood.deepdeep.plugin.mail.transport.SmtpSecurity
import java.io.File
import java.io.RandomAccessFile
import java.net.ServerSocket
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MailRuntimeIntegrationTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun addAccountConnectsSyncsAndPublishesState() = runBlocking {
        val inbox = FakeFolder(
            "INBOX",
            messages = mutableListOf(
                fixtureMessage(1, "first", "hello one"),
                fixtureMessage(2, "second", "hello two", unread = false),
            ),
        )
        val sent = FakeFolder("已发送", flags = setOf("\\Sent"))
        val archive = FakeFolder("Archive", flags = setOf("\\Archive"))
        val rig = createRig(listOf(inbox, sent, archive))
        try {
            val accountId = rig.connectAccount(expectedMessages = 2)
            val state = rig.runtime.state.value
            assertEquals(accountId, rig.runtime.accounts().single().id)
            assertEquals("user@example.com", rig.runtime.accounts().single().email)
            assertEquals(1, state.unreadByAccount[accountId])
            assertTrue(accountId in state.archiveAccountIds)
            assertFalse(accountId in state.trashAccountIds)
            assertNull(state.accountErrors[accountId])
            assertNotNull(state.lastSyncByAccount[accountId])
            assertTrue(state.messages.all { it.preview.isNotEmpty() })
            assertTrue(state.messages.any { it.id == "$accountId:inbox:1" })
            assertEquals("secret", rig.host.secretValues["mail-$accountId"])
            assertTrue(File(rig.mailDir, "accounts.json").isFile)
            assertTrue(File(rig.mailDir, "servers.json").isFile)
            assertTrue(File(rig.mailDir, "index-$accountId.json").isFile)
            assertTrue(File(File(rig.mailDir, "bodies"), accountId).listFiles().orEmpty().isNotEmpty())
            rig.mailDir.walkTopDown().filter { it.isFile }.forEach { file ->
                assertFalse("secret must not be stored in ${file.name}", file.readText().contains("secret"))
            }
        } finally {
            closeRig(rig)
        }
    }

    @Test
    fun readsMessageFetchesAttachmentAndMarksSeen() = runBlocking {
        val inbox = FakeFolder(
            "INBOX",
            messages = mutableListOf(
                fixtureMessage(1, "first", "hello one", withAttachment = true),
                fixtureMessage(2, "second", "hello two", unread = false),
            ),
        )
        val rig = createRig(listOf(inbox))
        try {
            val accountId = rig.connectAccount(expectedMessages = 2)
            File(File(rig.mailDir, "bodies"), accountId).listFiles()?.forEach { it.delete() }
            val summary = rig.runtime.state.value.messages.first { it.uid == 1L }
            val detail = rig.runtime.message(accountId, summary.id)
            assertNotNull(detail)
            assertEquals("hello one", detail!!.textBody.trim())
            assertEquals(1, detail.attachments.size)
            assertEquals("report.pdf", detail.attachments.single().name)
            val path = rig.runtime.loadAttachment(accountId, summary.id, detail.attachments.single().id)
            assertNotNull(path)
            assertEquals("%PDF-1.4\n", File(path!!).readText())
            assertEquals("second", rig.runtime.message(accountId, "2")?.summary?.subject)

            rig.runtime.mark(accountId, summary.id, unread = false)
            assertFalse(rig.runtime.state.value.messages.first { it.uid == 1L }.unread)
            assertTrue(inbox.messages.first { it.uid == 1L }.flags.contains("\\Seen"))
            assertEquals(0, rig.runtime.state.value.unreadByAccount[accountId])

            assertEquals(
                setOf(1L, 2L),
                rig.runtime.search("hello", accountId).map { it.uid }.toSet(),
            )
            assertTrue(rig.runtime.search("nothing-matches", null).isEmpty())
            assertTrue(rig.runtime.search("", accountId).size == 2)
        } finally {
            closeRig(rig)
        }
    }

    @Test
    fun archiveAndTrashMoveMessagesAndUpdateState() = runBlocking {
        val inbox = FakeFolder(
            "INBOX",
            messages = mutableListOf(fixtureMessage(1, "first", "one"), fixtureMessage(2, "second", "two")),
        )
        val archive = FakeFolder("Archive", flags = setOf("\\Archive"))
        val trash = FakeFolder("Trash", flags = setOf("\\Trash"))
        val rig = createRig(listOf(inbox, archive, trash))
        try {
            val accountId = rig.connectAccount(expectedMessages = 2)
            val first = rig.runtime.state.value.messages.first { it.uid == 1L }
            rig.runtime.archive(accountId, first.id)
            assertTrue(inbox.messages.none { it.uid == 1L })
            assertEquals(1, archive.messages.size)
            assertTrue(rig.runtime.state.value.messages.none { it.id == first.id })

            val second = rig.runtime.state.value.messages.first { it.uid == 2L }
            rig.runtime.trash(accountId, second.id)
            assertTrue(inbox.messages.isEmpty())
            assertEquals(1, trash.messages.size)
            assertTrue(rig.runtime.state.value.messages.isEmpty())
        } finally {
            closeRig(rig)
        }
    }

    @Test
    fun deleteForeverWorksAndMissingFoldersProduceReadableErrors() = runBlocking {
        val inbox = FakeFolder("INBOX", messages = mutableListOf(fixtureMessage(1, "first", "one")))
        val rig = createRig(listOf(inbox))
        try {
            val accountId = rig.connectAccount(expectedMessages = 1)
            val summary = rig.runtime.state.value.messages.single()
            val archiveError = runCatching { runBlocking { rig.runtime.archive(accountId, summary.id) } }.exceptionOrNull()
            assertTrue(archiveError is MailLocalizedException)
            assertTrue(archiveError!!.message.orEmpty().contains("归档"))
            assertFalse(archiveError.message.orEmpty().contains("Exception"))
            val trashError = runCatching { runBlocking { rig.runtime.trash(accountId, summary.id) } }.exceptionOrNull()
            assertTrue(trashError is MailLocalizedException)
            assertTrue(trashError!!.message.orEmpty().contains("回收站"))

            rig.runtime.deleteForever(accountId, summary.id)
            assertTrue(inbox.messages.isEmpty())
            assertTrue(rig.runtime.state.value.messages.isEmpty())
            assertNull(MailStore(rig.mailDir).readBody(accountId, "inbox-1"))
        } finally {
            closeRig(rig)
        }
    }

    @Test
    fun sendDraftSendsThroughSmtpAndAppendsToSent() = runBlocking {
        val inbox = FakeFolder("INBOX", messages = mutableListOf(fixtureMessage(1, "first", "one")))
        val sent = FakeFolder("已发送", flags = setOf("\\Sent"))
        val rig = createRig(listOf(inbox, sent))
        try {
            val accountId = rig.connectAccount(expectedMessages = 1)
            val draft = rig.runtime.saveDraft(
                MailDraft(
                    id = "",
                    accountId = accountId,
                    to = listOf("friend@example.com"),
                    subject = "测试主题",
                    body = "正文内容",
                    createdAt = 0,
                    updatedAt = 0,
                ),
            )
            assertTrue(draft.id.isNotBlank())
            assertEquals(1, rig.runtime.state.value.drafts.size)

            val result = rig.runtime.sendDraft(draft.id)
            assertTrue(result.error, result.ok)
            assertEquals(1, rig.smtp.messages.size)
            val parsed = MailMimeParser.parse(rig.smtp.messages.single())
            assertEquals("测试主题", parsed.headers.subject)
            assertEquals(listOf("friend@example.com"), parsed.headers.to.map { it.address })
            assertEquals("正文内容", parsed.textBody)
            assertEquals(1, sent.messages.size)
            assertTrue(rig.runtime.state.value.drafts.isEmpty())
        } finally {
            closeRig(rig)
        }
    }

    @Test
    fun sendDraftWithAttachmentSendsStoresAndCleansUp() = runBlocking {
        val inbox = FakeFolder("INBOX", messages = mutableListOf(fixtureMessage(1, "first", "one")))
        val sent = FakeFolder("已发送", flags = setOf("\\Sent"))
        val rig = createRig(listOf(inbox, sent))
        try {
            val accountId = rig.connectAccount(expectedMessages = 1)
            val source = File(temporaryFolder.newFolder("attach-source"), "report.txt")
            source.writeText("attachment payload")
            val attachment = rig.runtime.importDraftAttachment("draft-att-1", source.absolutePath, "报告.txt", "text/plain")
            assertEquals("报告.txt", attachment.name)
            assertTrue(File(attachment.path).isFile)
            val draft = rig.runtime.saveDraft(
                MailDraft(
                    id = "draft-att-1",
                    accountId = accountId,
                    to = listOf("friend@example.com"),
                    subject = "附件测试",
                    body = "正文内容",
                    attachments = listOf(attachment),
                    createdAt = 0,
                    updatedAt = 0,
                ),
            )
            val result = rig.runtime.sendDraft(draft.id)
            assertTrue(result.error, result.ok)
            assertEquals(1, rig.smtp.messages.size)
            val parsed = MailMimeParser.parse(rig.smtp.messages.single(), retainAttachments = true)
            assertEquals("附件测试", parsed.headers.subject)
            assertEquals("正文内容", parsed.textBody)
            assertEquals("报告.txt", parsed.attachments.single().name)
            assertEquals("attachment payload", parsed.attachments.single().data?.toString(Charsets.UTF_8))
            assertEquals(1, sent.messages.size)
            assertTrue(sent.messages.single().raw.contentEquals(rig.smtp.messages.single()))
            assertTrue(rig.runtime.state.value.drafts.isEmpty())
            assertFalse(File(attachment.path).exists())
            assertTrue(File(rig.mailDir, "drafts").listFiles().orEmpty().isEmpty())
            assertTrue(File(rig.mailDir, "tmp").listFiles().orEmpty().isEmpty())
        } finally {
            closeRig(rig)
        }
    }

    @Test
    fun sendDraftMissingAttachmentKeepsDraftWithReadableError() = runBlocking {
        val inbox = FakeFolder("INBOX", messages = mutableListOf(fixtureMessage(1, "first", "one")))
        val rig = createRig(listOf(inbox))
        try {
            val accountId = rig.connectAccount(expectedMessages = 1)
            val source = File(temporaryFolder.newFolder("missing-source"), "report.txt")
            source.writeText("payload")
            val attachment = rig.runtime.importDraftAttachment("draft-missing-1", source.absolutePath, "report.txt", "text/plain")
            val draft = rig.runtime.saveDraft(
                MailDraft(
                    id = "draft-missing-1",
                    accountId = accountId,
                    to = listOf("friend@example.com"),
                    subject = "missing attachment",
                    body = "body",
                    attachments = listOf(attachment),
                    createdAt = 0,
                    updatedAt = 0,
                ),
            )
            File(attachment.path).delete()
            val result = rig.runtime.sendDraft(draft.id)
            assertFalse(result.ok)
            assertTrue(result.error.orEmpty().contains("附件"))
            assertFalse(result.error.orEmpty().contains("Exception"))
            assertTrue(rig.smtp.messages.isEmpty())
            assertEquals(1, rig.runtime.state.value.drafts.size)
        } finally {
            closeRig(rig)
        }
    }

    @Test
    fun importDraftAttachmentRejectsOversizedFileWithReadableError() = runBlocking {
        val host = FakePluginHost(temporaryFolder.newFolder("import-limit"))
        val runtime = MailRuntime(host, PlainTextMailTransport)
        try {
            val source = File(temporaryFolder.newFolder("big-source"), "big.bin")
            RandomAccessFile(source, "rw").use { it.setLength(20L * 1024 * 1024 + 1) }
            val error = runCatching {
                runtime.importDraftAttachment("draft-big", source.absolutePath, "big.bin", "application/octet-stream")
            }.exceptionOrNull()
            assertTrue(error is MailLocalizedException)
            assertTrue(error?.message.orEmpty().contains("20 MB"))
            assertFalse(error?.message.orEmpty().contains("Exception"))
        } finally {
            runBlocking { runtime.stop() }
            host.close()
        }
    }

    @Test
    fun importDraftAttachmentMissingSourceIsReadable() = runBlocking {
        val host = FakePluginHost(temporaryFolder.newFolder("import-missing"))
        val runtime = MailRuntime(host, PlainTextMailTransport)
        try {
            val error = runCatching {
                runtime.importDraftAttachment("draft-1", "/definitely/missing.bin", "x.bin", "application/octet-stream")
            }.exceptionOrNull()
            assertTrue(error is MailLocalizedException)
            assertTrue(error?.message.orEmpty().contains("无法读取"))
        } finally {
            runBlocking { runtime.stop() }
            host.close()
        }
    }

    @Test
    fun removeDraftAttachmentDeletesCopyAndUpdatesDraft() = runBlocking {
        val host = FakePluginHost(temporaryFolder.newFolder("remove-attachment"))
        val runtime = MailRuntime(host, PlainTextMailTransport)
        try {
            val source = File(temporaryFolder.newFolder("remove-source"), "file.txt")
            source.writeText("payload")
            val attachment = runtime.importDraftAttachment("draft-2", source.absolutePath, "file.txt", "text/plain")
            runtime.saveDraft(
                MailDraft(
                    id = "draft-2",
                    accountId = "acc",
                    to = listOf("friend@example.com"),
                    subject = "remove",
                    body = "body",
                    attachments = listOf(attachment),
                    createdAt = 0,
                    updatedAt = 0,
                ),
            )
            assertEquals(1, runtime.state.value.drafts.single().attachments.size)
            runtime.removeDraftAttachment("draft-2", attachment)
            assertFalse(File(attachment.path).exists())
            assertTrue(runtime.state.value.drafts.single().attachments.isEmpty())
        } finally {
            runBlocking { runtime.stop() }
            host.close()
        }
    }

    @Test
    fun sendDraftKeepsDraftWhenSmtpIsUnavailable() = runBlocking {
        val inbox = FakeFolder("INBOX", messages = mutableListOf(fixtureMessage(1, "first", "one")))
        val rig = createRig(listOf(inbox))
        try {
            val accountId = rig.connectAccount(expectedMessages = 1)
            val draft = rig.runtime.saveDraft(
                MailDraft(
                    id = "draft-1",
                    accountId = accountId,
                    to = listOf("friend@example.com"),
                    subject = "subject",
                    body = "body",
                    createdAt = 1,
                    updatedAt = 1,
                ),
            )
            rig.smtp.close()
            val result = rig.runtime.sendDraft(draft.id)
            assertFalse(result.ok)
            assertNotNull(result.error)
            assertFalse(result.error.orEmpty().contains("Exception"))
            assertTrue(result.error.orEmpty().isNotBlank())
            assertEquals(1, rig.runtime.state.value.drafts.size)
        } finally {
            closeRig(rig)
        }
    }

    @Test
    fun reauthorizeWithWrongPasswordReturnsLocalizedErrors() = runBlocking {
        val inbox = FakeFolder("INBOX", messages = mutableListOf(fixtureMessage(1, "first", "one")))
        val rig = createRig(listOf(inbox))
        try {
            val accountId = rig.connectAccount(expectedMessages = 1)
            val result = rig.runtime.reauthorize(accountId, "wrong")
            assertFalse(result.ok)
            assertFalse(result.imapOk)
            assertFalse(result.smtpOk)
            assertTrue(result.imapError.orEmpty().contains("登录失败"))
            assertTrue(result.smtpError.orEmpty().contains("登录失败"))
            assertEquals("secret", rig.host.secretValues["mail-$accountId"])
        } finally {
            closeRig(rig)
        }
    }

    @Test
    fun notifiesNewUnreadMailWithThrottle() = runBlocking {
        val inbox = FakeFolder("INBOX", messages = mutableListOf(fixtureMessage(1, "first", "one")))
        val rig = createRig(listOf(inbox))
        try {
            val accountId = rig.connectAccount(expectedMessages = 1)
            assertTrue(rig.host.notifications.isEmpty())

            inbox.messages += fixtureMessage(2, "new mail", "new content")
            assertTrue(rig.runtime.reauthorize(accountId, "secret").ok)
            awaitUntil { rig.runtime.state.value.messages.any { it.uid == 2L } }
            awaitUntil { rig.host.notifications.isNotEmpty() }
            val notification = rig.host.notifications.single()
            assertEquals("mail-unread-$accountId", notification.id)
            assertTrue(notification.body.contains("新邮件"))
            assertEquals(2, rig.runtime.state.value.unreadByAccount[accountId])
            assertEquals(MAIL_OPEN_ACTION_ID, notification.actions.single().id)
            assertTrue(notification.actions.single().label.contains("打开邮件"))
            assertTrue(notification.payload["threadId"]?.jsonPrimitive?.contentOrNull.orEmpty().isNotBlank())
            assertEquals(accountId, notification.payload["accountId"]?.jsonPrimitive?.contentOrNull)

            inbox.messages += fixtureMessage(3, "another mail", "more content")
            assertTrue(rig.runtime.reauthorize(accountId, "secret").ok)
            awaitUntil { rig.runtime.state.value.messages.any { it.uid == 3L } }
            Thread.sleep(200)
            assertEquals(1, rig.host.notifications.size)
        } finally {
            closeRig(rig)
        }
    }

    @Test
    fun uidValidityChangeTriggersSilentRebuild() = runBlocking {
        val inbox = FakeFolder(
            "INBOX",
            messages = mutableListOf(fixtureMessage(1, "first", "one"), fixtureMessage(2, "second", "two")),
        )
        val rig = createRig(listOf(inbox))
        try {
            val accountId = rig.connectAccount(expectedMessages = 2)
            assertTrue(File(File(rig.mailDir, "bodies"), accountId).listFiles().orEmpty().isNotEmpty())
            rig.mailDir.walkTopDown().filter { it.isFile }.forEach { file ->
                assertFalse("secret must not be stored in ${file.name}", file.readText().contains("secret"))
            }

            inbox.messages.clear()
            inbox.messages += fixtureMessage(10, "rebuilt", "fresh")
            inbox.uidValidity = 200
            assertTrue(rig.runtime.reauthorize(accountId, "secret").ok)
            awaitUntil { rig.runtime.state.value.messages.size == 1 && rig.runtime.state.value.messages.single().uid == 10L }
            assertTrue(rig.host.notifications.isEmpty())
            val bodyNames = File(File(rig.mailDir, "bodies"), accountId).listFiles().orEmpty().map { it.name }
            assertEquals(listOf("inbox-10.eml"), bodyNames)
            val index = MailStore(rig.mailDir).index(accountId)
            assertEquals(200L, index.folders.first { it.role == MailFolderRole.INBOX }.uidValidity)
        } finally {
            closeRig(rig)
        }
    }

    @Test
    fun streamsLargeBodyToDiskAndParsesFromFile() = runBlocking {
        val body = ("line ".repeat(200) + "\n").repeat(2000)
        val inbox = FakeFolder("INBOX", messages = mutableListOf(fixtureMessage(1, "big", body)))
        val rig = createRig(listOf(inbox))
        try {
            val accountId = rig.connectAccount(expectedMessages = 1)
            val bodyFile = File(File(rig.mailDir, "bodies"), "$accountId/inbox-1.eml")
            assertTrue(bodyFile.isFile)
            assertTrue(bodyFile.length() > 1_500_000)
            bodyFile.delete()
            val detail = rig.runtime.message(accountId, rig.runtime.state.value.messages.single().id)
            assertNotNull(detail)
            assertTrue(detail!!.textBody.length <= 1024 * 1024)
            assertTrue(detail.textBody.isNotEmpty())
            assertTrue(bodyFile.isFile)
            assertFalse(File(bodyFile.parentFile, bodyFile.name + ".part").exists())
        } finally {
            closeRig(rig)
        }
    }

    @Test
    fun oversizedMessageProducesReadableError() = runBlocking {
        val dataDir = temporaryFolder.newFolder("oversize")
        val host = FakePluginHost(dataDir)
        val runtime = MailRuntime(host, PlainTextMailTransport)
        val accountId = "over0001"
        val closedPort = ServerSocket(0).use { it.localPort }
        val mailDir = File(dataDir, "mail")
        val store = MailStore(mailDir)
        val summary = MailMessageSummary(
            id = "$accountId:inbox:1",
            accountId = accountId,
            folderRole = MailFolderRole.INBOX,
            uid = 1,
            threadId = "1@example.com",
            subject = "huge",
            fromName = "Sender",
            fromAddress = "sender@example.com",
            to = listOf("user@example.com"),
            dateMillis = 1,
            preview = "",
            unread = false,
            starred = false,
            hasAttachments = true,
            sizeBytes = 200L * 1024 * 1024,
        )
        store.saveAccounts(listOf(MailAccount(accountId, "custom", "user@example.com", "User", 1, true, 1)))
        store.saveServers(listOf(MailServerSettings(accountId, "127.0.0.1", closedPort, "127.0.0.1", closedPort)))
        store.saveIndex(
            MailAccountIndex(
                accountId = accountId,
                folders = listOf(MailFolderIndex(MailFolderRole.INBOX, "INBOX", 100, 1)),
                messages = listOf(summary),
            ),
        )
        host.secretValues["mail-$accountId"] = "secret"
        runtime.start()
        try {
            val error = runCatching { runBlocking { runtime.message(accountId, summary.id) } }.exceptionOrNull()
            assertTrue(error is MailLocalizedException)
            assertTrue(error?.message.orEmpty().contains("太大"))
            assertFalse(error?.message.orEmpty().contains("Exception"))
        } finally {
            runBlocking { runtime.stop() }
            host.close()
        }
    }

    @Test
    fun prefetchSkipsMessagesAboveSizeBudget() = runBlocking {
        val hugeBody = "x".repeat(5 * 1024 * 1024)
        val inbox = FakeFolder(
            "INBOX",
            messages = mutableListOf(
                fixtureMessage(1, "small", "small preview"),
                fixtureMessage(2, "huge", hugeBody),
            ),
        )
        val rig = createRig(listOf(inbox))
        try {
            val accountId = rig.connectAccount(expectedMessages = 2)
            val state = rig.runtime.state.value
            val small = state.messages.first { it.uid == 1L }
            val huge = state.messages.first { it.uid == 2L }
            assertTrue(small.preview.isNotEmpty())
            assertTrue(huge.preview.isEmpty())
            assertTrue(huge.sizeBytes > 4L * 1024 * 1024)
            val bodyDirectory = File(File(rig.mailDir, "bodies"), accountId)
            assertTrue(File(bodyDirectory, "inbox-1.eml").isFile)
            assertFalse(File(bodyDirectory, "inbox-2.eml").exists())
        } finally {
            closeRig(rig)
        }
    }

    @Test
    fun throwableErrorsNeverEscapeRuntimeEntryPoints() = runBlocking {
        val dataDir = temporaryFolder.newFolder("throwable")
        val host = FakePluginHost(dataDir)
        val runtime = MailRuntime(host, ExplodingMailTransport)
        val accountId = "boom0001"
        val closedPort = ServerSocket(0).use { it.localPort }
        val store = MailStore(File(dataDir, "mail"))
        val summary = MailMessageSummary(
            id = "$accountId:inbox:1",
            accountId = accountId,
            folderRole = MailFolderRole.INBOX,
            uid = 1,
            threadId = "1@example.com",
            subject = "cached",
            fromName = "Sender",
            fromAddress = "sender@example.com",
            to = listOf("user@example.com"),
            dateMillis = 1,
            preview = "cached",
            unread = false,
            starred = false,
            hasAttachments = false,
        )
        store.saveAccounts(listOf(MailAccount(accountId, "custom", "user@example.com", "User", 1, true, 1)))
        store.saveServers(listOf(MailServerSettings(accountId, "127.0.0.1", closedPort, "127.0.0.1", closedPort)))
        store.saveIndex(
            MailAccountIndex(
                accountId = accountId,
                folders = listOf(MailFolderIndex(MailFolderRole.INBOX, "INBOX", 100, 1)),
                messages = listOf(summary),
            ),
        )
        host.secretValues["mail-$accountId"] = "secret"
        runtime.start()
        try {
            awaitUntil { runtime.state.value.accountErrors.containsKey(accountId) }
            val error = runtime.state.value.accountErrors.getValue(accountId)
            assertTrue(error.isNotBlank())
            assertFalse(error.contains("OutOfMemoryError"))
            assertFalse(error.contains("StackOverflowError"))
            assertEquals(1, runtime.state.value.messages.size)
            assertFalse(runtime.state.value.syncing)

            val test = runtime.testConnection(
                MailAccountInput(
                    providerId = "custom",
                    email = "user@example.com",
                    password = "secret",
                    imapHost = "127.0.0.1",
                    imapPort = closedPort,
                    smtpHost = "127.0.0.1",
                    smtpPort = closedPort,
                ),
            )
            assertFalse(test.ok)
            assertTrue(test.imapError.orEmpty().isNotBlank())

            val message = runtime.message(accountId, summary.id)
            assertNull(message)

            val markError = runCatching { runtime.mark(accountId, summary.id, unread = true) }.exceptionOrNull()
            assertTrue(markError is MailLocalizedException)
            assertFalse(markError?.message.orEmpty().contains("OutOfMemoryError"))
            assertFalse(runtime.state.value.messages.single().unread)
        } finally {
            runBlocking { runtime.stop() }
            host.close()
        }
    }

    @Test
    fun refreshIsDebouncedRightAfterSync() = runBlocking {
        val inbox = FakeFolder("INBOX", messages = mutableListOf(fixtureMessage(1, "first", "one")))
        val rig = createRig(listOf(inbox))
        try {
            rig.connectAccount(expectedMessages = 1)
            val commandsBefore = rig.imap.commands.size
            rig.runtime.refresh()
            rig.runtime.refresh()
            Thread.sleep(150)
            assertEquals(commandsBefore, rig.imap.commands.size)
        } finally {
            closeRig(rig)
        }
    }

    @Test
    fun syncFailureKeepsCachedMessagesAndReportsReadableError() = runBlocking {
        val dataDir = temporaryFolder.newFolder("seed")
        val host = FakePluginHost(dataDir)
        val runtime = MailRuntime(host, PlainTextMailTransport)
        val accountId = "seed0001"
        val closedPort = ServerSocket(0).use { it.localPort }
        val mailDir = File(dataDir, "mail")
        val store = MailStore(mailDir)
        val summary = MailMessageSummary(
            id = "$accountId:inbox:1",
            accountId = accountId,
            folderRole = MailFolderRole.INBOX,
            uid = 1,
            threadId = "1@example.com",
            subject = "cached",
            fromName = "Sender",
            fromAddress = "sender@example.com",
            to = listOf("user@example.com"),
            dateMillis = 1,
            preview = "",
            unread = false,
            starred = false,
            hasAttachments = false,
        )
        store.saveAccounts(listOf(MailAccount(accountId, "custom", "user@example.com", "User", 1, true, 1)))
        store.saveServers(
            listOf(MailServerSettings(accountId, "127.0.0.1", closedPort, "127.0.0.1", closedPort)),
        )
        store.saveIndex(
            MailAccountIndex(
                accountId = accountId,
                folders = listOf(MailFolderIndex(MailFolderRole.INBOX, "INBOX", 100, 1)),
                messages = listOf(summary),
            ),
        )
        store.writeBody(accountId, "inbox-1", plainRaw(1, "cached", "cached body"))
        host.secretValues["mail-$accountId"] = "secret"
        runtime.start()
        try {
            awaitUntil { runtime.state.value.accountErrors.containsKey(accountId) }
            val error = runtime.state.value.accountErrors.getValue(accountId)
            assertTrue(error.isNotBlank())
            assertFalse(error.contains("Exception"))
            assertFalse(error.contains("IOException"))
            assertEquals(1, runtime.state.value.messages.size)
            assertNotNull(runtime.state.value.syncError)
            val detail = runtime.message(accountId, summary.id)
            assertNotNull(detail)
            assertEquals("cached body", detail!!.textBody.trim())

            runtime.removeAccount(accountId)
            assertTrue(runtime.accounts().isEmpty())
            assertNull(host.secretValues["mail-$accountId"])
            assertFalse(File(mailDir, "index-$accountId.json").exists())
            assertFalse(File(File(mailDir, "bodies"), accountId).exists())
        } finally {
            runBlocking { runtime.stop() }
            host.close()
        }
    }

    @Test
    fun startSchedulesBackgroundSyncAndStopCancels() = runBlocking {
        val rig = createRig(listOf(FakeFolder("INBOX")))
        try {
            rig.runtime.start()
            awaitUntil { rig.host.backgroundSchedules.isNotEmpty() }
            val schedule = rig.host.backgroundSchedules.single()
            assertEquals(MAIL_SYNC_TASK_ID, schedule.id)
            assertEquals(PluginBackgroundKind.PERIODIC, schedule.kind)
            assertEquals(15L * 60_000L, schedule.intervalMs)
            assertTrue(schedule.requiresNetwork)
            rig.runtime.stop()
            assertTrue(rig.host.backgroundCancelled.contains(MAIL_SYNC_TASK_ID))
        } finally {
            closeRig(rig)
        }
    }

    @Test
    fun settingsPersistLastSyncAcrossRuntimeInstances() = runBlocking {
        val inbox = FakeFolder("INBOX", messages = mutableListOf(fixtureMessage(1, "first", "one")))
        val rig = createRig(listOf(inbox))
        var secondHost: FakePluginHost? = null
        var secondRuntime: MailRuntime? = null
        try {
            val accountId = rig.connectAccount(expectedMessages = 1)
            val syncedAt = rig.runtime.state.value.lastSyncByAccount.getValue(accountId)
            assertEquals(syncedAt.toString(), rig.host.settingsValues["sync.lastAt.$accountId"])
            rig.runtime.stop()

            secondHost = FakePluginHost(rig.host.dataDir, settingsSnapshot = rig.host.settingsValues.toMap())
            secondRuntime = MailRuntime(secondHost, PlainTextMailTransport)
            secondRuntime.start()
            assertEquals(syncedAt, secondRuntime.state.value.lastSyncByAccount[accountId])
            assertNotNull(secondRuntime.state.value.lastSyncAt)
        } finally {
            secondRuntime?.let { runtime -> runBlocking { runtime.stop() } }
            secondHost?.close()
            closeRig(rig)
        }
    }

    @Test
    fun backgroundTaskHookTriggersIncrementalSync() = runBlocking {
        val inbox = FakeFolder("INBOX", messages = mutableListOf(fixtureMessage(1, "first", "one")))
        val rig = createRig(listOf(inbox))
        try {
            rig.connectAccount(expectedMessages = 1)
            inbox.messages += fixtureMessage(2, "new mail", "new content")
            val hooks = mailHooks(rig.host, rig.runtime)
            hooks.backgroundTask(
                FakeBackgroundTaskEvent(MAIL_SYNC_TASK_ID, PluginBackgroundKind.PERIODIC, System.currentTimeMillis()),
            )
            awaitUntil { rig.runtime.state.value.messages.any { it.uid == 2L } }
            hooks.backgroundTask(FakeBackgroundTaskEvent("other", PluginBackgroundKind.PERIODIC, 1L))
        } finally {
            closeRig(rig)
        }
    }

    @Test
    fun notificationActionOpensMailPage() = runBlocking {
        val inbox = FakeFolder("INBOX", messages = mutableListOf(fixtureMessage(1, "first", "one")))
        val rig = createRig(listOf(inbox))
        try {
            rig.connectAccount(expectedMessages = 1)
            val hooks = mailHooks(rig.host, rig.runtime)
            hooks.notificationAction(
                FakeNotificationActionEvent(
                    "mail-unread-x",
                    MAIL_OPEN_ACTION_ID,
                    mailNotificationPayload("thread-1", rig.runtime.accounts().single().id, "msg-1"),
                ),
            )
            val page = rig.host.uiPages.single()
            assertEquals("mail", page.pageId)
            assertEquals(false, page.newTask)
            assertEquals("thread-1", page.arguments["threadId"]?.jsonPrimitive?.contentOrNull)
        } finally {
            closeRig(rig)
        }
    }

    @Test
    fun toolsSaveAttachmentToDownloadsAndShareToChat() = runBlocking {
        val inbox = FakeFolder(
            "INBOX",
            messages = mutableListOf(fixtureMessage(1, "with attachment", "hello", withAttachment = true)),
        )
        val rig = createRig(listOf(inbox))
        try {
            val accountId = rig.connectAccount(expectedMessages = 1)
            val summary = rig.runtime.state.value.messages.single()
            val detail = rig.runtime.message(accountId, summary.id)
            assertNotNull(detail)
            val attachment = detail!!.attachments.single()
            val tools = mailTools(rig.host, rig.runtime).tools.associateBy { it.name }
            val readCall = FakeToolCall(
                callId = "call-read",
                args = buildJsonObject { put("message_id", summary.id) },
                sessionId = "session-1",
            )
            val readResult = tools.getValue("mail_read").execute(readCall)
            assertFalse(readResult.isError)
            assertTrue(readResult.text.orEmpty().contains(attachment.id))
            assertEquals(1, rig.host.chatAttachments.size)
            val shared = rig.host.chatAttachments.single()
            assertEquals("session-1", shared.sessionId)
            assertEquals(false, shared.triggerTurn)
            assertEquals(1, shared.attachments.size)
            assertTrue(File(shared.attachments.single().path).isFile)

            tools.getValue("mail_read").execute(readCall)
            assertEquals(1, rig.host.chatAttachments.size)

            val saveResult = tools.getValue("mail_save_attachment").execute(
                FakeToolCall(
                    callId = "call-save",
                    args = buildJsonObject {
                        put("message_id", summary.id)
                        put("attachment_id", attachment.id)
                    },
                    sessionId = "session-1",
                ),
            )
            assertFalse(saveResult.isError)
            assertEquals(1, rig.host.mediaSaved.size)
            assertEquals(attachment.name, rig.host.mediaSaved.single().name)
            assertEquals(attachment.mimeType, rig.host.mediaSaved.single().mimeType)
        } finally {
            closeRig(rig)
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

    private class FakeToolCall(
        override val callId: String,
        override val args: JsonObject,
        override val sessionId: String,
    ) : PluginToolCall {
        override val signal: PluginRunSignal? = null

        override fun partial(result: PluginToolResult) = Unit
    }

    private suspend fun Rig.connectAccount(expectedMessages: Int): String {
        runtime.start()
        val test = runtime.addAccount(input())
        assertTrue("imap=${test.imapError} smtp=${test.smtpError}", test.ok)
        awaitUntil {
            runtime.state.value.messages.size == expectedMessages && !runtime.state.value.syncing
        }
        return runtime.accounts().single().id
    }

    private fun createRig(folders: List<FakeFolder>): Rig {
        val host = FakePluginHost(temporaryFolder.newFolder())
        val imap = FakeImapServer(folders.toMutableList())
        val smtp = FakeSmtpServer()
        val runtime = MailRuntime(host, PlainTextMailTransport)
        return Rig(host, imap, smtp, runtime)
    }

    private fun closeRig(rig: Rig) {
        runBlocking { rig.runtime.stop() }
        rig.host.close()
        rig.imap.close()
        rig.smtp.close()
    }

    private fun awaitUntil(timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(20)
        }
        fail("condition was not met within ${timeoutMs}ms")
    }

    private fun fixtureMessage(
        uid: Long,
        subject: String,
        body: String,
        unread: Boolean = true,
        withAttachment: Boolean = false,
    ): FakeMessage = FakeMessage(
        uid = uid,
        flags = if (unread) mutableSetOf() else mutableSetOf("\\Seen"),
        subject = subject,
        fromName = "Sender",
        fromAddress = "sender$uid@example.com",
        toName = "User",
        toAddress = "user@example.com",
        messageId = "<$uid@example.com>",
        raw = if (withAttachment) multipartRaw(uid, subject, body) else plainRaw(uid, subject, body),
    )

    private fun plainRaw(uid: Long, subject: String, body: String): ByteArray = (
        "From: Sender <sender$uid@example.com>\r\n" +
            "To: User <user@example.com>\r\n" +
            "Subject: $subject\r\n" +
            "Date: Tue, 12 Nov 2026 10:00:00 +0800\r\n" +
            "Message-ID: <$uid@example.com>\r\n" +
            "Content-Type: text/plain; charset=UTF-8\r\n" +
            "\r\n" +
            "$body\r\n"
        ).toByteArray(Charsets.UTF_8)

    private fun multipartRaw(uid: Long, subject: String, body: String): ByteArray = (
        "From: Sender <sender$uid@example.com>\r\n" +
            "To: User <user@example.com>\r\n" +
            "Subject: $subject\r\n" +
            "Date: Tue, 12 Nov 2026 10:00:00 +0800\r\n" +
            "Message-ID: <$uid@example.com>\r\n" +
            "MIME-Version: 1.0\r\n" +
            "Content-Type: multipart/mixed; boundary=\"B\"\r\n" +
            "\r\n" +
            "--B\r\n" +
            "Content-Type: text/plain; charset=UTF-8\r\n" +
            "\r\n" +
            "$body\r\n" +
            "--B\r\n" +
            "Content-Type: application/pdf; name=\"report.pdf\"\r\n" +
            "Content-Disposition: attachment; filename=\"report.pdf\"\r\n" +
            "Content-Transfer-Encoding: base64\r\n" +
            "\r\n" +
            "JVBERi0xLjQK\r\n" +
            "--B--\r\n"
        ).toByteArray(Charsets.UTF_8)

    private class Rig(
        val host: FakePluginHost,
        val imap: FakeImapServer,
        val smtp: FakeSmtpServer,
        val runtime: MailRuntime,
    ) {
        val mailDir: File get() = File(host.dataDir, "mail")

        fun input(password: String = "secret"): MailAccountInput = MailAccountInput(
            providerId = "custom",
            email = "user@example.com",
            password = password,
            imapHost = "127.0.0.1",
            imapPort = imap.port,
            smtpHost = "127.0.0.1",
            smtpPort = smtp.port,
        )
    }

    private object ExplodingMailTransport : MailTransport {
        override fun imap(host: String, port: Int, credentials: ImapCredentials): ImapClient =
            throw OutOfMemoryError("simulated imap allocation failure")

        override fun smtp(host: String, port: Int, credentials: SmtpCredentials): SmtpClient =
            throw StackOverflowError("simulated smtp stack overflow")
    }

    private object PlainTextMailTransport : MailTransport {
        override fun imap(host: String, port: Int, credentials: ImapCredentials): ImapClient =
            ImapClient(ImapEndpoint(host, port, tls = false), credentials)

        override fun smtp(host: String, port: Int, credentials: SmtpCredentials): SmtpClient =
            SmtpClient(SmtpEndpoint(host, port, SmtpSecurity.PLAIN), credentials)
    }
}
