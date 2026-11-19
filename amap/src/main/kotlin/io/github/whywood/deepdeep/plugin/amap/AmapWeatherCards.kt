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
    val colors = superPiColors()
    val current = details.days.first()
    val future = details.days.drop(1)
    val preview = future.take(WEATHER_PREVIEW_DAYS)
    var expanded by remember(details) { mutableStateOf(false) }
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
            WeatherFeaturedDay(host = host, day = current)
            if (future.isNotEmpty()) {
                Spacer(Modifier.height(DdSpacing.Md))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(DdShapes.Medium)
                        .background(colors.wash)
                        .padding(vertical = DdSpacing.Sm, horizontal = DdSpacing.Md),
                ) {
                    val days = if (expanded) future else preview
                    days.forEachIndexed { index, day ->
                        if (index > 0) DdHairline()
                        WeatherDayRow(host = host, day = day)
                    }
                }
                if (future.size > WEATHER_PREVIEW_DAYS) {
                    DdExpandToggle(
                        expanded = expanded,
                        collapsedLabel = host.tf(
                            "展开全部 %d 天",
                            "Show all %d days",
                            details.days.size,
                        ),
                        expandedLabel = host.t("收起", "Show less"),
                        onToggle = { expanded = !expanded },
                        modifier = Modifier.fillMaxWidth(),
                    )
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

private const val WEATHER_PREVIEW_DAYS = 3

@Composable
private fun WeatherFeaturedDay(host: PluginHost, day: AmapWeatherDay) {
    val colors = superPiColors()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(DdShapes.Medium)
            .background(colors.wash)
            .padding(DdSpacing.Md),
    ) {
        DdText(
            text = dayLabel(host, day),
            style = DdType.label,
            color = colors.ink,
            maxLines = 1,
        )
        Spacer(Modifier.height(DdSpacing.Sm))
        Row(Modifier.fillMaxWidth()) {
            WeatherHalf(host = host, day = day, dayHalf = true, large = true, modifier = Modifier.weight(1f))
            Box(Modifier.width(1.dp).height(58.dp).background(colors.hairline))
            WeatherHalf(host = host, day = day, dayHalf = false, large = true, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun WeatherDayRow(host: PluginHost, day: AmapWeatherDay) {
    val colors = superPiColors()
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = DdSpacing.Xxs),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.width(58.dp).padding(top = 2.dp)) {
            DdText(
                text = shortDateLabel(day.date) ?: "",
                style = DdType.chip,
                color = colors.inkTertiary,
                maxLines = 1,
            )
            weekdayLabel(host, day.week)?.let { weekday ->
                DdText(text = weekday, style = DdType.micro, color = colors.inkTertiary, maxLines = 1)
            }
        }
        WeatherHalf(host = host, day = day, dayHalf = true, modifier = Modifier.weight(1f))
        WeatherHalf(host = host, day = day, dayHalf = false, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun WeatherHalf(
    host: PluginHost,
    day: AmapWeatherDay,
    dayHalf: Boolean,
    modifier: Modifier = Modifier,
    large: Boolean = false,
) {
    val colors = superPiColors()
    val weather = if (dayHalf) day.dayWeather else day.nightWeather
    val temperature = if (dayHalf) {
        day.dayTemp ?: day.dayTempFloat?.roundToInt()
    } else {
        day.nightTemp ?: day.nightTempFloat?.roundToInt()
    }
    val wind = windText(
        host = host,
        direction = if (dayHalf) day.dayWind else day.nightWind,
        power = if (dayHalf) day.dayPower else day.nightPower,
    )
    Column(modifier.padding(horizontal = if (large) DdSpacing.Sm else DdSpacing.Xxs)) {
        DdText(
            text = if (dayHalf) host.t("白天", "Day") else host.t("夜间", "Night"),
            style = DdType.micro,
            color = colors.inkTertiary,
            maxLines = 1,
        )
        Spacer(Modifier.height(1.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            DdText(
                text = weather?.takeIf { it.isNotBlank() } ?: host.t("暂无", "No data"),
                modifier = Modifier.weight(1f),
                style = if (large) DdType.label else DdType.chip,
                color = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(DdSpacing.Xxs))
            DdText(
                text = temperature?.let { "$it°" } ?: "--",
                style = if (large) DdType.heading else DdType.numeric,
                color = colors.ink,
                maxLines = 1,
            )
        }
        wind?.let {
            DdText(
                text = it,
                style = DdType.chip,
                color = colors.inkSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun dayLabel(host: PluginHost, day: AmapWeatherDay): String {
    val date = shortDateLabel(day.date) ?: host.t("今天", "Today")
    val weekday = weekdayLabel(host, day.week)
    return listOfNotNull(date, weekday).joinToString(" ")
}

private fun weekdayLabel(host: PluginHost, week: String?): String? = when (week?.trim()?.toIntOrNull()) {
    1 -> host.t("周一", "Mon")
    2 -> host.t("周二", "Tue")
    3 -> host.t("周三", "Wed")
    4 -> host.t("周四", "Thu")
    5 -> host.t("周五", "Fri")
    6 -> host.t("周六", "Sat")
    7 -> host.t("周日", "Sun")
    else -> null
}

private fun windText(host: PluginHost, direction: String?, power: String?): String? {
    val dir = direction?.trim()?.takeIf { it.isNotEmpty() }
    val strength = power?.trim()?.takeIf { it.isNotEmpty() }?.let {
        if (it.endsWith("级")) it else host.tf("%s 级", "%s", it)
    }
    return when {
        dir != null && strength != null -> host.tf("%s %s", "%s %s", dir, strength)
        dir != null -> dir
        strength != null -> strength
        else -> null
    }
}

