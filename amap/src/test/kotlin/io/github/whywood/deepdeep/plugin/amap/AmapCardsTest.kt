package io.github.whywood.deepdeep.plugin.amap

import io.github.whywood.deepdeep.plugin.api.textResult
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AmapCardsTest {
    @Test
    fun placesCardLoadingErrorEmptyAndReady() {
        assertEquals(AmapCardPhase.LOADING, placesCardPhase(null))
        assertEquals(AmapCardPhase.ERROR, placesCardPhase(textResult("lookup failed", isError = true)))
        assertEquals(AmapCardPhase.EMPTY, placesCardPhase(textResult("no details")))
        assertEquals(
            AmapCardPhase.EMPTY,
            placesCardPhase(textResult("no items", details = buildJsonObject { put("kind", "places") })),
        )
        assertEquals(
            AmapCardPhase.READY,
            placesCardPhase(
                textResult(
                    "places",
                    details = buildJsonObject {
                        put("kind", "places")
                        put(
                            "items",
                            kotlinx.serialization.json.buildJsonArray {
                                add(buildJsonObject { put("name", "西湖") })
                            },
                        )
                    },
                ),
            ),
        )
    }

    @Test
    fun regeoCardLoadingErrorEmptyAndReady() {
        assertEquals(AmapCardPhase.LOADING, regeoCardPhase(null))
        assertEquals(AmapCardPhase.ERROR, regeoCardPhase(textResult("failed", isError = true)))
        assertEquals(AmapCardPhase.EMPTY, regeoCardPhase(textResult("missing address")))
        assertEquals(
            AmapCardPhase.READY,
            regeoCardPhase(
                textResult(
                    "regeo",
                    details = buildJsonObject {
                        put("formattedAddress", "浙江省杭州市西湖区北山街")
                        put("longitude", 120.14)
                        put("latitude", 30.26)
                    },
                ),
            ),
        )
    }

    @Test
    fun routeCardLoadingErrorEmptyAndReady() {
        assertEquals(AmapCardPhase.LOADING, routeCardPhase(null))
        assertEquals(AmapCardPhase.ERROR, routeCardPhase(textResult("failed", isError = true)))
        assertEquals(AmapCardPhase.EMPTY, routeCardPhase(textResult("no route")))
        assertEquals(
            AmapCardPhase.READY,
            routeCardPhase(
                textResult(
                    "route",
                    details = buildJsonObject {
                        put("kind", "route")
                        put("mode", "driving")
                        put("distanceMeters", 12_345)
                    },
                ),
            ),
        )
    }

    @Test
    fun weatherCardLoadingErrorEmptyAndReady() {
        assertEquals(AmapCardPhase.LOADING, weatherCardPhase(null))
        assertEquals(AmapCardPhase.ERROR, weatherCardPhase(textResult("failed", isError = true)))
        assertEquals(
            AmapCardPhase.EMPTY,
            weatherCardPhase(textResult("no days", details = buildJsonObject { put("city", "杭州") })),
        )
        assertEquals(
            AmapCardPhase.READY,
            weatherCardPhase(
                textResult(
                    "weather",
                    details = buildJsonObject {
                        put("city", "杭州")
                        put(
                            "days",
                            kotlinx.serialization.json.buildJsonArray {
                                add(buildJsonObject { put("date", "2026-11-15"); put("dayTemp", 20) })
                            },
                        )
                    },
                ),
            ),
        )
    }

    @Test
    fun transitTimelineSplitsWalkingFromEveryBusline() {
        val host = FakeAmapHost().host()
        val path = AmapRoutePath(
            distanceMeters = 8_600,
            durationSeconds = 2_400,
            walkingDistanceMeters = 600,
            transitLines = listOf(
                AmapTransitLine(
                    lineName = "地铁1号线 / 公交 12 路",
                    walkingMeters = 300,
                    walkingSeconds = 240,
                    entrance = "龙翔桥站B口",
                    exit = "出入口",
                    viaCount = 3,
                    viaStops = listOf("龙翔桥", "凤起路", "武林广场"),
                    options = listOf(
                        AmapTransitOption(
                            name = "地铁1号线(湘湖--萧山机场)",
                            departureStop = "龙翔桥",
                            arrivalStop = "武林广场",
                            viaCount = 3,
                            viaStops = listOf("龙翔桥", "凤起路", "武林广场"),
                            distanceMeters = 8_300,
                            durationSeconds = 2_100,
                            startTime = "06:00",
                            endTime = "22:30",
                        ),
                        AmapTransitOption(
                            name = "公交 12 路",
                            departureStop = "龙翔桥",
                            arrivalStop = "武林广场",
                            distanceMeters = 8_000,
                            durationSeconds = 2_400,
                        ),
                    ),
                ),
            ),
        )
        val details = AmapRouteDetails(
            mode = AmapRouteMode.TRANSIT,
            originName = "公司",
            destinationName = "机场",
            origin = LatLng(120.1, 30.2),
            destination = LatLng(120.3, 30.4),
            distanceMeters = 8_600,
            durationSeconds = 2_400,
            steps = emptyList(),
            transitLines = path.transitLines,
            navigationUrl = null,
            paths = listOf(path),
        )

        val legs = routeLegs(host, details, path)

        assertEquals(5, legs.size)
        assertEquals("步行", legs[1].title)
        assertTrue(legs[1].meta.orEmpty().contains("300 米"))
        assertTrue(legs[1].meta.orEmpty().contains("4 分钟"))
        assertEquals("地铁1号线(湘湖--萧山机场)", legs[2].title)
        assertTrue(legs[2].meta.orEmpty().contains("上车 龙翔桥，下车 武林广场"))
        assertTrue(legs[2].meta.orEmpty().contains("线路 8.3 公里"))
        assertTrue(legs[2].meta.orEmpty().contains("入口 龙翔桥站B口"))
        assertTrue(legs[2].meta.orEmpty().contains("首班 06:00，末班 22:30"))
        assertFalse(legs[2].meta.orEmpty().contains("出入口"))
        assertEquals("公交 12 路", legs[3].title)
        assertTrue(legs[3].meta.orEmpty().contains("线路 8.0 公里"))
        assertTrue(legs[2].detail.orEmpty().contains("经停"))
    }

    @Test
    fun roadTimelineOmitsBlankOrRepeatedRoad() {
        val host = FakeAmapHost().host()
        val path = AmapRoutePath(
            distanceMeters = 1_000,
            durationSeconds = 600,
            steps = listOf(
                AmapRouteStep(
                    instruction = "沿逸仙路向南行驶186米向右前方行驶",
                    road = "逸仙路",
                    distanceMeters = 186,
                    durationSeconds = 44,
                ),
                AmapRouteStep(
                    instruction = "向西行驶265米右转",
                    road = "",
                    distanceMeters = 265,
                    durationSeconds = 108,
                ),
            ),
        )
        val details = AmapRouteDetails(
            mode = AmapRouteMode.DRIVING,
            originName = null,
            destinationName = null,
            origin = LatLng(120.1, 30.2),
            destination = LatLng(120.3, 30.4),
            distanceMeters = 1_000,
            durationSeconds = 600,
            steps = path.steps,
            transitLines = emptyList(),
            navigationUrl = null,
            paths = listOf(path),
        )

        val legs = routeLegs(host, details, path)

        assertEquals("186 米 · 不到 1 分钟", legs[1].meta)
        assertEquals("265 米 · 1 分钟", legs[2].meta)
        assertFalse(legs[1].meta.orEmpty().contains("逸仙路"))
        assertFalse(legs[2].meta.orEmpty().startsWith("·"))
    }

    @Test
    fun readyCardDetailsCarryMapSpec() {
        val details = buildJsonObject {
            put("kind", "route")
            put("mode", "driving")
            put(
                "map",
                routeMapSpec(
                    origin = LatLng(120.1, 30.2),
                    destination = LatLng(120.3, 30.4),
                    polyline = listOf(LatLng(120.1, 30.2), LatLng(120.3, 30.4)),
                ).toJson(),
            )
        }

        val map = decodeMapSpec(details)

        assertEquals(2, map?.markers?.size)
        assertEquals(2, map?.polyline?.size)
    }


}
