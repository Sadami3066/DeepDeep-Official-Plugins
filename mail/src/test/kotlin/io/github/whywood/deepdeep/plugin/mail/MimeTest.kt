package io.github.whywood.deepdeep.plugin.mail

import io.github.whywood.deepdeep.plugin.mail.mime.HtmlText
import io.github.whywood.deepdeep.plugin.mail.mime.MailComposeException
import io.github.whywood.deepdeep.plugin.mail.mime.MailComposeFailure
import io.github.whywood.deepdeep.plugin.mail.mime.MailComposeLimits
import io.github.whywood.deepdeep.plugin.mail.mime.MailComposer
import io.github.whywood.deepdeep.plugin.mail.mime.MailLimits
import io.github.whywood.deepdeep.plugin.mail.mime.MailMimeParser
import io.github.whywood.deepdeep.plugin.mail.mime.OutgoingAttachment
import io.github.whywood.deepdeep.plugin.mail.mime.OutgoingMail
import io.github.whywood.deepdeep.plugin.mail.mime.Rfc2047
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MimeTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val mixedMessage = """
        From: =?UTF-8?B?5byg5LiJ?= <zhangsan@example.com>
        To: "Li Si" <lisi@example.com>
        Cc: cc@example.com
        Subject: =?UTF-8?B?5rWL6K+V5Li76aKY?=
        Date: Tue, 12 Nov 2026 10:00:00 +0800
        Message-ID: <abc@example.com>
        References: <root@example.com>
        MIME-Version: 1.0
        Content-Type: multipart/mixed; boundary="BOUND"

        --BOUND
        Content-Type: text/plain; charset=UTF-8
        Content-Transfer-Encoding: quoted-printable

        =E4=BD=A0=E5=A5=BD=EF=BC=8C=E4=B8=96=E7=95=8C
        --BOUND
        Content-Type: application/pdf; name="report.pdf"
        Content-Disposition: attachment; filename="report.pdf"
        Content-Transfer-Encoding: base64

        JVBERi0xLjQK
        --BOUND--
    """.trimIndent().replace("\n", "\r\n")

    @Test
    fun parsesMultipartWithAttachmentAndHeaders() {
        val parsed = MailMimeParser.parse(mixedMessage.toByteArray(Charsets.UTF_8), retainAttachments = true)
        assertEquals("测试主题", parsed.headers.subject)
        assertEquals("张三", parsed.headers.from.first().name)
        assertEquals("zhangsan@example.com", parsed.headers.from.first().address)
        assertEquals("Li Si <lisi@example.com>", parsed.headers.to.first().display)
        assertEquals("cc@example.com", parsed.headers.cc.first().address)
        assertEquals("<abc@example.com>", parsed.headers.messageId)
        assertEquals(listOf("<root@example.com>"), parsed.headers.references)
        val expectedDate = SimpleDateFormat("EEE, d MMM yyyy HH:mm:ss Z", Locale.US)
            .parse("Tue, 12 Nov 2026 10:00:00 +0800")!!.time
        assertEquals(expectedDate, parsed.headers.dateMillis)
        assertEquals("你好，世界", parsed.textBody)
        assertEquals(1, parsed.attachments.size)
        val attachment = parsed.attachments.first()
        assertEquals("report.pdf", attachment.name)
        assertEquals("application/pdf", attachment.mimeType)
        assertEquals("%PDF-1.4\n".toByteArray().size.toLong(), attachment.sizeBytes)
        assertEquals("%PDF-1.4\n", attachment.data?.toString(Charsets.UTF_8))
        assertEquals("att-0", attachment.id)
    }

    @Test
    fun attachmentMetadataIsAvailableWithoutRetainingData() {
        val parsed = MailMimeParser.parse(mixedMessage.toByteArray(Charsets.UTF_8))
        val attachment = parsed.attachments.single()
        assertNull(attachment.data)
        assertEquals("%PDF-1.4\n".toByteArray().size.toLong(), attachment.sizeBytes)
    }

    @Test
    fun prefersPlainTextOverHtmlAlternative() {
        val raw = """
            Subject: alternative
            Content-Type: multipart/alternative; boundary="ALT"

            --ALT
            Content-Type: text/plain; charset=UTF-8

            plain wins
            --ALT
            Content-Type: text/html; charset=UTF-8

            <p>html loses</p>
            --ALT--
        """.trimIndent().replace("\n", "\r\n")
        val parsed = MailMimeParser.parse(raw.toByteArray(Charsets.UTF_8))
        assertEquals("plain wins", parsed.textBody.trim())
        assertTrue(parsed.htmlBody!!.contains("html loses"))
    }

    @Test
    fun convertsHtmlBodyToPlainTextWhenNoPlainPart() {
        val raw = """
            Subject: html only
            Content-Type: text/html; charset=UTF-8
            Content-Transfer-Encoding: quoted-printable

            <p>Hello&nbsp;<b>world</b></p><div>Second line</div>
        """.trimIndent().replace("\n", "\r\n")
        val parsed = MailMimeParser.parse(raw.toByteArray(Charsets.UTF_8))
        assertEquals("Hello world\n\nSecond line", parsed.textBody)
        assertNotNull(parsed.htmlBody)
        assertTrue(parsed.attachments.isEmpty())
    }

    @Test
    fun decodesGbkCharset() {
        val header = "Subject: gbk\r\nContent-Type: text/plain; charset=GBK\r\n\r\n"
        val output = ByteArrayOutputStream()
        output.write(header.toByteArray(Charsets.ISO_8859_1))
        output.write("中文内容".toByteArray(Charset.forName("GBK")))
        val parsed = MailMimeParser.parse(output.toByteArray())
        assertEquals("中文内容", parsed.textBody)
    }

    @Test
    fun decodesRfc2047EncodedWords() {
        assertEquals("测试", Rfc2047.decode("=?UTF-8?B?5rWL6K+V?="))
        assertEquals("plain", Rfc2047.decode("plain"))
        val encoded = Rfc2047.encode("很长的中文主题".repeat(10))
        assertTrue(encoded.all { it.code in 0x20..0x7e })
        assertEquals("很长的中文主题".repeat(10), Rfc2047.decode(encoded))
    }

    @Test
    fun stripsScriptAndStyleContent() {
        assertEquals("Hello world", HtmlText.toPlainText("<style>p{color:red}</style><p>Hello <b>world</b></p>"))
        assertEquals("before", HtmlText.toPlainText("before<script>var value = 1;"))
    }

    @Test
    fun previewFlattensWhitespaceAndTruncates() {
        assertEquals("a b c", HtmlText.preview("  a\n\nb   c  "))
        val long = "word ".repeat(100)
        assertEquals(200, HtmlText.preview(long, 200).length)
    }

    @Test
    fun composerProducesParseableMessage() {
        val body = "第一行\n.\n第三行"
        val message = MailComposer.compose(
            OutgoingMail(
                fromAddress = "me@example.com",
                fromName = "我的名字",
                to = listOf("a@example.com", "b@example.com"),
                cc = listOf("c@example.com"),
                subject = "回复：测试",
                body = body,
                inReplyTo = "<parent@example.com>",
                references = listOf("<root@example.com>", "<parent@example.com>"),
            ),
        )
        val parsed = MailMimeParser.parse(message)
        assertEquals("回复：测试", parsed.headers.subject)
        assertEquals("我的名字", parsed.headers.from.first().name)
        assertEquals("me@example.com", parsed.headers.from.first().address)
        assertEquals(listOf("a@example.com", "b@example.com"), parsed.headers.to.map { it.address })
        assertEquals(listOf("c@example.com"), parsed.headers.cc.map { it.address })
        assertEquals(body, parsed.textBody)
        assertEquals(listOf("<root@example.com>", "<parent@example.com>"), parsed.headers.references)
        assertEquals("<parent@example.com>", parsed.headers.inReplyTo)
        assertNotNull(parsed.headers.messageId)
    }

    @Test
    fun composerStripsHeaderInjection() {
        val message = MailComposer.compose(
            OutgoingMail(
                fromAddress = "me@example.com",
                fromName = "name",
                to = listOf("victim@example.com\r\nBcc: evil@example.com"),
                cc = emptyList(),
                subject = "hi\r\nBcc: evil@example.com",
                body = "body",
            ),
        )
        val text = message.toString(Charsets.UTF_8)
        assertFalse(text.contains("\r\nBcc: evil"))
    }

    @Test
    fun parsesFromFileWithSameHeadersAndBody() {
        val file = File(temporaryFolder.newFolder("mime"), "message.eml")
        file.writeBytes(mixedMessage.toByteArray(Charsets.UTF_8))
        val parsed = MailMimeParser.parse(file)
        assertEquals("测试主题", parsed.headers.subject)
        assertEquals("你好，世界", parsed.textBody)
        assertEquals(1, parsed.attachments.size)
        assertNull(parsed.attachments.single().data)
    }

    @Test
    fun truncatesOversizedTextBodyWithinLimit() {
        val body = ("a".repeat(1000) + "\n").repeat(MailLimits.MAX_TEXT_PART_BYTES / 500)
        val raw = "Subject: huge\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\n$body"
        val parsed = MailMimeParser.parse(raw.toByteArray(Charsets.UTF_8))
        assertTrue(parsed.textBody.length <= MailLimits.MAX_TEXT_PART_BYTES)
        assertTrue(parsed.textBody.isNotBlank())
    }

    @Test
    fun fallbackStaysBoundedForMalformedHugeMessage() {
        val longHeader = "a".repeat(MailLimits.MAX_LINE_LENGTH + 1000)
        val body = "b".repeat(MailLimits.MAX_TEXT_PART_BYTES * 2)
        val raw = "Subject: fallback\r\nX-Long: $longHeader\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\n$body"
        val parsed = MailMimeParser.parse(raw.toByteArray(Charsets.UTF_8))
        assertEquals("fallback", parsed.headers.subject)
        assertTrue(parsed.textBody.length <= MailLimits.MAX_TEXT_PART_BYTES)
    }

    @Test
    fun extractsOnlyRequestedAttachmentFromFile() {
        val raw = """
            Subject: two attachments
            Content-Type: multipart/mixed; boundary="TWO"

            --TWO
            Content-Type: text/plain; charset=UTF-8

            body
            --TWO
            Content-Type: application/pdf; name="first.pdf"
            Content-Disposition: attachment; filename="first.pdf"
            Content-Transfer-Encoding: base64

            JVBERi0xLjQK
            --TWO
            Content-Type: application/octet-stream
            Content-Disposition: attachment; filename="second.bin"
            Content-Transfer-Encoding: base64

            c2Vjb25k
            --TWO--
        """.trimIndent().replace("\n", "\r\n")
        val file = File(temporaryFolder.newFolder("targeted"), "message.eml")
        file.writeBytes(raw.toByteArray(Charsets.UTF_8))
        val metadata = MailMimeParser.parse(file)
        assertEquals(2, metadata.attachments.size)
        assertEquals("first.pdf", metadata.attachments[0].name)
        assertEquals(9L, metadata.attachments[0].sizeBytes)
        assertEquals("second.bin", metadata.attachments[1].name)
        assertNull(metadata.attachments[0].data)
        val secondFile = File(temporaryFolder.newFolder("second"), "second.bin")
        assertEquals(6L, MailMimeParser.extractAttachmentTo(file, "att-1", secondFile, 1024 * 1024))
        assertEquals("second", secondFile.readText())
        val firstFile = File(temporaryFolder.newFolder("first"), "first.pdf")
        assertEquals(9L, MailMimeParser.extractAttachmentTo(file, "att-0", firstFile, 1024 * 1024))
        assertEquals("%PDF-1.4\n", firstFile.readText())
        val missingFile = File(temporaryFolder.newFolder("missing"), "none.bin")
        assertNull(MailMimeParser.extractAttachmentTo(file, "att-9", missingFile, 1024 * 1024))
        assertFalse(missingFile.exists())
        val cappedFile = File(temporaryFolder.newFolder("capped"), "first.pdf")
        assertNull(MailMimeParser.extractAttachmentTo(file, "att-0", cappedFile, 2))
        assertFalse(cappedFile.exists())
    }

    @Test
    fun composesMultipartMessageWithAttachments() {
        val directory = temporaryFolder.newFolder("compose")
        val textFile = File(directory, "notes.txt").apply { writeText("hello attachment") }
        val chineseFile = File(directory, "source.pdf").apply { writeText("PDFDATA") }
        val message = MailComposer.compose(
            OutgoingMail(
                fromAddress = "me@example.com",
                fromName = "Me",
                to = listOf("you@example.com"),
                cc = emptyList(),
                subject = "带附件",
                body = "第一行\n第二行",
                attachments = listOf(
                    OutgoingAttachment(textFile.absolutePath, "notes.txt", "text/plain"),
                    OutgoingAttachment(chineseFile.absolutePath, "中文报告.pdf", "application/pdf"),
                ),
            ),
        )
        val raw = message.toString(Charsets.UTF_8)
        assertTrue(raw.contains("multipart/mixed"))
        assertTrue(raw.contains("multipart/alternative"))
        assertTrue(raw.contains("filename*=UTF-8''"))
        val parsed = MailMimeParser.parse(message, retainAttachments = true)
        assertEquals("第一行\n第二行", parsed.textBody)
        assertNotNull(parsed.htmlBody)
        assertTrue(parsed.htmlBody!!.contains("第一行"))
        assertEquals(2, parsed.attachments.size)
        assertEquals("notes.txt", parsed.attachments[0].name)
        assertEquals("text/plain", parsed.attachments[0].mimeType)
        assertEquals("hello attachment", parsed.attachments[0].data?.toString(Charsets.UTF_8))
        assertEquals("中文报告.pdf", parsed.attachments[1].name)
        assertEquals("application/pdf", parsed.attachments[1].mimeType)
        assertEquals("PDFDATA", parsed.attachments[1].data?.toString(Charsets.UTF_8))
    }

    @Test
    fun composesAttachmentNameWithQuotesAsParameter() {
        val directory = temporaryFolder.newFolder("quoted")
        val file = File(directory, "source.bin").apply { writeBytes(ByteArray(8) { it.toByte() }) }
        val message = MailComposer.compose(
            OutgoingMail(
                fromAddress = "me@example.com",
                fromName = "Me",
                to = listOf("you@example.com"),
                cc = emptyList(),
                subject = "quoted",
                body = "body",
                attachments = listOf(OutgoingAttachment(file.absolutePath, "report \"final\".bin", "application/octet-stream")),
            ),
        )
        val parsed = MailMimeParser.parse(message)
        assertEquals("report \"final\".bin", parsed.attachments.single().name)
    }

    @Test
    fun rejectsOversizedSingleAttachment() {
        val file = temporaryFolder.newFile("big.bin")
        RandomAccessFile(file, "rw").use { it.setLength(MailComposeLimits.MAX_ATTACHMENT_BYTES + 1) }
        val error = runCatching {
            MailComposer.compose(
                OutgoingMail(
                    fromAddress = "me@example.com",
                    fromName = "Me",
                    to = listOf("you@example.com"),
                    cc = emptyList(),
                    subject = "big",
                    body = "body",
                    attachments = listOf(OutgoingAttachment(file.absolutePath, "big.bin", "application/octet-stream")),
                ),
            )
        }.exceptionOrNull()
        assertTrue(error is MailComposeException)
        assertEquals(MailComposeFailure.ATTACHMENT_TOO_LARGE, (error as MailComposeException).failure)
    }

    @Test
    fun rejectsTotalOversizedAttachments() {
        val first = temporaryFolder.newFile("first.bin")
        val second = temporaryFolder.newFile("second.bin")
        val each = MailComposeLimits.MAX_TOTAL_ATTACHMENT_BYTES / 2 + 1
        RandomAccessFile(first, "rw").use { it.setLength(each) }
        RandomAccessFile(second, "rw").use { it.setLength(each) }
        val error = runCatching {
            MailComposer.compose(
                OutgoingMail(
                    fromAddress = "me@example.com",
                    fromName = "Me",
                    to = listOf("you@example.com"),
                    cc = emptyList(),
                    subject = "big",
                    body = "body",
                    attachments = listOf(
                        OutgoingAttachment(first.absolutePath, "first.bin", "application/octet-stream"),
                        OutgoingAttachment(second.absolutePath, "second.bin", "application/octet-stream"),
                    ),
                ),
            )
        }.exceptionOrNull()
        assertTrue(error is MailComposeException)
        assertEquals(MailComposeFailure.TOTAL_TOO_LARGE, (error as MailComposeException).failure)
    }

    @Test
    fun rejectsMissingAttachmentFile() {
        val error = runCatching {
            MailComposer.compose(
                OutgoingMail(
                    fromAddress = "me@example.com",
                    fromName = "Me",
                    to = listOf("you@example.com"),
                    cc = emptyList(),
                    subject = "missing",
                    body = "body",
                    attachments = listOf(OutgoingAttachment("/definitely/missing/file.bin", "file.bin", "application/octet-stream")),
                ),
            )
        }.exceptionOrNull()
        assertTrue(error is MailComposeException)
        assertEquals(MailComposeFailure.ATTACHMENT_MISSING, (error as MailComposeException).failure)
    }

    @Test
    fun composeToFileWritesParseableMessage() {
        val directory = temporaryFolder.newFolder("compose-file")
        val attachment = File(directory, "data.txt").apply { writeText("payload") }
        val target = File(directory, "outgoing.eml")
        val written = MailComposer.composeToFile(
            OutgoingMail(
                fromAddress = "me@example.com",
                fromName = "Me",
                to = listOf("you@example.com"),
                cc = emptyList(),
                subject = "file",
                body = "body content",
                attachments = listOf(OutgoingAttachment(attachment.absolutePath, "data.txt", "text/plain")),
            ),
            target,
        )
        assertTrue(target.isFile)
        assertEquals(target.length(), written)
        assertFalse(File(directory, "outgoing.eml.part").exists())
        val parsed = MailMimeParser.parse(target, retainAttachments = true)
        assertEquals("body content", parsed.textBody)
        assertEquals("payload", parsed.attachments.single().data?.toString(Charsets.UTF_8))
    }

    @Test
    fun truncatedMessageDoesNotCrash() {
        val raw = "Subject: broken\r\nContent-Type: multipart/mixed; boundary=\"B\"\r\n\r\n--B\r\nContent-Type: text/plain\r\n\r\nhello"
        val parsed = MailMimeParser.parse(raw.toByteArray())
        assertEquals("broken", parsed.headers.subject)
    }
}
