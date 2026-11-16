package io.github.whywood.deepdeep.plugin.wecom

import io.github.whywood.deepdeep.plugin.api.PluginHost
import io.github.whywood.deepdeep.plugin.cli.arrayValue
import io.github.whywood.deepdeep.plugin.cli.card.CliCardCodec
import io.github.whywood.deepdeep.plugin.cli.card.CliCardItem
import io.github.whywood.deepdeep.plugin.cli.card.CliCardModel
import io.github.whywood.deepdeep.plugin.cli.card.CliCardState
import io.github.whywood.deepdeep.plugin.cli.intValue
import io.github.whywood.deepdeep.plugin.cli.stringValue
import kotlinx.serialization.json.JsonObject

internal object WecomChatCards {
    private val SUPPORTED_KINDS: Set<String> = WecomCommandKind.entries.map { it.customType }.toSet()

    fun build(host: PluginHost, command: WecomCommand, parsed: WecomParsed): CliCardModel = when (parsed) {
        is WecomParsed.Groups -> CliCardModel(
            kind = command.kind.customType,
            state = if (parsed.items.isEmpty()) CliCardState.EMPTY else CliCardState.READY,
            context = contextLabel(host, command),
            total = parsed.count,
            items = parsed.items.map { item ->
                CliCardItem(
                    title = item.name?.takeIf { it.isNotBlank() }
                        ?: host.t("未命名会话", "Untitled chat"),
                    subtitle = item.lastMessageTime?.takeIf { it.isNotBlank() }?.let { time ->
                        host.tf("最近消息 %s", "Last message %s", wecomShortText(time) ?: time)
                    },
                    meta = item.messageCount?.let { displayValue(host, it.toString(), "count") },
                    badge = item.chatId?.takeIf { it.isNotBlank() },
                )
            },
            note = moreNote(parsed.hasMore, parsed.nextCursor),
        )
        is WecomParsed.Messages -> CliCardModel(
            kind = command.kind.customType,
            state = if (parsed.items.isEmpty()) CliCardState.EMPTY else CliCardState.READY,
            context = contextLabel(host, command),
            total = parsed.count,
            items = parsed.items.map { item ->
                CliCardItem(
                    title = item.senderName?.takeIf { it.isNotBlank() }
                        ?: item.senderId?.takeIf { it.isNotBlank() }
                        ?: host.t("未知成员", "Unknown member"),
                    meta = item.sendTime?.takeIf { it.isNotBlank() },
                    badge = item.type?.takeIf { it.isNotBlank() },
                    body = item.summary?.takeIf { it.isNotBlank() } ?: messageSummary(host, item),
                )
            },
            note = moreNote(parsed.hasMore, parsed.nextCursor),
        )
        is WecomParsed.Docs -> CliCardModel(
            kind = command.kind.customType,
            state = if (parsed.items.isEmpty()) CliCardState.EMPTY else CliCardState.READY,
            context = contextLabel(host, command),
            total = parsed.count,
            items = parsed.items.map { item ->
                CliCardItem(
                    title = item.name?.takeIf { it.isNotBlank() } ?: host.t("未命名文档", "Untitled doc"),
                    subtitle = docSubtitle(host, item),
                    meta = item.type?.let { displayValue(host, it, "doc_type") },
                    badge = item.docId?.takeIf { it.isNotBlank() },
                    body = docDetails(host, item),
                    url = item.url?.takeIf { it.startsWith("http") },
                )
            },
            note = moreNote(parsed.hasMore, parsed.nextCursor),
        )
        is WecomParsed.DocContent -> CliCardModel(
            kind = command.kind.customType,
            state = CliCardState.READY,
            title = parsed.name,
            trailing = parsed.version?.let { host.tf("版本 %d", "Version %d", it) },
            context = command.docId,
            total = 1,
            summary = parsed.content.takeIf { it.isNotBlank() },
            openUrl = parsed.url?.takeIf { it.startsWith("http") },
        )
        is WecomParsed.Schedules -> CliCardModel(
            kind = command.kind.customType,
            state = if (parsed.items.isEmpty()) CliCardState.EMPTY else CliCardState.READY,
            context = contextLabel(host, command),
            total = parsed.count,
            items = parsed.items.map { item ->
                CliCardItem(
                    title = item.subject?.takeIf { it.isNotBlank() }
                        ?: host.t("未命名日程", "Untitled event"),
                    subtitle = scheduleSubtitle(host, item),
                    meta = item.attendees.size.takeIf { it > 0 }?.let { "+$it" },
                    badge = wecomDateKey(item.beginTime),
                    body = scheduleDetails(host, item),
                )
            },
            note = moreNote(parsed.hasMore, parsed.nextCursor),
        )
        is WecomParsed.Todos -> CliCardModel(
            kind = command.kind.customType,
            state = if (parsed.items.isEmpty()) CliCardState.EMPTY else CliCardState.READY,
            context = contextLabel(host, command),
            total = parsed.count,
            items = parsed.items.map { item ->
                CliCardItem(
                    title = item.title?.takeIf { it.isNotBlank() } ?: host.t("未命名待办", "Untitled to-do"),
                    subtitle = item.followers.takeIf { it.isNotEmpty() }?.joinToString("、") { follower ->
                        follower.status?.takeIf { it.isNotBlank() }?.let { status ->
                            host.tf(
                                "%s（%s）",
                                "%s (%s)",
                                follower.name,
                                displayValue(host, status, "todo_user_status"),
                            )
                        } ?: follower.name
                    },
                    meta = item.status?.let { displayValue(host, it, "todo_status") },
                    badge = item.deadline?.takeIf { it.isNotBlank() },
                    body = todoDetails(host, item),
                )
            },
            note = moreNote(parsed.hasMore, parsed.nextCursor),
        )
        is WecomParsed.Contacts -> CliCardModel(
            kind = command.kind.customType,
            state = if (parsed.items.isEmpty()) CliCardState.EMPTY else CliCardState.READY,
            context = contextLabel(host, command),
            total = parsed.count,
            items = parsed.items.map { item ->
                CliCardItem(
                    title = item.name?.takeIf { it.isNotBlank() }
                        ?: item.userId?.takeIf { it.isNotBlank() }
                        ?: host.t("未知成员", "Unknown member"),
                    subtitle = listOfNotNull(
                        item.departments.takeIf { it.isNotEmpty() }?.joinToString("/"),
                        item.position?.takeIf { it.isNotBlank() },
                    ).joinToString(" · ").ifBlank { null },
                    meta = item.email?.takeIf { it.isNotBlank() },
                    body = contactDetails(host, item),
                )
            },
            note = moreNote(parsed.hasMore, parsed.nextCursor),
        )
    }

