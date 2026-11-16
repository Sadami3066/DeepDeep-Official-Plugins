package io.github.whywood.deepdeep.plugin.ride

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.whywood.deepdeep.design.DdExpandToggle
import io.github.whywood.deepdeep.design.DdGroupCard
import io.github.whywood.deepdeep.design.DdHairline
import io.github.whywood.deepdeep.design.DdSpinner
import io.github.whywood.deepdeep.design.DdSpacing
import io.github.whywood.deepdeep.design.DdStatusRow
import io.github.whywood.deepdeep.design.DdStatusTone
import io.github.whywood.deepdeep.design.DdText
import io.github.whywood.deepdeep.design.DdTextAction
import io.github.whywood.deepdeep.design.DdType
import io.github.whywood.deepdeep.design.deepDeepColors
import io.github.whywood.deepdeep.plugin.api.PluginHost
import io.github.whywood.deepdeep.plugin.api.ToolResultState
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal fun rideCardPayload(state: ToolResultState): JsonObject? =
    unwrapRidePayload(state.structuredContent)
        ?: unwrapRidePayload(state.result?.details)
        ?: (parseDidiPayload(state.result?.text) as? JsonObject)

private fun rideCardError(state: ToolResultState): String? {
    val result = state.result ?: return null
    if (!result.isError) return null
    val text = summarizeDidiError(result.text)
    return text.ifBlank { null }
}

private fun estimateArguments(state: ToolResultState): Pair<String?, String?> {
    val from = (state.arguments["from_name"] as? JsonPrimitive)?.contentOrNull
    val to = (state.arguments["to_name"] as? JsonPrimitive)?.contentOrNull
    return from to to
}

