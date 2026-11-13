package io.github.whywood.deepdeep.plugin.mail.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.whywood.deepdeep.design.DdEmptyState
import io.github.whywood.deepdeep.design.DdIconButton
import io.github.whywood.deepdeep.design.DdIcons
import io.github.whywood.deepdeep.design.DdLoadingBlock
import io.github.whywood.deepdeep.design.DdPrimaryButton
import io.github.whywood.deepdeep.design.DdSearchField
import io.github.whywood.deepdeep.design.DdSegmented
import io.github.whywood.deepdeep.design.DdTextAction
import io.github.whywood.deepdeep.design.deepDeepColors
import io.github.whywood.deepdeep.plugin.api.PluginHost
import io.github.whywood.deepdeep.plugin.mail.MailAccount
import io.github.whywood.deepdeep.plugin.mail.MailFolderRole
import io.github.whywood.deepdeep.plugin.mail.MailRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val UndoWindowMillis = 5_000L
private const val HiddenFallbackMillis = 10_000L
private const val SearchDebounceMillis = 320L

@Composable
internal fun MailPage(host: PluginHost, repository: MailRepository) {
    val state by repository.state.collectAsState()
    val colors = deepDeepColors()
    val scope = rememberCoroutineScope()
    val chinese = host.chinese
    val now = System.currentTimeMillis()

    var overlay by remember { mutableStateOf<MailOverlay?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var selectedAccountId by rememberSaveable { mutableStateOf<String?>(null) }
    var onlyUnread by rememberSaveable { mutableStateOf(false) }
    var searchState by remember { mutableStateOf<MailSearchState>(MailSearchState.Idle) }
    var searchAttempt by remember { mutableIntStateOf(0) }
    var pendingRemoval by remember { mutableStateOf<MailPendingRemoval?>(null) }
    var failedRemoval by remember { mutableStateOf<MailFailedRemoval?>(null) }
    var confirmPermanentDelete by remember { mutableStateOf<MailPendingRemoval?>(null) }
    var hiddenIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var refreshError by remember { mutableStateOf<String?>(null) }
    var pullRefreshing by remember { mutableStateOf(false) }

    val accounts = state.accounts
    val activeAccountId = selectedAccountId?.takeIf { id -> accounts.any { it.id == id } }
    val hasSearch = query.isNotBlank()
    val initialLoading = state.loading ||
        (state.syncing && state.messages.isEmpty() && state.lastSyncAt == null)

    fun refresh() {
        scope.launch {
            runCatching { repository.refresh() }.onFailure { error ->
                refreshError = host.readableError(
                    error,
                    fallbackZh = "同步失败，请重试",
                    fallbackEn = "Sync failed. Try again.",
                )
            }
        }
    }

    fun pullRefresh() {
        if (pullRefreshing) return
        pullRefreshing = true
        scope.launch {
            runCatching { repository.refresh() }.onFailure { error ->
                refreshError = host.readableError(
                    error,
                    fallbackZh = "同步失败，请重试",
                    fallbackEn = "Sync failed. Try again.",
                )
            }
            pullRefreshing = false
        }
    }

    fun commitRemoval(removal: MailPendingRemoval) {
        scope.launch {
            runCatching { performRemoval(repository, removal) }
                .onSuccess {
                    delay(HiddenFallbackMillis)
                    hiddenIds = hiddenIds - removal.messages.mapTo(HashSet()) { it.id }
                }
                .onFailure { error ->
                    hiddenIds = hiddenIds - removal.messages.mapTo(HashSet()) { it.id }
                    failedRemoval = MailFailedRemoval(
                        removal = removal,
                        message = host.readableError(
                            error,
                            fallbackZh = "操作失败，请重试",
                            fallbackEn = "The action failed. Try again.",
                        ),
                    )
                }
        }
    }

    fun beginRemoval(row: MailThreadRow, kind: MailRemovalKind) {
        pendingRemoval?.let { previous ->
            pendingRemoval = null
            commitRemoval(previous)
        }
        if (kind == MailRemovalKind.TRASH && !state.trashAccountIds.contains(row.accountId)) {
            confirmPermanentDelete = MailPendingRemoval(row.messages, MailRemovalKind.DELETE)
            return
        }
        val removal = MailPendingRemoval(row.messages, kind)
        hiddenIds = hiddenIds + removal.messages.map { it.id }
        pendingRemoval = removal
    }

    fun undoRemoval() {
        val removal = pendingRemoval ?: return
        pendingRemoval = null
        hiddenIds = hiddenIds - removal.messages.mapTo(HashSet()) { it.id }
    }

    fun retryFailedRemoval() {
        val failed = failedRemoval ?: return
        failedRemoval = null
        hiddenIds = hiddenIds + failed.removal.messages.map { it.id }
        commitRemoval(failed.removal)
    }

    fun openCompose(request: MailComposeRequest? = null, returnTo: MailOverlay? = null) {
        val accountId = request?.accountId?.takeIf { it.isNotBlank() }
            ?: activeAccountId
            ?: accounts.firstOrNull()?.id
        if (accountId.isNullOrBlank()) {
            overlay = MailOverlay.Accounts(startAdding = true)
            return
        }
        overlay = MailOverlay.Compose(request ?: MailComposeRequest(accountId = accountId), returnTo)
    }

    LaunchedEffect(Unit) {
        runCatching { repository.refresh() }
    }

    LaunchedEffect(pendingRemoval) {
        val removal = pendingRemoval ?: return@LaunchedEffect
        delay(UndoWindowMillis)
        if (pendingRemoval === removal) {
            pendingRemoval = null
            commitRemoval(removal)
        }
    }

    val pendingReference = rememberUpdatedState(pendingRemoval)
    DisposableEffect(Unit) {
        onDispose {
            pendingReference.value?.let { removal ->
                host.scope.launch { runCatching { performRemoval(repository, removal) } }
            }
        }
    }

    LaunchedEffect(state.messages, hiddenIds) {
        if (hiddenIds.isEmpty()) return@LaunchedEffect
        val present = state.messages.mapTo(HashSet()) { it.id }
        val remaining = hiddenIds.intersect(present)
        if (remaining.size != hiddenIds.size) hiddenIds = remaining
    }

    LaunchedEffect(query, activeAccountId, searchAttempt) {
        val text = query.trim()
        if (text.isEmpty()) {
            searchState = MailSearchState.Idle
            return@LaunchedEffect
        }
        delay(SearchDebounceMillis)
        searchState = MailSearchState.Loading
        searchState = runCatching { repository.search(text, activeAccountId) }
            .fold(
                onSuccess = { MailSearchState.Loaded(mailThreadRows(it, onlyUnread = false)) },
                onFailure = { error ->
                    MailSearchState.Failed(
                        host.readableError(
                            error,
                            fallbackZh = "搜索失败，请重试",
                            fallbackEn = "Search failed. Try again.",
                        ),
                    )
                },
            )
    }

    val visibleMessages = remember(state.messages, activeAccountId, hiddenIds) {
        val hidden = hiddenIds
        state.messages.filter { message ->
            message.folderRole == MailFolderRole.INBOX &&
                (activeAccountId == null || message.accountId == activeAccountId) &&
                message.id !in hidden
        }
    }
    val inboxRows = remember(visibleMessages, onlyUnread) { mailThreadRows(visibleMessages, onlyUnread) }
    val searchRows = remember(searchState, hiddenIds, onlyUnread) {
        val loaded = searchState as? MailSearchState.Loaded
        if (loaded == null) {
            emptyList()
        } else {
            loaded.rows
                .filter { row -> row.latest.id !in hiddenIds }
                .filter { row -> !onlyUnread || row.unread }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            MailTopActions(
                host = host,
                query = query,
                onQueryChange = { query = it },
                activeAccount = accounts.firstOrNull { it.id == activeAccountId } ?: accounts.firstOrNull(),
                onCompose = { openCompose() },
                onAccounts = { overlay = MailOverlay.Accounts(startAdding = false) },
                onAddAccount = { overlay = MailOverlay.Accounts(startAdding = true) },
            )
            if (accounts.size > 1) {
                MailAccountSwitcher(
                    accounts = accounts,
                    selectedAccountId = activeAccountId,
                    allLabel = host.t("全部", "All"),
                    onSelect = { selectedAccountId = it },
                )
            }
            if (accounts.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                DdSegmented(
                    options = listOf(host.t("全部", "All"), host.t("未读", "Unread")),
                    selectedIndex = if (onlyUnread) 1 else 0,
                    onSelect = { onlyUnread = it == 1 },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                )
            }
            Spacer(Modifier.height(6.dp))
            val syncError = refreshError ?: state.syncError
            if (!hasSearch) {
                if (syncError != null && !initialLoading) {
                    Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 2.dp)) {
                        MailNoticeBar(
                            text = if (state.messages.isNotEmpty()) {
                                host.tf(
                                    "同步失败：%s · 显示本地缓存",
                                    "Sync failed: %s · showing cached mail",
                                    displayPreview(syncError, limit = 80),
                                )
                            } else {
                                host.tf(
                                    "同步失败：%s",
                                    "Sync failed: %s",
                                    displayPreview(syncError, limit = 120),
                                )
                            },
                            tone = colors.danger,
                            actionLabel = host.t("重试", "Retry"),
                            actionDescription = host.t("重新同步", "Sync again"),
                            onAction = {
                                refreshError = null
                                pullRefresh()
                            },
                        )
                    }
                } else if (state.syncing && !initialLoading && !pullRefreshing) {
                    Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 2.dp)) {
                        MailNoticeBar(
                            text = host.t("正在同步邮件", "Syncing mail"),
                            tone = colors.inkTertiary,
                            loading = true,
                        )
                    }
                }
            }
            Box(Modifier.fillMaxWidth().weight(1f)) {
                when {
                    initialLoading && state.messages.isEmpty() -> DdLoadingBlock(
                        modifier = Modifier.fillMaxSize(),
                        text = host.t("正在加载邮件", "Loading mail"),
                    )

                    accounts.isEmpty() -> MailNoAccounts(host) {
                        overlay = MailOverlay.Accounts(startAdding = true)
                    }

                    hasSearch -> MailSearchContent(
                        host = host,
                        searchState = searchState,
                        rows = searchRows,
                        accounts = accounts,
                        now = now,
                        chinese = chinese,
                        onlyUnread = onlyUnread,
                        onOpen = { row ->
                            overlay = MailOverlay.Thread(
                                MailThreadRequest(row.accountId, row.threadId, row.latest.id),
                            )
                        },
                        onRetry = { searchAttempt++ },
                        onClear = { query = "" },
                    )

                    inboxRows.isEmpty() -> MailEmptyInbox(
                        host = host,
                        onlyUnread = onlyUnread,
                        failed = syncError != null,
                    )

                    else -> MailThreadList(
                        host = host,
                        rows = inboxRows,
                        now = now,
                        accounts = accounts,
                        archiveAccountIds = state.archiveAccountIds,
                        refreshing = pullRefreshing,
                        swipeEnabled = true,
                        onOpen = { row ->
                            overlay = MailOverlay.Thread(
                                MailThreadRequest(row.accountId, row.threadId, row.latest.id),
                            )
                        },
                        onArchive = { row -> beginRemoval(row, MailRemovalKind.ARCHIVE) },
                        onDelete = { row -> beginRemoval(row, MailRemovalKind.TRASH) },
                        onRefresh = { pullRefresh() },
                    )
                }
                val pending = pendingRemoval
                val failed = failedRemoval
                when {
                    pending != null -> Box(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                    ) {
                        MailUndoBar(
                            text = removalLabel(host, pending),
                            actionLabel = host.t("撤销", "Undo"),
                            onAction = { undoRemoval() },
                        )
                    }

                    failed != null -> Box(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                    ) {
                        MailNoticeBar(
                            text = failed.message,
                            tone = colors.danger,
                            actionLabel = host.t("重试", "Retry"),
                            actionDescription = host.t("重试", "Retry"),
                            onAction = { retryFailedRemoval() },
                        )
                    }
                }
            }
        }
        overlay?.let { current ->
            when (current) {
                is MailOverlay.Thread -> MailThreadSheet(
                    host = host,
                    repository = repository,
                    request = current.request,
                    onDismiss = { overlay = null },
                    onCompose = { request ->
                        overlay = MailOverlay.Compose(request, returnTo = current)
                    },
                )

                is MailOverlay.Compose -> MailComposeSheet(
                    host = host,
                    repository = repository,
                    request = current.request,
                    onClose = { overlay = current.returnTo },
                )

                is MailOverlay.Accounts -> MailAccountsSheet(
                    host = host,
                    repository = repository,
                    startAdding = current.startAdding,
                    onDismiss = { overlay = null },
                    onCompose = { request ->
                        overlay = MailOverlay.Compose(
                            request = request,
                            returnTo = MailOverlay.Accounts(startAdding = false),
                        )
                    },
                )
            }
        }
    }

    confirmPermanentDelete?.let { removal ->
        val count = removal.messages.size
        MailConfirmDialog(
            host = host,
            title = host.t("永久删除？", "Delete permanently?"),
            message = if (count > 1) {
                host.tf(
                    "这段会话有 %d 封邮件，删除后无法恢复。",
                    "This conversation has %d messages. Deleting can't be undone.",
                    count,
                )
            } else {
                host.t(
                    "这封邮件无法移入废纸篓，删除后不能恢复。",
                    "This mailbox has no trash, so the message can't be recovered after deleting.",
                )
            },
            confirmLabel = host.t("删除", "Delete"),
            destructive = true,
            onConfirm = {
                confirmPermanentDelete = null
                hiddenIds = hiddenIds + removal.messages.map { it.id }
                commitRemoval(removal)
            },
            onDismiss = { confirmPermanentDelete = null },
        )
    }
}

