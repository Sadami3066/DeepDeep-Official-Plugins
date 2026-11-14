package io.github.whywood.deepdeep.plugin.ride

import io.github.whywood.deepdeep.plugin.api.PluginHost
import io.github.whywood.deepdeep.plugin.api.PluginToolCall
import io.github.whywood.deepdeep.plugin.api.PluginToolResult
import io.github.whywood.deepdeep.plugin.api.pluginTool
import io.github.whywood.deepdeep.plugin.api.textResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal fun rideTools(host: PluginHost) = listOf(
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
