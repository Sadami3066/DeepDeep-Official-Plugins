package io.github.whywood.superpi.plugin.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject

class CliJsonEnvelope(
    val successField: String? = "ok",
    private val noticeField: String = "_notice",
) {
    fun element(output: String): JsonElement? {
        val cleaned = cleaned(output) ?: return null
        var index = 0
        while (index < cleaned.length) {
            val char = cleaned[index]
            if (char == '{' || char == '[') {
                val end = matchBalanced(cleaned, index)
                if (end > index) {
                    val candidate = cleaned.substring(index, end + 1)
                    runCatching { Json.parseToJsonElement(candidate) }.getOrNull()?.let { return it }
                    index = end + 1
                    continue
                }
            }
            index++
        }
        return null
    }

    fun find(output: String): JsonObject? {
        val cleaned = cleaned(output) ?: return null
        val field = successField
        var start = cleaned.indexOf('{')
        while (start >= 0) {
            val end = matchBalanced(cleaned, start)
            if (end > start) {
                val candidate = cleaned.substring(start, end + 1)
                runCatching { Json.parseToJsonElement(candidate).jsonObject }.getOrNull()?.let { root ->
                    val known: Boolean? = if (field == null) {
                        true
                    } else {
                        (root[field] as? JsonPrimitive)?.booleanOrNull
                    }
                    if (known != null) return root
                }
            }
            start = cleaned.indexOf('{', start + 1)
        }
        return null
    }

    fun isSuccess(envelope: JsonObject): Boolean {
        val field = successField ?: return true
        return (envelope[field] as? JsonPrimitive)?.booleanOrNull == true
    }

    fun stripNotice(envelope: JsonObject): JsonObject =
        if (envelope[noticeField] == null) envelope else JsonObject(envelope.filterKeys { it != noticeField })

    fun hasNotice(envelope: JsonObject): Boolean = envelope[noticeField] != null

    private fun cleaned(output: String): String? {
        val stripped = CliText.stripAnsi(output)
        if (stripped.isBlank()) return null
        val trimmed = buildString {
            stripped.lineSequence().forEach { line ->
                if (CliText.isDotenvLine(line)) return@forEach
                appendLine(line)
            }
        }.trim()
        return trimmed.ifEmpty { null }
    }

    private fun matchBalanced(text: String, start: Int): Int {
        val expected = ArrayDeque<Char>()
        var inString = false
        var escaped = false
        var index = start
        while (index < text.length) {
            val char = text[index]
            if (inString) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> inString = false
                }
            } else {
                when (char) {
                    '"' -> inString = true
                    '{' -> expected.addLast('}')
                    '[' -> expected.addLast(']')
                    '}', ']' -> {
                        if (expected.isEmpty() || expected.last() != char) return -1
                        expected.removeLast()
                        if (expected.isEmpty()) return index
                    }
                }
            }
            index++
        }
        return -1
    }

    companion object {
        val DEFAULT: CliJsonEnvelope = CliJsonEnvelope()

        val LENIENT: CliJsonEnvelope = CliJsonEnvelope(successField = null)
    }
}
