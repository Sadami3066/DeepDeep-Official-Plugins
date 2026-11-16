package io.github.whywood.deepdeep.plugin.mail.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.whywood.deepdeep.design.DdAlpha
import io.github.whywood.deepdeep.design.DdIcon
import io.github.whywood.deepdeep.design.DdIcons
import io.github.whywood.deepdeep.design.DdPrimaryButton
import io.github.whywood.deepdeep.design.DdShapes
import io.github.whywood.deepdeep.design.DdSpacing
import io.github.whywood.deepdeep.design.DdSpinner
import io.github.whywood.deepdeep.design.DdText
import io.github.whywood.deepdeep.design.DdTextAction
import io.github.whywood.deepdeep.design.DdType
import io.github.whywood.deepdeep.design.deepDeepColors
import io.github.whywood.deepdeep.plugin.api.CustomMessageSlotState
import io.github.whywood.deepdeep.plugin.api.PluginHost
import io.github.whywood.deepdeep.plugin.mail.MailRepository
import io.github.whywood.deepdeep.plugin.mail.MailSendResult
import io.github.whywood.deepdeep.plugin.mail.tools.MailDraftCardAttachment
import io.github.whywood.deepdeep.plugin.mail.tools.MailDraftCardPayload
import kotlinx.coroutines.launch

private const val DraftBodyMaxLines = 3

private sealed interface MailCardSendState {
    data object Idle : MailCardSendState

    data object Sending : MailCardSendState

    data object Sent : MailCardSendState

    data class Failed(val message: String) : MailCardSendState
}

private object MailCardSends {
    private val states = mutableMapOf<String, MutableState<MailCardSendState>>()

