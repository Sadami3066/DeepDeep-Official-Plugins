package io.github.whywood.deepdeep.plugin.amap

import io.github.whywood.deepdeep.plugin.api.NetworkResponse
import io.github.whywood.deepdeep.plugin.api.PluginHost
import io.github.whywood.deepdeep.plugin.api.PluginToolResult
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AmapToolsTest {
    private fun host(network: FakeMcpNetwork): PluginHost = FakeAmapHost(
        network = network,
        secrets = FakeSecrets(mapOf(AmapKeyStore.SECRET_KEY to "secret-key")),
    ).host()

    private fun args(raw: String): JsonObject = AmapJson.parseToJsonElement(raw) as JsonObject

    private suspend fun run(
        host: PluginHost,
        name: String,
        arguments: JsonObject,
    ): PluginToolResult {
        val tool = amapTools(host).first { it.name == name }
        return tool.execute(FakeToolCall(arguments))
    }

    private fun raw(result: PluginToolResult): String {
        val text = result.text.orEmpty()
        val marker = "完整返回（上游原样）：\n"
        val start = text.indexOf(marker)
        if (start < 0) return ""
        val rest = text.substring(start + marker.length)
        val guidance = rest.indexOf("\n\n使用说明：")
        val card = rest.indexOf("\n\n展示这张卡片")
        val ends = listOf(guidance, card).filter { it >= 0 }
        val end = ends.minOrNull() ?: -1
        return if (end >= 0) rest.substring(0, end) else rest
    }

    private fun guidance(result: PluginToolResult): String {
        val text = result.text.orEmpty()
        val marker = "使用说明：\n"
        val start = text.indexOf(marker)
        if (start < 0) return ""
        val rest = text.substring(start + marker.length)
        val end = rest.indexOf("\n\n展示这张卡片")
        return if (end >= 0) rest.substring(0, end) else rest
    }

    private fun card(result: PluginToolResult): JsonObject =
        result.details?.get("deepdeep.card") as JsonObject

    private fun items(result: PluginToolResult): JsonArray =
        result.details?.get("items") as JsonArray

    @Test
    fun placeSearchReturnsInnerRawGuidanceAndCardPayload() = runTest {
        val payload = """
            {"suggestion":{"keywords":"","ciytes":{"suggestion":[]}},"pois":[
              {"id":"B00141IHRZ","name":"中山大学(广州校区南校园)","address":"新港西路135号","typecode":"141201","photo":"http://store.is.autonavi.com/showpic/1"},
              {"id":"B001403454","name":"中山大学(广州校区北校园)","address":"中山二路74号","typecode":"141201","photo":null}
            ]}
        """.trimIndent()
        val network = FakeMcpNetwork(payload = { payload })
        val result = run(
            host = host(network),
            name = "amap_place_search",
            arguments = args("""{"keywords":"中山大学","city":"广州市"}"""),
        )

        assertFalse(result.isError)
        assertEquals(payload, raw(result))
        assertTrue(guidance(result).contains("2 个地点"))
        assertTrue(guidance(result).contains("没有坐标"))
        assertEquals(setOf("title"), card(result).keys)
        assertEquals("地点：中山大学（2 处）", (card(result)["title"] as JsonPrimitive).content)
        assertEquals(2, items(result).size)
        val first = items(result)[0] as JsonObject
        assertEquals("B00141IHRZ", (first["id"] as JsonPrimitive).content)
        assertEquals("141201", (first["typecode"] as JsonPrimitive).content)
        assertEquals("高等院校", (first["typeName"] as JsonPrimitive).content)
        assertEquals("https://store.is.autonavi.com/showpic/1", (first["photo"] as JsonPrimitive).content)
        assertEquals("https://store.is.autonavi.com/showpic/1", ((first["photos"] as JsonArray)[0] as JsonPrimitive).content)
        assertEquals("广州市", (result.details?.get("city") as JsonPrimitive).content)
        assertEquals("maps_text_search", network.toolCalls.last().first)
        assertNull(network.toolCalls.last().second["page_size"])
        assertNull(network.toolCalls.last().second["page_num"])
    }

    @Test
    fun searchSchemasHaveNoPagingArgumentsOrFalsePromises() {
        val host = host(FakeMcpNetwork())
        val tools = amapTools(host).associateBy { it.name }

        listOf("amap_place_search", "amap_around_search").forEach { name ->
            val properties = ((tools.getValue(name).parameters["properties"]) as JsonObject).keys
            assertFalse("$name must not expose page_size", "page_size" in properties)
            assertFalse("$name must not expose page_num", "page_num" in properties)
        }
        val descriptions = tools.values.joinToString("\n") { it.description }
        assertFalse(descriptions.contains("总条数"))
        assertFalse(descriptions.contains("page_num"))
        assertTrue(descriptions.contains("不支持翻页"))
        assertTrue(tools.getValue("amap_place_search").description.contains("数量有限"))
        assertTrue(tools.getValue("amap_around_search").description.contains("数量有限"))
        assertFalse(tools.getValue("amap_around_search").description.contains("12 条"))
        assertTrue(tools.getValue("amap_regeo").description.contains("区县"))
        assertTrue(tools.getValue("amap_route_transit").description.contains("轨迹"))
        assertTrue(tools.getValue("amap_route_driving").description.contains("1 条备选"))
        assertTrue(tools.getValue("amap_weather").description.contains("白天"))
    }

    @Test
    fun aroundSearchKeepsCenterMapWithoutFakePoiMarkers() = runTest {
        val payload = """
            {"pois":[
              {"id":"B0MUTOMQJG","name":"拌壹桶干拌冒菜","address":"新港西路135号","typecode":"050100","photo":null},
              {"id":"B0FFGCANBL","name":"第一寄宿舍","address":"新港西路135号","typecode":"120303","photo":"https://store.is.autonavi.com/showpic/2"}
            ]}
        """.trimIndent()
        val network = FakeMcpNetwork(payload = { payload })
        val result = run(
            host = host(network),
            name = "amap_around_search",
            arguments = args("""{"longitude":113.2981,"latitude":23.0965,"radius":300}"""),
        )

        assertFalse(result.isError)
        assertEquals(2, items(result).size)
        val map = result.details?.get("map") as JsonObject
        assertEquals(0, (map["markers"] as JsonArray).size)
        assertEquals("113.2981,23.0965", ((map["center"] as JsonObject).let {
            "${(it["longitude"] as JsonPrimitive).content},${(it["latitude"] as JsonPrimitive).content}"
        }))
        assertTrue(guidance(result).contains("2 个周边地点"))
        assertTrue(guidance(result).contains("关键词由高德匹配"))
        val aroundCall = network.toolCalls.last { (name, _) -> name == "maps_around_search" }.second
        assertEquals("300", (aroundCall["radius"] as JsonPrimitive).content)
        assertNull(aroundCall["page_num"])
    }

    @Test
    fun aroundSearchWithKeywordsBuildsCountAndKeywordTitle() = runTest {
        val payload = """{"pois":[{"name":"咖啡店","address":"新港西路","typecode":"050500","photo":null}]}"""
        val network = FakeMcpNetwork(payload = { payload })
        val result = run(
            host = host(network),
            name = "amap_around_search",
            arguments = args("""{"longitude":113.2981,"latitude":23.0965,"radius":800,"keywords":"咖啡"}"""),
        )

        assertFalse(result.isError)
        assertEquals("附近 1 处「咖啡」", (card(result)["title"] as JsonPrimitive).content)
        assertEquals("咖啡", network.toolCalls.last().second["keywords"]?.let { (it as JsonPrimitive).content })
    }

    @Test
    fun httpErrorKeepsUpstreamRawAndClassifiesInvalidKey() = runTest {
        val network = FakeMcpNetwork(
            failure = NetworkResponse(
                statusCode = 400,
                body = """{"status":"0","info":"INVALID_USER_KEY","detail":"key=secret-key"}""",
            ),
        )
        val result = run(
            host = host(network),
            name = "amap_place_search",
            arguments = args("""{"keywords":"西湖"}"""),
        )

        assertTrue(result.isError)
        assertTrue(raw(result).contains("INVALID_USER_KEY"))
        assertFalse(raw(result).contains("secret-key"))
        assertTrue(raw(result).contains("***"))
        assertTrue(guidance(result).contains("Key"))
        assertNull(result.details?.get("deepdeep.card"))
        assertNull(result.details?.get("kind"))
    }

    @Test
    fun serverErrorsAreServiceNotNetwork() = runTest {
        val network = FakeMcpNetwork(
            failure = NetworkResponse(statusCode = 503, body = ""),
        )
        val result = run(
            host = host(network),
            name = "amap_weather",
            arguments = args("""{"city":"广州市"}"""),
        )

        assertTrue(result.isError)
        assertEquals("HTTP 503", raw(result))
        assertTrue(guidance(result).contains("服务"))
        assertFalse(guidance(result).contains("没有到达"))
    }

    @Test
    fun jsonRpcParameterErrorGetsParameterGuidance() = runTest {
        val network = FakeMcpNetwork(
            failure = NetworkResponse(
                statusCode = 200,
                body = """{"jsonrpc":"2.0","id":1,"error":{"code":-32602,"message":"Invalid params"}}""",
            ),
        )
        val result = run(
            host = host(network),
            name = "amap_around_search",
            arguments = args("""{"longitude":113.2981,"latitude":23.0965,"radius":800,"keywords":"咖啡"}"""),
        )

        assertTrue(result.isError)
        assertTrue(raw(result).contains("Invalid params"))
        assertTrue(guidance(result).contains("参数"))
        assertNull(result.details?.get("deepdeep.card"))
    }

    @Test
    fun upstreamStatusZeroBecomesParameterErrorWithRaw() = runTest {
        val payload = """{"status":"0","info":"INVALID_PARAMS","message":"radius out of range"}"""
        val network = FakeMcpNetwork(payload = { payload })
        val result = run(
            host = host(network),
            name = "amap_around_search",
            arguments = args("""{"longitude":113.2981,"latitude":23.0965,"radius":800,"keywords":"咖啡"}"""),
        )

        assertTrue(result.isError)
        assertEquals(payload, raw(result))
        assertTrue(guidance(result).contains("参数"))
    }

    @Test
    fun structuredContentIsAppendedToRaw() = runTest {
        val payload = """{"pois":[{"name":"西湖"}]}"""
        val network = FakeMcpNetwork(payload = { payload }, includeStructuredContent = true)
        val result = run(
            host = host(network),
            name = "amap_place_search",
            arguments = args("""{"keywords":"西湖"}"""),
        )

        assertFalse(result.isError)
        assertTrue(raw(result).contains(payload))
        assertTrue(raw(result).contains("structuredContent:"))
    }

    @Test
    fun regeoReturnsDistrictAndNotesPrecision() = runTest {
        val payload = """{"country":"中国","province":"广东省","city":"广州市","district":"海珠区"}"""
        val network = FakeMcpNetwork(payload = { payload })
        val result = run(
            host = host(network),
            name = "amap_regeo",
            arguments = args("""{"longitude":113.2981,"latitude":23.0965}"""),
        )

        assertFalse(result.isError)
        assertEquals("广东省广州市海珠区", (result.details?.get("formattedAddress") as JsonPrimitive).content)
        assertEquals("中国", (result.details?.get("country") as JsonPrimitive).content)
        assertEquals("海珠区", (result.details?.get("district") as JsonPrimitive).contentOrNull)
        assertEquals("district", (result.details?.get("precision") as JsonPrimitive).content)
        assertEquals(113.2981, (result.details?.get("longitude") as JsonPrimitive).content.toDouble(), 0.0001)
        assertTrue(guidance(result).contains("只到区县"))
        assertEquals("位置：广东省广州市海珠区", (card(result)["title"] as JsonPrimitive).content)
        assertEquals(payload, raw(result))
    }

    @Test
    fun transitRouteKeepsEveryOptionAndOnlyBestDistance() = runTest {
        val payload = """
            {
              "origin":"113.2981,23.0965",
              "destination":"113.3245,23.1065",
              "distance":"3812",
              "transits":[
                {"duration":"2189","walking_distance":"1546","segments":[
                  {"walking":{"distance":"1297","duration":"1222"},"bus":{"buslines":[
                    {"name":"地铁8号线(滘心--万胜围)","distance":"1250","duration":"270","departure_stop":{"name":"鹭江"},"arrival_stop":{"name":"客村"}}
                  ]},"entrance":{"name":"B口"},"exit":{"name":""}},
                  {"walking":{"distance":"195","duration":"261"},"bus":{"buslines":[]},"entrance":{"name":""},"exit":{"name":""}}
                ]},
                {"duration":"2309","walking_distance":"1290","segments":[
                  {"walking":{"distance":"873","duration":"794"},"bus":{"buslines":[
                    {"name":"69路","distance":"1703","duration":"666","departure_stop":{"name":"康乐村1"},"arrival_stop":{"name":"珠影"}},
                    {"name":"226路","distance":"1723","duration":"631","departure_stop":{"name":"康乐村1"},"arrival_stop":{"name":"珠影"}}
                  ]},"entrance":{"name":""},"exit":{"name":"出入口"}}
                ]}
              ]
            }
        """.trimIndent()
        val network = FakeMcpNetwork(payload = { payload })
        val result = run(
            host = host(network),
            name = "amap_route_transit",
            arguments = args(
                """{"origin_longitude":113.2981,"origin_latitude":23.0965,"destination_longitude":113.3245,"destination_latitude":23.1065,"city":"广州市","cityd":"广州市"}""",
            ),
        )

        assertFalse(result.isError)
        val paths = result.details?.get("paths") as JsonArray
        assertEquals(2, paths.size)
        assertEquals(3812, ((paths[0] as JsonObject)["distanceMeters"] as JsonPrimitive).int)
        assertNull((paths[1] as JsonObject)["distanceMeters"])
        val firstLines = (paths[0] as JsonObject)["transitLines"] as JsonArray
        assertEquals(2, firstLines.size)
        val secondPathLines = (paths[1] as JsonObject)["transitLines"] as JsonArray
        val secondOptions = (secondPathLines[0] as JsonObject)["options"] as JsonArray
        assertEquals(2, secondOptions.size)
        assertEquals("226路", (secondOptions[1] as JsonObject)["name"]?.let { (it as JsonPrimitive).content })
        assertTrue(guidance(result).contains("不含轨迹"))
        assertTrue((card(result)["title"] as JsonPrimitive).content.contains("公交路线"))
        assertEquals(payload, raw(result))
    }

    @Test
    fun weatherKeepsDayNightFieldsAndWeek() = runTest {
        val payload = """
            {"city":"广州市","forecasts":[
              {"date":"2026-11-16","week":"1","dayweather":"多云","nightweather":"多云","daytemp":"26","nighttemp":"18","daywind":"北","nightwind":"北","daypower":"1-3","nightpower":"1-3","daytemp_float":"26.0","nighttemp_float":"18.0"},
              {"date":"2026-11-17","week":"2","dayweather":"小雨","nightweather":"阴","daytemp":"23","nighttemp":"15","daywind":"北","nightwind":"东北","daypower":"1-3","nightpower":"3-4"}
            ]}
        """.trimIndent()
        val network = FakeMcpNetwork(payload = { payload })
        val result = run(
            host = host(network),
            name = "amap_weather",
            arguments = args("""{"city":"广州市"}"""),
        )

        assertFalse(result.isError)
        val days = result.details?.get("days") as JsonArray
        assertEquals(2, days.size)
        val first = days[0] as JsonObject
        assertEquals("1", (first["week"] as JsonPrimitive).content)
        assertEquals("多云", (first["dayWeather"] as JsonPrimitive).content)
        assertEquals("多云", (first["nightWeather"] as JsonPrimitive).content)
        assertEquals(26, (first["dayTemp"] as JsonPrimitive).int)
        assertEquals(18, (first["nightTemp"] as JsonPrimitive).int)
        assertEquals("北", (first["dayWind"] as JsonPrimitive).content)
        assertEquals("1-3", (first["dayPower"] as JsonPrimitive).content)
        assertEquals(26.0, (first["dayTempFloat"] as JsonPrimitive).content.toDouble(), 0.001)
        assertTrue(guidance(result).contains("天数由上游决定"))
        assertEquals("广州市天气（2 天）", (card(result)["title"] as JsonPrimitive).content)
    }

    @Test
    fun localInputErrorsReturnActionableGuidanceOnly() = runTest {
        val result = run(
            host = host(FakeMcpNetwork()),
            name = "amap_place_search",
            arguments = args("""{"keywords":""}"""),
        )

        assertTrue(result.isError)
        assertTrue(guidance(result).contains("关键词"))
        assertNull(result.details?.get("deepdeep.raw"))
        assertNull(result.details?.get("deepdeep.card"))
    }






}
