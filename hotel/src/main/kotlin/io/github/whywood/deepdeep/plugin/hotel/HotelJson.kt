package io.github.whywood.superpi.plugin.hotel

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
        val price = item.hotelPrice()
        HotelSummary(
            name = name,
            nameEn = item.string("nameEn", "name_en"),
            starRating = item.starRating(),
            priceText = price?.text,
            lowestPrice = price?.amount,
            currency = price?.currency,
            address = item.string("address", "addressAll"),
            tags = item.stringList("tags"),
            amenities = item.stringList("hotelAmenities", "amenities"),
            bookingUrl = item.string("bookingUrl", "booking_url"),
            imageUrl = item.string("imageUrl", "image_url", "image"),
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
        nextPageToken = root.string("nextPageToken", "next_page_token"),
        filter = root.filterEntries(),
        noMatchingRates = root.boolean("noMatchingRates", "no_matching_rates") ?: false,
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
        val explicitTotal = item.number("totalPrice", "total_price")
        val totalPrice = explicitTotal ?: averagePrice?.let { price -> price * nights }
        val bedRaw = item.string("bedTypeDescription", "bedType")
            ?: roomInfo?.string("bedTypeDescription", "bedType")
        val bed = bedRaw?.let { hotelCleanBed(it, name) }
        val cancelable = item.boolean("cancelable")
        val cancellations = item.cancellationTexts()
        HotelRoomPlan(
            name = name.trim(),
            ratePlanName = item.string("ratePlanName", "rate_plan_name"),
            bed = bed,
            priceText = money(totalPrice, currency),
            avgPriceText = money(averagePrice, currency),
            cancellation = cancellations.firstOrNull(),
            cancellations = cancellations,
            ratePlanId = item.string("ratePlanId", "rate_plan_id"),
            totalPrice = totalPrice,
            averagePrice = averagePrice,
            currency = currency,
            meal = item.string("mealTypeStr", "meal_type_str", "mealType", "meal_type"),
            mealAmount = item.number("mealAmount", "meal_amount")?.toInt(),
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
            images = roomInfo?.imageUrls().orEmpty(),
            totalDerived = explicitTotal == null && averagePrice != null,
        )
    }.orEmpty()
    return HotelDetailDetails(
        name = explicitName ?: fallbackName,
        nameEn = root.string("nameEn", "name_en"),
        starRating = root.starRating(),
        checkIn = checkIn,
        checkOut = checkOut,
        rooms = rooms,
        roomGroups = groupHotelRooms(rooms),
        bookingUrl = bookingUrl,
        fetchedAt = System.currentTimeMillis(),
        hotelId = root.number("hotelId", "hotel_id")?.toLong(),
        errorMessage = root.string("errorMessage", "error_message"),
        message = root.string("message"),
        filter = root.filterEntries(),
        noMatchingRates = root.boolean("noMatchingRates", "no_matching_rates") ?: false,
    )
}

internal fun hotelCleanBed(raw: String, roomName: String): String? {
    val cleaned = raw.trim().trim(',', '，', '|', ' ')
    if (cleaned.isEmpty()) return null
    val normalized = cleaned.replace(Regex("""\s+"""), " ")
    val doubleRoom = roomName.contains("双床")
    val singleBed = normalized.contains("单人床") || normalized.contains("单人")
    if (doubleRoom && singleBed && !normalized.contains("2") && !normalized.contains("两")) return null
    if (normalized.contains("|")) {
        hotelDisplayRatePlanName(normalized)?.let { return it }
    }
    return normalized
}

