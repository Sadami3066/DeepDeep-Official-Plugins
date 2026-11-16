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
import java.util.Locale

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

internal const val FILES_MODE_LIST = "list"

internal const val FILES_MODE_DOWNLOAD = "download"

internal const val GALLERY_MODE_LIST = "list"

internal const val GALLERY_MODE_ALBUMS = "albums"

internal data class MiCloudQuotaEntry(
    val label: String = "",
    val totalBytes: Long? = null,
    val usedBytes: Long? = null,
    val freeBytes: Long? = null,
    val expireTime: Long? = null,
    val source: JsonObject? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        putIfNotBlank("label", label)
        totalBytes?.let { put("totalBytes", it) }
        usedBytes?.let { put("usedBytes", it) }
        freeBytes?.let { put("freeBytes", it) }
        expireTime?.let { put("expireTime", it) }
        source?.let { put("source", it) }
    }

    companion object {
        fun fromJson(obj: JsonObject): MiCloudQuotaEntry = MiCloudQuotaEntry(
            label = obj.stringOrNull("label").orEmpty(),
            totalBytes = obj.longOrNull("totalBytes"),
            usedBytes = obj.longOrNull("usedBytes"),
            freeBytes = obj.longOrNull("freeBytes"),
            expireTime = obj.longOrNull("expireTime"),
            source = obj.objectOrNull("source"),
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
    val data: JsonObject? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("mode", mode)
        putIfNotBlank("nickname", nickname)
        putIfNotBlank("userId", userId)
        putIfNotBlank("email", email)
        putIfNotBlank("phone", phone)
        putJsonArray("entries") { entries.forEach { add(it.toJson()) } }
        data?.let { put("data", it) }
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
            data = obj.objectOrNull("data"),
        )
    }
}

internal data class MiCloudNoteItem(
    val id: String = "",
    val subject: String = "",
    val snippet: String = "",
    val modifyTime: Long = 0L,
    val source: JsonObject? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        putIfNotBlank("id", id)
        putIfNotBlank("subject", subject)
        putIfNotBlank("snippet", snippet)
        if (modifyTime > 0L) put("modifyTime", modifyTime)
        source?.let { put("source", it) }
    }

    companion object {
        fun fromJson(obj: JsonObject): MiCloudNoteItem = MiCloudNoteItem(
            id = obj.stringOrNull("id").orEmpty(),
            subject = obj.stringOrNull("subject").orEmpty(),
            snippet = obj.stringOrNull("snippet").orEmpty(),
            modifyTime = obj.longOrNull("modifyTime") ?: 0L,
            source = obj.objectOrNull("source"),
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
    val response: JsonObject? = null,
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
        response?.let { put("response", it) }
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
            response = obj.objectOrNull("response"),
        )
    }
}

internal data class MiCloudTodoItem(
    val id: String = "",
    val content: String = "",
    val done: Boolean = false,
    val remindTime: Long = 0L,
    val modifyTime: Long = 0L,
    val duplicateIds: List<String> = emptyList(),
    val source: JsonObject? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        putIfNotBlank("id", id)
        putIfNotBlank("content", content)
        put("done", done)
        if (remindTime > 0L) put("remindTime", remindTime)
        if (modifyTime > 0L) put("modifyTime", modifyTime)
        if (duplicateIds.isNotEmpty()) {
            putJsonArray("duplicateIds") { duplicateIds.forEach { add(JsonPrimitive(it)) } }
        }
        source?.let { put("source", it) }
    }

    companion object {
        fun fromJson(obj: JsonObject): MiCloudTodoItem = MiCloudTodoItem(
            id = obj.stringOrNull("id").orEmpty(),
            content = obj.stringOrNull("content").orEmpty(),
            done = obj.boolOrNull("done") ?: false,
            remindTime = obj.longOrNull("remindTime") ?: 0L,
            modifyTime = obj.longOrNull("modifyTime") ?: 0L,
            duplicateIds = obj.arrayOrNull("duplicateIds").orEmpty()
                .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) },
            source = obj.objectOrNull("source"),
        )
    }
}

