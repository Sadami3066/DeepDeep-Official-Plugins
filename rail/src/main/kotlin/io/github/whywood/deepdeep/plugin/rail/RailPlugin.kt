package io.github.whywood.deepdeep.plugin.rail

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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.LocalDate

private const val TOOL_NAME = "train_search"
private val JsonFormat = Json { ignoreUnknownKeys = true; isLenient = true }

class RailPlugin : DeepDeepPlugin {
    override suspend fun availability(host: PluginHost): PluginAvailability = available()

    override fun contributions(host: PluginHost) = contributions(
        tools = listOf(
            pluginTool(
                name = TOOL_NAME,
                label = host.t("火车票", "Trains"),
                description = host.t(
                    "查询 12306 火车票。参数：from 出发城市/车站，to 到达城市/车站，date 可选的 YYYY-MM-DD（默认明天）。",
                    "Search 12306 trains. Parameters: from departure city/station, to arrival city/station, optional date YYYY-MM-DD (default tomorrow).",
                ),
                parameters = buildJsonObject {
                    put("type", "object")
                    put(
                        "properties",
                        buildJsonObject {
                            put("from", buildJsonObject { put("type", "string"); put("description", host.t("出发地", "Departure")) })
                            put("to", buildJsonObject { put("type", "string"); put("description", host.t("到达地", "Arrival")) })
                            put("date", buildJsonObject { put("type", "string"); put("description", host.t("日期 YYYY-MM-DD", "Date YYYY-MM-DD")) })
                        },
                    )
                    put("required", JsonArray(listOf(JsonPrimitive("from"), JsonPrimitive("to"))))
                },
                promptSnippet = host.t(
                    "查火车票用 train_search；结果会显示成车次卡片。",
                    "Use train_search for 12306 trains; results render as a train card.",
                ),
                execute = { call -> executeSearch(host, call) },
            ),
        ),
    )

    override fun ui(host: PluginHost): PluginUiContributions = object : PluginUiContributions {
        override val bindings: List<SlotBinding> = listOf(
            slotBinding(
                slotId = SlotIds.toolCard(TOOL_NAME),
                content = composeSlot { state ->
                    TrainToolCard(host, state as ToolCardSlotState)
                },
            ),
        )

        override val pages = listOf(
            pluginPage(
                id = "main",
                title = localizedText(default = "火车票", zh = "火车票", en = "Trains"),
            ) {
                TrainPage(host)
            },
        )
    }

    private suspend fun executeSearch(host: PluginHost, call: PluginToolCall): PluginToolResult {
        val from = call.args["from"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val to = call.args["to"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val date = call.args["date"]?.jsonPrimitive?.contentOrNull?.trim().takeUnless { it.isNullOrBlank() }
            ?: LocalDate.now().plusDays(1).toString()
        if (from.isBlank() || to.isBlank()) return textResult(host.t("请提供出发地和到达地。", "Provide departure and arrival."), isError = true)
        return try {
            val details = TrainService(host).search(from, to, date)
            textResult(
                text = trainSummary(host, details),
                details = JsonFormat.encodeToJsonElement(TrainSearchDetails.serializer(), details) as JsonObject,
            )
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            host.logs().warn("train search failed: $from -> $to $date", error)
            textResult(
                host.t("车次查询失败：${error.message ?: "网络错误"}", "Train lookup failed: ${error.message ?: "network error"}"),
                isError = true,
            )
        }
    }
}
