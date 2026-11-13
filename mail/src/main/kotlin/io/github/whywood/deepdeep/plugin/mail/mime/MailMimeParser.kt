package io.github.whywood.deepdeep.plugin.mail.mime

import io.github.whywood.deepdeep.plugin.mail.transport.decodeText
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.charset.Charset
import org.apache.james.mime4j.codec.Base64InputStream
import org.apache.james.mime4j.codec.DecodeMonitor
import org.apache.james.mime4j.codec.QuotedPrintableInputStream
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
    val inline: Boolean = false,
    val contentId: String? = null,
)

internal data class ParsedMail(
    val headers: MailHeaders,
    val textBody: String,
    val htmlBody: String?,
    val attachments: List<MimeAttachment>,
) {
    val preview: String get() = HtmlText.preview(textBody)
}

internal object MailLimits {
    const val MAX_TEXT_PART_BYTES = 1024 * 1024
    const val MAX_TEXT_TOTAL_BYTES = 2 * 1024 * 1024
    const val MAX_ATTACHMENT_RETAIN_BYTES = 32 * 1024 * 1024
    const val MAX_RETAINED_ATTACHMENT_BYTES = 32 * 1024 * 1024
    const val MAX_HEADER_BLOCK_BYTES = 256 * 1024
    const val MAX_LINE_LENGTH = 32 * 1024
    const val MAX_HEADER_COUNT = 1_000
    const val MAX_HEADER_LENGTH = 256 * 1024
    const val MAX_TOTAL_HEADER_COUNT = 5_000
    const val MAX_TOTAL_HEADER_LENGTH = 2L * 1024 * 1024
    const val MAX_CONTENT_BYTES = 64L * 1024 * 1024
    const val MAX_INLINE_IMAGE_BYTES = 5L * 1024 * 1024
    const val MAX_PART_COUNT = 500
    const val MAX_NESTING_DEPTH = 16
}

internal object MailMimeParser {
    fun parse(raw: ByteArray, retainAttachments: Boolean = false): ParsedMail =
        parseSource({ ByteArrayInputStream(raw) }, retainAttachments)

    fun parse(file: File, retainAttachments: Boolean = false): ParsedMail =
        parseSource({ BufferedInputStream(file.inputStream()) }, retainAttachments)

    fun normalizeContentId(value: String?): String? {
        if (value.isNullOrBlank()) return null
        val decoded = Rfc2047.decode(value).trim()
        val normalized = decoded.trim('<', '>').trim()
        return normalized.takeIf { it.isNotEmpty() && !it.contains('<') && !it.contains('>') }
    }

    fun extractAttachmentTo(file: File, attachmentId: String, destination: File, maxBytes: Long): Long? {
        val index = attachmentId.removePrefix(ATTACHMENT_ID_PREFIX).toIntOrNull() ?: return null
        if (index < 0) return null
        destination.parentFile?.mkdirs()
        val collector = Collector(retainAttachments = false, streamTarget = index, targetFile = destination, targetMaxBytes = maxBytes)
        val parsed = try {
            parseStream(BufferedInputStream(file.inputStream()), collector)
        } catch (error: Exception) {
            null
        }
        val written = collector.streamedTargetBytes
        if (parsed == null || written == null || written < 0) {
            destination.delete()
            return null
        }
        return written
    }

    private fun parseSource(open: () -> InputStream, retainAttachments: Boolean): ParsedMail {
        val collector = Collector(retainAttachments = retainAttachments, streamTarget = null, targetFile = null, targetMaxBytes = 0)
        val primary = try {
            parseStream(open(), collector)
        } catch (error: Exception) {
            null
        }
        if (primary != null) return primary
        return try {
            fallback(open())
        } catch (error: Exception) {
            ParsedMail(MailHeaders.EMPTY, "", null, emptyList())
        }
    }

    private fun parseStream(stream: InputStream, collector: Collector): ParsedMail {
        stream.use {
            val parser = MimeStreamParser(
                MimeConfig.custom()
                    .setMaxLineLen(MailLimits.MAX_LINE_LENGTH)
                    .setMaxHeaderCount(MailLimits.MAX_HEADER_COUNT)
                    .setMaxHeaderLen(MailLimits.MAX_HEADER_LENGTH)
                    .setMaxTotalHeaderCount(MailLimits.MAX_TOTAL_HEADER_COUNT)
                    .setMaxTotalHeaderLen(MailLimits.MAX_TOTAL_HEADER_LENGTH)
                    .setMaxContentLen(MailLimits.MAX_CONTENT_BYTES)
                    .setMaxPartCount(MailLimits.MAX_PART_COUNT)
                    .setMaxNestingDepth(MailLimits.MAX_NESTING_DEPTH)
                    .build(),
            )
            parser.setContentDecoding(true)
            parser.setRecurse()
            parser.setContentHandler(collector)
            parser.parse(it)
            return collector.finish()
        }
    }

