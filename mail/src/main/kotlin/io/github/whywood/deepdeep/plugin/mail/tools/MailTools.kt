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
import io.github.whywood.deepdeep.plugin.mail.MailMessageDetail
import io.github.whywood.deepdeep.plugin.mail.MailMessageSummary
import io.github.whywood.deepdeep.plugin.mail.MailRepository
import io.github.whywood.deepdeep.plugin.mail.mime.HtmlText
import io.github.whywood.deepdeep.plugin.mail.mime.MailComposeLimits
import io.github.whywood.deepdeep.plugin.mail.ui.displayPreview
import io.github.whywood.deepdeep.plugin.mail.ui.readableError
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

private const val CardResultLimit = 5

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
    description = "在已连接的邮箱中按关键词、账号和未读状态搜索邮件，返回摘要列表；需要正文时再用 mail_read 打开。",
    parameters = parameters(
        required = emptyList(),
        "query" to stringProperty("关键词，可留空表示最近邮件"),
        "account" to stringProperty("邮箱地址或账号 id，可留空表示全部账号"),
        "unread_only" to booleanProperty("只看未读"),
        "limit" to intProperty("返回数量，1-50，默认 20", minimum = 1, maximum = 50),
    ),
    execute = guardedExecute(host) { call -> search(host, repository, call) },
)

private fun readTool(host: PluginHost, repository: MailRepository): PluginTool = pluginTool(
    name = "mail_read",
    label = "读取邮件",
    description = "读取一封邮件的正文、收发件人与附件清单。",
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
    description = "生成一封待用户确认的邮件草稿。不会直接发送；草稿会以卡片形式交给用户确认。可以通过 attachments 附带本地文件。",
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
    val items = repository.search(query, account?.id)
        .filter { !unreadOnly || it.unread }
        .take((call.args.int("limit") ?: 20).coerceIn(1, 50))
    postSearchCard(
        host = host,
        call = call,
        query = query,
        account = account,
        unreadOnly = unreadOnly,
        items = items,
        accounts = repository.accounts().associateBy { it.id },
    )
    if (items.isEmpty()) return textResult("没有找到匹配的邮件。")
    return textResult(
        buildString {
            append("找到 ${items.size} 封邮件：\n")
            items.forEach { message ->
                val unread = if (message.unread) "未读" else "已读"
                append("- [${message.id}] ${message.subject.ifBlank { "（无主题）" }} · ${message.fromName.ifBlank { message.fromAddress }} · $unread\n")
            }
        },
    )
}

private suspend fun postSearchCard(
    host: PluginHost,
    call: PluginToolCall,
    query: String,
    account: MailAccount?,
    unreadOnly: Boolean,
    items: List<MailMessageSummary>,
    accounts: Map<String, MailAccount>,
) {
    val payload = MailSearchCardPayload(
        query = query,
        account = account?.email.orEmpty(),
        unreadOnly = unreadOnly,
        total = items.size,
        items = items.take(CardResultLimit).map { message ->
            MailSearchCardItem(
                accountId = message.accountId,
                messageId = message.id,
                threadId = message.threadId,
                subject = message.subject,
                from = message.fromName.ifBlank { message.fromAddress },
                dateMillis = message.dateMillis,
                unread = message.unread,
                hasAttachments = message.hasAttachments,
                accountColor = accounts[message.accountId]?.color ?: 0,
            )
        },
    )
    val emptySubject = host.cardText("（无主题）", "(No subject)")
    val text = if (payload.total == 0) {
        host.cardText("没有找到匹配的邮件。", "No matching mail.")
    } else {
        host.cardText(
            "找到 %d 封邮件，最新：%s。",
            "Found %d messages. Latest: %s.",
            payload.total,
            displaySubject(payload.items.first().subject, emptySubject),
        )
    }
    postCard(host, call, MailChatCardTypes.SEARCH, payload.encodeCardPayload(), text)
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
    postReadCard(host, call, detail)
    shareAttachmentsToChat(host, repository, call, detail)
    val summary = detail.summary
    val attachments = if (detail.attachments.isEmpty()) {
        ""
    } else {
        detail.attachments.joinToString("\n") { attachment ->
            "附件：${attachment.name}（${attachment.mimeType}，${attachment.sizeBytes} 字节，id=${attachment.id}）"
        }
    }
    return textResult(
        buildString {
            append("主题：${summary.subject.ifBlank { "（无主题）" }}\n")
            append("发件人：${summary.fromName.ifBlank { summary.fromAddress }}\n")
            append("收件人：${summary.to.joinToString(", ")}\n")
            if (detail.cc.isNotEmpty()) append("抄送：${detail.cc.joinToString(", ")}\n")
            append("时间：${summary.dateMillis}\n")
            if (attachments.isNotEmpty()) append(attachments).append("\n")
            append("\n")
            append(detail.textBody.ifBlank { "（正文为空）" })
        },
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

private suspend fun postReadCard(host: PluginHost, call: PluginToolCall, detail: MailMessageDetail) {
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
        dateMillis = summary.dateMillis,
        preview = HtmlText.preview(plainBody, 240),
        unread = summary.unread,
        attachments = detail.attachments.map { MailReadCardAttachment(it.name, it.sizeBytes) },
    )
    val subject = displaySubject(summary.subject, host.cardText("（无主题）", "(No subject)"))
    val from = summary.fromName.ifBlank { summary.fromAddress }
    val text = host.cardText(
        "已读取邮件：%s · %s。",
        "Read mail: %s · %s.",
        subject,
        from,
    )
    postCard(host, call, MailChatCardTypes.READ, payload.encodeCardPayload(), text)
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
    postDraftCard(host, call, account, saved)
    val attachmentNote = if (saved.attachments.isEmpty()) {
        ""
    } else {
        "，附件 ${saved.attachments.size} 个"
    }
    return textResult(
        "已生成草稿：收件人 ${saved.to.joinToString(", ")}，主题「${saved.subject.ifBlank { "（无主题）" }}」$attachmentNote。发送前请在草稿卡片上确认。",
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

private suspend fun postDraftCard(
    host: PluginHost,
    call: PluginToolCall,
    account: MailAccount,
    draft: MailDraft,
) {
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
    postCard(host, call, MailChatCardTypes.DRAFT, payload.encodeCardPayload(), text)
}

private suspend fun postCard(
    host: PluginHost,
    call: PluginToolCall,
    customType: String,
    payload: JsonObject,
    text: String,
) {
    val sessionId = call.sessionId
    if (sessionId.isBlank() || text.isBlank()) return
    runCatching {
        host.chat().postCustomMessage(sessionId, customType, payload, text, display = true)
    }
}

private fun PluginHost.cardText(zh: String, en: String, vararg args: Any): String =
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
