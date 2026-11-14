package io.github.whywood.deepdeep.plugin.hotel

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
import io.github.whywood.deepdeep.design.DdType
import io.github.whywood.deepdeep.design.deepDeepColors
import io.github.whywood.deepdeep.plugin.api.DeepDeepPlugin
import io.github.whywood.deepdeep.plugin.api.PluginAvailability
import io.github.whywood.deepdeep.plugin.api.PluginHost
import io.github.whywood.deepdeep.plugin.api.PluginUiContributions
import io.github.whywood.deepdeep.plugin.api.ToolResultRendererContributor
import io.github.whywood.deepdeep.plugin.api.ToolResultSource
import io.github.whywood.deepdeep.plugin.api.ToolResultState
import io.github.whywood.deepdeep.plugin.api.available
import io.github.whywood.deepdeep.plugin.api.toolResultMatch
import io.github.whywood.deepdeep.plugin.api.toolResultRenderer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

class HotelPlugin : DeepDeepPlugin {
    override suspend fun availability(host: PluginHost): PluginAvailability = available()

    override fun ui(host: PluginHost): PluginUiContributions = object : PluginUiContributions, ToolResultRendererContributor {
        override val bindings = emptyList<io.github.whywood.deepdeep.plugin.api.SlotBinding>()
        override val pages = emptyList<io.github.whywood.deepdeep.plugin.api.PluginPage>()

        override val toolResultRenderers = listOf(
        toolResultRenderer(
            id = "rollinggo-hotel-search",
            match = toolResultMatch(source = ToolResultSource.MCP, tool = "searchHotels"),
            priority = 20,
        ) { state -> HotelSearchCard(state) },
        toolResultRenderer(
            id = "rollinggo-hotel-detail",
            match = toolResultMatch(source = ToolResultSource.MCP, tool = "getHotelDetail"),
            priority = 20,
        ) { state -> HotelDetailCard(state) },
        )
    }
}

@Composable
private fun HotelSearchCard(state: ToolResultState) {
    val colors = deepDeepColors()
    val payload = state.structuredContent ?: HotelPayload.parseText(state.result?.text)
    val hotels = HotelPayload.hotels(payload)
    val place = (state.arguments["place"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
        ?: (state.arguments["originQuery"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
        ?: "酒店"
    val nights = (state.arguments["checkInParam"] as? JsonObject)
        ?.get("stayNights")?.jsonPrimitive?.intOrNull
    DdGroupCard {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = DdSpacing.Card, vertical = DdSpacing.Lg),
            verticalArrangement = Arrangement.spacedBy(DdSpacing.Sm),
        ) {
            DdText(
                text = place,
                style = DdType.heading,
                color = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            DdText(
                text = if (nights != null) "酒店 · $nights 晚" else "酒店",
                style = DdType.bodySmall,
                color = colors.inkTertiary,
            )
            if (hotels.isEmpty()) {
                DdHairline()
                DdText(
                    text = state.result?.text ?: "没有解析到酒店列表。",
                    style = DdType.bodySmall,
                    color = colors.inkSecondary,
                )
            } else {
                DdHairline()
                hotels.take(8).forEachIndexed { index, hotel ->
                    HotelRow(hotel)
                    if (index < hotels.take(8).lastIndex) DdHairline()
                }
                DdText(
                    text = "结果来自 RollingGo 酒店 MCP，价格以实际查询为准",
                    style = DdType.bodySmall,
                    color = colors.inkTertiary,
                )
            }
        }
    }
}

@Composable
private fun HotelRow(hotel: HotelItem) {
    val colors = deepDeepColors()
    Column(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            DdText(
                text = hotel.name,
                modifier = Modifier.weight(1f),
                style = DdType.body,
                color = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            hotel.price?.let {
                Spacer(Modifier.width(DdSpacing.Sm))
                DdText(text = it, style = DdType.body, color = colors.ink)
            }
        }
        val meta = listOfNotNull(hotel.star?.let { if (it.endsWith("星")) it else "$it 星" }, hotel.distance).joinToString(" · ")
        if (meta.isNotBlank() || hotel.address != null) {
            DdText(
                text = listOfNotNull(meta.takeIf { it.isNotBlank() }, hotel.address).joinToString(" · "),
                style = DdType.bodySmall,
                color = colors.inkSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (hotel.tags.isNotEmpty()) {
            DdText(
                text = hotel.tags.joinToString(" · "),
                style = DdType.bodySmall,
                color = colors.inkTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun HotelDetailCard(state: ToolResultState) {
    val colors = deepDeepColors()
    val payload = state.structuredContent ?: HotelPayload.parseText(state.result?.text)
    val rooms = HotelPayload.rooms(payload)
    val hotelName = HotelPayload.hotelName(payload) ?: "酒店房型"
    DdGroupCard {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = DdSpacing.Card, vertical = DdSpacing.Lg),
            verticalArrangement = Arrangement.spacedBy(DdSpacing.Sm),
        ) {
            DdText(hotelName, style = DdType.heading, color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (rooms.isEmpty()) {
                DdText(
                    text = state.result?.text ?: "没有解析到房型。",
                    style = DdType.bodySmall,
                    color = colors.inkSecondary,
                )
            } else {
                DdHairline()
                rooms.take(8).forEachIndexed { index, room ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            DdText(
                                text = room.name,
                                modifier = Modifier.weight(1f),
                                style = DdType.body,
                                color = colors.ink,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            room.price?.let { DdText(it, style = DdType.body, color = colors.ink) }
                        }
                        room.policy?.let {
                            DdText(it, style = DdType.bodySmall, color = colors.inkSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (index < rooms.take(8).lastIndex) DdHairline()
                }
            }
            DdText("价格和退改规则以实际查询为准", style = DdType.bodySmall, color = colors.inkTertiary)
        }
    }
}
