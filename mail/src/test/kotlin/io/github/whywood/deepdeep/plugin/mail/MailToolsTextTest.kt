package io.github.whywood.deepdeep.plugin.mail

import io.github.whywood.deepdeep.plugin.mail.MailFolderRole
import io.github.whywood.deepdeep.plugin.mail.MailAttachment
import io.github.whywood.deepdeep.plugin.mail.MailHeaderField
import io.github.whywood.deepdeep.plugin.mail.MailMessageDetail
import io.github.whywood.deepdeep.plugin.mail.MailMessageSummary
import io.github.whywood.deepdeep.plugin.mail.tools.mailReadResultText
import io.github.whywood.deepdeep.plugin.mail.tools.mailSearchResultText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MailToolsTextTest {
    private val account = MailAccount(
        id = "acct",
        providerId = "custom",
        email = "user@example.com",
        displayName = "User",
        color = 0,
        createdAt = 0L,
    )

    private fun summary(
        id: String = "acct:inbox:1",
        role: MailFolderRole = MailFolderRole.INBOX,
        subject: String = "quarterly report",
        fromName: String = "Sender",
        fromAddress: String = "sender@example.com",
        to: List<String> = listOf("User <user@example.com>"),
        preview: String = "hello preview",
        unread: Boolean = true,
        starred: Boolean = false,
        hasAttachments: Boolean = true,
        sizeBytes: Long = 2048,
    ): MailMessageSummary = MailMessageSummary(
        id = id,
        accountId = "acct",
        folderRole = role,
        uid = 1,
        threadId = "thread-1",
        subject = subject,
        fromName = fromName,
        fromAddress = fromAddress,
        to = to,
        dateMillis = 1_760_000_000_000L,
        preview = preview,
        unread = unread,
        starred = starred,
        hasAttachments = hasAttachments,
        sizeBytes = sizeBytes,
    )

    private fun detail(
        summary: MailMessageSummary = summary(),
        cc: List<String> = listOf("cc@example.com"),
        textBody: String = "full body line one\n\nfull body line two",
        htmlBody: String? = null,
        hasPlainText: Boolean = true,
        textTruncated: Boolean = false,
        attachments: List<MailAttachment> = emptyList(),
        headers: List<MailHeaderField> = listOf(
            MailHeaderField("Message-ID", "<1@example.com>"),
            MailHeaderField("Reply-To", "reply@example.com"),
            MailHeaderField("References", "<root@example.com> <1@example.com>"),
            MailHeaderField("List-Id", "<list.example.com>"),
        ),
    ): MailMessageDetail = MailMessageDetail(
        summary = summary,
        cc = cc,
        textBody = textBody,
        htmlBody = htmlBody,
        attachments = attachments,
        headers = headers,
        hasPlainText = hasPlainText,
        textTruncated = textTruncated,
    )

    @Test
    fun searchTextListsFolderRecipientStatusSizeThreadAndOffsetHint() {
        val text = mailSearchResultText(
            items = listOf(summary()),
            total = 3,
            offset = 0,
            folderLabel = { role -> if (role == MailFolderRole.INBOX) "收件箱" else role.name },
            accounts = mapOf("acct" to account),
        )
        assertTrue(text, text.contains("匹配 3 封，返回 1 封"))
        assertTrue(text, text.contains("可用 offset 继续（offset=1）"))
        assertTrue(text, text.contains("id=acct:inbox:1"))
        assertTrue(text, text.contains("文件夹：收件箱 · 账户：user@example.com"))
        assertTrue(text, text.contains("发件人：Sender <sender@example.com>"))
        assertTrue(text, text.contains("收件人：User <user@example.com>"))
        assertTrue(text, text.contains("未读"))
        assertTrue(text, text.contains("含附件"))
        assertTrue(text, text.contains("大小 2 KB"))
        assertTrue(text, text.contains("会话：thread-1"))
        assertTrue(text, text.contains("预览：hello preview"))
    }

    @Test
    fun searchTextDoesNotOfferOffsetWhenEverythingReturned() {
        val text = mailSearchResultText(
            items = listOf(summary()),
            total = 1,
            offset = 0,
            folderLabel = { "收件箱" },
            accounts = mapOf("acct" to account),
        )
        assertTrue(text, text.contains("匹配 1 封，返回 1 封。"))
        assertFalse(text, text.contains("可用 offset"))
    }

    @Test
    fun readTextListsEveryHeaderAndCompleteBody() {
        val text = mailReadResultText(
            detail = detail(),
            folderLabel = "收件箱",
            accountEmail = "user@example.com",
        )
        assertTrue(text, text.contains("Message-ID：<1@example.com>"))
        assertTrue(text, text.contains("Reply-To：reply@example.com"))
        assertTrue(text, text.contains("References：<root@example.com> <1@example.com>"))
        assertTrue(text, text.contains("List-Id：<list.example.com>"))
        assertTrue(text, text.contains("抄送：cc@example.com"))
        assertTrue(text, text.contains("正文：\nfull body line one\n\nfull body line two"))
        assertFalse(text, text.contains("HTML 原文"))
        assertFalse(text, text.contains("已截断"))
    }

    @Test
    fun readTextAddsHtmlSourceWhenHtmlIsMoreComplete() {
        val shortPlain = detail(textBody = "short", htmlBody = "<p>html body with more text</p>")
        val shortText = mailReadResultText(shortPlain, "收件箱", "user@example.com")
        assertTrue(shortText, shortText.contains("HTML 原文："))
        assertTrue(shortText, shortText.contains("<p>html body with more text</p>"))

        val htmlOnly = detail(htmlBody = "<p>html only body</p>", hasPlainText = false)
        val htmlOnlyText = mailReadResultText(htmlOnly, "收件箱", "user@example.com")
        assertTrue(htmlOnlyText, htmlOnlyText.contains("HTML 原文："))
        assertTrue(htmlOnlyText, htmlOnlyText.contains("<p>html only body</p>"))
    }

    @Test
    fun readTextListsEveryAttachmentWithInlineMetadata() {
        val text = mailReadResultText(
            detail = detail(
                attachments = listOf(
                    MailAttachment(
                        id = "att-0",
                        name = "logo.png",
                        mimeType = "image/png",
                        sizeBytes = 2048,
                        inline = true,
                        contentId = "logo@example.com",
                    ),
                    MailAttachment(
                        id = "att-1",
                        name = "report.pdf",
                        mimeType = "application/pdf",
                        sizeBytes = 4096,
                    ),
                ),
            ),
            folderLabel = "收件箱",
            accountEmail = "user@example.com",
        )
        assertTrue(text, text.contains("附件：2 个"))
        assertTrue(text, text.contains("logo.png（image/png，2 KB，id=att-0，内嵌，Content-ID=logo@example.com）"))
        assertTrue(text, text.contains("report.pdf（application/pdf，4 KB，id=att-1）"))
        assertFalse(text, text.contains("base64"))
    }

    @Test
    fun readTextMentionsTextTruncationLimits() {
        val text = mailReadResultText(
            detail = detail(textTruncated = true),
            folderLabel = "收件箱",
            accountEmail = "user@example.com",
        )
        assertTrue(text, text.contains("正文说明：正文超过单段 1 MiB 或总计 2 MiB 上限，已截断。"))
    }
}
