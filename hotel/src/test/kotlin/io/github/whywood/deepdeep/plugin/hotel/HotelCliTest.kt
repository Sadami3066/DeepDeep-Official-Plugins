package io.github.whywood.superpi.plugin.hotel

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

        assertTrue(command.startsWith("HOME=/root DOTENV_CONFIG_QUIET=true rgh search-hotels"))
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
        assertTrue(orders.startsWith("HOME=/root DOTENV_CONFIG_QUIET=true rgh orders"))
        assertTrue(orders.contains("--status 'PENDING'"))

        val orderDetail = HotelCli.orderDetailCommand("H178969795522549201")
        assertTrue(orderDetail.startsWith("HOME=/root DOTENV_CONFIG_QUIET=true rgh order-detail"))
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
        assertTrue(confirm.startsWith("HOME=/root DOTENV_CONFIG_QUIET=true rgh price-confirm"))
        assertTrue(confirm.contains("--rate-plan-id 'plan-1'"))
        assertTrue(confirm.contains("--rooms 1"))
        assertTrue(confirm.contains("--adults 2"))

        val book = HotelCli.bookCommand("ref-1", "San", "Zhang", "san@example.com")
        assertTrue(book.startsWith("HOME=/root DOTENV_CONFIG_QUIET=true rgh book"))
        assertTrue(book.contains("--reference-no 'ref-1'"))
        assertTrue(book.contains("--email 'san@example.com'"))
    }

    @Test
    fun everyCommandSilencesDotenvTips() {
        val commands = listOf(
            HotelCli.versionCommand(),
            HotelCli.statusCommand(),
            HotelCli.loginCommand(),
            HotelCli.logoutCommand(),
            HotelCli.searchCommand(
                query = "q",
                place = "p",
                placeType = "城市",
                checkIn = null,
                nights = 1,
                adults = 2,
                size = 5,
                starRatings = null,
            ),
            HotelCli.detailCommand(1, null, null, null, 1, 2),
            HotelCli.priceConfirmCommand(1, "rp-1", 1, "2026-11-20", "2026-11-21", 2),
            HotelCli.bookCommand("ref-1", "San", "Zhang", "san@example.com"),
            HotelCli.ordersCommand(null),
            HotelCli.orderDetailCommand("H1"),
            HotelCli.preCancelCommand("H1"),
            HotelCli.confirmCancelCommand("H1", "b1", "c1", null),
            HotelCli.tagsCommand(),
            HotelCli.updateCommand(),
        )

        commands.forEach { command ->
            assertTrue(command, command.startsWith("HOME=/root DOTENV_CONFIG_QUIET=true rgh"))
        }
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

    @Test
    fun buildsSearchAndDetailFilterCommands() {
        val search = HotelCli.searchCommand(
            query = "西湖附近能免费取消的酒店",
            place = "西湖",
            placeType = "景点",
            checkIn = "2026-11-20",
            nights = 2,
            adults = 2,
            size = 5,
            starRatings = "4,5",
            distanceInMeter = 1000,
            requiredTags = listOf("免费WiFi", "近地铁"),
            preferredBrands = listOf("全季"),
            maxPricePerNight = 300.0,
        )
        assertTrue(search.contains("--distance-in-meter 1000"))
        assertTrue(search.contains("--required-tag '免费WiFi'"))
        assertTrue(search.contains("--required-tag '近地铁'"))
        assertTrue(search.contains("--preferred-brand '全季'"))
        assertTrue(search.contains("--max-price-per-night 300"))

        val detail = HotelCli.detailCommand(
            hotelId = null,
            name = "杭州西湖国宾馆",
            checkIn = "2026-11-20",
            checkOut = "2026-11-21",
            rooms = 1,
            adults = 2,
            cancelPolicy = "CANCELABLE",
            mealType = "WITH_BREAKFAST",
            childCount = 1,
            childAges = "4,8",
        )
        assertTrue(detail.contains("--cancel-policy 'CANCELABLE'"))
        assertTrue(detail.contains("--meal-type 'WITH_BREAKFAST'"))
        assertTrue(detail.contains("--child-count 1"))
        assertTrue(detail.contains("--child-age '4,8'"))

        val orders = HotelCli.ordersCommand("ALL", "2026-11-01", "2026-11-30")
        assertTrue(orders.contains("--start-date '2026-11-01'"))
        assertTrue(orders.contains("--end-date '2026-11-30'"))
    }

    @Test
    fun buildsBookCommandWithGuestsAndRequest() {
        val book = HotelCli.bookCommand(
            referenceNo = "ref-1",
            firstName = "",
            lastName = "",
            email = "san@example.com",
            guests = listOf(
                HotelGuest(roomNum = 1, firstName = "San", lastName = "Zhang", isAdult = true),
                HotelGuest(roomNum = 2, firstName = "Si", lastName = "Li", isAdult = false),
            ),
            customerRequest = "高楼层，无烟房",
        )
        assertTrue(book.contains("--guest '1,San,Zhang,true'"))
        assertTrue(book.contains("--guest '2,Si,Li,false'"))
        assertTrue(book.contains("--customer-request '高楼层，无烟房'"))
        assertFalse(book.contains("--first-name"))
        assertTrue(book.contains("--email 'san@example.com'"))
    }

    @Test
    fun buildsCancelTagsAndUpdateCommands() {
        val preCancel = HotelCli.preCancelCommand("H179476076885217871")
        assertTrue(preCancel.startsWith("HOME=/root DOTENV_CONFIG_QUIET=true rgh pre-cancel"))
        assertTrue(preCancel.contains("--order-no 'H179476076885217871'"))

        val confirm = HotelCli.confirmCancelCommand("H1", "b1", "c1", "行程有变")
        assertTrue(confirm.contains("--booking-id 'b1'"))
        assertTrue(confirm.contains("--confirm-id 'c1'"))
        assertTrue(confirm.contains("--description '行程有变'"))

        assertTrue(HotelCli.tagsCommand().startsWith("HOME=/root DOTENV_CONFIG_QUIET=true rgh hotel-tags"))
        assertTrue(HotelCli.updateCommand().startsWith("HOME=/root DOTENV_CONFIG_QUIET=true rgh update"))
        assertEquals("1.3.0", HotelCli.parseLatestVersion("1.3.0"))
        assertEquals("1.3.0", HotelCli.parseLatestVersion("noise\n1.3.0\n"))
    }

    @Test
    fun comparesVersionsNumeric() {
        assertTrue(HotelCli.versionNewer("1.3.0", "1.2.25"))
        assertTrue(HotelCli.versionNewer("1.10.0", "1.9.9"))
        assertFalse(HotelCli.versionNewer("1.2.25", "1.2.25"))
        assertFalse(HotelCli.versionNewer("1.2.24", "1.2.25"))
        assertFalse(HotelCli.versionNewer(null, "1.2.25"))
        assertFalse(HotelCli.versionNewer("1.3.0", null))
    }
}
