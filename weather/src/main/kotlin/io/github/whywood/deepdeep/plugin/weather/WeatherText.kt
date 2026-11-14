package io.github.whywood.deepdeep.plugin.weather

import io.github.whywood.deepdeep.plugin.api.PluginHost
import io.github.whywood.deepdeep.plugin.api.localizedText

internal fun PluginHost.t(zh: String, en: String): String =
    text(localizedText(default = zh, zh = zh, en = en))

internal fun conditionLabel(host: PluginHost, code: Int): String = when (code) {
    0 -> host.t("晴", "Clear")
    1 -> host.t("大致晴朗", "Mostly clear")
    2 -> host.t("多云", "Partly cloudy")
    3 -> host.t("阴", "Overcast")
    45, 48 -> host.t("雾", "Fog")
    51, 53, 55 -> host.t("毛毛雨", "Drizzle")
    56, 57 -> host.t("冻毛毛雨", "Freezing drizzle")
    61, 63, 65 -> host.t("雨", "Rain")
    66, 67 -> host.t("冻雨", "Freezing rain")
    71, 73, 75 -> host.t("雪", "Snow")
    77 -> host.t("雪粒", "Snow grains")
    80, 81, 82 -> host.t("阵雨", "Rain showers")
    85, 86 -> host.t("阵雪", "Snow showers")
    95, 96, 99 -> host.t("雷雨", "Thunderstorm")
    else -> host.t("未知", "Unknown")
}

internal fun weatherSummary(host: PluginHost, details: WeatherDetails): String {
    val current = details.current
    val location = listOfNotNull(details.location.name, details.location.region)
        .distinct()
        .joinToString(" · ")
    val metrics = buildList {
        current.apparentTemperature?.let { add(host.t("体感 ${it.rounded()}°", "Feels ${it.rounded()}°")) }
        current.humidity?.let { add(host.t("湿度 $it%", "Humidity $it%")) }
        current.windSpeed?.let { add(host.t("风速 ${it.rounded()} km/h", "Wind ${it.rounded()} km/h")) }
    }
    val currentLine = buildString {
        append(location).append("：")
        append(current.temperature.rounded()).append("°C ")
        append(conditionLabel(host, current.weatherCode))
        if (metrics.isNotEmpty()) append("，").append(metrics.joinToString("，"))
    }
    val dailyLine = details.daily.take(3).joinToString("；") { day ->
        host.t(
            "${day.date} ${conditionLabel(host, day.weatherCode)} ${day.max.rounded()}°/${day.min.rounded()}°",
            "${day.date} ${conditionLabel(host, day.weatherCode)} ${day.max.rounded()}°/${day.min.rounded()}°",
        )
    }
    return if (dailyLine.isBlank()) currentLine else currentLine + "。" + dailyLine
}

internal fun Double.rounded(): String = if (this % 1.0 == 0.0) toLong().toString() else String.format("%.1f", this)
