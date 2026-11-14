package io.github.whywood.deepdeep.plugin.weather

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherLogicTest {
    private val zh = FakeWeatherHost("zh")
    private val en = FakeWeatherHost("en")

    @Test
    fun mapsWeatherCodesToConditions() {
        assertEquals(WeatherCondition.CLEAR, weatherCondition(0))
        assertEquals(WeatherCondition.MOSTLY_CLEAR, weatherCondition(1))
        assertEquals(WeatherCondition.PARTLY_CLOUDY, weatherCondition(2))
        assertEquals(WeatherCondition.OVERCAST, weatherCondition(3))
        assertEquals(WeatherCondition.FOG, weatherCondition(48))
        assertEquals(WeatherCondition.DRIZZLE, weatherCondition(53))
        assertEquals(WeatherCondition.FREEZING_RAIN, weatherCondition(67))
        assertEquals(WeatherCondition.SNOW, weatherCondition(73))
        assertEquals(WeatherCondition.SHOWERS, weatherCondition(82))
        assertEquals(WeatherCondition.SNOW_SHOWERS, weatherCondition(86))
        assertEquals(WeatherCondition.THUNDERSTORM, weatherCondition(99))
        assertEquals(WeatherCondition.UNKNOWN, weatherCondition(42))
        assertEquals(WeatherCondition.UNKNOWN, weatherCondition(-1))
    }

    @Test
    fun mapsConditionsToGlyphs() {
        assertEquals(WeatherGlyph.SUN, weatherGlyph(0))
        assertEquals(WeatherGlyph.SUN, weatherGlyph(1))
        assertEquals(WeatherGlyph.CLOUD, weatherGlyph(2))
        assertEquals(WeatherGlyph.CLOUD, weatherGlyph(3))
        assertEquals(WeatherGlyph.CLOUD, weatherGlyph(61))
        assertEquals(WeatherGlyph.CLOUD, weatherGlyph(95))
        assertEquals(WeatherGlyph.CLOUD, weatherGlyph(42))
    }

    @Test
    fun validatesForecastDays() {
        assertNull(forecastDays(0))
        assertNull(forecastDays(8))
        assertNull(forecastDays(-1))
        assertEquals(3, forecastDays(null))
        assertEquals(1, forecastDays(1))
        assertEquals(5, forecastDays(5))
        assertEquals(7, forecastDays(7))
    }

    @Test
    fun mapsDaysToSegmentIndex() {
        assertEquals(0, pageDaysIndex(3))
        assertEquals(0, pageDaysIndex(4))
        assertEquals(0, pageDaysIndex(null))
        assertEquals(1, pageDaysIndex(5))
        assertEquals(1, pageDaysIndex(6))
        assertEquals(2, pageDaysIndex(7))
    }

    @Test
    fun limitsCardDays() {
        assertEquals(3, cardDayCount(7, 3))
        assertEquals(5, cardDayCount(7, 7))
        assertEquals(5, cardDayCount(6, 5))
        assertEquals(2, cardDayCount(2, 3))
        assertEquals(1, cardDayCount(7, 1))
        assertEquals(0, cardDayCount(0, 3))
        assertEquals(0, cardDayCount(0, 0))
    }

    @Test
    fun normalizesRecentCity() {
        assertNull(normalizeRecentCity(null))
        assertNull(normalizeRecentCity("   "))
        assertEquals("杭州", normalizeRecentCity("  杭州 "))
        assertEquals("New York", normalizeRecentCity("New   York"))
        assertEquals(80, normalizeRecentCity("x".repeat(120)).orEmpty().length)
    }

    @Test
    fun parsesPageArguments() {
        val parsed = parseWeatherPageArgs(
            buildJsonObject {
                put(ARG_LOCATION, " 杭州 ")
                put(ARG_DAYS, 5)
            },
        )
        assertEquals("杭州", parsed.location)
        assertEquals(5, parsed.days)

        val empty = parseWeatherPageArgs(buildJsonObject {})
        assertNull(empty.location)
        assertNull(empty.days)

        val blank = parseWeatherPageArgs(buildJsonObject { put(ARG_LOCATION, "   ") })
        assertNull(blank.location)
    }

    @Test
    fun resolvesInitialLocation() {
        assertEquals("杭州", initialLocation(WeatherPageArgs(location = "杭州", days = 5), "", null))
        assertEquals("杭州", initialLocation(null, "杭州", "上海"))
        assertEquals("上海", initialLocation(null, "  ", "上海"))
        assertNull(initialLocation(null, "", null))
        val ignoredInvalidDays = parseWeatherPageArgs(buildJsonObject { put(ARG_DAYS, "many") })
        assertNull(ignoredInvalidDays.days)
    }

    @Test
    fun labelsForecastDays() {
        assertEquals("今天", dayLabel("2024-01-08", 0, chinese = true))
        assertEquals("Today", dayLabel("2024-01-08", 0, chinese = false))
        assertEquals("明天", dayLabel("2024-01-08", 1, chinese = true))
        assertEquals("Tomorrow", dayLabel("2024-01-08", 1, chinese = false))
        assertEquals("周一", dayLabel("2024-01-08", 2, chinese = true))
        assertEquals("Mon", dayLabel("2024-01-08", 2, chinese = false))
        assertEquals("周日", dayLabel("2024-01-14", 3, chinese = true))
        assertEquals("not-a-date", dayLabel("not-a-date", 2, chinese = true))
    }

    @Test
    fun summarizesCurrentAndForecast() {
        val summary = weatherSummary(zh, details(name = "杭州", region = "浙江省"), 3)
        assertTrue(summary, summary.contains("杭州"))
        assertTrue(summary, summary.contains("23°C"))
        assertTrue(summary, summary.contains("多云"))
        assertTrue(summary, summary.contains("体感 24.5°"))
        assertTrue(summary, summary.contains("湿度 62%"))
        assertTrue(summary, summary.contains("降水概率 10%"))
        assertTrue(summary, summary.contains("今天"))
        assertTrue(summary, summary.contains("明天"))
        assertTrue(summary, summary.contains("周三"))
        assertFalse(summary, summary.contains("周四"))

        val english = weatherSummary(en, details(name = "Hangzhou", region = "Zhejiang"), 2)
        assertTrue(english, english.contains("Hangzhou"))
        assertTrue(english, english.contains("Partly cloudy"))
        assertTrue(english, english.contains("Feels 24.5°"))
        assertTrue(english, english.contains("Rain chance 10%"))
        assertTrue(english, english.contains("Today"))
        assertTrue(english, english.contains("Tomorrow"))
        assertFalse(english, english.contains("Wed"))
    }

    @Test
    fun roundsTemperatures() {
        assertEquals("21", 21.0.rounded())
        assertEquals("21.5", 21.5.rounded())
        assertEquals("-3", (-3.0).rounded())
    }

    private fun details(name: String, region: String): WeatherDetails = WeatherDetails(
        location = WeatherLocation(
            name = name,
            region = region,
            country = "China",
            latitude = 30.25,
            longitude = 120.17,
        ),
        current = WeatherCurrent(
            temperature = 23.0,
            apparentTemperature = 24.5,
            humidity = 62,
            windSpeed = 12.0,
            weatherCode = 2,
        ),
        daily = listOf(
            WeatherDay(date = "2024-01-08", weatherCode = 0, max = 21.0, min = 12.0, precipitationProbability = 10),
            WeatherDay(date = "2024-01-09", weatherCode = 61, max = 18.5, min = 10.0, precipitationProbability = 80),
            WeatherDay(date = "2024-01-10", weatherCode = 3, max = 15.0, min = 8.0, precipitationProbability = null),
        ),
        fetchedAt = 0L,
    )
}
