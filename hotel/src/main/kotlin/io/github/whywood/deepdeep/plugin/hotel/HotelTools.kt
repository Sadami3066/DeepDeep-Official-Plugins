package io.github.whywood.deepdeep.plugin.hotel

import io.github.whywood.deepdeep.plugin.api.PluginHost
import io.github.whywood.deepdeep.plugin.api.PluginToolCall
import io.github.whywood.deepdeep.plugin.api.PluginToolResult
import io.github.whywood.deepdeep.plugin.api.pluginTool
import io.github.whywood.deepdeep.plugin.api.textResult
import io.github.whywood.deepdeep.plugin.api.uiChoice
import io.github.whywood.deepdeep.plugin.api.uiChooseRequest
import io.github.whywood.deepdeep.plugin.api.uiConfirmRequest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

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
            "搜索酒店，返回名称、英文名、星级、参考价、距离、地址、全部标签与设施、hotelId 和官方预订链接。参数 query（用户原话）、place（地点）、place_type（默认城市）、check_in（YYYY-MM-DD）、nights、adults、size（1–10）、star_ratings（如 4,5）。只读，不锁价或下单。",
            "Search hotels and return names, English names, stars, reference prices, distance, addresses, all tags and facilities, hotelId and official booking links. Parameters: query, place, optional place_type (default city), check_in (YYYY-MM-DD), nights, adults, size (1–10), star_ratings (e.g. 4,5). Read-only; never locks a price or books.",
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
            "找酒店用 hotel_search；房型用 hotel_detail；预订用 hotel_book；订单列表用 hotel_orders，单笔订单用 hotel_order_detail。",
            "Use hotel_search for hotels, hotel_detail for room types, hotel_book for booking, hotel_orders for the order list and hotel_order_detail for one order.",
        ),
        promptGuidelines = listOf(
            host.t(
                "搜索和房型是只读操作；预订必须由用户明确确认酒店、房型、日期和总价后再调用 hotel_book，不能让用户未确认就下单。",
                "Search and room lookup are read-only. Only call hotel_book after the user explicitly confirms the hotel, room, dates and total price.",
            ),
            host.t(
                "工具结果已经是完整可读的事实：酒店名、英文名、星级、参考价、距离、地址、全部标签与设施、hotelId 和预订链接都在其中。直接基于这些事实回答，不要复述原始 JSON。",
                "Tool results are complete readable facts: name, English name, stars, reference price, distance, address, all tags and facilities, hotelId and booking link. Answer from those facts; don't repeat raw JSON.",
            ),
            host.t(
                "查房型时优先把 hotelId 传给 hotel_detail；预订时把所选方案的价格方案 ID 作为 rate_plan_id 传给 hotel_book。同一房型有多个价格方案时必须让用户明确选择，不要替用户选第一个。",
                "Pass hotelId to hotel_detail when available; pass the chosen plan's rate plan ID to hotel_book as rate_plan_id. When a room has several rate plans, let the user choose explicitly; never silently pick the first.",
            ),
        ),
        execute = { call -> execute(host, call) { service -> search(host, service, call) } },
    ),
    pluginTool(
        name = "hotel_detail",
        label = host.t("酒店房型", "Hotel rooms"),
        description = host.t(
            "查看酒店房型、床型、每晚均价、总价、早餐与份数、全部取消政策、价格方案 ID、入离日期、房间信息（面积/楼层/人数/窗/网络）和房型图片。同名房型的不同价格方案会归到同一房型下。参数 hotel_id 或 name 二选一，check_in/check_out（YYYY-MM-DD），rooms、adults。只读，不锁价或下单。",
            "View room types with beds, nightly averages, totals, breakfast and count, every cancellation policy, rate plan IDs, stay dates, room details (size/floor/occupancy/window/network) and room images. Rate plans of the same room type are grouped together. Provide hotel_id or name, optional check_in/check_out (YYYY-MM-DD), rooms and adults. Read-only; never locks or books.",
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
            "用户选好酒店后，用 hotel_detail 查房型；把房型、价格方案和官方预订链接交给用户。",
            "After the user picks a hotel, use hotel_detail for room types; give the user the rooms, rate plans and the official booking link.",
        ),
        promptGuidelines = listOf(
            host.t(
                "只读查询；用户确认房型、价格方案和总价后，才用 hotel_book 锁价并创建订单。",
                "Read-only lookup; use hotel_book only after the user confirms the room, rate plan and total price.",
            ),
        ),
        execute = { call -> execute(host, call) { service -> detail(host, service, call, toolState) } },
    ),
    pluginTool(
        name = "hotel_book",
        label = host.t("预订酒店", "Book hotel"),
        description = host.t(
            "在用户明确确认酒店、房型、日期和总价后创建真实酒店订单。参数 hotel_name、room_name、rate_plan_id（可选）、first_name、last_name、email、confirm（必须为 true）。有多个价格方案时优先传 rate_plan_id；未传且同一房型存在多个方案时会弹出原生选择。会先锁价再下单；成功返回订单号、支付类型和支付链接，支付由用户在 RollingGo 完成。",
            "Create a real hotel booking after the user explicitly confirms hotel, room, dates and total price. Parameters: hotel_name, room_name, optional rate_plan_id, first_name, last_name, email and confirm (must be true). Pass rate_plan_id when several rate plans exist; without it a native chooser appears when the room has several plans. It confirms the price then books; the result contains the order number, payment type and payment link, and payment happens on RollingGo.",
        ),
        parameters = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("hotel_name", buildJsonObject { put("type", "string") })
                put("room_name", buildJsonObject { put("type", "string") })
                put("rate_plan_id", buildJsonObject { put("type", "string"); put("description", host.t("hotel_detail 文本里的价格方案 ID", "Rate plan ID from the hotel_detail text")) })
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
            "用户确认预订后，用 hotel_book 锁价并创建订单；成功结果里有支付链接。",
            "After the user confirms, use hotel_book to lock the price and create the order; the result contains the payment link.",
        ),
        promptGuidelines = listOf(
            host.t(
                "下单前必须拿到用户对酒店、房型、价格方案、日期、总价的明确确认，并收集联系人名、姓和邮箱；不要在未确认时调用。",
                "Before booking, get explicit confirmation of the hotel, room, rate plan, dates and total price, and collect the contact first name, last name and email. Never call it unconfirmed.",
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
            "查询 RollingGo 订单列表，返回每笔订单的订单号、酒店、房型、入住/离店日期、晚数、房间数、早餐、取消政策、订单状态、总价、下单时间和支付链接；旧版返回还会带子订单号、参考号、地址、联系人和支付状态。参数 status 可选：ALL / PENDING / FINISHED。只读。单笔订单的完整信息（酒店确认号、酒店电话、入住人、支付截止时间等）用 hotel_order_detail 查询。",
            "List RollingGo hotel orders with order number, hotel, room, stay dates, nights, room count, breakfast, cancellation policy, status, total, order time and payment link; older responses also include sub-order, reference, address, contact and payment status. Optional status: ALL / PENDING / FINISHED. Read-only. For one order's full details (hotel confirmation number, hotel phone, guests, payment deadline), use hotel_order_detail.",
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
        promptSnippet = host.t(
            "查订单列表用 hotel_orders；用户要看某一笔的完整信息时，拿订单号调用 hotel_order_detail。",
            "Use hotel_orders for the order list; when the user wants one order's full details, call hotel_order_detail with its order number.",
        ),
        promptGuidelines = listOf(
            host.t(
                "订单列表只包含概要字段；用户问确认号、酒店电话、入住人、支付截止时间或免费取消截止时间时，必须再用 hotel_order_detail 查。",
                "The order list only has summary fields; when the user asks about the confirmation number, hotel phone, guests, payment deadline or free-cancellation deadline, call hotel_order_detail.",
            ),
        ),
        execute = { call -> execute(host, call) { service -> orders(host, service, call) } },
    ),
    pluginTool(
        name = "hotel_order_detail",
        label = host.t("订单详情", "Order details"),
        description = host.t(
            "按订单号查询单笔 RollingGo 订单的完整信息，返回订单号、状态、下单时间、酒店确认号、酒店名称/地址/电话/坐标、房型/床型/入离日期/晚数/房间数/早餐、联系人姓名/电话/邮箱、入住人名单、总价、支付方式/支付链接/支付截止时间、取消政策/是否可免费取消/免费取消截止时间和订单页面链接。参数 order_no：hotel_orders 返回的订单号。只读。",
            "Look up one RollingGo order by its order number and return the order number, status, order time, hotel confirmation number, hotel name/address/phone/coordinates, room/bed/stay dates/nights/room count/breakfast, contact name/phone/email, guest names, total, payment type/link/deadline, cancellation policy/free-cancellation availability/deadline and the order page link. Parameter order_no: the order number from hotel_orders. Read-only.",
        ),
        parameters = buildJsonObject {
            put("type", "object")
            put(
                "properties",
                buildJsonObject {
                    put("order_no", buildJsonObject { put("type", "string"); put("description", host.t("hotel_orders 返回的订单号", "Order number from hotel_orders")) })
                },
            )
            put("required", JsonArray(listOf(JsonPrimitive("order_no"))))
        },
        promptSnippet = host.t(
            "用户要看某一笔订单的完整信息时，用 hotel_order_detail 传 order_no。",
            "When the user wants one order's full details, use hotel_order_detail with order_no.",
        ),
        promptGuidelines = listOf(
            host.t(
                "只读查询；先通过 hotel_orders 拿到订单号，不要猜测或编造订单号。",
                "Read-only lookup; get the order number from hotel_orders instead of guessing one.",
            ),
        ),
        execute = { call -> execute(host, call) { service -> orderDetail(host, service, call) } },
    ),
)

