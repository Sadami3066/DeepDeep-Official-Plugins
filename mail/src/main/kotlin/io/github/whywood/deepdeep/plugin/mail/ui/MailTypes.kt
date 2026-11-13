package io.github.whywood.deepdeep.plugin.mail.ui

import io.github.whywood.deepdeep.plugin.mail.MailDraft
import io.github.whywood.deepdeep.plugin.mail.MailMessageSummary

internal data class MailThreadRequest(
    val accountId: String,
    val threadId: String,
    val messageId: String,
)

internal data class MailComposeRequest(
    val accountId: String,
    val to: List<String> = emptyList(),
    val cc: List<String> = emptyList(),
    val subject: String = "",
    val body: String = "",
    val inReplyToId: String? = null,
    val draft: MailDraft? = null,
)

internal sealed interface MailOverlay {
    data class Thread(val request: MailThreadRequest) : MailOverlay

    data class Compose(val request: MailComposeRequest, val returnTo: MailOverlay?) : MailOverlay

    data class Accounts(val startAdding: Boolean) : MailOverlay
}

internal data class MailThreadRow(
    val accountId: String,
    val threadId: String,
    val messages: List<MailMessageSummary>,
    val latest: MailMessageSummary,
    val unread: Boolean,
    val hasAttachments: Boolean,
)

internal fun mailThreadRows(messages: List<MailMessageSummary>, onlyUnread: Boolean): List<MailThreadRow> =
    messages
        .groupBy { it.accountId to it.threadId }
        .mapNotNull { (_, group) ->
            val latest = group.maxByOrNull { it.dateMillis } ?: return@mapNotNull null
            MailThreadRow(
                accountId = latest.accountId,
                threadId = latest.threadId,
                messages = group.sortedByDescending { it.dateMillis },
                latest = latest,
                unread = group.any { it.unread },
                hasAttachments = group.any { it.hasAttachments },
            )
        }
        .filter { !onlyUnread || it.unread }
        .sortedByDescending { it.latest.dateMillis }

internal sealed interface MailSearchState {
    data object Idle : MailSearchState

    data object Loading : MailSearchState

    data class Loaded(val rows: List<MailThreadRow>) : MailSearchState

    data class Failed(val message: String) : MailSearchState
}

internal enum class MailRemovalKind { ARCHIVE, TRASH, DELETE }

internal data class MailPendingRemoval(
    val messages: List<MailMessageSummary>,
    val kind: MailRemovalKind,
) {
    val accountId: String get() = messages.first().accountId
}

internal data class MailFailedRemoval(
    val removal: MailPendingRemoval,
    val message: String,
)
