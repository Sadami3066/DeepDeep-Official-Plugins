package io.github.whywood.deepdeep.plugin.mail.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.whywood.deepdeep.design.DdAlpha
import io.github.whywood.deepdeep.design.DdExpandToggle
import io.github.whywood.deepdeep.design.DdIcon
import io.github.whywood.deepdeep.design.DdIcons
import io.github.whywood.deepdeep.design.DdPressable
import io.github.whywood.deepdeep.design.DdSpacing
import io.github.whywood.deepdeep.design.DdText
import io.github.whywood.deepdeep.design.DdType
import io.github.whywood.deepdeep.design.deepDeepColors
import io.github.whywood.deepdeep.plugin.api.CustomMessageSlotState
import io.github.whywood.deepdeep.plugin.api.PluginHost
import io.github.whywood.deepdeep.plugin.mail.MailFolderRole
import io.github.whywood.deepdeep.plugin.mail.tools.MailReadCardPayload
import io.github.whywood.deepdeep.plugin.mail.tools.MailSearchCardItem
import io.github.whywood.deepdeep.plugin.mail.tools.MailSearchCardPayload
import io.github.whywood.deepdeep.plugin.mail.tools.mailFolderLabel

private const val SearchCollapsedRows = 5
private const val ReadBodyCollapsedLines = 4

@Composable
internal fun MailSearchChatCard(host: PluginHost, state: CustomMessageSlotState) {
    val payload = remember(state.payload) { state.decodeCardPayload(MailSearchCardPayload.serializer()) }
    if (payload == null) {
        MailUnavailableCard(
            host = host,
            title = host.t("邮件暂时无法显示", "Mail is unavailable here"),
            hint = host.t("可以打开邮箱查看刚才的邮件。", "Open Mail to see the messages."),
            onOpenMail = { host.openMailPage() },
        )
        return
    }
    val now = System.currentTimeMillis()
    var expanded by remember(payload) { mutableStateOf(false) }
    MailCardSurface {
        SearchHead(host = host, payload = payload)
        if (payload.items.isEmpty()) {
            SearchEmptyBody(host = host, payload = payload)
        } else {
            val visible = if (expanded) payload.items else payload.items.take(SearchCollapsedRows)
            visible.forEachIndexed { index, item ->
                if (index > 0) MailDashedDivider(Modifier.padding(horizontal = DdSpacing.Lg))
                SearchEnvelopeRow(
                    host = host,
                    item = item,
                    now = now,
                    onClick = { host.openMailThreadSheet(item.accountId, item.messageId, item.threadId) },
                )
            }
            if (payload.items.size > SearchCollapsedRows) {
                DdExpandToggle(
                    expanded = expanded,
                    collapsedLabel = host.tf(
                        "展开其余 %d 封",
                        "Show %d more",
                        payload.items.size - SearchCollapsedRows,
                    ),
                    expandedLabel = host.t("收起", "Collapse"),
                    onToggle = { expanded = !expanded },
                    modifier = Modifier.padding(horizontal = DdSpacing.Lg),
                )
            }
            MailCardActionRow(
                actionLabel = host.t("打开邮箱", "Open Mail"),
                onAction = { host.openMailPage() },
                note = if (payload.items.size < payload.total) {
                    host.tf("本页 %d 封，共 %d 封", "%d on this page, %d total", payload.items.size, payload.total)
                } else {
                    host.tf("共 %d 封", "%d messages", payload.total)
                },
            )
        }
    }
}

