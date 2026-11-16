package io.github.whywood.deepdeep.plugin.ride

import io.github.whywood.deepdeep.plugin.api.PluginHost
import io.github.whywood.deepdeep.plugin.api.PluginToolCall
import io.github.whywood.deepdeep.plugin.api.PluginToolResult
import io.github.whywood.deepdeep.plugin.api.UiConfirmRequest
import io.github.whywood.deepdeep.plugin.api.pluginTool
import io.github.whywood.deepdeep.plugin.api.textResult
import io.github.whywood.deepdeep.plugin.api.uiConfirmRequest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal class RideToolState {
    private val estimates = ConcurrentHashMap<String, RideEstimate>()
    private val lastOrders = ConcurrentHashMap<String, String>()

    fun rememberEstimate(sessionId: String, estimate: RideEstimate) {
        estimates[sessionId] = estimate
    }

    fun estimate(sessionId: String): RideEstimate? = estimates[sessionId]

    fun rememberOrder(sessionId: String, orderId: String?) {
        if (orderId.isNullOrBlank()) return
        lastOrders[sessionId] = orderId
    }

    fun lastOrder(sessionId: String): String? = lastOrders[sessionId]
}

private val PluginHost.rideTranslate: RideTranslate
    get() = { zh, en -> t(zh, en) }

