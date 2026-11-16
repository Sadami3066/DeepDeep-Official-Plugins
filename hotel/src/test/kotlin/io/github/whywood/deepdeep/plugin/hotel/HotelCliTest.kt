package io.github.whywood.deepdeep.plugin.hotel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HotelCliTest {
    @Test
    fun quotesShellArguments() {
        assertEquals("'hello'", HotelCli.quote("hello"))
        assertEquals("'it'\\''s'", HotelCli.quote("it's"))
        assertEquals("''", HotelCli.quote(""))
    }

    @Test
    fun buildsSearchCommandWithQuotedInputs() {
        val command = HotelCli.searchCommand(
            query = "杭州西湖附近五星酒店",
            place = "杭州西湖",
            placeType = "景点",
            checkIn = "2026-06-20",
            nights = 1,
            adults = 2,
            size = 5,
            starRatings = "4.5,5",
        )

        assertTrue(command.startsWith("HOME=/root rgh search-hotels"))
        assertTrue(command.contains("--origin-query '杭州西湖附近五星酒店'"))
        assertTrue(command.contains("--place '杭州西湖'"))
        assertTrue(command.contains("--place-type '景点'"))
        assertTrue(command.contains("--check-in-date '2026-06-20'"))
        assertTrue(command.contains("--size 5"))
        assertFalse(command.contains("hotelId"))
    }

    @Test
    fun buildsDetailAndOrdersCommands() {
        val detail = HotelCli.detailCommand(
            hotelId = 1090005,
            name = null,
            checkIn = "2026-06-20",
            checkOut = "2026-06-21",
            rooms = 1,
            adults = 2,
        )
        assertTrue(detail.contains("--hotel-id 1090005"))
        assertTrue(detail.contains("--check-out-date '2026-06-21'"))

        val orders = HotelCli.ordersCommand("PENDING")
        assertTrue(orders.startsWith("HOME=/root rgh orders"))
        assertTrue(orders.contains("--status 'PENDING'"))

        val orderDetail = HotelCli.orderDetailCommand("H178969795522549201")
        assertTrue(orderDetail.startsWith("HOME=/root rgh order-detail"))
        assertTrue(orderDetail.contains("'H178969795522549201'"))
    }

    @Test
    fun buildsPriceConfirmAndBookCommands() {
        val confirm = HotelCli.priceConfirmCommand(
            hotelId = 1090005,
            ratePlanId = "plan-1",
            rooms = 1,
            checkIn = "2026-06-20",
            checkOut = "2026-06-21",
            adults = 2,
        )
        assertTrue(confirm.startsWith("HOME=/root rgh price-confirm"))
        assertTrue(confirm.contains("--rate-plan-id 'plan-1'"))
        assertTrue(confirm.contains("--rooms 1"))
        assertTrue(confirm.contains("--adults 2"))

        val book = HotelCli.bookCommand("ref-1", "San", "Zhang", "san@example.com")
        assertTrue(book.startsWith("HOME=/root rgh book"))
        assertTrue(book.contains("--reference-no 'ref-1'"))
        assertTrue(book.contains("--email 'san@example.com'"))
    }

    @Test
    fun parsesVersionAndStatus() {
        assertEquals("1.2.25", HotelCli.parseVersion("1.2.25"))
        assertEquals("1.2.25", HotelCli.parseVersion("rgh 1.2.25\n更新可用"))

        val status = HotelCli.parseStatus("已登录\n   用户: 张三")
        assertTrue(status.loggedIn)
        assertEquals("张三", status.account)

        val signedOut = HotelCli.parseStatus("未登录，请先执行 rgh login")
        assertFalse(signedOut.loggedIn)
    }

    @Test
    fun parsesAuthorizationUrl() {
        val output = "请打开 https://api.rollinggo.store/oauth?code=abc 完成登录"
        assertEquals("https://api.rollinggo.store/oauth?code=abc", HotelCli.parseLoginUrl(output))
    }
}
