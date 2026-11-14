package io.github.whywood.deepdeep.plugin.weather

import kotlinx.serialization.Serializable

@Serializable
internal data class GeoResponse(val results: List<GeoPlace> = emptyList())

@Serializable
internal data class GeoPlace(
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val country: String? = null,
    val country_code: String? = null,
    val admin1: String? = null,
    val timezone: String? = null,
)

@Serializable
internal data class ForecastResponse(
    val timezone: String? = null,
    val current: ForecastCurrent? = null,
    val daily: ForecastDaily? = null,
)

@Serializable
internal data class ForecastCurrent(
    val time: String? = null,
    val temperature_2m: Double? = null,
    val apparent_temperature: Double? = null,
    val relative_humidity_2m: Int? = null,
    val weather_code: Int? = null,
    val wind_speed_10m: Double? = null,
)

@Serializable
internal data class ForecastDaily(
    val time: List<String> = emptyList(),
    val weather_code: List<Int> = emptyList(),
    val temperature_2m_max: List<Double> = emptyList(),
    val temperature_2m_min: List<Double> = emptyList(),
    val precipitation_probability_max: List<Int> = emptyList(),
)

@Serializable
internal data class WeatherDetails(
    val location: WeatherLocation,
    val current: WeatherCurrent,
    val daily: List<WeatherDay>,
    val source: String = "Open-Meteo",
    val fetchedAt: Long,
)

@Serializable
internal data class WeatherLocation(
    val name: String,
    val region: String? = null,
    val country: String? = null,
    val latitude: Double,
    val longitude: Double,
    val timezone: String? = null,
)

@Serializable
internal data class WeatherCurrent(
    val temperature: Double,
    val apparentTemperature: Double? = null,
    val humidity: Int? = null,
    val windSpeed: Double? = null,
    val weatherCode: Int,
)

@Serializable
internal data class WeatherDay(
    val date: String,
    val weatherCode: Int,
    val max: Double,
    val min: Double,
    val precipitationProbability: Int? = null,
)
