package io.github.whywood.deepdeep.plugin.weather

import io.github.whywood.deepdeep.plugin.api.PluginHost
import io.github.whywood.deepdeep.plugin.api.localizedText
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

internal const val ARG_LOCATION = "location"
internal const val ARG_DAYS = "days"
internal const val RECENT_CITY_KEY = "recent_city"
internal const val CARD_MAX_DAYS = 5
internal val PageDayOptions = listOf(3, 5, 7)

internal fun PluginHost.t(zh: String, en: String): String =
    text(localizedText(default = zh, zh = zh, en = en))

internal enum class WeatherCondition {
    CLEAR,
    MOSTLY_CLEAR,
    PARTLY_CLOUDY,
    OVERCAST,
    FOG,
    DRIZZLE,
    FREEZING_DRIZZLE,
    RAIN,
    FREEZING_RAIN,
    SNOW,
    SNOW_GRAINS,
    SHOWERS,
    SNOW_SHOWERS,
    THUNDERSTORM,
    UNKNOWN,
}

internal enum class WeatherGlyph { SUN, CLOUD }

internal fun weatherCondition(code: Int): WeatherCondition = when (code) {
    0 -> WeatherCondition.CLEAR
    1 -> WeatherCondition.MOSTLY_CLEAR
    2 -> WeatherCondition.PARTLY_CLOUDY
    3 -> WeatherCondition.OVERCAST
    45, 48 -> WeatherCondition.FOG
    51, 53, 55 -> WeatherCondition.DRIZZLE
    56, 57 -> WeatherCondition.FREEZING_DRIZZLE
    61, 63, 65 -> WeatherCondition.RAIN
    66, 67 -> WeatherCondition.FREEZING_RAIN
    71, 73, 75 -> WeatherCondition.SNOW
    77 -> WeatherCondition.SNOW_GRAINS
    80, 81, 82 -> WeatherCondition.SHOWERS
    85, 86 -> WeatherCondition.SNOW_SHOWERS
    95, 96, 99 -> WeatherCondition.THUNDERSTORM
    else -> WeatherCondition.UNKNOWN
}

internal fun weatherGlyph(code: Int): WeatherGlyph = when (weatherCondition(code)) {
    WeatherCondition.CLEAR, WeatherCondition.MOSTLY_CLEAR -> WeatherGlyph.SUN
    else -> WeatherGlyph.CLOUD
}

internal fun conditionLabel(host: PluginHost, code: Int): String = weatherCondition(code).label(host)

private fun WeatherCondition.label(host: PluginHost): String = when (this) {
    WeatherCondition.CLEAR -> host.t("晴", "Clear")
    WeatherCondition.MOSTLY_CLEAR -> host.t("大致晴朗", "Mostly clear")
    WeatherCondition.PARTLY_CLOUDY -> host.t("多云", "Partly cloudy")
    WeatherCondition.OVERCAST -> host.t("阴", "Overcast")
    WeatherCondition.FOG -> host.t("雾", "Fog")
    WeatherCondition.DRIZZLE -> host.t("毛毛雨", "Drizzle")
    WeatherCondition.FREEZING_DRIZZLE -> host.t("冻毛毛雨", "Freezing drizzle")
    WeatherCondition.RAIN -> host.t("雨", "Rain")
    WeatherCondition.FREEZING_RAIN -> host.t("冻雨", "Freezing rain")
    WeatherCondition.SNOW -> host.t("雪", "Snow")
    WeatherCondition.SNOW_GRAINS -> host.t("雪粒", "Snow grains")
    WeatherCondition.SHOWERS -> host.t("阵雨", "Rain showers")
    WeatherCondition.SNOW_SHOWERS -> host.t("阵雪", "Snow showers")
    WeatherCondition.THUNDERSTORM -> host.t("雷雨", "Thunderstorm")
    WeatherCondition.UNKNOWN -> host.t("未知", "Unknown")
}

internal fun forecastDays(raw: Int?): Int? = when {
    raw == null -> 3
    raw in 1..7 -> raw
    else -> null
}

