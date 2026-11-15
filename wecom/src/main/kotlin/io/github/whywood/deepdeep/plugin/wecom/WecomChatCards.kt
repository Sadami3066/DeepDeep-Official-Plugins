package io.github.whywood.deepdeep.plugin.wecom

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.whywood.deepdeep.design.DdGroupCard
import io.github.whywood.deepdeep.design.DdIcon
import io.github.whywood.deepdeep.design.DdIcons
import io.github.whywood.deepdeep.design.DdListRow
import io.github.whywood.deepdeep.design.DdNoticeBar
import io.github.whywood.deepdeep.design.DdNoticeTone
import io.github.whywood.deepdeep.design.DdRowDivider
import io.github.whywood.deepdeep.design.DdSpacing
import io.github.whywood.deepdeep.design.DdSpinner
import io.github.whywood.deepdeep.design.DdText
import io.github.whywood.deepdeep.design.DdTextAction
import io.github.whywood.deepdeep.design.DdType
import io.github.whywood.deepdeep.design.deepDeepColors
import io.github.whywood.deepdeep.plugin.api.CustomMessageSlotState
import io.github.whywood.deepdeep.plugin.api.PluginHost
import io.github.whywood.deepdeep.plugin.api.SlotBinding
import io.github.whywood.deepdeep.plugin.api.SlotIds
import io.github.whywood.deepdeep.plugin.api.composeSlot
import io.github.whywood.deepdeep.plugin.api.slotBinding
import io.github.whywood.deepdeep.plugin.cli.arrayValue
import io.github.whywood.deepdeep.plugin.cli.card.CliCardCodec
import io.github.whywood.deepdeep.plugin.cli.card.CliCardItem
import io.github.whywood.deepdeep.plugin.cli.card.CliCardModel
import io.github.whywood.deepdeep.plugin.cli.card.CliCardState
import io.github.whywood.deepdeep.plugin.cli.intValue
import io.github.whywood.deepdeep.plugin.cli.stringValue
import kotlinx.serialization.json.JsonObject

internal fun wecomChatCardBindings(host: PluginHost): List<SlotBinding> =
    WecomCommandKind.entries.map { kind ->
        slotBinding(
            slotId = SlotIds.chatMessage(kind.customType),
            content = composeSlot { state ->
                val custom = state as? CustomMessageSlotState ?: return@composeSlot
                WecomChatCard(host = host, kind = kind, state = custom)
            },
        )
    }

internal object WecomChatCards {
    const val MAX_CARD_ROWS: Int = CliCardCodec.MAX_ROWS

    private val SUPPORTED_KINDS: Set<String> = WecomCommandKind.entries.map { it.customType }.toSet()

