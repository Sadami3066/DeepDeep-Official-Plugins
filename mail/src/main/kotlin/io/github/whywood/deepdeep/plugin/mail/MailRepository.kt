package io.github.whywood.deepdeep.plugin.mail

import kotlinx.coroutines.flow.StateFlow

interface MailRepository {
    val state: StateFlow<MailState>

    fun accounts(): List<MailAccount>

    fun providers(): List<MailProviderPreset>

    suspend fun start()

    suspend fun stop()

    suspend fun refresh()

    suspend fun testConnection(input: MailAccountInput): MailConnectionTest

    suspend fun addAccount(input: MailAccountInput): MailConnectionTest

    suspend fun updateAccount(accountId: String, update: MailAccountUpdate)

    suspend fun removeAccount(accountId: String)

    suspend fun reauthorize(accountId: String, password: String): MailConnectionTest

    suspend fun message(accountId: String, messageId: String): MailMessageDetail?

    suspend fun mark(accountId: String, messageId: String, unread: Boolean)

    suspend fun archive(accountId: String, messageId: String)

    suspend fun trash(accountId: String, messageId: String)

    suspend fun deleteForever(accountId: String, messageId: String)

    suspend fun saveDraft(draft: MailDraft): MailDraft

    suspend fun deleteDraft(draftId: String)

    suspend fun sendDraft(draftId: String): MailSendResult

    suspend fun search(query: String, accountId: String?): List<MailMessageSummary>

    suspend fun loadAttachment(accountId: String, messageId: String, attachmentId: String): String?
}
