package io.github.whywood.deepdeep.plugin.mail

import io.github.whywood.deepdeep.plugin.api.PluginHost
import io.github.whywood.deepdeep.plugin.api.localizedText
import io.github.whywood.deepdeep.plugin.api.pluginNotification
import io.github.whywood.deepdeep.plugin.mail.mime.MailComposer
import io.github.whywood.deepdeep.plugin.mail.mime.MailHeaders
import io.github.whywood.deepdeep.plugin.mail.mime.MailMimeParser
import io.github.whywood.deepdeep.plugin.mail.mime.OutgoingMail
import io.github.whywood.deepdeep.plugin.mail.store.MailAccountIndex
import io.github.whywood.deepdeep.plugin.mail.store.MailFolderIndex
import io.github.whywood.deepdeep.plugin.mail.store.MailServerSettings
import io.github.whywood.deepdeep.plugin.mail.store.MailStore
import io.github.whywood.deepdeep.plugin.mail.sync.FolderMapping
import io.github.whywood.deepdeep.plugin.mail.sync.MailMessageMapper
import io.github.whywood.deepdeep.plugin.mail.sync.MessageKeys
import io.github.whywood.deepdeep.plugin.mail.sync.SyncPolicy
import io.github.whywood.deepdeep.plugin.mail.transport.ImapClient
import io.github.whywood.deepdeep.plugin.mail.transport.ImapCredentials
import io.github.whywood.deepdeep.plugin.mail.transport.MailConnectionLostException
import io.github.whywood.deepdeep.plugin.mail.transport.MailLocalizedException
import io.github.whywood.deepdeep.plugin.mail.transport.MailNetworkException
import io.github.whywood.deepdeep.plugin.mail.transport.MailTexts
import io.github.whywood.deepdeep.plugin.mail.transport.MailTransport
import io.github.whywood.deepdeep.plugin.mail.transport.SecureMailTransport
import io.github.whywood.deepdeep.plugin.mail.transport.MailTimeoutException
import io.github.whywood.deepdeep.plugin.mail.transport.SmtpCredentials
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
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

    override fun accounts(): List<MailAccount> = _state.value.accounts

    override fun providers(): List<MailProviderPreset> = ProviderCatalog.presets

    override suspend fun start() {
        if (stateMutex.withLock { started }) return
        val loaded = withContext(Dispatchers.IO) {
            val loadedAccounts = store.accounts()
            LoadedState(
                accounts = loadedAccounts,
                servers = store.servers().associateBy { it.accountId },
                indexes = loadedAccounts.associate { it.id to store.index(it.id) },
                drafts = store.drafts(),
            )
        }
        stateMutex.withLock {
            if (started) return
            accounts = loaded.accounts
            servers = loaded.servers
            indexes = loaded.indexes.toMutableMap()
            drafts = loaded.drafts
            started = true
            publishLocked()
            _state.update { it.copy(loading = false) }
        }
        syncJob = host.scope.launch {
            try {
                refreshInternal(force = true)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                host.logs().warn("mail initial sync failed", error)
            }
            while (isActive) {
                delay(SyncPolicy.REFRESH_INTERVAL_MS)
                if (!stateMutex.withLock { started }) break
                try {
                    refreshInternal(force = false)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    host.logs().warn("mail periodic sync failed", error)
                }
            }
        }
    }

    override suspend fun stop() {
        syncJob?.cancel()
        syncJob = null
        stateMutex.withLock { started = false }
        _state.update { it.copy(syncing = false) }
    }

    override suspend fun refresh() {
        refreshInternal(force = false)
    }

    override suspend fun testConnection(input: MailAccountInput): MailConnectionTest {
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

    override suspend fun addAccount(input: MailAccountInput): MailConnectionTest {
        val test = testConnection(input)
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

    override suspend fun updateAccount(accountId: String, update: MailAccountUpdate) {
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

    override suspend fun removeAccount(accountId: String) {
        stateMutex.withLock {
            accounts = accounts.filterNot { it.id == accountId }
            servers = servers - accountId
            indexes.remove(accountId)
            drafts = drafts.filterNot { it.accountId == accountId }
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
    }

    override suspend fun reauthorize(accountId: String, password: String): MailConnectionTest {
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

    override suspend fun message(accountId: String, messageId: String): MailMessageDetail? {
        val summary = findMessage(accountId, messageId) ?: return null
        val account = stateMutex.withLock { accounts.firstOrNull { it.id == accountId } } ?: return null
        val raw = ensureBody(account, summary) ?: return null
        val parsed = try {
            MailMimeParser.parse(raw)
        } catch (error: Exception) {
            host.logs().warn("mail: cannot parse message", error)
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
                )
            },
        )
    }

    override suspend fun mark(accountId: String, messageId: String, unread: Boolean) {
        val summary = requireMessage(accountId, messageId)
        if (summary.unread == unread) return
        val account = requireAccount(accountId)
        val folder = folderOf(accountId, summary.folderRole) ?: throw MailLocalizedException(texts.missingMessage())
        try {
            withImap(account) { client ->
                client.select(folder, readOnly = false)
                client.storeSeen(summary.uid, seen = !unread)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw MailLocalizedException(texts.describeImap(error), error)
        }
        updateMessage(summary.copy(unread = unread))
    }

    override suspend fun archive(accountId: String, messageId: String) {
        val summary = requireMessage(accountId, messageId)
        if (summary.folderRole == MailFolderRole.ARCHIVE) return
        val target = folderOf(accountId, MailFolderRole.ARCHIVE)
            ?: throw MailLocalizedException(texts.missingArchiveFolder())
        val account = requireAccount(accountId)
        val source = folderOf(accountId, summary.folderRole) ?: throw MailLocalizedException(texts.missingMessage())
        try {
            withImap(account) { client ->
                client.select(source, readOnly = false)
                client.move(summary.uid, target)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw MailLocalizedException(texts.describeImap(error), error)
        }
        removeMessage(summary)
    }

    override suspend fun trash(accountId: String, messageId: String) {
        val summary = requireMessage(accountId, messageId)
        if (summary.folderRole == MailFolderRole.TRASH) return
        val target = folderOf(accountId, MailFolderRole.TRASH)
            ?: throw MailLocalizedException(texts.missingTrashFolder())
        val account = requireAccount(accountId)
        val source = folderOf(accountId, summary.folderRole) ?: throw MailLocalizedException(texts.missingMessage())
        try {
            withImap(account) { client ->
                client.select(source, readOnly = false)
                client.move(summary.uid, target)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw MailLocalizedException(texts.describeImap(error), error)
        }
        removeMessage(summary)
    }

    override suspend fun deleteForever(accountId: String, messageId: String) {
        val summary = requireMessage(accountId, messageId)
        val account = requireAccount(accountId)
        val source = folderOf(accountId, summary.folderRole) ?: throw MailLocalizedException(texts.missingMessage())
        try {
            withImap(account) { client ->
                client.select(source, readOnly = false)
                client.delete(summary.uid)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw MailLocalizedException(texts.describeImap(error), error)
        }
        removeMessage(summary)
    }

    override suspend fun saveDraft(draft: MailDraft): MailDraft {
        val now = System.currentTimeMillis()
        val saved = draft.copy(
            id = draft.id.ifBlank { "draft-$now-${UUID.randomUUID().toString().take(8)}" },
            createdAt = if (draft.createdAt == 0L) now else draft.createdAt,
            updatedAt = now,
        )
        stateMutex.withLock {
            drafts = drafts.filterNot { it.id == saved.id } + saved
            persistDrafts()
            publishLocked()
        }
        return saved
    }

    override suspend fun deleteDraft(draftId: String) {
        stateMutex.withLock {
            drafts = drafts.filterNot { it.id == draftId }
            persistDrafts()
            publishLocked()
        }
    }

    override suspend fun sendDraft(draftId: String): MailSendResult {
        val draft = stateMutex.withLock { drafts.firstOrNull { it.id == draftId } }
            ?: return MailSendResult(ok = false, error = texts.missingDraft())
        if (draft.to.isEmpty()) return MailSendResult(ok = false, error = texts.missingRecipients())
        val account = stateMutex.withLock { accounts.firstOrNull { it.id == draft.accountId } }
            ?: return MailSendResult(ok = false, error = texts.missingAccount())
        val settings = stateMutex.withLock { servers[account.id] }
            ?: return MailSendResult(ok = false, error = texts.missingServers())
        val password = host.secrets().get(secretKey(account.id))
            ?: return MailSendResult(ok = false, error = texts.missingSecret())
        val reply = replyContext(account, draft)
        val message = MailComposer.compose(
            OutgoingMail(
                fromAddress = account.email,
                fromName = account.displayName,
                to = draft.to,
                cc = draft.cc,
                subject = draft.subject,
                body = draft.body,
                inReplyTo = reply.first,
                references = reply.second,
            ),
        )
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
                client.send(account.email, recipients, message)
                client.quit()
                null
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                texts.describeSmtp(failure)
            } finally {
                client.close()
            }
        }
        if (error != null) return MailSendResult(ok = false, error = error)
        val sentFolder = folderOf(account.id, MailFolderRole.SENT)
        if (sentFolder != null) {
            try {
                withImap(account) { client ->
                    client.select(sentFolder, readOnly = false)
                    client.append(sentFolder, listOf("\\Seen"), message)
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                host.logs().warn("mail: sent copy was not appended", failure)
            }
        }
        deleteDraft(draft.id)
        return MailSendResult(ok = true)
    }

    override suspend fun search(query: String, accountId: String?): List<MailMessageSummary> {
        val term = query.trim()
        val messages = stateMutex.withLock { indexes.values.flatMap { it.messages } }
        return messages.asSequence()
            .filter { accountId == null || it.accountId == accountId }
            .filter { matches(it, term) }
            .sortedWith(compareByDescending<MailMessageSummary> { it.dateMillis }.thenByDescending { it.uid })
            .toList()
    }

    override suspend fun loadAttachment(accountId: String, messageId: String, attachmentId: String): String? {
        val summary = findMessage(accountId, messageId) ?: return null
        val account = stateMutex.withLock { accounts.firstOrNull { it.id == accountId } } ?: return null
        val raw = ensureBody(account, summary) ?: return null
        val parsed = try {
            MailMimeParser.parse(raw, retainAttachments = true)
        } catch (error: Exception) {
            host.logs().warn("mail: cannot decode attachment", error)
            return null
        }
        val attachment = parsed.attachments.firstOrNull { it.id == attachmentId } ?: return null
        val data = attachment.data ?: return null
        val key = MessageKeys.key(summary.folderRole, summary.uid)
        val file = store.attachmentFile(accountId, key, attachment.id, attachment.name)
        return try {
            if (!file.isFile || file.length() != data.size.toLong()) {
                file.parentFile?.mkdirs()
                file.writeBytes(data)
            }
            file.absolutePath
        } catch (error: Exception) {
            host.logs().warn("mail: cannot save attachment", error)
            null
        }
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
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    val message = texts.describeImap(error)
                    host.logs().warn("mail sync failed: ${account.email}", error)
                    lastError = message
                    stateMutex.withLock {
                        accountErrors = accountErrors + (account.id to message)
                        publishLocked()
                    }
                }
            }
        } finally {
            _state.update {
                it.copy(
                    syncing = false,
                    loading = false,
                    syncError = lastError,
                    lastSyncAt = System.currentTimeMillis(),
                )
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
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (attempt == 0 && error.isRetryable()) {
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
        val targets = messages.sortedByDescending { it.uid }.take(SyncPolicy.BODY_PREFETCH_LIMIT)
        val bodies = try {
            client.fetchBodies(targets.map { it.uid })
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            return messages
        }
        val result = messages.toMutableList()
        for ((position, message) in result.withIndex()) {
            val bytes = bodies[message.uid] ?: continue
            try {
                store.writeBody(account.id, MessageKeys.key(role, message.uid), bytes)
                result[position] = MailMessageMapper.withParsed(message, MailMimeParser.parse(bytes))
            } catch (error: Exception) {
                host.logs().warn("mail: cannot cache message body", error)
            }
        }
        return result
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
        notifyNewMail(account, newUnread.filter { it.id in keptIds && it.folderRole == MailFolderRole.INBOX })
    }

    private suspend fun notifyNewMail(account: MailAccount, newUnread: List<MailMessageSummary>) {
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
        host.notifications().notify(pluginNotification("mail-unread-${account.id}", title, body))
    }

    private suspend fun ensureBody(account: MailAccount, summary: MailMessageSummary): ByteArray? {
        val key = MessageKeys.key(summary.folderRole, summary.uid)
        store.readBody(account.id, key)?.let { return it }
        val folder = folderOf(account.id, summary.folderRole) ?: return null
        val bytes = try {
            withImap(account) { client ->
                client.select(folder, readOnly = true)
                client.fetchBodies(listOf(summary.uid))[summary.uid]
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            host.logs().warn("mail: cannot fetch message body", error)
            return null
        }
        if (bytes != null) {
            try {
                store.writeBody(account.id, key, bytes)
            } catch (error: Exception) {
                host.logs().warn("mail: cannot cache message body", error)
            }
        }
        return bytes
    }

    private suspend fun replyContext(account: MailAccount, draft: MailDraft): Pair<String?, List<String>> {
        val replyTo = draft.inReplyToId?.takeIf { it.isNotBlank() } ?: return null to emptyList()
        val summary = findMessage(account.id, replyTo) ?: return null to emptyList()
        val raw = try {
            ensureBody(account, summary)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            null
        } ?: return null to emptyList()
        return try {
            val headers = MailHeaders.parse(raw)
            val messageId = headers.messageId ?: return null to emptyList()
            messageId to (headers.references + messageId).distinct().takeLast(20)
        } catch (error: Exception) {
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
                } catch (error: CancellationException) {
                    throw error
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
            } catch (error: Exception) {
                texts.describeImap(error)
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
            } catch (error: Exception) {
                texts.describeSmtp(error)
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
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                host.logs().warn("mail sync failed", error)
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
    )

    private data class FolderSync(
        val folder: MailFolderIndex,
        val messages: List<MailMessageSummary>,
        val newUnread: List<MailMessageSummary>,
    )

    private companion object {
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
