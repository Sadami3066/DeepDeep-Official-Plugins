package io.github.whywood.deepdeep.plugin.maps

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.whywood.deepdeep.design.DdGroupCard
import io.github.whywood.deepdeep.design.DdNoticeBar
import io.github.whywood.deepdeep.design.DdNoticeTone
import io.github.whywood.deepdeep.design.DdSpacing
import io.github.whywood.deepdeep.design.DdText
import io.github.whywood.deepdeep.design.DdType
import io.github.whywood.deepdeep.design.DdWebContent
import io.github.whywood.deepdeep.design.DdWebView
import io.github.whywood.deepdeep.design.deepDeepColors
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
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File

private const val TOOL_NAME = "map_show"
private val JsonFormat = Json { ignoreUnknownKeys = true; isLenient = true }

class MapsPlugin : DeepDeepPlugin {
    override suspend fun availability(host: PluginHost): PluginAvailability = available()

    override fun contributions(host: PluginHost) = contributions(
        tools = listOf(
            pluginTool(
                name = TOOL_NAME,
                label = host.t("地图", "Map"),
                description = host.t(
                    "在聊天里显示一个地点。参数：latitude 和 longitude 必填，title 可选（默认“地图”），zoom 可选（1–18）。",
                    "Show a place on a map in chat. Parameters: latitude and longitude required, optional title (default “Map”) and zoom (1–18).",
                ),
                parameters = buildJsonObject {
                    put("type", "object")
                    put(
                        "properties",
                        buildJsonObject {
                            put("latitude", buildJsonObject { put("type", "number"); put("description", host.t("纬度", "Latitude")) })
                            put("longitude", buildJsonObject { put("type", "number"); put("description", host.t("经度", "Longitude")) })
                            put("title", buildJsonObject { put("type", "string"); put("description", host.t("地点名称", "Place name")) })
                            put("zoom", buildJsonObject { put("type", "integer"); put("description", host.t("缩放级别 1–18", "Zoom 1–18")) })
                        },
                    )
                    put("required", JsonArray(listOf(JsonPrimitive("latitude"), JsonPrimitive("longitude"))))
                },
                promptSnippet = host.t(
                    "需要给用户看位置时用 map_show，坐标由你从搜索结果中确认后传入。",
                    "Use map_show when the user should see a location; pass coordinates you confirmed from a search result.",
                ),
                execute = { call -> executeShow(host, call) },
            ),
        ),
    )

    override fun ui(host: PluginHost): PluginUiContributions = object : PluginUiContributions {
        override val bindings: List<SlotBinding> = listOf(
            slotBinding(
                slotId = SlotIds.toolCard(TOOL_NAME),
                content = composeSlot { state ->
                    MapToolCard(host, state as ToolCardSlotState)
                },
            ),
        )

        override val pages = listOf(
            pluginPage(
                id = "main",
                title = localizedText(default = "地图", zh = "地图", en = "Maps"),
            ) {
                MapPage(host)
            },
        )
    }

    private suspend fun executeShow(host: PluginHost, call: PluginToolCall): PluginToolResult {
        val latitude = call.args["latitude"]?.jsonPrimitive?.doubleOrNull
        val longitude = call.args["longitude"]?.jsonPrimitive?.doubleOrNull
        val title = call.args["title"]?.jsonPrimitive?.contentOrNull?.trim().takeUnless { it.isNullOrBlank() }
            ?: host.t("地图", "Map")
        val zoom = call.args["zoom"]?.jsonPrimitive?.intOrNull?.coerceIn(1, 18) ?: 13
        if (latitude == null || longitude == null || latitude !in -90.0..90.0 || longitude !in -180.0..180.0) {
            return textResult(host.t("请提供有效的纬度和经度。", "Provide valid latitude and longitude."), isError = true)
        }
        val details = MapToolDetails(latitude = latitude, longitude = longitude, title = title, zoom = zoom)
        return textResult(
            text = host.t("已显示 $title（$latitude, $longitude）。", "Showing $title ($latitude, $longitude)."),
            details = JsonFormat.encodeToJsonElement(MapToolDetails.serializer(), details) as JsonObject,
        )
    }
}

