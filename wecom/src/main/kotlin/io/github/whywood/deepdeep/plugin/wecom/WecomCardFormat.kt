package io.github.whywood.deepdeep.plugin.wecom

import io.github.whywood.deepdeep.plugin.cli.card.CliCardItem
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.Locale

private val WECOM_DATE_TIME = Regex("""(\d{4})-(\d{2})-(\d{2})[ T](\d{2}):(\d{2})(?::\d{2})?""")
private val WECOM_DATE = Regex("""\d{4}-\d{2}-\d{2}""")
private val WECOM_RANGE = Regex("""(\d{1,2}):(\d{2})\s*(?:-|–|—|~|至|to)\s*(\d{1,2}):(\d{2})""")
private val WECOM_CLOCK = Regex("""(?:^|[^\d:])(\d{1,2}):(\d{2})(?:$|[^\d:])""")
private val WECOM_COUNT = Regex("""^(\d+)\s*(.*)$""")
private val WECOM_FILE_PREFIX = "[文件] "

private val MONTHS_EN = arrayOf(
    "Jan", "Feb", "Mar", "Apr", "May", "Jun",
    "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
)
private val WEEKDAYS_EN = arrayOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
private val WEEKDAYS_ZH = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

internal fun wecomClock(hour: String, minute: String): String =
    "%02d:%s".format(Locale.ROOT, hour.toIntOrNull() ?: 0, minute)

internal fun wecomClockTimes(value: String?): List<String> {
    val text = value?.trim().orEmpty()
    if (text.isEmpty()) return emptyList()
    WECOM_RANGE.find(text)?.let { match ->
        return listOf(
            wecomClock(match.groupValues[1], match.groupValues[2]),
            wecomClock(match.groupValues[3], match.groupValues[4]),
        )
    }
    val stamps = WECOM_DATE_TIME.findAll(text)
        .map { wecomClock(it.groupValues[4], it.groupValues[5]) }
        .toList()
    if (stamps.isNotEmpty()) return stamps
    return WECOM_CLOCK.findAll(text)
        .map { wecomClock(it.groupValues[1], it.groupValues[2]) }
        .toList()
        .distinct()
}

internal fun wecomDateKey(value: String?): String? = WECOM_DATE.find(value?.trim().orEmpty())?.value

internal fun wecomTimeOnly(value: String?): String? = wecomClockTimes(value).firstOrNull()

internal fun wecomShortDate(dateKey: String?): String? =
    dateKey?.takeIf { WECOM_DATE.matches(it) }?.takeLast(5)

internal fun wecomShortText(value: String?): String? {
    val text = value?.trim().orEmpty()
    if (text.isEmpty()) return null
    val stamped = WECOM_DATE_TIME.replace(text) { match ->
        "${match.groupValues[2]}-${match.groupValues[3]} ${wecomClock(match.groupValues[4], match.groupValues[5])}"
    }
    return WECOM_DATE.replace(stamped) { match -> match.value.takeLast(5) }
}

internal fun wecomShortDateTime(value: String?): String? {
    val text = value?.trim().orEmpty()
    if (text.isEmpty()) return null
    WECOM_DATE_TIME.find(text)?.let { stamp ->
        return "${stamp.groupValues[2]}-${stamp.groupValues[3]} ${wecomClock(stamp.groupValues[4], stamp.groupValues[5])}"
    }
    val date = WECOM_DATE.find(text)
    if (date != null) return date.value.takeLast(5)
    return wecomClockTimes(text).firstOrNull() ?: text
}

internal fun wecomLooksLikeTime(value: String?): Boolean =
    wecomDateKey(value) != null || wecomTimeOnly(value) != null

internal fun wecomInitial(text: String?): String =
    text?.trim()?.firstOrNull()?.toString()?.uppercase(Locale.ROOT).orEmpty()

internal fun wecomDayLabel(dateKey: String?, chinese: Boolean): String? {
    val date = dateKey?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
    return if (chinese) {
        "${date.monthValue}月${date.dayOfMonth}日"
    } else {
        "${MONTHS_EN[date.monthValue - 1]} ${date.dayOfMonth}"
    }
}

internal fun wecomWeekdayLabel(dateKey: String?, chinese: Boolean): String? {
    val date = dateKey?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
    val index = date.dayOfWeek.value - 1
    return if (chinese) WEEKDAYS_ZH[index] else WEEKDAYS_EN[index]
}

internal fun wecomIsToday(dateKey: String?): Boolean =
    dateKey != null && dateKey == LocalDate.now().toString()

