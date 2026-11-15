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
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.whywood.deepdeep.design.DdGroupCard
import io.github.whywood.deepdeep.design.DdHairline
import io.github.whywood.deepdeep.design.DdLoadingBlock
import io.github.whywood.deepdeep.design.DdNoticeBar
import io.github.whywood.deepdeep.design.DdNoticeTone
import io.github.whywood.deepdeep.design.DdPressable
import io.github.whywood.deepdeep.design.DdSpacing
import io.github.whywood.deepdeep.design.DdStatusRow
import io.github.whywood.deepdeep.design.DdStatusTone
import io.github.whywood.deepdeep.design.DdText
import io.github.whywood.deepdeep.design.DdTextAction
import io.github.whywood.deepdeep.design.DdType
import io.github.whywood.deepdeep.design.deepDeepColors
import io.github.whywood.deepdeep.plugin.api.PluginHost
import io.github.whywood.deepdeep.plugin.api.ToolCardSlotState
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

@Composable
internal fun HotelSearchCard(host: PluginHost, state: ToolCardSlotState) {
    val result = state.result
    if (result == null) {
        HotelLoadingCard(host)
        return
    }
    if (result.isError) {
        HotelErrorCard(host, result.text ?: host.t("酒店搜索失败", "Hotel search failed"))
        return
    }
    val details = result.details?.let(::decodeSearch)
    if (details == null) {
        HotelFallbackCard(host, result.text)
        return
    }
    if (details.hotels.isEmpty()) {
        HotelCardShell(
            title = "${details.place} · ${details.checkIn ?: host.t("近期", "Upcoming")}",
            subtitle = host.t("没有找到符合条件的酒店", "No hotels matched"),
        ) {
            DdText(
                host.t("可以放宽星级、距离或价格条件再试。", "Try relaxing stars, distance or price."),
                style = DdType.bodySmall,
                color = deepDeepColors().inkSecondary,
            )
        }
        return
    }
    HotelCardShell(
        title = "${details.place} · ${details.checkIn ?: host.t("近期", "Upcoming")}",
        subtitle = host.t("找到 ${details.hotels.size} 家酒店", "${details.hotels.size} hotels found"),
        footer = host.t("点酒店打开 RollingGo 查看并预订", "Tap a hotel to view and book on RollingGo"),
    ) {
        details.hotels.take(5).forEachIndexed { index, hotel ->
            if (index > 0) DdHairline()
            HotelSummaryRow(host, hotel)
        }
    }
}

@Composable
private fun HotelSummaryRow(host: PluginHost, hotel: HotelSummary) {
    val open: () -> Unit = {
        if (hotel.bookingUrl != null) host.openHotelBooking(hotel.bookingUrl)
    }
    if (hotel.bookingUrl != null) {
        DdPressable(
            onClick = open,
            modifier = Modifier.fillMaxWidth(),
            shape = RectangleShape,
            contentDescription = hotel.name,
        ) {
            HotelSummaryBody(host, hotel)
        }
    } else {
        HotelSummaryBody(host, hotel)
    }
}

