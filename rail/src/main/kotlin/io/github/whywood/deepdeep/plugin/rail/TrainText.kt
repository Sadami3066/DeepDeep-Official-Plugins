package io.github.whywood.deepdeep.plugin.rail

import io.github.whywood.deepdeep.plugin.api.PluginHost
import io.github.whywood.deepdeep.plugin.api.localizedText
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal typealias RailTranslate = (String, String) -> String

internal fun PluginHost.t(zh: String, en: String): String =
    text(localizedText(default = zh, zh = zh, en = en))

internal fun trainErrorMessage(host: PluginHost, error: Throwable): String {
    val translate: RailTranslate = { zh, en -> host.t(zh, en) }
    return trainErrorMessage(error, translate)
}

internal fun trainErrorMessage(error: Throwable, translate: RailTranslate): String = when (error) {
    is TrainPriceUnavailableException -> translate(
        "票价暂不可查：12306 没有返回这趟车的票价${error.detail.takeIf { it.isNotBlank() }?.let { "（$it）" }.orEmpty()}，请到 12306 查看。",
        "Ticket prices aren't available: 12306 returned no fare${error.detail.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()}. Check on 12306.",
    )

    is TrainQueryException -> when (error.kind) {
        TrainErrorKind.UNKNOWN_STATION ->
            translate(
                "找不到车站「${error.detail}」，可以写城市名（如 北京）或完整车站名（如 北京南）。",
                "Unknown station \"${error.detail}\". Use a city (e.g. 北京) or a full station name (e.g. 北京南).",
            )

        TrainErrorKind.AMBIGUOUS_STATION ->
            translate(
                "「${error.detail}」对应多个车站：${error.candidates.joinToString("、")}。请写明要去的车站。",
                "\"${error.detail}\" matches several stations: ${error.candidates.joinToString(", ")}. Name the station you want.",
            )

        TrainErrorKind.INVALID_DATE ->
            translate(
                "日期要在今天起 $RAIL_DATE_WINDOW_DAYS 天内，例如 ${TravelDate.today()}。",
                "Pick a date within $RAIL_DATE_WINDOW_DAYS days from today, for example ${TravelDate.today()}.",
            )

        TrainErrorKind.INVALID_ROUTE ->
            translate("出发站和到达站不能相同。", "Departure and arrival must be different stations.")

        TrainErrorKind.TRAIN_NOT_FOUND ->
            translate(
                "这天的车次里没有「${error.detail}」，请确认车次号或换一天再查。",
                "No \"${error.detail}\" among the trains for that date. Check the train number or another date.",
            )

        TrainErrorKind.SERVICE_UNAVAILABLE -> {
            val detail = error.detail.takeIf { it.isNotBlank() }?.let { "（$it）" }.orEmpty()
            translate(
                "12306 暂时没有响应$detail，请稍后重试。",
                "12306 isn't responding right now$detail. Try again later.",
            )
        }

        TrainErrorKind.PARSE_FAILED ->
            translate("车次数据解析失败，请稍后重试。", "Couldn't read the train data. Try again later.")
    }

    else -> translate("车次查询失败，请稍后重试。", "Train lookup failed. Try again later.")
}

internal fun trainSummary(host: PluginHost, details: TrainSearchDetails): String {
    val translate: RailTranslate = { zh, en -> host.t(zh, en) }
    return trainSummary(details, translate)
}

internal fun trainSummary(details: TrainSearchDetails, translate: RailTranslate): String = buildString {
    append(stationPair(details.from, details.fromCode, translate))
    append(" → ")
    append(stationPair(details.to, details.toCode, translate))
    append(" · ").append(details.date)
    if (details.trains.isEmpty()) {
        append("\n").append(translate("这一天没有查到车次。", "No trains found for this date."))
    } else {
        append(" · ").append(translate("共 ${details.trains.size} 趟", "${details.trains.size} trains"))
        details.trains.forEach { train ->
            append("\n").append(train.code).append(' ')
            append(train.depart).append("→").append(arrivalLabel(train.depart, train.arrive, train.duration))
            append(" ").append(train.duration)
            append(" ").append(train.fromStation)
            append("→").append(train.toStation)
            val seats = trainSeatLine(train.seats, translate)
            if (seats.isNotBlank()) append(" · ").append(seats)
        }
        append("\n").append(
            translate(
                "数据时间 ${dataClock(details.fetchedAt)}，余票以 12306 实时数据为准。",
                "Data fetched at ${dataClock(details.fetchedAt)}; availability follows live 12306 data.",
            ),
        )
    }
    if (details.bookingUrl.isNotBlank()) {
        append("\n").append(translate("去 12306 登录并预订：", "Sign in and book on 12306: ")).append(details.bookingUrl)
    }
}

