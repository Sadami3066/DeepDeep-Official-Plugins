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

internal const val RAIL_RAW_DETAILS_KEY = "deepdeep.raw"

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
    val raw: String = "",
    cause: Throwable? = null,
) : Exception(detail, cause)

internal class TrainPriceUnavailableException(
    val detail: String = "",
    val raw: String = "",
) : Exception(detail)

internal data class TrainSearchResult(
    val details: TrainSearchDetails,
    val raw: String,
)

internal data class TrainPriceResult(
    val details: TrainPriceDetails,
    val raw: String,
)

internal class RailRawLog {
    private val entries = mutableListOf<String>()

    fun record(method: String, url: String, status: Int, body: String) {
        entries += "$method $url [HTTP $status]\n$body"
    }

    fun text(): String = entries.joinToString("\n\n")
}

internal fun joinRawLogs(vararg parts: String): String =
    parts.filter { it.isNotBlank() }.joinToString("\n\n")

internal fun withRaw(details: JsonObject, raw: String): JsonObject =
    if (raw.isBlank()) details else JsonObject(details + (RAIL_RAW_DETAILS_KEY to JsonPrimitive(raw)))

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
            if (key == "9" && hasBusiness) return@mapNotNull null
            val wire = priceSeatWire(key) ?: key
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
    "D" -> "premium-first"
    "P" -> "premier"
    "A4" -> "soft-sleeper"
    "A3" -> "hard-sleeper"
    "A2" -> "soft-seat"
    "A1" -> "hard-seat"
    "A6" -> "deluxe-soft-sleeper"
    "F" -> "sleeper"
    "WZ" -> "standing"
    "H" -> "other"
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

internal data class SeatColumn(val wire: String, val index: Int, val codes: Set<String> = emptySet())

internal val RailSeatColumns: List<SeatColumn> = listOf(
    SeatColumn("second", 30, setOf("O")),
    SeatColumn("first", 31, setOf("M")),
    SeatColumn("business", 32, setOf("9")),
    SeatColumn("premium-first", 20, setOf("D")),
    SeatColumn("premier", 25, setOf("P")),
    SeatColumn("soft-sleeper", 23, setOf("I", "4")),
    SeatColumn("hard-sleeper", 28, setOf("3")),
    SeatColumn("soft-seat", 24, setOf("2")),
    SeatColumn("hard-seat", 29, setOf("1")),
    SeatColumn("deluxe-soft-sleeper", 21, setOf("J", "6")),
    SeatColumn("sleeper", 33, setOf("F")),
    SeatColumn("standing", 26, setOf("WZ")),
)

internal data class NormalizedSeat(val status: SeatStatus, val count: String)

internal fun normalizeSeatValue(raw: String?, offered: Boolean = true): NormalizedSeat {
    val value = raw?.trim().orEmpty()
    return when {
        value.isEmpty() -> NormalizedSeat(
            if (offered) SeatStatus.NOT_RETURNED else SeatStatus.NOT_OFFERED,
            "",
        )

        value == "无" || value == "0" -> NormalizedSeat(SeatStatus.SOLD_OUT, value)
        value == "*" -> NormalizedSeat(SeatStatus.NOT_ON_SALE, value)
        value == "--" -> NormalizedSeat(SeatStatus.NOT_OFFERED, value)
        else -> NormalizedSeat(SeatStatus.AVAILABLE, value)
    }
}

internal fun parseSeats(fields: List<String>): List<SeatAvailability> {
    val seatTypes = fields.getOrNull(35).orEmpty().trim()
    return RailSeatColumns.map { column ->
        val offered = seatTypes.isBlank() || column.codes.any { seatTypes.contains(it) }
        val value = normalizeSeatValue(fields.getOrNull(column.index), offered)
        val code = column.codes.firstOrNull { seatTypes.contains(it) }.orEmpty()
        SeatAvailability(column.wire, value.count, value.status, code)
    }
}

