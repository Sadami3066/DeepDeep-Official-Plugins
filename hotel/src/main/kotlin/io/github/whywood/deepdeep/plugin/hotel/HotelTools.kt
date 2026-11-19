package io.github.whywood.deepdeep.plugin.hotel

import io.github.whywood.deepdeep.plugin.api.PluginHost
import io.github.whywood.deepdeep.plugin.api.PluginToolCall
import io.github.whywood.deepdeep.plugin.api.PluginToolResult
import io.github.whywood.deepdeep.plugin.api.pluginTool
import io.github.whywood.deepdeep.plugin.api.textResult
import io.github.whywood.deepdeep.plugin.api.uiChoice
import io.github.whywood.deepdeep.plugin.api.uiChooseRequest
import io.github.whywood.deepdeep.plugin.api.uiConfirmRequest
import java.util.Locale
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
    private data class PendingCancel(val quote: HotelCancelQuote, val at: Long)

    private val details = ConcurrentHashMap<String, HotelDetailDetails>()

    private val cancels = ConcurrentHashMap<String, PendingCancel>()

    fun remember(sessionId: String, detail: HotelDetailDetails) {
        details[sessionId] = detail
    }

    fun detail(sessionId: String): HotelDetailDetails? = details[sessionId]

    fun rememberCancel(sessionId: String, quote: HotelCancelQuote) {
        cancels[cancelKey(sessionId, quote.orderNo)] = PendingCancel(quote, System.currentTimeMillis())
    }

    fun cancelQuote(sessionId: String, orderNo: String): HotelCancelQuote? {
        val pending = cancels[cancelKey(sessionId, orderNo)] ?: return null
        if (System.currentTimeMillis() - pending.at > CANCEL_QUOTE_TTL_MS) {
            cancels.remove(cancelKey(sessionId, orderNo))
            return null
        }
        return pending.quote
    }

    fun clearCancel(sessionId: String, orderNo: String) {
        cancels.remove(cancelKey(sessionId, orderNo))
    }

    private fun cancelKey(sessionId: String, orderNo: String): String = "$sessionId|$orderNo"

    private companion object {
        const val CANCEL_QUOTE_TTL_MS = 9 * 60 * 1000L
    }
}

private const val CONTACT_FIRST_KEY = "contact.first_name"
private const val CONTACT_LAST_KEY = "contact.last_name"
private const val CONTACT_EMAIL_KEY = "contact.email"

