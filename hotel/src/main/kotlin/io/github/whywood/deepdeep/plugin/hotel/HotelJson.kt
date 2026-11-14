package io.github.whywood.deepdeep.plugin.hotel

import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal val HotelJson = Json { ignoreUnknownKeys = true; isLenient = true }

internal fun extractJsonElement(raw: String?): JsonElement? = extractJsonElements(raw).firstOrNull()

internal fun extractJsonElements(raw: String?): List<JsonElement> {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty()) return emptyList()
    runCatching { HotelJson.parseToJsonElement(text) }.getOrNull()?.let { return listOf(it) }
    val elements = mutableListOf<JsonElement>()
    var index = 0
    while (index < text.length) {
        if (text[index] != '{' && text[index] != '[') {
            index += 1
            continue
        }
        val end = matchingJsonEnd(text, index)
        if (end == null) {
            index += 1
            continue
        }
        runCatching { HotelJson.parseToJsonElement(text.substring(index, end + 1)) }
            .getOrNull()
            ?.let(elements::add)
        index = end + 1
    }
    return elements
}

private fun matchingJsonEnd(text: String, start: Int): Int? {
    var depth = 0
    var inString = false
    var escaped = false
    for (index in start until text.length) {
        val char = text[index]
        if (inString) {
            when {
                escaped -> escaped = false
                char == '\\' -> escaped = true
                char == '"' -> inString = false
            }
            continue
        }
        when (char) {
            '"' -> inString = true
            '{', '[' -> depth += 1
            '}', ']' -> {
                depth -= 1
                if (depth == 0) return index
            }
        }
    }
    return null
}

internal fun parseHotelSearch(
    raw: String,
    query: String,
    place: String,
    placeType: String,
    checkIn: String?,
    nights: Int,
): HotelSearchDetails? {
    val root = extractJsonElements(raw).filterIsInstance<JsonObject>()
        .firstOrNull { it["hotelInformationList"] is JsonArray } ?: return null
    val list = root["hotelInformationList"] as? JsonArray ?: return null
    val hotels = list.mapNotNull { element ->
        val item = element as? JsonObject ?: return@mapNotNull null
        val name = item.string("name", "hotelName") ?: return@mapNotNull null
        HotelSummary(
            name = name,
            starRating = item.starRating(),
            priceText = item.hotelPrice(),
            address = item.string("address", "addressAll"),
            tags = item.stringList("tags").take(5),
            bookingUrl = item.string("bookingUrl", "booking_url"),
            distance = item.string("distanceText", "distance_text"),
        )
    }
    return HotelSearchDetails(
        query = query,
        place = place,
        placeType = placeType,
        checkIn = checkIn,
        nights = nights,
        hotels = hotels,
        fetchedAt = System.currentTimeMillis(),
    )
}

internal fun parseHotelDetail(raw: String, fallbackName: String): HotelDetailDetails? {
    val root = extractJsonElements(raw).filterIsInstance<JsonObject>()
        .firstOrNull { it["roomRatePlans"] is JsonArray || it["name"] != null || it["hotelName"] != null || it["bookingUrl"] != null }
        ?: return null
    val plans = root["roomRatePlans"] as? JsonArray
    val explicitName = root.string("name", "hotelName")
    val bookingUrl = root.string("bookingUrl", "booking_url")
    if (plans == null && explicitName == null && bookingUrl == null) return null
    val rooms = plans?.mapNotNull { element ->
        val item = element as? JsonObject ?: return@mapNotNull null
        val name = item.string("roomNameCn", "roomName", "name") ?: return@mapNotNull null
        HotelRoomPlan(
            name = name,
            bed = item.string("bedTypeDescription", "bedType"),
            priceText = money(item.number("totalPrice"), item.string("currency")),
            cancellation = item.cancellationText(),
        )
    }.orEmpty()
    return HotelDetailDetails(
        name = explicitName ?: fallbackName,
        checkIn = root.string("checkIn", "check_in")?.take(10),
        checkOut = root.string("checkOut", "check_out")?.take(10),
        rooms = rooms,
        bookingUrl = bookingUrl,
        fetchedAt = System.currentTimeMillis(),
    )
}