internal fun parseTrainRows(rows: List<String>, stationName: (String) -> String): List<TrainOption> =
    rows.mapNotNull { row -> parseTrainOption(row.split('|'), stationName) }

internal fun pickTrainForPrice(trains: List<TrainOption>, code: String, requestedToCode: String): TrainOption? {
    val matches = trains.filter { it.code.equals(code, ignoreCase = true) }
    if (matches.isEmpty()) return null
    return matches.firstOrNull { it.toCode == requestedToCode }
        ?: matches.maxByOrNull { clockMinutes(it.arrive) ?: -1 }
        ?: matches.first()
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

internal class TrainService(private val host: PluginHost) {
    private val session = HttpSession()

    suspend fun search(fromQuery: String, toQuery: String, dateQuery: String): TrainSearchResult {
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

        val raw = RailRawLog()
        val headers = baseHeaders()
        runCatching { fetch(RailEndpoints.INIT_URL, headers, null) }
        val cookieHeaders = session.cookieHeader().takeIf { it.isNotBlank() }
            ?.let { mapOf("Cookie" to it) }
            .orEmpty()

        val requestHeaders = headers + cookieHeaders
        var response = fetch(
            RailEndpoints.queryGUrl(date, from.code, to.code),
            requestHeaders,
            raw,
        )
        val primaryPayload = TicketPayload.parse(response.body)
        var payload: ParsedTicketPayload
        if (primaryPayload == null || (primaryPayload.status == false && RailEndpoints.nextUrl(primaryPayload.cUrl) == null)) {
            response = fetch(
                RailEndpoints.queryUrl(date, from.code, to.code),
                requestHeaders,
                raw,
            )
            payload = parseResponse(response, raw)
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
            response = fetch(next, requestHeaders, raw)
            payload = parseResponse(response, raw)
        }
        if (payload.rows.isEmpty() && response.status !in 200..299) {
            throw TrainQueryException(
                TrainErrorKind.SERVICE_UNAVAILABLE,
                "HTTP ${response.status}",
                raw = raw.text(),
            )
        }
        if (payload.rows.isEmpty() && payload.status == false) {
            throw TrainQueryException(
                TrainErrorKind.SERVICE_UNAVAILABLE,
                "12306 rejected the query",
                raw = raw.text(),
            )
        }

        val stationName: (String) -> String = { code ->
            payload.stationNames[code]?.takeIf { it.isNotBlank() } ?: index.nameOf(code)
        }
        val trains = parseTrainRows(payload.rows, stationName)
        if (payload.rows.isNotEmpty() && trains.isEmpty()) {
            throw TrainQueryException(
                TrainErrorKind.PARSE_FAILED,
                "unrecognized train rows",
                raw = raw.text(),
            )
        }
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
        val result = TrainSearchResult(details, raw.text())
        cache(from.code, to.code, date, result)
        return result
    }

    suspend fun price(
        fromQuery: String,
        toQuery: String,
        dateQuery: String,
        trainCode: String,
    ): TrainPriceResult {
        val search = search(fromQuery, toQuery, dateQuery)
        val details = search.details
        val code = trainCode.trim().uppercase(Locale.ROOT)
        if (code.isEmpty()) {
            throw TrainQueryException(TrainErrorKind.TRAIN_NOT_FOUND, trainCode, raw = search.raw)
        }
        val train = pickTrainForPrice(details.trains, code, details.toCode)
            ?: throw TrainQueryException(TrainErrorKind.TRAIN_NOT_FOUND, trainCode, raw = search.raw)
        if (train.trainNo.isBlank() || train.fromStationNo.isBlank() || train.toStationNo.isBlank() || train.seatTypes.isBlank()) {
            throw TrainPriceUnavailableException("车次缺少票价参数", search.raw)
        }
        val raw = RailRawLog()
        val response = try {
            fetch(
                RailEndpoints.priceUrl(
                    trainNo = train.trainNo,
                    fromStationNo = train.fromStationNo,
                    toStationNo = train.toStationNo,
                    seatTypes = train.seatTypes,
                    date = details.date,
                ),
                baseHeaders(),
                raw,
            )
        } catch (error: TrainQueryException) {
            throw TrainPriceUnavailableException(
                error.detail,
                joinRawLogs(search.raw, raw.text()),
            )
        }
        val combinedRaw = joinRawLogs(search.raw, raw.text())
        if (response.status !in 200..299) {
            throw TrainPriceUnavailableException("HTTP ${response.status}", combinedRaw)
        }
        val prices = TicketPricePayload.parse(response.body)
            ?: throw TrainPriceUnavailableException("", combinedRaw)
        return TrainPriceResult(
            details = TrainPriceDetails(
                code = train.code,
                from = details.from,
                to = details.to,
                fromCode = details.fromCode,
                toCode = details.toCode,
                date = details.date,
                prices = prices,
                fetchedAt = System.currentTimeMillis(),
                bookingUrl = details.bookingUrl,
            ),
            raw = combinedRaw,
        )
    }

    private fun resolveStation(index: StationIndex, query: String): Station = when (val resolution = index.resolve(query)) {
        is StationResolution.Found -> resolution.station
        is StationResolution.Candidates -> throw TrainQueryException(
            kind = TrainErrorKind.AMBIGUOUS_STATION,
            detail = resolution.query,
            candidates = resolution.stations.map { it.name },
        )
        StationResolution.NotFound -> throw TrainQueryException(TrainErrorKind.UNKNOWN_STATION, query)
    }

    private fun parseResponse(response: HttpResponse, raw: RailRawLog): ParsedTicketPayload = TicketPayload.parse(response.body)
        ?: if (response.status !in 200..299) {
            throw TrainQueryException(
                TrainErrorKind.SERVICE_UNAVAILABLE,
                "HTTP ${response.status}",
                raw = raw.text(),
            )
        } else {
            throw TrainQueryException(
                TrainErrorKind.PARSE_FAILED,
                "unexpected response",
                raw = raw.text(),
            )
        }

    private suspend fun fetch(
        url: String,
        headers: Map<String, String>,
        raw: RailRawLog?,
    ): HttpResponse = try {
        session.get(url, headers, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS, raw)
    } catch (error: IOException) {
        throw TrainQueryException(
            kind = TrainErrorKind.SERVICE_UNAVAILABLE,
            detail = error.message.orEmpty(),
            raw = raw?.text().orEmpty(),
            cause = error,
        )
    }

    private class HttpSession {
        private val cookies = LinkedHashMap<String, String>()

        suspend fun get(
            url: String,
            headers: Map<String, String>,
            connectTimeoutMs: Int,
            readTimeoutMs: Int,
            raw: RailRawLog? = null,
        ): HttpResponse = withContext(Dispatchers.IO) {
            var lastError: IOException? = null
            repeat(REQUEST_ATTEMPTS) { attempt ->
                try {
                    val response = request(url, headers, connectTimeoutMs, readTimeoutMs)
                    raw?.record("GET", url, response.status, response.body)
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

    private data class CachedDetails(val result: TrainSearchResult, val at: Long)

    private fun cached(fromCode: String, toCode: String, date: String): TrainSearchResult? {
        val key = cacheKey(fromCode, toCode, date)
        val entry = cache[key] ?: return null
        if (System.currentTimeMillis() - entry.at > CACHE_TTL_MS) {
            cache.remove(key)
            return null
        }
        return entry.result
    }

    private fun cache(fromCode: String, toCode: String, date: String, result: TrainSearchResult) {
        if (cache.size > CACHE_CAPACITY) cache.clear()
        cache[cacheKey(fromCode, toCode, date)] = CachedDetails(result, System.currentTimeMillis())
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


        private const val CACHE_TTL_MS = 60_000L

        private const val CACHE_CAPACITY = 32

        private val COOKIE_PATTERN = Regex("([A-Za-z0-9_]+)=([^;,\\s]*)")

        private val cache = ConcurrentHashMap<String, CachedDetails>()
    }
}