private val PluginHost.hotelTranslate: HotelTranslate
    get() = { zh, en -> t(zh, en) }

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
    val translate = host.hotelTranslate
    val plan = when (val selection = selectHotelPlan(detail, roomName, call.args.string("rate_plan_id"))) {
        is HotelPlanSelection.Selected -> selection.plan
        is HotelPlanSelection.Multiple -> {
            val options = selection.plans.mapIndexed { index, candidate ->
                val choiceId = candidate.ratePlanId?.takeIf { it.isNotBlank() } ?: "plan-${index + 1}"
                choiceId to candidate
            }
            val choiceId = runCatching {
                host.ui().choose(
                    uiChooseRequest(
                        title = host.t("选择价格方案", "Choose a rate plan"),
                        message = host.t(
                            "这个房型有 ${options.size} 个价格方案，请选择一个再预订。",
                            "This room has ${options.size} rate plans. Choose one to continue.",
                        ),
                        options = options.map { (id, candidate) ->
                            uiChoice(
                                id = id,
                                label = hotelPlanChoiceLabel(candidate, translate),
                                description = listOfNotNull(candidate.bed, candidate.cancellation)
                                    .joinToString(" · ")
                                    .takeIf { it.isNotEmpty() },
                            )
                        },
                    ),
                )
            }.getOrNull()
            if (choiceId == null) {
                return textResult(host.t("已取消预订。", "Booking cancelled."), isError = true)
            }
            options.firstOrNull { it.first == choiceId }?.second
                ?: return textResult(host.t("没有找到所选价格方案，请重新查看房型。", "That rate plan wasn't found. Look up the rooms again."), isError = true)
        }
        is HotelPlanSelection.MissingRoom -> {
            val known = detail.rooms.map { it.name }.distinct().joinToString("、")
            return textResult(
                host.t("没有找到这个房型，请从房型列表里选一个：$known", "That room wasn't found. Choose one of: $known"),
                isError = true,
            )
        }
        is HotelPlanSelection.MissingRatePlan -> return textResult(
            host.t("没有找到价格方案 ID ${selection.ratePlanId}，请重新查看房型后选择。", "Rate plan ${selection.ratePlanId} wasn't found. Look up the rooms and choose again."),
            isError = true,
        )
    }
    val ratePlanId = plan.ratePlanId?.takeIf { it.isNotBlank() }
        ?: return textResult(host.t("这个价格方案缺少可预订信息，请换一个方案。", "This rate plan can't be booked right now. Choose another."), isError = true)
    val checkIn = plan.checkIn ?: detail.checkIn
    val checkOut = plan.checkOut ?: detail.checkOut
    if (checkIn.isNullOrBlank() || checkOut.isNullOrBlank()) {
        return textResult(host.t("缺少入离日期，请重新查看房型。", "The dates are missing. Look up the rooms again."), isError = true)
    }
    val confirmed = runCatching {
        host.ui().confirm(
            uiConfirmRequest(
                title = host.t("确认预订", "Confirm booking"),
                message = hotelPlanConfirmMessage(plan, detail, translate),
                confirmLabel = host.t("确认预订", "Book now"),
                destructive = true,
            ),
        )
    }.getOrDefault(false)
    if (!confirmed) {
        return textResult(host.t("已取消预订。", "Booking cancelled."), isError = true)
    }
    return try {
        val output = HotelService(host).book(
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
        val booking = output.details
        val complete = booking.copy(
            hotelName = detail.name,
            roomName = plan.name,
            ratePlanName = plan.ratePlanName,
            bed = plan.bed,
            meal = plan.meal,
            checkIn = checkIn,
            checkOut = checkOut,
            nights = hotelNights(checkIn, checkOut),
            roomCount = detail.roomCount,
            adultCount = detail.adultCount,
            totalPrice = plan.priceText ?: booking.totalPrice,
            totalDerived = plan.priceText != null && plan.totalDerived,
            currency = plan.currency ?: booking.currency,
            cancellation = plan.cancellations.ifEmpty { listOfNotNull(plan.cancellation) }.joinToString("；").takeIf { it.isNotBlank() },
            contactName = listOf(firstName, lastName).filter { it.isNotBlank() }.joinToString(" "),
            contactEmail = email,
        )
        textResult(
            text = hotelBookingText(complete, translate),
            details = withHotelRaw(
                HotelJson.encodeToJsonElement(HotelBookingDetails.serializer(), complete) as JsonObject,
                output.raw,
            ),
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        host.logs().warn("hotel book failed", error)
        val message = error.message?.takeIf { it.isNotBlank() }
            ?: host.t("订单没有创建成功，请稍后再试。", "The order wasn't created. Try again.")
        val raw = (error as? HotelToolException)?.raw
        textResult(
            message,
            details = raw?.let { withHotelRaw(JsonObject(emptyMap()), it) },
            isError = true,
        )
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
    val raw = (error as? HotelToolException)?.raw
    return textResult(
        error.message?.takeIf { it.isNotBlank() }
            ?: host.t("酒店查询失败，请稍后再试。", "Hotel lookup failed. Try again later."),
        details = raw?.let { withHotelRaw(JsonObject(emptyMap()), it) },
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
    val output = service.search(
        query = query,
        place = place,
        placeType = call.args.string("place_type")?.takeIf { it.isNotBlank() } ?: "城市",
        checkIn = call.args.string("check_in")?.takeIf { it.isNotBlank() },
        nights = nights,
        adults = adults,
        size = size,
        starRatings = call.args.string("star_ratings")?.takeIf { it.isNotBlank() },
    )
    return textResult(
        text = hotelSearchText(output.details, host.hotelTranslate),
        details = withHotelRaw(
            HotelJson.encodeToJsonElement(HotelSearchDetails.serializer(), output.details) as JsonObject,
            output.raw,
        ),
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
    val output = service.detail(
        hotelId = hotelId,
        name = name,
        checkIn = checkIn,
        checkOut = checkOut ?: hotelDateAfter(checkIn, 1),
        rooms = rooms,
        adults = adults,
    )
    val details = output.details.copy(roomCount = rooms, adultCount = adults)
    toolState.remember(call.sessionId, details)
    return textResult(
        text = hotelDetailText(details, host.hotelTranslate),
        details = withHotelRaw(
            HotelJson.encodeToJsonElement(HotelDetailDetails.serializer(), details) as JsonObject,
            output.raw,
        ),
    )
}

private suspend fun orders(host: PluginHost, service: HotelService, call: PluginToolCall): PluginToolResult {
    val output = service.orders(call.args.string("status")?.takeIf { it.isNotBlank() })
    return textResult(
        text = hotelOrdersText(output.details, host.hotelTranslate),
        details = withHotelRaw(
            HotelJson.encodeToJsonElement(HotelOrdersDetails.serializer(), output.details) as JsonObject,
            output.raw,
        ),
    )
}

private suspend fun orderDetail(host: PluginHost, service: HotelService, call: PluginToolCall): PluginToolResult {
    val orderNo = call.args.string("order_no").orEmpty()
    if (orderNo.isBlank()) {
        return textResult(host.t("请提供订单号。", "Provide an order number."), isError = true)
    }
    val output = service.orderDetail(orderNo)
    return textResult(
        text = hotelOrderDetailText(output.details, host.hotelTranslate),
        details = withHotelRaw(
            HotelJson.encodeToJsonElement(HotelOrderDetail.serializer(), output.details) as JsonObject,
            output.raw,
        ),
    )
}

private fun JsonObject?.string(name: String): String? =
    (this?.get(name) as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

private fun JsonObject?.int(name: String): Int? =
    (this?.get(name) as? JsonPrimitive)?.contentOrNull?.trim()?.toIntOrNull()

private fun JsonObject?.boolean(name: String): Boolean? =
    (this?.get(name) as? JsonPrimitive)?.contentOrNull?.trim()?.toBooleanStrictOrNull()
