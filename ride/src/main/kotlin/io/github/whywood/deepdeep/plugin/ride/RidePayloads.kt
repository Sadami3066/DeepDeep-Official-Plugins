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

internal typealias RideTranslate = (String, String) -> String

private val RideJson = Json { ignoreUnknownKeys = true; isLenient = true }

internal data class RideEstimateItem(
    val productName: String,
    val priceText: String?,
    val priceDiscounted: String?,
    val productCategory: String? = null,
    val eta: String? = null,
    val distance: String? = null,
    val duration: String? = null,
)

internal data class RideEstimate(
    val fromName: String?,
    val toName: String?,
    val items: List<RideEstimateItem>,
    val traceId: String? = null,
    val eta: String? = null,
    val distance: String? = null,
    val duration: String? = null,
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
    val statusCode: String? = null,
    val statusText: String? = null,
    val driver: String?,
    val carModel: String? = null,
    val phone: String?,
    val plate: String?,
    val eta: String?,
    val distanceKm: String? = null,
    val phase: String? = null,
    val orderId: String? = null,
    val fromName: String? = null,
    val toName: String? = null,
    val phoneSuffix: String? = null,
) {
    val isEmpty: Boolean
        get() = listOf(
            status,
            statusCode,
            statusText,
            driver,
            carModel,
            phone,
            plate,
            eta,
            distanceKm,
            phase,
            orderId,
            fromName,
            toName,
            phoneSuffix,
        ).all { it.isNullOrBlank() }

    val route: String
        get() = listOfNotNull(fromName, toName).joinToString(" → ")
}

internal data class RideDriverLocation(
    val latitude: String?,
    val longitude: String?,
    val summary: String?,
) {
    val isEmpty: Boolean
        get() = listOf(latitude, longitude, summary).all { it.isNullOrBlank() }

    val coordinates: String?
        get() = if (latitude.isNullOrBlank() || longitude.isNullOrBlank()) null else "$longitude, $latitude"
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
    val map = source?.get("map") as? JsonObject
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
                productCategory = item.stringValue("productCategory", "product_category", "category"),
                eta = item.stringValue(*RIDE_ETA_KEYS),
                distance = item.stringValue(*RIDE_DISTANCE_KEYS),
                duration = item.stringValue(*RIDE_DURATION_KEYS),
            )
        }
        .orEmpty()
        .filter { it.productName.isNotBlank() }
    return RideEstimate(
        fromName = fromName?.takeIf { it.isNotBlank() },
        toName = toName?.takeIf { it.isNotBlank() },
        items = items,
        traceId = source?.stringValue("traceId", "trace_id", "estimateTraceId", "estimate_trace_id")
            ?: map?.stringValue("traceId", "trace_id"),
        eta = source?.stringValue(*RIDE_ETA_KEYS) ?: map?.stringValue(*RIDE_ETA_KEYS),
        distance = source?.stringValue(*RIDE_DISTANCE_KEYS) ?: map?.stringValue(*RIDE_DISTANCE_KEYS),
        duration = source?.stringValue(*RIDE_DURATION_KEYS) ?: map?.stringValue(*RIDE_DURATION_KEYS),
    )
}

private val RIDE_ETA_KEYS = arrayOf(
    "eta",
    "etaText",
    "eta_text",
    "estimatedArriveTime",
    "estimated_arrive_time",
    "arriveTime",
    "arrive_time",
    "pickupEta",
    "pickup_eta",
    "waitTime",
    "wait_time",
)

private val RIDE_DISTANCE_KEYS = arrayOf(
    "distance",
    "distanceText",
    "distance_text",
    "distanceKm",
    "distance_km",
    "routeDistance",
    "route_distance",
)

