package io.github.whywood.deepdeep.plugin.mail.tools

import io.github.whywood.deepdeep.plugin.api.ChatAttachment
import io.github.whywood.deepdeep.plugin.api.Contributions
import io.github.whywood.deepdeep.plugin.api.PluginHost
import io.github.whywood.deepdeep.plugin.api.PluginHooks
import io.github.whywood.deepdeep.plugin.api.PluginTool
import io.github.whywood.deepdeep.plugin.api.PluginToolCall
import io.github.whywood.deepdeep.plugin.api.PluginToolResult
import io.github.whywood.deepdeep.plugin.api.chatAttachment
import io.github.whywood.deepdeep.plugin.api.chatMessageOptions
import io.github.whywood.deepdeep.plugin.api.contributions
import io.github.whywood.deepdeep.plugin.api.localizedText
import io.github.whywood.deepdeep.plugin.api.pluginTool
import io.github.whywood.deepdeep.plugin.api.textResult
import io.github.whywood.deepdeep.plugin.mail.DraftAttachment
import io.github.whywood.deepdeep.plugin.mail.MailAccount
import io.github.whywood.deepdeep.plugin.mail.MailDraft
import io.github.whywood.deepdeep.plugin.mail.MailFolderRole
import io.github.whywood.deepdeep.plugin.mail.MailMessageDetail
import io.github.whywood.deepdeep.plugin.mail.MailMessageSummary
import io.github.whywood.deepdeep.plugin.mail.MailRepository
import io.github.whywood.deepdeep.plugin.mail.MailSendResult
import io.github.whywood.deepdeep.plugin.mail.mime.HtmlText
import io.github.whywood.deepdeep.plugin.mail.mime.MailComposeLimits
import io.github.whywood.deepdeep.plugin.mail.ui.displayPreview
import io.github.whywood.deepdeep.plugin.mail.ui.formatFileSize
import io.github.whywood.deepdeep.plugin.mail.ui.readableError
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

private const val CARD_DETAILS_KEY = "deepdeep.card"
private const val SEARCH_DEFAULT_LIMIT = 20
private const val SEARCH_MAX_LIMIT = 50

private data class MailCard(
    val customType: String,
    val title: String,
    val payload: JsonObject,
)

private fun PluginToolCall.withCard(text: String, card: MailCard): PluginToolResult {
    val details = buildJsonObject {
        put(
            CARD_DETAILS_KEY,
            buildJsonObject {
                put("customType", card.customType)
                put("title", card.title)
                put("payload", card.payload)
            },
        )
    }
    return textResult(text, details = details)
}

private fun guardedExecute(
    host: PluginHost,
    block: suspend (PluginToolCall) -> PluginToolResult,
): suspend (PluginToolCall) -> PluginToolResult = { call ->
    try {
        block(call)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        runCatching { host.logs().error("mail tool failed", error) }
        textResult(
            host.readableError(
                error,
                fallbackZh = "操作失败，请重试。",
                fallbackEn = "Something went wrong. Try again.",
            ),
            isError = true,
        )
    }
}

fun mailTools(
    host: PluginHost,
    repository: MailRepository,
    hooks: PluginHooks = PluginHooks.None,
): Contributions = contributions(
    tools = listOf(
        searchTool(host, repository),
        readTool(host, repository),
        saveAttachmentTool(host, repository),
        draftTool(host, repository),
        sendTool(host, repository),
        markTool(host, repository),
        archiveTool(host, repository),
    ),
    hooks = hooks,
)

private val sharedAttachmentKeys = ConcurrentHashMap.newKeySet<String>()

private const val MAX_CHAT_ATTACHMENTS = 3
private const val MAX_CHAT_ATTACHMENT_BYTES = 10L * 1024 * 1024

