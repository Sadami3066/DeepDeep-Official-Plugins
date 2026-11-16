package io.github.whywood.deepdeep.plugin.micloud

import java.net.SocketTimeoutException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal typealias MiCloudText = (zh: String, en: String) -> String

internal fun MiCloudText.fmt(zh: String, en: String, vararg args: Any): String =
    String.format(Locale.ROOT, this(zh, en), *args)

internal fun quotaLabelZh(raw: String): String = when (raw.trim().lowercase(Locale.ROOT)) {
    "gift" -> "赠送空间"
    "paid", "buy", "firstlevel" -> "实付空间"
    "" -> "云空间"
    else -> raw.trim()
}

internal fun quotaLabelEn(raw: String): String = when (raw.trim().lowercase(Locale.ROOT)) {
    "gift" -> "Gift space"
    "paid", "buy", "firstlevel" -> "Paid space"
    "" -> "Cloud storage"
    else -> raw.trim()
}

internal fun featureNameZh(feature: MiCloudFeature): String = when (feature) {
    MiCloudFeature.NOTES -> "笔记"
    MiCloudFeature.TODOS -> "待办"
    MiCloudFeature.SMS -> "短信"
    MiCloudFeature.CONTACTS -> "联系人"
    MiCloudFeature.GALLERY -> "相册"
    MiCloudFeature.RECORDINGS -> "录音"
    MiCloudFeature.ACCOUNT -> "账号"
}

internal fun featureNameEn(feature: MiCloudFeature): String = when (feature) {
    MiCloudFeature.NOTES -> "notes"
    MiCloudFeature.TODOS -> "to-dos"
    MiCloudFeature.SMS -> "SMS"
    MiCloudFeature.CONTACTS -> "contacts"
    MiCloudFeature.GALLERY -> "gallery"
    MiCloudFeature.RECORDINGS -> "recordings"
    MiCloudFeature.ACCOUNT -> "account"
}

internal fun capabilityOffText(feature: MiCloudFeature, t: MiCloudText): String {
    val zh = featureNameZh(feature)
    val en = featureNameEn(feature)
    val sentence = en.replaceFirstChar { it.uppercase(Locale.ROOT) }
    return t(
        "${zh}读取未开启，请在插件页打开${zh}开关",
        "$sentence access is off. Turn on $en on the Xiaomi Cloud plugin page.",
    )
}

internal fun formatMonthTime(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    if (millis <= 0L) return ""
    return DateTimeFormatter.ofPattern("yyyy-MM").format(Instant.ofEpochMilli(millis).atZone(zone))
}

internal fun formatShortTime(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    if (millis <= 0L) return ""
    return DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.ROOT)
        .withZone(zone)
        .format(Instant.ofEpochMilli(millis))
}

internal fun byteValueAndUnit(bytes: Long): Pair<String, String> {
    if (bytes < 1024L) return bytes.toString() to "B"
    val units = listOf("KB", "MB", "GB", "TB", "PB")
    var value = bytes.toDouble()
    var unit = -1
    while (value >= 1024.0 && unit < units.lastIndex) {
        value /= 1024.0
        unit++
    }
    val text = String.format(Locale.ROOT, "%.1f", value).removeSuffix(".0")
    return text to units[unit]
}

internal fun formatBytes(bytes: Long): String {
    if (bytes < 0L) return ""
    if (bytes < 1024L) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB", "PB")
    var value = bytes.toDouble()
    var unit = -1
    while (value >= 1024.0 && unit < units.lastIndex) {
        value /= 1024.0
        unit++
    }
    return String.format(Locale.ROOT, "%.1f %s", value, units[unit])
}

private val XML_NAMED_ENTITIES = mapOf(
    "lt" to "<",
    "gt" to ">",
    "amp" to "&",
    "quot" to "\"",
    "apos" to "'",
    "nbsp" to " ",
)

private val XML_ENTITY_REGEX = Regex("&(#x?[0-9a-fA-F]+|[a-zA-Z]+);")

internal fun decodeXmlEntities(raw: String): String =
    XML_ENTITY_REGEX.replace(raw) { match ->
        val token = match.groupValues[1]
        when {
            token.startsWith("#x") || token.startsWith("#X") ->
                token.drop(2).toIntOrNull(16)?.let { code -> code.toChar().toString() } ?: match.value

            token.startsWith("#") ->
                token.drop(1).toIntOrNull()?.let { code -> code.toChar().toString() } ?: match.value

            else -> XML_NAMED_ENTITIES[token.lowercase(Locale.ROOT)] ?: match.value
        }
    }

