package io.github.whywood.deepdeep.plugin.ride

import io.github.whywood.deepdeep.plugin.api.PluginHost
import io.github.whywood.deepdeep.plugin.api.PluginToolCall
import io.github.whywood.deepdeep.plugin.api.PluginToolResult
import io.github.whywood.deepdeep.plugin.api.pluginTool
import io.github.whywood.deepdeep.plugin.api.textResult
import kotlinx.serialization.json.JsonArray
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
        description = host.t("搜索地点并返回经纬度。参数 keywords、city。", "Search places and return coordinates. Parameters: keywords, city."),
        parameters = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("keywords", buildJsonObject { put("type", "string") })
                put("city", buildJsonObject { put("type", "string") })
            })
            put("required", JsonArray(listOf(JsonPrimitive("keywords"), JsonPrimitive("city"))))
        },
        execute = { call -> call(host, "maps_textsearch", call) { args ->
            buildJsonObject {
                put("keywords", args["keywords"]?.jsonPrimitive?.contentOrNull.orEmpty())
                put("city", args["city"]?.jsonPrimitive?.contentOrNull.orEmpty())
            }
        } },
    ),
    pluginTool(
        name = "ride_estimate",
        label = host.t("打车预估", "Ride estimate"),
        description = host.t("预估打车车型与价格。参数 from_name/from_lng/from_lat/to_name/to_lng/to_lat。", "Estimate ride options. Parameters: from_name/from_lng/from_lat/to_name/to_lng/to_lat."),
        parameters = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                listOf("from_name", "from_lng", "from_lat", "to_name", "to_lng", "to_lat").forEach { name ->
                    put(name, buildJsonObject { put("type", "string") })
                }
            })
            put("required", JsonArray(listOf("from_lng", "from_lat", "from_name", "to_lng", "to_lat", "to_name").map(::JsonPrimitive)))
        },
        execute = { call -> call(host, "taxi_estimate", call) { args ->
            buildJsonObject {
                listOf("from_name", "from_lng", "from_lat", "to_name", "to_lng", "to_lat").forEach { name ->
                    put(name, args[name]?.jsonPrimitive?.contentOrNull.orEmpty())
                }
            }
        } },
    ),
    pluginTool(
        name = "ride_app_link",
        label = host.t("打开滴滴", "Open Didi"),
        description = host.t("生成打开滴滴 App/小程序发单的链接。参数 from_lng/from_lat/to_lng/to_lat，可选 product_category。", "Create a Didi app/miniprogram ride link. Parameters: from_lng/from_lat/to_lng/to_lat, optional product_category."),
        parameters = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                listOf("from_lng", "from_lat", "to_lng", "to_lat", "product_category").forEach { name ->
                    put(name, buildJsonObject { put("type", "string") })
                }
            })
            put("required", JsonArray(listOf("from_lng", "from_lat", "to_lng", "to_lat").map(::JsonPrimitive)))
        },
        execute = { call -> call(host, "taxi_generate_ride_app_link", call) { args ->
            buildJsonObject {
                listOf("from_lng", "from_lat", "to_lng", "to_lat").forEach { name ->
                    put(name, args[name]?.jsonPrimitive?.contentOrNull.orEmpty())
                }
                args["product_category"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { put("product_category", it) }
            }
        } },
    ),
    pluginTool(
        name = "ride_order_status",
        label = host.t("打车订单", "Ride order"),
        description = host.t("查询当前账号未完成或指定订单的状态、司机和车牌。参数 order_id 可选。", "Query the current or specified ride order. Optional order_id."),
        parameters = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject { put("order_id", buildJsonObject { put("type", "string") }) })
        },
        execute = { call -> call(host, "taxi_query_order", call) { args ->
            buildJsonObject {
                args["order_id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { put("order_id", it) }
            }
        } },
    ),
    pluginTool(
        name = "ride_driver_location",
        label = host.t("司机位置", "Driver location"),
        description = host.t("查询指定订单司机的实时位置。参数 order_id 必填。", "Query the driver's live location. Required: order_id."),
        parameters = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject { put("order_id", buildJsonObject { put("type", "string") }) })
            put("required", JsonArray(listOf(JsonPrimitive("order_id"))))
        },
        execute = { call -> call(host, "taxi_get_driver_location", call) { args ->
            buildJsonObject { put("order_id", args["order_id"]?.jsonPrimitive?.contentOrNull.orEmpty()) }
        } },
    ),
)

private suspend fun call(
    host: PluginHost,
    tool: String,
    call: PluginToolCall,
    arguments: (JsonObject) -> JsonObject,
): PluginToolResult = try {
    val result = DidiClient(host).call(tool, arguments(call.args))
    val text = when (result) {
        is JsonArray -> result.toString()
        is JsonObject -> result.toString()
        else -> result.toString()
    }
    textResult(
        text = text,
        details = result as? JsonObject,
    )
} catch (cancelled: kotlinx.coroutines.CancellationException) {
    throw cancelled
} catch (error: Throwable) {
    host.logs().warn("didi $tool failed", error)
    textResult(host.t("打车查询失败：${error.message ?: "网络错误"}", "Ride lookup failed: ${error.message ?: "network error"}"), isError = true)
}
