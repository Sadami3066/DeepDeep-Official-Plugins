package io.github.whywood.deepdeep.plugin.feishu

import io.github.whywood.deepdeep.plugin.api.PluginHost
import io.github.whywood.deepdeep.plugin.api.PluginHooks
import io.github.whywood.deepdeep.plugin.api.ToolExecutionResult
import io.github.whywood.deepdeep.plugin.api.ToolResultDecision
import io.github.whywood.deepdeep.plugin.api.resultReplace
import io.github.whywood.deepdeep.plugin.api.textResult
import io.github.whywood.deepdeep.plugin.cli.CliCommandLine
import io.github.whywood.deepdeep.plugin.cli.CliText
import io.github.whywood.deepdeep.plugin.cli.CliTime
import io.github.whywood.deepdeep.plugin.cli.boolValue
import io.github.whywood.deepdeep.plugin.cli.bridge.CliBridgeConfig
import io.github.whywood.deepdeep.plugin.cli.bridge.CliCommandParser
import io.github.whywood.deepdeep.plugin.cli.bridge.CliErrorMapper
import io.github.whywood.deepdeep.plugin.cli.bridge.CliObservation
import io.github.whywood.deepdeep.plugin.cli.bridge.CliObservationBuilder
import io.github.whywood.deepdeep.plugin.cli.bridge.CliToolBridge
import io.github.whywood.deepdeep.plugin.cli.card.CliCardModel
import io.github.whywood.deepdeep.plugin.cli.intValue
import io.github.whywood.deepdeep.plugin.cli.objValue
import io.github.whywood.deepdeep.plugin.cli.objectList
import io.github.whywood.deepdeep.plugin.cli.stringValue
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

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

