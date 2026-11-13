package io.github.whywood.deepdeep.plugin.mail.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.whywood.deepdeep.design.DdHairline
import io.github.whywood.deepdeep.design.DdIcon
import io.github.whywood.deepdeep.design.DdIcons
import io.github.whywood.deepdeep.design.DdPrimaryButton
import io.github.whywood.deepdeep.design.DdSpacing
import io.github.whywood.deepdeep.design.DdTextAction
import io.github.whywood.deepdeep.design.DdType
import io.github.whywood.deepdeep.design.deepDeepColors
import io.github.whywood.deepdeep.plugin.api.CustomMessageSlotState
import io.github.whywood.deepdeep.plugin.api.PluginHost
import io.github.whywood.deepdeep.plugin.mail.MailRepository
import io.github.whywood.deepdeep.plugin.mail.MailSendResult
import io.github.whywood.deepdeep.plugin.mail.tools.MailDraftCardPayload
import kotlinx.coroutines.launch

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

@Composable
internal fun MailDraftChatCard(
    host: PluginHost,
    repository: MailRepository,
    state: CustomMessageSlotState,
) {
    val payload = remember(state.payload) { state.decodeCardPayload(MailDraftCardPayload.serializer()) }
    if (payload == null || payload.draftId.isBlank()) {
        MailChatUnavailableCard(host, host.t("邮件", "Mail"))
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

    fun editDraft() {
        MailCardNavigation.editDraft(payload.draftId)
        host.openMailPage()
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

    MailChatCardSurface {
        MailChatCardHeader(
            icon = DdIcons.Pen,
            title = host.t("邮件草稿", "Mail draft"),
            trailing = payload.account.takeIf { it.isNotBlank() },
        )
        Column(Modifier.fillMaxWidth().padding(horizontal = DdSpacing.Lg)) {
            Text(
                text = displaySubject(subject, host.t("（无主题）", "(No subject)")),
                style = DdType.body.copy(fontWeight = FontWeight.SemiBold),
                color = colors.ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(DdSpacing.Xs))
            MailChatMetaRow(
                label = host.t("收件人", "To"),
                value = recipients.joinToString(", "),
            )
            if (ccRecipients.isNotEmpty()) {
                MailChatMetaRow(
                    label = host.t("抄送", "Cc"),
                    value = ccRecipients.joinToString(", "),
                )
            }
            if (bodyPreview.isNotBlank()) {
                Spacer(Modifier.height(DdSpacing.Sm))
                Text(
                    text = bodyPreview,
                    style = DdType.bodySmall,
                    color = colors.inkSecondary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (current is MailCardSendState.Failed) {
                Spacer(Modifier.height(DdSpacing.Sm))
                Text(
                    text = current.message,
                    style = DdType.bodySmall,
                    color = colors.danger,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(DdSpacing.Md))
        }
        DdHairline()
        when {
            current is MailCardSendState.Sent -> DraftSentFooter(host) { host.openMailPage() }

            current is MailCardSendState.Sending -> DraftActionsFooter(
                host = host,
                editEnabled = false,
                primaryLabel = host.t("发送中…", "Sending…"),
                primaryEnabled = false,
                onEdit = { editDraft() },
                onPrimary = { sendDraft() },
            )

            missing -> DraftMissingFooter(host) { host.openMailPage() }

            current is MailCardSendState.Failed -> DraftActionsFooter(
                host = host,
                editEnabled = true,
                primaryLabel = host.t("重试", "Retry"),
                primaryEnabled = true,
                onEdit = { editDraft() },
                onPrimary = { sendDraft() },
            )

            else -> DraftActionsFooter(
                host = host,
                editEnabled = true,
                primaryLabel = host.t("发送", "Send"),
                primaryEnabled = true,
                onEdit = { editDraft() },
                onPrimary = { sendDraft() },
            )
        }
    }
}

@Composable
private fun DraftActionsFooter(
    host: PluginHost,
    editEnabled: Boolean,
    primaryLabel: String,
    primaryEnabled: Boolean,
    onEdit: () -> Unit,
    onPrimary: () -> Unit,
) {
    val colors = deepDeepColors()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = DdSpacing.Xs, end = DdSpacing.Lg, top = DdSpacing.Sm, bottom = DdSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DdTextAction(
            text = host.t("编辑", "Edit"),
            onClick = onEdit,
            color = colors.inkSecondary,
            enabled = editEnabled,
        )
        Spacer(Modifier.weight(1f))
        DdPrimaryButton(
            text = primaryLabel,
            onClick = onPrimary,
            enabled = primaryEnabled,
        )
    }
}

@Composable
private fun DraftSentFooter(host: PluginHost, onOpenMail: () -> Unit) {
    val colors = deepDeepColors()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .padding(start = DdSpacing.Lg, end = DdSpacing.Xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DdIcon(icon = DdIcons.Check, tint = colors.success, size = 15.dp)
        Spacer(Modifier.width(6.dp))
        Text(
            text = host.t("已发送", "Sent"),
            style = DdType.label,
            color = colors.success,
            maxLines = 1,
        )
        Spacer(Modifier.weight(1f))
        DdTextAction(text = host.t("打开邮箱", "Open Mail"), onClick = onOpenMail)
    }
}

@Composable
private fun DraftMissingFooter(host: PluginHost, onOpenMail: () -> Unit) {
    val colors = deepDeepColors()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .padding(start = DdSpacing.Lg, end = DdSpacing.Xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
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


@Composable
private fun MailChatMetaRow(label: String, value: String) {
    val colors = deepDeepColors()
    Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
        Text(
            text = label,
            modifier = Modifier.width(48.dp),
            style = DdType.chip,
            color = colors.inkTertiary,
            maxLines = 1,
        )
        Text(
            text = value,
            modifier = Modifier.weight(1f),
            style = DdType.bodySmall,
            color = colors.inkSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
