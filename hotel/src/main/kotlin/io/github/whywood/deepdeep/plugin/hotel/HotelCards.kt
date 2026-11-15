package io.github.whywood.deepdeep.plugin.hotel

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.whywood.deepdeep.design.DdAlpha
import io.github.whywood.deepdeep.design.DdHairline
import io.github.whywood.deepdeep.design.DdIcon
import io.github.whywood.deepdeep.design.DdIcons
import io.github.whywood.deepdeep.design.DdPressable
import io.github.whywood.deepdeep.design.DdPrimaryButton
import io.github.whywood.deepdeep.design.DdSpacing
import io.github.whywood.deepdeep.design.DdSpinner
import io.github.whywood.deepdeep.design.DdText
import io.github.whywood.deepdeep.design.DdTextAction
import io.github.whywood.deepdeep.design.DdType
import io.github.whywood.deepdeep.design.DeepDeepColors
import io.github.whywood.deepdeep.design.LocalDdMotionEnabled
import io.github.whywood.deepdeep.design.deepDeepColors
import io.github.whywood.deepdeep.plugin.api.PluginHost
import io.github.whywood.deepdeep.plugin.api.ToolCardSlotState
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

private val HotelCardShape = RoundedCornerShape(20.dp)
private val HotelTagShape = RoundedCornerShape(7.dp)
private const val SEARCH_PREVIEW_ROWS = 3
private const val ROOM_PREVIEW_ROWS = 3
private const val ORDER_PREVIEW_ROWS = 3

private enum class HotelTone { NEUTRAL, ACCENT, SUCCESS, WARNING, DANGER }

@Composable
private fun HotelCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val colors = deepDeepColors()
    Column(modifier.fillMaxWidth().clip(HotelCardShape).background(colors.surface)) {
        content()
    }
}

@Composable
private fun rememberHotelAppear(): Float {
    val motion = LocalDdMotionEnabled.current
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }
    val alpha by animateFloatAsState(
        targetValue = if (visible || !motion) 1f else 0f,
        animationSpec = tween(durationMillis = if (motion) 200 else 0),
        label = "hotel-card-appear",
    )
    return alpha
}

@Composable
private fun HotelTag(text: String, tone: HotelTone = HotelTone.NEUTRAL, modifier: Modifier = Modifier) {
    val colors = deepDeepColors()
    val color = tone.color(colors)
    val background = when (tone) {
        HotelTone.NEUTRAL -> colors.wash
        HotelTone.ACCENT -> colors.accentSoft
        else -> color.copy(alpha = 0.12f)
    }
    Box(
        modifier
            .clip(HotelTagShape)
            .background(background)
            .padding(horizontal = 7.dp, vertical = 3.dp),
    ) {
        DdText(
            text = text,
            style = DdType.chip.copy(fontSize = 11.sp, lineHeight = 15.sp),
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun HotelTone.color(colors: DeepDeepColors) = when (this) {
    HotelTone.NEUTRAL -> colors.inkSecondary
    HotelTone.ACCENT -> colors.accent
    HotelTone.SUCCESS -> colors.success
    HotelTone.WARNING -> colors.warning
    HotelTone.DANGER -> colors.danger
}

@Composable
private fun SkeletonBar(
    width: Dp? = null,
    height: Dp = 12.dp,
    modifier: Modifier = Modifier,
) {
    val colors = deepDeepColors()
    val size = if (width == null) Modifier.fillMaxWidth() else Modifier.width(width)
    Box(
        modifier
            .then(size)
            .height(height)
            .clip(RoundedCornerShape(6.dp))
            .background(colors.wash),
    )
}

@Composable
private fun HotelEmptyState(title: String, hint: String, icon: DdIcon = DdIcons.Search, note: String? = null) {
    val colors = deepDeepColors()
    HotelCard {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = DdSpacing.Card, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.size(46.dp).clip(CircleShape).background(colors.wash), contentAlignment = Alignment.Center) {
                DdIcon(icon = icon, tint = colors.inkTertiary, size = 20.dp)
            }
            Spacer(Modifier.height(10.dp))
            DdText(title, style = DdType.label, color = colors.ink, textAlign = TextAlign.Center)
            Spacer(Modifier.height(4.dp))
            DdText(
                hint,
                style = DdType.bodySmall,
                color = colors.inkSecondary,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            note?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(6.dp))
                DdText(it, style = DdType.chip, color = colors.inkTertiary, textAlign = TextAlign.Center, maxLines = 1)
            }
        }
    }
}

@Composable
private fun HotelErrorState(host: PluginHost, message: String) {
    val colors = deepDeepColors()
    HotelCard {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = DdSpacing.Card, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(3.dp).height(34.dp).clip(RoundedCornerShape(2.dp)).background(colors.danger))
            Spacer(Modifier.width(DdSpacing.Md))
            DdText(
                text = message,
                modifier = Modifier.weight(1f),
                style = DdType.bodySmall,
                color = colors.ink,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            DdTextAction(text = host.t("去连接", "Open"), onClick = { host.openHotelPage() })
        }
    }
}

