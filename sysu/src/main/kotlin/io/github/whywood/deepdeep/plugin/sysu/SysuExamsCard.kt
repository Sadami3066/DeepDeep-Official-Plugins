package io.github.whywood.deepdeep.plugin.sysu

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.whywood.deepdeep.design.DdSpacing
import io.github.whywood.deepdeep.design.DdText
import io.github.whywood.deepdeep.design.DdType
import io.github.whywood.deepdeep.design.deepDeepColors
import io.github.whywood.deepdeep.plugin.api.PluginHost
import io.github.whywood.deepdeep.plugin.cli.card.CliCardModel

private const val EXAMS_PREVIEW = 3

@Composable
internal fun SysuExamsCard(host: PluginHost, model: CliCardModel) {
    val plan = remember(model.sections, model.items) { SysuCardFormat.examsPlan(model) }
    if (plan == null || plan.entries.isEmpty()) {
        SysuFallbackListCard(host, model)
        return
    }
    var expanded by remember { mutableStateOf(false) }
    val colors = deepDeepColors()
    val entries = remember(plan) {
        plan.entries.sortedWith(
            compareBy(
                { (it.daysUntil ?: 0L) < 0L },
                { it.date?.toEpochDay() ?: Long.MAX_VALUE },
                { it.time.orEmpty() },
            ),
        )
    }
    val visible = if (expanded) entries else entries.take(EXAMS_PREVIEW)
    val pastCount = entries.count { it.daysUntil != null && it.daysUntil < 0 }
    SysuCardSurface {
        SysuTopLine(
            kicker = plan.term?.let { "$it · ${host.t("考试安排", "Exams")}" } ?: host.t("考试安排", "Exams"),
            title = host.t("${entries.size} 场考试", "${entries.size} exams"),
            trailing = null,
        )
        Spacer(Modifier.height(DdSpacing.Sm))
        visible.forEachIndexed { index, entry ->
            if (index > 0) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = DdSpacing.Xs)
                        .height(1.dp)
                        .background(colors.hairline.copy(alpha = 0.55f)),
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = DdSpacing.Sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SysuExamDateBlock(host = host, entry = entry)
                Spacer(Modifier.width(DdSpacing.Md))
                Column(Modifier.weight(1f)) {
                    DdText(
                        text = entry.name,
                        style = DdType.label.copy(fontSize = 14.5.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold),
                        color = colors.ink,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val detail = listOfNotNull(entry.time, entry.place).joinToString(" · ")
                    if (detail.isNotBlank()) {
                        Spacer(Modifier.height(2.dp))
                        DdText(
                            text = detail,
                            style = SysuCardType.caption,
                            color = colors.inkSecondary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (entry.sections != null || entry.details.isNotEmpty()) {
                        Spacer(Modifier.height(2.dp))
                        SysuDetailLines(
                            lines = listOfNotNull(entry.sections) + entry.details,
                        )
                    }
                }
                Spacer(Modifier.width(DdSpacing.Sm))
                SysuCountdownPill(host = host, days = entry.daysUntil)
            }
        }
        SysuExpandToggle(
            host = host,
            hidden = (entries.size - EXAMS_PREVIEW).coerceAtLeast(0),
            expanded = expanded,
            onToggle = { expanded = !expanded },
        )
        val footnote = when {
            pastCount > 0 -> host.t("已结束 $pastCount 场", "$pastCount ended")
            else -> null
        }
        SysuFootLine(left = footnote, right = null)
    }
}

@Composable
private fun SysuExamDateBlock(host: PluginHost, entry: SysuExamEntry) {
    val colors = deepDeepColors()
    val soon = entry.daysUntil != null && entry.daysUntil in 0..7
    Column(
        modifier = Modifier
            .size(width = 50.dp, height = 52.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (soon) colors.accentSoft else colors.wash),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        DdText(
            text = entry.date?.let { hostMonth(host, it.monthValue) } ?: "—",
            style = SysuCardType.caption.copy(fontSize = 9.5.sp, lineHeight = 12.sp),
            color = if (soon) colors.accent else colors.inkSecondary,
            maxLines = 1,
        )
        DdText(
            text = entry.date?.dayOfMonth?.toString()?.padStart(2, '0') ?: "—",
            style = DdType.label.copy(fontSize = 21.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
            color = if (soon) colors.accent else colors.ink,
            maxLines = 1,
        )
    }
}

private fun hostMonth(host: PluginHost, month: Int): String {
    val english = listOf(
        "Jan", "Feb", "Mar", "Apr", "May", "Jun",
        "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
    ).getOrNull(month - 1) ?: month.toString()
    return host.t("${month}月", english)
}
