package io.github.whywood.deepdeep.plugin.mail.sync

import io.github.whywood.deepdeep.plugin.mail.MailFolderRole
import io.github.whywood.deepdeep.plugin.mail.MailMessageSummary
import io.github.whywood.deepdeep.plugin.mail.mime.MailAddress
import io.github.whywood.deepdeep.plugin.mail.mime.MailDates
import io.github.whywood.deepdeep.plugin.mail.mime.MailHeaders
import io.github.whywood.deepdeep.plugin.mail.mime.ParsedMail
import io.github.whywood.deepdeep.plugin.mail.mime.Rfc2047
import io.github.whywood.deepdeep.plugin.mail.transport.ImapAddress
import io.github.whywood.deepdeep.plugin.mail.transport.ImapCodec
import io.github.whywood.deepdeep.plugin.mail.transport.ImapFetchResult
import io.github.whywood.deepdeep.plugin.mail.transport.ImapFolder

internal object FolderMapping {
    fun resolve(folders: List<ImapFolder>): Map<MailFolderRole, String> {
        val selectable = folders.filter { it.selectable }
        val result = linkedMapOf<MailFolderRole, String>()
        selectable.firstOrNull { it.name.equals("INBOX", ignoreCase = true) }?.let {
            result[MailFolderRole.INBOX] = it.name
        }
        for (folder in selectable) {
            val role = specialUseRole(folder.flags) ?: continue
            if (role !in result) result[role] = folder.name
        }
        for (folder in selectable) {
            if (result.containsValue(folder.name)) continue
            val role = nameRole(folder) ?: continue
            if (role !in result) result[role] = folder.name
        }
        return result
    }

    fun specialUseRole(flags: Set<String>): MailFolderRole? {
        for (flag in flags) {
            when {
                flag.equals("\\Sent", ignoreCase = true) -> return MailFolderRole.SENT
                flag.equals("\\Drafts", ignoreCase = true) -> return MailFolderRole.DRAFTS
                flag.equals("\\Trash", ignoreCase = true) -> return MailFolderRole.TRASH
                flag.equals("\\Archive", ignoreCase = true) -> return MailFolderRole.ARCHIVE
                flag.equals("\\All", ignoreCase = true) -> return MailFolderRole.ARCHIVE
            }
        }
        return null
    }

    fun nameRole(folder: ImapFolder): MailFolderRole? {
        val delimiter = folder.delimiter?.takeIf { it.isNotEmpty() } ?: "/"
        val segment = folder.name.substringAfterLast(delimiter).trim().lowercase()
        if (segment.isEmpty()) return null
        if (segment == "inbox" || segment == "收件箱") return MailFolderRole.INBOX
        if (segment.contains("sent") || segment.contains("已发送") || segment.contains("已发邮件") || segment == "发送") {
            return MailFolderRole.SENT
        }
        if (segment.contains("draft") || segment.contains("草稿")) return MailFolderRole.DRAFTS
        if (segment.contains("trash") || segment.contains("deleted") || segment.contains("已删除") ||
            segment.contains("回收站") || segment.contains("垃圾箱") || segment.contains("垃圾")
        ) {
            return MailFolderRole.TRASH
        }
        if (segment.contains("archive") || segment.contains("归档") || segment.contains("存档") ||
            segment.contains("all mail") || segment.contains("allmail") ||
            segment.contains("所有邮件") || segment.contains("全部邮件")
        ) {
            return MailFolderRole.ARCHIVE
        }
        return null
    }
}

internal object MailThreading {
    fun threadId(references: List<String>, inReplyTo: String?, subject: String, messageId: String?): String {
        references.firstOrNull()?.let { normalizeMessageId(it) }?.takeIf { it.isNotEmpty() }?.let { return it }
        inReplyTo?.let { normalizeMessageId(it) }?.takeIf { it.isNotEmpty() }?.let { return it }
        messageId?.let { normalizeMessageId(it) }?.takeIf { it.isNotEmpty() }?.let { return it }
        return normalizeSubject(subject)
    }

    fun normalizeMessageId(value: String?): String =
        value.orEmpty().trim().trim('<', '>').trim().lowercase()

    fun normalizeSubject(subject: String): String {
        var value = subject.trim()
        while (true) {
            val match = PREFIX.find(value) ?: break
            value = value.substring(match.range.last + 1).trim()
        }
        return WHITESPACE.replace(value, " ").trim().lowercase()
    }

    private val PREFIX = Regex("(?i)^(re|fw|fwd|aw|sv|回覆|回复|答复|转发|轉發)\\s*[:：]\\s*")
    private val WHITESPACE = Regex("\\s+")
}

internal object MessageKeys {
    fun id(accountId: String, role: MailFolderRole, uid: Long): String = "$accountId:${roleName(role)}:$uid"

    fun key(role: MailFolderRole, uid: Long): String = "${roleName(role)}-$uid"

    fun roleName(role: MailFolderRole): String = role.name.lowercase()

