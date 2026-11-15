package io.github.whywood.deepdeep.plugin.wecom

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

internal enum class WecomCardState(val wire: String) {
    READY("ready"),
    EMPTY("empty"),
    ERROR("error"),
    LOADING("loading"),
    ;

    companion object {
        fun fromWire(value: String?): WecomCardState? = entries.firstOrNull { it.wire == value }
    }
}

internal data class WecomCardRow(
    val title: String,
    val subtitle: String? = null,
    val value: String? = null,
    val valueKind: String? = null,
    val url: String? = null,
)

internal data class WecomCardPayload(
    val state: WecomCardState,
    val count: Int,
    val headerDetail: String? = null,
    val headerTrailing: String? = null,
    val context: String? = null,
    val note: String? = null,
    val summary: String? = null,
    val rows: List<WecomCardRow> = emptyList(),
    val actionUrl: String? = null,
    val errorMessage: String? = null,
)

internal object WecomCardPayloads {
    const val MAX_CARD_ROWS = 8
    const val MAX_STORED_ROWS = 12

    fun build(command: WecomCommand, parsed: WecomParsed): WecomCardPayload {
        val payload = buildInternal(command, parsed)
        return if (payload.rows.size > MAX_STORED_ROWS) {
            payload.copy(rows = payload.rows.take(MAX_STORED_ROWS))
        } else {
            payload
        }
    }

    private fun buildInternal(command: WecomCommand, parsed: WecomParsed): WecomCardPayload = when (parsed) {
        is WecomParsed.Groups -> WecomCardPayload(
            state = if (parsed.items.isEmpty()) WecomCardState.EMPTY else WecomCardState.READY,
            count = parsed.count,
            context = command.contextLabel,
            note = moreNote(parsed.hasMore, parsed.nextCursor),
            rows = parsed.items.map { item ->
                WecomCardRow(
                    title = item.name?.takeIf { it.isNotBlank() } ?: "未命名会话",
                    subtitle = listOfNotNull(
                        item.lastMessageTime?.takeIf { it.isNotBlank() }?.let { "最近消息 $it" },
                        item.chatId?.takeIf { it.isNotBlank() },
                    ).joinToString(" · ").ifBlank { null },
                    value = item.messageCount?.toString(),
                    valueKind = "count",
                )
            },
        )
        is WecomParsed.Messages -> WecomCardPayload(
            state = if (parsed.items.isEmpty()) WecomCardState.EMPTY else WecomCardState.READY,
            count = parsed.count,
            context = command.contextLabel,
            note = moreNote(parsed.hasMore, parsed.nextCursor),
            rows = parsed.items.map { item ->
                WecomCardRow(
                    title = item.senderName?.takeIf { it.isNotBlank() }
                        ?: item.senderId?.takeIf { it.isNotBlank() }
                        ?: "未知成员",
                    subtitle = listOfNotNull(
                        item.sendTime?.takeIf { it.isNotBlank() },
                        item.summary?.takeIf { it.isNotBlank() },
                    ).joinToString(" · "),
                    value = null,
                )
            },
        )
        is WecomParsed.Docs -> WecomCardPayload(
            state = if (parsed.items.isEmpty()) WecomCardState.EMPTY else WecomCardState.READY,
            count = parsed.count,
            context = command.contextLabel,
            note = moreNote(parsed.hasMore, parsed.nextCursor),
            rows = parsed.items.map { item ->
                WecomCardRow(
                    title = item.name?.takeIf { it.isNotBlank() } ?: "未命名文档",
                    subtitle = listOfNotNull(
                        item.creatorName?.takeIf { it.isNotBlank() },
                        item.modifyTime?.takeIf { it.isNotBlank() }?.let { "修改于 $it" },
                    ).joinToString(" · ").ifBlank { null },
                    value = item.type,
                    valueKind = "doc_type",
                    url = item.url?.takeIf { it.startsWith("http") },
                )
            },
        )
        is WecomParsed.DocContent -> WecomCardPayload(
            state = WecomCardState.READY,
            count = 1,
            headerDetail = parsed.name,
            headerTrailing = parsed.version?.let { "版本 $it" },
            context = command.docId,
            summary = summaryExcerpt(parsed.content),
            actionUrl = parsed.url?.takeIf { it.startsWith("http") },
            note = null,
            rows = emptyList(),
        )
        is WecomParsed.Schedules -> WecomCardPayload(
            state = if (parsed.items.isEmpty()) WecomCardState.EMPTY else WecomCardState.READY,
            count = parsed.count,
            context = command.contextLabel,
            rows = parsed.items.map { item ->
                WecomCardRow(
                    title = item.subject?.takeIf { it.isNotBlank() } ?: "未命名日程",
                    subtitle = scheduleSubtitle(item),
                    value = item.attendees.size.takeIf { it > 0 }?.let { "+$it" },
                    valueKind = "people",
                )
            },
        )
        is WecomParsed.Todos -> WecomCardPayload(
            state = if (parsed.items.isEmpty()) WecomCardState.EMPTY else WecomCardState.READY,
            count = parsed.count,
            context = command.contextLabel,
            note = moreNote(parsed.hasMore, parsed.nextCursor),
            rows = parsed.items.map { item ->
                WecomCardRow(
                    title = item.title?.takeIf { it.isNotBlank() } ?: "未命名待办",
                    subtitle = listOfNotNull(
                        item.deadline?.takeIf { it.isNotBlank() }?.let { "截止 $it" },
                        item.followers.takeIf { it.isNotEmpty() }?.joinToString("、"),
                    ).joinToString(" · ").ifBlank { null },
                    value = item.status,
                    valueKind = "todo_status",
                )
            },
        )
        is WecomParsed.Contacts -> WecomCardPayload(
            state = if (parsed.items.isEmpty()) WecomCardState.EMPTY else WecomCardState.READY,
            count = parsed.count,
            context = command.contextLabel,
            rows = parsed.items.map { item ->
                WecomCardRow(
                    title = item.name?.takeIf { it.isNotBlank() }
                        ?: item.userId?.takeIf { it.isNotBlank() }
                        ?: "未知成员",
                    subtitle = listOfNotNull(
                        item.departments.takeIf { it.isNotEmpty() }?.joinToString("/"),
                        item.position?.takeIf { it.isNotBlank() },
                    ).joinToString(" · ").ifBlank { item.userId },
                    value = item.email?.takeIf { it.isNotBlank() },
                    valueKind = "plain",
                )
            },
        )
    }

