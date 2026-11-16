package io.github.whywood.deepdeep.plugin.ride

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RidePayloadsTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun objectOf(raw: String): JsonObject = json.parseToJsonElement(raw) as JsonObject

    private fun arrayOf(raw: String): JsonArray = json.parseToJsonElement(raw) as JsonArray

    private val zh: RideTranslate = { text, _ -> text }

    private val estimatePayload = objectOf(
        """
        {
          "traceId": "trace-1",
          "phoneNumberSuffix": "1234",
          "distanceKm": "5.9",
          "eta": "21",
          "from": {
            "name": "中山大学(广州校区南校园)-南门",
            "address": "海珠区新港西路135号",
            "latitude": 23.091024824064075,
            "longitude": 113.2969363300036
          },
          "to": {
            "name": "广州塔地铁站出入口东侧",
            "address": "海珠区阅江西路222号",
            "latitude": 23.10713013402062,
            "longitude": 113.3245519484536
          },
          "items": [
            {"index": 1, "productName": "惊喜特价", "productCategory": "191", "priceText": "16"},
            {"index": 2, "productName": "特惠快车", "productCategory": "201", "priceText": "23", "priceDiscounted": "20"}
          ],
          "routes": [
            {"latitude": 23.091026, "longitude": 113.296936},
            {"latitude": 23.091124, "longitude": 113.29664},
            {"latitude": 23.091124, "longitude": 113.29664},
            {"latitude": 23.09113, "longitude": 113.2966}
          ]
        }
        """.trimIndent(),
    )

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
        assertEquals("trace-1", estimate.traceId)
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
    fun parsesActualEndpointsDistanceEtaAndRoutes() {
        val estimate = parseRideEstimate(
            estimatePayload,
            "中山大学(广州校区南校园)",
            "广州塔",
        )

        assertEquals("中山大学(广州校区南校园)-南门", estimate.pickupName)
        assertEquals("广州塔地铁站出入口东侧", estimate.dropoffName)
        assertEquals("海珠区新港西路135号", estimate.from?.address)
        assertEquals("23.091024824064075", estimate.from?.latitude)
        assertEquals("113.2969363300036", estimate.from?.longitude)
        assertEquals("5.9", estimate.distanceKm)
        assertEquals("21", estimate.eta)
        assertEquals("1234", estimate.phoneSuffix)
        assertEquals(2, estimate.items.size)
        assertTrue(estimate.adsorpedFrom)
        assertTrue(estimate.adsorpedTo)
        assertEquals("中山大学(广州校区南校园)-南门 → 广州塔地铁站出入口东侧", estimate.route)
        assertEquals("中山大学(广州校区南校园) → 广州塔", estimate.userRoute)
    }

    @Test
    fun dedupesConsecutiveRoutePointsAndKeepsEveryOtherPoint() {
        val points = parseRideRoutes(estimatePayload["routes"])

        assertEquals(3, points.size)
        assertEquals(RideGeoPoint(23.091026, 113.296936), points.first())
        assertEquals(RideGeoPoint(23.09113, 113.2966), points.last())
        assertTrue(parseRideRoutes(null).isEmpty())
        assertTrue(parseRideRoutes(arrayOf("""[{"latitude":"x","longitude":"y"}]""")).isEmpty())
    }

    @Test
    fun samePlaceComparisonIgnoresSpacingAndMissingNames() {
        assertTrue(rideSamePlace("广州塔", "广州塔 "))
        assertTrue(rideSamePlace("中山大学(广州校区南校园)", "中山大学(广州校区南校园)"))
        assertTrue(rideSamePlace(null, "广州塔"))
        assertTrue(rideSamePlace("广州塔", null))
        assertFalse(rideSamePlace("中山大学(广州校区南校园)", "中山大学(广州校区南校园)-南门"))
        assertFalse(rideSamePlace("广州塔", "广州塔地铁站出入口东侧"))
    }

    @Test
    fun measureTextAddsUnitOnlyWhenTheValueHasNone() {
        assertEquals("5.9 公里", rideMeasureText("5.9", "公里", "公里", "km"))
        assertEquals("5.2 公里", rideMeasureText("5.2 公里", "公里", "公里", "km"))
        assertEquals("5.2km", rideMeasureText("5.2km", "公里", "公里", "km"))
        assertEquals("21 分钟", rideMeasureText("21", "分钟", "分钟", "min"))
        assertEquals("21 分钟", rideMeasureText("21 分钟", "分钟", "分钟", "min"))
        assertNull(rideMeasureText(" ", "公里", "公里"))
        assertNull(rideMeasureText(null, "公里", "公里"))
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
                """{"status":"司机已接单","driverName":"王师傅","driverPhone":"13800000000","plateNumber":"粤A12345","eta":"5 分钟","orderId":"order-1","from":{"name":"公司"},"to":{"name":"机场"},"phoneNumberSuffix":"1234"}""",
            ),
        )
        assertEquals("司机已接单", order.status)
        assertEquals("王师傅", order.driver)
        assertEquals("13800000000", order.phone)
        assertEquals("粤A12345", order.plate)
        assertEquals("5 分钟", order.eta)
        assertEquals("order-1", order.orderId)
        assertEquals("公司", order.fromName)
        assertEquals("机场", order.toName)
        assertEquals("1234", order.phoneSuffix)
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
        assertEquals("116.4, 39.9", location.coordinates)
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
        assertEquals("116.378, 39.865", places[0].coordinates)
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
    fun groupsEveryPlaceByCityWithoutTruncation() {
        val places = (1..8).map { index ->
            RidePlace(
                name = "广州地点$index",
                address = "广州地址$index",
                latitude = "23.$index",
                longitude = "113.$index",
                city = "广州市",
            )
        } + (1..6).map { index ->
            RidePlace(
                name = "深圳地点$index",
                address = "深圳地址$index",
                latitude = "22.$index",
                longitude = "114.$index",
                city = "深圳市",
            )
        }

        val groups = ridePlaceGroups(places)

        assertEquals(listOf("广州市", "深圳市"), groups.mapNotNull { it.first })
        assertEquals(8, groups[0].second.size)
        assertEquals(6, groups[1].second.size)
        assertEquals(0, groups[0].second.first().index)
        assertEquals(8, groups[1].second.first().index)
        assertEquals(14, groups.sumOf { it.second.size })
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

    @Test
    fun parsesEstimateRouteFactsAndCategories() {
        val root = objectOf(
            """
            {
              "traceId": "trace-1",
              "distanceKm": "5.2",
              "duration": "18",
              "eta": "3",
              "items": [
                {
                  "productName": "特惠快车",
                  "productCategory": "201",
                  "priceText": "12",
                  "priceDiscounted": "9",
                  "eta": "4 分钟",
                  "distance": "5.2 公里",
                  "duration": "20 分钟"
                },
                {
                  "productName": "快车",
                  "productCategory": "1",
                  "priceText": "14"
                }
              ]
            }
            """.trimIndent(),
        )

        val estimate = parseRideEstimate(root, "望京SOHO", "国贸")

        assertEquals("trace-1", estimate.traceId)
        assertEquals("5.2", estimate.distanceKm)
        assertEquals("18", estimate.duration)
        assertEquals("3", estimate.eta)
        assertEquals("201", estimate.items[0].productCategory)
        assertEquals("4 分钟", estimate.items[0].eta)
        assertEquals("1", estimate.items[1].productCategory)
        assertEquals("望京SOHO → 国贸", estimate.route)
        assertFalse(estimate.adsorpedFrom)
        assertFalse(estimate.adsorpedTo)
    }

    @Test
    fun parsesOrderStatusAndDriverMapFields() {
        val order = parseRideOrder(
            objectOf(
                """
                {
                  "statusCode": 1,
                  "statusText": "司机已接单",
                  "orderId": "order-1",
                  "from": {"name": "公司"},
                  "to": {"name": "机场"},
                  "phoneNumberSuffix": "1234",
                  "driver": {"name": "王师傅", "phone": "13800000000", "carModel": "黑·奥迪·A6", "carPlate": "粤A12345"},
                  "map": {"distanceKm": "1.2", "eta": "3", "phase": "to_pickup"}
                }
                """.trimIndent(),
            ),
        )

        assertEquals("1", order.statusCode)
        assertEquals("司机已接单", order.statusText)
        assertEquals("王师傅", order.driver)
        assertEquals("黑·奥迪·A6", order.carModel)
        assertEquals("13800000000", order.phone)
        assertEquals("粤A12345", order.plate)
        assertEquals("1.2", order.distanceKm)
        assertEquals("3", order.eta)
        assertEquals("to_pickup", order.phase)
        assertEquals("公司 → 机场", order.route)
        assertFalse(order.isEmpty)
    }

    @Test
    fun orderStatusLabelMapsCodesAndKeepsRawCode() {
        val zh: RideTranslate = { text, _ -> text }

        assertEquals("司机已接单（1）", rideStatusLabel("1", "司机已接单", zh))
        assertEquals("匹配中（0）", rideStatusLabel("0", null, zh))
        assertEquals("行程中（4）", rideStatusLabel("4", null, zh))
        assertEquals("订单已结束（9）", rideStatusLabel("9", null, zh))
        assertEquals("自定义状态", rideStatusLabel(null, "自定义状态", zh))
        assertNull(rideStatusLabel(null, null, zh))
    }

    @Test
    fun statusLabelUsesUpstreamTextForTerminalCodes() {
        val zh: RideTranslate = { text, _ -> text }

        assertEquals("上游状态名（3）", rideStatusLabel("3", "上游状态名", zh))
        assertEquals("订单已结束（8）", rideStatusLabel("8", "未知状态", zh))
        assertEquals("订单已创建", rideStatusLabel("created", null, zh))
        assertEquals("上游状态名", rideStatusLabel("3", "上游状态名", zh, includeCode = false))
    }

    @Test
    fun parsesEstimateProductCurrencyCouponAndEta() {
        val estimate = parseRideEstimate(
            objectOf(
                """
                {"items":[{
                  "productName":"快车",
                  "productCategory":"1",
                  "productId":"p-1",
                  "currency":"CNY",
                  "coupon":{"text":"减 2 元"},
                  "eta":{"text":"3 分钟"},
                  "priceText":"10",
                  "priceDiscounted":"8"
                }]}
                """.trimIndent(),
            ),
            null,
            null,
        )

        val item = estimate.items.single()
        assertEquals("p-1", item.productId)
        assertEquals("CNY", item.currency)
        assertEquals("减 2 元", item.coupon)
        assertEquals("3 分钟", item.eta)
    }

    @Test
    fun estimateDetailsCarryActualPointsUnitsItemsAndRoutes() {
        val estimate = parseRideEstimate(estimatePayload, "中山大学(广州校区南校园)", "广州塔")

        val base = rideEstimateBase(estimatePayload, estimate)

        val from = base["from"] as JsonObject
        assertEquals("中山大学(广州校区南校园)-南门", from["name"]?.jsonPrimitive?.contentOrNull)
        assertEquals("海珠区新港西路135号", from["address"]?.jsonPrimitive?.contentOrNull)
        assertEquals("23.091024824064075", from["lat"]?.jsonPrimitive?.contentOrNull)
        assertEquals("113.2969363300036", from["lng"]?.jsonPrimitive?.contentOrNull)
        assertFalse(from.containsKey("latitude"))
        assertEquals("广州塔地铁站出入口东侧", (base["to"] as JsonObject)["name"]?.jsonPrimitive?.contentOrNull)
        assertEquals("5.9", base["distanceKm"]?.jsonPrimitive?.contentOrNull)
        assertEquals("21", base["eta"]?.jsonPrimitive?.contentOrNull)
        assertEquals(2, (base["items"] as JsonArray).size)
        assertEquals(4, (base["routes"] as JsonArray).size)
        assertEquals("1234", base["phoneNumberSuffix"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun placeBaseKeepsEveryItemAndQuery() {
        val places = arrayOf(
            """
            [
              {"display_name":"广州塔","city":"广州市","location":{"lng":113.32449,"lat":23.10649}},
              {"display_name":"广州塔广场","city":"广州市","location":{"lng":113.324506,"lat":23.105158}},
              {"display_name":"广州塔码头","city":"广州市","location":{"lng":113.327083,"lat":23.107458}}
            ]
            """.trimIndent(),
        )

        val base = ridePlaceBase(places, "广州塔", "广州市")

        assertEquals(3, (base["items"] as JsonArray).size)
        assertEquals("广州塔", base["query"]?.jsonPrimitive?.contentOrNull)
        assertEquals("广州市", base["city"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun cardPayloadSurvivesHostPostProcessing() {
        val estimate = parseRideEstimate(estimatePayload, "中山大学(广州校区南校园)", "广州塔")
        val raw = """{"content":[{"type":"text","text":"上游原文"}],"structuredContent":{"traceId":"trace-1"}}"""
        val details = rideDetails(
            rideEstimateBase(estimatePayload, estimate),
            raw,
            "打车价格",
            rideEstimateGuidance(estimate, zh),
        )

        val cardDetails = JsonObject(details - setOf(RIDE_RAW_DETAIL_KEY, RIDE_GUIDANCE_DETAIL_KEY))
        val restored = parseRideEstimate(unwrapRidePayload(cardDetails) ?: cardDetails, null, null)

        assertEquals("中山大学(广州校区南校园)-南门", restored.pickupName)
        assertEquals("广州塔地铁站出入口东侧", restored.dropoffName)
        assertEquals("5.9", restored.distanceKm)
        assertEquals("21", restored.eta)
        assertEquals(2, restored.items.size)
        assertEquals(3, restored.routes.size)
        assertTrue(cardDetails[RIDE_CARD_DETAIL_KEY] is JsonObject)
    }

    @Test
    fun placeCardPayloadSurvivesHostPostProcessing() {
        val payload = arrayOf("""[{"display_name":"广州塔","city":"广州市","location":{"lng":113.32449,"lat":23.10649}}]""")
        val details = rideDetails(ridePlaceBase(payload, "广州塔", "广州市"), "raw", "地点搜索", "guidance")

        val cardDetails = JsonObject(details - setOf(RIDE_RAW_DETAIL_KEY, RIDE_GUIDANCE_DETAIL_KEY))
        val places = parseRidePlaces(unwrapRidePayload(cardDetails) ?: cardDetails)

        assertEquals(1, places.size)
        assertEquals("广州塔", places.single().displayName)
        assertTrue(cardDetails[RIDE_CARD_DETAIL_KEY] is JsonObject)
    }

    @Test
    fun detailsCarryUpstreamRawGuidanceAndCardTitle() {
        val base = objectOf("""{"items":[{"name":"A"}],"traceId":"t"}""")
        val raw = """{"content":[{"type":"text","text":"上游原文"}],"structuredContent":{"items":[]}}"""

        val details = rideDetails(base, raw, "打车价格", "距离单位公里")

        assertEquals(raw, details[RIDE_RAW_DETAIL_KEY]?.jsonPrimitive?.contentOrNull)
        assertEquals("距离单位公里", details[RIDE_GUIDANCE_DETAIL_KEY]?.jsonPrimitive?.contentOrNull)
        assertEquals(
            "打车价格",
            (details[RIDE_CARD_DETAIL_KEY] as JsonObject)["title"]?.jsonPrimitive?.contentOrNull,
        )
        assertTrue((details["items"] as JsonArray).isNotEmpty())
        assertEquals("t", details["traceId"]?.jsonPrimitive?.contentOrNull)
        assertEquals(
            setOf("items", "traceId", RIDE_RAW_DETAIL_KEY, RIDE_GUIDANCE_DETAIL_KEY, RIDE_CARD_DETAIL_KEY),
            details.keys,
        )
    }

    @Test
    fun errorDetailsCarryRawKindAndGuidance() {
        val raw = """{"error":{"code":-32001,"message":"命中限流"}}"""

        val details = rideErrorDetails(raw, "约 1 分钟后重试", RideErrorKind.RATE_LIMIT, "命中限流")

        assertEquals(raw, details[RIDE_RAW_DETAIL_KEY]?.jsonPrimitive?.contentOrNull)
        assertEquals("约 1 分钟后重试", details[RIDE_GUIDANCE_DETAIL_KEY]?.jsonPrimitive?.contentOrNull)
        val error = details[RIDE_ERROR_DETAIL_KEY] as JsonObject
        assertEquals("rate_limit", error["kind"]?.jsonPrimitive?.contentOrNull)
        assertEquals("命中限流", error["message"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun didiRawKeepsContentTextAndAppendsStructuredContent() {
        val envelope = objectOf(
            """{"jsonrpc":"2.0","id":1,"result":{"content":[{"type":"text","text":"你好"}],"structuredContent":{"a":1},"isError":false}}""",
        )

        val raw = didiRaw(envelope, "fallback")

        assertEquals("你好\n\nstructuredContent:\n{\"a\":1}", raw)
        assertFalse(raw.contains("jsonrpc"))
        assertFalse(raw.contains("isError"))
        assertFalse(raw.contains("\"content\""))
    }

    @Test
    fun didiRawHandlesTextOnlyStructuredOnlyAndErrorResults() {
        val textOnly = objectOf("""{"result":{"content":[{"type":"text","text":"[{\"display_name\":\"广州塔\"}]"}]}}""")
        assertEquals("[{\"display_name\":\"广州塔\"}]", didiRaw(textOnly, "fallback"))

        val structuredOnly = objectOf("""{"result":{"structuredContent":{"traceId":"t-1"}}}""")
        assertEquals("structuredContent:\n{\"traceId\":\"t-1\"}", didiRaw(structuredOnly, "fallback"))

        val errorEnvelope = objectOf("""{"jsonrpc":"2.0","id":2,"error":{"code":-32011,"message":"订单不存在"}}""")
        assertEquals(
            objectOf("""{"code":-32011,"message":"订单不存在"}"""),
            json.parseToJsonElement(didiRaw(errorEnvelope, "fallback")),
        )
        assertEquals("plain text", didiRaw(objectOf("""{"jsonrpc":"2.0","id":3}"""), "plain text"))
    }
}
