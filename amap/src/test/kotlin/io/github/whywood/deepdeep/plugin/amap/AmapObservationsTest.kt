package io.github.whywood.deepdeep.plugin.amap

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AmapObservationsTest {
    private val localizer = zhLocalizer

    private fun assertNoCardWording(text: String) {
        listOf("卡片", "不要重复", "不要逐条", "已展示").forEach { wording ->
            assertFalse("observation must not contain \"$wording\": $text", text.contains(wording))
        }
    }

    @Test
    fun placeSearchListsNameAddressTypeDistanceAndCoordinates() {
        val text = placeSearchObservation(
            localizer = localizer,
            keywords = "西湖",
            city = "杭州",
            places = listOf(placeSample()),
        )

        assertTrue(text.contains("找到 1 个地点"))
        assertTrue(text.contains("西湖风景名胜区"))
        assertTrue(text.contains("龙井路1号"))
        assertTrue(text.contains("风景名胜"))
        assertTrue(text.contains("850 米"))
        assertTrue(text.contains("120.145, 30.256"))
        assertNoCardWording(text)
    }

    @Test
    fun placeSearchWithoutResultsKeepsKeyword() {
        val text = placeSearchObservation(localizer, "不存在的店", null, emptyList())

        assertTrue(text.contains("不存在的店"))
        assertNoCardWording(text)
    }

    @Test
    fun aroundSearchListsCenterRadiusAndDistance() {
        val text = aroundSearchObservation(
            localizer = localizer,
            center = LatLng(120.1, 30.2),
            radiusMeters = 1_000,
            places = listOf(placeSample(name = "星巴克", distanceMeters = 120)),
        )

        assertTrue(text.contains("半径 1000 米内找到 1 个地点"))
        assertTrue(text.contains("120.1, 30.2"))
        assertTrue(text.contains("距离 120 米"))
        assertNoCardWording(text)
    }

    @Test
    fun regeoReportsAddressAdministrativeAreasAndCoordinates() {
        val regeo = AmapRegeo(
            formattedAddress = "浙江省杭州市西湖区北山街",
            province = "浙江省",
            city = "杭州市",
            district = "西湖区",
            township = "北山街道",
            location = LatLng(120.14, 30.26),
        )

        val text = regeoObservation(localizer, regeo, LatLng(120.14, 30.26))

        assertTrue(text.contains("浙江省杭州市西湖区北山街"))
        assertTrue(text.contains("北山街道"))
        assertTrue(text.contains("120.14, 30.26"))
        assertNoCardWording(text)
    }

    @Test
    fun drivingRouteReportsCoordinatesDistanceDurationAndSteps() {
        val route = routeSample()

        val text = routeObservation(localizer, route, route.origin!!, route.destination!!, "公司", "机场")

        assertTrue(text.contains("驾车路线"))
        assertTrue(text.contains("公司"))
        assertTrue(text.contains("120.1, 30.2"))
        assertTrue(text.contains("120.3, 30.4"))
        assertTrue(text.contains("12.3 公里"))
        assertTrue(text.contains("25 分钟"))
        assertTrue(text.contains("向北行驶"))
        assertTrue(text.contains("文一西路"))
        assertTrue(text.contains("右转进入学院路"))
        assertNoCardWording(text)
    }

    @Test
    fun routeWithoutNamesStillReportsCoordinates() {
        val route = routeSample()

        val text = routeObservation(localizer, route, route.origin!!, route.destination!!, null, null)

        assertTrue(text.contains("120.1, 30.2"))
        assertTrue(text.contains("120.3, 30.4"))
        assertNoCardWording(text)
    }

    @Test
    fun transitRouteReportsLinesWalkingDistanceAndTiming() {
        val route = transitRouteSample()

        val text = routeObservation(localizer, route, route.origin!!, route.destination!!, null, null)

        assertTrue(text.contains("公交路线"))
        assertTrue(text.contains("地铁1号线(湘湖--萧山机场)"))
        assertTrue(text.contains("步行 300 米"))
        assertTrue(text.contains("公交 12 路"))
        assertTrue(text.contains("8.6 公里"))
        assertTrue(text.contains("40 分钟"))
        assertNoCardWording(text)
    }

    @Test
    fun weatherReportsDateWeatherTemperatureAndWind() {
        val weather = AmapWeather(
            city = "杭州市",
            days = listOf(
                AmapWeatherDay(
                    date = "2026-11-15",
                    dayWeather = "晴",
                    nightWeather = "多云",
                    dayTemp = 20,
                    nightTemp = 12,
                    dayWind = "东北风",
                    nightWind = "东风",
                    dayPower = "3级",
                    nightPower = "2级",
                ),
                AmapWeatherDay(
                    date = "2026-11-16",
                    dayWeather = "小雨",
                    nightWeather = "小雨",
                    dayTemp = 18,
                    nightTemp = 11,
                    dayWind = "北风",
                    nightWind = "北风",
                    dayPower = "2级",
                    nightPower = "2级",
                ),
            ),
        )

        val text = weatherObservation(localizer, weather, "杭州")

        assertTrue(text.contains("杭州市天气（2 天）"))
        assertTrue(text.contains("11/15"))
        assertTrue(text.contains("晴转多云"))
        assertTrue(text.contains("12°~20°"))
        assertTrue(text.contains("东北风 3级"))
        assertTrue(text.contains("11/16"))
        assertTrue(text.contains("小雨"))
        assertNoCardWording(text)
    }

    @Test
    fun placeObservationHidesNumericTypeCodes() {
        val text = placeSearchObservation(
            localizer = localizer,
            keywords = "咖啡",
            city = null,
            places = listOf(
                placeSample(name = "瑞幸咖啡", typeName = "050500", address = null, distanceMeters = null),
            ),
        )

        assertFalse(text.contains("050500"))
        assertTrue(text.contains("瑞幸咖啡"))
        assertNoCardWording(text)
    }

}
