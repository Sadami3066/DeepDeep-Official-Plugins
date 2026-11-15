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

@Composable
private fun WecomChatCard(host: PluginHost, kind: WecomCommandKind, state: CustomMessageSlotState) {
    val payload = remember(state.payload) { WecomCardPayloads.decode(state.payload) }
    if (payload == null) {
        WecomUnavailableCard(host, kind)
        return
    }
    val colors = deepDeepColors()
    val title = host.t(kind.listLabelZh, kind.listLabelEn)
    val trailing = payload.headerTrailing ?: countLabel(host, kind, payload.count)
    val footerVisible = payload.context != null || payload.note != null ||
        (payload.actionUrl != null && kind == WecomCommandKind.DOC_CONTENT)
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
                    if (payload.state != WecomCardState.ERROR) {
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
                payload.headerDetail?.takeIf { it.isNotBlank() }?.let { detail ->
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
                    val note = payload.context ?: payload.note
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
                    val actionUrl = payload.actionUrl
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
        when (payload.state) {
            WecomCardState.LOADING -> CardLoadingRow(host.t("正在读取…", "Loading…"))
            WecomCardState.EMPTY -> CardTextRow(host.t("没有相关记录", "No results"))
            WecomCardState.ERROR -> CardErrorRow(payload.errorMessage ?: host.t("读取失败", "Couldn't load"))
            WecomCardState.READY -> {
                val summary = payload.summary
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
                payload.rows.take(WecomCardPayloads.MAX_CARD_ROWS).forEachIndexed { index, row ->
                    if (index > 0) DdRowDivider(leadingInset = DdSpacing.Card)
                    DdListRow(
                        title = row.title,
                        subtitle = row.subtitle,
                        value = row.value?.let { displayValue(host, it, row.valueKind) },
                        onClick = row.url?.takeIf { it.startsWith("http") }?.let { url ->
                            { openUrl(host, url) }
                        },
                        showChevron = row.url?.startsWith("http") == true,
                    )
                }
                if (payload.rows.size > WecomCardPayloads.MAX_CARD_ROWS) {
                    DdText(
                        text = host.tf(
                            "共 %d 条，显示前 %d 条",
                            "%d items, showing first %d",
                            payload.count,
                            WecomCardPayloads.MAX_CARD_ROWS,
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

private fun displayValue(host: PluginHost, value: String, valueKind: String?): String = when (valueKind) {
    "todo_status" -> host.t(WecomObservation.todoStatusLabel(value), todoStatusEn(value))
    "doc_type" -> host.t(WecomObservation.docTypeLabel(value), docTypeEn(value))
    "count" -> if (host.chinese) "$value 条" else "$value"
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

private fun openUrl(host: PluginHost, url: String) {
    if (!url.startsWith("http")) return
    runCatching { host.ui().openUrl(url) }
}
