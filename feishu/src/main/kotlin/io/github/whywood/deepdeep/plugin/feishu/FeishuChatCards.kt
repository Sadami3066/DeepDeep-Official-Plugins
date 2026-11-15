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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.whywood.deepdeep.design.DdIcon
import io.github.whywood.deepdeep.design.DdIcons
import io.github.whywood.deepdeep.design.DdPressable
import io.github.whywood.deepdeep.design.DdRowDivider
import io.github.whywood.deepdeep.design.DdSpacing
import io.github.whywood.deepdeep.design.DdText
import io.github.whywood.deepdeep.design.DdType
import io.github.whywood.deepdeep.design.deepDeepColors
import io.github.whywood.deepdeep.plugin.api.PluginHost

@Composable
internal fun FeishuChatListCard(host: PluginHost, model: FeishuCardModel) {
    FeishuCardShell {
        when (FeishuKindState(host, model)) {
            FeishuKindStateKind.ERROR -> FeishuCardErrorBody(host, model.openUrl)
            FeishuKindStateKind.LOADING -> ChatListLoading()
            FeishuKindStateKind.EMPTY -> ChatListEmpty(host)
            FeishuKindStateKind.READY -> ChatListContent(host, model)
        }
    }
}

@Composable
private fun ChatListContent(host: PluginHost, model: FeishuCardModel) {
    val colors = deepDeepColors()
    val count = model.total.coerceAtLeast(model.items.size)
    val rows = model.items.take(FEISHU_CARD_CHAT_ROWS)
    val hidden = (count - rows.size).coerceAtLeast(0)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = DdSpacing.Lg, end = DdSpacing.Lg, top = DdSpacing.Md, bottom = DdSpacing.Sm),
    ) {
        FeishuCardHead(
            host = host,
            title = model.title?.takeIf { it.isNotBlank() } ?: host.t("聊天", "Chats"),
            detail = null,
            trailing = model.trailing?.takeIf { it.isNotBlank() }
                ?: if (count > 0) host.tf("%d 个会话", "%d chats", count) else null,
        )
        Spacer(Modifier.height(DdSpacing.Xs))
        rows.forEachIndexed { index, item ->
            if (index > 0) DdRowDivider(leadingInset = 48.dp)
            ChatListRow(host = host, item = item)
        }
        val note = model.note?.takeIf { it.isNotBlank() }
            ?: if (hidden > 0) host.tf("还有 %d 个未显示", "%d more not shown", hidden) else null
        note?.let { line ->
            Spacer(Modifier.height(DdSpacing.Sm))
            DdText(
                text = line,
                style = DdType.chip,
                color = colors.inkTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ChatListRow(host: PluginHost, item: FeishuCardItem) {
    val colors = deepDeepColors()
    val onOpen = item.url?.let { url -> { host.openFeishuLink(url) } }
    val content: @Composable () -> Unit = {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .padding(vertical = DdSpacing.Sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ChatAvatar(initialOf(item.title), 36)
            Spacer(Modifier.width(DdSpacing.Md))
            Column(Modifier.weight(1f)) {
                DdText(
                    text = item.title,
                    style = feishuItemTitleStyle(),
                    color = colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
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
            item.meta?.takeIf { it.isNotBlank() }?.let { meta ->
                Spacer(Modifier.width(DdSpacing.Sm))
                DdText(
                    text = meta,
                    style = DdType.chip,
                    color = colors.inkTertiary,
                    maxLines = 1,
                )
            }
            if (onOpen != null) {
                Spacer(Modifier.width(DdSpacing.Xs))
                DdIcon(
                    icon = DdIcons.ChevronRight,
                    tint = colors.inkTertiary,
                    size = 15.dp,
                )
            }
        }
    }
    if (onOpen != null) {
        DdPressable(
            onClick = onOpen,
            modifier = Modifier.fillMaxWidth(),
            shape = RectangleShape,
            contentDescription = item.title,
            content = { content() },
        )
    } else {
        Box(Modifier.fillMaxWidth()) { content() }
    }
}

@Composable
internal fun ChatAvatar(initial: String, size: Int) {
    val colors = deepDeepColors()
    Box(
        modifier = Modifier
            .size(size.dp)
            .background(colors.accentSoft, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        DdText(
            text = initial.ifBlank { "?" },
            style = if (size >= 32) {
                DdType.label.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, fontSize = 15.sp)
            } else {
                DdType.chip.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, fontSize = 12.sp)
            },
            color = colors.ink,
            maxLines = 1,
        )
    }
}

@Composable
private fun ChatListLoading() {
    val colors = deepDeepColors()
    Column(Modifier.padding(DdSpacing.Lg)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(14.dp)
                    .background(colors.wash, RoundedCornerShape(4.dp)),
            )
            Spacer(Modifier.width(DdSpacing.Sm))
            Box(Modifier.height(12.dp).width(56.dp).background(colors.wash, RoundedCornerShape(4.dp)))
        }
        Spacer(Modifier.height(DdSpacing.Md))
        repeat(3) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = DdSpacing.Sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(36.dp).background(colors.wash, CircleShape))
                Spacer(Modifier.width(DdSpacing.Md))
                Column(Modifier.weight(1f)) {
                    Box(Modifier.fillMaxWidth(0.55f).height(12.dp).background(colors.wash, RoundedCornerShape(4.dp)))
                    Spacer(Modifier.height(DdSpacing.Sm))
                    Box(Modifier.fillMaxWidth(0.4f).height(9.dp).background(colors.wash, RoundedCornerShape(4.dp)))
                }
            }
        }
    }
}

