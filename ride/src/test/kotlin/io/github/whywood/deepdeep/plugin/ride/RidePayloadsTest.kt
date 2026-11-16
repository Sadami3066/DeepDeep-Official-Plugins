package io.github.whywood.deepdeep.plugin.ride

import kotlinx.serialization.json.Json
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
              "distance": "5.2 公里",
              "duration": "18 分钟",
              "eta": "3 分钟",
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
        assertEquals("5.2 公里", estimate.distance)
        assertEquals("18 分钟", estimate.duration)
        assertEquals("3 分钟", estimate.eta)
        assertEquals("201", estimate.items[0].productCategory)
        assertEquals("4 分钟", estimate.items[0].eta)
        assertEquals("5.2 公里", estimate.items[0].distance)
        assertEquals("20 分钟", estimate.items[0].duration)
        assertEquals("1", estimate.items[1].productCategory)
        assertEquals("望京SOHO → 国贸", estimate.route)
    }

    @Test
    fun estimateTextCarriesEveryFactAndNeverSaysCard() {
        val estimate = parseRideEstimate(
            objectOf(
                """
                {"traceId":"trace-1","distance":"5.2 公里","duration":"18 分钟","eta":"3 分钟",
                 "items":[{"productName":"特惠快车","productCategory":"201","priceText":"12","priceDiscounted":"9","eta":"4 分钟"}]}
                """.trimIndent(),
            ),
            "望京SOHO",
            "国贸",
        )
        val zh: RideTranslate = { text, _ -> text }

        val text = rideEstimateText(estimate, zh)

        assertTrue(text.contains("望京SOHO → 国贸"))
        assertTrue(text.contains("全程 5.2 公里"))
        assertTrue(text.contains("约 18 分钟"))
        assertTrue(text.contains("接驾约 3 分钟"))
        assertTrue(text.contains("traceId：trace-1"))
        assertTrue(text.contains("特惠快车"))
        assertTrue(text.contains("参考 ¥12"))
        assertTrue(text.contains("券后 ¥9"))
        assertTrue(text.contains("车型品类 201"))
        assertTrue(text.contains("接驾约 4 分钟"))
        assertTrue(!text.contains("卡片"))
    }

    @Test
    fun placeTextCarriesAddressAndCoordinates() {
        val places = parseRidePlaces(
            objectOf(
                """{"items":[{"display_name":"北京南站","address_all":"丰台区永外大街车站路12号","location":{"lng":"116.378","lat":"39.865"}}]}""",
            ),
        )
        val zh: RideTranslate = { text, _ -> text }

        val text = ridePlacesText(places, zh)

        assertTrue(text.contains("北京南站"))
        assertTrue(text.contains("地址：丰台区永外大街车站路12号"))
        assertTrue(text.contains("经度 116.378，纬度 39.865"))
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
    fun estimateTextListsEveryVehicleAndNewFields() {
        val zh: RideTranslate = { text, _ -> text }
        val items = (1..9).map { index ->
            RideEstimateItem(
                productName = "车型$index",
                priceText = "10",
                priceDiscounted = null,
                productCategory = "1",
                productId = "p-$index",
                currency = "CNY",
                coupon = "减 2 元",
            )
        }
        val estimate = RideEstimate("公司", "机场", items, phoneSuffix = "1234")

        val text = rideEstimateText(estimate, zh)

        assertTrue(text.contains("可用车型与参考价（9 种）"))
        assertTrue(text.contains("车型9"))
        assertTrue(text.contains("车型 ID p-9"))
        assertTrue(text.contains("优惠 减 2 元"))
        assertTrue(text.contains("参考 ¥10"))
        assertTrue(text.contains("账号手机尾号：1234"))
        assertFalse(text.contains("还有"))
        assertFalse(text.contains("未显示"))
    }

    @Test
    fun placeTextListsEveryPlaceAndCity() {
        val zh: RideTranslate = { text, _ -> text }
        val places = (1..9).map { index ->
            RidePlace(
                name = "地点$index",
                address = "地址$index",
                latitude = "23.$index",
                longitude = "113.$index",
                city = "广州市",
            )
        }

        val text = ridePlacesText(places, zh)

        assertTrue(text.contains("找到 9 个地点"))
        assertTrue(text.contains("地点9"))
        assertTrue(text.contains("城市：广州市"))
        assertFalse(text.contains("还有"))
    }

    @Test
    fun orderTextListsStatusDriverAndMap() {
        val order = parseRideOrder(
            objectOf(
                """
                {"statusCode":1,"statusText":"司机已接单","orderId":"order-1","from":{"name":"公司"},"to":{"name":"机场"},
                 "phoneNumberSuffix":"1234","driver":{"name":"王师傅","phone":"13800000000","carModel":"黑·奥迪·A6","carPlate":"粤A12345"},
                 "map":{"distanceKm":"1.2","eta":"3","phase":"to_pickup"}}
                """.trimIndent(),
            ),
        )
        val zh: RideTranslate = { text, _ -> text }

        val text = rideOrderText(order, zh)

        assertTrue(text.contains("状态：司机已接单（1）"))
        assertTrue(text.contains("司机 王师傅"))
        assertTrue(text.contains("车型 黑·奥迪·A6"))
        assertTrue(text.contains("车牌 粤A12345"))
        assertTrue(text.contains("距上车点 1.2"))
        assertTrue(text.contains("预计到达 3"))
        assertTrue(text.contains("前往上车点"))
        assertTrue(text.contains("订单号 order-1"))
        assertTrue(text.contains("账号尾号 1234"))
        assertTrue(text.contains("行程 公司 → 机场"))
    }

    @Test
    fun rateLimitErrorsAreActionableInsteadOfNoOrder() {
        val zh: RideTranslate = { text, _ -> text }
        val quota = DidiException("命中限流", RATE_LIMIT_CODE)

        val text = rideErrorText(quota, null, zh, "查询失败", "Lookup failed")

        assertTrue(text.contains("命中限流"))
        assertTrue(text.contains("滴滴查询次数已达上限"))
        assertTrue(text.contains("1 分钟后重试"))
        assertTrue(text.contains("不是没有订单"))
        assertEquals(RATE_LIMIT_CODE, parseDidiErrorCode("""{"code":-32001,"message":"命中限流"}"""))
        assertTrue(isDidiRateLimited(null, "命中限流"))
        assertTrue(isDidiRateLimited(null, "Too Many Requests"))
    }

    @Test
    fun nonQuotaErrorsKeepReadableMessage() {
        val zh: RideTranslate = { text, _ -> text }

        val text = rideErrorText(
            DidiException("订单不存在", -32011),
            null,
            zh,
            "查询失败",
            "Lookup failed",
        )

        assertTrue(text.startsWith("查询失败："))
        assertTrue(text.contains("订单不存在"))
        assertTrue(!text.contains("限流"))
    }

    @Test
    fun parsesOrderTimesAndDriverExtras() {
        val order = parseRideOrder(
            objectOf(
                """
                {
                  "statusCode": 1,
                  "statusText": "司机已接单",
                  "createTime": "1794734858482",
                  "startTime": "2026-11-15T09:27:38Z",
                  "finishTime": "1794734958482",
                  "updateTime": "1794734898482",
                  "driver": {"name": "王师傅", "carPlate": "粤A12345", "rating": "4.9", "orderCount": "1234"}
                }
                """.trimIndent(),
            ),
        )
        val zh: RideTranslate = { text, _ -> text }

        assertEquals("1794734858482", order.createdAt)
        assertEquals("2026-11-15T09:27:38Z", order.startedAt)
        assertEquals("1794734958482", order.finishedAt)
        assertEquals("1794734898482", order.updatedAt)
        assertEquals("4.9", order.driverRating)
        assertEquals("1234", order.driverOrderCount)
        val text = rideOrderText(order, zh)
        assertTrue(text.contains("下单时间"))
        assertTrue(text.contains("行程开始"))
        assertTrue(text.contains("评分 4.9"))
        assertTrue(text.contains("已服务 1234 单"))
    }

    @Test
    fun rawDetailsCarryFullPayloadAndCardTitle() {
        val payload = objectOf("""{"items":[{"productName":"快车"}]}""")
        val raw = """{"jsonrpc":"2.0","id":1,"result":{"structuredContent":{"items":[{"productName":"快车"}]}}}"""

        val details = rideDetails(payload, raw, "打车价格")

        assertEquals(raw, details[RIDE_RAW_DETAIL_KEY]?.jsonPrimitive?.contentOrNull)
        assertEquals(
            "打车价格",
            (details[RIDE_CARD_DETAIL_KEY] as JsonObject)["title"]?.jsonPrimitive?.contentOrNull,
        )
        assertTrue(details.containsKey("items"))
    }

    @Test
    fun errorDetailsCarryUpstreamRaw() {
        val raw = """{"error":{"code":-32011,"message":"订单不存在"}}"""

        val details = rideErrorDetails(DidiException("订单不存在", -32011, raw = raw))

        assertEquals(raw, details?.get(RIDE_RAW_DETAIL_KEY)?.jsonPrimitive?.contentOrNull)
        assertNull(rideErrorDetails(DidiException("没有上游响应")))
    }
}