private val NOTE_TEXT_BLOCK_REGEX = Regex("<text(?:\\s[^>]*)?>(.*?)</text>", RegexOption.DOT_MATCHES_ALL)

private val ANY_TAG_REGEX = Regex("<[^>]+>", RegexOption.DOT_MATCHES_ALL)

internal fun notePlainText(rawText: String?): String {
    val raw = rawText?.trim().orEmpty()
    if (raw.isEmpty()) return ""
    if (!raw.contains('<')) return raw
    val blocks = NOTE_TEXT_BLOCK_REGEX.findAll(raw)
        .map { match -> decodeXmlEntities(match.groupValues[1]).trimEnd() }
        .toList()
    val text = if (blocks.isNotEmpty()) {
        blocks.joinToString("\n")
    } else {
        decodeXmlEntities(ANY_TAG_REGEX.replace(raw, "")).trim()
    }
    return text.lines().joinToString("\n") { line -> line.trimEnd() }.trim()
}

internal fun accountGuidance(hasProfile: Boolean, t: MiCloudText): String = if (hasProfile) {
    ""
} else {
    t("接口未返回账号资料。", "The account response contained no profile.")
}

internal fun quotaGuidance(entries: Int, t: MiCloudText): String = if (entries > 0) {
    ""
} else {
    t("接口未返回云空间容量数据。", "The response contained no storage capacity data.")
}

internal fun notesListGuidance(
    count: Int,
    cursor: String,
    lastPage: Boolean,
    t: MiCloudText,
): String = when {
    count == 0 -> t("没有返回笔记。", "No notes were returned.")
    cursor.isNotBlank() && !lastPage -> t.fmt(
        "还有更多：下一页用 syncTag=%s（action=list）。",
        "More notes: continue with syncTag=%s (action=list).",
        cursor,
    )

    !lastPage -> t(
        "接口未返回分页游标，无法继续翻页；本次返回接口给出的笔记。",
        "The API returned no page cursor, so paging cannot continue; all returned notes are included.",
    )

    else -> ""
}

internal fun todoListGuidance(
    total: Int,
    unique: Int,
    duplicateCount: Int,
    limit: Int,
    t: MiCloudText,
): String {
    if (total == 0) return t("没有返回待办。", "No to-dos were returned.")
    val parts = mutableListOf<String>()
    if (total >= limit) {
        parts += t.fmt(
            "本次返回 %d 条（已达 limit=%d）。待办接口是增量同步，没有下一页；要读更多请调大 limit，不要按游标翻页。",
            "Returned %d to-dos (limit=%d reached). The to-do API is incremental sync with no next page; raise limit to read more instead of paging.",
            total,
            limit,
        )
    } else {
        parts += t.fmt(
            "本次返回全部 %d 条待办。待办接口是增量同步，没有下一页，不要翻页或重试。",
            "All %d to-dos were returned. The to-do API is incremental sync with no next page; don't page or retry.",
            total,
        )
    }
    if (duplicateCount > 0) {
        parts += t.fmt(
            "其中 %d 条内容重复，合并后是 %d 件不同事项；回答时按 %d 件不同事项，不要按 %d 条重复列举。",
            "%d are duplicates, leaving %d distinct tasks; answer with the %d distinct tasks, not %d repeated rows.",
            duplicateCount,
            unique,
            unique,
            total,
        )
    }
    return parts.joinToString(" ")
}

internal fun rawNoiseNote(t: MiCloudText): String = t(
    "正文是上游原始数据：签名链接、拼音/排序索引和内部 id 不需要复述给用户。",
    "The body is raw upstream data: don't repeat signed links, pinyin/sort keys or internal ids back to the user.",
)

internal fun smsListGuidance(
    count: Int,
    syncTag: String,
    syncThreadTag: String,
    t: MiCloudText,
): String = when {
    count == 0 -> t("没有返回短信记录。", "No SMS records were returned.")
    syncTag.isNotBlank() || syncThreadTag.isNotBlank() -> {
        val params = buildList {
            if (syncTag.isNotBlank()) add("syncTag=$syncTag")
            if (syncThreadTag.isNotBlank()) add("syncThreadTag=$syncThreadTag")
        }
        t.fmt(
            "本次返回 %d 条；如果还要更早的记录，可带 %s 再请求一次，返回空说明已到末尾，不要重复翻页。",
            "Returned %d threads; to read older ones retry with %s, and stop when the response is empty.",
            count,
            params.joinToString(t("、", ", ")),
        )
    }

    else -> t(
        "接口未返回分页游标；本次返回接口给出的全部短信记录。",
        "The API returned no page cursor; all SMS records in this response are included.",
    )
}

