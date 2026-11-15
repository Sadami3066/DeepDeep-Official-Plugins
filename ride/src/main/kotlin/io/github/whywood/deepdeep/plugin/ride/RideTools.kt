package io.github.whywood.deepdeep.plugin.ride

import io.github.whywood.deepdeep.plugin.api.PluginHost
import io.github.whywood.deepdeep.plugin.api.PluginToolCall
import io.github.whywood.deepdeep.plugin.api.PluginToolResult
import io.github.whywood.deepdeep.plugin.api.pluginTool
import io.github.whywood.deepdeep.plugin.api.textResult
import io.github.whywood.deepdeep.plugin.api.uiConfirmRequest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap

internal class RideToolState {
    private val estimates = ConcurrentHashMap<String, RideEstimate>()
    private val lastOrders = ConcurrentHashMap<String, String>()

    fun rememberEstimate(sessionId: String, estimate: RideEstimate) {
        if (estimate.traceId.isNullOrBlank()) return
        estimates[sessionId] = estimate
    }

    fun estimate(sessionId: String): RideEstimate? = estimates[sessionId]

    fun rememberOrder(sessionId: String, orderId: String?) {
        if (orderId.isNullOrBlank()) return
        lastOrders[sessionId] = orderId
    }

    fun lastOrder(sessionId: String): String? = lastOrders[sessionId]
}

internal fun rideTools(host: PluginHost, toolState: RideToolState = RideToolState()) = listOf(
    pluginTool(
        name = "ride_place_search",
        label = host.t("地点搜索", "Place search"),
        description = host.t(
            "搜索地点并返回经纬度。参数 keywords、city。",
            "Search places and return coordinates. Parameters: keywords, city.",
        ),
        parameters = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("keywords", buildJsonObject { put("type", "string") })
                put("city", buildJsonObject { put("type", "string") })
            })
            put("required", JsonArray(listOf(JsonPrimitive("keywords"), JsonPrimitive("city"))))
        },
        execute = { call ->
            perform(host, "maps_textsearch", call, { args ->
                buildJsonObject {
                    put("keywords", args["keywords"]?.jsonPrimitive?.contentOrNull.orEmpty())
                    put("city", args["city"]?.jsonPrimitive?.contentOrNull.orEmpty())
                }
            }) { payload ->
                val places = parseRidePlaces(payload)
                if (places.isEmpty()) {
                    host.t("没有找到匹配的地点。", "No matching places found.")
                } else {
                    val names = places.mapNotNull { it.displayName }.distinct().take(3)
                    if (names.isEmpty()) {
                        host.tf("找到 %d 个地点", "Found %d places", places.size)
                    } else {
                        host.tf("找到 %d 个地点：%s", "Found %d places: %s", places.size, names.joinToString("、"))
                    }
                }
            }
        },
    ),
    pluginTool(
        name = "ride_estimate",
        label = host.t("打车预估", "Ride estimate"),
        description = host.t(
            "预估打车车型与价格。参数 from_name/from_lng/from_lat/to_name/to_lng/to_lat。",
            "Estimate ride options and fares. Parameters: from_name/from_lng/from_lat/to_name/to_lng/to_lat.",
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
                if (estimate.isEmpty) {
                    host.t("没有可用的车型或价格，换个地址再试。", "No ride options for this route. Try another address.")
                } else {
                    val route = estimate.route.ifBlank { host.t("本次行程", "This trip") }
                    val options = estimate.items.take(3).joinToString("、") { item ->
                        val price = item.priceDiscounted?.takeIf { it.isNotBlank() } ?: item.priceText.orEmpty()
                        if (price.isBlank()) item.productName else "${item.productName} $price"
                    }
                    host.tf("%s：%s", "%s: %s", route, options)
                }
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
                if (link.isEmpty) {
                    host.t("没有生成可用的链接，请重新预估后再试。", "No ride link was generated. Estimate again and retry.")
                } else {
                    host.t("已生成滴滴发单链接，请在滴滴里确认车型和价格后下单。", "Ride link ready. Confirm the ride and fare in Didi before booking.")
                }
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
        execute = { call -> createOrder(host, toolState, call) },
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
        execute = { call -> cancelOrder(host, toolState, call) },
    ),
    pluginTool(
        name = "ride_order_status",
        label = host.t("打车订单", "Ride order"),
        description = host.t(
            "查询当前账号未完成或指定订单的状态、司机和车牌。参数 order_id 可选。",
            "Query the current or specified ride order. Optional order_id.",
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
                    host.t("没有查询到进行中的订单。", "No active ride order found.")
                } else {
                    listOfNotNull(
                        order.status?.let { host.tf("状态：%s", "Status: %s", it) },
                        order.driver?.let { host.tf("司机：%s", "Driver: %s", it) },
                        order.plate?.let { host.tf("车牌：%s", "Plate: %s", it) },
                        order.eta?.let { host.tf("预计到达：%s", "ETA: %s", it) },
                    ).joinToString("；")
                }
            }
        },
    ),
    pluginTool(
        name = "ride_driver_location",
        label = host.t("司机位置", "Driver location"),
        description = host.t(
            "查询指定订单司机的实时位置。参数 order_id 必填。",
            "Query the driver's live location for an order. Required: order_id.",
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
                val coordinates = location.coordinates
                val summary = location.summary
                when {
                    !summary.isNullOrBlank() -> host.tf("司机位置：%s", "Driver location: %s", summary)
                    coordinates != null -> host.tf("司机位置：%s", "Driver location: %s", coordinates)
                    else -> host.t("暂时无法获取司机位置，稍后再试。", "Driver location is not available yet. Try again shortly.")
                }
            }
        },
    ),
)

