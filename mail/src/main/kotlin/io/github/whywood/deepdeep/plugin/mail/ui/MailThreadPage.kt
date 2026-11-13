package io.github.whywood.deepdeep.plugin.mail.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.whywood.deepdeep.design.DdAvatar
import io.github.whywood.deepdeep.design.DdFileRow
import io.github.whywood.deepdeep.design.DdGroupCard
import io.github.whywood.deepdeep.design.DdHairline
import io.github.whywood.deepdeep.design.DdHtmlBlocks
import io.github.whywood.deepdeep.design.DdIcon
import io.github.whywood.deepdeep.design.DdIconButton
import io.github.whywood.deepdeep.design.DdIcons
import io.github.whywood.deepdeep.design.DdImageViewer
import io.github.whywood.deepdeep.design.DdLoadingBlock
import io.github.whywood.deepdeep.design.DdMenu
import io.github.whywood.deepdeep.design.DdMenuItem
import io.github.whywood.deepdeep.design.DdPressable
import io.github.whywood.deepdeep.design.DdRowDivider
import io.github.whywood.deepdeep.design.DdShapes
import io.github.whywood.deepdeep.design.DdSpacing
import io.github.whywood.deepdeep.design.DdSpinner
import io.github.whywood.deepdeep.design.DdTextAction
import io.github.whywood.deepdeep.design.DdType
import io.github.whywood.deepdeep.design.deepDeepColors
import io.github.whywood.deepdeep.plugin.api.PluginHost
import io.github.whywood.deepdeep.plugin.api.shareRequest
import io.github.whywood.deepdeep.plugin.mail.MailAttachment
import io.github.whywood.deepdeep.plugin.mail.MailMessageDetail
import io.github.whywood.deepdeep.plugin.mail.MailMessageSummary
import io.github.whywood.deepdeep.plugin.mail.MailRepository
import kotlinx.coroutines.launch

internal sealed interface MailDetailState {
    data object Loading : MailDetailState

    data class Loaded(val detail: MailMessageDetail) : MailDetailState

    data class Failed(val message: String) : MailDetailState
}

internal enum class MailComposeKind { REPLY, REPLY_ALL, FORWARD }

private sealed interface MailThreadViewer {
    data class ViewerBitmap(val value: ImageBitmap) : MailThreadViewer

    data class ViewerPath(val path: String) : MailThreadViewer
}

