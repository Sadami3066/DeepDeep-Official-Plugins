package io.github.whywood.deepdeep.plugin.hotel

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.whywood.deepdeep.design.DdAlpha
import io.github.whywood.deepdeep.design.DdExpandToggle
import io.github.whywood.deepdeep.design.DdHairline
import io.github.whywood.deepdeep.design.DdIcon
import io.github.whywood.deepdeep.design.DdIcons
import io.github.whywood.deepdeep.design.DdPressable
import io.github.whywood.deepdeep.design.DdPrimaryButton
import io.github.whywood.deepdeep.design.DdRemoteImage
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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement

private val HotelCardShape = RoundedCornerShape(20.dp)
private val HotelTagShape = RoundedCornerShape(7.dp)
private const val SEARCH_PREVIEW_ROWS = 3
private const val SEARCH_TAG_PREVIEW = 3
private const val PLAN_PREVIEW_ROWS = 3
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
private fun HotelRemoteImage(
    host: PluginHost,
    url: String,
    description: String,
    modifier: Modifier,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(12.dp),
) {
    DdRemoteImage(
        url = url,
        load = { candidate -> runCatching { host.images().load(candidate)?.bitmap }.getOrNull() },
        contentDescription = description,
        modifier = modifier,
        contentScale = ContentScale.Crop,
        shape = shape,
    )
}

private fun hotelResultMessage(details: JsonObject?, text: String?, fallback: String): String {
    (details?.get(HOTEL_MESSAGE_KEY) as? JsonPrimitive)?.contentOrNull?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let { return it }
    if (details == null) {
        text?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    }
    return fallback
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
        HotelErrorState(
            host,
            hotelResultMessage(result.details, result.text, host.t("酒店搜索失败", "Hotel search failed")),
        )
        return
    }
    val details = result.details?.let(::decodeSearch)
    if (details == null) {
        HotelFallbackCard(host, host.t("没有可展示的酒店结果。", "No hotel results to show."))
        return
    }
    if (details.hotels.isEmpty()) {
        val context = listOfNotNull(
            details.place.takeIf { it.isNotBlank() },
            details.checkIn,
            details.maxPrice?.let { hotelMoneyText(it, null) }?.let { host.t("预算 $it", "budget $it") },
            details.filter.joinToString(" · ").takeIf { it.isNotBlank() },
        ).joinToString(" · ")
        HotelEmptyState(
            title = host.t("没有找到符合条件的酒店", "No hotels matched"),
            hint = host.t("可以放宽星级、距离或价格条件再试", "Try relaxing stars, distance or price"),
            icon = DdIcons.Search,
            note = context.ifBlank { null },
        )
        return
    }
    val appear = rememberHotelAppear()
    val rest = details.hotels.drop(1)
    var expanded by remember(details) { mutableStateOf(false) }
    val visible = if (expanded) rest else rest.take(SEARCH_PREVIEW_ROWS)
    val moreRows = rest.size - SEARCH_PREVIEW_ROWS
    val rowsHaveMore = details.hotels.any { hotel ->
        hotelDistinctLabels(hotel.tags).size > SEARCH_TAG_PREVIEW ||
            hotelDistinctLabels(hotel.amenities).isNotEmpty() ||
            (hotel.address?.length ?: 0) > 24
    }
    HotelCard(Modifier.graphicsLayer { alpha = appear }) {
        val hotel = details.hotels.first()
        SearchHero(host, hotel, details, expanded)
        if (details.maxPrice != null) {
            DdHairline(Modifier.padding(horizontal = 16.dp))
            SearchBudgetStrip(host, details)
        }
        visible.forEach { item ->
            DdHairline(Modifier.padding(start = 74.dp, end = 16.dp))
            CompactHotelRow(host, item, expanded, details.maxPrice)
        }
        if (moreRows > 0 || rowsHaveMore) {
            DdHairline(Modifier.padding(start = 74.dp, end = 16.dp))
            DdExpandToggle(
                expanded = expanded,
                collapsedLabel = if (moreRows > 0) {
                    host.t("展开其余 $moreRows 家酒店", "Show $moreRows more hotels")
                } else {
                    host.t("展开全部酒店信息", "Show all hotel details")
                },
                expandedLabel = if (moreRows > 0) {
                    host.t("收起酒店列表", "Show fewer hotels")
                } else {
                    host.t("收起酒店信息", "Show fewer details")
                },
                onToggle = { expanded = !expanded },
                modifier = Modifier.padding(start = DdSpacing.Card),
            )
        }
        SearchFooter(host, details)
    }
}