@Composable
private fun ChatListEmpty(host: PluginHost) {
    FeishuEmptyBody(
        title = host.t("还没有聊天", "No chats yet"),
        message = host.t(
            "加入群聊或开始单聊后，会话会显示在这里",
            "Group and direct chats will appear here",
        ),
    ) {
        repeat(3) { index ->
            if (index > 0) Spacer(Modifier.width(DdSpacing.Sm))
            Box(
                Modifier
                    .size((36 - index * 6).dp)
                    .background(deepDeepColors().wash, CircleShape),
            )
        }
    }
}

@Composable
internal fun FeishuMessagesCard(host: PluginHost, model: FeishuCardModel) {
    FeishuCardShell {
        when (FeishuKindState(host, model)) {
            FeishuKindStateKind.ERROR -> FeishuCardErrorBody(host, model.openUrl)
            FeishuKindStateKind.LOADING -> MessagesLoading()
            FeishuKindStateKind.EMPTY -> MessagesEmpty(host)
            FeishuKindStateKind.READY -> MessagesContent(host, model)
        }
    }
}

@Composable
private fun MessagesContent(host: PluginHost, model: FeishuCardModel) {
    val colors = deepDeepColors()
    val count = model.total.coerceAtLeast(model.items.size)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = DdSpacing.Lg, end = DdSpacing.Lg, top = DdSpacing.Md, bottom = DdSpacing.Sm),
    ) {
        FeishuCardHead(
            host = host,
            title = model.title?.takeIf { it.isNotBlank() } ?: host.t("消息", "Messages"),
            detail = cardRangeText(model.context),
            trailing = model.trailing?.takeIf { it.isNotBlank() }
                ?: if (count > 0) host.tf("%d 条", "%d messages", count) else null,
        )
        Spacer(Modifier.height(DdSpacing.Xs))
        model.items.take(FEISHU_CARD_MESSAGE_ROWS).forEach { item ->
            MessageBubbleRow(host = host, item = item)
        }
        val hidden = (count - minOf(model.items.size, FEISHU_CARD_MESSAGE_ROWS)).coerceAtLeast(0)
        val note = model.note?.takeIf { it.isNotBlank() }
            ?: if (hidden > 0) host.tf("还有 %d 条未显示", "%d more not shown", hidden) else null
        FeishuCardFoot(
            host = host,
            note = note,
            actionLabel = if (model.openUrl.isNullOrBlank()) null else host.t("打开会话", "Open chat"),
            openUrl = model.openUrl,
        )
    }
}

