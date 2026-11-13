package io.github.whywood.deepdeep.plugin.mail.mime

import io.github.whywood.deepdeep.plugin.mail.transport.decodeText
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

internal data class MailAddress(
    val name: String,
    val address: String,
) {
    val display: String
        get() = if (name.isBlank()) address else "$name <$address>"
}

internal data class MailHeaders(
    val subject: String = "",
    val from: List<MailAddress> = emptyList(),
    val to: List<MailAddress> = emptyList(),
    val cc: List<MailAddress> = emptyList(),
    val dateMillis: Long? = null,
    val messageId: String? = null,
    val references: List<String> = emptyList(),
    val inReplyTo: String? = null,
) {
    val sender: MailAddress?
        get() = from.firstOrNull()

    companion object {
        val EMPTY = MailHeaders()

        fun parse(raw: ByteArray): MailHeaders = fromFields(parseFields(decodeText(headerBlock(raw))))

        fun parse(value: String): MailHeaders = fromFields(parseFields(value))

        fun fromFields(fields: Map<String, List<String>>): MailHeaders {
            val subject = Rfc2047.decode(fields.firstValue("subject").orEmpty()).trim()
            val from = parseAddressList(fields.firstValue("from"))
            val to = parseAddressList(fields.firstValue("to"))
            val cc = parseAddressList(fields.firstValue("cc"))
            val date = MailDates.parse(fields.firstValue("date"))
            val messageId = normalizeMessageId(fields.firstValue("message-id"))
            val references = fields["references"].orEmpty().flatMap { splitMessageIds(it) }
            val inReplyTo = fields["in-reply-to"].orEmpty()
                .flatMap { splitMessageIds(it) }
                .firstOrNull()
            return MailHeaders(
                subject = subject,
                from = from,
                to = to,
                cc = cc,
                dateMillis = date,
                messageId = messageId,
                references = references,
                inReplyTo = inReplyTo,
            )
        }

        fun parseFields(value: String): Map<String, List<String>> {
            val fields = LinkedHashMap<String, MutableList<String>>()
            var currentName: String? = null
            var currentValue = StringBuilder()
            fun commit() {
                val name = currentName ?: return
                val text = currentValue.toString().trim()
                if (text.isNotEmpty()) fields.getOrPut(name) { mutableListOf() }.add(text)
            }
            for (line in value.split('\n')) {
                val stripped = line.removeSuffix("\r")
                if (stripped.isEmpty()) {
                    commit()
                    currentName = null
                    currentValue = StringBuilder()
                    continue
                }
                if ((stripped[0] == ' ' || stripped[0] == '\t') && currentName != null) {
                    currentValue.append(' ').append(stripped.trim())
                    continue
                }
                commit()
                val colon = stripped.indexOf(':')
                if (colon <= 0) {
                    currentName = null
                    currentValue = StringBuilder()
                    continue
                }
                currentName = stripped.substring(0, colon).trim().lowercase()
                currentValue = StringBuilder(stripped.substring(colon + 1).trim())
            }
            commit()
            return fields
        }

        fun parseAddressList(value: String?): List<MailAddress> {
            if (value.isNullOrBlank()) return emptyList()
            return splitAddressParts(value).mapNotNull { parseAddress(it) }
        }

        fun normalizeMessageId(value: String?): String? {
            val tokens = splitMessageIds(value.orEmpty())
            return tokens.firstOrNull()
        }

        fun splitMessageIds(value: String): List<String> {
            if (value.isBlank()) return emptyList()
            val matches = MESSAGE_ID.findAll(value).map { it.value.trim() }.toList()
            if (matches.isNotEmpty()) return matches
            return value.split(' ', ',', '\t', '\r', '\n').map { it.trim() }.filter { it.isNotEmpty() }
        }

        private fun Map<String, List<String>>.firstValue(name: String): String? =
            this[name]?.firstOrNull()?.takeIf { it.isNotBlank() }

        private fun headerBlock(raw: ByteArray): ByteArray {
            val text = raw.toString(StandardCharsets.ISO_8859_1)
            val crlf = text.indexOf("\r\n\r\n")
            val lf = text.indexOf("\n\n")
            val end = when {
                crlf >= 0 && (lf < 0 || crlf <= lf) -> crlf
                lf >= 0 -> lf
                else -> text.length
            }
            return text.substring(0, end).toByteArray(StandardCharsets.ISO_8859_1)
        }

        private fun splitAddressParts(value: String): List<String> {
            val parts = mutableListOf<String>()
            val builder = StringBuilder()
            var inQuotes = false
            var angleDepth = 0
            var escaped = false
            for (char in value) {
                when {
                    escaped -> {
                        builder.append(char)
                        escaped = false
                    }
                    char == '\\' && inQuotes -> {
                        builder.append(char)
                        escaped = true
                    }
                    char == '"' -> {
                        inQuotes = !inQuotes
                        builder.append(char)
                    }
                    char == '<' && !inQuotes -> {
                        angleDepth += 1
                        builder.append(char)
                    }
                    char == '>' && !inQuotes -> {
                        angleDepth = (angleDepth - 1).coerceAtLeast(0)
                        builder.append(char)
                    }
                    (char == ',' || char == ';') && !inQuotes && angleDepth == 0 -> {
                        parts += builder.toString()
                        builder.clear()
                    }
                    else -> builder.append(char)
                }
            }
            parts += builder.toString()
            return parts.map { it.trim() }.filter { it.isNotEmpty() }
        }

        private fun parseAddress(part: String): MailAddress? {
            val open = part.indexOf('<')
            val close = part.lastIndexOf('>')
            val address: String
            var name: String
            if (open >= 0 && close > open) {
                address = part.substring(open + 1, close).trim()
                name = part.substring(0, open)
            } else {
                val comment = COMMENT.find(part)
                address = COMMENT.replace(part, " ").trim()
                name = comment?.groupValues?.get(1).orEmpty()
            }
            val cleanedName = Rfc2047.decode(name.trim().trim('"', '\''))
                .replace(WHITESPACE, " ")
                .trim()
            val cleanedAddress = address.trim().trim('<', '>').trim()
            if (cleanedAddress.isEmpty() || cleanedAddress.contains(' ')) return null
            return MailAddress(cleanedName, cleanedAddress)
        }

        private val MESSAGE_ID = Regex("<[^<>\\s]+>")
        private val COMMENT = Regex("\\(([^)]*)\\)")
        private val WHITESPACE = Regex("\\s+")
    }
}

internal object MailDates {
    fun parse(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        val cleaned = value.replace(COMMENT, " ").replace(WHITESPACE, " ").trim()
        if (cleaned.isEmpty()) return null
        for (pattern in PATTERNS) {
            val parsed = runCatching {
                val format = SimpleDateFormat(pattern, Locale.US)
                format.timeZone = TimeZone.getTimeZone("UTC")
                format.isLenient = true
                format.parse(cleaned)?.time
            }.getOrNull()
            if (parsed != null) return parsed
        }
        return null
    }

    private val COMMENT = Regex("\\([^)]*\\)")
    private val WHITESPACE = Regex("\\s+")
    private val PATTERNS = listOf(
        "EEE, d MMM yyyy HH:mm:ss Z",
        "EEE, d MMM yyyy HH:mm:ss z",
        "d MMM yyyy HH:mm:ss Z",
        "d MMM yyyy HH:mm:ss z",
        "EEE, d MMM yyyy HH:mm Z",
        "d MMM yyyy HH:mm Z",
        "EEE, d MMM yy HH:mm:ss Z",
        "d MMM yy HH:mm:ss Z",
        "EEE, d MMM yyyy HH:mm:ss ZZZZ",
        "yyyy-MM-dd HH:mm:ss",
    )
}
