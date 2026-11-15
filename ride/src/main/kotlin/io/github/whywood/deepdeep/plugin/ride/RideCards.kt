package io.github.whywood.deepdeep.plugin.ride

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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

private fun money(host: PluginHost, value: String?): String? {
    val text = value?.trim().orEmpty()
    if (text.isEmpty()) return null
    val hasCurrency = text.startsWith("¥") || text.startsWith("￥") || text.startsWith("$")
    return if (hasCurrency) text else host.t("¥$text", "¥$text")
}

@Composable
private fun RideCardShell(
    title: String,
    subtitle: String? = null,
    footer: String? = null,
    content: @Composable () -> Unit = {},
) {
    val colors = deepDeepColors()
    DdGroupCard {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = DdSpacing.Card, vertical = DdSpacing.Lg),
            verticalArrangement = Arrangement.spacedBy(DdSpacing.Sm),
        ) {
            DdText(title, style = DdType.heading, color = colors.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                DdText(subtitle, style = DdType.bodySmall, color = colors.inkSecondary)
            }
            content()
            if (footer != null) {
                Spacer(Modifier.height(DdSpacing.Xs))
                DdText(footer, style = DdType.micro, color = colors.inkTertiary)
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
    val colors = deepDeepColors()
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
    val colors = deepDeepColors()
    val route = estimate.route.ifBlank { host.t("本次行程", "This trip") }
    RideCardShell(
        title = route,
        subtitle = host.t("车型与参考价", "Ride options and estimated fares"),
        footer = host.t("参考价，实际以下单页为准", "Estimated fare; the booking page is final."),
    ) {
        DdHairline()
        estimate.items.take(MAX_ESTIMATE_ITEMS).forEachIndexed { index, item ->
            Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                DdText(
                    text = item.productName,
                    modifier = Modifier.weight(1f),
                    style = DdType.body,
                    color = colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(DdSpacing.Sm))
                val price = money(host, item.priceText)
                val discounted = money(host, item.priceDiscounted)
                val text = when {
                    discounted != null && price != null ->
                        host.tf("券后 %s / 参考 %s", "%s after coupon / %s list", discounted, price)

                    discounted != null -> host.tf("券后 %s", "%s after coupon", discounted)
                    price != null -> price
                    else -> host.t("—", "—")
                }
                DdText(text, style = DdType.bodySmall, color = colors.inkSecondary, maxLines = 1)
            }
            if (index < estimate.items.take(MAX_ESTIMATE_ITEMS).lastIndex) DdHairline()
        }
        if (estimate.items.size > MAX_ESTIMATE_ITEMS) {
            DdText(
                host.tf("还有 %d 种车型", "%d more options", estimate.items.size - MAX_ESTIMATE_ITEMS),
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
    val created = state.toolName == "ride_create_order"
    val title = if (created) host.t("订单已创建", "Order created") else host.t("打车订单", "Ride order")
    rideCardError(state)?.let { error ->
        RideErrorCard(host, title, error)
        return
    }
    if (state.result == null) {
        RideLoadingCard(host, title)
        return
    }
    val order = parseRideOrder(rideCardPayload(state))
    if (order.isEmpty) {
        RideEmptyCard(
            host,
            title,
            host.t("没有查询到进行中的订单。", "No active ride order found."),
        )
        return
    }
    val colors = deepDeepColors()
    val route = listOfNotNull(order.fromName, order.toName).joinToString(" → ")
    RideCardShell(
        title = title,
        subtitle = route.ifBlank { null },
        footer = if (created) {
            host.t("支付在滴滴内完成；司机接单后可查询状态和位置。", "Payment happens in Didi. Check status and driver location after a driver accepts.")
        } else {
            null
        },
    ) {
        order.status?.let { status ->
            DdText(
                status,
                style = DdType.body,
                color = colors.ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (order.orderId != null || order.phoneSuffix != null) {
            DdHairline()
            order.orderId?.let { DdStatusRow(label = host.t("订单号", "Order"), value = it, tone = DdStatusTone.NEUTRAL) }
            order.phoneSuffix?.let { DdStatusRow(label = host.t("账号尾号", "Phone suffix"), value = it, tone = DdStatusTone.NEUTRAL) }
        }
        if (order.driver != null || order.plate != null || order.phone != null || order.eta != null) {
            DdHairline()
            order.driver?.let { DdStatusRow(label = host.t("司机", "Driver"), value = it, tone = DdStatusTone.NEUTRAL) }
            order.plate?.let { DdStatusRow(label = host.t("车牌", "Plate"), value = it, tone = DdStatusTone.NEUTRAL) }
            order.phone?.let { DdStatusRow(label = host.t("电话", "Phone"), value = it, tone = DdStatusTone.NEUTRAL) }
            order.eta?.let { DdStatusRow(label = host.t("预计到达", "ETA"), value = it, tone = DdStatusTone.ACCENT) }
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
        location.summary?.let {
            DdText(it, style = DdType.body, color = colors.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        location.coordinates?.let { coordinates ->
            DdStatusRow(label = host.t("坐标", "Coordinates"), value = coordinates, tone = DdStatusTone.NEUTRAL)
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
    val colors = deepDeepColors()
    RideCardShell(title = title, subtitle = host.tf("找到 %d 个地点", "Found %d places", places.size)) {
        DdHairline()
        places.take(MAX_ESTIMATE_ITEMS).forEachIndexed { index, place ->
            Column(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
                DdText(
                    place.displayName ?: host.t("地点", "Place"),
                    style = DdType.body,
                    color = colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                place.address?.takeIf { it != place.name }?.let {
                    DdText(it, style = DdType.micro, color = colors.inkTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                place.coordinates?.let {
                    DdText(it, style = DdType.micro, color = colors.inkTertiary, maxLines = 1)
                }
            }
            if (index < places.take(MAX_ESTIMATE_ITEMS).lastIndex) DdHairline()
        }
    }
}

private fun openRideUrl(host: PluginHost, url: String) {
    runCatching { host.ui().openUrl(url) }.onFailure {
        runCatching {
            host.ui().snackbar(host.t("无法打开链接，请稍后再试", "Could not open the link. Try again."))
        }
    }
}

private const val MAX_ESTIMATE_ITEMS = 6