internal data class MiCloudFileItem(
    val fileId: String = "",
    val name: String = "",
    val sizeBytes: Long = 0L,
    val timeMs: Long = 0L,
    val durationSeconds: Long = 0L,
    val path: String = "",
    val source: JsonObject? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        putIfNotBlank("fileId", fileId)
        putIfNotBlank("name", name)
        if (sizeBytes > 0L) put("sizeBytes", sizeBytes)
        if (timeMs > 0L) put("timeMs", timeMs)
        if (durationSeconds > 0L) put("durationSeconds", durationSeconds)
        putIfNotBlank("path", path)
        source?.let { put("source", it) }
    }

    companion object {
        fun fromJson(obj: JsonObject): MiCloudFileItem = MiCloudFileItem(
            fileId = obj.stringOrNull("fileId").orEmpty(),
            name = obj.stringOrNull("name").orEmpty(),
            sizeBytes = obj.longOrNull("sizeBytes") ?: 0L,
            timeMs = obj.longOrNull("timeMs") ?: 0L,
            durationSeconds = obj.longOrNull("durationSeconds") ?: 0L,
            path = obj.stringOrNull("path").orEmpty(),
            source = obj.objectOrNull("source"),
        )
    }
}

internal data class MiCloudFileCardData(
    val mode: String = FILES_MODE_LIST,
    val files: List<MiCloudFileItem> = emptyList(),
    val offset: Int = 0,
    val nextOffset: Int = 0,
    val limit: Int = 0,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("mode", mode)
        putJsonArray("files") { files.forEach { add(it.toJson()) } }
        if (offset > 0) put("offset", offset)
        if (nextOffset > 0) put("nextOffset", nextOffset)
        if (limit > 0) put("limit", limit)
    }

    companion object {
        fun fromJson(obj: JsonObject): MiCloudFileCardData = MiCloudFileCardData(
            mode = obj.stringOrNull("mode") ?: FILES_MODE_LIST,
            files = obj.arrayOrNull("files").orEmpty()
                .mapNotNull { it as? JsonObject }
                .map(MiCloudFileItem::fromJson),
            offset = obj.intOrNull("offset") ?: 0,
            nextOffset = obj.intOrNull("nextOffset") ?: 0,
            limit = obj.intOrNull("limit") ?: 0,
        )
    }
}

internal data class MiCloudSmsItem(
    val id: String = "",
    val threadId: String = "",
    val number: String = "",
    val contactName: String = "",
    val snippet: String = "",
    val timeMs: Long = 0L,
    val unread: Int = 0,
    val total: Int = 0,
    val folder: Int? = null,
    val tag: String = "",
    val operation: String = "",
    val source: JsonObject? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        putIfNotBlank("id", id)
        putIfNotBlank("threadId", threadId)
        putIfNotBlank("number", number)
        putIfNotBlank("contactName", contactName)
        putIfNotBlank("snippet", snippet)
        if (timeMs > 0L) put("timeMs", timeMs)
        if (unread > 0) put("unread", unread)
        if (total > 0) put("total", total)
        folder?.let { put("folder", it) }
        putIfNotBlank("tag", tag)
        putIfNotBlank("operation", operation)
        source?.let { put("source", it) }
    }

    companion object {
        fun fromJson(obj: JsonObject): MiCloudSmsItem = MiCloudSmsItem(
            id = obj.stringOrNull("id").orEmpty(),
            threadId = obj.stringOrNull("threadId").orEmpty(),
            number = obj.stringOrNull("number").orEmpty(),
            contactName = obj.stringOrNull("contactName").orEmpty(),
            snippet = obj.stringOrNull("snippet").orEmpty(),
            timeMs = obj.longOrNull("timeMs") ?: 0L,
            unread = obj.intOrNull("unread") ?: 0,
            total = obj.intOrNull("total") ?: 0,
            folder = obj.intOrNull("folder"),
            tag = obj.stringOrNull("tag").orEmpty(),
            operation = obj.stringOrNull("operation").orEmpty(),
            source = obj.objectOrNull("source"),
        )
    }
}

internal data class MiCloudCallItem(
    val id: String = "",
    val number: String = "",
    val timeMs: Long = 0L,
    val type: String = "",
    val durationSeconds: Long = 0L,
    val source: JsonObject? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        putIfNotBlank("id", id)
        putIfNotBlank("number", number)
        if (timeMs > 0L) put("timeMs", timeMs)
        putIfNotBlank("type", type)
        if (durationSeconds > 0L) put("durationSeconds", durationSeconds)
        source?.let { put("source", it) }
    }
}