internal fun contactsListGuidance(
    count: Int,
    fetched: Int,
    searching: Boolean,
    t: MiCloudText,
): String = when {
    searching && count == 0 -> t.fmt(
        "本次拉到的 %d 位联系人里没有匹配；联系人接口是增量同步（initdata），没有下一页，不要翻页或重试。",
        "No match among the %d contacts fetched; the contacts API is incremental sync (initdata) with no next page, so don't page or retry.",
        fetched,
    )

    searching -> t.fmt(
        "在本次拉到的 %d 位联系人中匹配到 %d 位；原始正文是完整响应，回答时只按匹配结果。联系人接口是增量同步，没有下一页。",
        "Matched %d of %d fetched contacts; the raw body is the full response, so answer only from the matches. The contacts API is incremental sync with no next page.",
        count,
        fetched,
    )

    count == 0 -> t("没有返回联系人。", "No contacts were returned.")
    else -> t.fmt(
        "本次返回 %d 位联系人；联系人接口是增量同步（initdata），没有下一页，不要翻页或重试。",
        "%d contacts returned; the contacts API is incremental sync (initdata) with no next page, so don't page or retry.",
        count,
    )
}

internal fun galleryGuidance(
    count: Int,
    hasMore: Boolean,
    albums: Boolean,
    nextPage: Int = 0,
    t: MiCloudText,
): String = when {
    count == 0 && albums -> t("没有返回相册。", "No albums were returned.")
    count == 0 -> t("没有返回相册内容。", "No gallery items were returned.")
    hasMore && albums -> t(
        "接口标记还有更多相册，但未返回分页参数；本次只返回这些。",
        "The API reports more albums but returned no page parameter; only these were returned.",
    )

    hasMore && nextPage > 0 -> t.fmt(
        "还有更多：下一页用 pageNum=%d（albumId 保持不变）。",
        "More photos: next page with pageNum=%d and the same albumId.",
        nextPage,
    )

    hasMore -> t(
        "接口标记还有更多相册内容，但未返回下一页参数；本次只返回这些。",
        "The API reports more gallery items but returned no next-page parameter; only these were returned.",
    )

    albums -> t("相册接口无分页参数，本次全部返回。", "The album API has no page parameter; everything was returned.")
    else -> t("相册内容已到最后一页。", "This is the last page of gallery items.")
}

internal fun recordingsListGuidance(count: Int, nextOffset: Int, t: MiCloudText): String = if (count == 0) {
    t("没有返回录音。", "No recordings were returned.")
} else {
    t.fmt(
        "下一页参数：offset=%d（action=list）。",
        "Next page parameter: offset=%d (action=list).",
        nextOffset,
    )
}

internal fun recordingDownloadGuidance(path: String, t: MiCloudText): String = t.fmt(
    "录音已保存到 %s，可用于读取或交付。",
    "Recording saved to %s; use the path to read or present it.",
    path,
)

internal fun requestFailureGuidance(
    feature: MiCloudFeature,
    error: Exception,
    t: MiCloudText,
): String {
    val zh = featureNameZh(feature)
    val en = featureNameEn(feature)
    if (error is MiCloudAuthException) {
        return t(
            "小米云登录已失效，请在插件页重新登录后再试。",
            "Xiaomi Cloud login expired. Sign in again on the plugin page.",
        )
    }
    if (error is IllegalArgumentException) {
        return error.message?.takeIf { it.isNotBlank() } ?: t("参数无效。", "Invalid argument.")
    }
    if (error is SocketTimeoutException || error.message.orEmpty().containsTimeout()) {
        return t(
            String.format(Locale.ROOT, "小米云%s请求超时，可重试", zh),
            String.format(Locale.ROOT, "Xiaomi Cloud %s request timed out. Try again.", en),
        )
    }
    if (error is MiCloudHttpException) {
        return t(
            String.format(
                Locale.ROOT,
                "小米云%s请求失败（HTTP %d），可稍后重试。",
                zh,
                error.status,
            ),
            String.format(
                Locale.ROOT,
                "Xiaomi Cloud %s request failed (HTTP %d). Try again later.",
                en,
                error.status,
            ),
        )
    }
    val detail = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
    return t(
        String.format(Locale.ROOT, "小米云%s请求失败：%s。可稍后重试。", zh, detail),
        String.format(Locale.ROOT, "Xiaomi Cloud %s request failed: %s. Try again later.", en, detail),
    )
}

private fun String.containsTimeout(): Boolean {
    val normalized = lowercase(Locale.ROOT)
    return normalized.contains("timeout") || normalized.contains("timed out")
}
