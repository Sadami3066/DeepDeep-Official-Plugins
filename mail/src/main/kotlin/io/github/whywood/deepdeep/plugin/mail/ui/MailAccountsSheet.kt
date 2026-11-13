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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.whywood.deepdeep.design.DdDialog
import io.github.whywood.deepdeep.design.DdGroupCard
import io.github.whywood.deepdeep.design.DdIconButton
import io.github.whywood.deepdeep.design.DdIcons
import io.github.whywood.deepdeep.design.DdListRow
import io.github.whywood.deepdeep.design.DdLoadingBlock
import io.github.whywood.deepdeep.design.DdPrimaryButton
import io.github.whywood.deepdeep.design.DdRowDivider
import io.github.whywood.deepdeep.design.DdSectionLabel
import io.github.whywood.deepdeep.design.DdSheet
import io.github.whywood.deepdeep.design.DdSwitch
import io.github.whywood.deepdeep.design.DdTextAction
import io.github.whywood.deepdeep.design.DdTextField
import io.github.whywood.deepdeep.design.DdType
import io.github.whywood.deepdeep.design.deepDeepColors
import io.github.whywood.deepdeep.plugin.api.PluginHost
import io.github.whywood.deepdeep.plugin.mail.MailAccount
import io.github.whywood.deepdeep.plugin.mail.MailAccountUpdate
import io.github.whywood.deepdeep.plugin.mail.MailConnectionTest
import io.github.whywood.deepdeep.plugin.mail.MailDraft
import io.github.whywood.deepdeep.plugin.mail.MailRepository
import io.github.whywood.deepdeep.plugin.mail.MailState
import io.github.whywood.deepdeep.plugin.mail.ProviderCatalog
import kotlinx.coroutines.launch

