package io.github.whywood.superpi.plugin.amap

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.whywood.superpi.design.DdExpandToggle
import io.github.whywood.superpi.design.DdGroupCard
import io.github.whywood.superpi.design.DdHairline
import io.github.whywood.superpi.design.DdIcon
import io.github.whywood.superpi.design.DdIcons
import io.github.whywood.superpi.design.DdImageViewer
import io.github.whywood.superpi.design.DdMotion
import io.github.whywood.superpi.design.DdPressable
import io.github.whywood.superpi.design.DdRemoteImage
import io.github.whywood.superpi.design.DdShapes
import io.github.whywood.superpi.design.DdSpacing
import io.github.whywood.superpi.design.DdSpinner
import io.github.whywood.superpi.design.DdText
import io.github.whywood.superpi.design.DdType
import io.github.whywood.superpi.design.LocalDdMotionEnabled
import io.github.whywood.superpi.design.superPiColors
import io.github.whywood.superpi.plugin.api.PluginHost
import io.github.whywood.superpi.plugin.api.PluginToolResult
import io.github.whywood.superpi.plugin.api.ToolResultState
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

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
    val colors = superPiColors()
    val map = decodeMapSpec(result?.details)
    val navigationUrl = details.navigationUrl?.takeIf { it.isNotBlank() }
    val paths = details.allPaths
    var selectedIndex by remember(details) { mutableStateOf(0) }
    val selected = paths.getOrNull(selectedIndex) ?: paths.firstOrNull() ?: return
    var segmentsExpanded by remember(details, selectedIndex) { mutableStateOf(false) }
    val distance = host.distanceText(selected.distanceMeters)
    val duration = host.durationText(selected.durationSeconds)
    val walking = host.distanceText(selected.walkingDistanceMeters)
    val allLegs = routeLegs(host = host, details = details, path = selected)
    val movement = allLegs.drop(1).dropLast(1)
    val segmentCount = movement.size
    val legs = if (segmentsExpanded) {
        allLegs
    } else {
        listOf(allLegs.first()) + movement.take(ROUTE_PREVIEW_SEGMENTS) + listOf(allLegs.last())
    }
    DdGroupCard(modifier = Modifier.amapCardEntrance()) {
        if (map != null) {
            AmapMapPreview(
                host = host,
                spec = map,
                openUrl = navigationUrl,
                contentDescription = host.t("路线地图", "Route map"),
                shape = RectangleShape,
            )
        }
        Column(Modifier.fillMaxWidth().padding(DdSpacing.Lg)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ModePill(host = host, mode = details.mode)
                Spacer(Modifier.weight(1f))
                if (walking != null) {
                    DdText(
                        text = host.tf("步行 %s", "walk %s", walking),
                        style = DdType.chip,
                        color = colors.inkTertiary,
                        maxLines = 1,
                    )
                    Spacer(Modifier.width(DdSpacing.Sm))
                }
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
            if (paths.size > 1) {
                Spacer(Modifier.height(DdSpacing.Md))
                RoutePathTabs(
                    host = host,
                    paths = paths,
                    selectedIndex = selectedIndex,
                    onSelect = { selectedIndex = it },
                )
            }
            Spacer(Modifier.height(DdSpacing.Md))
            legs.forEachIndexed { index, leg ->
                TimelineLeg(
                    leg = leg,
                    first = index == 0,
                    last = index == legs.lastIndex,
                    expanded = segmentsExpanded,
                )
            }
            if (segmentCount > ROUTE_PREVIEW_SEGMENTS) {
                DdExpandToggle(
                    expanded = segmentsExpanded,
                    collapsedLabel = if (details.mode == AmapRouteMode.TRANSIT) {
                        host.t("展开全部换乘", "Show all transfers")
                    } else {
                        host.tf("展开全部 %d 段", "Show all %d segments", segmentCount)
                    },
                    expandedLabel = host.t("收起", "Show less"),
                    onToggle = { segmentsExpanded = !segmentsExpanded },
                    modifier = Modifier.fillMaxWidth().padding(start = 22.dp),
                )
            }
            if (navigationUrl != null) {
                DdHairline()
                AmapOpenMapsAction(host = host, url = navigationUrl)
            }
        }
    }
}

