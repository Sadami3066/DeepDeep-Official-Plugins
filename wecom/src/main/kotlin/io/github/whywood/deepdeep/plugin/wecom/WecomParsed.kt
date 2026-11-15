package io.github.whywood.deepdeep.plugin.wecom

import io.github.whywood.deepdeep.plugin.cli.boolValue
import io.github.whywood.deepdeep.plugin.cli.intValue
import io.github.whywood.deepdeep.plugin.cli.objValue
import io.github.whywood.deepdeep.plugin.cli.stringValue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal sealed interface WecomParsed {
    val count: Int
    val hasMore: Boolean
    val nextCursor: String?

    data class Groups(
        val items: List<Group>,
        override val count: Int,
        override val hasMore: Boolean,
        override val nextCursor: String?,
    ) : WecomParsed

    data class Group(
        val chatId: String?,
        val name: String?,
        val lastMessageTime: String?,
        val messageCount: Int?,
    )

    data class Messages(
        val items: List<Message>,
        override val count: Int,
        override val hasMore: Boolean,
        override val nextCursor: String?,
    ) : WecomParsed

    data class Message(
        val sendTime: String?,
        val senderName: String?,
        val senderId: String?,
        val type: String?,
        val summary: String?,
        val mediaId: String?,
    )

    data class Docs(
        val items: List<Doc>,
        override val count: Int,
        override val hasMore: Boolean,
        override val nextCursor: String?,
    ) : WecomParsed

    data class Doc(
        val docId: String?,
        val name: String?,
        val type: String?,
        val creatorName: String?,
        val createTime: String?,
        val modifyTime: String?,
        val url: String?,
    )

    data class DocContent(
        val name: String?,
        val url: String?,
        val version: Int?,
        val content: String,
    ) : WecomParsed {
        override val count: Int = 1
        override val hasMore: Boolean = false
        override val nextCursor: String? = null
    }

    data class Schedules(
        val items: List<Schedule>,
        override val count: Int,
        override val hasMore: Boolean = false,
        override val nextCursor: String? = null,
    ) : WecomParsed

    data class Schedule(
        val scheduleId: String?,
        val subject: String?,
        val beginTime: String?,
        val endTime: String?,
        val location: String?,
        val calendarName: String?,
        val attendees: List<String>,
        val meetingRoom: String?,
        val allDay: Boolean,
    )

    data class Todos(
        val items: List<Todo>,
        override val count: Int,
        override val hasMore: Boolean,
        override val nextCursor: String?,
    ) : WecomParsed

    data class Todo(
        val todoId: String?,
        val title: String?,
        val status: String?,
        val deadline: String?,
        val creator: String?,
        val followers: List<String>,
        val updateTime: String?,
        val description: String?,
    )

    data class Contacts(
        val items: List<Contact>,
        override val count: Int,
        override val hasMore: Boolean = false,
        override val nextCursor: String? = null,
    ) : WecomParsed

    data class Contact(
        val userId: String?,
        val name: String?,
        val alias: String?,
        val departments: List<String>,
        val position: String?,
        val email: String?,
    )
}

internal object WecomParsedParser {
    fun parse(kind: WecomCommandKind, payload: JsonObject): WecomParsed? = when (kind) {
        WecomCommandKind.CHAT_GROUPS -> parseGroups(payload)
        WecomCommandKind.CHAT_MESSAGES -> parseMessages(payload)
        WecomCommandKind.DOC_SEARCH -> parseDocs(payload)
        WecomCommandKind.DOC_CONTENT -> parseDocContent(payload)
        WecomCommandKind.CALENDAR_SCHEDULES -> parseSchedules(payload)
        WecomCommandKind.TODO_LIST -> parseTodos(payload)
        WecomCommandKind.CONTACT_USERS -> parseContacts(payload)
    }

