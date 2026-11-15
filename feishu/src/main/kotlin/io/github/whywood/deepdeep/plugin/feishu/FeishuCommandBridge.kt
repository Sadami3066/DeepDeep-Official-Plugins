package io.github.whywood.deepdeep.plugin.feishu

import io.github.whywood.deepdeep.plugin.api.PluginHost
import io.github.whywood.deepdeep.plugin.api.PluginHooks
import io.github.whywood.deepdeep.plugin.cli.CliCommandLine
import io.github.whywood.deepdeep.plugin.cli.CliText
import io.github.whywood.deepdeep.plugin.cli.CliTime
import io.github.whywood.deepdeep.plugin.cli.boolValue
import io.github.whywood.deepdeep.plugin.cli.bridge.CliBridgeConfig
import io.github.whywood.deepdeep.plugin.cli.bridge.CliCommandParser
import io.github.whywood.deepdeep.plugin.cli.bridge.CliObservation
import io.github.whywood.deepdeep.plugin.cli.bridge.CliObservationBuilder
import io.github.whywood.deepdeep.plugin.cli.bridge.CliToolBridge
import io.github.whywood.deepdeep.plugin.cli.card.CliCardModel
import io.github.whywood.deepdeep.plugin.cli.intValue
import io.github.whywood.deepdeep.plugin.cli.objValue
import io.github.whywood.deepdeep.plugin.cli.objectList
import io.github.whywood.deepdeep.plugin.cli.stringValue
import kotlinx.serialization.json.JsonObject

internal enum class FeishuCommandKind {
    CHAT_LIST,
    CHAT_MESSAGES,
    CALENDAR_AGENDA,
    DRIVE_SEARCH,
    DOCS_FETCH,
    TASKS,
}

internal data class FeishuCommand(
    val kind: FeishuCommandKind,
    val chatId: String? = null,
    val userId: String? = null,
    val start: String? = null,
    val end: String? = null,
    val doc: String? = null,
    val docFormat: String? = null,
    val query: String? = null,
    val calendarId: String? = null,
)

internal data class FeishuObservation(
    val summary: String,
    val text: String,
    val card: FeishuCardModel,
)

internal fun parseLarkCliCommand(raw: String): FeishuCommand? {
    val command = raw.trim()
    if (command.isEmpty()) return null
    if (CliCommandLine.hasShellOperators(command)) return null
    val tokens = CliCommandLine.tokenize(command)
    if (tokens.isEmpty()) return null
    val binaryIndex = CliCommandLine.envPrefixLength(tokens)
    if (binaryIndex >= tokens.size) return null
    if (!CliCommandLine.isBinary(tokens[binaryIndex], FeishuCli.BINARY)) return null
    val args = tokens.drop(binaryIndex + 1)
    if (args.size < 2) return null
    val domain = args[0]
    val shortcut = args[1]
    val flags = CliCommandLine.readFlags(args.drop(2))
    if (flags.containsKey("--dry-run")) return null
    flags["--format"]?.let { format -> if (!format.equals("json", ignoreCase = true)) return null }
    if (flags.containsKey("--jq") || flags.containsKey("-q") || flags.containsKey("--concise")) return null
    return when {
        domain == "im" && shortcut == "+chat-list" -> FeishuCommand(FeishuCommandKind.CHAT_LIST)
        domain == "im" && shortcut == "+chat-messages-list" -> {
            val chatId = flags["--chat-id"]
            val userId = flags["--user-id"]
            if (chatId.isNullOrBlank() && userId.isNullOrBlank()) return null
            FeishuCommand(
                kind = FeishuCommandKind.CHAT_MESSAGES,
                chatId = chatId,
                userId = userId,
                start = flags["--start"] ?: flags["--start-time"],
                end = flags["--end"] ?: flags["--end-time"],
            )
        }
        domain == "calendar" && shortcut == "+agenda" -> FeishuCommand(
            kind = FeishuCommandKind.CALENDAR_AGENDA,
            start = flags["--start"],
            end = flags["--end"],
            calendarId = flags["--calendar-id"],
        )
        domain == "drive" && shortcut == "+search" -> FeishuCommand(
            kind = FeishuCommandKind.DRIVE_SEARCH,
            query = flags["--query"],
        )
        domain == "docs" && shortcut == "+fetch" -> {
            val doc = flags["--doc"]
            if (doc.isNullOrBlank()) return null
            FeishuCommand(
                kind = FeishuCommandKind.DOCS_FETCH,
                doc = doc,
                docFormat = flags["--doc-format"],
            )
        }
        domain == "task" && shortcut == "+get-my-tasks" -> FeishuCommand(FeishuCommandKind.TASKS)
        else -> null
    }
}