    private fun fallback(stream: InputStream): ParsedMail {
        stream.use {
            val headerBytes = readHeaderBlock(it)
            val fields = MailHeaders.parseFields(decodeText(headerBytes))
            val headers = MailHeaders.fromFields(fields)
            val contentType = fields["content-type"]?.firstOrNull().orEmpty().lowercase()
            if (contentType.contains("multipart")) {
                return ParsedMail(headers, "", null, emptyList())
            }
            val transferEncoding = fields["content-transfer-encoding"]?.firstOrNull().orEmpty().trim().lowercase()
            val decoded = when {
                transferEncoding.contains("base64") -> Base64InputStream(it, DecodeMonitor.SILENT)
                transferEncoding.contains("quoted-printable") -> QuotedPrintableInputStream(it, DecodeMonitor.SILENT)
                else -> it
            }
            val bodyBytes = readLimited(decoded, MailLimits.MAX_TEXT_PART_BYTES)
            val bodyText = String(bodyBytes, charsetOf(contentTypeParameter(contentType, "charset")) ?: Charsets.UTF_8)
            val isHtml = contentType.contains("text/html")
            return ParsedMail(
                headers = headers,
                textBody = if (isHtml) HtmlText.toPlainText(bodyText) else bodyText,
                htmlBody = if (isHtml) bodyText else null,
                attachments = emptyList(),
            )
        }
    }

    private fun readHeaderBlock(stream: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val tail = IntArray(4) { -1 }
        while (true) {
            val byte = stream.read()
            if (byte < 0) break
            output.write(byte)
            tail[0] = tail[1]
            tail[1] = tail[2]
            tail[2] = tail[3]
            tail[3] = byte
            val crlfEnd = tail[0] == '\r'.code && tail[1] == '\n'.code && tail[2] == '\r'.code && tail[3] == '\n'.code
            val lfEnd = tail[2] == '\n'.code && tail[3] == '\n'.code
            if (crlfEnd || lfEnd) break
            if (output.size() >= MailLimits.MAX_HEADER_BLOCK_BYTES) break
        }
        return output.toByteArray()
    }

    private fun readLimited(stream: InputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream(minOf(limit, 64 * 1024))
        val buffer = ByteArray(READ_BUFFER_SIZE)
        while (output.size() < limit) {
            val read = stream.read(buffer, 0, minOf(buffer.size, limit - output.size()))
            if (read < 0) break
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
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
        private val streamTarget: Int?,
        private val targetFile: File?,
        private val targetMaxBytes: Long,
    ) : AbstractContentHandler() {
        private val messageHeaders = LinkedHashMap<String, MutableList<String>>()
        private val headerStack = ArrayDeque<MutableMap<String, MutableList<String>>>()
        private val attachments = mutableListOf<MimeAttachment>()
        private var plainText: String? = null
        private var htmlText: String? = null
        private var textBytesUsed = 0L
        private var attachmentIndex = 0
        private var retainedAttachmentBytes = 0L
        var streamedTargetBytes: Long? = null
            private set

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
            val dispositionType = disposition.type.lowercase()
            val contentTypeName = parameter(headers.firstValue("content-type").orEmpty(), "name")
            val fileName = disposition.filename ?: contentTypeName?.let { Rfc2047.decode(it) }
            val contentId = normalizeContentId(headers.firstValue("content-id"))
            val imagePart = mimeType.startsWith("image/") && contentId != null
            val inline = when {
                dispositionType == "inline" -> true
                dispositionType == "attachment" -> false
                else -> imagePart
            }
            val attachmentPart = dispositionType == "attachment" ||
                fileName != null ||
                (dispositionType == "inline" && contentId != null) ||
                imagePart
            when {
                attachmentPart -> collectAttachment(mimeType, fileName, inline, contentId, stream)
                mimeType == "text/plain" || mimeType == "text/html" -> collectText(mimeType, descriptor.charset, stream)
                else -> collectAttachment(mimeType, fileName, inline, contentId, stream)
            }
        }

        private fun collectText(mimeType: String, charset: String?, stream: InputStream) {
            val isPlain = mimeType == "text/plain"
            val wanted = (isPlain && plainText == null) || (!isPlain && htmlText == null)
            val remaining = MailLimits.MAX_TEXT_TOTAL_BYTES - textBytesUsed
            val keep = wanted && remaining > 0
            val limit = minOf(MailLimits.MAX_TEXT_PART_BYTES.toLong(), remaining).toInt()
            val body = readBody(stream, keep, limit)
            val data = body.data ?: return
            textBytesUsed += data.size
            val text = String(data, charsetOf(charset) ?: Charsets.UTF_8)
            if (text.isBlank()) return
            if (isPlain && plainText == null) plainText = text
            if (!isPlain && htmlText == null) htmlText = text
        }

        private fun collectAttachment(
            mimeType: String,
            fileName: String?,
            inline: Boolean,
            contentId: String?,
            stream: InputStream,
        ) {
            val index = attachmentIndex++
            val displayName = if (!fileName.isNullOrBlank()) fileName else defaultName(mimeType, index)
            if (streamTarget == index && targetFile != null) {
                streamedTargetBytes = streamAttachment(stream, targetFile, targetMaxBytes)
                attachments += MimeAttachment(
                    id = "$ATTACHMENT_ID_PREFIX$index",
                    name = displayName,
                    mimeType = mimeType,
                    sizeBytes = streamedTargetBytes ?: 0L,
                    data = null,
                    inline = inline,
                    contentId = contentId,
                )
                return
            }
            val remaining = MailLimits.MAX_RETAINED_ATTACHMENT_BYTES - retainedAttachmentBytes
            val keep = retainAttachments && remaining > 0
            val limit = minOf(MailLimits.MAX_ATTACHMENT_RETAIN_BYTES.toLong(), remaining).toInt()
            val body = readBody(stream, keep, limit)
            val data = body.data?.takeIf { !body.truncated }
            if (data != null) retainedAttachmentBytes += data.size
            attachments += MimeAttachment(
                id = "$ATTACHMENT_ID_PREFIX$index",
                name = displayName,
                mimeType = mimeType,
                sizeBytes = body.size,
                data = data,
                inline = inline,
                contentId = contentId,
            )
        }

        private fun streamAttachment(stream: InputStream, destination: File, maxBytes: Long): Long {
            val buffer = ByteArray(READ_BUFFER_SIZE)
            var written = 0L
            var overflow = false
            FileOutputStream(destination).buffered(READ_BUFFER_SIZE).use { output ->
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    if (overflow) continue
                    if (written + read > maxBytes) {
                        overflow = true
                        continue
                    }
                    output.write(buffer, 0, read)
                    written += read
                }
            }
            return if (overflow) -1L else written
        }