internal fun parseHotelOrders(raw: String): HotelOrdersDetails? {
    val list = extractJsonElements(raw).firstNotNullOfOrNull { element ->
        when (element) {
            is JsonArray -> element.takeIf { array -> array.any { it is JsonObject && it["hotelBookingInfo"] != null } }
            is JsonObject -> (element["orders"] as? JsonArray) ?: (element["orderList"] as? JsonArray)
            else -> null
        }
    } ?: return null
    val orders = list.mapNotNull { row ->
        val item = row as? JsonObject ?: return@mapNotNull null
        val info = item["hotelBookingInfo"] as? JsonObject
        val hotel = item["hotelBookingHotel"] as? JsonObject
        val room = item["hotelRatePlanInfo"] as? JsonObject
        HotelOrderSummary(
            hotelName = hotel?.string("hotelName", "name")
                ?: info?.string("hotelName")
                ?: return@mapNotNull null,
            checkIn = info?.string("checkInDate")?.take(10),
            checkOut = info?.string("checkOutDate")?.take(10),
            status = info?.string("status", "orderStatus"),
            roomName = room?.string("roomNameCn", "roomName", "ratePlanName"),
            totalPrice = money(info?.number("totalPrice"), room?.string("currency")),
        )
    }
    return HotelOrdersDetails(orders = orders, fetchedAt = System.currentTimeMillis())
}

internal fun hotelDateAfter(checkIn: String?, nights: Int): String? {
    val start = checkIn?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() } ?: return null
    return start.plusDays(nights.coerceAtLeast(1).toLong()).toString()
}

internal fun hotelMoneyText(value: Double?, currency: String?): String? = money(value, currency)

private fun money(value: Double?, currency: String?): String? {
    if (value == null) return null
    val amount = if (value % 1.0 == 0.0) {
        value.toLong().toString()
    } else {
        String.format(Locale.ROOT, "%.2f", value).trimEnd('0').trimEnd('.')
    }
    val symbol = when (currency?.uppercase(Locale.ROOT)) {
        null, "CNY", "RMB" -> "¥"
        "USD" -> "$"
        "EUR" -> "€"
        "JPY" -> "¥"
        "HKD" -> "HK$"
        else -> "${currency.orEmpty()} "
    }
    return "$symbol$amount"
}

private fun JsonObject.string(vararg names: String): String? {
    names.forEach { name ->
        val value = (this[name] as? JsonPrimitive)?.contentOrNull?.trim()
        if (!value.isNullOrBlank()) return value
    }
    return null
}

private fun JsonObject.number(vararg names: String): Double? {
    names.forEach { name ->
        val value = (this[name] as? JsonPrimitive)?.contentOrNull?.trim() ?: return@forEach
        value.toDoubleOrNull()?.let { return it }
    }
    return null
}

private fun JsonObject.stringList(name: String): List<String> {
    val array = this[name] as? JsonArray ?: return emptyList()
    return array.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotBlank) }
}

private fun JsonObject.starRating(): String? {
    val raw = this["starRating"] as? JsonPrimitive ?: return null
    val text = raw.contentOrNull?.trim().orEmpty()
    if (text.isEmpty()) return null
    return text.toDoubleOrNull()?.let { value ->
        if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
    } ?: text
}

private fun JsonObject.hotelPrice(): String? {
    val price = this["price"] as? JsonObject ?: return null
    val lowest = price.number("lowestPrice", "lowest_price")
    val currency = price.string("currency")
    return money(lowest, currency)
}

private fun JsonObject.cancellationText(): String? {
    val policies = this["cancellationPolicies"] as? JsonArray ?: return null
    val first = policies.firstOrNull() as? JsonObject ?: return null
    val amount = first.number("amount")
    val from = first.string("fromDate", "from_date")?.take(10)
    return when {
        amount == null -> null
        amount == 0.0 -> "可免费取消"
        from != null -> "$from 起 ¥${amount.toLong()}"
        else -> "取消罚金 ¥${amount.toLong()}"
    }
}
