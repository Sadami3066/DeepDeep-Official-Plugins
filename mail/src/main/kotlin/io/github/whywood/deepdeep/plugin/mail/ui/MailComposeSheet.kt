package io.github.whywood.deepdeep.plugin.mail.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.whywood.deepdeep.design.DdChip
import io.github.whywood.deepdeep.design.DdFileRow
import io.github.whywood.deepdeep.design.DdGroupCard
import io.github.whywood.deepdeep.design.DdHairline
import io.github.whywood.deepdeep.design.DdIconButton
import io.github.whywood.deepdeep.design.DdIcons
import io.github.whywood.deepdeep.design.DdImage
import io.github.whywood.deepdeep.design.DdImageViewer
import io.github.whywood.deepdeep.design.DdPressable
import io.github.whywood.deepdeep.design.DdPrimaryButton
import io.github.whywood.deepdeep.design.DdRowDivider
import io.github.whywood.deepdeep.design.DdShapes
import io.github.whywood.deepdeep.design.DdSheet
import io.github.whywood.deepdeep.design.DdSpinner
import io.github.whywood.deepdeep.design.DdTextAction
import io.github.whywood.deepdeep.design.DdTextArea
import io.github.whywood.deepdeep.design.DdTextField
import io.github.whywood.deepdeep.design.DdType
import io.github.whywood.deepdeep.design.deepDeepColors
import io.github.whywood.deepdeep.plugin.api.PluginHost
import io.github.whywood.deepdeep.plugin.api.uiFilePickRequest
import io.github.whywood.deepdeep.plugin.mail.DraftAttachment
import io.github.whywood.deepdeep.plugin.mail.MailDraft
import io.github.whywood.deepdeep.plugin.mail.MailRepository
import io.github.whywood.deepdeep.plugin.mail.MailSendResult
import io.github.whywood.deepdeep.plugin.mail.mime.MailComposeLimits
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private val AttachmentTotalLimit = "${MailComposeLimits.MAX_TOTAL_ATTACHMENT_BYTES / (1024L * 1024L)} MB"

private val DraftAttachmentListSaver = listSaver<List<DraftAttachment>, String>(
    save = { attachments ->
        attachments.flatMap { attachment ->
            listOf(attachment.path, attachment.name, attachment.mimeType, attachment.sizeBytes.toString())
        }
    },
    restore = { values ->
        values.chunked(4).mapNotNull { chunk ->
            if (chunk.size != 4) return@mapNotNull null
            DraftAttachment(path = chunk[0], name = chunk[1], mimeType = chunk[2], sizeBytes = chunk[3].toLongOrNull() ?: 0L)
        }
    },
)