internal fun pageDaysIndex(days: Int?): Int = when {
    days == null -> 0
    days >= 7 -> 2
    days >= 5 -> 1
    else -> 0
}

internal fun cardDayCount(dailySize: Int, requestedDays: Int): Int =
    dailySize.coerceIn(0, minOf(requestedDays.coerceAtLeast(1), CARD_MAX_DAYS))

internal fun normalizeRecentCity(value: String?): String? =
    value?.trim()?.replace(Regex("\\s+"), " ")?.takeIf { it.isNotEmpty() }?.take(80)

internal data class WeatherPageArgs(
    val location: String? = null,
    val days: Int? = null,
)

internal fun parseWeatherPageArgs(arguments: JsonObject): WeatherPageArgs = WeatherPageArgs(
    location = (arguments[ARG_LOCATION] as? JsonPrimitive)?.contentOrNull?.let(::normalizeRecentCity),
    days = (arguments[ARG_DAYS] as? JsonPrimitive)?.intOrNull,
)

internal fun initialLocation(
    args: WeatherPageArgs?,
    savedQuery: String,
    recentCity: String?,
): String? = normalizeRecentCity(args?.location)
    ?: normalizeRecentCity(savedQuery)
    ?: normalizeRecentCity(recentCity)

internal fun dayLabel(date: String, index: Int, chinese: Boolean): String {
    if (index == 0) return if (chinese) "今天" else "Today"
    if (index == 1) return if (chinese) "明天" else "Tomorrow"
    val parsed = runCatching { LocalDate.parse(date) }.getOrNull() ?: return date
    if (chinese) {
        return when (parsed.dayOfWeek) {
            DayOfWeek.MONDAY -> "周一"
            DayOfWeek.TUESDAY -> "周二"
            DayOfWeek.WEDNESDAY -> "周三"
            DayOfWeek.THURSDAY -> "周四"
            DayOfWeek.FRIDAY -> "周五"
            DayOfWeek.SATURDAY -> "周六"
            DayOfWeek.SUNDAY -> "周日"
        }
    }
    return parsed.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
}

internal fun PluginHost.dayLabel(date: String, index: Int): String =
    t(dayLabel(date, index, chinese = true), dayLabel(date, index, chinese = false))

internal fun weatherSummary(host: PluginHost, details: WeatherDetails, maxDays: Int = CARD_MAX_DAYS): String {
    val current = details.current
    val location = listOfNotNull(details.location.name, details.location.region)
        .distinct()
        .joinToString(" · ")
    val metrics = buildList {
        current.apparentTemperature?.let { add(host.t("体感 ${it.rounded()}°", "Feels ${it.rounded()}°")) }
        current.humidity?.let { add(host.t("湿度 $it%", "Humidity $it%")) }
        current.windSpeed?.let { add(host.t("风速 ${it.rounded()} km/h", "Wind ${it.rounded()} km/h")) }
        details.daily.firstOrNull()?.precipitationProbability?.let {
            add(host.t("降水概率 $it%", "Rain chance $it%"))
        }
    }
    val currentLine = buildString {
        append(location).append("：")
        append(current.temperature.rounded()).append("°C ")
        append(conditionLabel(host, current.weatherCode))
        if (metrics.isNotEmpty()) append("，").append(metrics.joinToString("，"))
    }
    val dailyLine = details.daily
        .take(maxDays.coerceIn(1, 7))
        .mapIndexed { index, day ->
            val labelZh = dayLabel(day.date, index, chinese = true)
            val labelEn = dayLabel(day.date, index, chinese = false)
            val condition = conditionLabel(host, day.weatherCode)
            host.t(
                "$labelZh $condition ${day.max.rounded()}°/${day.min.rounded()}°",
                "$labelEn $condition ${day.max.rounded()}°/${day.min.rounded()}°",
            )
        }
        .joinToString("；")
    return if (dailyLine.isBlank()) currentLine else currentLine + "。" + dailyLine
}

internal fun Double.rounded(): String =
    if (this % 1.0 == 0.0) toLong().toString() else String.format(Locale.ROOT, "%.1f", this)
