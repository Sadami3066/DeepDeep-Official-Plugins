package io.github.whywood.deepdeep.plugin.rail

import io.github.whywood.deepdeep.plugin.api.PluginHost
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

internal const val RAIL_DATE_WINDOW_DAYS = 15

internal enum class TrainErrorKind {
    UNKNOWN_STATION,
    AMBIGUOUS_STATION,
    INVALID_DATE,
    INVALID_ROUTE,
    TRAIN_NOT_FOUND,
    SERVICE_UNAVAILABLE,
    PARSE_FAILED,
}

internal class TrainQueryException(
    val kind: TrainErrorKind,
    val detail: String = "",
    val candidates: List<String> = emptyList(),
) : Exception(detail)

internal class TrainPriceUnavailableException(val detail: String = "") : Exception(detail)

internal enum class TravelDateIssue {
    VALID,
    MALFORMED,
    BEFORE_TODAY,
    BEYOND_WINDOW,
}

internal object TravelDate {
    fun parse(raw: String): LocalDate? = try {
        LocalDate.parse(raw.trim())
    } catch (_: DateTimeParseException) {
        null
    }

    fun validate(raw: String, today: LocalDate = LocalDate.now()): TravelDateIssue {
        val parsed = parse(raw) ?: return TravelDateIssue.MALFORMED
        val days = ChronoUnit.DAYS.between(today, parsed)
        return when {
            days < 0 -> TravelDateIssue.BEFORE_TODAY
            days > RAIL_DATE_WINDOW_DAYS -> TravelDateIssue.BEYOND_WINDOW
            else -> TravelDateIssue.VALID
        }
    }

    fun today(clock: () -> LocalDate = LocalDate::now): String = clock().toString()
}

internal data class ParsedTicketPayload(
    val status: Boolean?,
    val cUrl: String?,
    val rows: List<String>,
    val stationNames: Map<String, String>,
)

internal object TicketPayload {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(body: String): ParsedTicketPayload? {
        val text = body.trim().removePrefix("\uFEFF")
        if (!text.startsWith("{")) return null
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        val status = (root["status"] as? JsonPrimitive)?.let { primitive ->
            primitive.booleanOrNull ?: primitive.contentOrNull?.toBooleanStrictOrNull()
        }
        val cUrl = root.string("c_url")
        val data = root["data"] as? JsonObject
        val rows = (data?.get("result") as? JsonArray)
            ?.mapNotNull { element -> (element as? JsonPrimitive)?.contentOrNull }
            .orEmpty()
        val stationNames = (data?.get("map") as? JsonObject)
            ?.mapNotNull { (code, element) ->
                (element as? JsonPrimitive)?.contentOrNull?.let { name -> code to name }
            }
            ?.toMap()
            .orEmpty()
        return ParsedTicketPayload(status = status, cUrl = cUrl, rows = rows, stationNames = stationNames)
    }

    private fun JsonObject.string(name: String): String? =
        (this[name] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
}

internal object TicketPricePayload {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(body: String): List<SeatPrice>? {
        val text = body.trim().removePrefix("\uFEFF")
        if (!text.startsWith("{")) return null
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        val status = (root["status"] as? JsonPrimitive)?.let { primitive ->
            primitive.booleanOrNull ?: primitive.contentOrNull?.toBooleanStrictOrNull()
        }
        if (status == false) return null
        val data = root["data"] as? JsonObject ?: return null
        val hasBusiness = data["A9"] != null
        val prices = data.entries.mapNotNull { (key, element) ->
            val wire = priceSeatWire(key) ?: return@mapNotNull null
            if (key == "9" && hasBusiness) return@mapNotNull null
            val raw = (element as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            val price = parseSeatPrice(raw, key) ?: return@mapNotNull null
            SeatPrice(name = wire, priceText = price)
        }
        return prices.ifEmpty { null }
    }

    private fun parseSeatPrice(raw: String, key: String): String? {
        if (raw.isEmpty()) return null
        if (raw.startsWith("¥") || raw.startsWith("￥")) return raw.replace('￥', '¥')
        val value = raw.toDoubleOrNull() ?: return null
        val amount = if (key == "9") value / 10.0 else value
        return "¥" + formatPriceAmount(amount)
    }

