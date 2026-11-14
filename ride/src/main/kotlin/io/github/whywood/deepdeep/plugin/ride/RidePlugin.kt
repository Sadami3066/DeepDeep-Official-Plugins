package io.github.whywood.deepdeep.plugin.ride

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.whywood.deepdeep.design.DdGroupCard
import io.github.whywood.deepdeep.design.DdHairline
import io.github.whywood.deepdeep.design.DdSpacing
import io.github.whywood.deepdeep.design.DdText
import io.github.whywood.deepdeep.design.DdTextAction
import io.github.whywood.deepdeep.design.DdType
import io.github.whywood.deepdeep.design.deepDeepColors
import io.github.whywood.deepdeep.plugin.api.DeepDeepPlugin
import io.github.whywood.deepdeep.plugin.api.PluginAvailability
import io.github.whywood.deepdeep.plugin.api.PluginHost
import io.github.whywood.deepdeep.plugin.api.PluginSkill
import io.github.whywood.deepdeep.plugin.api.PluginUiContributions
import io.github.whywood.deepdeep.plugin.api.SlotBinding
import io.github.whywood.deepdeep.plugin.api.ToolResultRendererContributor
import io.github.whywood.deepdeep.plugin.api.ToolResultSource
import io.github.whywood.deepdeep.plugin.api.ToolResultState
import io.github.whywood.deepdeep.plugin.api.available
import io.github.whywood.deepdeep.plugin.api.localizedText
import io.github.whywood.deepdeep.plugin.api.pluginSkill
import io.github.whywood.deepdeep.plugin.api.toolResultMatch
import io.github.whywood.deepdeep.plugin.api.toolResultRenderer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

class RidePlugin : DeepDeepPlugin {
    override suspend fun availability(host: PluginHost): PluginAvailability = available()

    override fun contributions(host: PluginHost): io.github.whywood.deepdeep.plugin.api.Contributions =
        io.github.whywood.deepdeep.plugin.api.contributions(tools = rideTools(host))

    override fun skills(host: PluginHost): List<PluginSkill> = listOf(
        pluginSkill(id = "deepdeep-ride", markdown = SKILL_MARKDOWN),
    )

    override fun ui(host: PluginHost): PluginUiContributions = object : PluginUiContributions, ToolResultRendererContributor {
        override val bindings: List<SlotBinding> = emptyList()
        override val pages = listOf(
            io.github.whywood.deepdeep.plugin.api.pluginPage(
                id = "main",
                title = io.github.whywood.deepdeep.plugin.api.localizedText(mapOf("default" to "打车", "zh" to "打车", "en" to "Ride")),
            ) { RidePage(host) },
        )

        override val toolResultRenderers = listOf(
            toolResultRenderer(
                id = "didi-taxi-estimate",
                match = toolResultMatch(source = ToolResultSource.MCP, tool = "taxi_estimate"),
                priority = 20,
            ) { state -> EstimateCard(state) },
            toolResultRenderer(
                id = "didi-taxi-link",
                match = toolResultMatch(source = ToolResultSource.MCP, tool = "taxi_generate_ride_app_link"),
                priority = 20,
            ) { state -> RideLinkCard(host, state) },
            toolResultRenderer(
                id = "didi-taxi-order",
                match = toolResultMatch(source = ToolResultSource.MCP, tool = "taxi_query_order"),
                priority = 20,
            ) { state -> OrderCard(state) },
            toolResultRenderer(
                id = "didi-taxi-create-order",
                match = toolResultMatch(source = ToolResultSource.MCP, tool = "taxi_create_order"),
                priority = 20,
            ) { state -> OrderCard(state) },
            toolResultRenderer(
                id = "didi-taxi-driver",
                match = toolResultMatch(source = ToolResultSource.MCP, tool = "taxi_get_driver_location"),
                priority = 20,
            ) { state -> SimpleCard(state, "司机位置") },
            toolResultRenderer(
                id = "didi-taxi-cancel",
                match = toolResultMatch(source = ToolResultSource.MCP, tool = "taxi_cancel_order"),
                priority = 20,
            ) { state -> SimpleCard(state, "取消订单") },
        )
    }
}

private val JsonFormat = Json { ignoreUnknownKeys = true; isLenient = true }

private fun payload(state: ToolResultState): JsonElement? =
    state.structuredContent ?: state.result?.text?.trim()?.let { raw ->
        val start = raw.indexOfFirst { it == '{' || it == '[' }
        val end = raw.indexOfLast { it == '}' || it == ']' }
        if (start < 0 || end <= start) null else runCatching { JsonFormat.parseToJsonElement(raw.substring(start, end + 1)) }.getOrNull()
    }

