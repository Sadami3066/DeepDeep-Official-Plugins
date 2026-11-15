package io.github.whywood.deepdeep.plugin.sysu

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
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

@Composable
internal fun SysuScheduleCard(host: PluginHost, model: CliCardModel) {
    val plan = remember(model.sections, model.total) { SysuCardFormat.schedulePlan(model) }
    if (plan == null || plan.blocks.isEmpty()) {
        SysuFallbackListCard(host, model)
        return
    }
    SysuCardSurface {
        SysuTopLine(
            kicker = model.context,
            title = host.t("本周课表", "This week's timetable"),
            trailing = model.trailing,
        )
        Spacer(Modifier.height(DdSpacing.Md))
        SysuWeekGrid(plan)
        SysuFootLine(
            left = model.note,
            right = if (plan.todayCount > 0) {
                host.t("今天 ${plan.todayCount} 门", "${plan.todayCount} today")
            } else {
                host.t("今天没课", "No class today")
            },
        )
    }
}

@Composable
private fun SysuWeekGrid(plan: SysuSchedulePlan) {
    val colors = deepDeepColors()
    val rowHeight = if (plan.maxPeriod - plan.minPeriod >= 12) 20.dp else 22.dp
    val labelWidth = 22.dp
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.width(labelWidth))
            plan.days.forEach { day ->
                SysuDayHeader(
                    day = day,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(Modifier.height(DdSpacing.Xs))
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val totalRows = (plan.maxPeriod - plan.minPeriod + 1).coerceAtLeast(1)
            val gridHeight = rowHeight * totalRows
            val colWidth = (maxWidth - labelWidth) / plan.days.size
            Box(Modifier.fillMaxWidth().height(gridHeight)) {
                Canvas(Modifier.fillMaxSize()) {
                    val hairline = colors.hairline.copy(alpha = 0.5f)
                    val columnLine = colors.hairline.copy(alpha = 0.28f)
                    val step = rowHeight.toPx()
                    for (row in 1 until totalRows) {
                        drawLine(
                            color = hairline,
                            start = Offset(0f, step * row),
                            end = Offset(size.width, step * row),
                            strokeWidth = 1f,
                        )
                    }
                    val colStep = colWidth.toPx()
                    val start = labelWidth.toPx()
                    for (column in 1 until plan.days.size) {
                        drawLine(
                            color = columnLine,
                            start = Offset(start + colStep * column, 0f),
                            end = Offset(start + colStep * column, size.height),
                            strokeWidth = 1f,
                        )
                    }
                }
                Column(Modifier.width(labelWidth).height(gridHeight)) {
                    repeat(totalRows) { index ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(rowHeight),
                            contentAlignment = Alignment.CenterEnd,
                        ) {
                            Box(Modifier.padding(end = DdSpacing.Xs)) {
                                DdText(
                                    text = "${plan.minPeriod + index}",
                                    style = SysuCardType.micro.copy(fontSize = 9.sp, letterSpacing = 0.sp),
                                    color = colors.inkTertiary,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
                plan.blocks.forEach { block ->
                    SysuScheduleBlockView(
                        block = block,
                        plan = plan,
                        rowHeight = rowHeight,
                        colWidth = colWidth,
                        labelWidth = labelWidth,
                    )
                }
            }
        }
    }
}

@Composable
private fun SysuScheduleBlockView(
    block: SysuPlanBlock,
    plan: SysuSchedulePlan,
    rowHeight: androidx.compose.ui.unit.Dp,
    colWidth: androidx.compose.ui.unit.Dp,
    labelWidth: androidx.compose.ui.unit.Dp,
) {
    val colors = deepDeepColors()
    val hue = if (block.isExam) colors.danger else sysuHue(colors, block.title)
    val laneWidth = colWidth / block.lanes
    val span = block.end - block.start + 1
    val height = rowHeight * span - 2.dp
    val width = laneWidth - 2.dp
    val offsetX = labelWidth + colWidth * block.dayIndex + laneWidth * block.lane + 1.dp
    val offsetY = rowHeight * (block.start - plan.minPeriod) + 1.dp
    val maxLines = ((height - 6.dp) / 12.dp).toInt().coerceIn(1, 3)
    Box(
        modifier = Modifier
            .offset(x = offsetX, y = offsetY)
            .width(width)
            .height(height)
            .clip(RoundedCornerShape(6.dp))
            .background(sysuHueTint(colors, hue))
            .drawBehind {
                drawRect(
                    color = hue,
                    topLeft = Offset.Zero,
                    size = Size(3.dp.toPx(), size.height),
                )
            }
            .padding(start = 6.dp, end = 3.dp, top = 3.dp, bottom = 2.dp),
    ) {
        DdText(
            text = block.title,
            style = DdType.chip.copy(fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.Medium),
            color = colors.ink,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SysuDayHeader(day: SysuPlanDay, modifier: Modifier = Modifier) {
    val colors = deepDeepColors()
    val foreground = if (day.isToday) colors.accent else colors.inkSecondary
    Column(
        modifier = modifier
            .padding(horizontal = 2.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(if (day.isToday) colors.accentSoft else Color.Transparent)
            .padding(vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        DdText(
            text = day.weekday,
            style = SysuCardType.caption.copy(fontSize = 10.5.sp, lineHeight = 14.sp),
            color = foreground,
            maxLines = 1,
        )
        DdText(
            text = day.date?.dayOfMonth?.toString()?.padStart(2, '0') ?: "—",
            style = DdType.label.copy(fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold),
            color = if (day.isToday) colors.accent else colors.ink,
            maxLines = 1,
        )
    }
}
