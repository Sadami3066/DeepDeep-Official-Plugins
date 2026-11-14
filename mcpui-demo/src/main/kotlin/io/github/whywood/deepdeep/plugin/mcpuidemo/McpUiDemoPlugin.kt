package io.github.whywood.deepdeep.plugin.mcpuidemo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.whywood.deepdeep.design.DdGroupCard
import io.github.whywood.deepdeep.design.DdSpacing
import io.github.whywood.deepdeep.design.DdText
import io.github.whywood.deepdeep.design.DdType
import io.github.whywood.deepdeep.design.deepDeepColors
import io.github.whywood.deepdeep.plugin.api.DeepDeepPlugin
import io.github.whywood.deepdeep.plugin.api.PluginHost
import io.github.whywood.deepdeep.plugin.api.PluginUiContributions
import io.github.whywood.deepdeep.plugin.api.ToolResultRendererContributor
import io.github.whywood.deepdeep.plugin.api.ToolResultSource
import io.github.whywood.deepdeep.plugin.api.available
import io.github.whywood.deepdeep.plugin.api.localizedText
import io.github.whywood.deepdeep.plugin.api.pluginPage
import io.github.whywood.deepdeep.plugin.api.toolResultMatch
import io.github.whywood.deepdeep.plugin.api.toolResultRenderer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class McpUiDemoPlugin : DeepDeepPlugin {
    override suspend fun availability(host: PluginHost) = available()

    override fun ui(host: PluginHost): PluginUiContributions = object :
        PluginUiContributions,
        ToolResultRendererContributor {
        override val bindings = emptyList<io.github.whywood.deepdeep.plugin.api.SlotBinding>()

        override val pages = listOf(
            pluginPage(
                id = "demo",
                title = localizedText(default = "MCP UI", zh = "MCP UI", en = "MCP UI"),
            ) {
                DemoPage()
            },
        )

        override val toolResultRenderers = listOf(
            toolResultRenderer(
                id = "demo-weather",
                match = toolResultMatch(
                    source = ToolResultSource.MCP,
                    server = "demo",
                    tool = "weather",
                ),
                priority = 10,
            ) { state ->
                WeatherCard(state.structuredContent as? JsonObject)
            },
        )
    }
}

@Composable
private fun DemoPage() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = DdSpacing.Xl, vertical = DdSpacing.Lg),
    ) {
        WeatherCard(
            buildJsonObject {
                put("city", "杭州")
                put("temperature", 26)
                put("condition", "晴")
                put("high", 29)
                put("low", 20)
            },
        )
    }
}

@Composable
private fun WeatherCard(data: JsonObject?) {
    val colors = deepDeepColors()
    val city = data?.get("city")?.jsonPrimitive?.content ?: "杭州"
    val temp = data?.get("temperature")?.jsonPrimitive?.intOrNull ?: 26
    val condition = data?.get("condition")?.jsonPrimitive?.content ?: "晴"
    val high = data?.get("high")?.jsonPrimitive?.intOrNull
    val low = data?.get("low")?.jsonPrimitive?.intOrNull
    DdGroupCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = DdSpacing.Card, vertical = DdSpacing.Md),
            verticalArrangement = Arrangement.spacedBy(DdSpacing.Xs),
        ) {
            DdText(text = city, style = DdType.bodySmall, color = colors.inkSecondary)
            Row(verticalAlignment = Alignment.CenterVertically) {
                DdText(
                    text = temp.toString(),
                    color = colors.ink,
                    style = DdType.heading,
                )
                DdText(
                    text = "°C  $condition",
                    modifier = Modifier.padding(start = DdSpacing.Sm),
                    style = DdType.body,
                    color = colors.ink,
                )
            }
            if (high != null && low != null) {
                DdText(
                    text = "最高 $high° / 最低 $low°",
                    style = DdType.bodySmall,
                    color = colors.inkTertiary,
                )
            }
        }
    }
}
