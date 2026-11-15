package io.github.whywood.deepdeep.plugin.micloud

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

internal const val ACCOUNT_MODE_PROFILE = "profile"

internal const val ACCOUNT_MODE_QUOTA = "quota"

internal const val NOTES_ACTION_LIST = "list"

internal const val NOTES_ACTION_READ = "read"

internal const val NOTES_ACTION_CREATE = "create"

internal const val NOTES_ACTION_UPDATE = "update"

internal const val NOTES_ACTION_DELETE = "delete"

internal const val TODO_ACTION_LIST = "list"

internal const val TODO_ACTION_CREATE = "create"

internal const val TODO_ACTION_UPDATE = "update"

internal const val TODO_ACTION_DELETE = "delete"

internal const val MAX_CARD_ROWS = 8

internal const val FILES_MODE_LIST = "list"

internal const val FILES_MODE_DOWNLOAD = "download"

internal data class MiCloudQuotaEntry(
    val label: String = "",
    val totalBytes: Long? = null,
    val usedBytes: Long? = null,
    val freeBytes: Long? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        putIfNotBlank("label", label)
        totalBytes?.let { put("totalBytes", it) }
        usedBytes?.let { put("usedBytes", it) }
        freeBytes?.let { put("freeBytes", it) }
    }

    companion object {
        fun fromJson(obj: JsonObject): MiCloudQuotaEntry = MiCloudQuotaEntry(
            label = obj.stringOrNull("label").orEmpty(),
            totalBytes = obj.longOrNull("totalBytes"),
            usedBytes = obj.longOrNull("usedBytes"),
            freeBytes = obj.longOrNull("freeBytes"),
        )
    }
}

internal data class MiCloudAccountCardData(
    val mode: String = ACCOUNT_MODE_PROFILE,
    val nickname: String = "",
    val userId: String = "",
    val email: String = "",
    val phone: String = "",
    val entries: List<MiCloudQuotaEntry> = emptyList(),
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("mode", mode)
        putIfNotBlank("nickname", nickname)
        putIfNotBlank("userId", userId)
        putIfNotBlank("email", email)
        putIfNotBlank("phone", phone)
        putJsonArray("entries") { entries.forEach { add(it.toJson()) } }
    }

    companion object {
        fun fromJson(obj: JsonObject): MiCloudAccountCardData = MiCloudAccountCardData(
            mode = obj.stringOrNull("mode") ?: ACCOUNT_MODE_PROFILE,
            nickname = obj.stringOrNull("nickname").orEmpty(),
            userId = obj.stringOrNull("userId").orEmpty(),
            email = obj.stringOrNull("email").orEmpty(),
            phone = obj.stringOrNull("phone").orEmpty(),
            entries = obj.arrayOrNull("entries").orEmpty()
                .mapNotNull { it as? JsonObject }
                .map(MiCloudQuotaEntry::fromJson),
        )
    }
}

internal data class MiCloudNoteItem(
    val id: String = "",
    val subject: String = "",
    val snippet: String = "",
    val modifyTime: Long = 0L,
) {
    fun toJson(): JsonObject = buildJsonObject {
        putIfNotBlank("id", id)
        putIfNotBlank("subject", subject)
        putIfNotBlank("snippet", snippet)
        if (modifyTime > 0L) put("modifyTime", modifyTime)
    }

    companion object {
        fun fromJson(obj: JsonObject): MiCloudNoteItem = MiCloudNoteItem(
            id = obj.stringOrNull("id").orEmpty(),
            subject = obj.stringOrNull("subject").orEmpty(),
            snippet = obj.stringOrNull("snippet").orEmpty(),
            modifyTime = obj.longOrNull("modifyTime") ?: 0L,
        )
    }
}

internal data class MiCloudNotesCardData(
    val action: String = NOTES_ACTION_LIST,
    val total: Int = 0,
    val noteId: String = "",
    val subject: String = "",
    val preview: String = "",
    val modifyTime: Long = 0L,
    val cursor: String = "",
    val lastPage: Boolean = false,
    val rows: List<MiCloudNoteItem> = emptyList(),
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("action", action)
        put("total", total)
        putIfNotBlank("noteId", noteId)
        putIfNotBlank("subject", subject)
        putIfNotBlank("preview", preview)
        if (modifyTime > 0L) put("modifyTime", modifyTime)
        putIfNotBlank("cursor", cursor)
        if (lastPage) put("lastPage", true)
        putJsonArray("rows") { rows.forEach { add(it.toJson()) } }
    }

    companion object {
        fun fromJson(obj: JsonObject): MiCloudNotesCardData = MiCloudNotesCardData(
            action = obj.stringOrNull("action") ?: NOTES_ACTION_LIST,
            total = obj.intOrNull("total") ?: 0,
            noteId = obj.stringOrNull("noteId").orEmpty(),
            subject = obj.stringOrNull("subject").orEmpty(),
            preview = obj.stringOrNull("preview").orEmpty(),
            modifyTime = obj.longOrNull("modifyTime") ?: 0L,
            cursor = obj.stringOrNull("cursor").orEmpty(),
            lastPage = obj.boolOrNull("lastPage") ?: false,
            rows = obj.arrayOrNull("rows").orEmpty()
                .mapNotNull { it as? JsonObject }
                .map(MiCloudNoteItem::fromJson),
        )
    }
}

