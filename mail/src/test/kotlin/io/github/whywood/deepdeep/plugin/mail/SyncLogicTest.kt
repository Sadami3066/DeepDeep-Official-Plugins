package io.github.whywood.deepdeep.plugin.mail

import io.github.whywood.deepdeep.plugin.mail.sync.FolderMapping
import io.github.whywood.deepdeep.plugin.mail.sync.MailMessageMapper
import io.github.whywood.deepdeep.plugin.mail.sync.MailThreading
import io.github.whywood.deepdeep.plugin.mail.sync.MessageKeys
import io.github.whywood.deepdeep.plugin.mail.sync.SyncPolicy
import io.github.whywood.deepdeep.plugin.mail.transport.ImapAddress
import io.github.whywood.deepdeep.plugin.mail.transport.ImapEnvelope
import io.github.whywood.deepdeep.plugin.mail.transport.ImapFetchResult
import io.github.whywood.deepdeep.plugin.mail.transport.ImapFolder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncLogicTest {
    @Test
    fun resolvesFoldersBySpecialUseFlags() {
        val folders = listOf(
            ImapFolder("INBOX", "/", emptySet()),
            ImapFolder("Sent Items", "/", setOf("\\Sent")),
            ImapFolder("Drafts", "/", setOf("\\Drafts")),
            ImapFolder("Deleted", "/", setOf("\\Trash")),
            ImapFolder("Archives", "/", setOf("\\Archive")),
            ImapFolder("Parent", "/", setOf("\\Noselect")),
        )
        val roles = FolderMapping.resolve(folders)
        assertEquals("INBOX", roles[MailFolderRole.INBOX])
        assertEquals("Sent Items", roles[MailFolderRole.SENT])
        assertEquals("Drafts", roles[MailFolderRole.DRAFTS])
        assertEquals("Deleted", roles[MailFolderRole.TRASH])
        assertEquals("Archives", roles[MailFolderRole.ARCHIVE])
    }

    @Test
    fun resolvesChineseFolderNamesWithoutSpecialUseFlags() {
        val folders = listOf(
            ImapFolder("INBOX", "/", emptySet()),
            ImapFolder("已发送", "/", emptySet()),
            ImapFolder("草稿箱", "/", emptySet()),
            ImapFolder("已删除邮件", "/", emptySet()),
            ImapFolder("归档", "/", emptySet()),
        )
        val roles = FolderMapping.resolve(folders)
        assertEquals("已发送", roles[MailFolderRole.SENT])
        assertEquals("草稿箱", roles[MailFolderRole.DRAFTS])
        assertEquals("已删除邮件", roles[MailFolderRole.TRASH])
        assertEquals("归档", roles[MailFolderRole.ARCHIVE])
    }

    @Test
    fun resolvesNestedGmailFolders() {
        val folders = listOf(
            ImapFolder("INBOX", "/", emptySet()),
            ImapFolder("[Gmail]/Sent Mail", "/", emptySet()),
            ImapFolder("[Gmail]/Drafts", "/", emptySet()),
            ImapFolder("[Gmail]/Trash", "/", emptySet()),
            ImapFolder("[Gmail]/All Mail", "/", emptySet()),
        )
        val roles = FolderMapping.resolve(folders)
        assertEquals("[Gmail]/Sent Mail", roles[MailFolderRole.SENT])
        assertEquals("[Gmail]/All Mail", roles[MailFolderRole.ARCHIVE])
        assertEquals("[Gmail]/Trash", roles[MailFolderRole.TRASH])
    }

    @Test
    fun prefersSpecialUseOverNameHeuristics() {
        val folders = listOf(
            ImapFolder("INBOX", "/", emptySet()),
            ImapFolder("Posteingang", "/", emptySet()),
            ImapFolder("Sent", "/", setOf("\\Trash")),
        )
        val roles = FolderMapping.resolve(folders)
        assertEquals("Sent", roles[MailFolderRole.TRASH])
        assertNull(roles[MailFolderRole.SENT])
    }

    @Test
    fun buildsThreadFromReferencesRoot() {
        assertEquals(
            "root@example.com",
            MailThreading.threadId(
                references = listOf("<root@example.com>", "<parent@example.com>"),
                inReplyTo = "<parent@example.com>",
                subject = "Re: topic",
                messageId = "<self@example.com>",
            ),
        )
    }

    @Test
    fun fallsBackToInReplyToThenMessageId() {
        assertEquals(
            "parent@example.com",
            MailThreading.threadId(emptyList(), "<parent@example.com>", "Re: topic", "<self@example.com>"),
        )
        assertEquals(
            "self@example.com",
            MailThreading.threadId(emptyList(), null, "Re: topic", "<self@example.com>"),
        )
    }

    @Test
    fun fallsBackToNormalizedSubject() {
        assertEquals("topic", MailThreading.threadId(emptyList(), null, "Re: FWD: 回覆： Topic ", null))
        assertEquals("", MailThreading.threadId(emptyList(), null, "   ", null))
    }

    @Test
    fun capsAccountMessagesToNewest() {
        val messages = (1L..2100L).map { uid ->
            summary(uid, dateMillis = uid)
        }
        val capped = SyncPolicy.capAccount(messages)
        assertEquals(2000, capped.size)
        assertEquals(2100L, capped.first().uid)
        assertEquals(101L, capped.last().uid)
    }

    @Test
    fun selectsNewestFlagWindowPerRole() {
        val messages = (1L..600L).map { uid -> summary(uid, dateMillis = uid) } +
            summary(700, dateMillis = 700, role = MailFolderRole.SENT)
        val window = SyncPolicy.flagWindow(messages, MailFolderRole.INBOX)
        assertEquals(500, window.size)
        assertEquals(600L, window.first().uid)
        assertEquals(101L, window.last().uid)
    }

    @Test
    fun prefetchSkipsLargeMessagesFromStressScenario() {
        val messages = (1L..8L).map { uid -> summary(uid, dateMillis = uid, sizeBytes = 32L * 1024 * 1024) }
        assertTrue(SyncPolicy.prefetchCandidates(messages).isEmpty())
    }

    @Test
    fun prefetchRespectsTotalBudgetAndNewestFirst() {
        val messages = (1L..10L).map { uid -> summary(uid, dateMillis = uid, sizeBytes = 3L * 1024 * 1024) }
        val selected = SyncPolicy.prefetchCandidates(messages)
        assertEquals(8, selected.size)
        assertTrue(selected.sumOf { it.sizeBytes } <= SyncPolicy.PREFETCH_TOTAL_BYTES)
        assertEquals(10L, selected.first().uid)
        assertEquals(3L, selected.last().uid)
    }

    @Test
    fun messageKeysRoundTrip() {
        assertEquals("acc:inbox:5", MessageKeys.id("acc", MailFolderRole.INBOX, 5))
        assertEquals("inbox-5", MessageKeys.key(MailFolderRole.INBOX, 5))
        assertEquals(MailFolderRole.SENT, MessageKeys.roleFromName("sent"))
        assertNull(MessageKeys.roleFromName("nope"))
    }

    @Test
    fun mapsFetchIntoSummary() {
        val envelope = ImapEnvelope(
            date = "Tue, 12 Nov 2026 10:00:00 +0800",
            subject = "=?UTF-8?B?5L2g5aW9?=",
            from = listOf(ImapAddress("Alice", "alice", "example.com")),
            to = listOf(ImapAddress(null, "bob", "example.com")),
            cc = emptyList(),
            inReplyTo = null,
            messageId = "<self@example.com>",
        )
        val fetch = ImapFetchResult(
            sequence = 1,
            uid = 8,
            flags = setOf("\\Flagged"),
            internalDate = "12-Nov-2026 10:00:00 +0800",
            size = 100,
            envelope = envelope,
            headerBlock = null,
            body = null,
        )
        val summary = MailMessageMapper.toSummary("acc", MailFolderRole.INBOX, fetch)
        assertEquals("你好", summary.subject)
        assertEquals("Alice", summary.fromName)
        assertEquals("alice@example.com", summary.fromAddress)
        assertEquals(listOf("bob@example.com"), summary.to)
        assertTrue(summary.unread)
        assertTrue(summary.starred)
        assertEquals("self@example.com", summary.threadId)
        assertEquals("", summary.preview)
        assertFalse(summary.hasAttachments)
        assertEquals(100L, summary.sizeBytes)
    }

    @Test
    fun threadedRepliesShareRootId() {
        val root = MailThreading.threadId(emptyList(), null, "Topic", "<root@example.com>")
        val reply = MailThreading.threadId(listOf("<root@example.com>"), "<root@example.com>", "Re: Topic", "<reply@example.com>")
        assertEquals(root, reply)
        assertNotEquals("", root)
    }

    private fun summary(
        uid: Long,
        dateMillis: Long,
        role: MailFolderRole = MailFolderRole.INBOX,
        sizeBytes: Long = 0,
    ): MailMessageSummary = MailMessageSummary(
        id = MessageKeys.id("acc", role, uid),
        accountId = "acc",
        folderRole = role,
        uid = uid,
        threadId = "thread-$uid",
        subject = "subject $uid",
        fromName = "sender",
        fromAddress = "sender@example.com",
        to = listOf("acc@example.com"),
        dateMillis = dateMillis,
        preview = "preview $uid",
        unread = false,
        starred = false,
        hasAttachments = false,
        sizeBytes = sizeBytes,
    )
}
