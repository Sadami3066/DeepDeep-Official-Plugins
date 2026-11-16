package io.github.whywood.deepdeep.plugin.amap

import androidx.compose.runtime.Composable
import io.github.whywood.deepdeep.plugin.api.Contributions
import io.github.whywood.deepdeep.plugin.api.DeepDeepPlugin
import io.github.whywood.deepdeep.plugin.api.PluginAvailability
import io.github.whywood.deepdeep.plugin.api.PluginHost
import io.github.whywood.deepdeep.plugin.api.PluginPage
import io.github.whywood.deepdeep.plugin.api.PluginSkill
import io.github.whywood.deepdeep.plugin.api.PluginUiContributions
import io.github.whywood.deepdeep.plugin.api.SlotBinding
import io.github.whywood.deepdeep.plugin.api.ToolResultRenderer
import io.github.whywood.deepdeep.plugin.api.ToolResultRendererContributor
import io.github.whywood.deepdeep.plugin.api.ToolResultSource
import io.github.whywood.deepdeep.plugin.api.ToolResultState
import io.github.whywood.deepdeep.plugin.api.assetIcon
import io.github.whywood.deepdeep.plugin.api.available
import io.github.whywood.deepdeep.plugin.api.contributions
import io.github.whywood.deepdeep.plugin.api.localizedText
import io.github.whywood.deepdeep.plugin.api.pluginPage
import io.github.whywood.deepdeep.plugin.api.pluginSkill
import io.github.whywood.deepdeep.plugin.api.toolResultMatch
import io.github.whywood.deepdeep.plugin.api.toolResultRenderer
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

class AmapPlugin : DeepDeepPlugin {
    override suspend fun availability(host: PluginHost): PluginAvailability = available()

    override suspend fun onEnable(host: PluginHost) {
        runCatching { AmapClient(host).migrateLegacyKey() }
            .onFailure { runCatching { host.logs().warn("amap key migration failed") } }
    }

    override suspend fun onUpgrade(host: PluginHost, fromVersion: String) {
        runCatching { AmapClient(host).migrateLegacyKey() }
            .onFailure { runCatching { host.logs().warn("amap key migration failed") } }
    }

    override suspend fun uninstall(host: PluginHost) {
        withContext(NonCancellable) {
            runCatching { AmapClient(host).clearKey() }
            runCatching { host.settings().clear() }
        }
    }

    override fun contributions(host: PluginHost): Contributions = contributions(tools = amapTools(host))

    override fun skills(host: PluginHost): List<PluginSkill> = listOf(
        pluginSkill(id = SKILL_ID, markdown = SKILL_MARKDOWN),
    )

    override fun ui(host: PluginHost): PluginUiContributions = object : PluginUiContributions, ToolResultRendererContributor {
        override val bindings: List<SlotBinding> = emptyList()

        override val pages: List<PluginPage> = listOf(
            pluginPage(
                id = PAGE_ID,
                title = localizedText(mapOf("default" to "高德地图", "zh" to "高德地图", "en" to "Amap Maps")),
                icon = assetIcon(ICON_ASSET),
            ) { AmapPage(host) },
        )

        override val toolResultRenderers: List<ToolResultRenderer> = listOf(
            renderer("amap-place-search", "amap_place_search") { state -> AmapPlacesCard(host, state, around = false) },
            renderer("amap-around-search", "amap_around_search") { state -> AmapPlacesCard(host, state, around = true) },
            renderer("amap-regeo", "amap_regeo") { state -> AmapRegeoCard(host, state) },
            renderer("amap-route-driving", "amap_route_driving") { state -> AmapRouteCard(host, state) },
            renderer("amap-route-transit", "amap_route_transit") { state -> AmapRouteCard(host, state) },
            renderer("amap-route-walking", "amap_route_walking") { state -> AmapRouteCard(host, state) },
            renderer("amap-route-bicycling", "amap_route_bicycling") { state -> AmapRouteCard(host, state) },
            renderer("amap-weather", "amap_weather") { state -> AmapWeatherCard(host, state) },
        )
    }

    private fun renderer(
        id: String,
        tool: String,
        content: @Composable (ToolResultState) -> Unit,
    ): ToolResultRenderer = toolResultRenderer(
        id = id,
        match = toolResultMatch(source = ToolResultSource.PLUGIN, tool = tool),
        priority = 20,
        content = content,
    )

    private companion object {
        const val SKILL_ID = "deepdeep-amap"
        const val PAGE_ID = "main"
        const val ICON_ASSET = "icon.png"
    }
}

private val SKILL_MARKDOWN = """
    ---
    name: deepdeep-amap
    description: 高德地图用法：地点搜索、周边搜索、坐标转地址、驾车/公交/步行/骑行路线和天气；当用户提到地图、地点、周边、路线、导航、距离、天气或高德时使用。
    ---

    # 高德地图

    DeepDeep 高德地图插件提供八个只读工具，导航和打车在高德地图 App 内由用户完成。

    - 找地点：`amap_place_search`（keywords + city），返回名称、地址、类型、电话、评分、商圈、照片、城市、坐标、距离和总条数；服务支持分页时可用 page_size/page_num 继续。
    - 找附近：`amap_around_search`（中心点经纬度 + keywords + radius），返回附近地点的名称、地址、类型、电话、评分、商圈、照片、城市、坐标、距离和总条数。
    - 坐标转地址：`amap_regeo`（经纬度），返回完整地址、省市区县、街道门牌和坐标。
    - 路线：`amap_route_driving` / `amap_route_transit` / `amap_route_walking` / `amap_route_bicycling`；返回最优路线和全部备选，含起终点名称和坐标、每条路线的距离与耗时；驾车、步行和骑行包含全部步骤与每步耗时，公交包含全部换乘分段、步行距离、出入口、停靠站和首末班时间。公交路线需要 city 和 cityd。
    - 天气：`amap_weather`（城市名称或城市编码），返回全部预报天，含白天与夜间的天气、温度、风向和风力。
    - 不要编造坐标、路线、距离、时间或天气；所有数据以工具返回为准。
    - 工具结果带卡片 id 时，用 present 交付卡片，正文只写结论和下一步。
    - Key 由用户在插件页配置；工具提示 Key 无效或未配置时，引导用户到插件页填写并验证。
""".trimIndent()
