package io.github.whywood.deepdeep.plugin.mail.ui

import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.whywood.deepdeep.design.DdAlpha
import io.github.whywood.deepdeep.design.DdHairline
import io.github.whywood.deepdeep.design.DdIcon
import io.github.whywood.deepdeep.design.DdIcons
import io.github.whywood.deepdeep.design.DdPressable
import io.github.whywood.deepdeep.design.DdRowDivider
import io.github.whywood.deepdeep.design.DdSpacing
import io.github.whywood.deepdeep.design.DdType
import io.github.whywood.deepdeep.design.deepDeepColors
import io.github.whywood.deepdeep.plugin.api.CustomMessageSlotState
import io.github.whywood.deepdeep.plugin.api.PluginHost
import io.github.whywood.deepdeep.plugin.mail.tools.MailReadCardPayload
import io.github.whywood.deepdeep.plugin.mail.tools.MailSearchCardItem
import io.github.whywood.deepdeep.plugin.mail.tools.MailSearchCardPayload

@Composable
internal fun MailSearchChatCard(host: PluginHost, state: CustomMessageSlotState) {
    val payload = remember(state.payload) { state.decodeCardPayload(MailSearchCardPayload.serializer()) }
    if (payload == null) {
        MailChatUnavailableCard(host, host.t("邮件", "Mail"))
        return
    }
    val colors = deepDeepColors()
    val now = System.currentTimeMillis()
    val empty = payload.items.isEmpty()
    MailChatCardSurface {
        MailChatCardHeader(
            icon = DdIcons.Search,
            title = if (empty) {
                host.t("没有找到邮件", "No mail found")
            } else {
                host.tf("找到 %d 封邮件", "Found %d messages", payload.total)
            },
            bottomPadding = DdSpacing.Xs,
        )
        Text(
            text = searchContextLabel(host, payload),
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = DdSpacing.Lg, end = DdSpacing.Lg, bottom = DdSpacing.Md),
            style = DdType.chip,
            color = colors.inkTertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        DdHairline()
        if (!empty) {
            payload.items.forEachIndexed { index, item ->
                if (index > 0) DdRowDivider(leadingInset = 32.dp)
                MailSearchResultRow(host = host, item = item, now = now) {
                    host.openMailThreadSheet(item.accountId, item.messageId, item.threadId)
                }
            }
            DdHairline()
        }
        MailChatCardFooter(
            actionLabel = host.t("打开邮箱", "Open Mail"),
            onAction = { host.openMailPage() },
            note = if (payload.total > payload.items.size) {
                host.tf("还有 %d 封", "%d more", payload.total - payload.items.size)
            } else {
                null
            },
        )
    }
}

