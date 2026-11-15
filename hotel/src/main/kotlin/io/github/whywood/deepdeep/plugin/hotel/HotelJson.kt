package io.github.whywood.deepdeep.plugin.hotel

import java.time.LocalDate
import java.time.temporal.ChronoUnit
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
            distanceMeters = item.number("distanceInMeters", "distance_in_meters")?.toInt(),
            hotelId = item.number("hotelId", "hotel_id")?.toLong(),
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
        .firstOrNull {
            it["roomRatePlans"] is JsonArray ||
                it["name"] != null ||
                it["hotelName"] != null ||
                it["bookingUrl"] != null ||
                it["errorMessage"] != null
        }
        ?: return null
    val plans = root["roomRatePlans"] as? JsonArray
    val explicitName = root.string("name", "hotelName")
    val bookingUrl = root.string("bookingUrl", "booking_url")
    if (plans == null && explicitName == null && bookingUrl == null && root.string("errorMessage") == null) return null
    val checkIn = root.string("checkIn", "check_in")?.take(10)
    val checkOut = root.string("checkOut", "check_out")?.take(10)
    val nights = hotelNights(checkIn, checkOut)
    val rooms = plans?.mapNotNull { element ->
        val item = element as? JsonObject ?: return@mapNotNull null
        val name = item.string("roomNameCn", "roomName", "name") ?: return@mapNotNull null
        val roomInfo = item["roomInfo"] as? JsonObject
        val currency = item.string("currency")
        val averagePrice = item.number("averagePrice", "average_price", "totalSalesRate", "total_sales_rate")
        val totalPrice = item.number("totalPrice", "total_price")
            ?: averagePrice?.let { price -> price * nights }
        val cancelable = item.boolean("cancelable")
        HotelRoomPlan(
            name = name.trim(),
            ratePlanName = item.string("ratePlanName", "rate_plan_name"),
            bed = item.string("bedTypeDescription", "bedType")
                ?: roomInfo?.string("bedTypeDescription", "bedType"),
            priceText = money(totalPrice, currency),
            avgPriceText = money(averagePrice, currency),
            cancellation = item.string("cancelPolicy", "cancel_policy")
                ?: item.cancellationText()
                ?: if (cancelable == false) "不可取消" else null,
            ratePlanId = item.string("ratePlanId", "rate_plan_id"),
            totalPrice = totalPrice,
            averagePrice = averagePrice,
            currency = currency,
            meal = item.string("mealTypeStr", "meal_type_str", "mealType", "meal_type"),
            cancelable = cancelable,
            checkIn = checkIn,
            checkOut = checkOut,
            inventoryCount = item.number("inventoryCount", "inventory_count")?.toInt()
                ?: roomInfo?.number("inventoryCount", "inventory_count")?.toInt(),
            onRequest = item.boolean("isOnRequest", "is_on_request"),
            roomSize = roomInfo?.string("size", "roomSize", "room_size"),
            floor = roomInfo?.string("floor"),
            maxOccupancy = roomInfo?.number("maxOccupancy", "max_occupancy")?.toInt(),
            hasWindow = roomInfo?.boolean("hasWindow", "has_window"),
            hasWifi = roomInfo?.boolean("hasWifi", "has_wifi"),
        )
    }.orEmpty()
    return HotelDetailDetails(
        name = explicitName ?: fallbackName,
        checkIn = checkIn,
        checkOut = checkOut,
        rooms = rooms,
        roomGroups = groupHotelRooms(rooms),
        bookingUrl = bookingUrl,
        fetchedAt = System.currentTimeMillis(),
        hotelId = root.number("hotelId", "hotel_id")?.toLong(),
        errorMessage = root.string("errorMessage", "error_message"),
    )
}

internal fun groupHotelRooms(plans: List<HotelRoomPlan>): List<HotelRoomGroup> {
    val groups = LinkedHashMap<String, MutableList<HotelRoomPlan>>()
    plans.forEach { plan ->
        val key = plan.name.trim().lowercase(Locale.ROOT).replace(WHITESPACE, " ")
        groups.getOrPut(key) { mutableListOf() }.add(plan)
    }
    return groups.values.map { members ->
        val sorted = sortedHotelPlans(members)
        val cheapest = sorted.firstOrNull { it.priceText != null } ?: sorted.first()
        val beds = sorted.mapNotNull { it.bed?.trim()?.takeIf(String::isNotEmpty) }.distinct()
        HotelRoomGroup(
            name = cheapest.name.trim(),
            bed = beds.singleOrNull(),
            lowestPriceText = cheapest.priceText,
            planCount = sorted.size,
            cheapestCancellation = cheapest.cancellation,
            plans = sorted,
        )
    }
}

