package io.github.whywood.deepdeep.plugin.feishu

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.whywood.deepdeep.design.DdExpandToggle
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
    val all = model.items
    val count = model.total.coerceAtLeast(all.size)
    var expanded by remember(model) { mutableStateOf(false) }
    val rows = if (expanded) all else all.take(FEISHU_CHAT_PREVIEW_ROWS)
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
        if (all.size > FEISHU_CHAT_PREVIEW_ROWS) {
            DdExpandToggle(
                expanded = expanded,
                collapsedLabel = host.tf(
                    "展开其余 %d 个会话",
                    "Show %d more chats",
                    all.size - FEISHU_CHAT_PREVIEW_ROWS,
                ),
                expandedLabel = host.t("收起会话列表", "Show fewer chats"),
                onToggle = { expanded = !expanded },
                modifier = Modifier.fillMaxWidth(),
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
            FeishuAvatar(host = host, avatarUrl = item.avatar, name = item.title, size = 36)
            Spacer(Modifier.width(DdSpacing.Md))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DdText(
                        text = item.title,
                        modifier = Modifier.weight(1f, fill = false),
                        style = feishuItemTitleStyle(),
                        color = colors.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    item.badgeTokens().forEach { token ->
                        Spacer(Modifier.width(DdSpacing.Xs))
                        FeishuChip(text = token, tint = colors.inkSecondary, background = colors.wash)
                    }
                }
                item.subtitle?.takeIf { it.isNotBlank() }?.let { subtitle ->
                    Spacer(Modifier.height(2.dp))
                    DdText(
                        text = subtitle,
                        style = DdType.chip,
                        color = colors.inkSecondary,
                        maxLines = 2,
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
    val count = model.total.coerceAtLeast(model.items.size)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = DdSpacing.Lg, end = DdSpacing.Lg, top = DdSpacing.Md, bottom = DdSpacing.Sm),
    ) {
        FeishuCardHead(
            host = host,
            title = model.title?.takeIf { it.isNotBlank() } ?: host.t("消息", "Messages"),
            detail = model.context?.takeIf { it.isNotBlank() },
            trailing = model.trailing?.takeIf { it.isNotBlank() }
                ?: if (count > 0) host.tf("%d 条", "%d messages", count) else null,
        )
        Spacer(Modifier.height(DdSpacing.Xs))
        model.items.forEach { item ->
            MessageBubbleRow(host = host, item = item)
        }
        FeishuCardFoot(
            host = host,
            note = model.note?.takeIf { it.isNotBlank() },
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
    val markers = item.badgeTokens()
    val recalled = markers.any { it == "已撤回" || it == "Recalled" }
    val attachments = item.subtitle?.split(" · ")?.drop(2)?.filter { it.isNotBlank() }.orEmpty()
    val text = (item.body?.takeIf { it.isNotBlank() } ?: if (attachments.isEmpty()) item.title else "").trim()
    val long = text.lineSequence().count() > FEISHU_MESSAGE_PREVIEW_LINES || text.length > 160
    var expanded by remember(item) { mutableStateOf(false) }
    val onOpen = item.url?.let { url -> { host.openFeishuLink(url) } }
    val content: @Composable () -> Unit = {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = DdSpacing.Sm),
            verticalAlignment = Alignment.Top,
        ) {
            FeishuAvatar(host = host, avatarUrl = null, name = sender ?: item.title, size = 28)
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
                if (markers.isNotEmpty()) {
                    Spacer(Modifier.height(DdSpacing.Xs))
                    val shown = markers.take(3)
                    val extra = markers.size - shown.size
                    Row(horizontalArrangement = Arrangement.spacedBy(DdSpacing.Xs)) {
                        shown.forEach { marker ->
                            FeishuChip(text = marker, tint = colors.inkSecondary, background = colors.wash)
                        }
                        if (extra > 0) {
                            FeishuChip(text = "+$extra", tint = colors.inkTertiary, background = colors.wash)
                        }
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
                    Column {
                        attachments.forEach { name ->
                            FeishuChip(
                                text = name,
                                tint = colors.inkSecondary,
                                background = colors.surface,
                                shape = RoundedCornerShape(DdSpacing.Sm),
                            )
                            Spacer(Modifier.height(DdSpacing.Xs))
                        }
                        if (text.isNotBlank()) {
                            DdText(
                                text = text,
                                style = DdType.bodySmall.copy(
                                    textDecoration = if (recalled) TextDecoration.LineThrough else TextDecoration.None,
                                ),
                                color = if (recalled) colors.inkTertiary else colors.ink,
                                maxLines = if (expanded) Int.MAX_VALUE else FEISHU_MESSAGE_PREVIEW_LINES,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                if (long) {
                    DdExpandToggle(
                        expanded = expanded,
                        collapsedLabel = host.t("展开全文", "Show full message"),
                        expandedLabel = host.t("收起全文", "Show less"),
                        onToggle = { expanded = !expanded },
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

@Composable
internal fun FeishuMessagesSearchCard(host: PluginHost, model: FeishuCardModel) {
    FeishuCardShell {
        if (model.isEmpty) MessagesSearchEmpty(host) else MessagesSearchContent(host, model)
    }
}

@Composable
private fun MessagesSearchContent(host: PluginHost, model: FeishuCardModel) {
    val all = model.items
    val count = model.total.coerceAtLeast(all.size)
    var expanded by remember(model) { mutableStateOf(false) }
    val rows = if (expanded) all else all.take(FEISHU_SEARCH_PREVIEW_ROWS)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = DdSpacing.Lg, end = DdSpacing.Lg, top = DdSpacing.Md, bottom = DdSpacing.Sm),
    ) {
        FeishuCardHead(
            host = host,
            title = model.title?.takeIf { it.isNotBlank() } ?: host.t("消息搜索", "Message search"),
            detail = model.context?.takeIf { it.isNotBlank() },
            trailing = model.trailing?.takeIf { it.isNotBlank() }
                ?: if (count > 0) host.tf("%d 条", "%d messages", count) else null,
        )
        Spacer(Modifier.height(DdSpacing.Xs))
        rows.forEachIndexed { index, item ->
            if (index > 0) DdRowDivider(leadingInset = 36.dp)
            MessageSearchRow(host = host, item = item)
        }
        if (all.size > FEISHU_SEARCH_PREVIEW_ROWS) {
            DdExpandToggle(
                expanded = expanded,
                collapsedLabel = host.tf(
                    "展开其余 %d 条",
                    "Show %d more",
                    all.size - FEISHU_SEARCH_PREVIEW_ROWS,
                ),
                expandedLabel = host.t("收起搜索结果", "Show fewer"),
                onToggle = { expanded = !expanded },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun MessageSearchRow(host: PluginHost, item: FeishuCardItem) {
    val colors = deepDeepColors()
    val sender = item.messageSender()
    val kindLabel = item.messageKindLabel()
    val parts = item.subtitle?.split(" · ").orEmpty()
    val attachments = parts.drop(2).filter { it.isNotBlank() }
    val time = item.meta?.takeIf { it.isNotBlank() }
    val markers = item.badgeTokens()
    val recalled = markers.any { it == "已撤回" || it == "Recalled" }
    val text = item.body?.takeIf { it.isNotBlank() }.orEmpty()
    val long = text.lineSequence().count() > FEISHU_MESSAGE_PREVIEW_LINES || text.length > 160
    var expanded by remember(item) { mutableStateOf(false) }
    val onOpen = item.url?.let { url -> { host.openFeishuLink(url) } }
    val content: @Composable () -> Unit = {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = DdSpacing.Sm),
            verticalAlignment = Alignment.Top,
        ) {
            FeishuAvatar(host = host, avatarUrl = null, name = sender ?: item.title, size = 28)
            Spacer(Modifier.width(DdSpacing.Sm))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DdText(
                        text = item.title.takeIf { it.isNotBlank() } ?: host.t("单聊", "Direct"),
                        modifier = Modifier.weight(1f),
                        style = feishuItemTitleStyle().copy(fontSize = 13.sp, lineHeight = 18.sp),
                        color = colors.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    time?.let {
                        Spacer(Modifier.width(DdSpacing.Xs))
                        DdText(text = it, style = DdType.numeric, color = colors.inkTertiary, maxLines = 1)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    sender?.let { who ->
                        DdText(
                            text = who,
                            modifier = Modifier.weight(1f, fill = false),
                            style = DdType.label.copy(
                                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                                fontSize = 12.sp,
                            ),
                            color = colors.inkSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    kindLabel?.let {
                        Spacer(Modifier.width(DdSpacing.Xs))
                        DdText(text = it, style = DdType.numeric, color = colors.inkTertiary, maxLines = 1)
                    }
                    markers.take(3).forEach { marker ->
                        Spacer(Modifier.width(DdSpacing.Xs))
                        FeishuChip(text = marker, tint = colors.inkSecondary, background = colors.wash)
                    }
                }
                Spacer(Modifier.height(DdSpacing.Xs))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colors.wash, RoundedCornerShape(14.dp))
                        .padding(horizontal = DdSpacing.Md, vertical = DdSpacing.Sm),
                ) {
                    Column {
                        attachments.forEach { name ->
                            FeishuChip(
                                text = name,
                                tint = colors.inkSecondary,
                                background = colors.surface,
                                shape = RoundedCornerShape(DdSpacing.Sm),
                            )
                            Spacer(Modifier.height(DdSpacing.Xs))
                        }
                        if (text.isNotBlank()) {
                            DdText(
                                text = text,
                                style = DdType.bodySmall.copy(
                                    textDecoration = if (recalled) TextDecoration.LineThrough else TextDecoration.None,
                                ),
                                color = if (recalled) colors.inkTertiary else colors.ink,
                                maxLines = if (expanded) Int.MAX_VALUE else FEISHU_MESSAGE_PREVIEW_LINES,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                if (long) {
                    DdExpandToggle(
                        expanded = expanded,
                        collapsedLabel = host.t("展开全文", "Show full message"),
                        expandedLabel = host.t("收起全文", "Show less"),
                        onToggle = { expanded = !expanded },
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
private fun MessagesSearchEmpty(host: PluginHost) {
    FeishuEmptyBody(
        title = host.t("没有搜到消息", "No messages found"),
        message = host.t("换个关键词、时间范围或会话再试", "Try another keyword, time range or chat"),
    ) {
        DdIcon(icon = DdIcons.Search, tint = deepDeepColors().inkTertiary, size = 24.dp)
    }
}
