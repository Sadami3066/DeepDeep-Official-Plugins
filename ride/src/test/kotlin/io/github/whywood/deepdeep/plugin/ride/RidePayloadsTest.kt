package io.github.whywood.deepdeep.plugin.ride

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RidePayloadsTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun objectOf(raw: String): JsonObject = json.parseToJsonElement(raw) as JsonObject

    @Test
    fun parsesEstimateItemsWithDiscountPrices() {
        val root = objectOf(
            """
            {
              "traceId": "trace-1",
              "items": [
                {"productName": "快车", "priceText": "20", "priceDiscounted": "18"},
                {"productName": "专车", "priceText": "35"}
              ]
            }
            """.trimIndent(),
        )

        val estimate = parseRideEstimate(root, "公司", "机场")

        assertEquals("公司 → 机场", estimate.route)
        assertEquals(2, estimate.items.size)
        assertEquals("快车", estimate.items[0].productName)
        assertEquals("20", estimate.items[0].priceText)
        assertEquals("18", estimate.items[0].priceDiscounted)
        assertEquals("35", estimate.items[1].priceText)
        assertFalse(estimate.isEmpty)
    }

    @Test
    fun parsesNestedEstimatePayload() {
        val root = objectOf("""{"data":{"items":[{"productName":"快车","priceText":"12"}]}}""")

        val estimate = parseRideEstimate(root, null, null)

        assertEquals(1, estimate.items.size)
        assertEquals("快车", estimate.items[0].productName)
    }

    @Test
    fun emptyEstimateWhenItemsMissingOrBlank() {
        assertTrue(parseRideEstimate(JsonObject(emptyMap()), "a", "b").isEmpty)
        assertTrue(parseRideEstimate(objectOf("""{"items":[]}"""), "a", "b").isEmpty)
        assertTrue(parseRideEstimate(objectOf("""{"items":[{"priceText":"12"}]}"""), "a", "b").isEmpty)
    }

    @Test
    fun parsesPayloadFromWrappedText() {
        val element = parseDidiPayload("查询结果：{\"items\":[{\"productName\":\"快车\"}]} 请参考")
        val estimate = parseRideEstimate(element as? JsonObject, null, null)

        assertEquals(1, estimate.items.size)
        assertNull(parseDidiPayload("没有 JSON"))
    }

    @Test
    fun parsesRideLinksAndDetectsEmpty() {
        val link = parseRideLink(
            objectOf("""{"appLink":"https://didi/app","miniprogramLink":"https://didi/mp"}"""),
        )
        assertEquals("https://didi/app", link.appLink)
        assertEquals("https://didi/mp", link.miniprogramLink)
        assertFalse(link.isEmpty)

        assertTrue(parseRideLink(JsonObject(emptyMap())).isEmpty)
    }

    @Test
    fun parsesOrderFieldsAndDetectsEmpty() {
        val order = parseRideOrder(
            objectOf(
                """{"status":"司机已接单","driverName":"王师傅","driverPhone":"13800000000","plateNumber":"粤A12345","eta":"5 分钟"}""",
            ),
        )
        assertEquals("司机已接单", order.status)
        assertEquals("王师傅", order.driver)
        assertEquals("13800000000", order.phone)
        assertEquals("粤A12345", order.plate)
        assertEquals("5 分钟", order.eta)
        assertFalse(order.isEmpty)

        assertTrue(parseRideOrder(null).isEmpty)
        assertTrue(parseRideOrder(JsonObject(emptyMap())).isEmpty)
    }

    @Test
    fun parsesDriverLocationCoordinatesAndSummary() {
        val location = parseRideDriverLocation(
            objectOf("""{"latitude":"39.9","longitude":"116.4","address":"国贸桥"}"""),
        )
        assertEquals("39.9", location.latitude)
        assertEquals("116.4", location.longitude)
        assertEquals("国贸桥", location.summary)
        assertEquals("39.9, 116.4", location.coordinates)
        assertFalse(location.isEmpty)

        assertTrue(parseRideDriverLocation(JsonObject(emptyMap())).isEmpty)
    }

    @Test
    fun parsesCancelResultStates() {
        val cancelled = parseRideCancelResult(objectOf("""{"cancelled":true}"""))
        assertEquals(true, cancelled.cancelled)
        assertFalse(cancelled.isEmpty)

        val failed = parseRideCancelResult(objectOf("""{"success":false,"message":"订单已开始"}"""))
        assertEquals(false, failed.cancelled)
        assertEquals("订单已开始", failed.message)

        assertTrue(parseRideCancelResult(null).isEmpty)
    }

    @Test
    fun parsesRealPlaceSearchFields() {
        val places = parseRidePlaces(
            objectOf(
                """
                {"items":[
                  {"display_name":"北京南站","address_all":"丰台区永外大街车站路12号","location":{"lng":"116.378","lat":"39.865"}},
                  {"display_name":"北京站","address_all":"东城区毛家湾胡同甲13号"}
                ]}
                """.trimIndent(),
            ),
        )

        assertEquals(2, places.size)
        assertEquals("北京南站", places[0].displayName)
        assertEquals("丰台区永外大街车站路12号", places[0].address)
        assertEquals("39.865, 116.378", places[0].coordinates)
        assertEquals("北京站", places[1].displayName)
        assertNull(places[1].coordinates)
    }

    @Test
    fun skipsEmptyPlaceEntries() {
        val places = parseRidePlaces(
            objectOf("""{"items":[{"foo":"bar"},{"display_name":"国贸"},null]}"""),
        )
        assertEquals(1, places.size)
        assertEquals("国贸", places[0].displayName)
        assertTrue(parseRidePlaces(null).isEmpty())
        assertTrue(parseRidePlaces(objectOf("""{"items":[]}""")).isEmpty())
    }

    @Test
    fun detectsToolListResponses() {
        assertTrue(
            parseDidiToolsList(
                """{"jsonrpc":"2.0","result":{"tools":[{"name":"taxi_estimate"}]}}""",
            ),
        )
        assertFalse(parseDidiToolsList("""{"error":{"message":"invalid key"}}"""))
        assertFalse(parseDidiToolsList("""{"result":{"tools":[]}}"""))
        assertFalse(parseDidiToolsList("not json"))
    }
}