    fun build(host: PluginHost, command: WecomCommand, parsed: WecomParsed): CliCardModel = when (parsed) {
        is WecomParsed.Groups -> CliCardModel(
            kind = command.kind.customType,
            state = if (parsed.items.isEmpty()) CliCardState.EMPTY else CliCardState.READY,
            context = command.contextLabel,
            total = parsed.count,
            items = parsed.items.take(MAX_CARD_ROWS).map { item ->
                CliCardItem(
                    title = item.name?.takeIf { it.isNotBlank() } ?: "未命名会话",
                    subtitle = listOfNotNull(
                        item.lastMessageTime?.takeIf { it.isNotBlank() }?.let { "最近消息 $it" },
                        item.chatId?.takeIf { it.isNotBlank() },
                    ).joinToString(" · ").ifBlank { null },
                    meta = item.messageCount?.let { displayValue(host, it.toString(), "count") },
                )
            },
            note = moreNote(parsed.hasMore, parsed.nextCursor),
        )
        is WecomParsed.Messages -> CliCardModel(
            kind = command.kind.customType,
            state = if (parsed.items.isEmpty()) CliCardState.EMPTY else CliCardState.READY,
            context = command.contextLabel,
            total = parsed.count,
            items = parsed.items.take(MAX_CARD_ROWS).map { item ->
                CliCardItem(
                    title = item.senderName?.takeIf { it.isNotBlank() }
                        ?: item.senderId?.takeIf { it.isNotBlank() }
                        ?: "未知成员",
                    subtitle = listOfNotNull(
                        item.sendTime?.takeIf { it.isNotBlank() },
                        item.summary?.takeIf { it.isNotBlank() },
                    ).joinToString(" · "),
                )
            },
            note = moreNote(parsed.hasMore, parsed.nextCursor),
        )
        is WecomParsed.Docs -> CliCardModel(
            kind = command.kind.customType,
            state = if (parsed.items.isEmpty()) CliCardState.EMPTY else CliCardState.READY,
            context = command.contextLabel,
            total = parsed.count,
            items = parsed.items.take(MAX_CARD_ROWS).map { item ->
                CliCardItem(
                    title = item.name?.takeIf { it.isNotBlank() } ?: "未命名文档",
                    subtitle = listOfNotNull(
                        item.creatorName?.takeIf { it.isNotBlank() },
                        item.modifyTime?.takeIf { it.isNotBlank() }?.let { "修改于 $it" },
                    ).joinToString(" · ").ifBlank { null },
                    meta = item.type?.let { displayValue(host, it, "doc_type") },
                    url = item.url?.takeIf { it.startsWith("http") },
                )
            },
            note = moreNote(parsed.hasMore, parsed.nextCursor),
        )
        is WecomParsed.DocContent -> CliCardModel(
            kind = command.kind.customType,
            state = CliCardState.READY,
            title = parsed.name,
            trailing = parsed.version?.let { "版本 $it" },
            context = command.docId,
            total = 1,
            summary = summaryExcerpt(parsed.content),
            openUrl = parsed.url?.takeIf { it.startsWith("http") },
        )
        is WecomParsed.Schedules -> CliCardModel(
            kind = command.kind.customType,
            state = if (parsed.items.isEmpty()) CliCardState.EMPTY else CliCardState.READY,
            context = command.contextLabel,
            total = parsed.count,
            items = parsed.items.take(MAX_CARD_ROWS).map { item ->
                CliCardItem(
                    title = item.subject?.takeIf { it.isNotBlank() } ?: "未命名日程",
                    subtitle = scheduleSubtitle(item),
                    meta = item.attendees.size.takeIf { it > 0 }?.let { "+$it" },
                )
            },
        )
        is WecomParsed.Todos -> CliCardModel(
            kind = command.kind.customType,
            state = if (parsed.items.isEmpty()) CliCardState.EMPTY else CliCardState.READY,
            context = command.contextLabel,
            total = parsed.count,
            items = parsed.items.take(MAX_CARD_ROWS).map { item ->
                CliCardItem(
                    title = item.title?.takeIf { it.isNotBlank() } ?: "未命名待办",
                    subtitle = listOfNotNull(
                        item.deadline?.takeIf { it.isNotBlank() }?.let { "截止 $it" },
                        item.followers.takeIf { it.isNotEmpty() }?.joinToString("、"),
                    ).joinToString(" · ").ifBlank { null },
                    meta = item.status?.let { displayValue(host, it, "todo_status") },
                )
            },
            note = moreNote(parsed.hasMore, parsed.nextCursor),
        )
        is WecomParsed.Contacts -> CliCardModel(
            kind = command.kind.customType,
            state = if (parsed.items.isEmpty()) CliCardState.EMPTY else CliCardState.READY,
            context = command.contextLabel,
            total = parsed.count,
            items = parsed.items.take(MAX_CARD_ROWS).map { item ->
                CliCardItem(
                    title = item.name?.takeIf { it.isNotBlank() }
                        ?: item.userId?.takeIf { it.isNotBlank() }
                        ?: "未知成员",
                    subtitle = listOfNotNull(
                        item.departments.takeIf { it.isNotEmpty() }?.joinToString("/"),
                        item.position?.takeIf { it.isNotBlank() },
                    ).joinToString(" · ").ifBlank { item.userId },
                    meta = item.email?.takeIf { it.isNotBlank() },
                )
            },
        )
    }

    fun decode(host: PluginHost, payload: JsonObject, kind: WecomCommandKind): CliCardModel? =
        CliCardCodec.decode(payload, SUPPORTED_KINDS) ?: legacyCard(host, payload, kind)

    private fun legacyCard(host: PluginHost, payload: JsonObject, kind: WecomCommandKind): CliCardModel? = runCatching {
        val state = CliCardState.fromWire(payload.stringValue("state")) ?: return null
        val count = payload.intValue("count") ?: return null
        val items = payload.arrayValue("rows").mapNotNull { element ->
            val row = element as? JsonObject ?: return@mapNotNull null
            val title = row.stringValue("title") ?: return@mapNotNull null
            CliCardItem(
                title = title,
                subtitle = row.stringValue("subtitle"),
                meta = row.stringValue("value")?.let { displayValue(host, it, row.stringValue("valueKind")) },
                url = row.stringValue("url"),
            )
        }
        CliCardModel(
            kind = kind.customType,
            state = state,
            title = payload.stringValue("headerDetail"),
            trailing = payload.stringValue("headerTrailing"),
            context = payload.stringValue("context"),
            total = count,
            items = items,
            summary = payload.stringValue("summary") ?: payload.stringValue("errorMessage"),
            openUrl = payload.stringValue("actionUrl"),
            note = payload.stringValue("note"),
        )
    }.getOrNull()

