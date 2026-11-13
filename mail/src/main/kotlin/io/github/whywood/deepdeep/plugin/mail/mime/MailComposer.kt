package io.github.whywood.deepdeep.plugin.mail.mime

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

internal data class OutgoingAttachment(
    val path: String,
    val name: String,
    val mimeType: String,
)

internal data class OutgoingMail(
    val fromAddress: String,
    val fromName: String,
    val to: List<String>,
    val cc: List<String>,
    val subject: String,
    val body: String,
    val inReplyTo: String? = null,
    val references: List<String> = emptyList(),
    val attachments: List<OutgoingAttachment> = emptyList(),
)

internal object MailComposeLimits {
    const val MAX_ATTACHMENT_BYTES = 20L * 1024 * 1024
    const val MAX_TOTAL_ATTACHMENT_BYTES = 25L * 1024 * 1024
}

internal enum class MailComposeFailure {
    ATTACHMENT_MISSING,
    ATTACHMENT_UNREADABLE,
    ATTACHMENT_TOO_LARGE,
    TOTAL_TOO_LARGE,
}

internal class MailComposeException(
    val failure: MailComposeFailure,
    val attachmentName: String? = null,
    cause: Throwable? = null,
) : Exception(failure.name, cause)

internal object MailComposer {
    fun compose(mail: OutgoingMail, now: Long = System.currentTimeMillis()): ByteArray =
        ByteArrayOutputStream().also { composeTo(mail, it, now) }.toByteArray()

    fun composeTo(mail: OutgoingMail, output: OutputStream, now: Long = System.currentTimeMillis()): Long {
        val attachments = validate(mail.attachments)
        val boundary = if (attachments.isEmpty()) null else nextBoundary()
        val counter = CountingOutputStream(output)
        writeHeaders(counter, mail, now, boundary)
        if (boundary == null) {
            writeBase64(counter, mail.body.toByteArray(Charsets.UTF_8))
        } else {
            writeMultipart(counter, mail, attachments, boundary)
        }
        counter.flush()
        return counter.count
    }

