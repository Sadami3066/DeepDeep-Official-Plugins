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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

private const val SEARCH_TOOL = "train_search"
private const val PRICE_TOOL = "train_price"
private val JsonFormat = Json { ignoreUnknownKeys = true; isLenient = true }

class RailPlugin : DeepDeepPlugin {
    override suspend fun availability(host: PluginHost): PluginAvailability = available()

    override fun contributions(host: PluginHost) = contributions(
        tools = listOf(searchTool(host), priceTool(host)),
    )

    override fun ui(host: PluginHost): PluginUiContributions = object : PluginUiContributions {
        override val bindings: List<SlotBinding> = listOf(
            slotBinding(
                slotId = SlotIds.toolCard(SEARCH_TOOL),
                content = composeSlot { state ->
                    TrainToolCard(host, state as ToolCardSlotState)
                },
            ),
            slotBinding(
                slotId = SlotIds.toolCard(PRICE_TOOL),
                content = composeSlot { state ->
                    RailPriceCard(host, state as ToolCardSlotState)
                },
            ),
        )

        override val pages = listOf(
            pluginPage(
                id = "main",
                title = localizedText(default = "12306铁路", zh = "12306铁路", en = "12306 Railway"),
            ) {
                TrainPage(host)
            },
        )
    }

    private fun searchTool(host: PluginHost) = pluginTool(
        name = SEARCH_TOOL,
        label = host.t("12306铁路", "12306 Railway"),
        description = host.t(
            "查询 12306 火车时刻与余票，结果按车次分组，列出每趟车的到站组合、出发/到达时间、历时和全部席别余票。参数：from 出发城市/车站，to 到达城市/车站，date 可选 YYYY-MM-DD（默认今天，限今天起 $RAIL_DATE_WINDOW_DAYS 天内）。城市名优先匹配同名车站（如 北京 即北京站），同名车站不存在时返回候选车站列表。按城市查询时同一车次可能有多个实际到站，每行标注实际出发/到达站与上游日期。只查票，购票、登录与支付由用户在 12306 完成。",
            "Search 12306 trains and seat availability. Results are grouped by train code and list every station pair, departure/arrival time, duration and every seat status. Parameters: from departure city/station, to arrival city/station, optional date YYYY-MM-DD (default today, within $RAIL_DATE_WINDOW_DAYS days). A city name resolves to the station with the same name (e.g. 北京 means Beijing station); when no same-named station exists the result lists candidate stations. City-level queries can return several actual station pairs for one train; each row labels the actual stations and the upstream date. This tool only searches; sign-in, booking and payment happen on 12306.",
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
            "查火车票用 train_search；结果按车次分组，直接引用即可。",
            "Use train_search for 12306 trains; results are grouped by train code.",
        ),
        promptGuidelines = listOf(
            host.t(
                "train_search 只返回车次与余票参考，不要声称已经购票或锁座。",
                "train_search only returns train times and seat availability; never claim a ticket is booked or held.",
            ),
            host.t(
                "用户要购票时，引导点「去 12306 预订」或把结果里的 12306 预订链接交给用户。",
                "When the user wants to book, point them to the 12306 booking link in the result.",
            ),
        ),
        execute = { call -> executeSearch(host, call) },
    )

    private fun priceTool(host: PluginHost) = pluginTool(
        name = PRICE_TOOL,
        label = host.t("12306票价", "12306 fares"),
        description = host.t(
            "查询某趟车次在出发到达区间的各席别票价，来自 12306 票价接口，按 二等座、一等座、商务座、优选一等座、特等座、软卧、硬卧、软座、硬座、高级软卧、动卧、无座 的固定顺序列出。参数：from、to、train_code（如 G814），date 可选 YYYY-MM-DD；train_no、from_station_no、to_station_no 可选，用于指定同一车次的具体到站组合。同一路线刚查过车次时直接复用最近一次 train_search 的结果，不再重复查询。12306 没有返回票价时明确给出票价暂不可查，绝不编造价格。",
            "Query per-seat fares for one train over the departure-arrival leg from the 12306 fare endpoint, listed in a fixed seat order. Parameters: from, to, train_code (e.g. G814), optional date YYYY-MM-DD; optional train_no, from_station_no and to_station_no pick one station pair of the same train. It reuses the latest train_search for the same route instead of querying again. When 12306 returns no fare the result says fares are unavailable; never invent prices.",
        ),
        parameters = buildJsonObject {
            put("type", "object")
            put(
                "properties",
                buildJsonObject {
                    put("from", buildJsonObject { put("type", "string"); put("description", host.t("出发城市或车站", "Departure city or station")) })
                    put("to", buildJsonObject { put("type", "string"); put("description", host.t("到达城市或车站", "Arrival city or station")) })
                    put("train_code", buildJsonObject { put("type", "string"); put("description", host.t("车次号，如 G814", "Train code, e.g. G814")) })
                    put("date", buildJsonObject { put("type", "string"); put("description", host.t("日期 YYYY-MM-DD，默认今天", "Date YYYY-MM-DD, defaults to today")) })
                    put("train_no", buildJsonObject { put("type", "string"); put("description", host.t("可选，12306 车次内部编号", "Optional 12306 train number")) })
                    put("from_station_no", buildJsonObject { put("type", "string"); put("description", host.t("可选，出发站序号", "Optional departure station index")) })
                    put("to_station_no", buildJsonObject { put("type", "string"); put("description", host.t("可选，到达站序号", "Optional arrival station index")) })
                },
            )
            put("required", JsonArray(listOf(JsonPrimitive("from"), JsonPrimitive("to"), JsonPrimitive("train_code"))))
        },
        promptSnippet = host.t(
            "用户问某趟车多少钱时，用 train_price 查票价；结果没有票价时直接说票价暂不可查。",
            "When the user asks about one train's fare, use train_price; if no fare comes back, say fares are unavailable.",
        ),
        promptGuidelines = listOf(
            host.t(
                "票价只来自 12306 票价接口；工具说票价暂不可查时不要把预估或历史记忆当成真实票价。",
                "Fares only come from the 12306 fare endpoint; when the tool says fares are unavailable, never treat estimates or memory as real prices.",
            ),
        ),
        execute = { call -> executePrice(host, call) },
    )

