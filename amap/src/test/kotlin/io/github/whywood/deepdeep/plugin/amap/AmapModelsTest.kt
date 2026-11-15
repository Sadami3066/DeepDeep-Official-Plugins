package io.github.whywood.deepdeep.plugin.amap

import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AmapModelsTest {
    private fun objectOf(raw: String): JsonObject = AmapJson.parseToJsonElement(raw) as JsonObject

    @Test
    fun parsesTextSearchPlacesFromServerShape() {
        val payload = objectOf(
            """
            {
              "suggestion": {"keywords": "西湖", "ciytes": []},
              "pois": [
                {"id": "B000A8UIN8", "name": "西湖风景名胜区", "address": "龙井路1号", "typecode": "110000", "photos": []},
                {"id": "B0FFH", "name": "断桥残雪", "address": "北山街", "typecode": "110000"}
              ]
            }
            """.trimIndent(),
        )

        val places = parsePlaces(payload)

        assertEquals(2, places.size)
        assertEquals("西湖风景名胜区", places[0].name)
        assertEquals("龙井路1号", places[0].address)
        assertNull(places[0].typeName)
    }

    @Test
    fun parsesRawAmapPlacesWithCoordinates() {
        val payload = objectOf(
            """
            {
              "pois": [
                {
                  "id": "B0FFH",
                  "name": "西湖",
                  "address": "西湖区",
                  "location": "120.145,30.256",
                  "type": "风景名胜",
                  "distance": "850",
                  "cityname": "杭州市",
                  "tel": []
                }
              ]
            }
            """.trimIndent(),
        )

        val places = parsePlaces(payload)

        assertEquals(1, places.size)
        assertEquals("120.145,30.256", places[0].location?.wire)
        assertEquals(850, places[0].distanceMeters)
        assertEquals("杭州市", places[0].cityName)
    }

    @Test
    fun parsesGeocodeReturnArray() {
        val payload = objectOf(
            """
            {
              "return": [
                {
                  "formatted_address": "浙江省杭州市西湖区北山街",
                  "province": "浙江省",
                  "city": "杭州市",
                  "district": "西湖区",
                  "adcode": "330106",
                  "location": "120.14,30.26",
                  "level": "门牌号"
                }
              ]
            }
            """.trimIndent(),
        )

        val places = parsePlaces(payload)

        assertEquals(1, places.size)
        assertEquals("浙江省杭州市西湖区北山街", places[0].name)
        assertEquals("门牌号", places[0].typeName)
        assertEquals("120.14,30.26", places[0].location?.wire)
    }

    @Test
    fun parsesRawGeocodesArray() {
        val payload = objectOf(
            """
            {
              "status": "1",
              "geocodes": [
                {"formatted_address": "浙江省杭州市西湖区北山街", "province": "浙江省", "city": "杭州市", "location": "120.14,30.26", "level": "门牌号"}
              ]
            }
            """.trimIndent(),
        )

        val places = parsePlaces(payload)

        assertEquals(1, places.size)
        assertEquals("浙江省杭州市西湖区北山街", places[0].name)
        assertEquals("120.14,30.26", places[0].location?.wire)
    }

    @Test
    fun parsesRegeoFromRawShape() {
        val payload = objectOf(
            """
            {
              "status": "1",
              "regeocode": {
                "formatted_address": "浙江省杭州市西湖区北山街",
                "addressComponent": {
                  "province": "浙江省",
                  "city": "杭州市",
                  "district": "西湖区",
                  "township": "北山街道",
                  "street": "北山街",
                  "number": "1号",
                  "location": "120.14,30.26"
                }
              }
            }
            """.trimIndent(),
        )

        val regeo = parseRegeo(payload)

        assertEquals("浙江省杭州市西湖区北山街", regeo?.formattedAddress)
        assertEquals("杭州市", regeo?.city)
        assertEquals("北山街道", regeo?.township)
        assertEquals("120.14,30.26", regeo?.location?.wire)
    }

    @Test
    fun parsesRegeoFromNormalizedShape() {
        val payload = objectOf("""{"provice":"浙江省","city":"杭州市","district":"西湖区"}""")

        val regeo = parseRegeo(payload)

        assertEquals("浙江省杭州市西湖区", regeo?.formattedAddress)
        assertEquals("浙江省", regeo?.province)
    }

    @Test
    fun parsesDrivingRoute() {
        val payload = objectOf(
            """
            {
              "route": {
                "origin": "120.1,30.2",
                "destination": "120.3,30.4",
                "paths": [
                  {
                    "distance": "12345",
                    "duration": "1500",
                    "steps": [
                      {"instruction": "向北行驶", "road": "文一西路", "distance": "500", "duration": "60"},
                      {"instruction": "右转进入学院路", "road": "学院路", "distance": "1200", "duration": "180"}
                    ]
                  }
                ]
              }
            }
            """.trimIndent(),
        )

        val route = parseRoute(payload, AmapRouteMode.DRIVING)

        assertEquals(12345, route?.distanceMeters)
        assertEquals(1500, route?.durationSeconds)
        assertEquals(2, route?.steps?.size)
        assertEquals("文一西路", route?.steps?.get(0)?.road)
        assertEquals("120.1,30.2", route?.origin?.wire)
    }

    @Test
    fun parsesBicyclingRouteFromDataWrapper() {
        val payload = objectOf(
            """
            {
              "data": {
                "origin": "120.1,30.2",
                "destination": "120.3,30.4",
                "paths": [
                  {"distance": 3200, "duration": 900, "steps": [{"instruction": "沿河骑行", "road": "沿河路"}]}
                ]
              }
            }
            """.trimIndent(),
        )

        val route = parseRoute(payload, AmapRouteMode.BICYCLING)

        assertEquals(3200, route?.distanceMeters)
        assertEquals(900, route?.durationSeconds)
        assertEquals(1, route?.steps?.size)
    }

    @Test
    fun parsesTransitRouteSegments() {
        val payload = objectOf(
            """
            {
              "route": {
                "origin": "120.1,30.2",
                "destination": "120.3,30.4",
                "distance": "8600",
                "transits": [
                  {
                    "duration": "2400",
                    "walking_distance": "600",
                    "segments": [
                      {
                        "walking": {"distance": "300", "steps": []},
                        "bus": {
                          "buslines": [
                            {"name": "地铁1号线(湘湖--萧山机场)", "departure_stop": {"name": "龙翔桥"}, "arrival_stop": {"name": "武林广场"}}
                          ]
                        },
                        "entrance": {"name": "龙翔桥站"},
                        "exit": {"name": "武林广场站"},
                        "railway": {"name": [], "trip": []}
                      }
                    ]
                  }
                ]
              }
            }
            """.trimIndent(),
        )

        val route = parseRoute(payload, AmapRouteMode.TRANSIT)

        assertEquals(8600, route?.distanceMeters)
        assertEquals(2400, route?.durationSeconds)
        assertEquals(1, route?.transitLines?.size)
        assertTrue(route?.transitLines?.get(0)?.lineName.orEmpty().contains("地铁1号线"))
        assertEquals(300, route?.transitLines?.get(0)?.walkingMeters)
    }

    @Test
    fun parsesWeatherWithCasts() {
        val payload = objectOf(
            """
            {
              "forecasts": [
                {
                  "city": "杭州市",
                  "casts": [
                    {"date": "2026-11-15", "dayweather": "晴", "nightweather": "多云", "daytemp": "20", "nighttemp": "12", "daywind": "东北风", "nightwind": "东风", "daypower": "3级", "nightpower": "2级"},
                    {"date": "2026-11-16", "dayweather": "小雨", "nightweather": "阴", "daytemp": "18", "nighttemp": "11"}
                  ]
                }
              ]
            }
            """.trimIndent(),
        )

        val weather = parseWeather(payload)

        assertEquals("杭州市", weather?.city)
        assertEquals(2, weather?.days?.size)
        assertEquals("晴", weather?.days?.get(0)?.dayWeather)
        assertEquals(20, weather?.days?.get(0)?.dayTemp)
        assertEquals("东北风", weather?.days?.get(0)?.dayWind)
    }

    @Test
    fun parsesFlatWeatherForecasts() {
        val payload = objectOf(
            """
            {
              "city": "杭州",
              "forecasts": [
                {"date": "2026-11-15", "dayweather": "晴", "nightweather": "多云", "daytemp": "21", "nighttemp": "13"}
              ]
            }
            """.trimIndent(),
        )

        val weather = parseWeather(payload)

        assertEquals("杭州", weather?.city)
        assertEquals(1, weather?.days?.size)
        assertEquals(21, weather?.days?.get(0)?.dayTemp)
    }

    @Test
    fun parsesLiveWeather() {
        val payload = objectOf(
            """
            {
              "lives": [
                {"city": "杭州市", "weather": "晴", "temperature": "22", "winddirection": "东南风", "windpower": "2级", "reporttime": "2026-11-15 10:00:00"}
              ]
            }
            """.trimIndent(),
        )

        val weather = parseWeather(payload)

        assertEquals("杭州市", weather?.city)
        assertEquals("晴", weather?.days?.get(0)?.dayWeather)
        assertEquals(22, weather?.days?.get(0)?.dayTemp)
    }

    @Test
    fun parsesJsonWithLeadingAndTrailingNoise() {
        val text = """
            正在加载数据...
            {"pois":[{"name":"西湖","location":"120.1,30.2"}]}
            完成
        """.trimIndent()

        val payload = parseJsonText(text)

        assertEquals(1, parsePlaces(payload).size)
    }

    @Test
    fun parsesJsonWithBracesInsideStrings() {
        val text = """{"note":"a } b","pois":[{"name":"西湖"}]}"""

        val payload = parseJsonText(text)

        assertEquals("西湖", parsePlaces(payload).firstOrNull()?.name)
    }

    @Test
    fun classifiesAmapFailures() {
        assertEquals(AmapFailure.INVALID_KEY, classifyAmapFailure(401, ""))
        assertEquals(AmapFailure.INVALID_KEY, classifyAmapFailure(200, """{"status":"0","info":"INVALID_USER_KEY"}"""))
        assertEquals(AmapFailure.QUOTA, classifyAmapFailure(200, """{"info":"DAILY_QUERY_OVER_LIMIT"}"""))
        assertEquals(AmapFailure.SERVICE, classifyAmapFailure(503, ""))
        assertEquals(AmapFailure.UNKNOWN, classifyAmapFailure(200, "something else"))
    }

    @Test
    fun buildsNavigationUriWithNames() {
        val uri = AmapUri.navigation(
            origin = LatLng(120.1, 30.2),
            destination = LatLng(120.3, 30.4),
            mode = AmapRouteMode.DRIVING,
            originName = "起点",
            destinationName = "终点",
        )

        assertEquals(
            "https://uri.amap.com/navigation?from=120.1,30.2,%E8%B5%B7%E7%82%B9&to=120.3,30.4,%E7%BB%88%E7%82%B9&mode=car&policy=1&coordinate=gaode&callnative=1",
            uri,
        )
    }

    @Test
    fun buildsMarkerAndSearchUri() {
        assertEquals(
            "https://uri.amap.com/marker?position=120.1,30.2&name=%E8%A5%BF%E6%B9%96&coordinate=gaode&callnative=1",
            AmapUri.marker(LatLng(120.1, 30.2), "西湖"),
        )
        assertEquals(
            "https://uri.amap.com/search?keyword=%E9%A4%90%E9%A6%86&city=%E6%9D%AD%E5%B7%9E&coordinate=gaode&callnative=1",
            AmapUri.search("餐馆", "杭州"),
        )
    }

    @Test
    fun formatsCoordinatesWithoutTrailingZeros() {
        assertEquals("120.1", formatCoordinate(120.1))
        assertEquals("120", formatCoordinate(120.0))
        assertEquals("30.256", formatCoordinate(30.256))
    }

    @Test
    fun formatsShortDateLabels() {
        assertEquals("11/15", shortDateLabel("2026-11-15"))
        assertEquals("11/15", shortDateLabel("2026-11-15 10:00:00"))
        assertNull(shortDateLabel(null))
    }

    @Test
    fun parsesAndValidatesCoordinates() {
        assertEquals(LatLng(120.1, 30.2), LatLng.parse("120.1,30.2"))
        assertEquals("120.1,30.2", LatLng.parse("120.1,30.2")?.wire)
        assertNull(LatLng.parse("120.1"))
        assertNull(LatLng.parse("200,30"))
        assertNull(LatLng.parse("120,100"))
        assertNull(LatLng.parse("abc,def"))
    }

    @Test
    fun decodesPlaceDetailsForCards() {
        val details = objectOf(
            """
            {
              "kind": "places",
              "query": "西湖",
              "city": "杭州",
              "items": [
                {"name": "西湖", "address": "西湖区", "typeName": "风景名胜", "distanceMeters": 850, "longitude": 120.14, "latitude": 30.25}
              ]
            }
            """.trimIndent(),
        )

        val decoded = decodePlacesDetails(details)

        assertEquals("西湖", decoded.query)
        assertEquals(1, decoded.items.size)
        assertEquals("西湖", decoded.items[0].name)
        assertEquals(850, decoded.items[0].distanceMeters)
        assertEquals(120.14, decoded.items[0].location?.longitude ?: 0.0, 0.0001)
    }

    @Test
    fun decodesRouteDetailsForCards() {
        val details = objectOf(
            """
            {
              "kind": "route",
              "mode": "driving",
              "originName": "公司",
              "destinationName": "机场",
              "origin": {"longitude": 120.1, "latitude": 30.2},
              "destination": {"longitude": 120.3, "latitude": 30.4},
              "distanceMeters": 12345,
              "durationSeconds": 1500,
              "steps": [{"instruction": "向北行驶", "road": "文一西路", "distanceMeters": 500}],
              "transitLines": [],
              "navigationUrl": "https://uri.amap.com/navigation?x=1"
            }
            """.trimIndent(),
        )

        val decoded = decodeRouteDetails(details)

        assertEquals(AmapRouteMode.DRIVING, decoded?.mode)
        assertEquals("公司", decoded?.originName)
        assertEquals(12345, decoded?.distanceMeters)
        assertEquals(1, decoded?.steps?.size)
        assertEquals("https://uri.amap.com/navigation?x=1", decoded?.navigationUrl)
    }

    @Test
    fun decodesWeatherDetailsForCards() {
        val details = objectOf(
            """
            {
              "kind": "weather",
              "city": "杭州",
              "days": [{"date": "2026-11-15", "dayWeather": "晴", "nightWeather": "多云", "dayTemp": 20, "nightTemp": 12}]
            }
            """.trimIndent(),
        )

        val decoded = decodeWeatherDetails(details)

        assertEquals("杭州", decoded?.city)
        assertEquals(1, decoded?.days?.size)
        assertEquals(20, decoded?.days?.get(0)?.dayTemp)
    }

    @Test
    fun decodesRegeoDetailsForCards() {
        val details = objectOf(
            """
            {
              "kind": "regeo",
              "formattedAddress": "浙江省杭州市西湖区北山街",
              "province": "浙江省",
              "city": "杭州市",
              "district": "西湖区",
              "longitude": 120.14,
              "latitude": 30.26
            }
            """.trimIndent(),
        )

        val decoded = decodeRegeoDetails(details)

        assertEquals("浙江省杭州市西湖区北山街", decoded?.formattedAddress)
        assertEquals("杭州市", decoded?.city)
    }

    @Test
    fun parsesMcpEnvelopeFromPlainAndSseBodies() {
        val plain = AmapMcpProtocol.envelope("""{"jsonrpc":"2.0","id":1,"result":{"tools":[{"name":"maps_weather"}]}}""")
        assertEquals(listOf("maps_weather"), AmapMcpProtocol.tools(plain!!))

        val sse = AmapMcpProtocol.envelope(
            """
            event: message
            data: {"jsonrpc":"2.0","id":2,"result":{"content":[{"type":"text","text":"ok"}]}}

            """.trimIndent(),
        )
        assertEquals("ok", AmapMcpProtocol.contentText(sse!!["result"] as JsonObject))
    }

    @Test
    fun parsesMultiLineSseEnvelope() {
        val body = """
            event: message
            data: {"jsonrpc":"2.0",
            data: "id":3,"result":{"content":[{"type":"text","text":"hi"}]}}

        """.trimIndent()

        val envelope = AmapMcpProtocol.envelope(body)

        assertEquals("hi", AmapMcpProtocol.contentText(envelope!!["result"] as JsonObject))
    }

    @Test
    fun readsSessionIdHeaderCaseInsensitively() {
        assertEquals("abc", AmapMcpProtocol.sessionId(mapOf("mcp-session-id" to "abc")))
        assertEquals("xyz", AmapMcpProtocol.sessionId(mapOf("Mcp-Session-Id" to " xyz ")))
        assertNull(AmapMcpProtocol.sessionId(mapOf("other" to "1")))
    }

    @Test
    fun detectsExpiredSession() {
        val expired = objectOf("""{"jsonrpc":"2.0","id":2,"error":{"code":-32001,"message":"Session not found"}}""")
        assertTrue(AmapMcpProtocol.isSessionExpired(expired))

        val normal = objectOf("""{"jsonrpc":"2.0","id":2,"result":{}}""")
        assertFalse(AmapMcpProtocol.isSessionExpired(normal))
    }

    @Test
    fun hidesNumericTypeCodesWhenParsingPlaces() {
        val payload = objectOf(
            """
            {
              "pois": [
                {"name": "瑞幸咖啡", "typecode": "050500", "location": "120.1,30.2"},
                {"name": "西湖", "type": "风景名胜", "location": "120.1,30.2"}
              ]
            }
            """.trimIndent(),
        )

        val places = parsePlaces(payload)

        assertEquals(2, places.size)
        assertNull(places[0].typeName)
        assertEquals("风景名胜", places[1].typeName)
    }

    @Test
    fun hidesNumericTypeNameWhenDecodingPlaceDetails() {
        val details = objectOf(
            """
            {
              "kind": "places",
              "items": [
                {"name": "瑞幸咖啡", "typeName": "050500"},
                {"name": "西湖", "typeName": "风景名胜"}
              ]
            }
            """.trimIndent(),
        )

        val decoded = decodePlacesDetails(details)

        assertEquals(2, decoded.items.size)
        assertNull(decoded.items[0].typeName)
        assertEquals("风景名胜", decoded.items[1].typeName)
    }

}
