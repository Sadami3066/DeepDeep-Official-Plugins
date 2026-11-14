package io.github.whywood.deepdeep.plugin.rail

import io.github.whywood.deepdeep.plugin.api.PluginHost
import io.github.whywood.deepdeep.plugin.api.localizedText

internal fun PluginHost.t(zh: String, en: String): String =
    text(localizedText(default = zh, zh = zh, en = en))

internal fun trainErrorMessage(host: PluginHost, error: Throwable): String = when (error) {
    is TrainQueryException -> when (error.kind) {
        TrainErrorKind.UNKNOWN_STATION ->
            host.t("找不到车次涉及的车站：${error.detail}。换个站名试试。", "Unknown station: ${error.detail}. Try another name.")

        TrainErrorKind.INVALID_DATE ->
            host.t(
                "日期要在今天起 $RAIL_DATE_WINDOW_DAYS 天内，例如 ${TravelDate.today()}。",
                "Pick a date within $RAIL_DATE_WINDOW_DAYS days from today, for example ${TravelDate.today()}.",
            )

        TrainErrorKind.INVALID_ROUTE ->
            host.t("出发站和到达站不能相同。", "Departure and arrival must be different stations.")

        TrainErrorKind.SERVICE_UNAVAILABLE ->
            host.t("12306 暂时没有响应，请稍后重试。", "12306 isn't responding right now. Try again later.")

        TrainErrorKind.PARSE_FAILED ->
            host.t("车次数据解析失败，请稍后重试。", "Couldn't read the train data. Try again later.")
    }

    else -> host.t("车次查询失败，请稍后重试。", "Train lookup failed. Try again later.")
}

internal fun trainSummary(host: PluginHost, details: TrainSearchDetails): String = buildString {
    val header = host.t(
        "${details.from} → ${details.to} · ${details.date}",
        "${details.from} → ${details.to} · ${details.date}",
    )
    append(header)
    if (details.trains.isEmpty()) {
        append("\n")
        append(host.t("这一天没有查到车次。", "No trains found for this date."))
    } else {
        details.trains.take(6).forEach { train ->
            append("\n")
            append(train.code).append(' ')
            append(train.depart).append("→").append(train.arrive).append(' ')
            append(train.duration)
            if (train.seats.isNotEmpty()) {
                append(" · ")
                append(train.seats.take(4).joinToString(" ") { "${seatLabel(host, it.name)} ${it.count}" })
            }
        }
        append("\n")
        append(host.t("余票以 12306 实时数据为准。", "Availability follows live 12306 data."))
    }
    if (details.bookingUrl.isNotBlank()) {
        append("\n")
        append(host.t("去 12306 登录并预订：", "Sign in and book on 12306: "))
        append(details.bookingUrl)
    }
}