private val FEISHU_BRIDGE_CONFIG: CliBridgeConfig<FeishuCommand, CliCardModel> = CliBridgeConfig(
    binaryName = FeishuCli.BINARY,
    customType = FeishuCardTypes.CHAT_CARD,
    logTag = "feishu.bridge",
    parser = CliCommandParser { raw -> parseLarkCliCommand(raw) },
    observer = CliObservationBuilder { host, command, envelope ->
        buildFeishuObservation(host, command, envelope)?.let { observation ->
            CliObservation(summary = observation.summary, text = observation.text, card = observation.card)
        }
    },
    cardEncoder = { model -> encodeFeishuCardPayload(model) },
)

internal fun PluginHost.feishuBridgeLog(message: String) {
    runCatching { logs().debug("feishu.bridge $message") }
}

internal class FeishuToolBridge {
    private val bridge = CliToolBridge(FEISHU_BRIDGE_CONFIG)

    fun hooks(host: PluginHost): PluginHooks = bridge.hooks(host)

    fun clear() {
        bridge.clear()
    }
}

internal fun buildFeishuObservation(
    host: PluginHost,
    command: FeishuCommand,
    envelope: JsonObject,
): FeishuObservation? = runCatching {
    when (command.kind) {
        FeishuCommandKind.CHAT_LIST -> chatListObservation(host, envelope)
        FeishuCommandKind.CHAT_MESSAGES -> messagesObservation(host, command, envelope)
        FeishuCommandKind.CALENDAR_AGENDA -> agendaObservation(host, command, envelope)
        FeishuCommandKind.DRIVE_SEARCH -> docsSearchObservation(host, command, envelope)
        FeishuCommandKind.DOCS_FETCH -> docsReadObservation(host, command, envelope)
        FeishuCommandKind.TASKS -> tasksObservation(host, envelope)
    }
}.getOrNull()

private fun chatListObservation(host: PluginHost, envelope: JsonObject): FeishuObservation? {
    val data = envelope.objValue("data") ?: return null
    val chats = data.objectList("chats")
    val total = chats.size
    val zh = host.isChinese()
    val rows = chats.take(MAX_CARD_ROWS).map { chat ->
        val id = chat.stringValue("chat_id")
        val name = chat.stringValue("name") ?: id.orEmpty()
        val mode = chat.stringValue("chat_mode")
        val modeLabel = when {
            mode == "p2p" -> if (zh) "单聊" else "Direct"
            mode == "topic" -> if (zh) "话题群" else "Topic group"
            else -> if (zh) "群聊" else "Group"
        }
        val description = chat.stringValue("description")
        FeishuCardItem(
            title = name,
            subtitle = description?.let { CliText.oneLine(it, 80) },
            meta = modeLabel,
            url = chat.stringValue("chat_app_link") ?: chatAppLink(id),
        )
    }
    val detailRows = chats.take(MAX_CARD_ROWS).mapIndexed { index, chat ->
        val id = chat.stringValue("chat_id").orEmpty()
        val name = chat.stringValue("name") ?: id
        val mode = when (chat.stringValue("chat_mode")) {
            "p2p" -> if (zh) "单聊" else "direct"
            "topic" -> if (zh) "话题群" else "topic group"
            else -> if (zh) "群聊" else "group"
        }
        val description = chat.stringValue("description")?.let { " — ${CliText.oneLine(it, 80)}" }.orEmpty()
        val link = chat.stringValue("chat_app_link") ?: chatAppLink(id)
        val linkPart = link?.let { if (zh) "；链接：$it" else "; link: $it" }.orEmpty()
        "${index + 1}. $name（$mode，chat_id: $id）$description$linkPart"
    }
    val note = if (total > rows.size) {
        if (zh) "还有 ${total - rows.size} 个未显示" else "${total - rows.size} more not shown"
    } else {
        null
    }
    val header = if (zh) "飞书聊天列表（共 $total 个）" else "Feishu chats ($total total)"
    val text = buildString {
        appendLine(header)
        if (detailRows.isEmpty()) {
            appendLine(if (zh) "当前身份没有加入任何聊天。" else "The current identity is not in any chats.")
        } else {
            detailRows.forEach { appendLine(it) }
            note?.let { appendLine(it) }
        }
    }.trim()
    return FeishuObservation(
        summary = header,
        text = text,
        card = FeishuCardModel(
            kind = FeishuCardTypes.KIND_CHAT_LIST,
            total = total,
            items = rows,
            openUrl = null,
            note = note,
        ),
    )
}