@Composable
internal fun MailThreadPage(
    host: PluginHost,
    repository: MailRepository,
    request: MailThreadRequest,
    onBack: () -> Unit,
    onCompose: (MailComposeRequest) -> Unit,
    onArchive: (List<MailMessageSummary>) -> Unit,
    onDelete: (List<MailMessageSummary>) -> Unit,
) {
    BackHandler(onBack = onBack)
    val state by repository.state.collectAsState()
    val colors = deepDeepColors()
    val scope = rememberCoroutineScope()
    val chinese = host.chinese
    val account = remember(state.accounts, request.accountId) {
        state.accounts.firstOrNull { it.id == request.accountId }
    }
    val messages = remember(state.messages, request) {
        state.messages
            .filter { it.accountId == request.accountId && it.threadId == request.threadId }
            .sortedBy { it.dateMillis }
    }
    var expanded by remember(request) { mutableStateOf<Set<String>>(emptySet()) }
    var details by remember(request) { mutableStateOf<Map<String, MailDetailState>>(emptyMap()) }
    var fallback by remember(request) { mutableStateOf<MailDetailState?>(null) }
    var attachmentBusyId by remember { mutableStateOf<String?>(null) }
    var attachmentError by remember { mutableStateOf<String?>(null) }
    var viewer by remember { mutableStateOf<MailThreadViewer?>(null) }
    var fallbackAttempt by remember { mutableIntStateOf(0) }
    var menuExpanded by remember { mutableStateOf(false) }
    var allExpanded by remember(request) { mutableStateOf(false) }
    var markedRead by remember(request) { mutableStateOf(false) }
    var defaultExpandedApplied by remember(request) { mutableStateOf(false) }

    fun loadDetail(messageId: String, force: Boolean = false) {
        if (!force && details.containsKey(messageId)) return
        details = details + (messageId to MailDetailState.Loading)
        scope.launch {
            val result = runCatching { repository.message(request.accountId, messageId) }
            details = details + (
                messageId to result.fold(
                    onSuccess = { detail ->
                        if (detail != null) {
                            MailDetailState.Loaded(detail)
                        } else {
                            MailDetailState.Failed(
                                host.t(
                                    "打不开这封邮件，可能已被移动。",
                                    "This message can't be opened. It may have been moved.",
                                ),
                            )
                        }
                    },
                    onFailure = {
                        MailDetailState.Failed(
                            host.readableError(
                                it,
                                fallbackZh = "加载邮件失败，请重试",
                                fallbackEn = "Couldn't load the message. Try again.",
                            ),
                        )
                    },
                )
                )
        }
    }

    fun openAttachment(messageId: String, attachment: MailAttachment) {
        if (attachmentBusyId != null) return
        attachmentError = null
        attachmentBusyId = attachment.id
        scope.launch {
            val result = runCatching { repository.loadAttachment(request.accountId, messageId, attachment.id) }
            attachmentBusyId = null
            val path = result.getOrNull()
            if (!path.isNullOrBlank()) {
                if (isImageAttachment(attachment.mimeType, attachment.name)) {
                    viewer = MailThreadViewer.ViewerPath(path)
                } else {
                    host.ui().share(shareRequest(filePath = path, mimeType = attachment.mimeType))
                }
            } else {
                attachmentError = result.exceptionOrNull()?.let { error ->
                    host.readableError(
                        error,
                        fallbackZh = "附件下载失败，请重试。",
                        fallbackEn = "Couldn't download the attachment. Try again.",
                    )
                } ?: host.t("附件下载失败，请重试。", "Couldn't download the attachment. Try again.")
            }
        }
    }

    fun latestSummary(): MailMessageSummary? = messages.lastOrNull()
        ?: (fallback as? MailDetailState.Loaded)?.detail?.summary

    fun latestDetail(): MailMessageDetail? {
        val latestId = messages.lastOrNull()?.id
        if (latestId != null) {
            return (details[latestId] as? MailDetailState.Loaded)?.detail
        }
        return (fallback as? MailDetailState.Loaded)?.detail
    }

    fun composeRequest(kind: MailComposeKind) {
        val detail = latestDetail() ?: return
        val accountEmail = account?.email.orEmpty()
        val to = when (kind) {
            MailComposeKind.REPLY -> listOf(detail.summary.fromAddress)
            MailComposeKind.REPLY_ALL -> {
                (listOf(detail.summary.fromAddress) + detail.summary.to)
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && !it.equals(accountEmail, ignoreCase = true) }
                    .distinct()
            }

            MailComposeKind.FORWARD -> emptyList()
        }
        val cc = if (kind == MailComposeKind.REPLY_ALL) {
            detail.cc
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.equals(accountEmail, ignoreCase = true) && it !in to }
                .distinct()
        } else {
            emptyList()
        }
        val subject = when (kind) {
            MailComposeKind.FORWARD -> withSubjectPrefix("Fwd: ", detail.summary.subject)
            else -> withSubjectPrefix("Re: ", detail.summary.subject)
        }
        onCompose(
            MailComposeRequest(
                accountId = request.accountId,
                to = to,
                cc = cc,
                subject = subject,
                body = quotedBody(host, detail, chinese),
                inReplyToId = detail.summary.id,
            ),
        )
    }

    LaunchedEffect(messages, request) {
        if (!defaultExpandedApplied && messages.isNotEmpty()) {
            defaultExpandedApplied = true
            expanded = setOf(messages.last().id)
        }
    }

    LaunchedEffect(expanded) {
        expanded.forEach { messageId -> loadDetail(messageId) }
    }

    LaunchedEffect(messages, request) {
        if (markedRead || messages.isEmpty()) return@LaunchedEffect
        markedRead = true
        messages.filter { it.unread }.forEach { message ->
            runCatching { repository.mark(message.accountId, message.id, unread = false) }
        }
    }

    LaunchedEffect(messages, request, fallbackAttempt) {
        if (messages.isNotEmpty()) {
            fallback = null
            return@LaunchedEffect
        }
        if (fallbackAttempt == 0 && fallback != null) return@LaunchedEffect
        fallback = MailDetailState.Loading
        val detail = runCatching { repository.message(request.accountId, request.messageId) }
        detail.getOrNull()?.let { loaded ->
            if (loaded.summary.unread && !markedRead) {
                markedRead = true
                runCatching { repository.mark(request.accountId, loaded.summary.id, unread = false) }
            }
        }
        fallback = detail.fold(
            onSuccess = { loaded ->
                if (loaded != null) {
                    MailDetailState.Loaded(loaded)
                } else {
                    MailDetailState.Failed(
                        host.t(
                            "打不开这封邮件，可能已被移动。",
                            "This message can't be opened. It may have been moved.",
                        ),
                    )
                }
            },
            onFailure = {
                MailDetailState.Failed(
                    host.readableError(
                        it,
                        fallbackZh = "加载邮件失败，请重试",
                        fallbackEn = "Couldn't load the message. Try again.",
                    ),
                )
            },
        )
    }

    val fallbackDetail = (fallback as? MailDetailState.Loaded)?.detail
    val actionMessages = remember(messages, fallbackDetail) {
        messages.ifEmpty { fallbackDetail?.summary?.let(::listOf).orEmpty() }
    }
    val title = remember(messages, fallbackDetail) {
        val subject = messages.lastOrNull()?.subject ?: fallbackDetail?.summary?.subject.orEmpty()
        displaySubject(subject, host.t("（无主题）", "(No subject)"))
    }
    val latestDetailState = if (messages.isEmpty()) {
        fallback
    } else {
        details[messages.last().id]
    }
    val canCompose = latestDetailState is MailDetailState.Loaded
    val canArchive = state.archiveAccountIds.contains(request.accountId)
    val allExpandable = messages.size > 1

    Box(Modifier.fillMaxSize().background(colors.paper)) {
        Column(Modifier.fillMaxSize()) {
            MailThreadTopBar(
                host = host,
                title = title,
                canArchive = canArchive,
                menuExpanded = menuExpanded,
                onMenuExpandedChange = { menuExpanded = it },
                allExpanded = allExpanded,
                expandAllEnabled = allExpandable,
                onBack = onBack,
                onArchive = {
                    if (actionMessages.isNotEmpty()) onArchive(actionMessages)
                },
                onDelete = {
                    if (actionMessages.isNotEmpty()) onDelete(actionMessages)
                },
                onMarkUnread = {
                    latestSummary()?.let { summary ->
                        scope.launch {
                            runCatching { repository.mark(request.accountId, summary.id, unread = true) }
                            onBack()
                        }
                    }
                },
                onToggleExpandAll = {
                    allExpanded = !allExpanded
                    expanded = if (allExpanded) messages.mapTo(LinkedHashSet()) { it.id } else emptySet()
                },
            )
            if (messages.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                ) {
                    FallbackThreadContent(
                        host = host,
                        repository = repository,
                        state = fallback,
                        attachmentBusyId = attachmentBusyId,
                        onRetry = { fallbackAttempt++ },
                        onAttachment = { attachment ->
                            openAttachment(fallbackDetail?.summary?.id ?: request.messageId, attachment)
                        },
                        onViewer = { viewer = it },
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(messages, key = { message -> message.id }) { message ->
                        key(message.id) {
                            MailThreadMessageCard(
                                host = host,
                                repository = repository,
                                message = message,
                                chinese = chinese,
                                expanded = expanded.contains(message.id),
                                detailState = details[message.id],
                                attachmentBusyId = attachmentBusyId,
                                onToggle = {
                                    expanded = if (expanded.contains(message.id)) {
                                        expanded - message.id
                                    } else {
                                        expanded + message.id
                                    }
                                },
                                onRetry = { loadDetail(message.id, force = true) },
                                onAttachment = { attachment -> openAttachment(message.id, attachment) },
                                onViewer = { viewer = it },
                            )
                        }
                    }
                }
            }
            attachmentError?.let { error ->
                Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
                    MailNoticeBar(text = error, tone = colors.danger)
                }
            }
            DdHairline()
            MailThreadComposerBar(
                host = host,
                enabled = canCompose,
                onReply = { composeRequest(MailComposeKind.REPLY) },
                onReplyAll = { composeRequest(MailComposeKind.REPLY_ALL) },
                onForward = { composeRequest(MailComposeKind.FORWARD) },
            )
        }
    }

    when (val current = viewer) {
        is MailThreadViewer.ViewerBitmap -> DdImageViewer(
            bitmap = current.value,
            onDismiss = { viewer = null },
            contentDescription = host.t("邮件图片", "Message image"),
        )

        is MailThreadViewer.ViewerPath -> DdImageViewer(
            path = current.path,
            onDismiss = { viewer = null },
            contentDescription = host.t("图片附件", "Image attachment"),
        )

        null -> Unit
    }
}