internal data class MiCloudTodoItem(
    val id: String = "",
    val content: String = "",
    val done: Boolean = false,
    val remindTime: Long = 0L,
    val modifyTime: Long = 0L,
) {
    fun toJson(): JsonObject = buildJsonObject {
        putIfNotBlank("id", id)
        putIfNotBlank("content", content)
        put("done", done)
        if (remindTime > 0L) put("remindTime", remindTime)
        if (modifyTime > 0L) put("modifyTime", modifyTime)
    }

    companion object {
        fun fromJson(obj: JsonObject): MiCloudTodoItem = MiCloudTodoItem(
            id = obj.stringOrNull("id").orEmpty(),
            content = obj.stringOrNull("content").orEmpty(),
            done = obj.boolOrNull("done") ?: false,
            remindTime = obj.longOrNull("remindTime") ?: 0L,
            modifyTime = obj.longOrNull("modifyTime") ?: 0L,
        )
    }
}

internal data class MiCloudFileItem(
    val fileId: String = "",
    val name: String = "",
    val sizeBytes: Long = 0L,
    val timeMs: Long = 0L,
    val durationSeconds: Long = 0L,
) {
    fun toJson(): JsonObject = buildJsonObject {
        putIfNotBlank("fileId", fileId)
        putIfNotBlank("name", name)
        if (sizeBytes > 0L) put("sizeBytes", sizeBytes)
        if (timeMs > 0L) put("timeMs", timeMs)
        if (durationSeconds > 0L) put("durationSeconds", durationSeconds)
    }

    companion object {
        fun fromJson(obj: JsonObject): MiCloudFileItem = MiCloudFileItem(
            fileId = obj.stringOrNull("fileId").orEmpty(),
            name = obj.stringOrNull("name").orEmpty(),
            sizeBytes = obj.longOrNull("sizeBytes") ?: 0L,
            timeMs = obj.longOrNull("timeMs") ?: 0L,
            durationSeconds = obj.longOrNull("durationSeconds") ?: 0L,
        )
    }
}

internal data class MiCloudFileCardData(
    val mode: String = FILES_MODE_LIST,
    val files: List<MiCloudFileItem> = emptyList(),
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("mode", mode)
        putJsonArray("files") { files.forEach { add(it.toJson()) } }
    }

    companion object {
        fun fromJson(obj: JsonObject): MiCloudFileCardData = MiCloudFileCardData(
            mode = obj.stringOrNull("mode") ?: FILES_MODE_LIST,
            files = obj.arrayOrNull("files").orEmpty()
                .mapNotNull { it as? JsonObject }
                .map(MiCloudFileItem::fromJson),
        )
    }
}

internal data class MiCloudQuotaSummary(
    val totalBytes: Long?,
    val usedBytes: Long?,
    val freeBytes: Long?,
) {
    val percent: Int?
        get() {
            val total = totalBytes ?: return null
            val used = usedBytes ?: return null
            if (total <= 0L) return null
            return ((used.toDouble() / total.toDouble()) * 100.0).toInt().coerceIn(0, 100)
        }
}

internal fun quotaSummary(entries: List<MiCloudQuotaEntry>): MiCloudQuotaSummary? {
    if (entries.isEmpty()) return null
    val totals = entries.mapNotNull { it.totalBytes }
    val usedValues = entries.mapNotNull { it.usedBytes }
    val total = if (totals.isEmpty()) null else totals.sum()
    val used = if (usedValues.isEmpty()) null else usedValues.sum()
    if (total == null && used == null) return null
    val free = if (total != null && used != null) (total - used).coerceAtLeast(0L) else null
    return MiCloudQuotaSummary(totalBytes = total, usedBytes = used, freeBytes = free)
}

