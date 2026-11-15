package io.github.whywood.deepdeep.plugin.sysu

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.whywood.deepdeep.design.DdSpacing
import io.github.whywood.deepdeep.design.DdText
import io.github.whywood.deepdeep.design.deepDeepColors
import io.github.whywood.deepdeep.plugin.api.PluginHost
import io.github.whywood.deepdeep.plugin.cli.card.CliCardModel

@Composable
internal fun SysuLmsCard(host: PluginHost, model: CliCardModel) {
    val plan = remember(model.sections, model.items) { SysuCardFormat.lmsPlan(model) }
    if (plan == null) {
        SysuFallbackListCard(host, model)
        return
    }
    val colors = deepDeepColors()
    SysuCardSurface {
        SysuTopLine(
            kicker = host.t("在线教学平台", "LMS"),
            title = host.t("在修课程", "Courses"),
            trailing = model.trailing,
        )
        plan.averageProgress?.let { average ->
            Spacer(Modifier.height(DdSpacing.Xs))
            DdText(
                text = host.t("平均进度 $average%", "Average progress $average%"),
                style = SysuCardType.caption,
                color = colors.inkSecondary,
                maxLines = 1,
            )
        }
        Spacer(Modifier.height(DdSpacing.Sm))
        val rows = plan.courses.take(4)
        rows.forEachIndexed { index, course ->
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
                SysuProgressRing(progress = course.progress)
                Spacer(Modifier.width(DdSpacing.Md))
                Column(Modifier.weight(1f)) {
                    DdText(
                        text = course.name,
                        style = SysuCardType.bodyStrong,
                        color = colors.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val end = course.end?.toString()
                    if (end != null) {
                        Spacer(Modifier.height(2.dp))
                        DdText(
                            text = host.t("结课 $end", "ends $end"),
                            style = SysuCardType.caption,
                            color = colors.inkSecondary,
                            maxLines = 1,
                        )
                    }
                }
                course.courseId?.takeIf { it.isNotBlank() }?.let { id ->
                    Spacer(Modifier.width(DdSpacing.Sm))
                    DdText(
                        text = "#$id",
                        style = SysuCardType.caption,
                        color = colors.inkTertiary,
                        maxLines = 1,
                    )
                }
            }
        }
        if (plan.courses.size > rows.size) {
            DdText(
                text = host.t("还有 ${plan.courses.size - rows.size} 门", "${plan.courses.size - rows.size} more"),
                modifier = Modifier.padding(top = DdSpacing.Sm),
                style = SysuCardType.caption,
                color = colors.inkTertiary,
            )
        }
    }
}

@Composable
private fun SysuProgressRing(progress: Int?) {
    val colors = deepDeepColors()
    val value = (progress ?: 0).coerceIn(0, 100)
    val ringColor = when {
        progress == null -> colors.inkTertiary
        value >= 100 -> colors.success
        else -> colors.accent
    }
    Box(Modifier.size(38.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 3.dp.toPx()
            val inset = stroke / 2f
            val diameter = size.minDimension - stroke
            drawCircle(
                color = colors.wash,
                radius = diameter / 2f,
                style = Stroke(stroke),
            )
            if (progress != null && value > 0) {
                drawArc(
                    color = ringColor,
                    startAngle = -90f,
                    sweepAngle = 3.6f * value,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = Size(diameter, diameter),
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
            }
        }
        DdText(
            text = progress?.let { "$it%" } ?: "—",
            style = SysuCardType.micro.copy(fontSize = 9.sp, letterSpacing = 0.sp),
            color = colors.inkSecondary,
            maxLines = 1,
        )
    }
}
