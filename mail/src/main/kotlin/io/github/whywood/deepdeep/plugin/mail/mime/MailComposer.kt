package io.github.whywood.deepdeep.plugin.mail.mime

import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

internal data class OutgoingMail(
    val fromAddress: String,
    val fromName: String,
    val to: List<String>,
    val cc: List<String>,
    val subject: String,
    val body: String,
    val inReplyTo: String? = null,
    val references: List<String> = emptyList(),
)

internal object MailComposer {
    fun compose(mail: OutgoingMail, now: Long = System.currentTimeMillis()): ByteArray {
        val messageId = newMessageId(mail.fromAddress, now)
        val builder = StringBuilder()
        builder.append("From: ").append(formatAddress(mail.fromName, mail.fromAddress)).append(CRLF)
        builder.append("To: ").append(mail.to.joinToString(", ") { sanitize(it) }).append(CRLF)
        if (mail.cc.isNotEmpty()) {
            builder.append("Cc: ").append(mail.cc.joinToString(", ") { sanitize(it) }).append(CRLF)
        }
        builder.append("Subject: ").append(Rfc2047.encode(sanitize(mail.subject))).append(CRLF)
        builder.append("Date: ").append(formatDate(now)).append(CRLF)
        builder.append("Message-ID: ").append(messageId).append(CRLF)
        if (!mail.inReplyTo.isNullOrBlank()) {
            builder.append("In-Reply-To: ").append(sanitize(mail.inReplyTo)).append(CRLF)
        }
        val references = mail.references.map { sanitize(it) }.filter { it.isNotBlank() }.distinct().take(MAX_REFERENCES)
        if (references.isNotEmpty()) {
            builder.append("References: ").append(references.joinToString(" ")).append(CRLF)
        }
        builder.append("MIME-Version: 1.0").append(CRLF)
        builder.append("Content-Type: text/plain; charset=UTF-8").append(CRLF)
        builder.append("Content-Transfer-Encoding: base64").append(CRLF)
        builder.append(CRLF)
        val encoded = Base64.getEncoder().encodeToString(mail.body.toByteArray(Charsets.UTF_8))
        encoded.chunked(BASE64_LINE_LENGTH).forEach { line ->
            builder.append(line).append(CRLF)
        }
        return builder.toString().toByteArray(Charsets.UTF_8)
    }

    fun newMessageId(fromAddress: String, now: Long = System.currentTimeMillis()): String {
        val domain = fromAddress.substringAfter('@', "").substringBefore('>').trim()
            .ifBlank { "deepdeep.local" }
        return "<$now.${UUID.randomUUID().toString().replace("-", "")}@$domain>"
    }

    private fun formatAddress(name: String, address: String): String {
        val cleanAddress = sanitize(address)
        val cleanName = sanitize(name)
        return if (cleanName.isBlank()) cleanAddress else "${Rfc2047.encode(cleanName)} <$cleanAddress>"
    }

    private fun sanitize(value: String): String = value.replace('\r', ' ').replace('\n', ' ').trim()

    private fun formatDate(millis: Long): String {
        val format = SimpleDateFormat("EEE, d MMM yyyy HH:mm:ss Z", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        return format.format(Date(millis))
    }

    private const val CRLF = "\r\n"
    private const val BASE64_LINE_LENGTH = 76
    private const val MAX_REFERENCES = 20
}