private fun messagesObservation(host: PluginHost, command: FeishuCommand, envelope: JsonObject): FeishuObservation? {
    val data = envelope.objValue("data") ?: return null
    val messages = data.objectList("messages")
    val total = messages.size
    val zh = host.isChinese()
    val chatId = messages.firstNotNullOfOrNull { it.stringValue("chat_id") } ?: command.chatId
    val rows = mutableListOf<FeishuCardItem>()
    val detailRows = mutableListOf<String>()
    var longCount = 0
    messages.take(MAX_CARD_ROWS).forEachIndexed { index, message ->
        val messageId = message.stringValue("message_id").orEmpty()
        val msgType = message.stringValue("msg_type").orEmpty()
        val link = message.stringValue("message_app_link")
        val rawContent = message.stringValue("content").orEmpty()
        val plain = CliText.sanitize(rawContent)
        val sender = message.objValue("sender")?.stringValue("name").orEmpty().ifBlank {
            message.objValue("sender")?.stringValue("id").orEmpty()
        }
        val time = message.stringValue("create_time")
        val interactive = msgType == "interactive"
        val long = interactive || plain.length > 240
        if (long) longCount++
        val summary = when {
            interactive -> if (zh) "交互消息" else "Interactive message"
            plain.isEmpty() -> if (zh) "（无文字内容）" else "(no text)"
            long -> CliText.oneLine(plain, 60)
            else -> plain
        }
        rows += FeishuCardItem(
            title = summary,
            subtitle = listOfNotNull(sender.takeIf { it.isNotBlank() }, msgTypeLabel(msgType, zh)).joinToString(" · "),
            meta = CliTime.shortDateTime(time),
            url = link,
        )
        val timePart = time ?: if (zh) "未知时间" else "unknown time"
        val senderPart = sender.ifBlank { if (zh) "未知发送者" else "unknown sender" }
        val contentPart = when {
            interactive -> if (zh) "交互消息（内容较长，完整内容请打开飞书查看）" else "Interactive message (open Feishu for the full content)"
            plain.isEmpty() -> if (zh) "（无文字内容）" else "(no text)"
            else -> CliText.oneLine(plain)
        }
        val linkPart = link?.let { if (zh) "；链接：$it" else "; link: $it" }.orEmpty()
        detailRows += "${index + 1}. [$timePart] $senderPart（$msgType）：$contentPart" +
            (if (zh) "（消息 ID：$messageId$linkPart）" else " (message id: $messageId$linkPart)")
    }
    val header = if (zh) "飞书消息（共 $total 条）" else "Feishu messages ($total total)"
    val context = buildString {
        val chat = chatId?.let { if (zh) "会话：$it" else "chat: $it" }
        if (chat != null) append(chat)
        val range = timeRangeLabel(command.start, command.end, zh)
        if (range != null) append(if (isEmpty()) "" else (if (zh) "；" else "; ")).append(range)
    }.ifBlank { null }
    val note = when {
        total > rows.size -> if (zh) "还有 ${total - rows.size} 条未显示" else "${total - rows.size} more not shown"
        longCount > 0 -> if (zh) "长消息只显示摘要，点开在飞书查看完整内容" else "Long messages show a summary only; open Feishu for the full content"
        else -> null
    }
    val text = buildString {
        appendLine(header)
        context?.let { appendLine(it) }
        if (detailRows.isEmpty()) {
            appendLine(if (zh) "这个时间段没有消息。" else "No messages in this time range.")
        } else {
            detailRows.forEach { appendLine(it) }
            note?.let { appendLine(it) }
        }
    }.trim()
    return FeishuObservation(
        summary = header,
        text = text,
        card = FeishuCardModel(
            kind = FeishuCardTypes.KIND_MESSAGES,
            context = context,
            total = total,
            items = rows,
            openUrl = chatAppLink(chatId) ?: rows.firstOrNull()?.url,
            note = note,
        ),
    )
}

