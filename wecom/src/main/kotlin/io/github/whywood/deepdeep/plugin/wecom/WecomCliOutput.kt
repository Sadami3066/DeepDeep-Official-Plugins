package io.github.whywood.deepdeep.plugin.wecom

import io.github.whywood.deepdeep.plugin.cli.CliJsonEnvelope
import io.github.whywood.deepdeep.plugin.cli.CliText
import kotlinx.serialization.json.Json
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
    const val MAX_OUTPUT_LENGTH = 4 * 1024 * 1024

    private val ENVELOPE = CliJsonEnvelope.LENIENT
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
        val cleaned = clean(output) ?: return null
        val envelope = ENVELOPE.find(cleaned.text) ?: return null
        failureOf(envelope)?.let { return it }
        if (cleaned.exitCode != null && cleaned.exitCode != 0) return null
        return payload(envelope)?.let { WecomCliOutput.Success(it) }
    }

    fun payload(envelope: JsonObject): JsonObject? = unwrap(envelope)

    fun payloadElement(output: String): kotlinx.serialization.json.JsonElement? {
        val cleaned = clean(output) ?: return null
        return ENVELOPE.element(cleaned.text)
    }

    fun exitCode(output: String): Int? =
        EXIT_MARKER.findAll(CliText.stripAnsi(output)).lastOrNull()?.groupValues?.getOrNull(1)?.toIntOrNull()

    fun exitMarker(output: String): String? =
        EXIT_MARKER.findAll(CliText.stripAnsi(output)).lastOrNull()?.value

    private data class Cleaned(val text: String, val exitCode: Int?)

    private fun clean(output: String): Cleaned? {
        if (output.isBlank() || output.length > MAX_OUTPUT_LENGTH) return null
        var text = CliText.stripAnsi(output)
            .replace("\uFEFF", "")
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

    private fun failureOf(envelope: JsonObject): WecomCliOutput.Failure? {
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
        return null
    }

    private fun unwrap(envelope: JsonObject): JsonObject? {
        if (!envelope.keys.all { it in ENVELOPE_KEYS }) return envelope
        if (!envelope.containsKey("errcode") && !envelope.containsKey("results_json") && !envelope.containsKey("result")) {
            return envelope
        }
        val result = envelope["result"]
        if (result is JsonObject) return withEnvelopeFields(envelope, result)
        if (result is JsonPrimitive && result.isString) {
            return withEnvelopeFields(envelope, parseNested(result.contentOrNull))
        }
        val resultsJson = envelope["results_json"]
        if (resultsJson is JsonPrimitive && resultsJson.isString) {
            val nested = parseNested(resultsJson.contentOrNull) ?: return null
            val inner = nested["result"]
            if (inner is JsonObject) return withEnvelopeFields(envelope, inner)
            if (inner is JsonPrimitive && inner.isString) {
                return withEnvelopeFields(envelope, parseNested(inner.contentOrNull))
            }
            return withEnvelopeFields(envelope, nested)
        }
        return envelope
    }

    private fun withEnvelopeFields(envelope: JsonObject, payload: JsonObject?): JsonObject? {
        if (payload == null) return null
        val merged = payload.toMutableMap()
        ENVELOPE_FIELDS.forEach { key ->
            if (key !in merged) envelope[key]?.let { merged[key] = it }
        }
        return JsonObject(merged)
    }

    private fun parseNested(value: String?): JsonObject? {
        if (value.isNullOrBlank()) return null
        return runCatching { Json.parseToJsonElement(value) }.getOrNull() as? JsonObject
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private val ENVELOPE_FIELDS = listOf("security_notice", "errcode", "errmsg")

    private val ENVELOPE_KEYS = setOf(
        "errcode",
        "errmsg",
        "result",
        "results_json",
        "error",
        "security_notice",
    )
}
