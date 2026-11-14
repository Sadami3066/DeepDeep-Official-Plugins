package io.github.whywood.deepdeep.plugin.hotel

import io.github.whywood.deepdeep.plugin.api.PluginHost
import io.github.whywood.deepdeep.plugin.api.PluginToolCall
import io.github.whywood.deepdeep.plugin.api.PluginToolResult
import io.github.whywood.deepdeep.plugin.api.pluginTool
import io.github.whywood.deepdeep.plugin.api.textResult
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal fun hotelTools(host: PluginHost) = listOf(
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
            "找酒店用 hotel_search；房型用 hotel_detail；订单用 hotel_orders。结果会显示为卡片，不要复述 JSON。",
            "Use hotel_search for hotels, hotel_detail for room types and hotel_orders for orders. Results render as cards; never repeat JSON.",
        ),
        promptGuidelines = listOf(
            host.t(
                "搜索和房型是只读操作；用户要预订时打开卡片里的 RollingGo 链接，不要在未确认价格时锁价或下单。",
                "Search and room lookup are read-only. When the user wants to book, open the RollingGo link on the card; never lock a price or book before the user confirms.",
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
                "只读查询；不要在用户确认价格前执行 price-confirm 或 book。",
                "Read-only lookup; don't run price-confirm or book before the user confirms the price.",
            ),
        ),
        execute = { call -> execute(host, call) { service -> detail(host, service, call) } },
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

private suspend fun detail(host: PluginHost, service: HotelService, call: PluginToolCall): PluginToolResult {
    val hotelId = call.args.string("hotel_id")?.toLongOrNull()
    val name = call.args.string("name")?.takeIf { it.isNotBlank() }
    if (hotelId == null && name == null) {
        return textResult(host.t("请提供酒店名称或 ID。", "Provide a hotel name or ID."), isError = true)
    }
    val checkIn = call.args.string("check_in")?.takeIf { it.isNotBlank() }
    val checkOut = call.args.string("check_out")?.takeIf { it.isNotBlank() }
    val details = service.detail(
        hotelId = hotelId,
        name = name,
        checkIn = checkIn,
        checkOut = checkOut ?: hotelDateAfter(checkIn, 1),
        rooms = call.args.int("rooms")?.coerceIn(1, 10) ?: 1,
        adults = call.args.int("adults")?.coerceIn(1, 10) ?: 2,
    )
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
