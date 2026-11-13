package io.github.whywood.deepdeep.plugin.mail

import io.github.whywood.deepdeep.plugin.mail.store.MailAccountIndex
import io.github.whywood.deepdeep.plugin.mail.store.MailFolderIndex
import io.github.whywood.deepdeep.plugin.mail.store.MailServerSettings
import io.github.whywood.deepdeep.plugin.mail.store.MailStore
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MailStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun store(): MailStore = MailStore(File(temporaryFolder.root, "mail"))

    private val account = MailAccount(
        id = "abc123",
        providerId = "qq",
        email = "user@example.com",
        displayName = "User",
        color = 1,
        createdAt = 1000L,
    )

    private val summary = MailMessageSummary(
        id = "abc123:inbox:7",
        accountId = "abc123",
        folderRole = MailFolderRole.INBOX,
        uid = 7,
        threadId = "thread",
        subject = "hello",
        fromName = "Alice",
        fromAddress = "alice@example.com",
        to = listOf("user@example.com"),
        dateMillis = 1234L,
        preview = "preview",
        unread = true,
        starred = false,
        hasAttachments = false,
    )

    @Test
    fun accountsRoundTrip() {
        val store = store()
        store.saveAccounts(listOf(account))
        assertEquals(listOf(account), store.accounts())
    }

    @Test
    fun serversRoundTrip() {
        val store = store()
        val settings = MailServerSettings("abc123", "imap.example.com", 993, "smtp.example.com", 465)
        store.saveServers(listOf(settings))
        assertEquals(listOf(settings), store.servers())
    }

    @Test
    fun indexRoundTrip() {
        val store = store()
        val index = MailAccountIndex(
            accountId = "abc123",
            folders = listOf(MailFolderIndex(MailFolderRole.INBOX, "INBOX", 55, 7)),
            messages = listOf(summary),
            updatedAt = 99,
        )
        store.saveIndex(index)
        assertEquals(index, store.index("abc123"))
    }

    @Test
    fun draftsRoundTrip() {
        val store = store()
        val draft = MailDraft(
            id = "draft-1",
            accountId = "abc123",
            to = listOf("a@example.com"),
            subject = "subject",
            body = "body",
            createdAt = 1,
            updatedAt = 2,
        )
        store.saveDrafts(listOf(draft))
        assertEquals(listOf(draft), store.drafts())
    }

    @Test
    fun corruptJsonFallsBackToDefaults() {
        val root = File(temporaryFolder.root, "mail")
        root.mkdirs()
        File(root, "accounts.json").writeText("{not json")
        File(root, "index-abc123.json").writeText("[[[")
        val store = MailStore(root)
        assertEquals(emptyList<MailAccount>(), store.accounts())
        assertEquals(MailAccountIndex(accountId = "abc123"), store.index("abc123"))
    }

    @Test
    fun bodyCacheRoundTripAndPrune() {
        val store = store()
        store.writeBody("abc123", "inbox-7", "hello".toByteArray())
        store.writeBody("abc123", "inbox-8", "stale".toByteArray())
        assertEquals("hello", store.readBody("abc123", "inbox-7")?.toString(Charsets.UTF_8))
        store.retainBodies("abc123", setOf("inbox-7", "inbox-9"))
        assertNotNull(store.readBody("abc123", "inbox-7"))
        assertNull(store.readBody("abc123", "inbox-8"))
        val leftovers = File(File(temporaryFolder.root, "mail/bodies/abc123"), "inbox-7.eml.tmp")
        assertFalse(leftovers.exists())
    }

    @Test
    fun pruneRemovesStalePartFiles() {
        val store = store()
        store.writeBody("abc123", "inbox-7", "hello".toByteArray())
        val directory = File(temporaryFolder.root, "mail/bodies/abc123")
        val stale = File(directory, "inbox-8.eml.part").apply {
            writeText("partial")
            setLastModified(System.currentTimeMillis() - 2 * 60 * 60 * 1000)
        }
        val fresh = File(directory, "inbox-9.eml.part").apply { writeText("partial") }
        store.retainBodies("abc123", setOf("inbox-7"))
        assertFalse(stale.exists())
        assertTrue(fresh.exists())
    }

    @Test
    fun attachmentPathIsSanitized() {
        val store = store()
        val file = store.attachmentFile("abc123", "inbox-7", "att-0", "../../evil name.txt")
        val directory = store.attachmentDirectory("abc123", "inbox-7").canonicalFile
        assertTrue(file.canonicalFile.toPath().startsWith(directory.toPath()))
        assertFalse(file.name.contains("/"))
        assertFalse(file.name.contains(".."))
    }

    @Test
    fun deletingAccountRemovesEverything() {
        val store = store()
        store.saveIndex(MailAccountIndex(accountId = "abc123", messages = listOf(summary)))
        store.writeBody("abc123", "inbox-7", "body".toByteArray())
        store.attachmentFile("abc123", "inbox-7", "att-0", "file.txt").apply {
            parentFile?.mkdirs()
            writeText("x")
        }
        store.deleteAccount("abc123")
        assertFalse(File(File(temporaryFolder.root, "mail"), "index-abc123.json").exists())
        assertFalse(File(File(temporaryFolder.root, "mail/bodies"), "abc123").exists())
        assertNull(store.readBody("abc123", "inbox-7"))
    }

    @Test
    fun atomicWritesLeaveNoTempFile() {
        val store = store()
        store.saveAccounts(listOf(account))
        store.saveAccounts(listOf(account))
        val temp = File(File(temporaryFolder.root, "mail"), "accounts.json.tmp")
        assertFalse(temp.exists())
        assertTrue(File(File(temporaryFolder.root, "mail"), "accounts.json").exists())
    }
}