@Composable
private fun SearchHead(host: PluginHost, payload: MailSearchCardPayload) {
    val colors = deepDeepColors()
    val query = payload.query.trim()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = DdSpacing.Lg, end = DdSpacing.Lg, top = 14.dp, bottom = DdSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            DdText(
                text = if (query.isEmpty()) {
                    host.t("最近邮件", "Recent mail")
                } else {
                    host.tf("「%s」", "“%s”", query)
                },
                style = DdType.body.copy(fontWeight = FontWeight.SemiBold),
                color = colors.ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (payload.items.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                SearchContextChipRow(host = host, payload = payload)
            }
        }
        if (payload.total > 0) {
            Spacer(Modifier.width(DdSpacing.Sm))
            DdText(
                text = host.tf("共 %d 封", "%d messages", payload.total),
                style = DdType.chip.copy(fontSize = 11.sp),
                color = colors.inkTertiary,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun SearchContextChipRow(host: PluginHost, payload: MailSearchCardPayload) {
    val folder = MailFolderRole.entries.firstOrNull { it.name.equals(payload.folder, ignoreCase = true) }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (folder != null) MailCardChip(text = mailFolderLabel(host, folder), accent = true)
        if (payload.unreadOnly) MailCardChip(text = host.t("仅未读", "Unread only"), accent = true)
        MailCardChip(text = payload.account.trim().ifEmpty { host.t("全部账户", "All accounts") })
    }
}

@Composable
private fun SearchEnvelopeRow(
    host: PluginHost,
    item: MailSearchCardItem,
    now: Long,
    onClick: () -> Unit,
) {
    val colors = deepDeepColors()
    val subject = displaySubject(item.subject, host.t("（无主题）", "(No subject)"))
    val outgoing = item.sent
    val recipients = item.to.joinToString(", ").trim()
    val sender = item.from.trim()
    val who = if (outgoing) {
        recipients.ifEmpty { host.t("（无收件人）", "(No recipient)") }
    } else {
        sender.ifEmpty { host.t("未知发件人", "Unknown sender") }
    }
    val accountColor = Color(item.accountColor)
    val accent = if (accountColor.alpha > 0f) accountColor else colors.accent
    val unread = item.unread && !outgoing
    DdPressable(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RectangleShape,
        contentDescription = "$who $subject",
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 60.dp)
                .padding(start = DdSpacing.Lg, end = DdSpacing.Md, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(30.dp), contentAlignment = Alignment.Center) {
                if (outgoing) {
                    DdIcon(icon = DdIcons.ArrowUp, tint = colors.inkTertiary, size = 20.dp)
                } else {
                    MailEnvelopeGlyph(
                        tint = if (unread) accent else colors.inkTertiary,
                        modifier = Modifier.size(24.dp),
                    )
                }
                if (unread) {
                    MailUnreadDot(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .offset(x = 2.dp, y = 3.dp),
                        color = accent,
                        size = 6.dp,
                    )
                }
            }
            Spacer(Modifier.width(DdSpacing.Sm))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DdText(
                        text = who,
                        modifier = Modifier.weight(1f),
                        style = DdType.bodySmall.copy(
                            fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Normal,
                        ),
                        color = colors.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (item.dateMillis > 0L) {
                        Spacer(Modifier.width(DdSpacing.Sm))
                        DdText(
                            text = mailListTime(item.dateMillis, now, host.chinese),
                            style = DdType.chip.copy(fontSize = 11.sp),
                            color = colors.inkTertiary,
                            maxLines = 1,
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DdText(
                        text = subject,
                        modifier = Modifier.weight(1f),
                        style = DdType.bodySmall,
                        color = if (unread) colors.ink else colors.inkSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (outgoing) {
                        Spacer(Modifier.width(6.dp))
                        MailCardChip(text = host.t("已发送", "Sent"))
                    }
                    if (item.hasAttachments) {
                        Spacer(Modifier.width(6.dp))
                        DdIcon(icon = DdIcons.Paperclip, tint = colors.inkTertiary, size = 11.dp)
                    }
                }
            }
            Spacer(Modifier.width(DdSpacing.Xs))
            DdIcon(
                icon = DdIcons.ChevronRight,
                tint = colors.inkTertiary.copy(alpha = DdAlpha.Muted),
                size = 14.dp,
            )
        }
    }
}

@Composable
private fun SearchEmptyBody(host: PluginHost, payload: MailSearchCardPayload) {
    val colors = deepDeepColors()
    val moreMatches = payload.total > 0
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = DdSpacing.Xl, end = DdSpacing.Xl, top = 18.dp, bottom = DdSpacing.Sm),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        MailEmptyEnvelopeGlyph()
        Spacer(Modifier.height(DdSpacing.Md))
        DdText(
            text = if (moreMatches) {
                host.t("这一页没有邮件", "No mail on this page")
            } else {
                host.t("没有找到邮件", "No mail found")
            },
            style = DdType.body.copy(fontWeight = FontWeight.SemiBold),
            color = colors.ink,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(DdSpacing.Xs))
        DdText(
            text = when {
                moreMatches -> host.tf(
                    "共 %d 封，可以减小 offset 或换个关键词。",
                    "%d messages matched. Lower the offset or try another keyword.",
                    payload.total,
                )
                payload.query.isBlank() -> host.t("邮箱里暂时没有可显示的邮件。", "There's no mail to show right now.")
                else -> host.t("换一个关键词，或取消未读筛选后再试。", "Try another keyword, or search without the unread filter.")
            },
            style = DdType.bodySmall,
            color = colors.inkSecondary,
            textAlign = TextAlign.Center,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(DdSpacing.Sm))
        SearchContextChipRow(host = host, payload = payload)
    }
    MailCardActionRow(
        actionLabel = host.t("打开邮箱", "Open Mail"),
        onAction = { host.openMailPage() },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MailReadChatCard(host: PluginHost, state: CustomMessageSlotState) {
    val payload = remember(state.payload) { state.decodeCardPayload(MailReadCardPayload.serializer()) }
    if (payload == null || payload.messageId.isBlank()) {
        MailUnavailableCard(
            host = host,
            title = host.t("这封信暂时打不开", "This message can't be opened here"),
            hint = host.t("可能已从邮箱里删除或移动，可以到邮箱里再找一次。", "It may have been moved or deleted. Open Mail to check."),
            onOpenMail = { host.openMailPage() },
        )
        return
    }
    val colors = deepDeepColors()
    var bodyExpanded by remember(payload) { mutableStateOf(false) }
    val subject = displaySubject(payload.subject, host.t("（无主题）", "(No subject)"))
    val outgoing = payload.folderRole.equals("sent", ignoreCase = true)
    val recipients = payload.to.joinToString(", ").trim()
    val senderName = payload.fromName.trim().ifEmpty { payload.fromAddress.trim() }
    val displayName = if (outgoing) {
        recipients.ifEmpty { host.t("（无收件人）", "(No recipient)") }
    } else {
        senderName.ifEmpty { host.t("未知发件人", "Unknown sender") }
    }
    val address = if (outgoing) "" else payload.fromAddress.trim()
    val body = payload.body.ifBlank { payload.preview }.trim()
    val collapsible = body.length > 320 || body.count { it == '\n' } >= ReadBodyCollapsedLines
    MailCardSurface {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = DdSpacing.Lg, end = DdSpacing.Lg, top = DdSpacing.Lg, bottom = DdSpacing.Md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SenderAvatar(
                host = host,
                address = address.ifEmpty { recipients },
                name = displayName,
                size = 40.dp,
            )
            Spacer(Modifier.width(DdSpacing.Md))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DdText(
                        text = displayName,
                        modifier = Modifier.weight(1f),
                        style = DdType.body.copy(fontWeight = FontWeight.SemiBold),
                        color = colors.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (outgoing) {
                        Spacer(Modifier.width(6.dp))
                        MailCardChip(text = host.t("已发送", "Sent"))
                    }
                }
                if (address.isNotEmpty() && !address.equals(displayName, ignoreCase = true)) {
                    Spacer(Modifier.height(DdSpacing.Xxs))
                    DdText(
                        text = address,
                        style = DdType.chip.copy(fontSize = 11.sp),
                        color = colors.inkTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(DdSpacing.Sm))
            Column(horizontalAlignment = Alignment.End) {
                if (payload.dateMillis > 0L) {
                    DdText(
                        text = mailFullTime(payload.dateMillis, host.chinese),
                        style = DdType.chip.copy(fontSize = 11.sp),
                        color = colors.inkTertiary,
                        maxLines = 1,
                    )
                }
                if (payload.unread) {
                    Spacer(Modifier.height(DdSpacing.Xs))
                    MailUnreadDot(color = colors.accent, size = 6.dp)
                }
            }
        }
        MailLetterRules(Modifier.padding(horizontal = DdSpacing.Lg))
        DdText(
            text = subject,
            modifier = Modifier.padding(start = DdSpacing.Lg, end = DdSpacing.Lg, top = DdSpacing.Md),
            style = DdType.body.copy(fontWeight = FontWeight.SemiBold),
            color = colors.ink,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        if (body.isNotEmpty()) {
            DdText(
                text = body,
                modifier = Modifier.padding(start = DdSpacing.Lg, end = DdSpacing.Lg, top = DdSpacing.Sm),
                style = DdType.bodySmall.copy(lineHeight = 22.sp),
                color = colors.inkSecondary,
                maxLines = if (!collapsible || bodyExpanded) Int.MAX_VALUE else ReadBodyCollapsedLines,
                overflow = TextOverflow.Ellipsis,
            )
            if (collapsible) {
                DdExpandToggle(
                    expanded = bodyExpanded,
                    collapsedLabel = host.t("展开全文", "Show full text"),
                    expandedLabel = host.t("收起", "Collapse"),
                    onToggle = { bodyExpanded = !bodyExpanded },
                    modifier = Modifier.padding(horizontal = DdSpacing.Lg),
                )
            }
        } else {
            DdText(
                text = host.t("（正文为空）", "(No body text)"),
                modifier = Modifier.padding(start = DdSpacing.Lg, end = DdSpacing.Lg, top = DdSpacing.Sm),
                style = DdType.bodySmall,
                color = colors.inkTertiary,
                maxLines = 1,
            )
        }
        if (payload.textTruncated) {
            DdText(
                text = host.t("正文超过大小上限，已截断。", "The body exceeded the size limit and was truncated."),
                modifier = Modifier.padding(start = DdSpacing.Lg, end = DdSpacing.Lg, top = DdSpacing.Xs),
                style = DdType.chip.copy(fontSize = 11.sp),
                color = colors.inkTertiary,
                maxLines = 2,
            )
        }
        if (payload.attachments.isNotEmpty()) {
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = DdSpacing.Lg, end = DdSpacing.Lg, top = DdSpacing.Md),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                payload.attachments.forEach { attachment ->
                    MailAttachmentPill(
                        name = attachment.name,
                        sizeText = formatFileSize(attachment.sizeBytes).ifBlank { null },
                    )
                }
            }
        }
        Spacer(Modifier.height(DdSpacing.Sm))
        MailCardActionRow(
            actionLabel = host.t("打开邮件", "Open message"),
            onAction = {
                host.openMailThreadSheet(payload.accountId, payload.messageId, payload.threadId)
            },
            note = if (payload.attachments.isEmpty()) {
                null
            } else {
                host.tf("附件 %d 个", "%d attachments", payload.attachments.size)
            },
        )
    }
}