@Composable
internal fun MailAccountsSheet(
    host: PluginHost,
    repository: MailRepository,
    startAdding: Boolean,
    onDismiss: () -> Unit,
    onCompose: (MailComposeRequest) -> Unit,
) {
    val state by repository.state.collectAsState()
    val colors = deepDeepColors()
    val scope = rememberCoroutineScope()
    var adding by rememberSaveable { mutableStateOf(startAdding) }
    var removeTarget by remember { mutableStateOf<MailAccount?>(null) }
    var reauthTarget by remember { mutableStateOf<MailAccount?>(null) }
    var actionError by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        scope.launch { runCatching { repository.refresh() } }
    }

    fun updateNotify(account: MailAccount, notify: Boolean) {
        scope.launch {
            runCatching { repository.updateAccount(account.id, MailAccountUpdate(notify = notify)) }
                .onFailure {
                    actionError = host.readableError(
                        it,
                        fallbackZh = "保存失败，请重试",
                        fallbackEn = "Couldn't save the change. Try again.",
                    )
                }
        }
    }

    fun deleteDraft(draft: MailDraft) {
        scope.launch {
            runCatching { repository.deleteDraft(draft.id) }
                .onFailure {
                    actionError = host.readableError(
                        it,
                        fallbackZh = "删除草稿失败，请重试",
                        fallbackEn = "Couldn't delete the draft. Try again.",
                    )
                }
        }
    }

    DdSheet(onDismiss = onDismiss) {
        if (adding) {
            Box(Modifier.fillMaxWidth().fillMaxHeight(0.94f)) {
                MailAddAccountFlow(
                    host = host,
                    repository = repository,
                    onClose = onDismiss,
                    onExit = { adding = false },
                    onAdded = {
                        adding = false
                        refresh()
                    },
                )
            }
        } else {
            Column(Modifier.fillMaxWidth().fillMaxHeight(0.94f)) {
                MailSheetHeader(
                    host = host,
                    title = host.t("账户", "Accounts"),
                    onClose = onDismiss,
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                ) {
                    val syncError = state.syncError
                    if (syncError != null) {
                        Box(Modifier.padding(horizontal = 18.dp, vertical = 6.dp)) {
                            MailNoticeBar(
                                text = displayPreview(syncError, limit = 110),
                                tone = colors.danger,
                                actionLabel = host.t("重试", "Retry"),
                                actionDescription = host.t("重新同步", "Sync again"),
                                onAction = { refresh() },
                            )
                        }
                    }
                    actionError?.let { message ->
                        Box(Modifier.padding(horizontal = 18.dp, vertical = 6.dp)) {
                            MailNoticeBar(text = message, tone = colors.danger)
                        }
                    }
                    if (state.accounts.isEmpty()) {
                        Column(
                            Modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                text = host.t(
                                    "还没有连接邮箱。",
                                    "No mailbox is connected yet.",
                                ),
                                style = DdType.bodySmall,
                                color = colors.inkTertiary,
                            )
                            Spacer(Modifier.height(16.dp))
                            DdPrimaryButton(
                                text = host.t("添加邮箱", "Add mailbox"),
                                onClick = { adding = true },
                            )
                        }
                    } else {
                        DdSectionLabel(
                            text = host.t("账户", "Accounts"),
                            modifier = Modifier.padding(start = 18.dp, top = 16.dp, bottom = 8.dp),
                        )
                        DdGroupCard {
                            state.accounts.forEachIndexed { index, account ->
                                AccountItem(
                                    host = host,
                                    account = account,
                                    syncLabel = syncLabel(host, state, account),
                                    accountError = state.accountErrors[account.id],
                                    onNotifyChange = { notify -> updateNotify(account, notify) },
                                    onReauthorize = { reauthTarget = account },
                                    onRemove = { removeTarget = account },
                                )
                                if (index < state.accounts.lastIndex) {
                                    DdRowDivider(leadingInset = 18.dp)
                                }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        DdGroupCard {
                            DdListRow(
                                title = host.t("添加邮箱", "Add mailbox"),
                                leadingIcon = DdIcons.Plus,
                                onClick = { adding = true },
                                showChevron = true,
                            )
                        }
                    }
                    val drafts = state.drafts.sortedByDescending { it.updatedAt }
                    if (drafts.isNotEmpty()) {
                        DdSectionLabel(
                            text = host.t("草稿", "Drafts"),
                            modifier = Modifier.padding(start = 18.dp, top = 24.dp, bottom = 8.dp),
                        )
                        DdGroupCard {
                            drafts.forEachIndexed { index, draft ->
                                DraftItem(
                                    host = host,
                                    draft = draft,
                                    onOpen = {
                                        onCompose(
                                            MailComposeRequest(
                                                accountId = draft.accountId,
                                                to = draft.to,
                                                cc = draft.cc,
                                                subject = draft.subject,
                                                body = draft.body,
                                                draft = draft,
                                            ),
                                        )
                                    },
                                    onDelete = { deleteDraft(draft) },
                                )
                                if (index < drafts.lastIndex) {
                                    DdRowDivider(leadingInset = 18.dp)
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(28.dp))
                }
            }
        }
    }

    reauthTarget?.let { account ->
        MailReauthorizeDialog(
            host = host,
            repository = repository,
            account = account,
            onDismiss = { reauthTarget = null },
        )
    }

    removeTarget?.let { account ->
        MailConfirmDialog(
            host = host,
            title = host.t("移除账户？", "Remove account?"),
            message = host.tf(
                "将删除 %s 的本地缓存和授权信息，之后可以重新添加。",
                "This deletes cached mail and the sign-in for %s. You can add it again later.",
                account.email,
            ),
            confirmLabel = host.t("移除", "Remove"),
            destructive = true,
            onConfirm = {
                removeTarget = null
                scope.launch {
                    runCatching { repository.removeAccount(account.id) }
                        .onFailure {
                            actionError = host.readableError(
                                it,
                                fallbackZh = "移除账户失败，请重试",
                                fallbackEn = "Couldn't remove the account. Try again.",
                            )
                        }
                }
            },
            onDismiss = { removeTarget = null },
        )
    }
}

@Composable
private fun AccountItem(
    host: PluginHost,
    account: MailAccount,
    syncLabel: String,
    accountError: String?,
    onNotifyChange: (Boolean) -> Unit,
    onReauthorize: () -> Unit,
    onRemove: () -> Unit,
) {
    val colors = deepDeepColors()
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MailAvatar(
                initials = mailInitial(account.displayName, account.email),
                color = Color(account.color),
                size = 40.dp,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = account.email,
                    style = DdType.body,
                    color = colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = account.displayName.ifBlank {
                        host.providerNameById(account.providerId)
                    },
                    style = DdType.bodySmall,
                    color = colors.inkTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        DdListRow(
            title = host.t("新邮件通知", "New mail notifications"),
            trailing = {
                DdSwitch(
                    checked = account.notify,
                    onCheckedChange = onNotifyChange,
                    modifier = Modifier.padding(start = 12.dp),
                )
            },
        )
        DdListRow(
            title = host.t("同步", "Sync"),
            value = syncLabel,
        )
        if (accountError != null) {
            Text(
                text = accountError,
                modifier = Modifier.padding(start = 18.dp, end = 18.dp, bottom = 6.dp),
                style = DdType.bodySmall,
                color = colors.danger,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(end = 12.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (accountError != null) {
                DdTextAction(
                    text = host.t("重新授权", "Sign in again"),
                    onClick = onReauthorize,
                    color = colors.accent,
                )
            }
            DdTextAction(
                text = host.t("移除账户", "Remove account"),
                onClick = onRemove,
                color = colors.danger,
            )
        }
    }
}

@Composable
private fun DraftItem(
    host: PluginHost,
    draft: MailDraft,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = deepDeepColors()
    val recipients = draft.to.ifEmpty { draft.cc }
    DdListRow(
        title = displaySubject(draft.subject, host.t("（无主题）", "(No subject)")),
        subtitle = listOfNotNull(
            recipients.takeIf { it.isNotEmpty() }?.joinToString(", "),
            mailFullTime(draft.updatedAt, host.chinese),
        ).joinToString(" · "),
        leadingIcon = DdIcons.Pen,
        onClick = onOpen,
        trailing = {
            DdIconButton(
                icon = DdIcons.Trash,
                onClick = onDelete,
                contentDescription = host.t("删除草稿", "Delete draft"),
                size = 36.dp,
                iconSize = 16.dp,
                tint = colors.inkTertiary,
            )
        },
    )
}

private fun syncLabel(
    host: PluginHost,
    state: MailState,
    account: MailAccount,
): String {
    val lastSync = state.lastSyncByAccount[account.id]
    val hasError = state.accountErrors.containsKey(account.id)
    return when {
        state.syncing -> host.t("正在同步…", "Syncing…")
        hasError -> host.t("同步失败", "Sync failed")
        lastSync != null -> host.tf(
            "上次同步 %s",
            "Last synced %s",
            mailLastSyncTime(lastSync, host.chinese),
        )

        else -> host.t("尚未同步", "Not synced yet")
    }
}

private fun PluginHost.providerNameById(providerId: String): String {
    val preset = ProviderCatalog.byId(providerId)
    return providerName(preset)
}

@Composable
private fun MailReauthorizeDialog(
    host: PluginHost,
    repository: MailRepository,
    account: MailAccount,
    onDismiss: () -> Unit,
) {
    val colors = deepDeepColors()
    val scope = rememberCoroutineScope()
    var password by rememberSaveable(account.id) { mutableStateOf("") }
    var visible by rememberSaveable(account.id) { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun submit() {
        if (busy || password.isBlank()) return
        busy = true
        error = null
        scope.launch {
            val result = runCatching { repository.reauthorize(account.id, password) }.fold(
                onSuccess = { it },
                onFailure = {
                    MailConnectionTest(
                        imapOk = false,
                        smtpOk = false,
                        imapError = host.readableError(
                            it,
                            fallbackZh = "连接失败，请检查授权码",
                            fallbackEn = "Couldn't sign in. Check the password.",
                        ),
                    )
                },
            )
            busy = false
            if (result.ok) {
                host.ui().toast(host.t("已重新连接", "Signed in again"))
                onDismiss()
            } else {
                val reason = listOfNotNull(result.imapError, result.smtpError)
                    .firstOrNull { it.isNotBlank() }
                    ?: host.t("连接失败，请检查授权码。", "Couldn't sign in. Check the password.")
                error = reason
            }
        }
    }
    DdDialog(
        onDismissRequest = onDismiss,
        title = host.t("重新授权", "Sign in again"),
        content = {
            Text(
                text = host.tf(
                    "为 %s 输入新的授权码或应用专用密码。",
                    "Enter a new authorization code or app password for %s.",
                    account.email,
                ),
                style = DdType.bodySmall,
                color = colors.inkSecondary,
            )
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                DdTextField(
                    value = password,
                    onValueChange = {
                        password = it
                        error = null
                    },
                    placeholder = host.t("授权码或应用专用密码", "Authorization code or app password"),
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    visualTransformation = if (visible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                )
                DdIconButton(
                    icon = DdIcons.Eye,
                    onClick = { visible = !visible },
                    contentDescription = if (visible) {
                        host.t("隐藏密码", "Hide password")
                    } else {
                        host.t("显示密码", "Show password")
                    },
                    tint = if (visible) colors.accent else colors.inkTertiary,
                    iconSize = 19.dp,
                )
            }
            error?.let { message ->
                Spacer(Modifier.height(10.dp))
                Text(text = message, style = DdType.bodySmall, color = colors.danger)
            }
            if (busy) {
                Spacer(Modifier.height(12.dp))
                DdLoadingBlock(text = host.t("正在连接", "Connecting"))
            }
        },
        actions = {
            DdTextAction(text = host.t("取消", "Cancel"), onClick = onDismiss, color = colors.inkSecondary)
            DdTextAction(
                text = host.t("连接", "Connect"),
                onClick = { submit() },
                color = colors.accent,
                enabled = !busy && password.isNotBlank(),
            )
        },
    )
}
