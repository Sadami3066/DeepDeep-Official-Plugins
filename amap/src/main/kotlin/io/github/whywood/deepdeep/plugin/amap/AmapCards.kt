package io.github.whywood.deepdeep.plugin.amap

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import io.github.whywood.deepdeep.design.DdIcon
import io.github.whywood.deepdeep.design.DdIcons
import io.github.whywood.deepdeep.design.DdPressable
import io.github.whywood.deepdeep.design.DdSpacing
import io.github.whywood.deepdeep.design.DdSpinner
import io.github.whywood.deepdeep.design.DdText
import io.github.whywood.deepdeep.design.DdTextAction
import io.github.whywood.deepdeep.design.DdType
import io.github.whywood.deepdeep.design.deepDeepColors
import io.github.whywood.deepdeep.plugin.api.PluginHost
import io.github.whywood.deepdeep.plugin.api.PluginToolResult
import io.github.whywood.deepdeep.plugin.api.ToolResultState

internal enum class AmapCardPhase { LOADING, ERROR, EMPTY, READY }

internal fun placesCardPhase(result: PluginToolResult?): AmapCardPhase = when {
    result == null -> AmapCardPhase.LOADING
    result.isError -> AmapCardPhase.ERROR
    decodePlacesDetails(result.details).items.isEmpty() -> AmapCardPhase.EMPTY
    else -> AmapCardPhase.READY
}

internal fun regeoCardPhase(result: PluginToolResult?): AmapCardPhase = when {
    result == null -> AmapCardPhase.LOADING
    result.isError -> AmapCardPhase.ERROR
    decodeRegeoDetails(result.details) == null -> AmapCardPhase.EMPTY
    else -> AmapCardPhase.READY
}

internal fun routeCardPhase(result: PluginToolResult?): AmapCardPhase = when {
    result == null -> AmapCardPhase.LOADING
    result.isError -> AmapCardPhase.ERROR
    decodeRouteDetails(result.details) == null -> AmapCardPhase.EMPTY
    else -> AmapCardPhase.READY
}

internal fun weatherCardPhase(result: PluginToolResult?): AmapCardPhase = when {
    result == null -> AmapCardPhase.LOADING
    result.isError -> AmapCardPhase.ERROR
    decodeWeatherDetails(result.details)?.days?.isEmpty() != false -> AmapCardPhase.EMPTY
    else -> AmapCardPhase.READY
}

@Composable
internal fun AmapPlacesCard(host: PluginHost, state: ToolResultState, around: Boolean) {
    val fallbackTitle = if (around) host.t("周边搜索", "Nearby") else host.t("地点搜索", "Place search")
    val result = state.result
    when (placesCardPhase(result)) {
        AmapCardPhase.LOADING -> {
            AmapLoadingCard(host, fallbackTitle, DdIcons.Search)
            return
        }
        AmapCardPhase.ERROR -> {
            AmapErrorCard(host, fallbackTitle, result?.text)
            return
        }
        AmapCardPhase.EMPTY -> {
            AmapEmptyCard(
                host = host,
                title = fallbackTitle,
                message = host.t(
                    "没有找到相关地点，换个关键词或扩大范围再试。",
                    "No places found. Try another keyword or a wider range.",
                ),
                icon = DdIcons.Search,
            )
            return
        }
        AmapCardPhase.READY -> Unit
    }
    val details = decodePlacesDetails(result?.details)
    val title = if (around) {
        listOfNotNull(host.t("周边", "Nearby"), details.query?.takeIf { it.isNotBlank() }).joinToString(" · ")
    } else {
        details.query?.takeIf { it.isNotBlank() } ?: fallbackTitle
    }
    val trailing = details.city?.takeIf { it.isNotBlank() }
        ?: host.tf("%d 个", "%d", details.items.size)
    AmapCardShell(title = title, icon = DdIcons.Search, trailing = trailing) {
        val map = decodeMapSpec(result?.details)
        if (map != null) {
            val mapOpen = if (around) {
                AmapUri.marker(map.center, details.query)
            } else {
                AmapUri.search(details.query.orEmpty(), details.city)
            }
            AmapMapPreview(
                host = host,
                spec = map,
                openUrl = mapOpen,
                contentDescription = title,
            )
            Spacer(Modifier.height(DdSpacing.Sm))
        }
        details.items.take(6).forEachIndexed { index, item ->
            if (index > 0) DdHairline()
            AmapPlaceRow(host, item)
        }
    }
}

