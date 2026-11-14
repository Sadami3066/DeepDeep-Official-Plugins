package io.github.whywood.deepdeep.plugin.weather

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.whywood.deepdeep.design.DdEmptyState
import io.github.whywood.deepdeep.design.DdGroupCard
import io.github.whywood.deepdeep.design.DdHairline
import io.github.whywood.deepdeep.design.DdIcon
import io.github.whywood.deepdeep.design.DdIcons
import io.github.whywood.deepdeep.design.DdLoadingBlock
import io.github.whywood.deepdeep.design.DdNoticeBar
import io.github.whywood.deepdeep.design.DdNoticeTone
import io.github.whywood.deepdeep.design.DdPrimaryButton
import io.github.whywood.deepdeep.design.DdSegmented
import io.github.whywood.deepdeep.design.DdSpacing
import io.github.whywood.deepdeep.design.DdText
import io.github.whywood.deepdeep.design.DdTextAction
import io.github.whywood.deepdeep.design.DdTextField
import io.github.whywood.deepdeep.design.DdType
import io.github.whywood.deepdeep.design.deepDeepColors
import io.github.whywood.deepdeep.plugin.api.LocalPluginPageContext
import io.github.whywood.deepdeep.plugin.api.PluginHost
import io.github.whywood.deepdeep.plugin.api.ToolCardSlotState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

private val JsonFormat = Json { ignoreUnknownKeys = true; isLenient = true }

private sealed interface PageState {
    data object Idle : PageState

    data object Loading : PageState

    data class Failed(val message: String) : PageState

    data class Loaded(val details: WeatherDetails) : PageState
}

@Composable
internal fun WeatherPage(host: PluginHost) {
    val colors = deepDeepColors()
    val scope = rememberCoroutineScope()
    val pageContext = LocalPluginPageContext.current
    val pageArgs = remember(pageContext) { pageContext?.arguments?.let(::parseWeatherPageArgs) }

    var query by rememberSaveable { mutableStateOf(normalizeRecentCity(pageArgs?.location).orEmpty()) }
    var selectedDays by rememberSaveable { mutableIntStateOf(pageDaysIndex(pageArgs?.days)) }
    var state by remember { mutableStateOf<PageState>(PageState.Idle) }
    var busy by remember { mutableStateOf(false) }
    var lastQuery by remember { mutableStateOf("") }
    var requestToken by remember { mutableIntStateOf(0) }

    fun load(location: String) {
        val target = normalizeRecentCity(location) ?: return
        if (busy && target == lastQuery) return
        val token = ++requestToken
        lastQuery = target
        busy = true
        if (state !is PageState.Loaded) state = PageState.Loading
        scope.launch {
            val result = try {
                Result.success(WeatherService(host).forecast(target, PageDayOptions.last()))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Result.failure(error)
            }
            if (token != requestToken) return@launch
            busy = false
            result.fold(
                onSuccess = { details ->
                    state = PageState.Loaded(details)
                    runCatching {
                        val city = normalizeRecentCity(details.location.name) ?: target
                        host.settings().put(RECENT_CITY_KEY, city)
                    }
                },
                onFailure = { error ->
                    state = PageState.Failed(
                        error.message?.takeIf { it.isNotBlank() }
                            ?: host.t("查询失败，请稍后重试。", "Lookup failed. Try again later."),
                    )
                },
            )
        }
    }

    LaunchedEffect(pageContext) {
        val requested = normalizeRecentCity(pageArgs?.location)
        if (requested != null) {
            if (pageArgs?.days != null) selectedDays = pageDaysIndex(pageArgs.days)
            if (!(state is PageState.Loaded && requested == lastQuery)) {
                query = requested
                load(requested)
            }
            return@LaunchedEffect
        }
        if (state !is PageState.Idle || busy) return@LaunchedEffect
        val recent = runCatching { host.settings().get(RECENT_CITY_KEY) }.getOrNull()
        val location = initialLocation(pageArgs, query, recent) ?: return@LaunchedEffect
        query = location
        load(location)
    }

    val canQuery = query.isNotBlank() && !busy

    Column(
        modifier = Modifier
            .fillMaxSize()
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
            onDone = { if (canQuery) load(query) },
        )
        DdPrimaryButton(
            text = if (busy) {
                host.t("正在查询…", "Checking…")
            } else {
                host.t("查询天气", "Check weather")
            },
            onClick = { load(query) },
            enabled = canQuery,
            modifier = Modifier.fillMaxWidth(),
        )
        when (val current = state) {
            PageState.Idle -> DdEmptyState(
                text = host.t(
                    "输入城市，查看当前天气和未来几天预报。",
                    "Enter a city to see current conditions and the forecast.",
                ),
                icon = DdIcons.Sun,
            )

            PageState.Loading -> DdLoadingBlock(text = host.t("正在查询…", "Checking…"))

            is PageState.Failed -> DdNoticeBar(
                text = current.message,
                tone = DdNoticeTone.DANGER,
                actionLabel = host.t("重试", "Retry"),
                onAction = { load(lastQuery.ifBlank { query }) },
            )

            is PageState.Loaded -> {
                CurrentWeatherCard(host, current.details)
                ForecastCard(
                    host = host,
                    details = current.details,
                    selectedIndex = selectedDays,
                    onSelectIndex = { selectedDays = it },
                )
                DdText(
                    text = host.t("天气数据来自 Open-Meteo", "Weather data by Open-Meteo"),
                    style = DdType.chip,
                    color = colors.inkTertiary,
                )
            }
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
            text = result.text ?: host.t("天气查询失败，请稍后重试。", "Weather lookup failed. Try again later."),
            tone = DdNoticeTone.DANGER,
        )
        return
    }
    val details = result.details?.let(::parseDetails)
    if (details == null) {
        DdGroupCard {
            DdText(
                text = result.text ?: host.t("没有天气数据。", "No weather data."),
                modifier = Modifier.padding(horizontal = DdSpacing.Card, vertical = DdSpacing.Lg),
                style = DdType.bodySmall,
                color = deepDeepColors().inkSecondary,
            )
        }
        return
    }
    val requestedDays = forecastDays(state.args["days"]?.jsonPrimitive?.intOrNull) ?: 3
    WeatherResultCard(host, details, requestedDays)
}

