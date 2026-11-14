package io.github.whywood.deepdeep.plugin.rail

import java.net.URLEncoder
import java.time.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrainServiceTest {
    private fun trainRow(
        code: String = "G1",
        fromCode: String = "BJP",
        toCode: String = "SHH",
        depart: String = "08:00",
        arrive: String = "12:00",
        duration: String = "04:00",
        seats: Map<Int, String> = emptyMap(),
    ): String {
        val fields = MutableList(40) { "" }
        fields[3] = code
        fields[6] = fromCode
        fields[7] = toCode
        fields[8] = depart
        fields[9] = arrive
        fields[10] = duration
        seats.forEach { (index, value) -> fields[index] = value }
        return fields.joinToString("|")
    }

    private fun queryPayload(rows: List<String>): String {
        val result = rows.joinToString(",") { "\"$it\"" }
        return """{"status":true,"data":{"result":[$result],"map":{"BJP":"北京","SHH":"上海"}}}"""
    }

    @Test
    fun parsesQueryGResponseWithStationMap() {
        val payload = TicketPayload.parse(queryPayload(listOf(trainRow())))

        assertEquals(true, payload?.status)
        assertEquals(1, payload?.rows?.size)
        assertEquals("北京", payload?.stationNames?.get("BJP"))
        assertEquals("上海", payload?.stationNames?.get("SHH"))
    }

    @Test
    fun parsesTrainRowUsingStationMap() {
        val payload = requireNotNull(TicketPayload.parse(queryPayload(listOf(trainRow()))))
        val train = requireNotNull(
            parseTrainOption(
                payload.rows.single().split('|'),
                stationName = { code -> payload.stationNames[code] ?: code },
            ),
        )

        assertEquals("G1", train.code)
        assertEquals("北京", train.fromStation)
        assertEquals("上海", train.toStation)
        assertEquals("08:00", train.depart)
        assertEquals("12:00", train.arrive)
        assertEquals("04:00", train.duration)
    }

    @Test
    fun staleQueryResponsePointsToQueryG() {
        val payload = TicketPayload.parse("""{"c_url":"leftTicket/queryG","status":false}""")

        assertEquals(false, payload?.status)
        assertEquals("leftTicket/queryG", payload?.cUrl)
        assertTrue(payload?.rows?.isEmpty() == true)
        assertEquals("https://kyfw.12306.cn/otn/leftTicket/queryG", RailEndpoints.nextUrl(payload?.cUrl))
    }

    @Test
    fun followsRedirectUrlsWithSlashOrAbsoluteValue() {
        assertEquals(
            "https://kyfw.12306.cn/otn/leftTicket/queryZ",
            RailEndpoints.nextUrl("/leftTicket/queryZ"),
        )
        assertEquals(
            "https://kyfw.12306.cn/otn/leftTicket/queryA",
            RailEndpoints.nextUrl("https://kyfw.12306.cn/otn/leftTicket/queryA"),
        )
        assertNull(RailEndpoints.nextUrl(null))
        assertNull(RailEndpoints.nextUrl("  "))
    }

    @Test
    fun bookingUrlEncodesStationNames() {
        val url = RailEndpoints.bookingUrl("北京", "BJP", "上海", "SHH", "2026-01-01")

        assertEquals(
            "https://kyfw.12306.cn/otn/leftTicket/init?linktypeid=dc" +
                "&fs=${URLEncoder.encode("北京", "UTF-8")},BJP" +
                "&ts=${URLEncoder.encode("上海", "UTF-8")},SHH" +
                "&date=2026-01-01&flag=N,N,Y",
            url,
        )
        assertTrue(url.contains(",BJP"))
        assertTrue(url.contains(",SHH"))
    }

    @Test
    fun queryUrlCarriesAdultPurposeAndCodes() {
        val url = RailEndpoints.queryUrl("2026-01-01", "BJP", "SHH")

        assertEquals(
            "https://kyfw.12306.cn/otn/leftTicket/query" +
                "?leftTicketDTO.train_date=2026-01-01" +
                "&leftTicketDTO.from_station=BJP" +
                "&leftTicketDTO.to_station=SHH" +
                "&purpose_codes=ADULT",
            url,
        )
    }

    @Test
    fun queryGUrlIsPreferredEndpoint() {
        assertEquals(
            "https://kyfw.12306.cn/otn/leftTicket/queryG" +
                "?leftTicketDTO.train_date=2026-01-01" +
                "&leftTicketDTO.from_station=BJP" +
                "&leftTicketDTO.to_station=SHH" +
                "&purpose_codes=ADULT",
            RailEndpoints.queryGUrl("2026-01-01", "BJP", "SHH"),
        )
    }

    @Test
    fun validatesTravelDateWindow() {
        val today = LocalDate.of(2026, 1, 1)

        assertEquals(TravelDateIssue.VALID, TravelDate.validate("2026-01-01", today))
        assertEquals(
            TravelDateIssue.VALID,
            TravelDate.validate(today.plusDays(RAIL_DATE_WINDOW_DAYS.toLong()).toString(), today),
        )
        assertEquals(TravelDateIssue.BEFORE_TODAY, TravelDate.validate("2025-12-31", today))
        assertEquals(
            TravelDateIssue.BEYOND_WINDOW,
            TravelDate.validate(today.plusDays(RAIL_DATE_WINDOW_DAYS.toLong() + 1).toString(), today),
        )
        assertEquals(TravelDateIssue.MALFORMED, TravelDate.validate("2026-1-1", today))
        assertEquals(TravelDateIssue.MALFORMED, TravelDate.validate("", today))
    }

    @Test
    fun parsesSeatAvailabilityColumns() {
        val fields = trainRow(
            seats = mapOf(
                32 to "3",
                31 to "有",
                30 to "12",
                25 to "--",
                21 to "无",
                26 to "",
            ),
        ).split('|')

        val seats = parseSeats(fields)

        assertEquals(listOf("second", "first", "business"), seats.map { it.name })
        assertEquals(listOf("12", "有", "3"), seats.map { it.count })
    }

    @Test
    fun ignoresIncompleteTrainRows() {
        assertNull(parseTrainOption(listOf("a", "b", "c"), { it }))
        assertNull(parseTrainOption(trainRow(code = "").split('|'), { it }))
        assertNull(parseTrainOption(trainRow(depart = "").split('|'), { it }))
    }

    @Test
    fun detailsSerializationKeepsBookingFields() {
        val details = TrainSearchDetails(
            from = "北京",
            to = "上海",
            fromCode = "BJP",
            toCode = "SHH",
            date = "2026-01-01",
            trains = emptyList(),
            fetchedAt = 1L,
            bookingUrl = "https://kyfw.12306.cn/otn/leftTicket/init?linktypeid=dc",
        )
        val json = Json.encodeToJsonElement(TrainSearchDetails.serializer(), details)
        val decoded = Json.decodeFromJsonElement(TrainSearchDetails.serializer(), json)

        assertEquals(details, decoded)
        assertEquals("BJP", decoded.fromCode)
        assertEquals("SHH", decoded.toCode)
        assertTrue(decoded.bookingUrl.isNotBlank())
    }

    @Test
    fun recentRoutesKeepLatestFirstAndLimit() {
        val current = listOf(RecentRoute("北京", "上海"), RecentRoute("广州", "深圳"))

        assertEquals(
            listOf(RecentRoute("广州", "深圳"), RecentRoute("北京", "上海")),
            mergeRecentRoutes(current, "广州", "深圳"),
        )
        assertEquals(
            listOf(RecentRoute("杭州", "南京"), RecentRoute("北京", "上海"), RecentRoute("广州", "深圳")),
            mergeRecentRoutes(current, "杭州", "南京"),
        )
        assertEquals(3, mergeRecentRoutes(current, "成都", "重庆").size)
        assertEquals(current, mergeRecentRoutes(current, "", ""))
    }

    @Test
    fun invalidPayloadIsRejected() {
        assertNull(TicketPayload.parse("<html>blocked</html>"))
        assertNull(TicketPayload.parse("not-json"))
        val empty = TicketPayload.parse("""{"status":false}""")
        assertFalse(empty?.rows?.isNotEmpty() == true)
        assertNull(empty?.cUrl)
    }
}