internal data class MiCloudTodoCardData(
    val action: String = TODO_ACTION_LIST,
    val total: Int = 0,
    val doneCount: Int = 0,
    val todoId: String = "",
    val content: String = "",
    val done: Boolean = false,
    val remindTime: Long = 0L,
    val modifyTime: Long = 0L,
    val cursor: String = "",
    val hasMore: Boolean = false,
    val rows: List<MiCloudTodoItem> = emptyList(),
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("action", action)
        put("total", total)
        put("doneCount", doneCount)
        putIfNotBlank("todoId", todoId)
        putIfNotBlank("content", content)
        put("done", done)
        if (remindTime > 0L) put("remindTime", remindTime)
        if (modifyTime > 0L) put("modifyTime", modifyTime)
        putIfNotBlank("cursor", cursor)
        if (hasMore) put("hasMore", true)
        putJsonArray("rows") { rows.forEach { add(it.toJson()) } }
    }

    companion object {
        fun fromJson(obj: JsonObject): MiCloudTodoCardData = MiCloudTodoCardData(
            action = obj.stringOrNull("action") ?: TODO_ACTION_LIST,
            total = obj.intOrNull("total") ?: 0,
            doneCount = obj.intOrNull("doneCount") ?: 0,
            todoId = obj.stringOrNull("todoId").orEmpty(),
            content = obj.stringOrNull("content").orEmpty(),
            done = obj.boolOrNull("done") ?: false,
            remindTime = obj.longOrNull("remindTime") ?: 0L,
            modifyTime = obj.longOrNull("modifyTime") ?: 0L,
            cursor = obj.stringOrNull("cursor").orEmpty(),
            hasMore = obj.boolOrNull("hasMore") ?: false,
            rows = obj.arrayOrNull("rows").orEmpty()
                .mapNotNull { it as? JsonObject }
                .map(MiCloudTodoItem::fromJson),
        )
    }
}

internal fun todoContentObject(item: JsonObject): JsonObject? = when (val raw = item["contentJson"]) {
    is JsonObject -> raw
    is JsonArray -> raw.firstOrNull() as? JsonObject
    is JsonPrimitive -> raw.contentOrNull
        ?.takeIf { it.isNotBlank() }
        ?.let { encoded -> runCatching { Json.parseToJsonElement(encoded) }.getOrNull() }
        ?.let { parsed -> parsed as? JsonObject ?: (parsed as? JsonArray)?.firstOrNull() as? JsonObject }

    else -> null
}

internal fun parseQuotaEntries(raw: JsonElement): List<MiCloudQuotaEntry> {
    val data = miCloudResponseData(raw) ?: return emptyList()
    val named = mutableListOf<MiCloudQuotaEntry>()
    collectNamedQuotaBuckets(data, named, depth = 0)
    if (named.isNotEmpty()) return named.distinctBy(::quotaIdentity)
    val generic = mutableListOf<MiCloudQuotaEntry>()
    collectQuotaBuckets(data, generic, depth = 0)
    return generic.distinctBy(::quotaIdentity)
}

private val QUOTA_NAMES = setOf("gift", "firstlevel")

private fun quotaIdentity(entry: MiCloudQuotaEntry): String =
    "${entry.label.lowercase()}|${entry.totalBytes}|${entry.usedBytes}|${entry.freeBytes}"

private fun collectNamedQuotaBuckets(
    element: JsonElement,
    out: MutableList<MiCloudQuotaEntry>,
    depth: Int,
) {
    if (depth > QUOTA_MAX_DEPTH) return
    when (element) {
        is JsonObject -> {
            element.forEach { (key, value) ->
                if (key.trim().lowercase() in QUOTA_NAMES && value is JsonObject) {
                    value.toQuotaEntry(key.trim())?.let(out::add)
                }
                collectNamedQuotaBuckets(value, out, depth + 1)
            }
            val name = element.stringOrNull("name")
                ?: element.stringOrNull("type")
                ?: element.stringOrNull("label")
            if (name != null && name.trim().lowercase() in QUOTA_NAMES) {
                element.toQuotaEntry(name.trim())?.let(out::add)
            }
        }

        is JsonArray -> element.forEach { item -> collectNamedQuotaBuckets(item, out, depth + 1) }
        else -> Unit
    }
}

private fun collectQuotaBuckets(
    element: JsonElement,
    out: MutableList<MiCloudQuotaEntry>,
    depth: Int,
) {
    if (depth > QUOTA_MAX_DEPTH) return
    when (element) {
        is JsonObject -> {
            element.toQuotaEntry(quotaLabelOf(element))?.let(out::add)
            element.forEach { (_, value) -> collectQuotaBuckets(value, out, depth + 1) }
        }

        is JsonArray -> element.forEach { item -> collectQuotaBuckets(item, out, depth + 1) }
        else -> Unit
    }
}