    private fun moreNote(hasMore: Boolean, nextCursor: String?): String? = when {
        hasMore && !nextCursor.isNullOrBlank() -> "还有更多，cursor=$nextCursor"
        hasMore -> "还有更多"
        else -> null
    }

    private fun scheduleSubtitle(item: WecomParsed.Schedule): String? = listOfNotNull(
        item.beginTime?.takeIf { it.isNotBlank() }?.let { begin ->
            item.endTime?.takeIf { it.isNotBlank() }?.let { end -> "$begin - $end" } ?: begin
        },
        item.location?.takeIf { it.isNotBlank() } ?: item.meetingRoom?.takeIf { it.isNotBlank() },
    ).joinToString(" · ").ifBlank { null }

    private fun summaryExcerpt(content: String): String? {
        val normalized = content
            .replace("\r\n", "\n")
            .lines()
            .joinToString(" ") { it.trim() }
            .replace(Regex("\\s+"), " ")
            .trim()
        if (normalized.isEmpty()) return null
        return if (normalized.length > 240) normalized.take(240) + "…" else normalized
    }

    internal fun displayValue(host: PluginHost, value: String, valueKind: String?): String = when (valueKind) {
        "todo_status" -> host.t(WecomObservation.todoStatusLabel(value), todoStatusEn(value))
        "doc_type" -> host.t(WecomObservation.docTypeLabel(value), docTypeEn(value))
        "count" -> if (host.chinese) "$value 条" else value
        "people" -> value
        else -> value
    }

    private fun todoStatusEn(status: String): String = when (status) {
        "proceed" -> "In progress"
        "finished" -> "Done"
        "deleted" -> "Deleted"
        else -> "Unknown"
    }

    private fun docTypeEn(type: String): String = when (type) {
        "doc" -> "Doc"
        "sheet" -> "Sheet"
        "smartsheet" -> "Smart sheet"
        "smartpage" -> "Smart page"
        "ppt", "slide" -> "Slides"
        "pdf" -> "PDF"
        "collect" -> "Form"
        "mind" -> "Mind map"
        "flow" -> "Flowchart"
        "journal" -> "Journal"
        else -> type
    }
}

