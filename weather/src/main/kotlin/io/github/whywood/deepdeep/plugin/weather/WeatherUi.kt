package io.github.whywood.deepdeep.plugin.weather

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.whywood.deepdeep.design.DdGroupCard
import io.github.whywood.deepdeep.design.DdHairline
import io.github.whywood.deepdeep.design.DdLoadingBlock
import io.github.whywood.deepdeep.design.DdNoticeBar
import io.github.whywood.deepdeep.design.DdNoticeTone
import io.github.whywood.deepdeep.design.DdPrimaryButton
import io.github.whywood.deepdeep.design.DdSpacing
import io.github.whywood.deepdeep.design.DdText
import io.github.whywood.deepdeep.design.DdTextField
import io.github.whywood.deepdeep.design.DdType
import io.github.whywood.deepdeep.design.deepDeepColors
import io.github.whywood.deepdeep.plugin.api.PluginHost
import io.github.whywood.deepdeep.plugin.api.ToolCardSlotState
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

private val JsonFormat = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true }

private sealed interface PageState {
    data object Idle : PageState
    data object Loading : PageState
    data class Failed(val message: String) : PageState
    data class Loaded(val details: WeatherDetails) : PageState
}

@Composable
internal fun WeatherPage(host: PluginHost) {
    var query by rememberSaveable { mutableStateOf("") }
    var state by remember { mutableStateOf<PageState>(PageState.Idle) }
    val scope = rememberCoroutineScope()

    fun search() {
        if (query.isBlank() || state == PageState.Loading) return
        state = PageState.Loading
        scope.launch {
            state = try {
                PageState.Loaded(WeatherService(host).forecast(query, 3))
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                PageState.Failed(error.message ?: host.t("查询失败", "Lookup failed"))
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(start = DdSpacing.Xl, end = DdSpacing.Xl, top = DdSpacing.Md, bottom = DdSpacing.Xxl),
        verticalArrangement = Arrangement.spacedBy(DdSpacing.Lg),
    ) {
        DdTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = host.t("城市名，例如杭州", "City, e.g. Hangzhou"),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            onDone = ::search,
        )
        DdPrimaryButton(
            text = host.t("查询天气", "Check weather"),
            onClick = ::search,
            enabled = query.isNotBlank() && state != PageState.Loading,
        )
        when (val current = state) {
            PageState.Idle -> DdText(
                text = host.t("输入城市，查看当前天气和未来三天预报。", "Enter a city to see current weather and a three-day forecast."),
                style = DdType.bodySmall,
                color = deepDeepColors().inkTertiary,
            )

            PageState.Loading -> DdLoadingBlock(text = host.t("正在查询…", "Checking…"))
            is PageState.Failed -> DdNoticeBar(
                text = current.message,
                tone = DdNoticeTone.DANGER,
                actionLabel = host.t("重试", "Retry"),
                onAction = ::search,
            )

            is PageState.Loaded -> WeatherCard(host, current.details)
        }
    }
}

@Composable
internal fun WeatherToolCard(host: PluginHost, state: ToolCardSlotState) {
    val result = state.result
    if (result == null) {
        DdGroupCard {
            DdLoadingBlock(
                modifier = Modifier.padding(vertical = DdSpacing.Lg),
                text = host.t("正在查询天气…", "Checking weather…"),
            )
        }
        return
    }
    if (result.isError) {
        DdNoticeBar(
            text = result.text ?: host.t("天气查询失败", "Weather lookup failed"),
            tone = DdNoticeTone.DANGER,
        )
        return
    }
    val details = result.details?.let { parseDetails(it) }
    if (details == null) {
        DdGroupCard {
            DdText(
                text = result.text ?: host.t("没有天气数据", "No weather data"),
                modifier = Modifier.padding(horizontal = DdSpacing.Card, vertical = DdSpacing.Lg),
                style = DdType.bodySmall,
                color = deepDeepColors().inkSecondary,
            )
        }
    } else {
        WeatherCard(host, details)
    }
}

private fun parseDetails(details: JsonObject): WeatherDetails? = runCatching {
    JsonFormat.decodeFromJsonElement(WeatherDetails.serializer(), details)
}.getOrNull()

@Composable
private fun WeatherCard(host: PluginHost, details: WeatherDetails) {
    val colors = deepDeepColors()
    val current = details.current
    val location = listOfNotNull(details.location.name, details.location.region)
        .distinct()
        .joinToString(" · ")
    DdGroupCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = DdSpacing.Card, vertical = DdSpacing.Lg),
            verticalArrangement = Arrangement.spacedBy(DdSpacing.Sm),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    DdText(text = location, style = DdType.heading, color = colors.ink)
                    Spacer(Modifier.height(2.dp))
                    DdText(
                        text = conditionLabel(host, current.weatherCode),
                        style = DdType.bodySmall,
                        color = colors.inkSecondary,
                    )
                }
                DdText(
                    text = "${current.temperature.rounded()}°",
                    style = DdType.display,
                    color = colors.ink,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(DdSpacing.Lg)) {
                current.apparentTemperature?.let { Metric(host.t("体感", "Feels"), "${it.rounded()}°") }
                current.humidity?.let { Metric(host.t("湿度", "Humidity"), "$it%") }
                current.windSpeed?.let { Metric(host.t("风速", "Wind"), "${it.rounded()} km/h") }
            }
            if (details.daily.isNotEmpty()) {
                DdHairline()
                details.daily.take(7).forEachIndexed { index, day ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        DdText(
                            text = day.date.takeLast(5),
                            modifier = Modifier.width(52.dp),
                            style = DdType.bodySmall,
                            color = colors.inkSecondary,
                        )
                        DdText(
                            text = conditionLabel(host, day.weatherCode),
                            modifier = Modifier.weight(1f),
                            style = DdType.bodySmall,
                            color = colors.ink,
                        )
                        DdText(
                            text = "${day.max.rounded()}° / ${day.min.rounded()}°",
                            style = DdType.bodySmall,
                            color = colors.ink,
                        )
                    }
                    if (index < details.daily.lastIndex) DdHairline()
                }
            }
            DdText(
                text = host.t("数据来源 ${details.source}", "Source: ${details.source}"),
                style = DdType.bodySmall.copy(fontSize = 11.sp),
                color = colors.inkTertiary,
            )
        }
    }
}

@Composable
private fun Metric(label: String, value: String) {
    val colors = deepDeepColors()
    Column {
        DdText(text = label, style = DdType.bodySmall, color = colors.inkTertiary)
        DdText(text = value, style = DdType.bodySmall, color = colors.ink)
    }
}
