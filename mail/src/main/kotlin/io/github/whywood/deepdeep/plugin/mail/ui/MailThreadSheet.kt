package io.github.whywood.deepdeep.plugin.mail.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.whywood.deepdeep.design.DdHairline
import io.github.whywood.deepdeep.design.DdIcon
import io.github.whywood.deepdeep.design.DdIcons
import io.github.whywood.deepdeep.design.DdListRow
import io.github.whywood.deepdeep.design.DdLoadingBlock
import io.github.whywood.deepdeep.design.DdPressable
import io.github.whywood.deepdeep.design.DdRadius
import io.github.whywood.deepdeep.design.DdSheet
import io.github.whywood.deepdeep.design.DdSpinner
import io.github.whywood.deepdeep.design.DdText
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

private sealed interface MailDetailState {
    data object Loading : MailDetailState

    data class Loaded(val detail: MailMessageDetail) : MailDetailState

    data class Failed(val message: String) : MailDetailState
}

@Composable
internal fun MailThreadSheet(
    host: PluginHost,
    repository: MailRepository,
    request: MailThreadRequest,
    onDismiss: () -> Unit,
    onCompose: (MailComposeRequest) -> Unit,
) {
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
    val expanded = remember(request) { mutableStateListOf<String>() }
    var details by remember(request) { mutableStateOf<Map<String, MailDetailState>>(emptyMap()) }
    var fallback by remember(request) { mutableStateOf<MailDetailState?>(null) }
    var attachmentBusyId by remember { mutableStateOf<String?>(null) }
    var attachmentError by remember { mutableStateOf<String?>(null) }
    var fallbackAttempt by remember { mutableIntStateOf(0) }

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
                                host.t("打不开这封邮件，可能已被移动。", "This message can't be opened. It may have been moved."),
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

    fun shareAttachment(messageId: String, attachment: MailAttachment) {
        if (attachmentBusyId != null) return
        attachmentError = null
        attachmentBusyId = attachment.id
        scope.launch {
            val result = runCatching { repository.loadAttachment(request.accountId, messageId, attachment.id) }
            attachmentBusyId = null
            val path = result.getOrNull()
            if (!path.isNullOrBlank()) {
                host.ui().share(shareRequest(filePath = path, mimeType = attachment.mimeType))
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

    LaunchedEffect(state.messages, request) {
        if (messages.isEmpty()) return@LaunchedEffect
        val latestId = messages.last().id
        if (expanded.isEmpty()) expanded.add(latestId)
    }

    LaunchedEffect(messages, request) {
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
            if (loaded.summary.unread) {
                runCatching { repository.mark(request.accountId, loaded.summary.id, unread = false) }
            }
        }
        fallback = detail.fold(
            onSuccess = { loaded ->
                if (loaded != null) {
                    MailDetailState.Loaded(loaded)
                } else {
                    MailDetailState.Failed(
                        host.t("打不开这封邮件，可能已被移动。", "This message can't be opened. It may have been moved."),
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

    val title = remember(messages, fallback, request) {
        val subject = messages.lastOrNull()?.subject
            ?: (fallback as? MailDetailState.Loaded)?.detail?.summary?.subject
            ?: ""
        displaySubject(subject, host.t("（无主题）", "(No subject)"))
    }
    val accountColor = remember(account) {
        val color = Color(account?.color ?: 0)
        if (color.alpha <= 0f) Color.Transparent else color
    }
    val latestDetailState = if (messages.isEmpty()) {
        fallback
    } else {
        details[messages.last().id]
    }
    val canCompose = latestDetailState is MailDetailState.Loaded

    DdSheet(onDismiss = onDismiss) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.94f)) {
            MailSheetHeader(host = host, title = title, onClose = onDismiss)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
            ) {
                if (messages.isEmpty()) {
                    FallbackMessage(
                        host = host,
                        state = fallback,
                        accountColor = accountColor,
                        chinese = chinese,
                        attachmentBusyId = attachmentBusyId,
                        onRetry = { fallbackAttempt++ },
                        onAttachment = { messageId, attachment ->
                            shareAttachment(messageId, attachment)
                        },
                    )
                } else {
                    messages.forEachIndexed { index, message ->
                        key(message.id) {
                            val isExpanded = expanded.contains(message.id)
                            LaunchedEffect(isExpanded) {
                                if (isExpanded) loadDetail(message.id)
                            }
                            if (index > 0) Spacer(Modifier.height(8.dp))
                            ThreadMessage(
                                host = host,
                                message = message,
                                accountColor = accountColor,
                                chinese = chinese,
                                expanded = isExpanded,
                                detailState = details[message.id],
                                attachmentBusyId = attachmentBusyId,
                                onToggle = {
                                    if (isExpanded) {
                                        expanded.remove(message.id)
                                    } else {
                                        expanded.add(message.id)
                                    }
                                },
                                onRetry = { loadDetail(message.id, force = true) },
                                onAttachment = { attachment -> shareAttachment(message.id, attachment) },
                            )
                        }
                    }
                }
                attachmentError?.let { error ->
                    Spacer(Modifier.height(12.dp))
                    MailNoticeBar(text = error, tone = deepDeepColors().danger)
                }
                Spacer(Modifier.height(16.dp))
            }
            DdHairline()
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DdTextAction(
                    text = host.t("回复", "Reply"),
                    onClick = { composeRequest(MailComposeKind.REPLY) },
                    enabled = canCompose,
                )
                DdTextAction(
                    text = host.t("全部回复", "Reply all"),
                    onClick = { composeRequest(MailComposeKind.REPLY_ALL) },
                    enabled = canCompose,
                )
                DdTextAction(
                    text = host.t("转发", "Forward"),
                    onClick = { composeRequest(MailComposeKind.FORWARD) },
                    enabled = canCompose,
                )
            }
        }
    }
}

@Composable
private fun FallbackMessage(
    host: PluginHost,
    state: MailDetailState?,
    accountColor: Color,
    chinese: Boolean,
    attachmentBusyId: String?,
    onRetry: () -> Unit,
    onAttachment: (String, MailAttachment) -> Unit,
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
            val detail = state.detail
            MailMessageBody(
                host = host,
                detail = detail,
                accountColor = accountColor,
                chinese = chinese,
                showHeader = true,
                attachmentBusyId = attachmentBusyId,
                onAttachment = { attachment -> onAttachment(detail.summary.id, attachment) },
            )
        }
    }
}

