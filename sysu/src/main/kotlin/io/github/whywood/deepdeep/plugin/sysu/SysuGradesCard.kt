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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.whywood.deepdeep.design.DdSpacing
import io.github.whywood.deepdeep.design.DdText
import io.github.whywood.deepdeep.design.deepDeepColors
import io.github.whywood.deepdeep.plugin.api.PluginHost
import io.github.whywood.deepdeep.plugin.cli.card.CliCardModel

@Composable
internal fun SysuGradesCard(host: PluginHost, model: CliCardModel) {
    val plan = remember(model.sections, model.items) { SysuCardFormat.gradesPlan(model) }
    if (plan == null) {
        SysuFallbackListCard(host, model)
        return
    }
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
        val rows = plan.rows.take(4)
        rows.forEachIndexed { index, row ->
            if (index > 0) {
                Box(
                    Modifier
                        .fillMaxWidth()
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
                Column(Modifier.weight(1f)) {
                    DdText(
                        text = row.name,
                        style = SysuCardType.bodyStrong,
                        color = colors.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    row.code?.takeIf { it.isNotBlank() }?.let { code ->
                        Spacer(Modifier.height(1.dp))
                        DdText(
                            text = code,
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
        if (plan.rows.size > rows.size) {
            DdText(
                text = host.t("还有 ${plan.rows.size - rows.size} 门", "${plan.rows.size - rows.size} more"),
                modifier = Modifier.padding(top = DdSpacing.Sm),
                style = SysuCardType.caption,
                color = colors.inkTertiary,
            )
        }
        val note = model.note?.takeIf { it.isNotBlank() }
        if (note != null) {
            SysuFootLine(left = note, right = null)
        }
    }
}