private fun searchTool(host: PluginHost, repository: MailRepository): PluginTool = pluginTool(
    name = "mail_search",
    label = "搜索邮件",
    description = "在已连接的邮箱中按关键词、账号、文件夹和未读状态搜索邮件，返回带文件夹、收发件人、预览、大小与会话的摘要列表；需要正文时再用 mail_read 打开。",
    parameters = parameters(
        required = emptyList(),
        "query" to stringProperty("关键词，匹配主题、发件人、收件人和预览；可留空表示最近邮件"),
        "account" to stringProperty("邮箱地址或账号 id，可留空表示全部账号"),
        "folder" to enumProperty(
            "文件夹：all（全部）、inbox（收件箱）、sent（已发送）、drafts（草稿）、archive（归档），默认 all",
            listOf("all", "inbox", "sent", "drafts", "archive"),
        ),
        "unread_only" to booleanProperty("只看未读"),
        "offset" to intProperty("从第几封开始返回，默认 0；用于继续翻页", minimum = 0),
        "limit" to intProperty("返回数量，1-50，默认 20", minimum = 1, maximum = SEARCH_MAX_LIMIT),
    ),
    execute = guardedExecute(host) { call -> search(host, repository, call) },
)

private fun readTool(host: PluginHost, repository: MailRepository): PluginTool = pluginTool(
    name = "mail_read",
    label = "读取邮件",
    description = "读取一封邮件的完整纯文本正文、HTML 原文、全部头字段、收发件人与全部附件（含内嵌附件及其 Content-ID）。",
    parameters = parameters(
        required = listOf("message_id"),
        "message_id" to stringProperty("mail_search 返回的邮件 id"),
    ),
    execute = guardedExecute(host) { call -> read(host, repository, call) },
)

private fun saveAttachmentTool(host: PluginHost, repository: MailRepository): PluginTool = pluginTool(
    name = "mail_save_attachment",
    label = "保存附件",
    description = "把一封邮件的附件保存到手机的下载目录，返回保存结果。附件 id 见 mail_read 输出。",
    parameters = parameters(
        required = listOf("message_id", "attachment_id"),
        "message_id" to stringProperty("邮件 id"),
        "attachment_id" to stringProperty("mail_read 返回的附件 id，例如 att-0"),
        "account" to stringProperty("邮箱地址或账号 id，可留空"),
    ),
    execute = guardedExecute(host) { call -> saveAttachment(host, repository, call) },
)

private fun draftTool(host: PluginHost, repository: MailRepository): PluginTool = pluginTool(
    name = "mail_draft",
    label = "起草邮件",
    description = "生成一封邮件草稿并返回草稿 id。不会直接发送；用户明确要求发送时，生成后再调用 mail_send。可以通过 attachments 附带本地文件。",
    parameters = parameters(
        required = listOf("to", "subject", "body"),
        "to" to stringProperty("收件人，多个用逗号分隔"),
        "cc" to stringProperty("抄送，多个用逗号分隔，可留空"),
        "subject" to stringProperty("主题"),
        "body" to stringProperty("正文纯文本"),
        "reply_to" to stringProperty("要回复的邮件 id，可留空"),
        "attachments" to stringProperty("附件文件的本地绝对路径，多个用英文逗号分隔，可留空"),
    ),
    execute = guardedExecute(host) { call -> draft(host, repository, call) },
)

private fun sendTool(host: PluginHost, repository: MailRepository): PluginTool = pluginTool(
    name = "mail_send",
    label = "发送邮件",
    description = "发送一封 mail_draft 生成的草稿。只在用户明确要求发送时使用；发送后如实报告结果。",
    parameters = parameters(
        required = listOf("draft_id"),
        "draft_id" to stringProperty("mail_draft 返回的草稿 id"),
    ),
    execute = guardedExecute(host) { call -> send(host, repository, call) },
)

private fun markTool(host: PluginHost, repository: MailRepository): PluginTool = pluginTool(
    name = "mail_mark",
    label = "标记邮件",
    description = "把一封邮件标记为已读或未读。",
    parameters = parameters(
        required = listOf("message_id"),
        "message_id" to stringProperty("邮件 id"),
        "unread" to booleanProperty("true 表示未读"),
    ),
    execute = guardedExecute(host) { call -> mark(repository, call) },
)

private fun archiveTool(host: PluginHost, repository: MailRepository): PluginTool = pluginTool(
    name = "mail_archive",
    label = "归档邮件",
    description = "把一封邮件移动到归档文件夹（服务商没有归档文件夹时不可用）。",
    parameters = parameters(
        required = listOf("message_id"),
        "message_id" to stringProperty("邮件 id"),
    ),
    execute = guardedExecute(host) { call -> archive(repository, call) },
)

