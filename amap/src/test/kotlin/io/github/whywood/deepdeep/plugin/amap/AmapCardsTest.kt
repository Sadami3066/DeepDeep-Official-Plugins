package io.github.whywood.deepdeep.plugin.amap

import io.github.whywood.deepdeep.plugin.api.textResult
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
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