    fun encode(payload: WecomCardPayload): JsonObject = buildJsonObject {
        put("state", payload.state.wire)
        put("count", payload.count)
        payload.headerDetail?.let { put("headerDetail", it) }
        payload.headerTrailing?.let { put("headerTrailing", it) }
        payload.context?.let { put("context", it) }
        payload.note?.let { put("note", it) }
        payload.summary?.let { put("summary", it) }
        payload.actionUrl?.let { put("actionUrl", it) }
        payload.errorMessage?.let { put("errorMessage", it) }
        put("rows", buildJsonArray {
            payload.rows.forEach { row ->
                add(buildJsonObject {
                    put("title", row.title)
                    row.subtitle?.let { put("subtitle", it) }
                    row.value?.let { put("value", it) }
                    row.valueKind?.let { put("valueKind", it) }
                    row.url?.let { put("url", it) }
                })
            }
        })
    }

    fun decode(root: JsonObject): WecomCardPayload? {
        val state = WecomCardState.fromWire(root.stringOrNull("state")) ?: return null
        val count = root.intOrNull("count") ?: return null
        val rows = root.arrayOrNull("rows")?.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val title = item.stringOrNull("title") ?: return@mapNotNull null
            WecomCardRow(
                title = title,
                subtitle = item.stringOrNull("subtitle"),
                value = item.stringOrNull("value"),
                valueKind = item.stringOrNull("valueKind"),
                url = item.stringOrNull("url"),
            )
        }.orEmpty()
        return WecomCardPayload(
            state = state,
            count = count,
            headerDetail = root.stringOrNull("headerDetail"),
            headerTrailing = root.stringOrNull("headerTrailing"),
            context = root.stringOrNull("context"),
            note = root.stringOrNull("note"),
            summary = root.stringOrNull("summary"),
            rows = rows,
            actionUrl = root.stringOrNull("actionUrl"),
            errorMessage = root.stringOrNull("errorMessage"),
        )
    }

    fun errorPayload(message: String): WecomCardPayload = WecomCardPayload(
        state = WecomCardState.ERROR,
        count = 0,
        errorMessage = message,
    )

    fun emptyPayload(context: String? = null): WecomCardPayload = WecomCardPayload(
        state = WecomCardState.EMPTY,
        count = 0,
        context = context,
    )

    fun loadingPayload(context: String? = null): WecomCardPayload = WecomCardPayload(
        state = WecomCardState.LOADING,
        count = 0,
        context = context,
    )

    private fun moreNote(hasMore: Boolean, nextCursor: String?): String? = when {
        hasMore && !nextCursor.isNullOrBlank() -> "还有更多，cursor=$nextCursor"
        hasMore -> "还有更多"
        else -> null
    }

    private fun scheduleSubtitle(item: WecomParsed.Schedule): String? = listOfNotNull(
        item.beginTime?.takeIf { it.isNotBlank() }?.let { begin ->
            item.endTime?.takeIf { it.isNotBlank() }?.let { end -> "$begin - $end" } ?: begin
        },
        item.location?.takeIf { it.isNotBlank() } ?: item.meetingRoom?.takeIf { it.isNotBlank() },
    ).joinToString(" · ").ifBlank { null }

    private fun summaryExcerpt(content: String): String? {
        val normalized = content
            .replace("\r\n", "\n")
            .lines()
            .joinToString(" ") { it.trim() }
            .replace(Regex("\\s+"), " ")
            .trim()
        if (normalized.isEmpty()) return null
        return if (normalized.length > 240) normalized.take(240) + "…" else normalized
    }

    private fun JsonObject.stringOrNull(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun JsonObject.intOrNull(key: String): Int? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()

    private fun JsonObject.arrayOrNull(key: String): JsonArray? = this[key] as? JsonArray
}