@Composable
private fun MailTopActions(
    host: PluginHost,
    query: String,
    onQueryChange: (String) -> Unit,
    activeAccount: MailAccount?,
    onCompose: () -> Unit,
    onAccounts: () -> Unit,
    onAddAccount: () -> Unit,
) {
    val colors = deepDeepColors()
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DdSearchField(
            query = query,
            onQueryChange = onQueryChange,
            placeholder = host.t("搜索邮件", "Search mail"),
            modifier = Modifier.weight(1f),
            clearContentDescription = host.t("清除搜索", "Clear search"),
        )
        DdIconButton(
            icon = DdIcons.Compose,
            onClick = onCompose,
            contentDescription = host.t("写邮件", "Compose"),
            tint = colors.ink,
        )
        if (activeAccount != null) {
            MailAvatar(
                initials = mailInitial(activeAccount.displayName, activeAccount.email),
                color = Color(activeAccount.color),
                size = 36.dp,
                contentDescription = host.t("账户", "Accounts"),
                onClick = onAccounts,
            )
        } else {
            DdIconButton(
                icon = DdIcons.Plus,
                onClick = onAddAccount,
                contentDescription = host.t("添加邮箱", "Add mailbox"),
                tint = colors.ink,
            )
        }
    }
}

@Composable
private fun MailNoAccounts(host: PluginHost, onAdd: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().padding(horizontal = 40.dp),
        contentAlignment = Alignment.Center,
    ) {
        DdEmptyState(
            text = host.t(
                "连接邮箱后，邮件会显示在这里。",
                "Connect a mailbox and your mail will show up here.",
            ),
            icon = DdIcons.Message,
            action = {
                DdPrimaryButton(
                    text = host.t("添加邮箱", "Add mailbox"),
                    onClick = onAdd,
                )
            },
        )
    }
}

