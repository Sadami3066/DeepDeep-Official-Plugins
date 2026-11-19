package io.github.whywood.superpi.plugin.micloud

import io.github.whywood.superpi.plugin.api.Cards
import io.github.whywood.superpi.plugin.api.PluginToolResult
import io.github.whywood.superpi.plugin.api.textResult
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

internal const val DEFAULT_LIST_LIMIT = 20

internal const val MAX_LIST_LIMIT = 200

internal const val SUPERPI_RAW_KEY = Cards.RAW_KEY

internal const val SUPERPI_GUIDANCE_KEY = Cards.GUIDANCE_KEY

internal const val SUPERPI_CARD_KEY = Cards.KEY

internal data class MiCloudResponse(
    val body: String,
    val json: JsonElement,
)

internal data class MiCloudCard(
    val title: String,
    val payload: JsonObject,
)

internal interface MiCloudReadApi {
    suspend fun userId(): String?

    suspend fun getJson(pathWithQuery: String): MiCloudResponse

    suspend fun download(pathWithQuery: String, dest: File): File
}

internal interface MiCloudWriteApi {
    suspend fun post(path: String, form: Map<String, String>): String
}

internal suspend fun requiredUserId(client: MiCloudReadApi): String =
    client.userId() ?: throw MiCloudAuthException("userId is missing, sign in again")

internal fun requireWrite(settings: MiCloudSettings, feature: MiCloudFeature, message: String) {
    if (!settings.capabilities.value.writeEnabled(feature)) {
        throw IllegalArgumentException(message)
    }
}

internal suspend fun miCloudToolResult(
    feature: MiCloudFeature,
    text: MiCloudText,
    block: suspend () -> PluginToolResult,
): PluginToolResult = try {
    block()
} catch (error: CancellationException) {
    throw error
} catch (error: MiCloudAuthException) {
    toolErrorResult(feature, error, text)
} catch (error: IllegalArgumentException) {
    toolErrorResult(feature, error, text)
} catch (error: IOException) {
    toolErrorResult(feature, error, text)
} catch (error: Exception) {
    toolErrorResult(feature, error, text)
}

private fun toolErrorResult(
    feature: MiCloudFeature,
    error: Exception,
    text: MiCloudText,
): PluginToolResult {
    val raw = when (error) {
        is MiCloudHttpException -> error.body.ifBlank { error.message.orEmpty() }
        is IllegalArgumentException -> ""
        else -> error.message.orEmpty()
    }
    return textResult(
        text = "",
        details = toolResultDetails(
            raw = raw,
            guidance = requestFailureGuidance(feature, error, text),
        ),
        isError = true,
    )
}

internal fun toolResultDetails(
    raw: String = "",
    guidance: String = "",
    card: MiCloudCard? = null,
): JsonObject = buildJsonObject {
    if (raw.isNotBlank()) put(SUPERPI_RAW_KEY, raw)
    if (guidance.isNotBlank()) put(SUPERPI_GUIDANCE_KEY, guidance)
    card?.let { value ->
        putJsonObject(SUPERPI_CARD_KEY) {
            put("title", value.title)
            put("payload", value.payload)
        }
    }
}

internal fun cardPayload(details: JsonObject?): JsonObject? {
    val descriptor = details?.get(SUPERPI_CARD_KEY) as? JsonObject
    val payload = descriptor?.get("payload") as? JsonObject
    return payload ?: details
}

internal fun now(): Long = System.currentTimeMillis()

internal fun JsonElement.payloadElement(): JsonElement {
    val root = this as? JsonObject ?: return this
    val data = root["data"] ?: root["D"]
    return if (data != null && data !is JsonNull) data else this
}

internal fun JsonElement.payloadObject(): JsonObject? = payloadElement() as? JsonObject

internal fun JsonElement.payloadArray(vararg keys: String): JsonArray {
    val element = payloadElement()
    if (element is JsonArray) return element
    val obj = element as? JsonObject ?: return JsonArray(emptyList())
    keys.forEach { key -> (obj[key] as? JsonArray)?.let { return it } }
    return JsonArray(emptyList())
}

internal fun JsonObject?.objectOrNull(key: String): JsonObject? = (this?.get(key) as? JsonObject)

