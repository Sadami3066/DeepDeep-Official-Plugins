package io.github.whywood.deepdeep.plugin.mail.mime

import io.github.whywood.deepdeep.plugin.mail.transport.decodeText
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import org.apache.james.mime4j.parser.AbstractContentHandler
import org.apache.james.mime4j.parser.MimeStreamParser
import org.apache.james.mime4j.stream.BodyDescriptor
import org.apache.james.mime4j.stream.Field
import org.apache.james.mime4j.stream.MimeConfig

internal data class MimeAttachment(
    val id: String,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
    val data: ByteArray?,
)

internal data class ParsedMail(
    val headers: MailHeaders,
    val textBody: String,
    val htmlBody: String?,
    val attachments: List<MimeAttachment>,
) {
    val preview: String get() = HtmlText.preview(textBody)
}

internal object MailMimeParser {
    fun parse(raw: ByteArray, retainAttachments: Boolean = false): ParsedMail {
        val collector = Collector(retainAttachments)
        return try {
            val parser = MimeStreamParser(
                MimeConfig.custom()
                    .setMaxLineLen(MAX_LINE_LENGTH)
                    .setMaxHeaderCount(MAX_HEADER_COUNT)
                    .setMaxHeaderLen(MAX_HEADER_LENGTH)
                    .setMaxContentLen(MAX_CONTENT_LENGTH)
                    .setMaxPartCount(MAX_PART_COUNT)
                    .setMaxNestingDepth(MAX_NESTING_DEPTH)
                    .build(),
            )
            parser.setContentDecoding(true)
            parser.setRecurse()
            parser.setContentHandler(collector)
            parser.parse(ByteArrayInputStream(raw))
            collector.finish()
        } catch (error: Exception) {
            fallback(raw)
        }
    }

    private fun fallback(raw: ByteArray): ParsedMail {
        val headers = MailHeaders.parse(raw)
        val text = raw.toString(StandardCharsets.ISO_8859_1)
        val lineBreak = when {
            text.contains("\r\n\r\n") -> text.indexOf("\r\n\r\n") + 4
            text.contains("\n\n") -> text.indexOf("\n\n") + 2
            else -> text.length
        }
        val headerBlock = raw.copyOf(
            when {
                text.contains("\r\n\r\n") -> text.indexOf("\r\n\r\n")
                text.contains("\n\n") -> text.indexOf("\n\n")
                else -> raw.size
            },
        )
        val fields = MailHeaders.parseFields(decodeText(headerBlock))
        val bodyBytes = text.substring(lineBreak).toByteArray(StandardCharsets.ISO_8859_1)
        val contentType = fields["content-type"]?.firstOrNull().orEmpty().lowercase()
        val transferEncoding = fields["content-transfer-encoding"]?.firstOrNull().orEmpty().trim().lowercase()
        val decodedBody = when {
            transferEncoding.contains("base64") -> decodeBase64(bodyBytes)
            transferEncoding.contains("quoted-printable") -> decodeQuotedPrintable(bodyBytes)
            else -> bodyBytes
        }
        val charsetName = contentTypeParameter(contentType, "charset")
        val bodyText = decodeBytes(decodedBody, charsetName)
        val textBody = if (contentType.contains("text/html")) HtmlText.toPlainText(bodyText) else bodyText
        return ParsedMail(
            headers = headers,
            textBody = textBody,
            htmlBody = if (contentType.contains("text/html")) bodyText else null,
            attachments = emptyList(),
        )
    }

    private fun decodeBase64(bytes: ByteArray): ByteArray {
        val compact = bytes.toString(StandardCharsets.ISO_8859_1).replace(Regex("\\s+"), "")
        return try {
            java.util.Base64.getDecoder().decode(compact)
        } catch (error: IllegalArgumentException) {
            ByteArray(0)
        }
    }

