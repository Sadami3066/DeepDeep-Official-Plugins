package io.github.whywood.deepdeep.plugin.feishu

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.whywood.deepdeep.design.DdAlpha
import io.github.whywood.deepdeep.design.DdSpacing
import io.github.whywood.deepdeep.design.DdText
import io.github.whywood.deepdeep.design.DdType
import io.github.whywood.deepdeep.design.deepDeepColors
import io.github.whywood.deepdeep.plugin.api.PluginHost

@Composable
internal fun FeishuAgendaCard(host: PluginHost, model: FeishuCardModel) {
    FeishuCardShell {
        when (FeishuKindState(host, model)) {
            FeishuKindStateKind.ERROR -> FeishuCardErrorBody(host, model.openUrl)
            FeishuKindStateKind.LOADING -> AgendaLoading()
            FeishuKindStateKind.EMPTY -> AgendaEmpty(host)
            FeishuKindStateKind.READY -> AgendaContent(host, model)
        }
    }
}

@Composable
private fun AgendaContent(host: PluginHost, model: FeishuCardModel) {
    val colors = deepDeepColors()
    val count = model.total.coerceAtLeast(model.items.size)
    val rows = model.items.take(FEISHU_CARD_AGENDA_ROWS)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = DdSpacing.Lg, end = DdSpacing.Lg, top = DdSpacing.Md, bottom = DdSpacing.Sm),
    ) {
        FeishuCardHead(
            host = host,
            title = model.title?.takeIf { it.isNotBlank() } ?: host.t("日程", "Agenda"),
            detail = cardRangeText(model.context),
            trailing = model.trailing?.takeIf { it.isNotBlank() }
                ?: if (count > 0) host.tf("%d 个", "%d events", count) else null,
        )
        Spacer(Modifier.height(DdSpacing.Xs))
        rows.forEachIndexed { index, item ->
            AgendaRow(
                host = host,
                item = item,
                first = index == 0,
                last = index == rows.lastIndex,
            )
        }
        val hidden = (count - rows.size).coerceAtLeast(0)
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
private fun AgendaRow(host: PluginHost, item: FeishuCardItem, first: Boolean, last: Boolean) {
    val colors = deepDeepColors()
    val parts = item.agendaTimeParts()
    val location = item.agendaLocation()
    val today = todayLabel(parts.date, host)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        verticalAlignment = Alignment.Top,
    ) {
        Column(
            modifier = Modifier
                .width(48.dp)
                .padding(top = 2.dp),
            horizontalAlignment = Alignment.End,
        ) {
            DdText(
                text = parts.start ?: host.t("全天", "All day"),
                style = feishuItemTitleStyle(),
                color = colors.ink,
                maxLines = 1,
                textAlign = TextAlign.End,
            )
            parts.end?.let { end ->
                Spacer(Modifier.height(2.dp))
                DdText(
                    text = end,
                    style = DdType.numeric,
                    color = colors.inkTertiary,
                    maxLines = 1,
                    textAlign = TextAlign.End,
                )
            }
            today?.let { label ->
                Spacer(Modifier.height(2.dp))
                DdText(
                    text = label,
                    style = DdType.numeric,
                    color = colors.accent,
                    maxLines = 1,
                    textAlign = TextAlign.End,
                )
            }
        }
        Spacer(Modifier.width(DdSpacing.Sm))
        Canvas(
            Modifier
                .width(12.dp)
                .fillMaxHeight(),
        ) {
            val centerX = size.width / 2f
            val dotY = 9.dp.toPx()
            val radius = 4.dp.toPx()
            val lineColor = colors.hairline
            val lineWidth = 1.dp.toPx()
            if (!first) {
                drawLine(lineColor, Offset(centerX, 0f), Offset(centerX, dotY - radius), strokeWidth = lineWidth)
            }
            if (!last) {
                drawLine(lineColor, Offset(centerX, dotY + radius), Offset(centerX, size.height), strokeWidth = lineWidth)
            }
            if (parts.allDay) {
                drawCircle(
                    color = colors.inkTertiary.copy(alpha = DdAlpha.Muted),
                    radius = radius,
                    center = Offset(centerX, dotY),
                    style = Stroke(width = 1.6.dp.toPx()),
                )
            } else {
                drawCircle(
                    color = colors.accent.copy(alpha = DdAlpha.TrackLight),
                    radius = radius + 3.dp.toPx(),
                    center = Offset(centerX, dotY),
                )
                drawCircle(color = colors.accent, radius = radius, center = Offset(centerX, dotY))
            }
        }
        Spacer(Modifier.width(DdSpacing.Sm))
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(bottom = DdSpacing.Lg),
        ) {
            DdText(
                text = item.title,
                style = feishuItemTitleStyle(),
                color = colors.ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            location?.let { place ->
                Spacer(Modifier.height(2.dp))
                DdText(
                    text = place,
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
private fun AgendaLoading() {
    val colors = deepDeepColors()
    Column(Modifier.padding(DdSpacing.Lg)) {
        Box(Modifier.height(12.dp).width(52.dp).background(colors.wash, RoundedCornerShape(4.dp)))
        Spacer(Modifier.height(DdSpacing.Md))
        repeat(2) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min)
                    .padding(vertical = DdSpacing.Sm),
            ) {
                Box(Modifier.height(14.dp).width(36.dp).background(colors.wash, RoundedCornerShape(4.dp)))
                Spacer(Modifier.width(DdSpacing.Md))
                Column(Modifier.weight(1f)) {
                    Box(Modifier.fillMaxWidth(0.6f).height(12.dp).background(colors.wash, RoundedCornerShape(4.dp)))
                    Spacer(Modifier.height(DdSpacing.Sm))
                    Box(Modifier.fillMaxWidth(0.35f).height(9.dp).background(colors.wash, RoundedCornerShape(4.dp)))
                }
            }
        }
    }
}

@Composable
private fun AgendaEmpty(host: PluginHost) {
    val colors = deepDeepColors()
    FeishuEmptyBody(
        title = host.t("这段时间没有日程", "No events in this range"),
        message = host.t(
            "换个日期看看，或者让我帮你安排",
            "Try another date, or ask me to schedule something",
        ),
    ) {
        Canvas(Modifier.width(22.dp).height(34.dp)) {
            val centerX = size.width / 2f
            drawLine(
                color = colors.hairline,
                start = Offset(centerX, 0f),
                end = Offset(centerX, size.height),
                strokeWidth = 1.dp.toPx(),
            )
            drawCircle(
                color = colors.inkTertiary,
                radius = 4.dp.toPx(),
                center = Offset(centerX, 8.dp.toPx()),
                style = Stroke(width = 1.6.dp.toPx()),
            )
        }
    }
}