@Composable
private fun MailSearchResultRow(
    host: PluginHost,
    item: MailSearchCardItem,
    now: Long,
    onClick: () -> Unit,
) {
    val colors = deepDeepColors()
    val subject = displaySubject(item.subject, host.t("（无主题）", "(No subject)"))
    val sender = item.from.ifBlank { host.t("未知发件人", "Unknown sender") }
    val accountColor = Color(item.accountColor)
    val unreadColor = if (accountColor.alpha > 0f) accountColor else colors.accent
    DdPressable(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RectangleShape,
        contentDescription = "$subject $sender",
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 54.dp)
                .padding(start = DdSpacing.Lg, end = DdSpacing.Md, top = DdSpacing.Sm, bottom = DdSpacing.Sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(8.dp), contentAlignment = Alignment.CenterStart) {
                if (item.unread) MailUnreadDot(size = 6.dp, color = unreadColor)
            }
            Spacer(Modifier.width(DdSpacing.Sm))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = subject,
                        modifier = Modifier.weight(1f),
                        style = DdType.bodySmall.copy(
                            fontWeight = if (item.unread) FontWeight.SemiBold else FontWeight.Normal,
                        ),
                        color = colors.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (item.dateMillis > 0L) {
                        Spacer(Modifier.width(DdSpacing.Sm))
                        Text(
                            text = mailListTime(item.dateMillis, now, host.chinese),
                            style = DdType.chip,
                            color = colors.inkTertiary,
                            maxLines = 1,
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = sender,
                        modifier = Modifier.weight(1f),
                        style = DdType.chip,
                        color = colors.inkSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (item.hasAttachments) {
                        Spacer(Modifier.width(6.dp))
                        DdIcon(
                            icon = DdIcons.Paperclip,
                            tint = colors.inkTertiary,
                            size = 12.dp,
                        )
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
internal fun MailReadChatCard(host: PluginHost, state: CustomMessageSlotState) {
    val payload = remember(state.payload) { state.decodeCardPayload(MailReadCardPayload.serializer()) }
    if (payload == null || payload.messageId.isBlank()) {
        MailChatUnavailableCard(host, host.t("邮件", "Mail"))
        return
    }
    val colors = deepDeepColors()
    val now = System.currentTimeMillis()
    val subject = displaySubject(payload.subject, host.t("（无主题）", "(No subject)"))
    val preview = payload.preview.trim()
    val attachmentCount = payload.attachments.size
    MailChatCardSurface {
        MailChatCardHeader(
            icon = DdIcons.Message,
            title = host.t("邮件", "Mail"),
            trailing = if (payload.dateMillis > 0L) mailListTime(payload.dateMillis, now, host.chinese) else null,
        )
        Column(Modifier.fillMaxWidth().padding(horizontal = DdSpacing.Lg)) {
            Text(
                text = subject,
                style = DdType.body.copy(fontWeight = FontWeight.SemiBold),
                color = colors.ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(DdSpacing.Xs))
            Text(
                text = senderLabel(host, payload),
                style = DdType.chip,
                color = colors.inkSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(DdSpacing.Sm))
            Text(
                text = preview.ifEmpty { host.t("（正文为空）", "(No body text)") },
                style = DdType.bodySmall,
                color = if (preview.isEmpty()) colors.inkTertiary else colors.inkSecondary,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
            if (attachmentCount > 0) {
                Spacer(Modifier.height(DdSpacing.Sm))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DdIcon(icon = DdIcons.Paperclip, tint = colors.inkTertiary, size = 12.dp)
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = if (attachmentCount == 1) {
                            host.t("1 个附件", "1 attachment")
                        } else {
                            host.tf("%d 个附件", "%d attachments", attachmentCount)
                        },
                        style = DdType.chip,
                        color = colors.inkTertiary,
                        maxLines = 1,
                    )
                }
            }
            Spacer(Modifier.height(DdSpacing.Md))
        }
        DdHairline()
        MailChatCardFooter(
            actionLabel = host.t("打开邮件", "Open message"),
            onAction = {
                host.openMailThreadSheet(payload.accountId, payload.messageId, payload.threadId)
            },
        )
    }
}


private fun searchContextLabel(host: PluginHost, payload: MailSearchCardPayload): String {
    val query = payload.query.trim()
    val queryLabel = if (query.isEmpty()) {
        host.t("最近邮件", "Recent mail")
    } else {
        host.tf("关键词「%s」", "“%s”", displayPreview(query, 30))
    }
    val filters = buildList {
        if (payload.unreadOnly) add(host.t("未读", "Unread"))
        add(if (payload.account.isBlank()) host.t("全部账户", "All accounts") else payload.account)
    }
    return (listOf(queryLabel) + filters).joinToString(" · ")
}

private fun senderLabel(host: PluginHost, payload: MailReadCardPayload): String {
    val name = payload.fromName.trim()
    val address = payload.fromAddress.trim()
    return when {
        name.isEmpty() -> address
        address.isEmpty() || name.equals(address, ignoreCase = true) -> name
        else -> "$name · $address"
    }.ifEmpty { host.t("未知发件人", "Unknown sender") }
}