@Composable
private fun MapPage(host: PluginHost) {
    val html = remember(host.assetsDir) {
        runCatching { File(host.assetsDir, "map.html").readText() }.getOrDefault("")
    }
    val page = remember(html) { mapHtml(html, 30.2765, 120.1595, host.t("杭州", "Hangzhou"), 12) }
    var error by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().weight(1f)) {
            DdWebView(
                content = DdWebContent.Html(
                    html = page,
                    baseUrl = "file://" + host.assetsDir.absolutePath.trimEnd('/') + "/",
                ),
                modifier = Modifier.fillMaxSize(),
                allowFileAccess = true,
                javaScriptEnabled = true,
                domStorageEnabled = true,
                darkMode = false,
                onError = {
                    error = host.t("地图加载失败，请检查网络后重试。", "The map didn't load. Check your network and try again.")
                },
            )
            error?.let { message ->
                DdNoticeBar(
                    text = message,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .padding(DdSpacing.Xl),
                    tone = DdNoticeTone.DANGER,
                    actionLabel = host.t("重试", "Retry"),
                    onAction = { error = null },
                )
            }
        }
    }
}

@Composable
private fun MapToolCard(host: PluginHost, state: ToolCardSlotState) {
    val result = state.result
    if (result == null) {
        DdGroupCard {
            DdText(
                text = host.t("正在准备地图…", "Preparing the map…"),
                modifier = Modifier.padding(horizontal = DdSpacing.Card, vertical = DdSpacing.Lg),
                style = DdType.bodySmall,
                color = deepDeepColors().inkSecondary,
            )
        }
        return
    }
    if (result.isError) {
        DdNoticeBar(result.text ?: host.t("地图加载失败", "Map failed to load"), tone = DdNoticeTone.DANGER)
        return
    }
    val details = result.details?.let { runCatching { JsonFormat.decodeFromJsonElement(MapToolDetails.serializer(), it) }.getOrNull() }
    if (details == null) {
        DdNoticeBar(result.text ?: host.t("地图数据不完整", "Map data is incomplete"), tone = DdNoticeTone.DANGER)
        return
    }
    val html = remember(host.assetsDir, details) {
        val asset = runCatching { File(host.assetsDir, "map.html").readText() }.getOrDefault("")
        mapHtml(asset, details.latitude, details.longitude, details.title, details.zoom, compact = true)
    }
    DdGroupCard {
        Column(Modifier.fillMaxWidth()) {
            DdText(
                text = details.title,
                modifier = Modifier.padding(horizontal = DdSpacing.Card, vertical = DdSpacing.Md),
                style = DdType.body,
                color = deepDeepColors().ink,
            )
            DdWebView(
                content = DdWebContent.Html(
                    html = html,
                    baseUrl = "file://" + host.assetsDir.absolutePath.trimEnd('/') + "/",
                ),
                modifier = Modifier.fillMaxWidth().height(240.dp),
                allowFileAccess = true,
                javaScriptEnabled = true,
                domStorageEnabled = true,
                darkMode = false,
            )
        }
    }
}

private fun mapHtml(asset: String, latitude: Double, longitude: Double, title: String, zoom: Int, compact: Boolean = false): String {
    val escapedTitle = title.replace("\\", "\\\\").replace("'", "\\'")
    return asset
        .replace("__LAT__", latitude.toString())
        .replace("__LNG__", longitude.toString())
        .replace("__TITLE__", escapedTitle)
        .replace("__ZOOM__", zoom.toString())
        .replace("__COMPACT__", if (compact) "true" else "false")
}

private fun PluginHost.t(zh: String, en: String): String =
    text(localizedText(default = zh, zh = zh, en = en))
