package io.github.whywood.deepdeep.plugin.weather

import io.github.whywood.deepdeep.plugin.api.PluginHost
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

internal class WeatherException(message: String) : Exception(message)

internal class WeatherService(private val host: PluginHost) {
    private val json = Json {
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
        val body = request(url)
        val parsed = runCatching { json.decodeFromString(ForecastResponse.serializer(), body) }
            .getOrElse { throw WeatherException(parseFailed()) }
        val current = parsed.current ?: throw WeatherException(incomplete())
        val daily = parsed.daily
        val currentTemperature = current.temperature_2m ?: throw WeatherException(incomplete())
        return WeatherDetails(
            location = WeatherLocation(
                name = place.name,
                region = listOfNotNull(place.admin1, place.country).firstOrNull { it.isNotBlank() },
                country = place.country,
                latitude = place.latitude,
                longitude = place.longitude,
                timezone = parsed.timezone ?: place.timezone,
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
        if (trimmed.isEmpty()) throw WeatherException(host.t("请输入城市名", "Enter a city name"))
        coordinates(trimmed)?.let { (latitude, longitude) ->
            return GeoPlace(name = "${latitude.format()}, ${longitude.format()}", latitude = latitude, longitude = longitude)
        }
        val language = if (host.app().info.language.startsWith("zh")) "zh" else "en"
        val url = "https://geocoding-api.open-meteo.com/v1/search?name=" +
            URLEncoder.encode(trimmed, "UTF-8") +
            "&count=1&language=$language&format=json"
        val body = request(url)
        val results = runCatching { json.decodeFromString(GeoResponse.serializer(), body) }
            .getOrNull()
            ?.results
            .orEmpty()
        return results.firstOrNull() ?: throw WeatherException(cityNotFound())
    }

    private suspend fun request(url: String): String = withContext(Dispatchers.IO) {
        val connection = try {
            (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 20_000
                readTimeout = 20_000
                setRequestProperty("Accept", "application/json")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            host.logs().warn("weather connection failed", error)
            throw WeatherException(networkFailed())
        }
        try {
            val status = connection.responseCode
            if (status !in 200..299) throw WeatherException(statusMessage(status))
            connection.inputStream.bufferedReader().use { it.readText() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: WeatherException) {
            throw error
        } catch (error: Throwable) {
            host.logs().warn("weather request failed", error)
            throw WeatherException(networkFailed())
        } finally {
            connection.disconnect()
        }
    }

    private fun statusMessage(code: Int): String = when {
        code == 429 -> host.t(
            "查询太频繁了，请稍后再试。",
            "Too many requests. Try again in a moment.",
        )
        code in 500..599 -> host.t(
            "天气服务暂时不可用，请稍后重试。",
            "The weather service is unavailable. Try again later.",
        )
        else -> host.t("天气服务返回 $code，请稍后重试。", "The weather service returned $code. Try again later.")
    }

    private fun networkFailed(): String =
        host.t("网络连接失败，请检查网络后重试。", "Network error. Check your connection and try again.")

    private fun parseFailed(): String =
        host.t("天气数据解析失败，请稍后重试。", "Could not parse the weather data. Try again later.")

    private fun incomplete(): String =
        host.t("天气数据不完整，请稍后重试。", "The weather data is incomplete. Try again later.")

    private fun cityNotFound(): String =
        host.t("找不到这个城市，换个名称试试。", "Could not find that city. Try a different name.")

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