private data class FeishuPaging(
    val hasMore: Boolean,
    val nextToken: String?,
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
    errorMapper = CliErrorMapper { host, request, _ -> feishuErrorHint(host, request) },
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

private fun feishuErrorHint(host: PluginHost, request: ToolExecutionResult): ToolResultDecision? {
    val text = request.result.text.orEmpty().trimEnd()
    if (text.isBlank()) return null
    val hint = when {
        text.contains("missing_scopes") || text.contains("99991679") || text.contains("99991672") ->
            host.t(
                "部分飞书权限还没开通：在飞书插件页的「应用权限」里开通后重新登录再试。",
                "Some Feishu permissions are missing: enable them under App permissions on the Feishu plugin page, then sign in again.",
            )
        text.contains("\"authentication\"") || text.contains("not logged in") ||
            text.contains("auth login") || text.contains("invalid token") ->
            host.t(
                "飞书登录已失效：在飞书插件页重新登录后再试。",
                "Your Feishu sign-in has expired: sign in again from the Feishu plugin page.",
            )
        else -> return null
    }
    return resultReplace(textResult("$text\n\n$hint", isError = true))
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
    val paging = feishuPaging(envelope, data)
    val rows = chats.map { chat ->
        val id = chat.stringValue("chat_id")
        val name = chat.stringValue("name") ?: id.orEmpty()
        FeishuCardItem(
            title = name,
            subtitle = chat.stringValue("description")?.let { CliText.sanitize(it) },
            meta = chatModeLabel(chat.stringValue("chat_mode"), zh),
            badge = chatMarkers(chat, zh).joinToString(" · ").takeIf { it.isNotBlank() },
            url = chat.stringValue("chat_app_link") ?: chatAppLink(id),
            avatar = chat.stringValue("avatar"),
        )
    }
    val header = if (zh) "飞书聊天列表（共 $total 个）" else "Feishu chats ($total total)"
    val text = buildString {
        appendLine(header)
        if (chats.isEmpty()) {
            appendLine(if (zh) "当前身份没有加入任何聊天。" else "The current identity is not in any chats.")
        } else {
            chats.forEachIndexed { index, chat -> appendLine(chatDetailRow(index, chat, zh)) }
            pagingHint(paging, total, zh)?.let { appendLine(it) }
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
            note = null,
        ),
    )
}

private fun chatDetailRow(index: Int, chat: JsonObject, zh: Boolean): String {
    val id = chat.stringValue("chat_id").orEmpty()
    val name = chat.stringValue("name") ?: id
    val mode = chatModeLabel(chat.stringValue("chat_mode"), zh)
    val facts = mutableListOf<String>()
    chat.stringValue("description")?.takeIf { it.isNotBlank() }?.let { description ->
        facts += if (zh) "描述：${CliText.sanitize(description)}" else "description: ${CliText.sanitize(description)}"
    }
    chat.stringValue("owner_id")?.takeIf { it.isNotBlank() }?.let { owner ->
        facts += if (zh) "群主：$owner" else "owner: $owner"
    }
    chat.stringValue("chat_status")?.takeIf { it.isNotBlank() }?.let { status ->
        facts += if (zh) "状态：${chatStatusLabel(status, zh)}（$status）" else "status: ${chatStatusLabel(status, zh)} ($status)"
    }
    chat.boolValue("external")?.let { external ->
        facts += if (zh) {
            "外部群：${if (external) "是" else "否"}（external: $external）"
        } else {
            "external: $external"
        }
    }
    chat.stringValue("p2p_target_id")?.takeIf { it.isNotBlank() }?.let { target ->
        val targetType = chat.stringValue("p2p_target_type").orEmpty()
        facts += if (zh) {
            "单聊对象：$target${if (targetType.isBlank()) "" else "（$targetType）"}"
        } else {
            "p2p target: $target${if (targetType.isBlank()) "" else " ($targetType)"}"
        }
    }
    chat.stringValue("avatar")?.takeIf { it.isNotBlank() }?.let { avatar ->
        facts += if (zh) "头像：$avatar" else "avatar: $avatar"
    }
    val link = chat.stringValue("chat_app_link") ?: chatAppLink(id)
    return buildString {
        append("${index + 1}. $name（$mode，chat_id: $id）")
        facts.forEach { fact ->
            appendLine()
            append("   $fact")
        }
        link?.let { url ->
            appendLine()
            append(if (zh) "   链接：$url" else "   link: $url")
        }
    }
}

private fun messagesObservation(host: PluginHost, command: FeishuCommand, envelope: JsonObject): FeishuObservation? {
    val data = envelope.objValue("data") ?: return null
    val messages = data.objectList("messages")
    val total = messages.size
    val zh = host.isChinese()
    val paging = feishuPaging(envelope, data)
    val chatId = messages.firstNotNullOfOrNull { it.stringValue("chat_id") } ?: command.chatId
    val rows = messages.map { message -> messageCardItem(message, zh) }
    val header = if (zh) "飞书消息（共 $total 条）" else "Feishu messages ($total total)"
    val context = buildString {
        val chat = chatId?.let { if (zh) "会话：$it" else "chat: $it" }
        if (chat != null) append(chat)
        val range = timeRangeLabel(command.start, command.end, zh)
        if (range != null) append(if (isEmpty()) "" else (if (zh) "；" else "; ")).append(range)
    }.ifBlank { null }
    val text = buildString {
        appendLine(header)
        context?.let { appendLine(it) }
        if (messages.isEmpty()) {
            appendLine(if (zh) "这个时间段没有消息。" else "No messages in this time range.")
        } else {
            messages.forEachIndexed { index, message ->
                appendLine(messageDetailRow(index, message, zh))
            }
            pagingHint(paging, total, zh)?.let { appendLine(it) }
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
            note = null,
        ),
    )
}

private fun messageDetailRow(index: Int, message: JsonObject, zh: Boolean): String {
    val messageId = message.stringValue("message_id").orEmpty()
    val msgType = message.stringValue("msg_type").orEmpty()
    val sender = message.objValue("sender")
    val senderName = sender?.stringValue("name") ?: sender?.stringValue("id")
        ?: (if (zh) "未知发送者" else "unknown sender")
    val time = CliTime.fullDateTime(message.stringValue("create_time"))
        ?: message.stringValue("create_time")
        ?: (if (zh) "未知时间" else "unknown time")
    val deleted = message.boolValue("deleted") == true
    val updated = message.boolValue("updated") == true
    val updateTime = CliTime.fullDateTime(message.stringValue("update_time")) ?: message.stringValue("update_time")
    val mentions = message.objectList("mentions")
    val threadId = message.stringValue("thread_id")
    val body = messageBody(message.stringValue("content").orEmpty(), mentions, zh)
    val bodyLines = body.lineSequence().map { it.trimEnd() }.filter { it.isNotBlank() }.toList()
    return buildString {
        append("${index + 1}. [$time] $senderName（${msgTypeLabel(msgType, zh)}）")
        appendLine()
        append(if (zh) "   发送者：$senderName" else "   sender: $senderName")
        sender?.stringValue("id")?.let { id ->
            val idType = sender.stringValue("id_type").orEmpty()
            append(if (zh) "；ID：$id${if (idType.isBlank()) "" else "（$idType）"}" else "; id: $id${if (idType.isBlank()) "" else " ($idType)"}")
        }
        sender?.stringValue("sender_type")?.let { senderType ->
            append(if (zh) "；身份：$senderType" else "; identity: $senderType")
        }
        appendLine()
        append(if (zh) "   消息 ID：$messageId" else "   message id: $messageId")
        message.stringValue("message_position")?.let { position ->
            append(if (zh) "；位置：$position" else "; position: $position")
        }
        appendLine()
        append(
            when {
                deleted -> if (zh) "   状态：已撤回（deleted: true）" else "   status: recalled (deleted: true)"
                updated -> if (zh) {
                    "   状态：已编辑（updated: true；更新时间：${updateTime ?: "未知"}）"
                } else {
                    "   status: edited (updated: true; updated at: ${updateTime ?: "unknown"})"
                }
                else -> if (zh) {
                    "   状态：未撤回、未编辑（deleted: false，updated: false）"
                } else {
                    "   status: not recalled, not edited (deleted: false, updated: false)"
                }
            },
        )
        if (mentions.isNotEmpty()) {
            appendLine()
            val names = mentions.mapNotNull { mention ->
                val name = mention.stringValue("name") ?: return@mapNotNull null
                val id = mention.stringValue("id")
                if (id.isNullOrBlank()) "@$name" else "@$name（$id）"
            }
            append(if (zh) "   提及：${names.joinToString("、")}" else "   mentions: ${names.joinToString(", ")}")
        }
        threadId?.takeIf { it.isNotBlank() }?.let { thread ->
            appendLine()
            append(if (zh) "   话题：$thread" else "   thread: $thread")
        }
        appendLine()
        if (bodyLines.size <= 1) {
            append(if (zh) "   正文：${bodyLines.firstOrNull() ?: "（无文字内容）"}" else "   body: ${bodyLines.firstOrNull() ?: "(no text)"}")
        } else {
            append(if (zh) "   正文：" else "   body:")
            bodyLines.forEach { line ->
                appendLine()
                append("     $line")
            }
        }
        message.stringValue("message_app_link")?.let { link ->
            appendLine()
            append(if (zh) "   链接：$link" else "   link: $link")
        }
    }
}

private fun messageCardItem(message: JsonObject, zh: Boolean): FeishuCardItem {
    val msgType = message.stringValue("msg_type").orEmpty()
    val sender = message.objValue("sender")
    val senderName = sender?.stringValue("name") ?: sender?.stringValue("id")
        ?: (if (zh) "未知发送者" else "unknown sender")
    val content = message.stringValue("content").orEmpty()
    val body = messageBody(content, message.objectList("mentions"), zh)
    val attachments = messageAttachmentNames(content)
    val preview = body.lineSequence().firstOrNull { it.isNotBlank() }
        ?: if (zh) "（无文字内容）" else "(no text)"
    return FeishuCardItem(
        title = CliText.clip(preview, 80),
        subtitle = buildList {
            add(senderName)
            add(msgTypeLabel(msgType, zh))
            addAll(attachments)
        }.joinToString(" · "),
        meta = CliTime.shortDateTime(message.stringValue("create_time")),
        badge = messageMarkers(message, zh).joinToString(" · ").takeIf { it.isNotBlank() },
        url = message.stringValue("message_app_link"),
        body = stripAttachmentLines(body),
    )
}

private val FILE_NAME_ATTRIBUTE = Regex("""<file\b[^>]*\bname="([^"]*)"""")

private fun messageAttachmentNames(content: String): List<String> =
    FILE_NAME_ATTRIBUTE.findAll(content)
        .map { it.groupValues[1].trim() }
        .filter { it.isNotBlank() }
        .distinct()
        .toList()

private val ATTACHMENT_LABELS = listOf(
    "附件：", "附件:", "文件：", "文件:", "Attachment:", "Attachment：", "File:", "File：",
)

private fun stripAttachmentLines(body: String): String =
    body.lineSequence()
        .filterNot { line -> ATTACHMENT_LABELS.any { line.trim().startsWith(it) } }
        .joinToString("\n")
        .trim()

private fun messageMarkers(message: JsonObject, zh: Boolean): List<String> = buildList {
    if (message.boolValue("deleted") == true) add(if (zh) "已撤回" else "Recalled")
    if (message.boolValue("updated") == true) add(if (zh) "已编辑" else "Edited")
    if (!message.stringValue("thread_id").isNullOrBlank()) add(if (zh) "话题" else "Thread")
    message.objectList("mentions").mapNotNull { it.stringValue("name") }.forEach { add("@$it") }
}

private val JSON_SKIP_KEYS = setOf(
    "tag", "type", "key", "token", "href", "url", "style", "align", "text_size", "text_color",
    "bg_color", "corner_radius", "id", "file_key", "image_key", "img_key", "emoji", "note",
)

private val RESOURCE_TAG = Regex("""<(file|folder|audio|video|media|img|image)\b[^>]*/?>""", RegexOption.IGNORE_CASE)
private val RESOURCE_NAME = Regex("""\bname="([^"]*)"""")
private val RESOURCE_DURATION = Regex("""\bduration="([^"]*)"""")
private val IMAGE_MARKDOWN = Regex("""!\[[^\]]*]\(([^)]+)\)""")

private fun messageBody(content: String, mentions: List<JsonObject>, zh: Boolean): String {
    val trimmed = content.trim()
    val structured = if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
        flattenJsonContent(trimmed) ?: trimmed
    } else {
        trimmed
    }
    val withResources = renderResourceTags(structured, zh)
    val withMentions = applyMentionNames(withResources, mentions)
    return withMentions
        .replace("\r\n", "\n")
        .lineSequence()
        .map { it.trimEnd() }
        .joinToString("\n")
        .trim()
        .ifBlank { if (zh) "（无文字内容）" else "(no text)" }
}

private fun flattenJsonContent(raw: String): String? {
    val element = runCatching { Json.parseToJsonElement(raw) }.getOrNull() ?: return null
    val parts = mutableListOf<String>()
    collectJsonStrings(element, parts)
    return parts.joinToString("\n").trim().takeIf { it.isNotBlank() }
}

private fun collectJsonStrings(element: JsonElement, out: MutableList<String>) {
    when (element) {
        is JsonObject -> element.forEach { (key, value) ->
            if (key.lowercase(Locale.ROOT) !in JSON_SKIP_KEYS) collectJsonStrings(value, out)
        }
        is JsonArray -> element.forEach { collectJsonStrings(it, out) }
        is JsonPrimitive -> {
            if (element.isString) element.contentOrNull?.takeIf { it.isNotBlank() }?.let(out::add)
        }
    }
}

private fun renderResourceTags(text: String, zh: Boolean): String =
    RESOURCE_TAG.replace(text) { match ->
        val tag = match.groupValues[1].lowercase(Locale.ROOT)
        val name = RESOURCE_NAME.find(match.value)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
        val duration = RESOURCE_DURATION.find(match.value)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
        when (tag) {
            "file" -> name?.let { if (zh) "文件：$it" else "file: $it" } ?: if (zh) "文件" else "file"
            "folder" -> name?.let { if (zh) "文件夹：$it" else "folder: $it" } ?: if (zh) "文件夹" else "folder"
            "audio" -> duration?.let { if (zh) "语音（$it）" else "audio ($it)" } ?: if (zh) "语音" else "audio"
            "video", "media" -> if (zh) "视频" else "video"
            else -> if (zh) "图片" else "image"
        }
    }.replace(IMAGE_MARKDOWN) { match ->
        val token = match.groupValues[1].trim()
        if (zh) "图片（$token）" else "image ($token)"
    }

private fun applyMentionNames(text: String, mentions: List<JsonObject>): String {
    if (mentions.isEmpty()) return text
    var result = text
    mentions.forEach { mention ->
        val key = mention.stringValue("key") ?: return@forEach
        val name = mention.stringValue("name") ?: return@forEach
        result = result.replace(key, "@$name")
        if (!key.startsWith("@")) result = result.replace("@$key", "@$name")
    }
    return result
}

private fun agendaObservation(host: PluginHost, command: FeishuCommand, envelope: JsonObject): FeishuObservation? {
    val events = envelope.objectList("data")
    val total = events.size
    val zh = host.isChinese()
    val rows = events.map { event -> agendaCardItem(event, zh) }
    val header = if (zh) "飞书日程（共 $total 个）" else "Feishu agenda ($total events)"
    val range = timeRangeLabel(command.start, command.end, zh)
    val text = buildString {
        appendLine(header)
        range?.let { appendLine(it) }
        if (events.isEmpty()) {
            appendLine(if (zh) "这段时间没有日程，这是正常的空结果。" else "No events in this time range; that is a normal empty result.")
        } else {
            events.forEachIndexed { index, event -> appendLine(agendaDetailRow(index, event, zh)) }
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

private fun agendaDetailRow(index: Int, event: JsonObject, zh: Boolean): String {
    val eventId = event.stringValue("event_id").orEmpty()
    val summary = event.stringValue("summary")?.takeIf { it.isNotBlank() }
        ?: if (zh) "（无标题日程）" else "(untitled event)"
    val start = event.objValue("start_time")
    val end = event.objValue("end_time")
    val allDay = start?.stringValue("date") != null && start.stringValue("datetime") == null
    val location = event.objValue("location")?.stringValue("name")
    val freeBusy = event.stringValue("free_busy_status")
    val rsvp = event.stringValue("self_rsvp_status")
    val description = event.stringValue("description")
    return buildString {
        append("${index + 1}. $summary")
        appendLine()
        when {
            allDay -> {
                val startDate = start.stringValue("date")
                val endDate = end?.stringValue("date")
                append(if (zh) "   时间：全天" else "   time: all day")
                startDate?.let { append(if (zh) "，开始日期：$it" else ", start date: $it") }
                endDate?.let { append(if (zh) "，结束日期：$it" else ", end date: $it") }
            }
            else -> {
                val startText = eventMomentText(start, zh)
                val endText = eventMomentText(end, zh)
                append(if (zh) "   时间：" else "   time: ")
                append(listOfNotNull(startText, endText).joinToString(if (zh) " 至 " else " to "))
            }
        }
        val timezone = start?.stringValue("timezone") ?: end?.stringValue("timezone")
        timezone?.let { zone ->
            append(if (zh) "（时区：$zone）" else " (timezone: $zone)")
        }
        if (timezone.isNullOrBlank()) {
            val rawTimestamp = start?.stringValue("timestamp") ?: end?.stringValue("timestamp")
            rawTimestamp?.let { stamp ->
                append(if (zh) "（时间戳：$stamp）" else " (timestamp: $stamp)")
            }
        }
        appendLine()
        append(if (zh) "   event_id：$eventId" else "   event_id: $eventId")
        location?.takeIf { it.isNotBlank() }?.let { place ->
            appendLine()
            append(if (zh) "   地点：${CliText.sanitize(place)}" else "   location: ${CliText.sanitize(place)}")
        }
        if (!freeBusy.isNullOrBlank() || !rsvp.isNullOrBlank()) {
            appendLine()
            append(if (zh) "   忙闲：${freeBusy ?: "未知"}；我的答复：${rsvp ?: "未知"}" else "   busy status: ${freeBusy ?: "unknown"}; my RSVP: ${rsvp ?: "unknown"}")
        }
        description?.takeIf { it.isNotBlank() }?.let { text ->
            appendLine()
            val lines = text.replace("\r\n", "\n").lineSequence().map { it.trimEnd() }.filter { it.isNotBlank() }.toList()
            if (lines.size <= 1) {
                append(if (zh) "   描述：${lines.firstOrNull().orEmpty()}" else "   description: ${lines.firstOrNull().orEmpty()}")
            } else {
                append(if (zh) "   描述：" else "   description:")
                lines.forEach { line ->
                    appendLine()
                    append("     $line")
                }
            }
        }
    }
}

private fun agendaCardItem(event: JsonObject, zh: Boolean): FeishuCardItem {
    val summary = event.stringValue("summary")?.takeIf { it.isNotBlank() }
        ?: if (zh) "（无标题日程）" else "(untitled event)"
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
    val meta = if (allDay) {
        start.stringValue("date")
    } else {
        CliTime.fullDateTime(start?.stringValue("datetime"))
    }
    return FeishuCardItem(
        title = summary,
        subtitle = listOfNotNull(timeLabel, location?.takeIf { it.isNotBlank() }?.let { CliText.sanitize(it) })
            .joinToString(" · "),
        meta = meta,
        body = event.stringValue("description")?.takeIf { it.isNotBlank() },
    )
}

private fun eventMomentText(time: JsonObject?, zh: Boolean): String? {
    if (time == null) return null
    time.stringValue("datetime")?.let { return CliTime.fullDateTime(it) }
    time.stringValue("date")?.let { return it }
    time.stringValue("timestamp")?.let { raw ->
        return epochMillisText(raw) ?: epochSecondsText(raw) ?: raw
    }
    return null
}

private fun docsSearchObservation(host: PluginHost, command: FeishuCommand, envelope: JsonObject): FeishuObservation? {
    val data = envelope.objValue("data") ?: return null
    val results = data.objectList("results")
    val total = results.size
    val zh = host.isChinese()
    val paging = feishuPaging(envelope, data)
    val rows = results.map { result -> docsSearchCardItem(result, zh) }
    val query = command.query?.trim()?.takeIf { it.isNotBlank() }
    val header = if (zh) "飞书云文档（共 $total 个）" else "Feishu docs ($total total)"
    val context = query?.let {
        if (zh) "搜索：${CliText.oneLine(it, 60)}" else "search: ${CliText.oneLine(it, 60)}"
    }
    val text = buildString {
        appendLine(header)
        context?.let { appendLine(it) }
        if (results.isEmpty()) {
            appendLine(if (zh) "没有找到匹配的文档，可以换关键词再试。" else "No matching docs; try another keyword.")
        } else {
            results.forEachIndexed { index, result -> appendLine(docsSearchDetailRow(index, result, zh)) }
            pagingHint(paging, total, zh)?.let { appendLine(it) }
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
            note = null,
        ),
    )
}

private fun docsSearchCardItem(result: JsonObject, zh: Boolean): FeishuCardItem {
    val title = CliText.sanitize(result.stringValue("title").orEmpty())
        .ifBlank { if (zh) "（无标题文档）" else "(untitled doc)" }
    val type = result.stringValue("doc_type").orEmpty()
    val edited = CliTime.fullDateTime(result.stringValue("edit_time_iso"))
    val summary = docSummary(result)
    return FeishuCardItem(
        title = title,
        subtitle = listOfNotNull(
            type.ifBlank { null },
            edited?.let { if (zh) "编辑于 $it" else "edited $it" },
        ).joinToString(" · "),
        body = summary,
        url = result.stringValue("url"),
    )
}

private fun docsSearchDetailRow(index: Int, result: JsonObject, zh: Boolean): String {
    val title = CliText.sanitize(result.stringValue("title").orEmpty())
        .ifBlank { if (zh) "（无标题文档）" else "(untitled doc)" }
    val type = result.stringValue("doc_type").orEmpty()
    val token = result.stringValue("token").orEmpty()
    val url = result.stringValue("url")
    val created = docDateTime(result.stringValue("create_time_iso"), result.stringValue("create_time"))
    val edited = docDateTime(result.stringValue("edit_time_iso"), result.stringValue("edit_time"))
    val owner = result.stringValue("owner_id")
    val summary = docSummary(result)
    val meta = resultMetaText(result.objValue("result_meta"))
    return buildString {
        append("${index + 1}. $title")
        if (type.isNotBlank()) append("（$type）")
        appendLine()
        created?.let { appendLine(if (zh) "   创建时间：$it" else "   created: $it") }
        edited?.let { appendLine(if (zh) "   编辑时间：$it" else "   edited: $it") }
        owner?.takeIf { it.isNotBlank() }?.let { appendLine(if (zh) "   owner：$it" else "   owner: $it") }
        meta?.let { appendLine(if (zh) "   result_meta：$it" else "   result_meta: $it") }
        summary?.let { appendLine(if (zh) "   摘要：$it" else "   summary: $it") }
        append(if (zh) "   token：$token" else "   token: $token")
        url?.let { link ->
            appendLine()
            append(if (zh) "   链接：$link" else "   link: $link")
        }
    }
}

private fun docSummary(result: JsonObject): String? =
    (result.stringValue("summary") ?: result.stringValue("summary_highlighted"))
        ?.let { CliText.sanitize(it) }
        ?.takeIf { it.isNotBlank() }

private fun docDateTime(iso: String?, epoch: String?): String? {
    CliTime.fullDateTime(iso)?.let { return it }
    return epoch?.trim()?.toLongOrNull()?.let { CliTime.shortEpochSeconds(it.toString()) }
}

private fun resultMetaText(meta: JsonObject?): String? {
    if (meta == null || meta.isEmpty()) return null
    val parts = meta.entries.mapNotNull { (key, value) ->
        jsonFlatValue(value)?.let { "$key=$it" }
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString("；")
}

private fun jsonFlatValue(value: JsonElement): String? = when (value) {
    is JsonPrimitive -> value.contentOrNull?.takeIf { it.isNotBlank() }
    is JsonArray -> value.mapNotNull { jsonFlatValue(it) }.joinToString(",").takeIf { it.isNotBlank() }
    is JsonObject -> value.entries
        .joinToString(",") { (key, item) -> "$key=${jsonFlatValue(item).orEmpty()}" }
        .takeIf { it.isNotBlank() }
}

private fun docsReadObservation(host: PluginHost, command: FeishuCommand, envelope: JsonObject): FeishuObservation? {
    val data = envelope.objValue("data") ?: return null
    val document = data.objValue("document") ?: return null
    val documentId = document.stringValue("document_id") ?: command.doc ?: ""
    val content = document.stringValue("content").orEmpty()
    val plain = CliText.cleanDocContent(content)
    val plainLines = plain.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
    val titleFromXml = content.takeIf { it.contains("<title>") }
        ?.substringAfter("<title>")?.substringBefore("</title>")
        ?.let { CliText.sanitize(it) }
    val titleFromMarkdown = plainLines.firstOrNull()
        ?.removePrefix("#")?.trim()?.takeIf { it.isNotEmpty() }
        ?.let { CliText.sanitize(it) }
    val title = titleFromXml ?: titleFromMarkdown
    val zh = host.isChinese()
    val url = feishuDocUrl(command.doc ?: documentId)
    val revision = document.intValue("revision_id") ?: 0
    val note = if (zh) "正文约 ${plain.length} 字" else "${plain.length} chars"
    val text = buildString {
        appendLine(if (zh) "飞书文档：${title ?: documentId}" else "Feishu document: ${title ?: documentId}")
        appendLine(
            if (zh) {
                "文档 ID：$documentId；版本：$revision"
            } else {
                "document id: $documentId; revision: $revision"
            },
        )
        if (plainLines.isEmpty()) {
            appendLine(if (zh) "文档正文为空。" else "The document is empty.")
        } else {
            appendLine(if (zh) "正文（共 ${plainLines.size} 行）：" else "content (${plainLines.size} lines):")
            plainLines.forEach { appendLine(it) }
        }
        url?.let { appendLine(if (zh) "链接：$it" else "link: $it") }
    }.trim()
    return FeishuObservation(
        summary = if (zh) "已读取文档「${title ?: documentId}」" else "Read document \"${title ?: documentId}\"",
        text = text,
        card = FeishuCardModel(
            kind = FeishuCardTypes.KIND_DOCS_READ,
            context = title,
            total = plainLines.size,
            items = if (plainLines.isEmpty()) {
                emptyList()
            } else {
                listOf(FeishuCardItem(title = title ?: documentId, body = plain))
            },
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
    val paging = feishuPaging(envelope, data)
    val rows = tasks.map { task -> taskCardItem(task, zh) }
    val open = tasks.count { it.boolValue("completed") != true }
    val header = if (zh) "飞书任务（共 $total 条，未完成 $open 条）" else "Feishu tasks ($total total, $open open)"
    val text = buildString {
        appendLine(header)
        if (tasks.isEmpty()) {
            appendLine(if (zh) "没有待办任务，这是正常的空结果。" else "No tasks; that is a normal empty result.")
        } else {
            tasks.forEachIndexed { index, task -> appendLine(taskDetailRow(index, task, zh)) }
            pagingHint(paging, total, zh)?.let { appendLine(it) }
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
            note = null,
        ),
    )
}

private fun taskCardItem(task: JsonObject, zh: Boolean): FeishuCardItem {
    val summary = task.stringValue("summary")?.takeIf { it.isNotBlank() }
        ?: if (zh) "（无标题任务）" else "(untitled task)"
    val completed = task.boolValue("completed") == true
    val due = CliTime.fullDateTime(task.stringValue("due_at"))
    val completedAt = CliTime.fullDateTime(task.stringValue("completed_at"))
    val subtitle = when {
        completed && completedAt != null -> if (zh) "完成于 $completedAt" else "completed $completedAt"
        due != null -> if (zh) "截止 $due" else "due $due"
        else -> if (zh) "无截止时间" else "no due date"
    }
    val members = taskMembers(task)
    return FeishuCardItem(
        title = summary,
        subtitle = subtitle,
        badge = if (completed) (if (zh) "已完成" else "Done") else (if (zh) "待办" else "To do"),
        meta = CliTime.shortDateTime(task.stringValue("due_at")),
        url = task.stringValue("url"),
        body = if (members.isEmpty()) null else (if (zh) "成员：${members.joinToString("、")}" else "members: ${members.joinToString(", ")}"),
    )
}

private fun taskDetailRow(index: Int, task: JsonObject, zh: Boolean): String {
    val guid = task.stringValue("guid").orEmpty()
    val summary = task.stringValue("summary")?.takeIf { it.isNotBlank() }
        ?: if (zh) "（无标题任务）" else "(untitled task)"
    val completed = task.boolValue("completed") == true
    val due = CliTime.fullDateTime(task.stringValue("due_at"))
    val completedAt = CliTime.fullDateTime(task.stringValue("completed_at"))
    val createdAt = CliTime.fullDateTime(task.stringValue("created_at"))
    val start = task.objValue("start")
    val startText = start?.stringValue("timestamp")?.let { epochMillisText(it) ?: epochSecondsText(it) ?: it }
    val members = taskMembers(task)
    val url = task.stringValue("url")
    return buildString {
        append("${index + 1}. $summary（")
        append(if (completed) (if (zh) "已完成" else "done") else (if (zh) "未完成" else "open"))
        append(if (zh) "，guid: $guid）" else ", guid: $guid)")
        appendLine()
        if (createdAt != null) appendLine(if (zh) "   创建：$createdAt" else "   created: $createdAt")
        if (completed) {
            completedAt?.let { appendLine(if (zh) "   完成时间：$it" else "   completed: $it") }
        } else {
            due?.let { appendLine(if (zh) "   截止：$it" else "   due: $it") }
        }
        start?.let { value ->
            val allDay = value.boolValue("is_all_day") == true
            val label = if (allDay) (if (zh) "全天" else "all day") else (startText ?: (if (zh) "时间未知" else "time unknown"))
            appendLine(if (zh) "   开始：$label" else "   start: $label")
        }
        if (members.isNotEmpty()) {
            appendLine(if (zh) "   成员：${members.joinToString("、")}" else "   members: ${members.joinToString(", ")}")
        }
        url?.let { link ->
            appendLine(if (zh) "   链接：$link" else "   link: $link")
        }
    }
}

private fun taskMembers(task: JsonObject): List<String> =
    task.objectList("members").mapNotNull { member ->
        val id = member.stringValue("id") ?: return@mapNotNull null
        val role = member.stringValue("role").orEmpty()
        val type = member.stringValue("type").orEmpty()
        val facts = listOf(role, type).filter { it.isNotBlank() }.joinToString("，")
        if (facts.isBlank()) id else "$id（$facts）"
    }

private fun feishuPaging(envelope: JsonObject, data: JsonObject?): FeishuPaging {
    val hasMore = data?.boolValue("has_more") == true
    val token = data?.stringValue("page_token")
    if (hasMore) return FeishuPaging(hasMore = true, nextToken = token)
    val pagination = envelope.objValue("meta")?.objValue("pagination")
    if (pagination?.boolValue("complete") == false) {
        return FeishuPaging(hasMore = true, nextToken = pagination.stringValue("next_token") ?: token)
    }
    return FeishuPaging(hasMore = false, nextToken = token)
}

private fun pagingHint(paging: FeishuPaging, count: Int, zh: Boolean): String? {
    if (!paging.hasMore) return null
    val token = paging.nextToken
    return when {
        !token.isNullOrBlank() -> if (zh) {
            "本页 $count 条，还有更多：用 --page-token $token 继续"
        } else {
            "This page has $count rows and more are available: continue with --page-token $token"
        }
        else -> if (zh) {
            "本页 $count 条，还有更多：可用 --page-all 继续拉取（本次未返回 page_token）"
        } else {
            "This page has $count rows and more are available: use --page-all to continue (no page_token returned)"
        }
    }
}

private fun chatModeLabel(mode: String?, zh: Boolean): String = when (mode) {
    "p2p" -> if (zh) "单聊" else "direct"
    "topic" -> if (zh) "话题群" else "topic group"
    else -> if (zh) "群聊" else "group"
}

private fun chatStatusLabel(status: String, zh: Boolean): String = when (status) {
    "normal" -> if (zh) "正常" else "normal"
    "dissolved" -> if (zh) "已解散" else "dissolved"
    "dissolved_save" -> if (zh) "已解散（可恢复）" else "dissolved (recoverable)"
    else -> status
}

private fun chatMarkers(chat: JsonObject, zh: Boolean): List<String> = buildList {
    if (chat.boolValue("external") == true) add(if (zh) "外部" else "External")
    when (chat.stringValue("chat_status")) {
        "dissolved" -> add(if (zh) "已解散" else "Dissolved")
        "dissolved_save" -> add(if (zh) "已解散" else "Dissolved")
    }
}

private fun eventTimeLabel(time: JsonObject?): String? {
    if (time == null) return null
    time.stringValue("datetime")?.let { return CliTime.shortDateTime(it) }
    time.stringValue("date")?.let { return it }
    time.stringValue("timestamp")?.let { raw ->
        return epochMillisText(raw) ?: epochSecondsText(raw) ?: raw
    }
    return null
}

private fun epochMillisText(raw: String?): String? {
    val millis = raw?.trim()?.toLongOrNull() ?: return null
    if (millis < 100_000_000_000L) return null
    return runCatching {
        Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT))
    }.getOrNull()
}

private fun epochSecondsText(raw: String?): String? {
    val seconds = raw?.trim()?.toLongOrNull() ?: return null
    if (seconds >= 100_000_000_000L) return null
    return runCatching {
        Instant.ofEpochSecond(seconds).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT))
    }.getOrNull()
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
