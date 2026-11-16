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
    val securityNotice: String?

    data class Groups(
        val items: List<Group>,
        override val count: Int,
        override val hasMore: Boolean,
        override val nextCursor: String?,
        override val securityNotice: String?,
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
        override val securityNotice: String?,
    ) : WecomParsed

    data class Message(
        val sendTime: String?,
        val senderName: String?,
        val senderId: String?,
        val type: String?,
        val summary: String?,
        val mediaIds: List<String>,
    )

    data class Docs(
        val items: List<Doc>,
        override val count: Int,
        override val hasMore: Boolean,
        override val nextCursor: String?,
        override val securityNotice: String?,
    ) : WecomParsed

    data class Doc(
        val docId: String?,
        val name: String?,
        val type: String?,
        val creatorName: String?,
        val creatorUserId: String?,
        val createTime: String?,
        val modifyTime: String?,
        val openTime: String?,
        val textHighlight: List<String>,
        val url: String?,
    )

    data class DocContent(
        val name: String?,
        val url: String?,
        val version: Int?,
        val errcode: Int?,
        val errmsg: String?,
        val content: String,
        val filePath: String?,
        override val securityNotice: String?,
    ) : WecomParsed {
        override val count: Int = 1
        override val hasMore: Boolean = false
        override val nextCursor: String? = null
    }

    data class Schedules(
        val items: List<Schedule>,
        override val count: Int,
        override val hasMore: Boolean,
        override val nextCursor: String?,
        override val securityNotice: String?,
    ) : WecomParsed

    data class Schedule(
        val scheduleId: String?,
        val subject: String?,
        val beginTime: String?,
        val endTime: String?,
        val location: String?,
        val creatorName: String?,
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
        override val securityNotice: String?,
    ) : WecomParsed

    data class Todo(
        val todoId: String?,
        val title: String?,
        val status: String?,
        val userStatus: String?,
        val deadline: String?,
        val deadlineType: String?,
        val creator: String?,
        val followers: List<Follower>,
        val updateTime: String?,
        val description: String?,
    )

    data class Follower(
        val name: String,
        val status: String?,
    )

    data class Contacts(
        val items: List<Contact>,
        override val count: Int,
        override val hasMore: Boolean,
        override val nextCursor: String?,
        val hint: String?,
        override val securityNotice: String?,
    ) : WecomParsed

    data class Contact(
        val userId: String?,
        val name: String?,
        val alias: String?,
        val departments: List<String>,
        val position: String?,
        val email: String?,
        val matchedKeywords: List<String>,
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
            securityNotice = payload.securityNotice(),
        )
    }

    private fun parseMessages(payload: JsonObject): WecomParsed.Messages? {
        val items = payload.responseArray("messages", "messages_count", MESSAGES_KEYS) ?: return null
        val messages = items.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val type = item.stringValue("msg_type")
            val content = messageContent(item, type)
            WecomParsed.Message(
                sendTime = item.stringValue("send_time"),
                senderName = item.stringValue("user_name"),
                senderId = item.stringValue("userid"),
                type = type,
                summary = content.text,
                mediaIds = content.mediaIds,
            )
        }
        return WecomParsed.Messages(
            items = messages,
            count = payload.intValue("messages_count") ?: messages.size,
            hasMore = payload.boolValue("has_more") ?: false,
            nextCursor = payload.stringValue("next_cursor"),
            securityNotice = payload.securityNotice(),
        )
    }

    private data class MessageContent(val text: String?, val mediaIds: List<String>)

    private fun messageContent(item: JsonObject, type: String?): MessageContent = when (type) {
        "text" -> MessageContent(item.objValue("text")?.stringValue("content"), emptyList())
        "image" -> MessageContent("[图片]", mediaIdsOf(item.objValue("image")))
        "voice" -> MessageContent("[语音]", mediaIdsOf(item.objValue("voice")))
        "video" -> MessageContent("[视频]", mediaIdsOf(item.objValue("video")))
        "file" -> {
            val file = item.objValue("file")
            val name = file?.stringValue("file_name")
            MessageContent("[文件] ${name ?: "未命名文件"}", mediaIdsOf(file))
        }
        "mixed" -> {
            val entries = item.objValue("mixed")?.arrayOrMissing("items").orEmpty().mapNotNull { element ->
                val entry = element as? JsonObject ?: return@mapNotNull null
                when (entry.stringValue("msg_type")) {
                    "text" -> MixedPart(entry.objValue("text")?.stringValue("content"), emptyList())
                    "image" -> MixedPart("[图片]", mediaIdsOf(entry.objValue("image")))
                    else -> null
                }
            }
            val text = entries.mapNotNull { it.text?.takeIf(String::isNotBlank) }.joinToString("\n")
            MessageContent(
                text = text.ifBlank { "[图文]" },
                mediaIds = entries.flatMap { it.mediaIds },
            )
        }
        else -> MessageContent(null, emptyList())
    }

    private data class MixedPart(val text: String?, val mediaIds: List<String>)

    private fun mediaIdsOf(content: JsonObject?): List<String> =
        content?.stringValue("media_id")?.let(::listOf).orEmpty()

    private fun parseDocs(payload: JsonObject): WecomParsed.Docs? {
        val items = payload.responseArray("docs", "docs_count", DOCS_KEYS) ?: return null
        val docs = items.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            WecomParsed.Doc(
                docId = item.stringValue("docid") ?: item.stringValue("doc_id"),
                name = item.stringValue("doc_name") ?: item.stringValue("name"),
                type = item.stringValue("doc_type"),
                creatorName = item.stringValue("creator_name"),
                creatorUserId = item.stringValue("creator_userid"),
                createTime = item.stringValue("create_time"),
                modifyTime = item.stringValue("modify_time"),
                openTime = item.stringValue("open_time"),
                textHighlight = item.stringList("text_highlight"),
                url = item.stringValue("url"),
            )
        }
        return WecomParsed.Docs(
            items = docs,
            count = payload.intValue("docs_count") ?: docs.size,
            hasMore = payload.boolValue("has_more") ?: false,
            nextCursor = payload.stringValue("next_cursor"),
            securityNotice = payload.securityNotice(),
        )
    }

    private fun parseDocContent(payload: JsonObject): WecomParsed.DocContent? {
        val name = payload.stringValue("name")
        val url = payload.stringValue("url")
        val content = payload.stringValue("content").orEmpty()
        val filePath = payload.stringValue("file_path")
        if (name == null && url == null && content.isBlank() && filePath == null) return null
        return WecomParsed.DocContent(
            name = name,
            url = url,
            version = payload.intValue("version"),
            errcode = payload.intValue("errcode"),
            errmsg = payload.stringValue("errmsg"),
            content = content,
            filePath = filePath,
            securityNotice = payload.securityNotice(),
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
                creatorName = item.stringValue("creator_name"),
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
            hasMore = payload.boolValue("has_more") ?: false,
            nextCursor = payload.stringValue("next_cursor"),
            securityNotice = payload.securityNotice(),
        )
    }

    private fun parseTodos(payload: JsonObject): WecomParsed.Todos? {
        val items = payload.responseArray("items", "items_count", TODOS_KEYS) ?: return null
        val todos = items.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val deadline = item.objValue("deadline")
            WecomParsed.Todo(
                todoId = item.stringValue("todo_id"),
                title = item.stringValue("title"),
                status = item.stringValue("status"),
                userStatus = item.stringValue("user_status"),
                deadline = deadline?.stringValue("value"),
                deadlineType = deadline?.stringValue("type"),
                creator = item.objValue("creator")?.let { creator ->
                    creator.stringValue("user_name") ?: creator.stringValue("userid")
                },
                followers = item.arrayOrMissing("followers").orEmpty().mapNotNull { follower ->
                    val entry = follower as? JsonObject ?: return@mapNotNull null
                    val name = entry.stringValue("user_name") ?: entry.stringValue("userid") ?: return@mapNotNull null
                    WecomParsed.Follower(name = name, status = entry.stringValue("user_status"))
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
            securityNotice = payload.securityNotice(),
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
                matchedKeywords = item.stringList("matched_keywords"),
            )
        }
        return WecomParsed.Contacts(
            items = contacts,
            count = payload.intValue("users_count") ?: contacts.size,
            hasMore = payload.boolValue("has_more") ?: false,
            nextCursor = payload.stringValue("next_cursor"),
            hint = payload.stringValue("hint"),
            securityNotice = payload.securityNotice(),
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

    private fun JsonObject.stringList(key: String): List<String> =
        arrayOrMissing(key).orEmpty().mapNotNull { element ->
            (element as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        }

    private fun JsonObject.securityNotice(): String? = stringValue("security_notice")

    private val COMMON_KEYS = setOf(
        "errcode",
        "errmsg",
        "security_notice",
        "has_more",
        "next_cursor",
        "hint",
    )
    private val GROUPS_KEYS = COMMON_KEYS + setOf("chats", "chats_count")
    private val MESSAGES_KEYS = COMMON_KEYS + setOf("messages", "messages_count")
    private val DOCS_KEYS = COMMON_KEYS + setOf("docs", "docs_count")
    private val SCHEDULES_KEYS = COMMON_KEYS + setOf("schedule_list", "schedule_list_count")
    private val TODOS_KEYS = COMMON_KEYS + setOf("items", "items_count")
    private val CONTACTS_KEYS = COMMON_KEYS + setOf("users", "users_count")
}
