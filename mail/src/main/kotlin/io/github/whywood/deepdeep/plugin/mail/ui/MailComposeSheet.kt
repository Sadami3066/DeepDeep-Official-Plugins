package io.github.whywood.deepdeep.plugin.mail.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.whywood.deepdeep.design.DdChip
import io.github.whywood.deepdeep.design.DdHairline
import io.github.whywood.deepdeep.design.DdPrimaryButton
import io.github.whywood.deepdeep.design.DdSheet
import io.github.whywood.deepdeep.design.DdTextAction
import io.github.whywood.deepdeep.design.DdTextArea
import io.github.whywood.deepdeep.design.DdTextField
import io.github.whywood.deepdeep.design.DdType
import io.github.whywood.deepdeep.design.deepDeepColors
import io.github.whywood.deepdeep.plugin.api.PluginHost
import io.github.whywood.deepdeep.plugin.mail.MailDraft
import io.github.whywood.deepdeep.plugin.mail.MailRepository
import io.github.whywood.deepdeep.plugin.mail.MailSendResult
import kotlinx.coroutines.launch

@Composable
internal fun MailComposeSheet(
    host: PluginHost,
    repository: MailRepository,
    request: MailComposeRequest,
    onClose: () -> Unit,
) {
    val state by repository.state.collectAsState()
    val colors = deepDeepColors()
    val scope = rememberCoroutineScope()
    val accounts = state.accounts

    var accountId by rememberSaveable(request) { mutableStateOf(request.accountId) }
    var to by rememberSaveable(request) { mutableStateOf(recipientInput(request.to)) }
    var cc by rememberSaveable(request) { mutableStateOf(recipientInput(request.cc)) }
    var showCc by rememberSaveable(request) { mutableStateOf(request.cc.isNotEmpty()) }
    var subject by rememberSaveable(request) { mutableStateOf(request.subject) }
    var body by rememberSaveable(request) { mutableStateOf(request.body) }
    var draftId by rememberSaveable(request) { mutableStateOf(request.draft?.id) }
    val createdAt by rememberSaveable(request) {
        mutableLongStateOf(request.draft?.createdAt ?: System.currentTimeMillis())
    }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmEmptySubject by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }
    var dirty by remember { mutableStateOf(false) }

    val buildDraft: () -> MailDraft? = {
        val recipients = parseRecipients(to)
        val ccRecipients = if (showCc) parseRecipients(cc) else emptyList()
        if (accountId.isBlank()) {
            null
        } else if (recipients.isEmpty() && ccRecipients.isEmpty() && subject.isBlank() && body.isBlank()) {
            null
        } else {
            MailDraft(
                id = draftId ?: "draft-$createdAt",
                accountId = accountId,
                to = recipients,
                cc = ccRecipients,
                subject = subject.trim(),
                body = body,
                inReplyToId = request.draft?.inReplyToId ?: request.inReplyToId,
                createdAt = request.draft?.createdAt ?: createdAt,
                updatedAt = System.currentTimeMillis(),
            )
        }
    }

    fun send() {
        if (sending) return
        error = null
        sending = true
        dirty = true
        scope.launch {
            val draft = buildDraft()
            if (draft == null) {
                sending = false
                error = host.t("请填写收件人。", "Add at least one recipient.")
                return@launch
            }
            val result = runCatching {
                val saved = repository.saveDraft(draft)
                draftId = saved.id
                repository.sendDraft(saved.id)
            }.fold(
                onSuccess = { it },
                onFailure = {
                    MailSendResult(
                        ok = false,
                        error = host.readableError(
                            it,
                            fallbackZh = "发送失败，请重试",
                            fallbackEn = "Couldn't send. Try again.",
                        ),
                    )
                },
            )
            sending = false
            if (result.ok) {
                finished = true
                host.ui().toast(host.t("邮件已发送", "Message sent"))
                onClose()
            } else {
                error = result.error?.takeIf { it.isNotBlank() }
                    ?: host.t("发送失败，请重试。", "Couldn't send. Try again.")
            }
        }
    }

    fun requestSend() {
        val recipients = parseRecipients(to)
        if (recipients.isEmpty()) {
            error = host.t("请填写收件人。", "Add at least one recipient.")
            return
        }
        if (recipients.any { !validRecipient(it) }) {
            error = host.t("收件人地址格式不正确。", "One of the recipient addresses looks wrong.")
            return
        }
        if (showCc && parseRecipients(cc).any { !validRecipient(it) }) {
            error = host.t("抄送地址格式不正确。", "One of the cc addresses looks wrong.")
            return
        }
        error = null
        if (subject.isBlank()) {
            confirmEmptySubject = true
            return
        }
        send()
    }

    fun requestClose() {
        if (sending) {
            host.ui().toast(host.t("正在发送，请稍候", "Sending. Please wait."))
            return
        }
        onClose()
    }

    val draftSnapshot = rememberUpdatedState(buildDraft)
    val finishedSnapshot = rememberUpdatedState(finished)
    val dirtySnapshot = rememberUpdatedState(dirty)
    DisposableEffect(Unit) {
        onDispose {
            if (finishedSnapshot.value || !dirtySnapshot.value) return@onDispose
            val draft = draftSnapshot.value()
            if (draft != null) {
                host.scope.launch { runCatching { repository.saveDraft(draft) } }
            } else {
                request.draft?.let { existing ->
                    host.scope.launch { runCatching { repository.deleteDraft(existing.id) } }
                }
            }
        }
    }

    DdSheet(onDismiss = { requestClose() }) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.94f)) {
            MailSheetHeader(
                host = host,
                title = if (request.draft != null) {
                    host.t("编辑草稿", "Edit draft")
                } else {
                    host.t("写邮件", "New mail")
                },
                onClose = { requestClose() },
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
            ) {
                if (accounts.size > 1) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        accounts.forEach { account ->
                            DdChip(
                                text = account.email,
                                onClick = {
                                    accountId = account.id
                                    dirty = true
                                },
                                selected = accountId == account.id,
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
                DdTextField(
                    value = to,
                    onValueChange = {
                        to = it
                        dirty = true
                        error = null
                    },
                    placeholder = host.t("收件人，多个用逗号分隔", "Recipients, separated by commas"),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Email,
                        imeAction = ImeAction.Next,
                    ),
                )
                Spacer(Modifier.height(8.dp))
                if (showCc) {
                    DdTextField(
                        value = cc,
                        onValueChange = {
                            cc = it
                            dirty = true
                            error = null
                        },
                        placeholder = host.t("抄送，多个用逗号分隔", "Cc, separated by commas"),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Email,
                            imeAction = ImeAction.Next,
                        ),
                    )
                    Spacer(Modifier.height(4.dp))
                }
                DdTextAction(
                    text = if (showCc) {
                        host.t("收起抄送", "Hide cc")
                    } else {
                        host.t("添加抄送", "Add cc")
                    },
                    onClick = {
                        showCc = !showCc
                        dirty = true
                        if (!showCc) cc = ""
                    },
                    color = colors.inkSecondary,
                )
                Spacer(Modifier.height(4.dp))
                DdTextField(
                    value = subject,
                    onValueChange = {
                        subject = it
                        dirty = true
                        error = null
                    },
                    placeholder = host.t("主题", "Subject"),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                )
                Spacer(Modifier.height(12.dp))
                DdTextArea(
                    value = body,
                    onValueChange = {
                        body = it
                        dirty = true
                        error = null
                    },
                    placeholder = host.t("正文", "Message"),
                    minHeight = 200.dp,
                    maxHeight = 340.dp,
                )
                error?.let { message ->
                    Spacer(Modifier.height(12.dp))
                    Text(text = message, style = DdType.bodySmall, color = colors.danger)
                }
                Spacer(Modifier.height(16.dp))
            }
            DdHairline()
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.weight(1f))
                DdPrimaryButton(
                    text = if (sending) {
                        host.t("发送中…", "Sending…")
                    } else {
                        host.t("发送", "Send")
                    },
                    onClick = { requestSend() },
                    enabled = !sending,
                )
            }
        }
    }

    if (confirmEmptySubject) {
        MailConfirmDialog(
            host = host,
            title = host.t("邮件没有主题", "No subject"),
            message = host.t("仍然发送这封邮件吗？", "Send this message anyway?"),
            confirmLabel = host.t("仍然发送", "Send anyway"),
            onConfirm = {
                confirmEmptySubject = false
                send()
            },
            onDismiss = { confirmEmptySubject = false },
        )
    }
}
