package io.github.whywood.deepdeep.plugin.wecom

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.whywood.deepdeep.design.DdIcon
import io.github.whywood.deepdeep.design.DdIcons
import io.github.whywood.deepdeep.design.DdRowDivider
import io.github.whywood.deepdeep.design.DdSpacing
import io.github.whywood.deepdeep.design.DdText
import io.github.whywood.deepdeep.design.deepDeepColors
import io.github.whywood.deepdeep.plugin.api.PluginHost
import io.github.whywood.deepdeep.plugin.cli.card.CliCardItem
import io.github.whywood.deepdeep.plugin.cli.card.CliCardModel

private const val WECOM_TODO_ROWS = 4

@Composable
internal fun WecomTodoCard(host: PluginHost, kind: WecomCommandKind, model: CliCardModel) {
    val phase = model.wecomPhase()
    if (phase != WecomCardPhase.READY) {
        WecomStateCard(host, kind, model)
        return
    }
    var expanded by remember(model) { mutableStateOf(false) }
    val rows = if (expanded) model.items else model.items.take(WECOM_TODO_ROWS)
    val total = maxOf(model.total, model.items.size)
    WecomCardSurface {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = DdSpacing.Lg, end = DdSpacing.Lg, top = DdSpacing.Lg, bottom = DdSpacing.Md),
        ) {
            WecomHead(
                title = host.t(kind.listLabelZh, kind.listLabelEn),
                trailing = wecomCountLabel(host, kind, total),
            )
            Spacer(Modifier.height(DdSpacing.Xs))
            rows.forEachIndexed { index, item ->
                if (index > 0) DdRowDivider()
                TodoRow(item = item, expanded = expanded)
            }
            WecomExpandToggle(
                host = host,
                kind = kind,
                expanded = expanded,
                hidden = model.items.size - WECOM_TODO_ROWS,
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
private fun TodoRow(item: CliCardItem, expanded: Boolean) {
    val colors = deepDeepColors()
    val parts = wecomTodoParts(item)
    val subtitle = listOfNotNull(parts.status, parts.followers).joinToString(" · ").ifBlank { null }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 58.dp)
            .padding(vertical = DdSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TodoCheck(done = parts.done)
        Spacer(Modifier.width(DdSpacing.Md))
        Column(Modifier.weight(1f)) {
            DdText(
                text = item.title,
                style = if (parts.done) {
                    WecomCardType.name.copy(textDecoration = TextDecoration.LineThrough)
                } else {
                    WecomCardType.name
                },
                color = if (parts.done) colors.inkTertiary else colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            subtitle?.let { line ->
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (parts.status != null) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(wecomTodoDot(parts)),
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    DdText(
                        text = line,
                        modifier = Modifier.weight(1f, fill = false),
                        style = WecomCardType.secondary,
                        color = if (parts.done) colors.inkTertiary else colors.inkSecondary,
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
        parts.dueDate?.let { date ->
            Spacer(Modifier.width(DdSpacing.Sm))
            Column(horizontalAlignment = Alignment.End) {
                DdText(
                    text = date,
                    style = WecomCardType.number,
                    color = colors.ink,
                    maxLines = 1,
                )
                parts.dueTime?.let { time ->
                    Spacer(Modifier.height(1.dp))
                    DdText(
                        text = time,
                        style = WecomCardType.micro,
                        color = colors.inkTertiary,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun TodoCheck(done: Boolean) {
    val colors = deepDeepColors()
    if (done) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(colors.success),
            contentAlignment = Alignment.Center,
        ) {
            DdIcon(icon = DdIcons.Check, tint = colors.onAccent, size = 12.dp)
        }
    } else {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .border(1.5.dp, colors.inkTertiary, CircleShape),
        )
    }
}

@Composable
private fun wecomTodoDot(parts: WecomTodoParts): Color {
    val colors = deepDeepColors()
    return when {
        parts.done -> colors.success
        parts.deleted -> colors.inkTertiary
        else -> colors.warning
    }
}
