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

    @Test
    fun dedupesSameTrainAlightingVariantsForCityRoute() {
        val rows = listOf(
            trainRow(code = "D7167", fromCode = "MXA", toCode = "XWQ", depart = "07:08", arrive = "09:00", duration = "01:52"),
            trainRow(code = "D7167", fromCode = "MXA", toCode = "GGQ", depart = "07:08", arrive = "09:25", duration = "02:17"),
            trainRow(code = "D7167", fromCode = "MXA", toCode = "ZCA", depart = "07:08", arrive = "08:47", duration = "01:39"),
            trainRow(code = "G6351", fromCode = "MXA", toCode = "IZQ", depart = "06:58", arrive = "10:22", duration = "03:24"),
        ).mapNotNull { row ->
            parseTrainOption(row.split('|')) { code ->
                when (code) {
                    "MXA" -> "梅州西"
                    "XWQ" -> "广州新塘"
                    "GGQ" -> "广州东"
                    "ZCA" -> "增城"
                    "IZQ" -> "广州南"
                    else -> code
                }
            }
        }

        val deduped = dedupeTrainOptions(rows, requestedToCode = "GGQ")

        assertEquals(2, deduped.size)
        val d7167 = deduped.single { it.code == "D7167" }
        assertEquals("广州东", d7167.toStation)
        assertEquals("09:25", d7167.arrive)
        assertEquals("02:17", d7167.duration)
    }

    @Test
    fun dedupeKeepsDifferentBoardingStations() {
        val rows = listOf(
            trainRow(code = "G756", fromCode = "XHH", toCode = "VNP", depart = "08:42", arrive = "14:53", duration = "06:11"),
            trainRow(code = "G756", fromCode = "HGH", toCode = "VNP", depart = "08:57", arrive = "14:53", duration = "05:56"),
        ).mapNotNull { row ->
            parseTrainOption(row.split('|')) { code ->
                when (code) {
                    "XHH" -> "杭州南"
                    "HGH" -> "杭州东"
                    "VNP" -> "北京南"
                    else -> code
                }
            }
        }

        val deduped = dedupeTrainOptions(rows, requestedToCode = "VNP")

        assertEquals(2, deduped.size)
        assertEquals(listOf("08:42", "08:57"), deduped.map { it.depart })
    }

    @Test
    fun dedupeFallsBackToLatestArrivalWithoutExactStation() {
        val rows = listOf(
            trainRow(code = "D7167", fromCode = "MXA", toCode = "ZCA", depart = "07:08", arrive = "08:47", duration = "01:39"),
            trainRow(code = "D7167", fromCode = "MXA", toCode = "XWQ", depart = "07:08", arrive = "09:00", duration = "01:52"),
        ).mapNotNull { row ->
            parseTrainOption(row.split('|')) { code ->
                when (code) {
                    "MXA" -> "梅州西"
                    "ZCA" -> "增城"
                    "XWQ" -> "广州新塘"
                    else -> code
                }
            }
        }

        val deduped = dedupeTrainOptions(rows, requestedToCode = "GZQ")

        assertEquals(1, deduped.size)
        assertEquals("广州新塘", deduped.single().toStation)
    }

    @Test
    fun marksOvernightArrivals() {
        assertEquals("13:07", arrivalLabel("06:50", "13:07", "06:17"))
        assertEquals("次日08:10", arrivalLabel("08:22", "08:10", "23:48"))
        assertEquals("次日07:09", arrivalLabel("17:10", "07:09", "13:59"))
        assertEquals("次日09:52", arrivalLabel("17:40", "09:52", "16:12"))
    }

    @Test
    fun priceEndpointUrlCarriesFareQuery() {
        assertEquals(
            "https://kyfw.12306.cn/otn/leftTicket/queryTicketPrice" +
                "?train_no=560000G81400" +
                "&from_station_no=01" +
                "&to_station_no=14" +
                "&seat_types=9MOO" +
                "&train_date=2026-11-16",
            RailEndpoints.priceUrl("560000G81400", "01", "14", "9MOO", "2026-11-16"),
        )
    }

    @Test
    fun parsesFarePayloadAndDropsDuplicateBusinessKey() {
        val prices = requireNotNull(
            TicketPricePayload.parse(
                """{"httpstatus":200,"data":{"WZ":"¥601.0","A9":"¥2023.0","9":"20230","M":"¥999.0","O":"¥601.0"},"status":true}""",
            ),
        )
        val bySeat = prices.associate { it.name to it.priceText }

        assertEquals("¥601.0", bySeat["second"])
        assertEquals("¥999.0", bySeat["first"])
        assertEquals("¥2023.0", bySeat["business"])
        assertEquals("¥601.0", bySeat["standing"])
        assertEquals(4, prices.size)
    }

    @Test
    fun parsesNumericFareFallbacks() {
        val prices = requireNotNull(TicketPricePayload.parse("""{"status":true,"data":{"O":601,"9":"20230"}}"""))
        val bySeat = prices.associate { it.name to it.priceText }

        assertEquals("¥601", bySeat["second"])
        assertEquals("¥2023", bySeat["business"])
    }

    @Test
    fun farePayloadWithoutDataIsUnavailable() {
        assertNull(TicketPricePayload.parse("""{"status":false}"""))
        assertNull(TicketPricePayload.parse("<html>blocked</html>"))
        assertNull(TicketPricePayload.parse("""{"status":true,"data":{}}"""))
    }

    @Test
    fun trainSummaryListsTenTrainsWithTimesSeatsAndDataTime() {
        val trains = (1..12).map { index ->
            TrainOption(
                code = "G${100 + index}",
                fromStation = "北京南",
                toStation = "上海虹桥",
                depart = "0${index % 10}:00",
                arrive = "1${index % 10}:00",
                duration = "0${index % 5}:00",
                seats = listOf(SeatAvailability("second", "有")),
                fromCode = "VNP",
                toCode = "AOH",
            )
        }
        val details = TrainSearchDetails(
            from = "北京",
            to = "上海",
            fromCode = "BJP",
            toCode = "SHH",
            date = "2026-11-16",
            trains = trains,
            fetchedAt = 1_700_000_000_000L,
            bookingUrl = "https://kyfw.12306.cn/otn/leftTicket/init",
        )
        val zh: RailTranslate = { text, _ -> text }

        val text = trainSummary(details, zh)

        assertTrue(text.contains("北京（BJP） → 上海（SHH）"))
        assertTrue(text.contains("G101"))
        assertTrue(text.contains("G110"))
        assertTrue(!text.contains("G111"))
        assertTrue(text.contains("共 12 趟"))
        assertTrue(text.contains("二等座 有"))
        assertTrue(text.contains("数据时间"))
        assertTrue(text.contains("https://kyfw.12306.cn/otn/leftTicket/init"))
        assertTrue(!text.contains("卡片"))
    }

    @Test
    fun trainPriceTextListsSeatsAndSource() {
        val details = TrainPriceDetails(
            code = "G814",
            from = "北京",
            to = "上海",
            fromCode = "BJP",
            toCode = "SHH",
            date = "2026-11-16",
            prices = listOf(
                SeatPrice("second", "¥601"),
                SeatPrice("first", "¥999"),
                SeatPrice("business", "¥2023"),
            ),
            fetchedAt = 1_700_000_000_000L,
            bookingUrl = "https://kyfw.12306.cn/otn/leftTicket/init",
        )
        val zh: RailTranslate = { text, _ -> text }

        val text = trainPriceText(details, zh)

        assertTrue(text.contains("G814"))
        assertTrue(text.contains("北京（BJP） → 上海（SHH）"))
        assertTrue(text.contains("二等座 ¥601"))
        assertTrue(text.contains("一等座 ¥999"))
        assertTrue(text.contains("商务座 ¥2023"))
        assertTrue(text.contains("票价来自 12306"))
        assertTrue(text.contains("https://kyfw.12306.cn/otn/leftTicket/init"))
    }

    @Test
    fun trainErrorMessageExplainsAmbiguousStationAndUnavailableFare() {
        val zh: RailTranslate = { text, _ -> text }

        val ambiguous = trainErrorMessage(
            TrainQueryException(TrainErrorKind.AMBIGUOUS_STATION, "香港", listOf("香港西九龙", "香港红磡")),
            zh,
        )
        assertTrue(ambiguous.contains("香港西九龙"))
        assertTrue(ambiguous.contains("香港红磡"))
        assertTrue(ambiguous.contains("请写明要去的车站"))

        val unknown = trainErrorMessage(TrainQueryException(TrainErrorKind.UNKNOWN_STATION, "不存在"), zh)
        assertTrue(unknown.contains("找不到车站"))

        val noTrain = trainErrorMessage(TrainQueryException(TrainErrorKind.TRAIN_NOT_FOUND, "D9999"), zh)
        assertTrue(noTrain.contains("D9999"))

        val noFare = trainErrorMessage(TrainPriceUnavailableException(""), zh)
        assertTrue(noFare.contains("票价暂不可查"))
        assertTrue(!noFare.contains("¥"))
    }
}
