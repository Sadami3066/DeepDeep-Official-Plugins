package io.github.whywood.deepdeep.plugin.hotel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HotelJsonTest {
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
                  "price": {"lowestPrice": 352, "currency": "CNY"},
                  "tags": ["五星酒店", "免费WiFi", "免费停车场"],
                  "bookingUrl": "https://rollinggo.cn/example"
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
        assertEquals("富春街道金桥北路61号", hotel.address)
        assertEquals(listOf("五星酒店", "免费WiFi", "免费停车场"), hotel.tags)
        assertEquals("https://rollinggo.cn/example", hotel.bookingUrl)
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
        assertEquals("2026-06-19 起 ¥352", room.cancellation)
    }

    @Test
    fun parsesOrdersArray() {
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
}
