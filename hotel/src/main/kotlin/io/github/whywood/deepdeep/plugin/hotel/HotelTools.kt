package io.github.whywood.deepdeep.plugin.hotel

import io.github.whywood.deepdeep.plugin.api.PluginHost
import io.github.whywood.deepdeep.plugin.api.PluginToolCall
import io.github.whywood.deepdeep.plugin.api.PluginToolResult
import io.github.whywood.deepdeep.plugin.api.pluginTool
import io.github.whywood.deepdeep.plugin.api.textResult
import io.github.whywood.deepdeep.plugin.api.uiConfirmRequest
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap

internal class HotelToolState {
    private val details = ConcurrentHashMap<String, HotelDetailDetails>()

    fun remember(sessionId: String, detail: HotelDetailDetails) {
        details[sessionId] = detail
    }

    fun detail(sessionId: String): HotelDetailDetails? = details[sessionId]
}

internal fun hotelTools(host: PluginHost, toolState: HotelToolState = HotelToolState()) = listOf(
    pluginTool(
        name = "hotel_search",
        label = host.t("搜索酒店", "Search hotels"),
        description = host.t(
            "搜索酒店，返回名称、星级、参考价、地址、标签和官方预订链接。参数 query（用户原话）、place（地点）、place_type（默认城市）、check_in（YYYY-MM-DD）、nights、adults、size（1–10）、star_ratings（如 4,5）。只读，不锁价或下单。",
            "Search hotels and return names, stars, reference prices, addresses, tags and official booking links. Parameters: query, place, optional place_type (default city), check_in (YYYY-MM-DD), nights, adults, size (1–10), star_ratings (e.g. 4,5). Read-only; never locks a price or books.",
        ),
        parameters = buildJsonObject {
            put("type", "object")
            put(
                "properties",
                buildJsonObject {
                    put("query", buildJsonObject { put("type", "string"); put("description", host.t("用户的原始需求", "The user's original request")) })
                    put("place", buildJsonObject { put("type", "string"); put("description", host.t("城市、景点或地标", "City, attraction or landmark")) })
                    put("place_type", buildJsonObject { put("type", "string"); put("description", host.t("城市 / 景点 / 机场 / 火车站 / 地铁站 / 酒店 / 区/县 / 详细地址", "City / attraction / airport / train station / metro / hotel / district / address")) })
                    put("check_in", buildJsonObject { put("type", "string"); put("description", host.t("入住日期 YYYY-MM-DD", "Check-in YYYY-MM-DD")) })
                    put("nights", buildJsonObject { put("type", "integer"); put("description", host.t("入住晚数，1–30", "Nights, 1–30")) })
                    put("adults", buildJsonObject { put("type", "integer"); put("description", host.t("每间房成人数，1–10", "Adults per room, 1–10")) })
                    put("size", buildJsonObject { put("type", "integer"); put("description", host.t("返回数量，1–10", "Result count, 1–10")) })
                    put("star_ratings", buildJsonObject { put("type", "string"); put("description", host.t("星级范围，如 4,5", "Star range, e.g. 4,5")) })
                },
            )
            put("required", JsonArray(listOf(JsonPrimitive("query"), JsonPrimitive("place"))))
        },
        promptSnippet = host.t(
            "找酒店用 hotel_search；房型用 hotel_detail；预订用 hotel_book；订单用 hotel_orders。结果会显示为卡片，不要复述 JSON。",
            "Use hotel_search for hotels, hotel_detail for room types, hotel_book for booking and hotel_orders for orders. Results render as cards; never repeat JSON.",
        ),
        promptGuidelines = listOf(
            host.t(
                "搜索和房型是只读操作；预订必须由用户明确确认酒店、房型、日期和总价后再调用 hotel_book，不能让用户未确认就下单。",
                "Search and room lookup are read-only. Only call hotel_book after the user explicitly confirms the hotel, room, dates and total price.",
            ),
            host.t(
                "不要展示 hotelId、ratePlanId、referenceNo 或原始 JSON；卡片已经展示时不要重复列表。",
                "Don't expose hotelId, ratePlanId, referenceNo or raw JSON; don't repeat the list when a card is already visible.",
            ),
        ),
        execute = { call -> execute(host, call) { service -> search(host, service, call) } },
    ),
    pluginTool(
        name = "hotel_detail",
        label = host.t("酒店房型", "Hotel rooms"),
        description = host.t(
            "查看酒店房型、床型、总价和取消政策。参数 hotel_id 或 name 二选一，check_in/check_out（YYYY-MM-DD），rooms、adults。只读，不锁价或下单。",
            "View room types, beds, total prices and cancellation policies. Provide hotel_id or name, optional check_in/check_out (YYYY-MM-DD), rooms and adults. Read-only; never locks or books.",
        ),
        parameters = buildJsonObject {
            put("type", "object")
            put(
                "properties",
                buildJsonObject {
                    put("hotel_id", buildJsonObject { put("type", "string"); put("description", host.t("酒店 ID（可选，通常用名称）", "Hotel ID (optional; name is preferred)")) })
                    put("name", buildJsonObject { put("type", "string"); put("description", host.t("酒店名称", "Hotel name")) })
                    put("check_in", buildJsonObject { put("type", "string"); put("description", host.t("入住日期 YYYY-MM-DD", "Check-in YYYY-MM-DD")) })
                    put("check_out", buildJsonObject { put("type", "string"); put("description", host.t("离店日期 YYYY-MM-DD", "Check-out YYYY-MM-DD")) })
                    put("rooms", buildJsonObject { put("type", "integer"); put("description", host.t("房间数，1–10", "Rooms, 1–10")) })
                    put("adults", buildJsonObject { put("type", "integer"); put("description", host.t("每间房成人数，1–10", "Adults per room, 1–10")) })
                },
            )
        },
        promptSnippet = host.t(
            "用户选好酒店后，用 hotel_detail 查房型；把房型和官方预订链接交给用户。",
            "After the user picks a hotel, use hotel_detail for room types; give the user the rooms and the official booking link.",
        ),
        promptGuidelines = listOf(
            host.t(
                "只读查询；用户确认房型和总价后，才用 hotel_book 锁价并创建订单。",
                "Read-only lookup; use hotel_book only after the user confirms the room and total price.",
            ),
        ),
        execute = { call -> execute(host, call) { service -> detail(host, service, call, toolState) } },
    ),
    pluginTool(
        name = "hotel_book",
        label = host.t("预订酒店", "Book hotel"),
        description = host.t(
            "在用户明确确认酒店、房型、日期和总价后创建真实酒店订单。参数 hotel_name、room_name、first_name、last_name、email、confirm（必须为 true）。会先锁价再下单；成功返回支付链接，支付由用户在 RollingGo 完成。",
            "Create a real hotel booking after the user explicitly confirms hotel, room, dates and total price. Parameters: hotel_name, room_name, first_name, last_name, email and confirm (must be true). It confirms the price then books; the result contains a payment link, and payment happens on RollingGo.",
        ),
        parameters = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("hotel_name", buildJsonObject { put("type", "string") })
                put("room_name", buildJsonObject { put("type", "string") })
                put("first_name", buildJsonObject { put("type", "string") })
                put("last_name", buildJsonObject { put("type", "string") })
                put("email", buildJsonObject { put("type", "string") })
                put("confirm", buildJsonObject { put("type", "boolean") })
            })
            put(
                "required",
                JsonArray(
                    listOf(
                        JsonPrimitive("hotel_name"),
                        JsonPrimitive("room_name"),
                        JsonPrimitive("first_name"),
                        JsonPrimitive("last_name"),
                        JsonPrimitive("email"),
                        JsonPrimitive("confirm"),
                    ),
                ),
            )
        },
        promptSnippet = host.t(
            "用户确认预订后，用 hotel_book 锁价并创建订单；成功卡片会给出支付链接。",
            "After the user confirms, use hotel_book to lock the price and create the order; the card shows the payment link.",
        ),
        promptGuidelines = listOf(
            host.t(
                "下单前必须拿到用户对酒店、房型、日期、总价的明确确认，并收集联系人名、姓和邮箱；不要在未确认时调用。",
                "Before booking, get explicit confirmation of the hotel, room, dates and total price, and collect the contact first name, last name and email. Never call it unconfirmed.",
            ),
            host.t(
                "插件只创建待支付订单并返回官方支付链接，不代替用户支付；不要把支付链接说成已经付款。",
                "The plugin only creates a pending order and returns the official payment link. It never pays for the user; don't claim payment is complete.",
            ),
        ),
        execute = { call -> book(host, toolState, call) },
    ),
    pluginTool(
        name = "hotel_orders",
        label = host.t("酒店订单", "Hotel orders"),
        description = host.t(
            "查询 RollingGo 订单列表。参数 status 可选：ALL / PENDING / FINISHED。只读。",
            "List RollingGo hotel orders. Optional status: ALL / PENDING / FINISHED. Read-only.",
        ),
        parameters = buildJsonObject {
            put("type", "object")
            put(
                "properties",
                buildJsonObject {
                    put("status", buildJsonObject { put("type", "string"); put("description", host.t("ALL / PENDING / FINISHED", "ALL / PENDING / FINISHED")) })
                },
            )
        },
        execute = { call -> execute(host, call) { service -> orders(host, service, call) } },
    ),
)