    private fun formatPriceAmount(value: Double): String =
        if (value % 1.0 == 0.0) {
            value.toLong().toString()
        } else {
            String.format(Locale.ROOT, "%.2f", value).trimEnd('0').trimEnd('.')
        }
}

internal fun priceSeatWire(key: String): String? = when (key) {
    "O" -> "second"
    "M" -> "first"
    "A9" -> "business"
    "9" -> "business"
    "P" -> "premier"
    "A4" -> "soft-sleeper"
    "A3" -> "hard-sleeper"
    "A2" -> "soft-seat"
    "A1" -> "hard-seat"
    "A6" -> "deluxe-soft-sleeper"
    "F" -> "sleeper"
    "WZ" -> "standing"
    else -> null
}

internal object RailEndpoints {
    const val INIT_URL = "https://kyfw.12306.cn/otn/leftTicket/init"

    private const val BASE = "https://kyfw.12306.cn/otn/"

    private const val QUERY_PATH = "leftTicket/query"

    private const val QUERY_G_PATH = "leftTicket/queryG"

    private const val PRICE_PATH = "leftTicket/queryTicketPrice"

    fun queryUrl(date: String, fromCode: String, toCode: String): String =
        queryUrl(QUERY_PATH, date, fromCode, toCode)

    fun queryGUrl(date: String, fromCode: String, toCode: String): String =
        queryUrl(QUERY_G_PATH, date, fromCode, toCode)

    private fun queryUrl(path: String, date: String, fromCode: String, toCode: String): String = buildString {
        append(BASE).append(path)
        append("?leftTicketDTO.train_date=").append(encode(date))
        append("&leftTicketDTO.from_station=").append(encode(fromCode))
        append("&leftTicketDTO.to_station=").append(encode(toCode))
        append("&purpose_codes=ADULT")
    }

    fun priceUrl(
        trainNo: String,
        fromStationNo: String,
        toStationNo: String,
        seatTypes: String,
        date: String,
    ): String = buildString {
        append(BASE).append(PRICE_PATH)
        append("?train_no=").append(encode(trainNo))
        append("&from_station_no=").append(encode(fromStationNo))
        append("&to_station_no=").append(encode(toStationNo))
        append("&seat_types=").append(encode(seatTypes))
        append("&train_date=").append(encode(date))
    }

    fun nextUrl(cUrl: String?): String? {
        val trimmed = cUrl?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed
        return BASE + trimmed.trimStart('/')
    }

    fun bookingUrl(fromName: String, fromCode: String, toName: String, toCode: String, date: String): String =
        buildString {
            append(BASE).append("leftTicket/init?linktypeid=dc")
            append("&fs=").append(encode(fromName)).append(',').append(encode(fromCode))
            append("&ts=").append(encode(toName)).append(',').append(encode(toCode))
            append("&date=").append(encode(date))
            append("&flag=N,N,Y")
        }

    fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}

internal data class SeatColumn(val wire: String, val index: Int)

internal val RailSeatColumns: List<SeatColumn> = listOf(
    SeatColumn("second", 30),
    SeatColumn("first", 31),
    SeatColumn("business", 32),
    SeatColumn("premier", 25),
    SeatColumn("soft-sleeper", 23),
    SeatColumn("hard-sleeper", 28),
    SeatColumn("soft-seat", 24),
    SeatColumn("hard-seat", 29),
    SeatColumn("deluxe-soft-sleeper", 21),
    SeatColumn("sleeper", 33),
    SeatColumn("standing", 26),
)

internal fun normalizeSeatValue(raw: String?): String? {
    val value = raw?.trim().orEmpty()
    return when (value) {
        "", "--", "*", "无" -> null
        else -> value
    }
}

internal fun parseSeats(fields: List<String>): List<SeatAvailability> = RailSeatColumns.mapNotNull { column ->
    val value = normalizeSeatValue(fields.getOrNull(column.index)) ?: return@mapNotNull null
    SeatAvailability(column.wire, value)
}

internal fun parseTrainOption(
    fields: List<String>,
    stationName: (String) -> String,
): TrainOption? {
    if (fields.size < 11) return null
    val code = fields.getOrNull(3).orEmpty().trim()
    val depart = fields.getOrNull(8).orEmpty().trim()
    val arrive = fields.getOrNull(9).orEmpty().trim()
    if (code.isEmpty() || depart.isEmpty() || arrive.isEmpty()) return null
    val fromCode = fields.getOrNull(6).orEmpty().trim()
    val toCode = fields.getOrNull(7).orEmpty().trim()
    val seatTypes = fields.getOrNull(35).orEmpty().trim().takeIf { it.isNotEmpty() }
        ?: fields.getOrNull(34).orEmpty().trim()
    return TrainOption(
        code = code,
        fromStation = stationName(fromCode),
        toStation = stationName(toCode),
        depart = depart,
        arrive = arrive,
        duration = fields.getOrNull(10).orEmpty().trim(),
        seats = parseSeats(fields),
        fromCode = fromCode,
        toCode = toCode,
        fromStationNo = fields.getOrNull(16).orEmpty().trim(),
        toStationNo = fields.getOrNull(17).orEmpty().trim(),
        trainNo = fields.getOrNull(2).orEmpty().trim(),
        seatTypes = seatTypes,
    )
}

internal fun dedupeTrainOptions(trains: List<TrainOption>, requestedToCode: String): List<TrainOption> {
    val grouped = LinkedHashMap<String, MutableList<TrainOption>>()
    trains.forEach { train ->
        val key = "${train.code}|${train.fromCode}|${train.depart}"
        grouped.getOrPut(key) { mutableListOf() }.add(train)
    }
    return grouped.values.map { variants ->
        if (variants.size == 1) {
            variants.single()
        } else {
            variants.firstOrNull { it.toCode == requestedToCode }
                ?: variants.maxByOrNull { clockMinutes(it.arrive) ?: -1 }
                ?: variants.first()
        }
    }
}

internal class TrainService(private val host: PluginHost) {
    private val session = HttpSession()

    suspend fun search(fromQuery: String, toQuery: String, dateQuery: String): TrainSearchDetails {
        val index = StationIndex.load(host)
        val fromText = fromQuery.trim()
        val toText = toQuery.trim()
        if (fromText.isEmpty() || toText.isEmpty()) {
            throw TrainQueryException(TrainErrorKind.UNKNOWN_STATION, fromText.ifEmpty { toText })
        }
        val from = resolveStation(index, fromText)
        val to = resolveStation(index, toText)
        if (from.code == to.code) throw TrainQueryException(TrainErrorKind.INVALID_ROUTE, from.name)
        val date = dateQuery.trim().ifEmpty { TravelDate.today() }
        when (TravelDate.validate(date)) {
            TravelDateIssue.VALID -> Unit
            else -> throw TrainQueryException(TrainErrorKind.INVALID_DATE, date)
        }

        cached(from.code, to.code, date)?.let { return it }

        val headers = baseHeaders()
        runCatching { session.get(RailEndpoints.INIT_URL, headers, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS) }
        val cookieHeaders = session.cookieHeader().takeIf { it.isNotBlank() }
            ?.let { mapOf("Cookie" to it) }
            .orEmpty()

        val requestHeaders = headers + cookieHeaders
        var response = session.get(
            RailEndpoints.queryGUrl(date, from.code, to.code),
            requestHeaders,
            CONNECT_TIMEOUT_MS,
            READ_TIMEOUT_MS,
        )
        val primaryPayload = TicketPayload.parse(response.body)
        var payload: ParsedTicketPayload
        if (primaryPayload == null || (primaryPayload.status == false && RailEndpoints.nextUrl(primaryPayload.cUrl) == null)) {
            response = session.get(
                RailEndpoints.queryUrl(date, from.code, to.code),
                requestHeaders,
                CONNECT_TIMEOUT_MS,
                READ_TIMEOUT_MS,
            )
            payload = parseResponse(response)
        } else {
            payload = primaryPayload
        }
        var currentUrl = RailEndpoints.queryGUrl(date, from.code, to.code)
        var followed = 0
        while (payload.rows.isEmpty() && followed < MAX_FOLLOWED_REDIRECTS) {
            val next = RailEndpoints.nextUrl(payload.cUrl) ?: break
            if (next == currentUrl) break
            currentUrl = next
            followed += 1
            response = session.get(next, requestHeaders, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS)
            payload = parseResponse(response)
        }
        if (payload.rows.isEmpty() && response.status !in 200..299) {
            throw TrainQueryException(TrainErrorKind.SERVICE_UNAVAILABLE, "HTTP ${response.status}")
        }
        if (payload.rows.isEmpty() && payload.status == false) {
            throw TrainQueryException(TrainErrorKind.SERVICE_UNAVAILABLE, "12306 rejected the query")
        }

        val stationName: (String) -> String = { code ->
            payload.stationNames[code]?.takeIf { it.isNotBlank() } ?: index.nameOf(code)
        }
        val parsedTrains = payload.rows.mapNotNull { row -> parseTrainOption(row.split('|'), stationName) }
        if (payload.rows.isNotEmpty() && parsedTrains.isEmpty()) {
            throw TrainQueryException(TrainErrorKind.PARSE_FAILED, "unrecognized train rows")
        }
        val trains = dedupeTrainOptions(parsedTrains, to.code)
        val details = TrainSearchDetails(
            from = from.name,
            to = to.name,
            fromCode = from.code,
            toCode = to.code,
            date = date,
            trains = trains,
            fetchedAt = System.currentTimeMillis(),
            bookingUrl = RailEndpoints.bookingUrl(from.name, from.code, to.name, to.code, date),
        )
        cache(from.code, to.code, date, details)
        return details
    }

