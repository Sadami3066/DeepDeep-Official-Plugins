package io.github.whywood.deepdeep.plugin.wecom

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.whywood.deepdeep.design.DdIcon
import io.github.whywood.deepdeep.design.DdIcons
import io.github.whywood.deepdeep.design.DdRowDivider
import io.github.whywood.deepdeep.design.DdShapes
import io.github.whywood.deepdeep.design.DdSpacing
import io.github.whywood.deepdeep.design.DdText
import io.github.whywood.deepdeep.design.deepDeepColors
import io.github.whywood.deepdeep.plugin.api.PluginHost
import io.github.whywood.deepdeep.plugin.cli.card.CliCardItem
import io.github.whywood.deepdeep.plugin.cli.card.CliCardModel

private const val WECOM_GROUP_ROWS = 4
private const val WECOM_MESSAGE_ROWS = 3

@Composable
internal fun WecomGroupsCard(host: PluginHost, kind: WecomCommandKind, model: CliCardModel) {
    val phase = model.wecomPhase()
    if (phase != WecomCardPhase.READY) {
        WecomStateCard(host, kind, model)
        return
    }
    val colors = deepDeepColors()
    val rows = model.items.take(WECOM_GROUP_ROWS)
    val total = maxOf(model.total, model.items.size)
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
            rows.forEachIndexed { index, item ->
                if (index > 0) DdRowDivider()
                GroupChatRow(item = item, index = index)
            }
            WecomMoreLine(
                host = host,
                kind = kind,
                hidden = total - rows.size,
                hasMore = !model.note.isNullOrBlank(),
            )
        }
    }
}

@Composable
private fun GroupChatRow(item: CliCardItem, index: Int) {
    val colors = deepDeepColors()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(vertical = DdSpacing.Xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(34.dp)
                .clip(CircleShape)
                .background(if (index == 0) colors.accent else Color.Transparent),
        )
        Spacer(Modifier.width(11.dp))
        DdText(
            text = "%02d".format(index + 1),
            modifier = Modifier.width(22.dp),
            style = WecomCardType.micro.copy(fontWeight = FontWeight.SemiBold),
            color = colors.inkTertiary,
            maxLines = 1,
        )
        Column(Modifier.weight(1f)) {
            DdText(
                text = item.title,
                style = WecomCardType.name,
                color = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            item.subtitle?.takeIf { it.isNotBlank() }?.let { subtitle ->
                Spacer(Modifier.height(2.dp))
                DdText(
                    text = wecomShortText(subtitle) ?: subtitle,
                    style = WecomCardType.secondary,
                    color = colors.inkSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        item.meta?.takeIf { it.isNotBlank() }?.let { meta ->
            Spacer(Modifier.width(DdSpacing.Sm))
            val parts = wecomCountParts(meta)
            if (parts != null) {
                Row(verticalAlignment = Alignment.Bottom) {
                    DdText(
                        text = parts.value,
                        style = WecomCardType.number,
                        color = colors.inkSecondary,
                        maxLines = 1,
                    )
                    if (parts.unit.isNotBlank()) {
                        Spacer(Modifier.width(2.dp))
                        DdText(
                            text = parts.unit,
                            style = WecomCardType.tiny,
                            color = colors.inkTertiary,
                            maxLines = 1,
                        )
                    }
                }
            } else {
                DdText(
                    text = meta,
                    style = WecomCardType.micro,
                    color = colors.inkTertiary,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
internal fun WecomMessagesCard(host: PluginHost, kind: WecomCommandKind, model: CliCardModel) {
    val phase = model.wecomPhase()
    if (phase != WecomCardPhase.READY) {
        WecomStateCard(host, kind, model)
        return
    }
    val colors = deepDeepColors()
    val rows = model.items.take(WECOM_MESSAGE_ROWS)
    val total = maxOf(model.total, model.items.size)
    val trailing = listOfNotNull(
        wecomCountLabel(host, kind, total),
        model.context?.let(::wecomShortText),
    ).joinToString(" · ").ifBlank { null }
    WecomCardSurface {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = DdSpacing.Lg, end = DdSpacing.Lg, top = DdSpacing.Lg, bottom = DdSpacing.Md),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DdText(
                    text = host.t(kind.listLabelZh, kind.listLabelEn),
                    modifier = Modifier.weight(1f),
                    style = WecomCardType.eyebrow,
                    color = colors.inkTertiary,
                    maxLines = 1,
                )
                trailing?.let { line ->
                    Spacer(Modifier.width(DdSpacing.Sm))
                    DdText(
                        text = line,
                        modifier = Modifier.widthIn(max = 210.dp),
                        style = WecomCardType.secondary,
                        color = colors.inkTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.height(DdSpacing.Md))
            Box(Modifier.fillMaxWidth()) {
                Canvas(Modifier.matchParentSize()) {
                    val x = 15.dp.toPx()
                    val top = 18.dp.toPx()
                    val bottom = size.height - 18.dp.toPx()
                    if (bottom > top) {
                        drawLine(
                            color = colors.hairline,
                            start = Offset(x, top),
                            end = Offset(x, bottom),
                            strokeWidth = 2.dp.toPx(),
                            cap = StrokeCap.Round,
                        )
                    }
                }
                Column(Modifier.fillMaxWidth()) {
                    rows.forEach { item -> MessageRow(item = item) }
                }
            }
            WecomMoreLine(
                host = host,
                kind = kind,
                hidden = total - rows.size,
                hasMore = !model.note.isNullOrBlank(),
            )
        }
    }
}

@Composable
private fun MessageRow(item: CliCardItem) {
    val colors = deepDeepColors()
    val parts = wecomMessageParts(item)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
    ) {
        WecomAvatar(
            initial = wecomInitial(item.title),
            size = 30.dp,
            style = WecomCardType.micro.copy(
                fontSize = 12.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.SemiBold,
            ),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DdText(
                    text = item.title,
                    modifier = Modifier.weight(1f),
                    style = WecomCardType.sender,
                    color = colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                parts.time?.let { time ->
                    Spacer(Modifier.width(DdSpacing.Sm))
                    DdText(
                        text = time,
                        style = WecomCardType.micro,
                        color = colors.inkTertiary,
                        maxLines = 1,
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            parts.fileName?.let { name ->
                MessageFileChip(name = name)
            } ?: parts.body?.let { body ->
                DdText(
                    text = body,
                    style = WecomCardType.body,
                    color = colors.ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun MessageFileChip(name: String) {
    val colors = deepDeepColors()
    Row(
        modifier = Modifier
            .clip(DdShapes.Small)
            .background(wecomFill(colors))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DdIcon(icon = DdIcons.File, tint = colors.inkTertiary, size = 13.dp)
        Spacer(Modifier.width(6.dp))
        DdText(
            text = name,
            modifier = Modifier.widthIn(max = 210.dp),
            style = WecomCardType.secondary.copy(fontSize = 13.sp, lineHeight = 18.sp),
            color = colors.inkSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