internal fun rideTools(host: PluginHost, toolState: RideToolState = RideToolState()) = listOf(
    pluginTool(
        name = "ride_place_search",
        label = host.t("地点搜索", "Place search"),
        description = host.t(
            "搜索地点并返回接口给出的全部地点：名称、完整地址、城市和经纬度。参数 keywords、city（城市写完整名称，如 北京市）。",
            "Search places and return every place the API gives: name, full address, city and coordinates. Parameters: keywords, city (use the full city name, e.g. 北京市).",
        ),
        parameters = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("keywords", buildJsonObject { put("type", "string") })
                put("city", buildJsonObject { put("type", "string") })
            })
            put("required", JsonArray(listOf(JsonPrimitive("keywords"), JsonPrimitive("city"))))
        },
        promptSnippet = host.t(
            "打车前先用 ride_place_search 拿到起终点经纬度，把地址和坐标一起交给用户确认。",
            "Before estimating a ride, use ride_place_search to get coordinates and confirm the address with the user.",
        ),
        execute = { call ->
            perform(host, "maps_textsearch", call, { args ->
                buildJsonObject {
                    put("keywords", args["keywords"]?.jsonPrimitive?.contentOrNull.orEmpty())
                    put("city", args["city"]?.jsonPrimitive?.contentOrNull.orEmpty())
                }
            }) { payload ->
                val places = parseRidePlaces(payload)
                val query = listOfNotNull(
                    call.args["keywords"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
                    call.args["city"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
                ).joinToString(" · ")
                RideSummary(
                    text = ridePlacesText(places, host.rideTranslate),
                    cardTitle = when {
                        places.isEmpty() -> host.t("地点搜索", "Place search")
                        query.isBlank() -> host.tf("地点搜索（%d 个）", "Place search (%d)", places.size)
                        else -> host.tf("地点搜索：%s（%d 个）", "Place search: %s (%d)", query, places.size)
                    },
                )
            }
        },
    ),
    pluginTool(
        name = "ride_estimate",
        label = host.t("打车预估", "Ride estimate"),
        description = host.t(
            "预估打车价格，返回接口给出的全部车型：车型名、车型 ID/品类、参考价、券后价、优惠、币种、接驾等待、距离、时长、账号手机尾号和 traceId。参数 from_name/from_lng/from_lat/to_name/to_lng/to_lat，坐标来自 ride_place_search，经纬度都传字符串。",
            "Estimate ride fares and return every vehicle the API gives: names, product IDs/categories, list and discounted prices, coupons, currency, pickup ETA, distance, duration, account phone suffix and traceId. Parameters: from_name/from_lng/from_lat/to_name/to_lng/to_lat, coordinates from ride_place_search and all passed as strings.",
        ),
        parameters = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                listOf("from_name", "from_lng", "from_lat", "to_name", "to_lng", "to_lat").forEach { name ->
                    put(name, buildJsonObject { put("type", "string") })
                }
            })
            put("required", JsonArray(listOf("from_lng", "from_lat", "from_name", "to_lng", "to_lat", "to_name").map(::JsonPrimitive)))
        },
        promptSnippet = host.t(
            "用户确认起终点后，用 ride_estimate 报价；把车型、参考价和券后价展示给用户选择。",
            "After the user confirms the route, use ride_estimate for fares; show the options and prices for the user to pick.",
        ),
        execute = { call ->
            perform(host, "taxi_estimate", call, { args ->
                buildJsonObject {
                    listOf("from_name", "from_lng", "from_lat", "to_name", "to_lng", "to_lat").forEach { name ->
                        put(name, args[name]?.jsonPrimitive?.contentOrNull.orEmpty())
                    }
                }
            }) { payload ->
                val fromName = call.args["from_name"]?.jsonPrimitive?.contentOrNull
                val toName = call.args["to_name"]?.jsonPrimitive?.contentOrNull
                val estimate = parseRideEstimate(payload as? JsonObject, fromName, toName)
                toolState.rememberEstimate(call.sessionId, estimate)
                val route = estimate.route.ifBlank { host.t("本次行程", "This trip") }
                RideSummary(
                    text = rideEstimateText(estimate, host.rideTranslate),
                    cardTitle = if (estimate.isEmpty) {
                        host.t("打车价格", "Ride fares")
                    } else {
                        host.tf("打车价格：%s（%d 种车型）", "Ride fares: %s (%d options)", route, estimate.items.size)
                    },
                )
            }
        },
    ),
    pluginTool(
        name = "ride_app_link",
        label = host.t("打开滴滴", "Open Didi"),
        description = host.t(
            "生成打开滴滴 App 或小程序完成发单的链接。参数 from_lng/from_lat/to_lng/to_lat，可选 product_category。不会自动下单。",
            "Create a Didi app or mini program link to finish booking. Parameters: from_lng/from_lat/to_lng/to_lat, optional product_category. Never books automatically.",
        ),
        parameters = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                listOf("from_lng", "from_lat", "to_lng", "to_lat", "product_category").forEach { name ->
                    put(name, buildJsonObject { put("type", "string") })
                }
            })
            put("required", JsonArray(listOf("from_lng", "from_lat", "to_lng", "to_lat").map(::JsonPrimitive)))
        },
        execute = { call ->
            perform(host, "taxi_generate_ride_app_link", call, { args ->
                buildJsonObject {
                    listOf("from_lng", "from_lat", "to_lng", "to_lat").forEach { name ->
                        put(name, args[name]?.jsonPrimitive?.contentOrNull.orEmpty())
                    }
                    args["product_category"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let {
                        put("product_category", it)
                    }
                }
            }) { payload ->
                val link = parseRideLink(payload as? JsonObject)
                val text = if (link.isEmpty) {
                    host.t("没有生成可用的链接，请重新预估后再试。", "No ride link was generated. Estimate again and retry.")
                } else {
                    buildString {
                        append(host.t("已生成滴滴发单链接，请在滴滴内确认车型和价格后下单。", "Ride link ready. Confirm the ride and fare in Didi before booking."))
                        link.appLink?.takeIf { it.isNotBlank() }?.let {
                            append("\n").append(host.t("App 链接：$it", "App link: $it"))
                        }
                        link.miniprogramLink?.takeIf { it.isNotBlank() }?.let {
                            append("\n").append(host.t("小程序链接：$it", "Mini program link: $it"))
                        }
                    }
                }
                RideSummary(
                    text = text,
                    cardTitle = host.t("滴滴发单链接", "DiDi booking link"),
                )
            }
        },
    ),
    pluginTool(
        name = "ride_create_order",
        label = host.t("创建订单", "Create order"),
        description = host.t(
            "在用户已经看到车型和价格并明确确认后创建真实打车订单。参数 product_name（ride_estimate 返回的车型名）、confirm（必须为 true）、caller_car_phone 可选。调用后立即产生真实订单；不会自动支付，支付在滴滴内完成。",
            "Create a real ride after the user has seen the options and explicitly confirmed. Parameters: product_name from ride_estimate, confirm (must be true), optional caller_car_phone. Creates a real order immediately; payment happens in Didi.",
        ),
        parameters = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("product_name", buildJsonObject { put("type", "string") })
                put("caller_car_phone", buildJsonObject { put("type", "string") })
                put("confirm", buildJsonObject { put("type", "boolean") })
            })
            put("required", JsonArray(listOf(JsonPrimitive("product_name"), JsonPrimitive("confirm"))))
        },
        promptSnippet = host.t(
            "用户确认车型和价格后，用 ride_create_order 发单，confirm 必须为 true。",
            "After the user confirms the vehicle and fare, use ride_create_order with confirm true.",
        ),
        execute = { call ->
            createRideOrder(
                translate = host.rideTranslate,
                confirm = { request -> host.ui().confirm(request) },
                callTool = { tool, args -> DidiClient(host).call(tool, args) },
                currentKey = { runCatching { RideKeyStore(host.settings(), host.secrets()).currentKey() }.getOrNull() },
                warn = { message, error -> host.logs().warn(message, error) },
                toolState = toolState,
                call = call,
            )
        },
    ),
    pluginTool(
        name = "ride_cancel_order",
        label = host.t("取消订单", "Cancel order"),
        description = host.t(
            "取消已创建的打车订单。参数 order_id 可选（默认最近创建的订单），reason 可选，confirm 必须为 true。",
            "Cancel a created ride. Optional order_id (defaults to the latest created order), reason, and confirm must be true.",
        ),
        parameters = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("order_id", buildJsonObject { put("type", "string") })
                put("reason", buildJsonObject { put("type", "string") })
                put("confirm", buildJsonObject { put("type", "boolean") })
            })
            put("required", JsonArray(listOf(JsonPrimitive("confirm"))))
        },
        promptSnippet = host.t(
            "取消订单前先问用户确认，用户同意后用 ride_cancel_order，confirm 必须为 true。",
            "Ask the user before cancelling; after they agree, use ride_cancel_order with confirm true.",
        ),
        execute = { call ->
            cancelRideOrder(
                translate = host.rideTranslate,
                confirm = { request -> host.ui().confirm(request) },
                callTool = { tool, args -> DidiClient(host).call(tool, args) },
                currentKey = { runCatching { RideKeyStore(host.settings(), host.secrets()).currentKey() }.getOrNull() },
                warn = { message, error -> host.logs().warn(message, error) },
                toolState = toolState,
                call = call,
            )
        },
    ),
    pluginTool(
        name = "ride_order_status",
        label = host.t("打车订单", "Ride order"),
        description = host.t(
            "查询当前账号进行中或指定订单的状态、司机、车型、车牌、电话、评分、距上车点距离、预计到达时间和订单时间。参数 order_id 可选。查询次数超限时会明确提示限流，而不是当成没有订单。",
            "Query the current or specified active ride with status, driver, car, plate, phone, driver rating, distance to pickup, ETA and order times. Optional order_id. When the query quota is exceeded the result says rate limited instead of no order.",
        ),
        parameters = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject { put("order_id", buildJsonObject { put("type", "string") }) })
        },
        execute = { call ->
            perform(host, "taxi_query_order", call, { args ->
                buildJsonObject {
                    args["order_id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let {
                        put("order_id", it)
                    }
                }
            }) { payload ->
                val order = parseRideOrder(payload as? JsonObject)
                if (order.isEmpty) {
                    RideSummary(
                        text = host.t(
                            "当前没有进行中的滴滴订单。历史订单请在滴滴 App 查看。",
                            "No active Didi order found. Historical orders are available in the Didi app.",
                        ),
                        cardTitle = host.t("滴滴订单", "DiDi ride"),
                    )
                } else {
                    val status = rideStatusLabel(
                        code = order.statusCode ?: order.status,
                        statusText = order.statusText,
                        translate = host.rideTranslate,
                        includeCode = false,
                    )
                    RideSummary(
                        text = rideOrderText(order, host.rideTranslate),
                        cardTitle = if (status.isNullOrBlank()) {
                            host.t("滴滴订单", "DiDi ride")
                        } else {
                            host.tf("滴滴订单：%s", "DiDi ride: %s", status)
                        },
                    )
                }
            }
        },
    ),
    pluginTool(
        name = "ride_driver_location",
        label = host.t("司机位置", "Driver location"),
        description = host.t(
            "查询指定订单司机的实时位置：经纬度、地址，以及接口给出的司机、车牌、距离、预计到达、状态与更新时间。参数 order_id 必填。",
            "Query the driver's live location for an order: coordinates, address, and any driver, plate, distance, ETA, status and update time the API gives. Required: order_id.",
        ),
        parameters = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject { put("order_id", buildJsonObject { put("type", "string") }) })
            put("required", JsonArray(listOf(JsonPrimitive("order_id"))))
        },
        execute = { call ->
            perform(host, "taxi_get_driver_location", call, { args ->
                buildJsonObject { put("order_id", args["order_id"]?.jsonPrimitive?.contentOrNull.orEmpty()) }
            }) { payload ->
                val location = parseRideDriverLocation(payload as? JsonObject)
                RideSummary(
                    text = rideDriverLocationText(location, host.rideTranslate),
                    cardTitle = host.t("司机位置", "Driver location"),
                )
            }
        },
    ),
)

