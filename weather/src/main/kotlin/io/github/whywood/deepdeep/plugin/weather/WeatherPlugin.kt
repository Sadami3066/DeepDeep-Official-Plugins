package io.github.whywood.deepdeep.plugin.weather

import androidx.compose.runtime.Composable
import io.github.whywood.deepdeep.plugin.api.DeepDeepPlugin
import io.github.whywood.deepdeep.plugin.api.PluginAvailability
import io.github.whywood.deepdeep.plugin.api.PluginHost
import io.github.whywood.deepdeep.plugin.api.PluginToolCall
import io.github.whywood.deepdeep.plugin.api.PluginToolResult
import io.github.whywood.deepdeep.plugin.api.PluginUiContributions
import io.github.whywood.deepdeep.plugin.api.SlotBinding
import io.github.whywood.deepdeep.plugin.api.SlotIds
import io.github.whywood.deepdeep.plugin.api.ToolCardSlotState
import io.github.whywood.deepdeep.plugin.api.available
import io.github.whywood.deepdeep.plugin.api.composeSlot
import io.github.whywood.deepdeep.plugin.api.contributions
import io.github.whywood.deepdeep.plugin.api.localizedText
import io.github.whywood.deepdeep.plugin.api.pluginPage
import io.github.whywood.deepdeep.plugin.api.pluginTool
import io.github.whywood.deepdeep.plugin.api.slotBinding
import io.github.whywood.deepdeep.plugin.api.textResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

private const val TOOL_NAME = "weather_forecast"
private const val PAGE_ID = "main"
private val JsonFormat = Json { ignoreUnknownKeys = true; isLenient = true }

class WeatherPlugin : DeepDeepPlugin {
    override suspend fun availability(host: PluginHost): PluginAvailability = available()

    override fun contributions(host: PluginHost) = contributions(
        tools = listOf(
            pluginTool(
                name = TOOL_NAME,
                label = host.t("天气", "Weather"),
                description = host.t(
                    "查询城市当前天气和未来 1–7 天预报。参数 location 是城市名，例如杭州或 Kyoto；days 可选，默认 3。",
                    "Look up current weather and a 1–7 day forecast for a city. location is a city name such as Hangzhou or Kyoto; days is optional, default 3.",
                ),
                parameters = buildJsonObject {
                    put("type", "object")
                    put(
                        "properties",
                        buildJsonObject {
                            put(
                                "location",
                                buildJsonObject {
                                    put("type", "string")
                                    put("description", host.t("城市名，或“纬度,经度”", "City name, or \"latitude,longitude\""))
                                },
                            )
                            put(
                                "days",
                                buildJsonObject {
                                    put("type", "integer")
                                    put("description", host.t("预报天数，1–7，默认 3", "Forecast days, 1–7, default 3"))
                                },
                            )
                        },
                    )
                    put("required", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("location"))))
                },
                promptSnippet = host.t(
                    "查天气用 weather_forecast；结果会显示成天气卡片。",
                    "Use weather_forecast for weather; the result renders as a weather card.",
                ),
                execute = { call -> executeForecast(host, call) },
            ),
        ),
    )

    override fun ui(host: PluginHost): PluginUiContributions = object : PluginUiContributions {
        override val bindings: List<SlotBinding> = listOf(
            slotBinding(
                slotId = SlotIds.toolCard(TOOL_NAME),
                content = composeSlot { state ->
                    WeatherToolCard(host, state as ToolCardSlotState)
                },
            ),
        )

        override val pages = listOf(
            pluginPage(
                id = PAGE_ID,
                title = localizedText(default = "天气", zh = "天气", en = "Weather"),
            ) {
                WeatherPage(host)
            },
        )
    }

    private suspend fun executeForecast(host: PluginHost, call: PluginToolCall): PluginToolResult {
        val location = call.args["location"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val days = call.args["days"]?.jsonPrimitive?.intOrNull?.coerceIn(1, 7) ?: 3
        if (location.isBlank()) {
            return textResult(host.t("请提供城市名。", "Provide a city name."), isError = true)
        }
        return try {
            val details = WeatherService(host).forecast(location, days)
            textResult(
                text = weatherSummary(host, details),
                details = JsonFormat.encodeToJsonElement(WeatherDetails.serializer(), details) as kotlinx.serialization.json.JsonObject,
            )
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            host.logs().warn("weather forecast failed: $location", error)
            textResult(
                host.t("天气查询失败：${error.message ?: "网络错误"}", "Weather lookup failed: ${error.message ?: "network error"}"),
                isError = true,
            )
        }
    }
}