private fun agendaObservation(host: PluginHost, command: FeishuCommand, envelope: JsonObject): FeishuObservation? {
    val events = envelope.objectList("data")
    val total = events.size
    val zh = host.isChinese()
    val rows = mutableListOf<FeishuCardItem>()
    val detailRows = mutableListOf<String>()
    events.take(MAX_CARD_ROWS).forEachIndexed { index, event ->
        val eventId = event.stringValue("event_id").orEmpty()
        val summary = CliText.oneLine(event.stringValue("summary").orEmpty(), 80)
            .ifBlank { if (zh) "（无标题日程）" else "(untitled event)" }
        val start = event.objValue("start_time")
        val end = event.objValue("end_time")
        val startLabel = eventTimeLabel(start)
        val endLabel = eventTimeLabel(end)
        val allDay = start != null && start.stringValue("date") != null && start.stringValue("datetime") == null
        val timeLabel = when {
            allDay -> if (zh) "全天" else "All day"
            startLabel != null && endLabel != null -> "$startLabel-$endLabel"
            else -> startLabel ?: endLabel ?: (if (zh) "时间未知" else "time unknown")
        }
        val location = event.objValue("location")?.stringValue("name")
        val freeBusy = event.stringValue("free_busy_status")
        val rsvp = event.stringValue("self_rsvp_status")
        rows += FeishuCardItem(
            title = summary,
            subtitle = listOfNotNull(timeLabel, location?.takeIf { it.isNotBlank() }).joinToString(" · "),
            meta = if (allDay) null else CliTime.shortDateTime(start?.stringValue("datetime")),
        )
        val facts = buildString {
            append("${index + 1}. $timeLabel $summary")
            location?.takeIf { it.isNotBlank() }?.let { append(if (zh) "；地点：$it" else "; location: $it") }
            freeBusy?.takeIf { it.isNotBlank() }?.let { append(if (zh) "；忙闲：$it" else "; busy status: $it") }
            rsvp?.takeIf { it.isNotBlank() }?.let { append(if (zh) "；我的答复：$it" else "; my RSVP: $it") }
            append(if (zh) "（event_id: $eventId）" else " (event_id: $eventId)")
        }
        detailRows += facts
    }
    val header = if (zh) "飞书日程（共 $total 个）" else "Feishu agenda ($total events)"
    val range = timeRangeLabel(command.start, command.end, zh)
    val text = buildString {
        appendLine(header)
        range?.let { appendLine(it) }
        if (detailRows.isEmpty()) {
            appendLine(if (zh) "这段时间没有日程，这是正常的空结果。" else "No events in this time range; that is a normal empty result.")
        } else {
            detailRows.forEach { appendLine(it) }
        }
    }.trim()
    return FeishuObservation(
        summary = header,
        text = text,
        card = FeishuCardModel(
            kind = FeishuCardTypes.KIND_AGENDA,
            context = range,
            total = total,
            items = rows,
            note = null,
        ),
    )
}