private suspend fun book(host: PluginHost, toolState: HotelToolState, call: PluginToolCall): PluginToolResult {
    if (call.args.boolean("confirm") != true) {
        return textResult(host.t("需要你先确认酒店、房型、日期和总价，确认后才能预订。", "Confirm the hotel, room, dates and total price before booking."), isError = true)
    }
    val hotelName = call.args.string("hotel_name").orEmpty()
    val roomName = call.args.string("room_name").orEmpty()
    val firstName = call.args.string("first_name").orEmpty()
    val lastName = call.args.string("last_name").orEmpty()
    val email = call.args.string("email").orEmpty()
    if (hotelName.isBlank() || roomName.isBlank() || firstName.isBlank() || lastName.isBlank() || email.isBlank()) {
        return textResult(host.t("请补全酒店、房型、联系人姓名和邮箱。", "Provide the hotel, room, contact name and email."), isError = true)
    }
    val detail = toolState.detail(call.sessionId)
        ?: return textResult(host.t("请先查看这家酒店的房型，再确认预订。", "Look up the room types before booking."), isError = true)
    val hotelId = detail.hotelId
        ?: return textResult(host.t("这家酒店缺少可预订信息，请重新查看房型。", "This hotel is missing booking details. Look up the rooms again."), isError = true)
    val room = detail.rooms.firstOrNull { it.name == roomName }
        ?: detail.rooms.firstOrNull { it.name.contains(roomName) || roomName.contains(it.name) }
        ?: return textResult(host.t("没有找到这个房型，请从房型卡片里选一个。", "That room wasn't found. Choose one from the room card."), isError = true)
    val ratePlanId = room.ratePlanId
        ?: return textResult(host.t("这个房型暂时不能预订，请换一个房型。", "This room can't be booked right now. Choose another."), isError = true)
    val checkIn = detail.checkIn
    val checkOut = detail.checkOut
    if (checkIn.isNullOrBlank() || checkOut.isNullOrBlank()) {
        return textResult(host.t("缺少入离日期，请重新查看房型。", "The dates are missing. Look up the rooms again."), isError = true)
    }
    val confirmed = runCatching {
        host.ui().confirm(
            uiConfirmRequest(
                title = host.t("确认预订", "Confirm booking"),
                message = listOfNotNull(
                    detail.name,
                    room.name,
                    "$checkIn → $checkOut",
                    room.priceText,
                ).joinToString(" · "),
                confirmLabel = host.t("确认预订", "Book now"),
                destructive = true,
            ),
        )
    }.getOrDefault(false)
    if (!confirmed) {
        return textResult(host.t("已取消预订。", "Booking cancelled."), isError = true)
    }
    return try {
        val booking = HotelService(host).book(
            hotelId = hotelId,
            ratePlanId = ratePlanId,
            checkIn = checkIn,
            checkOut = checkOut,
            rooms = detail.roomCount,
            adults = detail.adultCount,
            firstName = firstName,
            lastName = lastName,
            email = email,
        )
        val complete = booking.copy(
            hotelName = detail.name,
            roomName = room.name,
            checkIn = checkIn,
            checkOut = checkOut,
            totalPrice = room.priceText ?: booking.totalPrice,
        )
        val paymentNote = if (complete.paymentUrl.isNullOrBlank()) {
            host.t("订单已创建，请在 RollingGo 完成支付。", "Order created. Complete payment on RollingGo.")
        } else {
            host.t("订单已创建，等待支付；请打开卡片里的支付链接完成付款。", "Order created and awaiting payment. Open the payment link on the card to pay.")
        }
        textResult(
            text = paymentNote,
            details = HotelJson.encodeToJsonElement(HotelBookingDetails.serializer(), complete) as JsonObject,
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        host.logs().warn("hotel book failed", error)
        val message = error.message?.takeIf { it.isNotBlank() }
            ?: host.t("订单没有创建成功，请稍后再试。", "The order wasn't created. Try again.")
        textResult(message, isError = true)
    }
}

private suspend fun execute(
    host: PluginHost,
    call: PluginToolCall,
    block: suspend (HotelService) -> PluginToolResult,
): PluginToolResult = try {
    block(HotelService(host))
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Throwable) {
    host.logs().warn("hotel tool ${call.callId} failed", error)
    textResult(
        error.message?.takeIf { it.isNotBlank() }
            ?: host.t("酒店查询失败，请稍后再试。", "Hotel lookup failed. Try again later."),
        isError = true,
    )
}

private suspend fun search(host: PluginHost, service: HotelService, call: PluginToolCall): PluginToolResult {
    val query = call.args.string("query").orEmpty()
    val place = call.args.string("place").orEmpty()
    if (query.isBlank() || place.isBlank()) {
        return textResult(host.t("请提供搜索需求和地点。", "Provide a request and a place."), isError = true)
    }
    val nights = call.args.int("nights")?.coerceIn(1, 30) ?: 1
    val adults = call.args.int("adults")?.coerceIn(1, 10) ?: 2
    val size = call.args.int("size")?.coerceIn(1, 10) ?: 5
    val details = service.search(
        query = query,
        place = place,
        placeType = call.args.string("place_type")?.takeIf { it.isNotBlank() } ?: "城市",
        checkIn = call.args.string("check_in")?.takeIf { it.isNotBlank() },
        nights = nights,
        adults = adults,
        size = size,
        starRatings = call.args.string("star_ratings")?.takeIf { it.isNotBlank() },
    )
    val text = if (details.hotels.isEmpty()) {
        host.t("没有找到符合条件的酒店，可以放宽星级或距离再试。", "No hotels matched. Try relaxing stars or distance.")
    } else {
        details.hotels.take(size).joinToString("\n") { hotel ->
            listOfNotNull(
                hotel.name,
                hotel.starRating?.let { "$it★" },
                hotel.priceText,
                hotel.address,
            ).joinToString(" · ")
        }
    }
    return textResult(
        text = text,
        details = HotelJson.encodeToJsonElement(HotelSearchDetails.serializer(), details) as JsonObject,
    )
}

private suspend fun detail(host: PluginHost, service: HotelService, call: PluginToolCall, toolState: HotelToolState): PluginToolResult {
    val hotelId = call.args.string("hotel_id")?.toLongOrNull()
    val name = call.args.string("name")?.takeIf { it.isNotBlank() }
    if (hotelId == null && name == null) {
        return textResult(host.t("请提供酒店名称或 ID。", "Provide a hotel name or ID."), isError = true)
    }
    val checkIn = call.args.string("check_in")?.takeIf { it.isNotBlank() }
    val checkOut = call.args.string("check_out")?.takeIf { it.isNotBlank() }
    val rooms = call.args.int("rooms")?.coerceIn(1, 10) ?: 1
    val adults = call.args.int("adults")?.coerceIn(1, 10) ?: 2
    val details = service.detail(
        hotelId = hotelId,
        name = name,
        checkIn = checkIn,
        checkOut = checkOut ?: hotelDateAfter(checkIn, 1),
        rooms = rooms,
        adults = adults,
    ).copy(roomCount = rooms, adultCount = adults)
    val text = if (details.rooms.isEmpty()) {
        host.t("${details.name} 暂时没有可订房型。", "No rooms are available at ${details.name} right now.")
    } else {
        buildString {
            append(details.name)
            if (details.checkIn != null && details.checkOut != null) {
                append(" · ").append(details.checkIn).append(" → ").append(details.checkOut)
            }
            append("\n")
            append(
                details.rooms.take(6).joinToString("\n") { room ->
                    listOfNotNull(room.name, room.bed, room.priceText).joinToString(" · ")
                },
            )
        }
    }
    toolState.remember(call.sessionId, details)
    return textResult(
        text = text,
        details = HotelJson.encodeToJsonElement(HotelDetailDetails.serializer(), details) as JsonObject,
    )
}

private suspend fun orders(host: PluginHost, service: HotelService, call: PluginToolCall): PluginToolResult {
    val details = service.orders(call.args.string("status")?.takeIf { it.isNotBlank() })
    val text = if (details.orders.isEmpty()) {
        host.t("暂时没有查到酒店订单。", "No hotel orders found.")
    } else {
        details.orders.take(5).joinToString("\n") { order ->
            listOfNotNull(
                order.hotelName,
                order.checkIn,
                order.status,
                order.totalPrice,
            ).joinToString(" · ")
        }
    }
    return textResult(
        text = text,
        details = HotelJson.encodeToJsonElement(HotelOrdersDetails.serializer(), details) as JsonObject,
    )
}

private fun JsonObject?.string(name: String): String? =
    (this?.get(name) as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

private fun JsonObject?.int(name: String): Int? =
    (this?.get(name) as? JsonPrimitive)?.contentOrNull?.trim()?.toIntOrNull()

private fun JsonObject?.boolean(name: String): Boolean? =
    (this?.get(name) as? JsonPrimitive)?.contentOrNull?.trim()?.toBooleanStrictOrNull()