private fun quotaLabelOf(obj: JsonObject): String =
    obj.stringOrNull("name") ?: obj.stringOrNull("type") ?: obj.stringOrNull("label").orEmpty()

private fun JsonObject.toQuotaEntry(label: String): MiCloudQuotaEntry? {
    val source = capacitySource()
    val total = source.capacity("total", "totalSize", "totalBytes", "size", "capacity", "quota", "totalQuota")
    val used = source.capacity("used", "usedSize", "usedBytes", "usedQuota", "usage", "occupied")
    val free = source.capacity("free", "freeSize", "freeBytes", "available", "availableSize", "remain", "remaining", "left")
        ?: if (total != null && used != null) (total - used).coerceAtLeast(0L) else null
    if (total == null && used == null && free == null) return null
    return MiCloudQuotaEntry(label = label, totalBytes = total, usedBytes = used, freeBytes = free)
}

private fun JsonObject.capacitySource(): JsonObject {
    for (key in CAPACITY_CONTAINER_KEYS) {
        val nested = this[key] as? JsonObject ?: continue
        if (nested.hasCapacity()) return nested
    }
    return this
}

private fun JsonObject.hasCapacity(): Boolean =
    capacity("total", "totalSize", "totalBytes", "size", "capacity", "quota", "totalQuota") != null ||
        capacity("used", "usedSize", "usedBytes", "usedQuota", "usage", "occupied") != null

private fun JsonObject.capacity(vararg keys: String): Long? {
    for (key in keys) {
        val value = this[key] as? JsonPrimitive ?: continue
        value.contentOrNull?.toDoubleOrNull()?.let { return it.toLong() }
    }
    return null
}

private val CAPACITY_CONTAINER_KEYS = listOf("quota", "storage", "capacity", "space", "usage")

private const val QUOTA_MAX_DEPTH = 6

internal fun recordingDurationSeconds(raw: Long?): Long = when {
    raw == null || raw <= 0L -> 0L
    raw > MAX_PLAUSIBLE_RECORDING_SECONDS -> raw / 1000L
    else -> raw
}

private const val MAX_PLAUSIBLE_RECORDING_SECONDS = 24L * 60L * 60L

internal fun noteItems(entries: JsonArray): List<MiCloudNoteItem> = entries
    .mapNotNull { it as? JsonObject }
    .map(::noteItem)

internal fun noteItem(item: JsonObject): MiCloudNoteItem = MiCloudNoteItem(
    id = item.stringOrNull("id").orEmpty(),
    subject = item.stringOrNull("subject").orEmpty(),
    snippet = notePlainText(item.stringOrNull("snippet")).ifBlank {
        notePlainText(item.stringOrNull("content"))
    },
    modifyTime = item.longOrNull("modifyDate") ?: item.longOrNull("modifyTime") ?: 0L,
)

internal fun todoItems(records: JsonArray): List<MiCloudTodoItem> = records
    .mapNotNull { it as? JsonObject }
    .map(::todoItem)

internal fun todoItem(item: JsonObject): MiCloudTodoItem {
    val content = todoContentObject(item)
    val id = item.stringOrNull("id")?.takeIf { it.isNotBlank() && it != "0" }
        ?: content?.stringOrNull("id")?.takeIf { it.isNotBlank() && it != "0" }
        ?: ""
    val done = content?.boolOrNull("is_finish")
        ?: ((content?.intOrNull("isFinish") ?: 0) != 0)
    return MiCloudTodoItem(
        id = id,
        content = content?.let(::todoContentText).orEmpty(),
        done = done,
        remindTime = content?.longOrNull("remindTime") ?: 0L,
        modifyTime = content?.longOrNull("lastModifiedTime") ?: 0L,
    )
}

private fun todoContentText(content: JsonObject): String {
    content.stringOrNull("content")?.takeIf { it.isNotBlank() }?.let { return it }
    content.stringOrNull("plainText")?.takeIf { it.isNotBlank() }?.let { return it }
    content.stringOrNull("text")?.takeIf { it.isNotBlank() }?.let { return it }
    return when (val nested = content["content"]) {
        is JsonObject -> nested.stringOrNull("text") ?: nested.stringOrNull("content").orEmpty()
        is JsonArray -> nested.mapNotNull { part ->
            (part as? JsonObject)?.stringOrNull("text") ?: (part as? JsonPrimitive)?.contentOrNull
        }.joinToString("").trim()

        else -> ""
    }
}

internal fun JsonObjectBuilder.putIfNotBlank(key: String, value: String) {
    if (value.isNotBlank()) put(key, value)
}