    fun decode(host: PluginHost, payload: JsonObject, kind: WecomCommandKind): CliCardModel? =
        CliCardCodec.decode(payload, SUPPORTED_KINDS) ?: legacyCard(host, payload, kind)

    private fun legacyCard(host: PluginHost, payload: JsonObject, kind: WecomCommandKind): CliCardModel? = runCatching {
        val state = CliCardState.fromWire(payload.stringValue("state")) ?: return null
        val count = payload.intValue("count") ?: return null
        val items = payload.arrayValue("rows").mapNotNull { element ->
            val row = element as? JsonObject ?: return@mapNotNull null
            val title = row.stringValue("title") ?: return@mapNotNull null
            CliCardItem(
                title = title,
                subtitle = row.stringValue("subtitle"),
                meta = row.stringValue("value")?.let { displayValue(host, it, row.stringValue("valueKind")) },
                url = row.stringValue("url"),
            )
        }
        CliCardModel(
            kind = kind.customType,
            state = state,
            title = payload.stringValue("headerDetail"),
            trailing = payload.stringValue("headerTrailing"),
            context = payload.stringValue("context"),
            total = count,
            items = items,
            summary = payload.stringValue("summary") ?: payload.stringValue("errorMessage"),
            openUrl = payload.stringValue("actionUrl"),
            note = payload.stringValue("note"),
        )
    }.getOrNull()