@Composable
private fun HotelFallbackCard(host: PluginHost, text: String?) {
    val colors = deepDeepColors()
    HotelCard {
        DdText(
            text = text ?: host.t("没有酒店数据。", "No hotel data."),
            modifier = Modifier.padding(horizontal = DdSpacing.Card, vertical = 18.dp),
            style = DdType.bodySmall,
            color = colors.inkSecondary,
        )
    }
}

@Composable
internal fun HotelSearchCard(host: PluginHost, state: ToolCardSlotState) {
    val result = state.result
    if (result == null) {
        HotelSearchLoading(host)
        return
    }
    if (result.isError) {
        HotelErrorState(host, result.text ?: host.t("酒店搜索失败", "Hotel search failed"))
        return
    }
    val details = result.details?.let(::decodeSearch)
    if (details == null) {
        HotelFallbackCard(host, result.text)
        return
    }
    if (details.hotels.isEmpty()) {
        val context = listOfNotNull(details.place.takeIf { it.isNotBlank() }, details.checkIn).joinToString(" · ")
        HotelEmptyState(
            title = host.t("没有找到符合条件的酒店", "No hotels matched"),
            hint = host.t("可以放宽星级、距离或价格条件再试", "Try relaxing stars, distance or price"),
            icon = DdIcons.Search,
            note = context.ifBlank { null },
        )
        return
    }
    val appear = rememberHotelAppear()
    HotelCard(Modifier.graphicsLayer { alpha = appear }) {
        val hotel = details.hotels.first()
        SearchHero(host, hotel, details)
        val rows = details.hotels.drop(1).take(SEARCH_PREVIEW_ROWS)
        rows.forEach { item ->
            DdHairline(Modifier.padding(start = 74.dp, end = 16.dp))
            CompactHotelRow(host, item)
        }
        val remaining = details.hotels.size - 1 - rows.size
        SearchFooter(host, details, remaining)
    }
}

