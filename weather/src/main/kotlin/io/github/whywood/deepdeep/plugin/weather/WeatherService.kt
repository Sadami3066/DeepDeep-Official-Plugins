package io.github.whywood.deepdeep.plugin.weather

import io.github.whywood.deepdeep.plugin.api.PluginHost
import java.net.URLEncoder

internal class WeatherException(message: String) : Exception(message)

internal class WeatherService(private val host: PluginHost) {
    private val json = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    suspend fun forecast(query: String, days: Int): WeatherDetails {
        val place = resolve(query)
        val url = buildString {
            append("https://api.open-meteo.com/v1/forecast")
            append("?latitude=").append(place.latitude)
            append("&longitude=").append(place.longitude)
            append("&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m")
            append("&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max")
            append("&timezone=auto")
            append("&forecast_days=").append(days.coerceIn(1, 7))
        }
        val response = host.network().request(method = "GET", url = url)
        if (response.statusCode !in 200..299) {
            throw WeatherException(host.t("天气服务返回 ${response.statusCode}", "Weather service returned ${response.statusCode}"))
        }
        val body = runCatching { json.decodeFromString(ForecastResponse.serializer(), response.body) }
            .getOrElse { throw WeatherException(host.t("天气数据解析失败", "Could not parse weather data")) }
        val current = body.current ?: throw WeatherException(host.t("天气数据不完整", "Weather data is incomplete"))
        val daily = body.daily
        val currentTemperature = current.temperature_2m
            ?: throw WeatherException(host.t("天气数据不完整", "Weather data is incomplete"))
        return WeatherDetails(
            location = WeatherLocation(
                name = place.name,
                region = listOfNotNull(place.admin1, place.country).firstOrNull { it.isNotBlank() },
                country = place.country,
                latitude = place.latitude,
                longitude = place.longitude,
                timezone = body.timezone ?: place.timezone,
            ),
            current = WeatherCurrent(
                temperature = currentTemperature,
                apparentTemperature = current.apparent_temperature,
                humidity = current.relative_humidity_2m,
                windSpeed = current.wind_speed_10m,
                weatherCode = current.weather_code ?: 0,
            ),
            daily = if (daily == null) {
                emptyList()
            } else {
                daily.time.indices.mapNotNull { index ->
                    val max = daily.temperature_2m_max.getOrNull(index) ?: return@mapNotNull null
                    val min = daily.temperature_2m_min.getOrNull(index) ?: return@mapNotNull null
                    WeatherDay(
                        date = daily.time[index],
                        weatherCode = daily.weather_code.getOrNull(index) ?: 0,
                        max = max,
                        min = min,
                        precipitationProbability = daily.precipitation_probability_max.getOrNull(index),
                    )
                }
            },
            fetchedAt = System.currentTimeMillis(),
        )
    }

    private suspend fun resolve(query: String): GeoPlace {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) throw WeatherException(host.t("请告诉我城市名", "Tell me a city name"))
        coordinates(trimmed)?.let { (latitude, longitude) ->
            return GeoPlace(name = "${latitude.format()}, ${longitude.format()}", latitude = latitude, longitude = longitude)
        }
        val url = "https://geocoding-api.open-meteo.com/v1/search?name=" +
            URLEncoder.encode(trimmed, "UTF-8") +
            "&count=1&language=zh&format=json"
        val response = host.network().request(method = "GET", url = url)
        if (response.statusCode !in 200..299) {
            throw WeatherException(host.t("找不到这个城市", "Could not find that city"))
        }
        val results = runCatching { json.decodeFromString(GeoResponse.serializer(), response.body) }
            .getOrNull()
            ?.results
            .orEmpty()
        return results.firstOrNull() ?: throw WeatherException(host.t("找不到这个城市", "Could not find that city"))
    }

    private fun coordinates(query: String): Pair<Double, Double>? {
        val parts = query.split(',', '，', ' ').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size != 2) return null
        val latitude = parts[0].toDoubleOrNull() ?: return null
        val longitude = parts[1].toDoubleOrNull() ?: return null
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
        return latitude to longitude
    }

    private fun Double.format(): String = if (this % 1.0 == 0.0) toLong().toString() else toString()

}