    suspend fun price(
        fromQuery: String,
        toQuery: String,
        dateQuery: String,
        trainCode: String,
    ): TrainPriceDetails {
        val details = search(fromQuery, toQuery, dateQuery)
        val code = trainCode.trim().uppercase(Locale.ROOT)
        if (code.isEmpty()) throw TrainQueryException(TrainErrorKind.TRAIN_NOT_FOUND, trainCode)
        val train = details.trains.firstOrNull { candidate -> candidate.code.equals(code, ignoreCase = true) }
            ?: throw TrainQueryException(TrainErrorKind.TRAIN_NOT_FOUND, trainCode)
        if (train.trainNo.isBlank() || train.fromStationNo.isBlank() || train.toStationNo.isBlank() || train.seatTypes.isBlank()) {
            throw TrainPriceUnavailableException("车次缺少票价参数")
        }
        val response = session.get(
            RailEndpoints.priceUrl(
                trainNo = train.trainNo,
                fromStationNo = train.fromStationNo,
                toStationNo = train.toStationNo,
                seatTypes = train.seatTypes,
                date = details.date,
            ),
            baseHeaders(),
            CONNECT_TIMEOUT_MS,
            READ_TIMEOUT_MS,
        )
        if (response.status !in 200..299) {
            throw TrainPriceUnavailableException("HTTP ${response.status}")
        }
        val prices = TicketPricePayload.parse(response.body)
            ?: throw TrainPriceUnavailableException("")
        return TrainPriceDetails(
            code = train.code,
            from = details.from,
            to = details.to,
            fromCode = details.fromCode,
            toCode = details.toCode,
            date = details.date,
            prices = prices,
            fetchedAt = System.currentTimeMillis(),
            bookingUrl = details.bookingUrl,
        )
    }

    private fun resolveStation(index: StationIndex, query: String): Station = when (val resolution = index.resolve(query)) {
        is StationResolution.Found -> resolution.station
        is StationResolution.Candidates -> throw TrainQueryException(
            kind = TrainErrorKind.AMBIGUOUS_STATION,
            detail = resolution.query,
            candidates = resolution.stations.take(MAX_CANDIDATES).map { it.name },
        )
        StationResolution.NotFound -> throw TrainQueryException(TrainErrorKind.UNKNOWN_STATION, query)
    }