        private fun readBody(stream: InputStream, keep: Boolean, limit: Int): BodyData {
            val output = if (keep && limit > 0) ByteArrayOutputStream(minOf(limit, 64 * 1024)) else null
            val buffer = ByteArray(READ_BUFFER_SIZE)
            var total = 0L
            var truncated = false
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                total += read
                if (output != null) {
                    val remaining = limit - output.size()
                    if (remaining > 0) output.write(buffer, 0, minOf(read, remaining))
                    if (read > remaining) truncated = true
                }
            }
            return BodyData(output?.takeIf { it.size() > 0 }?.toByteArray(), total, truncated)
        }

        fun finish(): ParsedMail {
            val htmlBody = htmlText
            val textBody = plainText ?: htmlBody?.let { HtmlText.toPlainText(it) }.orEmpty()
            return ParsedMail(
                headers = MailHeaders.fromFields(messageHeaders),
                textBody = textBody,
                htmlBody = htmlBody,
                attachments = attachments.toList(),
            )
        }

        private data class BodyData(
            val data: ByteArray?,
            val size: Long,
            val truncated: Boolean,
        )

        private data class Disposition(val type: String, val filename: String?)

        private fun disposition(value: String?): Disposition {
            if (value.isNullOrBlank()) return Disposition("", null)
            val parts = splitParameters(value)
            val type = parts.firstOrNull().orEmpty().trim().lowercase()
            val extended = parts.drop(1).firstOrNull { part ->
                part.substringBefore('=').trim().equals("filename*", ignoreCase = true)
            }
            val plain = parts.drop(1).firstOrNull { part ->
                part.substringBefore('=').trim().equals("filename", ignoreCase = true)
            }
            val selected = extended ?: plain ?: return Disposition(type, null)
            val index = selected.indexOf('=')
            if (index <= 0) return Disposition(type, null)
            return Disposition(type, decodeParameter(selected.substring(index + 1).trim()))
        }

        private fun parameter(value: String, name: String): String? {
            if (value.isBlank()) return null
            val parts = splitParameters(value)
            val extended = parts.drop(1).firstOrNull { part ->
                part.substringBefore('=').trim().equals("$name*", ignoreCase = true)
            }
            val plain = parts.drop(1).firstOrNull { part ->
                part.substringBefore('=').trim().equals(name, ignoreCase = true)
            }
            val selected = extended ?: plain ?: return null
            val index = selected.indexOf('=')
            if (index <= 0) return null
            return decodeParameter(selected.substring(index + 1).trim())
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
            val bytes = ByteArrayOutputStream()
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

        private fun Map<String, List<String>>.firstValue(name: String): String? =
            this[name]?.firstOrNull()?.takeIf { it.isNotBlank() }

        private fun defaultName(mimeType: String, index: Int): String {
            val extension = EXTENSIONS[mimeType.substringBefore(';').trim().lowercase()] ?: "bin"
            return "attachment-${index + 1}.$extension"
        }

        private companion object {
            const val READ_BUFFER_SIZE = 16 * 1024
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

    private const val ATTACHMENT_ID_PREFIX = "att-"
    private const val READ_BUFFER_SIZE = 16 * 1024
}
