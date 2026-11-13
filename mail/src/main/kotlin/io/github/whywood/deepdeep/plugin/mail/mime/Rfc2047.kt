package io.github.whywood.deepdeep.plugin.mail.mime

import java.nio.charset.StandardCharsets
import java.util.Base64
import org.apache.james.mime4j.codec.DecodeMonitor
import org.apache.james.mime4j.codec.DecoderUtil

internal object Rfc2047 {
    fun decode(value: String): String {
        if (!value.contains("=?")) return value
        return try {
            DecoderUtil.decodeEncodedWords(value, DecodeMonitor.SILENT)
        } catch (error: IllegalArgumentException) {
            value
        }
    }

    fun encode(value: String): String {
        if (value.isEmpty()) return value
        if (value.all { it.code in 0x20..0x7e }) return value
        val builder = StringBuilder()
        var segment = StringBuilder()
        var segmentBytes = 0
        var index = 0
        while (index < value.length) {
            val codePoint = value.codePointAt(index)
            val text = String(Character.toChars(codePoint))
            val size = text.toByteArray(StandardCharsets.UTF_8).size
            if (segmentBytes + size > MAX_ENCODED_BYTES && segment.isNotEmpty()) {
                appendSegment(builder, segment.toString())
                segment = StringBuilder()
                segmentBytes = 0
            }
            segment.append(text)
            segmentBytes += size
            index += Character.charCount(codePoint)
        }
        if (segment.isNotEmpty()) appendSegment(builder, segment.toString())
        return builder.toString()
    }

    private fun appendSegment(builder: StringBuilder, value: String) {
        if (builder.isNotEmpty()) builder.append(' ')
        val encoded = Base64.getEncoder().encodeToString(value.toByteArray(StandardCharsets.UTF_8))
        builder.append("=?UTF-8?B?").append(encoded).append("?=")
    }

    private const val MAX_ENCODED_BYTES = 45
}
