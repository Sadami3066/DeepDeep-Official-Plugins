package io.github.whywood.deepdeep.plugin.ride

import io.github.whywood.deepdeep.plugin.api.PluginHost
import io.github.whywood.deepdeep.plugin.api.localizedText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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
    val productId: String? = null,
    val currency: String? = null,
    val coupon: String? = null,
)

internal data class RideEstimate(
    val fromName: String?,
    val toName: String?,
    val items: List<RideEstimateItem>,
    val traceId: String? = null,
    val eta: String? = null,
    val distance: String? = null,
    val duration: String? = null,
    val phoneSuffix: String? = null,
    val appLink: String? = null,
    val browserLink: String? = null,
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
    val createdAt: String? = null,
    val startedAt: String? = null,
    val finishedAt: String? = null,
    val updatedAt: String? = null,
    val driverRating: String? = null,
    val driverOrderCount: String? = null,
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
            createdAt,
            startedAt,
            finishedAt,
            updatedAt,
            driverRating,
            driverOrderCount,
        ).all { it.isNullOrBlank() }

    val route: String
        get() = listOfNotNull(fromName, toName).joinToString(" → ")
}

internal data class RideDriverLocation(
    val latitude: String?,
    val longitude: String?,
    val summary: String?,
    val status: String? = null,
    val updatedAt: String? = null,
    val eta: String? = null,
    val distance: String? = null,
    val driverName: String? = null,
    val plate: String? = null,
) {
    val isEmpty: Boolean
        get() = listOf(
            latitude,
            longitude,
            summary,
            status,
            updatedAt,
            eta,
            distance,
            driverName,
            plate,
        ).all { it.isNullOrBlank() }

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
                eta = item.stringValue(*RIDE_ETA_KEYS) ?: item.objectText(*RIDE_ETA_KEYS),
                distance = item.stringValue(*RIDE_DISTANCE_KEYS) ?: item.objectText(*RIDE_DISTANCE_KEYS),
                duration = item.stringValue(*RIDE_DURATION_KEYS) ?: item.objectText(*RIDE_DURATION_KEYS),
                productId = item.stringValue(
                    "productId",
                    "product_id",
                    "productCode",
                    "product_code",
                    "productNo",
                    "product_no",
                ),
                currency = item.stringValue(
                    "currency",
                    "currencyCode",
                    "currency_code",
                    "currencySymbol",
                    "currency_symbol",
                    "priceCurrency",
                    "price_currency",
                ),
                coupon = item.stringValue(
                    "coupon",
                    "couponText",
                    "coupon_text",
                    "couponAmount",
                    "coupon_amount",
                    "discount",
                    "discountText",
                    "discount_text",
                    "promotion",
                    "promotionText",
                    "promotion_text",
                ) ?: item.objectText(
                    "coupon",
                    "discount",
                    "promotion",
                ),
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
        phoneSuffix = source?.stringValue("phoneNumberSuffix", "phone_number_suffix", "phoneSuffix", "phone_suffix"),
        appLink = source?.stringValue("appLink", "app_link"),
        browserLink = source?.stringValue("browserLink", "browser_link"),
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
        phoneSuffix = source?.stringValue("phoneNumberSuffix", "phone_number_suffix", "phoneSuffix", "phone_suffix"),
        createdAt = source?.stringValue(*RIDE_CREATED_AT_KEYS),
        startedAt = source?.stringValue(*RIDE_STARTED_AT_KEYS),
        finishedAt = source?.stringValue(*RIDE_FINISHED_AT_KEYS),
        updatedAt = source?.stringValue(*RIDE_UPDATED_AT_KEYS),
        driverRating = driver?.stringValue("rating", "driverRating", "driver_rating", "star", "score")
            ?: source?.stringValue("driverRating", "driver_rating"),
        driverOrderCount = driver?.stringValue(
            "orderCount",
            "order_count",
            "driverOrderCount",
            "driver_order_count",
            "serviceCount",
            "service_count",
            "orderNum",
            "order_num",
        ) ?: source?.stringValue("driverOrderCount", "driver_order_count"),
    )
}

private val RIDE_CREATED_AT_KEYS = arrayOf(
    "createdAt",
    "created_at",
    "createTime",
    "create_time",
    "orderTime",
    "order_time",
    "bookingTime",
    "booking_time",
    "orderCreateTime",
    "order_create_time",
)

private val RIDE_STARTED_AT_KEYS = arrayOf(
    "startTime",
    "start_time",
    "tripStartTime",
    "trip_start_time",
    "departTime",
    "depart_time",
    "pickupTime",
    "pickup_time",
    "boardTime",
    "board_time",
    "departedAt",
    "departed_at",
)

private val RIDE_FINISHED_AT_KEYS = arrayOf(
    "finishTime",
    "finish_time",
    "endTime",
    "end_time",
    "completedAt",
    "completed_at",
    "tripEndTime",
    "trip_end_time",
    "closeTime",
    "close_time",
)

