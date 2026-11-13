package io.github.whywood.deepdeep.plugin.mail

import kotlinx.serialization.Serializable

@Serializable
data class MailAccount(
    val id: String,
    val providerId: String,
    val email: String,
    val displayName: String,
    val color: Int,
    val notify: Boolean = true,
    val createdAt: Long,
)

@Serializable
data class MailAccountInput(
    val providerId: String,
    val email: String,
    val password: String,
    val displayName: String = "",
    val imapHost: String? = null,
    val imapPort: Int? = null,
    val smtpHost: String? = null,
    val smtpPort: Int? = null,
)

data class MailConnectionTest(
    val imapOk: Boolean,
    val smtpOk: Boolean,
    val imapError: String? = null,
    val smtpError: String? = null,
) {
    val ok: Boolean get() = imapOk && smtpOk
}

@Serializable
enum class MailFolderRole {
    INBOX,
    SENT,
    DRAFTS,
    ARCHIVE,
    TRASH,
}

@Serializable
data class MailMessageSummary(
    val id: String,
    val accountId: String,
    val folderRole: MailFolderRole,
    val uid: Long,
    val threadId: String,
    val subject: String,
    val fromName: String,
    val fromAddress: String,
    val to: List<String>,
    val dateMillis: Long,
    val preview: String,
    val unread: Boolean,
    val starred: Boolean,
    val hasAttachments: Boolean,
    val sizeBytes: Long = 0,
)

@Serializable
data class MailAttachment(
    val id: String,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
    val inline: Boolean = false,
    val contentId: String? = null,
)

@Serializable
data class MailMessageDetail(
    val summary: MailMessageSummary,
    val cc: List<String>,
    val textBody: String,
    val htmlBody: String?,
    val attachments: List<MailAttachment>,
)

@Serializable
data class DraftAttachment(
    val path: String,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
)

@Serializable
data class MailDraft(
    val id: String,
    val accountId: String,
    val to: List<String>,
    val cc: List<String> = emptyList(),
    val subject: String,
    val body: String,
    val inReplyToId: String? = null,
    val attachments: List<DraftAttachment> = emptyList(),
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
data class MailAccountUpdate(
    val displayName: String? = null,
    val notify: Boolean? = null,
)

data class MailSendResult(
    val ok: Boolean,
    val error: String? = null,
)

@Serializable
data class MailState(
    val accounts: List<MailAccount> = emptyList(),
    val messages: List<MailMessageSummary> = emptyList(),
    val drafts: List<MailDraft> = emptyList(),
    val unreadByAccount: Map<String, Int> = emptyMap(),
    val archiveAccountIds: Set<String> = emptySet(),
    val trashAccountIds: Set<String> = emptySet(),
    val accountErrors: Map<String, String> = emptyMap(),
    val lastSyncByAccount: Map<String, Long> = emptyMap(),
    val syncing: Boolean = false,
    val loading: Boolean = true,
    val syncError: String? = null,
    val lastSyncAt: Long? = null,
) {
    val unreadTotal: Int get() = unreadByAccount.values.sum()
}

@Serializable
data class MailListToolDetails(
    val type: String = "mail.list",
    val items: List<MailListToolItem>,
)

@Serializable
data class MailListToolItem(
    val id: String,
    val subject: String,
    val from: String,
    val dateMillis: Long,
    val preview: String,
    val unread: Boolean,
    val account: String,
)

@Serializable
data class MailDraftToolDetails(
    val type: String = "mail.draft",
    val draftId: String,
    val account: String,
    val to: List<String>,
    val subject: String,
    val bodyPreview: String,
)
