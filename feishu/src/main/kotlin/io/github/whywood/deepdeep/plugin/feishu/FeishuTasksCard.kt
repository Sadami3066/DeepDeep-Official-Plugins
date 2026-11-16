package io.github.whywood.deepdeep.plugin.feishu

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.whywood.deepdeep.design.DdExpandToggle
import io.github.whywood.deepdeep.design.DdIcon
import io.github.whywood.deepdeep.design.DdIcons
import io.github.whywood.deepdeep.design.DdPressable
import io.github.whywood.deepdeep.design.DdRowDivider
import io.github.whywood.deepdeep.design.DdSpacing
import io.github.whywood.deepdeep.design.DdText
import io.github.whywood.deepdeep.design.DdType
import io.github.whywood.deepdeep.design.deepDeepColors
import io.github.whywood.deepdeep.plugin.api.PluginHost

@Composable
internal fun FeishuTasksCard(host: PluginHost, model: FeishuCardModel) {
    FeishuCardShell {
        when (FeishuKindState(host, model)) {
            FeishuKindStateKind.ERROR -> FeishuCardErrorBody(host, model.openUrl)
            FeishuKindStateKind.LOADING -> TasksLoading()
            FeishuKindStateKind.EMPTY -> TasksEmpty(host)
            FeishuKindStateKind.READY -> TasksContent(host, model)
        }
    }
}

@Composable
private fun TasksContent(host: PluginHost, model: FeishuCardModel) {
    val colors = deepDeepColors()
    val all = model.items
    val count = model.total.coerceAtLeast(all.size)
    var expanded by remember(model) { mutableStateOf(false) }
    val rows = if (expanded) all else all.take(FEISHU_TASK_PREVIEW_ROWS)
    val open = all.count { !it.taskCompleted() }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = DdSpacing.Lg, end = DdSpacing.Lg, top = DdSpacing.Md, bottom = DdSpacing.Sm),
    ) {
        FeishuCardHead(
            host = host,
            title = model.title?.takeIf { it.isNotBlank() } ?: host.t("任务", "Tasks"),
            detail = when {
                count == 0 -> null
                open == count -> host.t("$count 条待办", "$count to do")
                open == 0 -> host.t("全部完成", "All done")
                else -> host.t("$open 条待办 · $count 条", "$open to do · $count total")
            },
            trailing = model.trailing?.takeIf { it.isNotBlank() },
        )
        Spacer(Modifier.height(DdSpacing.Sm))
        rows.forEachIndexed { index, item ->
            if (index > 0) DdRowDivider(leadingInset = 30.dp)
            TasksRow(host = host, item = item)
        }
        if (all.size > FEISHU_TASK_PREVIEW_ROWS) {
            DdExpandToggle(
                expanded = expanded,
                collapsedLabel = host.tf(
                    "展开其余 %d 条任务",
                    "Show %d more tasks",
                    all.size - FEISHU_TASK_PREVIEW_ROWS,
                ),
                expandedLabel = host.t("收起任务列表", "Show fewer tasks"),
                onToggle = { expanded = !expanded },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        val note = model.note?.takeIf { it.isNotBlank() }
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
        model.openUrl?.takeIf { it.isNotBlank() }?.let { url ->
            Spacer(Modifier.height(DdSpacing.Xs))
            FeishuCardFoot(
                host = host,
                note = null,
                actionLabel = host.t("打开飞书任务", "Open in Feishu"),
                openUrl = url,
            )
        }
    }
}

@Composable
private fun TasksRow(host: PluginHost, item: FeishuCardItem) {
    val colors = deepDeepColors()
    val completed = item.taskCompleted()
    val onOpen = item.url?.takeIf { it.isNotBlank() }?.let { url -> { host.openFeishuLink(url) } }
    val content: @Composable () -> Unit = {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .padding(vertical = DdSpacing.Sm),
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                modifier = Modifier
                    .padding(top = 2.dp)
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(if (completed) colors.success else colors.wash),
                contentAlignment = Alignment.Center,
            ) {
                if (completed) {
                    DdIcon(icon = DdIcons.Check, tint = colors.surface, size = 11.dp)
                } else {
                    Box(
                        Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(colors.hairline),
                    )
                }
            }
            Spacer(Modifier.width(DdSpacing.Md))
            Column(Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    modifier = Modifier.fillMaxWidth(),
                    style = feishuItemTitleStyle().copy(
                        textDecoration = if (completed) TextDecoration.LineThrough else TextDecoration.None,
                    ),
                    color = if (completed) colors.inkTertiary else colors.ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                item.subtitle?.takeIf { it.isNotBlank() }?.let { subtitle ->
                    Spacer(Modifier.height(2.dp))
                    DdText(
                        text = subtitle,
                        style = DdType.chip,
                        color = colors.inkSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                item.body?.takeIf { it.isNotBlank() }?.let { members ->
                    Spacer(Modifier.height(2.dp))
                    DdText(
                        text = members,
                        style = DdType.numeric,
                        color = colors.inkTertiary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            item.meta?.takeIf { it.isNotBlank() }?.let { meta ->
                Spacer(Modifier.width(DdSpacing.Sm))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(percent = 50))
                        .background(if (completed) colors.wash else colors.accentSoft)
                        .padding(horizontal = DdSpacing.Sm, vertical = 3.dp),
                ) {
                    DdText(
                        text = meta,
                        style = DdType.chip,
                        color = if (completed) colors.inkTertiary else colors.accent,
                        maxLines = 1,
                    )
                }
            }
        }
    }
    if (onOpen != null) {
        DdPressable(
            onClick = onOpen,
            modifier = Modifier.fillMaxWidth(),
            shape = RectangleShape,
            contentDescription = item.title,
            content = { content() },
        )
    } else {
        Box(Modifier.fillMaxWidth()) { content() }
    }
}

private fun FeishuCardItem.taskCompleted(): Boolean {
    val badge = badge?.trim().orEmpty()
    return badge == "已完成" || badge == "Done" || badge.equals("completed", ignoreCase = true)
}

@Composable
private fun TasksLoading() {
    val colors = deepDeepColors()
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = DdSpacing.Lg, end = DdSpacing.Lg, top = DdSpacing.Md, bottom = DdSpacing.Lg),
        verticalArrangement = Arrangement.spacedBy(DdSpacing.Md),
    ) {
        repeat(3) { index ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(18.dp).clip(CircleShape).background(colors.wash))
                Spacer(Modifier.width(DdSpacing.Md))
                Box(
                    Modifier
                        .fillMaxWidth(if (index == 1) 0.55f else 0.75f)
                        .height(12.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(colors.wash),
                )
            }
        }
    }
}

@Composable
private fun TasksEmpty(host: PluginHost) {
    FeishuEmptyBody(
        title = host.t("没有待办任务", "No tasks"),
        message = host.t("当前没有需要处理的任务", "Nothing needs your attention right now"),
    ) {
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(deepDeepColors().wash),
            contentAlignment = Alignment.Center,
        ) {
            DdIcon(icon = DdIcons.Check, tint = deepDeepColors().inkTertiary, size = 14.dp)
        }
    }
}
