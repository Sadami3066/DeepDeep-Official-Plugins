package io.github.whywood.deepdeep.plugin.ride

import io.github.whywood.deepdeep.plugin.api.PluginHost
import io.github.whywood.deepdeep.plugin.api.localizedText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal fun PluginHost.t(zh: String, en: String): String =
    text(localizedText(mapOf("default" to zh, "zh" to zh, "en" to en)))

internal fun PluginHost.tf(zh: String, en: String, vararg args: Any): String =
    text(localizedText(mapOf("default" to zh, "zh" to zh, "en" to en)), *args)

private val RideJson = Json { ignoreUnknownKeys = true; isLenient = true }

internal data class RideEstimateItem(
    val productName: String,
    val priceText: String?,
    val priceDiscounted: String?,
)

internal data class RideEstimate(
    val fromName: String?,
    val toName: String?,
    val items: List<RideEstimateItem>,
) {
    val route: String
        get() = listOfNotNull(fromName, toName).joinToString(" → ")

    val isEmpty: Boolean
        get() = items.isEmpty()
}

internal data class RideLink(
    val appLink: String?,
    val miniprogramLink: String?,
) {
    val isEmpty: Boolean
        get() = appLink.isNullOrBlank() && miniprogramLink.isNullOrBlank()
}

internal data class RideOrder(
    val status: String?,
    val driver: String?,
    val phone: String?,
    val plate: String?,
    val eta: String?,
) {
    val isEmpty: Boolean
        get() = listOf(status, driver, phone, plate, eta).all { it.isNullOrBlank() }
}

internal data class RideDriverLocation(
    val latitude: String?,
    val longitude: String?,
    val summary: String?,
) {
    val isEmpty: Boolean
        get() = listOf(latitude, longitude, summary).all { it.isNullOrBlank() }

    val coordinates: String?
        get() = if (latitude.isNullOrBlank() || longitude.isNullOrBlank()) {
            null
        } else {
            "$latitude, $longitude"
        }
}

internal data class RideCancelResult(
    val cancelled: Boolean?,
    val message: String?,
) {
    val isEmpty: Boolean
        get() = cancelled == null && message.isNullOrBlank()
}

internal fun parseDidiPayload(text: String?): JsonElement? {
    val raw = text?.trim().orEmpty()
    if (raw.isEmpty()) return null
    val start = raw.indexOfFirst { it == '{' || it == '[' }
    if (start < 0) return null
    val end = raw.indexOfLast { it == '}' || it == ']' }
    if (end <= start) return null
    return runCatching { RideJson.parseToJsonElement(raw.substring(start, end + 1)) }.getOrNull()
}

internal fun unwrapRidePayload(element: JsonElement?): JsonObject? {
    val objectValue = when (element) {
        is JsonObject -> element
        is JsonArray -> return null
        else -> return null
    }
    val nested = objectValue["data"] as? JsonObject
        ?: objectValue["result"] as? JsonObject
    return nested ?: objectValue
}

internal fun parseRideEstimate(root: JsonObject?, fromName: String?, toName: String?): RideEstimate {
    val source = root?.let { unwrapRidePayload(it) }
    val items = source?.let { findItemsArray(it) }
        ?.mapNotNull { it as? JsonObject }
        ?.map { item ->
            RideEstimateItem(
                productName = item.stringValue("productName", "product_name", "name") ?: "",
                priceText = item.stringValue("priceText", "price_text", "price", "originalPrice"),
                priceDiscounted = item.stringValue(
                    "priceDiscounted",
                    "price_discounted",
                    "discountedPrice",
                    "discountPrice",
                ),
            )
        }
        .orEmpty()
        .filter { it.productName.isNotBlank() }
    return RideEstimate(fromName = fromName?.takeIf { it.isNotBlank() }, toName = toName?.takeIf { it.isNotBlank() }, items = items)
}

private fun findItemsArray(root: JsonObject): JsonArray? {
    val keys = listOf("items", "productList", "products", "estimates", "options", "list")
    keys.forEach { key ->
        (root[key] as? JsonArray)?.let { return it }
    }
    return null
}

internal fun parseRideLink(root: JsonObject?): RideLink {
    val source = root?.let { unwrapRidePayload(it) }
    return RideLink(
        appLink = source?.stringValue("appLink", "app_link", "deeplink"),
        miniprogramLink = source?.stringValue(
            "miniprogramLink",
            "miniprogram_link",
            "miniProgramLink",
            "mpLink",
        ),
    )
}

internal fun parseRideOrder(root: JsonObject?): RideOrder {
    val source = root?.let { unwrapRidePayload(it) }
    return RideOrder(
        status = source?.stringValue("status", "orderStatus", "order_status", "state"),
        driver = source?.stringValue("driverName", "driver_name", "driver"),
        phone = source?.stringValue("driverPhone", "driver_phone", "phone", "mobile"),
        plate = source?.stringValue("plateNumber", "plate_number", "carPlate", "plate", "licensePlate"),
        eta = source?.stringValue(
            "eta",
            "estimatedArriveTime",
            "estimated_arrive_time",
            "arriveTime",
            "arrive_time",
        ),
    )
}