    private fun contextLabel(host: PluginHost, command: WecomCommand): String? = when (command.kind) {
        WecomCommandKind.CHAT_GROUPS,
        WecomCommandKind.CHAT_MESSAGES,
        WecomCommandKind.CALENDAR_SCHEDULES,
        -> rangeLabel(host, command.beginTime, command.endTime)
        WecomCommandKind.DOC_SEARCH, WecomCommandKind.CONTACT_USERS ->
            command.keywords.takeIf { it.isNotEmpty() }?.joinToString(" ")
        WecomCommandKind.DOC_CONTENT -> command.docId
        WecomCommandKind.TODO_LIST -> null
    }

    private fun rangeLabel(host: PluginHost, begin: String?, end: String?): String? = when {
        begin != null && end != null -> host.tf("%s 至 %s", "%s to %s", begin, end)
        begin != null -> host.tf("%s 起", "From %s", begin)
        end != null -> host.tf("截至 %s", "Until %s", end)
        else -> null
    }

    private fun messageSummary(host: PluginHost, item: WecomParsed.Message): String? = when (item.type) {
        "image" -> host.t("[图片]", "[Image]")
        "voice" -> host.t("[语音]", "[Voice]")
        "video" -> host.t("[视频]", "[Video]")
        "file" -> host.t("[文件]", "[File]")
        "mixed" -> host.t("[图文]", "[Post]")
        else -> null
    }

    private fun docSubtitle(host: PluginHost, item: WecomParsed.Doc): String? = listOfNotNull(
        item.creatorName?.takeIf { it.isNotBlank() },
        item.modifyTime?.takeIf { it.isNotBlank() }?.let { time ->
            host.tf("修改于 %s", "Edited %s", wecomShortText(time) ?: time)
        },
    ).joinToString(" · ").ifBlank { null }

    private fun docDetails(host: PluginHost, item: WecomParsed.Doc): String? = listOfNotNull(
        item.creatorUserId?.takeIf { it.isNotBlank() }?.let {
            host.tf("创建人 ID %s", "Creator ID %s", it)
        },
        item.createTime?.takeIf { it.isNotBlank() }?.let {
            host.tf("创建于 %s", "Created %s", wecomShortText(it) ?: it)
        },
        item.openTime?.takeIf { it.isNotBlank() }?.let {
            host.tf("最近打开 %s", "Opened %s", wecomShortText(it) ?: it)
        },
        item.textHighlight.takeIf { it.isNotEmpty() }?.joinToString(" ")?.let {
            host.tf("命中 %s", "Matched %s", it)
        },
    ).joinToString("\n").ifBlank { null }

    private fun scheduleSubtitle(host: PluginHost, item: WecomParsed.Schedule): String? {
        val start = wecomTimeOnly(item.beginTime)
        val end = wecomTimeOnly(item.endTime)
        val time = when {
            item.allDay -> host.t("全天", "All day")
            start != null && end != null -> "$start - $end"
            start != null -> start
            else -> null
        }
        val location = item.location?.takeIf { it.isNotBlank() }
            ?: item.meetingRoom?.takeIf { it.isNotBlank() }
        return listOfNotNull(time, location).joinToString(" · ").ifBlank { null }
    }

    private fun scheduleDetails(host: PluginHost, item: WecomParsed.Schedule): String? = listOfNotNull(
        item.creatorName?.takeIf { it.isNotBlank() }?.let {
            host.tf("创建人 %s", "Organizer %s", it)
        },
        item.meetingRoom?.takeIf { it.isNotBlank() }?.let {
            host.tf("会议室 %s", "Meeting room %s", it)
        },
        item.attendees.takeIf { it.isNotEmpty() }?.joinToString("、")?.let {
            host.tf("参与人 %s", "Attendees %s", it)
        },
        item.calendarName?.takeIf { it.isNotBlank() }?.let {
            host.tf("日历 %s", "Calendar %s", it)
        },
        item.scheduleId?.takeIf { it.isNotBlank() }?.let {
            host.tf("日程 ID %s", "Schedule ID %s", it)
        },
    ).joinToString("\n").ifBlank { null }