internal data class MiCloudSmsCardData(
    val entries: List<MiCloudSmsItem> = emptyList(),
    val calls: List<MiCloudCallItem> = emptyList(),
    val nextSyncTag: String = "",
    val nextSyncThreadTag: String = "",
    val watermark: JsonObject? = null,
    val withPhoneCall: Boolean = false,
    val limit: Int = 0,
) {
    fun toJson(): JsonObject = buildJsonObject {
        putJsonArray("entries") { entries.forEach { add(it.toJson()) } }
        putJsonArray("calls") { calls.forEach { add(it.toJson()) } }
        putIfNotBlank("nextSyncTag", nextSyncTag)
        putIfNotBlank("nextSyncThreadTag", nextSyncThreadTag)
        watermark?.let { put("watermark", it) }
        if (withPhoneCall) put("withPhoneCall", true)
        if (limit > 0) put("limit", limit)
    }

    companion object {
        fun fromJson(obj: JsonObject): MiCloudSmsCardData = MiCloudSmsCardData(
            entries = obj.arrayOrNull("entries").orEmpty()
                .mapNotNull { it as? JsonObject }
                .map(MiCloudSmsItem::fromJson),
            calls = obj.arrayOrNull("calls").orEmpty()
                .mapNotNull { it as? JsonObject }
                .map { call ->
                    MiCloudCallItem(
                        id = call.stringOrNull("id").orEmpty(),
                        number = call.stringOrNull("number").orEmpty(),
                        timeMs = call.longOrNull("timeMs") ?: 0L,
                        type = call.stringOrNull("type").orEmpty(),
                        durationSeconds = call.longOrNull("durationSeconds") ?: 0L,
                        source = call.objectOrNull("source"),
                    )
                },
            nextSyncTag = obj.stringOrNull("nextSyncTag").orEmpty(),
            nextSyncThreadTag = obj.stringOrNull("nextSyncThreadTag").orEmpty(),
            watermark = obj.objectOrNull("watermark"),
            withPhoneCall = obj.boolOrNull("withPhoneCall") ?: false,
            limit = obj.intOrNull("limit") ?: 0,
        )
    }
}

internal data class MiCloudContactItem(
    val id: String = "",
    val name: String = "",
    val pinyin: String = "",
    val spinyin: String = "",
    val phones: List<String> = emptyList(),
    val emails: List<String> = emptyList(),
    val organization: String = "",
    val starred: Boolean = false,
    val source: JsonObject? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        putIfNotBlank("id", id)
        putIfNotBlank("name", name)
        putIfNotBlank("pinyin", pinyin)
        putIfNotBlank("spinyin", spinyin)
        putJsonArray("phones") { phones.forEach { add(JsonPrimitive(it)) } }
        putJsonArray("emails") { emails.forEach { add(JsonPrimitive(it)) } }
        putIfNotBlank("organization", organization)
        if (starred) put("starred", true)
        source?.let { put("source", it) }
    }
}

internal data class MiCloudContactsCardData(
    val contacts: List<MiCloudContactItem> = emptyList(),
    val groups: JsonArray = JsonArray(emptyList()),
) {
    fun toJson(): JsonObject = buildJsonObject {
        putJsonArray("contacts") { contacts.forEach { add(it.toJson()) } }
        put("groups", groups)
    }

    companion object {
        fun fromJson(obj: JsonObject): MiCloudContactsCardData = MiCloudContactsCardData(
            contacts = obj.arrayOrNull("contacts").orEmpty()
                .mapNotNull { it as? JsonObject }
                .map { contact ->
                    MiCloudContactItem(
                        id = contact.stringOrNull("id").orEmpty(),
                        name = contact.stringOrNull("name").orEmpty(),
                        pinyin = contact.stringOrNull("pinyin").orEmpty(),
                        spinyin = contact.stringOrNull("spinyin").orEmpty(),
                        phones = contact.arrayOrNull("phones").orEmpty()
                            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf { value -> value.isNotBlank() } },
                        emails = contact.arrayOrNull("emails").orEmpty()
                            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf { value -> value.isNotBlank() } },
                        organization = contact.stringOrNull("organization").orEmpty(),
                        starred = contact.boolOrNull("starred") ?: false,
                        source = contact.objectOrNull("source"),
                    )
                },
            groups = obj.arrayOrNull("groups") ?: JsonArray(emptyList()),
        )
    }
}

