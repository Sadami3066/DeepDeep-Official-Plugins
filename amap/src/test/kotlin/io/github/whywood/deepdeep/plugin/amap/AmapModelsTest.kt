package io.github.whywood.superpi.plugin.amap

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
        assertEquals("B000A8UIN8", places[0].id)
        assertEquals("西湖风景名胜区", places[0].name)
        assertEquals("龙井路1号", places[0].address)
        assertEquals("110000", places[0].typecode)
        assertEquals("风景名胜", places[0].typeName)
        assertTrue(places[0].photos.isEmpty())
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
        assertEquals("风景名胜", places[0].typeName)
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
        val payload = objectOf(
            """{"country":"中国","province":"广东省","city":"广州市","district":"海珠区"}""",
        )

        val regeo = parseRegeo(payload)

        assertEquals("广东省广州市海珠区", regeo?.formattedAddress)
        assertEquals("中国", regeo?.country)
        assertEquals("广东省", regeo?.province)
        assertEquals("海珠区", regeo?.district)
        assertNull(regeo?.street)
        assertNull(regeo?.location)
    }

    @Test
    fun regeoDeduplicatesMunicipalityProvinceAndCity() {
        val payload = objectOf(
            """{"country":"中国","province":"北京市","city":"北京市","district":"朝阳区"}""",
        )

        val regeo = parseRegeo(payload)

        assertEquals("北京市朝阳区", regeo?.formattedAddress)
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
        assertEquals(AmapFailure.QUOTA, classifyAmapFailure(429, ""))
        assertEquals(AmapFailure.PARAMETER, classifyAmapFailure(400, """{"status":"0","info":"INVALID_PARAMS"}"""))
        assertEquals(AmapFailure.PARAMETER, classifyAmapFailure(422, ""))
        assertEquals(AmapFailure.SERVICE, classifyAmapFailure(503, ""))
        assertEquals(AmapFailure.PROTOCOL, classifyAmapFailure(404, ""))
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
                {"id": "B000A8UIN8", "name": "西湖", "address": "西湖区", "typecode": "110000", "typeName": "050500", "photos": ["http://a/1.jpg"], "longitude": 120.14, "latitude": 30.25}
              ]
            }
            """.trimIndent(),
        )

        val decoded = decodePlacesDetails(details)

        assertEquals("西湖", decoded.query)
        assertEquals(1, decoded.items.size)
        assertEquals("B000A8UIN8", decoded.items[0].id)
        assertEquals("西湖", decoded.items[0].name)
        assertEquals("咖啡厅", decoded.items[0].typeName)
        assertEquals(listOf("https://a/1.jpg"), decoded.items[0].photos)
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
    fun mapsNumericTypeCodesToChineseLabelsWhenParsingPlaces() {
        val payload = objectOf(
            """
            {
              "pois": [
                {"name": "瑞幸咖啡", "typecode": "050500", "location": "120.1,30.2"},
                {"name": "中山大学", "typecode": "141201", "location": "120.1,30.2"},
                {"name": "西湖", "type": "风景名胜", "location": "120.1,30.2"}
              ]
            }
            """.trimIndent(),
        )

        val places = parsePlaces(payload)

        assertEquals(3, places.size)
        assertEquals("咖啡厅", places[0].typeName)
        assertEquals("高等院校", places[1].typeName)
        assertEquals("风景名胜", places[2].typeName)
    }

    @Test
    fun mapsNumericTypeNameWhenDecodingPlaceDetails() {
        val details = objectOf(
            """
            {
              "kind": "places",
              "items": [
                {"name": "瑞幸咖啡", "typecode": "050500", "typeName": "050500"},
                {"name": "西湖", "typeName": "风景名胜"}
              ]
            }
            """.trimIndent(),
        )

        val decoded = decodePlacesDetails(details)

        assertEquals(2, decoded.items.size)
        assertEquals("咖啡厅", decoded.items[0].typeName)
        assertEquals("050500", decoded.items[0].typecode)
        assertEquals("风景名胜", decoded.items[1].typeName)
    }


    @Test
    fun parsesPoiIdTypecodePhotoAndHttpsUpgrade() {
        val payload = objectOf(
            """
            {
              "status": "1",
              "pois": [
                {
                  "id": "B00141IHRZ",
                  "name": "中山大学(广州校区南校园)",
                  "address": "新港西路135号",
                  "typecode": "141201",
                  "photo": "http://store.is.autonavi.com/showpic/1"
                },
                {
                  "name": "断桥残雪",
                  "typecode": "110000",
                  "photo": null
                },
                {
                  "name": "苏堤春晓",
                  "photos": [
                    {"url": "https://store.is.autonavi.com/showpic/2"},
                    {"url": "http://store.is.autonavi.com/showpic/3"}
                  ]
                }
              ]
            }
            """.trimIndent(),
        )

        val places = parsePlaces(payload)

        assertEquals(3, places.size)
        assertEquals("B00141IHRZ", places[0].id)
        assertEquals("https://store.is.autonavi.com/showpic/1", places[0].photos.single())
        assertEquals("高等院校", places[0].typeName)
        assertTrue(places[1].photos.isEmpty())
        assertEquals(
            listOf(
                "https://store.is.autonavi.com/showpic/2",
                "https://store.is.autonavi.com/showpic/3",
            ),
            places[2].photos,
        )
    }

    @Test
    fun parsesEveryDrivingPathAndStepDuration() {
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
                  },
                  {
                    "distance": "15600",
                    "duration": "2100",
                    "steps": [
                      {"instruction": "沿文一西路向西", "road": "文一西路", "distance": "900", "duration": "120"}
                    ]
                  }
                ]
              }
            }
            """.trimIndent(),
        )

        val route = parseRoute(payload, AmapRouteMode.DRIVING)

        assertEquals(2, route?.paths?.size)
        assertEquals(12345, route?.distanceMeters)
        assertEquals(60, route?.paths?.get(0)?.steps?.get(0)?.durationSeconds)
        assertEquals(2100, route?.paths?.get(1)?.durationSeconds)
        assertEquals("沿文一西路向西", route?.paths?.get(1)?.steps?.get(0)?.instruction)
    }

    @Test
    fun parsesEveryTransitOptionSegmentBuslineAndStop() {
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
                        "walking": {"distance": "300", "duration": "240"},
                        "entrance": {"name": "龙翔桥站B口"},
                        "exit": {"name": "武林广场站A口"},
                        "bus": {
                          "buslines": [
                            {
                              "name": "地铁1号线(湘湖--萧山机场)",
                              "departure_stop": {"name": "龙翔桥"},
                              "arrival_stop": {"name": "武林广场"},
                              "via_num": "3",
                              "via_stops": [{"name": "龙翔桥"}, {"name": "凤起路"}, {"name": "武林广场"}],
                              "distance": "8300",
                              "duration": "2100",
                              "start_time": "06:00",
                              "end_time": "22:30"
                            },
                            {
                              "name": "公交 12 路",
                              "departure_stop": {"name": "龙翔桥"},
                              "arrival_stop": {"name": "武林广场"}
                            }
                          ]
                        }
                      }
                    ]
                  },
                  {
                    "duration": "3000",
                    "segments": [
                      {
                        "walking": {"distance": "500", "duration": "400"},
                        "railway": {"name": "G1234", "departure_stop": "杭州东", "arrival_stop": "上海虹桥"}
                      }
                    ]
                  }
                ]
              }
            }
            """.trimIndent(),
        )

        val route = parseRoute(payload, AmapRouteMode.TRANSIT)

        assertEquals(2, route?.paths?.size)
        assertEquals(8600, route?.paths?.get(0)?.distanceMeters)
        assertNull(route?.paths?.get(1)?.distanceMeters)
        assertEquals(600, route?.paths?.get(0)?.walkingDistanceMeters)
        val first = route?.paths?.get(0)?.transitLines?.first()
        assertEquals("地铁1号线(湘湖--萧山机场) / 公交 12 路", first?.lineName)
        assertEquals(listOf("地铁1号线(湘湖--萧山机场)", "公交 12 路"), first?.buslineNames)
        assertEquals("龙翔桥", first?.departureStop)
        assertEquals("武林广场", first?.arrivalStop)
        assertEquals("龙翔桥站B口", first?.entrance)
        assertEquals("武林广场站A口", first?.exit)
        assertEquals(300, first?.walkingMeters)
        assertEquals(240, first?.walkingSeconds)
        assertEquals(3, first?.viaCount)
        assertEquals(listOf("龙翔桥", "凤起路", "武林广场"), first?.viaStops)
        assertEquals(8300, first?.lineDistanceMeters)
        assertEquals("06:00", first?.startTime)
        assertEquals(2, first?.options?.size)
        assertEquals("公交 12 路", first?.options?.get(1)?.name)
        assertEquals(8300, first?.options?.get(0)?.distanceMeters)
        val railway = route?.paths?.get(1)?.transitLines?.first()
        assertEquals("G1234", railway?.lineName)
        assertEquals("杭州东", railway?.departureStop)
        assertEquals("上海虹桥", railway?.arrivalStop)
    }

    @Test
    fun parsesEveryWeatherForecastAndCast() {
        val payload = objectOf(
            """
            {
              "forecasts": [
                {
                  "city": "杭州市",
                  "casts": [
                    {"date": "2026-11-15", "week": "1", "dayweather": "晴", "nightweather": "多云", "daytemp": "20", "nighttemp": "12", "daytemp_float": "20.0", "nighttemp_float": "12.0"},
                    {"date": "2026-11-16", "dayweather": "小雨", "nightweather": "阴", "daytemp": "18", "nighttemp": "11"}
                  ]
                },
                {
                  "city": "杭州市",
                  "casts": [
                    {"date": "2026-11-17", "dayweather": "多云", "nightweather": "晴", "daytemp": "21", "nighttemp": "13"}
                  ]
                }
              ]
            }
            """.trimIndent(),
        )

        val weather = parseWeather(payload)

        assertEquals("杭州市", weather?.city)
        assertEquals(3, weather?.days?.size)
        assertEquals("1", weather?.days?.get(0)?.week)
        assertEquals(20.0, weather?.days?.get(0)?.dayTempFloat ?: 0.0, 0.001)
        assertEquals("2026-11-17", weather?.days?.get(2)?.date)
        assertEquals(21, weather?.days?.get(2)?.dayTemp)
    }

    @Test
    fun decodesNewPlaceAndRouteDetailsFields() {
        val places = decodePlacesDetails(
            objectOf(
                """
                {
                  "kind": "places",
                  "items": [
                    {
                      "id": "B000A8UIN8",
                      "name": "西湖",
                      "typecode": "110000",
                      "typeName": "风景名胜",
                      "photos": ["https://a/1.jpg"]
                    }
                  ]
                }
                """.trimIndent(),
            ),
        )
        assertEquals("B000A8UIN8", places.items[0].id)
        assertEquals("110000", places.items[0].typecode)
        assertEquals("风景名胜", places.items[0].typeName)
        assertEquals(listOf("https://a/1.jpg"), places.items[0].photos)

        val route = decodeRouteDetails(
            objectOf(
                """
                {
                  "kind": "route",
                  "mode": "driving",
                  "distanceMeters": 1000,
                  "durationSeconds": 600,
                  "steps": [{"instruction": "直行", "distanceMeters": 1000, "durationSeconds": 600}],
                  "paths": [
                    {"distanceMeters": 1000, "durationSeconds": 600, "steps": [{"instruction": "直行", "distanceMeters": 1000, "durationSeconds": 600}]},
                    {"distanceMeters": 1300, "durationSeconds": 720, "steps": [{"instruction": "绕行", "distanceMeters": 1300}]}
                  ]
                }
                """.trimIndent(),
            ),
        )
        assertEquals(2, route?.allPaths?.size)
        assertEquals(720, route?.allPaths?.get(1)?.durationSeconds)
        assertEquals(600, route?.steps?.first()?.durationSeconds)
    }

    @Test
    fun readableTypeTakesFirstSegment() {
        assertEquals("商务住宅", readablePlaceType("商务住宅;楼宇;商务写字楼"))
        assertEquals("高等院校", readablePlaceType("141201"))
        assertEquals("风景名胜", readablePlaceType("风景名胜"))
    }


}
