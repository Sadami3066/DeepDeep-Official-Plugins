package io.github.whywood.deepdeep.plugin.sysu

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.whywood.deepdeep.design.DdSpacing
import io.github.whywood.deepdeep.design.DdText
import io.github.whywood.deepdeep.design.deepDeepColors
import io.github.whywood.deepdeep.plugin.api.PluginHost
import io.github.whywood.deepdeep.plugin.cli.card.CliCardModel
import io.github.whywood.deepdeep.plugin.cli.card.CliCardState

private const val GRADES_PREVIEW = 4

@Composable
internal fun SysuGradesCard(host: PluginHost, model: CliCardModel) {
    val plan = remember(model.sections, model.items) { SysuCardFormat.gradesPlan(model) }
    if (plan == null || plan.rows.isEmpty()) {
        SysuEmptyCard(
            host,
            CliCardModel(
                kind = SysuCardKind.Grades.wire,
                state = CliCardState.EMPTY,
                context = model.context,
            ),
        )
        return
    }
    var expanded by remember { mutableStateOf(false) }
    val colors = deepDeepColors()
    SysuCardSurface {
        SysuTopLine(
            kicker = plan.semester?.let { "$it · ${host.t("成绩", "Grades")}" } ?: host.t("成绩", "Grades"),
            title = host.t("成绩单", "Grades"),
            trailing = model.trailing,
        )
        Spacer(Modifier.height(DdSpacing.Md))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
        ) {
            Column(Modifier.weight(1f)) {
                DdText(
                    text = plan.gpa?.let { SysuFormat.formatDecimal(it, 3) } ?: "—",
                    style = SysuCardType.hero,
                    color = colors.ink,
                    maxLines = 1,
                )
                Spacer(Modifier.height(2.dp))
                DdText(
                    text = host.t("加权绩点（自算）", "Weighted GPA (self-calculated)"),
                    style = SysuCardType.caption,
                    color = colors.inkSecondary,
                    maxLines = 1,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                DdText(
                    text = plan.credits?.let { SysuFormat.formatDecimal(it, 1) } ?: "—",
                    style = SysuCardType.heroSmall,
                    color = colors.inkSecondary,
                    maxLines = 1,
                )
                Spacer(Modifier.height(2.dp))
                DdText(
                    text = host.t("学分", "credits"),
                    style = SysuCardType.caption,
                    color = colors.inkSecondary,
                    maxLines = 1,
                )
            }
        }
        Spacer(Modifier.height(DdSpacing.Md))
        var shown = 0
        val limit = if (expanded) Int.MAX_VALUE else GRADES_PREVIEW
        plan.groups.forEach { group ->
            if (shown >= limit) return@forEach
            if (group.label.isNotBlank() && plan.groups.size > 1) {
                Spacer(Modifier.height(if (shown > 0) DdSpacing.Sm else DdSpacing.Xs))
                DdText(
                    text = group.label,
                    style = SysuCardType.caption,
                    color = colors.inkTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            group.rows.forEach { row ->
                if (shown >= limit) return@forEach
                if (shown > 0) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(colors.hairline.copy(alpha = 0.55f)),
                    )
                }
                SysuGradeRowView(host = host, row = row)
                shown += 1
            }
        }
        SysuExpandToggle(
            host = host,
            hidden = (plan.rows.size - GRADES_PREVIEW).coerceAtLeast(0),
            expanded = expanded,
            onToggle = { expanded = !expanded },
        )
    }
}

@Composable
private fun SysuGradeRowView(host: PluginHost, row: SysuGradeRow) {
    val colors = deepDeepColors()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = DdSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            DdText(
                text = row.name,
                style = SysuCardType.bodyStrong,
                color = colors.ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val metaLine = listOfNotNull(
                row.code?.takeIf { it.isNotBlank() },
                row.point?.let { host.t("绩点 ${SysuFormat.formatDecimal(it, 1)}", "GP ${SysuFormat.formatDecimal(it, 1)}") },
            ).joinToString(" · ")
            if (metaLine.isNotEmpty()) {
                Spacer(Modifier.height(1.dp))
                DdText(
                    text = metaLine,
                    style = SysuCardType.caption,
                    color = colors.inkTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        row.credits?.let { credits ->
            Spacer(Modifier.width(DdSpacing.Sm))
            DdText(
                text = host.t("${SysuFormat.formatDecimal(credits, 1)} 学分", "${SysuFormat.formatDecimal(credits, 1)} cr"),
                style = SysuCardType.caption,
                color = colors.inkSecondary,
                maxLines = 1,
            )
        }
        row.score?.takeIf { it.isNotBlank() }?.let { score ->
            Spacer(Modifier.width(DdSpacing.Sm))
            val (foreground, background) = sysuGradeColor(colors, score)
            SysuPill(text = score, foreground = foreground, background = background)
        }
    }
}
