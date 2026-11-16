package io.github.whywood.deepdeep.plugin.sysu

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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.whywood.deepdeep.design.DdShapes
import io.github.whywood.deepdeep.design.DdSpacing
import io.github.whywood.deepdeep.design.DdText
import io.github.whywood.deepdeep.design.DeepDeepColors
import io.github.whywood.deepdeep.design.deepDeepColors
import io.github.whywood.deepdeep.plugin.api.PluginHost
import io.github.whywood.deepdeep.plugin.cli.card.CliCardModel
import java.time.format.DateTimeFormatter

private val TODO_DUE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
private val TODO_DUE_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd")
private const val TODO_PREVIEW = 4

@Composable
internal fun SysuTodoCard(host: PluginHost, model: CliCardModel) {
    val plan = remember(model.sections, model.items) { SysuCardFormat.todoPlan(model) }
    if (plan.isNullOrEmpty()) {
        SysuFallbackListCard(host, model)
        return
    }
    var expanded by remember { mutableStateOf(false) }
    val sorted = remember(plan) {
        plan.sortedWith(compareBy<SysuTodoEntry>({ it.due == null }, { it.due }))
    }
    val rows = if (expanded) sorted else sorted.take(TODO_PREVIEW)
    SysuCardSurface {
        SysuTopLine(
            kicker = host.t("作业与待办", "Assignments"),
            title = host.t("${plan.size} 项待完成", "${plan.size} open"),
            trailing = model.trailing,
        )
        Spacer(Modifier.height(DdSpacing.Md))
        rows.forEachIndexed { index, entry ->
            if (index > 0) Spacer(Modifier.height(DdSpacing.Sm))
            SysuTodoRow(host = host, entry = entry)
        }
        SysuExpandToggle(
            host = host,
            hidden = (sorted.size - TODO_PREVIEW).coerceAtLeast(0),
            expanded = expanded,
            onToggle = { expanded = !expanded },
        )
        SysuFootLine(
            left = if (sorted.size > 1) host.t("按截止时间排序", "Sorted by due date") else null,
            right = null,
        )
    }
}

@Composable
private fun SysuTodoRow(host: PluginHost, entry: SysuTodoEntry) {
    val colors = deepDeepColors()
    val tone = sysuTodoTone(colors, entry.daysUntil)
    val dueLabel = sysuTodoDueLabel(host, entry)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(DdShapes.Medium)
            .background(colors.wash)
            .heightIn(min = 44.dp)
            .padding(horizontal = DdSpacing.Md, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(CircleShape)
                .border(1.6.dp, tone, CircleShape),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            DdText(
                text = entry.name,
                style = SysuCardType.bodyStrong,
                color = colors.ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            entry.course?.let { course ->
                Spacer(Modifier.height(2.dp))
                DdText(
                    text = course,
                    style = SysuCardType.caption,
                    color = colors.inkSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (entry.details.isNotEmpty() || entry.url != null) {
                Spacer(Modifier.height(2.dp))
                SysuDetailLines(lines = entry.details + listOfNotNull(entry.url))
            }
        }
        if (dueLabel != null) {
            Spacer(Modifier.width(DdSpacing.Sm))
            Column(horizontalAlignment = Alignment.End) {
                SysuPill(
                    text = dueLabel,
                    foreground = tone,
                    background = tone.copy(alpha = if (colors.dark) 0.22f else 0.12f),
                )
                if (entry.due != null) {
                    Spacer(Modifier.height(4.dp))
                    DdText(
                        text = sysuDueText(entry),
                        style = SysuCardType.caption.copy(fontSize = 10.5.sp, lineHeight = 14.sp),
                        color = colors.inkTertiary,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

private fun sysuTodoDueLabel(host: PluginHost, entry: SysuTodoEntry): String? {
    val days = entry.daysUntil ?: return null
    return when {
        days < 0 -> host.t("已逾期", "Overdue")
        days == 0L -> host.t("今天截止", "Due today")
        days == 1L -> host.t("明天截止", "Due tomorrow")
        else -> host.t("$days 天后", "in $days days")
    }
}

private fun sysuTodoTone(colors: DeepDeepColors, days: Long?): Color = when {
    days == null -> colors.accent
    days < 0 -> colors.danger
    days <= 2 -> colors.warning
    else -> colors.accent
}

private fun sysuDueText(entry: SysuTodoEntry): String {
    val due = entry.due ?: return ""
    return due.format(if (entry.hasDueTime) TODO_DUE_TIME else TODO_DUE_DATE)
}