private fun docsSearchObservation(host: PluginHost, command: FeishuCommand, envelope: JsonObject): FeishuObservation? {
    val data = envelope.objValue("data") ?: return null
    val results = data.objectList("results")
    val total = results.size
    val zh = host.isChinese()
    val rows = mutableListOf<FeishuCardItem>()
    val detailRows = mutableListOf<String>()
    results.take(MAX_CARD_ROWS).forEachIndexed { index, result ->
        val title = CliText.oneLine(result.stringValue("title").orEmpty(), 80)
            .ifBlank { if (zh) "（无标题文档）" else "(untitled doc)" }
        val type = result.stringValue("doc_type").orEmpty()
        val token = result.stringValue("token").orEmpty()
        val url = result.stringValue("url")
        val edited = CliTime.fullDateTime(result.stringValue("edit_time_iso"))
        val owner = result.stringValue("owner_id")
        rows += FeishuCardItem(
            title = title,
            subtitle = listOfNotNull(type.ifBlank { null }, edited?.let { if (zh) "编辑于 $it" else "edited $it" }).joinToString(" · "),
            meta = null,
            url = url,
        )
        detailRows += buildString {
            append("${index + 1}. $title")
            if (type.isNotBlank()) append("（$type）")
            edited?.let { append(if (zh) "；编辑时间：$it" else "; edited: $it") }
            owner?.takeIf { it.isNotBlank() }?.let { append(if (zh) "；owner: $it" else "; owner: $it") }
            append(if (zh) "；token: $token" else "; token: $token")
            url?.let { append(if (zh) "；链接：$it" else "; link: $it") }
        }
    }
    val query = command.query?.trim()?.takeIf { it.isNotBlank() }
    val header = if (zh) "飞书云文档（共 $total 个）" else "Feishu docs ($total total)"
    val context = query?.let {
        if (zh) "搜索：${CliText.oneLine(it, 60)}" else "search: ${CliText.oneLine(it, 60)}"
    }
    val text = buildString {
        appendLine(header)
        context?.let { appendLine(it) }
        if (detailRows.isEmpty()) {
            appendLine(if (zh) "没有找到匹配的文档，可以换关键词再试。" else "No matching docs; try another keyword.")
        } else {
            detailRows.forEach { appendLine(it) }
        }
    }.trim()
    return FeishuObservation(
        summary = header,
        text = text,
        card = FeishuCardModel(
            kind = FeishuCardTypes.KIND_DOCS_SEARCH,
            context = context,
            total = total,
            items = rows,
            openUrl = results.firstOrNull()?.stringValue("url"),
        ),
    )
}

private fun docsReadObservation(host: PluginHost, command: FeishuCommand, envelope: JsonObject): FeishuObservation? {
    val data = envelope.objValue("data") ?: return null
    val document = data.objValue("document") ?: return null
    val documentId = document.stringValue("document_id") ?: command.doc
    val content = document.stringValue("content").orEmpty()
    val plain = CliText.cleanDocContent(content)
    val titleFromXml = content.takeIf { it.contains("<title>") }
        ?.substringAfter("<title>")?.substringBefore("</title>")
        ?.let { CliText.oneLine(it, 80) }
    val titleFromMarkdown = plain.lineSequence().firstOrNull()
        ?.trim()?.removePrefix("#")?.trim()?.takeIf { it.isNotEmpty() }
        ?.let { CliText.oneLine(it, 80) }
    val title = titleFromXml ?: titleFromMarkdown
    val paragraphs = plain.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.take(6).toList()
    val zh = host.isChinese()
    val url = feishuDocUrl(command.doc ?: documentId)
    val excerpt = paragraphs.take(3).map { CliText.clip(it, 160) }
    val rows = excerpt.map { FeishuCardItem(title = it) }
    val header = if (zh) "飞书文档" else "Feishu document"
    val context = title?.takeIf { it.isNotBlank() }
    val note = if (zh) "正文约 ${plain.length} 字" else "${plain.length} chars"
    val text = buildString {
        appendLine(if (zh) "飞书文档：${context ?: documentId}" else "Feishu document: ${context ?: documentId}")
        appendLine(if (zh) "文档 ID：$documentId；版本：${document.intValue("revision_id") ?: 0}" else "document id: $documentId; revision: ${document.intValue("revision_id") ?: 0}")
        if (excerpt.isEmpty()) {
            appendLine(if (zh) "文档正文为空。" else "The document is empty.")
        } else {
            appendLine(if (zh) "内容节选：" else "Excerpt:")
            excerpt.forEach { appendLine("- $it") }
        }
        url?.let { appendLine(if (zh) "链接：$it" else "link: $it") }
    }.trim()
    return FeishuObservation(
        summary = if (zh) "已读取文档「${context ?: documentId}」" else "Read document \"${context ?: documentId}\"",
        text = text,
        card = FeishuCardModel(
            kind = FeishuCardTypes.KIND_DOCS_READ,
            context = context,
            total = excerpt.size,
            items = rows,
            openUrl = url,
            note = note,
        ),
    )
}