@Composable
private fun SearchHero(host: PluginHost, hotel: HotelSummary, details: HotelSearchDetails) {
    val colors = deepDeepColors()
    val star = hotel.starRating?.takeIf { it.isNotBlank() }?.let { "$it★" }
    val distance = hotelDistanceText(hotel.distanceMeters) ?: hotel.distance
    val placeLine = listOfNotNull(hotel.address?.takeIf { it.isNotBlank() }, distance)
        .joinToString(" · ")
        .ifBlank { details.place }
    val body: @Composable () -> Unit = {
        Box(
            Modifier
                .fillMaxWidth()
                .height(150.dp)
                .background(Brush.linearGradient(listOf(colors.accentSoft, colors.wash))),
        ) {
            Canvas(Modifier.matchParentSize()) {
                val unit = size.minDimension
                drawCircle(
                    color = colors.accent.copy(alpha = 0.10f),
                    radius = unit * 0.44f,
                    center = Offset(size.width * 0.94f, size.height * 0.04f),
                )
                drawCircle(
                    color = colors.accent.copy(alpha = 0.16f),
                    radius = unit * 0.62f,
                    center = Offset(size.width * 0.94f, size.height * 0.04f),
                    style = Stroke(width = 1.dp.toPx()),
                )
                drawCircle(
                    color = colors.accent.copy(alpha = 0.07f),
                    radius = unit * 0.34f,
                    center = Offset(size.width * 0.04f, size.height * 1.02f),
                )
            }
            star?.let {
                Box(
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 14.dp, top = 12.dp)
                        .clip(HotelTagShape)
                        .background(colors.surface)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    DdText(it, style = DdType.chip.copy(fontSize = 11.sp), color = colors.ink)
                }
            }
            Row(
                Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 13.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                Column(Modifier.weight(1f)) {
                    DdText(
                        text = hotel.name,
                        style = DdType.heading.copy(fontSize = 19.sp, lineHeight = 24.sp),
                        color = colors.ink,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (placeLine.isNotBlank()) {
                        Spacer(Modifier.height(3.dp))
                        DdText(
                            text = placeLine,
                            style = DdType.chip.copy(fontSize = 11.sp, lineHeight = 15.sp),
                            color = colors.inkSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(Modifier.width(DdSpacing.Md))
                Column(horizontalAlignment = Alignment.End) {
                    DdText(host.t("起价", "From"), style = DdType.chip.copy(fontSize = 11.sp), color = colors.inkSecondary)
                    Row(verticalAlignment = Alignment.Bottom) {
                        DdText(
                            text = hotel.priceText ?: host.t("待确认", "TBD"),
                            style = DdType.heading.copy(fontSize = 27.sp, lineHeight = 30.sp),
                            color = if (hotel.priceText != null) colors.accent else colors.inkSecondary,
                            maxLines = 1,
                        )
                        hotel.priceText?.let {
                            DdText(host.t(" 起", " up"), style = DdType.chip.copy(fontSize = 11.sp), color = colors.inkSecondary)
                        }
                    }
                }
            }
        }
    }
    val url = hotel.bookingUrl
    if (url != null) {
        DdPressable(onClick = { host.openHotelBooking(url) }, shape = RectangleShape, contentDescription = hotel.name) {
            body()
        }
    } else {
        body()
    }
}

@Composable
private fun CompactHotelRow(host: PluginHost, hotel: HotelSummary) {
    val colors = deepDeepColors()
    val star = hotel.starRating?.takeIf { it.isNotBlank() }?.let { "$it★" }
    val meta = listOfNotNull(
        star,
        hotelDistanceText(hotel.distanceMeters) ?: hotel.distance,
        hotel.tags.firstOrNull(),
    ).joinToString(" · ")
    val body: @Composable () -> Unit = {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Brush.linearGradient(listOf(colors.accentSoft, colors.wash))),
                contentAlignment = Alignment.Center,
            ) {
                DdText(
                    text = hotel.name.trim().take(1),
                    style = DdType.label.copy(fontSize = 17.sp),
                    color = colors.accent,
                )
            }
            Spacer(Modifier.width(DdSpacing.Md))
            Column(Modifier.weight(1f)) {
                DdText(
                    text = hotel.name,
                    style = DdType.label.copy(fontSize = 15.sp, lineHeight = 21.sp),
                    color = colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (meta.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    DdText(
                        text = meta,
                        style = DdType.chip.copy(fontSize = 11.sp, lineHeight = 15.sp),
                        color = colors.inkSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(DdSpacing.Sm))
            Column(horizontalAlignment = Alignment.End) {
                DdText(
                    text = hotel.priceText ?: host.t("待确认", "TBD"),
                    style = DdType.label.copy(fontSize = 17.sp, lineHeight = 22.sp),
                    color = if (hotel.priceText != null) colors.ink else colors.inkSecondary,
                    maxLines = 1,
                )
                if (hotel.priceText != null) {
                    DdText(host.t("起/晚", "from/night"), style = DdType.chip.copy(fontSize = 10.sp), color = colors.inkTertiary)
                }
            }
        }
    }
    val url = hotel.bookingUrl
    if (url != null) {
        DdPressable(onClick = { host.openHotelBooking(url) }, shape = RectangleShape, contentDescription = hotel.name) {
            body()
        }
    } else {
        body()
    }
}

@Composable
private fun SearchFooter(host: PluginHost, details: HotelSearchDetails, remaining: Int) {
    val colors = deepDeepColors()
    DdHairline()
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            val context = listOfNotNull(
                details.place.takeIf { it.isNotBlank() },
                details.checkIn?.takeIf { it.isNotBlank() }?.let {
                    host.t("$it 入住 ${details.nights} 晚", "$it · ${details.nights} night(s)")
                },
            ).joinToString(" · ")
            if (context.isNotBlank()) {
                DdText(
                    text = context,
                    style = DdType.chip.copy(fontSize = 11.sp, lineHeight = 15.sp),
                    color = colors.inkSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (remaining > 0) {
                DdText(
                    text = host.t("还有 $remaining 家酒店", "$remaining more hotels"),
                    style = DdType.chip.copy(fontSize = 11.sp, lineHeight = 15.sp),
                    color = colors.inkTertiary,
                    maxLines = 1,
                )
            }
        }
        val url = details.hotels.firstNotNullOfOrNull { it.bookingUrl }
        if (url != null) {
            DdTextAction(text = host.t("去 RollingGo 预订", "Book on RollingGo"), onClick = { host.openHotelBooking(url) })
        }
    }
}

@Composable
internal fun HotelDetailCard(host: PluginHost, state: ToolCardSlotState) {
    val result = state.result
    if (result == null) {
        HotelDetailLoading(host)
        return
    }
    if (result.isError) {
        HotelErrorState(host, result.text ?: host.t("房型查询失败", "Room lookup failed"))
        return
    }
    val details = result.details?.let(::decodeDetail)
    if (details == null) {
        HotelFallbackCard(host, result.text)
        return
    }
    val colors = deepDeepColors()
    val groups = details.roomGroups.ifEmpty { groupHotelRooms(details.rooms) }
    if (groups.isEmpty()) {
        HotelEmptyState(
            title = host.t("暂时没有可订房型", "No rooms available right now"),
            hint = host.t("换一家酒店或日期再试", "Try another hotel or date"),
            icon = DdIcons.Info,
            note = details.errorMessage?.takeIf { it.isNotBlank() } ?: details.name,
        )
        return
    }
    val appear = rememberHotelAppear()
    val nights = hotelNights(details.checkIn, details.checkOut)
    HotelCard(Modifier.graphicsLayer { alpha = appear }) {
        Row(
            Modifier.fillMaxWidth().padding(start = DdSpacing.Card, end = DdSpacing.Card, top = 14.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                DdText(
                    text = details.name,
                    style = DdType.label.copy(fontSize = 16.sp, lineHeight = 22.sp),
                    color = colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val sub = listOfNotNull(
                    details.checkIn?.let { checkIn ->
                        details.checkOut?.let { checkOut -> "${shortDate(checkIn)} → ${shortDate(checkOut)}" }
                    },
                    host.t("$nights 晚", "$nights night(s)"),
                    host.t("每间 ${details.adultCount} 位成人", "${details.adultCount} adult(s) each"),
                ).joinToString(" · ")
                if (sub.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    DdText(
                        text = sub,
                        style = DdType.chip.copy(fontSize = 11.sp, lineHeight = 15.sp),
                        color = colors.inkSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(DdSpacing.Sm))
            HotelTag(text = host.t("$nights 晚", "$nights night(s)"), tone = HotelTone.ACCENT)
        }
        DdHairline()
        groups.take(ROOM_PREVIEW_ROWS).forEach { group ->
            RoomPlanRow(host, group)
            DdHairline(Modifier.padding(start = DdSpacing.Card))
        }
        if (groups.size > ROOM_PREVIEW_ROWS) {
            DdText(
                text = host.t("还有 ${groups.size - ROOM_PREVIEW_ROWS} 个房型", "${groups.size - ROOM_PREVIEW_ROWS} more room types"),
                modifier = Modifier.padding(horizontal = DdSpacing.Card, vertical = 8.dp),
                style = DdType.chip.copy(fontSize = 11.sp),
                color = colors.inkTertiary,
            )
        }
        DetailFooter(host, details, nights)
    }
}

@Composable
private fun RoomPlanRow(host: PluginHost, group: HotelRoomGroup) {
    val colors = deepDeepColors()
    val cheapest = group.plans.firstOrNull()
    val nightly = cheapest?.avgPriceText
    val total = cheapest?.priceText
    val cancellation = group.cheapestCancellation
    val cancellationTone = when {
        cancellation == null -> HotelTone.NEUTRAL
        cancellation.contains("不可") || cancellation.contains("Non-refundable", ignoreCase = true) -> HotelTone.DANGER
        cancellation.contains("免费") || cancellation.contains("Free", ignoreCase = true) -> HotelTone.SUCCESS
        else -> HotelTone.WARNING
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = DdSpacing.Card, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            DdText(
                text = group.name,
                style = DdType.label.copy(fontSize = 15.sp, lineHeight = 21.sp),
                color = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(5.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                group.bed?.takeIf { it.isNotBlank() }?.let { HotelTag(it, modifier = Modifier.weight(1f, fill = false)) }
                cheapest?.meal?.takeIf { it.isNotBlank() }?.let { HotelTag(it, modifier = Modifier.weight(1f, fill = false)) }
            }
            Spacer(Modifier.height(5.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(5.dp).clip(CircleShape).background(cancellationTone.color(colors)))
                Spacer(Modifier.width(5.dp))
                DdText(
                    text = cancellation ?: host.t("取消政策未提供", "Cancellation policy not provided"),
                    style = DdType.chip.copy(fontSize = 11.sp, lineHeight = 15.sp),
                    color = cancellationTone.color(colors),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(DdSpacing.Md))
        Column(horizontalAlignment = Alignment.End) {
            when {
                nightly != null -> Row(verticalAlignment = Alignment.Bottom) {
                    DdText(
                        text = nightly,
                        style = DdType.heading.copy(fontSize = 22.sp, lineHeight = 26.sp),
                        color = colors.ink,
                        maxLines = 1,
                    )
                    DdText(host.t("/晚", "/night"), style = DdType.chip.copy(fontSize = 11.sp), color = colors.inkSecondary)
                }

                total != null -> DdText(
                    text = total,
                    style = DdType.heading.copy(fontSize = 20.sp, lineHeight = 24.sp),
                    color = colors.ink,
                    maxLines = 1,
                )

                else -> DdText(
                    text = host.t("价格待确认", "Price pending"),
                    style = DdType.chip.copy(fontSize = 11.sp),
                    color = colors.inkTertiary,
                    maxLines = 1,
                )
            }
            if (total != null && nightly != null) {
                DdText(
                    text = host.t("总价 $total", "total $total"),
                    style = DdType.chip.copy(fontSize = 10.sp, lineHeight = 14.sp),
                    color = colors.inkTertiary,
                    maxLines = 1,
                )
            }
            if (group.planCount > 1) {
                DdText(
                    text = host.t("${group.planCount} 个方案", "${group.planCount} plans"),
                    style = DdType.chip.copy(fontSize = 10.sp, lineHeight = 14.sp),
                    color = colors.inkTertiary,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun DetailFooter(host: PluginHost, details: HotelDetailDetails, nights: Int) {
    val colors = deepDeepColors()
    DdHairline()
    Row(
        Modifier.fillMaxWidth().padding(start = DdSpacing.Card, end = 10.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DdText(
            text = host.t(
                "总价按 $nights 晚计算，以 RollingGo 实时价格为准",
                "Total for $nights night(s); live prices are confirmed on RollingGo",
            ),
            modifier = Modifier.weight(1f),
            style = DdType.chip.copy(fontSize = 11.sp, lineHeight = 15.sp),
            color = colors.inkTertiary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        details.bookingUrl?.takeIf { it.isNotBlank() }?.let { url ->
            DdTextAction(text = host.t("去 RollingGo 预订", "Book on RollingGo"), onClick = { host.openHotelBooking(url) })
        }
    }
}

@Composable
internal fun HotelOrdersCard(host: PluginHost, state: ToolCardSlotState) {
    val result = state.result
    if (result == null) {
        HotelOrdersLoading(host)
        return
    }
    if (result.isError) {
        HotelErrorState(host, result.text ?: host.t("订单查询失败", "Order lookup failed"))
        return
    }
    val details = result.details?.let(::decodeOrders)
    if (details == null) {
        HotelFallbackCard(host, result.text)
        return
    }
    if (details.orders.isEmpty()) {
        HotelEmptyState(
            title = host.t("还没有酒店订单", "No hotel orders yet"),
            hint = host.t("预订成功后会显示在这里", "Your orders will appear here after booking"),
            icon = DdIcons.Book,
            note = details.message?.takeIf { it.isNotBlank() },
        )
        return
    }
    val colors = deepDeepColors()
    val appear = rememberHotelAppear()
    val translate: HotelTranslate = { zh, en -> host.t(zh, en) }
    val preview = details.orders.take(ORDER_PREVIEW_ROWS)
    HotelCard(Modifier.graphicsLayer { alpha = appear }) {
        Row(
            Modifier.fillMaxWidth().padding(start = DdSpacing.Card, end = DdSpacing.Card, top = 14.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DdText(
                text = host.t("我的酒店订单", "Hotel orders"),
                modifier = Modifier.weight(1f),
                style = DdType.label.copy(fontSize = 16.sp, lineHeight = 22.sp),
                color = colors.ink,
            )
            DdText(
                text = host.t("${details.orders.size} 笔", "${details.orders.size}"),
                style = DdType.chip.copy(fontSize = 11.sp),
                color = colors.inkTertiary,
            )
        }
        preview.forEachIndexed { index, order ->
            OrderTimelineRow(host, order, translate, drawLine = index < preview.lastIndex)
        }
        if (details.orders.size > ORDER_PREVIEW_ROWS) {
            DdText(
                text = host.t("还有 ${details.orders.size - ORDER_PREVIEW_ROWS} 笔订单", "${details.orders.size - ORDER_PREVIEW_ROWS} more orders"),
                modifier = Modifier.padding(start = 26.dp + DdSpacing.Md, end = DdSpacing.Card, top = 2.dp, bottom = 8.dp),
                style = DdType.chip.copy(fontSize = 11.sp),
                color = colors.inkTertiary,
            )
        }
        DdHairline()
        Row(
            Modifier.fillMaxWidth().padding(start = DdSpacing.Card, end = 10.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DdText(
                text = host.t("订单状态以 RollingGo 为准", "Order status is confirmed on RollingGo"),
                modifier = Modifier.weight(1f),
                style = DdType.chip.copy(fontSize = 11.sp),
                color = colors.inkTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            DdTextAction(text = host.t("打开酒店页面", "Open Hotels"), onClick = { host.openHotelPage() })
        }
    }
}

@Composable
private fun OrderTimelineRow(
    host: PluginHost,
    order: HotelOrderSummary,
    translate: HotelTranslate,
    drawLine: Boolean,
) {
    val colors = deepDeepColors()
    val tone = hotelOrderTone(order.status, order.paymentStatus)
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        OrderRail(tone = tone.color(colors), drawLine = drawLine)
        Column(Modifier.weight(1f).padding(end = DdSpacing.Card, top = 12.dp, bottom = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DdText(
                    text = order.hotelName,
                    modifier = Modifier.weight(1f),
                    style = DdType.label.copy(fontSize = 15.sp, lineHeight = 21.sp),
                    color = colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                order.totalPrice?.let {
                    Spacer(Modifier.width(DdSpacing.Sm))
                    DdText(
                        text = it,
                        style = DdType.label.copy(fontSize = 17.sp, lineHeight = 22.sp),
                        color = colors.ink,
                        maxLines = 1,
                    )
                }
            }
            val stay = listOfNotNull(
                order.checkIn?.let { checkIn ->
                    order.checkOut?.let { checkOut -> "${shortDate(checkIn)} → ${shortDate(checkOut)}" }
                },
                order.nights?.let { host.t("$it 晚", "$it night(s)") },
                order.roomName,
            ).joinToString(" · ")
            if (stay.isNotBlank()) {
                Spacer(Modifier.height(3.dp))
                DdText(
                    text = stay,
                    style = DdType.chip.copy(fontSize = 11.sp, lineHeight = 15.sp),
                    color = colors.inkSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                HotelTag(
                    text = hotelOrderStatusText(order.status, translate)
                        ?: host.t("状态未知", "Unknown"),
                    tone = tone,
                )
                order.orderNo?.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.width(DdSpacing.Sm))
                    DdText(
                        text = it,
                        style = DdType.numeric.copy(fontSize = 10.sp, letterSpacing = 0.2.sp),
                        color = colors.inkTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun OrderRail(tone: androidx.compose.ui.graphics.Color, drawLine: Boolean) {
    val colors = deepDeepColors()
    Canvas(Modifier.fillMaxHeight().width(26.dp)) {
        val centerX = size.width / 2f
        val dotY = 22.dp.toPx()
        if (drawLine) {
            drawLine(
                color = colors.hairline.copy(alpha = DdAlpha.Hairline),
                start = Offset(centerX, dotY + 6.dp.toPx()),
                end = Offset(centerX, size.height),
                strokeWidth = 1.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
        drawCircle(color = tone.copy(alpha = 0.18f), radius = 7.dp.toPx(), center = Offset(centerX, dotY))
        drawCircle(color = tone, radius = 4.dp.toPx(), center = Offset(centerX, dotY))
    }
}

@Composable
internal fun HotelBookCard(host: PluginHost, state: ToolCardSlotState) {
    val result = state.result
    if (result == null) {
        HotelBookLoading(host)
        return
    }
    if (result.isError) {
        HotelErrorState(host, result.text ?: host.t("订单没有创建成功", "The order wasn't created"))
        return
    }
    val details = result.details?.let(::decodeBooking)
    if (details == null) {
        HotelFallbackCard(host, result.text)
        return
    }
    val colors = deepDeepColors()
    val appear = rememberHotelAppear()
    val nights = hotelNights(details.checkIn, details.checkOut)
    HotelCard(Modifier.graphicsLayer { alpha = appear }) {
        Column(
            Modifier.fillMaxWidth().padding(start = DdSpacing.Card, end = DdSpacing.Card, top = 18.dp, bottom = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(colors.success), contentAlignment = Alignment.Center) {
                DdIcon(icon = DdIcons.Check, tint = colors.onAccent, size = 22.dp)
            }
            Spacer(Modifier.height(9.dp))
            DdText(
                text = host.t("预订成功，等待支付", "Booked, awaiting payment"),
                style = DdType.label.copy(fontSize = 18.sp, lineHeight = 24.sp),
                color = colors.ink,
                textAlign = TextAlign.Center,
            )
            val summary = listOfNotNull(
                details.hotelName.takeIf { it.isNotBlank() },
                details.roomName?.takeIf { it.isNotBlank() },
            ).joinToString(" · ")
            if (summary.isNotBlank()) {
                Spacer(Modifier.height(3.dp))
                DdText(
                    text = summary,
                    style = DdType.chip.copy(fontSize = 11.sp, lineHeight = 15.sp),
                    color = colors.inkSecondary,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                DateAnchor(label = host.t("入住", "Check-in"), value = details.checkIn)
                Box(
                    Modifier.weight(1f).padding(horizontal = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    DdHairline()
                    DdText(
                        text = host.t("$nights 晚", "$nights night(s)"),
                        modifier = Modifier.background(colors.surface).padding(horizontal = 7.dp),
                        style = DdType.chip.copy(fontSize = 11.sp, lineHeight = 15.sp),
                        color = colors.inkTertiary,
                    )
                }
                DateAnchor(label = host.t("离店", "Check-out"), value = details.checkOut)
            }
            Spacer(Modifier.height(14.dp))
            DashedDivider()
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    DdText(host.t("订单号", "Order no."), style = DdType.chip.copy(fontSize = 10.sp), color = colors.inkTertiary)
                    Spacer(Modifier.height(2.dp))
                    DdText(
                        text = details.orderNo,
                        style = DdType.numeric.copy(fontSize = 12.sp, letterSpacing = 0.3.sp),
                        color = colors.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(DdSpacing.Md))
                Column(horizontalAlignment = Alignment.End) {
                    DdText(host.t("总价", "Total"), style = DdType.chip.copy(fontSize = 10.sp), color = colors.inkTertiary)
                    DdText(
                        text = details.totalPrice ?: host.t("以 RollingGo 为准", "See RollingGo"),
                        style = DdType.heading.copy(fontSize = 24.sp, lineHeight = 28.sp),
                        color = colors.ink,
                        maxLines = 1,
                    )
                }
            }
            details.cancellation?.takeIf { it.isNotBlank() }?.let { cancellation ->
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(5.dp).clip(CircleShape).background(cancellationTone(cancellation).color(colors)))
                    Spacer(Modifier.width(6.dp))
                    DdText(
                        text = host.t("取消政策：$cancellation", "Cancellation: $cancellation"),
                        style = DdType.chip.copy(fontSize = 11.sp, lineHeight = 15.sp),
                        color = colors.inkSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            val paymentUrl = details.paymentUrl?.takeIf { it.isNotBlank() }
            if (paymentUrl != null) {
                DdPrimaryButton(
                    text = host.t("去支付", "Pay now"),
                    onClick = { host.openHotelBooking(paymentUrl) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                DdText(
                    text = host.t("请在 30 分钟内完成支付，超时订单可能关闭", "Pay within 30 minutes; unpaid orders may close"),
                    style = DdType.chip.copy(fontSize = 11.sp, lineHeight = 15.sp),
                    color = colors.inkTertiary,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
            } else {
                DdText(
                    text = host.t("请在 RollingGo 完成支付", "Complete payment on RollingGo"),
                    style = DdType.label,
                    color = colors.inkSecondary,
                    textAlign = TextAlign.Center,
                )
            }
            details.message?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(6.dp))
                DdText(
                    text = it,
                    style = DdType.chip.copy(fontSize = 11.sp),
                    color = colors.inkTertiary,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun DateAnchor(label: String, value: String?) {
    val colors = deepDeepColors()
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        DdText(
            text = value?.let(::shortDate) ?: "--",
            style = DdType.heading.copy(fontSize = 25.sp, lineHeight = 29.sp),
            color = colors.ink,
            maxLines = 1,
        )
        Spacer(Modifier.height(1.dp))
        DdText(label, style = DdType.chip.copy(fontSize = 11.sp), color = colors.inkTertiary)
    }
}

@Composable
private fun DashedDivider() {
    val colors = deepDeepColors()
    Canvas(Modifier.fillMaxWidth().height(1.dp)) {
        drawLine(
            color = colors.hairline.copy(alpha = DdAlpha.Hairline),
            start = Offset(0f, 0f),
            end = Offset(size.width, 0f),
            strokeWidth = 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 5.dp.toPx())),
        )
    }
}

@Composable
private fun HotelSearchLoading(host: PluginHost) {
    val colors = deepDeepColors()
    val appear = rememberHotelAppear()
    HotelCard(Modifier.graphicsLayer { alpha = appear }) {
        Box(
            Modifier.fillMaxWidth().height(150.dp).background(Brush.linearGradient(listOf(colors.accentSoft, colors.wash))),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                DdSpinner(color = colors.inkSecondary, size = 22.dp, strokeWidth = 2.dp)
                Spacer(Modifier.height(9.dp))
                DdText(
                    text = host.t("正在查找酒店…", "Looking for hotels…"),
                    style = DdType.chip.copy(fontSize = 11.sp),
                    color = colors.inkSecondary,
                )
            }
        }
        repeat(2) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(colors.wash))
                Spacer(Modifier.width(DdSpacing.Md))
                Column(Modifier.weight(1f)) {
                    SkeletonBar(width = 168.dp, height = 13.dp)
                    Spacer(Modifier.height(7.dp))
                    SkeletonBar(width = 116.dp, height = 10.dp)
                }
                SkeletonBar(width = 54.dp, height = 16.dp)
            }
        }
        DdHairline()
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            SkeletonBar(width = 148.dp, height = 11.dp)
            Spacer(Modifier.weight(1f))
            SkeletonBar(width = 82.dp, height = 11.dp)
        }
    }
}

@Composable
private fun HotelDetailLoading(host: PluginHost) {
    val colors = deepDeepColors()
    val appear = rememberHotelAppear()
    HotelCard(Modifier.graphicsLayer { alpha = appear }) {
        Column(Modifier.fillMaxWidth().padding(horizontal = DdSpacing.Card, vertical = 14.dp)) {
            SkeletonBar(width = 190.dp, height = 16.dp)
            Spacer(Modifier.height(7.dp))
            SkeletonBar(width = 150.dp, height = 11.dp)
        }
        DdHairline()
        repeat(3) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = DdSpacing.Card, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    SkeletonBar(width = 150.dp, height = 14.dp)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        SkeletonBar(width = 48.dp, height = 18.dp)
                        SkeletonBar(width = 62.dp, height = 18.dp)
                        SkeletonBar(width = 74.dp, height = 18.dp)
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    SkeletonBar(width = 72.dp, height = 20.dp)
                    Spacer(Modifier.height(5.dp))
                    SkeletonBar(width = 42.dp, height = 10.dp)
                }
            }
            DdHairline(Modifier.padding(start = DdSpacing.Card))
        }
        DdText(
            text = host.t("正在查询房型…", "Checking room rates…"),
            modifier = Modifier.padding(horizontal = DdSpacing.Card, vertical = 10.dp),
            style = DdType.chip.copy(fontSize = 11.sp),
            color = colors.inkTertiary,
        )
    }
}

@Composable
private fun HotelOrdersLoading(host: PluginHost) {
    val colors = deepDeepColors()
    val appear = rememberHotelAppear()
    HotelCard(Modifier.graphicsLayer { alpha = appear }) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = DdSpacing.Card, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SkeletonBar(width = 132.dp, height = 15.dp)
            Spacer(Modifier.weight(1f))
            SkeletonBar(width = 36.dp, height = 11.dp)
        }
        repeat(2) {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                Box(Modifier.width(26.dp).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
                    Box(Modifier.padding(top = 18.dp).size(8.dp).clip(CircleShape).background(colors.wash))
                }
                Column(Modifier.weight(1f).padding(end = DdSpacing.Card, top = 12.dp, bottom = 14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SkeletonBar(width = 168.dp, height = 14.dp)
                        Spacer(Modifier.weight(1f))
                        SkeletonBar(width = 54.dp, height = 16.dp)
                    }
                    Spacer(Modifier.height(8.dp))
                    SkeletonBar(width = 206.dp, height = 10.dp)
                    Spacer(Modifier.height(8.dp))
                    SkeletonBar(width = 94.dp, height = 16.dp)
                }
            }
        }
        DdText(
            text = host.t("正在查询订单…", "Checking orders…"),
            modifier = Modifier.padding(horizontal = DdSpacing.Card, vertical = 10.dp),
            style = DdType.chip.copy(fontSize = 11.sp),
            color = colors.inkTertiary,
        )
    }
}

@Composable
private fun HotelBookLoading(host: PluginHost) {
    val colors = deepDeepColors()
    val appear = rememberHotelAppear()
    HotelCard(Modifier.graphicsLayer { alpha = appear }) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 30.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            DdSpinner(color = colors.inkSecondary, size = 22.dp, strokeWidth = 2.dp)
            Spacer(Modifier.height(10.dp))
            DdText(
                text = host.t("正在确认预订…", "Confirming the booking…"),
                style = DdType.label,
                color = colors.inkSecondary,
            )
        }
    }
}

private fun hotelOrderTone(status: String?, paymentStatus: String?): HotelTone {
    if (paymentStatus?.uppercase() == "CREATED") return HotelTone.WARNING
    val value = status?.uppercase().orEmpty()
    return when {
        value == "1" || value == "PENDING" || value == "UNPAID" -> HotelTone.WARNING
        value == "3" || value == "FINISHED" -> HotelTone.SUCCESS
        value.contains("CANCEL") -> HotelTone.DANGER
        else -> HotelTone.NEUTRAL
    }
}

private fun cancellationTone(text: String): HotelTone = when {
    text.contains("不可") -> HotelTone.DANGER
    text.contains("免费") -> HotelTone.SUCCESS
    else -> HotelTone.WARNING
}

private fun shortDate(raw: String?): String? {
    val value = raw?.takeIf { it.isNotBlank() } ?: return null
    val parsed = runCatching { LocalDate.parse(value.take(10)) }.getOrNull() ?: return value.take(10)
    return parsed.format(ShortDateFormatter)
}

private val ShortDateFormatter = DateTimeFormatter.ofPattern("MM-dd")

private fun PluginHost.openHotelBooking(url: String) {
    runCatching { ui().openUrl(url) }
        .onFailure { runCatching { ui().toast(t("无法打开 RollingGo", "Couldn't open RollingGo")) } }
}

private fun PluginHost.openHotelPage() {
    runCatching { ui().openPage(HOTEL_PAGE_ID) }
        .onFailure { runCatching { ui().toast(t("无法打开酒店页面", "Couldn't open Hotels")) } }
}

private fun decodeSearch(details: JsonObject): HotelSearchDetails? = runCatching {
    HotelJson.decodeFromJsonElement(HotelSearchDetails.serializer(), details)
}.getOrNull()

private fun decodeDetail(details: JsonObject): HotelDetailDetails? = runCatching {
    HotelJson.decodeFromJsonElement(HotelDetailDetails.serializer(), details)
}.getOrNull()

private fun decodeOrders(details: JsonObject): HotelOrdersDetails? = runCatching {
    HotelJson.decodeFromJsonElement(HotelOrdersDetails.serializer(), details)
}.getOrNull()

private fun decodeBooking(details: JsonObject): HotelBookingDetails? = runCatching {
    HotelJson.decodeFromJsonElement(HotelBookingDetails.serializer(), details)
}.getOrNull()