@Composable
private fun AmapPlaceRow(host: PluginHost, item: AmapPlaceItem) {
    val colors = deepDeepColors()
    val location = item.location
    val open: (() -> Unit)? = if (location != null) {
        { runCatching { host.ui().openUrl(AmapUri.marker(location, item.name)) } }
    } else {
        null
    }
    DdPressable(
        onClick = open ?: {},
        modifier = Modifier.fillMaxWidth(),
        shape = RectangleShape,
        enabled = open != null,
        contentDescription = item.name,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = DdSpacing.Sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                DdText(
                    text = item.name,
                    style = DdType.body,
                    color = colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val meta = listOfNotNull(
                    item.typeName?.takeIf { it.isNotBlank() },
                    host.distanceText(item.distanceMeters),
                ).joinToString(" · ")
                if (meta.isNotBlank()) {
                    DdText(meta, style = DdType.bodySmall, color = colors.inkSecondary, maxLines = 1)
                }
                item.address?.takeIf { it.isNotBlank() }?.let { address ->
                    DdText(
                        text = address,
                        style = DdType.chip,
                        color = colors.inkTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (open != null) {
                Spacer(Modifier.width(DdSpacing.Sm))
                DdIcon(icon = DdIcons.ChevronRight, tint = colors.inkTertiary, size = 14.dp)
            }
        }
    }
}

@Composable
internal fun AmapRegeoCard(host: PluginHost, state: ToolResultState) {
    val title = host.t("位置", "Location")
    val result = state.result
    when (regeoCardPhase(result)) {
        AmapCardPhase.LOADING -> {
            AmapLoadingCard(host, title, DdIcons.Pin)
            return
        }
        AmapCardPhase.ERROR -> {
            AmapErrorCard(host, title, result?.text)
            return
        }
        AmapCardPhase.EMPTY -> {
            AmapEmptyCard(
                host = host,
                title = title,
                message = host.t("没有解析出地址，请检查经纬度。", "No address found. Check the coordinates."),
                icon = DdIcons.Pin,
            )
            return
        }
        AmapCardPhase.READY -> Unit
    }
    val details = decodeRegeoDetails(result?.details) ?: return
    val meta = listOfNotNull(
        details.province?.takeIf { it.isNotBlank() },
        details.city?.takeIf { it.isNotBlank() && it != details.province },
        details.district?.takeIf { it.isNotBlank() },
        details.township?.takeIf { it.isNotBlank() },
    ).joinToString(" · ")
    val location = details.location
    val footer: (@Composable () -> Unit)? = if (location != null) {
        {
            DdTextAction(
                text = host.t("在高德地图中打开", "Open in Amap Maps"),
                onClick = { runCatching { host.ui().openUrl(AmapUri.marker(location, details.formattedAddress)) } },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    } else {
        null
    }
    val openUrl = location?.let { AmapUri.marker(it, details.formattedAddress) }
    AmapCardShell(
        title = title,
        icon = DdIcons.Pin,
        footer = footer,
    ) {
        decodeMapSpec(result?.details)?.let { map ->
            AmapMapPreview(
                host = host,
                spec = map,
                openUrl = openUrl,
                contentDescription = title,
            )
            Spacer(Modifier.height(DdSpacing.Sm))
        }
        DdText(
            text = details.formattedAddress,
            style = DdType.body,
            color = deepDeepColors().ink,
        )
        if (meta.isNotBlank()) {
            DdText(meta, style = DdType.bodySmall, color = deepDeepColors().inkSecondary)
        }
    }
}

@Composable
internal fun AmapRouteCard(host: PluginHost, state: ToolResultState) {
    val fallbackTitle = host.t("路线", "Route")
    val result = state.result
    when (routeCardPhase(result)) {
        AmapCardPhase.LOADING -> {
            AmapLoadingCard(host, fallbackTitle, DdIcons.Target)
            return
        }
        AmapCardPhase.ERROR -> {
            AmapErrorCard(host, fallbackTitle, result?.text)
            return
        }
        AmapCardPhase.EMPTY -> {
            AmapEmptyCard(
                host = host,
                title = fallbackTitle,
                message = host.t("没有规划出路线，请检查起终点。", "No route found. Check the start and end points."),
                icon = DdIcons.Target,
            )
            return
        }
        AmapCardPhase.READY -> Unit
    }
    val details = decodeRouteDetails(result?.details) ?: return
    val summary = listOfNotNull(
        host.distanceText(details.distanceMeters),
        host.durationText(details.durationSeconds),
    ).joinToString(" · ")
    val navigationUrl = details.navigationUrl?.takeIf { it.isNotBlank() }
    val footer: (@Composable () -> Unit)? = if (navigationUrl != null) {
        {
            DdTextAction(
                text = host.t("在高德地图中打开", "Open in Amap Maps"),
                onClick = { runCatching { host.ui().openUrl(navigationUrl) } },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    } else {
        null
    }
    AmapCardShell(
        title = host.routeTitle(details.mode),
        icon = DdIcons.Target,
        trailing = summary.takeIf { it.isNotBlank() },
        footer = footer,
    ) {
        decodeMapSpec(result?.details)?.let { map ->
            AmapMapPreview(
                host = host,
                spec = map,
                openUrl = navigationUrl,
                contentDescription = host.routeTitle(details.mode),
            )
            Spacer(Modifier.height(DdSpacing.Sm))
        }
        val routeNames = listOfNotNull(
            details.originName?.takeIf { it.isNotBlank() },
            details.destinationName?.takeIf { it.isNotBlank() },
        )
        if (routeNames.size == 2) {
            DdText(
                text = "${routeNames[0]} → ${routeNames[1]}",
                style = DdType.bodySmall,
                color = deepDeepColors().inkSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (details.transitLines.isNotEmpty()) {
            details.transitLines.take(5).forEachIndexed { index, line ->
                if (index > 0) DdHairline()
                DdText(
                    text = transitLineText(host, line),
                    style = DdType.bodySmall,
                    color = deepDeepColors().ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        } else if (details.steps.isEmpty()) {
            DdText(
                text = host.t("已规划路线，详情可在高德地图中查看。", "Route ready. Open it in Amap Maps for details."),
                style = DdType.bodySmall,
                color = deepDeepColors().inkSecondary,
            )
        } else {
            details.steps.take(4).forEachIndexed { index, step ->
                if (index > 0) DdHairline()
                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    DdText(
                        text = step.instruction,
                        style = DdType.bodySmall,
                        color = deepDeepColors().ink,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val meta = listOfNotNull(
                        step.road?.takeIf { it.isNotBlank() },
                        host.distanceText(step.distanceMeters),
                    ).joinToString(" · ")
                    if (meta.isNotBlank()) {
                        DdText(meta, style = DdType.chip, color = deepDeepColors().inkTertiary, maxLines = 1)
                    }
                }
            }
        }
    }
}

private fun transitLineText(host: PluginHost, line: AmapTransitLine): String {
    val name = line.lineName?.takeIf { it.isNotBlank() }
    val walking = line.walkingMeters?.takeIf { it > 0 }
    return when {
        name != null && walking != null -> host.tf(
            "%s · 步行 %d 米",
            "%s · walk %d m",
            name,
            walking,
        )
        name != null -> name
        walking != null -> host.tf("步行 %d 米", "Walk %d m", walking)
        else -> host.t("步行", "Walk")
    }
}

@Composable
internal fun AmapWeatherCard(host: PluginHost, state: ToolResultState) {
    val fallbackTitle = host.t("天气", "Weather")
    val result = state.result
    when (weatherCardPhase(result)) {
        AmapCardPhase.LOADING -> {
            AmapLoadingCard(host, fallbackTitle, DdIcons.Sun)
            return
        }
        AmapCardPhase.ERROR -> {
            AmapErrorCard(host, fallbackTitle, result?.text)
            return
        }
        AmapCardPhase.EMPTY -> {
            AmapEmptyCard(
                host = host,
                title = fallbackTitle,
                message = host.t("没有获取到天气，请稍后重试。", "Weather is not available. Try again later."),
                icon = DdIcons.Sun,
            )
            return
        }
        AmapCardPhase.READY -> Unit
    }
    val details = decodeWeatherDetails(result?.details) ?: return
    AmapCardShell(
        title = listOfNotNull(
            host.t("天气", "Weather"),
            details.city?.takeIf { it.isNotBlank() },
        ).joinToString(" · "),
        icon = DdIcons.Sun,
        trailing = host.tf("%d 天", "%d days", details.days.size),
        footer = {
            DdText(
                text = host.t("预报仅供参考。", "Forecast is for reference only."),
                style = DdType.micro,
                color = deepDeepColors().inkTertiary,
            )
        },
    ) {
        details.days.take(5).forEachIndexed { index, day ->
            if (index > 0) DdHairline()
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = DdSpacing.Xs),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DdText(
                        text = shortDateLabel(day.date) ?: host.t("今天", "Today"),
                        modifier = Modifier.width(52.dp),
                        style = DdType.bodySmall,
                        color = deepDeepColors().inkSecondary,
                    )
                    DdText(
                        text = weatherLine(host, day),
                        modifier = Modifier.weight(1f),
                        style = DdType.bodySmall,
                        color = deepDeepColors().ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    DdText(
                        text = temperatureLine(day),
                        style = DdType.bodySmall,
                        color = deepDeepColors().ink,
                        maxLines = 1,
                    )
                }
                val wind = windLine(host, day)
                if (wind.isNotBlank()) {
                    DdText(
                        text = wind,
                        modifier = Modifier.padding(start = 52.dp),
                        style = DdType.chip,
                        color = deepDeepColors().inkTertiary,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

private fun weatherLine(host: PluginHost, day: AmapWeatherDay): String {
    val dayWeather = day.dayWeather?.takeIf { it.isNotBlank() }
    val nightWeather = day.nightWeather?.takeIf { it.isNotBlank() && it != dayWeather }
    return when {
        dayWeather != null && nightWeather != null -> host.tf("%s 转 %s", "%s to %s", dayWeather, nightWeather)
        dayWeather != null -> dayWeather
        nightWeather != null -> nightWeather
        else -> host.t("暂无天气", "No data")
    }
}

private fun temperatureLine(day: AmapWeatherDay): String {
    val low = day.nightTemp
    val high = day.dayTemp
    return when {
        low != null && high != null -> "$low°~$high°"
        high != null -> "$high°"
        low != null -> "$low°"
        else -> "--"
    }
}

private fun windLine(host: PluginHost, day: AmapWeatherDay): String {
    val direction = day.dayWind?.takeIf { it.isNotBlank() }
    val power = day.dayPower?.takeIf { it.isNotBlank() }
    return when {
        direction != null && power != null -> host.tf("%s %s", "%s %s", direction, power)
        direction != null -> direction
        power != null -> power
        else -> ""
    }
}

@Composable
private fun AmapCardShell(
    title: String,
    icon: DdIcon,
    trailing: String? = null,
    footer: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = deepDeepColors()
    DdGroupCard {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = DdSpacing.Card, vertical = DdSpacing.Md),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                DdIcon(icon = icon, tint = colors.accent, size = 15.dp)
                Spacer(Modifier.width(DdSpacing.Sm))
                DdText(
                    text = title,
                    modifier = Modifier.weight(1f),
                    style = DdType.label,
                    color = colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!trailing.isNullOrBlank()) {
                    Spacer(Modifier.width(DdSpacing.Sm))
                    DdText(
                        text = trailing,
                        style = DdType.chip,
                        color = colors.inkTertiary,
                        maxLines = 1,
                    )
                }
            }
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = DdSpacing.Sm),
                verticalArrangement = Arrangement.spacedBy(DdSpacing.Xs),
                content = content,
            )
            if (footer != null) {
                DdHairline(Modifier.padding(top = DdSpacing.Md))
                Column(Modifier.fillMaxWidth().padding(top = DdSpacing.Xs)) { footer() }
            }
        }
    }
}

@Composable
private fun AmapLoadingCard(host: PluginHost, title: String, icon: DdIcon) {
    AmapCardShell(title = title, icon = icon) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DdSpinner(color = deepDeepColors().inkTertiary, size = 14.dp, strokeWidth = 1.5.dp)
            Spacer(Modifier.width(DdSpacing.Sm))
            DdText(
                text = host.t("正在查询…", "Looking up…"),
                style = DdType.bodySmall,
                color = deepDeepColors().inkSecondary,
            )
        }
    }
}

@Composable
private fun AmapErrorCard(host: PluginHost, title: String, error: String?) {
    AmapCardShell(
        title = title,
        icon = DdIcons.Info,
        footer = {
            DdText(
                text = host.t("可以稍后在对话里重试。", "Try again in the chat later."),
                style = DdType.micro,
                color = deepDeepColors().inkTertiary,
            )
        },
    ) {
        DdText(
            text = error?.takeIf { it.isNotBlank() } ?: host.t("查询失败，请稍后重试。", "Lookup failed. Try again later."),
            style = DdType.bodySmall,
            color = deepDeepColors().inkSecondary,
        )
    }
}

@Composable
private fun AmapEmptyCard(host: PluginHost, title: String, message: String, icon: DdIcon) {
    AmapCardShell(title = title, icon = icon) {
        DdText(
            text = message,
            style = DdType.bodySmall,
            color = deepDeepColors().inkSecondary,
        )
    }
}