    private suspend fun executeSearch(host: PluginHost, call: PluginToolCall): PluginToolResult {
        val from = call.args["from"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val to = call.args["to"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val date = call.args["date"]?.jsonPrimitive?.contentOrNull?.trim().takeUnless { it.isNullOrBlank() }
            ?: TravelDate.today()
        if (from.isBlank() || to.isBlank()) {
            val message = host.t("请提供出发地和到达地。", "Provide both departure and arrival.")
            return textResult(
                "",
                details = railErrorDetails(
                    raw = message,
                    guidance = host.t("向用户询问出发地和到达地后再查。", "Ask the user for departure and arrival, then retry."),
                    message = message,
                ),
                isError = true,
            )
        }
        return try {
            val result = TrainService(host).search(from, to, date)
            val details = result.details
            val groups = groupTrains(details.trains).size
            val trainsLabel = if (groups == 1) host.t("1 趟", "1 train") else host.t("$groups 趟", "$groups trains")
            val cardTitle = if (details.trains.isEmpty()) {
                "${details.from} → ${details.to}"
            } else {
                "${details.from} → ${details.to} · $trainsLabel"
            }
            val guidance = host.t(
                "问某趟车的票价时用 train_price，train_code 必填。",
                "For one train's fare call train_price with train_code.",
            )
            textResult(
                "",
                details = railDetails(
                    raw = trainSearchText(host, details),
                    guidance = guidance,
                    cardTitle = cardTitle,
                    payload = encodeSearch(details),
                ),
            )
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            host.logs().warn("train search failed: $from -> $to $date", error)
            railErrorResult(host, error)
        }
    }

    private suspend fun executePrice(host: PluginHost, call: PluginToolCall): PluginToolResult {
        val from = call.args["from"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val to = call.args["to"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val trainCode = call.args["train_code"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val trainNo = call.args["train_no"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val fromStationNo = call.args["from_station_no"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val toStationNo = call.args["to_station_no"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val date = call.args["date"]?.jsonPrimitive?.contentOrNull?.trim().takeUnless { it.isNullOrBlank() }
            ?: TravelDate.today()
        if (from.isBlank() || to.isBlank() || trainCode.isBlank()) {
            val message = host.t("请提供出发地、到达地和车次号。", "Provide departure, arrival and the train code.")
            return textResult(
                "",
                details = railErrorDetails(
                    raw = message,
                    guidance = host.t("向用户询问出发地、到达地和车次号后再查。", "Ask the user for departure, arrival and the train code, then retry."),
                    message = message,
                ),
                isError = true,
            )
        }
        return try {
            val details = TrainService(host).price(
                fromQuery = from,
                toQuery = to,
                dateQuery = date,
                trainCode = trainCode,
                trainNo = trainNo,
                fromStationNo = fromStationNo,
                toStationNo = toStationNo,
            )
            val cardTitle = "${details.code} ${details.fromPlace} → ${details.toPlace}"
            val guidance = host.t(
                "票价与余票以 12306 为准，不要说已购票或已锁座。",
                "Fares and availability follow 12306; never claim a ticket is booked or held.",
            )
            textResult(
                "",
                details = railDetails(
                    raw = trainPriceText(host, details),
                    guidance = guidance,
                    cardTitle = cardTitle,
                    payload = encodePrice(details),
                ),
            )
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            host.logs().warn("train price failed: $trainCode $from -> $to $date", error)
            railErrorResult(host, error)
        }
    }

    private fun railErrorResult(host: PluginHost, error: Throwable): PluginToolResult {
        val message = trainErrorMessage(host, error)
        error.railWire().takeIf { it.isNotBlank() }?.let { wire ->
            host.logs().warn("12306 wire for failed call: $wire")
        }
        return textResult(
            "",
            details = railErrorDetails(
                raw = message,
                guidance = trainErrorGuidance(host, error),
                message = message,
            ),
            isError = true,
        )
    }

    private fun Throwable.railWire(): String = when (this) {
        is TrainQueryException -> raw
        is TrainPriceUnavailableException -> raw
        else -> ""
    }

    private fun encodeSearch(details: TrainSearchDetails): JsonObject =
        JsonFormat.encodeToJsonElement(TrainSearchDetails.serializer(), details) as JsonObject

    private fun encodePrice(details: TrainPriceDetails): JsonObject =
        JsonFormat.encodeToJsonElement(TrainPriceDetails.serializer(), details) as JsonObject
}

internal fun railDetails(raw: String, guidance: String, cardTitle: String, payload: JsonObject): JsonObject =
    buildJsonObject {
        put(RAIL_RAW_DETAILS_KEY, raw)
        if (guidance.isNotBlank()) put(RAIL_GUIDANCE_DETAILS_KEY, guidance)
        put(
            RAIL_CARD_DETAILS_KEY,
            buildJsonObject {
                put("title", cardTitle)
                put("payload", payload)
            },
        )
    }

internal fun railErrorDetails(raw: String, guidance: String, message: String): JsonObject = buildJsonObject {
    put(RAIL_RAW_DETAILS_KEY, raw)
    if (guidance.isNotBlank()) put(RAIL_GUIDANCE_DETAILS_KEY, guidance)
    put(RAIL_MESSAGE_DETAILS_KEY, message)
}