    fun composeToFile(mail: OutgoingMail, file: File, now: Long = System.currentTimeMillis()): Long {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, file.name + ".part")
        temporary.delete()
        try {
            val length = FileOutputStream(temporary).use { stream -> composeTo(mail, stream, now) }
            file.delete()
            if (!temporary.renameTo(file)) throw MailComposeException(MailComposeFailure.ATTACHMENT_UNREADABLE)
            return length
        } finally {
            temporary.delete()
        }
    }

    fun newMessageId(fromAddress: String, now: Long = System.currentTimeMillis()): String {
        val domain = fromAddress.substringAfter('@', "").substringBefore('>').trim()
            .ifBlank { "deepdeep.local" }
        return "<$now.${UUID.randomUUID().toString().replace("-", "")}@$domain>"
    }

    private data class ValidatedAttachment(
        val file: File,
        val name: String,
        val mimeType: String,
    )

    private fun validate(attachments: List<OutgoingAttachment>): List<ValidatedAttachment> {
        var total = 0L
        val result = mutableListOf<ValidatedAttachment>()
        for (attachment in attachments) {
            val file = File(attachment.path)
            val displayName = sanitize(attachment.name).ifBlank { file.name }.take(MAX_NAME_LENGTH)
            if (!file.isFile) throw MailComposeException(MailComposeFailure.ATTACHMENT_MISSING, displayName)
            val size = file.length()
            if (size > MailComposeLimits.MAX_ATTACHMENT_BYTES) {
                throw MailComposeException(MailComposeFailure.ATTACHMENT_TOO_LARGE, displayName)
            }
            total += size
            if (total > MailComposeLimits.MAX_TOTAL_ATTACHMENT_BYTES) {
                throw MailComposeException(MailComposeFailure.TOTAL_TOO_LARGE, displayName)
            }
            val mimeType = sanitize(attachment.mimeType).ifBlank { "application/octet-stream" }
                .takeIf { value -> value.none { it == ';' || it == '"' || it.code < 0x20 } }
                ?: "application/octet-stream"
            result += ValidatedAttachment(file, displayName, mimeType)
        }
        return result
    }

    private fun writeHeaders(output: OutputStream, mail: OutgoingMail, now: Long, boundary: String?) {
        writeLine(output, "From: " + formatAddress(mail.fromName, mail.fromAddress))
        writeLine(output, "To: " + mail.to.joinToString(", ") { sanitize(it) })
        if (mail.cc.isNotEmpty()) writeLine(output, "Cc: " + mail.cc.joinToString(", ") { sanitize(it) })
        writeLine(output, "Subject: " + Rfc2047.encode(sanitize(mail.subject)))
        writeLine(output, "Date: " + formatDate(now))
        writeLine(output, "Message-ID: " + newMessageId(mail.fromAddress, now))
        if (!mail.inReplyTo.isNullOrBlank()) writeLine(output, "In-Reply-To: " + sanitize(mail.inReplyTo))
        val references = mail.references.map { sanitize(it) }.filter { it.isNotBlank() }.distinct().take(MAX_REFERENCES)
        if (references.isNotEmpty()) writeLine(output, "References: " + references.joinToString(" "))
        writeLine(output, "MIME-Version: 1.0")
        if (boundary == null) {
            writeLine(output, "Content-Type: text/plain; charset=UTF-8")
            writeLine(output, "Content-Transfer-Encoding: base64")
        } else {
            writeLine(output, "Content-Type: multipart/mixed; boundary=\"$boundary\"")
        }
        writeLine(output, "")
    }

    private fun writeMultipart(
        output: OutputStream,
        mail: OutgoingMail,
        attachments: List<ValidatedAttachment>,
        boundary: String,
    ) {
        val alternativeBoundary = nextBoundary()
        writeLine(output, "--$boundary")
        writeLine(output, "Content-Type: multipart/alternative; boundary=\"$alternativeBoundary\"")
        writeLine(output, "")
        writeLine(output, "--$alternativeBoundary")
        writeLine(output, "Content-Type: text/plain; charset=UTF-8")
        writeLine(output, "Content-Transfer-Encoding: base64")
        writeLine(output, "")
        writeBase64(output, mail.body.toByteArray(Charsets.UTF_8))
        writeLine(output, "--$alternativeBoundary")
        writeLine(output, "Content-Type: text/html; charset=UTF-8")
        writeLine(output, "Content-Transfer-Encoding: base64")
        writeLine(output, "")
        writeBase64(output, htmlFromText(mail.body))
        writeLine(output, "--$alternativeBoundary--")
        for (attachment in attachments) {
            writeLine(output, "--$boundary")
            writeLine(output, "Content-Type: ${attachment.mimeType}; ${headerParameter("name", attachment.name)}")
            writeLine(output, "Content-Disposition: attachment; ${headerParameter("filename", attachment.name)}")
            writeLine(output, "Content-Transfer-Encoding: base64")
            writeLine(output, "")
            writeBase64File(output, attachment.file)
        }
        writeLine(output, "--$boundary--")
    }

    private fun writeBase64File(output: OutputStream, file: File) {
        val buffer = ByteArray(BASE64_INPUT_CHUNK)
        file.inputStream().use { input ->
            while (true) {
                val read = readFully(input, buffer)
                if (read <= 0) break
                writeBase64Chunk(output, buffer, read)
            }
        }
    }

    private fun writeBase64(output: OutputStream, data: ByteArray) {
        var index = 0
        while (index < data.size) {
            val count = minOf(BASE64_INPUT_CHUNK, data.size - index)
            writeBase64Chunk(output, data, count, index)
            index += count
        }
    }

    private fun writeBase64Chunk(output: OutputStream, data: ByteArray, count: Int, offset: Int = 0) {
        val encoded = Base64.getEncoder().encodeToString(data.copyOfRange(offset, offset + count))
        var index = 0
        while (index < encoded.length) {
            val end = minOf(index + BASE64_LINE_LENGTH, encoded.length)
            writeLine(output, encoded.substring(index, end))
            index = end
        }
    }

    private fun readFully(input: InputStream, buffer: ByteArray): Int {
        var read = 0
        while (read < buffer.size) {
            val count = input.read(buffer, read, buffer.size - read)
            if (count < 0) break
            read += count
        }
        return read
    }

    private fun htmlFromText(text: String): ByteArray = buildString {
        append("<!DOCTYPE html><html><body>")
        if (text.isNotEmpty()) {
            for (line in text.split('\n')) {
                append("<p>").append(escapeHtml(line.removeSuffix("\r"))).append("</p>")
            }
        }
        append("</body></html>")
    }.toByteArray(Charsets.UTF_8)

    private fun escapeHtml(value: String): String = buildString(value.length + 8) {
        for (char in value) {
            when (char) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(char)
            }
        }
    }

    private fun headerParameter(name: String, value: String): String {
        val direct = value.all { it.code in 0x20..0x7e } && value.none { it == '"' || it == '\\' || it == ';' }
        if (direct) return "$name=\"$value\""
        val fallback = "attachment." + value.substringAfterLast('.', "bin")
            .filter { it.isLetterOrDigit() }
            .take(8)
            .ifEmpty { "bin" }
        return "$name=\"$fallback\"; $name*=UTF-8''${percentEncode(value)}"
    }

    private fun percentEncode(value: String): String = buildString {
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            val code = byte.toInt() and 0xff
            val char = code.toChar()
            if (char.isLetterOrDigit() || char in "!#$&+-.^_`|~") {
                append(char)
            } else {
                append('%')
                append(HEX[code shr 4])
                append(HEX[code and 0x0f])
            }
        }
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

    private fun nextBoundary(): String = "deepdeep-" + UUID.randomUUID().toString().replace("-", "")

    private fun writeLine(output: OutputStream, line: String) {
        output.write(line.toByteArray(Charsets.UTF_8))
        output.write(CRLF_BYTES)
    }

    private class CountingOutputStream(private val delegate: OutputStream) : OutputStream() {
        var count = 0L
            private set

        override fun write(byte: Int) {
            delegate.write(byte)
            count += 1
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            delegate.write(bytes, offset, length)
            count += length
        }

        override fun flush() {
            delegate.flush()
        }
    }

    private val CRLF_BYTES = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte())
    private const val BASE64_LINE_LENGTH = 76
    private const val BASE64_INPUT_CHUNK = 57 * 512
    private const val MAX_REFERENCES = 20
    private const val MAX_NAME_LENGTH = 200
    private val HEX = "0123456789ABCDEF".toCharArray()
}