private val RIDE_UPDATED_AT_KEYS = arrayOf(
    "updateTime",
    "update_time",
    "updatedAt",
    "updated_at",
    "statusTime",
    "status_time",
    "statusUpdatedAt",
    "status_updated_at",
    "timestamp",
)

private val RIDE_GENERIC_STATUS_TEXTS = setOf(
    "未知状态",
    "未知",
    "unknown",
    "unknown state",
    "unknown status",
    "n/a",
    "-",
)

internal fun rideStatusLabel(
    code: String?,
    statusText: String?,
    translate: RideTranslate,
    includeCode: Boolean = true,
): String? {
    val rawCode = code?.trim()?.takeIf { it.isNotEmpty() }
    val apiText = statusText?.trim()
        ?.takeIf { it.isNotEmpty() && it.lowercase() !in RIDE_GENERIC_STATUS_TEXTS }
    val mapped = when (rawCode) {
        "0" -> translate("匹配中", "matching a driver")
        "1" -> translate("司机已接单", "driver accepted")
        "2" -> translate("司机已到达", "driver arrived")
        "3", "8", "9", "10" -> translate("订单已结束", "order ended")
        "4" -> translate("行程中", "on the trip")
        "5" -> translate("订单完成", "order completed")
        "6" -> translate("订单已被系统取消", "order cancelled by the system")
        "7" -> translate("订单已取消", "order cancelled")
        "11" -> translate("客服关闭订单", "order closed by support")
        "12" -> translate("未能完成服务", "service not completed")
        "created" -> translate("订单已创建", "order created")
        else -> null
    }
    val label = apiText ?: mapped ?: return null
    val numericCode = rawCode != null && rawCode.all { it.isDigit() }
    return if (includeCode && numericCode) "$label（$rawCode）" else label
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
    val driver = source["driver"] as? JsonObject
    return RideDriverLocation(
        latitude = latitude,
        longitude = longitude,
        summary = summary,
        status = source.stringValue("status", "statusText", "status_text", "orderStatus", "order_status", "state"),
        updatedAt = source.stringValue(*RIDE_UPDATED_AT_KEYS),
        eta = source.stringValue(*RIDE_ETA_KEYS) ?: source.objectText(*RIDE_ETA_KEYS),
        distance = source.stringValue(*RIDE_DISTANCE_KEYS) ?: source.objectText(*RIDE_DISTANCE_KEYS),
        driverName = source.stringValue("driverName", "driver_name") ?: driver?.stringValue("name", "driverName"),
        plate = source.stringValue("plateNumber", "plate_number", "carPlate", "plate", "licensePlate")
            ?: driver?.stringValue("carPlate", "plate", "licensePlate"),
    )
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
    val city: String? = null,
) {
    val displayName: String?
        get() = name ?: address

    val coordinates: String?
        get() = if (latitude.isNullOrBlank() || longitude.isNullOrBlank()) null else "$longitude, $latitude"

    val isEmpty: Boolean
        get() = name.isNullOrBlank() &&
            address.isNullOrBlank() &&
            latitude.isNullOrBlank() &&
            longitude.isNullOrBlank() &&
            city.isNullOrBlank()
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
    val city = item.stringValue("city", "cityName", "city_name")
    return RidePlace(name = name, address = address, latitude = latitude, longitude = longitude, city = city)
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

internal fun summarizeDidiError(raw: String?, secret: String? = null): String =
    redactDidiError(raw, secret)

internal fun redactDidiError(raw: String?, secret: String? = null): String {
    var text = raw.orEmpty()
    if (text.isEmpty()) return ""
    if (!secret.isNullOrBlank()) text = text.replace(secret, "***")
    text = text.replace(
        Regex("(?i)\\b(key|token|secret|apikey|api_key|access_token|refresh_token)\\s*=\\s*([^&\\s\"'<>]+)"),
        "$1=***",
    )
    text = text.replace(
        Regex("(?i)(\"(?:key|token|secret|apikey|api_key|access_token|refresh_token)\"\\s*:\\s*\")[^\"]*(\")"),
        "$1***$2",
    )
    text = text.replace(Regex("(?i)(authorization\\s*:\\s*bearer\\s+)[^\\s\"']+"), "$1***")
    text = text.replace(Regex("(?i)(\"authorization\"\\s*:\\s*\"bearer\\s+)[^\"]*(\")"), "$1***$2")
    return text.trim()
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
    val summary = summarizeDidiError(raw, secret = secret)
    if (isDidiRateLimited(code, raw)) {
        val upstream = summary.ifBlank { translate("滴滴返回限流", "DiDi returned a rate limit") }
        return translate(
            "$actionZh：$upstream\n滴滴查询次数已达上限（限流），请约 1 分钟后重试；这不是没有订单。",
            "$actionEn: $upstream\nThe DiDi query quota is exceeded (rate limited). Retry in about a minute; this is not \"no order\".",
        )
    }
    val hint = if (summary.isBlank()) {
        translate("网络或服务暂时不可用，请稍后重试。", "network or service unavailable. Try again.")
    } else {
        didiErrorHint(code, translate)
    }
    return if (summary.isBlank()) {
        translate("$actionZh：$hint", "$actionEn: $hint")
    } else {
        translate("$actionZh：$summary\n$hint", "$actionEn: $summary\n$hint")
    }
}

private fun didiErrorHint(code: Int?, translate: RideTranslate): String = when (code) {
    -32002 -> translate("请检查滴滴 Key 是否有效，或在设置页重新填写。", "Check that the DiDi key is valid, or re-enter it in settings.")
    -32010 -> translate("请检查参数格式，经纬度与车型品类都传字符串。", "Check the parameters; coordinates and the product category must be strings.")
    -32011 -> translate("请确认订单号是否正确。", "Check that the order ID is correct.")
    -32021 -> translate("预估结果已过期，请重新预估后再下单。", "The estimate expired. Estimate again before booking.")
    -32030 -> translate("这类订单不支持此操作。", "This order type does not support the operation.")
    -32031 -> translate("订单还未支付。", "The order has not been paid yet.")
    -32040 -> translate("订单已经取消过了，无需重复操作。", "The order was already cancelled.")
    -32041 -> translate("司机已接单或订单已完成，无法通过接口取消。", "A driver has accepted or the ride is complete, so it cannot be cancelled through the API.")
    -32050 -> translate("滴滴内部错误，请稍后重试。", "DiDi had an internal error. Try again later.")
    -32060 -> translate("支付失败，请在滴滴内检查支付账户。", "Payment failed. Check the payment account in DiDi.")
    else -> translate("请稍后重试；如果一直失败，请检查 Key 是否有效。", "Try again later; if it keeps failing, check that the key is valid.")
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

internal fun JsonObject.objectText(vararg names: String): String? {
    names.forEach { name ->
        val value = this[name] as? JsonObject ?: return@forEach
        value.stringValue(
            "text",
            "label",
            "displayText",
            "display_text",
            "title",
            "name",
            "amount",
            "value",
            "description",
            "desc",
        )?.let { return it }
    }
    return null
}

internal fun rideMoney(value: String?, currency: String? = null): String? {
    val text = value?.trim().orEmpty()
    if (text.isEmpty()) return null
    if (text.any { it == '¥' || it == '￥' || it == '$' || it == '€' || it == '£' || it == '元' }) return text
    if (text.contains("CNY", ignoreCase = true) || text.contains("RMB", ignoreCase = true)) return text
    val code = currency?.trim()?.uppercase().orEmpty()
    val unit = when (code) {
        "", "CNY", "RMB" -> "¥"
        "USD" -> "$"
        "HKD" -> "HK$"
        "EUR" -> "€"
        "GBP" -> "£"
        "JPY" -> "¥"
        else -> "$code "
    }
    return unit + text
}

private val RIDE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

internal fun rideTimeText(value: String?): String? {
    val text = value?.trim().orEmpty()
    if (text.isEmpty()) return null
    val instant = when {
        text.all { it.isDigit() && it in '0'..'9' } && text.length == 13 ->
            text.toLongOrNull()?.let(Instant::ofEpochMilli)
        text.all { it.isDigit() && it in '0'..'9' } && text.length == 10 ->
            text.toLongOrNull()?.let(Instant::ofEpochSecond)
        else -> runCatching { Instant.parse(text) }.getOrNull()
    } ?: return text
    return runCatching {
        RIDE_TIME_FORMATTER.format(instant.atZone(ZoneId.systemDefault()))
    }.getOrDefault(text)
}

internal const val RIDE_RAW_DETAIL_KEY = "deepdeep.raw"
internal const val RIDE_CARD_DETAIL_KEY = "deepdeep.card"

internal fun rideDetails(payload: JsonElement?, raw: String?, cardTitle: String?): JsonObject {
    val base = when (payload) {
        is JsonObject -> payload.filterKeys { it != RIDE_RAW_DETAIL_KEY && it != RIDE_CARD_DETAIL_KEY }
        is JsonArray -> mapOf("items" to payload)
        is JsonPrimitive -> mapOf("text" to payload)
        null -> emptyMap()
    }
    return buildJsonObject {
        base.forEach { (key, value) -> put(key, value) }
        raw?.takeIf { it.isNotBlank() }?.let { put(RIDE_RAW_DETAIL_KEY, it) }
        cardTitle?.takeIf { it.isNotBlank() }?.let {
            put(RIDE_CARD_DETAIL_KEY, buildJsonObject { put("title", it) })
        }
    }
}

internal fun rideErrorDetails(error: Throwable): JsonObject? {
    val raw = (error as? DidiException)?.raw?.takeIf { it.isNotBlank() } ?: return null
    return buildJsonObject { put(RIDE_RAW_DETAIL_KEY, raw) }
}
