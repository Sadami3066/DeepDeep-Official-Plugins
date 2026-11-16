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

    private fun raw(result: PluginToolResult): String =
        (result.details?.get("deepdeep.raw") as JsonPrimitive).content

    private fun card(result: PluginToolResult): JsonObject =
        result.details?.get("deepdeep.card") as JsonObject

    @Test
    fun placeSearchMountsFullRawAllRowsFieldsAndCardTitle() = runTest {
        val network = FakeMcpNetwork(
            searchProperties = setOf("keywords", "city", "types", "page_size", "page_num"),
            payload = {
                """
                {
                  "status": "1",
                  "count": "126",
                  "pois": [
                    {"name": "西湖", "address": "龙井路1号", "cityname": "杭州市", "tel": "0571-87179617", "biz_ext": {"rating": "4.7"}, "business_area": "西湖风景区", "photos": [{"url": "https://a/1.jpg"}], "location": "120.145,30.256"},
                    {"name": "断桥残雪", "location": "120.150,30.260"},
                    {"name": "苏堤春晓", "location": "120.140,30.250"},
                    {"name": "曲院风荷", "location": "120.135,30.255"},
                    {"name": "平湖秋月", "location": "120.148,30.252"},
                    {"name": "柳浪闻莺", "location": "120.155,30.240"},
                    {"name": "花港观鱼", "location": "120.138,30.235"},
                    {"name": "雷峰夕照", "location": "120.149,30.231"},
                    {"name": "南屏晚钟", "location": "120.147,30.228"},
                    {"name": "三潭印月", "location": "120.142,30.238"}
                  ]
                }
                """.trimIndent()
            },
        )
        val result = run(
            host = host(network),
            name = "amap_place_search",
            arguments = args("""{"keywords":"西湖","city":"杭州","page_size":20,"page_num":2}"""),
        )

        assertFalse(result.isError)
        assertTrue(result.text.orEmpty().contains("共 126 条"))
        assertTrue(result.text.orEmpty().contains("用 page_num=3 继续"))
        assertEquals(10, (result.details?.get("items") as JsonArray).size)
        val first = ((result.details?.get("items") as JsonArray)[0]) as JsonObject
        assertEquals("0571-87179617", (first["tel"] as JsonPrimitive).content)
        assertEquals(4.7, (first["rating"] as JsonPrimitive).content.toDouble(), 0.001)
        assertEquals("西湖风景区", (first["businessArea"] as JsonPrimitive).content)
        assertEquals("https://a/1.jpg", ((first["photos"] as JsonArray)[0] as JsonPrimitive).content)
        assertEquals(126, (result.details?.get("count") as JsonPrimitive).int)
        assertTrue(card(result)["title"].toString().contains("西湖"))
        assertTrue(raw(result).contains("\"jsonrpc\":\"2.0\""))
        assertTrue(raw(result).contains("\"count\":\"126\""))
        assertEquals(10, (AmapJson.parseToJsonElement(raw(result)) as JsonObject)
            .let { envelope -> (envelope["result"] as JsonObject) }
            .let { resultObject -> (resultObject["structuredContent"] as JsonObject)["pois"] as JsonArray }
            .size)
        val call = network.toolCalls.last()
        assertEquals("maps_text_search", call.first)
        assertEquals(20, (call.second["page_size"] as JsonPrimitive).int)
        assertEquals(2, (call.second["page_num"] as JsonPrimitive).int)
    }

    @Test
    fun placeSearchExplainsLimitsWhenServiceHasNoPagingArguments() = runTest {
        val network = FakeMcpNetwork(
            searchProperties = setOf("keywords", "city"),
            payload = { """{"pois":[{"name":"西湖"},{"name":"断桥残雪"}]}""" },
        )
        val result = run(
            host = host(network),
            name = "amap_place_search",
            arguments = args("""{"keywords":"西湖","page_num":2}"""),
        )

        assertTrue(result.text.orEmpty().contains("接口不支持 page_size/page_num"))
        assertNull(network.toolCalls.last().second["page_num"])
        assertTrue(raw(result).contains("\"pois\""))
    }

    @Test
    fun routeToolDetailsCarryEveryPathAndRaw() = runTest {
        val network = FakeMcpNetwork(
            payload = {
                """
                {
                  "route": {
                    "origin": "120.1,30.2",
                    "destination": "120.3,30.4",
                    "paths": [
                      {"distance": "12345", "duration": "1500", "steps": [{"instruction": "向北行驶", "road": "文一西路", "distance": "500", "duration": "60"}]},
                      {"distance": "15600", "duration": "2100", "steps": [{"instruction": "沿文一西路向西", "distance": "900", "duration": "120"}]}
                    ]
                  }
                }
                """.trimIndent()
            },
        )
        val result = run(
            host = host(network),
            name = "amap_route_driving",
            arguments = args(
                """{"origin_longitude":120.1,"origin_latitude":30.2,"destination_longitude":120.3,"destination_latitude":30.4,"origin_name":"公司","destination_name":"机场"}""",
            ),
        )

        assertFalse(result.isError)
        assertTrue(result.text.orEmpty().contains("另有 1 条备选"))
        assertEquals(2, (result.details?.get("paths") as JsonArray).size)
        val second = ((result.details?.get("paths") as JsonArray)[1]) as JsonObject
        assertEquals(15600, (second["distanceMeters"] as JsonPrimitive).int)
        assertEquals(1, (second["steps"] as JsonArray).size)
        assertEquals(1500, (result.details?.get("durationSeconds") as JsonPrimitive).int)
        assertTrue(card(result)["title"].toString().contains("驾车路线"))
        assertTrue(card(result)["title"].toString().contains("公司"))
        assertTrue(raw(result).contains("\"paths\""))
        assertEquals("maps_direction_driving", network.toolCalls.last().first)
    }

    @Test
    fun weatherToolKeepsEveryForecastDayAndRaw() = runTest {
        val network = FakeMcpNetwork(
            payload = {
                """
                {
                  "forecasts": [
                    {"city": "杭州市", "casts": [
                      {"date": "2026-11-15", "dayweather": "晴", "nightweather": "多云", "daytemp": "20", "nighttemp": "12"},
                      {"date": "2026-11-16", "dayweather": "小雨", "nightweather": "阴", "daytemp": "18", "nighttemp": "11"}
                    ]},
                    {"city": "杭州市", "casts": [
                      {"date": "2026-11-17", "dayweather": "多云", "nightweather": "晴", "daytemp": "21", "nighttemp": "13"}
                    ]}
                  ]
                }
                """.trimIndent()
            },
        )
        val result = run(
            host = host(network),
            name = "amap_weather",
            arguments = args("""{"city":"杭州"}"""),
        )

        assertFalse(result.isError)
        assertEquals(3, (result.details?.get("days") as JsonArray).size)
        assertTrue(result.text.orEmpty().contains("11/17"))
        assertTrue(card(result)["title"].toString().contains("3 天"))
        assertTrue(raw(result).contains("\"casts\""))
    }

    @Test
    fun upstreamErrorKeepsDetailAndRedactsKey() = runTest {
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
        assertTrue(result.text.orEmpty().contains("INVALID_USER_KEY"))
        assertTrue(result.text.orEmpty().contains("上游返回"))
        assertFalse(result.text.orEmpty().contains("secret-key"))
        val detailsRaw = (result.details?.get("deepdeep.raw") as JsonPrimitive).content
        assertTrue(detailsRaw.contains("INVALID_USER_KEY"))
        assertFalse(detailsRaw.contains("secret-key"))
        assertTrue(detailsRaw.contains("***"))
        assertTrue(result.details?.get("deepdeep.card") == null)
    }

    @Test
    fun regeoDetailsCarryStreetAndCard() = runTest {
        val network = FakeMcpNetwork(
            payload = {
                """
                {
                  "status": "1",
                  "regeocode": {
                    "formatted_address": "浙江省杭州市西湖区北山街道北山街1号",
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
                """.trimIndent()
            },
        )
        val result = run(
            host = host(network),
            name = "amap_regeo",
            arguments = args("""{"longitude":120.14,"latitude":30.26}"""),
        )

        assertFalse(result.isError)
        assertEquals("西湖区", (result.details?.get("district") as JsonPrimitive).contentOrNull)
        assertEquals("北山街", (result.details?.get("street") as JsonPrimitive).contentOrNull)
        assertEquals("1号", (result.details?.get("streetNumber") as JsonPrimitive).contentOrNull)
        assertTrue(result.text.orEmpty().contains("街道 北山街1号"))
        assertTrue(card(result)["title"].toString().contains("位置"))
        assertTrue(raw(result).contains("\"regeocode\""))
    }
}