internal fun hotelTools(host: PluginHost, toolState: HotelToolState = HotelToolState()) = listOf(
    pluginTool(
        name = "hotel_search",
        label = host.t("搜索酒店", "Search hotels"),
        description = host.t(
            "搜索酒店，返回名称、英文名、星级、参考价、距离、地址、全部标签与设施、hotelId、酒店图片和官方预订链接。参数 query（用户原话）、place（地点）、place_type（默认城市）、check_in（YYYY-MM-DD）、nights、adults、size（1–10）、star_ratings（如 4,5）、max_price（每晚预算，元；未传时会从 query 识别「100 元以内」这类预算，并作为上游筛选条件）、distance_in_meter（距离上限，米）、required_tags（必须标签，先用 hotel_tags 查合法名称）、preferred_brands（偏好品牌）。结果带预算时仍会标注超预算与价格待确认的情况；只读，不锁价或下单。",
            "Search hotels and return names, English names, stars, reference prices, distance, addresses, all tags and facilities, hotelId, hotel photo and official booking links. Parameters: query, place, optional place_type (default city), check_in (YYYY-MM-DD), nights, adults, size (1–10), star_ratings (e.g. 4,5), max_price (nightly budget in CNY, inferred from the query when omitted and applied upstream), distance_in_meter, required_tags (list valid names with hotel_tags) and preferred_brands. Over-budget and unconfirmed-price results are still flagged. Read-only; never locks a price or books.",
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
                    put("max_price", buildJsonObject { put("type", "number"); put("description", host.t("每晚预算上限（元），传给上游按预算筛选；未传时也会从 query 识别", "Nightly budget cap in CNY, applied upstream; also inferred from query when omitted")) })
                    put("distance_in_meter", buildJsonObject { put("type", "integer"); put("description", host.t("距离上限（米），例如 1000 表示 1 公里内", "Max distance in meters, e.g. 1000 for 1 km")) })
                    put("required_tags", buildJsonObject {
                        put("type", "array")
                        put("items", buildJsonObject { put("type", "string") })
                        put("description", host.t("必须满足的酒店标签，标签名可先用 hotel_tags 查询", "Required hotel tags; list valid names with hotel_tags first"))
                    })
                    put("preferred_brands", buildJsonObject {
                        put("type", "array")
                        put("items", buildJsonObject { put("type", "string") })
                        put("description", host.t("偏好品牌，如 全季、汉庭", "Preferred brands, e.g. Ji Hotel, Hanting"))
                    })
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
                "工具结果正文是上游完整返回，另附使用说明；按使用说明回答，不要复述原始 JSON。",
                "The result body is the complete upstream output plus usage guidance; answer from the guidance and never repeat the raw JSON.",
            ),
            host.t(
                "用户提到预算时把预算传给 max_price：工具只标注超预算数量和最低超预算价，不删除结果；回答时要说明哪些结果超预算。",
                "When the user mentions a budget, pass it as max_price: the tool only marks the over-budget count and lowest over-budget price, never removing results; mention which results exceed it.",
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
            "查看酒店房型、床型、每晚均价、总价、早餐与份数、全部取消政策、价格方案 ID、入离日期、房间信息（面积/楼层/人数/窗/网络）和房型图片。同名房型的不同价格方案会归到同一房型下，组内按总价从低到高。参数 hotel_id 或 name 二选一，check_in/check_out（YYYY-MM-DD），rooms、adults。只读，不锁价或下单。",
            "View room types with beds, nightly averages, totals, breakfast and count, every cancellation policy, rate plan IDs, stay dates, room details (size/floor/occupancy/window/network) and room images. Rate plans of the same room type are grouped and sorted by total within each group. Provide hotel_id or name, optional check_in/check_out (YYYY-MM-DD), rooms and adults. Read-only; never locks or books.",
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
                    put("cancel_policy", buildJsonObject { put("type", "string"); put("description", host.t("只要可取消或不可取消：CANCELABLE / NON_CANCELABLE", "Cancellable filter: CANCELABLE / NON_CANCELABLE")) })
                    put("meal_type", buildJsonObject { put("type", "string"); put("description", host.t("餐食：WITH_BREAKFAST / SINGLE_BREAKFAST / DOUBLE_BREAKFAST / NO_MEAL", "Meal type: WITH_BREAKFAST / SINGLE_BREAKFAST / DOUBLE_BREAKFAST / NO_MEAL")) })
                    put("child_count", buildJsonObject { put("type", "integer"); put("description", host.t("每间房儿童人数", "Children per room")) })
                    put("child_age", buildJsonObject { put("type", "string"); put("description", host.t("儿童年龄，逗号分隔，如 4,8", "Child ages, comma separated, e.g. 4,8")) })
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
            "在用户明确确认酒店、房型、日期和总价后创建真实酒店订单。参数 hotel_name、room_name、rate_plan_id（可选）、first_name/last_name/email（可选，省略时用上次预订保存的联系人；邮箱首次必须提供）、guests（可选住客名单，多间房/多人时传）、customer_request（可选特殊要求）、confirm（必须为 true）。有多个价格方案时优先传 rate_plan_id；未传且同一房型存在多个方案时会弹出原生选择。会先锁价再下单；成功返回订单号、支付类型和支付链接，支付由用户在 RollingGo 完成。",
            "Create a real hotel booking after the user explicitly confirms hotel, room, dates and total price. Parameters: hotel_name, room_name, optional rate_plan_id, optional first_name/last_name/email (fall back to the contact saved from the last booking; the email is required the first time), optional guests for multiple rooms/guests, optional customer_request and confirm (must be true). Pass rate_plan_id when several rate plans exist; without it a native chooser appears when the room has several plans. It confirms the price then books; the result contains the order number, payment type and payment link, and payment happens on RollingGo.",
        ),
        parameters = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("hotel_name", buildJsonObject { put("type", "string") })
                put("room_name", buildJsonObject { put("type", "string") })
                put("rate_plan_id", buildJsonObject { put("type", "string"); put("description", host.t("hotel_detail 文本里的价格方案 ID", "Rate plan ID from the hotel_detail text")) })
                put("first_name", buildJsonObject { put("type", "string"); put("description", host.t("联系人名；省略时用上次预订保存的联系人", "Contact first name; falls back to the saved contact from the last booking")) })
                put("last_name", buildJsonObject { put("type", "string"); put("description", host.t("联系人姓；省略时用上次预订保存的联系人", "Contact last name; falls back to the saved contact from the last booking")) })
                put("email", buildJsonObject { put("type", "string"); put("description", host.t("联系邮箱；省略时用上次预订保存的邮箱", "Contact email; falls back to the saved email from the last booking")) })
                put("guests", buildJsonObject {
                    put("type", "array")
                    put("items", buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            put("room", buildJsonObject { put("type", "integer"); put("description", host.t("房间序号，从 1 开始", "Room number starting at 1")) })
                            put("first_name", buildJsonObject { put("type", "string") })
                            put("last_name", buildJsonObject { put("type", "string") })
                            put("is_adult", buildJsonObject { put("type", "boolean") })
                        })
                    })
                    put("description", host.t("住客名单；多间房或多人入住时传入", "Guest list for multiple rooms or guests"))
                })
                put("customer_request", buildJsonObject { put("type", "string"); put("description", host.t("特殊要求，如高楼层、无烟房", "Special requests such as high floor or non-smoking")) })
                put("confirm", buildJsonObject { put("type", "boolean") })
            })
            put(
                "required",
                JsonArray(
                    listOf(
                        JsonPrimitive("hotel_name"),
                        JsonPrimitive("room_name"),
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
            "查询 RollingGo 订单列表，返回每笔订单的订单号、酒店、房型、入住/离店日期、晚数、房间数、早餐、取消政策、订单状态、总价、下单时间和支付链接；旧版返回还会带子订单号、参考号、地址、联系人和支付状态。列表不包含取消原因。参数 status 可选：ALL / PENDING / FINISHED。只读。单笔订单的完整信息（取消原因、酒店确认号、酒店电话、入住人、支付截止时间等）用 hotel_order_detail 查询。",
            "List RollingGo hotel orders with order number, hotel, room, stay dates, nights, room count, breakfast, cancellation policy, status, total, order time and payment link; older responses also include sub-order, reference, address, contact and payment status. The list has no cancellation reason. Optional status: ALL / PENDING / FINISHED. Read-only. For one order's full details (cancellation reason, hotel confirmation number, hotel phone, guests, payment deadline), use hotel_order_detail.",
        ),
        parameters = buildJsonObject {
            put("type", "object")
            put(
                "properties",
                buildJsonObject {
                    put("status", buildJsonObject { put("type", "string"); put("description", host.t("ALL / PENDING / FINISHED", "ALL / PENDING / FINISHED")) })
                    put("start_date", buildJsonObject { put("type", "string"); put("description", host.t("开始日期 YYYY-MM-DD", "Start date YYYY-MM-DD")) })
                    put("end_date", buildJsonObject { put("type", "string"); put("description", host.t("结束日期 YYYY-MM-DD", "End date YYYY-MM-DD")) })
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
    pluginTool(
        name = "hotel_cancel",
        label = host.t("取消订单", "Cancel order"),
        description = host.t(
            "取消 RollingGo 酒店订单，两步：第一次调用（不带 confirm）只做预取消，返回取消罚金、取消政策、凭证与有效期，不会真正取消；用户明确同意后，带 confirm=true 再调用一次完成取消（不可撤销）。参数 order_no 必填，confirm 布尔，booking_id/confirm_id 一般不用（会复用预取消返回的凭证），description 可选取消备注。",
            "Cancel a RollingGo hotel order in two steps: the first call (without confirm) only pre-cancels and returns the cancellation fee, policy, credentials and their lifetime; nothing is cancelled. After explicit user consent, call again with confirm=true to cancel for real (irreversible). Required: order_no; optional: confirm, booking_id/confirm_id (normally reused from the pre-check) and description.",
        ),
        parameters = buildJsonObject {
            put("type", "object")
            put(
                "properties",
                buildJsonObject {
                    put("order_no", buildJsonObject { put("type", "string"); put("description", host.t("hotel_orders 返回的订单号", "Order number from hotel_orders")) })
                    put("confirm", buildJsonObject { put("type", "boolean"); put("description", host.t("用户明确同意取消后传 true；不传只查罚金", "Pass true only after explicit consent; omit to quote the fee")) })
                    put("booking_id", buildJsonObject { put("type", "string"); put("description", host.t("可选，预取消返回的预订号", "Optional booking id from the pre-check")) })
                    put("confirm_id", buildJsonObject { put("type", "string"); put("description", host.t("可选，预取消返回的取消凭证", "Optional cancellation credential from the pre-check")) })
                    put("description", buildJsonObject { put("type", "string"); put("description", host.t("可选的取消原因或备注", "Optional cancellation note")) })
                },
            )
            put("required", JsonArray(listOf(JsonPrimitive("order_no"))))
        },
        promptSnippet = host.t(
            "用户要取消订单时，先调用 hotel_cancel 只做预检，把罚金和政策告诉用户，确认后再带 confirm=true 真正取消。",
            "When the user wants to cancel, call hotel_cancel without confirm first, tell the user the fee and policy, then cancel with confirm=true after consent.",
        ),
        promptGuidelines = listOf(
            host.t(
                "取消不可撤销：必须先拿到预取消的罚金与政策并向用户确认，不能直接传 confirm=true；用户没有明确同意时不要取消。",
                "Cancellation is irreversible: quote the fee and policy first and get explicit consent; never call with confirm=true without it.",
            ),
            host.t(
                "预取消返回 canCancel=false 时，这笔订单不能在线取消，按返回消息引导用户联系 RollingGo 客服，不要重试。",
                "When the pre-check returns canCancel=false, the order cannot be cancelled online; follow its message and suggest RollingGo support instead of retrying.",
            ),
        ),
        execute = { call -> cancelHotel(host, toolState, call) },
    ),
    pluginTool(
        name = "hotel_tags",
        label = host.t("酒店筛选标签", "Hotel tags"),
        description = host.t(
            "获取 RollingGo 支持的酒店搜索标签（按分类返回，如品牌与评分、交通与支付、亲子家庭）。用户要某类设施/品牌/位置特征时，先用它确认合法标签名，再把标签名传给 hotel_search 的 required_tags（品牌也可以传 preferred_brands）。只读。",
            "List RollingGo hotel search tags grouped by category (brands and ratings, transport and payment, family, etc.). When the user asks for a facility, brand or location trait, look up valid tag names here and pass them to hotel_search as required_tags (brands can go to preferred_brands). Read-only.",
        ),
        parameters = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {})
        },
        promptSnippet = host.t(
            "用户要明确设施或标签时，先用 hotel_tags 查合法标签名，再传给 hotel_search。",
            "When the user wants specific facilities or tags, list valid names with hotel_tags first, then pass them to hotel_search.",
        ),
        promptGuidelines = listOf(
            host.t(
                "不要自己发明标签名；用户没提具体设施或品牌时，不要为了凑筛选去查标签。",
                "Do not invent tag names, and don't look up tags just to add filters the user didn't ask for.",
            ),
        ),
        execute = { call -> execute(host, call) { service -> tags(host, service) } },
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
    val settings = runCatching { host.settings() }.getOrNull()
    val firstName = call.args.string("first_name").orEmpty()
        .ifBlank { settings?.readSetting(CONTACT_FIRST_KEY).orEmpty() }
    val lastName = call.args.string("last_name").orEmpty()
        .ifBlank { settings?.readSetting(CONTACT_LAST_KEY).orEmpty() }
    val email = call.args.string("email").orEmpty()
        .ifBlank { settings?.readSetting(CONTACT_EMAIL_KEY).orEmpty() }
    val guests = call.args.objects("guests").mapNotNull { guest ->
        val guestFirst = guest.string("first_name").orEmpty()
        val guestLast = guest.string("last_name").orEmpty()
        if (guestFirst.isBlank() && guestLast.isBlank()) return@mapNotNull null
        HotelGuest(
            roomNum = guest.int("room")?.coerceIn(1, 10) ?: 1,
            firstName = guestFirst,
            lastName = guestLast,
            isAdult = guest.boolean("is_adult") != false,
        )
    }
    val customerRequest = call.args.string("customer_request")?.takeIf { it.isNotBlank() }
    if (hotelName.isBlank() || roomName.isBlank()) {
        return textResult(host.t("请补全酒店和房型。", "Provide the hotel and room."), isError = true)
    }
    if (email.isBlank()) {
        return textResult(
            host.t("请提供联系邮箱；首次预订会记住，下次可以省略。", "Provide the contact email; it will be remembered for next time."),
            isError = true,
        )
    }
    if ((firstName.isBlank() || lastName.isBlank()) && guests.isEmpty()) {
        return textResult(
            host.t("请提供联系人名和姓，或至少一位住客信息。", "Provide the contact name, or at least one guest."),
            isError = true,
        )
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
            guests = guests,
            customerRequest = customerRequest,
            expectedTotal = plan.totalPrice,
            expectedCurrency = plan.currency,
        )
        val booking = output.details
        runCatching {
            settings?.let { store ->
                if (firstName.isNotBlank()) store.put(CONTACT_FIRST_KEY, firstName)
                if (lastName.isNotBlank()) store.put(CONTACT_LAST_KEY, lastName)
                store.put(CONTACT_EMAIL_KEY, email)
            }
        }
        val complete = booking.copy(
            hotelName = detail.name,
            hotelId = hotelId,
            roomName = plan.name,
            ratePlanId = ratePlanId,
            ratePlanName = plan.ratePlanName,
            bed = plan.bed,
            meal = plan.meal,
            checkIn = checkIn,
            checkOut = checkOut,
            nights = hotelNights(checkIn, checkOut),
            roomCount = detail.roomCount,
            adultCount = detail.adultCount,
            totalPrice = booking.totalPrice ?: plan.priceText,
            totalDerived = booking.totalPrice == null && plan.totalDerived,
            currency = plan.currency ?: booking.currency,
            cancellation = plan.cancellations.ifEmpty { listOfNotNull(plan.cancellation) }.joinToString("；").takeIf { it.isNotBlank() },
            contactName = listOf(firstName, lastName).filter { it.isNotBlank() }.joinToString(" ")
                .ifBlank { guests.firstOrNull()?.let { listOf(it.firstName, it.lastName).filter(String::isNotBlank).joinToString(" ") }.orEmpty() },
            contactEmail = email,
            guestNames = guests.map { listOf(it.firstName, it.lastName).filter(String::isNotBlank).joinToString(" ") }
                .filter(String::isNotBlank),
            customerRequest = customerRequest,
        )
        textResult(
            text = "",
            details = hotelResultDetails(
                body = HotelJson.encodeToJsonElement(HotelBookingDetails.serializer(), complete) as JsonObject,
                raw = output.raw,
                guidance = hotelBookingGuidance(complete, translate),
                title = host.tf("预订成功 %s", "Booked %s", complete.orderNo),
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
            details = raw?.let { hotelErrorDetails(message, it) },
            isError = true,
        )
    }
}

private suspend fun tags(host: PluginHost, service: HotelService): PluginToolResult {
    val output = service.tags()
    val details = output.details
    return textResult(
        text = "",
        details = hotelResultDetails(
            body = HotelJson.encodeToJsonElement(HotelSearchTags.serializer(), details) as JsonObject,
            raw = output.raw,
            guidance = hotelTagsGuidance(details, host.hotelTranslate),
            title = host.tf("酒店标签（%d 个）", "Hotel tags (%d)", details.count),
        ),
    )
}

private suspend fun cancelHotel(
    host: PluginHost,
    toolState: HotelToolState,
    call: PluginToolCall,
): PluginToolResult {
    val orderNo = call.args.string("order_no").orEmpty()
    if (orderNo.isBlank()) {
        return textResult(host.t("请提供订单号。", "Provide an order number."), isError = true)
    }
    val translate = host.hotelTranslate
    if (call.args.boolean("confirm") != true) {
        return execute(host, call) { service ->
            val output = service.cancelQuote(orderNo)
            val quote = markAlreadyCancelled(service, output.details)
            toolState.rememberCancel(call.sessionId, quote)
            textResult(
                text = "",
                details = hotelResultDetails(
                    body = HotelJson.encodeToJsonElement(HotelCancelQuote.serializer(), quote) as JsonObject,
                    raw = output.raw,
                    guidance = hotelCancelQuoteGuidance(quote, translate),
                    title = host.tf("取消订单 %s（预检）", "Cancel order %s (quote)", orderNo),
                ),
            )
        }
    }
    val cached = toolState.cancelQuote(call.sessionId, orderNo)
    val quote = cached ?: run {
        val output = HotelService(host).cancelQuote(orderNo)
        toolState.rememberCancel(call.sessionId, output.details)
        output.details
    }
    val resolvedQuote = markAlreadyCancelled(HotelService(host), quote)
    if (!resolvedQuote.canCancel) {
        return textResult(
            hotelCancelQuoteGuidance(resolvedQuote, translate),
            details = hotelResultDetails(
                body = HotelJson.encodeToJsonElement(HotelCancelQuote.serializer(), resolvedQuote) as JsonObject,
                raw = null,
                guidance = hotelCancelQuoteGuidance(resolvedQuote, translate),
                title = host.tf("取消订单 %s（预检）", "Cancel order %s (quote)", orderNo),
            ),
            isError = true,
        )
    }
    val bookingId = call.args.string("booking_id") ?: quote.bookingId
    val confirmId = call.args.string("confirm_id") ?: quote.confirmId
    if (bookingId.isNullOrBlank() || confirmId.isNullOrBlank()) {
        return textResult(
            host.t(
                "上游没有返回取消凭证，这笔订单暂时无法在线取消；引导用户到 RollingGo 订单页或联系客服处理。",
                "The upstream returned no cancellation credentials; guide the user to the RollingGo order page or support.",
            ),
            isError = true,
        )
    }
    val message = listOfNotNull(
        quote.hotelName,
        host.t("订单号 $orderNo", "Order $orderNo"),
        quote.feeText?.let { host.t("取消罚金 $it", "Cancellation fee $it") },
        quote.policy,
    ).joinToString("\n")
    val confirmed = runCatching {
        host.ui().confirm(
            uiConfirmRequest(
                title = host.t("确认取消订单", "Confirm cancellation"),
                message = message.ifBlank { host.t("取消后不可恢复。", "Cancellation is irreversible.") },
                confirmLabel = host.t("确认取消", "Cancel order"),
                cancelLabel = host.t("再想想", "Keep order"),
                destructive = true,
            ),
        )
    }.getOrDefault(false)
    if (!confirmed) {
        return textResult(
            host.t("已取消取消操作，订单没有变动。", "Cancellation aborted; the order is unchanged."),
            isError = true,
        )
    }
    return execute(host, call) { service ->
        val output = service.cancelConfirm(orderNo, bookingId, confirmId, call.args.string("description"))
        toolState.clearCancel(call.sessionId, orderNo)
        val result = output.details
        textResult(
            text = "",
            details = hotelResultDetails(
                body = HotelJson.encodeToJsonElement(HotelCancelResult.serializer(), result) as JsonObject,
                raw = output.raw,
                guidance = hotelCancelResultGuidance(result, translate),
                title = host.tf("取消订单 %s", "Cancel order %s", result.orderNo),
            ),
        )
    }
}

private suspend fun markAlreadyCancelled(service: HotelService, quote: HotelCancelQuote): HotelCancelQuote {
    if (quote.canCancel || quote.alreadyCancelled) return quote
    val detail = runCatching { service.orderDetail(quote.orderNo).details }.getOrNull() ?: return quote
    val cancelled = detail.orderStatus?.contains("cancel", ignoreCase = true) == true ||
        detail.message?.contains("已取消") == true
    if (!cancelled) return quote
    return quote.copy(
        alreadyCancelled = true,
        message = "该订单已取消，无需操作",
        hotelName = detail.hotelName ?: quote.hotelName,
    )
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
    val message = error.message?.takeIf { it.isNotBlank() }
        ?: host.t("酒店查询失败，请稍后再试。", "Hotel lookup failed. Try again later.")
    val raw = (error as? HotelToolException)?.raw
    return textResult(
        message,
        details = raw?.let { hotelErrorDetails(message, it) },
        isError = true,
    )
}

private suspend fun search(host: PluginHost, service: HotelService, call: PluginToolCall): PluginToolResult {
    val query = call.args.string("query").orEmpty()
    val place = call.args.string("place").orEmpty()
    if (query.isBlank() || place.isBlank()) {
        return textResult(host.t("请提供搜索需求和地点。", "Provide a request and a place."), isError = true)
    }
    val requestedBudget = call.args.double("max_price")
    if (requestedBudget != null && (!requestedBudget.isFinite() || requestedBudget <= 0.0)) {
        return textResult(host.t("预算要大于 0。", "The budget must be greater than 0."), isError = true)
    }
    val maxPrice = requestedBudget ?: hotelBudgetFromQuery(query)
    val nights = call.args.int("nights")?.coerceIn(1, 30) ?: 1
    val adults = call.args.int("adults")?.coerceIn(1, 10) ?: 2
    val size = call.args.int("size")?.coerceIn(1, 10) ?: 5
    val distanceInMeter = call.args.int("distance_in_meter")?.takeIf { it > 0 }
    val requiredTags = call.args.strings("required_tags")
    val preferredBrands = call.args.strings("preferred_brands")
    val output = service.search(
        query = query,
        place = place,
        placeType = call.args.string("place_type")?.takeIf { it.isNotBlank() } ?: "城市",
        checkIn = call.args.string("check_in")?.takeIf { it.isNotBlank() },
        nights = nights,
        adults = adults,
        size = size,
        starRatings = call.args.string("star_ratings")?.takeIf { it.isNotBlank() },
        distanceInMeter = distanceInMeter,
        requiredTags = requiredTags,
        preferredBrands = preferredBrands,
        maxPricePerNight = maxPrice,
    )
    val details = sortHotelResults(withHotelBudget(output.details, maxPrice))
    val translate = host.hotelTranslate
    return textResult(
        text = "",
        details = hotelResultDetails(
            body = HotelJson.encodeToJsonElement(HotelSearchDetails.serializer(), details) as JsonObject,
            raw = output.raw,
            guidance = hotelSearchGuidance(details, translate),
            title = host.tf("酒店搜索：%s（%d 家）", "Hotels: %s (%d)", details.place, details.hotels.size),
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
        cancelPolicy = call.args.string("cancel_policy")?.takeIf { it.isNotBlank() }?.uppercase(Locale.ROOT),
        mealType = call.args.string("meal_type")?.takeIf { it.isNotBlank() }?.uppercase(Locale.ROOT),
        childCount = call.args.int("child_count")?.coerceIn(1, 10),
        childAges = call.args.string("child_age")?.takeIf { it.isNotBlank() },
    )
    val details = output.details.copy(roomCount = rooms, adultCount = adults)
    toolState.remember(call.sessionId, details)
    val translate = host.hotelTranslate
    val groupCount = details.roomGroups.ifEmpty { groupHotelRooms(details.rooms) }.size
    return textResult(
        text = "",
        details = hotelResultDetails(
            body = HotelJson.encodeToJsonElement(HotelDetailDetails.serializer(), details) as JsonObject,
            raw = output.raw,
            guidance = hotelDetailGuidance(details, translate),
            title = host.tf("房型：%s（%d 个房型）", "Rooms: %s (%d types)", details.name, groupCount),
        ),
    )
}

private suspend fun orders(host: PluginHost, service: HotelService, call: PluginToolCall): PluginToolResult {
    val output = service.orders(
        status = call.args.string("status")?.takeIf { it.isNotBlank() },
        startDate = call.args.string("start_date")?.takeIf { it.isNotBlank() },
        endDate = call.args.string("end_date")?.takeIf { it.isNotBlank() },
    )
    val details = output.details
    return textResult(
        text = "",
        details = hotelResultDetails(
            body = HotelJson.encodeToJsonElement(HotelOrdersDetails.serializer(), details) as JsonObject,
            raw = output.raw,
            guidance = hotelOrdersGuidance(details, host.hotelTranslate),
            title = host.tf("酒店订单（%d 笔）", "Hotel orders (%d)", details.orders.size),
        ),
    )
}

private suspend fun orderDetail(host: PluginHost, service: HotelService, call: PluginToolCall): PluginToolResult {
    val orderNo = call.args.string("order_no").orEmpty()
    if (orderNo.isBlank()) {
        return textResult(host.t("请提供订单号。", "Provide an order number."), isError = true)
    }
    val output = service.orderDetail(orderNo)
    val details = output.details
    return textResult(
        text = "",
        details = hotelResultDetails(
            body = HotelJson.encodeToJsonElement(HotelOrderDetail.serializer(), details) as JsonObject,
            raw = output.raw,
            guidance = hotelOrderDetailGuidance(details, host.hotelTranslate),
            title = host.tf("订单详情 %s", "Order %s", details.orderNo),
        ),
    )
}

private fun JsonObject?.string(name: String): String? =
    (this?.get(name) as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

private fun JsonObject?.int(name: String): Int? =
    (this?.get(name) as? JsonPrimitive)?.contentOrNull?.trim()?.toIntOrNull()

private fun JsonObject?.double(name: String): Double? =
    (this?.get(name) as? JsonPrimitive)?.contentOrNull?.trim()?.toDoubleOrNull()

private fun JsonObject?.boolean(name: String): Boolean? =
    (this?.get(name) as? JsonPrimitive)?.contentOrNull?.trim()?.toBooleanStrictOrNull()

private fun JsonObject?.strings(name: String): List<String> =
    (this?.get(name) as? JsonArray).orEmpty()
        .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotBlank) }
        .distinct()

private fun JsonObject?.objects(name: String): List<JsonObject> =
    (this?.get(name) as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }

private suspend fun io.github.whywood.deepdeep.plugin.api.PluginSettingsService.readSetting(key: String): String =
    runCatching { get(key) }.getOrNull().orEmpty()
