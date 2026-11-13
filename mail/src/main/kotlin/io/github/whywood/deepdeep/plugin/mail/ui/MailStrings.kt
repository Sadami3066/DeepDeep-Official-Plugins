package io.github.whywood.deepdeep.plugin.mail.ui

import io.github.whywood.deepdeep.plugin.api.PluginHost
import io.github.whywood.deepdeep.plugin.api.localizedText
import io.github.whywood.deepdeep.plugin.mail.MailProviderPreset
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal fun PluginHost.t(zh: String, en: String): String =
    text(localizedText(default = zh, zh = zh, en = en))

internal fun PluginHost.tf(zh: String, en: String, vararg args: Any): String =
    text(localizedText(default = zh, zh = zh, en = en), *args)

internal val PluginHost.chinese: Boolean
    get() = app().info.language.startsWith("zh")

internal fun PluginHost.providerName(preset: MailProviderPreset): String =
    text(localizedText(default = preset.nameZh, zh = preset.nameZh, en = preset.nameEn))

internal fun PluginHost.providerStep(preset: MailProviderPreset, index: Int): String {
    val zh = preset.stepsZh.getOrNull(index).orEmpty()
    val en = preset.stepsEn.getOrNull(index) ?: zh
    return text(localizedText(default = zh, zh = zh, en = en))
}

internal fun PluginHost.providerNote(preset: MailProviderPreset): String? {
    val zh = preset.noteZh ?: return null
    val en = preset.noteEn ?: zh
    return text(localizedText(default = zh, zh = zh, en = en))
}

private val ClockFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
private val ZhMonthDayFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("M月d日", Locale.SIMPLIFIED_CHINESE)
private val EnMonthDayFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH)
private val ZhFullDateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy年M月d日", Locale.SIMPLIFIED_CHINESE)
private val EnFullDateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH)
private val ZhDateTimeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("M月d日 HH:mm", Locale.SIMPLIFIED_CHINESE)
private val EnDateTimeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, HH:mm", Locale.ENGLISH)

internal enum class MailDateGroup { TODAY, YESTERDAY, EARLIER }

internal fun mailDateGroup(millis: Long, now: Long): MailDateGroup {
    val zone = ZoneId.systemDefault()
    val date = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    return when (date) {
        today -> MailDateGroup.TODAY
        today.minusDays(1) -> MailDateGroup.YESTERDAY
        else -> MailDateGroup.EARLIER
    }
}

internal fun mailListTime(millis: Long, now: Long, chinese: Boolean): String {
    val zone = ZoneId.systemDefault()
    val dateTime = Instant.ofEpochMilli(millis).atZone(zone)
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    return when {
        dateTime.toLocalDate() == today || dateTime.toLocalDate() == today.minusDays(1) ->
            ClockFormat.format(dateTime)
        dateTime.year == today.year -> (if (chinese) ZhMonthDayFormat else EnMonthDayFormat).format(dateTime)
        else -> (if (chinese) ZhFullDateFormat else EnFullDateFormat).format(dateTime)
    }
}

internal fun mailFullTime(millis: Long, chinese: Boolean): String {
    val dateTime = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
    return (if (chinese) ZhDateTimeFormat else EnDateTimeFormat).format(dateTime)
}

internal fun mailLastSyncTime(millis: Long, chinese: Boolean): String {
    val zone = ZoneId.systemDefault()
    val date = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
    val today = Instant.now().atZone(zone).toLocalDate()
    return when {
        date == today -> ClockFormat.format(Instant.ofEpochMilli(millis).atZone(zone))
        date.year == today.year -> (if (chinese) ZhMonthDayFormat else EnMonthDayFormat).format(date)
        else -> (if (chinese) ZhFullDateFormat else EnFullDateFormat).format(date)
    }
}

internal fun mailInitial(name: String, address: String): String {
    val source = name.trim().ifEmpty { address.trim() }
    val first = source.firstOrNull { !it.isWhitespace() } ?: return "?"
    return if (first.code < 128) first.uppercaseChar().toString() else first.toString()
}

internal fun parseRecipients(value: String): List<String> =
    value.split(',', ';', '，', '；')
        .map { it.trim() }
        .filter { it.isNotEmpty() }

internal fun recipientInput(values: List<String>): String = values.joinToString(", ")

internal fun validRecipient(value: String): Boolean =
    value.contains('@') && value.length >= 3 && value.none { it.isWhitespace() }

internal fun withSubjectPrefix(prefix: String, subject: String): String =
    if (subject.trimStart().startsWith(prefix, ignoreCase = true)) subject else prefix + subject

internal fun formatFileSize(bytes: Long): String = when {
    bytes <= 0L -> ""
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> String.format(Locale.ROOT, "%.0f KB", bytes / 1024.0)
    else -> String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0))
}

private val ImageExtensions = setOf(
    "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif", "svg",
)

internal fun isImageAttachment(mimeType: String, name: String): Boolean {
    if (mimeType.startsWith("image/", ignoreCase = true)) return true
    return name.substringAfterLast('.', "").lowercase() in ImageExtensions
}

internal fun PluginHost.readableError(
    error: Throwable,
    fallbackZh: String = "操作失败，请重试",
    fallbackEn: String = "Something went wrong. Try again.",
): String {
    val message = error.message?.trim().orEmpty()
    return message.ifEmpty { t(fallbackZh, fallbackEn) }
}

internal fun displayPreview(value: String, limit: Int = 160): String {
    val single = value.replace(Regex("\\s+"), " ").trim()
    return if (single.length <= limit) single else single.take(limit) + "…"
}

internal fun displaySubject(value: String, empty: String): String = value.trim().ifEmpty { empty }
