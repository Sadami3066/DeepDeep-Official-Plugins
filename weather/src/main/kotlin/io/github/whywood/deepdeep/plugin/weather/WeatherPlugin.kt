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
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal const val TOOL_NAME = "weather_forecast"
internal const val PAGE_ID = "main"

private val JsonFormat = Json { ignoreUnknownKeys = true; isLenient = true }

class WeatherPlugin : DeepDeepPlugin {
    override suspend fun availability(host: PluginHost): PluginAvailability = available()

    override fun contributions(host: PluginHost) = contributions(
        tools = listOf(
            pluginTool(
                name = TOOL_NAME,
                label = host.t("天气", "Weather"),
                description = host.t(
                    "查询城市当前天气和未来 1–7 天预报；结果以天气卡片展示。location 是城市名，例如杭州或 Kyoto；days 可选，默认 3。",
                    "Look up current weather and a 1–7 day forecast for a city; the result is shown as a weather card. location is a city name such as Hangzhou or Kyoto; days is optional and defaults to 3.",
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
                    put("required", JsonArray(listOf(JsonPrimitive("location"))))
                },
                promptSnippet = host.t(
                    "查天气用 weather_forecast；结果会以天气卡片展示。",
                    "Use weather_forecast for weather; the result renders as a weather card.",
                ),
                promptGuidelines = listOf(
                    host.t(
                        "天气结果已经以卡片展示给用户，回复时不要复述逐日列表，可以补充出行或穿衣建议。",
                        "Weather results are already shown to the user as a card; don't repeat the daily list in your reply. Add practical advice instead.",
                    ),
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
        if (location.isBlank()) {
            return textResult(host.t("请提供城市名。", "Provide a city name."), isError = true)
        }
        val requestedDays = forecastDays(call.args["days"]?.jsonPrimitive?.intOrNull)
            ?: return textResult(
                host.t("days 需要在 1–7 之间。", "days must be between 1 and 7."),
                isError = true,
            )
        return try {
            val details = WeatherService(host).forecast(location, requestedDays)
            textResult(
                text = weatherSummary(host, details, requestedDays),
                details = JsonFormat.encodeToJsonElement(WeatherDetails.serializer(), details) as kotlinx.serialization.json.JsonObject,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            host.logs().warn("weather forecast failed: $location", error)
            textResult(
                error.message?.takeIf { it.isNotBlank() }
                    ?: host.t("天气查询失败，请稍后重试。", "Weather lookup failed. Try again later."),
                isError = true,
            )
        }
    }
}
