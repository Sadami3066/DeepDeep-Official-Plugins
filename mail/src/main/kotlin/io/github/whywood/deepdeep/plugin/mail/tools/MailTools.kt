package io.github.whywood.deepdeep.plugin.mail.tools

import io.github.whywood.deepdeep.plugin.api.Contributions
import io.github.whywood.deepdeep.plugin.api.PluginHost
import io.github.whywood.deepdeep.plugin.api.PluginTool
import io.github.whywood.deepdeep.plugin.api.PluginToolCall
import io.github.whywood.deepdeep.plugin.api.PluginToolResult
import io.github.whywood.deepdeep.plugin.api.contributions
import io.github.whywood.deepdeep.plugin.api.localizedText
import io.github.whywood.deepdeep.plugin.api.pluginTool
import io.github.whywood.deepdeep.plugin.api.textResult
import io.github.whywood.deepdeep.plugin.mail.MailAccount
import io.github.whywood.deepdeep.plugin.mail.MailDraft
import io.github.whywood.deepdeep.plugin.mail.MailMessageDetail
import io.github.whywood.deepdeep.plugin.mail.MailMessageSummary
import io.github.whywood.deepdeep.plugin.mail.MailRepository
import io.github.whywood.deepdeep.plugin.mail.mime.HtmlText
import io.github.whywood.deepdeep.plugin.mail.ui.readableError
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

fun mailTools(host: PluginHost, repository: MailRepository): Contributions = contributions(
    tools = listOf(
        searchTool(host, repository),
        readTool(host, repository),
        draftTool(host, repository),
        markTool(host, repository),
        archiveTool(host, repository),
    ),
)

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

private fun draftTool(host: PluginHost, repository: MailRepository): PluginTool = pluginTool(
    name = "mail_draft",
    label = "起草邮件",
    description = "生成一封待用户确认的邮件草稿。不会直接发送；草稿会以卡片形式交给用户确认。",
    parameters = parameters(
        required = listOf("to", "subject", "body"),
        "to" to stringProperty("收件人，多个用逗号分隔"),
        "cc" to stringProperty("抄送，多个用逗号分隔，可留空"),
        "subject" to stringProperty("主题"),
        "body" to stringProperty("正文纯文本"),
        "reply_to" to stringProperty("要回复的邮件 id，可留空"),
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
    val summary = detail.summary
    val attachments = if (detail.attachments.isEmpty()) {
        ""
    } else {
        detail.attachments.joinToString("\n") { "附件：${it.name}（${it.mimeType}，${it.sizeBytes} 字节）" }
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
    val now = System.currentTimeMillis()
    val draft = MailDraft(
        id = "draft-${now}-${to.hashCode()}",
        accountId = account.id,
        to = to,
        cc = recipients(call.args.str("cc")),
        subject = call.args.str("subject").trim(),
        body = call.args.str("body"),
        inReplyToId = call.args.str("reply_to").ifBlank { null },
        createdAt = now,
        updatedAt = now,
    )
    val saved = repository.saveDraft(draft)
    postDraftCard(host, call, account, saved)
    return textResult(
        "已生成草稿：收件人 ${saved.to.joinToString(", ")}，主题「${saved.subject.ifBlank { "（无主题）" }}」。发送前请在草稿卡片上确认。",
    )
}

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
    )
    val subject = displaySubject(draft.subject, host.cardText("（无主题）", "(No subject)"))
    val text = host.cardText(
        "已生成草稿：收件人 %s，主题「%s」。确认后再发送。",
        "Draft ready for %s with subject “%s”. Review it before sending.",
        draft.to.joinToString(", "),
        subject,
    )
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
