package io.github.whywood.deepdeep.plugin.sysu

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.whywood.deepdeep.design.DdGroupCard
import io.github.whywood.deepdeep.design.DdHairline
import io.github.whywood.deepdeep.design.DdIcon
import io.github.whywood.deepdeep.design.DdIcons
import io.github.whywood.deepdeep.design.DdListRow
import io.github.whywood.deepdeep.design.DdLoadingBlock
import io.github.whywood.deepdeep.design.DdNoticeBar
import io.github.whywood.deepdeep.design.DdNoticeTone
import io.github.whywood.deepdeep.design.DdSpacing
import io.github.whywood.deepdeep.design.DdText
import io.github.whywood.deepdeep.design.DdType
import io.github.whywood.deepdeep.design.deepDeepColors
import io.github.whywood.deepdeep.plugin.api.CustomMessageSlotState
import io.github.whywood.deepdeep.plugin.api.PluginHost
import io.github.whywood.deepdeep.plugin.api.SlotBinding
import io.github.whywood.deepdeep.plugin.api.SlotIds
import io.github.whywood.deepdeep.plugin.api.composeSlot
import io.github.whywood.deepdeep.plugin.api.localizedText
import io.github.whywood.deepdeep.plugin.api.slotBinding

internal fun sysuCardBinding(host: PluginHost): SlotBinding = slotBinding(
    slotId = SlotIds.chatMessage(SYSU_CARD_TYPE),
    content = composeSlot { state ->
        val message = state as? CustomMessageSlotState ?: return@composeSlot
        SysuChatCard(host = host, message = message)
    },
)

@Composable
private fun SysuChatCard(host: PluginHost, message: CustomMessageSlotState) {
    val payload = remember(message.payload) {
        SysuCards.decode(message.payload)?.let(SysuCards::normalized)
    }
    if (payload == null) {
        SysuBrokenCard(host)
        return
    }
    SysuCardBody(host, payload)
}

@Composable
private fun SysuBrokenCard(host: PluginHost) {
    val colors = deepDeepColors()
    DdGroupCard(
        header = {
            SysuCardHeader(
                icon = DdIcons.Info,
                title = host.t("校园服务", "Campus services"),
                subtitle = null,
                count = null,
            )
        },
        footer = null,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = DdSpacing.Card, vertical = DdSpacing.Sm),
        ) {
            DdText(
                text = host.t("这张卡片暂时无法显示，可以重新查询一次。", "This card can't be shown right now. Try the query again."),
                style = DdType.bodySmall,
                color = colors.inkSecondary,
            )
        }
    }
}

@Composable
private fun SysuCardBody(host: PluginHost, payload: SysuCardPayload) {
    val colors = deepDeepColors()
    val icon = iconFor(payload.kind)
    val footer: (@Composable () -> Unit)? = payload.footer
        ?.takeIf { it.isNotBlank() }
        ?.let { text ->
            {
                DdText(
                    text = text,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = DdSpacing.Card, vertical = DdSpacing.Sm),
                    style = DdType.chip,
                    color = colors.inkTertiary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    DdGroupCard(
        header = {
            SysuCardHeader(
                icon = icon,
                title = payload.title.ifBlank { titleFor(host, payload.kind) },
                subtitle = payload.subtitle,
                count = payload.count,
            )
        },
        footer = footer,
    ) {
        when (payload.state) {
            SysuCardState.ERROR -> Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = DdSpacing.Card, vertical = DdSpacing.Sm),
            ) {
                DdNoticeBar(
                    text = payload.body.orEmpty().ifBlank { titleFor(host, payload.kind) },
                    tone = DdNoticeTone.DANGER,
                )
            }

            SysuCardState.LOADING -> DdLoadingBlock(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = DdSpacing.Xl),
                text = payload.body,
            )

            SysuCardState.EMPTY -> DdText(
                text = payload.body.orEmpty().ifBlank { titleFor(host, payload.kind) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = DdSpacing.Card, vertical = DdSpacing.Md),
                style = DdType.bodySmall,
                color = colors.inkSecondary,
            )

            else -> SysuCardRows(payload)
        }
    }
}

