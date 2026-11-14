package io.github.whywood.deepdeep.plugin.hotel

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

internal data class HotelItem(
    val name: String,
    val star: String?,
    val price: String?,
    val address: String?,
    val distance: String?,
    val tags: List<String>,
)

internal data class RoomItem(
    val name: String,
    val price: String?,
    val policy: String?,
)

internal object HotelPayload {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(element: JsonElement?): JsonElement? = element

    fun parseText(text: String?): JsonElement? {
        val raw = text?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val start = raw.indexOfFirst { it == '{' || it == '[' }
        if (start < 0) return null
        val end = raw.indexOfLast { it == '}' || it == ']' }
        if (end <= start) return null
        return runCatching { json.parseToJsonElement(raw.substring(start, end + 1)) }.getOrNull()
    }

    fun hotels(root: JsonElement?): List<HotelItem> {
        val array = findArray(root, HOTEL_KEYS) ?: return emptyList()
        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val name = text(obj, "name", "hotelName", "hotel_name", "title") ?: return@mapNotNull null
            HotelItem(
                name = name,
                star = text(obj, "star", "starRating", "starLevel", "stars", "rating"),
                price = price(obj),
                address = text(obj, "address", "addressName", "addressText", "location"),
                distance = text(obj, "distance", "distanceText", "distanceInMeter"),
                tags = texts(obj, "tags", "tagList", "labels", "features").take(4),
            )
        }
    }

    fun rooms(root: JsonElement?): List<RoomItem> {
        val array = findArray(root, ROOM_KEYS) ?: return emptyList()
        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val name = text(obj, "name", "roomName", "room_name", "title") ?: return@mapNotNull null
            RoomItem(
                name = name,
                price = price(obj),
                policy = text(obj, "cancelPolicy", "cancelPolicyText", "policy", "refundPolicy"),
            )
        }
    }

    fun hotelName(root: JsonElement?): String? {
        val obj = root as? JsonObject ?: return null
        return text(obj, "hotelName", "hotel_name", "name")
    }

    private fun price(obj: JsonObject): String? {
        raw(obj, "price", "lowestPrice", "minPrice", "pricePerNight", "lowestPricePerNight", "amount")
            ?.let { return formatPrice(it) }
        val nested = listOf("priceInfo", "priceDetail", "lowestPriceInfo")
            .mapNotNull { obj[it] as? JsonObject }
            .firstOrNull { it.isNotEmpty() }
            ?: return null
        return raw(nested, "price", "amount", "value", "totalPrice", "lowestPrice")?.let(::formatPrice)
    }

    private fun formatPrice(value: JsonElement): String {
        val text = (value as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        if (text.isEmpty()) return ""
        if (text.startsWith("¥") || text.contains("元")) return text
        return "¥$text"
    }

    private fun findArray(root: JsonElement?, keys: Set<String>): JsonArray? {
        if (root == null || root is JsonNull) return null
        if (root is JsonArray) {
            if (root.any { it is JsonObject && (it["name"] != null || it["hotelName"] != null || it["roomName"] != null) }) return root
            root.forEach { child -> findArray(child, keys)?.let { return it } }
            return null
        }
        val obj = root as? JsonObject ?: return null
        keys.forEach { key ->
            obj.entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value?.let { value ->
                if (value is JsonArray) return value
                findArray(value, keys)?.let { return it }
            }
        }
        obj.values.forEach { child -> findArray(child, keys)?.let { return it } }
        return null
    }

    private fun raw(obj: JsonObject, vararg keys: String): JsonElement? {
        keys.forEach { key ->
            obj.entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value
                ?.takeIf { it !is JsonNull && (it !is JsonPrimitive || it.contentOrNull?.isNotBlank() == true) }
                ?.let { return it }
        }
        return null
    }

    private fun text(obj: JsonObject, vararg keys: String): String? {
        keys.forEach { key ->
            val value = obj.entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value ?: return@forEach
            when (value) {
                is JsonPrimitive -> value.contentOrNull?.takeIf { it.isNotBlank() }?.let { return it }
                is JsonObject -> (value["text"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.let { return it }
                else -> Unit
            }
        }
        return null
    }

    private fun texts(obj: JsonObject, vararg keys: String): List<String> {
        keys.forEach { key ->
            val value = obj.entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value ?: return@forEach
            if (value is JsonArray) {
                return value.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf { s -> s.isNotBlank() } }
            }
        }
        return emptyList()
    }

    private val HOTEL_KEYS = setOf("hotels", "hotelList", "hotel_list", "list", "items", "results", "data")
    private val ROOM_KEYS = setOf("rooms", "roomList", "room_list", "roomTypes", "roomTypesList", "data")
}
