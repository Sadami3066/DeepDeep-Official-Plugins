package io.github.whywood.deepdeep.plugin.feishu

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.whywood.deepdeep.design.DdHairline
import io.github.whywood.deepdeep.design.DdIcon
import io.github.whywood.deepdeep.design.DdIcons
import io.github.whywood.deepdeep.design.DdPressable
import io.github.whywood.deepdeep.design.DdRowDivider
import io.github.whywood.deepdeep.design.DdShapes
import io.github.whywood.deepdeep.design.DdSpacing
import io.github.whywood.deepdeep.design.DdText
import io.github.whywood.deepdeep.design.DdTextAction
import io.github.whywood.deepdeep.design.DdType
import io.github.whywood.deepdeep.design.deepDeepColors
import io.github.whywood.deepdeep.plugin.api.CustomMessageSlotState
import io.github.whywood.deepdeep.plugin.api.PluginHost
import io.github.whywood.deepdeep.plugin.cli.card.CliCardCodec
import io.github.whywood.deepdeep.plugin.cli.card.CliCardItem
import io.github.whywood.deepdeep.plugin.cli.card.CliCardModel
import kotlinx.serialization.json.JsonObject

internal object FeishuCardTypes {
    const val CHAT_CARD = "feishu.card"
    const val KIND_CHAT_LIST = "chat_list"
    const val KIND_MESSAGES = "messages"
    const val KIND_AGENDA = "agenda"
    const val KIND_DOCS_SEARCH = "docs_search"
    const val KIND_DOCS_READ = "docs_read"
    const val KIND_TASKS = "tasks"
}

internal typealias FeishuCardItem = CliCardItem

internal typealias FeishuCardModel = CliCardModel

internal fun encodeFeishuCardPayload(model: FeishuCardModel): JsonObject = CliCardCodec.encode(model)

internal fun decodeFeishuCardPayload(payload: JsonObject): FeishuCardModel? =
    CliCardCodec.decode(payload, SUPPORTED_CARD_KINDS)

internal val SUPPORTED_CARD_KINDS = setOf(
    FeishuCardTypes.KIND_CHAT_LIST,
    FeishuCardTypes.KIND_MESSAGES,
    FeishuCardTypes.KIND_AGENDA,
    FeishuCardTypes.KIND_DOCS_SEARCH,
    FeishuCardTypes.KIND_DOCS_READ,
    FeishuCardTypes.KIND_TASKS,
)

internal const val MAX_CARD_ROWS = CliCardCodec.MAX_ROWS

@Composable
internal fun FeishuChatCard(host: PluginHost, state: CustomMessageSlotState) {
    val model = remember(state.payload) { decodeFeishuCardPayload(state.payload) }
    if (model == null) {
        FeishuCardSurface {
            FeishuCardHeader(host = host, icon = DdIcons.Message, title = host.t("飞书", "Feishu"))
            DdText(
                text = host.t(
                    "这张卡片暂时无法显示，可以直接查看对话里的结果。",
                    "This card can't be shown here. Check the result in the conversation.",
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = DdSpacing.Lg, vertical = DdSpacing.Sm),
                style = DdType.bodySmall,
                color = deepDeepColors().inkSecondary,
            )
        }
        return
    }
    FeishuCardSurface {
        val context = model.context?.takeIf { it.isNotBlank() }
        FeishuCardHeader(
            host = host,
            icon = cardIcon(model.kind),
            title = cardTitle(host, model.kind),
            trailing = cardCountLabel(host, model),
            bottomPadding = if (context == null) DdSpacing.Md else DdSpacing.Xs,
        )
        if (context != null) {
            DdText(
                text = context,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = DdSpacing.Lg, end = DdSpacing.Lg, bottom = DdSpacing.Md),
                style = DdType.chip,
                color = deepDeepColors().inkTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        DdHairline()
        if (model.items.isEmpty()) {
            DdText(
                text = emptyLabel(host, model.kind),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = DdSpacing.Lg, vertical = DdSpacing.Lg),
                style = DdType.bodySmall,
                color = deepDeepColors().inkSecondary,
            )
        } else {
            model.items.forEachIndexed { index, item ->
                if (index > 0) DdRowDivider(leadingInset = DdSpacing.Lg)
                FeishuCardRow(host = host, item = item)
            }
            DdHairline()
        }
        if (!model.note.isNullOrBlank() || !model.openUrl.isNullOrBlank()) {
            FeishuCardFooter(host = host, note = model.note, openUrl = model.openUrl)
        }
    }
}

@Composable
private fun FeishuCardSurface(content: @Composable ColumnScope.() -> Unit) {
    val colors = deepDeepColors()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(DdShapes.Card)
            .background(colors.raised),
        content = content,
    )
}