internal data class MiCloudGalleryItem(
    val id: String = "",
    val name: String = "",
    val itemCount: Int = 0,
    val thumbUrl: String = "",
    val timeMs: Long = 0L,
    val sizeBytes: Long = 0L,
    val mimeType: String = "",
    val source: JsonObject? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        putIfNotBlank("id", id)
        putIfNotBlank("name", name)
        if (itemCount > 0) put("itemCount", itemCount)
        putIfNotBlank("thumbUrl", thumbUrl)
        if (timeMs > 0L) put("timeMs", timeMs)
        if (sizeBytes > 0L) put("sizeBytes", sizeBytes)
        putIfNotBlank("mimeType", mimeType)
        source?.let { put("source", it) }
    }

    companion object {
        fun fromJson(obj: JsonObject): MiCloudGalleryItem = MiCloudGalleryItem(
            id = obj.stringOrNull("id").orEmpty(),
            name = obj.stringOrNull("name").orEmpty(),
            itemCount = obj.intOrNull("itemCount") ?: 0,
            thumbUrl = obj.stringOrNull("thumbUrl").orEmpty(),
            timeMs = obj.longOrNull("timeMs") ?: 0L,
            sizeBytes = obj.longOrNull("sizeBytes") ?: 0L,
            mimeType = obj.stringOrNull("mimeType").orEmpty(),
            source = obj.objectOrNull("source"),
        )
    }
}

internal data class MiCloudGalleryCardData(
    val mode: String = GALLERY_MODE_LIST,
    val items: List<MiCloudGalleryItem> = emptyList(),
    val hasMore: Boolean = false,
    val isLastPage: Boolean? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("mode", mode)
        putJsonArray("items") { items.forEach { add(it.toJson()) } }
        if (hasMore) put("hasMore", true)
        isLastPage?.let { put("isLastPage", it) }
    }

    companion object {
        fun fromJson(obj: JsonObject): MiCloudGalleryCardData = MiCloudGalleryCardData(
            mode = obj.stringOrNull("mode") ?: GALLERY_MODE_LIST,
            items = obj.arrayOrNull("items").orEmpty()
                .mapNotNull { it as? JsonObject }
                .map(MiCloudGalleryItem::fromJson),
            hasMore = obj.boolOrNull("hasMore") ?: false,
            isLastPage = obj.boolOrNull("isLastPage"),
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
    val duplicateCount: Int = 0,
    val todoId: String = "",
    val content: String = "",
    val done: Boolean = false,
    val remindTime: Long = 0L,
    val modifyTime: Long = 0L,
    val rows: List<MiCloudTodoItem> = emptyList(),
    val response: JsonObject? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("action", action)
        put("total", total)
        put("doneCount", doneCount)
        if (duplicateCount > 0) put("duplicateCount", duplicateCount)
        putIfNotBlank("todoId", todoId)
        putIfNotBlank("content", content)
        put("done", done)
        if (remindTime > 0L) put("remindTime", remindTime)
        if (modifyTime > 0L) put("modifyTime", modifyTime)
        putJsonArray("rows") { rows.forEach { add(it.toJson()) } }
        response?.let { put("response", it) }
    }

    companion object {
        fun fromJson(obj: JsonObject): MiCloudTodoCardData = MiCloudTodoCardData(
            action = obj.stringOrNull("action") ?: TODO_ACTION_LIST,
            total = obj.intOrNull("total") ?: 0,
            doneCount = obj.intOrNull("doneCount") ?: 0,
            duplicateCount = obj.intOrNull("duplicateCount") ?: 0,
            todoId = obj.stringOrNull("todoId").orEmpty(),
            content = obj.stringOrNull("content").orEmpty(),
            done = obj.boolOrNull("done") ?: false,
            remindTime = obj.longOrNull("remindTime") ?: 0L,
            modifyTime = obj.longOrNull("modifyTime") ?: 0L,
            rows = obj.arrayOrNull("rows").orEmpty()
                .mapNotNull { it as? JsonObject }
                .map(MiCloudTodoItem::fromJson),
            response = obj.objectOrNull("response"),
        )
    }
}