@Composable
private fun MailThreadTopBar(
    host: PluginHost,
    title: String,
    canArchive: Boolean,
    menuExpanded: Boolean,
    onMenuExpandedChange: (Boolean) -> Unit,
    allExpanded: Boolean,
    expandAllEnabled: Boolean,
    onBack: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
    onMarkUnread: () -> Unit,
    onToggleExpandAll: () -> Unit,
) {
    val colors = deepDeepColors()
    Row(
        modifier = Modifier.fillMaxWidth().height(56.dp).padding(start = 6.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DdIconButton(
            icon = DdIcons.ArrowLeft,
            onClick = onBack,
            contentDescription = host.t("返回", "Back"),
            tint = colors.ink,
            iconSize = 21.dp,
        )
        Text(
            text = title,
            modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
            style = DdType.heading,
            color = colors.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (canArchive) {
            DdIconButton(
                icon = DdIcons.Archive,
                onClick = onArchive,
                contentDescription = host.t("归档", "Archive"),
                tint = colors.inkSecondary,
                iconSize = 20.dp,
            )
        }
        DdIconButton(
            icon = DdIcons.Trash,
            onClick = onDelete,
            contentDescription = host.t("删除", "Delete"),
            tint = colors.danger,
            iconSize = 20.dp,
        )
        Box {
            DdIconButton(
                icon = DdIcons.More,
                onClick = { onMenuExpandedChange(true) },
                contentDescription = host.t("更多", "More"),
                tint = colors.inkSecondary,
                iconSize = 20.dp,
            )
            DdMenu(
                expanded = menuExpanded,
                onDismissRequest = { onMenuExpandedChange(false) },
                menuWidth = 180.dp,
            ) {
                DdMenuItem(
                    text = host.t("标记未读", "Mark unread"),
                    icon = DdIcons.Message,
                    onClick = {
                        onMenuExpandedChange(false)
                        onMarkUnread()
                    },
                )
                if (expandAllEnabled) {
                    DdMenuItem(
                        text = if (allExpanded) {
                            host.t("全部收起", "Collapse all")
                        } else {
                            host.t("全部展开", "Expand all")
                        },
                        icon = DdIcons.ChevronDown,
                        onClick = {
                            onMenuExpandedChange(false)
                            onToggleExpandAll()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun MailThreadComposerBar(
    host: PluginHost,
    enabled: Boolean,
    onReply: () -> Unit,
    onReplyAll: () -> Unit,
    onForward: () -> Unit,
) {
    val colors = deepDeepColors()
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = DdSpacing.Xs, vertical = DdSpacing.Xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            DdTextAction(
                text = host.t("回复", "Reply"),
                onClick = onReply,
                enabled = enabled,
                color = colors.accent,
            )
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            DdTextAction(
                text = host.t("全部回复", "Reply all"),
                onClick = onReplyAll,
                enabled = enabled,
                color = colors.accent,
            )
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            DdTextAction(
                text = host.t("转发", "Forward"),
                onClick = onForward,
                enabled = enabled,
                color = colors.accent,
            )
        }
    }
}

@Composable
private fun FallbackThreadContent(
    host: PluginHost,
    repository: MailRepository,
    state: MailDetailState?,
    attachmentBusyId: String?,
    onRetry: () -> Unit,
    onAttachment: (MailAttachment) -> Unit,
    onViewer: (MailThreadViewer) -> Unit,
) {
    val colors = deepDeepColors()
    when (state) {
        null, is MailDetailState.Loading -> Box(
            Modifier.fillMaxWidth().padding(vertical = 40.dp),
            contentAlignment = Alignment.Center,
        ) {
            DdLoadingBlock(text = host.t("正在加载邮件", "Loading mail"))
        }

        is MailDetailState.Failed -> Column(
            Modifier.fillMaxWidth().padding(vertical = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(text = state.message, style = DdType.bodySmall, color = colors.danger)
            DdTextAction(text = host.t("重试", "Retry"), onClick = onRetry, color = colors.accent)
        }

        is MailDetailState.Loaded -> {
            MailThreadMessageCard(
                host = host,
                repository = repository,
                message = state.detail.summary,
                chinese = host.chinese,
                expanded = true,
                detailState = state,
                attachmentBusyId = attachmentBusyId,
                onToggle = {},
                onRetry = onRetry,
                onAttachment = onAttachment,
                onViewer = onViewer,
            )
        }
    }
}

@Composable
private fun MailThreadMessageCard(
    host: PluginHost,
    repository: MailRepository,
    message: MailMessageSummary,
    chinese: Boolean,
    expanded: Boolean,
    detailState: MailDetailState?,
    attachmentBusyId: String?,
    onToggle: () -> Unit,
    onRetry: () -> Unit,
    onAttachment: (MailAttachment) -> Unit,
    onViewer: (MailThreadViewer) -> Unit,
) {
    val colors = deepDeepColors()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .clip(DdShapes.Card)
            .background(colors.surface),
    ) {
        DdPressable(
            onClick = onToggle,
            modifier = Modifier.fillMaxWidth(),
            shape = RectangleShape,
            contentDescription = null,
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = DdSpacing.Lg, vertical = DdSpacing.Md)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    SenderAvatar(
                        host = host,
                        address = message.fromAddress,
                        name = message.fromName,
                        size = 34.dp,
                    )
                    Spacer(Modifier.width(DdSpacing.Md))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (message.unread) {
                                MailUnreadDot(size = 6.dp)
                                Spacer(Modifier.width(6.dp))
                            }
                            Text(
                                text = message.fromName.ifBlank { message.fromAddress },
                                modifier = Modifier.weight(1f),
                                style = DdType.label.copy(
                                    fontWeight = if (message.unread) FontWeight.SemiBold else FontWeight.Medium,
                                ),
                                color = colors.ink,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (message.fromName.isNotBlank()) {
                            Text(
                                text = message.fromAddress,
                                style = DdType.chip,
                                color = colors.inkTertiary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Spacer(Modifier.width(DdSpacing.Sm))
                    Text(
                        text = mailFullTime(message.dateMillis, chinese),
                        style = DdType.chip,
                        color = colors.inkTertiary,
                        maxLines = 1,
                    )
                    Spacer(Modifier.width(DdSpacing.Xs))
                    DdIcon(
                        icon = DdIcons.ChevronDown,
                        modifier = Modifier.rotate(if (expanded) 180f else 0f),
                        tint = colors.inkTertiary,
                        size = 16.dp,
                    )
                }
                if (!expanded) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = displayPreview(message.preview, limit = 120).ifEmpty {
                            host.t("（正文为空）", "(No body text)")
                        },
                        style = DdType.bodySmall,
                        color = colors.inkTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (expanded) {
            DdHairline()
            Column(Modifier.fillMaxWidth().padding(horizontal = DdSpacing.Lg, vertical = DdSpacing.Md)) {
                when (detailState) {
                    null, is MailDetailState.Loading -> DdLoadingBlock(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        text = host.t("正在加载邮件", "Loading mail"),
                    )

                    is MailDetailState.Failed -> Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                        Text(text = detailState.message, style = DdType.bodySmall, color = colors.danger)
                        DdTextAction(text = host.t("重试", "Retry"), onClick = onRetry, color = colors.accent)
                    }

                    is MailDetailState.Loaded -> MailMessageContent(
                        host = host,
                        repository = repository,
                        detail = detailState.detail,
                        attachmentBusyId = attachmentBusyId,
                        onAttachment = onAttachment,
                        onViewer = onViewer,
                    )
                }
            }
        }
    }
}

@Composable
private fun MailMessageContent(
    host: PluginHost,
    repository: MailRepository,
    detail: MailMessageDetail,
    attachmentBusyId: String?,
    onAttachment: (MailAttachment) -> Unit,
    onViewer: (MailThreadViewer) -> Unit,
) {
    val colors = deepDeepColors()
    val scope = rememberCoroutineScope()
    val html = remember(detail) { mailBodyHtml(detail) }
    val loader = remember(detail.summary.accountId, detail.summary.id) {
        MailInlineImageLoader(
            host = host,
            repository = repository,
            accountId = detail.summary.accountId,
            messageId = detail.summary.id,
        )
    }
    val onLinkClick: (String) -> Unit = remember(host) {
        { url: String ->
            runCatching { host.ui().openUrl(url) }
        }
    }
    val onImageClick: (String) -> Unit = remember(loader) {
        { source: String ->
            scope.launch {
                loader.load(source)?.let { bitmap -> onViewer(MailThreadViewer.ViewerBitmap(bitmap)) }
            }
        }
    }
    val webHtml by produceState<String?>(initialValue = null, detail) {
        value = runCatching { prepareMailWebHtml(repository, detail) }.getOrNull()
    }
    val prepared = webHtml
    if (!prepared.isNullOrBlank()) {
        MailHtmlWebView(
            html = prepared,
            modifier = Modifier.fillMaxWidth(),
            onOpenUrl = onLinkClick,
        )
    } else {
        DdHtmlBlocks(
            html = html,
            modifier = Modifier.fillMaxWidth(),
            loader = loader,
            onLinkClick = onLinkClick,
            onImageClick = onImageClick,
            imageLabel = host.t("图片", "Image"),
            style = DdType.body,
            color = colors.ink,
        )
    }
    val attachments = remember(detail) { detail.attachments.filter { !it.inline } }
    if (attachments.isNotEmpty()) {
        Spacer(Modifier.height(DdSpacing.Md))
        DdGroupCard {
            attachments.forEachIndexed { index, attachment ->
                DdFileRow(
                    name = attachment.name.ifBlank { host.t("附件", "Attachment") },
                    subtitle = formatFileSize(attachment.sizeBytes).takeIf { it.isNotBlank() },
                    icon = if (isImageAttachment(attachment.mimeType, attachment.name)) {
                        DdIcons.Image
                    } else {
                        DdIcons.Paperclip
                    },
                    onClick = { onAttachment(attachment) },
                    trailing = {
                        if (attachmentBusyId == attachment.id) {
                            DdSpinner(size = 16.dp, strokeWidth = 1.5.dp, color = colors.inkTertiary)
                        } else {
                            DdIcon(icon = DdIcons.Download, tint = colors.inkTertiary, size = 16.dp)
                        }
                    },
                )
                if (index < attachments.lastIndex) DdRowDivider(leadingInset = 36.dp)
            }
        }
    }
}

private fun quotedBody(host: PluginHost, detail: MailMessageDetail, chinese: Boolean): String {
    val sender = detail.summary.fromName.ifBlank { detail.summary.fromAddress }
    val attribution = host.tf(
        "在 %s，%s 写道：",
        "On %s, %s wrote:",
        mailFullTime(detail.summary.dateMillis, chinese),
        sender,
    )
    val quoted = mailQuoteText(detail)
        .trimEnd()
        .lines()
        .joinToString("\n") { line -> if (line.isEmpty()) ">" else "> $line" }
    return "\n\n$attribution\n$quoted"
}