@Composable
private fun FeishuCardHeader(
    host: PluginHost,
    icon: DdIcon,
    title: String,
    trailing: String? = null,
    bottomPadding: Dp = DdSpacing.Md,
) {
    val colors = deepDeepColors()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = DdSpacing.Lg, end = DdSpacing.Lg, top = DdSpacing.Md, bottom = bottomPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DdIcon(icon = icon, tint = colors.accent, size = 15.dp)
        Spacer(Modifier.width(DdSpacing.Sm))
        DdText(
            text = title,
            modifier = Modifier.weight(1f),
            style = DdType.label,
            color = colors.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!trailing.isNullOrBlank()) {
            Spacer(Modifier.width(DdSpacing.Sm))
            DdText(
                text = trailing,
                modifier = Modifier.widthIn(max = 160.dp),
                style = DdType.chip,
                color = colors.inkTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun FeishuCardRow(host: PluginHost, item: FeishuCardItem) {
    val colors = deepDeepColors()
    val onOpen = item.url?.let { url -> { host.openFeishuLink(url) } }
    val content: @Composable () -> Unit = {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .padding(start = DdSpacing.Lg, end = DdSpacing.Md, top = DdSpacing.Sm, bottom = DdSpacing.Sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                DdText(
                    text = item.title,
                    style = DdType.body,
                    color = colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                item.subtitle?.takeIf { it.isNotBlank() }?.let { subtitle ->
                    Spacer(Modifier.height(DdSpacing.Xs))
                    DdText(
                        text = subtitle,
                        style = DdType.chip,
                        color = colors.inkTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            item.badge?.takeIf { it.isNotBlank() }?.let { badge ->
                Spacer(Modifier.width(DdSpacing.Sm))
                DdText(
                    text = badge,
                    style = DdType.chip,
                    color = colors.accent,
                    maxLines = 1,
                )
            }
            item.meta?.takeIf { it.isNotBlank() }?.let { meta ->
                Spacer(Modifier.width(DdSpacing.Sm))
                DdText(
                    text = meta,
                    modifier = Modifier.widthIn(max = 96.dp),
                    style = DdType.chip,
                    color = colors.inkSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
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
        ) {
            content()
        }
    } else {
        content()
    }
}

@Composable
private fun FeishuCardFooter(host: PluginHost, note: String?, openUrl: String?) {
    val colors = deepDeepColors()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .padding(start = DdSpacing.Lg, end = DdSpacing.Xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DdText(
            text = note.orEmpty(),
            modifier = Modifier.weight(1f),
            style = DdType.chip,
            color = colors.inkTertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!openUrl.isNullOrBlank()) {
            DdTextAction(
                text = host.t("在飞书打开", "Open in Feishu"),
                onClick = { host.openFeishuLink(openUrl) },
            )
        }
    }
}

internal fun PluginHost.openFeishuLink(url: String) {
    runCatching { ui().openUrl(url) }
}

private fun cardIcon(kind: String): DdIcon = when (kind) {
    FeishuCardTypes.KIND_CHAT_LIST, FeishuCardTypes.KIND_MESSAGES -> DdIcons.Message
    FeishuCardTypes.KIND_AGENDA -> DdIcons.Clock
    FeishuCardTypes.KIND_DOCS_SEARCH -> DdIcons.Search
    FeishuCardTypes.KIND_DOCS_READ -> DdIcons.Book
    FeishuCardTypes.KIND_TASKS -> DdIcons.Target
    else -> DdIcons.Message
}

private fun cardTitle(host: PluginHost, kind: String): String = when (kind) {
    FeishuCardTypes.KIND_CHAT_LIST -> host.t("聊天", "Chats")
    FeishuCardTypes.KIND_MESSAGES -> host.t("消息", "Messages")
    FeishuCardTypes.KIND_AGENDA -> host.t("日程", "Agenda")
    FeishuCardTypes.KIND_DOCS_SEARCH -> host.t("云文档", "Docs")
    FeishuCardTypes.KIND_DOCS_READ -> host.t("文档摘要", "Document summary")
    FeishuCardTypes.KIND_TASKS -> host.t("任务", "Tasks")
    else -> host.t("飞书", "Feishu")
}

private fun cardCountLabel(host: PluginHost, model: FeishuCardModel): String? {
    val total = model.total
    if (total <= 0) return null
    return when (model.kind) {
        FeishuCardTypes.KIND_CHAT_LIST -> host.tf("共 %d 个", "%d chats", total)
        FeishuCardTypes.KIND_AGENDA -> host.tf("共 %d 个", "%d events", total)
        FeishuCardTypes.KIND_DOCS_SEARCH -> host.tf("共 %d 个", "%d docs", total)
        FeishuCardTypes.KIND_DOCS_READ -> null
        else -> host.tf("共 %d 条", "%d items", total)
    }
}

private fun emptyLabel(host: PluginHost, kind: String): String = when (kind) {
    FeishuCardTypes.KIND_CHAT_LIST -> host.t("没有找到聊天", "No chats found")
    FeishuCardTypes.KIND_MESSAGES -> host.t("这个时间段没有消息", "No messages in this range")
    FeishuCardTypes.KIND_AGENDA -> host.t("这段时间没有日程", "No events in this range")
    FeishuCardTypes.KIND_DOCS_SEARCH -> host.t("没有找到文档", "No docs found")
    FeishuCardTypes.KIND_DOCS_READ -> host.t("没有读取到内容", "Nothing to show")
    FeishuCardTypes.KIND_TASKS -> host.t("没有待办任务", "No tasks")
    else -> host.t("没有内容", "Nothing here")
}