private fun parseDetails(details: JsonObject): WeatherDetails? = runCatching {
    JsonFormat.decodeFromJsonElement(WeatherDetails.serializer(), details)
}.getOrNull()

@Composable
private fun WeatherResultCard(host: PluginHost, details: WeatherDetails, requestedDays: Int) {
    val colors = deepDeepColors()
    val visibleDays = details.daily.take(cardDayCount(details.daily.size, requestedDays))
    DdGroupCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = DdSpacing.Card, vertical = DdSpacing.Lg),
            verticalArrangement = Arrangement.spacedBy(DdSpacing.Sm),
        ) {
            CurrentWeatherSection(host, details)
            if (visibleDays.isNotEmpty()) {
                DdHairline()
                visibleDays.forEachIndexed { index, day ->
                    if (index > 0) DdHairline()
                    ForecastRow(host, day, index)
                }
            }
            DdHairline()
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DdText(
                    text = details.source,
                    modifier = Modifier.weight(1f),
                    style = DdType.chip,
                    color = colors.inkTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                DdTextAction(
                    text = host.t("查看完整预报", "Full forecast"),
                    onClick = { openFullForecast(host, details) },
                )
            }
        }
    }
}

@Composable
private fun CurrentWeatherCard(host: PluginHost, details: WeatherDetails) {
    DdGroupCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = DdSpacing.Card, vertical = DdSpacing.Lg),
            verticalArrangement = Arrangement.spacedBy(DdSpacing.Sm),
        ) {
            CurrentWeatherSection(host, details)
        }
    }
}

