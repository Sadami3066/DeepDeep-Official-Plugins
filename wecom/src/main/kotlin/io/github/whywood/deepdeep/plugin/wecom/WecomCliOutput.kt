package io.github.whywood.deepdeep.plugin.wecom

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

internal sealed interface WecomCliOutput {
    data class Success(val payload: JsonObject) : WecomCliOutput

    data class Failure(
        val message: String,
        val authRequired: Boolean,
    ) : WecomCliOutput
}

internal object WecomCliOutputParser {
    const val MAX_OUTPUT_LENGTH = 256 * 1024

    private val ANSI = Regex("\u001B\\[[0-9;?]*[A-Za-z]")
    private val EXIT_MARKER = Regex("\\[exit:\\s*(-?\\d+)]")
    private val NOTICE_PREFIX = Regex("(?m)^\\s*\\[wecom][^\\n]*$")
    private val KNOWN_NOISE = listOf(
        "该接口不存在，可能为接口命令错误，请更新到最新 skill 后重试",
        "Could not fetch remote schema, falling back to builtin schema",
        "failed to fetch remote discovery",
    )
    private val AUTH_HINTS = listOf(
        "AuthError",
        "auth init",
        "token invalid",
        "cli token",
        "893201",
        "853005",
        "401",
        "unauthorized",
        "未授权",
        "请先运行",
    )

    fun parse(output: String): WecomCliOutput? {
        if (output.isBlank() || output.length > MAX_OUTPUT_LENGTH) return null
        val cleaned = clean(output) ?: return null
        val extracted = extractJson(cleaned.text) ?: return null
        val root = runCatching { Json.parseToJsonElement(extracted) }.getOrNull()
        val envelope = root as? JsonObject ?: return null
        val errorElement = envelope["error"]
        if (errorElement is JsonObject) {
            val type = errorElement.string("type").orEmpty()
            val message = errorElement.string("message").orEmpty().ifBlank {
                errorElement.string("errmsg").orEmpty()
            }
            val code = errorElement.string("code").orEmpty()
            val combined = "$type $message $code"
            val authRequired = AUTH_HINTS.any { combined.contains(it, ignoreCase = true) }
            return WecomCliOutput.Failure(
                message = message.ifBlank { type.ifBlank { "企业微信命令执行失败" } },
                authRequired = authRequired,
            )
        }
        val errcode = envelope["errcode"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
        if (errcode != null && errcode != 0) {
            val errmsg = envelope["errmsg"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val authRequired = errcode == 853005 || errcode == 853004 || errcode == 893201 ||
                AUTH_HINTS.any { errmsg.contains(it, ignoreCase = true) }
            return WecomCliOutput.Failure(
                message = errmsg.ifBlank { "企业微信接口返回错误码 $errcode" },
                authRequired = authRequired,
            )
        }
        if (cleaned.exitCode != null && cleaned.exitCode != 0) return null
        val payload = unwrap(envelope) ?: return null
        return WecomCliOutput.Success(payload)
    }

    private data class Cleaned(val text: String, val exitCode: Int?)

    private fun clean(output: String): Cleaned? {
        var text = output
            .replace("\uFEFF", "")
            .replace(ANSI, "")
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace("\u0000", "")
        var exitCode: Int? = null
        val markers = EXIT_MARKER.findAll(text).toList()
        if (markers.isNotEmpty()) {
            val last = markers.last()
            exitCode = last.groupValues.getOrNull(1)?.toIntOrNull()
            text = text.removeRange(last.range)
        }
        text = NOTICE_PREFIX.replace(text, "")
        KNOWN_NOISE.forEach { noise -> text = text.replace(noise, "") }
        if (text.isBlank()) return null
        return Cleaned(text, exitCode)
    }

    private fun extractJson(text: String): String? {
        val start = text.indexOf('{')
        if (start < 0) return null
        val end = findObjectEnd(text, start) ?: return null
        return text.substring(start, end + 1)
    }

    private fun findObjectEnd(text: String, start: Int): Int? {
        var depth = 0
        var inString = false
        var escaped = false
        var index = start
        while (index < text.length) {
            val character = text[index]
            if (inString) {
                when {
                    escaped -> escaped = false
                    character == '\\' -> escaped = true
                    character == '"' -> inString = false
                }
            } else {
                when (character) {
                    '"' -> inString = true
                    '{', '[' -> depth++
                    '}', ']' -> {
                        depth--
                        if (depth == 0) return index
                        if (depth < 0) return null
                    }
                }
            }
            index++
        }
        return null
    }

    private fun unwrap(envelope: JsonObject): JsonObject? {
        if (!envelope.keys.all { it in ENVELOPE_KEYS }) return envelope
        if (!envelope.containsKey("errcode") && !envelope.containsKey("results_json") && !envelope.containsKey("result")) {
            return envelope
        }
        val result = envelope["result"]
        if (result is JsonObject) return result
        if (result is JsonPrimitive && result.isString) return parseNested(result.contentOrNull)
        val resultsJson = envelope["results_json"]
        if (resultsJson is JsonPrimitive && resultsJson.isString) {
            val nested = parseNested(resultsJson.contentOrNull) ?: return null
            val inner = nested["result"]
            if (inner is JsonObject) return inner
            if (inner is JsonPrimitive && inner.isString) return parseNested(inner.contentOrNull)
            return nested
        }
        return envelope
    }

    private fun parseNested(value: String?): JsonObject? {
        if (value.isNullOrBlank()) return null
        return runCatching { Json.parseToJsonElement(value) }.getOrNull() as? JsonObject
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private val ENVELOPE_KEYS = setOf(
        "errcode",
        "errmsg",
        "result",
        "results_json",
        "error",
        "security_notice",
    )
}

internal fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

internal fun JsonObject.intOrNull(key: String): Int? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()

internal fun JsonObject.booleanOrNull(key: String): Boolean? = when (val value = (this[key] as? JsonPrimitive)?.contentOrNull) {
    "true" -> true
    "false" -> false
    else -> null
}

internal fun JsonObject.objectOrNull(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.arrayOrNull(key: String): JsonArray? = this[key] as? JsonArray
