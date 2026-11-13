package io.github.whywood.deepdeep.plugin.mail

import io.github.whywood.deepdeep.plugin.mail.mime.HtmlText
import io.github.whywood.deepdeep.plugin.mail.mime.MailComposer
import io.github.whywood.deepdeep.plugin.mail.mime.MailMimeParser
import io.github.whywood.deepdeep.plugin.mail.mime.OutgoingMail
import io.github.whywood.deepdeep.plugin.mail.mime.Rfc2047
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MimeTest {
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
    fun truncatedMessageDoesNotCrash() {
        val raw = "Subject: broken\r\nContent-Type: multipart/mixed; boundary=\"B\"\r\n\r\n--B\r\nContent-Type: text/plain\r\n\r\nhello"
        val parsed = MailMimeParser.parse(raw.toByteArray())
        assertEquals("broken", parsed.headers.subject)
    }
}