internal fun JsonObject?.arrayOrNull(key: String): JsonArray? = (this?.get(key) as? JsonArray)

internal fun JsonObject?.stringOrNull(key: String): String? =
    (this?.get(key) as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

internal fun JsonObject?.longOrNull(key: String): Long? =
    stringOrNull(key)?.toDoubleOrNull()?.toLong()

internal fun JsonObject?.intOrNull(key: String): Int? =
    stringOrNull(key)?.toDoubleOrNull()?.toInt()

internal fun JsonObject?.boolOrNull(key: String): Boolean? = when (stringOrNull(key)) {
    "true", "1" -> true
    "false", "0" -> false
    else -> null
}

internal fun JsonObject.argString(name: String): String? =
    stringOrNull(name)?.trim()?.takeIf { it.isNotEmpty() }

internal fun JsonObject.requiredArgString(name: String): String =
    argString(name) ?: throw IllegalArgumentException("Missing required argument: $name")

internal fun JsonObject.argInt(name: String): Int? =
    (this[name] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.toInt()

internal fun JsonObject.argLong(name: String): Long? =
    (this[name] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.toLong()

internal fun JsonObject.argBool(name: String): Boolean? =
    (this[name] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()

internal fun limitArg(args: JsonObject): Int {
    if (!args.containsKey("limit")) return DEFAULT_LIST_LIMIT
    val limit = args.argInt("limit")
        ?: throw IllegalArgumentException("limit must be an integer")
    if (limit !in 1..MAX_LIST_LIMIT) {
        throw IllegalArgumentException("limit must be between 1 and $MAX_LIST_LIMIT")
    }
    return limit
}

internal fun parseResponseObject(body: String): JsonObject? {
    val trimmed = body.trim()
    if (trimmed.isEmpty()) return null
    val parsed = runCatching { Json.parseToJsonElement(trimmed) }.getOrNull() ?: return null
    return parsed.payloadObject() ?: parsed as? JsonObject
}

internal fun responseId(response: JsonObject?): String {
    val direct = response?.stringOrNull("id")
    if (!direct.isNullOrBlank() && direct != "0") return direct
    val nestedKeys = listOf("entry", "record", "data", "item", "result")
    nestedKeys.forEach { key ->
        val nested = response?.objectOrNull(key) ?: return@forEach
        val id = nested.stringOrNull("id")
        if (!id.isNullOrBlank() && id != "0") return id
    }
    return ""
}

internal fun cursorValue(raw: String?): String =
    raw?.trim()?.takeUnless { it.isEmpty() || it == "0" }.orEmpty()

internal fun cursorValue(element: JsonElement?): String = when (element) {
    null -> ""
    is JsonPrimitive -> cursorValue(element.contentOrNull)
    is JsonObject, is JsonArray -> element.toString().takeUnless { it == "null" || it == "{}" }.orEmpty()
}

internal fun encodeComponent(value: String): String = URLEncoder.encode(value, "UTF-8")

internal fun toolParams(
    required: List<String> = emptyList(),
    block: JsonObjectBuilder.() -> Unit,
): JsonObject = buildJsonObject {
    put("type", "object")
    putJsonObject("properties", block)
    if (required.isNotEmpty()) {
        putJsonArray("required") {
            required.forEach { name -> add(JsonPrimitive(name)) }
        }
    }
}

internal fun propString(description: String): JsonObject = buildJsonObject {
    put("type", "string")
    put("description", description)
}

internal fun propInt(
    description: String,
    minimum: Int? = null,
    maximum: Int? = null,
    default: Int? = null,
): JsonObject = buildJsonObject {
    put("type", "integer")
    put("description", description)
    minimum?.let { put("minimum", it) }
    maximum?.let { put("maximum", it) }
    default?.let { put("default", it) }
}

internal fun propLong(description: String): JsonObject = buildJsonObject {
    put("type", "integer")
    put("description", description)
}

internal fun propBool(description: String, default: Boolean? = null): JsonObject = buildJsonObject {
    put("type", "boolean")
    put("description", description)
    default?.let { put("default", it) }
}

internal fun propEnum(description: String, values: List<String>): JsonObject = buildJsonObject {
    put("type", "string")
    put("description", description)
    putJsonArray("enum") {
        values.forEach { value -> add(JsonPrimitive(value)) }
    }
}