    fun roleFromName(value: String): MailFolderRole? =
        MailFolderRole.entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
}

internal object SyncPolicy {
    const val MAX_MESSAGES_PER_ACCOUNT = 2000
    const val MAX_MESSAGES_PER_FOLDER = 2000
    const val FLAG_WINDOW_SIZE = 500
    const val FETCH_PAGE_SIZE = 50
    const val BODY_PREFETCH_LIMIT = 50
    const val PREFETCH_MAX_MESSAGE_BYTES = 4L * 1024 * 1024
    const val PREFETCH_TOTAL_BYTES = 24L * 1024 * 1024
    const val MAX_MESSAGE_BODY_BYTES = 48L * 1024 * 1024
    const val MAX_ATTACHMENT_BYTES = 32L * 1024 * 1024
    const val BACKGROUND_SYNC_INTERVAL_MS = 15 * 60 * 1000L
    const val REFRESH_DEBOUNCE_MS = 30 * 1000L
    const val NOTIFY_THROTTLE_MS = 30 * 60 * 1000L

    fun capAccount(messages: List<MailMessageSummary>, limit: Int = MAX_MESSAGES_PER_ACCOUNT): List<MailMessageSummary> =
        messages.sortedWith(compareByDescending<MailMessageSummary> { it.dateMillis }.thenByDescending { it.uid })
            .take(limit)

    fun flagWindow(messages: List<MailMessageSummary>, role: MailFolderRole): List<MailMessageSummary> =
        messages.asSequence()
            .filter { it.folderRole == role }
            .sortedByDescending { it.uid }
            .take(FLAG_WINDOW_SIZE)
            .toList()

    fun prefetchCandidates(
        messages: List<MailMessageSummary>,
        limit: Int = BODY_PREFETCH_LIMIT,
        maxMessageBytes: Long = PREFETCH_MAX_MESSAGE_BYTES,
        totalBudget: Long = PREFETCH_TOTAL_BYTES,
    ): List<MailMessageSummary> {
        val result = mutableListOf<MailMessageSummary>()
        var budget = totalBudget
        for (message in messages.sortedByDescending { it.uid }) {
            if (result.size >= limit) break
            if (budget <= 0) break
            val estimated = if (message.sizeBytes > 0) message.sizeBytes else maxMessageBytes
            if (estimated > maxMessageBytes) continue
            if (estimated > budget) continue
            budget -= estimated
            result += message
        }
        return result
    }
}

internal object MailMessageMapper {
    fun toSummary(accountId: String, role: MailFolderRole, fetch: ImapFetchResult): MailMessageSummary {
        val uid = fetch.uid ?: 0L
        val headers = fetch.headerBlock?.let { MailHeaders.parse(it) } ?: MailHeaders.EMPTY
        val envelope = fetch.envelope
        val from = if (headers.from.isNotEmpty()) headers.from else envelope?.from.toAddresses()
        val to = if (headers.to.isNotEmpty()) headers.to else envelope?.to.toAddresses()
        val subject = headers.subject.ifBlank { Rfc2047.decode(envelope?.subject.orEmpty()) }
        val dateMillis = ImapCodec.parseInternalDate(fetch.internalDate.orEmpty())
            ?: headers.dateMillis
            ?: envelope?.date?.let { MailDates.parse(it) }
            ?: 0L
        val messageId = headers.messageId ?: envelope?.messageId?.let { MailHeaders.normalizeMessageId(it) }
        val inReplyTo = headers.inReplyTo ?: envelope?.inReplyTo?.let { MailHeaders.normalizeMessageId(it) }
        val references = headers.references
        val flags = fetch.flags.orEmpty()
        return MailMessageSummary(
            id = MessageKeys.id(accountId, role, uid),
            accountId = accountId,
            folderRole = role,
            uid = uid,
            threadId = MailThreading.threadId(references, inReplyTo, subject, messageId),
            subject = subject.trim(),
            fromName = from.firstOrNull()?.name.orEmpty(),
            fromAddress = from.firstOrNull()?.address.orEmpty(),
            to = to.map { it.display },
            dateMillis = dateMillis,
            preview = "",
            unread = flags.none { it.equals("\\Seen", ignoreCase = true) },
            starred = flags.any { it.equals("\\Flagged", ignoreCase = true) },
            hasAttachments = false,
            sizeBytes = fetch.size ?: 0L,
        )
    }

    fun withParsed(summary: MailMessageSummary, parsed: ParsedMail): MailMessageSummary = summary.copy(
        preview = parsed.preview,
        hasAttachments = parsed.attachments.isNotEmpty(),
    )

    private fun List<ImapAddress>?.toAddresses(): List<MailAddress> = orEmpty().mapNotNull { address ->
        val email = address.address
        if (email.isBlank()) return@mapNotNull null
        MailAddress(Rfc2047.decode(address.name.orEmpty()).trim(), email)
    }
}
