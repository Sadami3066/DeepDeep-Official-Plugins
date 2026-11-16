package io.github.whywood.deepdeep.plugin.hotel

import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HotelJsonTest {
    private val zh: HotelTranslate = { text, _ -> text }

    @Test
    fun parsesSearchResultsWithPriceAndTags() {
        val raw = """
            npm notice update available
            {
              "success": true,
              "hotelInformationList": [
                {
                  "hotelId": 1090005,
                  "name": "杭州富阳东方茂开元名都大酒店",
                  "starRating": 5,
                  "address": "富春街道金桥北路61号",
                  "distanceInMeters": 1200,
                  "price": {"lowestPrice": 352, "currency": "CNY"},
                  "tags": ["五星酒店", "免费WiFi", "免费停车场"],
                  "bookingUrl": "https://rollinggo.cn/example",
                  "imageUrl": "https://image-cdn.rollinggo.cn/hotel.jpg"
                }
              ]
            }
        """.trimIndent()

        val details = requireNotNull(
            parseHotelSearch(raw, "杭州西湖附近酒店", "杭州西湖", "景点", "2026-06-20", 1),
        )

        assertEquals(1, details.hotels.size)
        val hotel = details.hotels.single()
        assertEquals("杭州富阳东方茂开元名都大酒店", hotel.name)
        assertEquals("5", hotel.starRating)
        assertEquals("¥352", hotel.priceText)
        assertEquals(352.0, hotel.lowestPrice!!, 0.001)
        assertEquals("CNY", hotel.currency)
        assertEquals("https://image-cdn.rollinggo.cn/hotel.jpg", hotel.imageUrl)
        assertEquals("富春街道金桥北路61号", hotel.address)
        assertEquals(1200, hotel.distanceMeters)
        assertEquals("1.2 公里", hotelDistanceText(hotel.distanceMeters))
        assertEquals(listOf("五星酒店", "免费WiFi", "免费停车场"), hotel.tags)
        assertEquals("https://rollinggo.cn/example", hotel.bookingUrl)
        assertEquals(1090005L, hotel.hotelId)
        assertEquals("杭州西湖", details.place)
    }

    @Test
    fun parsesRoomPlansAndCancellation() {
        val raw = """
            {
              "success": true,
              "hotelId": 1090005,
              "name": "杭州富阳东方茂开元名都大酒店",
              "checkIn": "2026-06-20",
              "checkOut": "2026-06-21",
              "bookingUrl": "https://rollinggo.cn/example",
              "roomRatePlans": [
                {
                  "roomNameCn": "普通双床房",
                  "bedTypeDescription": "2 单人床",
                  "currency": "CNY",
                  "totalPrice": 352,
                  "cancellationPolicies": [
                    {"fromDate": "2026-06-19T00:00:00+08:00", "amount": 352}
                  ]
                }
              ]
            }
        """.trimIndent()

        val details = requireNotNull(parseHotelDetail(raw, "fallback"))

        assertEquals("杭州富阳东方茂开元名都大酒店", details.name)
        assertEquals("2026-06-20", details.checkIn)
        assertEquals(1, details.rooms.size)
        val room = details.rooms.single()
        assertEquals("普通双床房", room.name)
        assertEquals("2 单人床", room.bed)
        assertEquals("¥352", room.priceText)
        assertEquals("2026-06-19 起取消扣 ¥352", room.cancellation)
        assertEquals("2026-06-20", room.checkIn)
        assertEquals("2026-06-21", room.checkOut)
        assertEquals(1090005L, details.hotelId)
        assertEquals(1, details.roomGroups.size)
    }

    @Test
    fun groupsSameRoomPlansAndPicksLowestTotal() {
        val details = requireNotNull(parseHotelDetail(sampleDetailJson(), "fallback"))

        assertEquals(3, details.rooms.size)
        assertEquals(2, details.roomGroups.size)
        val group = details.roomGroups.first()
        assertEquals("豪华大床房", group.name)
        assertEquals(2, group.planCount)
        assertEquals("¥823", group.lowestPriceText)
        assertEquals("不可取消", group.cheapestCancellation)
        assertEquals("1 张大床", group.bed)
        assertEquals(listOf("rp-strict", "rp-free"), group.plans.map { it.ratePlanId })
        assertEquals("园景双床房", details.roomGroups.last().name)
        assertEquals(1, details.roomGroups.last().planCount)
        assertEquals("2026-11-20", group.plans.first().checkIn)
        assertEquals("2026-11-21", group.plans.first().checkOut)
    }

    @Test
    fun parsesRatePlanExtras() {
        val details = requireNotNull(parseHotelDetail(sampleDetailJson(), "fallback"))
        val plan = details.rooms.first { it.ratePlanId == "rp-free" }

        assertEquals("含双早 · 限时免费取消", plan.ratePlanName)
        assertEquals("¥1023", plan.avgPriceText)
        assertEquals("2026-11-19 前可免费取消", plan.cancellation)
        assertEquals(2, plan.inventoryCount)
        assertEquals(false, plan.onRequest)
    }

    @Test
    fun selectsPlanByExplicitRatePlanId() {
        val details = requireNotNull(parseHotelDetail(sampleDetailJson(), "fallback"))

        val selection = selectHotelPlan(details, "豪华大床房", "rp-free")

        assertTrue(selection is HotelPlanSelection.Selected)
        assertEquals("rp-free", (selection as HotelPlanSelection.Selected).plan.ratePlanId)
    }

    @Test
    fun selectsSinglePlanByRoomName() {
        val details = requireNotNull(parseHotelDetail(sampleDetailJson(), "fallback"))

        val selection = selectHotelPlan(details, "园景双床房", null)

        assertTrue(selection is HotelPlanSelection.Selected)
        assertEquals("rp-twin", (selection as HotelPlanSelection.Selected).plan.ratePlanId)
    }

    @Test
    fun ambiguousRoomNameReturnsEveryPlan() {
        val details = requireNotNull(parseHotelDetail(sampleDetailJson(), "fallback"))

        val selection = selectHotelPlan(details, "豪华大床房", null)

        assertTrue(selection is HotelPlanSelection.Multiple)
        assertEquals(listOf("rp-strict", "rp-free"), (selection as HotelPlanSelection.Multiple).plans.map { it.ratePlanId })
    }

    @Test
    fun unknownRoomAndUnknownPlanAreReported() {
        val details = requireNotNull(parseHotelDetail(sampleDetailJson(), "fallback"))

        assertTrue(selectHotelPlan(details, "总统套房", null) is HotelPlanSelection.MissingRoom)
        assertTrue(selectHotelPlan(details, "豪华大床房", "rp-missing") is HotelPlanSelection.MissingRatePlan)
    }

    @Test
    fun detailGuidanceExplainsGroupingAndRatePlanSelection() {
        val details = requireNotNull(parseHotelDetail(sampleDetailJson(), "fallback"))

        val guidance = hotelDetailGuidance(details, zh)

        assertTrue(guidance.contains("按名称分组"))
        assertTrue(guidance.contains("组内按总价"))
        assertTrue(guidance.contains("rate_plan_id"))
    }

    @Test
    fun detailGuidanceReportsRollingGoError() {
        val details = requireNotNull(
            parseHotelDetail("""{"success":false,"errorMessage":"入住日期不合法"}""", "某酒店"),
        )

        val guidance = hotelDetailGuidance(details, zh)

        assertTrue(guidance.contains("没有可用房型"))
        assertTrue(guidance.contains("入住日期不合法"))
    }

    @Test
    fun searchGuidanceMentionsBudgetAndPagingWithoutDroppingHotels() {
        val raw = """
            {"hotelInformationList":[
              {"name":"A","price":{"lowestPrice":147,"currency":"CNY"}},
              {"name":"B","price":{"lowestPrice":320,"currency":"CNY"}}
            ],"nextPageToken":"token-1"}
        """.trimIndent()
        val details = withHotelBudget(
            requireNotNull(parseHotelSearch(raw, "q", "中山大学", "景点", "2026-11-20", 1)),
            100.0,
        )

        val guidance = hotelSearchGuidance(details, zh)

        assertTrue(guidance.contains("2 家超出预算"))
        assertTrue(guidance.contains("最低 ¥147"))
        assertTrue(guidance.contains("还有下一页"))
        assertTrue(guidance.contains("hotel_detail"))
        assertEquals(2, details.hotels.size)
    }

    @Test
    fun parsesOrdersFromOrderInfoListWithFullFields() {
        val details = requireNotNull(parseHotelOrders(sampleOrdersJson()))

        assertEquals("查询成功", details.message)
        assertEquals(1, details.orders.size)
        val order = details.orders.single()
        assertEquals("杭州西湖国宾馆", order.hotelName)
        assertEquals("M17816893017792282", order.orderNo)
        assertEquals("S17816893017792283", order.subOrderNo)
        assertEquals("ref-1", order.referenceNo)
        assertEquals("西湖区杨公堤18号", order.hotelAddress)
        assertEquals("2026-11-20", order.checkIn)
        assertEquals("2026-11-21", order.checkOut)
        assertEquals(1, order.nights)
        assertEquals(2, order.roomCount)
        assertEquals("3", order.status)
        assertEquals("CREATED", order.paymentStatus)
        assertEquals("豪华大床房", order.roomName)
        assertEquals("¥1023", order.totalPrice)
        assertEquals("San Zhang", order.contactName)
        assertEquals("san@example.com", order.contactEmail)
    }

    @Test
    fun ordersGuidancePointsToOrderDetailForCancellationReason() {
        val details = requireNotNull(parseHotelOrders(realOrderListJson()))

        val guidance = hotelOrdersGuidance(details, zh)

        assertTrue(guidance.contains("没有取消原因"))
        assertTrue(guidance.contains("hotel_order_detail"))
    }

    @Test
    fun emptyOrderListIsNoOrdersNotFailure() {
        val details = requireNotNull(parseHotelOrders("""{"message":"暂无订单","orderInfoList":[]}"""))

        assertTrue(details.orders.isEmpty())
        assertEquals("暂无订单", details.message)
        assertTrue(hotelOrdersGuidance(details, zh).contains("没有酒店订单"))
    }

    @Test
    fun unreadableOrderPayloadIsQueryFailure() {
        assertNull(parseHotelOrders("{}"))
        assertNull(parseHotelOrders("not json"))
        assertNull(parseHotelOrders("""{"message":"鉴权失败"}"""))
        assertEquals("鉴权失败", parseHotelOrderMessage("""{"message":"鉴权失败"}"""))
    }

    @Test
    fun parsesLegacyOrdersArray() {
        val raw = """
            [
              {
                "hotelBookingInfo": {
                  "status": "3",
                  "checkInDate": "2026-06-20T00:00:00.000",
                  "checkOutDate": "2026-06-21T00:00:00.000",
                  "totalPrice": 352
                },
                "hotelBookingHotel": {"hotelName": "杭州富阳东方茂开元名都大酒店"},
                "hotelRatePlanInfo": {"roomNameCn": "普通双床房", "currency": "CNY"}
              }
            ]
        """.trimIndent()

        val details = requireNotNull(parseHotelOrders(raw))

        assertEquals(1, details.orders.size)
        val order = details.orders.single()
        assertEquals("杭州富阳东方茂开元名都大酒店", order.hotelName)
        assertEquals("2026-06-20", order.checkIn)
        assertEquals("3", order.status)
        assertEquals("普通双床房", order.roomName)
        assertEquals("¥352", order.totalPrice)
    }

    @Test
    fun parsesPriceConfirmationAndBookingWithPaymentUrl() {
        val price = requireNotNull(
            parseHotelPriceConfirmation(
                """
                {"success":true,"priceDetailsInfo":{"referenceNo":"ref-1","hotelList":[{"totalPrice":352,"ratePlanList":[{"totalPrice":352,"currency":"CNY"}]}]}}
                """.trimIndent(),
            ),
        )
        assertEquals("ref-1", price.referenceNo)
        assertEquals(352.0, price.totalPrice)

        val booking = requireNotNull(
            parseHotelBooking(
                """
                {"success":true,"bookingResult":{"orderNo":"order-1","paymentType":"URL","paymentUrl":"https://rollinggo.cn/pay"}}
                """.trimIndent(),
            ),
        )
        assertEquals("order-1", booking.orderNo)
        assertEquals("URL", booking.paymentType)
        assertEquals("https://rollinggo.cn/pay", booking.paymentUrl)
    }

    @Test
    fun bookingGuidanceKeepsPaymentInstruction() {
        val booking = HotelBookingDetails(
            orderNo = "order-1",
            hotelName = "杭州西湖国宾馆",
            roomName = "豪华大床房",
            ratePlanName = "含双早 · 限时免费取消",
            checkIn = "2026-11-20",
            checkOut = "2026-11-21",
            totalPrice = "¥1023",
            cancellation = "2026-11-19 前可免费取消",
            paymentUrl = "https://rollinggo.cn/pay",
            fetchedAt = 1L,
        )

        val guidance = hotelBookingGuidance(booking, zh)

        assertTrue(guidance.contains("30 分钟内"))
        assertTrue(guidance.contains("不要说已经付款"))
        assertTrue(hotelBookingGuidance(booking.copy(paymentUrl = null), zh).contains("没有返回支付链接"))
    }

    @Test
    fun ignoresDotenvTipObjectBeforeResult() {
        val raw = """
            ◇ injected env (1) from ../../root/.hotel-cli/.env // tip: override existing { override: true }
            {"success":true,"hotelInformationList":[{"name":"A","price":{"lowestPrice":119,"currency":"CNY"}}]}
        """.trimIndent()

        val details = requireNotNull(parseHotelSearch(raw, "q", "p", "城市", null, 1))

        assertEquals("A", details.hotels.single().name)
        assertEquals("¥119", details.hotels.single().priceText)
    }

    @Test
    fun toleratesTrailingNoiseAndMissingLists() {
        assertNotNull(extractJsonElement("notice\n{\"success\":true}\nnotice"))
        assertNull(extractJsonElement("not json"))
        assertNull(parseHotelSearch("not json", "x", "y", "城市", null, 1))
        assertNull(parseHotelDetail("{}", "fallback"))
        assertNull(parseHotelOrders("{}"))
    }

    @Test
    fun computesCheckoutDate() {
        assertEquals("2026-06-21", hotelDateAfter("2026-06-20", 1))
        assertEquals("2026-06-22", hotelDateAfter("2026-06-20", 2))
        assertNull(hotelDateAfter(null, 1))
        assertNull(hotelDateAfter("bad-date", 1))
    }

    @Test
    fun formatsMoney() {
        assertEquals("¥352", hotelMoneyText(352.0, "CNY"))
        assertEquals("\$352.5", hotelMoneyText(352.5, "USD"))
        assertEquals("¥0", hotelMoneyText(0.0, "CNY"))
        assertNull(hotelMoneyText(null, "CNY"))
    }

    @Test
    fun parsesPriceFallback() {
        val raw = """
            {"hotelInformationList":[{"name":"A","price":{"lowestPrice":128.5,"currency":"CNY"}}]}
        """.trimIndent()
        val hotel = requireNotNull(parseHotelSearch(raw, "q", "p", "城市", null, 1)).hotels.single()
        assertTrue(hotel.priceText == "¥128.5")
    }

    private fun sampleDetailJson(): String = """
        {
          "success": true,
          "hotelId": 1109562,
          "name": "杭州西湖国宾馆",
          "checkIn": "2026-11-20",
          "checkOut": "2026-11-21",
          "bookingUrl": "https://rollinggo.cn/example",
          "roomRatePlans": [
            {
              "roomTypeId": 1,
              "roomName": "Deluxe King",
              "roomNameCn": "豪华大床房",
              "ratePlanId": "rp-free",
              "ratePlanName": "含双早 · 限时免费取消",
              "bedType": 1,
              "bedTypeDescription": "1 张大床",
              "currency": "CNY",
              "totalPrice": 1023,
              "totalSalesRate": 1023,
              "inventoryCount": 2,
              "isOnRequest": false,
              "cancellationPolicies": [
                {"fromDate": "2026-11-19T00:00:00+08:00", "amount": 0, "percent": 0, "description": "2026-11-19 前可免费取消"}
              ]
            },
            {
              "roomTypeId": 1,
              "roomNameCn": "豪华大床房",
              "ratePlanId": "rp-strict",
              "ratePlanName": "不含早 · 不可取消",
              "bedTypeDescription": "1 张大床",
              "currency": "CNY",
              "totalPrice": 823,
              "cancellationPolicies": [
                {"fromDate": "2026-11-19T00:00:00+08:00", "amount": 823, "percent": 100, "description": "不可取消"}
              ]
            },
            {
              "roomTypeId": 2,
              "roomNameCn": "园景双床房",
              "ratePlanId": "rp-twin",
              "bedTypeDescription": "2 单人床",
              "currency": "CNY",
              "totalPrice": 923,
              "cancellationPolicies": [
                {"amount": 0, "percent": 0, "description": "可免费取消"}
              ]
            }
          ]
        }
    """.trimIndent()

    private fun sampleSearchJson(): String = """
        {
          "success": true,
          "hotelInformationList": [
            {
              "hotelId": 1090005,
              "name": "杭州富阳东方茂开元名都大酒店",
              "starRating": 5,
              "address": "富春街道金桥北路61号",
              "distanceInMeters": 1200,
              "price": {"lowestPrice": 352, "currency": "CNY"},
              "tags": ["五星酒店", "免费WiFi"],
              "bookingUrl": "https://rollinggo.cn/example"
            }
          ]
        }
    """.trimIndent()

    private fun sampleOrdersJson(): String = """
        {
          "message": "查询成功",
          "orderInfoList": [
            {
              "hotelBookingInfo": {
                "referenceNo": "ref-1",
                "status": "3",
                "mainOrderNo": "M17816893017792282",
                "subOrderNo": "S17816893017792283",
                "checkInDate": "2026-11-20T00:00:00.000",
                "checkOutDate": "2026-11-21T00:00:00.000",
                "numOfRooms": 2,
                "nights": 1,
                "totalPrice": 1023,
                "paymentStatus": "CREATED"
              },
              "hotelBookingHotel": {
                "hotelName": "杭州西湖国宾馆",
                "hotelAddress": "西湖区杨公堤18号",
                "starRating": "5"
              },
              "hotelContact": {
                "firstName": "San",
                "lastName": "Zhang",
                "email": "san@example.com"
              },
              "hotelRatePlanInfo": {
                "roomName": "Deluxe King",
                "roomNameCn": "豪华大床房",
                "totalPrice": 1023,
                "currency": "CNY"
              }
            }
          ]
        }
    """.trimIndent()

    @Test
    fun parsesRealRollingGoRoomRatePlansWithoutTotalPrice() {
        val details = requireNotNull(parseHotelDetail(realRoomRatePlansJson(), "fallback"))

        assertEquals("广州威珀斯酒店", details.name)
        assertEquals("2026-11-16", details.checkIn)
        assertEquals("2026-11-18", details.checkOut)
        assertEquals(2, hotelNights(details.checkIn, details.checkOut))
        assertEquals(5, details.rooms.size)

        val plan = details.rooms.first()
        assertEquals("威珀斯商务大床房", plan.name)
        assertEquals("rp-a", plan.ratePlanId)
        assertEquals(754.0, plan.averagePrice!!, 0.001)
        assertEquals(1508.0, plan.totalPrice!!, 0.001)
        assertEquals("¥1508", plan.priceText)
        assertEquals("¥754", plan.avgPriceText)
        assertEquals("1 大床", plan.bed)
        assertEquals("不含早餐", plan.meal)
        assertEquals("不可免费取消", plan.cancellation)
        assertEquals(false, plan.cancelable)
        assertEquals(true, plan.onRequest)
        assertEquals("43", plan.roomSize)
        assertEquals("17-29", plan.floor)
        assertEquals(2, plan.maxOccupancy)
        assertEquals(true, plan.hasWindow)
        assertEquals(true, plan.hasWifi)
    }

    @Test
    fun realPlansGroupByRoomAndShowLowestTotalPrice() {
        val details = requireNotNull(parseHotelDetail(realRoomRatePlansJson(), "fallback"))

        assertEquals(4, details.roomGroups.size)
        val group = details.roomGroups.first()
        assertEquals("威珀斯商务大床房", group.name)
        assertEquals(2, group.planCount)
        assertEquals("¥1508", group.lowestPriceText)
        assertEquals("不可免费取消", group.cheapestCancellation)
        assertEquals(listOf("rp-a", "rp-b"), group.plans.map { it.ratePlanId })
    }

    @Test
    fun realPlansKeepExplicitTotalPriceAndMissingPriceStaysNull() {
        val details = requireNotNull(parseHotelDetail(realRoomRatePlansJson(), "fallback"))

        val explicit = details.rooms.single { it.ratePlanId == "rp-legacy" }
        assertEquals(300.0, explicit.totalPrice!!, 0.001)
        assertEquals("¥300", explicit.priceText)

        val missing = details.rooms.single { it.ratePlanId == "rp-missing-price" }
        assertNull(missing.totalPrice)
        assertNull(missing.priceText)
        assertNull(missing.avgPriceText)
    }

    @Test
    fun realPlansGroupsCarryRoomFactsAndImages() {
        val details = requireNotNull(parseHotelDetail(realRoomRatePlansJson(), "fallback"))

        val group = details.roomGroups.first()

        assertEquals("威珀斯商务大床房", group.name)
        assertEquals("43", group.roomSize)
        assertEquals("17-29", group.floor)
        assertEquals(2, group.maxOccupancy)
        assertEquals(true, group.hasWindow)
        assertEquals(true, group.hasWifi)
        assertEquals("https://image-cdn.rollinggo.cn/example.jpg", group.images.single())
        assertTrue(group.plans.all { it.ratePlanId != null })
    }

    @Test
    fun singleNightFallbackUsesAveragePriceAsTotal() {
        val details = requireNotNull(
            parseHotelDetail(
                """
                {"success":true,"name":"某酒店","checkIn":"2026-11-16","roomRatePlans":[
                  {"roomName":"大床房","ratePlanId":"rp-1","averagePrice":754,"currency":"CNY","cancelPolicy":"可免费取消","cancelable":true}
                ]}
                """.trimIndent(),
                "fallback",
            ),
        )

        assertEquals(1, hotelNights(details.checkIn, details.checkOut))
        assertEquals(754.0, details.rooms.single().totalPrice!!, 0.001)
        assertEquals("¥754", details.rooms.single().priceText)
        assertEquals("可免费取消", details.rooms.single().cancellation)
    }

    @Test
    fun nightsFallBackToSingleNightForInvalidDates() {
        assertEquals(1, hotelNights(null, null))
        assertEquals(1, hotelNights("2026-11-16", null))
        assertEquals(1, hotelNights("bad-date", "2026-11-18"))
        assertEquals(1, hotelNights("2026-11-18", "2026-11-16"))
        assertEquals(3, hotelNights("2026-11-16", "2026-11-19"))
    }

    @Test
    fun realRoomPlansSurviveCardSerializationRoundTrip() {
        val details = requireNotNull(parseHotelDetail(realRoomRatePlansJson(), "fallback"))

        val encoded = HotelJson.encodeToJsonElement(HotelDetailDetails.serializer(), details)
        val decoded = HotelJson.decodeFromJsonElement(HotelDetailDetails.serializer(), encoded)

        assertEquals(details, decoded)
        assertEquals(details.roomGroups, decoded.roomGroups)
    }

    private fun realRoomRatePlansJson(): String = """
        {
          "success": true,
          "hotelId": 207304,
          "bookingUrl": "https://rollinggo.cn/pages/hotel/detail/index?id=207304",
          "name": "广州威珀斯酒店",
          "starRating": 5,
          "checkIn": "2026-11-16",
          "checkOut": "2026-11-18",
          "filter": {},
          "noMatchingRates": false,
          "code": 2000,
          "message": "搜索成功",
          "nameEn": "Vaperse Hotel",
          "roomRatePlans": [
            {
              "roomName": "威珀斯商务大床房",
              "ratePlanId": "rp-a",
              "ratePlanName": "Vaperse Business Room(King Bed)",
              "averagePrice": 754,
              "bedTypeDescription": "1 大床",
              "currency": "CNY",
              "mealAmount": 0,
              "mealTypeStr": "不含早餐",
              "isOnRequest": true,
              "cancelPolicy": "不可免费取消",
              "cancelable": false,
              "roomInfo": {
                "hasWifi": true,
                "hasWindow": true,
                "maxOccupancy": 2,
                "size": "43",
                "floor": "17-29",
                "images": "https://image-cdn.rollinggo.cn/example.jpg"
              }
            },
            {
              "roomName": "威珀斯商务大床房",
              "ratePlanId": "rp-b",
              "ratePlanName": "Vaperse Business Room(King Bed) with breakfast",
              "averagePrice": 841,
              "bedTypeDescription": "1 大床",
              "currency": "CNY",
              "mealAmount": 1,
              "mealTypeStr": "含早餐",
              "isOnRequest": true,
              "cancelPolicy": "不可免费取消",
              "cancelable": false
            },
            {
              "roomName": "金珀商务双床房（公寓）",
              "ratePlanId": "rp-c",
              "ratePlanName": "金珀商务双床房(公寓)(KIMPER BUSINESS TWIN ROOM APARTMENT)",
              "averagePrice": 894,
              "bedTypeDescription": "2 单人床",
              "currency": "CNY",
              "mealAmount": 0,
              "mealTypeStr": "不含早餐",
              "isOnRequest": false,
              "cancelPolicy": "不可免费取消",
              "cancelable": false
            },
            {
              "roomName": "特价大床房",
              "ratePlanId": "rp-legacy",
              "ratePlanName": "旧版总价方案",
              "totalPrice": 300,
              "currency": "CNY",
              "cancelPolicy": "可免费取消"
            },
            {
              "roomName": "无价房型",
              "ratePlanId": "rp-missing-price",
              "ratePlanName": "价格未提供",
              "currency": "CNY"
            }
          ]
        }
    """.trimIndent()

    @Test
    fun parsesLiveRollingGoDetailSampleWithAveragePriceOnly() {
        val raw = requireNotNull(javaClass.classLoader?.getResourceAsStream("fixtures/hotel-detail-live.json"))
            .bufferedReader()
            .use { it.readText() }

        val details = requireNotNull(parseHotelDetail(raw, "广州威珀斯酒店"))

        assertEquals(70, details.rooms.size)
        assertEquals(16, details.roomGroups.size)
        assertEquals("Vaperse Hotel", details.nameEn)
        assertEquals("5", details.starRating)
        assertTrue(details.filter.isEmpty())
        assertEquals(false, details.noMatchingRates)
        assertTrue(details.rooms.all { it.averagePrice != null && it.totalPrice != null && it.priceText != null && it.totalDerived })
        val first = details.rooms.first()
        assertEquals("威珀斯商务大床房", first.name)
        assertEquals(754.0, first.averagePrice!!, 0.001)
        assertEquals(754.0, first.totalPrice!!, 0.001)
        assertEquals("¥754", first.priceText)
        assertEquals("1 大床", first.bed)
        assertEquals("不含早餐", first.meal)
        assertEquals(0, first.mealAmount)
        assertEquals("https://image-cdn.rollinggo.cn/v2/207304/room-type/default/6/13290290/201401021/image/205c05a4_z.jpg", first.images.single())
        assertEquals("不可免费取消", first.cancellation)
        assertEquals(false, first.cancelable)
        assertEquals(true, first.onRequest)
        assertEquals("43", first.roomSize)
        assertEquals("17-29", first.floor)
        assertEquals(2, first.maxOccupancy)
        assertEquals(true, first.hasWindow)
        assertEquals(true, first.hasWifi)
    }

    @Test
    fun liveSampleGroupsKeepEveryPlanAndRoomInfo() {
        val raw = requireNotNull(javaClass.classLoader?.getResourceAsStream("fixtures/hotel-detail-live.json"))
            .bufferedReader()
            .use { it.readText() }
        val details = requireNotNull(parseHotelDetail(raw, "广州威珀斯酒店"))

        assertEquals(16, details.roomGroups.size)
        assertEquals(70, details.roomGroups.sumOf { it.plans.size })
        assertTrue(details.roomGroups.any { it.images.isNotEmpty() })
        assertTrue(details.rooms.all { it.totalDerived })
        val guidance = hotelDetailGuidance(details, zh)
        assertTrue(guidance.contains("按名称分组"))
        assertTrue(guidance.contains("按 1 晚"))
    }

    @Test
    fun parsesRealOrderListSummaryShape() {
        val details = requireNotNull(parseHotelOrders(realOrderListJson()))

        assertEquals("查询成功", details.message)
        val order = details.orders.single()
        assertEquals("H178969795522549201", order.orderNo)
        assertEquals("广州威珀斯酒店", order.hotelName)
        assertEquals("威珀斯商务大床房", order.roomName)
        assertEquals(2, order.roomCount)
        assertEquals("2026-11-16", order.checkIn)
        assertEquals("2026-11-18", order.checkOut)
        assertEquals(2, order.nights)
        assertEquals("双早", order.meal)
        assertEquals("11月15日前免费取消", order.cancelPolicy)
        assertEquals("已确认", order.status)
        assertEquals("¥1508", order.totalPrice)
        assertEquals("CNY", order.currency)
        assertEquals("2026-11-10 12:34:56", order.orderDate)
        assertEquals("https://rollinggo.cn/order/detail?orderNo=H178969795522549201", order.orderDetailUrl)
    }

    @Test
    fun orderListKeepsCancellationPolicyAndPointsToDetail() {
        val details = requireNotNull(parseHotelOrders(realOrderListJson()))
        val order = details.orders.single()

        assertEquals("11月15日前免费取消", order.cancelPolicy)
        assertEquals("已确认", hotelOrderStatusText(order.status, zh))
        val guidance = hotelOrdersGuidance(details, zh)
        assertTrue(guidance.contains("没有取消原因"))
        assertTrue(guidance.contains("hotel_order_detail"))
    }

    @Test
    fun parsesOrderDetailWithEveryDocumentedField() {
        val details = requireNotNull(parseHotelOrderDetail(realOrderDetailJson(), "H178969795522549201"))

        assertEquals("H178969795522549201", details.orderNo)
        assertEquals("REF-1", details.referenceNo)
        assertEquals("HC-998877", details.hotelConfirmationNo)
        assertEquals("已确认", details.orderStatus)
        assertEquals("2026-11-10 12:34:56", details.orderDate)
        assertEquals("https://rollinggo.cn/pages/order/detail?orderNo=H178969795522549201", details.orderDetailUrl)
        assertEquals("广州威珀斯酒店", details.hotelName)
        assertEquals("https://image-cdn.rollinggo.cn/hotel.jpg", details.hotelImageUrl)
        assertEquals("020-12345678", details.hotelPhone)
        assertEquals("400-888-8888", details.platformSupportPhone)
        assertEquals("广州市天河区示例路 1 号", details.hotelAddress)
        assertEquals(23.123456, details.latitude!!, 0.000001)
        assertEquals(113.654321, details.longitude!!, 0.000001)
        assertEquals("威珀斯商务大床房", details.roomName)
        assertEquals("1张特大床 (1.8m)", details.bedType)
        assertEquals("2026-11-16", details.checkIn)
        assertEquals("2026-11-18", details.checkOut)
        assertEquals("14:00", details.checkInTime)
        assertEquals("12:00", details.checkOutTime)
        assertEquals(2, details.roomCount)
        assertEquals(2, details.nights)
        assertEquals("双早", details.meal)
        assertEquals("张三", details.contactName)
        assertEquals("13800000000", details.contactPhone)
        assertEquals("san@example.com", details.contactEmail)
        assertEquals(listOf("ZHANG SAN", "LI SI"), details.guestNames)
        assertEquals("¥1508", details.totalPrice)
        assertEquals("CNY", details.currency)
        assertEquals("URL", details.paymentType)
        assertEquals("https://rollinggo.cn/pay/H178969795522549201", details.paymentUrl)
        assertEquals("2026-11-10 13:04:56", details.paymentDeadline)
        assertEquals(true, details.isCancelable)
        assertEquals("2026-11-15 18:00:00", details.freeCancelDeadline)
        assertEquals("11月15日前免费取消", details.cancelPolicy)
    }

    @Test
    fun orderDetailGuidanceCoversCancellationAndPayment() {
        val details = requireNotNull(parseHotelOrderDetail(realOrderDetailJson(), "H178969795522549201"))

        val guidance = hotelOrderDetailGuidance(details, zh)

        assertTrue(guidance.contains("cancelPolicy"))
        assertTrue(guidance.contains("支付链接"))
    }

    @Test
    fun missingOrderDetailIsNotFabricated() {
        assertNull(parseHotelOrderDetail("""{"success":false,"message":"订单不存在"}""", "H1"))
    }

    @Test
    fun resultDetailsAttachCleanRawGuidanceAndCardTitle() {
        val body = kotlinx.serialization.json.buildJsonObject { put("message", "查询成功") }
        val raw = "\u001B[32m◇ injected env (1) from ../../root/.hotel-cli/.env // tip: ◈ encrypted .env [www.dotenvx.com]\u001B[0m\n" +
            "◇ injected env (1) from ../../root/.hotel-cli/.env // tip: ⌘ override existing { override: true }\n" +
            "◇ injected env (1) from ../../root/.hotel-cli/.env // tip: ⌘ enable debugging { debug: true }\n" +
            "{\"success\":true}\n[exit: 0]\n"

        val details = hotelResultDetails(body, raw, "使用说明", "酒店订单（1 笔）")

        assertEquals("{\"success\":true}", details[DEEPDEEP_RAW_KEY]?.jsonPrimitive?.contentOrNull)
        assertEquals("使用说明", details[DEEPDEEP_GUIDANCE_KEY]?.jsonPrimitive?.contentOrNull)
        assertEquals(
            "酒店订单（1 笔）",
            (details[DEEPDEEP_CARD_KEY] as kotlinx.serialization.json.JsonObject)["title"]?.jsonPrimitive?.contentOrNull,
        )
        assertEquals("查询成功", details["message"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun errorDetailsKeepMessageForCardAndRawForModel() {
        val details = hotelErrorDetails(
            "需要先安装酒店服务",
            "◇ injected env (1) from ../../root/.hotel-cli/.env // tip: ⌘ enable debugging { debug: true }\nboom\n[exit: 1]",
        )

        assertEquals("需要先安装酒店服务", details["message"]?.jsonPrimitive?.contentOrNull)
        assertEquals("需要先安装酒店服务", details[DEEPDEEP_GUIDANCE_KEY]?.jsonPrimitive?.contentOrNull)
        assertEquals("boom", details[DEEPDEEP_RAW_KEY]?.jsonPrimitive?.contentOrNull)
        assertTrue(!details.containsKey(DEEPDEEP_CARD_KEY))
    }

    @Test
    fun budgetsMarkOverBudgetCountAndLowestPriceWithoutDroppingResults() {
        val raw = """
            {"hotelInformationList":[
              {"name":"A","price":{"lowestPrice":147,"currency":"CNY"}},
              {"name":"B","price":{"lowestPrice":320,"currency":"CNY"}},
              {"name":"C","price":{"message":"价格待确认"}}
            ]}
        """.trimIndent()
        val details = requireNotNull(parseHotelSearch(raw, "q", "p", "城市", null, 1))

        val budgeted = withHotelBudget(details, 100.0)

        assertEquals(100.0, budgeted.maxPrice!!, 0.001)
        assertEquals(2, budgeted.overBudgetCount)
        assertEquals("¥147", budgeted.lowestOverBudgetPrice)
        assertEquals(3, budgeted.hotels.size)
        assertEquals(details.hotels, budgeted.hotels)
        assertTrue(hotelIsOverBudget(budgeted.hotels[0], budgeted.maxPrice))
        assertTrue(!hotelIsOverBudget(budgeted.hotels[2], budgeted.maxPrice))

        val withinBudget = withHotelBudget(details, 400.0)
        assertEquals(0, withinBudget.overBudgetCount)
        assertNull(withinBudget.lowestOverBudgetPrice)
        assertNull(withHotelBudget(details, null).maxPrice)
    }

    @Test
    fun displayLabelsDedupeAndLimit() {
        val labels = hotelDistinctLabels(listOf("免费WiFi", "停车场", "免费WiFi", "  ", "停车场"))

        assertEquals(listOf("免费WiFi", "停车场"), labels)
        assertEquals(listOf("A", "B", "C"), hotelVisibleLabels(listOf("A", "B", "C", "D"), expanded = false))
        assertEquals(listOf("A", "B", "C", "D"), hotelVisibleLabels(listOf("A", "B", "C", "D"), expanded = true))
    }

    @Test
    fun budgetIsInferredFromChineseQuery() {
        assertEquals(100.0, hotelBudgetFromQuery("中大南校园附近 100 元以内 一晚")!!, 0.001)
        assertEquals(300.0, hotelBudgetFromQuery("预算300元，找五星酒店")!!, 0.001)
        assertEquals(200.0, hotelBudgetFromQuery("每晚不超过200元")!!, 0.001)
        assertNull(hotelBudgetFromQuery("中大南校园附近一晚"))
        assertNull(hotelBudgetFromQuery("距离1000米以内"))
    }

    @Test
    fun dirtyRatePlanNameIsNormalizedForDisplayButKeptInPayload() {
        assertEquals("大床 1.8米", hotelDisplayRatePlanName(",,大床|1|1.8米"))
        assertEquals("大床房(Queen Room)", hotelDisplayRatePlanName("大床房(Queen Room)"))
        assertNull(hotelDisplayRatePlanName(" , , "))

        val raw = """
            {"name":"某酒店","checkIn":"2026-11-20","checkOut":"2026-11-21","roomRatePlans":[
              {"roomName":"特惠房","ratePlanId":"rp-1","ratePlanName":",,大床|1|1.8米","averagePrice":218,"currency":"CNY","bedTypeDescription":"1 大床 或 2 单人床"}
            ]}
        """.trimIndent()
        val details = requireNotNull(parseHotelDetail(raw, "fallback"))
        val plan = details.rooms.single()
        val payload = HotelJson.encodeToJsonElement(HotelDetailDetails.serializer(), details)
        val decoded = HotelJson.decodeFromJsonElement(HotelDetailDetails.serializer(), payload)

        assertEquals(",,大床|1|1.8米", plan.ratePlanName)
        assertEquals("大床 1.8米", hotelDisplayRatePlanName(plan.ratePlanName))
        assertEquals("1 大床 或 2 单人床", plan.bed)
        assertEquals(",,大床|1|1.8米", decoded.rooms.single().ratePlanName)
        assertEquals("大床 1.8米", hotelDisplayRatePlanName(decoded.rooms.single().ratePlanName))
    }

    @Test
    fun searchCardPayloadKeepsImageLowestPriceAndBudget() {
        val raw = """
            {"hotelInformationList":[
              {"name":"A","imageUrl":"https://image-cdn.rollinggo.cn/a.jpg","price":{"lowestPrice":147,"currency":"CNY"},"tags":["t1"],"hotelAmenities":["wifi","wifi"]}
            ],"nextPageToken":"token"}
        """.trimIndent()
        val details = withHotelBudget(
            requireNotNull(parseHotelSearch(raw, "q", "p", "城市", null, 1)),
            100.0,
        )
        val payload = HotelJson.encodeToJsonElement(HotelSearchDetails.serializer(), details)
        val decoded = HotelJson.decodeFromJsonElement(HotelSearchDetails.serializer(), payload)

        assertEquals(details, decoded)
        assertEquals("https://image-cdn.rollinggo.cn/a.jpg", decoded.hotels.single().imageUrl)
        assertEquals(147.0, decoded.hotels.single().lowestPrice!!, 0.001)
        assertEquals(1, decoded.overBudgetCount)
        assertEquals(100.0, decoded.maxPrice!!, 0.001)
        assertEquals("token", decoded.nextPageToken)
    }

    @Test
    fun cardPayloadKeepsAllRoomsPlansAndOrders() {
        val raw = requireNotNull(javaClass.classLoader?.getResourceAsStream("fixtures/hotel-detail-live.json"))
            .bufferedReader()
            .use { it.readText() }
        val detail = requireNotNull(parseHotelDetail(raw, "广州威珀斯酒店"))
        val detailPayload = HotelJson.encodeToJsonElement(HotelDetailDetails.serializer(), detail)
        val decodedDetail = HotelJson.decodeFromJsonElement(HotelDetailDetails.serializer(), detailPayload)

        assertEquals(16, decodedDetail.roomGroups.size)
        assertEquals(70, decodedDetail.roomGroups.sumOf { it.plans.size })
        assertTrue(decodedDetail.roomGroups.all { it.plans.size == it.planCount })
        assertTrue(decodedDetail.rooms.all { it.totalDerived })

        val orders = requireNotNull(parseHotelOrders(realOrderListJson()))
        val ordersPayload = HotelJson.encodeToJsonElement(HotelOrdersDetails.serializer(), orders)
        val decodedOrders = HotelJson.decodeFromJsonElement(HotelOrdersDetails.serializer(), ordersPayload)
        assertEquals(orders.orders, decodedOrders.orders)
        assertEquals(orders.orders.single().orderDetailUrl, decodedOrders.orders.single().orderDetailUrl)

        val orderDetail = requireNotNull(parseHotelOrderDetail(realOrderDetailJson(), "H178969795522549201"))
        val orderDetailPayload = HotelJson.encodeToJsonElement(HotelOrderDetail.serializer(), orderDetail)
        val decodedOrderDetail = HotelJson.decodeFromJsonElement(HotelOrderDetail.serializer(), orderDetailPayload)
        assertEquals(orderDetail, decodedOrderDetail)
        assertEquals(listOf("ZHANG SAN", "LI SI"), decodedOrderDetail.guestNames)
    }

    private fun realOrderListJson(): String = """
        {
          "success": true,
          "code": 2000,
          "message": "查询成功",
          "orderList": [
            {
              "orderNo": "H178969795522549201",
              "hotelName": "广州威珀斯酒店",
              "roomName": "威珀斯商务大床房",
              "numOfRooms": 2,
              "checkInDate": "2026-11-16",
              "checkOutDate": "2026-11-18",
              "mealDesc": "双早",
              "cancelPolicyDesc": "11月15日前免费取消",
              "orderStatus": "已确认",
              "totalPrice": 1508,
              "currency": "CNY",
              "orderDate": "2026-11-10 12:34:56",
              "orderDetailUrl": "https://rollinggo.cn/order/detail?orderNo=H178969795522549201",
              "paymentUrl": null
            }
          ]
        }
    """.trimIndent()

    private fun realOrderDetailJson(): String = """
        {
          "success": true,
          "code": 2000,
          "message": "查询成功",
          "orderDetail": {
            "baseInfo": {
              "orderNo": "H178969795522549201",
              "referenceNo": "REF-1",
              "hotelConfirmationNo": "HC-998877",
              "orderStatus": "已确认",
              "orderDate": "2026-11-10 12:34:56",
              "orderDetailUrl": "https://rollinggo.cn/pages/order/detail?orderNo=H178969795522549201"
            },
            "hotelInfo": {
              "hotelName": "广州威珀斯酒店",
              "hotelImageUrl": "https://image-cdn.rollinggo.cn/hotel.jpg",
              "hotelPhone": "020-12345678",
              "platformSupportPhone": "400-888-8888",
              "hotelAddress": "广州市天河区示例路 1 号",
              "latitude": 23.123456,
              "longitude": 113.654321
            },
            "stayInfo": {
              "roomName": "威珀斯商务大床房",
              "bedTypeStr": "1张特大床 (1.8m)",
              "checkInDate": "2026-11-16",
              "checkOutDate": "2026-11-18",
              "checkInTime": "14:00",
              "checkOutTime": "12:00",
              "numOfRooms": 2,
              "nights": 2,
              "mealDesc": "双早",
              "contact": {"name": "张三", "phone": "13800000000", "email": "san@example.com"},
              "guestNames": ["ZHANG SAN", "LI SI"]
            },
            "priceInfo": {
              "totalPrice": 1508,
              "currency": "CNY",
              "paymentType": "URL",
              "paymentUrl": "https://rollinggo.cn/pay/H178969795522549201",
              "paymentDeadline": "2026-11-10 13:04:56"
            },
            "policyInfo": {
              "isCancelable": true,
              "freeCancelDeadline": "2026-11-15 18:00:00",
              "cancelPolicyDesc": "11月15日前免费取消"
            }
          }
        }
    """.trimIndent()


    @Test
    fun liveSamplePayloadKeepsEveryRatePlanId() {
        val raw = requireNotNull(javaClass.classLoader?.getResourceAsStream("fixtures/hotel-detail-live.json"))
            .bufferedReader()
            .use { it.readText() }
        val details = requireNotNull(parseHotelDetail(raw, "广州威珀斯酒店"))
        val payload = HotelJson.encodeToJsonElement(HotelDetailDetails.serializer(), details)
        val decoded = HotelJson.decodeFromJsonElement(HotelDetailDetails.serializer(), payload)

        assertEquals(70, decoded.rooms.size)
        assertEquals(details.rooms.map { it.ratePlanId }, decoded.rooms.map { it.ratePlanId })
        assertTrue(decoded.rooms.all { it.ratePlanId != null })
    }

    @Test
    fun ordersPayloadKeepsEveryOrderAndDetailLink() {
        val rows = (1..4).joinToString(",") { index ->
            """{"orderNo":"H$index","hotelName":"酒店$index","roomName":"大床房","checkInDate":"2026-11-16","checkOutDate":"2026-11-17","orderStatus":"已确认","totalPrice":$index}"""
        }
        val details = requireNotNull(parseHotelOrders("""{"message":"查询成功","orderList":[$rows]}"""))
        val payload = HotelJson.encodeToJsonElement(HotelOrdersDetails.serializer(), details)
        val decoded = HotelJson.decodeFromJsonElement(HotelOrdersDetails.serializer(), payload)

        assertEquals(4, decoded.orders.size)
        assertEquals(details.orders, decoded.orders)
        assertEquals("H1", decoded.orders.first().orderNo)
    }

    @Test
    fun groupsRoomsIgnoringMarketingSuffix() {
        val plans = listOf(
            roomPlan(name = "豪华家庭房", ratePlanId = "p1", price = 500.0),
            roomPlan(name = "豪华家庭房（大空间+一房一厅+可观广州塔景色）", ratePlanId = "p2", price = 700.0),
            roomPlan(name = "行政套房", ratePlanId = "p3", price = 900.0),
        )
        val groups = groupHotelRooms(plans)
        assertEquals(2, groups.size)
        assertEquals("豪华家庭房", groups.first().name)
        assertEquals(2, groups.first().plans.size)
        assertEquals("行政套房", groups.last().name)
    }

    @Test
    fun extractsRoomMarketingSuffixes() {
        assertEquals(listOf("无窗"), hotelRoomSuffixes("双床房（无窗）"))
        assertEquals(listOf("大空间+一房一厅+可观广州塔景色"), hotelRoomSuffixes("豪华家庭房（大空间+一房一厅+可观广州塔景色）"))
        assertTrue(hotelRoomSuffixes("豪华家庭房").isEmpty())
        assertEquals("双床房", hotelRoomBaseName("双床房（无窗）（高层）"))
    }

    @Test
    fun parsesSearchTagsGroupedByCategory() {
        val tags = parseHotelSearchTags(
            """{"success":true,"tags":[{"name":"免费WiFi","category":"服务与餐饮"},{"name":"近地铁","category":"交通与支付"},{"name":"全季","category":"品牌与评分"}]}""",
        )
        assertEquals(3, tags?.count)
        assertEquals(3, tags?.categories?.size)
        assertEquals(listOf("免费WiFi"), tags?.categories?.first { it.category == "服务与餐饮" }?.names)
    }

    @Test
    fun parsesCancelQuoteAndResult() {
        val quote = parseHotelCancelQuote(
            """{"success":true,"canCancel":true,"orderNo":"H1","cancelFee":50,"currency":"CNY","cancelPolicyDesc":"入住当天 18:00 前取消扣 50 元","confirmId":"c1","bookingId":"b1","expireTime":"2026-11-20 21:10:00"}""",
            "H1",
        )
        assertEquals("H1", quote?.orderNo)
        assertTrue(quote?.canCancel == true)
        assertEquals("¥50", quote?.feeText)
        assertEquals("c1", quote?.confirmId)
        assertEquals("b1", quote?.bookingId)
        assertEquals("入住当天 18:00 前取消扣 50 元", quote?.policy)

        val refused = parseHotelCancelQuote(
            """{"success":false,"canCancel":false,"message":"该订单按规则无法在线取消","orderNo":"H1"}""",
            "H1",
        )
        assertFalse(refused?.canCancel ?: true)
        assertTrue(refused?.message.orEmpty().contains("无法在线取消"))

        val result = parseHotelCancelResult(
            """{"success":true,"orderNo":"H1","orderStatus":"Canceled","message":"取消成功"}""",
            "H1",
            "西湖国宾馆",
        )
        assertTrue(result?.cancelled == true)
        assertEquals("Canceled", result?.orderStatus)
        assertEquals("西湖国宾馆", result?.hotelName)
    }

    @Test
    fun sortsByBudgetThenDistance() {
        val hotels = listOf(
            sortedHotel("A", price = 300.0, meters = 2000),
            sortedHotel("B", price = 180.0, meters = 800),
            sortedHotel("C", price = 240.0, meters = 1500),
        )
        val priced = sortHotelResults(searchDetails(hotels, maxPrice = 300.0))
        assertEquals("price", priced.sortedBy)
        assertEquals(listOf("B", "C", "A"), priced.hotels.map { it.name })

        val distanced = sortHotelResults(searchDetails(hotels, maxPrice = null))
        assertEquals("distance", distanced.sortedBy)
        assertEquals(listOf("B", "C", "A"), distanced.hotels.map { it.name })

        val noSignal = sortHotelResults(
            searchDetails(hotels.map { it.copy(distanceMeters = null) }, maxPrice = null),
        )
        assertNull(noSignal.sortedBy)
        assertEquals(listOf("A", "B", "C"), noSignal.hotels.map { it.name })
    }

    private fun roomPlan(name: String, ratePlanId: String, price: Double) = HotelRoomPlan(
        name = name,
        ratePlanId = ratePlanId,
        priceText = "¥$price",
        totalPrice = price,
        currency = "CNY",
    )

    private fun sortedHotel(name: String, price: Double, meters: Int) = HotelSummary(
        name = name,
        priceText = "¥$price",
        lowestPrice = price,
        currency = "CNY",
        distanceMeters = meters,
    )

    private fun searchDetails(hotels: List<HotelSummary>, maxPrice: Double?) = HotelSearchDetails(
        query = "西湖附近酒店",
        place = "西湖",
        placeType = "景点",
        checkIn = "2026-11-20",
        nights = 1,
        hotels = hotels,
        fetchedAt = 1L,
        maxPrice = maxPrice,
    )

    @Test
    fun groupsPlanVariantsByBedMealAndPrice() {
        val plans = listOf(
            HotelRoomPlan(
                name = "特惠房", ratePlanName = "大床", bed = "1 大床", meal = "不含早",
                priceText = "¥200", avgPriceText = "¥200", totalPrice = 200.0, ratePlanId = "p1",
                cancellation = "不可取消", cancellations = listOf("不可取消"), onRequest = false,
            ),
            HotelRoomPlan(
                name = "特惠房", ratePlanName = "大床", bed = "1 大床", meal = "不含早",
                priceText = "¥200", avgPriceText = "¥200", totalPrice = 200.0, ratePlanId = "p2",
                cancellation = "入住当天 18:00 前免费取消", cancellations = listOf("入住当天 18:00 前免费取消"),
                onRequest = true,
            ),
            HotelRoomPlan(
                name = "特惠房", ratePlanName = "大床", bed = "1 大床", meal = "不含早",
                priceText = "¥220", avgPriceText = "¥220", totalPrice = 220.0, ratePlanId = "p3",
                cancellation = "不可取消", cancellations = listOf("不可取消"), onRequest = false,
            ),
        )
        val variants = groupHotelPlanVariants(plans)
        assertEquals(2, variants.size)
        assertEquals(2, variants.first().count)
        assertEquals(1, variants.last().count)
        assertEquals(2, variants.first().policies.size)
        assertTrue(variants.first().mixedOnRequest)
        assertEquals("p1", variants.first().plan.ratePlanId)
    }

    @Test
    fun cleansBedDescriptionAndDropsContradictorySingleBed() {
        assertEquals(null, hotelCleanBed("1 单人床", "双床房"))
        assertEquals("1 大床", hotelCleanBed("1 大床", "大床房"))
        assertEquals("2 单人床", hotelCleanBed("2 单人床", "双床房"))
        assertEquals("大床 1.8米", hotelCleanBed(",,大床|1|1.8米", "大床房"))
    }

    @Test
    fun budgetGuidanceExplainsUpstreamFilterWhenNothingIsOverBudget() {
        val details = withHotelBudget(
            searchDetails(
                listOf(sortedHotel("A", price = 180.0, meters = 800), sortedHotel("B", price = 260.0, meters = 900)),
                maxPrice = 300.0,
            ),
            300.0,
        )
        val guidance = hotelSearchGuidance(details, zh)
        assertTrue(guidance.contains("上游筛选"))
        assertTrue(guidance.contains("size"))
    }

    @Test
    fun cancelGuidanceForAlreadyCancelledOrderDoesNotAskForSupport() {
        val guidance = hotelCancelQuoteGuidance(
            HotelCancelQuote(
                orderNo = "H1",
                canCancel = false,
                alreadyCancelled = true,
                fetchedAt = 1L,
            ),
            zh,
        )
        assertTrue(guidance.contains("已经取消"))
        assertFalse(guidance.contains("请联系") || guidance.contains("需要联系"))
    }
}