private val RIDE_DURATION_KEYS = arrayOf(
    "duration",
    "durationText",
    "duration_text",
    "durationMin",
    "duration_min",
    "durationMinutes",
    "duration_minutes",
    "estimatedDuration",
    "estimated_duration",
    "travelTime",
    "travel_time",
)

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
    val from = source?.get("from") as? JsonObject
    val to = source?.get("to") as? JsonObject
    val driver = source?.get("driver") as? JsonObject
    val map = source?.get("map") as? JsonObject
    return RideOrder(
        status = source?.stringValue("status", "statusText", "orderStatus", "order_status", "state"),
        statusCode = source?.stringValue("statusCode", "status_code"),
        statusText = source?.stringValue("statusText", "status_text"),
        driver = source?.stringValue("driverName", "driver_name", "driver")
            ?: driver?.stringValue("name", "driverName"),
        carModel = source?.stringValue("carModel", "car_model")
            ?: driver?.stringValue("carModel", "car_model"),
        phone = source?.stringValue("driverPhone", "driver_phone", "phone", "mobile")
            ?: driver?.stringValue("phone", "mobile"),
        plate = source?.stringValue("plateNumber", "plate_number", "carPlate", "plate", "licensePlate")
            ?: driver?.stringValue("carPlate", "plate", "licensePlate"),
        eta = source?.stringValue(*RIDE_ETA_KEYS) ?: map?.stringValue(*RIDE_ETA_KEYS),
        distanceKm = source?.stringValue(*RIDE_DISTANCE_KEYS) ?: map?.stringValue(*RIDE_DISTANCE_KEYS),
        phase = source?.stringValue("phase", "mapPhase", "map_phase")
            ?: map?.stringValue("phase", "mapPhase", "map_phase"),
        orderId = source?.stringValue("orderId", "order_id"),
        fromName = from?.stringValue("name", "displayName"),
        toName = to?.stringValue("name", "displayName"),
        phoneSuffix = source?.stringValue("phoneNumberSuffix", "phone_suffix"),
    )
}

internal fun rideStatusLabel(code: String?, statusText: String?, translate: RideTranslate): String? {
    val rawCode = code?.trim()?.takeIf { it.isNotEmpty() }
    val apiText = statusText?.trim()?.takeIf { it.isNotEmpty() }
    val label = when (rawCode) {
        "0" -> translate("匹配中", "matching a driver")
        "1" -> translate("司机已接单", "driver accepted")
        "2" -> translate("司机已到达", "driver arrived")
        "3" -> translate("未知状态", "unknown state")
        "4" -> translate("行程中", "on the trip")
        "5" -> translate("订单完成", "order completed")
        "6" -> translate("订单已被系统取消", "order cancelled by the system")
        "7" -> translate("订单已取消", "order cancelled")
        "8", "9", "10" -> translate("未知状态", "unknown state")
        "11" -> translate("客服关闭订单", "order closed by support")
        "12" -> translate("未能完成服务", "service not completed")
        else -> null
    } ?: apiText
        ?: return null
    return if (rawCode != null) "$label（$rawCode）" else label
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
        get() = if (latitude.isNullOrBlank() || longitude.isNullOrBlank()) null else "$longitude, $latitude"

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

internal fun parseDidiErrorCode(raw: String?): Int? {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty()) return null
    return Regex("-(320\\d{2})").find(text)?.value?.toIntOrNull()
}

internal fun isDidiRateLimited(code: Int?, raw: String?): Boolean {
    if (code == RATE_LIMIT_CODE) return true
    val text = raw?.lowercase().orEmpty()
    return RATE_LIMIT_MARKERS.any { text.contains(it) }
}

internal fun rideErrorText(
    error: Throwable,
    secret: String?,
    translate: RideTranslate,
    actionZh: String,
    actionEn: String,
): String {
    val code = (error as? DidiException)?.code
    val raw = error.message
    if (isDidiRateLimited(code, raw)) {
        return translate(
            "$actionZh：滴滴查询次数已达上限（限流），请约 1 分钟后重试；这不是没有订单。",
            "$actionEn: the DiDi query quota is exceeded (rate limited). Retry in about a minute; this is not \"no order\".",
        )
    }
    val summary = summarizeDidiError(raw, secret = secret)
    return if (summary.isBlank()) {
        translate("$actionZh：网络或服务暂时不可用，请稍后重试。", "$actionEn: network or service unavailable. Try again.")
    } else {
        translate("$actionZh：$summary", "$actionEn: $summary")
    }
}

internal const val RATE_LIMIT_CODE = -32001

private val RATE_LIMIT_MARKERS = listOf("限流", "请求过于频繁", "次数已达上限", "too many requests", "rate limit")

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