@Composable
private fun MessageBubbleRow(host: PluginHost, item: FeishuCardItem) {
    val colors = deepDeepColors()
    val sender = item.messageSender()
    val kindLabel = item.messageKindLabel()
    val time = item.timePart()
    val onOpen = item.url?.let { url -> { host.openFeishuLink(url) } }
    val content: @Composable () -> Unit = {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = DdSpacing.Sm),
            verticalAlignment = Alignment.Top,
        ) {
            ChatAvatar(initialOf(sender ?: item.title), 28)
            Spacer(Modifier.width(DdSpacing.Sm))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    sender?.let { who ->
                        DdText(
                            text = who,
                            style = DdType.label.copy(
                                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                                fontSize = 12.sp,
                            ),
                            color = colors.inkSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.width(DdSpacing.Xs))
                    }
                    time?.let {
                        DdText(text = it, style = DdType.numeric, color = colors.inkTertiary, maxLines = 1)
                        Spacer(Modifier.width(DdSpacing.Xs))
                    }
                    kindLabel?.let {
                        DdText(text = it, style = DdType.numeric, color = colors.inkTertiary, maxLines = 1)
                    }
                }
                Spacer(Modifier.height(DdSpacing.Xs))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            colors.wash,
                            RoundedCornerShape(
                                topStart = 4.dp,
                                topEnd = 14.dp,
                                bottomEnd = 14.dp,
                                bottomStart = 14.dp,
                            ),
                        )
                        .padding(horizontal = DdSpacing.Md, vertical = DdSpacing.Sm),
                ) {
                    DdText(
                        text = item.title,
                        style = DdType.bodySmall,
                        color = colors.ink,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
    if (onOpen != null) {
        DdPressable(
            onClick = onOpen,
            modifier = Modifier.fillMaxWidth(),
            shape = RectangleShape,
            contentDescription = item.title,
            content = { content() },
        )
    } else {
        content()
    }
}

@Composable
private fun MessagesLoading() {
    val colors = deepDeepColors()
    Column(Modifier.padding(DdSpacing.Lg)) {
        Box(Modifier.height(12.dp).width(64.dp).background(colors.wash, RoundedCornerShape(4.dp)))
        Spacer(Modifier.height(DdSpacing.Md))
        repeat(2) {
            Row(Modifier.padding(vertical = DdSpacing.Sm)) {
                Box(Modifier.size(28.dp).background(colors.wash, CircleShape))
                Spacer(Modifier.width(DdSpacing.Sm))
                Column(Modifier.weight(1f)) {
                    Box(Modifier.height(10.dp).width(96.dp).background(colors.wash, RoundedCornerShape(4.dp)))
                    Spacer(Modifier.height(DdSpacing.Sm))
                    Box(Modifier.fillMaxWidth().height(34.dp).background(colors.wash, RoundedCornerShape(14.dp)))
                }
            }
        }
    }
}

@Composable
private fun MessagesEmpty(host: PluginHost) {
    FeishuEmptyBody(
        title = host.t("这个时间段没有消息", "No messages in this range"),
        message = host.t(
            "换个时间范围，或者直接问我聊了什么",
            "Try another time range, or ask me what was discussed",
        ),
    ) {
        Box(
            Modifier
                .width(74.dp)
                .height(30.dp)
                .background(deepDeepColors().wash, RoundedCornerShape(topStart = 4.dp, topEnd = 14.dp, bottomEnd = 14.dp, bottomStart = 14.dp)),
        )
        Spacer(Modifier.width(DdSpacing.Sm))
        Box(
            Modifier
                .width(44.dp)
                .height(22.dp)
                .background(deepDeepColors().wash, RoundedCornerShape(topStart = 4.dp, topEnd = 14.dp, bottomEnd = 14.dp, bottomStart = 14.dp)),
        )
    }
}
