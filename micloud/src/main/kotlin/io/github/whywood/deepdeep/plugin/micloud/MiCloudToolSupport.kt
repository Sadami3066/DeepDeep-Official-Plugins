package io.github.whywood.deepdeep.plugin.micloud

import io.github.whywood.deepdeep.plugin.api.PluginToolResult
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

internal const val MAX_RESULT_CHARS = 12_000

internal const val MAX_BODY_CHARS = 4_000

internal const val COMPACT_MAX_DEPTH = 3

internal const val COMPACT_MAX_ARRAY = 12

internal const val TOOL_AUTH_ERROR =
    "Mi Cloud login expired; open the Mi Cloud plugin page in DeepDeep and sign in again"

internal const val TOOL_REQUEST_FAILED = "Mi Cloud request failed: "

internal interface MiCloudReadApi {
    suspend fun userId(): String?

    suspend fun getJson(pathWithQuery: String): JsonElement

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

internal suspend fun miCloudToolResult(block: suspend () -> PluginToolResult): PluginToolResult = try {
    block()
} catch (error: CancellationException) {
    throw error
} catch (error: MiCloudAuthException) {
    textResult(TOOL_AUTH_ERROR, isError = true)
} catch (error: IllegalArgumentException) {
    textResult(error.message ?: "Invalid argument", isError = true)
} catch (error: IOException) {
    textResult(TOOL_REQUEST_FAILED + error.message.orEmpty(), isError = true)
} catch (error: Exception) {
    textResult(TOOL_REQUEST_FAILED + (error.message ?: error.javaClass.simpleName), isError = true)
}

internal fun textResult(text: String, isError: Boolean = false): PluginToolResult =
    PluginToolResult.Text(text, isError = isError)

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

internal fun itemsResult(items: List<JsonObject>, extra: JsonObject = JsonObject(emptyMap())): PluginToolResult {
    val kept = mutableListOf<JsonObject>()
    var chars = 0
    for (item in items) {
        val size = item.toString().length + 2
        if (kept.isNotEmpty() && chars + size > MAX_RESULT_CHARS) break
        kept += item
        chars += size
    }
    val result = buildJsonObject {
        put("count", kept.size)
        if (kept.size < items.size) put("truncated", true)
        putJsonArray("items") {
            kept.forEach { item -> add(item) }
        }
        extra.forEach { (key, value) -> put(key, value) }
    }
    return textResult(result.toString())
}

internal fun writeResult(body: String): PluginToolResult {
    val trimmed = body.trim()
    val parsed = runCatching { Json.parseToJsonElement(trimmed) }.getOrNull()
    val text = if (parsed != null) {
        compactJson(parsed.payloadObject() ?: parsed).toString()
    } else {
        trimmed
    }
    return textResult(text.take(MAX_BODY_CHARS))
}

internal fun compactPayload(element: JsonElement): String =
    compactJson(element.payloadObject() ?: element).toString()

internal fun compactObject(element: JsonObject): JsonObject =
    compactJson(element) as? JsonObject ?: JsonObject(emptyMap())

internal fun compactJson(element: JsonElement, depth: Int = 0): JsonElement = when (element) {
    is JsonObject -> JsonObject(
        element.mapValues { (_, value) ->
            if (depth >= COMPACT_MAX_DEPTH) {
                if (value is JsonPrimitive) value else JsonPrimitive("[...]")
            } else {
                compactJson(value, depth + 1)
            }
        },
    )

    is JsonArray -> JsonArray(
        element.take(COMPACT_MAX_ARRAY).map { item -> compactJson(item, depth + 1) },
    )

    else -> element
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
