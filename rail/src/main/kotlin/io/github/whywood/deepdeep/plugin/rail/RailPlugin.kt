package io.github.whywood.deepdeep.plugin.rail

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
import java.time.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

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
                    "查询 12306 火车时刻与余票。参数：from 出发城市/车站，to 到达城市/车站，date 可选 YYYY-MM-DD（默认今天，限今天起 $RAIL_DATE_WINDOW_DAYS 天内）。只查票，购票、登录与支付由用户在 12306 完成。",
                    "Search 12306 train times and seat availability. Parameters: from departure city/station, to arrival city/station, optional date YYYY-MM-DD (default today, within $RAIL_DATE_WINDOW_DAYS days). This tool only searches; sign-in, booking and payment happen on 12306.",
                ),
                parameters = buildJsonObject {
                    put("type", "object")
                    put(
                        "properties",
                        buildJsonObject {
                            put(
                                "from",
                                buildJsonObject {
                                    put("type", "string")
                                    put("description", host.t("出发城市或车站", "Departure city or station"))
                                },
                            )
                            put(
                                "to",
                                buildJsonObject {
                                    put("type", "string")
                                    put("description", host.t("到达城市或车站", "Arrival city or station"))
                                },
                            )
                            put(
                                "date",
                                buildJsonObject {
                                    put("type", "string")
                                    put("description", host.t("日期 YYYY-MM-DD，默认今天", "Date YYYY-MM-DD, defaults to today"))
                                },
                            )
                        },
                    )
                    put("required", JsonArray(listOf(JsonPrimitive("from"), JsonPrimitive("to"))))
                },
                promptSnippet = host.t(
                    "查火车票用 train_search；结果会显示车次卡片，购票需去 12306。",
                    "Use train_search for 12306 trains; results show a train card and booking happens on 12306.",
                ),
                promptGuidelines = listOf(
                    host.t(
                        "train_search 只返回车次与余票参考，不要声称已经购票或锁座。",
                        "train_search only returns train times and seat availability; never claim a ticket is booked or held.",
                    ),
                    host.t(
                        "用户要购票时，引导点卡片或页面里的「去 12306 预订」。",
                        "When the user wants to book, point them to the 12306 booking action on the card or page.",
                    ),
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
            ?: TravelDate.today()
        if (from.isBlank() || to.isBlank()) {
            return textResult(
                host.t("请提供出发地和到达地。", "Provide both departure and arrival."),
                isError = true,
            )
        }
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
            textResult(trainErrorMessage(host, error), isError = true)
        }
    }
}
