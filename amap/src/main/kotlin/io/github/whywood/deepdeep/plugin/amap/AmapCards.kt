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
    val previewRows = if (map != null) 3 else 5
    var expanded by remember(details) { mutableStateOf(false) }
    val visible = if (expanded) details.items else details.items.take(previewRows)
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
                    DdIcon(icon = DdIcons.Search, tint = superPiColors().accent, size = 15.dp)
                    Spacer(Modifier.width(DdSpacing.Sm))
                    DdText(
                        text = title,
                        modifier = Modifier.weight(1f),
                        style = DdType.label,
                        color = superPiColors().ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    DdText(text = count, style = DdType.chip, color = superPiColors().inkTertiary, maxLines = 1)
                }
                Spacer(Modifier.height(DdSpacing.Sm))
            }
            visible.forEachIndexed { index, item ->
                if (index > 0) DdHairline()
                PlaceRow(host = host, item = item, searchCity = details.city)
            }
            if (details.items.size > previewRows) {
                DdExpandToggle(
                    expanded = expanded,
                    collapsedLabel = host.tf("展开全部 %d 处", "Show all %d places", details.items.size),
                    expandedLabel = host.t("收起", "Show less"),
                    onToggle = { expanded = !expanded },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun MapLabelPill(icon: DdIcon, title: String, trailing: String) {
    val colors = superPiColors()
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
private fun PlaceRow(host: PluginHost, item: AmapPlaceItem, searchCity: String?) {
    val colors = superPiColors()
    val type = readablePlaceType(item.typeName)
    val address = item.address?.takeIf { it.isNotBlank() }
    var viewer by remember(item.photos.firstOrNull()) { mutableStateOf<ImageBitmap?>(null) }
    DdPressable(
        onClick = {
            runCatching { host.ui().openUrl(AmapUri.search(item.name, searchCity)) }
        },
        modifier = Modifier.fillMaxWidth(),
        shape = DdShapes.Small,
        contentDescription = item.name,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = DdSpacing.Xs, vertical = DdSpacing.Sm),
            verticalAlignment = Alignment.Top,
        ) {
            PlacePhoto(
                host = host,
                name = item.name,
                url = item.photos.firstOrNull(),
                onOpen = { viewer = it },
            )
            Spacer(Modifier.width(DdSpacing.Md))
            Column(Modifier.weight(1f)) {
                DdText(
                    text = item.name,
                    style = DdType.label,
                    color = colors.ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (type != null) {
                    Spacer(Modifier.height(DdSpacing.Xxs))
                    TypeTag(text = type)
                }
                if (address != null) {
                    DdText(
                        text = address,
                        modifier = Modifier.fillMaxWidth().padding(top = DdSpacing.Xxs),
                        style = DdType.chip,
                        color = colors.inkSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
    viewer?.let { bitmap ->
        DdImageViewer(
            bitmap = bitmap,
            onDismiss = { viewer = null },
            contentDescription = item.name,
        )
    }
}

@Composable
private fun PlacePhoto(
    host: PluginHost,
    name: String,
    url: String?,
    onOpen: (ImageBitmap) -> Unit,
) {
    val colors = superPiColors()
    val scope = rememberCoroutineScope()
    val size = 64.dp
    if (url == null) {
        Box(
            modifier = Modifier.size(size).clip(DdShapes.Small).background(colors.wash),
            contentAlignment = Alignment.Center,
        ) {
            DdIcon(icon = DdIcons.Image, tint = colors.inkTertiary, size = 18.dp)
        }
        return
    }
    DdPressable(
        onClick = {
            scope.launch {
                runCatching { host.images().load(url)?.bitmap }.getOrNull()?.let(onOpen)
            }
        },
        modifier = Modifier.size(size),
        shape = DdShapes.Small,
        contentDescription = host.t("全屏查看图片", "View image full screen"),
    ) {
        Box(Modifier.fillMaxSize()) {
            DdRemoteImage(
                url = url,
                load = { target -> runCatching { host.images().load(target)?.bitmap }.getOrNull() },
                contentDescription = name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                shape = DdShapes.Small,
                failure = {
                    Box(
                        modifier = Modifier.fillMaxSize().background(colors.wash),
                        contentAlignment = Alignment.Center,
                    ) {
                        DdIcon(icon = DdIcons.Image, tint = colors.inkTertiary, size = 18.dp)
                    }
                },
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(3.dp)
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(colors.raised),
                contentAlignment = Alignment.Center,
            ) {
                DdIcon(icon = DdIcons.Expand, tint = colors.inkSecondary, size = 11.dp)
            }
        }
    }
}

@Composable
private fun TypeTag(text: String) {
    val colors = superPiColors()
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
    val colors = superPiColors()
    val location = details.location
    val openUrl = location?.let { AmapUri.marker(it, details.formattedAddress) }
    val admin = listOfNotNull(
        details.province?.takeIf { it.isNotBlank() },
        details.city?.takeIf { it.isNotBlank() && it != details.province },
        details.district?.takeIf { it.isNotBlank() },
        details.township?.takeIf { it.isNotBlank() },
    ).joinToString(" · ")
    val street = listOfNotNull(
        details.street?.takeIf { it.isNotBlank() },
        details.streetNumber?.takeIf { it.isNotBlank() },
    ).joinToString("")
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
            if (street.isNotBlank()) {
                Spacer(Modifier.height(DdSpacing.Xxs))
                DdText(
                    text = host.tf("街道 %s", "Street %s", street),
                    style = DdType.bodySmall,
                    color = colors.inkSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Spacer(Modifier.height(DdSpacing.Xxs))
                DdText(
                    text = host.t(
                        "高德只解析到区县，没有街道和门牌",
                        "Amap resolves only to the district; no street or number",
                    ),
                    style = DdType.chip,
                    color = colors.inkTertiary,
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
internal fun AmapOpenMapsAction(host: PluginHost, url: String) {
    val colors = superPiColors()
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
internal fun AmapLoadingCard(host: PluginHost, title: String, hero: Boolean) {
    val colors = superPiColors()
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
internal fun AmapMessageCard(
    host: PluginHost,
    icon: DdIcon,
    title: String,
    message: String,
    danger: Boolean,
) {
    val colors = superPiColors()
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
                maxLines = 8,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun Modifier.amapCardEntrance(): Modifier {
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