private suspend fun search(
    host: PluginHost,
    repository: MailRepository,
    call: PluginToolCall,
): PluginToolResult {
    val query = call.args.str("query").trim()
    val unreadOnly = call.args.bool("unread_only")
    val account = accountOf(repository, call.args.str("account"))
    val folder = when (val value = call.args.str("folder").trim().lowercase()) {
        "", "all" -> null
        "inbox" -> MailFolderRole.INBOX
        "sent" -> MailFolderRole.SENT
        "drafts" -> MailFolderRole.DRAFTS
        "archive" -> MailFolderRole.ARCHIVE
        else -> return textResult("folder 只能是 all、inbox、sent、drafts、archive，收到「$value」。", isError = true)
    }
    val offset = (call.args.int("offset") ?: 0).coerceAtLeast(0)
    val limit = (call.args.int("limit") ?: SEARCH_DEFAULT_LIMIT).coerceIn(1, SEARCH_MAX_LIMIT)
    val matches = repository.search(query, account?.id, folder)
        .filter { !unreadOnly || it.unread }
    val total = matches.size
    val items = matches.drop(offset).take(limit)
    val accounts = repository.accounts().associateBy { it.id }
    val card = searchCard(
        host = host,
        query = query,
        account = account,
        folder = folder,
        unreadOnly = unreadOnly,
        items = items,
        total = total,
        accounts = accounts,
    )
    return call.withCard(
        mailSearchResultText(
            items = items,
            total = total,
            offset = offset,
            folderLabel = { role -> mailFolderLabel(host, role) },
            accounts = accounts,
        ),
        card,
    )
}

internal const val MAIL_TOOL_TIME_PATTERN = "yyyy-MM-dd HH:mm"

internal fun mailToolDate(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    if (millis <= 0L) return ""
    return DateTimeFormatter.ofPattern(MAIL_TOOL_TIME_PATTERN, Locale.ROOT)
        .withZone(zone)
        .format(Instant.ofEpochMilli(millis))
}

internal fun mailSenderText(name: String, address: String): String = when {
    name.isBlank() -> address.ifBlank { "未知发件人" }
    address.isBlank() || name.equals(address, ignoreCase = true) -> name
    else -> "$name <$address>"
}

internal fun mailSubjectText(subject: String): String = subject.trim().ifEmpty { "（无主题）" }

internal fun mailFolderLabel(host: PluginHost, role: MailFolderRole): String = when (role) {
    MailFolderRole.INBOX -> host.cardText("收件箱", "Inbox")
    MailFolderRole.SENT -> host.cardText("已发送", "Sent")
    MailFolderRole.DRAFTS -> host.cardText("草稿", "Drafts")
    MailFolderRole.ARCHIVE -> host.cardText("归档", "Archive")
    MailFolderRole.TRASH -> host.cardText("废纸篓", "Trash")
}

internal fun mailSearchResultText(
    items: List<MailMessageSummary>,
    total: Int,
    offset: Int,
    folderLabel: (MailFolderRole) -> String,
    accounts: Map<String, MailAccount>,
): String = buildString {
    append("匹配 $total 封，返回 ${items.size} 封")
    if (offset > 0) append("（offset=$offset）")
    val next = offset + items.size
    if (items.isNotEmpty() && next < total) append("，可用 offset 继续（offset=$next）")
    append("。\n")
    if (total == 0) {
        append("没有找到匹配的邮件。\n")
    } else if (items.isEmpty()) {
        append("没有更多邮件，可以减小 offset。\n")
    }
    items.forEach { message ->
        append("- id=${message.id}\n")
        append("  主题：${mailSubjectText(message.subject)}\n")
        val account = accounts[message.accountId]?.email
            ?.takeIf { it.isNotBlank() }
            ?: message.accountId
        append("  文件夹：${folderLabel(message.folderRole)} · 账户：$account\n")
        append("  发件人：${mailSenderText(message.fromName, message.fromAddress)}\n")
        append("  收件人：${message.to.joinToString(", ").ifBlank { "（无收件人）" }}\n")
        val date = mailToolDate(message.dateMillis)
        if (date.isNotBlank()) append("  时间：$date\n")
        append("  状态：${if (message.unread) "未读" else "已读"}")
        append("，${if (message.starred) "已星标" else "未星标"}")
        append("，${if (message.hasAttachments) "含附件" else "无附件"}")
        formatFileSize(message.sizeBytes).takeIf { it.isNotBlank() }?.let { append("，大小 $it") }
        append("\n")
        append("  会话：${message.threadId}\n")
        if (message.preview.isNotBlank()) append("  预览：${message.preview.trim()}\n")
    }
}