    fun state(draftId: String): MutableState<MailCardSendState> = synchronized(states) {
        states.getOrPut(draftId) { mutableStateOf(MailCardSendState.Idle) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MailDraftChatCard(
    host: PluginHost,
    repository: MailRepository,
    state: CustomMessageSlotState,
) {
    val payload = remember(state.payload) { state.decodeCardPayload(MailDraftCardPayload.serializer()) }
    if (payload == null || payload.draftId.isBlank()) {
        MailUnavailableCard(
            host = host,
            title = host.t("这份草稿暂时打不开", "This draft can't be opened here"),
            hint = host.t("可以打开邮箱继续编辑或发送。", "Open Mail to keep editing or send it."),
            onOpenMail = { host.openMailPage() },
        )
        return
    }
    val colors = deepDeepColors()
    val mailState by repository.state.collectAsState()
    val sendState = remember(payload.draftId) { MailCardSends.state(payload.draftId) }
    val current = sendState.value
    val liveDraft = mailState.drafts.firstOrNull { draft -> draft.id == payload.draftId }
    val missing = current is MailCardSendState.Idle && !mailState.loading && liveDraft == null
    val subject = liveDraft?.subject ?: payload.subject
    val recipients = liveDraft?.to ?: payload.to
    val ccRecipients = liveDraft?.cc ?: payload.cc
    val bodyPreview = liveDraft?.body?.let { displayPreview(it, 240) } ?: payload.bodyPreview
    val attachments = if (liveDraft != null) {
        liveDraft.attachments.map { MailDraftCardAttachment(name = it.name, sizeBytes = it.sizeBytes) }
    } else {
        payload.attachments
    }

    fun editDraft() {
        host.openMailDraftPage(payload.draftId)
    }

    fun sendDraft() {
        if (sendState.value is MailCardSendState.Sending || sendState.value is MailCardSendState.Sent) return
        sendState.value = MailCardSendState.Sending
        host.scope.launch {
            val result = runCatching { repository.sendDraft(payload.draftId) }.fold(
                onSuccess = { it },
                onFailure = { error ->
                    MailSendResult(
                        ok = false,
                        error = host.readableError(
                            error,
                            fallbackZh = "发送失败，请重试",
                            fallbackEn = "Couldn't send. Try again.",
                        ),
                    )
                },
            )
            sendState.value = if (result.ok) {
                MailCardSendState.Sent
            } else {
                MailCardSendState.Failed(
                    result.error?.takeIf { it.isNotBlank() }
                        ?: host.t("发送失败，请重试。", "Couldn't send. Try again."),
                )
            }
        }
    }

    MailCardSurface {
        ComposeTopBar(host = host, account = payload.account, sent = current is MailCardSendState.Sent)
        DdText(
            text = displaySubject(subject, host.t("（无主题）", "(No subject)")),
            modifier = Modifier.padding(start = DdSpacing.Lg, end = DdSpacing.Lg, top = DdSpacing.Sm),
            style = DdType.body.copy(fontWeight = FontWeight.SemiBold),
            color = colors.ink,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        RecipientRow(host = host, label = host.t("收件人", "To"), recipients = recipients, accent = true)
        if (ccRecipients.isNotEmpty()) {
            RecipientRow(host = host, label = host.t("抄送", "Cc"), recipients = ccRecipients, accent = false)
        }
        DraftBodyPanel(
            text = bodyPreview,
            empty = host.t("（正文为空）", "(No body text)"),
        )
        if (attachments.isNotEmpty()) {
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = DdSpacing.Lg, end = DdSpacing.Lg, top = DdSpacing.Sm),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                attachments.forEach { attachment ->
                    MailAttachmentPill(
                        name = attachment.name,
                        sizeText = formatFileSize(attachment.sizeBytes).ifBlank { null },
                    )
                }
            }
        }
        if (current is MailCardSendState.Failed) {
            DdText(
                text = current.message,
                modifier = Modifier.padding(start = DdSpacing.Lg, end = DdSpacing.Lg, top = DdSpacing.Sm),
                style = DdType.bodySmall,
                color = colors.danger,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(DdSpacing.Xs))
        when {
            current is MailCardSendState.Sent -> DraftSentRow(host) { host.openMailPage() }

            missing -> DraftMissingRow(host) { host.openMailPage() }

            else -> DraftActionsRow(
                host = host,
                sending = current is MailCardSendState.Sending,
                retry = current is MailCardSendState.Failed,
                onEdit = { editDraft() },
                onPrimary = { sendDraft() },
            )
        }
    }
}

@Composable
private fun ComposeTopBar(host: PluginHost, account: String, sent: Boolean) {
    val colors = deepDeepColors()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = DdSpacing.Lg, end = DdSpacing.Lg, top = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .clip(DdShapes.Pill)
                .background(colors.wash)
                .padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DdIcon(icon = DdIcons.Pen, tint = colors.inkSecondary, size = 11.dp)
            Spacer(Modifier.width(5.dp))
            DdText(
                text = if (sent) host.t("已发送", "Sent") else host.t("草稿 · 未发送", "Draft · Not sent"),
                style = DdType.chip.copy(fontSize = 11.sp, lineHeight = 16.sp),
                color = colors.inkSecondary,
                maxLines = 1,
            )
        }
        Spacer(Modifier.weight(1f))
        if (account.isNotBlank()) {
            Spacer(Modifier.width(DdSpacing.Sm))
            DdText(
                text = account,
                modifier = Modifier.widthIn(max = 150.dp),
                style = DdType.chip.copy(fontSize = 11.sp),
                color = colors.inkTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecipientRow(host: PluginHost, label: String, recipients: List<String>, accent: Boolean) {
    val colors = deepDeepColors()
    if (recipients.isEmpty()) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = DdSpacing.Lg, end = DdSpacing.Lg, top = DdSpacing.Sm),
        verticalAlignment = Alignment.Top,
    ) {
        DdText(
            text = label,
            modifier = Modifier.padding(top = 4.dp),
            style = DdType.chip.copy(fontSize = 11.sp, lineHeight = 16.sp),
            color = colors.inkTertiary,
            maxLines = 1,
        )
        Spacer(Modifier.width(DdSpacing.Sm))
        FlowRow(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            recipients.forEach { recipient ->
                MailRecipientChip(recipient = recipient, accent = accent)
            }
        }
    }
}

@Composable
private fun DraftBodyPanel(text: String, empty: String) {
    val colors = deepDeepColors()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = DdSpacing.Lg, end = DdSpacing.Lg, top = DdSpacing.Md)
            .clip(DdShapes.Medium)
            .background(colors.wash)
            .padding(horizontal = DdSpacing.Md, vertical = 10.dp),
    ) {
        DdText(
            text = text.ifBlank { empty },
            style = DdType.bodySmall.copy(lineHeight = 21.sp),
            color = if (text.isBlank()) colors.inkTertiary else colors.inkSecondary,
            maxLines = DraftBodyMaxLines,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun DraftActionsRow(
    host: PluginHost,
    sending: Boolean,
    retry: Boolean,
    onEdit: () -> Unit,
    onPrimary: () -> Unit,
) {
    val colors = deepDeepColors()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = DdSpacing.Xs, end = DdSpacing.Md, top = DdSpacing.Xs, bottom = DdSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DdTextAction(
            text = host.t("编辑", "Edit"),
            onClick = onEdit,
            color = colors.inkSecondary,
            enabled = !sending,
        )
        Spacer(Modifier.weight(1f))
        if (sending) {
            DdSpinner(size = 14.dp, color = colors.inkTertiary)
            Spacer(Modifier.width(DdSpacing.Sm))
        }
        DdPrimaryButton(
            text = when {
                sending -> host.t("发送中…", "Sending…")
                retry -> host.t("重试", "Retry")
                else -> host.t("发送", "Send")
            },
            onClick = onPrimary,
            enabled = !sending,
        )
    }
}

@Composable
private fun DraftSentRow(host: PluginHost, onOpenMail: () -> Unit) {
    val colors = deepDeepColors()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = DdSpacing.Lg, end = DdSpacing.Xs, bottom = DdSpacing.Xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DdIcon(icon = DdIcons.Check, tint = colors.success, size = 15.dp)
        Spacer(Modifier.width(6.dp))
        DdText(
            text = host.t("已发送", "Sent"),
            style = DdType.label.copy(fontWeight = FontWeight.SemiBold),
            color = colors.success,
            maxLines = 1,
        )
        Spacer(Modifier.weight(1f))
        DdTextAction(text = host.t("打开邮箱", "Open Mail"), onClick = onOpenMail)
    }
}

@Composable
private fun DraftMissingRow(host: PluginHost, onOpenMail: () -> Unit) {
    val colors = deepDeepColors()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = DdSpacing.Lg, end = DdSpacing.Xs, top = 2.dp, bottom = DdSpacing.Xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DdText(
            text = host.t("这份草稿已不在草稿箱中", "This draft is no longer in your drafts"),
            modifier = Modifier.weight(1f),
            style = DdType.bodySmall,
            color = colors.inkTertiary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(DdSpacing.Xs))
        DdTextAction(text = host.t("打开邮箱", "Open Mail"), onClick = onOpenMail)
    }
}
