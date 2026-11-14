package io.github.whywood.deepdeep.plugin.rail

import io.github.whywood.deepdeep.plugin.api.PluginHost
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class TrainException(message: String) : Exception(message)

internal class TrainService(private val host: PluginHost) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"

    suspend fun search(fromQuery: String, toQuery: String, date: String): TrainSearchDetails {
        val index = StationIndex.load(host)
        val from = index.resolve(fromQuery) ?: throw TrainException(host.t("找不到出发站：$fromQuery", "Unknown departure station: $fromQuery"))
        val to = index.resolve(toQuery) ?: throw TrainException(host.t("找不到到达站：$toQuery", "Unknown arrival station: $toQuery"))
        if (!DATE.matches(date)) throw TrainException(host.t("日期格式应为 YYYY-MM-DD", "Date must be YYYY-MM-DD"))
        val headers = mapOf(
            "User-Agent" to userAgent,
            "Referer" to INIT_URL,
            "Accept-Language" to "zh-CN,zh;q=0.9",
        )
        val session = HttpSession()
        session.get(INIT_URL, headers, 20_000)
        val cookie = session.cookieHeader()
        val query = buildString {
            append("$QUERY_URL?leftTicketDTO.train_date=").append(URLEncoder.encode(date, "UTF-8"))
            append("&leftTicketDTO.from_station=").append(URLEncoder.encode(from.code, "UTF-8"))
            append("&leftTicketDTO.to_station=").append(URLEncoder.encode(to.code, "UTF-8"))
            append("&purpose_codes=ADULT")
        }
        val response = session.get(
            query,
            headers + if (cookie.isBlank()) emptyMap() else mapOf("Cookie" to cookie),
            30_000,
        )
        val body = response.second.trim().removePrefix("\uFEFF")
        if (response.first !in 200..299 || body.startsWith("<")) {
            throw TrainException(host.t("12306 暂时没有返回车次，请稍后重试。", "12306 didn't return train data. Try again later."))
        }
        val result = runCatching { json.decodeFromString(TicketResponse.serializer(), body) }
            .getOrElse { throw TrainException(host.t("车次数据解析失败", "Could not parse train data")) }
        val trains = result.data?.result.orEmpty().mapNotNull { row ->
            val fields = row.split('|')
            if (fields.size < 11) return@mapNotNull null
            val code = fields.getOrNull(3).orEmpty()
            val depart = fields.getOrNull(8).orEmpty()
            val arrive = fields.getOrNull(9).orEmpty()
            if (code.isBlank() || depart.isBlank() || arrive.isBlank()) return@mapNotNull null
            TrainOption(
                code = code,
                fromStation = index.nameOf(fields.getOrNull(6).orEmpty()),
                toStation = index.nameOf(fields.getOrNull(7).orEmpty()),
                depart = depart,
                arrive = arrive,
                duration = fields.getOrNull(10).orEmpty(),
                seats = seatAvailability(fields),
            )
        }
        return TrainSearchDetails(
            from = from.name,
            to = to.name,
            date = date,
            trains = trains,
            fetchedAt = System.currentTimeMillis(),
        )
    }

    private fun seatAvailability(fields: List<String>): List<SeatAvailability> {
        val result = mutableListOf<SeatAvailability>()
        SEATS.forEach { (name, index) ->
            val value = fields.getOrNull(index)?.trim().orEmpty()
            if (value.isNotEmpty() && value != "--" && value != "*") {
                result += SeatAvailability(name, value)
            }
        }
        return result
    }

    private class HttpSession {
        private val cookies = LinkedHashMap<String, String>()

        suspend fun get(url: String, headers: Map<String, String>, timeoutMs: Int): Pair<Int, String> = withContext(Dispatchers.IO) {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = true
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                headers.forEach { (key, value) -> setRequestProperty(key, value) }
            }
            try {
                val status = connection.responseCode
                connection.headerFields.entries
                    .filter { it.key.equals("Set-Cookie", ignoreCase = true) }
                    .flatMap { it.value.orEmpty() }
                    .forEach { header ->
                        Regex("([A-Za-z0-9_]+)=([^;,\\s]*)").findAll(header).forEach { match ->
                            cookies[match.groupValues[1]] = match.groupValues[2]
                        }
                    }
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                status to text
            } finally {
                connection.disconnect()
            }
        }

        fun cookieHeader(): String = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    companion object {
        private const val INIT_URL = "https://kyfw.12306.cn/otn/leftTicket/init"
        private const val QUERY_URL = "https://kyfw.12306.cn/otn/leftTicket/query"
        private val DATE = Regex("\\d{4}-\\d{2}-\\d{2}")
        private val SEATS = listOf(
            "高级软卧" to 21,
            "软卧" to 23,
            "软座" to 24,
            "特等座" to 25,
            "无座" to 26,
            "硬卧" to 28,
            "硬座" to 29,
            "二等座" to 30,
            "一等座" to 31,
            "商务座" to 32,
            "动卧" to 33,
        )
    }
}