@Composable
private fun MailEmptyInbox(host: PluginHost, onlyUnread: Boolean, failed: Boolean) {
    Box(Modifier.fillMaxSize().padding(horizontal = 40.dp), contentAlignment = Alignment.Center) {
        DdEmptyState(
            text = when {
                failed && !onlyUnread -> host.t(
                    "暂时无法加载邮件，请检查网络后重试。",
                    "Couldn't load mail. Check your network and try again.",
                )

                onlyUnread -> host.t("没有未读邮件。", "No unread mail.")
                else -> host.t("收件箱是空的，新邮件会显示在这里。", "Your inbox is empty.")
            },
            icon = when {
                failed && !onlyUnread -> DdIcons.Info
                onlyUnread -> DdIcons.Check
                else -> DdIcons.Message
            },
        )
    }
}

@Composable
private fun MailSearchContent(
    host: PluginHost,
    searchState: MailSearchState,
    rows: List<MailThreadRow>,
    accounts: List<MailAccount>,
    now: Long,
    chinese: Boolean,
    onlyUnread: Boolean,
    onOpen: (MailThreadRow) -> Unit,
    onRetry: () -> Unit,
    onClear: () -> Unit,
) {
    val colors = deepDeepColors()
    when (searchState) {
        is MailSearchState.Idle, is MailSearchState.Loading -> DdLoadingBlock(
            modifier = Modifier.fillMaxSize(),
            text = host.t("正在搜索", "Searching"),
        )

        is MailSearchState.Failed -> Box(
            Modifier.fillMaxSize().padding(horizontal = 40.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                DdEmptyState(text = searchState.message, icon = DdIcons.Info)
                DdTextAction(
                    text = host.t("重试", "Retry"),
                    onClick = onRetry,
                    color = colors.accent,
                )
            }
        }

        is MailSearchState.Loaded -> if (rows.isEmpty()) {
            Box(
                Modifier.fillMaxSize().padding(horizontal = 40.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    DdEmptyState(
                        text = if (onlyUnread) {
                            host.t("没有找到未读邮件。", "No unread mail found.")
                        } else {
                            host.t("没有找到相关邮件。", "No matching mail found.")
                        },
                        icon = DdIcons.Search,
                    )
                    DdTextAction(
                        text = host.t("清除搜索", "Clear search"),
                        onClick = onClear,
                        color = colors.inkSecondary,
                    )
                }
            }
        } else {
            MailThreadList(
                host = host,
                rows = rows,
                now = now,
                accounts = accounts,
                archiveAccountIds = emptySet(),
                refreshing = false,
                swipeEnabled = false,
                onOpen = onOpen,
                onArchive = {},
                onDelete = {},
                onRefresh = null,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private fun removalLabel(host: PluginHost, removal: MailPendingRemoval): String {
    val empty = host.t("（无主题）", "(No subject)")
    val single = removal.messages.size == 1
    val subject = displaySubject(removal.messages.first().subject, empty)
    return when (removal.kind) {
        MailRemovalKind.ARCHIVE -> if (single) {
            host.tf("已归档「%s」", "Archived “%s”", subject)
        } else {
            host.t("已归档这段会话", "Conversation archived")
        }

        MailRemovalKind.TRASH -> if (single) {
            host.tf("已移到废纸篓「%s」", "Moved to trash “%s”", subject)
        } else {
            host.t("已移到废纸篓", "Moved to trash")
        }

        MailRemovalKind.DELETE -> if (single) {
            host.tf("已删除「%s」", "Deleted “%s”", subject)
        } else {
            host.t("已删除", "Deleted")
        }
    }
}

private suspend fun performRemoval(repository: MailRepository, removal: MailPendingRemoval) {
    removal.messages.forEach { message ->
        when (removal.kind) {
            MailRemovalKind.ARCHIVE -> repository.archive(message.accountId, message.id)
            MailRemovalKind.TRASH -> repository.trash(message.accountId, message.id)
            MailRemovalKind.DELETE -> repository.deleteForever(message.accountId, message.id)
        }
    }
}
