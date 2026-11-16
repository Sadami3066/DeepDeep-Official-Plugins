package io.github.whywood.deepdeep.plugin.feishu

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.whywood.deepdeep.design.DdExpandToggle
import io.github.whywood.deepdeep.design.DdIcon
import io.github.whywood.deepdeep.design.DdIcons
import io.github.whywood.deepdeep.design.DdRowDivider
import io.github.whywood.deepdeep.design.DdSpacing
import io.github.whywood.deepdeep.design.DdText
import io.github.whywood.deepdeep.design.DdType
import io.github.whywood.deepdeep.design.deepDeepColors
import io.github.whywood.deepdeep.plugin.api.PluginHost

@Composable
internal fun FeishuMailListCard(host: PluginHost, model: FeishuCardModel) {
    FeishuCardShell {
        if (model.isEmpty) MailListEmpty(host) else MailListContent(host, model)
    }
}

@Composable
private fun MailListContent(host: PluginHost, model: FeishuCardModel) {
    val all = model.items
    val count = model.total.coerceAtLeast(all.size)
    var expanded by remember(model) { mutableStateOf(false) }
    val rows = if (expanded) all else all.take(FEISHU_MAIL_PREVIEW_ROWS)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = DdSpacing.Lg, end = DdSpacing.Lg, top = DdSpacing.Md, bottom = DdSpacing.Sm),
    ) {
        FeishuCardHead(
            host = host,
            title = model.title?.takeIf { it.isNotBlank() } ?: host.t("邮箱", "Mail"),
            detail = model.context?.takeIf { it.isNotBlank() },
            trailing = model.trailing?.takeIf { it.isNotBlank() }
                ?: if (count > 0) host.tf("%d 封", "%d mails", count) else null,
        )
        Spacer(Modifier.height(DdSpacing.Xs))
        rows.forEachIndexed { index, item ->
            if (index > 0) DdRowDivider(leadingInset = 30.dp)
            MailRow(host = host, item = item)
        }
        if (all.size > FEISHU_MAIL_PREVIEW_ROWS) {
            DdExpandToggle(
                expanded = expanded,
                collapsedLabel = host.tf(
                    "展开其余 %d 封",
                    "Show %d more mails",
                    all.size - FEISHU_MAIL_PREVIEW_ROWS,
                ),
                expandedLabel = host.t("收起邮件列表", "Show fewer mails"),
                onToggle = { expanded = !expanded },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun MailRow(host: PluginHost, item: FeishuCardItem) {
    val colors = deepDeepColors()
    val unread = item.badgeTokens().any { it == host.t("未读", "Unread") }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(vertical = DdSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(14.dp), contentAlignment = Alignment.CenterStart) {
            if (unread) {
                Box(
                    Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(colors.accent),
                )
            }
        }
        Spacer(Modifier.width(DdSpacing.Sm))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DdText(
                    text = item.title,
                    modifier = Modifier.weight(1f),
                    style = feishuItemTitleStyle(),
                    color = colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                item.meta?.takeIf { it.isNotBlank() }?.let { meta ->
                    Spacer(Modifier.width(DdSpacing.Sm))
                    DdText(
                        text = meta,
                        style = DdType.numeric,
                        color = colors.inkTertiary,
                        maxLines = 1,
                    )
                }
            }
            item.subtitle?.takeIf { it.isNotBlank() }?.let { subtitle ->
                Spacer(Modifier.height(2.dp))
                DdText(
                    text = subtitle,
                    style = DdType.chip,
                    color = colors.inkSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun MailListEmpty(host: PluginHost) {
    FeishuEmptyBody(
        title = host.t("这里没有邮件", "No mail here"),
        message = host.t("换个文件夹、关键词或时间范围试试", "Try another folder, keyword or time range"),
    ) {
        DdIcon(icon = DdIcons.Message, tint = deepDeepColors().inkTertiary, size = 24.dp)
    }
}

@Composable
internal fun FeishuMailReadCard(host: PluginHost, model: FeishuCardModel) {
    FeishuCardShell {
        if (model.isEmpty) MailReadEmpty(host) else MailReadContent(host, model)
    }
}

@Composable
private fun MailReadContent(host: PluginHost, model: FeishuCardModel) {
    val colors = deepDeepColors()
    val item = model.items.firstOrNull()
    val subject = model.context?.takeIf { it.isNotBlank() } ?: host.t("邮件", "Mail")
    val body = item?.body?.takeIf { it.isNotBlank() }
    val long = body != null && (body.lineSequence().count() > 8 || body.length > 320)
    var expanded by remember(model) { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = DdSpacing.Lg, vertical = DdSpacing.Lg),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DdIcon(icon = DdIcons.Message, tint = colors.accent, size = 15.dp)
            Spacer(Modifier.width(DdSpacing.Xs))
            DdText(
                text = host.t("邮件", "Mail"),
                style = DdType.chip,
                color = colors.inkTertiary,
                maxLines = 1,
            )
            Spacer(Modifier.weight(1f))
            item?.badgeTokens().orEmpty().forEach { token ->
                Spacer(Modifier.width(DdSpacing.Xs))
                FeishuChip(
                    text = token,
                    tint = colors.inkSecondary,
                    background = colors.wash,
                    shape = RoundedCornerShape(percent = 50),
                )
            }
        }
        Spacer(Modifier.height(DdSpacing.Sm))
        DdText(
            text = subject,
            style = DdType.heading,
            color = colors.ink,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(DdSpacing.Xs))
        item?.let { row ->
            DdText(
                text = listOfNotNull(row.title, row.meta).joinToString(" · "),
                style = DdType.bodySmall,
                color = colors.inkSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            row.subtitle?.takeIf { it.isNotBlank() }?.let { line ->
                Spacer(Modifier.height(2.dp))
                DdText(
                    text = line,
                    style = DdType.numeric,
                    color = colors.inkTertiary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.height(DdSpacing.Md))
        Box(
            Modifier
                .width(30.dp)
                .height(3.dp)
                .background(colors.accent, RoundedCornerShape(2.dp)),
        )
        Spacer(Modifier.height(DdSpacing.Md))
        body?.let { text ->
            DdText(
                text = text,
                style = DdType.body,
                color = colors.ink,
                maxLines = if (expanded) Int.MAX_VALUE else 8,
                overflow = TextOverflow.Ellipsis,
            )
            if (long) {
                DdExpandToggle(
                    expanded = expanded,
                    collapsedLabel = host.t("展开全文", "Show full text"),
                    expandedLabel = host.t("收起全文", "Show less"),
                    onToggle = { expanded = !expanded },
                )
            }
        } ?: DdText(
            text = host.t("这封邮件没有正文", "This email has no body"),
            style = DdType.bodySmall,
            color = colors.inkSecondary,
        )
        model.note?.takeIf { it.isNotBlank() }?.let { line ->
            Spacer(Modifier.height(DdSpacing.Sm))
            DdText(
                text = line,
                style = DdType.chip,
                color = colors.inkTertiary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun MailReadEmpty(host: PluginHost) {
    FeishuEmptyBody(
        title = host.t("这封邮件暂时读不到", "This email can't be read right now"),
        message = host.t("可能没有访问权限，或邮件已经不存在", "You may not have access, or the mail no longer exists"),
    ) {
        DdIcon(icon = DdIcons.Message, tint = deepDeepColors().inkTertiary, size = 24.dp)
    }
}