internal fun mailReadResultText(
    detail: MailMessageDetail,
    folderLabel: String,
    accountEmail: String,
): String {
    val summary = detail.summary
    return buildString {
        append("id：${summary.id}\n")
        append("主题：${mailSubjectText(summary.subject)}\n")
        append("文件夹：$folderLabel\n")
        append("账户：${accountEmail.ifBlank { summary.accountId }}\n")
        append("发件人：${mailSenderText(summary.fromName, summary.fromAddress)}\n")
        append("收件人：${summary.to.joinToString(", ").ifBlank { "（无收件人）" }}\n")
        if (detail.cc.isNotEmpty()) append("抄送：${detail.cc.joinToString(", ")}\n")
        val date = mailToolDate(summary.dateMillis)
        if (date.isNotBlank()) append("时间：$date\n")
        append("会话：${summary.threadId}\n")
        formatFileSize(summary.sizeBytes).takeIf { it.isNotBlank() }?.let { append("大小：$it\n") }
        detail.headers.forEach { header -> append("${header.name}：${header.value}\n") }
        append("附件：${detail.attachments.size} 个\n")
        detail.attachments.forEach { attachment ->
            append("- ${attachment.name}（${attachment.mimeType}")
            formatFileSize(attachment.sizeBytes).takeIf { it.isNotBlank() }?.let { append("，$it") }
            append("，id=${attachment.id}")
            if (attachment.inline) append("，内嵌")
            attachment.contentId?.takeIf { it.isNotBlank() }?.let { append("，Content-ID=$it") }
            append("）\n")
        }
        if (detail.textTruncated) {
            append("正文说明：正文超过单段 1 MiB 或总计 2 MiB 上限，已截断。\n")
        }
        append("\n正文：\n")
        val html = detail.htmlBody?.trim().orEmpty()
        when {
            detail.textBody.isNotBlank() -> append(detail.textBody)
            html.isNotEmpty() -> append("（无纯文本正文，见下方 HTML 原文）")
            else -> append("（正文为空）")
        }
        append("\n")
        if (html.isNotEmpty()) {
            val htmlText = HtmlText.toPlainText(html).trim()
            val plain = detail.textBody.trim()
            if (!detail.hasPlainText || htmlText.length > plain.length) {
                append("\nHTML 原文：\n")
                append(html)
                append("\n")
            }
        }
    }
}

private fun searchCard(
    host: PluginHost,
    query: String,
    account: MailAccount?,
    folder: MailFolderRole?,
    unreadOnly: Boolean,
    items: List<MailMessageSummary>,
    total: Int,
    accounts: Map<String, MailAccount>,
): MailCard {
    val payload = MailSearchCardPayload(
        query = query,
        account = account?.email.orEmpty(),
        folder = folder?.name?.lowercase().orEmpty(),
        unreadOnly = unreadOnly,
        total = total,
        items = items.map { message ->
            MailSearchCardItem(
                accountId = message.accountId,
                messageId = message.id,
                threadId = message.threadId,
                subject = message.subject,
                from = message.fromName.ifBlank { message.fromAddress },
                to = message.to,
                preview = message.preview,
                dateMillis = message.dateMillis,
                unread = message.unread,
                starred = message.starred,
                hasAttachments = message.hasAttachments,
                sizeBytes = message.sizeBytes,
                folderRole = message.folderRole.name.lowercase(),
                sent = message.folderRole == MailFolderRole.SENT,
                account = accounts[message.accountId]?.email.orEmpty(),
                accountColor = accounts[message.accountId]?.color ?: 0,
            )
        },
    )
    val emptySubject = host.cardText("（无主题）", "(No subject)")
    val latest = payload.items.firstOrNull()
    val text = when {
        payload.total == 0 -> host.cardText("没有找到匹配的邮件。", "No matching mail.")
        latest == null -> host.cardText(
            "匹配 %d 封，这一页没有邮件。",
            "%d messages matched, none on this page.",
            payload.total,
        )
        folder == MailFolderRole.SENT -> host.cardText(
            "已发送 %d 封，最新：%s。",
            "Sent %d messages. Latest: %s.",
            payload.total,
            displaySubject(latest.subject, emptySubject),
        )
        else -> host.cardText(
            "找到 %d 封邮件，最新：%s。",
            "Found %d messages. Latest: %s.",
            payload.total,
            displaySubject(latest.subject, emptySubject),
        )
    }
    return MailCard(customType = MailChatCardTypes.SEARCH, title = text, payload = payload.encodeCardPayload())
}