@Composable
private fun WecomChatCard(host: PluginHost, kind: WecomCommandKind, state: CustomMessageSlotState) {
    val model = remember(state.payload) { WecomChatCards.decode(host, state.payload, kind) }
    if (model == null) {
        WecomUnavailableCard(host, kind)
        return
    }
    val colors = deepDeepColors()
    val title = host.t(kind.listLabelZh, kind.listLabelEn)
    val trailing = model.trailing ?: countLabel(host, kind, model.total)
    val footerVisible = model.context != null || model.note != null ||
        (model.openUrl != null && kind == WecomCommandKind.DOC_CONTENT)
    val rows = model.flatItems.take(WecomChatCards.MAX_CARD_ROWS)
    DdGroupCard(
        header = {
            Column(verticalArrangement = Arrangement.spacedBy(DdSpacing.Xs)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DdIcon(icon = kindIcon(kind), tint = colors.accent, size = 16.dp)
                    Spacer(Modifier.width(DdSpacing.Sm))
                    DdText(
                        text = title,
                        modifier = Modifier.weight(1f),
                        style = DdType.label,
                        color = colors.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (model.state != CliCardState.ERROR) {
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
                model.title?.takeIf { it.isNotBlank() }?.let { detail ->
                    DdText(
                        text = detail,
                        style = DdType.bodySmall,
                        color = colors.inkSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        footer = if (footerVisible) {
            {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val note = model.context ?: model.note
                    if (!note.isNullOrBlank()) {
                        DdText(
                            text = note,
                            modifier = Modifier.weight(1f),
                            style = DdType.chip,
                            color = colors.inkTertiary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                    val actionUrl = model.openUrl
                    if (actionUrl != null && kind == WecomCommandKind.DOC_CONTENT) {
                        DdTextAction(
                            text = host.t("打开文档", "Open doc"),
                            onClick = { openUrl(host, actionUrl) },
                        )
                    }
                }
            }
        } else {
            null
        },
    ) {
        when (model.state) {
            CliCardState.LOADING -> CardLoadingRow(host.t("正在读取…", "Loading…"))
            CliCardState.EMPTY -> CardTextRow(host.t("没有相关记录", "No results"))
            CliCardState.ERROR -> CardErrorRow(model.summary ?: host.t("读取失败", "Couldn't load"))
            CliCardState.READY -> {
                val summary = model.summary
                if (!summary.isNullOrBlank()) {
                    DdText(
                        text = summary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = DdSpacing.Card, vertical = DdSpacing.Sm),
                        style = DdType.body,
                        color = colors.ink,
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                rows.forEachIndexed { index, item ->
                    if (index > 0) DdRowDivider(leadingInset = DdSpacing.Card)
                    DdListRow(
                        title = item.title,
                        subtitle = item.subtitle,
                        value = item.meta,
                        onClick = item.url?.takeIf { it.startsWith("http") }?.let { url ->
                            { openUrl(host, url) }
                        },
                        showChevron = item.url?.startsWith("http") == true,
                    )
                }
                if (rows.size >= WecomChatCards.MAX_CARD_ROWS && model.total > rows.size) {
                    DdText(
                        text = host.tf(
                            "共 %d 条，显示前 %d 条",
                            "%d items, showing first %d",
                            model.total,
                            WecomChatCards.MAX_CARD_ROWS,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = DdSpacing.Card, vertical = DdSpacing.Sm),
                        style = DdType.chip,
                        color = colors.inkTertiary,
                    )
                }
            }
        }
    }
}

@Composable
private fun CardLoadingRow(text: String) {
    val colors = deepDeepColors()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = DdSpacing.Card, vertical = DdSpacing.Md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DdSpacing.Sm),
    ) {
        DdSpinner(size = 14.dp, color = colors.inkTertiary)
        DdText(text = text, style = DdType.bodySmall, color = colors.inkSecondary)
    }
}

@Composable
private fun CardTextRow(text: String) {
    val colors = deepDeepColors()
    DdText(
        text = text,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = DdSpacing.Card, vertical = DdSpacing.Md),
        style = DdType.bodySmall,
        color = colors.inkSecondary,
    )
}

@Composable
private fun CardErrorRow(message: String) {
    DdNoticeBar(
        text = message,
        tone = DdNoticeTone.DANGER,
        modifier = Modifier.padding(horizontal = DdSpacing.Sm),
    )
}

@Composable
private fun WecomUnavailableCard(host: PluginHost, kind: WecomCommandKind) {
    val colors = deepDeepColors()
    DdGroupCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = DdSpacing.Card, vertical = DdSpacing.Md),
            verticalArrangement = Arrangement.spacedBy(DdSpacing.Xs),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DdIcon(icon = kindIcon(kind), tint = colors.accent, size = 16.dp)
                Spacer(Modifier.width(DdSpacing.Sm))
                DdText(
                    text = host.t(kind.listLabelZh, kind.listLabelEn),
                    style = DdType.label,
                    color = colors.ink,
                )
            }
            DdText(
                text = host.t(
                    "这张卡片暂时无法显示，请在对话里重新查询。",
                    "This card can't be shown here. Run the query again in chat.",
                ),
                style = DdType.bodySmall,
                color = colors.inkSecondary,
            )
        }
    }
}

private fun kindIcon(kind: WecomCommandKind): DdIcon = when (kind) {
    WecomCommandKind.CHAT_GROUPS, WecomCommandKind.CHAT_MESSAGES -> DdIcons.Message
    WecomCommandKind.DOC_SEARCH, WecomCommandKind.DOC_CONTENT -> DdIcons.File
    WecomCommandKind.CALENDAR_SCHEDULES -> DdIcons.Clock
    WecomCommandKind.TODO_LIST -> DdIcons.Check
    WecomCommandKind.CONTACT_USERS -> DdIcons.Book
}

private fun countLabel(host: PluginHost, kind: WecomCommandKind, count: Int): String =
    if (host.chinese) {
        "$count ${kind.countUnitZh}"
    } else {
        "$count ${kind.countUnitEn}"
    }

private fun openUrl(host: PluginHost, url: String) {
    if (!url.startsWith("http")) return
    runCatching { host.ui().openUrl(url) }
}
