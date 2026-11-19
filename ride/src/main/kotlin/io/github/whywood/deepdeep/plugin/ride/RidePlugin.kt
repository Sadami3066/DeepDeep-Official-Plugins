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
    private val toolState = RideToolState()

    override suspend fun availability(host: PluginHost): PluginAvailability = available()

    override suspend fun onEnable(host: PluginHost) {
        runCatching { DidiClient(host).migrateLegacyKey() }
            .onFailure { runCatching { host.logs().warn("didi key migration failed") } }
    }

    override fun contributions(host: PluginHost) =
        io.github.whywood.deepdeep.plugin.api.contributions(tools = rideTools(host, toolState))

    override fun skills(host: PluginHost): List<PluginSkill> = listOf(
        pluginSkill(id = SKILL_ID, markdown = SKILL_MARKDOWN),
    )

    override fun ui(host: PluginHost): PluginUiContributions =
        object : PluginUiContributions, ToolResultRendererContributor {
            override val bindings: List<SlotBinding> = emptyList()

            override val pages = listOf(
                pluginPage(
                    id = "main",
                    title = localizedText(mapOf("default" to "滴滴打车", "zh" to "滴滴打车", "en" to "DiDi Ride")),
                ) { RidePage(host) },
            )

            override val toolResultRenderers = listOf(
                pluginRenderer("ride-estimate", "ride_estimate") { state -> RideEstimateCard(host, state) },
                pluginRenderer("ride-app-link", "ride_app_link") { state -> RideLinkCard(host, state) },
                pluginRenderer("ride-create-order", "ride_create_order") { state -> RideOrderCard(host, state) },
                pluginRenderer("ride-cancel-order", "ride_cancel_order") { state -> RideCancelCard(host, state) },
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

    DeepDeep 打车插件提供从估价到下单、取消、订单跟踪的完整流程。

    - 地点坐标：用 `ride_place_search`（keywords + city）拿名称、完整地址、城市和经纬度，不要把地址猜成坐标；经度对应 *_lng、纬度对应 *_lat。
    - 预估：用 `ride_estimate`，传出发/目的地的名称与经纬度；结果里的 from/to 是滴滴实际吸附后的上/下车点，和用户给的地点不一致时先把实际点位告诉用户；distanceKm 单位公里，eta 与 duration 单位分钟；车型给出参考价、券后价和品类，把选择交给用户。
    - 下单：用户明确确认车型和价格后，调用 `ride_create_order`，传 `product_name`（ride_estimate 返回的车型名）和 `confirm=true`。调用后立即产生真实订单，不要在用户确认前调用。
    - 支付：滴滴订单由滴滴账号完成支付；插件不会自动支付，也没有支付工具。
    - 取消：用户明确要求取消时用 `ride_cancel_order`，传 `confirm=true`；未传 order_id 时取消最近创建的订单。
    - 订单状态：用 `ride_order_status`，结果包含状态、司机、车型、车牌、电话、评分、距上车点距离、预计到达和订单时间；返回限流提示时告诉用户约 1 分钟后重试，不要说成没有订单。司机位置：`ride_driver_location`，有司机、车牌、距离、预计到达、状态和更新时间时一并给出。
    - 工具结果会附上完整上游返回和简短使用说明。
    - 展示时只保留地名、车型、价格、车牌/司机/ETA 等用户需要的信息，不向用户暴露 traceId、车型 ID 等内部字段。
""".trimIndent()