@Composable
private fun HotelLabelChips(host: PluginHost, labels: List<String>, hiddenCount: Int = 0) {
    val colors = deepDeepColors()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        labels.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                row.forEach { label ->
                    HotelTag(label, modifier = Modifier.weight(1f, fill = false))
                }
            }
        }
        if (hiddenCount > 0) {
            DdText(
                text = host.t("还有 $hiddenCount 个标签", "$hiddenCount more tags"),
                style = DdType.chip.copy(fontSize = 10.sp, lineHeight = 14.sp),
                color = colors.inkTertiary,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun SearchBudgetStrip(host: PluginHost, details: HotelSearchDetails) {
    val colors = deepDeepColors()
    val budget = details.maxPrice ?: return
    val budgetText = hotelMoneyText(budget, null).orEmpty()
    val unpriced = details.hotels.count { it.lowestPrice == null }
    val tone = when {
        details.overBudgetCount > 0 -> HotelTone.WARNING
        unpriced > 0 -> HotelTone.NEUTRAL
        else -> HotelTone.SUCCESS
    }
    val summary = when {
        details.overBudgetCount > 0 -> {
            val lowest = details.lowestOverBudgetPrice
            if (lowest != null) {
                host.t("${details.overBudgetCount} 家超预算，最低 $lowest", "${details.overBudgetCount} over budget, lowest $lowest")
            } else {
                host.t("${details.overBudgetCount} 家超预算", "${details.overBudgetCount} over budget")
            }
        }

        unpriced > 0 -> host.t("$unpriced 家价格待确认", "$unpriced price(s) pending")
        else -> host.t("已按预算筛选，无超预算结果", "Filtered by budget; nothing over")
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HotelTag(host.t("预算 $budgetText", "Budget $budgetText"), tone)
        Spacer(Modifier.width(DdSpacing.Sm))
        DdText(
            text = summary,
            style = DdType.chip.copy(fontSize = 11.sp, lineHeight = 15.sp),
            color = tone.color(colors),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SearchHero(host: PluginHost, hotel: HotelSummary, details: HotelSearchDetails, expanded: Boolean) {
    val colors = deepDeepColors()
    val star = hotel.starRating?.takeIf { it.isNotBlank() }?.let { "$it★" }
    val distance = hotelDistanceText(hotel.distanceMeters) ?: hotel.distance
    val meta = listOfNotNull(star, distance).joinToString(" · ")
    val overBudget = hotelIsOverBudget(hotel, details.maxPrice)
    val tags = hotelVisibleLabels(hotel.tags, expanded)
    val hiddenTags = hotelDistinctLabels(hotel.tags).size - tags.size
    val amenities = hotelVisibleLabels(hotel.amenities, expanded, Int.MAX_VALUE)
    val body: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth()) {
            hotel.imageUrl?.takeIf { it.isNotBlank() }?.let { url ->
                Box(Modifier.fillMaxWidth().height(150.dp)) {
                    HotelRemoteImage(host, url, hotel.name, Modifier.fillMaxSize(), RectangleShape)
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
                }
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        DdText(
                            text = hotel.name,
                            style = DdType.heading.copy(fontSize = 18.sp, lineHeight = 24.sp),
                            color = colors.ink,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        hotel.nameEn?.takeIf { it.isNotBlank() && !hotel.name.contains(it) }?.let { nameEn ->
                            Spacer(Modifier.height(1.dp))
                            DdText(
                                text = nameEn,
                                style = DdType.chip.copy(fontSize = 10.sp, lineHeight = 14.sp),
                                color = colors.inkTertiary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (meta.isNotBlank()) {
                            Spacer(Modifier.height(3.dp))
                            DdText(
                                text = meta,
                                style = DdType.chip.copy(fontSize = 11.sp, lineHeight = 15.sp),
                                color = colors.inkSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        hotel.address?.takeIf { it.isNotBlank() }?.let { address ->
                            Spacer(Modifier.height(2.dp))
                            DdText(
                                text = address,
                                style = DdType.chip.copy(fontSize = 11.sp, lineHeight = 15.sp),
                                color = colors.inkSecondary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Spacer(Modifier.width(DdSpacing.Md))
                    Column(horizontalAlignment = Alignment.End) {
                        DdText(
                            text = hotel.priceText ?: host.t("待确认", "TBD"),
                            style = DdType.heading.copy(fontSize = 25.sp, lineHeight = 29.sp),
                            color = when {
                                hotel.priceText == null -> colors.inkSecondary
                                overBudget -> colors.warning
                                else -> colors.accent
                            },
                            maxLines = 1,
                        )
                        if (overBudget) {
                            Spacer(Modifier.height(3.dp))
                            HotelTag(host.t("超预算", "Over budget"), HotelTone.WARNING)
                        } else if (hotel.priceText != null) {
                            DdText(host.t("起/晚", "from/night"), style = DdType.chip.copy(fontSize = 10.sp), color = colors.inkTertiary)
                        }
                    }
                }
                if (expanded && tags.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    HotelLabelChips(host, tags, hiddenTags)
                }
                if (expanded && amenities.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    DdText(
                        text = host.t("设施：${amenities.joinToString(" · ")}", "Facilities: ${amenities.joinToString(" · ")}"),
                        style = DdType.chip.copy(fontSize = 10.sp, lineHeight = 14.sp),
                        color = colors.inkTertiary,
                    )
                }
            }
        }
    }
    val url = hotel.bookingUrl
    if (url != null) {
        DdPressable(onClick = { host.openHotelUrl(url) }, shape = RectangleShape, contentDescription = hotel.name) {
            body()
        }
    } else {
        body()
    }
}

@Composable
private fun CompactHotelRow(
    host: PluginHost,
    hotel: HotelSummary,
    expanded: Boolean,
    maxPrice: Double?,
) {
    val colors = deepDeepColors()
    val star = hotel.starRating?.takeIf { it.isNotBlank() }?.let { "$it★" }
    val meta = listOfNotNull(
        star,
        hotelDistanceText(hotel.distanceMeters) ?: hotel.distance,
    ).joinToString(" · ")
    val overBudget = hotelIsOverBudget(hotel, maxPrice)
    val tags = hotelVisibleLabels(hotel.tags, expanded)
    val amenities = hotelVisibleLabels(hotel.amenities, expanded, Int.MAX_VALUE)
    val body: @Composable () -> Unit = {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            hotel.imageUrl?.takeIf { it.isNotBlank() }?.let { url ->
                HotelRemoteImage(host, url, hotel.name, Modifier.size(44.dp), RoundedCornerShape(14.dp))
            } ?: Box(
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
                    maxLines = if (expanded) 2 else 1,
                    overflow = TextOverflow.Ellipsis,
                )
                hotel.nameEn?.takeIf { it.isNotBlank() && !hotel.name.contains(it) }?.let {
                    Spacer(Modifier.height(1.dp))
                    DdText(
                        text = it,
                        style = DdType.chip.copy(fontSize = 10.sp, lineHeight = 14.sp),
                        color = colors.inkTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
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
                hotel.address?.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.height(2.dp))
                    DdText(
                        text = it,
                        style = DdType.chip.copy(fontSize = 11.sp, lineHeight = 15.sp),
                        color = colors.inkSecondary,
                        maxLines = if (expanded) Int.MAX_VALUE else 1,
                        overflow = if (expanded) TextOverflow.Clip else TextOverflow.Ellipsis,
                    )
                }
                if (expanded && tags.isNotEmpty()) {
                    Spacer(Modifier.height(3.dp))
                    DdText(
                        text = tags.joinToString(" · "),
                        style = DdType.chip.copy(fontSize = 10.sp, lineHeight = 14.sp),
                        color = colors.inkTertiary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (expanded && amenities.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    DdText(
                        text = amenities.joinToString(" · "),
                        style = DdType.chip.copy(fontSize = 10.sp, lineHeight = 14.sp),
                        color = colors.inkTertiary,
                    )
                }
            }
            Spacer(Modifier.width(DdSpacing.Sm))
            Column(horizontalAlignment = Alignment.End) {
                DdText(
                    text = hotel.priceText ?: host.t("待确认", "TBD"),
                    style = DdType.label.copy(fontSize = 17.sp, lineHeight = 22.sp),
                    color = when {
                        hotel.priceText == null -> colors.inkSecondary
                        overBudget -> colors.warning
                        else -> colors.ink
                    },
                    maxLines = 1,
                )
                if (overBudget) {
                    Spacer(Modifier.height(2.dp))
                    HotelTag(host.t("超预算", "Over budget"), HotelTone.WARNING)
                } else if (hotel.lowestPrice != null) {
                    DdText(host.t("起/晚", "from/night"), style = DdType.chip.copy(fontSize = 10.sp), color = colors.inkTertiary)
                }
            }
        }
    }
    val url = hotel.bookingUrl
    if (url != null) {
        DdPressable(onClick = { host.openHotelUrl(url) }, shape = RectangleShape, contentDescription = hotel.name) {
            body()
        }
    } else {
        body()
    }
}

@Composable
private fun SearchFooter(host: PluginHost, details: HotelSearchDetails) {
    val colors = deepDeepColors()
    DdHairline()
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            val sortLabel = when (details.sortedBy) {
                "price" -> host.t("按起价排序", "sorted by price")
                "distance" -> host.t("按距离排序", "sorted by distance")
                else -> null
            }
            val context = listOfNotNull(
                details.place.takeIf { it.isNotBlank() },
                details.checkIn?.takeIf { it.isNotBlank() }?.let {
                    host.t("$it 入住 ${details.nights} 晚", "$it · ${details.nights} night(s)")
                },
                sortLabel,
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
            details.filter.takeIf { it.isNotEmpty() }?.let {
                DdText(
                    text = host.t("筛选：${it.joinToString(" · ")}", "Filters: ${it.joinToString(" · ")}"),
                    style = DdType.chip.copy(fontSize = 10.sp, lineHeight = 14.sp),
                    color = colors.inkTertiary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (details.noMatchingRates) {
                DdText(
                    text = host.t("当前筛选没有完全匹配的报价", "No rates fully matched the filters"),
                    style = DdType.chip.copy(fontSize = 10.sp, lineHeight = 14.sp),
                    color = colors.warning,
                    maxLines = 1,
                )
            }
            if (!details.nextPageToken.isNullOrBlank()) {
                DdText(
                    text = host.t("还有下一页结果", "Another page is available"),
                    style = DdType.chip.copy(fontSize = 10.sp, lineHeight = 14.sp),
                    color = colors.inkTertiary,
                    maxLines = 1,
                )
            }
        }
        val url = details.hotels.firstNotNullOfOrNull { it.bookingUrl }
        if (url != null) {
            DdTextAction(text = host.t("打开 RollingGo", "Open RollingGo"), onClick = { host.openHotelUrl(url) })
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
        HotelErrorState(
            host,
            hotelResultMessage(result.details, result.text, host.t("房型查询失败", "Room lookup failed")),
        )
        return
    }
    val details = result.details?.let(::decodeDetail)
    if (details == null) {
        HotelFallbackCard(host, host.t("没有可展示的房型。", "No rooms to show."))
        return
    }
    val colors = deepDeepColors()
    val groups = details.roomGroups.ifEmpty { groupHotelRooms(details.rooms) }
    if (groups.isEmpty()) {
        val note = details.errorMessage?.takeIf { it.isNotBlank() }
            ?: details.message?.takeIf { it.isNotBlank() }
            ?: details.filter.joinToString(" · ").takeIf { it.isNotBlank() }
            ?: details.name
        HotelEmptyState(
            title = host.t("暂时没有可订房型", "No rooms available right now"),
            hint = if (details.noMatchingRates) {
                host.t("当前筛选没有匹配的房价，可以放宽条件再试", "No rates matched the filters; try relaxing them")
            } else {
                host.t("换一家酒店或日期再试", "Try another hotel or date")
            },
            icon = DdIcons.Info,
            note = note,
        )
        return
    }
    val appear = rememberHotelAppear()
    val nights = hotelNights(details.checkIn, details.checkOut)
    var expandedGroups by remember(details) { mutableStateOf(emptySet<String>()) }
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
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                details.nameEn?.takeIf { it.isNotBlank() && !details.name.contains(it) }?.let {
                    Spacer(Modifier.height(1.dp))
                    DdText(
                        text = it,
                        style = DdType.chip.copy(fontSize = 10.sp, lineHeight = 14.sp),
                        color = colors.inkTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val sub = listOfNotNull(
                    details.starRating?.takeIf { it.isNotBlank() }?.let { "$it★" },
                    details.checkIn?.let { checkIn ->
                        details.checkOut?.let { checkOut -> "${shortDate(checkIn)} → ${shortDate(checkOut)}" }
                    },
                    host.t("$nights 晚", "$nights night(s)"),
                    host.t("${details.roomCount} 间房", "${details.roomCount} room(s)"),
                    host.t("每间 ${details.adultCount} 位成人", "${details.adultCount} adult(s) each"),
                ).joinToString(" · ")
                if (sub.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    DdText(
                        text = sub,
                        style = DdType.chip.copy(fontSize = 11.sp, lineHeight = 15.sp),
                        color = colors.inkSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                details.filter.takeIf { it.isNotEmpty() }?.let {
                    DdText(
                        text = host.t("筛选：${it.joinToString(" · ")}", "Filters: ${it.joinToString(" · ")}"),
                        style = DdType.chip.copy(fontSize = 10.sp, lineHeight = 14.sp),
                        color = colors.inkTertiary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (details.noMatchingRates) {
                    DdText(
                        text = host.t("当前筛选没有完全匹配的房价", "No rates fully matched the filters"),
                        style = DdType.chip.copy(fontSize = 10.sp, lineHeight = 14.sp),
                        color = colors.warning,
                        maxLines = 2,
                    )
                }
                Spacer(Modifier.height(2.dp))
                DdText(
                    text = host.t("按房型分组，组内按总价排序", "Grouped by room type; sorted by total within each group"),
                    style = DdType.chip.copy(fontSize = 10.sp, lineHeight = 14.sp),
                    color = colors.inkTertiary,
                    maxLines = 2,
                )
            }
            Spacer(Modifier.width(DdSpacing.Sm))
            HotelTag(text = host.t("$nights 晚", "$nights night(s)"), tone = HotelTone.ACCENT)
        }
        DdHairline()
        groups.forEachIndexed { index, group ->
            RoomGroupBlock(
                host = host,
                group = group,
                nights = nights,
                expanded = expandedGroups.contains(group.name),
                onToggle = {
                    expandedGroups = if (expandedGroups.contains(group.name)) {
                        expandedGroups - group.name
                    } else {
                        expandedGroups + group.name
                    }
                },
            )
            if (index < groups.lastIndex) DdHairline(Modifier.padding(start = DdSpacing.Card))
        }
        DetailFooter(host, details, nights)
    }
}

@Composable
private fun RoomGroupBlock(
    host: PluginHost,
    group: HotelRoomGroup,
    nights: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val colors = deepDeepColors()
    val cheapest = group.plans.firstOrNull()
    val nightly = cheapest?.avgPriceText
    val total = cheapest?.priceText
    val cancellation = group.cheapestCancellation
    val cancellationTone = cancellationToneOf(cancellation)
    val roomFacts = listOfNotNull(
        group.bed?.takeIf { it.isNotBlank() },
        group.roomSize?.takeIf { it.isNotBlank() }?.let { host.t("面积 $it㎡", "size $it sqm") },
        group.hasWindow?.let { if (it) host.t("有窗", "window") else host.t("无窗", "no window") },
        group.maxOccupancy?.let { host.t("可住 $it 人", "up to $it guests") },
    ).joinToString(" · ")
    val imageUrl = group.images.firstOrNull()?.takeIf { it.isNotBlank() }
    val variants = groupHotelPlanVariants(group.plans)
    val visiblePlans = if (expanded) variants else variants.take(PLAN_PREVIEW_ROWS)
    val morePlans = variants.size - PLAN_PREVIEW_ROWS
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = DdSpacing.Card, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            imageUrl?.let { url ->
                HotelRemoteImage(host, url, group.name, Modifier.size(60.dp, 52.dp), RoundedCornerShape(12.dp))
                Spacer(Modifier.width(DdSpacing.Md))
            }
            Column(Modifier.weight(1f)) {
                DdText(
                    text = group.name,
                    style = DdType.label.copy(fontSize = 15.sp, lineHeight = 21.sp),
                    color = colors.ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (roomFacts.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    DdText(
                        text = roomFacts,
                        style = DdType.chip.copy(fontSize = 11.sp, lineHeight = 15.sp),
                        color = colors.inkSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(5.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(5.dp).clip(CircleShape).background(cancellationTone.color(colors)))
                    Spacer(Modifier.width(5.dp))
                    DdText(
                        text = cancellation ?: host.t("取消政策未提供", "Cancellation policy not provided"),
                        style = DdType.chip.copy(fontSize = 11.sp, lineHeight = 15.sp),
                        color = cancellationTone.color(colors),
                        maxLines = 2,
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
                if (cheapest?.totalDerived == true) {
                    DdText(
                        text = host.t("按 $nights 晚折算", "derived for $nights night(s)"),
                        style = DdType.chip.copy(fontSize = 10.sp, lineHeight = 14.sp),
                        color = colors.inkTertiary,
                        maxLines = 1,
                    )
                }
                DdText(
                    text = if (variants.size < group.planCount) {
                        host.t("${group.planCount} 个方案 · ${variants.size} 类", "${group.planCount} plans · ${variants.size} types")
                    } else {
                        host.t("${group.planCount} 个方案", "${group.planCount} plans")
                    },
                    style = DdType.chip.copy(fontSize = 10.sp, lineHeight = 14.sp),
                    color = colors.inkTertiary,
                    maxLines = 1,
                )
            }
        }
        visiblePlans.forEach { variant ->
            PlanDetailRow(host, variant, nights)
        }
        if (morePlans > 0) {
            DdExpandToggle(
                expanded = expanded,
                collapsedLabel = host.t("展开其余 $morePlans 类方案", "Show $morePlans more types"),
                expandedLabel = host.t("收起方案", "Hide plans"),
                onToggle = onToggle,
                modifier = Modifier.padding(start = DdSpacing.Card),
            )
        }
    }
}

private fun cancellationToneOf(cancellation: String?): HotelTone = when {
    cancellation == null -> HotelTone.NEUTRAL
    cancellation.contains("不可") || cancellation.contains("Non-refundable", ignoreCase = true) -> HotelTone.DANGER
    cancellation.contains("免费") || cancellation.contains("Free", ignoreCase = true) -> HotelTone.SUCCESS
    else -> HotelTone.WARNING
}

@Composable
private fun PlanDetailRow(host: PluginHost, variant: HotelPlanVariant, nights: Int) {
    val colors = deepDeepColors()
    val plan = variant.plan
    val meal = plan.meal?.takeIf { it.isNotBlank() }
    val mealText = when {
        meal != null && plan.mealAmount != null -> host.t("$meal（${plan.mealAmount} 份）", "$meal (${plan.mealAmount} serving(s))")
        meal != null -> meal
        plan.mealAmount != null -> host.t("早餐 ${plan.mealAmount} 份", "${plan.mealAmount} breakfast(s)")
        else -> null
    }
    val policies = variant.policies
    val facts = listOfNotNull(
        plan.avgPriceText?.let { host.t("每晚 $it", "$it/night") },
        plan.priceText?.let {
            if (plan.totalDerived) {
                host.t("总价 $it（按 $nights 晚折算）", "total $it (derived for $nights night(s))")
            } else {
                host.t("总价 $it", "total $it")
            }
        },
        if (plan.priceText == null && plan.avgPriceText == null) host.t("价格待确认", "price pending") else null,
    ).joinToString(" · ")
    val extraFacts = listOfNotNull(
        plan.floor?.takeIf { it.isNotBlank() }?.let { host.t("$it 层", "floors $it") },
        plan.inventoryCount?.let { host.t("剩余 $it 间", "$it room(s) left") },
        when {
            variant.mixedOnRequest -> host.t("部分方案需酒店确认", "some need confirmation")
            variant.hasOnRequest -> host.t("需酒店确认", "On request")
            else -> null
        },
    ).joinToString(" · ")
    val displayName = hotelDisplayRatePlanName(plan.ratePlanName) ?: host.t("价格方案", "Rate plan")
    Column(
        Modifier.fillMaxWidth().padding(start = DdSpacing.Card + 12.dp, end = DdSpacing.Card, bottom = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            DdText(
                text = displayName,
                modifier = Modifier.weight(1f),
                style = DdType.label.copy(fontSize = 13.sp, lineHeight = 18.sp),
                color = colors.ink,
            )
            Spacer(Modifier.width(DdSpacing.Sm))
            DdText(
                text = plan.priceText ?: plan.avgPriceText ?: host.t("价格待确认", "price pending"),
                style = DdType.label.copy(fontSize = 14.sp, lineHeight = 19.sp),
                color = if (plan.priceText != null || plan.avgPriceText != null) colors.ink else colors.inkTertiary,
                maxLines = 1,
            )
        }
        Spacer(Modifier.height(3.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            plan.bed?.takeIf { it.isNotBlank() }?.let { HotelTag(it, modifier = Modifier.weight(1f, fill = false)) }
            mealText?.let { HotelTag(it, modifier = Modifier.weight(1f, fill = false)) }
            hotelRoomSuffixes(plan.name).take(2).forEach { suffix ->
                HotelTag(suffix, modifier = Modifier.weight(1f, fill = false))
            }
            if (variant.count > 1) {
                HotelTag(
                    host.tf("%d 个方案", "%d plans", variant.count),
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
        }
        if (facts.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            DdText(
                text = facts,
                style = DdType.chip.copy(fontSize = 11.sp, lineHeight = 15.sp),
                color = colors.inkSecondary,
            )
        }
        if (extraFacts.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            DdText(
                text = extraFacts,
                style = DdType.chip.copy(fontSize = 10.sp, lineHeight = 14.sp),
                color = colors.inkTertiary,
            )
        }
        if (policies.isNotEmpty()) {
            Spacer(Modifier.height(3.dp))
            policies.forEach { policy ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(4.dp).clip(CircleShape).background(cancellationTone(policy).color(colors)))
                    Spacer(Modifier.width(5.dp))
                    DdText(
                        text = policy,
                        style = DdType.chip.copy(fontSize = 10.sp, lineHeight = 14.sp),
                        color = cancellationTone(policy).color(colors),
                    )
                }
            }
        }
        if (plan.images.isNotEmpty()) {
            Spacer(Modifier.height(2.dp))
            DdTextAction(
                text = host.t("查看房型图片", "View room photos"),
                onClick = { host.openHotelUrl(plan.images.first()) },
            )
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
            DdTextAction(text = host.t("打开 RollingGo", "Open RollingGo"), onClick = { host.openHotelUrl(url) })
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
        HotelErrorState(
            host,
            hotelResultMessage(result.details, result.text, host.t("订单查询失败", "Order lookup failed")),
        )
        return
    }
    val details = result.details?.let(::decodeOrders)
    if (details == null) {
        HotelFallbackCard(host, host.t("没有可展示的订单。", "No orders to show."))
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
    var expanded by remember(details) { mutableStateOf(false) }
    val visible = if (expanded) details.orders else details.orders.take(ORDER_PREVIEW_ROWS)
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
        visible.forEachIndexed { index, order ->
            OrderTimelineRow(host, order, translate, drawLine = index < visible.lastIndex)
        }
        if (details.orders.size > ORDER_PREVIEW_ROWS) {
            DdExpandToggle(
                expanded = expanded,
                collapsedLabel = host.t("展开其余 ${details.orders.size - ORDER_PREVIEW_ROWS} 笔订单", "Show ${details.orders.size - ORDER_PREVIEW_ROWS} more orders"),
                expandedLabel = host.t("收起订单列表", "Show fewer orders"),
                onToggle = { expanded = !expanded },
                modifier = Modifier.padding(start = 26.dp + DdSpacing.Md),
            )
        }
        DdHairline()
        Row(
            Modifier.fillMaxWidth().padding(start = DdSpacing.Card, end = 10.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DdText(
                text = host.t(
                    "列表不含取消原因；取消原因、确认号、酒店电话用订单详情查询",
                    "The list has no cancellation reason; use order details for the reason, confirmation number and hotel phone",
                ),
                modifier = Modifier.weight(1f),
                style = DdType.chip.copy(fontSize = 11.sp),
                color = colors.inkTertiary,
                maxLines = 2,
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
                    maxLines = 2,
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
                order.roomCount?.let { host.t("$it 间", "$it room(s)") },
                order.roomName,
            ).joinToString(" · ")
            if (stay.isNotBlank()) {
                Spacer(Modifier.height(3.dp))
                DdText(
                    text = stay,
                    style = DdType.chip.copy(fontSize = 11.sp, lineHeight = 15.sp),
                    color = colors.inkSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val extras = listOfNotNull(
                order.orderDate?.takeIf { it.isNotBlank() }?.let { host.t("下单 $it", "ordered $it") },
                order.meal?.takeIf { it.isNotBlank() },
                order.paymentStatus?.takeIf { it.isNotBlank() }?.let {
                    hotelPaymentStatusText(it, translate)?.let { status -> host.t("支付状态：$status", "Payment: $status") }
                },
            )
            if (extras.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                DdText(
                    text = extras.joinToString(" · "),
                    style = DdType.chip.copy(fontSize = 10.sp, lineHeight = 14.sp),
                    color = colors.inkTertiary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            order.cancelPolicy?.takeIf { it.isNotBlank() }?.let { policy ->
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(4.dp).clip(CircleShape).background(cancellationTone(policy).color(colors)))
                    Spacer(Modifier.width(5.dp))
                    DdText(
                        text = host.t("取消政策：$policy", "Cancellation: $policy"),
                        style = DdType.chip.copy(fontSize = 10.sp, lineHeight = 14.sp),
                        color = colors.inkSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
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
            order.hotelAddress?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(3.dp))
                DdText(
                    text = it,
                    style = DdType.chip.copy(fontSize = 10.sp, lineHeight = 14.sp),
                    color = colors.inkTertiary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val contact = listOfNotNull(
                order.contactName?.takeIf { it.isNotBlank() },
                order.contactEmail?.takeIf { it.isNotBlank() },
            )
            if (contact.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                DdText(
                    text = host.t("联系人：${contact.joinToString(" ")}", "Contact: ${contact.joinToString(" ")}"),
                    style = DdType.chip.copy(fontSize = 10.sp, lineHeight = 14.sp),
                    color = colors.inkTertiary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val actions = listOfNotNull(
                order.paymentUrl?.takeIf { it.isNotBlank() }?.let { url ->
                    host.t("去支付", "Pay now") to url
                },
                order.orderDetailUrl?.takeIf { it.isNotBlank() }?.let { url ->
                    host.t("订单详情", "Order details") to url
                },
            )
            if (actions.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(DdSpacing.Md)) {
                    actions.forEach { (label, url) ->
                        DdTextAction(text = label, onClick = { host.openHotelUrl(url) })
                    }
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
internal fun HotelOrderDetailCard(host: PluginHost, state: ToolCardSlotState) {
    val result = state.result
    if (result == null) {
        HotelOrderDetailLoading(host)
        return
    }
    if (result.isError) {
        HotelErrorState(
            host,
            hotelResultMessage(result.details, result.text, host.t("订单详情查询失败", "Order detail lookup failed")),
        )
        return
    }
    val details = result.details?.let(::decodeOrderDetail)
    if (details == null) {
        HotelFallbackCard(host, host.t("没有可展示的订单详情。", "No order details to show."))
        return
    }
    val colors = deepDeepColors()
    val appear = rememberHotelAppear()
    val nights = details.nights ?: hotelNights(details.checkIn, details.checkOut)
    var expanded by remember(details) { mutableStateOf(false) }
    val translate: HotelTranslate = { zh, en -> host.t(zh, en) }
    val tone = hotelOrderTone(details.orderStatus, null)
    HotelCard(Modifier.graphicsLayer { alpha = appear }) {
        Row(
            Modifier.fillMaxWidth().padding(start = DdSpacing.Card, end = DdSpacing.Card, top = 14.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DdText(
                text = host.t("订单详情", "Order details"),
                modifier = Modifier.weight(1f),
                style = DdType.label.copy(fontSize = 16.sp, lineHeight = 22.sp),
                color = colors.ink,
            )
            details.orderStatus?.takeIf { it.isNotBlank() }?.let {
                HotelTag(text = hotelOrderStatusText(it, translate) ?: it, tone = tone)
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = DdSpacing.Card)) {
            DdText(
                text = details.hotelName ?: host.t("酒店订单", "Hotel order"),
                style = DdType.heading.copy(fontSize = 18.sp, lineHeight = 24.sp),
                color = colors.ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            details.hotelAddress?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(2.dp))
                DdText(
                    text = it,
                    style = DdType.chip.copy(fontSize = 11.sp, lineHeight = 15.sp),
                    color = colors.inkSecondary,
                )
            }
            Spacer(Modifier.height(12.dp))
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
            Spacer(Modifier.height(10.dp))
            OrderDetailLine(host.t("订单号", "Order no."), details.orderNo, numeric = true)
            val room = listOfNotNull(
                details.roomName?.takeIf { it.isNotBlank() },
                details.bedType?.takeIf { it.isNotBlank() },
                details.meal?.takeIf { it.isNotBlank() },
                details.roomCount?.let { host.t("$it 间", "$it room(s)") },
            ).joinToString(" · ")
            OrderDetailLine(host.t("房型", "Room"), room)
            OrderDetailLine(host.t("入住人", "Guests"), details.guestNames.joinToString("、").takeIf { it.isNotBlank() })
            val contact = listOfNotNull(
                details.contactName?.takeIf { it.isNotBlank() },
                details.contactPhone?.takeIf { it.isNotBlank() },
                details.contactEmail?.takeIf { it.isNotBlank() },
            ).joinToString(" · ")
            OrderDetailLine(host.t("联系人", "Contact"), contact)
            OrderDetailLine(host.t("酒店确认号", "Hotel confirmation"), details.hotelConfirmationNo, numeric = true)
            OrderDetailLine(host.t("总价", "Total"), details.totalPrice)
            OrderDetailLine(host.t("支付截止", "Pay by"), details.paymentDeadline)
            OrderDetailLine(host.t("取消政策", "Cancellation"), details.cancelPolicy)
            if (expanded) {
                OrderDetailLine(host.t("下单时间", "Ordered"), details.orderDate)
                OrderDetailLine(
                    host.t("入离时间", "Check-in / out"),
                    listOfNotNull(
                        details.checkInTime?.takeIf { it.isNotBlank() }?.let { host.t("最早入住 $it", "from $it") },
                        details.checkOutTime?.takeIf { it.isNotBlank() }?.let { host.t("最晚退房 $it", "by $it") },
                    ).joinToString(" · "),
                )
                OrderDetailLine(host.t("酒店电话", "Hotel phone"), details.hotelPhone)
                OrderDetailLine(host.t("平台客服", "Platform support"), details.platformSupportPhone)
                OrderDetailLine(
                    host.t("坐标", "Coordinates"),
                    if (details.latitude != null && details.longitude != null) "${details.latitude}, ${details.longitude}" else null,
                )
                OrderDetailLine(host.t("支付方式", "Payment type"), details.paymentType)
                OrderDetailLine(
                    host.t("免费取消", "Free cancellation"),
                    details.isCancelable?.let {
                        if (it) host.t("当前可免费取消", "available now") else host.t("当前不可免费取消", "not available now")
                    },
                )
                OrderDetailLine(host.t("免费取消截止", "Free cancel until"), details.freeCancelDeadline)
            }
        }
        DdExpandToggle(
            expanded = expanded,
            collapsedLabel = host.t("展开全部信息", "Show all details"),
            expandedLabel = host.t("收起全部信息", "Show fewer details"),
            onToggle = { expanded = !expanded },
            modifier = Modifier.padding(start = DdSpacing.Card),
        )
        Column(Modifier.fillMaxWidth().padding(horizontal = DdSpacing.Card)) {
            details.paymentUrl?.takeIf { it.isNotBlank() }?.let { paymentUrl ->
                DdPrimaryButton(
                    text = host.t("去支付", "Pay now"),
                    onClick = { host.openHotelUrl(paymentUrl) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
            }
            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                details.message?.takeIf { it.isNotBlank() }?.let {
                    DdText(
                        text = it,
                        modifier = Modifier.weight(1f),
                        style = DdType.chip.copy(fontSize = 11.sp, lineHeight = 15.sp),
                        color = colors.inkTertiary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                } ?: Spacer(Modifier.weight(1f))
                details.hotelImageUrl?.takeIf { it.isNotBlank() }?.let { url ->
                    DdTextAction(text = host.t("酒店图片", "Hotel photo"), onClick = { host.openHotelUrl(url) })
                }
                details.orderDetailUrl?.takeIf { it.isNotBlank() }?.let { url ->
                    DdTextAction(text = host.t("打开订单页面", "Open order page"), onClick = { host.openHotelUrl(url) })
                }
            }
        }
    }
}

@Composable
private fun OrderDetailLine(label: String, value: String?, numeric: Boolean = false) {
    if (value.isNullOrBlank()) return
    val colors = deepDeepColors()
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
        DdText(
            text = label,
            modifier = Modifier.width(78.dp),
            style = DdType.chip.copy(fontSize = 10.sp, lineHeight = 16.sp),
            color = colors.inkTertiary,
        )
        DdText(
            text = value,
            modifier = Modifier.weight(1f),
            style = if (numeric) {
                DdType.numeric.copy(fontSize = 12.sp, letterSpacing = 0.2.sp)
            } else {
                DdType.label.copy(fontSize = 13.sp, lineHeight = 18.sp)
            },
            color = colors.ink,
        )
    }
}

@Composable
private fun HotelOrderDetailLoading(host: PluginHost) {
    val colors = deepDeepColors()
    val appear = rememberHotelAppear()
    HotelCard(Modifier.graphicsLayer { alpha = appear }) {
        Column(Modifier.fillMaxWidth().padding(horizontal = DdSpacing.Card, vertical = 14.dp)) {
            SkeletonBar(width = 120.dp, height = 15.dp)
            Spacer(Modifier.height(10.dp))
            SkeletonBar(width = 196.dp, height = 18.dp)
            Spacer(Modifier.height(7.dp))
            SkeletonBar(width = 160.dp, height = 11.dp)
        }
        DdHairline()
        repeat(3) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = DdSpacing.Card, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SkeletonBar(width = 64.dp, height = 10.dp)
                Spacer(Modifier.width(DdSpacing.Md))
                SkeletonBar(height = 12.dp)
            }
        }
        DdText(
            text = host.t("正在查询订单详情…", "Checking order details…"),
            modifier = Modifier.padding(horizontal = DdSpacing.Card, vertical = 10.dp),
            style = DdType.chip.copy(fontSize = 11.sp),
            color = colors.inkTertiary,
        )
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
        HotelErrorState(
            host,
            hotelResultMessage(result.details, result.text, host.t("订单没有创建成功", "The order wasn't created")),
        )
        return
    }
    val details = result.details?.let(::decodeBooking)
    if (details == null) {
        HotelFallbackCard(host, host.t("没有可展示的预订结果。", "No booking result to show."))
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
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val roomFacts = listOfNotNull(
                details.ratePlanName?.takeIf { it.isNotBlank() },
                details.bed?.takeIf { it.isNotBlank() },
                details.meal?.takeIf { it.isNotBlank() },
                details.roomCount?.let { host.t("$it 间", "$it room(s)") },
                details.adultCount?.let { host.t("每间 $it 位成人", "$it adult(s) each") },
            )
            if (roomFacts.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                DdText(
                    text = roomFacts.joinToString(" · "),
                    style = DdType.chip.copy(fontSize = 10.sp, lineHeight = 14.sp),
                    color = colors.inkTertiary,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val guestText = details.guestNames.takeIf { it.isNotEmpty() }
                ?.joinToString("、")?.let { host.t("住客：$it", "Guests: $it") }
            val requestText = details.customerRequest?.takeIf { it.isNotBlank() }
                ?.let { host.t("要求：$it", "Request: $it") }
            listOfNotNull(guestText, requestText).forEach { line ->
                Spacer(Modifier.height(2.dp))
                DdText(
                    text = line,
                    style = DdType.chip.copy(fontSize = 10.sp, lineHeight = 14.sp),
                    color = colors.inkTertiary,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
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
                    if (details.totalDerived && details.totalPrice != null) {
                        DdText(
                            text = host.t("按 ${details.nights ?: nights} 晚折算", "derived for ${details.nights ?: nights} night(s)"),
                            style = DdType.chip.copy(fontSize = 10.sp, lineHeight = 14.sp),
                            color = colors.inkTertiary,
                            maxLines = 1,
                        )
                    }
                }
            }
            val bookingFacts = listOfNotNull(
                details.paymentType?.takeIf { it.isNotBlank() }?.let { host.t("支付方式：$it", "Payment type: $it") },
                details.contactName?.takeIf { it.isNotBlank() }?.let { host.t("联系人：$it", "Contact: $it") },
                details.contactEmail?.takeIf { it.isNotBlank() },
            )
            if (bookingFacts.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                DdText(
                    text = bookingFacts.joinToString(" · "),
                    style = DdType.chip.copy(fontSize = 10.sp, lineHeight = 14.sp),
                    color = colors.inkTertiary,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
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
                    onClick = { host.openHotelUrl(paymentUrl) },
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
    val payment = paymentStatus?.uppercase().orEmpty()
    if (payment == "CREATED" || payment == "UNPAID" || payment == "PENDING") return HotelTone.WARNING
    val raw = status?.trim().orEmpty()
    val value = raw.uppercase()
    return when {
        value.contains("CANCEL") || raw.contains("取消") -> HotelTone.DANGER
        value == "1" || value == "PENDING" || value == "UNPAID" || raw.contains("待支付") || raw.contains("待出行") ->
            HotelTone.WARNING

        value == "3" || value == "FINISHED" || raw.contains("已确认") || raw.contains("已完成") || raw.contains("已入住") ->
            HotelTone.SUCCESS

        else -> HotelTone.NEUTRAL
    }
}

private fun cancellationTone(text: String): HotelTone = cancellationToneOf(text)

private fun shortDate(raw: String?): String? {
    val value = raw?.takeIf { it.isNotBlank() } ?: return null
    val parsed = runCatching { LocalDate.parse(value.take(10)) }.getOrNull() ?: return value.take(10)
    return parsed.format(ShortDateFormatter)
}

private val ShortDateFormatter = DateTimeFormatter.ofPattern("MM-dd")

private fun PluginHost.openHotelUrl(url: String) {
    runCatching { ui().openUrl(url) }
        .onFailure { runCatching { ui().toast(t("无法打开链接", "Couldn't open the link")) } }
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

private fun decodeOrderDetail(details: JsonObject): HotelOrderDetail? = runCatching {
    HotelJson.decodeFromJsonElement(HotelOrderDetail.serializer(), details)
}.getOrNull()

private fun decodeCancelQuote(details: JsonObject): HotelCancelQuote? = runCatching {
    HotelJson.decodeFromJsonElement(HotelCancelQuote.serializer(), details)
}.getOrNull()

private fun decodeCancelResult(details: JsonObject): HotelCancelResult? = runCatching {
    HotelJson.decodeFromJsonElement(HotelCancelResult.serializer(), details)
}.getOrNull()

private fun decodeTags(details: JsonObject): HotelSearchTags? = runCatching {
    HotelJson.decodeFromJsonElement(HotelSearchTags.serializer(), details)
}.getOrNull()

@Composable
internal fun HotelCancelCard(host: PluginHost, state: ToolCardSlotState) {
    val result = state.result
    if (result == null) {
        HotelCancelLoading(host)
        return
    }
    if (result.isError) {
        HotelErrorState(
            host,
            hotelResultMessage(result.details, result.text, host.t("取消没有完成", "Cancellation didn't finish")),
        )
        return
    }
    val details = result.details
    if (details == null) {
        HotelFallbackCard(host, host.t("没有可展示的取消结果。", "No cancellation result to show."))
        return
    }
    if (details["cancelled"] != null) {
        val cancelled = decodeCancelResult(details)
        if (cancelled == null) {
            HotelFallbackCard(host, host.t("没有可展示的取消结果。", "No cancellation result to show."))
            return
        }
        CancelResultCard(host, cancelled)
        return
    }
    val quote = decodeCancelQuote(details)
    if (quote == null) {
        HotelFallbackCard(host, host.t("没有可展示的取消结果。", "No cancellation result to show."))
        return
    }
    CancelQuoteCard(host, quote)
}

@Composable
private fun CancelQuoteCard(host: PluginHost, quote: HotelCancelQuote) {
    val colors = deepDeepColors()
    HotelCard {
        Column(Modifier.fillMaxWidth().padding(horizontal = DdSpacing.Card, vertical = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DdText(
                    text = host.t("取消订单", "Cancel order"),
                    modifier = Modifier.weight(1f),
                    style = DdType.label.copy(fontSize = 16.sp, lineHeight = 22.sp),
                    color = colors.ink,
                )
                HotelTag(
                    text = when {
                        quote.alreadyCancelled -> host.t("已取消", "Cancelled")
                        quote.canCancel -> host.t("预检", "Quote")
                        else -> host.t("不可在线取消", "Cannot cancel online")
                    },
                    tone = when {
                        quote.alreadyCancelled -> HotelTone.SUCCESS
                        quote.canCancel -> HotelTone.NEUTRAL
                        else -> HotelTone.WARNING
                    },
                )
            }
            quote.hotelName?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(3.dp))
                DdText(
                    text = it,
                    style = DdType.chip.copy(fontSize = 11.sp, lineHeight = 15.sp),
                    color = colors.inkSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(10.dp))
            DdHairline()
            Spacer(Modifier.height(10.dp))
            CancelLine(host.t("订单号", "Order"), quote.orderNo)
            if (!quote.alreadyCancelled) {
                CancelLine(host.t("取消罚金", "Fee"), quote.feeText ?: host.t("以确认页为准", "shown at confirmation"))
                quote.policy?.takeIf { it.isNotBlank() }?.let { CancelLine(host.t("取消政策", "Policy"), it) }
                quote.expiresAt?.takeIf { it.isNotBlank() }?.let {
                    CancelLine(host.t("确认凭证有效期", "Credential valid until"), it)
                }
            }
            quote.message?.takeIf { it.isNotBlank() && !quote.alreadyCancelled }?.let { message ->
                Spacer(Modifier.height(6.dp))
                DdText(
                    text = message,
                    style = DdType.bodySmall,
                    color = if (quote.canCancel) colors.inkSecondary else colors.warning,
                )
            }
            Spacer(Modifier.height(10.dp))
            DdText(
                text = when {
                    quote.alreadyCancelled -> host.t("订单已取消，无需操作", "Order is already cancelled; no action needed")
                    quote.canCancel -> host.t("还没有真正取消；确认时会再弹一次确认，取消后不可撤销", "Nothing is cancelled yet; confirming once more will cancel it permanently")
                    else -> host.t("这笔订单不能在线取消，请联系 RollingGo 客服", "This order can't be cancelled online; contact RollingGo support")
                },
                style = DdType.chip.copy(fontSize = 10.sp, lineHeight = 14.sp),
                color = colors.inkTertiary,
            )
        }
    }
}

@Composable
private fun CancelResultCard(host: PluginHost, result: HotelCancelResult) {
    val colors = deepDeepColors()
    HotelCard {
        Column(Modifier.fillMaxWidth().padding(horizontal = DdSpacing.Card, vertical = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(28.dp).clip(CircleShape)
                        .background(if (result.cancelled) colors.success else colors.warning),
                    contentAlignment = Alignment.Center,
                ) {
                    DdIcon(
                        icon = if (result.cancelled) DdIcons.Check else DdIcons.Info,
                        tint = colors.onAccent,
                        size = 16.dp,
                    )
                }
                Spacer(Modifier.width(9.dp))
                DdText(
                    text = if (result.cancelled) host.t("订单已取消", "Order cancelled") else host.t("取消未完成", "Cancellation incomplete"),
                    modifier = Modifier.weight(1f),
                    style = DdType.label.copy(fontSize = 16.sp, lineHeight = 22.sp),
                    color = colors.ink,
                )
            }
            Spacer(Modifier.height(10.dp))
            CancelLine(host.t("订单号", "Order"), result.orderNo)
            result.hotelName?.takeIf { it.isNotBlank() }?.let { CancelLine(host.t("酒店", "Hotel"), it) }
            val translate: HotelTranslate = { zh, en -> host.t(zh, en) }
            result.orderStatus?.takeIf { it.isNotBlank() }?.let { raw ->
                val human = hotelOrderStatusText(raw, translate) ?: raw
                val cancelledLabel = host.t("已取消", "cancelled")
                if (!(result.cancelled && human == cancelledLabel)) {
                    CancelLine(host.t("订单状态", "Status"), human)
                }
            }
            result.message?.takeIf { it.isNotBlank() }?.let { message ->
                Spacer(Modifier.height(6.dp))
                DdText(text = message, style = DdType.bodySmall, color = colors.inkSecondary)
            }
            Spacer(Modifier.height(8.dp))
            DdText(
                text = host.t("退款进度以 RollingGo 订单页为准", "Refund progress appears on the RollingGo order page"),
                style = DdType.chip.copy(fontSize = 10.sp, lineHeight = 14.sp),
                color = colors.inkTertiary,
            )
        }
    }
}

@Composable
private fun CancelLine(label: String, value: String) {
    val colors = deepDeepColors()
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
        DdText(
            text = label,
            modifier = Modifier.width(72.dp),
            style = DdType.chip.copy(fontSize = 11.sp, lineHeight = 15.sp),
            color = colors.inkTertiary,
            maxLines = 1,
        )
        Spacer(Modifier.width(DdSpacing.Sm))
        DdText(
            text = value,
            modifier = Modifier.weight(1f),
            style = DdType.chip.copy(fontSize = 12.sp, lineHeight = 17.sp),
            color = colors.ink,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun HotelCancelLoading(host: PluginHost) {
    HotelCard {
        Column(Modifier.fillMaxWidth().padding(horizontal = DdSpacing.Card, vertical = 16.dp)) {
            SkeletonBar(120.dp, 14.dp)
            Spacer(Modifier.height(10.dp))
            SkeletonBar(220.dp, 10.dp)
            Spacer(Modifier.height(6.dp))
            SkeletonBar(180.dp, 10.dp)
        }
    }
}

@Composable
internal fun HotelTagsCard(host: PluginHost, state: ToolCardSlotState) {
    val result = state.result
    if (result == null) {
        HotelTagsLoading()
        return
    }
    if (result.isError) {
        HotelErrorState(
            host,
            hotelResultMessage(result.details, result.text, host.t("标签查询失败", "Tag lookup failed")),
        )
        return
    }
    val details = result.details?.let(::decodeTags)
    if (details == null || details.categories.isEmpty()) {
        HotelEmptyState(
            title = host.t("没有可用的筛选标签", "No search tags available"),
            hint = host.t("也可以直接按用户原话传标签或品牌", "You can also pass tags or brands from the user's words"),
            icon = DdIcons.Search,
        )
        return
    }
    val colors = deepDeepColors()
    var expanded by remember(details) { mutableStateOf(false) }
    val categories = if (expanded) details.categories else details.categories.take(TAG_CATEGORY_PREVIEW)
    HotelCard {
        Column(Modifier.fillMaxWidth().padding(horizontal = DdSpacing.Card, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DdText(
                    text = host.t("酒店可筛标签", "Hotel filter tags"),
                    modifier = Modifier.weight(1f),
                    style = DdType.label.copy(fontSize = 16.sp, lineHeight = 22.sp),
                    color = colors.ink,
                )
                DdText(
                    text = host.t("${details.count} 个", "${details.count} tags"),
                    style = DdType.chip.copy(fontSize = 11.sp),
                    color = colors.inkTertiary,
                )
            }
            categories.forEach { category ->
                Spacer(Modifier.height(9.dp))
                DdText(
                    text = category.category,
                    style = DdType.chip.copy(fontSize = 10.sp, lineHeight = 14.sp),
                    color = colors.inkTertiary,
                )
                Spacer(Modifier.height(4.dp))
                val names = if (expanded) category.names else category.names.take(TAG_PREVIEW)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    names.forEach { name -> HotelTag(name) }
                    if (!expanded && category.names.size > TAG_PREVIEW) {
                        DdText(
                            text = host.t("等 ${category.names.size} 个", "${category.names.size} in total"),
                            style = DdType.chip.copy(fontSize = 10.sp),
                            color = colors.inkTertiary,
                        )
                    }
                }
            }
            Spacer(Modifier.height(9.dp))
            if (!expanded && (details.categories.size > TAG_CATEGORY_PREVIEW ||
                    details.categories.any { it.names.size > TAG_PREVIEW })
            ) {
                DdExpandToggle(
                    expanded = expanded,
                    collapsedLabel = host.t("展开全部标签", "Show all tags"),
                    expandedLabel = host.t("收起标签", "Show fewer tags"),
                    onToggle = { expanded = !expanded },
                )
            }
            DdHairline()
            Spacer(Modifier.height(8.dp))
            DdText(
                text = host.t(
                    "标签名用于 hotel_search 的 required_tags；品牌也可以传 preferred_brands",
                    "Pass tag names to hotel_search required_tags; brands can go to preferred_brands",
                ),
                style = DdType.chip.copy(fontSize = 10.sp, lineHeight = 14.sp),
                color = colors.inkTertiary,
            )
        }
    }
}

@Composable
private fun HotelTagsLoading() {
    HotelCard {
        Column(Modifier.fillMaxWidth().padding(horizontal = DdSpacing.Card, vertical = 16.dp)) {
            SkeletonBar(140.dp, 14.dp)
            Spacer(Modifier.height(12.dp))
            repeat(3) {
                SkeletonBar(200.dp, 10.dp)
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

private const val TAG_CATEGORY_PREVIEW = 3
private const val TAG_PREVIEW = 6