internal data class WecomCountParts(val value: String, val unit: String)

internal fun wecomCountParts(meta: String?): WecomCountParts? {
    val match = WECOM_COUNT.find(meta?.trim().orEmpty()) ?: return null
    return WecomCountParts(match.groupValues[1], match.groupValues[2].trim())
}

internal data class WecomMessageParts(
    val time: String?,
    val body: String?,
    val fileName: String?,
)

internal fun wecomMessageParts(item: CliCardItem): WecomMessageParts {
    val meta = item.meta?.trim().orEmpty()
    val subtitle = item.subtitle?.trim().orEmpty()
    if (meta.isNotEmpty()) {
        return wecomMessageBody(wecomShortDateTime(meta), subtitle)
    }
    val segments = subtitle.split(" · ", limit = 2)
    if (segments.size == 2 && wecomLooksLikeTime(segments[0])) {
        return wecomMessageBody(wecomShortDateTime(segments[0]), segments[1].trim())
    }
    return wecomMessageBody(null, subtitle)
}

private fun wecomMessageBody(time: String?, body: String?): WecomMessageParts {
    val value = body?.trim().orEmpty()
    val fileName = value.takeIf { it.startsWith(WECOM_FILE_PREFIX) }
        ?.removePrefix(WECOM_FILE_PREFIX)
        ?.trim()
        ?.ifBlank { null }
    return WecomMessageParts(
        time = time,
        body = value.ifBlank { null }.takeIf { fileName == null },
        fileName = fileName,
    )
}

internal data class WecomScheduleParts(
    val dateKey: String?,
    val start: String?,
    val end: String?,
    val allDay: Boolean,
    val location: String?,
)

internal fun wecomScheduleParts(item: CliCardItem): WecomScheduleParts {
    val subtitle = item.subtitle?.trim().orEmpty()
    val segments = subtitle.split(" · ").map { it.trim() }.filter { it.isNotEmpty() }
    val allDay = subtitle.contains("全天") || subtitle.contains("all day", ignoreCase = true)
    val clocks = wecomClockTimes(subtitle)
    return WecomScheduleParts(
        dateKey = wecomDateKey(item.badge) ?: wecomDateKey(subtitle),
        start = if (allDay) null else clocks.getOrNull(0),
        end = if (allDay) null else clocks.getOrNull(1),
        allDay = allDay,
        location = segments.drop(1).joinToString(" · ").ifBlank { null },
    )
}

internal fun WecomScheduleParts.isUpcoming(): Boolean {
    val date = dateKey?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return false
    val clock = start ?: return !date.isBefore(LocalDate.now())
    val time = runCatching { LocalTime.parse(clock) }.getOrNull() ?: return false
    return LocalDateTime.of(date, time).isAfter(LocalDateTime.now().minusMinutes(1))
}

internal data class WecomTodoParts(
    val status: String?,
    val followers: String?,
    val done: Boolean,
    val deleted: Boolean,
    val dueDate: String?,
    val dueTime: String?,
)

private val WECOM_DONE_STATUSES = setOf("已完成", "完成", "Done", "Completed")
private val WECOM_DELETED_STATUSES = setOf("已删除", "Deleted")
private val WECOM_DUE_MARKERS = listOf("截止 ", "Due ")

internal fun wecomTodoParts(item: CliCardItem): WecomTodoParts {
    val status = item.meta?.trim()?.takeIf { it.isNotEmpty() }
    val subtitle = item.subtitle?.trim().orEmpty()
    val dueRaw = item.badge?.trim()?.takeIf { it.isNotEmpty() } ?: wecomDueRaw(subtitle)
    return WecomTodoParts(
        status = status,
        followers = wecomFollowers(subtitle),
        done = status in WECOM_DONE_STATUSES,
        deleted = status in WECOM_DELETED_STATUSES,
        dueDate = wecomShortDate(wecomDateKey(dueRaw)),
        dueTime = wecomTimeOnly(dueRaw),
    )
}

private fun wecomDueRaw(subtitle: String): String? {
    val marker = WECOM_DUE_MARKERS.firstOrNull { subtitle.contains(it) } ?: return null
    return subtitle.substringAfter(marker).substringBefore(" · ").trim().ifBlank { null }
}

private fun wecomFollowers(subtitle: String): String? {
    if (subtitle.isEmpty()) return null
    return subtitle.split(" · ")
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .filterNot { part -> WECOM_DUE_MARKERS.any { part.startsWith(it) } }
        .joinToString(" · ")
        .ifBlank { null }
}
