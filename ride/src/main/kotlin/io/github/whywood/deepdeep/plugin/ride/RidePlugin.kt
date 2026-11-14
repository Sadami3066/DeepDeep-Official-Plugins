package io.github.whywood.deepdeep.plugin.ride

import androidx.compose.runtime.Composable
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
import io.github.whywood.deepdeep.plugin.api.pluginPage
import io.github.whywood.deepdeep.plugin.api.pluginSkill
import io.github.whywood.deepdeep.plugin.api.toolResultMatch
import io.github.whywood.deepdeep.plugin.api.toolResultRenderer

class RidePlugin : DeepDeepPlugin {
    override suspend fun availability(host: PluginHost): PluginAvailability = available()

    override suspend fun onEnable(host: PluginHost) {
        runCatching { DidiClient(host).migrateLegacyKey() }
            .onFailure { runCatching { host.logs().warn("didi key migration failed") } }
    }

    override fun contributions(host: PluginHost) = io.github.whywood.deepdeep.plugin.api.contributions(tools = rideTools(host))

    override fun skills(host: PluginHost): List<PluginSkill> = listOf(
        pluginSkill(id = SKILL_ID, markdown = SKILL_MARKDOWN),
    )

    override fun ui(host: PluginHost): PluginUiContributions =
        object : PluginUiContributions, ToolResultRendererContributor {
            override val bindings: List<SlotBinding> = emptyList()

            override val pages = listOf(
                pluginPage(
                    id = "main",
                    title = localizedText(mapOf("default" to "打车", "zh" to "打车", "en" to "Ride")),
                ) { RidePage(host) },
            )

            override val toolResultRenderers = listOf(
                pluginRenderer("ride-estimate", "ride_estimate") { state -> RideEstimateCard(host, state) },
                pluginRenderer("ride-app-link", "ride_app_link") { state -> RideLinkCard(host, state) },
                pluginRenderer("ride-order-status", "ride_order_status") { state -> RideOrderCard(host, state) },
                pluginRenderer("ride-driver-location", "ride_driver_location") { state ->
                    RideDriverLocationCard(host, state)
                },
                pluginRenderer("ride-place-search", "ride_place_search") { state -> RideSearchCard(host, state) },
                mcpRenderer("didi-taxi-estimate", "taxi_estimate") { state -> RideEstimateCard(host, state) },
                mcpRenderer("didi-taxi-link", "taxi_generate_ride_app_link") { state -> RideLinkCard(host, state) },
                mcpRenderer("didi-taxi-order", "taxi_query_order") { state -> RideOrderCard(host, state) },
                mcpRenderer("didi-taxi-create-order", "taxi_create_order") { state -> RideOrderCard(host, state) },
                mcpRenderer("didi-taxi-driver", "taxi_get_driver_location") { state ->
                    RideDriverLocationCard(host, state)
                },
                mcpRenderer("didi-taxi-cancel", "taxi_cancel_order") { state -> RideCancelCard(host, state) },
            )
        }

    private fun pluginRenderer(
        id: String,
        tool: String,
        content: @Composable (ToolResultState) -> Unit,
    ) = toolResultRenderer(
        id = id,
        match = toolResultMatch(source = ToolResultSource.PLUGIN, tool = tool),
        priority = 20,
        content = content,
    )

    private fun mcpRenderer(
        id: String,
        tool: String,
        content: @Composable (ToolResultState) -> Unit,
    ) = toolResultRenderer(
        id = id,
        match = toolResultMatch(source = ToolResultSource.MCP, tool = tool),
        priority = 20,
        content = content,
    )
}

private const val SKILL_ID = "deepdeep-ride"

private val SKILL_MARKDOWN = """
    ---
    name: deepdeep-ride
    description: 滴滴出行打车用法：地点搜索、车型与价格预估、生成滴滴 App/小程序发单链接、订单状态、司机位置；当用户提到打车、网约车、滴滴、快车、专车、叫车或司机时使用。
    ---

    # 滴滴打车

    DeepDeep 打车插件提供五个查询工具，真实下单和支付始终在滴滴 App 或小程序里由用户完成。

    - 地点坐标：用 `ride_place_search`（keywords + city）拿经纬度，不要把地址猜成坐标。
    - 预估：用 `ride_estimate`，传出发/目的地的名称与经纬度；把车型、参考价和券后价展示给用户，让用户选车型。
    - 发单：优先 `ride_app_link`，给用户滴滴 App / 小程序跳转链接；插件不会创建真实订单，也不能自动支付。
    - 不能自动发单、不能自动支付；用户未明确选择车型前不要发单。
    - 订单状态：用 `ride_order_status`；司机位置：`ride_driver_location`。
    - 展示时只保留地名、车型、价格、车牌/司机/ETA 等用户需要的信息，不暴露 traceId、品类代码等内部字段。
""".trimIndent()