    private fun parseResponse(response: HttpResponse): ParsedTicketPayload = TicketPayload.parse(response.body)
        ?: if (response.status !in 200..299) {
            throw TrainQueryException(TrainErrorKind.SERVICE_UNAVAILABLE, "HTTP ${response.status}")
        } else {
            throw TrainQueryException(TrainErrorKind.PARSE_FAILED, "unexpected response")
        }

    private class HttpSession {
        private val cookies = LinkedHashMap<String, String>()

        suspend fun get(
            url: String,
            headers: Map<String, String>,
            connectTimeoutMs: Int,
            readTimeoutMs: Int,
        ): HttpResponse = withContext(Dispatchers.IO) {
            var lastError: IOException? = null
            repeat(REQUEST_ATTEMPTS) { attempt ->
                try {
                    val response = request(url, headers, connectTimeoutMs, readTimeoutMs)
                    if (response.status < 500 || attempt == REQUEST_ATTEMPTS - 1) {
                        return@withContext response
                    }
                } catch (error: IOException) {
                    lastError = error
                }
                delay(RETRY_DELAY_MS)
            }
            throw lastError ?: IOException("request failed")
        }

        fun cookieHeader(): String = cookies.entries.joinToString("; ") { (name, value) -> "$name=$value" }

        private fun request(
            url: String,
            headers: Map<String, String>,
            connectTimeoutMs: Int,
            readTimeoutMs: Int,
        ): HttpResponse {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = true
                this.connectTimeout = connectTimeoutMs
                this.readTimeout = readTimeoutMs
                headers.forEach { (key, value) -> setRequestProperty(key, value) }
            }
            try {
                val status = connection.responseCode
                connection.headerFields.entries
                    .filter { it.key.equals("Set-Cookie", ignoreCase = true) }
                    .flatMap { it.value.orEmpty() }
                    .forEach { header ->
                        COOKIE_PATTERN.findAll(header).forEach { match ->
                            cookies[match.groupValues[1]] = match.groupValues[2]
                        }
                    }
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                return HttpResponse(status, body)
            } finally {
                connection.disconnect()
            }
        }
    }

    private data class HttpResponse(val status: Int, val body: String)

    private data class CachedDetails(val details: TrainSearchDetails, val at: Long)

    private fun cached(fromCode: String, toCode: String, date: String): TrainSearchDetails? {
        val key = cacheKey(fromCode, toCode, date)
        val entry = cache[key] ?: return null
        if (System.currentTimeMillis() - entry.at > CACHE_TTL_MS) {
            cache.remove(key)
            return null
        }
        return entry.details
    }

    private fun cache(fromCode: String, toCode: String, date: String, details: TrainSearchDetails) {
        if (cache.size > CACHE_CAPACITY) cache.clear()
        cache[cacheKey(fromCode, toCode, date)] = CachedDetails(details, System.currentTimeMillis())
    }

    private fun cacheKey(fromCode: String, toCode: String, date: String): String = "$fromCode|$toCode|$date"

    private fun baseHeaders(): Map<String, String> = mapOf(
        "User-Agent" to USER_AGENT,
        "Referer" to RailEndpoints.INIT_URL,
        "Accept-Language" to "zh-CN,zh;q=0.9",
        "Accept" to "application/json, text/javascript, */*; q=0.01",
    )

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"

        private const val CONNECT_TIMEOUT_MS = 20_000

        private const val READ_TIMEOUT_MS = 30_000

        private const val REQUEST_ATTEMPTS = 2

        private const val RETRY_DELAY_MS = 400L

        private const val MAX_FOLLOWED_REDIRECTS = 2

        private const val MAX_CANDIDATES = 8

        private const val CACHE_TTL_MS = 60_000L

        private const val CACHE_CAPACITY = 32

        private val COOKIE_PATTERN = Regex("([A-Za-z0-9_]+)=([^;,\\s]*)")

        private val cache = ConcurrentHashMap<String, CachedDetails>()
    }
}