internal fun trainSeatLine(seats: List<SeatAvailability>, translate: RailTranslate): String {
    if (seats.isEmpty()) return ""
    val offered = seats.filter { it.status != SeatStatus.NOT_OFFERED }
    val missing = seats.filter { it.status == SeatStatus.NOT_OFFERED }
    val listed = offered.joinToString(" · ") { seat ->
        "${seatLabel(translate, seat)} ${seatStatusText(translate, seat)}"
    }
    val absent = if (missing.isEmpty()) {
        ""
    } else {
        translate(
            "无此席别：${missing.joinToString("、") { seatLabel(translate, it) }}",
            "Not offered: ${missing.joinToString(", ") { seatLabel(translate, it) }}",
        )
    }
    return listOf(listed, absent).filter { it.isNotBlank() }.joinToString(" · ")
}

internal fun seatStatusText(translate: RailTranslate, seat: SeatAvailability): String = when (seat.status) {
    SeatStatus.AVAILABLE -> seat.count.ifBlank { translate("有", "available") }
    SeatStatus.SOLD_OUT -> translate("无票", "sold out")
    SeatStatus.NOT_ON_SALE -> translate("未开售", "not on sale")
    SeatStatus.NOT_RETURNED -> translate("未返回", "not returned")
    SeatStatus.NOT_OFFERED -> translate("无此席别", "not offered")
}

internal fun trainPriceText(host: PluginHost, details: TrainPriceDetails): String {
    val translate: RailTranslate = { zh, en -> host.t(zh, en) }
    return trainPriceText(details, translate)
}

internal fun trainPriceText(details: TrainPriceDetails, translate: RailTranslate): String = buildString {
    append(details.code).append(' ')
    append(stationPair(details.from, details.fromCode, translate))
    append(" → ")
    append(stationPair(details.to, details.toCode, translate))
    append(" · ").append(details.date)
    append("\n").append(
        details.prices.joinToString(" · ") { "${seatLabel(translate, it.name)} ${it.priceText}" },
    )
    append("\n").append(
        translate(
            "票价来自 12306，实际金额以 12306 下单页为准；数据时间 ${dataClock(details.fetchedAt)}。",
            "Fares come from 12306; the booking page is final. Data fetched at ${dataClock(details.fetchedAt)}.",
        ),
    )
    if (details.bookingUrl.isNotBlank()) {
        append("\n").append(translate("去 12306 预订：", "Book on 12306: ")).append(details.bookingUrl)
    }
}

internal fun seatLabel(translate: RailTranslate, seat: SeatAvailability): String = when {
    seat.name == "soft-sleeper" && seat.code == "I" -> translate("一等卧", "First-class sleeper")
    seat.name == "deluxe-soft-sleeper" && seat.code == "J" -> translate("二等卧", "Second-class sleeper")
    else -> seatLabel(translate, seat.name)
}

internal fun seatLabel(translate: RailTranslate, wire: String): String = when (wire) {
    "second" -> translate("二等座", "Second class")
    "first" -> translate("一等座", "First class")
    "business" -> translate("商务座", "Business class")
    "premium-first" -> translate("优选一等座", "Preferred first class")
    "premier" -> translate("特等座", "Premier class")
    "soft-sleeper" -> translate("软卧", "Soft sleeper")
    "hard-sleeper" -> translate("硬卧", "Hard sleeper")
    "soft-seat" -> translate("软座", "Soft seat")
    "hard-seat" -> translate("硬座", "Hard seat")
    "deluxe-soft-sleeper" -> translate("高级软卧", "Deluxe soft sleeper")
    "sleeper" -> translate("动卧", "Sleeper")
    "standing" -> translate("无座", "Standing")
    "other" -> translate("其他", "Other")
    else -> wire
}

internal fun arrivalLabel(depart: String, arrive: String, duration: String): String {
    val departMinutes = clockMinutes(depart)
    val durationMinutes = clockMinutes(duration)
    val nextDay = if (departMinutes != null && durationMinutes != null) {
        (departMinutes + durationMinutes) / MINUTES_PER_DAY
    } else {
        val arriveMinutes = clockMinutes(arrive) ?: return arrive
        val departValue = departMinutes ?: return arrive
        if (arriveMinutes < departValue) 1 else 0
    }
    return if (nextDay > 0) {
        "次日$arrive"
    } else {
        arrive
    }
}

internal fun clockMinutes(value: String): Int? {
    val parts = value.trim().split(':')
    if (parts.size < 2) return null
    val hours = parts[0].toIntOrNull() ?: return null
    val minutes = parts[1].toIntOrNull() ?: return null
    if (hours !in 0..23 || minutes !in 0..59) return null
    return hours * 60 + minutes
}

private fun stationPair(name: String, code: String, translate: RailTranslate): String =
    if (code.isBlank()) name else translate("$name（$code）", "$name ($code)")

internal fun dataClock(fetchedAt: Long): String =
    TIME_FORMAT.format(Instant.ofEpochMilli(fetchedAt).atZone(ZoneId.systemDefault()))

private const val MINUTES_PER_DAY = 24 * 60

private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm")
