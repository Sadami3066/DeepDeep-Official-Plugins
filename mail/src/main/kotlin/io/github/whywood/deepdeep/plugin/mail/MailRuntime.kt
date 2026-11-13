package io.github.whywood.deepdeep.plugin.mail

import io.github.whywood.deepdeep.plugin.api.NotificationChannel
import io.github.whywood.deepdeep.plugin.api.PluginBackgroundKind
import io.github.whywood.deepdeep.plugin.api.PluginHost
import io.github.whywood.deepdeep.plugin.api.localizedText
import io.github.whywood.deepdeep.plugin.api.pluginNotificationWithActions
import io.github.whywood.deepdeep.plugin.mail.mime.MailComposeException
import io.github.whywood.deepdeep.plugin.mail.mime.MailComposeFailure
import io.github.whywood.deepdeep.plugin.mail.mime.MailComposeLimits
import io.github.whywood.deepdeep.plugin.mail.mime.MailComposer
import io.github.whywood.deepdeep.plugin.mail.mime.MailLimits
import io.github.whywood.deepdeep.plugin.mail.mime.MailMimeParser
import io.github.whywood.deepdeep.plugin.mail.mime.OutgoingAttachment
import io.github.whywood.deepdeep.plugin.mail.mime.OutgoingMail
import io.github.whywood.deepdeep.plugin.mail.store.DraftAttachmentException
import io.github.whywood.deepdeep.plugin.mail.store.DraftAttachmentFailure
import io.github.whywood.deepdeep.plugin.mail.store.MailAccountIndex
import io.github.whywood.deepdeep.plugin.mail.store.MailFolderIndex
import io.github.whywood.deepdeep.plugin.mail.store.MailServerSettings
import io.github.whywood.deepdeep.plugin.mail.store.MailStore
import io.github.whywood.deepdeep.plugin.mail.sync.FolderMapping
import io.github.whywood.deepdeep.plugin.mail.sync.MailMessageMapper
import io.github.whywood.deepdeep.plugin.mail.sync.MessageKeys
import io.github.whywood.deepdeep.plugin.mail.sync.SyncPolicy
import io.github.whywood.deepdeep.plugin.mail.transport.ImapBodyOutcome
import io.github.whywood.deepdeep.plugin.mail.transport.ImapBodySkip
import io.github.whywood.deepdeep.plugin.mail.transport.ImapBodyTarget
import io.github.whywood.deepdeep.plugin.mail.transport.ImapClient
import io.github.whywood.deepdeep.plugin.mail.transport.ImapCredentials
import io.github.whywood.deepdeep.plugin.mail.transport.MailConnectionLostException
import io.github.whywood.deepdeep.plugin.mail.transport.MailLocalizedException
import io.github.whywood.deepdeep.plugin.mail.transport.MailNetworkException
import io.github.whywood.deepdeep.plugin.mail.transport.MailTexts
import io.github.whywood.deepdeep.plugin.mail.transport.MailTimeoutException
import io.github.whywood.deepdeep.plugin.mail.transport.MailTransport
import io.github.whywood.deepdeep.plugin.mail.transport.SecureMailTransport
import io.github.whywood.deepdeep.plugin.mail.tools.MAIL_SYNC_TASK_ID
import io.github.whywood.deepdeep.plugin.mail.tools.mailNotificationPayload
import io.github.whywood.deepdeep.plugin.mail.tools.mailOpenAction
import io.github.whywood.deepdeep.plugin.mail.transport.SmtpCredentials
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class MailRuntime(
    private val host: PluginHost,
    private val transport: MailTransport = SecureMailTransport,
) : MailRepository {
    private val texts = MailTexts(host)
    private val store = MailStore(File(host.dataDir, "mail")) { message, error ->
        host.logs().warn(message, error)
    }
    private val fallbackErrorText: String = runCatching {
        host.text(localizedText(default = FALLBACK_ERROR_ZH, zh = FALLBACK_ERROR_ZH, en = FALLBACK_ERROR_EN))
    }.getOrDefault("")
    private val stateMutex = Mutex()
    private val refreshLock = Mutex()
    private val notifiedAt = mutableMapOf<String, Long>()
    private val _state = MutableStateFlow(MailState())

    override val state: StateFlow<MailState> = _state.asStateFlow()

    private var accounts: List<MailAccount> = emptyList()
    private var servers: Map<String, MailServerSettings> = emptyMap()
    private var indexes: MutableMap<String, MailAccountIndex> = mutableMapOf()
    private var drafts: List<MailDraft> = emptyList()
    private var accountErrors: Map<String, String> = emptyMap()
    private var lastSyncByAccount: Map<String, Long> = emptyMap()
    private var lastRefreshAt = 0L
    private var started = false
    private var syncJob: Job? = null

    override fun accounts(): List<MailAccount> = try {
        _state.value.accounts
    } catch (error: Throwable) {
        report(error)
        emptyList()
    }

    override fun providers(): List<MailProviderPreset> = try {
        ProviderCatalog.presets
    } catch (error: Throwable) {
        report(error)
        emptyList()
    }

    override suspend fun start(): Unit = boundary(
        fallback = {
            _state.update { current -> current.copy(loading = false) }
        },
    ) {
        startInternal()
    }

    override suspend fun stop(): Unit = boundary(fallback = { }) {
        stopInternal()
    }

    override suspend fun refresh(): Unit = boundary(
        fallback = { error -> markSyncFailure(safeDescribe(error)) },
    ) {
        refreshInternal(force = false)
    }

    override suspend fun testConnection(input: MailAccountInput): MailConnectionTest = boundary(
        fallback = { error -> connectionFailure(safeDescribe(error)) },
    ) {
        testConnectionInternal(input)
    }

    override suspend fun addAccount(input: MailAccountInput): MailConnectionTest = boundary(
        fallback = { error -> connectionFailure(safeDescribe(error)) },
    ) {
        addAccountInternal(input)
    }

    override suspend fun updateAccount(accountId: String, update: MailAccountUpdate): Unit = boundary(fallback = { }) {
        updateAccountInternal(accountId, update)
    }

    override suspend fun removeAccount(accountId: String): Unit = boundary(fallback = { }) {
        removeAccountInternal(accountId)
    }

    override suspend fun reauthorize(accountId: String, password: String): MailConnectionTest = boundary(
        fallback = { error -> connectionFailure(safeDescribe(error)) },
    ) {
        reauthorizeInternal(accountId, password)
    }

    override suspend fun message(accountId: String, messageId: String): MailMessageDetail? = boundary(
        fallback = { null },
    ) {
        messageInternal(accountId, messageId)
    }

    override suspend fun mark(accountId: String, messageId: String, unread: Boolean): Unit = boundary(
        fallback = { error -> throw localizedFailure(error) },
    ) {
        markInternal(accountId, messageId, unread)
    }

    override suspend fun archive(accountId: String, messageId: String): Unit = boundary(
        fallback = { error -> throw localizedFailure(error) },
    ) {
        archiveInternal(accountId, messageId)
    }

    override suspend fun trash(accountId: String, messageId: String): Unit = boundary(
        fallback = { error -> throw localizedFailure(error) },
    ) {
        trashInternal(accountId, messageId)
    }

    override suspend fun deleteForever(accountId: String, messageId: String): Unit = boundary(
        fallback = { error -> throw localizedFailure(error) },
    ) {
        deleteForeverInternal(accountId, messageId)
    }

    override suspend fun importDraftAttachment(
        draftId: String,
        sourcePath: String,
        name: String,
        mimeType: String,
    ): DraftAttachment = boundary(
        fallback = { error -> throw localizedFailure(error) },
    ) {
        importDraftAttachmentInternal(draftId, sourcePath, name, mimeType)
    }

    override suspend fun removeDraftAttachment(draftId: String, attachment: DraftAttachment): Unit = boundary(
        fallback = { },
    ) {
        removeDraftAttachmentInternal(draftId, attachment)
    }

    override suspend fun saveDraft(draft: MailDraft): MailDraft = boundary(
        fallback = { error -> throw localizedFailure(error) },
    ) {
        saveDraftInternal(draft)
    }

    override suspend fun deleteDraft(draftId: String): Unit = boundary(fallback = { }) {
        deleteDraftInternal(draftId)
    }

    override suspend fun sendDraft(draftId: String): MailSendResult = boundary(
        fallback = { error -> MailSendResult(ok = false, error = safeDescribe(error)) },
    ) {
        sendDraftInternal(draftId)
    }

    override suspend fun search(query: String, accountId: String?): List<MailMessageSummary> = boundary(
        fallback = { emptyList() },
    ) {
        searchInternal(query, accountId)
    }

    override suspend fun loadAttachment(accountId: String, messageId: String, attachmentId: String): String? = boundary(
        fallback = { error -> throw localizedFailure(error) },
    ) {
        loadAttachmentInternal(accountId, messageId, attachmentId)
    }

    override suspend fun loadInlineImage(accountId: String, messageId: String, contentId: String): String? = boundary(
        fallback = { null },
    ) {
        loadInlineImageInternal(accountId, messageId, contentId)
    }

    private suspend fun startInternal() {
        if (stateMutex.withLock { started }) return
        val loaded = withContext(Dispatchers.IO) {
            val loadedAccounts = store.accounts()
            val loadedDrafts = store.drafts()
            store.retainDraftData(loadedDrafts.map { it.id }.toSet())
            store.cleanupOutgoingTemp()
            val settings = runCatching { host.settings().snapshot() }.getOrDefault(emptyMap())
            LoadedState(
                accounts = loadedAccounts,
                servers = store.servers().associateBy { it.accountId },
                indexes = loadedAccounts.associate { it.id to store.index(it.id) },
                drafts = loadedDrafts,
                lastSyncAt = settings[SETTING_LAST_SYNC_AT]?.toLongOrNull(),
                lastSyncByAccount = loadedAccounts.mapNotNull { account ->
                    settings["$SETTING_LAST_SYNC_PREFIX${account.id}"]?.toLongOrNull()?.let { account.id to it }
                }.toMap(),
            )
        }
        stateMutex.withLock {
            if (started) return
            accounts = loaded.accounts
            servers = loaded.servers
            indexes = loaded.indexes.toMutableMap()
            drafts = loaded.drafts
            lastSyncByAccount = loaded.lastSyncByAccount
            started = true
            publishLocked()
            _state.update { it.copy(loading = false, lastSyncAt = loaded.lastSyncAt) }
        }
        scheduleBackgroundSync()
        syncJob = host.scope.launch {
            try {
                refreshInternal(force = true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                report(error)
            }
        }
    }

    private suspend fun stopInternal() {
        syncJob?.cancel()
        syncJob = null
        stateMutex.withLock { started = false }
        _state.update { it.copy(syncing = false) }
        cancelBackgroundSync()
    }

    internal suspend fun runBackgroundSync() {
        if (!stateMutex.withLock { started }) return
        if (_state.value.syncing) return
        try {
            refreshInternal(force = true)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            report(error)
            markSyncFailure(safeDescribe(error))
        }
    }

    private suspend fun scheduleBackgroundSync() {
        try {
            host.background().schedule(
                id = MAIL_SYNC_TASK_ID,
                kind = PluginBackgroundKind.PERIODIC,
                intervalMs = SyncPolicy.BACKGROUND_SYNC_INTERVAL_MS,
                requiresNetwork = true,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            report(error)
        }
    }

    private suspend fun cancelBackgroundSync() {
        try {
            host.background().cancel(MAIL_SYNC_TASK_ID)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            report(error)
        }
    }

    private suspend fun testConnectionInternal(input: MailAccountInput): MailConnectionTest {
        val email = input.email.trim()
        if (!EMAIL.matches(email)) return MailConnectionTest(false, false, texts.invalidEmail(), texts.invalidEmail())
        if (input.password.isBlank()) {
            return MailConnectionTest(false, false, texts.missingPassword(), texts.missingPassword())
        }
        val servers = try {
            resolveServers(input.providerId, input.imapHost, input.imapPort, input.smtpHost, input.smtpPort)
        } catch (error: MailLocalizedException) {
            val message = error.message.orEmpty()
            return MailConnectionTest(false, false, message, message)
        }
        return testServers(servers, email, input.password)
    }

    private suspend fun addAccountInternal(input: MailAccountInput): MailConnectionTest {
        val test = testConnectionInternal(input)
        if (!test.ok) return test
        val email = input.email.trim()
        val accountId = accountId(email)
        val serverSettings = resolveServers(input.providerId, input.imapHost, input.imapPort, input.smtpHost, input.smtpPort)
        host.secrets().put(secretKey(accountId), input.password)
        stateMutex.withLock {
            val existing = accounts.firstOrNull { it.id == accountId }
            val account = existing?.copy(
                providerId = input.providerId,
                email = email,
                displayName = input.displayName.trim().ifBlank { existing.displayName },
            ) ?: MailAccount(
                id = accountId,
                providerId = input.providerId,
                email = email,
                displayName = input.displayName.trim().ifBlank { email.substringBefore('@') },
                color = paletteColor(accountId),
                createdAt = System.currentTimeMillis(),
            )
            accounts = accounts.filterNot { it.id == accountId } + account
            servers = servers + (
                accountId to MailServerSettings(
                    accountId = accountId,
                    imapHost = serverSettings.imapHost,
                    imapPort = serverSettings.imapPort,
                    smtpHost = serverSettings.smtpHost,
                    smtpPort = serverSettings.smtpPort,
                )
                )
            indexes.getOrPut(accountId) { MailAccountIndex(accountId = accountId) }
            accountErrors = accountErrors - accountId
            persistAccounts()
            persistServers()
            publishLocked()
        }
        launchRefresh()
        return test
    }

    private suspend fun updateAccountInternal(accountId: String, update: MailAccountUpdate) {
        stateMutex.withLock {
            accounts = accounts.map { account ->
                if (account.id != accountId) {
                    account
                } else {
                    account.copy(
                        displayName = update.displayName?.trim()?.takeIf { it.isNotEmpty() } ?: account.displayName,
                        notify = update.notify ?: account.notify,
                    )
                }
            }
            persistAccounts()
            publishLocked()
        }
    }

    private suspend fun removeAccountInternal(accountId: String) {
        stateMutex.withLock {
            val removedDrafts = drafts.filter { it.accountId == accountId }.map { it.id }
            accounts = accounts.filterNot { it.id == accountId }
            servers = servers - accountId
            indexes.remove(accountId)
            drafts = drafts.filterNot { it.accountId == accountId }
            removedDrafts.forEach { draftId -> store.deleteDraftData(draftId) }
            accountErrors = accountErrors - accountId
            lastSyncByAccount = lastSyncByAccount - accountId
            notifiedAt.remove(accountId)
            persistAccounts()
            persistServers()
            persistDrafts()
            store.deleteAccount(accountId)
            publishLocked()
        }
        host.secrets().remove(secretKey(accountId))
        removeSyncSetting(accountId)
    }

    private suspend fun reauthorizeInternal(accountId: String, password: String): MailConnectionTest {
        val account = stateMutex.withLock { accounts.firstOrNull { it.id == accountId } }
            ?: return MailConnectionTest(false, false, texts.missingAccount(), texts.missingAccount())
        if (password.isBlank()) {
            return MailConnectionTest(false, false, texts.missingPassword(), texts.missingPassword())
        }
        val settings = stateMutex.withLock { servers[accountId] }
            ?: return MailConnectionTest(false, false, texts.missingServers(), texts.missingServers())
        val resolved = ResolvedServers(settings.imapHost, settings.imapPort, settings.smtpHost, settings.smtpPort)
        val test = testServers(resolved, account.email, password)
        if (test.ok) {
            host.secrets().put(secretKey(accountId), password)
            stateMutex.withLock {
                accountErrors = accountErrors - accountId
                publishLocked()
            }
            launchRefresh()
        }
        return test
    }

    private suspend fun messageInternal(accountId: String, messageId: String): MailMessageDetail? {
        val summary = findMessage(accountId, messageId) ?: return null
        val account = stateMutex.withLock { accounts.firstOrNull { it.id == accountId } } ?: return null
        val file = ensureBodyFile(account, summary) ?: return null
        val parsed = try {
            MailMimeParser.parse(file)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            report(error)
            return null
        }
        val updated = MailMessageMapper.withParsed(summary, parsed)
        if (updated != summary) updateMessage(updated)
        return MailMessageDetail(
            summary = updated,
            cc = parsed.headers.cc.map { it.display },
            textBody = parsed.textBody,
            htmlBody = parsed.htmlBody,
            attachments = parsed.attachments.map { attachment ->
                MailAttachment(
                    id = attachment.id,
                    name = attachment.name,
                    mimeType = attachment.mimeType,
                    sizeBytes = attachment.sizeBytes,
                    inline = attachment.inline,
                    contentId = attachment.contentId,
                )
            },
        )
    }

    private suspend fun markInternal(accountId: String, messageId: String, unread: Boolean) {
        val summary = requireMessage(accountId, messageId)
        if (summary.unread == unread) return
        val account = requireAccount(accountId)
        val folder = folderOf(accountId, summary.folderRole) ?: throw MailLocalizedException(texts.missingMessage())
        try {
            withImap(account) { client ->
                client.select(folder, readOnly = false)
                client.storeSeen(summary.uid, seen = !unread)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            throw MailLocalizedException(texts.describeImap(error), error)
        }
        updateMessage(summary.copy(unread = unread))
    }

    private suspend fun archiveInternal(accountId: String, messageId: String) {
        val summary = requireMessage(accountId, messageId)
        if (summary.folderRole == MailFolderRole.ARCHIVE) return
        val target = folderOf(accountId, MailFolderRole.ARCHIVE)
            ?: throw MailLocalizedException(texts.missingArchiveFolder())
        moveMessage(summary, target)
    }

    private suspend fun trashInternal(accountId: String, messageId: String) {
        val summary = requireMessage(accountId, messageId)
        if (summary.folderRole == MailFolderRole.TRASH) return
        val target = folderOf(accountId, MailFolderRole.TRASH)
            ?: throw MailLocalizedException(texts.missingTrashFolder())
        moveMessage(summary, target)
    }

    private suspend fun deleteForeverInternal(accountId: String, messageId: String) {
        val summary = requireMessage(accountId, messageId)
        val account = requireAccount(accountId)
        val source = folderOf(accountId, summary.folderRole) ?: throw MailLocalizedException(texts.missingMessage())
        try {
            withImap(account) { client ->
                client.select(source, readOnly = false)
                client.delete(summary.uid)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            throw MailLocalizedException(texts.describeImap(error), error)
        }
        removeMessage(summary)
    }

    private suspend fun moveMessage(summary: MailMessageSummary, target: String) {
        val account = requireAccount(summary.accountId)
        val source = folderOf(summary.accountId, summary.folderRole)
            ?: throw MailLocalizedException(texts.missingMessage())
        try {
            withImap(account) { client ->
                client.select(source, readOnly = false)
                client.move(summary.uid, target)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            throw MailLocalizedException(texts.describeImap(error), error)
        }
        removeMessage(summary)
    }

    private suspend fun saveDraftInternal(draft: MailDraft): MailDraft {
        val now = System.currentTimeMillis()
        val saved = draft.copy(
            id = draft.id.ifBlank { "draft-$now-${UUID.randomUUID().toString().take(8)}" },
            createdAt = if (draft.createdAt == 0L) now else draft.createdAt,
            updatedAt = now,
        )
        stateMutex.withLock {
            drafts = drafts.filterNot { it.id == saved.id } + saved
            persistDrafts()
            store.pruneDraftAttachments(saved.id, saved.attachments.map { it.path }.toSet())
            publishLocked()
        }
        return saved
    }

    private suspend fun deleteDraftInternal(draftId: String) {
        stateMutex.withLock {
            drafts = drafts.filterNot { it.id == draftId }
            persistDrafts()
            store.deleteDraftData(draftId)
            publishLocked()
        }
    }

    private suspend fun importDraftAttachmentInternal(
        draftId: String,
        sourcePath: String,
        name: String,
        mimeType: String,
    ): DraftAttachment {
        if (draftId.isBlank()) throw MailLocalizedException(texts.missingDraft())
        return withContext(Dispatchers.IO) {
            try {
                store.importDraftAttachment(
                    draftId = draftId,
                    sourcePath = sourcePath,
                    name = name,
                    mimeType = mimeType,
                    maxBytes = MailComposeLimits.MAX_ATTACHMENT_BYTES,
                    maxTotalBytes = MailComposeLimits.MAX_TOTAL_ATTACHMENT_BYTES,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: DraftAttachmentException) {
                throw MailLocalizedException(draftAttachmentError(failure), failure)
            } catch (error: Exception) {
                report(error)
                throw MailLocalizedException(texts.draftAttachmentInvalid(), error)
            }
        }
    }

    private suspend fun removeDraftAttachmentInternal(draftId: String, attachment: DraftAttachment) {
        withContext(Dispatchers.IO) { store.removeDraftAttachmentFile(attachment.path) }
        stateMutex.withLock {
            val existing = drafts.firstOrNull { it.id == draftId } ?: return
            drafts = drafts.map { draft ->
                if (draft.id == draftId) {
                    draft.copy(attachments = draft.attachments.filterNot { it.path == attachment.path })
                } else {
                    draft
                }
            }
            persistDrafts()
            store.pruneDraftAttachments(existing.id, drafts.first { it.id == draftId }.attachments.map { it.path }.toSet())
            publishLocked()
        }
    }

    private suspend fun sendDraftInternal(draftId: String): MailSendResult {
        val draft = stateMutex.withLock { drafts.firstOrNull { it.id == draftId } }
            ?: return MailSendResult(ok = false, error = texts.missingDraft())
        if (draft.to.isEmpty()) return MailSendResult(ok = false, error = texts.missingRecipients())
        val account = stateMutex.withLock { accounts.firstOrNull { it.id == draft.accountId } }
            ?: return MailSendResult(ok = false, error = texts.missingAccount())
        val settings = stateMutex.withLock { servers[account.id] }
            ?: return MailSendResult(ok = false, error = texts.missingServers())
        val password = host.secrets().get(secretKey(account.id))
            ?: return MailSendResult(ok = false, error = texts.missingSecret())
        val missing = draft.attachments.firstOrNull { attachment -> !File(attachment.path).isFile }
        if (missing != null) return MailSendResult(ok = false, error = texts.draftAttachmentMissing(missing.name))
        val reply = replyContext(account, draft)
        val outgoing = OutgoingMail(
            fromAddress = account.email,
            fromName = account.displayName,
            to = draft.to,
            cc = draft.cc,
            subject = draft.subject,
            body = draft.body,
            inReplyTo = reply.first,
            references = reply.second,
            attachments = draft.attachments.map { attachment ->
                OutgoingAttachment(path = attachment.path, name = attachment.name, mimeType = attachment.mimeType)
            },
        )
        val temporary = store.outgoingTempFile()
        val messageSize = try {
            withContext(Dispatchers.IO) { MailComposer.composeToFile(outgoing, temporary) }
        } catch (cancelled: CancellationException) {
            temporary.delete()
            throw cancelled
        } catch (compose: MailComposeException) {
            temporary.delete()
            return MailSendResult(ok = false, error = composeErrorMessage(compose))
        } catch (error: Exception) {
            temporary.delete()
            report(error)
            return MailSendResult(ok = false, error = texts.storageFailed())
        }
        val recipients = draft.to + draft.cc
        val error = withContext(Dispatchers.IO) {
            val client = transport.smtp(
                settings.smtpHost,
                settings.smtpPort,
                SmtpCredentials(account.email, password),
            )
            try {
                client.connect()
                client.login()
                client.sendFile(account.email, recipients, temporary)
                client.quit()
                null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                texts.describeSmtp(failure)
            } finally {
                client.close()
            }
        }
        if (error != null) {
            temporary.delete()
            return MailSendResult(ok = false, error = error)
        }
        try {
            val sentFolder = folderOf(account.id, MailFolderRole.SENT)
            if (sentFolder != null) {
                try {
                    withImap(account) { client ->
                        client.select(sentFolder, readOnly = false)
                        temporary.inputStream().use { input ->
                            client.appendStream(sentFolder, listOf("\\Seen"), messageSize, input)
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    report(failure)
                }
            }
            deleteDraftInternal(draft.id)
        } finally {
            temporary.delete()
        }
        return MailSendResult(ok = true)
    }

    private fun draftAttachmentError(error: DraftAttachmentException): String = when (error.failure) {
        DraftAttachmentFailure.SOURCE_MISSING -> texts.draftAttachmentInvalid()
        DraftAttachmentFailure.SOURCE_UNREADABLE -> texts.draftAttachmentInvalid()
        DraftAttachmentFailure.TOO_LARGE -> texts.draftAttachmentTooLarge()
        DraftAttachmentFailure.TOTAL_TOO_LARGE -> texts.draftAttachmentsTooLarge()
    }

    private fun composeErrorMessage(error: MailComposeException): String = when (error.failure) {
        MailComposeFailure.ATTACHMENT_MISSING -> texts.draftAttachmentMissing(error.attachmentName.orEmpty())
        MailComposeFailure.ATTACHMENT_UNREADABLE -> texts.draftAttachmentInvalid()
        MailComposeFailure.ATTACHMENT_TOO_LARGE -> texts.draftAttachmentTooLarge()
        MailComposeFailure.TOTAL_TOO_LARGE -> texts.draftAttachmentsTooLarge()
    }

    private suspend fun searchInternal(query: String, accountId: String?): List<MailMessageSummary> {
        val term = query.trim()
        val messages = stateMutex.withLock { indexes.values.flatMap { it.messages } }
        return messages.asSequence()
            .filter { accountId == null || it.accountId == accountId }
            .filter { matches(it, term) }
            .sortedWith(compareByDescending<MailMessageSummary> { it.dateMillis }.thenByDescending { it.uid })
            .toList()
    }

    private suspend fun loadAttachmentInternal(accountId: String, messageId: String, attachmentId: String): String? {
        val summary = findMessage(accountId, messageId) ?: return null
        val account = stateMutex.withLock { accounts.firstOrNull { it.id == accountId } } ?: return null
        val bodyFile = ensureBodyFile(account, summary) ?: return null
        val metadata = try {
            MailMimeParser.parse(bodyFile)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            report(error)
            return null
        }
        val attachment = metadata.attachments.firstOrNull { it.id == attachmentId } ?: return null
        if (attachment.sizeBytes > SyncPolicy.MAX_ATTACHMENT_BYTES) {
            throw MailLocalizedException(texts.attachmentTooLarge())
        }
        val key = MessageKeys.key(summary.folderRole, summary.uid)
        val file = store.attachmentFile(accountId, key, attachment.id, attachment.name)
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, file.name + ".part")
        temporary.delete()
        val written = try {
            MailMimeParser.extractAttachmentTo(bodyFile, attachmentId, temporary, SyncPolicy.MAX_ATTACHMENT_BYTES)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            report(error)
            null
        } ?: run {
            temporary.delete()
            throw MailLocalizedException(texts.attachmentUnavailable())
        }
        if (file.isFile && file.length() == written) {
            temporary.delete()
            return file.absolutePath
        }
        file.delete()
        if (!temporary.renameTo(file)) {
            temporary.delete()
            throw MailLocalizedException(texts.storageFailed())
        }
        return file.absolutePath
    }

    private suspend fun loadInlineImageInternal(accountId: String, messageId: String, contentId: String): String? {
        val normalized = MailMimeParser.normalizeContentId(contentId) ?: return null
        val summary = findMessage(accountId, messageId) ?: return null
        val account = stateMutex.withLock { accounts.firstOrNull { it.id == accountId } } ?: return null
        return try {
            val detailAttachment = messageInternal(accountId, messageId)
                ?.attachments
                ?.firstOrNull { it.inline && it.contentId.equals(normalized, ignoreCase = true) }
            val attachment = detailAttachment ?: findInlineAttachment(account, summary, normalized) ?: return null
            if (attachment.sizeBytes > MailLimits.MAX_INLINE_IMAGE_BYTES) return null
            val key = MessageKeys.key(summary.folderRole, summary.uid)
            val cached = store.inlineImageFile(key, normalized, attachment.name)
            if (cached.isFile && cached.length() > 0) {
                store.touchInlineImage(cached)
                return cached.absolutePath
            }
            val bodyFile = ensureBodyFile(account, summary) ?: return null
            val temporary = store.inlineImageTempFile()
            val written = try {
                MailMimeParser.extractAttachmentTo(
                    file = bodyFile,
                    attachmentId = attachment.id,
                    destination = temporary,
                    maxBytes = MailLimits.MAX_INLINE_IMAGE_BYTES,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                report(error)
                null
            }
            if (written == null) {
                temporary.delete()
                return null
            }
            store.commitInlineImage(temporary, cached)?.absolutePath
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (localized: MailLocalizedException) {
            null
        } catch (error: Throwable) {
            report(error)
            null
        }
    }

    private suspend fun findInlineAttachment(
        account: MailAccount,
        summary: MailMessageSummary,
        contentId: String,
    ): MailAttachment? {
        val bodyFile = ensureBodyFile(account, summary) ?: return null
        val parsed = try {
            MailMimeParser.parse(bodyFile)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            report(error)
            return null
        }
        val match = parsed.attachments.firstOrNull { it.inline && it.contentId.equals(contentId, ignoreCase = true) }
            ?: return null
        return MailAttachment(
            id = match.id,
            name = match.name,
            mimeType = match.mimeType,
            sizeBytes = match.sizeBytes,
            inline = match.inline,
            contentId = match.contentId,
        )
    }

    private suspend fun refreshInternal(force: Boolean) {
        if (force) {
            refreshLock.withLock { runSyncPass() }
            return
        }
        if (_state.value.syncing) return
        if (!refreshLock.tryLock()) return
        try {
            if (System.currentTimeMillis() - lastRefreshAt < SyncPolicy.REFRESH_DEBOUNCE_MS) return
            runSyncPass()
        } finally {
            refreshLock.unlock()
        }
    }

    private suspend fun runSyncPass() {
        val snapshot = stateMutex.withLock {
            if (!started) return
            _state.update { it.copy(syncing = true, loading = false) }
            accounts
        }
        var lastError: String? = null
        try {
            for (account in snapshot) {
                try {
                    syncAccount(account)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    val message = safeDescribe(error)
                    host.logs().warn("mail sync failed: ${account.email}", error)
                    lastError = message
                    stateMutex.withLock {
                        accountErrors = accountErrors + (account.id to message)
                        publishLocked()
                    }
                }
            }
        } finally {
            runCatching {
                _state.update {
                    it.copy(
                        syncing = false,
                        loading = false,
                        syncError = lastError,
                        lastSyncAt = System.currentTimeMillis(),
                    )
                }
            }
            lastRefreshAt = System.currentTimeMillis()
        }
    }

    private suspend fun syncAccount(account: MailAccount) {
        val index = stateMutex.withLock { indexes[account.id] ?: store.index(account.id) }
        var attempt = 0
        while (true) {
            try {
                val folderStates = mutableListOf<MailFolderIndex>()
                val messages = mutableListOf<MailMessageSummary>()
                val newUnread = mutableListOf<MailMessageSummary>()
                withImap(account) { client ->
                    val mapping = FolderMapping.resolve(client.listFolders())
                    if (mapping[MailFolderRole.INBOX] == null) throw MailLocalizedException(texts.missingInbox())
                    for ((role, remoteName) in mapping) {
                        val result = syncFolder(client, account, role, remoteName, index)
                        folderStates += result.folder
                        messages += result.messages
                        newUnread += result.newUnread
                    }
                }
                commitAccount(account, folderStates, messages, newUnread)
                return
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (attempt == 0 && error is Exception && error.isRetryable()) {
                    attempt += 1
                    continue
                }
                throw error
            }
        }
    }

    private suspend fun syncFolder(
        client: ImapClient,
        account: MailAccount,
        role: MailFolderRole,
        remoteName: String,
        index: MailAccountIndex,
    ): FolderSync {
        val status = client.select(remoteName, readOnly = true)
        val uidValidity = status.uidValidity ?: 0L
        val previous = index.folders.firstOrNull { it.role == role }
        if (previous == null || previous.uidValidity != uidValidity) {
            val serverUids = client.searchAll()
            val candidates = if (serverUids.size > SyncPolicy.MAX_MESSAGES_PER_FOLDER) {
                serverUids.takeLast(SyncPolicy.MAX_MESSAGES_PER_FOLDER)
            } else {
                serverUids
            }
            val fetched = client.fetchHeaders(candidates)
            val messages = prefetchBodies(
                client,
                account,
                role,
                fetched.map { MailMessageMapper.toSummary(account.id, role, it) },
            )
            return FolderSync(
                folder = MailFolderIndex(role, remoteName, uidValidity, serverUids.maxOrNull() ?: 0L),
                messages = messages,
                newUnread = emptyList(),
            )
        }
        val lastUid = previous.lastUid
        val newUids = client.searchNewUids(lastUid)
        val newMessages = if (newUids.isEmpty()) {
            emptyList()
        } else {
            val fetched = client.fetchHeaders(newUids)
            prefetchBodies(
                client,
                account,
                role,
                fetched.map { MailMessageMapper.toSummary(account.id, role, it) },
            )
        }
        val previousMessages = index.messages.filter { it.folderRole == role }
        val window = SyncPolicy.flagWindow(previousMessages, role)
        val windowUids = window.map { it.uid }.toSet()
        val windowFlags = if (windowUids.isEmpty()) {
            emptyMap()
        } else {
            client.fetchFlags(windowUids)
        }
        val kept = previousMessages.mapNotNull { message ->
            if (message.uid !in windowUids) return@mapNotNull message
            val flags = windowFlags[message.uid] ?: return@mapNotNull null
            message.copy(
                unread = flags.none { it.equals("\\Seen", ignoreCase = true) },
                starred = flags.any { it.equals("\\Flagged", ignoreCase = true) },
            )
        }
        return FolderSync(
            folder = MailFolderIndex(
                role = role,
                remoteName = remoteName,
                uidValidity = uidValidity,
                lastUid = maxOf(lastUid, newUids.maxOrNull() ?: lastUid),
            ),
            messages = kept + newMessages,
            newUnread = newMessages.filter { it.unread },
        )
    }

    private suspend fun prefetchBodies(
        client: ImapClient,
        account: MailAccount,
        role: MailFolderRole,
        messages: List<MailMessageSummary>,
    ): List<MailMessageSummary> {
        if (messages.isEmpty()) return messages
        val targets = selectPrefetchTargets(account, role, messages)
        if (targets.isEmpty()) return messages
        val outcomes = try {
            client.fetchBodiesToFiles(targets, SyncPolicy.PREFETCH_MAX_MESSAGE_BYTES)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            report(error)
            return messages
        }
        val saved = outcomes.filterIsInstance<ImapBodyOutcome.Saved>().associateBy { it.uid }
        if (saved.isEmpty()) return messages
        val result = messages.toMutableList()
        for ((position, message) in result.withIndex()) {
            if (message.uid !in saved) continue
            try {
                val file = store.bodyFile(account.id, MessageKeys.key(role, message.uid))
                result[position] = MailMessageMapper.withParsed(message, MailMimeParser.parse(file))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                report(error)
            }
        }
        return result
    }

    private fun selectPrefetchTargets(
        account: MailAccount,
        role: MailFolderRole,
        messages: List<MailMessageSummary>,
    ): List<ImapBodyTarget> = SyncPolicy.prefetchCandidates(messages).map { message ->
        ImapBodyTarget(
            uid = message.uid,
            file = store.bodyFile(account.id, MessageKeys.key(role, message.uid)),
            declaredSize = message.sizeBytes,
        )
    }

    private suspend fun commitAccount(
        account: MailAccount,
        folders: List<MailFolderIndex>,
        messages: List<MailMessageSummary>,
        newUnread: List<MailMessageSummary>,
    ) {
        val now = System.currentTimeMillis()
        val keptIds = stateMutex.withLock {
            if (accounts.none { it.id == account.id }) return
            val capped = SyncPolicy.capAccount(messages)
            val index = MailAccountIndex(
                accountId = account.id,
                folders = folders.sortedBy { it.role.ordinal },
                messages = capped,
                updatedAt = now,
            )
            indexes[account.id] = index
            store.saveIndex(index)
            val keys = capped.map { MessageKeys.key(it.folderRole, it.uid) }.toSet()
            store.retainBodies(account.id, keys)
            store.retainAttachmentDirectories(account.id, keys)
            accountErrors = accountErrors - account.id
            lastSyncByAccount = lastSyncByAccount + (account.id to now)
            publishLocked()
            capped.map { it.id }.toSet()
        }
        persistSyncSetting(account.id, now)
        notifyNewMail(account, newUnread.filter { it.id in keptIds && it.folderRole == MailFolderRole.INBOX })
    }

    private suspend fun notifyNewMail(account: MailAccount, newUnread: List<MailMessageSummary>) {
        try {
            if (newUnread.isEmpty() || !account.notify) return
            val now = System.currentTimeMillis()
            val previous = notifiedAt[account.id] ?: 0L
            if (now - previous < SyncPolicy.NOTIFY_THROTTLE_MS) return
            notifiedAt[account.id] = now
            val latest = newUnread.maxByOrNull { it.dateMillis }
            val count = newUnread.size
            val subject = latest?.subject.orEmpty()
            val title = host.text(localizedText(default = "新邮件", zh = "新邮件", en = "New mail"))
            val body = host.text(
                localizedText(
                    default = if (subject.isBlank()) {
                        "${account.email} 收到 $count 封新邮件"
                    } else {
                        "${account.email} 收到 $count 封新邮件，最新：$subject"
                    },
                    zh = if (subject.isBlank()) {
                        "${account.email} 收到 $count 封新邮件"
                    } else {
                        "${account.email} 收到 $count 封新邮件，最新：$subject"
                    },
                    en = if (subject.isBlank()) {
                        "${account.email}: $count new messages"
                    } else {
                        "${account.email}: $count new messages, latest: $subject"
                    },
                ),
            )
            val latestId = latest?.id.orEmpty()
            host.notifications().notifyChecked(
                pluginNotificationWithActions(
                    id = "mail-unread-${account.id}",
                    title = title,
                    body = body,
                    channel = NotificationChannel.DEFAULT,
                    actions = listOf(mailOpenAction(host)),
                    payload = mailNotificationPayload(
                        threadId = latest?.threadId.orEmpty(),
                        accountId = account.id,
                        messageId = latestId,
                    ),
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            report(error)
        }
    }

    private suspend fun ensureBodyFile(account: MailAccount, summary: MailMessageSummary): File? {
        val key = MessageKeys.key(summary.folderRole, summary.uid)
        val file = store.bodyFile(account.id, key)
        if (file.isFile && file.length() > 0) return file
        val folder = folderOf(account.id, summary.folderRole) ?: return null
        val limit = SyncPolicy.MAX_MESSAGE_BODY_BYTES
        if (summary.sizeBytes > limit) throw MailLocalizedException(texts.messageTooLarge())
        val outcome = try {
            withImap(account) { client ->
                client.select(folder, readOnly = true)
                client.fetchBodiesToFiles(listOf(ImapBodyTarget(summary.uid, file, summary.sizeBytes)), limit)
                    .firstOrNull()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (localized: MailLocalizedException) {
            throw localized
        } catch (error: Exception) {
            host.logs().warn("mail: cannot fetch message body", error)
            null
        } catch (error: Throwable) {
            report(error)
            null
        }
        return when (outcome) {
            is ImapBodyOutcome.Saved -> file
            is ImapBodyOutcome.Skipped -> if (outcome.reason == ImapBodySkip.TOO_LARGE) {
                throw MailLocalizedException(texts.messageTooLarge())
            } else {
                null
            }
            null -> null
        }
    }

    private suspend fun replyContext(account: MailAccount, draft: MailDraft): Pair<String?, List<String>> {
        val replyTo = draft.inReplyToId?.takeIf { it.isNotBlank() } ?: return null to emptyList()
        val summary = findMessage(account.id, replyTo) ?: return null to emptyList()
        val file = try {
            ensureBodyFile(account, summary)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            null
        } ?: return null to emptyList()
        return try {
            val headers = MailMimeParser.parse(file).headers
            val messageId = headers.messageId ?: return null to emptyList()
            messageId to (headers.references + messageId).distinct().takeLast(20)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            null to emptyList()
        }
    }

    private suspend fun findMessage(accountId: String, messageId: String): MailMessageSummary? =
        stateMutex.withLock { findMessageLocked(accountId, messageId) }

    private fun findMessageLocked(accountId: String, messageId: String): MailMessageSummary? {
        val messages = indexes[accountId]?.messages ?: return null
        val raw = messageId.trim()
        if (raw.isEmpty()) return null
        messages.firstOrNull { it.id == raw }?.let { return it }
        val stripped = raw.removePrefix("$accountId:")
        messages.firstOrNull { it.id == stripped }?.let { return it }
        messages.firstOrNull { it.id == "$accountId:$stripped" }?.let { return it }
        val uid = stripped.substringAfterLast(':').toLongOrNull() ?: return null
        val roleName = stripped.substringBeforeLast(':', "")
        val role = MessageKeys.roleFromName(roleName)
        return messages.firstOrNull { it.uid == uid && (role == null || it.folderRole == role) }
    }

    private suspend fun requireMessage(accountId: String, messageId: String): MailMessageSummary =
        findMessage(accountId, messageId) ?: throw MailLocalizedException(texts.missingMessage())

    private suspend fun requireAccount(accountId: String): MailAccount =
        stateMutex.withLock { accounts.firstOrNull { it.id == accountId } }
            ?: throw MailLocalizedException(texts.missingAccount())

    private suspend fun folderOf(accountId: String, role: MailFolderRole): String? =
        stateMutex.withLock { folderOfLocked(accountId, role) }

    private fun folderOfLocked(accountId: String, role: MailFolderRole): String? =
        indexes[accountId]?.folders?.firstOrNull { it.role == role }?.remoteName

    private suspend fun updateMessage(summary: MailMessageSummary) {
        stateMutex.withLock {
            val index = indexes[summary.accountId] ?: return
            if (index.messages.none { it.id == summary.id }) return
            val updated = index.copy(
                messages = index.messages.map { if (it.id == summary.id) summary else it },
                updatedAt = System.currentTimeMillis(),
            )
            indexes[summary.accountId] = updated
            store.saveIndex(updated)
            publishLocked()
        }
    }

    private suspend fun removeMessage(summary: MailMessageSummary) {
        stateMutex.withLock {
            val index = indexes[summary.accountId] ?: return
            val key = MessageKeys.key(summary.folderRole, summary.uid)
            val updated = index.copy(
                messages = index.messages.filterNot { it.id == summary.id },
                updatedAt = System.currentTimeMillis(),
            )
            indexes[summary.accountId] = updated
            store.saveIndex(updated)
            store.deleteBody(summary.accountId, key)
            store.deleteAttachmentDirectory(summary.accountId, key)
            publishLocked()
        }
    }

    private suspend fun <T> withImap(account: MailAccount, block: suspend (ImapClient) -> T): T {
        val settings = stateMutex.withLock { servers[account.id] }
            ?: throw MailLocalizedException(texts.missingServers())
        val password = host.secrets().get(secretKey(account.id))?.takeIf { it.isNotBlank() }
            ?: throw MailLocalizedException(texts.missingSecret())
        return withContext(Dispatchers.IO) {
            var lastError: Exception? = null
            for (attempt in 0..1) {
                val client = transport.imap(
                    settings.imapHost,
                    settings.imapPort,
                    ImapCredentials(account.email, password),
                )
                try {
                    client.connect()
                    client.login()
                    return@withContext block(client)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    if (attempt == 0 && error.isRetryable()) {
                        lastError = error
                    } else {
                        throw error
                    }
                } finally {
                    client.close()
                }
            }
            throw lastError ?: MailLocalizedException(texts.operationFailed())
        }
    }

    private suspend fun testServers(
        servers: ResolvedServers,
        email: String,
        password: String,
    ): MailConnectionTest {
        val imapError = withContext(Dispatchers.IO) {
            val client = transport.imap(
                servers.imapHost,
                servers.imapPort,
                ImapCredentials(email, password),
            )
            try {
                client.connect()
                client.login()
                client.listFolders()
                null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                texts.describeImap(error)
            } catch (error: Throwable) {
                report(error)
                safeDescribe(error)
            } finally {
                client.close()
            }
        }
        val smtpError = withContext(Dispatchers.IO) {
            val client = transport.smtp(
                servers.smtpHost,
                servers.smtpPort,
                SmtpCredentials(email, password),
            )
            try {
                client.connect()
                client.login()
                client.quit()
                null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                texts.describeSmtp(error)
            } catch (error: Throwable) {
                report(error)
                safeDescribe(error)
            } finally {
                client.close()
            }
        }
        return MailConnectionTest(
            imapOk = imapError == null,
            smtpOk = smtpError == null,
            imapError = imapError,
            smtpError = smtpError,
        )
    }

    private fun launchRefresh() {
        host.scope.launch {
            try {
                refreshInternal(force = true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                report(error)
            }
        }
    }

    private fun resolveServers(
        providerId: String,
        imapHost: String?,
        imapPort: Int?,
        smtpHost: String?,
        smtpPort: Int?,
    ): ResolvedServers {
        val preset = ProviderCatalog.byId(providerId)
        val resolvedImapHost = imapHost?.trim()?.takeIf { it.isNotEmpty() } ?: preset.imapHost.trim()
        val resolvedSmtpHost = smtpHost?.trim()?.takeIf { it.isNotEmpty() } ?: preset.smtpHost.trim()
        if (resolvedImapHost.isEmpty() || resolvedSmtpHost.isEmpty()) throw MailLocalizedException(texts.missingServers())
        val resolvedImapPort = imapPort?.takeIf { it in 1..65535 } ?: preset.imapPort
        val resolvedSmtpPort = smtpPort?.takeIf { it in 1..65535 } ?: preset.smtpPort
        return ResolvedServers(resolvedImapHost, resolvedImapPort, resolvedSmtpHost, resolvedSmtpPort)
    }

    private fun publishLocked() {
        val messages = indexes.values
            .flatMap { it.messages }
            .sortedWith(compareByDescending<MailMessageSummary> { it.dateMillis }.thenByDescending { it.uid })
        val unreadByAccount = accounts.associate { account ->
            account.id to (indexes[account.id]?.messages?.count { it.folderRole == MailFolderRole.INBOX && it.unread } ?: 0)
        }
        val archiveAccountIds = accounts
            .filter { account -> indexes[account.id]?.folders?.any { it.role == MailFolderRole.ARCHIVE } == true }
            .map { it.id }
            .toSet()
        val trashAccountIds = accounts
            .filter { account -> indexes[account.id]?.folders?.any { it.role == MailFolderRole.TRASH } == true }
            .map { it.id }
            .toSet()
        _state.update {
            it.copy(
                accounts = accounts,
                messages = messages,
                drafts = drafts,
                unreadByAccount = unreadByAccount,
                archiveAccountIds = archiveAccountIds,
                trashAccountIds = trashAccountIds,
                accountErrors = accountErrors,
                lastSyncByAccount = lastSyncByAccount,
            )
        }
    }

    private fun persistAccounts() {
        try {
            store.saveAccounts(accounts)
        } catch (error: Exception) {
            host.logs().warn("mail: cannot save accounts", error)
        }
    }

    private fun persistServers() {
        try {
            store.saveServers(servers.values.toList())
        } catch (error: Exception) {
            host.logs().warn("mail: cannot save server settings", error)
        }
    }

    private fun persistDrafts() {
        try {
            store.saveDrafts(drafts)
        } catch (error: Exception) {
            host.logs().warn("mail: cannot save drafts", error)
        }
    }

    private fun matches(message: MailMessageSummary, term: String): Boolean {
        if (term.isEmpty()) return true
        return message.subject.contains(term, ignoreCase = true) ||
            message.fromName.contains(term, ignoreCase = true) ||
            message.fromAddress.contains(term, ignoreCase = true) ||
            message.preview.contains(term, ignoreCase = true)
    }

    private fun secretKey(accountId: String): String = "mail-$accountId"

    private fun accountId(email: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(email.lowercase().toByteArray(Charsets.UTF_8))
        return digest.take(8).joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
    }

    private fun paletteColor(accountId: String): Int = PALETTE[accountId.hashCode().mod(PALETTE.size)]

    private fun markSyncFailure(message: String) {
        runCatching {
            _state.update {
                it.copy(syncing = false, loading = false, syncError = message, lastSyncAt = System.currentTimeMillis())
            }
        }
    }

    private fun connectionFailure(message: String): MailConnectionTest =
        MailConnectionTest(imapOk = false, smtpOk = false, imapError = message, smtpError = message)

    private fun localizedFailure(error: Throwable): MailLocalizedException =
        MailLocalizedException(safeDescribe(error), error)

    private suspend fun persistSyncSetting(accountId: String, now: Long) {
        try {
            val settings = host.settings()
            settings.put("$SETTING_LAST_SYNC_PREFIX$accountId", now.toString())
            settings.put(SETTING_LAST_SYNC_AT, now.toString())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            report(error)
        }
    }

    private suspend fun removeSyncSetting(accountId: String) {
        try {
            host.settings().remove("$SETTING_LAST_SYNC_PREFIX$accountId")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            report(error)
        }
    }

    private fun safeDescribe(error: Throwable): String =
        runCatching { texts.describeAny(error) }.getOrElse { fallbackErrorText }

    private fun report(error: Throwable) {
        runCatching { host.logs().error("mail: unexpected failure", error) }
    }

    private suspend fun <T> boundary(fallback: (Throwable) -> T, block: suspend () -> T): T = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (localized: MailLocalizedException) {
        throw localized
    } catch (error: Throwable) {
        report(error)
        fallback(error)
    }

    private fun Throwable.isRetryable(): Boolean =
        this is MailNetworkException || this is MailTimeoutException || this is MailConnectionLostException

    private data class ResolvedServers(
        val imapHost: String,
        val imapPort: Int,
        val smtpHost: String,
        val smtpPort: Int,
    )

    private data class LoadedState(
        val accounts: List<MailAccount>,
        val servers: Map<String, MailServerSettings>,
        val indexes: Map<String, MailAccountIndex>,
        val drafts: List<MailDraft>,
        val lastSyncAt: Long? = null,
        val lastSyncByAccount: Map<String, Long> = emptyMap(),
    )

    private data class FolderSync(
        val folder: MailFolderIndex,
        val messages: List<MailMessageSummary>,
        val newUnread: List<MailMessageSummary>,
    )

    private companion object {
        const val SETTING_LAST_SYNC_AT = "sync.lastAt"
        const val SETTING_LAST_SYNC_PREFIX = "sync.lastAt."
        const val FALLBACK_ERROR_ZH = "操作没有完成，请稍后重试。"
        const val FALLBACK_ERROR_EN = "The action did not finish. Try again later."
        val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
        val PALETTE = listOf(
            0xFF5B8DEF.toInt(),
            0xFF34A853.toInt(),
            0xFFF29900.toInt(),
            0xFFEA4335.toInt(),
            0xFF9334E6.toInt(),
            0xFF00ACC1.toInt(),
            0xFFD01884.toInt(),
            0xFF7CB342.toInt(),
        )
    }
}
