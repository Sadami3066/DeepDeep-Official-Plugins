package io.github.whywood.superpi.plugin.rail

import java.net.URLEncoder
import java.time.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
        seatTypes: String = "",
        trainNo: String = "24000G100",
        fromStationNo: String = "01",
        toStationNo: String = "02",
        date: String = "20261120",
    ): String {
        val fields = MutableList(40) { "" }
        fields[2] = trainNo
        fields[3] = code
        fields[6] = fromCode
        fields[7] = toCode
        fields[8] = depart
        fields[9] = arrive
        fields[10] = duration
        fields[13] = date
        fields[16] = fromStationNo
        fields[17] = toStationNo
        fields[35] = seatTypes
        seats.forEach { (index, value) -> fields[index] = value }
        return fields.joinToString("|")
    }

    private fun queryPayload(rows: List<String>): String {
        val result = rows.joinToString(",") { "\"$it\"" }
        return """{"status":true,"data":{"result":[$result],"map":{"BJP":"北京","SHH":"上海"}}}"""
    }

    private fun seat(
        name: String,
        count: String,
        status: SeatStatus = SeatStatus.AVAILABLE,
        code: String = "",
    ): SeatAvailability = SeatAvailability(name = name, count = count, status = status, code = code)

    private fun allSeats(
        second: String = "有",
        first: String = "3",
        business: String = "",
        standing: String = "无",
    ): List<SeatAvailability> = listOf(
        seat("second", second),
        seat("first", first),
        seat("business", business, SeatStatus.NOT_OFFERED),
        seat("soft-sleeper", "", SeatStatus.NOT_OFFERED),
        seat("hard-sleeper", "", SeatStatus.NOT_OFFERED),
        seat("standing", standing, SeatStatus.SOLD_OUT),
    )

    private fun trainOption(
        code: String = "G1",
        fromStation: String = "北京南",
        toStation: String = "上海虹桥",
        depart: String = "08:00",
        arrive: String = "12:00",
        duration: String = "04:00",
        seats: List<SeatAvailability> = allSeats(),
        fromCode: String = "VNP",
        toCode: String = "AOH",
        trainNo: String = "24000G100",
        seatTypes: String = "9MOO",
        fromStationNo: String = "01",
        toStationNo: String = "02",
        date: String = "20261120",
    ): TrainOption = TrainOption(
        code = code,
        fromStation = fromStation,
        toStation = toStation,
        depart = depart,
        arrive = arrive,
        duration = duration,
        seats = seats,
        fromCode = fromCode,
        toCode = toCode,
        fromStationNo = fromStationNo,
        toStationNo = toStationNo,
        trainNo = trainNo,
        seatTypes = seatTypes,
        date = date,
    )

    private val zh: RailTranslate = { text, _ -> text }

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
        assertEquals("20261120", train.date)
        assertEquals("24000G100", train.trainNo)
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
    fun parsesEverySeatColumnWithDistinctStatuses() {
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
        val byName = seats.associateBy { it.name }

        assertEquals(RailSeatColumns.size, seats.size)
        assertEquals("12", byName["second"]?.count)
        assertEquals("有", byName["first"]?.count)
        assertEquals("3", byName["business"]?.count)
        assertEquals(SeatStatus.AVAILABLE, byName["second"]?.status)
        assertEquals(SeatStatus.SOLD_OUT, byName["deluxe-soft-sleeper"]?.status)
        assertEquals(SeatStatus.NOT_OFFERED, byName["premier"]?.status)
        assertEquals(SeatStatus.NOT_RETURNED, byName["standing"]?.status)
    }

    @Test
    fun seatStatusUsesSeatTypesToTellMissingFromNotOffered() {
        val fields = trainRow(
            seatTypes = "9MODO",
            seats = mapOf(
                30 to "",
                31 to "无",
                32 to "*",
                28 to "",
                25 to "--",
                20 to "11",
            ),
        ).split('|')

        val byName = parseSeats(fields).associateBy { it.name }

        assertEquals(SeatStatus.NOT_RETURNED, byName["second"]?.status)
        assertEquals(SeatStatus.SOLD_OUT, byName["first"]?.status)
        assertEquals(SeatStatus.NOT_ON_SALE, byName["business"]?.status)
        assertEquals(SeatStatus.NOT_OFFERED, byName["hard-sleeper"]?.status)
        assertEquals(SeatStatus.NOT_OFFERED, byName["premier"]?.status)
        assertEquals(SeatStatus.AVAILABLE, byName["premium-first"]?.status)
        assertEquals("11", byName["premium-first"]?.count)
        assertEquals("D", byName["premium-first"]?.code)
    }

    @Test
    fun normalizeSeatValueKeepsRawMarkersApart() {
        assertEquals(SeatStatus.SOLD_OUT, normalizeSeatValue("无").status)
        assertEquals(SeatStatus.NOT_ON_SALE, normalizeSeatValue("*").status)
        assertEquals(SeatStatus.NOT_OFFERED, normalizeSeatValue("--").status)
        assertEquals(SeatStatus.NOT_RETURNED, normalizeSeatValue("", offered = true).status)
        assertEquals(SeatStatus.NOT_OFFERED, normalizeSeatValue("", offered = false).status)
        assertEquals(SeatStatus.AVAILABLE, normalizeSeatValue("有").status)
        assertEquals(SeatStatus.AVAILABLE, normalizeSeatValue("17").status)
    }

    @Test
    fun seatStatusTextUsesTheFourAvailabilityWords() {
        assertEquals("有票", seatStatusText(zh, seat("second", "有")))
        assertEquals("12 张", seatStatusText(zh, seat("second", "12")))
        assertEquals("无票", seatStatusText(zh, seat("second", "无", SeatStatus.SOLD_OUT)))
        assertEquals("未开售", seatStatusText(zh, seat("second", "*", SeatStatus.NOT_ON_SALE)))
        assertEquals("未返回", seatStatusText(zh, seat("second", "", SeatStatus.NOT_RETURNED)))
        assertEquals("无此席别", seatStatusText(zh, seat("second", "--", SeatStatus.NOT_OFFERED)))
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
    fun keepsEveryAlightingVariantWithoutSilentMerging() {
        val rows = listOf(
            trainRow(code = "D7167", fromCode = "MXA", toCode = "XWQ", depart = "07:08", arrive = "09:00", duration = "01:52"),
            trainRow(code = "D7167", fromCode = "MXA", toCode = "GGQ", depart = "07:08", arrive = "09:25", duration = "02:17"),
            trainRow(code = "D7167", fromCode = "MXA", toCode = "ZCA", depart = "07:08", arrive = "08:47", duration = "01:39"),
            trainRow(code = "G6351", fromCode = "MXA", toCode = "IZQ", depart = "06:58", arrive = "10:22", duration = "03:24"),
        )
        val trains = parseTrainRows(rows) { code ->
            when (code) {
                "MXA" -> "梅州西"
                "XWQ" -> "广州新塘"
                "GGQ" -> "广州东"
                "ZCA" -> "增城"
                "IZQ" -> "广州南"
                else -> code
            }
        }

        assertEquals(4, trains.size)
        assertEquals(3, trains.count { it.code == "D7167" })
        assertEquals(
            setOf("广州新塘", "广州东", "增城"),
            trains.filter { it.code == "D7167" }.map { it.toStation }.toSet(),
        )
    }

    @Test
    fun groupsTrainsByCodeAndKeepsEveryStationPair() {
        val trains = listOf(
            trainOption(code = "D7167", toStation = "增城", toCode = "ZCA", depart = "07:58", arrive = "08:47", duration = "00:49", toStationNo = "05"),
            trainOption(code = "D7167", toStation = "广州东", toCode = "GGQ", depart = "07:58", arrive = "09:25", duration = "01:27", toStationNo = "07"),
            trainOption(code = "K677", toStation = "广州白云", toCode = "GBA", depart = "16:57", arrive = "21:07", duration = "04:10", date = "20261119"),
            trainOption(code = "D7167", toStation = "广州新塘", toCode = "XWQ", depart = "07:58", arrive = "09:00", duration = "01:02", toStationNo = "06"),
        )

        val groups = groupTrains(trains)

        assertEquals(listOf("D7167", "K677"), groups.map { it.code })
        assertEquals(3, groups.first().trains.size)
        assertEquals("07:58", groups.first().depart)
        assertEquals("00:49–01:27", groups.first().durationLabel)
        assertEquals(listOf("business", "soft-sleeper", "hard-sleeper"), groups.first().notOfferedSeats.map { it.name })
        assertEquals(36, groupTrains((1..36).map { trainOption(code = "G${100 + it}") }).size)
    }

    @Test
    fun primaryPairUsesRequestedStationOrTheFarthestArrival() {
        val trains = listOf(
            trainOption(code = "D7167", toStation = "增城", toCode = "ZCA", depart = "07:58", arrive = "08:47", duration = "00:49"),
            trainOption(code = "D7167", toStation = "广州东", toCode = "GGQ", depart = "07:58", arrive = "09:25", duration = "01:27"),
            trainOption(code = "D7167", toStation = "广州新塘", toCode = "XWQ", depart = "07:58", arrive = "09:00", duration = "01:02"),
        )
        val group = groupTrains(trains).single()

        assertEquals("广州东", group.primary("广州东").toStation)
        assertEquals("广州东", group.primary("广州").toStation)
        assertEquals("广州东", group.primary("").toStation)
        assertEquals("增城", group.primary("增城").toStation)
    }

    @Test
    fun trainSearchTextGroupsByTrainAndSummarizesMissingSeatsOnce() {
        val details = TrainSearchDetails(
            from = "河源",
            to = "广州东",
            fromCode = "VIQ",
            toCode = "GGQ",
            date = "2026-11-20",
            trains = listOf(
                trainOption(
                    code = "D7167",
                    fromStation = "河源东",
                    toStation = "增城",
                    fromCode = "HEA",
                    toCode = "ZCA",
                    depart = "07:58",
                    arrive = "08:47",
                    duration = "00:49",
                    fromStationNo = "03",
                    toStationNo = "05",
                ),
                trainOption(
                    code = "D7167",
                    fromStation = "河源东",
                    toStation = "广州东",
                    fromCode = "HEA",
                    toCode = "GGQ",
                    depart = "07:58",
                    arrive = "09:25",
                    duration = "01:27",
                    fromStationNo = "03",
                    toStationNo = "07",
                ),
                trainOption(
                    code = "K677",
                    fromStation = "河源",
                    toStation = "广州白云",
                    fromCode = "VIQ",
                    toCode = "GBA",
                    depart = "16:57",
                    arrive = "21:07",
                    duration = "04:10",
                    fromStationNo = "19",
                    toStationNo = "22",
                    date = "20261119",
                ),
            ),
            fetchedAt = 1_700_000_000_000L,
            bookingUrl = "https://kyfw.12306.cn/otn/leftTicket/init?linktypeid=dc",
        )

        val text = trainSearchText(details, zh)

        assertTrue(text.startsWith("河源（VIQ） → 广州东（GGQ） · 2026-11-20"))
        assertTrue(text.contains("2 趟 / 3 个到站组合"))
        assertEquals(1, Regex("\nD7167 07:58 发车 · 历时 00:49–01:27").findAll(text).count())
        assertTrue(text.contains("河源东出发 / 到增城 08:47（00:49）"))
        assertTrue(text.contains("河源东出发 / 广州东 09:25（01:27）"))
        assertTrue(text.contains("河源 / 到广州白云 21:07（04:10）"))
        assertTrue(text.contains("二等座 有票"))
        assertTrue(text.contains("一等座 3 张"))
        assertTrue(text.contains("无座 无票"))
        assertTrue(text.contains("上游日期 2026-11-19"))
        assertEquals(2, Regex("无此席别：").findAll(text).count())
        assertTrue(text.contains("数据时间"))
        assertTrue(text.contains("https://kyfw.12306.cn/otn/leftTicket/init"))
    }

    @Test
    fun upstreamRowDateIsPassedThroughAndLabeled() {
        val train = trainOption(date = "20261119")

        assertEquals("20261119", train.date)
        assertEquals("上游日期 2026-11-19", upstreamDateLabel(train, "2026-11-20", zh))
        assertNull(upstreamDateLabel(trainOption(date = "20261120"), "2026-11-20", zh))
        assertNull(upstreamDateLabel(trainOption(date = ""), "2026-11-20", zh))
        assertEquals("2026-11-19", formatWireDate("20261119"))
        assertNull(formatWireDate("2026-11-19"))
        assertEquals("20261119", parseTrainOption(trainRow(date = "20261119").split('|'), { it })?.date)
    }

    @Test
    fun pickTrainForPricePrefersRequestedStationThenLatestArrival() {
        val rows = listOf(
            trainRow(code = "D7167", fromCode = "MXA", toCode = "ZCA", depart = "07:08", arrive = "08:47", duration = "01:39", toStationNo = "05"),
            trainRow(code = "D7167", fromCode = "MXA", toCode = "GGQ", depart = "07:08", arrive = "09:25", duration = "02:17", toStationNo = "07"),
        )
        val trains = parseTrainRows(rows) { code ->
            when (code) {
                "MXA" -> "梅州西"
                "ZCA" -> "增城"
                "GGQ" -> "广州东"
                else -> code
            }
        }

        assertEquals("广州东", pickTrainForPrice(trains, "d7167", "GGQ")?.toStation)
        assertEquals("广州东", pickTrainForPrice(trains, "D7167", "GZQ")?.toStation)
        assertNull(pickTrainForPrice(trains, "G999", "GGQ"))
    }

    @Test
    fun pickTrainForPriceSelectsRequestedStationNumbersOrTrainNo() {
        val trains = listOf(
            trainOption(code = "D7167", toStation = "增城", toCode = "ZCA", toStationNo = "05", trainNo = "6j000D717001"),
            trainOption(code = "D7167", toStation = "广州东", toCode = "GGQ", toStationNo = "07", trainNo = "6j000D717001"),
            trainOption(code = "D7167", toStation = "广州新塘", toCode = "XWQ", toStationNo = "06", trainNo = "6j000D717001"),
        )

        assertEquals("广州新塘", pickTrainForPrice(trains, "D7167", "GGQ", toStationNo = "06")?.toStation)
        assertEquals("增城", pickTrainForPrice(trains, "D7167", "GGQ", fromStationNo = "01", toStationNo = "05")?.toStation)
        assertEquals("广州东", pickTrainForPrice(trains, "D7167", "GGQ", trainNo = "6j000D717001")?.toStation)
        assertNull(pickTrainForPrice(trains, "D7167", "GGQ", trainNo = "nope"))
        assertNull(pickTrainForPrice(trains, "D7167", "GGQ", toStationNo = "99"))
    }

    @Test
    fun trainPriceCacheServesTheLatestSearchForTenMinutes() {
        val trains = listOf(trainOption(code = "D7167"))
        RailTrainCache.clear()
        try {
            RailTrainCache.remember("VIQ", "GGQ", "2026-11-20", trains, now = 1_000_000L)

            assertEquals(trains, RailTrainCache.lookup("VIQ", "GGQ", "2026-11-20", now = 1_000_000L + RailTrainCache.TTL_MS))
            assertNull(RailTrainCache.lookup("VIQ", "GGQ", "2026-11-20", now = 1_000_001L + RailTrainCache.TTL_MS))
            assertNull(RailTrainCache.lookup("HEA", "GGQ", "2026-11-20", now = 1_000_000L))
            assertNull(RailTrainCache.lookup("VIQ", "GGQ", "2026-11-21", now = 1_000_000L))

            RailTrainCache.remember("VIQ", "GGQ", "2026-11-20", trains, now = 2_000_000L)
            assertEquals(trains, RailTrainCache.lookup("VIQ", "GGQ", "2026-11-20", now = 2_000_000L))
        } finally {
            RailTrainCache.clear()
        }
        assertNull(RailTrainCache.lookup("VIQ", "GGQ", "2026-11-20"))
        assertEquals(10 * 60 * 1000L, RailTrainCache.TTL_MS)
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
                """{"httpstatus":200,"data":{"WZ":"¥601.0","A9":"¥2023.0","9":"20230","M":"¥999.0","O":"¥601.0","D":"¥1063.0"},"status":true}""",
            ),
        )
        val bySeat = prices.associate { it.name to it.priceText }

        assertEquals("¥601.0", bySeat["second"])
        assertEquals("¥999.0", bySeat["first"])
        assertEquals("¥2023.0", bySeat["business"])
        assertEquals("¥1063.0", bySeat["premium-first"])
        assertEquals("¥601.0", bySeat["standing"])
        assertEquals(5, prices.size)
        assertEquals(
            listOf("second", "first", "business", "premium-first", "standing"),
            prices.map { it.name },
        )
    }

    @Test
    fun keepsUnknownFareKeysInsteadOfDroppingThem() {
        val prices = requireNotNull(TicketPricePayload.parse("""{"status":true,"data":{"A5":"¥500.0"}}"""))

        assertEquals("A5", prices.single().name)
        assertEquals("¥500.0", prices.single().priceText)
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
    fun trainPriceTextListsSeatsInFixedOrderWithActualStations() {
        val details = TrainPriceDetails(
            code = "D7167",
            from = "河源",
            to = "广州东",
            fromCode = "HEA",
            toCode = "GGQ",
            date = "2026-11-20",
            prices = listOf(
                SeatPrice("second", "¥104"),
                SeatPrice("first", "¥153"),
                SeatPrice("standing", "¥104"),
            ),
            fetchedAt = 1_700_000_000_000L,
            bookingUrl = "https://kyfw.12306.cn/otn/leftTicket/init",
            fromStation = "河源东",
            toStation = "广州东",
            trainNo = "6j000D717001",
        )

        val text = trainPriceText(details, zh)

        assertTrue(text.contains("D7167 河源东（HEA） → 广州东（GGQ）"))
        assertTrue(text.contains("二等座 ¥104 · 一等座 ¥153 · 无座 ¥104"))
        assertTrue(text.contains("票价来自 12306"))
        assertTrue(text.contains("https://kyfw.12306.cn/otn/leftTicket/init"))

        val json = Json.encodeToJsonElement(TrainPriceDetails.serializer(), details)
        val decoded = Json.decodeFromJsonElement(TrainPriceDetails.serializer(), json)
        assertEquals(details, decoded)
        assertEquals("河源东", decoded.fromPlace)
        assertEquals("广州东", decoded.toPlace)
    }

    @Test
    fun oldPricePayloadWithoutStationsStillDecodes() {
        val decoded = Json.decodeFromString(
            TrainPriceDetails.serializer(),
            """{"code":"G814","from":"北京","to":"上海","fromCode":"BJP","toCode":"SHH","date":"2026-11-16","prices":[{"name":"second","priceText":"¥601"}],"fetchedAt":1}""",
        )

        assertEquals("", decoded.fromStation)
        assertEquals("北京", decoded.fromPlace)
        assertEquals("上海", decoded.toPlace)
    }

    @Test
    fun trainErrorMessageExplainsAmbiguousStationAndUnavailableFare() {
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

        val noLeg = trainErrorMessage(TrainQueryException(TrainErrorKind.PRICE_LEG_NOT_FOUND, "D7167 03→05"), zh)
        assertTrue(noLeg.contains("D7167 03→05"))

        val noFare = trainErrorMessage(TrainPriceUnavailableException(""), zh)
        assertTrue(noFare.contains("票价暂不可查"))
        assertTrue(!noFare.contains("¥"))
    }

    @Test
    fun trainErrorGuidanceNamesTheNextStep() {
        val ambiguous = trainErrorGuidance(
            TrainQueryException(TrainErrorKind.AMBIGUOUS_STATION, "香港", listOf("香港西九龙", "香港红磡")),
            zh,
        )
        assertTrue(ambiguous.contains("候选"))

        val noFare = trainErrorGuidance(TrainPriceUnavailableException(""), zh)
        assertTrue(noFare.contains("12306"))
        assertTrue(!noFare.contains("¥"))
    }

    @Test
    fun rawLogKeepsEachResponseBodyIntact() {
        val log = RailRawLog()
        val queryBody = """{"status":true,"data":{"result":["G1|..."]}}"""
        val priceBody = """{"status":true,"data":{"O":"¥601.0"}}"""

        log.record("GET", "https://kyfw.12306.cn/otn/leftTicket/queryG?x=1", 200, queryBody)
        log.record("GET", "https://kyfw.12306.cn/otn/leftTicket/queryTicketPrice?x=2", 500, priceBody)

        val text = log.text()

        assertTrue(text.contains("queryG?x=1 [HTTP 200]"))
        assertTrue(text.contains("queryTicketPrice?x=2 [HTTP 500]"))
        assertTrue(text.contains(queryBody))
        assertTrue(text.contains(priceBody))
        assertEquals("a\n\nb", joinRawLogs("a", "", "b"))
    }

    @Test
    fun toolDetailsCarryRawGuidanceAndTheFullCardPayload() {
        val details = TrainSearchDetails(
            from = "河源",
            to = "广州东",
            fromCode = "VIQ",
            toCode = "GGQ",
            date = "2026-11-20",
            trains = listOf(
                trainOption(
                    code = "D7167",
                    fromStation = "河源东",
                    toStation = "广州东",
                    fromCode = "HEA",
                    toCode = "GGQ",
                    fromStationNo = "03",
                    toStationNo = "07",
                    trainNo = "6j000D717001",
                    date = "20261119",
                    seats = parseSeats(trainRow(seatTypes = "MOO", seats = mapOf(30 to "有", 31 to "12", 26 to "无")).split('|')),
                ),
            ),
            fetchedAt = 1_700_000_000_000L,
            bookingUrl = "https://kyfw.12306.cn/otn/leftTicket/init?linktypeid=dc",
        )
        val payload = Json.encodeToJsonElement(TrainSearchDetails.serializer(), details) as JsonObject

        val toolDetails = railDetails("raw text", "guidance text", "河源 → 广州东 · 1 趟", payload)
        val card = requireNotNull(toolDetails[RAIL_CARD_DETAILS_KEY]).jsonObject
        val cardPayload = requireNotNull(card["payload"]).jsonObject
        val train = requireNotNull(cardPayload["trains"]).jsonArray.single().jsonObject

        assertEquals("raw text", toolDetails[RAIL_RAW_DETAILS_KEY]?.jsonPrimitive?.content)
        assertEquals("guidance text", toolDetails[RAIL_GUIDANCE_DETAILS_KEY]?.jsonPrimitive?.content)
        assertEquals("河源 → 广州东 · 1 趟", card["title"]?.jsonPrimitive?.content)
        assertEquals("2026-11-20", cardPayload["date"]?.jsonPrimitive?.content)
        assertEquals("https://kyfw.12306.cn/otn/leftTicket/init?linktypeid=dc", cardPayload["bookingUrl"]?.jsonPrimitive?.content)
        assertEquals("20261119", train["date"]?.jsonPrimitive?.content)
        assertEquals("6j000D717001", train["trainNo"]?.jsonPrimitive?.content)
        assertEquals("03", train["fromStationNo"]?.jsonPrimitive?.content)
        assertEquals("07", train["toStationNo"]?.jsonPrimitive?.content)
        assertEquals(RailSeatColumns.size, train["seats"]!!.jsonArray.size)
    }

    @Test
    fun errorDetailsCarryRawGuidanceAndMessageWithoutCard() {
        val details = railErrorDetails(
            raw = "error raw",
            guidance = "retry guidance",
            message = "找不到车站「不存在」",
        )

        assertEquals("error raw", details[RAIL_RAW_DETAILS_KEY]?.jsonPrimitive?.content)
        assertEquals("retry guidance", details[RAIL_GUIDANCE_DETAILS_KEY]?.jsonPrimitive?.content)
        assertEquals("找不到车站「不存在」", details[RAIL_MESSAGE_DETAILS_KEY]?.jsonPrimitive?.content)
        assertFalse(details.containsKey(RAIL_CARD_DETAILS_KEY))
    }

    @Test
    fun oldSeatPayloadWithoutStatusStillDecodesAsAvailable() {
        val seat = Json.decodeFromString(
            SeatAvailability.serializer(),
            """{"name":"second","count":"12"}""",
        )

        assertEquals(SeatStatus.AVAILABLE, seat.status)
        assertEquals("", seat.code)
        assertEquals("12", seat.count)
    }
}
