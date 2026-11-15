package io.github.whywood.deepdeep.plugin.amap

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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.whywood.deepdeep.design.DdGroupCard
import io.github.whywood.deepdeep.design.DdHairline
import io.github.whywood.deepdeep.design.DdIcon
import io.github.whywood.deepdeep.design.DdIcons
import io.github.whywood.deepdeep.design.DdMotion
import io.github.whywood.deepdeep.design.DdPressable
import io.github.whywood.deepdeep.design.DdShapes
import io.github.whywood.deepdeep.design.DdSpacing
import io.github.whywood.deepdeep.design.DdSpinner
import io.github.whywood.deepdeep.design.DdText
import io.github.whywood.deepdeep.design.DdType
import io.github.whywood.deepdeep.design.LocalDdMotionEnabled
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
    val result = state.result
    when (placesCardPhase(result)) {
        AmapCardPhase.LOADING -> {
            AmapLoadingCard(
                host = host,
                title = if (around) host.t("正在查找附近的地点…", "Finding places nearby…") else host.t("正在查找地点…", "Finding places…"),
                hero = true,
            )
            return
        }
        AmapCardPhase.ERROR -> {
            AmapMessageCard(
                host = host,
                icon = DdIcons.Search,
                title = if (around) host.t("附近查询失败", "Nearby search failed") else host.t("地点查询失败", "Place search failed"),
                message = result?.text.orEmpty().ifBlank { host.t("请稍后再试。", "Try again later.") },
                danger = true,
            )
            return
        }
        AmapCardPhase.EMPTY -> {
            AmapMessageCard(
                host = host,
                icon = DdIcons.Search,
                title = if (around) host.t("附近没有找到地点", "No places nearby") else host.t("没有找到地点", "No places found"),
                message = if (around) {
                    host.t("可以扩大搜索范围，或换个关键词再试。", "Widen the search area or try another keyword.")
                } else {
                    host.t("换个关键词，或补充城市后再试。", "Try another keyword or add a city.")
                },
                danger = false,
            )
            return
        }
        AmapCardPhase.READY -> Unit
    }
    val details = decodePlacesDetails(result?.details)
    val map = decodeMapSpec(result?.details)
    val title = when {
        details.query?.isNotBlank() == true -> details.query.orEmpty()
        around -> host.t("附近的地点", "Nearby places")
        else -> host.t("地点", "Places")
    }
    val count = host.tf("%d 处", "%d places", details.items.size)
    val mapOpen = if (map == null) {
        null
    } else if (around) {
        AmapUri.marker(map.center, details.query)
    } else {
        AmapUri.search(details.query.orEmpty(), details.city)
    }
    val maxRows = if (map != null) 3 else 5
    DdGroupCard(modifier = Modifier.amapCardEntrance()) {
        if (map != null) {
            Box(Modifier.fillMaxWidth()) {
                AmapMapPreview(
                    host = host,
                    spec = map,
                    openUrl = mapOpen,
                    contentDescription = title,
                    shape = RectangleShape,
                )
                Box(Modifier.align(Alignment.BottomStart).padding(DdSpacing.Md)) {
                    MapLabelPill(icon = if (around) DdIcons.Pin else DdIcons.Search, title = title, trailing = count)
                }
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = DdSpacing.Lg, vertical = DdSpacing.Md)) {
            if (map == null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DdIcon(icon = DdIcons.Search, tint = deepDeepColors().accent, size = 15.dp)
                    Spacer(Modifier.width(DdSpacing.Sm))
                    DdText(
                        text = title,
                        modifier = Modifier.weight(1f),
                        style = DdType.label,
                        color = deepDeepColors().ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    DdText(text = count, style = DdType.chip, color = deepDeepColors().inkTertiary, maxLines = 1)
                }
                Spacer(Modifier.height(DdSpacing.Sm))
            }
            details.items.take(maxRows).forEachIndexed { index, item ->
                if (index > 0) DdHairline()
                PlaceRow(host = host, index = index + 1, item = item)
            }
            val remaining = details.items.size - maxRows
            if (remaining > 0) {
                DdHairline()
                DdText(
                    text = host.tf("还有 %d 条", "%d more", remaining),
                    modifier = Modifier.fillMaxWidth().padding(top = DdSpacing.Sm),
                    style = DdType.chip,
                    color = deepDeepColors().inkTertiary,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun MapLabelPill(icon: DdIcon, title: String, trailing: String) {
    val colors = deepDeepColors()
    Row(
        modifier = Modifier
            .clip(DdShapes.Pill)
            .background(colors.surface)
            .padding(horizontal = DdSpacing.Md, vertical = DdSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DdIcon(icon = icon, tint = colors.accent, size = 13.dp)
        Spacer(Modifier.width(DdSpacing.Sm))
        DdText(
            text = title,
            modifier = Modifier.weight(1f, fill = false),
            style = DdType.label,
            color = colors.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(DdSpacing.Xs))
        DdText(text = trailing, style = DdType.chip, color = colors.inkTertiary, maxLines = 1)
    }
}

@Composable
private fun PlaceRow(host: PluginHost, index: Int, item: AmapPlaceItem) {
    val colors = deepDeepColors()
    val location = item.location
    val distance = host.distanceText(item.distanceMeters)
    DdPressable(
        onClick = {
            if (location != null) runCatching { host.ui().openUrl(AmapUri.marker(location, item.name)) }
        },
        modifier = Modifier.fillMaxWidth(),
        shape = DdShapes.Small,
        enabled = location != null,
        contentDescription = item.name,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = DdSpacing.Xs, vertical = DdSpacing.Sm),
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                modifier = Modifier.size(22.dp).clip(CircleShape).background(colors.accentSoft),
                contentAlignment = Alignment.Center,
            ) {
                DdText(text = index.toString(), style = DdType.numeric, color = colors.accent, maxLines = 1)
            }
            Spacer(Modifier.width(DdSpacing.Md))
            Column(Modifier.weight(1f)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    DdText(
                        text = item.name,
                        modifier = Modifier.weight(1f),
                        style = DdType.label,
                        color = colors.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (distance != null) {
                        Spacer(Modifier.width(DdSpacing.Sm))
                        DdText(text = distance, style = DdType.chip, color = colors.inkSecondary, maxLines = 1)
                    }
                }
                val type = item.typeName?.takeIf { it.isNotBlank() }
                val address = item.address?.takeIf { it.isNotBlank() }
                if (type != null || address != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = DdSpacing.Xxs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (type != null) {
                            TypeTag(text = type)
                            if (address != null) Spacer(Modifier.width(DdSpacing.Sm))
                        }
                        if (address != null) {
                            DdText(
                                text = address,
                                modifier = Modifier.weight(1f),
                                style = DdType.chip,
                                color = colors.inkSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TypeTag(text: String) {
    val colors = deepDeepColors()
    Box(
        modifier = Modifier
            .clip(DdShapes.Pill)
            .background(colors.wash)
            .padding(horizontal = DdSpacing.Sm, vertical = 1.dp),
    ) {
        DdText(text = text, style = DdType.chip, color = colors.inkSecondary, maxLines = 1)
    }
}

@Composable
internal fun AmapRegeoCard(host: PluginHost, state: ToolResultState) {
    val result = state.result
    when (regeoCardPhase(result)) {
        AmapCardPhase.LOADING -> {
            AmapLoadingCard(host = host, title = host.t("正在解析位置…", "Resolving the location…"), hero = true)
            return
        }
        AmapCardPhase.ERROR -> {
            AmapMessageCard(
                host = host,
                icon = DdIcons.Pin,
                title = host.t("位置查询失败", "Location lookup failed"),
                message = result?.text.orEmpty().ifBlank { host.t("请稍后再试。", "Try again later.") },
                danger = true,
            )
            return
        }
        AmapCardPhase.EMPTY -> {
            AmapMessageCard(
                host = host,
                icon = DdIcons.Pin,
                title = host.t("没有解析出地址", "No address found"),
                message = host.t("检查经纬度后再试。", "Check the coordinates and try again."),
                danger = false,
            )
            return
        }
        AmapCardPhase.READY -> Unit
    }
    val details = decodeRegeoDetails(result?.details) ?: return
    val colors = deepDeepColors()
    val location = details.location
    val openUrl = location?.let { AmapUri.marker(it, details.formattedAddress) }
    val admin = listOfNotNull(
        details.province?.takeIf { it.isNotBlank() },
        details.city?.takeIf { it.isNotBlank() && it != details.province },
        details.district?.takeIf { it.isNotBlank() },
        details.township?.takeIf { it.isNotBlank() },
    ).joinToString(" · ")
    val map = decodeMapSpec(result?.details)
    DdGroupCard(modifier = Modifier.amapCardEntrance()) {
        Column(Modifier.fillMaxWidth().padding(DdSpacing.Lg)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DdIcon(icon = DdIcons.Pin, tint = colors.accent, size = 13.dp)
                Spacer(Modifier.width(DdSpacing.Sm))
                DdText(
                    text = host.t("位置", "Location"),
                    style = DdType.micro,
                    color = colors.inkTertiary,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.height(DdSpacing.Sm))
            DdText(
                text = details.formattedAddress,
                style = DdType.heading,
                color = colors.ink,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            if (admin.isNotBlank()) {
                Spacer(Modifier.height(DdSpacing.Xs))
                DdText(
                    text = admin,
                    style = DdType.bodySmall,
                    color = colors.inkSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (location != null) {
                Spacer(Modifier.height(DdSpacing.Sm))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DdText(
                        text = host.t("坐标", "Coordinates"),
                        style = DdType.micro,
                        color = colors.inkTertiary,
                        maxLines = 1,
                    )
                    Spacer(Modifier.width(DdSpacing.Sm))
                    DdText(
                        text = location.display,
                        style = DdType.numeric,
                        color = colors.inkTertiary,
                        maxLines = 1,
                    )
                }
            }
            if (map != null) {
                Spacer(Modifier.height(DdSpacing.Md))
                AmapMapPreview(
                    host = host,
                    spec = map,
                    openUrl = openUrl,
                    contentDescription = details.formattedAddress,
                )
            }
            if (openUrl != null) {
                Spacer(Modifier.height(DdSpacing.Md))
                DdHairline()
                AmapOpenMapsAction(host = host, url = openUrl)
            }
        }
    }
}

@Composable
internal fun AmapRouteCard(host: PluginHost, state: ToolResultState) {
    val result = state.result
    when (routeCardPhase(result)) {
        AmapCardPhase.LOADING -> {
            AmapLoadingCard(host = host, title = host.t("正在规划路线…", "Planning the route…"), hero = false)
            return
        }
        AmapCardPhase.ERROR -> {
            AmapMessageCard(
                host = host,
                icon = DdIcons.Target,
                title = host.t("路线规划失败", "Route planning failed"),
                message = result?.text.orEmpty().ifBlank { host.t("请稍后再试。", "Try again later.") },
                danger = true,
            )
            return
        }
        AmapCardPhase.EMPTY -> {
            AmapMessageCard(
                host = host,
                icon = DdIcons.Target,
                title = host.t("没有规划出路线", "No route found"),
                message = host.t("检查起点和终点后再试。", "Check the start and destination and try again."),
                danger = false,
            )
            return
        }
        AmapCardPhase.READY -> Unit
    }
    val details = decodeRouteDetails(result?.details) ?: return
    val colors = deepDeepColors()
    val distance = host.distanceText(details.distanceMeters)
    val duration = host.durationText(details.durationSeconds)
    val legs = routeLegs(host, details)
    val totalSegments = if (details.transitLines.isNotEmpty()) details.transitLines.size else details.steps.size
    val truncated = totalSegments > MAX_ROUTE_SEGMENTS
    val navigationUrl = details.navigationUrl?.takeIf { it.isNotBlank() }
    DdGroupCard(modifier = Modifier.amapCardEntrance()) {
        Column(Modifier.fillMaxWidth().padding(DdSpacing.Lg)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ModePill(host = host, mode = details.mode)
                Spacer(Modifier.weight(1f))
                if (distance != null) {
                    DdText(text = distance, style = DdType.chip, color = colors.inkSecondary, maxLines = 1)
                }
            }
            if (duration != null) {
                Spacer(Modifier.height(DdSpacing.Md))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DdText(
                        text = host.t("约", "About"),
                        style = DdType.bodySmall,
                        color = colors.inkSecondary,
                        maxLines = 1,
                    )
                    Spacer(Modifier.width(DdSpacing.Xs))
                    DdText(
                        text = duration,
                        style = DdType.heading,
                        color = colors.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.height(DdSpacing.Md))
            legs.forEachIndexed { index, leg ->
                TimelineLeg(
                    leg = leg,
                    first = index == 0,
                    last = index == legs.lastIndex,
                )
            }
            if (truncated) {
                DdText(
                    text = host.tf("还有 %d 段", "%d more segments", totalSegments - MAX_ROUTE_SEGMENTS),
                    modifier = Modifier.padding(start = 22.dp, bottom = DdSpacing.Sm),
                    style = DdType.chip,
                    color = colors.inkTertiary,
                    maxLines = 1,
                )
            }
            if (navigationUrl != null) {
                DdHairline()
                AmapOpenMapsAction(host = host, url = navigationUrl)
            }
        }
    }
}

private enum class RouteLegKind { ORIGIN, STEP, DESTINATION }

private data class RouteLeg(
    val title: String,
    val meta: String?,
    val kind: RouteLegKind,
)

private fun routeLegs(host: PluginHost, details: AmapRouteDetails): List<RouteLeg> {
    val originMeta = host.t("起点", "Start")
    val destinationMeta = host.t("终点", "Destination")
    val origin = RouteLeg(
        title = details.originName?.takeIf { it.isNotBlank() }
            ?: details.origin?.display
            ?: host.t("起点", "Start"),
        meta = originMeta,
        kind = RouteLegKind.ORIGIN,
    )
    val destination = RouteLeg(
        title = details.destinationName?.takeIf { it.isNotBlank() }
            ?: details.destination?.display
            ?: host.t("终点", "Destination"),
        meta = destinationMeta,
        kind = RouteLegKind.DESTINATION,
    )
    val segments = if (details.transitLines.isNotEmpty()) {
        details.transitLines.map { line ->
            val walking = line.walkingMeters?.takeIf { it > 0 }
            RouteLeg(
                title = line.lineName?.takeIf { it.isNotBlank() } ?: host.t("步行", "Walk"),
                meta = walking?.let { host.tf("步行 %d 米", "Walk %d m", it) },
                kind = RouteLegKind.STEP,
            )
        }
    } else {
        details.steps.map { step ->
            val meta = listOfNotNull(
                step.road?.takeIf { it.isNotBlank() },
                host.distanceText(step.distanceMeters),
            ).joinToString(" · ")
            RouteLeg(
                title = step.instruction,
                meta = meta.takeIf { it.isNotBlank() },
                kind = RouteLegKind.STEP,
            )
        }
    }
    return buildList {
        add(origin)
        addAll(segments.take(MAX_ROUTE_SEGMENTS))
        add(destination)
    }
}

private const val MAX_ROUTE_SEGMENTS = 4

@Composable
private fun ModePill(host: PluginHost, mode: AmapRouteMode) {
    val colors = deepDeepColors()
    Box(
        modifier = Modifier
            .clip(DdShapes.Pill)
            .background(colors.accentSoft)
            .padding(horizontal = DdSpacing.Md, vertical = DdSpacing.Xxs),
    ) {
        DdText(text = host.routeModeLabel(mode), style = DdType.chip, color = colors.accent, maxLines = 1)
    }
}

private fun PluginHost.routeModeLabel(mode: AmapRouteMode): String = when (mode) {
    AmapRouteMode.DRIVING -> t("驾车", "Driving")
    AmapRouteMode.TRANSIT -> t("公交", "Transit")
    AmapRouteMode.WALKING -> t("步行", "Walking")
    AmapRouteMode.BICYCLING -> t("骑行", "Cycling")
}

@Composable
private fun TimelineLeg(leg: RouteLeg, first: Boolean, last: Boolean) {
    val colors = deepDeepColors()
    val ring = when (leg.kind) {
        RouteLegKind.ORIGIN -> colors.success
        RouteLegKind.DESTINATION -> colors.danger
        RouteLegKind.STEP -> colors.inkTertiary
    }
    val strong = leg.kind != RouteLegKind.STEP
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Canvas(Modifier.width(12.dp).fillMaxHeight()) {
            val centerX = size.width / 2f
            val centerY = 9.dp.toPx()
            val radius = if (strong) 5.dp.toPx() else 4.dp.toPx()
            val stroke = 2.dp.toPx()
            if (!first) {
                drawLine(
                    color = colors.hairline,
                    start = Offset(centerX, 0f),
                    end = Offset(centerX, centerY - radius),
                    strokeWidth = stroke,
                )
            }
            if (!last) {
                drawLine(
                    color = colors.hairline,
                    start = Offset(centerX, centerY + radius),
                    end = Offset(centerX, size.height),
                    strokeWidth = stroke,
                )
            }
            drawCircle(color = colors.surface, radius = radius, center = Offset(centerX, centerY))
            drawCircle(
                color = ring,
                radius = radius,
                center = Offset(centerX, centerY),
                style = Stroke(width = stroke),
            )
        }
        Spacer(Modifier.width(DdSpacing.Md))
        Column(Modifier.weight(1f).padding(bottom = DdSpacing.Md)) {
            DdText(
                text = leg.title,
                style = if (strong) DdType.label else DdType.bodySmall,
                color = colors.ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            leg.meta?.let { meta ->
                Spacer(Modifier.height(1.dp))
                DdText(
                    text = meta,
                    style = DdType.chip,
                    color = colors.inkSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
internal fun AmapWeatherCard(host: PluginHost, state: ToolResultState) {
    val result = state.result
    when (weatherCardPhase(result)) {
        AmapCardPhase.LOADING -> {
            AmapLoadingCard(host = host, title = host.t("正在获取天气…", "Loading the forecast…"), hero = false)
            return
        }
        AmapCardPhase.ERROR -> {
            AmapMessageCard(
                host = host,
                icon = DdIcons.Sun,
                title = host.t("天气查询失败", "Weather lookup failed"),
                message = result?.text.orEmpty().ifBlank { host.t("请稍后再试。", "Try again later.") },
                danger = true,
            )
            return
        }
        AmapCardPhase.EMPTY -> {
            AmapMessageCard(
                host = host,
                icon = DdIcons.Sun,
                title = host.t("没有获取到天气", "No weather found"),
                message = host.t("稍后再试，或换个城市看看。", "Try again later or check another city."),
                danger = false,
            )
            return
        }
        AmapCardPhase.READY -> Unit
    }
    val details = decodeWeatherDetails(result?.details) ?: return
    val colors = deepDeepColors()
    val current = details.days.first()
    val future = details.days.drop(1).take(MAX_FUTURE_DAYS)
    val bigTemp = current.dayTemp ?: current.nightTemp
    DdGroupCard(modifier = Modifier.amapCardEntrance()) {
        Column(Modifier.fillMaxWidth().padding(DdSpacing.Lg)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DdText(
                    text = details.city?.takeIf { it.isNotBlank() } ?: host.t("天气", "Weather"),
                    modifier = Modifier.weight(1f),
                    style = DdType.label,
                    color = colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                DdText(
                    text = host.tf("未来 %d 天", "%d-day forecast", details.days.size),
                    style = DdType.chip,
                    color = colors.inkTertiary,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.height(DdSpacing.Sm))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.Top) {
                    DdText(
                        text = bigTemp?.toString() ?: "--",
                        style = DdType.display,
                        color = colors.ink,
                        maxLines = 1,
                    )
                    if (bigTemp != null) {
                        DdText(text = "°", style = DdType.heading, color = colors.ink, maxLines = 1)
                    }
                }
                Column(Modifier.padding(bottom = DdSpacing.Xs), horizontalAlignment = Alignment.End) {
                    DdText(
                        text = weatherLine(host, current),
                        style = DdType.label,
                        color = colors.ink,
                        maxLines = 1,
                    )
                    Spacer(Modifier.height(1.dp))
                    DdText(
                        text = temperatureLine(current),
                        style = DdType.chip,
                        color = colors.inkSecondary,
                        maxLines = 1,
                    )
                    windLine(host, current).takeIf { it.isNotBlank() }?.let { wind ->
                        Spacer(Modifier.height(1.dp))
                        DdText(
                            text = wind,
                            style = DdType.chip,
                            color = colors.inkSecondary,
                            maxLines = 1,
                        )
                    }
                }
            }
            if (future.isNotEmpty()) {
                Spacer(Modifier.height(DdSpacing.Md))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(DdShapes.Medium)
                        .background(colors.wash)
                        .padding(vertical = DdSpacing.Sm, horizontal = DdSpacing.Xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    future.forEachIndexed { index, day ->
                        if (index > 0) {
                            Box(Modifier.width(1.dp).height(34.dp).background(colors.hairline))
                        }
                        Column(
                            modifier = Modifier.weight(1f),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(1.dp),
                        ) {
                            DdText(
                                text = shortDateLabel(day.date) ?: "",
                                style = DdType.chip,
                                color = colors.inkTertiary,
                                maxLines = 1,
                            )
                            DdText(
                                text = weatherLine(host, day),
                                style = DdType.chip,
                                color = colors.inkSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            DdText(
                                text = temperatureLine(day),
                                style = DdType.numeric,
                                color = colors.ink,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(DdSpacing.Sm))
            DdText(
                text = host.t("预报仅供参考", "Forecast for reference only"),
                style = DdType.chip,
                color = colors.inkTertiary,
                maxLines = 1,
            )
        }
    }
}

private const val MAX_FUTURE_DAYS = 4

@Composable
private fun weatherLine(host: PluginHost, day: AmapWeatherDay): String {
    val dayWeather = day.dayWeather?.takeIf { it.isNotBlank() }
    val nightWeather = day.nightWeather?.takeIf { it.isNotBlank() && it != dayWeather }
    return when {
        dayWeather != null && nightWeather != null -> host.tf("%s转%s", "%s to %s", dayWeather, nightWeather)
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
private fun AmapOpenMapsAction(host: PluginHost, url: String) {
    val colors = deepDeepColors()
    val label = host.t("在高德地图中打开", "Open in Amap Maps")
    DdPressable(
        onClick = { runCatching { host.ui().openUrl(url) } },
        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
        shape = DdShapes.Small,
        contentDescription = label,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                DdText(text = label, style = DdType.label, color = colors.accent, maxLines = 1)
                Spacer(Modifier.weight(1f))
                DdIcon(icon = DdIcons.External, tint = colors.accent, size = 15.dp)
            }
        }
    }
}

@Composable
private fun AmapLoadingCard(host: PluginHost, title: String, hero: Boolean) {
    val colors = deepDeepColors()
    DdGroupCard(modifier = Modifier.amapCardEntrance()) {
        if (hero) {
            Box(
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(colors.wash),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    DdSpinner(color = colors.inkTertiary, size = 16.dp, strokeWidth = 1.5.dp)
                    Spacer(Modifier.height(DdSpacing.Sm))
                    DdText(text = title, style = DdType.chip, color = colors.inkSecondary, maxLines = 1)
                }
            }
        }
        Column(Modifier.fillMaxWidth().padding(DdSpacing.Lg)) {
            if (!hero) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DdSpinner(color = colors.inkTertiary, size = 14.dp, strokeWidth = 1.5.dp)
                    Spacer(Modifier.width(DdSpacing.Sm))
                    DdText(text = title, style = DdType.chip, color = colors.inkSecondary, maxLines = 1)
                }
                Spacer(Modifier.height(DdSpacing.Md))
            }
            Box(Modifier.fillMaxWidth(0.72f).height(13.dp).clip(DdShapes.Small).background(colors.wash))
            Spacer(Modifier.height(DdSpacing.Sm))
            Box(Modifier.fillMaxWidth(0.46f).height(11.dp).clip(DdShapes.Small).background(colors.wash))
        }
    }
}

@Composable
private fun AmapMessageCard(
    host: PluginHost,
    icon: DdIcon,
    title: String,
    message: String,
    danger: Boolean,
) {
    val colors = deepDeepColors()
    DdGroupCard(modifier = Modifier.amapCardEntrance()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(DdSpacing.Lg),
            verticalArrangement = Arrangement.spacedBy(DdSpacing.Sm),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DdIcon(icon = icon, tint = if (danger) colors.danger else colors.accent, size = 15.dp)
                Spacer(Modifier.width(DdSpacing.Sm))
                DdText(
                    text = title,
                    modifier = Modifier.weight(1f),
                    style = DdType.label,
                    color = colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            DdText(
                text = message,
                style = DdType.bodySmall,
                color = colors.inkSecondary,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun Modifier.amapCardEntrance(): Modifier {
    val motion = LocalDdMotionEnabled.current
    var appeared by remember { mutableStateOf(!motion) }
    val progress by animateFloatAsState(
        targetValue = if (appeared) 1f else 0f,
        animationSpec = tween(durationMillis = DdMotion.FAST, easing = DdMotion.EaseOut),
        label = "amap-card-entrance",
    )
    LaunchedEffect(Unit) { appeared = true }
    return graphicsLayer {
        alpha = progress
        translationY = (1f - progress) * 8.dp.toPx()
    }
}