private suspend fun read(
    host: PluginHost,
    repository: MailRepository,
    call: PluginToolCall,
): PluginToolResult {
    val messageId = call.args.str("message_id")
    val resolved = resolveMessage(repository, messageId)
        ?: return textResult("找不到这封邮件，请先 mail_search 获取最新 id。", isError = true)
    val detail = repository.message(resolved.first.id, resolved.second)
        ?: return textResult("读取邮件失败，可能已被删除或移动。", isError = true)
    val accountEmail = repository.accounts()
        .firstOrNull { it.id == detail.summary.accountId }
        ?.email
        .orEmpty()
    val card = readCard(host, detail, accountEmail)
    shareAttachmentsToChat(host, repository, call, detail)
    return call.withCard(
        mailReadResultText(
            detail = detail,
            folderLabel = mailFolderLabel(host, detail.summary.folderRole),
            accountEmail = accountEmail,
        ),
        card,
    )
}

private suspend fun shareAttachmentsToChat(
    host: PluginHost,
    repository: MailRepository,
    call: PluginToolCall,
    detail: MailMessageDetail,
) {
    val sessionId = call.sessionId
    if (sessionId.isBlank() || detail.attachments.isEmpty()) return
    val dedupeKey = "$sessionId:${detail.summary.id}"
    if (!sharedAttachmentKeys.add(dedupeKey)) return
    val attachments = mutableListOf<ChatAttachment>()
    for (attachment in detail.attachments.take(MAX_CHAT_ATTACHMENTS)) {
        if (attachment.sizeBytes > MAX_CHAT_ATTACHMENT_BYTES) continue
        val path = try {
            repository.loadAttachment(detail.summary.accountId, detail.summary.id, attachment.id)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            runCatching { host.logs().warn("mail attachment chat share failed", error) }
            null
        } ?: continue
        attachments += chatAttachment(path, attachment.mimeType, attachment.name)
    }
    if (attachments.isEmpty()) return
    val subject = detail.summary.subject.ifBlank { host.cardText("（无主题）", "(No subject)") }
    val text = host.cardText("邮件「%s」的附件：", "Attachments from “%s”:", subject)
    try {
        host.chat().sendMessageWithAttachments(
            sessionId = sessionId,
            text = text,
            attachments = attachments,
            options = chatMessageOptions(triggerTurn = false),
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        runCatching { host.logs().warn("mail attachment chat share failed", error) }
    }
}

private suspend fun saveAttachment(
    host: PluginHost,
    repository: MailRepository,
    call: PluginToolCall,
): PluginToolResult {
    val attachmentId = call.args.str("attachment_id").trim()
    if (attachmentId.isEmpty()) return textResult("请提供附件 id。", isError = true)
    val resolved = resolveMessage(repository, call.args.str("message_id"))
        ?: return textResult("找不到这封邮件，请先 mail_read 获取最新附件 id。", isError = true)
    val detail = repository.message(resolved.first.id, resolved.second)
        ?: return textResult("读取邮件失败，可能已被删除或移动。", isError = true)
    val attachment = detail.attachments.firstOrNull { it.id == attachmentId }
        ?: return textResult("这封邮件没有找到该附件，请重新读取邮件。", isError = true)
    val localPath = try {
        repository.loadAttachment(resolved.first.id, resolved.second, attachment.id)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        null
    } ?: return textResult("附件读取失败，请稍后重试。", isError = true)
    val saved = try {
        host.media().saveFileToDownloads(localPath, attachment.name, attachment.mimeType)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        runCatching { host.logs().warn("mail attachment save failed", error) }
        null
    }
    return if (saved.isNullOrBlank()) {
        textResult(
            host.toolText(
                "保存附件失败，请稍后重试。",
                "Couldn't save the attachment. Try again.",
            ),
            isError = true,
        )
    } else {
        textResult("附件「${attachment.name}」已保存到下载目录。")
    }
}

private fun readCard(host: PluginHost, detail: MailMessageDetail, accountEmail: String): MailCard {
    val summary = detail.summary
    val plainBody = detail.textBody.ifBlank {
        detail.htmlBody?.let(HtmlText::toPlainText).orEmpty()
    }
    val payload = MailReadCardPayload(
        accountId = summary.accountId,
        messageId = summary.id,
        threadId = summary.threadId,
        subject = summary.subject,
        fromName = summary.fromName,
        fromAddress = summary.fromAddress,
        to = summary.to,
        account = accountEmail,
        folderRole = summary.folderRole.name.lowercase(),
        dateMillis = summary.dateMillis,
        preview = HtmlText.preview(plainBody, 240),
        body = plainBody,
        unread = summary.unread,
        textTruncated = detail.textTruncated,
        attachments = detail.attachments.map { attachment ->
            MailReadCardAttachment(
                id = attachment.id,
                name = attachment.name,
                mimeType = attachment.mimeType,
                sizeBytes = attachment.sizeBytes,
                inline = attachment.inline,
                contentId = attachment.contentId,
            )
        },
    )
    val subject = displaySubject(summary.subject, host.cardText("（无主题）", "(No subject)"))
    val from = if (summary.folderRole == MailFolderRole.SENT) {
        summary.to.joinToString(", ").ifBlank { accountEmail }
    } else {
        summary.fromName.ifBlank { summary.fromAddress }
    }
    val text = host.cardText(
        "已读取邮件：%s · %s。",
        "Read mail: %s · %s.",
        subject,
        from,
    )
    return MailCard(customType = MailChatCardTypes.READ, title = text, payload = payload.encodeCardPayload())
}

private suspend fun draft(
    host: PluginHost,
    repository: MailRepository,
    call: PluginToolCall,
): PluginToolResult {
    val account = accountOf(repository, call.args.str("account"))
        ?: repository.accounts().firstOrNull()
        ?: return textResult("还没有连接邮箱账号，请先在「插件 → 邮箱」中添加。", isError = true)
    val to = recipients(call.args.str("to"))
    if (to.isEmpty()) return textResult("收件人不能为空。", isError = true)
    val attachmentFiles = mutableListOf<File>()
    attachmentInputError(host, call.args.str("attachments"), attachmentFiles)?.let { message ->
        return textResult(message, isError = true)
    }
    val now = System.currentTimeMillis()
    val draftId = "draft-$now-${to.hashCode()}"
    val imported = mutableListOf<DraftAttachment>()
    try {
        attachmentFiles.forEach { file ->
            imported += repository.importDraftAttachment(
                draftId = draftId,
                sourcePath = file.absolutePath,
                name = file.name,
                mimeType = "",
            )
        }
    } catch (cancelled: CancellationException) {
        discardImportedAttachments(repository, draftId, imported)
        throw cancelled
    } catch (error: Throwable) {
        discardImportedAttachments(repository, draftId, imported)
        throw error
    }
    val draft = MailDraft(
        id = draftId,
        accountId = account.id,
        to = to,
        cc = recipients(call.args.str("cc")),
        subject = call.args.str("subject").trim(),
        body = call.args.str("body"),
        inReplyToId = call.args.str("reply_to").ifBlank { null },
        attachments = imported,
        createdAt = now,
        updatedAt = now,
    )
    val saved = try {
        repository.saveDraft(draft)
    } catch (cancelled: CancellationException) {
        discardImportedAttachments(repository, draftId, imported)
        throw cancelled
    } catch (error: Throwable) {
        discardImportedAttachments(repository, draftId, imported)
        return textResult(
            host.readableError(
                error,
                fallbackZh = "保存草稿失败，请重试。",
                fallbackEn = "Couldn't save the draft. Try again.",
            ),
            isError = true,
        )
    }
    val card = draftCard(host, account, saved)
    val attachmentNote = if (saved.attachments.isEmpty()) {
        ""
    } else {
        "，附件 ${saved.attachments.size} 个"
    }
    return call.withCard(
        "已生成草稿（id=${saved.id}）：收件人 ${saved.to.joinToString(", ")}，主题「${saved.subject.ifBlank { "（无主题）" }}」$attachmentNote。用户要求发送时调用 mail_send。",
        card,
    )
}

private suspend fun send(
    host: PluginHost,
    repository: MailRepository,
    call: PluginToolCall,
): PluginToolResult {
    val draftId = call.args.str("draft_id").trim()
    if (draftId.isEmpty()) return textResult("请提供草稿 id。", isError = true)
    val draft = repository.state.value.drafts.firstOrNull { it.id == draftId }
        ?: return textResult(
            host.toolText(
                "找不到这份草稿，可能已经发送或删除；请重新用 mail_draft 生成草稿。",
                "Couldn't find that draft. It may have been sent or deleted; create it again with mail_draft.",
            ),
            isError = true,
        )
    val result = try {
        repository.sendDraft(draftId)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        MailSendResult(
            ok = false,
            error = host.readableError(
                error,
                fallbackZh = "发送失败，请稍后重试。",
                fallbackEn = "Couldn't send the message. Try again.",
            ),
        )
    }
    if (!result.ok) {
        val error = result.error?.takeIf { it.isNotBlank() }
            ?: host.toolText("发送失败，请稍后重试。", "Couldn't send the message. Try again.")
        return textResult(error, isError = true)
    }
    val subject = displaySubject(draft.subject, "（无主题）")
    return textResult(
        "已发送「$subject」给 ${draft.to.joinToString(", ")}。",
    )
}

private fun attachmentInputError(
    host: PluginHost,
    raw: String,
    into: MutableList<File>,
): String? {
    val paths = raw.split(',', ';', '，', '；').map { it.trim() }.filter { it.isNotEmpty() }
    var total = 0L
    for (path in paths) {
        val file = File(path)
        if (!file.isAbsolute) {
            return host.toolText(
                "附件「%s」需要使用本地绝对路径。",
                "Attachment “%s” needs an absolute local path.",
                displayPreview(path, 120),
            )
        }
        if (!file.isFile) {
            return host.toolText(
                "找不到附件文件「%s」。",
                "Attachment file “%s” was not found.",
                displayPreview(path, 120),
            )
        }
        if (!file.canRead()) {
            return host.toolText(
                "无法读取附件「%s」。",
                "Attachment “%s” can't be read.",
                file.name,
            )
        }
        if (file.length() > MailComposeLimits.MAX_ATTACHMENT_BYTES) {
            return host.toolText(
                "附件「%s」超过 20 MB，请换一个文件。",
                "Attachment “%s” is larger than 20 MB. Choose another file.",
                file.name,
            )
        }
        total += file.length()
        if (total > MailComposeLimits.MAX_TOTAL_ATTACHMENT_BYTES) {
            return host.toolText(
                "附件总大小不能超过 25 MB。",
                "Attachments must total 25 MB or less.",
            )
        }
        into += file
    }
    return null
}

private suspend fun discardImportedAttachments(
    repository: MailRepository,
    draftId: String,
    attachments: List<DraftAttachment>,
) {
    attachments.forEach { attachment ->
        runCatching { repository.removeDraftAttachment(draftId, attachment) }
    }
}

private fun PluginHost.toolText(zh: String, en: String, vararg args: Any): String =
    text(localizedText(default = zh, zh = zh, en = en), *args)

private fun draftCard(
    host: PluginHost,
    account: MailAccount,
    draft: MailDraft,
): MailCard {
    val payload = MailDraftCardPayload(
        draftId = draft.id,
        account = account.email,
        to = draft.to,
        cc = draft.cc,
        subject = draft.subject,
        bodyPreview = HtmlText.preview(draft.body, 240),
        attachments = draft.attachments.map { MailDraftCardAttachment(it.name, it.sizeBytes) },
    )
    val subject = displaySubject(draft.subject, host.cardText("（无主题）", "(No subject)"))
    val text = if (draft.attachments.isEmpty()) {
        host.cardText(
            "已生成草稿：收件人 %s，主题「%s」。确认后再发送。",
            "Draft ready for %s with subject “%s”. Review it before sending.",
            draft.to.joinToString(", "),
            subject,
        )
    } else {
        host.cardText(
            "已生成草稿：收件人 %s，主题「%s」，附件 %d 个。确认后再发送。",
            "Draft ready for %s with subject “%s” and %d attachments. Review it before sending.",
            draft.to.joinToString(", "),
            subject,
            draft.attachments.size,
        )
    }
    return MailCard(customType = MailChatCardTypes.DRAFT, title = text, payload = payload.encodeCardPayload())
}

internal fun PluginHost.cardText(zh: String, en: String, vararg args: Any): String =
    text(localizedText(default = zh, zh = zh, en = en), *args)

private fun displaySubject(subject: String, empty: String): String = subject.trim().ifEmpty { empty }

private suspend fun mark(repository: MailRepository, call: PluginToolCall): PluginToolResult {
    val resolved = resolveMessage(repository, call.args.str("message_id"))
        ?: return textResult("找不到这封邮件，请先 mail_search 获取最新 id。", isError = true)
    repository.mark(resolved.first.id, resolved.second, call.args.bool("unread", default = true))
    return textResult("已更新邮件状态。")
}

private suspend fun archive(repository: MailRepository, call: PluginToolCall): PluginToolResult {
    val resolved = resolveMessage(repository, call.args.str("message_id"))
        ?: return textResult("找不到这封邮件，请先 mail_search 获取最新 id。", isError = true)
    return try {
        repository.archive(resolved.first.id, resolved.second)
        textResult("已归档。")
    } catch (error: Exception) {
        textResult("归档失败：${error.message ?: "该邮箱没有归档文件夹"}", isError = true)
    }
}

private fun resolveMessage(repository: MailRepository, messageId: String): Pair<MailAccount, String>? {
    if (messageId.isBlank()) return null
    val candidates = repository.accounts().mapNotNull { account ->
        val prefix = account.id + ":"
        if (messageId.startsWith(prefix)) account to messageId.removePrefix(prefix) else null
    }
    if (candidates.isNotEmpty()) return candidates.first()
    val stateMessage = repository.state.value.messages.firstOrNull { it.id == messageId }
    val account = stateMessage?.let { message -> repository.accounts().firstOrNull { it.id == message.accountId } }
    return account?.let { it to messageId }
}

private fun accountOf(repository: MailRepository, value: String): MailAccount? {
    if (value.isBlank()) return null
    return repository.accounts().firstOrNull { it.id == value || it.email.equals(value, ignoreCase = true) }
}

private fun recipients(value: String): List<String> = value
    .split(',', ';', '，', '；')
    .map { it.trim() }
    .filter { it.isNotEmpty() }

private fun parameters(required: List<String>, vararg properties: Pair<String, JsonObject>): JsonObject = buildJsonObject {
    put("type", "object")
    put(
        "properties",
        buildJsonObject {
            properties.forEach { (name, schema) -> put(name, schema) }
        },
    )
    put(
        "required",
        kotlinx.serialization.json.buildJsonArray {
            required.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
        },
    )
    put("additionalProperties", false)
}

private fun stringProperty(description: String): JsonObject = buildJsonObject {
    put("type", "string")
    put("description", description)
}

private fun booleanProperty(description: String): JsonObject = buildJsonObject {
    put("type", "boolean")
    put("description", description)
}

private fun enumProperty(description: String, values: List<String>): JsonObject = buildJsonObject {
    put("type", "string")
    put("description", description)
    put("enum", JsonArray(values.map { JsonPrimitive(it) }))
}

private fun intProperty(description: String, minimum: Int? = null, maximum: Int? = null): JsonObject =
    buildJsonObject {
        put("type", "integer")
        put("description", description)
        if (minimum != null) put("minimum", minimum)
        if (maximum != null) put("maximum", maximum)
    }

private fun JsonObject.str(name: String): String =
    (this[name] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull.orEmpty()

private fun JsonObject.bool(name: String, default: Boolean = false): Boolean =
    (this[name] as? kotlinx.serialization.json.JsonPrimitive)?.booleanOrNull ?: default

private fun JsonObject.int(name: String): Int? =
    (this[name] as? kotlinx.serialization.json.JsonPrimitive)?.intOrNull
