package io.github.whywood.deepdeep.plugin.mail.transport

import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Locale
import java.util.TimeZone

internal object ImapCodec {
    fun encodeMailbox(name: String): String {
        val builder = StringBuilder()
        var index = 0
        while (index < name.length) {
            val char = name[index]
            when {
                char == '&' -> {
                    builder.append("&-")
                    index++
                }
                char.code in 0x20..0x7e -> {
                    builder.append(char)
                    index++
                }
                else -> {
                    val start = index
                    while (index < name.length) {
                        val current = name[index]
                        if (current == '&' || current.code in 0x20..0x7e) break
                        index++
                    }
                    builder.append('&')
                    builder.append(encodeModifiedBase64(name.substring(start, index)))
                    builder.append('-')
                }
            }
        }
        return builder.toString()
    }

    fun decodeMailbox(raw: String): String {
        val builder = StringBuilder()
        var index = 0
        while (index < raw.length) {
            val char = raw[index]
            if (char != '&') {
                builder.append(char)
                index++
                continue
            }
            val end = raw.indexOf('-', index + 1)
            if (end < 0) {
                builder.append(raw.substring(index))
                break
            }
            val section = raw.substring(index + 1, end)
            if (section.isEmpty()) {
                builder.append('&')
            } else {
                builder.append(decodeModifiedBase64(section))
            }
            index = end + 1
        }
        return builder.toString()
    }

    fun quote(value: String): String {
        val builder = StringBuilder(value.length + 2)
        builder.append('"')
        for (char in value) {
            when (char) {
                '\\' -> builder.append("\\\\")
                '"' -> builder.append("\\\"")
                '\r', '\n' -> Unit
                else -> builder.append(char)
            }
        }
        builder.append('"')
        return builder.toString()
    }

    fun quoteMailbox(name: String): String = quote(encodeMailbox(name))

    fun uidSet(uids: Collection<Long>): String {
        if (uids.isEmpty()) return ""
        val sorted = uids.toSortedSet()
        val builder = StringBuilder()
        var rangeStart = -1L
        var previous = -1L
        for (uid in sorted) {
            if (rangeStart < 0) {
                rangeStart = uid
                previous = uid
                continue
            }
            if (uid == previous + 1) {
                previous = uid
                continue
            }
            appendRange(builder, rangeStart, previous)
            rangeStart = uid
            previous = uid
        }
        appendRange(builder, rangeStart, previous)
        return builder.toString()
    }

    fun parseInternalDate(value: String): Long? {
        val normalized = value.trim()
        if (normalized.isEmpty()) return null
        return try {
            val format = SimpleDateFormat("d-MMM-yyyy HH:mm:ss Z", Locale.US)
            format.timeZone = TimeZone.getTimeZone("UTC")
            format.isLenient = true
            format.parse(normalized)?.time
        } catch (error: Exception) {
            null
        }
    }

    private fun appendRange(builder: StringBuilder, start: Long, end: Long) {
        if (builder.isNotEmpty()) builder.append(',')
        builder.append(start)
        if (end != start) builder.append(':').append(end)
    }

    private fun encodeModifiedBase64(value: String): String {
        val bytes = value.toByteArray(StandardCharsets.UTF_16BE)
        return Base64.getEncoder()
            .encodeToString(bytes)
            .trimEnd('=')
            .replace('/', ',')
    }

    private fun decodeModifiedBase64(value: String): String {
        val standard = value.replace(',', '/')
        val padding = (4 - standard.length % 4) % 4
        val bytes = Base64.getDecoder().decode(standard + "=".repeat(padding))
        return String(bytes, StandardCharsets.UTF_16BE)
    }
}