internal fun parseRideDriverLocation(root: JsonObject?): RideDriverLocation {
    val source = root?.let { unwrapRidePayload(it) }
    if (source == null) return RideDriverLocation(null, null, null)
    val location = source["location"] as? JsonObject
    val latitude = source.stringValue("latitude", "lat", "driverLat", "driver_lat", "driverLatitude")
        ?: location?.stringValue("latitude", "lat")
    val longitude = source.stringValue("longitude", "lng", "lon", "driverLng", "driver_lng", "driverLongitude")
        ?: location?.stringValue("longitude", "lng", "lon")
    val summary = source.stringValue(
        "address",
        "poiName",
        "poi_name",
        "locationText",
        "location_text",
        "description",
        "summary",
        "text",
    )
    return RideDriverLocation(latitude = latitude, longitude = longitude, summary = summary)
}

internal fun parseRideCancelResult(root: JsonObject?): RideCancelResult {
    val source = root?.let { unwrapRidePayload(it) }
    if (source == null) return RideCancelResult(null, null)
    val cancelled = source.booleanValue("cancelled", "canceled", "success", "ok")
    val message = source.stringValue("message", "msg", "text", "content", "status")
    return RideCancelResult(cancelled = cancelled, message = message)
}

internal data class RidePlace(
    val name: String?,
    val address: String?,
    val latitude: String?,
    val longitude: String?,
) {
    val displayName: String?
        get() = name ?: address

    val coordinates: String?
        get() = if (latitude.isNullOrBlank() || longitude.isNullOrBlank()) null else "$latitude, $longitude"

    val isEmpty: Boolean
        get() = name.isNullOrBlank() && address.isNullOrBlank() && latitude.isNullOrBlank() && longitude.isNullOrBlank()
}

internal fun parseRidePlace(item: JsonObject): RidePlace {
    val name = item.stringValue(
        "display_name",
        "displayName",
        "name",
        "poiName",
        "poi_name",
        "title",
    )
    val address = item.stringValue(
        "address_all",
        "addressAll",
        "address",
        "addr",
        "addressName",
        "description",
    )
    val location = item["location"] as? JsonObject
    val latitude = item.stringValue("lat", "latitude") ?: location?.stringValue("lat", "latitude")
    val longitude = item.stringValue("lng", "lon", "longitude") ?: location?.stringValue("lng", "lon", "longitude")
    return RidePlace(name = name, address = address, latitude = latitude, longitude = longitude)
}

internal fun parseRidePlaces(element: JsonElement?): List<RidePlace> {
    val array = when (element) {
        is JsonArray -> element
        is JsonObject -> {
            val source = unwrapRidePayload(element) ?: element
            listOf("items", "places", "poiList", "list").firstNotNullOfOrNull { key ->
                source[key] as? JsonArray
            }
        }
        else -> null
    }.orEmpty()
    return array.mapNotNull { it as? JsonObject }.map(::parseRidePlace).filterNot { it.isEmpty }
}

internal fun parseDidiToolsList(body: String?): Boolean {
    val envelope = parseDidiPayload(body) as? JsonObject ?: return false
    if (envelope["error"] != null) return false
    val result = envelope["result"] as? JsonObject ?: envelope
    val tools = result["tools"] as? JsonArray ?: return false
    return tools.isNotEmpty()
}

internal fun summarizeDidiError(
    raw: String?,
    secret: String? = null,
    maxLength: Int = 120,
): String {
    var text = raw?.trim().orEmpty()
    if (text.isEmpty()) return ""
    if (!secret.isNullOrBlank()) text = text.replace(secret, "***")
    text = text.replace(Regex("(?i)\\b(key|token|secret|apikey)=[^&\\s]+"), "$1=***")
    text = text.replace(Regex("https?://\\S+"), "…")
    text = text.replace(Regex("[{}\\[\\]\"`]"), " ")
    text = text.replace(Regex("\\s+"), " ").trim()
    return if (text.length > maxLength) text.take(maxLength - 1) + "…" else text
}

internal fun JsonObject.stringValue(vararg names: String): String? {
    names.forEach { name ->
        val value = (this[name] as? JsonPrimitive)?.contentOrNull?.trim()
        if (!value.isNullOrEmpty()) return value
    }
    return null
}

internal fun JsonObject.booleanValue(vararg names: String): Boolean? {
    names.forEach { name ->
        val primitive = this[name] as? JsonPrimitive ?: return@forEach
        val text = primitive.contentOrNull?.trim()?.lowercase() ?: return@forEach
        when (text) {
            "true", "1", "yes", "ok", "success", "succeeded", "cancelled", "canceled" -> return true
            "false", "0", "no", "fail", "failed", "error" -> return false
        }
    }
    return null
}