@Composable
private fun CurrentWeatherSection(host: PluginHost, details: WeatherDetails) {
    val colors = deepDeepColors()
    val current = details.current
    val condition = conditionLabel(host, current.weatherCode)
    val region = details.location.region?.takeIf { it.isNotBlank() }
    val subtitle = listOfNotNull(condition, region).joinToString(" · ")
    Column(verticalArrangement = Arrangement.spacedBy(DdSpacing.Sm)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                DdText(
                    text = details.location.name,
                    style = DdType.heading,
                    color = colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                DdText(
                    text = subtitle,
                    style = DdType.bodySmall,
                    color = colors.inkSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            DdIcon(
                icon = weatherIcon(current.weatherCode),
                tint = colors.accent,
                size = 26.dp,
            )
            Spacer(Modifier.width(DdSpacing.Sm))
            DdText(
                text = "${current.temperature.rounded()}°",
                style = DdType.display,
                color = colors.ink,
                maxLines = 1,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(DdSpacing.Md)) {
            current.apparentTemperature?.let {
                Metric(host.t("体感", "Feels"), "${it.rounded()}°", Modifier.weight(1f))
            }
            current.humidity?.let {
                Metric(host.t("湿度", "Humidity"), "$it%", Modifier.weight(1f))
            }
            current.windSpeed?.let {
                Metric(host.t("风", "Wind"), "${it.rounded()} km/h", Modifier.weight(1f))
            }
            details.daily.firstOrNull()?.precipitationProbability?.let {
                Metric(host.t("降水", "Rain"), "$it%", Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ForecastCard(
    host: PluginHost,
    details: WeatherDetails,
    selectedIndex: Int,
    onSelectIndex: (Int) -> Unit,
) {
    val colors = deepDeepColors()
    val index = selectedIndex.coerceIn(0, PageDayOptions.lastIndex)
    val visibleDays = details.daily.take(PageDayOptions[index])
    DdGroupCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = DdSpacing.Card, vertical = DdSpacing.Lg),
            verticalArrangement = Arrangement.spacedBy(DdSpacing.Md),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DdText(
                    text = host.t("未来预报", "Forecast"),
                    modifier = Modifier.weight(1f),
                    style = DdType.label,
                    color = colors.ink,
                    maxLines = 1,
                )
                DdSegmented(
                    options = PageDayOptions.map { host.t("$it 天", "$it days") },
                    selectedIndex = index,
                    onSelect = onSelectIndex,
                    modifier = Modifier.width(172.dp),
                )
            }
            if (visibleDays.isEmpty()) {
                DdText(
                    text = host.t("没有可用的逐日预报。", "No daily forecast available."),
                    style = DdType.bodySmall,
                    color = colors.inkTertiary,
                )
            } else {
                visibleDays.forEachIndexed { dayIndex, day ->
                    if (dayIndex > 0) DdHairline()
                    ForecastRow(host, day, dayIndex)
                }
            }
        }
    }
}

@Composable
private fun ForecastRow(host: PluginHost, day: WeatherDay, index: Int) {
    val colors = deepDeepColors()
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = DdSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DdText(
            text = host.dayLabel(day.date, index),
            modifier = Modifier.width(68.dp),
            style = DdType.bodySmall,
            color = colors.inkSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        DdIcon(
            icon = weatherIcon(day.weatherCode),
            tint = colors.inkTertiary,
            size = 14.dp,
        )
        Spacer(Modifier.width(DdSpacing.Sm))
        DdText(
            text = conditionLabel(host, day.weatherCode),
            modifier = Modifier.weight(1f),
            style = DdType.bodySmall,
            color = colors.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        day.precipitationProbability?.let {
            DdText(
                text = "$it%",
                style = DdType.chip,
                color = colors.inkTertiary,
                maxLines = 1,
            )
            Spacer(Modifier.width(DdSpacing.Sm))
        }
        DdText(
            text = "${day.max.rounded()}° / ${day.min.rounded()}°",
            style = DdType.bodySmall,
            color = colors.ink,
            maxLines = 1,
        )
    }
}

@Composable
private fun Metric(label: String, value: String, modifier: Modifier = Modifier) {
    val colors = deepDeepColors()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        DdText(
            text = label,
            style = DdType.chip,
            color = colors.inkTertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        DdText(
            text = value,
            style = DdType.label,
            color = colors.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun weatherIcon(code: Int): DdIcon = when (weatherGlyph(code)) {
    WeatherGlyph.SUN -> DdIcons.Sun
    WeatherGlyph.CLOUD -> DdIcons.Cloud
}

private fun openFullForecast(host: PluginHost, details: WeatherDetails) {
    val arguments = buildJsonObject {
        put(ARG_LOCATION, details.location.name)
        put(ARG_DAYS, PageDayOptions.last())
    }
    runCatching { host.ui().openPageWithArguments(PAGE_ID, arguments) }
        .onFailure { host.logs().warn("weather: cannot open forecast page", it) }
}