    private fun parseGroups(payload: JsonObject): WecomParsed.Groups? {
        val items = payload.responseArray("chats", "chats_count", GROUPS_KEYS) ?: return null
        val groups = items.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            WecomParsed.Group(
                chatId = item.stringValue("chat_id"),
                name = item.stringValue("chat_name") ?: item.stringValue("name"),
                lastMessageTime = item.stringValue("last_msg_time"),
                messageCount = item.intValue("msg_count"),
            )
        }
        return WecomParsed.Groups(
            items = groups,
            count = payload.intValue("chats_count") ?: groups.size,
            hasMore = payload.boolValue("has_more") ?: false,
            nextCursor = payload.stringValue("next_cursor"),
        )
    }

    private fun parseMessages(payload: JsonObject): WecomParsed.Messages? {
        val items = payload.responseArray("messages", "messages_count", MESSAGES_KEYS) ?: return null
        val messages = items.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val type = item.stringValue("msg_type")
            val summary = messageSummary(item, type)
            WecomParsed.Message(
                sendTime = item.stringValue("send_time"),
                senderName = item.stringValue("user_name"),
                senderId = item.stringValue("userid"),
                type = type,
                summary = summary.first,
                mediaId = summary.second,
            )
        }
        return WecomParsed.Messages(
            items = messages,
            count = payload.intValue("messages_count") ?: messages.size,
            hasMore = payload.boolValue("has_more") ?: false,
            nextCursor = payload.stringValue("next_cursor"),
        )
    }

    private fun messageSummary(item: JsonObject, type: String?): Pair<String?, String?> = when (type) {
        "text" -> item.objValue("text")?.stringValue("content") to null
        "image" -> "[图片]" to item.objValue("image")?.stringValue("media_id")
        "voice" -> "[语音]" to item.objValue("voice")?.stringValue("media_id")
        "video" -> "[视频]" to item.objValue("video")?.stringValue("media_id")
        "file" -> {
            val file = item.objValue("file")
            val name = file?.stringValue("file_name")
            ("[文件] ${name ?: "未命名文件"}") to file?.stringValue("media_id")
        }
        "mixed" -> {
            val parts = item.objValue("mixed")?.arrayOrMissing("items").orEmpty().mapNotNull { element ->
                val entry = element as? JsonObject ?: return@mapNotNull null
                when (entry.stringValue("msg_type")) {
                    "text" -> entry.objValue("text")?.stringValue("content")
                    "image" -> "[图片]"
                    else -> null
                }
            }
            parts.filter { !it.isNullOrBlank() }.joinToString(" ").ifBlank { "[图文]" } to null
        }
        else -> null to null
    }

    private fun parseDocs(payload: JsonObject): WecomParsed.Docs? {
        val items = payload.responseArray("docs", "docs_count", DOCS_KEYS) ?: return null
        val docs = items.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            WecomParsed.Doc(
                docId = item.stringValue("docid") ?: item.stringValue("doc_id"),
                name = item.stringValue("doc_name") ?: item.stringValue("name"),
                type = item.stringValue("doc_type"),
                creatorName = item.stringValue("creator_name"),
                createTime = item.stringValue("create_time"),
                modifyTime = item.stringValue("modify_time"),
                url = item.stringValue("url"),
            )
        }
        return WecomParsed.Docs(
            items = docs,
            count = payload.intValue("docs_count") ?: docs.size,
            hasMore = payload.boolValue("has_more") ?: false,
            nextCursor = payload.stringValue("next_cursor"),
        )
    }

    private fun parseDocContent(payload: JsonObject): WecomParsed.DocContent? {
        val name = payload.stringValue("name")
        val url = payload.stringValue("url")
        val content = payload.stringValue("content")
            ?: payload.stringValue("file_path")?.let { "内容较大，已写入文件：$it" }
            ?: ""
        if (name == null && url == null && content.isBlank()) return null
        return WecomParsed.DocContent(
            name = name,
            url = url,
            version = payload.intValue("version"),
            content = content,
        )
    }

    private fun parseSchedules(payload: JsonObject): WecomParsed.Schedules? {
        val items = payload.responseArray("schedule_list", "schedule_list_count", SCHEDULES_KEYS) ?: return null
        val schedules = items.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            WecomParsed.Schedule(
                scheduleId = item.stringValue("schedule_id"),
                subject = item.stringValue("subject"),
                beginTime = item.stringValue("begin_time"),
                endTime = item.stringValue("end_time"),
                location = item.stringValue("location"),
                calendarName = item.stringValue("calendar_name"),
                attendees = item.arrayOrMissing("attendees").orEmpty().mapNotNull { attendee ->
                    val entry = attendee as? JsonObject ?: return@mapNotNull null
                    entry.stringValue("name") ?: entry.stringValue("userid")
                },
                meetingRoom = item.objValue("meeting_room")?.stringValue("meeting_room_name"),
                allDay = item.boolValue("is_all_day") ?: false,
            )
        }
        return WecomParsed.Schedules(
            items = schedules,
            count = payload.intValue("schedule_list_count") ?: schedules.size,
        )
    }

    private fun parseTodos(payload: JsonObject): WecomParsed.Todos? {
        val items = payload.responseArray("items", "items_count", TODOS_KEYS) ?: return null
        val todos = items.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            WecomParsed.Todo(
                todoId = item.stringValue("todo_id"),
                title = item.stringValue("title"),
                status = item.stringValue("status"),
                deadline = item.objValue("deadline")?.stringValue("value"),
                creator = item.objValue("creator")?.let { creator ->
                    creator.stringValue("user_name") ?: creator.stringValue("userid")
                },
                followers = item.arrayOrMissing("followers").orEmpty().mapNotNull { follower ->
                    val entry = follower as? JsonObject ?: return@mapNotNull null
                    entry.stringValue("user_name") ?: entry.stringValue("userid")
                },
                updateTime = item.stringValue("update_time"),
                description = item.stringValue("description"),
            )
        }
        return WecomParsed.Todos(
            items = todos,
            count = payload.intValue("items_count") ?: todos.size,
            hasMore = payload.boolValue("has_more") ?: false,
            nextCursor = payload.stringValue("next_cursor"),
        )
    }

    private fun parseContacts(payload: JsonObject): WecomParsed.Contacts? {
        val items = payload.responseArray("users", "users_count", CONTACTS_KEYS) ?: return null
        val contacts = items.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            WecomParsed.Contact(
                userId = item.stringValue("userid"),
                name = item.stringValue("name"),
                alias = item.stringValue("alias"),
                departments = item.arrayOrMissing("departments").orEmpty().mapNotNull { department ->
                    (department as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                },
                position = item.stringValue("position"),
                email = item.stringValue("email"),
            )
        }
        return WecomParsed.Contacts(
            items = contacts,
            count = payload.intValue("users_count") ?: contacts.size,
        )
    }

    private fun JsonObject.responseArray(key: String, countKey: String, allowedKeys: Set<String>): JsonArray? {
        return when (val value = this[key]) {
            is JsonArray -> value
            null -> {
                if (!keys.all { it in allowedKeys }) return null
                val count = intValue(countKey)
                val hasMore = boolValue("has_more")
                if (keys.isEmpty() || count == 0 || hasMore != null) JsonArray(emptyList()) else null
            }
            else -> null
        }
    }

    private fun JsonObject.arrayOrMissing(key: String): JsonArray? = when (val value = this[key]) {
        null -> JsonArray(emptyList())
        is JsonArray -> value
        else -> null
    }

    private val COMMON_KEYS = setOf("errcode", "errmsg", "security_notice")
    private val GROUPS_KEYS = COMMON_KEYS + setOf("chats", "chats_count", "has_more", "next_cursor")
    private val MESSAGES_KEYS = COMMON_KEYS + setOf("messages", "messages_count", "has_more", "next_cursor")
    private val DOCS_KEYS = COMMON_KEYS + setOf("docs", "docs_count", "has_more", "next_cursor")
    private val SCHEDULES_KEYS = COMMON_KEYS + setOf("schedule_list", "schedule_list_count")
    private val TODOS_KEYS = COMMON_KEYS + setOf("items", "items_count", "has_more", "next_cursor")
    private val CONTACTS_KEYS = COMMON_KEYS + setOf("users", "users_count", "hint")
}
