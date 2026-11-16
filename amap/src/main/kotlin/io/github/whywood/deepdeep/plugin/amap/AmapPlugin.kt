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

    - 找地点：`amap_place_search`（keywords + city），返回名称、地址、类型和图片；单次结果数量有限且不支持翻页，结果里没有坐标、距离、评分或电话。
    - 找附近：`amap_around_search`（中心点经纬度 + 可选 keywords/radius），结果同样没有坐标和距离，关键词由高德匹配。
    - 坐标转地址：`amap_regeo`（经纬度），只到省市区县，没有街道和门牌。
    - 路线：`amap_route_driving` / `amap_route_transit` / `amap_route_walking` / `amap_route_bicycling`，起终点用经纬度；返回路线和分段距离、耗时。高德不返回轨迹串，驾车有时只有 1 条备选；公交需要起点和终点城市，返回全部换乘方案。
    - 天气：`amap_weather`（城市名称或编码），按白天和夜间返回天气、温度、风向和风力，天数由高德决定。
    - 返回的数据以工具结果为准；结果带卡片 id 时用 present 交付卡片，正文写结论和下一步。
    - Key 在插件页配置；Key 无效或未配置时引导用户到插件页处理。
""".trimIndent()