@Composable
private fun RoutePathTabs(
    host: PluginHost,
    paths: List<AmapRoutePath>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    val colors = superPiColors()
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(DdSpacing.Sm),
    ) {
        paths.forEachIndexed { index, path ->
            val selected = index == selectedIndex
            val parts = buildList {
                add(if (index == 0) host.t("最优", "Best") else host.tf("备选 %d", "Option %d", index))
                host.durationText(path.durationSeconds)?.let(::add)
                host.distanceText(path.distanceMeters)?.let(::add)
            }
            val label = parts.joinToString(" · ")
            DdPressable(
                onClick = { onSelect(index) },
                modifier = Modifier.heightIn(min = 44.dp),
                shape = DdShapes.Pill,
                contentDescription = label,
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .clip(DdShapes.Pill)
                        .background(if (selected) colors.accentSoft else colors.wash)
                        .padding(horizontal = DdSpacing.Md, vertical = DdSpacing.Xxs),
                ) {
                    DdText(
                        text = label,
                        style = DdType.chip,
                        color = if (selected) colors.accent else colors.inkSecondary,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

internal enum class RouteLegKind { ORIGIN, STEP, DESTINATION }

internal data class RouteLeg(
    val title: String,
    val meta: String?,
    val detail: String? = null,
    val kind: RouteLegKind,
)

internal fun routeLegs(
    host: PluginHost,
    details: AmapRouteDetails,
    path: AmapRoutePath,
): List<RouteLeg> {
    val origin = RouteLeg(
        title = details.originName?.takeIf { it.isNotBlank() }
            ?: details.origin?.display
            ?: host.t("起点", "Start"),
        meta = host.t("起点", "Start"),
        kind = RouteLegKind.ORIGIN,
    )
    val destination = RouteLeg(
        title = details.destinationName?.takeIf { it.isNotBlank() }
            ?: details.destination?.display
            ?: host.t("终点", "Destination"),
        meta = host.t("终点", "Destination"),
        kind = RouteLegKind.DESTINATION,
    )
    val movement = if (path.transitLines.isNotEmpty()) {
        path.transitLines.flatMap { line -> transitLegs(host, line) }
    } else {
        path.steps.map { step -> stepLeg(host, step) }
    }
    return listOf(origin) + movement + destination
}

private fun stepLeg(host: PluginHost, step: AmapRouteStep): RouteLeg {
    val road = step.road?.trim()?.takeIf { it.isNotEmpty() && !step.instruction.contains(it) }
    val meta = listOfNotNull(
        road,
        host.distanceText(step.distanceMeters),
        host.durationText(step.durationSeconds),
    ).joinToString(" · ")
    return RouteLeg(
        title = step.instruction,
        meta = meta.takeIf { it.isNotBlank() },
        kind = RouteLegKind.STEP,
    )
}

private fun transitLegs(host: PluginHost, line: AmapTransitLine): List<RouteLeg> = buildList {
    val walkingMeters = line.walkingMeters?.takeIf { it > 0 }
    val walkingTime = host.durationText(line.walkingSeconds)
    if (walkingMeters != null || walkingTime != null) {
        val meta = listOfNotNull(
            host.distanceText(walkingMeters),
            walkingTime,
        ).joinToString(" · ")
        add(
            RouteLeg(
                title = host.t("步行", "Walk"),
                meta = meta.takeIf { it.isNotBlank() },
                kind = RouteLegKind.STEP,
            ),
        )
    }
    val entrance = meaningfulStopName(line.entrance)
    val exit = meaningfulStopName(line.exit)
    if (line.options.isNotEmpty()) {
        line.options.forEach { option ->
            add(transitOptionLeg(host, option, entrance, exit))
        }
    } else if (!line.lineName.isNullOrBlank()) {
        add(
            transitLineLeg(
                host = host,
                name = line.lineName,
                departureStop = line.departureStop,
                arrivalStop = line.arrivalStop,
                viaCount = line.viaCount,
                viaStops = line.viaStops,
                distanceMeters = line.lineDistanceMeters,
                durationSeconds = line.lineDurationSeconds,
                startTime = line.startTime,
                endTime = line.endTime,
                entrance = entrance,
                exit = exit,
            ),
        )
    }
}

private fun transitOptionLeg(
    host: PluginHost,
    option: AmapTransitOption,
    entrance: String?,
    exit: String?,
): RouteLeg = transitLineLeg(
    host = host,
    name = option.name,
    departureStop = option.departureStop,
    arrivalStop = option.arrivalStop,
    viaCount = option.viaCount,
    viaStops = option.viaStops,
    distanceMeters = option.distanceMeters,
    durationSeconds = option.durationSeconds,
    startTime = option.startTime,
    endTime = option.endTime,
    entrance = entrance,
    exit = exit,
)

private fun transitLineLeg(
    host: PluginHost,
    name: String,
    departureStop: String?,
    arrivalStop: String?,
    viaCount: Int?,
    viaStops: List<String>,
    distanceMeters: Int?,
    durationSeconds: Int?,
    startTime: String?,
    endTime: String?,
    entrance: String?,
    exit: String?,
): RouteLeg {
    val facts = buildList {
        addStops(host, departureStop, arrivalStop)
        viaCount?.takeIf { it > 0 }?.let { add(host.tf("经停 %d 站", "%d stops", it)) }
        host.distanceText(distanceMeters)?.let { add(host.tf("线路 %s", "line %s", it)) }
        host.durationText(durationSeconds)?.let { add(host.tf("约 %s", "about %s", it)) }
        addFirstLast(host, startTime, endTime)
        entrance?.let { add(host.tf("入口 %s", "Entrance %s", it)) }
        exit?.let { add(host.tf("出口 %s", "Exit %s", it)) }
    }
    val detail = if (viaStops.isNotEmpty()) {
        host.tf("经停：%s", "Stops: %s", viaStops.joinToString(host.t("、", ", ")))
    } else {
        null
    }
    return RouteLeg(
        title = name,
        meta = facts.joinToString(" · ").takeIf { it.isNotBlank() },
        detail = detail,
        kind = RouteLegKind.STEP,
    )
}

private fun MutableList<String>.addStops(
    host: PluginHost,
    departureStop: String?,
    arrivalStop: String?,
) {
    val from = meaningfulStopName(departureStop)
    val to = meaningfulStopName(arrivalStop)
    when {
        from != null && to != null -> add(host.tf("上车 %s，下车 %s", "Board %s, alight %s", from, to))
        from != null -> add(host.tf("上车 %s", "Board at %s", from))
        to != null -> add(host.tf("下车 %s", "Alight at %s", to))
    }
}

private fun MutableList<String>.addFirstLast(
    host: PluginHost,
    startTime: String?,
    endTime: String?,
) {
    val start = startTime?.trim()?.takeIf { it.isNotEmpty() }
    val end = endTime?.trim()?.takeIf { it.isNotEmpty() }
    when {
        start != null && end != null -> add(host.tf("首班 %s，末班 %s", "First %s, last %s", start, end))
        start != null -> add(host.tf("首班 %s", "First %s", start))
        end != null -> add(host.tf("末班 %s", "Last %s", end))
    }
}

private fun meaningfulStopName(value: String?): String? {
    val trimmed = value?.trim().orEmpty()
    if (trimmed.isEmpty() || trimmed == "出入口" || trimmed == "入口" || trimmed == "出口") return null
    return trimmed
}

private const val ROUTE_PREVIEW_SEGMENTS = 3

@Composable
private fun ModePill(host: PluginHost, mode: AmapRouteMode) {
    val colors = superPiColors()
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
private fun TimelineLeg(leg: RouteLeg, first: Boolean, last: Boolean, expanded: Boolean) {
    val colors = superPiColors()
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
                maxLines = if (expanded) 4 else 2,
                overflow = TextOverflow.Ellipsis,
            )
            leg.meta?.let { meta ->
                Spacer(Modifier.height(1.dp))
                DdText(
                    text = meta,
                    style = DdType.chip,
                    color = colors.inkSecondary,
                    maxLines = if (expanded) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            leg.detail?.let { detail ->
                Spacer(Modifier.height(1.dp))
                DdText(
                    text = detail,
                    style = DdType.chip,
                    color = colors.inkTertiary,
                    maxLines = if (expanded) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