@Composable
private fun SysuCardRows(payload: SysuCardPayload) {
    val colors = deepDeepColors()
    if (payload.groups.isEmpty()) {
        DdText(
            text = payload.body.orEmpty(),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = DdSpacing.Card, vertical = DdSpacing.Md),
            style = DdType.bodySmall,
            color = colors.inkSecondary,
        )
        return
    }
    var remaining = MAX_CARD_ROWS
    for ((index, group) in payload.groups.withIndex()) {
        if (remaining <= 0) break
        if (index > 0) DdHairline()
        group.label?.takeIf { it.isNotBlank() }?.let { label ->
            DdText(
                text = label,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = DdSpacing.Card, end = DdSpacing.Card, top = DdSpacing.Sm, bottom = DdSpacing.Xxs),
                style = DdType.chip,
                color = colors.inkTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val rows = group.rows.take(remaining)
        remaining -= rows.size
        for (row in rows) {
            DdListRow(
                title = row.title,
                subtitle = row.subtitle,
                value = row.value,
                subtitleMaxLines = row.subtitleMaxLines.coerceIn(1, 3),
            )
        }
    }
}

@Composable
private fun SysuCardHeader(
    icon: DdIcon,
    title: String,
    subtitle: String?,
    count: String?,
) {
    val colors = deepDeepColors()
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = DdSpacing.Card,
                    end = DdSpacing.Card,
                    top = DdSpacing.Md,
                    bottom = if (subtitle == null) DdSpacing.Md else DdSpacing.Xs,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DdIcon(icon = icon, tint = colors.accent, size = 16.dp)
            Spacer(Modifier.width(DdSpacing.Sm))
            DdText(
                text = title,
                modifier = Modifier.weight(1f),
                style = DdType.label,
                color = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!count.isNullOrBlank()) {
                Spacer(Modifier.width(DdSpacing.Sm))
                DdText(
                    text = count,
                    style = DdType.chip,
                    color = colors.inkTertiary,
                    maxLines = 1,
                )
            }
        }
        if (!subtitle.isNullOrBlank()) {
            DdText(
                text = subtitle,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = DdSpacing.Card, end = DdSpacing.Card, bottom = DdSpacing.Md),
                style = DdType.chip,
                color = colors.inkTertiary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun iconFor(kind: String): DdIcon = when (SysuCardKind.fromWire(kind)) {
    SysuCardKind.Schedule -> DdIcons.Clock
    SysuCardKind.Grades -> DdIcons.Book
    SysuCardKind.Exams -> DdIcons.Clock
    SysuCardKind.Lms -> DdIcons.Book
    SysuCardKind.Todo -> DdIcons.Check
    SysuCardKind.Gym -> DdIcons.Target
    SysuCardKind.Netpay -> DdIcons.Cloud
    null -> DdIcons.Info
}

private fun titleFor(host: PluginHost, kind: String): String = when (SysuCardKind.fromWire(kind)) {
    SysuCardKind.Schedule -> host.t("课表", "Timetable")
    SysuCardKind.Grades -> host.t("成绩", "Grades")
    SysuCardKind.Exams -> host.t("考试安排", "Exams")
    SysuCardKind.Lms -> host.t("在线教学", "LMS")
    SysuCardKind.Todo -> host.t("作业与待办", "Assignments")
    SysuCardKind.Gym -> host.t("体育场馆空位", "Venue availability")
    SysuCardKind.Netpay -> host.t("校园网服务", "Campus network")
    null -> host.t("校园服务", "Campus services")
}

private fun PluginHost.t(zh: String, en: String): String =
    text(localizedText(default = zh, zh = zh, en = en))