private suspend fun createOrder(host: PluginHost, toolState: RideToolState, call: PluginToolCall): PluginToolResult {
    if (call.args["confirm"]?.jsonPrimitive?.booleanOrNull != true) {
        return textResult(host.t("需要你先确认车型和价格，确认后才能创建订单。", "Confirm the vehicle and fare before creating the order."), isError = true)
    }
    val estimate = toolState.estimate(call.sessionId)
        ?: return textResult(host.t("请先预估价格，再创建订单。", "Estimate the fare before creating the order."), isError = true)
    val productName = call.args["product_name"]?.jsonPrimitive?.contentOrNull.orEmpty()
    val item = estimate.items.firstOrNull { it.productName == productName }
        ?: return textResult(host.t("没有找到这个车型，请从预估卡片里选一个车型。", "That vehicle wasn't in the estimate. Choose one from the estimate card."), isError = true)
    val traceId = estimate.traceId
    val category = item.productCategory
    if (traceId.isNullOrBlank() || category.isNullOrBlank()) {
        return textResult(host.t("这次预估已过期，请重新预估后再下单。", "This estimate has expired. Estimate again before booking."), isError = true)
    }
    val route = listOfNotNull(estimate.fromName, estimate.toName).joinToString(" → ")
    val price = item.priceDiscounted ?: item.priceText.orEmpty()
    val confirmed = runCatching {
        host.ui().confirm(
            uiConfirmRequest(
                title = host.t("确认叫车", "Confirm ride"),
                message = listOfNotNull(
                    route.ifBlank { null },
                    item.productName,
                    price.ifBlank { null },
                ).joinToString(" · "),
                confirmLabel = host.t("确认叫车", "Book ride"),
                destructive = true,
            ),
        )
    }.getOrDefault(false)
    if (!confirmed) {
        return textResult(host.t("已取消叫车。", "Ride creation cancelled."), isError = true)
    }
    return try {
        val payload = DidiClient(host).call(
            "taxi_create_order",
            buildJsonObject {
                put("product_category", category)
                put("estimate_trace_id", traceId)
                call.args["caller_car_phone"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let {
                    put("caller_car_phone", it)
                }
            },
        )
        val order = parseRideOrder(payload as? JsonObject)
        toolState.rememberOrder(call.sessionId, order.orderId)
        val route = listOfNotNull(order.fromName, order.toName).joinToString(" → ")
        textResult(
            text = host.tf(
                "订单已创建%s%s，正在为你匹配司机；支付在滴滴内完成。",
                "Order created%s%s. Matching a driver now; payment happens in Didi.",
                if (order.orderId.isNullOrBlank()) "" else "：${order.orderId}",
                if (route.isBlank()) "" else "，$route",
            ),
            details = payloadDetails(payload),
        )
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        val secret = runCatching { RideKeyStore(host.settings(), host.secrets()).currentKey() }.getOrNull()
        val message = summarizeDidiError(error.message, secret = secret)
            .ifBlank { host.t("订单没有创建成功，请稍后再试。", "The order wasn't created. Try again.") }
        host.logs().warn("ride create order failed: $message")
        textResult(host.tf("下单失败：%s", "Could not create the order: %s", message), isError = true)
    }
}

private suspend fun cancelOrder(host: PluginHost, toolState: RideToolState, call: PluginToolCall): PluginToolResult {
    if (call.args["confirm"]?.jsonPrimitive?.booleanOrNull != true) {
        return textResult(host.t("需要你确认后才能取消订单。", "Confirm before cancelling the order."), isError = true)
    }
    val orderId = call.args["order_id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        ?: toolState.lastOrder(call.sessionId)
        ?: return textResult(host.t("没有找到要取消的订单。", "No order to cancel was found."), isError = true)
    return try {
        val payload = DidiClient(host).call(
            "taxi_cancel_order",
            buildJsonObject {
                put("order_id", orderId)
                call.args["reason"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { put("reason", it) }
            },
        )
        val result = parseRideCancelResult(payload as? JsonObject)
        val message = result.message ?: when (result.cancelled) {
            true -> host.t("订单已取消。", "Order cancelled.")
            false -> host.t("订单暂时无法取消。", "The order cannot be cancelled yet.")
            null -> host.t("已提交取消请求。", "Cancellation requested.")
        }
        textResult(text = message, details = payloadDetails(payload))
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        val secret = runCatching { RideKeyStore(host.settings(), host.secrets()).currentKey() }.getOrNull()
        val message = summarizeDidiError(error.message, secret = secret)
            .ifBlank { host.t("取消失败，请稍后再试。", "Couldn't cancel. Try again.") }
        host.logs().warn("ride cancel order failed: $message")
        textResult(host.tf("取消失败：%s", "Couldn't cancel: %s", message), isError = true)
    }
}

private suspend fun perform(
    host: PluginHost,
    tool: String,
    call: PluginToolCall,
    arguments: (JsonObject) -> JsonObject,
    summary: suspend (JsonElement) -> String,
): PluginToolResult = try {
    val payload = DidiClient(host).call(tool, arguments(call.args))
    textResult(text = summary(payload), details = payloadDetails(payload))
} catch (cancelled: kotlinx.coroutines.CancellationException) {
    throw cancelled
} catch (error: Throwable) {
    val secret = runCatching { RideKeyStore(host.settings(), host.secrets()).currentKey() }.getOrNull()
    val message = summarizeDidiError(error.message, secret = secret)
        .ifBlank { host.t("网络或服务暂时不可用", "Network or service unavailable") }
    host.logs().warn("ride $tool failed: $message")
    textResult(
        text = host.tf("查询失败：%s", "Lookup failed: %s", message),
        isError = true,
    )
}

private fun payloadDetails(payload: JsonElement): JsonObject = when (payload) {
    is JsonObject -> payload
    is JsonArray -> buildJsonObject { put("items", payload) }
    is JsonPrimitive -> buildJsonObject { put("text", payload.contentOrNull.orEmpty()) }
    else -> JsonObject(emptyMap())
}