internal fun sortedHotelPlans(plans: List<HotelRoomPlan>): List<HotelRoomPlan> =
    plans.sortedWith(compareBy(nullsLast()) { plan: HotelRoomPlan -> plan.totalPrice })

internal fun selectHotelPlan(
    details: HotelDetailDetails,
    roomName: String,
    ratePlanId: String?,
): HotelPlanSelection {
    val wantedId = ratePlanId?.trim().orEmpty()
    if (wantedId.isNotEmpty()) {
        details.rooms.firstOrNull { it.ratePlanId?.trim() == wantedId }?.let { return HotelPlanSelection.Selected(it) }
        return HotelPlanSelection.MissingRatePlan(wantedId)
    }
    val wantedName = roomName.trim()
    if (wantedName.isEmpty()) return HotelPlanSelection.MissingRoom(roomName)
    val exact = details.rooms.filter { it.name.trim() == wantedName }
    val candidates = exact.ifEmpty {
        details.rooms.filter {
            val name = it.name.trim()
            name.contains(wantedName) || wantedName.contains(name)
        }
    }
    return when {
        candidates.isEmpty() -> HotelPlanSelection.MissingRoom(wantedName)
        candidates.size == 1 -> HotelPlanSelection.Selected(candidates.single())
        else -> HotelPlanSelection.Multiple(sortedHotelPlans(candidates))
    }
}

internal fun parseHotelPriceConfirmation(raw: String): HotelPriceConfirmation? {
    val root = extractJsonElements(raw).filterIsInstance<JsonObject>()
        .firstOrNull { it["priceDetailsInfo"] is JsonObject } ?: return null
    val details = root["priceDetailsInfo"] as? JsonObject ?: return null
    val reference = details.string("referenceNo", "reference_no") ?: return null
    val hotel = (details["hotelList"] as? JsonArray)?.firstOrNull() as? JsonObject
    val rate = (hotel?.get("ratePlanList") as? JsonArray)?.firstOrNull() as? JsonObject
    return HotelPriceConfirmation(
        referenceNo = reference,
        totalPrice = hotel?.number("totalPrice") ?: rate?.number("totalPrice"),
        currency = rate?.string("currency") ?: hotel?.string("currency"),
    )
}

internal fun parseHotelBooking(raw: String): HotelBookingDetails? {
    val root = extractJsonElements(raw).filterIsInstance<JsonObject>()
        .firstOrNull { it["bookingResult"] is JsonObject } ?: return null
    val booking = root["bookingResult"] as? JsonObject ?: return null
    val orderNo = booking.string("orderNo", "order_no", "orderId", "order_id") ?: return null
    return HotelBookingDetails(
        orderNo = orderNo,
        hotelName = root.string("hotelName", "name").orEmpty(),
        roomName = root.string("roomName"),
        checkIn = root.string("checkIn", "checkInDate")?.take(10),
        checkOut = root.string("checkOut", "checkOutDate")?.take(10),
        totalPrice = money(root.number("totalPrice"), root.string("currency")),
        paymentType = booking.string("paymentType", "payment_type"),
        paymentUrl = booking.string("paymentUrl", "payment_url", "alipayUrl", "alipay_url"),
        message = root.string("message"),
        fetchedAt = System.currentTimeMillis(),
    )
}

internal fun parseHotelOrders(raw: String): HotelOrdersDetails? {
    val elements = extractJsonElements(raw)
    val rootObject = elements.filterIsInstance<JsonObject>().firstOrNull { root ->
        root["orderInfoList"] is JsonArray || root["orders"] is JsonArray || root["orderList"] is JsonArray
    }
    val rootArray = elements.filterIsInstance<JsonArray>().firstOrNull { array ->
        array.any { row -> row is JsonObject && (row["hotelBookingInfo"] != null || row["orderNo"] != null) }
    }
    val list = rootObject?.let { root ->
        (root["orderInfoList"] as? JsonArray)
            ?: (root["orders"] as? JsonArray)
            ?: (root["orderList"] as? JsonArray)
    } ?: rootArray ?: return null
    val orders = list.mapNotNull { row -> parseHotelOrder(row as? JsonObject ?: return@mapNotNull null) }
    if (list.isNotEmpty() && orders.isEmpty()) return null
    return HotelOrdersDetails(
        message = rootObject?.string("message"),
        orders = orders,
        fetchedAt = System.currentTimeMillis(),
    )
}