    private fun decodeQuotedPrintable(bytes: ByteArray): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        var index = 0
        while (index < bytes.size) {
            val byte = bytes[index].toInt() and 0xff
            if (byte == '='.code) {
                if (index + 1 < bytes.size && bytes[index + 1].toInt() == '\n'.code) {
                    index += 2
                    continue
                }
                if (index + 1 < bytes.size && bytes[index + 1].toInt() == '\r'.code &&
                    index + 2 < bytes.size && bytes[index + 2].toInt() == '\n'.code
                ) {
                    index += 3
                    continue
                }
                if (index + 2 < bytes.size) {
                    val high = hexValue(bytes[index + 1])
                    val low = hexValue(bytes[index + 2])
                    if (high >= 0 && low >= 0) {
                        output.write((high shl 4) or low)
                        index += 3
                        continue
                    }
                }
            }
            output.write(byte)
            index += 1
        }
        return output.toByteArray()
    }

    private fun hexValue(byte: Byte): Int = when (val char = byte.toInt().toChar()) {
        in '0'..'9' -> char - '0'
        in 'A'..'F' -> char - 'A' + 10
        in 'a'..'f' -> char - 'a' + 10
        else -> -1
    }

    private fun decodeBytes(bytes: ByteArray, charsetName: String?): String {
        val charset = charsetOf(charsetName) ?: Charsets.UTF_8
        return String(bytes, charset)
    }

    private fun charsetOf(name: String?): Charset? {
        if (name.isNullOrBlank()) return null
        return try {
            Charset.forName(name.trim().trim('"'))
        } catch (error: Exception) {
            null
        }
    }

    private fun contentTypeParameter(value: String, name: String): String? {
        for (part in value.split(';')) {
            val index = part.indexOf('=')
            if (index <= 0) continue
            if (!part.substring(0, index).trim().equals(name, ignoreCase = true)) continue
            return part.substring(index + 1).trim().trim('"')
        }
        return null
    }

    private class Collector(
        private val retainAttachments: Boolean,
    ) : AbstractContentHandler() {
        private val messageHeaders = LinkedHashMap<String, MutableList<String>>()
        private val headerStack = ArrayDeque<MutableMap<String, MutableList<String>>>()
        private val leaves = mutableListOf<Leaf>()
        private var retainedBytes = 0L

        override fun field(field: Field) {
            val target = headerStack.lastOrNull() ?: messageHeaders
            target.getOrPut(field.name.lowercase()) { mutableListOf() }.add(field.body)
        }

        override fun startBodyPart() {
            headerStack.addLast(LinkedHashMap())
        }

        override fun endBodyPart() {
            headerStack.removeLastOrNull()
        }

        override fun body(descriptor: BodyDescriptor, stream: InputStream) {
            val mimeType = descriptor.mimeType.lowercase().ifBlank { "application/octet-stream" }
            val headers = headerStack.lastOrNull() ?: messageHeaders
            val disposition = disposition(headers.firstValue("content-disposition"))
            val contentTypeName = parameter(headers.firstValue("content-type").orEmpty(), "name")
            val fileName = disposition.filename ?: contentTypeName?.let { Rfc2047.decode(it) }
            val isText = mimeType == "text/plain" || mimeType == "text/html"
            val keep = isText || (retainAttachments && retainedBytes < MAX_RETAINED_BYTES)
            val limit = if (isText) MAX_TEXT_BYTES else MAX_ATTACHMENT_BYTES
            val body = readBody(stream, keep, limit)
            if (!isText && body.data != null) retainedBytes += body.data.size
            leaves += Leaf(
                mimeType = mimeType,
                charset = descriptor.charset,
                data = body.data,
                sizeBytes = body.size,
                fileName = fileName,
                dispositionType = disposition.type,
            )
        }

        private fun readBody(stream: InputStream, keep: Boolean, limit: Int): BodyData {
            val buffer = ByteArray(READ_BUFFER_SIZE)
            val output = if (keep) java.io.ByteArrayOutputStream() else null
            var total = 0L
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                total += read
                if (output != null) {
                    val remaining = limit - output.size()
                    if (remaining > 0) output.write(buffer, 0, minOf(read, remaining))
                }
            }
            return BodyData(output?.takeIf { total <= limit }?.toByteArray(), total)
        }

        private data class BodyData(val data: ByteArray?, val size: Long)

        fun finish(): ParsedMail {
            val headers = MailHeaders.fromFields(messageHeaders)
            val plain = leaves.firstOrNull { it.mimeType == "text/plain" && it.text().isNotBlank() }
            val html = leaves.firstOrNull { it.mimeType == "text/html" && it.text().isNotBlank() }
            val htmlBody = html?.text()
            val textBody = plain?.text() ?: htmlBody?.let { HtmlText.toPlainText(it) }.orEmpty()
            val attachments = leaves
                .filter { it.isAttachment() }
                .mapIndexed { index, leaf ->
                    MimeAttachment(
                        id = "att-$index",
                        name = leaf.fileName?.takeIf { it.isNotBlank() } ?: defaultName(leaf.mimeType, index),
                        mimeType = leaf.mimeType,
                        sizeBytes = leaf.sizeBytes,
                        data = leaf.data,
                    )
                }
            return ParsedMail(
                headers = headers,
                textBody = textBody,
                htmlBody = htmlBody,
                attachments = attachments,
            )
        }

        private data class Disposition(val type: String, val filename: String?)

        private fun disposition(value: String?): Disposition {
            if (value.isNullOrBlank()) return Disposition("", null)
            val parts = splitParameters(value)
            val type = parts.firstOrNull().orEmpty().trim().lowercase()
            var filename: String? = null
            for (part in parts.drop(1)) {
                val index = part.indexOf('=')
                if (index <= 0) continue
                var name = part.substring(0, index).trim().lowercase()
                var rawValue = part.substring(index + 1).trim()
                if (name == "filename" || name == "filename*") {
                    filename = decodeParameter(rawValue)
                    break
                }
            }
            return Disposition(type, filename)
        }

        private fun parameter(value: String, name: String): String? {
            if (value.isBlank()) return null
            val parts = splitParameters(value)
            for (part in parts.drop(1)) {
                val index = part.indexOf('=')
                if (index <= 0) continue
                if (!part.substring(0, index).trim().equals(name, ignoreCase = true)) continue
                return decodeParameter(part.substring(index + 1).trim())
            }
            return null
        }

        private fun decodeParameter(value: String): String {
            val trimmed = value.trim()
            if (trimmed.endsWith("*") || trimmed.contains("''")) return decodeExtendedParameter(trimmed)
            val unquoted = if (trimmed.length >= 2 && trimmed.first() == '"' && trimmed.last() == '"') {
                trimmed.substring(1, trimmed.length - 1).replace(Regex("\\\\(.)"), "$1")
            } else {
                trimmed
            }
            return Rfc2047.decode(unquoted)
        }

        private fun decodeExtendedParameter(value: String): String {
            val firstQuote = value.indexOf('\'')
            if (firstQuote < 0) return Rfc2047.decode(value)
            val secondQuote = value.indexOf('\'', firstQuote + 1)
            if (secondQuote < 0) return Rfc2047.decode(value)
            val charsetName = value.substring(0, firstQuote)
            val encoded = value.substring(secondQuote + 1)
            val bytes = java.io.ByteArrayOutputStream()
            var index = 0
            while (index < encoded.length) {
                val char = encoded[index]
                if (char == '%' && index + 2 < encoded.length) {
                    val high = Character.digit(encoded[index + 1], 16)
                    val low = Character.digit(encoded[index + 2], 16)
                    if (high >= 0 && low >= 0) {
                        bytes.write((high shl 4) or low)
                        index += 3
                        continue
                    }
                }
                bytes.write(char.code)
                index += 1
            }
            val charset = charsetOf(charsetName) ?: Charsets.UTF_8
            return String(bytes.toByteArray(), charset)
        }

        private fun splitParameters(value: String): List<String> {
            val parts = mutableListOf<String>()
            val builder = StringBuilder()
            var inQuotes = false
            for (char in value) {
                when {
                    char == '"' -> {
                        inQuotes = !inQuotes
                        builder.append(char)
                    }
                    char == ';' && !inQuotes -> {
                        parts += builder.toString().trim()
                        builder.clear()
                    }
                    else -> builder.append(char)
                }
            }
            parts += builder.toString().trim()
            return parts.filter { it.isNotEmpty() }
        }

        private data class Leaf(
            val mimeType: String,
            val charset: String?,
            val data: ByteArray?,
            val sizeBytes: Long,
            val fileName: String?,
            val dispositionType: String,
        ) {
            fun text(): String {
                val bytes = data ?: return ""
                val charset = charsetName()
                return String(bytes, charset)
            }

            fun isAttachment(): Boolean = fileName != null ||
                dispositionType == "attachment" ||
                (mimeType != "text/plain" && mimeType != "text/html")

            private fun charsetName(): Charset {
                val name = charset?.trim()?.trim('"')
                if (!name.isNullOrBlank()) {
                    try {
                        return Charset.forName(name)
                    } catch (error: Exception) {
                        Unit
                    }
                }
                return Charsets.UTF_8
            }
        }

        private fun Map<String, List<String>>.firstValue(name: String): String? =
            this[name]?.firstOrNull()?.takeIf { it.isNotBlank() }

        private fun defaultName(mimeType: String, index: Int): String {
            val extension = EXTENSIONS[mimeType.substringBefore(';').trim().lowercase()] ?: "bin"
            return "attachment-${index + 1}.$extension"
        }

        private companion object {
            const val READ_BUFFER_SIZE = 16 * 1024
            const val MAX_TEXT_BYTES = 4 * 1024 * 1024
            const val MAX_ATTACHMENT_BYTES = 64 * 1024 * 1024
            const val MAX_RETAINED_BYTES = 96L * 1024 * 1024
            val EXTENSIONS = mapOf(
                "image/png" to "png",
                "image/jpeg" to "jpg",
                "image/gif" to "gif",
                "image/webp" to "webp",
                "application/pdf" to "pdf",
                "application/zip" to "zip",
                "application/gzip" to "gz",
                "application/msword" to "doc",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document" to "docx",
                "application/vnd.ms-excel" to "xls",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" to "xlsx",
                "application/vnd.ms-powerpoint" to "ppt",
                "application/vnd.openxmlformats-officedocument.presentationml.presentation" to "pptx",
                "application/json" to "json",
                "application/xml" to "xml",
                "text/calendar" to "ics",
                "text/vcard" to "vcf",
                "message/rfc822" to "eml",
                "audio/mpeg" to "mp3",
                "video/mp4" to "mp4",
            )
        }
    }

    private const val MAX_LINE_LENGTH = 100_000
    private const val MAX_HEADER_COUNT = 2_000
    private const val MAX_HEADER_LENGTH = 1_000_000
    private const val MAX_CONTENT_LENGTH = 96L * 1024 * 1024
    private const val MAX_PART_COUNT = 1_000
    private const val MAX_NESTING_DEPTH = 20
}