private fun JsonObject.string(name: String): String? =
    (this[name] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

@Composable
private fun EstimateCard(state: ToolResultState) {
    val colors = deepDeepColors()
    val root = payload(state) as? JsonObject
    val items = (root?.get("items") as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
    val fromName = (state.arguments["from_name"] as? JsonPrimitive)?.contentOrNull
    val toName = (state.arguments["to_name"] as? JsonPrimitive)?.contentOrNull
    val route = listOfNotNull(fromName, toName).joinToString(" → ").ifBlank { "打车预估" }
    DdGroupCard {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = DdSpacing.Card, vertical = DdSpacing.Lg),
            verticalArrangement = Arrangement.spacedBy(DdSpacing.Sm),
        ) {
            DdText(route, style = DdType.heading, color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            DdText("车型与参考价", style = DdType.bodySmall, color = colors.inkTertiary)
            if (items.isNotEmpty()) {
                DdHairline()
                items.take(6).forEachIndexed { index, item ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                        DdText(
                            text = item.string("productName") ?: "车型",
                            modifier = Modifier.weight(1f),
                            style = DdType.body,
                            color = colors.ink,
                        )
                        val price = item.string("priceText")
                        val discounted = item.string("priceDiscounted")
                        val text = when {
                            discounted != null && price != null -> "券后 ¥$discounted / 参考 ¥$price"
                            discounted != null -> "券后 ¥$discounted"
                            price != null -> "¥$price"
                            else -> "—"
                        }
                        Spacer(Modifier.width(DdSpacing.Sm))
                        DdText(text, style = DdType.bodySmall, color = colors.inkSecondary)
                    }
                    if (index < items.take(6).lastIndex) DdHairline()
                }
            } else {
                DdText(state.result?.text ?: "没有车型数据", style = DdType.bodySmall, color = colors.inkSecondary)
            }
            DdText("参考价，实际以下单页为准", style = DdType.bodySmall, color = colors.inkTertiary)
        }
    }
}

@Composable
private fun RideLinkCard(host: PluginHost, state: ToolResultState) {
    val colors = deepDeepColors()
    val root = payload(state) as? JsonObject
    val appLink = root?.string("appLink")
    val miniprogramLink = root?.string("miniprogramLink")
    DdGroupCard {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = DdSpacing.Card, vertical = DdSpacing.Lg),
            verticalArrangement = Arrangement.spacedBy(DdSpacing.Sm),
        ) {
            DdText("打开滴滴完成发单", style = DdType.heading, color = colors.ink)
            DdText("确认车型和价格后，在滴滴 App / 小程序内继续。", style = DdType.bodySmall, color = colors.inkSecondary)
            appLink?.let { DdTextAction("打开滴滴 App", onClick = { host.ui().openUrl(it) }) }
            miniprogramLink?.let { DdTextAction("打开滴滴小程序", onClick = { host.ui().openUrl(it) }) }
            if (appLink == null && miniprogramLink == null) {
                DdText(state.result?.text ?: "没有可用的跳转链接", style = DdType.bodySmall, color = colors.inkSecondary)
            }
        }
    }
}

@Composable
private fun OrderCard(state: ToolResultState) {
    val colors = deepDeepColors()
    val root = payload(state) as? JsonObject
    val status = root?.string("status") ?: root?.string("orderStatus") ?: root?.string("state")
    val driver = root?.string("driverName") ?: root?.string("driver_name")
    val phone = root?.string("driverPhone") ?: root?.string("driver_phone")
    val plate = root?.string("plateNumber") ?: root?.string("carPlate") ?: root?.string("plate_number")
    val eta = root?.string("eta") ?: root?.string("estimatedArriveTime") ?: root?.string("arriveTime")
    DdGroupCard {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = DdSpacing.Card, vertical = DdSpacing.Lg),
            verticalArrangement = Arrangement.spacedBy(DdSpacing.Sm),
        ) {
            DdText("打车订单", style = DdType.heading, color = colors.ink)
            status?.let { DdText("状态：$it", style = DdType.body, color = colors.ink) }
            driver?.let { DdText("司机：$it", style = DdType.bodySmall, color = colors.inkSecondary) }
            plate?.let { DdText("车牌：$it", style = DdType.bodySmall, color = colors.inkSecondary) }
            phone?.let { DdText("电话：$it", style = DdType.bodySmall, color = colors.inkSecondary) }
            eta?.let { DdText("预计到达：$it", style = DdType.bodySmall, color = colors.inkSecondary) }
            if (status == null && driver == null && plate == null && phone == null && eta == null) {
                DdText(state.result?.text ?: "没有订单详情", style = DdType.bodySmall, color = colors.inkSecondary)
            }
        }
    }
}

@Composable
private fun SimpleCard(state: ToolResultState, title: String) {
    val colors = deepDeepColors()
    DdGroupCard {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = DdSpacing.Card, vertical = DdSpacing.Lg),
            verticalArrangement = Arrangement.spacedBy(DdSpacing.Sm),
        ) {
            DdText(title, style = DdType.heading, color = colors.ink)
            DdText(
                state.result?.text ?: "操作已完成",
                style = DdType.bodySmall,
                color = colors.inkSecondary,
            )
        }
    }
}

internal fun PluginHost.t(zh: String, en: String): String = text(localizedText(mapOf("default" to zh, "zh" to zh, "en" to en)))

private val SKILL_MARKDOWN = """
    ---
    name: deepdeep-ride
    description: 滴滴出行 MCP 打车用法：地点搜索、车型与价格预估、生成滴滴 App/小程序发单链接、订单状态、司机位置与取消；当用户提到打车、网约车、滴滴、快车、专车、叫车或司机时使用。
    ---

    # 滴滴打车

    DeepDeep 打车插件渲染滴滴 MCP 的结果。MCP 工具由宿主连接器提供，真实发单前必须让用户确认。

    - 地点坐标：用 `ride_place_search`（keywords + city）拿经纬度，不要把地址猜成坐标。
    - 预估：用 `ride_estimate`，传出发/目的地的名称与经纬度；把车型、参考价和券后价展示给用户，让用户选车型。
    - 发单：优先 `ride_app_link`，给用户滴滴 App / 小程序跳转链接；插件不直接创建真实订单。
    - 不能自动发单、不能自动支付；用户未明确选择车型前不要发单。
    - 订单状态：用 `ride_order_status`；司机位置：`ride_driver_location`。
    - 展示时只保留地名、车型、价格、车牌/司机/ETA 等用户需要的信息，不暴露 traceId、品类代码等内部字段。
""".trimIndent()