internal fun todoContentObject(item: JsonObject): JsonObject? {
    val container = when (val raw = item["contentJson"]) {
        is JsonObject -> raw
        is JsonArray -> raw.firstOrNull() as? JsonObject
        is JsonPrimitive -> raw.contentOrNull
            ?.takeIf { it.isNotBlank() }
            ?.let { encoded -> runCatching { Json.parseToJsonElement(encoded) }.getOrNull() }
            ?.let { parsed -> parsed as? JsonObject ?: (parsed as? JsonArray)?.firstOrNull() as? JsonObject }

        else -> null
    } ?: return null
    (container["entity"] as? JsonObject)?.let { return it }
    (container["assets"] as? JsonArray)?.forEach { asset ->
        (asset as? JsonObject)?.let { nested ->
            when (val entity = nested["entity"]) {
                is JsonObject -> return entity
                else -> Unit
            }
        }
    }
    return container
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
    "${entry.label.lowercase()}|${entry.totalBytes}|${entry.usedBytes}|${entry.freeBytes}|${entry.expireTime}"

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

private fun quotaLabelOf(obj: JsonObject): String {
    obj.stringOrNull("name")?.takeIf { it.isNotBlank() }?.let { return it }
    obj.stringOrNull("type")?.takeIf { it.isNotBlank() }?.let { return it }
    obj.stringOrNull("label")?.takeIf { it.isNotBlank() }?.let { return it }
    when (obj.stringOrNull("category")?.trim()?.lowercase(Locale.ROOT)) {
        "gift" -> return "gift"
        "buy", "paid" -> return "paid"
    }
    when (obj.stringOrNull("level")?.trim()?.lowercase(Locale.ROOT)) {
        "gift" -> return "gift"
        "firstlevel" -> return "paid"
    }
    return ""
}

private fun JsonObject.toQuotaEntry(label: String): MiCloudQuotaEntry? {
    val source = capacitySource()
    val total = source.capacity("total", "totalSize", "totalBytes", "size", "capacity", "quota", "totalQuota")
    val used = source.capacity("used", "usedSize", "usedBytes", "usedQuota", "usage", "occupied")
    val free = source.capacity("free", "freeSize", "freeBytes", "available", "availableSize", "remain", "remaining", "left")
        ?: if (total != null && used != null) (total - used).coerceAtLeast(0L) else null
    if (total == null && used == null && free == null) return null
    return MiCloudQuotaEntry(
        label = label,
        totalBytes = total,
        usedBytes = used,
        freeBytes = free,
        expireTime = this.epochMillis("expireTime", "expire_time", "effectiveEndTime", "endTime", "end_time"),
        source = this,
    )
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

private fun JsonObject.epochMillis(vararg keys: String): Long? {
    for (key in keys) {
        val value = this[key] as? JsonPrimitive ?: continue
        val number = value.contentOrNull?.trim()?.toDoubleOrNull() ?: continue
        if (number <= 0.0) continue
        val millis = number.toLong()
        return if (millis < 1_000_000_000_000L) millis * 1000L else millis
    }
    return null
}

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

internal fun noteItem(item: JsonObject): MiCloudNoteItem {
    val rawSnippet = notePlainText(item.stringOrNull("snippet")).ifBlank {
        notePlainText(item.stringOrNull("content"))
    }
    val explicit = item.stringOrNull("subject").orEmpty().ifBlank { noteExtraInfoTitle(item) }
    val (subject, snippet) = splitNoteTitle(explicit, rawSnippet)
    return MiCloudNoteItem(
        id = item.stringOrNull("id").orEmpty(),
        subject = subject,
        snippet = snippet,
        modifyTime = item.longOrNull("modifyDate") ?: item.longOrNull("modifyTime") ?: 0L,
        source = item,
    )
}

private fun noteExtraInfoTitle(item: JsonObject): String {
    val raw = (item["extraInfo"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: return ""
    val parsed = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return ""
    return parsed.stringOrNull("title").orEmpty().trim()
}

private fun splitNoteTitle(explicit: String, rawSnippet: String): Pair<String, String> {
    val title = explicit.trim().takeIf { it.isNotBlank() } ?: run {
        val first = rawSnippet.lineSequence().firstOrNull()?.trim().orEmpty()
        first.take(NOTE_TITLE_MAX_CHARS)
    }
    if (explicit.isNotBlank()) return title to rawSnippet
    val lines = rawSnippet.lineSequence().toList()
    val rest = if (lines.isNotEmpty()) {
        lines.drop(1).joinToString("\n").trim()
    } else {
        ""
    }
    return title to rest
}

private const val NOTE_TITLE_MAX_CHARS = 40

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
        remindTime = content?.longOrNull("remindTime") ?: content?.longOrNull("firstRemindTime") ?: 0L,
        modifyTime = content?.longOrNull("lastModifiedTime") ?: content?.longOrNull("modifyTime") ?: 0L,
        source = item,
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

internal fun todoContentKey(item: MiCloudTodoItem): String =
    item.content.trim().lowercase(Locale.ROOT)

internal fun galleryAlbumLabel(rawName: String, fallback: String): String =
    rawName.trim().ifBlank { fallback }

internal fun recordingDisplayName(rawName: String): String {
    val trimmed = rawName.trim()
    if (trimmed.isEmpty()) return trimmed
    val cleaned = trimmed.replace(RECORDING_SUFFIX, "").trim()
    return cleaned.ifEmpty { trimmed }
}

private val RECORDING_SUFFIX = Regex("""(?:_\d+)+$""")

internal fun contactGroupTitles(groups: JsonArray): List<String> = groups.mapNotNull { element ->
    val group = element as? JsonObject ?: return@mapNotNull null
    val content = group["content"] as? JsonObject
    content?.stringOrNull("title")?.takeIf { it.isNotBlank() }
        ?: group.stringOrNull("title")?.takeIf { it.isNotBlank() }
}

internal fun smsItems(entries: JsonArray): List<MiCloudSmsItem> = entries
    .mapNotNull { it as? JsonObject }
    .map(::smsItem)

internal fun smsItem(item: JsonObject): MiCloudSmsItem {
    val operation = item.stringOrNull("operation").orEmpty()
    val entry = item.objectOrNull("entry") ?: item
    return MiCloudSmsItem(
        id = entry.stringOrNull("id").orEmpty(),
        threadId = entry.stringOrNull("threadId").orEmpty(),
        number = entry.firstString("recipients", "address", "number", "phone", "msisdn").orEmpty(),
        contactName = entry.firstString("name", "contactName").orEmpty(),
        snippet = entry.firstString("snippet", "body", "content", "text").orEmpty(),
        timeMs = entry.firstLong("localTime", "date", "time", "timestamp", "dateTime", "ts") ?: 0L,
        unread = entry.intOrNull("unread") ?: entry.intOrNull("unreadCount") ?: 0,
        total = entry.intOrNull("total") ?: 0,
        folder = entry.intOrNull("folder"),
        tag = entry.stringOrNull("tag").orEmpty(),
        operation = operation,
        source = entry,
    )
}

internal fun callItems(entries: JsonArray): List<MiCloudCallItem> = entries
    .mapNotNull { it as? JsonObject }
    .map { call ->
        MiCloudCallItem(
            id = call.firstString("id", "bindId").orEmpty(),
            number = call.firstString("number", "address", "phone").orEmpty(),
            timeMs = call.firstLong("date", "localTime", "time", "timestamp") ?: 0L,
            type = call.stringOrNull("type").orEmpty(),
            durationSeconds = recordingDurationSeconds(call.longOrNull("duration")),
            source = call,
        )
    }

internal fun contactItems(element: JsonElement): List<MiCloudContactItem> {
    val payload = element.payloadElement()
    val source = when (payload) {
        is JsonArray -> payload
        is JsonObject -> payload["content"] ?: payload["contacts"] ?: payload["list"]
        else -> null
    }
    return when (source) {
        is JsonArray -> source.mapNotNull { it as? JsonObject }.map(::contactItem)
        is JsonObject -> source.values.mapNotNull { it as? JsonObject }.map(::contactItem)
        else -> emptyList()
    }.filter { it.name.isNotBlank() || it.phones.isNotEmpty() || it.emails.isNotEmpty() }
}

internal fun contactItem(top: JsonObject): MiCloudContactItem {
    val nested = top.objectOrNull("content")
        ?.takeIf { it.containsKey("displayName") || it.containsKey("phoneNumbers") || it.containsKey("emails") }
    val card = nested ?: top
    val name = card.firstString("displayName", "fullName")
        ?: card.objectOrNull("name")?.firstString("formatted", "givenName")
        ?: top.firstString("displayName", "fullName")
        ?: (top["name"] as? JsonPrimitive)?.contentOrNull
    return MiCloudContactItem(
        id = top.stringOrNull("id") ?: card.stringOrNull("id").orEmpty(),
        name = name.orEmpty(),
        pinyin = top.firstString("pinyin").orEmpty(),
        spinyin = top.firstString("spinyin").orEmpty(),
        phones = collectValues(
            card["phoneNumbers"],
            card["phones"],
            card["phone"],
            card["number"],
            card["mobile"],
            card["phoneNumber"],
        ),
        emails = collectValues(
            card["emails"],
            card["emailAddresses"],
            card["email"],
            card["emailAddress"],
        ),
        organization = organizationOf(card, top),
        starred = card.boolOrNull("starred") ?: false,
        source = top,
    )
}

private fun organizationOf(card: JsonObject, top: JsonObject): String {
    (card["organizations"] as? JsonArray)?.firstNotNullOfOrNull { item ->
        (item as? JsonObject)?.firstString("company", "name", "title")
    }?.takeIf { it.isNotBlank() }?.let { return it }
    (top["organizations"] as? JsonArray)?.firstNotNullOfOrNull { item ->
        (item as? JsonObject)?.firstString("company", "name", "title")
    }?.takeIf { it.isNotBlank() }?.let { return it }
    return card.firstString("organization", "company").orEmpty()
}

private fun collectValues(vararg elements: JsonElement?): List<String> = buildList {
    elements.forEach { element ->
        when (element) {
            is JsonPrimitive -> element.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }?.let(::add)
            is JsonArray -> element.forEach { item ->
                when (item) {
                    is JsonPrimitive -> item.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }?.let(::add)
                    is JsonObject -> item.firstString(
                        "value",
                        "number",
                        "address",
                        "email",
                        "phone",
                    )?.let(::add)

                    else -> Unit
                }
            }

            else -> Unit
        }
    }
}.distinct()

internal fun galleryItems(entries: JsonArray): List<MiCloudGalleryItem> = entries
    .mapNotNull { it as? JsonObject }
    .map(::galleryItem)

internal fun galleryItem(item: JsonObject): MiCloudGalleryItem = MiCloudGalleryItem(
    id = item.firstString("albumId", "id", "fileId").orEmpty(),
    name = item.firstString("name", "title", "albumName", "fileName", "displayName").orEmpty(),
    itemCount = item.intOrNull("mediaCount")
        ?: item.intOrNull("photoCount")
        ?: item.intOrNull("count")
        ?: item.intOrNull("itemCount")
        ?: 0,
    thumbUrl = thumbnailUrl(item),
    timeMs = item.firstLong("lastUpdateTime", "createTime", "dateTaken", "updateTime", "date", "time") ?: 0L,
    sizeBytes = item.longOrNull("size") ?: 0L,
    mimeType = item.stringOrNull("mimeType").orEmpty(),
    source = item,
)

private fun thumbnailUrl(item: JsonObject): String {
    item.arrayOrNull("thumbnails")?.forEach { thumbnail ->
        (thumbnail as? JsonObject)?.firstString("url", "thumbnailUrl")?.let { return it }
    }
    item.objectOrNull("thumbnail")?.firstString("url")?.let { return it }
    return item.firstString(
        "thumbnailUrl",
        "thumbUrl",
        "coverUrl",
        "cover",
        "thumbnail",
        "url",
    ).orEmpty()
}

private fun JsonObject.firstString(vararg keys: String): String? {
    keys.forEach { key ->
        stringOrNull(key)?.takeIf { it.isNotBlank() }?.let { return it }
    }
    return null
}

private fun JsonObject.firstLong(vararg keys: String): Long? {
    keys.forEach { key ->
        longOrNull(key)?.let { return it }
    }
    return null
}

internal fun JsonObjectBuilder.putIfNotBlank(key: String, value: String) {
    if (value.isNotBlank()) put(key, value)
}