    private fun todoDetails(host: PluginHost, item: WecomParsed.Todo): String? = listOfNotNull(
        item.description?.takeIf { it.isNotBlank() },
        item.deadlineType?.takeIf { it.isNotBlank() }?.let {
            host.tf(
                "截止类型 %s",
                "Due type %s",
                host.t(WecomObservation.deadlineTypeLabel(it), deadlineTypeEn(it)),
            )
        },
        item.creator?.takeIf { it.isNotBlank() }?.let {
            host.tf("创建人 %s", "Created by %s", it)
        },
        item.userStatus?.takeIf { it.isNotBlank() }?.let {
            host.tf("我的状态 %s", "My status %s", displayValue(host, it, "todo_user_status"))
        },
        item.updateTime?.takeIf { it.isNotBlank() }?.let {
            host.tf("更新于 %s", "Updated %s", wecomShortText(it) ?: it)
        },
        item.todoId?.takeIf { it.isNotBlank() }?.let {
            host.tf("待办 ID %s", "To-do ID %s", it)
        },
    ).joinToString("\n").ifBlank { null }

    private fun contactDetails(host: PluginHost, item: WecomParsed.Contact): String? = listOfNotNull(
        item.userId?.takeIf { it.isNotBlank() }?.let { host.tf("userid %s", "User ID %s", it) },
        item.alias?.takeIf { it.isNotBlank() }?.let { host.tf("英文名 %s", "Alias %s", it) },
        item.email?.takeIf { it.isNotBlank() }?.let { host.tf("邮箱 %s", "Email %s", it) },
        item.matchedKeywords.takeIf { it.isNotEmpty() }?.joinToString("、")?.let {
            host.tf("命中关键词 %s", "Matched %s", it)
        },
    ).joinToString("\n").ifBlank { null }

    private fun moreNote(hasMore: Boolean, nextCursor: String?): String? = when {
        hasMore && !nextCursor.isNullOrBlank() -> "还有更多，cursor=$nextCursor"
        hasMore -> "还有更多"
        else -> null
    }

    internal fun displayValue(host: PluginHost, value: String, valueKind: String?): String = when (valueKind) {
        "todo_status" -> host.t(WecomObservation.todoStatusLabel(value), todoStatusEn(value))
        "todo_user_status" -> host.t(WecomObservation.todoUserStatusLabel(value), todoUserStatusEn(value))
        "doc_type" -> host.t(WecomObservation.docTypeLabel(value), docTypeEn(value))
        "count" -> if (host.chinese) "$value 条" else value
        "people" -> value
        else -> value
    }

    private fun todoStatusEn(status: String): String = when (status) {
        "proceed" -> "In progress"
        "finished" -> "Done"
        "deleted" -> "Deleted"
        else -> "Unknown"
    }

    private fun deadlineTypeEn(type: String): String = when (type) {
        "datetime" -> "Date and time"
        "date" -> "Date"
        "none" -> "No due date"
        else -> type
    }

    private fun todoUserStatusEn(status: String): String = when (status) {
        "accept" -> "Accepted"
        "finished" -> "Done"
        "reject" -> "Rejected"
        "removed" -> "Removed"
        "notshow" -> "Hidden"
        else -> "Unknown"
    }

    private fun docTypeEn(type: String): String = when (type) {
        "doc" -> "Doc"
        "sheet" -> "Sheet"
        "smartsheet" -> "Smart sheet"
        "smartpage" -> "Smart page"
        "ppt", "slide" -> "Slides"
        "pdf" -> "PDF"
        "collect" -> "Form"
        "mind" -> "Mind map"
        "flow" -> "Flowchart"
        "journal" -> "Journal"
        else -> type
    }
}