private fun parseHotelOrder(row: JsonObject): HotelOrderSummary? {
    val info = row["hotelBookingInfo"] as? JsonObject
    val hotel = row["hotelBookingHotel"] as? JsonObject
    val room = row["hotelRatePlanInfo"] as? JsonObject
    val contact = row["hotelContact"] as? JsonObject
    val hotelName = hotel?.string("hotelName", "name")
        ?: info?.string("hotelName")
        ?: row.string("hotelName")
        ?: return null
    return HotelOrderSummary(
        hotelName = hotelName,
        orderNo = info?.string("mainOrderNo", "main_order_no", "orderNo", "order_no", "orderId", "order_id"),
        subOrderNo = info?.string("subOrderNo", "sub_order_no"),
        referenceNo = info?.string("referenceNo", "reference_no"),
        hotelAddress = hotel?.string("hotelAddress", "address"),
        checkIn = info?.string("checkInDate", "check_in_date")?.take(10),
        checkOut = info?.string("checkOutDate", "check_out_date")?.take(10),
        nights = info?.number("nights", "nightNum")?.toInt(),
        roomCount = info?.number("numOfRooms", "roomCount", "room_count")?.toInt(),
        status = info?.string("status", "orderStatus", "order_status"),
        paymentStatus = info?.string("paymentStatus", "payment_status"),
        roomName = room?.string("roomNameCn", "roomName", "ratePlanName"),
        totalPrice = money(
            info?.number("totalPrice") ?: room?.number("totalPrice"),
            room?.string("currency") ?: info?.string("currency"),
        ),
        contactName = listOfNotNull(contact?.string("firstName"), contact?.string("lastName"))
            .joinToString(" ")
            .takeIf { it.isNotBlank() },
        contactEmail = contact?.string("email"),
        paymentUrl = info?.string("paymentUrl", "payment_url") ?: row.string("paymentUrl", "payment_url"),
    )
}

internal fun parseHotelOrderMessage(raw: String): String? =
    extractJsonElements(raw).filterIsInstance<JsonObject>()
        .firstNotNullOfOrNull { it.string("message", "errorMessage", "error_message") }

internal fun hotelNights(checkIn: String?, checkOut: String?): Int {
    val start = checkIn?.take(10)?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return 1
    val end = checkOut?.take(10)?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return 1
    val nights = ChronoUnit.DAYS.between(start, end).toInt()
    return if (nights < 1) 1 else nights
}

internal fun hotelDateAfter(checkIn: String?, nights: Int): String? {
    val start = checkIn?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() } ?: return null
    return start.plusDays(nights.coerceAtLeast(1).toLong()).toString()
}

internal fun hotelMoneyText(value: Double?, currency: String?): String? = money(value, currency)

internal fun hotelDistanceText(meters: Int?): String? {
    if (meters == null) return null
    return if (meters < 1000) {
        "$meters 米"
    } else {
        String.format(Locale.ROOT, "%.1f 公里", meters / 1000.0)
    }
}

private val WHITESPACE = Regex("\\s+")

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

private fun JsonObject.boolean(vararg names: String): Boolean? {
    names.forEach { name ->
        val value = (this[name] as? JsonPrimitive)?.contentOrNull?.trim() ?: return@forEach
        when (value.lowercase(Locale.ROOT)) {
            "true", "1", "yes" -> return true
            "false", "0", "no" -> return false
        }
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
    val price = this["price"] as? JsonObject
    if (price != null) {
        money(price.number("lowestPrice", "lowest_price"), price.string("currency"))?.let { return it }
        price.string("message")?.let { return it }
    }
    return money(number("lowestPrice", "lowest_price"), string("currency"))
}

private fun JsonObject.cancellationText(): String? {
    val policies = this["cancellationPolicies"] as? JsonArray ?: return null
    val first = policies.firstOrNull() as? JsonObject ?: return null
    first.string("description")?.let { return it }
    val amount = first.number("amount")
    val percent = first.number("percent")
    val from = first.string("fromDate", "from_date")?.take(10)
    return when {
        amount == null && percent == null -> null
        amount == 0.0 || percent == 0.0 -> "可免费取消"
        amount != null && from != null -> "$from 起取消扣 ${money(amount, "CNY")}"
        percent != null && from != null -> "$from 起取消扣 ${percent.toInt()}%"
        amount != null -> "取消扣 ${money(amount, "CNY")}"
        else -> "取消扣 ${percent?.toInt()}%"
    }
}