internal fun groupHotelPlanVariants(plans: List<HotelRoomPlan>): List<HotelPlanVariant> {
    val groups = LinkedHashMap<String, MutableList<HotelRoomPlan>>()
    plans.forEach { plan ->
        val key = listOf(
            plan.bed.orEmpty(),
            plan.meal.orEmpty(),
            plan.priceText.orEmpty(),
            plan.avgPriceText.orEmpty(),
        ).joinToString("|")
        groups.getOrPut(key) { mutableListOf() }.add(plan)
    }
    return groups.values.map { members ->
        val policies = members.flatMap { plan ->
            plan.cancellations.ifEmpty { listOfNotNull(plan.cancellation) }
        }.distinct()
        val requestFlags = members.map { it.onRequest == true }.distinct()
        HotelPlanVariant(
            plan = members.first(),
            count = members.size,
            policies = policies,
            hasOnRequest = requestFlags.any { it },
            mixedOnRequest = requestFlags.size > 1,
        )
    }.sortedWith(compareBy(nullsLast()) { variant: HotelPlanVariant -> variant.plan.totalPrice })
}

internal fun groupHotelRooms(plans: List<HotelRoomPlan>): List<HotelRoomGroup> {
    val groups = LinkedHashMap<String, MutableList<HotelRoomPlan>>()
    plans.forEach { plan ->
        val key = hotelRoomBaseName(plan.name).lowercase(Locale.ROOT).replace(WHITESPACE, " ")
        groups.getOrPut(key) { mutableListOf() }.add(plan)
    }
    return groups.values.map { members ->
        val sorted = sortedHotelPlans(members)
        val cheapest = sorted.firstOrNull { it.priceText != null } ?: sorted.first()
        val beds = sorted.mapNotNull { it.bed?.trim()?.takeIf(String::isNotEmpty) }.distinct()
        HotelRoomGroup(
            name = hotelRoomBaseName(cheapest.name),
            bed = beds.singleOrNull(),
            lowestPriceText = cheapest.priceText,
            planCount = sorted.size,
            cheapestCancellation = cheapest.cancellation,
            roomSize = sorted.firstNotNullOfOrNull { it.roomSize?.trim()?.takeIf(String::isNotEmpty) },
            floor = sorted.firstNotNullOfOrNull { it.floor?.trim()?.takeIf(String::isNotEmpty) },
            maxOccupancy = sorted.firstNotNullOfOrNull { it.maxOccupancy },
            hasWindow = sorted.firstNotNullOfOrNull { it.hasWindow },
            hasWifi = sorted.firstNotNullOfOrNull { it.hasWifi },
            images = sorted.firstNotNullOfOrNull { it.images.takeIf(List<String>::isNotEmpty) }.orEmpty(),
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
    val hotelName = row.string("hotelName")
        ?: hotel?.string("hotelName", "name")
        ?: info?.string("hotelName")
        ?: return null
    val checkIn = row.string("checkInDate", "check_in_date")?.take(10)
        ?: info?.string("checkInDate", "check_in_date")?.take(10)
    val checkOut = row.string("checkOutDate", "check_out_date")?.take(10)
        ?: info?.string("checkOutDate", "check_out_date")?.take(10)
    val currency = row.string("currency") ?: room?.string("currency") ?: info?.string("currency")
    return HotelOrderSummary(
        hotelName = hotelName,
        orderNo = row.string("orderNo", "order_no", "orderId", "order_id")
            ?: info?.string("mainOrderNo", "main_order_no", "orderNo", "order_no", "orderId", "order_id"),
        subOrderNo = row.string("subOrderNo", "sub_order_no")
            ?: info?.string("subOrderNo", "sub_order_no"),
        referenceNo = row.string("referenceNo", "reference_no")
            ?: info?.string("referenceNo", "reference_no"),
        hotelAddress = row.string("hotelAddress", "hotel_address", "address")
            ?: hotel?.string("hotelAddress", "address"),
        checkIn = checkIn,
        checkOut = checkOut,
        nights = row.number("nights", "nightNum")?.toInt()
            ?: info?.number("nights", "nightNum")?.toInt()
            ?: if (checkIn != null && checkOut != null) hotelNights(checkIn, checkOut) else null,
        roomCount = row.number("numOfRooms", "roomCount", "room_count")?.toInt()
            ?: info?.number("numOfRooms", "roomCount", "room_count")?.toInt(),
        status = row.string("orderStatus", "order_status", "status")
            ?: info?.string("status", "orderStatus", "order_status"),
        paymentStatus = row.string("paymentStatus", "payment_status")
            ?: info?.string("paymentStatus", "payment_status"),
        roomName = row.string("roomName", "room_name")
            ?: room?.string("roomNameCn", "roomName", "ratePlanName"),
        totalPrice = money(
            row.number("totalPrice", "total_price")
                ?: info?.number("totalPrice")
                ?: room?.number("totalPrice"),
            currency,
        ),
        currency = currency,
        meal = row.string("mealDesc", "meal_desc", "meal"),
        cancelPolicy = row.string("cancelPolicyDesc", "cancel_policy_desc", "cancelPolicy"),
        orderDate = row.string("orderDate", "order_date"),
        orderDetailUrl = row.string("orderDetailUrl", "order_detail_url"),
        contactName = listOfNotNull(contact?.string("firstName"), contact?.string("lastName"))
            .joinToString(" ")
            .takeIf { it.isNotBlank() },
        contactEmail = contact?.string("email"),
        paymentUrl = row.string("paymentUrl", "payment_url")
            ?: info?.string("paymentUrl", "payment_url"),
    )
}

internal fun parseHotelOrderDetail(raw: String, fallbackOrderNo: String): HotelOrderDetail? {
    val elements = extractJsonElements(raw)
    val root = elements.filterIsInstance<JsonObject>().firstOrNull { it["orderDetail"] is JsonObject }
        ?: elements.filterIsInstance<JsonObject>().firstOrNull { candidate ->
            candidate["baseInfo"] is JsonObject ||
                candidate["stayInfo"] is JsonObject ||
                candidate["priceInfo"] is JsonObject ||
                candidate["policyInfo"] is JsonObject ||
                (candidate["orderNo"] != null && candidate["hotelName"] != null)
        }
        ?: return null
    val detail = root["orderDetail"] as? JsonObject ?: root
    val base = detail["baseInfo"] as? JsonObject ?: detail
    val hotel = detail["hotelInfo"] as? JsonObject ?: detail
    val stay = detail["stayInfo"] as? JsonObject ?: detail
    val price = detail["priceInfo"] as? JsonObject ?: detail
    val policy = detail["policyInfo"] as? JsonObject ?: detail
    val contact = stay["contact"] as? JsonObject
    val orderNo = base.string("orderNo", "order_no")
        ?: detail.string("orderNo", "order_no")
        ?: root.string("orderNo", "order_no")
        ?: fallbackOrderNo.takeIf { it.isNotBlank() }
        ?: return null
    val checkIn = stay.string("checkInDate", "check_in_date", "checkIn")?.take(10)
    val checkOut = stay.string("checkOutDate", "check_out_date", "checkOut")?.take(10)
    val currency = price.string("currency") ?: detail.string("currency")
    return HotelOrderDetail(
        orderNo = orderNo,
        referenceNo = base.string("referenceNo", "reference_no") ?: detail.string("referenceNo", "reference_no"),
        hotelConfirmationNo = base.string("hotelConfirmationNo", "hotel_confirmation_no"),
        orderStatus = base.string("orderStatus", "order_status", "status"),
        orderDate = base.string("orderDate", "order_date"),
        orderDetailUrl = base.string("orderDetailUrl", "order_detail_url"),
        hotelName = hotel.string("hotelName", "hotel_name", "name"),
        hotelImageUrl = hotel.string("hotelImageUrl", "hotel_image_url", "imageUrl"),
        hotelPhone = hotel.string("hotelPhone", "hotel_phone", "phone"),
        platformSupportPhone = hotel.string("platformSupportPhone", "platform_support_phone"),
        hotelAddress = hotel.string("hotelAddress", "hotel_address", "address"),
        latitude = hotel.number("latitude", "lat"),
        longitude = hotel.number("longitude", "lng", "lon"),
        roomName = stay.string("roomName", "room_name", "roomNameCn", "room_name_cn"),
        bedType = stay.string("bedTypeStr", "bedTypeDescription", "bed_type_str", "bedType"),
        checkIn = checkIn,
        checkOut = checkOut,
        checkInTime = stay.string("checkInTime", "check_in_time"),
        checkOutTime = stay.string("checkOutTime", "check_out_time"),
        roomCount = stay.number("numOfRooms", "roomCount", "room_count")?.toInt(),
        nights = stay.number("nights", "nightNum")?.toInt()
            ?: if (checkIn != null && checkOut != null) hotelNights(checkIn, checkOut) else null,
        meal = stay.string("mealDesc", "meal_desc", "mealTypeStr", "meal"),
        contactName = contact?.string("name", "contactName", "contact_name")
            ?: stay.string("contactName", "contact_name"),
        contactPhone = contact?.string("phone", "contactPhone", "contact_phone")
            ?: stay.string("contactPhone", "contact_phone"),
        contactEmail = contact?.string("email", "contactEmail", "contact_email")
            ?: stay.string("contactEmail", "contact_email"),
        guestNames = stay.stringList("guestNames", "guest_names"),
        totalPrice = money(
            price.number("totalPrice", "total_price") ?: detail.number("totalPrice", "total_price"),
            currency,
        ),
        currency = currency,
        paymentType = price.string("paymentType", "payment_type")
            ?: detail.string("paymentType", "payment_type"),
        paymentUrl = price.string("paymentUrl", "payment_url")
            ?: base.string("paymentUrl", "payment_url")
            ?: detail.string("paymentUrl", "payment_url"),
        paymentDeadline = price.string("paymentDeadline", "payment_deadline")
            ?: base.string("paymentDeadline", "payment_deadline"),
        isCancelable = policy.boolean("isCancelable", "is_cancelable", "cancelable"),
        freeCancelDeadline = policy.string("freeCancelDeadline", "free_cancel_deadline"),
        cancelPolicy = policy.string("cancelPolicyDesc", "cancel_policy_desc", "cancelPolicy", "description")
            ?: detail.string("cancelPolicyDesc", "cancelPolicy"),
        message = root.string("message"),
        fetchedAt = System.currentTimeMillis(),
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

internal val ROOM_SUFFIX = Regex("""\s*[（(][^（）()]*[)）]\s*$""")

internal val ROOM_CAPTURE_SUFFIX = Regex("""\s*[（(]([^（）()]*)[)）]\s*$""")

internal fun hotelRoomSuffixes(raw: String): List<String> {
    val suffixes = mutableListOf<String>()
    var base = raw.trim()
    while (true) {
        val match = ROOM_CAPTURE_SUFFIX.find(base) ?: break
        val suffix = match.groupValues[1].trim()
        if (suffix.isNotEmpty()) suffixes.add(0, suffix)
        base = base.removeRange(match.range).trim()
        if (base.isEmpty()) break
    }
    return suffixes
}

internal fun hotelRoomBaseName(raw: String): String {
    var base = raw.trim()
    while (true) {
        val next = base.replace(ROOM_SUFFIX, "").trim()
        if (next == base || next.isEmpty()) break
        base = next
    }
    return base.ifEmpty { raw.trim() }
}

internal fun sortHotelResults(details: HotelSearchDetails): HotelSearchDetails {
    if (details.hotels.size < 2) return details
    val sortedBy = when {
        details.maxPrice != null && details.hotels.any { it.lowestPrice != null } -> "price"
        details.hotels.all { it.distanceMeters != null } -> "distance"
        else -> null
    } ?: return details
    val hotels = when (sortedBy) {
        "price" -> details.hotels.sortedWith(compareBy(nullsLast()) { it.lowestPrice })
        else -> details.hotels.sortedWith(compareBy(nullsLast()) { it.distanceMeters })
    }
    return details.copy(hotels = hotels, sortedBy = sortedBy)
}

internal fun parseHotelSearchTags(raw: String): HotelSearchTags? {
    val root = extractJsonElements(raw).filterIsInstance<JsonObject>()
        .firstOrNull { it["tags"] is JsonArray } ?: return null
    val tags = (root["tags"] as? JsonArray).orEmpty().mapNotNull { element ->
        val item = element as? JsonObject ?: return@mapNotNull null
        val name = item.string("name", "tagName", "tag_name") ?: return@mapNotNull null
        val category = item.string("category", "categoryName", "group") ?: "其他"
        HotelSearchTag(name = name, category = category)
    }
    val categories = LinkedHashMap<String, MutableList<String>>()
    tags.forEach { tag -> categories.getOrPut(tag.category) { mutableListOf() }.add(tag.name) }
    return HotelSearchTags(
        categories = categories.map { (category, names) -> HotelTagCategory(category, names.distinct()) },
        fetchedAt = System.currentTimeMillis(),
    )
}

internal fun parseHotelCancelQuote(raw: String, fallbackOrderNo: String): HotelCancelQuote? {
    val root = extractJsonElements(raw).filterIsInstance<JsonObject>()
        .firstOrNull {
            it["canCancel"] != null || it["can_cancel"] != null || it["confirmId"] != null ||
                it["confirm_id"] != null || it["cancelFee"] != null || it["bookingId"] != null ||
                it["orderNo"] != null || it["success"] != null || it["message"] != null
        } ?: return null
    val feeInfo = root["cancelFeeInfo"] as? JsonObject ?: root["feeInfo"] as? JsonObject
    val currency = root.string("currency", "currencyCode") ?: feeInfo?.string("currency")
    val feeAmount = root.number(
        "cancelFee", "cancelFeeAmount", "cancellationFee", "penaltyAmount", "cancelAmount",
        "feeAmount", "deductAmount", "penalty", "fee",
    ) ?: feeInfo?.number("amount", "fee", "cancelFee")
    val feeText = root.string("cancelFeeText", "feeText", "cancelFeeDesc", "penaltyDesc", "cancelDescription")
        ?: money(feeAmount, currency)
    val success = root.boolean("success")
    val canCancel = root.boolean("canCancel", "can_cancel") ?: success ?: true
    return HotelCancelQuote(
        orderNo = root.string("orderNo", "order_no") ?: fallbackOrderNo,
        hotelName = root.string("hotelName", "hotel_name"),
        canCancel = canCancel,
        feeText = feeText,
        currency = currency,
        policy = root.string("cancelPolicy", "cancelPolicyDesc", "policy", "cancelRule", "description"),
        message = root.string("message", "msg"),
        bookingId = root.string("bookingId", "booking_id"),
        confirmId = root.string("confirmId", "confirm_id", "confirmToken"),
        expiresAt = root.string("expireTime", "expiresAt", "expireAt", "validUntil", "deadline", "expireIn"),
        fetchedAt = System.currentTimeMillis(),
    )
}

internal fun parseHotelCancelResult(raw: String, orderNo: String, hotelName: String?): HotelCancelResult? {
    val root = extractJsonElements(raw).filterIsInstance<JsonObject>()
        .firstOrNull {
            it["success"] != null || it["orderStatus"] != null || it["order_status"] != null ||
                it["orderNo"] != null || it["message"] != null
        } ?: return null
    val status = root.string("orderStatus", "order_status", "status")
    val success = root.boolean("success")
    val cancelled = success == true || status?.contains("cancel", ignoreCase = true) == true
    return HotelCancelResult(
        orderNo = root.string("orderNo", "order_no") ?: orderNo,
        cancelled = cancelled,
        hotelName = root.string("hotelName", "hotel_name") ?: hotelName,
        orderStatus = status,
        message = root.string("message", "msg"),
        fetchedAt = System.currentTimeMillis(),
    )
}

private val WHITESPACE = Regex("\\s+")

internal fun money(value: Double?, currency: String?): String? {
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

private fun JsonObject.stringList(vararg names: String): List<String> {
    names.forEach { name ->
        val array = this[name] as? JsonArray ?: return@forEach
        val values = array.mapNotNull {
            (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotBlank)
        }
        if (values.isNotEmpty()) return values
    }
    return emptyList()
}

private fun JsonObject.imageUrls(): List<String> {
    val element = this["images"] ?: this["imageUrls"] ?: this["imagesList"] ?: return emptyList()
    return when (element) {
        is JsonPrimitive -> listOfNotNull(element.contentOrNull?.trim()?.takeIf(String::isNotBlank))
        is JsonArray -> element.mapNotNull { entry ->
            when (entry) {
                is JsonPrimitive -> entry.contentOrNull?.trim()?.takeIf(String::isNotBlank)
                is JsonObject -> entry.string("url", "imageUrl", "image_url")
                else -> null
            }
        }

        is JsonObject -> listOfNotNull(element.string("url", "imageUrl", "image_url"))
    }
}

private fun JsonObject.filterEntries(): List<String> {
    val filter = this["filter"] as? JsonObject ?: return emptyList()
    return filter.entries.mapNotNull { (key, value) ->
        val text = when (value) {
            is JsonPrimitive -> value.contentOrNull?.trim()
            is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }
                .filter(String::isNotEmpty)
                .joinToString("、")
                .takeIf(String::isNotBlank)

            is JsonObject -> value.toString().takeIf { it.isNotBlank() && it != "{}" }
        }
        text?.takeIf(String::isNotBlank)?.let { "$key=$it" }
    }
}

private fun JsonObject.starRating(): String? {
    val raw = this["starRating"] as? JsonPrimitive ?: return null
    val text = raw.contentOrNull?.trim().orEmpty()
    if (text.isEmpty()) return null
    return text.toDoubleOrNull()?.let { value ->
        if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
    } ?: text
}

private data class HotelPrice(val text: String, val amount: Double?, val currency: String?)

private fun JsonObject.hotelPrice(): HotelPrice? {
    val price = this["price"] as? JsonObject
    val amount = price?.number("lowestPrice", "lowest_price") ?: number("lowestPrice", "lowest_price")
    val currency = price?.string("currency") ?: string("currency")
    if (amount != null) {
        money(amount, currency)?.let { return HotelPrice(it, amount, currency) }
    }
    return price?.string("message")?.let { HotelPrice(it, null, currency) }
}

private fun JsonObject.cancellationTexts(): List<String> {
    val texts = mutableListOf<String>()
    (this["cancellationPolicies"] as? JsonArray)?.forEach { element ->
        (element as? JsonObject)?.cancellationText()?.let(texts::add)
    }
    string("cancelPolicy", "cancel_policy")?.let(texts::add)
    if (texts.isEmpty()) {
        when (boolean("cancelable")) {
            false -> texts.add("不可取消")
            true -> texts.add("可免费取消")
            null -> Unit
        }
    }
    return texts.distinct()
}

private fun JsonObject.cancellationText(): String? {
    string("description")?.let { return it }
    val amount = number("amount")
    val percent = number("percent")
    val from = string("fromDate", "from_date")?.take(10)
    return when {
        amount == null && percent == null -> null
        amount == 0.0 || percent == 0.0 -> "可免费取消"
        amount != null && from != null -> "$from 起取消扣 ${money(amount, "CNY")}"
        percent != null && from != null -> "$from 起取消扣 ${percent.toInt()}%"
        amount != null -> "取消扣 ${money(amount, "CNY")}"
        else -> "取消扣 ${percent?.toInt()}%"
    }
}