private fun attachmentFailure(host: PluginHost, name: String, message: String): String =
    host.tf("%s：%s", "%s: %s", name, message)

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
    val createdAt by rememberSaveable(request) {
        mutableLongStateOf(request.draft?.createdAt ?: System.currentTimeMillis())
    }
    var draftId by rememberSaveable(request) {
        mutableStateOf(
            request.draft?.id?.takeIf { it.isNotBlank() }
                ?: "draft-$createdAt-${UUID.randomUUID().toString().take(8)}",
        )
    }
    var attachments by rememberSaveable(request, stateSaver = DraftAttachmentListSaver) {
        mutableStateOf(request.draft?.attachments ?: emptyList())
    }
    var importing by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmEmptySubject by remember { mutableStateOf(false) }
    var viewerPath by remember { mutableStateOf<String?>(null) }
    val finishedState = remember { mutableStateOf(false) }
    var finished by finishedState
    val dirtyState = remember { mutableStateOf(false) }
    var dirty by dirtyState

    val totalAttachmentBytes = attachments.sumOf { it.sizeBytes }
    val canEditAttachments = !sending && !importing

    val buildDraft: () -> MailDraft? = {
        val recipients = parseRecipients(to)
        val ccRecipients = if (showCc) parseRecipients(cc) else emptyList()
        if (accountId.isBlank()) {
            null
        } else if (
            recipients.isEmpty() && ccRecipients.isEmpty() && subject.isBlank() && body.isBlank() &&
            attachments.isEmpty()
        ) {
            null
        } else {
            MailDraft(
                id = draftId,
                accountId = accountId,
                to = recipients,
                cc = ccRecipients,
                subject = subject.trim(),
                body = body,
                inReplyToId = request.draft?.inReplyToId ?: request.inReplyToId,
                attachments = attachments,
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
        if (importing) {
            host.ui().toast(host.t("正在添加附件，请稍候", "Adding the attachment. Please wait."))
            return
        }
        onClose()
    }

    fun removeAttachment(attachment: DraftAttachment) {
        if (!canEditAttachments) return
        attachments = attachments.filterNot { it.path == attachment.path }
        dirty = true
        error = null
        scope.launch {
            runCatching { repository.removeDraftAttachment(draftId, attachment) }
                .onFailure { failure ->
                    if (attachments.none { it.path == attachment.path }) {
                        attachments = attachments + attachment
                        dirty = true
                    }
                    error = host.readableError(
                        failure,
                        fallbackZh = "移除附件失败，请重试。",
                        fallbackEn = "Couldn't remove the attachment. Try again.",
                    )
                }
        }
    }

    fun pickAttachments() {
        if (!canEditAttachments) return
        importing = true
        error = null
        scope.launch {
            val picked = try {
                host.ui().pickFiles(uiFilePickRequest(mimeTypes = listOf("*/*"), multiple = true))
            } catch (cancelled: CancellationException) {
                importing = false
                throw cancelled
            } catch (failure: Throwable) {
                importing = false
                error = host.readableError(
                    failure,
                    fallbackZh = "无法打开文件选择器。",
                    fallbackEn = "Couldn't open the file picker.",
                )
                return@launch
            }
            if (picked.isEmpty()) {
                importing = false
                return@launch
            }
            val failures = mutableListOf<String>()
            picked.forEach { file ->
                val name = file.name.ifBlank { host.t("附件", "Attachment") }
                if (file.sizeBytes > 0L && file.sizeBytes > MailComposeLimits.MAX_ATTACHMENT_BYTES) {
                    failures += attachmentFailure(
                        host,
                        name,
                        host.t("单个附件不能超过 20 MB。", "Each attachment must be 20 MB or smaller."),
                    )
                    return@forEach
                }
                if (
                    file.sizeBytes > 0L &&
                    attachments.sumOf { it.sizeBytes } + file.sizeBytes > MailComposeLimits.MAX_TOTAL_ATTACHMENT_BYTES
                ) {
                    failures += attachmentFailure(
                        host,
                        name,
                        host.t("附件总大小不能超过 25 MB。", "Attachments must total 25 MB or less."),
                    )
                    return@forEach
                }
                try {
                    val attachment = repository.importDraftAttachment(
                        draftId = draftId,
                        sourcePath = file.path,
                        name = name,
                        mimeType = file.mimeType.orEmpty(),
                    )
                    attachments = attachments + attachment
                    dirty = true
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    failures += attachmentFailure(
                        host,
                        name,
                        host.readableError(
                            failure,
                            fallbackZh = "无法添加附件，请重试。",
                            fallbackEn = "Couldn't add the attachment. Try again.",
                        ),
                    )
                }
            }
            importing = false
            if (failures.isNotEmpty()) error = failures.distinct().take(3).joinToString("\n")
        }
    }

    val draftSnapshot = rememberUpdatedState(buildDraft)
    DisposableEffect(Unit) {
        onDispose {
            if (finishedState.value || !dirtyState.value) return@onDispose
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
                if (attachments.isNotEmpty() || importing) {
                    Spacer(Modifier.height(8.dp))
                    if (importing) {
                        Row(
                            modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            DdSpinner(size = 14.dp, strokeWidth = 1.5.dp, color = colors.inkTertiary)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = host.t("正在添加附件", "Adding attachment"),
                                style = DdType.bodySmall,
                                color = colors.inkSecondary,
                            )
                        }
                    }
                    if (attachments.isNotEmpty()) {
                        DdGroupCard {
                            attachments.forEachIndexed { index, attachment ->
                                val image = isImageAttachment(attachment.mimeType, attachment.name)
                                DdFileRow(
                                    name = attachment.name,
                                    subtitle = formatFileSize(attachment.sizeBytes).takeIf { it.isNotBlank() },
                                    icon = if (image) DdIcons.Image else DdIcons.Paperclip,
                                    trailing = {
                                        if (image) {
                                            DdPressable(
                                                onClick = { viewerPath = attachment.path },
                                                shape = DdShapes.Medium,
                                                contentDescription = host.t("预览图片", "Preview image"),
                                            ) {
                                                DdImage(
                                                    path = attachment.path,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(34.dp),
                                                )
                                            }
                                            Spacer(Modifier.width(4.dp))
                                        }
                                        DdIconButton(
                                            icon = DdIcons.Close,
                                            onClick = { removeAttachment(attachment) },
                                            contentDescription = host.t("移除附件", "Remove attachment"),
                                            size = 36.dp,
                                            iconSize = 15.dp,
                                            tint = if (canEditAttachments) {
                                                colors.inkTertiary
                                            } else {
                                                colors.inkTertiary.copy(alpha = 0.35f)
                                            },
                                            enabled = canEditAttachments,
                                        )
                                    },
                                )
                                if (index < attachments.lastIndex) DdRowDivider(leadingInset = 36.dp)
                            }
                        }
                        Text(
                            text = host.tf(
                                "共 %s，上限 %s",
                                "Total %s · limit %s",
                                formatFileSize(totalAttachmentBytes),
                                AttachmentTotalLimit,
                            ),
                            modifier = Modifier.padding(start = 16.dp, top = 6.dp),
                            style = DdType.chip,
                            color = colors.inkTertiary,
                        )
                    }
                }
                error?.let { message ->
                    Spacer(Modifier.height(12.dp))
                    Text(text = message, style = DdType.bodySmall, color = colors.danger)
                }
                Spacer(Modifier.height(16.dp))
            }
            DdHairline()
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 20.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DdIconButton(
                    icon = DdIcons.Paperclip,
                    onClick = { pickAttachments() },
                    contentDescription = host.t("添加附件", "Add attachment"),
                    tint = if (canEditAttachments) {
                        colors.inkSecondary
                    } else {
                        colors.inkTertiary.copy(alpha = 0.35f)
                    },
                    enabled = canEditAttachments,
                )
                Spacer(Modifier.weight(1f))
                DdPrimaryButton(
                    text = if (sending) {
                        host.t("发送中…", "Sending…")
                    } else {
                        host.t("发送", "Send")
                    },
                    onClick = { requestSend() },
                    enabled = !sending && !importing,
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

    viewerPath?.let { path ->
        DdImageViewer(
            path = path,
            onDismiss = { viewerPath = null },
            contentDescription = host.t("图片附件", "Image attachment"),
        )
    }
}