@Composable
private fun ThreadMessage(
    host: PluginHost,
    message: MailMessageSummary,
    accountColor: Color,
    chinese: Boolean,
    expanded: Boolean,
    detailState: MailDetailState?,
    attachmentBusyId: String?,
    onToggle: () -> Unit,
    onRetry: () -> Unit,
    onAttachment: (MailAttachment) -> Unit,
) {
    val colors = deepDeepColors()
    if (!expanded) {
        DdPressable(
            onClick = onToggle,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(DdRadius.Medium),
            contentDescription = null,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MailAvatar(
                    initials = mailInitial(message.fromName, message.fromAddress),
                    color = accountColor,
                    size = 28.dp,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = message.fromName.ifBlank { message.fromAddress },
                    modifier = Modifier.weight(1f),
                    style = DdType.label,
                    color = colors.inkSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = displayPreview(message.preview, limit = 40),
                    modifier = Modifier.weight(1.4f),
                    style = DdType.bodySmall,
                    color = colors.inkTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = mailListTime(message.dateMillis, System.currentTimeMillis(), chinese),
                    style = DdType.bodySmall,
                    color = colors.inkTertiary,
                    maxLines = 1,
                )
            }
        }
        return
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        when (detailState) {
            null, is MailDetailState.Loading -> {
                MessageHeader(host, message, accountColor, chinese)
                DdLoadingBlock(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    text = host.t("正在加载邮件", "Loading mail"),
                )
            }

            is MailDetailState.Failed -> {
                MessageHeader(host, message, accountColor, chinese)
                Column(Modifier.padding(top = 8.dp)) {
                    Text(text = detailState.message, style = DdType.bodySmall, color = colors.danger)
                    DdTextAction(text = host.t("重试", "Retry"), onClick = onRetry, color = colors.accent)
                }
            }

            is MailDetailState.Loaded -> MailMessageBody(
                host = host,
                detail = detailState.detail,
                accountColor = accountColor,
                chinese = chinese,
                showHeader = true,
                attachmentBusyId = attachmentBusyId,
                onAttachment = onAttachment,
            )
        }
    }
}

@Composable
private fun MessageHeader(
    host: PluginHost,
    message: MailMessageSummary,
    accountColor: Color,
    chinese: Boolean,
) {
    val colors = deepDeepColors()
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        MailAvatar(
            initials = mailInitial(message.fromName, message.fromAddress),
            color = accountColor,
            size = 32.dp,
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = message.fromName.ifBlank { message.fromAddress },
                style = DdType.label,
                color = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (message.fromName.isNotBlank()) {
                Text(
                    text = message.fromAddress,
                    style = DdType.bodySmall,
                    color = colors.inkTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = mailFullTime(message.dateMillis, chinese),
            style = DdType.bodySmall,
            color = colors.inkTertiary,
            maxLines = 1,
        )
    }
}

@Composable
private fun MailMessageBody(
    host: PluginHost,
    detail: MailMessageDetail,
    accountColor: Color,
    chinese: Boolean,
    showHeader: Boolean,
    attachmentBusyId: String?,
    onAttachment: (MailAttachment) -> Unit,
) {
    val colors = deepDeepColors()
    Column(Modifier.fillMaxWidth()) {
        if (showHeader) {
            MessageHeader(host, detail.summary, accountColor, chinese)
        }
        val body = rememberMailBody(host, detail)
        Box(Modifier.fillMaxWidth().padding(top = 10.dp)) {
            DdText(
                text = body,
                style = DdType.body,
                color = colors.ink,
            )
        }
        if (detail.attachments.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                detail.attachments.forEach { attachment ->
                    DdListRow(
                        title = attachment.name.ifBlank { host.t("附件", "Attachment") },
                        subtitle = formatFileSize(attachment.sizeBytes).takeIf { it.isNotBlank() },
                        leadingIcon = DdIcons.Paperclip,
                        onClick = { onAttachment(attachment) },
                        trailing = {
                            if (attachmentBusyId == attachment.id) {
                                DdSpinner(size = 16.dp, strokeWidth = 1.5.dp, color = colors.inkTertiary)
                            } else {
                                DdIcon(icon = DdIcons.Download, tint = colors.inkTertiary, size = 16.dp)
                            }
                        },
                    )
                }
            }
        }
    }
}

private enum class MailComposeKind { REPLY, REPLY_ALL, FORWARD }

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
