package io.github.whywood.deepdeep.plugin.micloud

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal typealias MiCloudText = (zh: String, en: String) -> String

internal fun MiCloudText.fmt(zh: String, en: String, vararg args: Any): String =
    String.format(Locale.ROOT, this(zh, en), *args)

internal fun quotaLabelZh(raw: String): String = when (raw.trim().lowercase(Locale.ROOT)) {
    "gift" -> "赠送空间"
    "firstlevel" -> "基础空间"
    "" -> "云空间"
    else -> raw.trim()
}

internal fun quotaLabelEn(raw: String): String = when (raw.trim().lowercase(Locale.ROOT)) {
    "gift" -> "Gift space"
    "firstlevel" -> "Base space"
    "" -> "Cloud storage"
    else -> raw.trim()
}

internal fun featureNameZh(feature: MiCloudFeature): String = when (feature) {
    MiCloudFeature.NOTES -> "笔记"
    MiCloudFeature.TODOS -> "待办"
    MiCloudFeature.SMS -> "短信与通话"
    MiCloudFeature.CONTACTS -> "联系人"
    MiCloudFeature.GALLERY -> "相册"
    MiCloudFeature.RECORDINGS -> "录音"
    MiCloudFeature.ACCOUNT -> "账号信息"
}

internal fun featureNameEn(feature: MiCloudFeature): String = when (feature) {
    MiCloudFeature.NOTES -> "Notes"
    MiCloudFeature.TODOS -> "To-dos"
    MiCloudFeature.SMS -> "SMS and calls"
    MiCloudFeature.CONTACTS -> "Contacts"
    MiCloudFeature.GALLERY -> "Gallery"
    MiCloudFeature.RECORDINGS -> "Recordings"
    MiCloudFeature.ACCOUNT -> "Account info"
}

internal fun capabilityOffText(feature: MiCloudFeature, t: MiCloudText): String {
    val zh = featureNameZh(feature)
    val en = featureNameEn(feature)
    return t(
        "${zh}读取未开启，请在插件页打开${zh}开关",
        "$en access is off. Turn on $en on the Xiaomi Cloud plugin page.",
    )
}

internal const val OBSERVATION_TIME_PATTERN = "yyyy-MM-dd HH:mm"

internal fun formatTime(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    if (millis <= 0L) return ""
    return DateTimeFormatter.ofPattern(OBSERVATION_TIME_PATTERN, Locale.ROOT)
        .withZone(zone)
        .format(Instant.ofEpochMilli(millis))
}