@Composable
private fun RideCardShell(
    title: String,
    subtitle: String? = null,
    footer: String? = null,
    content: @Composable () -> Unit = {},
) {
    val colors = deepDeepColors()
    RideCardEntrance {
        DdGroupCard {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = DdSpacing.Card, vertical = DdSpacing.Lg),
                verticalArrangement = Arrangement.spacedBy(DdSpacing.Sm),
            ) {
                DdText(title, style = DdType.heading, color = colors.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) {
                    DdText(
                        subtitle,
                        style = DdType.bodySmall,
                        color = colors.inkSecondary,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                content()
                if (footer != null) {
                    Spacer(Modifier.height(DdSpacing.Xs))
                    DdText(
                        footer,
                        style = DdType.micro,
                        color = colors.inkTertiary,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun RideLoadingCard(host: PluginHost, title: String) {
    val colors = deepDeepColors()
    RideCardShell(title = title) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DdSpinner(color = colors.inkTertiary, size = 14.dp)
            Spacer(Modifier.width(DdSpacing.Sm))
            DdText(host.t("正在查询，请稍候…", "Loading…"), style = DdType.bodySmall, color = colors.inkSecondary)
        }
    }
}

@Composable
private fun RideErrorCard(host: PluginHost, title: String, error: String) {
    RideCardShell(
        title = title,
        subtitle = error,
        footer = host.t("可以稍后在对话里重试。", "Try again in the chat later."),
    )
}

@Composable
private fun RideEmptyCard(host: PluginHost, title: String, reason: String) {
    RideCardShell(title = title, subtitle = reason)
}

@Composable
private fun RideEstimateRow(host: PluginHost, item: RideEstimateItem) {
    val colors = deepDeepColors()
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DdText(
                    text = item.productName,
                    modifier = Modifier.weight(1f),
                    style = DdType.body,
                    color = colors.ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                item.eta?.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.width(DdSpacing.Sm))
                    RideTag(host.t("接驾 $it", "ETA $it"))
                }
            }
            val meta = listOfNotNull(
                item.distance?.let { host.t("距离 $it", "distance $it") },
                item.duration?.let { host.t("约 $it", "about $it") },
                item.coupon?.let { host.t("优惠 $it", "coupon $it") },
            ).joinToString(" · ")
            if (meta.isNotBlank()) {
                DdText(meta, style = DdType.micro, color = colors.inkTertiary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(DdSpacing.Md))
        val listPrice = rideMoney(item.priceText, item.currency)
        val finalPrice = rideMoney(item.priceDiscounted, item.currency)
        RidePriceBlock(
            price = finalPrice ?: listPrice,
            listPrice = if (finalPrice != null) listPrice else null,
            highlighted = finalPrice != null,
        )
    }
}

@Composable
internal fun RideEstimateCard(host: PluginHost, state: ToolResultState) {
    val title = host.t("打车预估", "Ride estimate")
    rideCardError(state)?.let { error ->
        RideErrorCard(host, title, error)
        return
    }
    if (state.result == null) {
        RideLoadingCard(host, title)
        return
    }
    val (fromName, toName) = estimateArguments(state)
    val estimate = parseRideEstimate(rideCardPayload(state), fromName, toName)
    if (estimate.isEmpty) {
        RideEmptyCard(
            host,
            title,
            host.t("没有可用的车型或价格，换个地址再试。", "No ride options for this route. Try another address."),
        )
        return
    }
    var expanded by remember(estimate.traceId, estimate.route, estimate.items.size) { mutableStateOf(false) }
    val colors = deepDeepColors()
    val route = estimate.route.ifBlank { host.t("本次行程", "This trip") }
    val routeFacts = listOfNotNull(
        estimate.distance?.let { host.t("全程 $it", "distance $it") },
        estimate.duration?.let { host.t("约 $it", "about $it") },
        estimate.eta?.let { host.t("接驾约 $it", "pickup in $it") },
        estimate.phoneSuffix?.let { host.t("账号尾号 $it", "account suffix $it") },
    ).joinToString(" · ")
    val visible = if (expanded) estimate.items else estimate.items.take(CARD_PREVIEW_ITEMS)
    RideCardShell(
        title = route,
        subtitle = routeFacts.ifBlank {
            host.t("车型与参考价", "Ride options and estimated fares")
        },
        footer = host.t("参考价，实际以下单页为准", "Estimated fare; the booking page is final."),
    ) {
        DdHairline()
        visible.forEachIndexed { index, item ->
            RideEstimateRow(host, item)
            if (index < visible.lastIndex) DdHairline()
        }
        if (estimate.items.size > CARD_PREVIEW_ITEMS) {
            DdExpandToggle(
                expanded = expanded,
                collapsedLabel = host.tf(
                    "查看全部 %d 种车型",
                    "Show all %d options",
                    estimate.items.size,
                ),
                expandedLabel = host.t("收起车型", "Show fewer options"),
                onToggle = { expanded = !expanded },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (estimate.items.size <= CARD_PREVIEW_ITEMS) {
            DdText(
                host.tf("共 %d 种车型", "%d options", estimate.items.size),
                style = DdType.micro,
                color = colors.inkTertiary,
            )
        }
    }
}

@Composable
internal fun RideLinkCard(host: PluginHost, state: ToolResultState) {
    val title = host.t("打开滴滴完成发单", "Open Didi to book")
    rideCardError(state)?.let { error ->
        RideErrorCard(host, title, error)
        return
    }
    if (state.result == null) {
        RideLoadingCard(host, title)
        return
    }
    val link = parseRideLink(rideCardPayload(state))
    if (link.isEmpty) {
        RideEmptyCard(
            host,
            title,
            host.t("没有生成可用的跳转链接，请重新预估后再试。", "No ride link was generated. Estimate again and retry."),
        )
        return
    }
    val colors = deepDeepColors()
    RideCardShell(
        title = title,
        subtitle = host.t("确认车型和价格后，在滴滴里继续下单。", "Review the ride and fare, then book in Didi."),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(DdSpacing.Lg)) {
            link.appLink?.let { url ->
                DdTextAction(
                    text = host.t("打开滴滴 App", "Open Didi app"),
                    onClick = { openRideUrl(host, url) },
                )
            }
            link.miniprogramLink?.let { url ->
                DdTextAction(
                    text = host.t("打开小程序", "Open mini program"),
                    onClick = { openRideUrl(host, url) },
                )
            }
        }
        DdText(
            host.t("插件不会自动下单或支付。", "The plugin never books or pays for you."),
            style = DdType.micro,
            color = colors.inkTertiary,
        )
    }
}

@Composable
internal fun RideOrderCard(host: PluginHost, state: ToolResultState) {
    val created = state.toolName == "ride_create_order" || state.toolName == "taxi_create_order"
    val defaultTitle = if (created) host.t("订单已创建", "Order created") else host.t("打车订单", "Ride order")
    rideCardError(state)?.let { error ->
        RideErrorCard(host, defaultTitle, error)
        return
    }
    if (state.result == null) {
        RideLoadingCard(host, defaultTitle)
        return
    }
    val order = parseRideOrder(rideCardPayload(state))
    if (order.isEmpty) {
        RideEmptyCard(
            host,
            defaultTitle,
            host.t("没有查询到进行中的订单。", "No active ride order found."),
        )
        return
    }
    val colors = deepDeepColors()
    val translate: RideTranslate = { zh, en -> host.t(zh, en) }
    val status = rideStatusLabel(order.statusCode ?: order.status, order.statusText, translate, includeCode = false)
    val headline = status ?: defaultTitle
    val route = order.route
    RideCardShell(
        title = headline,
        footer = if (created) {
            host.t("支付在滴滴内完成；司机接单后可查询状态和位置。", "Payment happens in Didi. Check status and driver location after a driver accepts.")
        } else {
            host.t("状态由滴滴实时返回，可能随后变化。", "Status comes from DiDi and may change.")
        },
    ) {
        if (order.fromName != null && order.toName != null) {
            DdHairline()
            RideRouteStrip(from = order.fromName, to = order.toName, translate = translate)
        }
        if (order.driver != null || order.carModel != null || order.plate != null || order.phone != null ||
            order.driverRating != null || order.driverOrderCount != null
        ) {
            DdHairline()
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                DdText(
                    text = order.driver ?: host.t("司机", "Driver"),
                    modifier = Modifier.weight(1f),
                    style = DdType.body,
                    color = colors.ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                order.plate?.takeIf { it.isNotBlank() }?.let { RidePlate(it) }
            }
            order.carModel?.let { DdStatusRow(label = host.t("车型", "Car"), value = it, tone = DdStatusTone.NEUTRAL) }
            order.phone?.let { DdStatusRow(label = host.t("电话", "Phone"), value = it, tone = DdStatusTone.NEUTRAL) }
            val driverFacts = listOfNotNull(
                order.driverRating?.let { host.t("评分 $it", "rating $it") },
                order.driverOrderCount?.let { host.t("已服务 $it 单", "$it trips") },
            ).joinToString(" · ")
            if (driverFacts.isNotBlank()) {
                DdText(driverFacts, style = DdType.micro, color = colors.inkTertiary)
            }
        }
        val positionRows = buildList<Triple<String, String, DdStatusTone>> {
            order.distanceKm?.let { add(Triple(host.t("距上车点", "To pickup"), it, DdStatusTone.NEUTRAL)) }
            order.eta?.let { add(Triple(host.t("预计到达", "ETA"), it, DdStatusTone.ACCENT)) }
            order.phase?.let { phase ->
                val label = when (phase) {
                    "to_pickup" -> host.t("前往上车点", "Heading to pickup")
                    "to_dropoff" -> host.t("前往目的地", "Heading to destination")
                    else -> null
                }
                label?.let { add(Triple(host.t("阶段", "Phase"), it, DdStatusTone.NEUTRAL)) }
            }
        }
        if (positionRows.isNotEmpty()) {
            DdHairline()
            positionRows.forEach { (label, value, tone) ->
                DdStatusRow(label = label, value = value, tone = tone)
            }
        }
        val timeRows = listOfNotNull(
            order.createdAt?.let { rideTimeText(it)?.let { time -> host.t("下单时间", "Ordered") to time } },
            order.startedAt?.let { rideTimeText(it)?.let { time -> host.t("行程开始", "Trip started") to time } },
            order.finishedAt?.let { rideTimeText(it)?.let { time -> host.t("行程结束", "Trip ended") to time } },
            order.updatedAt?.let { rideTimeText(it)?.let { time -> host.t("状态更新", "Status updated") to time } },
        )
        if (timeRows.isNotEmpty()) {
            DdHairline()
            timeRows.forEach { (label, value) ->
                DdStatusRow(label = label, value = value, tone = DdStatusTone.NEUTRAL)
            }
        }
        val orderRows = listOfNotNull(
            order.orderId?.let { host.t("订单号", "Order") to it },
            order.phoneSuffix?.let { host.t("账号尾号", "Phone suffix") to it },
            route.takeIf { it.isNotBlank() && (order.fromName == null || order.toName == null) }
                ?.let { host.t("行程", "Trip") to it },
        )
        if (orderRows.isNotEmpty()) {
            DdHairline()
            orderRows.forEach { (label, value) ->
                DdStatusRow(label = label, value = value, tone = DdStatusTone.NEUTRAL)
            }
        }
    }
}

@Composable
internal fun RideDriverLocationCard(host: PluginHost, state: ToolResultState) {
    val title = host.t("司机位置", "Driver location")
    rideCardError(state)?.let { error ->
        RideErrorCard(host, title, error)
        return
    }
    if (state.result == null) {
        RideLoadingCard(host, title)
        return
    }
    val location = parseRideDriverLocation(rideCardPayload(state))
    if (location.isEmpty) {
        RideEmptyCard(
            host,
            title,
            host.t("暂时无法获取司机位置，稍后再试。", "Driver location is not available yet. Try again shortly."),
        )
        return
    }
    val colors = deepDeepColors()
    RideCardShell(title = title) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            RideRadar()
            Spacer(Modifier.width(DdSpacing.Md))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                DdText(
                    text = location.summary ?: host.t("司机实时位置", "Live driver location"),
                    style = DdType.body,
                    color = colors.ink,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                location.coordinates?.let {
                    DdText(it, style = DdType.micro, color = colors.inkTertiary)
                }
            }
        }
        if (location.driverName != null || location.plate != null) {
            DdHairline()
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                DdText(
                    text = location.driverName ?: host.t("司机", "Driver"),
                    modifier = Modifier.weight(1f),
                    style = DdType.body,
                    color = colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                location.plate?.takeIf { it.isNotBlank() }?.let { RidePlate(it) }
            }
        }
        location.status?.takeIf { it.isNotBlank() }?.let { status ->
            val translate: RideTranslate = { zh, en -> host.t(zh, en) }
            DdStatusRow(
                label = host.t("状态", "Status"),
                value = rideStatusLabel(status, null, translate, includeCode = false) ?: status,
                tone = rideStatusTone(status),
            )
        }
        location.distance?.let {
            DdStatusRow(label = host.t("距上车点", "To pickup"), value = it, tone = DdStatusTone.NEUTRAL)
        }
        location.eta?.let {
            DdStatusRow(label = host.t("预计到达", "ETA"), value = it, tone = DdStatusTone.ACCENT)
        }
        location.updatedAt?.let {
            rideTimeText(it)?.let { time ->
                DdStatusRow(label = host.t("更新时间", "Updated"), value = time, tone = DdStatusTone.NEUTRAL)
            }
        }
        DdText(
            host.t("位置由滴滴实时提供，仅供查看。", "Location comes from Didi and is read-only."),
            style = DdType.micro,
            color = colors.inkTertiary,
        )
    }
}

@Composable
internal fun RideCancelCard(host: PluginHost, state: ToolResultState) {
    val title = host.t("取消订单", "Cancel ride")
    rideCardError(state)?.let { error ->
        RideErrorCard(host, title, error)
        return
    }
    if (state.result == null) {
        RideLoadingCard(host, title)
        return
    }
    val result = parseRideCancelResult(rideCardPayload(state))
    if (result.isEmpty) {
        RideEmptyCard(host, title, host.t("没有返回取消结果。", "No cancellation result was returned."))
        return
    }
    val tone = when (result.cancelled) {
        true -> DdStatusTone.SUCCESS
        false -> DdStatusTone.DANGER
        null -> DdStatusTone.NEUTRAL
    }
    val value = result.message ?: when (result.cancelled) {
        true -> host.t("已取消", "Cancelled")
        false -> host.t("取消失败", "Cancellation failed")
        null -> host.t("未知", "Unknown")
    }
    RideCardShell(title = title) {
        DdStatusRow(label = host.t("结果", "Result"), value = value, tone = tone)
    }
}

@Composable
internal fun RideSearchCard(host: PluginHost, state: ToolResultState) {
    val title = host.t("地点搜索", "Place search")
    rideCardError(state)?.let { error ->
        RideErrorCard(host, title, error)
        return
    }
    if (state.result == null) {
        RideLoadingCard(host, title)
        return
    }
    val places = parseRidePlaces(state.structuredContent ?: state.result?.details)
    if (places.isEmpty()) {
        RideEmptyCard(host, title, host.t("没有找到匹配的地点。", "No matching places found."))
        return
    }
    var expanded by remember(places.size, places.firstOrNull()?.displayName) { mutableStateOf(false) }
    val colors = deepDeepColors()
    val cities = places.mapNotNull { it.city?.takeIf { city -> city.isNotBlank() } }.distinct()
    val subtitle = if (cities.size == 1) {
        host.tf("%s · %d 个地点", "%s · %d places", cities.first(), places.size)
    } else {
        host.tf("找到 %d 个地点", "Found %d places", places.size)
    }
    val visible = if (expanded) places else places.take(CARD_PREVIEW_ITEMS)
    RideCardShell(title = title, subtitle = subtitle) {
        DdHairline()
        visible.forEachIndexed { index, place ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(colors.wash),
                    contentAlignment = Alignment.Center,
                ) {
                    DdText("${index + 1}", style = DdType.numeric, color = colors.inkSecondary)
                }
                Spacer(Modifier.width(DdSpacing.Md))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    DdText(
                        text = place.displayName ?: host.t("地点", "Place"),
                        style = DdType.body,
                        color = colors.ink,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    place.address?.takeIf { it.isNotBlank() && it != place.name }?.let {
                        DdText(it, style = DdType.bodySmall, color = colors.inkSecondary, maxLines = 3)
                    }
                    val meta = listOfNotNull(
                        place.city?.takeIf { it.isNotBlank() },
                        place.coordinates,
                    ).joinToString(" · ")
                    if (meta.isNotBlank()) {
                        DdText(meta, style = DdType.micro, color = colors.inkTertiary, maxLines = 1)
                    }
                }
            }
            if (index < visible.lastIndex) DdHairline()
        }
        if (places.size > CARD_PREVIEW_ITEMS) {
            DdExpandToggle(
                expanded = expanded,
                collapsedLabel = host.tf("查看全部 %d 个地点", "Show all %d places", places.size),
                expandedLabel = host.t("收起地点", "Show fewer places"),
                onToggle = { expanded = !expanded },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private fun rideStatusTone(code: String?): DdStatusTone = when (code?.trim()) {
    "0" -> DdStatusTone.ACCENT
    "1", "2", "4", "5" -> DdStatusTone.SUCCESS
    "6", "7", "11", "12" -> DdStatusTone.DANGER
    "3", "8", "9", "10" -> DdStatusTone.WARNING
    else -> DdStatusTone.NEUTRAL
}

private fun openRideUrl(host: PluginHost, url: String) {
    runCatching { host.ui().openUrl(url) }.onFailure {
        runCatching {
            host.ui().snackbar(host.t("无法打开链接，请稍后再试", "Could not open the link. Try again."))
        }
    }
}

private const val CARD_PREVIEW_ITEMS = 4
