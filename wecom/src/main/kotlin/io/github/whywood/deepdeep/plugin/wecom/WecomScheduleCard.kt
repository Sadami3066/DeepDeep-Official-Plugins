package io.github.whywood.deepdeep.plugin.wecom

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.whywood.deepdeep.design.DdIcon
import io.github.whywood.deepdeep.design.DdIcons
import io.github.whywood.deepdeep.design.DdSpacing
import io.github.whywood.deepdeep.design.DdText
import io.github.whywood.deepdeep.design.deepDeepColors
import io.github.whywood.deepdeep.plugin.api.PluginHost
import io.github.whywood.deepdeep.plugin.cli.card.CliCardItem
import io.github.whywood.deepdeep.plugin.cli.card.CliCardModel

private const val WECOM_SCHEDULE_ROWS = 4
private val WECOM_TIME_COLUMN = 50.dp
private val WECOM_RAIL_WIDTH = 18.dp
private val WECOM_DOT_CENTER_X = 59.dp
private val WECOM_DOT_CENTER_Y = 16.dp

@Composable
internal fun WecomScheduleCard(host: PluginHost, kind: WecomCommandKind, model: CliCardModel) {
    val phase = model.wecomPhase()
    if (phase != WecomCardPhase.READY) {
        WecomStateCard(host, kind, model)
        return
    }
    var expanded by remember(model) { mutableStateOf(false) }
    val rows = if (expanded) model.items else model.items.take(WECOM_SCHEDULE_ROWS)
    val total = maxOf(model.total, model.items.size)
    val entries = rows.map { wecomScheduleParts(it) }
    val nextIndex = entries.indexOfFirst { it.isUpcoming() }
    val groups = entries.withIndex().groupBy { it.value.dateKey }
    WecomCardSurface {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = DdSpacing.Lg, end = DdSpacing.Lg, top = DdSpacing.Lg, bottom = DdSpacing.Md),
        ) {
            WecomHead(
                title = host.t(kind.listLabelZh, kind.listLabelEn),
                detail = model.context?.let(::wecomShortText),
                trailing = wecomCountLabel(host, kind, total),
            )
            Spacer(Modifier.height(DdSpacing.Sm))
            groups.entries.forEachIndexed { groupIndex, entry ->
                if (groupIndex > 0) Spacer(Modifier.height(DdSpacing.Sm))
                ScheduleDayBand(host = host, dateKey = entry.key)
                val events = entry.value
                events.forEachIndexed { eventIndex, indexed ->
                    ScheduleEvent(
                        host = host,
                        item = rows[indexed.index],
                        parts = indexed.value,
                        isFirst = eventIndex == 0,
                        isLast = eventIndex == events.lastIndex,
                        isNext = indexed.index == nextIndex,
                        expanded = expanded,
                    )
                }
            }
            WecomExpandToggle(
                host = host,
                kind = kind,
                expanded = expanded,
                hidden = model.items.size - WECOM_SCHEDULE_ROWS,
                onToggle = { expanded = !expanded },
            )
            WecomMoreLine(
                host = host,
                kind = kind,
                hasMore = !model.note.isNullOrBlank(),
            )
        }
    }
}

@Composable
private fun ScheduleDayBand(host: PluginHost, dateKey: String?) {
    val colors = deepDeepColors()
    val day = wecomDayLabel(dateKey, host.chinese) ?: wecomShortText(dateKey) ?: return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = DdSpacing.Xs, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DdText(
            text = day,
            style = WecomCardType.sender,
            color = colors.ink,
            maxLines = 1,
        )
        wecomWeekdayLabel(dateKey, host.chinese)?.let { weekday ->
            Spacer(Modifier.width(6.dp))
            DdText(
                text = weekday,
                style = WecomCardType.micro,
                color = colors.inkTertiary,
                maxLines = 1,
            )
        }
        if (wecomIsToday(dateKey)) {
            Spacer(Modifier.width(6.dp))
            WecomTodayChip(host)
        }
    }
}

@Composable
private fun ScheduleEvent(
    host: PluginHost,
    item: CliCardItem,
    parts: WecomScheduleParts,
    isFirst: Boolean,
    isLast: Boolean,
    isNext: Boolean,
    expanded: Boolean,
) {
    val colors = deepDeepColors()
    Box(Modifier.fillMaxWidth()) {
        Canvas(Modifier.matchParentSize()) {
            if (!isFirst || !isLast) {
                val x = WECOM_DOT_CENTER_X.toPx()
                val start = if (isFirst) WECOM_DOT_CENTER_Y.toPx() else 0f
                val end = if (isLast) WECOM_DOT_CENTER_Y.toPx() else size.height
                if (end > start) {
                    drawLine(
                        color = colors.hairline,
                        start = Offset(x, start),
                        end = Offset(x, end),
                        strokeWidth = 2.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.width(WECOM_TIME_COLUMN)) {
                DdText(
                    text = if (parts.allDay) host.t("全天", "All day") else parts.start.orEmpty(),
                    style = if (parts.allDay) WecomCardType.sender else WecomCardType.number,
                    color = colors.ink,
                    maxLines = 1,
                )
                parts.end?.let { end ->
                    Spacer(Modifier.height(2.dp))
                    DdText(
                        text = end,
                        style = WecomCardType.micro,
                        color = colors.inkTertiary,
                        maxLines = 1,
                    )
                }
            }
            Box(Modifier.width(WECOM_RAIL_WIDTH)) {
                Box(
                    modifier = Modifier
                        .padding(start = 4.dp, top = 5.dp)
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(if (isNext) colors.accent else colors.surface)
                        .border(2.dp, if (isNext) colors.accent else colors.inkTertiary, CircleShape),
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 2.dp),
            ) {
                DdText(
                    text = item.title,
                    style = WecomCardType.name,
                    color = colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                parts.location?.let { place ->
                    Spacer(Modifier.height(3.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        DdIcon(icon = DdIcons.Pin, tint = colors.inkTertiary, size = 12.dp)
                        Spacer(Modifier.width(DdSpacing.Xs))
                        DdText(
                            text = place,
                            modifier = Modifier.weight(1f, fill = false),
                            style = WecomCardType.secondary,
                            color = colors.inkSecondary,
                            maxLines = if (expanded) Int.MAX_VALUE else 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                item.body?.takeIf { it.isNotBlank() }?.let { details ->
                    Spacer(Modifier.height(3.dp))
                    WecomDetailText(
                        text = details,
                        maxLines = if (expanded) Int.MAX_VALUE else 2,
                    )
                }
            }
            item.meta?.takeIf { it.isNotBlank() }?.let { people ->
                Spacer(Modifier.width(DdSpacing.Sm))
                DdText(
                    text = people,
                    modifier = Modifier.padding(top = 1.dp),
                    style = WecomCardType.secondary.copy(
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    ),
                    color = colors.inkSecondary,
                    maxLines = 1,
                )
            }
        }
    }
}