internal fun formatEpochValue(value: Long): String =
    when {
        value <= 0L -> ""
        value < 10_000_000_000L -> formatTime(value * 1000)
        else -> formatTime(value)
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

internal fun formatShortTime(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    if (millis <= 0L) return ""
    return DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.ROOT)
        .withZone(zone)
        .format(Instant.ofEpochMilli(millis))
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

internal fun previewText(raw: String?, maxChars: Int = 240): String {
    val text = raw?.trim().orEmpty()
    if (text.length <= maxChars) return text
    return text.take(maxChars).trimEnd() + "…"
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

internal fun accountProfileObservation(profile: MiCloudProfile?, raw: JsonObject, t: MiCloudText): String {
    val nickname = profile?.nickname.orEmpty()
    val userId = profile?.userId.orEmpty()
    val email = raw.stringOrNull("email") ?: raw.stringOrNull("mail").orEmpty()
    val phone = raw.stringOrNull("phone") ?: raw.stringOrNull("mobile").orEmpty()
    return buildString {
        appendLine(t("小米账号资料：", "Xiaomi account:"))
        appendLine(t.fmt("- 昵称：%s", "- Nickname: %s", nickname.ifBlank { t("未设置", "Not set") }))
        appendLine(t.fmt("- 用户 ID：%s", "- User ID: %s", userId.ifBlank { t("未知", "Unknown") }))
        if (email.isNotBlank()) appendLine(t.fmt("- 邮箱：%s", "- Email: %s", email))
        if (phone.isNotBlank()) appendLine(t.fmt("- 手机：%s", "- Phone: %s", phone))
    }.trim()
}

internal fun quotaObservation(entries: List<MiCloudQuotaEntry>, t: MiCloudText): String {
    if (entries.isEmpty()) return t("暂时无法读取云空间用量。", "Couldn't read cloud storage usage.")
    return buildString {
        appendLine(t.fmt("小米云空间（%d 项）：", "Xiaomi Cloud storage (%d items):", entries.size))
        entries.forEach { entry ->
            appendLine("- " + quotaEntryLine(entry, t))
        }
    }.trim()
}

internal fun quotaEntryLine(entry: MiCloudQuotaEntry, t: MiCloudText): String {
    val label = t(quotaLabelZh(entry.label), quotaLabelEn(entry.label))
    val total = entry.totalBytes
    val used = entry.usedBytes
    val free = entry.freeBytes ?: if (total != null && used != null) (total - used).coerceAtLeast(0L) else null
    return when {
        total != null && used != null -> t.fmt(
            "%s：已用 %s / 共 %s，剩余 %s",
            "%s: used %s of %s, %s free",
            label,
            formatBytes(used),
            formatBytes(total),
            formatBytes(free ?: (total - used).coerceAtLeast(0L)),
        )

        used != null -> t.fmt("%s：已用 %s", "%s: used %s", label, formatBytes(used))
        total != null -> t.fmt("%s：共 %s", "%s: total %s", label, formatBytes(total))
        else -> t.fmt("%s：暂无容量数据", "%s: no capacity data", label)
    }
}

internal fun notesListObservation(
    items: List<MiCloudNoteItem>,
    total: Int,
    cursor: String,
    lastPage: Boolean,
    t: MiCloudText,
): String {
    if (items.isEmpty()) return t("没有笔记。", "No notes.")
    return buildString {
        appendLine(t.fmt("笔记（共 %d 条）：", "Notes (%d items):", total))
        items.forEach { item ->
            appendLine("- " + noteListLine(item, t))
        }
        if (cursor.isNotBlank()) {
            appendLine(t.fmt("下一页游标：%s", "Next cursor: %s", cursor))
        } else if (lastPage) {
            appendLine(t("已到最后一页。", "This is the last page."))
        }
    }.trim()
}

internal fun noteListLine(item: MiCloudNoteItem, t: MiCloudText): String = buildString {
    append(t.fmt("id=%s", "id=%s", item.id.ifBlank { "-" }))
    append(t.fmt("：主题「%s」", ": subject “%s”", item.subject.ifBlank { t("（无主题）", "(No subject)") }))
    val modified = formatTime(item.modifyTime)
    if (modified.isNotBlank()) append(t.fmt("，修改时间 %s", ", modified %s", modified))
    if (item.snippet.isNotBlank()) append(t.fmt("，摘要：%s", ", preview: %s", previewText(item.snippet, 120)))
}

internal fun noteReadObservation(noteId: String, item: MiCloudNoteItem, plainText: String, t: MiCloudText): String =
    buildString {
        appendLine(t.fmt("笔记 id=%s", "Note id=%s", noteId.ifBlank { item.id.ifBlank { "-" } }))
        appendLine(t.fmt("主题：%s", "Subject: %s", item.subject.ifBlank { t("（无主题）", "(No subject)") }))
        val modified = formatTime(item.modifyTime)
        if (modified.isNotBlank()) appendLine(t.fmt("修改时间：%s", "Modified: %s", modified))
        appendLine(t.fmt("正文：", "Body:"))
        append(plainText.ifBlank { t("（正文为空）", "(Empty body)") })
    }

internal fun noteWriteObservation(
    action: String,
    noteId: String,
    subject: String,
    content: String,
    modifyTime: Long,
    t: MiCloudText,
): String = buildString {
    val head = when (action) {
        NOTES_ACTION_CREATE -> t("已新建笔记", "Note created")
        NOTES_ACTION_UPDATE -> t("已修改笔记", "Note updated")
        NOTES_ACTION_DELETE -> t("已删除笔记", "Note deleted")
        else -> t("已保存笔记", "Note saved")
    }
    append(head)
    if (noteId.isNotBlank()) append(t.fmt("（id=%s）", " (id=%s)", noteId))
    appendLine()
    if (subject.isNotBlank()) appendLine(t.fmt("主题：%s", "Subject: %s", subject))
    if (content.isNotBlank()) appendLine(t.fmt("正文：%s", "Body: %s", previewText(content, 400)))
    val modified = formatTime(modifyTime)
    if (modified.isNotBlank()) appendLine(t.fmt("修改时间：%s", "Modified: %s", modified))
}.trim()

internal fun todoListObservation(
    items: List<MiCloudTodoItem>,
    cursor: String,
    hasMore: Boolean,
    t: MiCloudText,
): String {
    if (items.isEmpty()) return t("没有待办。", "No to-dos.")
    val doneCount = items.count { it.done }
    return buildString {
        appendLine(t.fmt("待办（共 %d 条，已完成 %d 条）：", "To-dos (%d items, %d done):", items.size, doneCount))
        items.forEach { item ->
            appendLine("- " + todoLine(item, t))
        }
        if (cursor.isNotBlank()) {
            appendLine(t.fmt("下一页游标：%s", "Next cursor: %s", cursor))
        } else if (!hasMore) {
            appendLine(t("已到最后一页。", "This is the last page."))
        }
    }.trim()
}

internal fun todoLine(item: MiCloudTodoItem, t: MiCloudText): String = buildString {
    append(t.fmt("id=%s", "id=%s", item.id.ifBlank { "-" }))
    append(t.fmt("：%s", ": %s", item.content.ifBlank { t("（无内容）", "(No content)") }))
    append(t("，", ", "))
    append(if (item.done) t("已完成", "done") else t("未完成", "not done"))
    val remind = formatTime(item.remindTime)
    if (remind.isNotBlank()) append(t.fmt("，提醒时间 %s", ", reminder %s", remind))
    val modified = formatTime(item.modifyTime)
    if (modified.isNotBlank()) append(t.fmt("，修改时间 %s", ", modified %s", modified))
}

internal fun todoWriteObservation(
    action: String,
    todoId: String,
    content: String,
    done: Boolean,
    remindTime: Long,
    modifyTime: Long,
    t: MiCloudText,
): String = buildString {
    val head = when (action) {
        TODO_ACTION_CREATE -> t("已新建待办", "To-do created")
        TODO_ACTION_UPDATE -> t("已修改待办", "To-do updated")
        TODO_ACTION_DELETE -> t("已删除待办", "To-do deleted")
        else -> t("已保存待办", "To-do saved")
    }
    append(head)
    if (todoId.isNotBlank()) append(t.fmt("（id=%s）", " (id=%s)", todoId))
    appendLine()
    if (content.isNotBlank()) appendLine(t.fmt("内容：%s", "Content: %s", content))
    appendLine(t.fmt("状态：%s", "State: %s", if (done) t("已完成", "done") else t("未完成", "not done")))
    val remind = formatTime(remindTime)
    if (remind.isNotBlank()) appendLine(t.fmt("提醒时间：%s", "Reminder: %s", remind))
    val modified = formatTime(modifyTime)
    if (modified.isNotBlank()) appendLine(t.fmt("修改时间：%s", "Modified: %s", modified))
}.trim()

internal fun smsObservation(entries: List<JsonObject>, cursor: String, t: MiCloudText): String {
    if (entries.isEmpty()) return t("没有短信记录。", "No SMS records.")
    return buildString {
        appendLine(t.fmt("短信与通话（%d 条）：", "SMS and calls (%d items):", entries.size))
        entries.forEach { entry -> appendLine("- " + smsLine(entry, t)) }
        if (cursor.isNotBlank()) appendLine(t.fmt("下一页 watermark：%s", "Next watermark: %s", cursor))
    }.trim()
}

internal fun albumsObservation(items: List<JsonObject>, t: MiCloudText): String {
    if (items.isEmpty()) return t("没有相册。", "No albums.")
    return buildString {
        appendLine(t.fmt("相册（%d 个）：", "Albums (%d):", items.size))
        items.forEach { item ->
            appendLine("- " + albumLine(item, t))
        }
    }.trim()
}

private fun albumLine(item: JsonObject, t: MiCloudText): String {
    val parts = mutableListOf<String>()
    val id = item.stringOrNull("id")
    if (!id.isNullOrBlank()) parts += t.fmt("id=%s", "id=%s", id)
    val name = item.stringOrNull("name") ?: item.stringOrNull("title") ?: item.stringOrNull("albumName")
    if (!name.isNullOrBlank()) parts += name
    val count = item.intOrNull("photoCount")
        ?: item.intOrNull("mediaCount")
        ?: item.intOrNull("count")
        ?: item.intOrNull("size")
    if (count != null) parts += t.fmt("%d 项", "%d items", count)
    val epoch = item.firstEpoch("createTime", "date", "time", "updateTime")
    if (epoch > 0L) parts += formatEpochValue(epoch)
    if (parts.isEmpty()) parts += scalarSummary(item, t)
    return parts.joinToString(t("；", "; "))
}

private fun smsLine(entry: JsonObject, t: MiCloudText): String {
    val parts = mutableListOf<String>()
    val id = entry.stringOrNull("id") ?: entry.stringOrNull("threadId") ?: entry.stringOrNull("sid")
    if (!id.isNullOrBlank()) parts += t.fmt("id=%s", "id=%s", id)
    val number = entry.stringOrNull("address")
        ?: entry.stringOrNull("number")
        ?: entry.stringOrNull("phone")
        ?: entry.stringOrNull("msisdn")
    if (!number.isNullOrBlank()) parts += t.fmt("号码 %s", "number %s", number)
    val contact = entry.stringOrNull("name") ?: entry.stringOrNull("contactName")
    if (!contact.isNullOrBlank()) parts += t.fmt("联系人 %s", "contact %s", contact)
    val text = entry.stringOrNull("body")
        ?: entry.stringOrNull("snippet")
        ?: entry.stringOrNull("content")
        ?: entry.stringOrNull("text")
    if (!text.isNullOrBlank()) parts += t.fmt("内容「%s」", "text “%s”", previewText(text, 80))
    val epoch = entry.firstEpoch("date", "time", "timestamp", "dateTime", "ts")
    if (epoch > 0L) parts += formatEpochValue(epoch)
    val unread = entry.intOrNull("unread") ?: entry.intOrNull("unreadCount")
    if (unread != null && unread > 0) parts += t.fmt("未读 %d", "%d unread", unread)
    if (parts.isEmpty()) parts += scalarSummary(entry, t)
    return parts.joinToString(t("；", "; "))
}

internal fun contactsObservation(contacts: List<JsonObject>, t: MiCloudText): String {
    if (contacts.isEmpty()) return t("没有联系人。", "No contacts.")
    return buildString {
        appendLine(t.fmt("联系人（%d 位）：", "Contacts (%d):", contacts.size))
        contacts.forEach { contact -> appendLine("- " + contactLine(contact, t)) }
    }.trim()
}

private fun contactLine(contact: JsonObject, t: MiCloudText): String {
    val parts = mutableListOf<String>()
    val name = contact.stringOrNull("name")
        ?: contact.stringOrNull("displayName")
        ?: contact.stringOrNull("fullName")
    if (!name.isNullOrBlank()) parts += name
    val numbers = contact.strings("phone", "number", "mobile", "phoneNumber", "phones", "phoneNumbers")
    if (numbers.isNotEmpty()) parts += t.fmt("电话 %s", "phone %s", numbers.joinToString(", "))
    val emails = contact.strings("email", "emails", "emailAddress", "emailAddresses")
    if (emails.isNotEmpty()) parts += t.fmt("邮箱 %s", "email %s", emails.joinToString(", "))
    val organization = contact.stringOrNull("organization") ?: contact.stringOrNull("company")
    if (!organization.isNullOrBlank()) parts += t.fmt("单位 %s", "organization %s", organization)
    if (parts.isEmpty()) parts += scalarSummary(contact, t)
    return parts.joinToString(t("；", "; "))
}

internal fun galleryObservation(items: List<JsonObject>, t: MiCloudText): String {
    if (items.isEmpty()) return t("没有相册内容。", "No gallery items.")
    return buildString {
        appendLine(t.fmt("相册内容（%d 项）：", "Gallery items (%d):", items.size))
        items.forEach { item -> appendLine("- " + galleryLine(item, t)) }
    }.trim()
}

private fun galleryLine(item: JsonObject, t: MiCloudText): String {
    val parts = mutableListOf<String>()
    val id = item.stringOrNull("id")
    if (!id.isNullOrBlank()) parts += t.fmt("id=%s", "id=%s", id)
    val name = item.stringOrNull("fileName") ?: item.stringOrNull("name") ?: item.stringOrNull("title")
    if (!name.isNullOrBlank()) parts += name
    item.longOrNull("size")?.let { parts += formatBytes(it) }
    item.stringOrNull("mimeType")?.let { parts += it }
    val epoch = item.firstEpoch("dateTaken", "createTime", "date", "time")
    if (epoch > 0L) parts += formatEpochValue(epoch)
    if (item.boolOrNull("favorite") == true) parts += t("已收藏", "favorite")
    if (parts.isEmpty()) parts += scalarSummary(item, t)
    return parts.joinToString(t("；", "; "))
}

internal fun recordingsObservation(items: List<JsonObject>, nextOffset: Int, t: MiCloudText): String {
    if (items.isEmpty()) return t("没有录音。", "No recordings.")
    return buildString {
        appendLine(t.fmt("录音（%d 条）：", "Recordings (%d items):", items.size))
        items.forEach { item ->
            appendLine("- " + recordingLine(item, t))
        }
        appendLine(t.fmt("下一页 offset：%d", "Next offset: %d", nextOffset))
    }.trim()
}

private fun recordingLine(item: JsonObject, t: MiCloudText): String {
    val parts = mutableListOf<String>()
    item.stringOrNull("id")?.let { parts += t.fmt("id=%s", "id=%s", it) }
    val name = item.stringOrNull("name") ?: item.stringOrNull("fileName")
    if (!name.isNullOrBlank()) parts += name
    recordingDurationSeconds(item.longOrNull("duration")).takeIf { it > 0L }?.let { parts += t.fmt("时长 %d 秒", "%d s", it) }
    item.longOrNull("size")?.let { parts += formatBytes(it) }
    val epoch = item.firstEpoch("createTime", "date", "time")
    if (epoch > 0L) parts += formatEpochValue(epoch)
    if (parts.isEmpty()) parts += scalarSummary(item, t)
    return parts.joinToString(t("；", "; "))
}

internal fun recordingDownloadObservation(fileId: String, name: String, size: Long, path: String, t: MiCloudText): String =
    t.fmt(
        "录音已下载：id=%s，文件名 %s，大小 %s，路径 %s",
        "Recording downloaded: id=%s, file %s, size %s, path %s",
        fileId,
        name,
        formatBytes(size),
        path,
    )

private fun scalarSummary(obj: JsonObject, t: MiCloudText): String = obj.entries
    .mapNotNull { (key, value) ->
        when (value) {
            is JsonPrimitive -> value.contentOrNull?.takeIf { it.isNotBlank() }?.let { "$key：${previewText(it, 60)}" }
            is JsonArray -> t.fmt("%s 共 %d 项", "%s (%d items)", key, value.size)
            is JsonObject -> t.fmt("%s 共 %d 项", "%s (%d fields)", key, value.size)
            else -> null
        }
    }
    .take(MAX_SCALAR_FIELDS)
    .joinToString(t("；", "; "))

private const val MAX_SCALAR_FIELDS = 8

private fun JsonObject.strings(vararg keys: String): List<String> = keys.flatMap { key ->
    when (val value = this[key]) {
        is JsonPrimitive -> listOfNotNull(value.contentOrNull?.takeIf { it.isNotBlank() })
        is JsonArray -> value.mapNotNull { item ->
            when (item) {
                is JsonPrimitive -> item.contentOrNull?.takeIf { it.isNotBlank() }
                is JsonObject ->
                    item.stringOrNull("number")
                        ?: item.stringOrNull("value")
                        ?: item.stringOrNull("address")
                        ?: item.stringOrNull("email")

                else -> null
            }
        }

        else -> emptyList()
    }
}

private fun JsonObject.firstEpoch(vararg keys: String): Long {
    keys.forEach { key ->
        val raw = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@forEach
        raw.toDoubleOrNull()?.let { return it.toLong() }
    }
    return 0L
}