@Composable
private fun HotelSummaryBody(host: PluginHost, hotel: HotelSummary) {
    val colors = deepDeepColors()
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            DdText(hotel.name, style = DdType.body, color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val meta = listOfNotNull(
                hotel.starRating?.let { "$it★" },
                hotel.priceText,
                hotel.distanceMeters?.let(::hotelDistanceText) ?: hotel.distance,
            ).joinToString(" · ")
            if (meta.isNotBlank()) {
                DdText(meta, style = DdType.bodySmall, color = colors.inkSecondary, maxLines = 1)
            }
            hotel.address?.let {
                DdText(it, style = DdType.chip, color = colors.inkTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (hotel.tags.isNotEmpty()) {
                DdText(
                    hotel.tags.joinToString(" · "),
                    style = DdType.chip,
                    color = colors.inkTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (hotel.bookingUrl != null) {
            Spacer(Modifier.width(DdSpacing.Sm))
            DdText(host.t("查看", "View"), style = DdType.label, color = colors.accent)
        }
    }
}

@Composable
internal fun HotelDetailCard(host: PluginHost, state: ToolCardSlotState) {
    val result = state.result
    if (result == null) {
        HotelLoadingCard(host)
        return
    }
    if (result.isError) {
        HotelErrorCard(host, result.text ?: host.t("房型查询失败", "Room lookup failed"))
        return
    }
    val details = result.details?.let(::decodeDetail)
    if (details == null) {
        HotelFallbackCard(host, result.text)
        return
    }
    HotelCardShell(
        title = details.name,
        subtitle = listOfNotNull(details.checkIn, details.checkOut).joinToString(" → ").ifBlank { null },
        footer = if (details.bookingUrl != null) {
            host.t("价格按 ${hotelNights(details.checkIn, details.checkOut)} 晚计算，以 RollingGo 实时价格为准", "Total for ${hotelNights(details.checkIn, details.checkOut)} night(s); live prices are confirmed on RollingGo")
        } else {
            null
        },
    ) {
        if (details.rooms.isEmpty()) {
            DdText(
                details.errorMessage ?: host.t("暂时没有可订房型。", "No rooms are available right now."),
                style = DdType.bodySmall,
                color = deepDeepColors().inkSecondary,
            )
        } else {
            val groups = details.roomGroups.ifEmpty { groupHotelRooms(details.rooms) }
            val nights = hotelNights(details.checkIn, details.checkOut)
            groups.take(6).forEachIndexed { index, group ->
                if (index > 0) DdHairline()
                Column(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        DdText(group.name, modifier = Modifier.weight(1f), style = DdType.body, color = deepDeepColors().ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        DdText(
                            group.lowestPriceText ?: host.t("价格待确认", "Price pending"),
                            style = DdType.body,
                            color = deepDeepColors().ink,
                        )
                    }
                    val cheapest = group.plans.firstOrNull()
                    val meta = listOfNotNull(
                        cheapest?.avgPriceText?.let { host.t("每晚 $it", "$it/night") },
                        group.bed,
                        if (group.planCount > 1) host.t("${group.planCount} 个方案", "${group.planCount} rate plans") else null,
                        group.cheapestCancellation,
                    ).joinToString(" · ")
                    if (meta.isNotBlank()) {
                        DdText(
                            meta,
                            style = DdType.bodySmall,
                            color = deepDeepColors().inkSecondary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    cheapest?.meal?.takeIf { it.isNotBlank() }?.let {
                        DdText(
                            "$it · ${host.t("共 $nights 晚", "$nights night(s)")}",
                            style = DdType.chip,
                            color = deepDeepColors().inkTertiary,
                            maxLines = 1,
                        )
                    }
                }
            }
            if (groups.size > 6) {
                DdText(
                    host.t("还有 ${groups.size - 6} 个房型", "${groups.size - 6} more room types"),
                    style = DdType.chip,
                    color = deepDeepColors().inkTertiary,
                )
            }
        }
        val bookingUrl = details.bookingUrl
        if (bookingUrl != null) {
            DdHairline()
            DdTextAction(
                text = host.t("去 RollingGo 预订", "Book on RollingGo"),
                onClick = { host.openHotelBooking(bookingUrl) },
            )
        }
    }
}

@Composable
internal fun HotelOrdersCard(host: PluginHost, state: ToolCardSlotState) {
    val result = state.result
    if (result == null) {
        HotelLoadingCard(host)
        return
    }
    if (result.isError) {
        HotelErrorCard(host, result.text ?: host.t("订单查询失败", "Order lookup failed"))
        return
    }
    val details = result.details?.let(::decodeOrders)
    if (details == null) {
        HotelFallbackCard(host, result.text)
        return
    }
    HotelCardShell(
        title = host.t("酒店订单", "Hotel orders"),
        subtitle = if (details.orders.isEmpty()) null else host.t("${details.orders.size} 笔订单", "${details.orders.size} orders"),
    ) {
        if (details.orders.isEmpty()) {
            DdText(
                details.message?.takeIf { it.isNotBlank() } ?: host.t("暂时没有酒店订单。", "No hotel orders yet."),
                style = DdType.bodySmall,
                color = deepDeepColors().inkSecondary,
            )
        } else {
            val translate: HotelTranslate = { zh, en -> host.t(zh, en) }
            details.orders.take(5).forEachIndexed { index, order ->
                if (index > 0) DdHairline()
                Column(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        DdText(order.hotelName, modifier = Modifier.weight(1f), style = DdType.body, color = deepDeepColors().ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        order.totalPrice?.let {
                            DdText(it, style = DdType.body, color = deepDeepColors().ink)
                        }
                    }
                    val stayMeta = listOfNotNull(
                        order.checkIn,
                        order.checkOut,
                        order.nights?.let { host.t("$it 晚", "$it night(s)") },
                        order.roomCount?.let { host.t("$it 间", "$it room(s)") },
                        order.roomName,
                    ).joinToString(" · ")
                    if (stayMeta.isNotBlank()) {
                        DdText(stayMeta, style = DdType.bodySmall, color = deepDeepColors().inkSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    val stateMeta = listOfNotNull(
                        hotelOrderStatusText(order.status, translate),
                        hotelPaymentStatusText(order.paymentStatus, translate),
                        order.orderNo?.let { host.t("订单号 $it", "Order $it") },
                    ).joinToString(" · ")
                    if (stateMeta.isNotBlank()) {
                        DdText(stateMeta, style = DdType.chip, color = deepDeepColors().inkTertiary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun HotelCardShell(
    title: String,
    subtitle: String? = null,
    footer: String? = null,
    content: @Composable () -> Unit,
) {
    DdGroupCard {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = DdSpacing.Card, vertical = DdSpacing.Lg),
            verticalArrangement = Arrangement.spacedBy(DdSpacing.Sm),
        ) {
            DdText(title, style = DdType.heading, color = deepDeepColors().ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            subtitle?.let {
                DdText(it, style = DdType.bodySmall, color = deepDeepColors().inkSecondary)
            }
            content()
            footer?.let {
                DdText(it, style = DdType.chip, color = deepDeepColors().inkTertiary)
            }
        }
    }
}

@Composable
internal fun HotelBookCard(host: PluginHost, state: ToolCardSlotState) {
    val result = state.result
    if (result == null) {
        HotelLoadingCard(host)
        return
    }
    if (result.isError) {
        HotelErrorCard(host, result.text ?: host.t("订单没有创建成功", "The order wasn't created"))
        return
    }
    val details = result.details?.let(::decodeBooking)
    if (details == null) {
        HotelFallbackCard(host, result.text)
        return
    }
    HotelCardShell(
        title = host.t("订单已创建", "Order created"),
        subtitle = details.hotelName,
        footer = if (details.paymentUrl.isNullOrBlank()) {
            host.t("请在 RollingGo 完成支付。", "Complete payment on RollingGo.")
        } else {
            host.t("请在 30 分钟内完成支付，超时订单可能关闭。", "Pay within 30 minutes; unpaid orders may close.")
        },
    ) {
        DdStatusRow(label = host.t("订单号", "Order"), value = details.orderNo, tone = DdStatusTone.NEUTRAL)
        details.roomName?.let {
            val room = listOfNotNull(it, details.ratePlanName).joinToString(" · ")
            DdStatusRow(label = host.t("房型", "Room"), value = room, tone = DdStatusTone.NEUTRAL)
        }
        if (details.checkIn != null && details.checkOut != null) {
            DdStatusRow(
                label = host.t("入住", "Stay"),
                value = "${details.checkIn} → ${details.checkOut}",
                tone = DdStatusTone.NEUTRAL,
            )
        }
        details.cancellation?.takeIf { it.isNotBlank() }?.let {
            DdStatusRow(label = host.t("取消政策", "Cancellation"), value = it, tone = DdStatusTone.NEUTRAL)
        }
        details.totalPrice?.let { DdStatusRow(label = host.t("总价", "Total"), value = it, tone = DdStatusTone.ACCENT) }
        details.paymentUrl?.takeIf { it.isNotBlank() }?.let { url ->
            DdHairline()
            DdTextAction(
                text = host.t("去支付", "Pay now"),
                onClick = { host.openHotelBooking(url) },
            )
        }
        details.message?.takeIf { it.isNotBlank() }?.let {
            DdText(it, style = DdType.bodySmall, color = deepDeepColors().inkSecondary)
        }
    }
}

@Composable
private fun HotelLoadingCard(host: PluginHost) {
    DdGroupCard {
        DdLoadingBlock(
            modifier = Modifier.padding(vertical = DdSpacing.Lg),
            text = host.t("正在查询酒店…", "Checking hotels…"),
        )
    }
}

@Composable
private fun HotelErrorCard(host: PluginHost, message: String) {
    DdNoticeBar(
        text = message,
        tone = DdNoticeTone.DANGER,
        actionLabel = host.t("去连接", "Open"),
        onAction = {
            runCatching { host.ui().openPage(HOTEL_PAGE_ID) }
                .onFailure { runCatching { host.ui().toast(host.t("无法打开酒店页面", "Couldn't open Hotels")) } }
        },
    )
}

@Composable
private fun HotelFallbackCard(host: PluginHost, text: String?) {
    DdGroupCard {
        DdText(
            text ?: host.t("没有酒店数据。", "No hotel data."),
            modifier = Modifier.padding(horizontal = DdSpacing.Card, vertical = DdSpacing.Lg),
            style = DdType.bodySmall,
            color = deepDeepColors().inkSecondary,
        )
    }
}

private fun PluginHost.openHotelBooking(url: String) {
    runCatching { ui().openUrl(url) }
        .onFailure { runCatching { ui().toast(t("无法打开 RollingGo", "Couldn't open RollingGo")) } }
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