internal suspend fun createRideOrder(
    translate: RideTranslate,
    confirm: suspend (UiConfirmRequest) -> Boolean,
    callTool: suspend (String, JsonObject) -> RideResponse,
    currentKey: suspend () -> String?,
    warn: (String, Throwable?) -> Unit,
    toolState: RideToolState,
    call: PluginToolCall,
): PluginToolResult {
    if (call.args["confirm"]?.jsonPrimitive?.booleanOrNull != true) {
        return textResult(
            translate("需要你先确认车型和价格，确认后才能创建订单。", "Confirm the vehicle and fare before creating the order."),
            isError = true,
        )
    }
    val estimate = toolState.estimate(call.sessionId)
        ?: return textResult(translate("请先预估价格，再创建订单。", "Estimate the fare before creating the order."), isError = true)
    val productName = call.args["product_name"]?.jsonPrimitive?.contentOrNull.orEmpty()
    val item = estimate.items.firstOrNull { it.productName == productName }
        ?: return textResult(
            translate("没有找到这个车型，请从预估结果里选一个车型。", "That vehicle wasn't in the estimate. Choose one of the options."),
            isError = true,
        )
    val traceId = estimate.traceId
    val category = item.productCategory
    if (traceId.isNullOrBlank() || category.isNullOrBlank()) {
        return textResult(translate("这次预估缺少下单所需信息，请重新预估后再下单。", "This estimate is missing booking data. Estimate again before booking."), isError = true)
    }
    val route = estimate.route
    val price = item.priceDiscounted ?: item.priceText.orEmpty()
    val message = listOfNotNull(
        route.takeIf { it.isNotBlank() },
        item.productName,
        price.takeIf { it.isNotBlank() },
        item.eta?.let { translate("接驾约 $it", "pickup in $it") },
    ).joinToString(" · ")
    val confirmed = runCatching {
        confirm(
            uiConfirmRequest(
                title = translate("确认叫车", "Confirm ride"),
                message = message,
                confirmLabel = translate("确认叫车", "Book ride"),
                destructive = true,
            ),
        )
    }.getOrDefault(false)
    if (!confirmed) {
        return textResult(translate("已取消叫车。", "Ride creation cancelled."), isError = true)
    }
    return try {
        val response = callTool(
            "taxi_create_order",
            buildJsonObject {
                put("product_category", category)
                put("estimate_trace_id", traceId)
                call.args["caller_car_phone"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let {
                    put("caller_car_phone", it)
                }
            },
        )
        val order = parseRideOrder(response.payload as? JsonObject)
        toolState.rememberOrder(call.sessionId, order.orderId)
        textResult(
            text = rideCreatedOrderText(order, translate),
            details = rideDetails(
                payload = response.payload,
                raw = response.raw,
                cardTitle = translate("滴滴订单已创建", "DiDi ride created"),
            ),
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        val secret = runCatching { currentKey() }.getOrNull()
        val text = rideErrorText(error, secret, translate, "下单失败", "Could not create the order")
        warn("ride create order failed: ${summarizeDidiError(error.message, secret)}", error)
        textResult(text, details = rideErrorDetails(error), isError = true)
    }
}

internal suspend fun cancelRideOrder(
    translate: RideTranslate,
    confirm: suspend (UiConfirmRequest) -> Boolean,
    callTool: suspend (String, JsonObject) -> RideResponse,
    currentKey: suspend () -> String?,
    warn: (String, Throwable?) -> Unit,
    toolState: RideToolState,
    call: PluginToolCall,
): PluginToolResult {
    if (call.args["confirm"]?.jsonPrimitive?.booleanOrNull != true) {
        return textResult(translate("需要你确认后才能取消订单。", "Confirm before cancelling the order."), isError = true)
    }
    val orderId = call.args["order_id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        ?: toolState.lastOrder(call.sessionId)
        ?: return textResult(translate("没有找到要取消的订单，请提供订单号。", "No order to cancel was found. Provide an order ID."), isError = true)
    val confirmed = runCatching {
        confirm(
            uiConfirmRequest(
                title = translate("确认取消订单", "Cancel this ride"),
                message = translate("订单 $orderId 取消后无法恢复。", "Order $orderId cannot be restored after cancelling."),
                confirmLabel = translate("取消订单", "Cancel ride"),
                cancelLabel = translate("保留订单", "Keep ride"),
                destructive = true,
            ),
        )
    }.getOrDefault(false)
    if (!confirmed) {
        return textResult(translate("已保留订单，未取消。", "The order was kept and not cancelled."), isError = true)
    }
    return try {
        val response = callTool(
            "taxi_cancel_order",
            buildJsonObject {
                put("order_id", orderId)
                call.args["reason"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { put("reason", it) }
            },
        )
        val result = parseRideCancelResult(response.payload as? JsonObject)
        val message = result.message ?: when (result.cancelled) {
            true -> translate("订单已取消。", "Order cancelled.")
            false -> translate("订单暂时无法取消。", "The order cannot be cancelled yet.")
            null -> translate("已提交取消请求。", "Cancellation requested.")
        }
        textResult(
            text = message,
            details = rideDetails(
                payload = response.payload,
                raw = response.raw,
                cardTitle = translate("取消订单", "Cancel ride"),
            ),
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        val secret = runCatching { currentKey() }.getOrNull()
        val text = rideErrorText(error, secret, translate, "取消失败", "Couldn't cancel")
        warn("ride cancel order failed: ${summarizeDidiError(error.message, secret)}", error)
        textResult(text, details = rideErrorDetails(error), isError = true)
    }
}

private data class RideSummary(
    val text: String,
    val cardTitle: String?,
)

private suspend fun perform(
    host: PluginHost,
    tool: String,
    call: PluginToolCall,
    arguments: (JsonObject) -> JsonObject,
    summary: suspend (JsonElement) -> RideSummary,
): PluginToolResult {
    var upstreamRaw: String? = null
    return try {
        val response = DidiClient(host).call(tool, arguments(call.args))
        upstreamRaw = response.raw
        val outcome = summary(response.payload)
        textResult(
            text = outcome.text,
            details = rideDetails(
                payload = response.payload,
                raw = response.raw,
                cardTitle = outcome.cardTitle,
            ),
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        val secret = runCatching { RideKeyStore(host.settings(), host.secrets()).currentKey() }.getOrNull()
        val message = rideErrorText(error, secret, host.rideTranslate, "查询失败", "Lookup failed")
        host.logs().warn("ride $tool failed: ${summarizeDidiError(error.message, secret)}")
        textResult(
            text = message,
            details = rideErrorDetails(error) ?: upstreamRaw?.let { rideDetails(null, it, null) },
            isError = true,
        )
    }
}