private fun tasksObservation(host: PluginHost, envelope: JsonObject): FeishuObservation? {
    val data = envelope.objValue("data") ?: return null
    val tasks = data.objectList("items")
    val total = tasks.size
    val zh = host.isChinese()
    val rows = mutableListOf<FeishuCardItem>()
    val detailRows = mutableListOf<String>()
    tasks.take(MAX_CARD_ROWS).forEachIndexed { index, task ->
        val guid = task.stringValue("guid").orEmpty()
        val summary = CliText.oneLine(task.stringValue("summary").orEmpty(), 80)
            .ifBlank { if (zh) "（无标题任务）" else "(untitled task)" }
        val completed = task.boolValue("completed") == true
        val due = CliTime.fullDateTime(task.stringValue("due_at"))
        val completedAt = CliTime.fullDateTime(task.stringValue("completed_at"))
        val url = task.stringValue("url")
        val subtitle = when {
            completed && completedAt != null -> if (zh) "完成于 $completedAt" else "completed $completedAt"
            due != null -> if (zh) "截止 $due" else "due $due"
            else -> if (zh) "无截止时间" else "no due date"
        }
        rows += FeishuCardItem(
            title = summary,
            subtitle = subtitle,
            badge = if (completed) (if (zh) "已完成" else "Done") else (if (zh) "待办" else "To do"),
            meta = CliTime.shortDateTime(task.stringValue("due_at")),
            url = url,
        )
        detailRows += buildString {
            append("${index + 1}. $summary（${if (completed) (if (zh) "已完成" else "done") else (if (zh) "未完成" else "open")}）")
            if (completed) completedAt?.let { append(if (zh) "；完成时间：$it" else "; completed: $it") }
            else due?.let { append(if (zh) "；截止：$it" else "; due: $it") }
            append(if (zh) "；guid: $guid" else "; guid: $guid")
            url?.let { append(if (zh) "；链接：$it" else "; link: $it") }
        }
    }
    val open = tasks.count { it.boolValue("completed") != true }
    val header = if (zh) "飞书任务（共 $total 条，未完成 $open 条）" else "Feishu tasks ($total total, $open open)"
    val text = buildString {
        appendLine(header)
        if (detailRows.isEmpty()) {
            appendLine(if (zh) "没有待办任务，这是正常的空结果。" else "No tasks; that is a normal empty result.")
        } else {
            detailRows.forEach { appendLine(it) }
        }
    }.trim()
    return FeishuObservation(
        summary = header,
        text = text,
        card = FeishuCardModel(
            kind = FeishuCardTypes.KIND_TASKS,
            total = total,
            items = rows,
            openUrl = tasks.firstOrNull()?.stringValue("url"),
        ),
    )
}

private fun eventTimeLabel(time: JsonObject?): String? {
    if (time == null) return null
    time.stringValue("datetime")?.let { return CliTime.shortDateTime(it) }
    time.stringValue("date")?.let { return it }
    time.stringValue("timestamp")?.let { raw -> return CliTime.shortEpochSeconds(raw) }
    return null
}


private fun feishuDocUrl(doc: String?): String? {
    val value = doc?.trim().orEmpty()
    if (value.isEmpty()) return null
    if (value.startsWith("http://") || value.startsWith("https://")) return value
    return "https://feishu.cn/docx/$value"
}

private fun chatAppLink(chatId: String?): String? =
    chatId?.trim()?.takeIf { it.isNotEmpty() }?.let {
        "https://applink.feishu.cn/client/chat/open?openChatId=$it"
    }

private fun msgTypeLabel(msgType: String, zh: Boolean): String = when (msgType) {
    "text" -> if (zh) "文本" else "text"
    "post" -> if (zh) "富文本" else "post"
    "image" -> if (zh) "图片" else "image"
    "file" -> if (zh) "文件" else "file"
    "audio" -> if (zh) "语音" else "audio"
    "video", "media" -> if (zh) "视频" else "video"
    "interactive" -> if (zh) "交互消息" else "interactive"
    "sticker" -> if (zh) "表情" else "sticker"
    "system" -> if (zh) "系统消息" else "system"
    else -> msgType.ifBlank { if (zh) "消息" else "message" }
}

private fun timeRangeLabel(start: String?, end: String?, zh: Boolean): String? {
    val from = CliTime.shortDateTime(start)
    val to = CliTime.shortDateTime(end)
    return when {
        from != null && to != null -> if (zh) "时间范围：$from 至 $to" else "range: $from to $to"
        from != null -> if (zh) "起始：$from" else "from: $from"
        to != null -> if (zh) "截止：$to" else "until: $to"
        else -> null
    }
}
