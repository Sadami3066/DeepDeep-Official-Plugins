package io.github.whywood.deepdeep.plugin.cli

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

fun JsonObject.objValue(name: String): JsonObject? =
    runCatching { (this[name] as? JsonObject) ?: this[name]?.jsonObject }.getOrNull()

fun JsonObject.arrayValue(name: String): List<JsonElement> =
    runCatching { (this[name] as? JsonArray)?.toList() ?: this[name]?.jsonArray?.toList() }.getOrNull().orEmpty()

fun JsonObject.stringValue(name: String): String? =
    (this[name] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

fun JsonObject.intValue(name: String): Int? = (this[name] as? JsonPrimitive)?.intOrNull

fun JsonObject.longValue(name: String): Long? = (this[name] as? JsonPrimitive)?.longOrNull

fun JsonObject.boolValue(name: String): Boolean? = (this[name] as? JsonPrimitive)?.booleanOrNull

fun JsonObject.objectList(name: String): List<JsonObject> =
    arrayValue(name).mapNotNull { element -> runCatching { element.jsonObject }.getOrNull() }
