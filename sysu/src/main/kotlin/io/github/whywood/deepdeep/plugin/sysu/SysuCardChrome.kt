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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.whywood.deepdeep.design.DeepDeepColors
import io.github.whywood.deepdeep.design.DdExpandToggle
import io.github.whywood.deepdeep.design.DdIcon
import io.github.whywood.deepdeep.design.DdIcons
import io.github.whywood.deepdeep.design.DdShapes
import io.github.whywood.deepdeep.design.DdSpacing
import io.github.whywood.deepdeep.design.DdText
import io.github.whywood.deepdeep.design.DdTextAction
import io.github.whywood.deepdeep.design.DdType
import io.github.whywood.deepdeep.design.deepDeepColors
import io.github.whywood.deepdeep.plugin.api.PluginHost
import io.github.whywood.deepdeep.plugin.api.localizedText
import io.github.whywood.deepdeep.plugin.cli.card.CliCardModel

internal fun PluginHost.t(zh: String, en: String): String =
    text(localizedText(default = zh, zh = zh, en = en))

internal object SysuCardType {
    val kicker = DdType.chip.copy(fontSize = 11.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium)
    val title = DdType.label.copy(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
    val hero = DdType.heading.copy(fontSize = 30.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold)
    val heroSmall = DdType.heading.copy(fontSize = 20.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold)
    val body = DdType.bodySmall.copy(fontSize = 13.sp, lineHeight = 18.sp)
    val bodyStrong = DdType.label.copy(fontSize = 14.sp, lineHeight = 20.sp)
    val caption = DdType.chip.copy(fontSize = 11.sp, lineHeight = 15.sp)
    val micro = DdType.micro.copy(letterSpacing = 0.3.sp)
}

internal fun sysuPalette(colors: DeepDeepColors): List<Color> = listOf(
    colors.accent,
    colors.success,
    colors.warning,
    colors.danger,
    colors.codeKeyword,
    colors.codeType,
)

internal fun sysuHue(colors: DeepDeepColors, key: String): Color {
    val palette = sysuPalette(colors)
    return palette[((key.hashCode() % palette.size) + palette.size) % palette.size]
}

internal fun sysuHueTint(colors: DeepDeepColors, hue: Color): Color =
    hue.copy(alpha = if (colors.dark) 0.22f else 0.13f)

internal fun sysuGradeColor(colors: DeepDeepColors, score: String?): Pair<Color, Color> {
    val normalized = score?.trim()?.uppercase().orEmpty()
    val fg = when {
        normalized.startsWith("A") || normalized.startsWith("B") -> colors.success
        normalized.startsWith("C") || normalized.startsWith("D") -> colors.warning
        normalized.startsWith("F") || normalized == "不及格" -> colors.danger
        else -> colors.inkSecondary
    }
    return fg to fg.copy(alpha = if (colors.dark) 0.22f else 0.12f)
}

internal fun sysuStatusColor(colors: DeepDeepColors, status: String?): Color {
    val text = status.orEmpty()
    return when {
        text.contains("正常") || text.contains("active", ignoreCase = true) -> colors.success
        text.contains("暂停") || text.contains("停") -> colors.warning
        text.contains("欠费") || text.contains("过期") || text.contains("失效") -> colors.danger
        else -> colors.accent
    }
}

@Composable
internal fun SysuCardSurface(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val colors = deepDeepColors()
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(DdShapes.Card)
            .background(colors.surface),
    ) {
        Column(Modifier.fillMaxWidth().padding(DdSpacing.Lg)) {
            content()
        }
    }
}

@Composable
internal fun SysuTopLine(
    kicker: String?,
    title: String,
    trailing: String?,
) {
    val colors = deepDeepColors()
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f)) {
            if (!kicker.isNullOrBlank()) {
                DdText(
                    text = kicker,
                    style = SysuCardType.kicker,
                    color = colors.inkTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
            }
            DdText(
                text = title,
                style = SysuCardType.title,
                color = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!trailing.isNullOrBlank()) {
            Spacer(Modifier.width(DdSpacing.Sm))
            SysuPill(text = trailing, foreground = colors.inkSecondary, background = colors.wash)
        }
    }
}

@Composable
internal fun SysuPill(
    text: String,
    foreground: Color,
    background: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(background)
            .padding(horizontal = DdSpacing.Sm, vertical = 3.dp),
    ) {
        DdText(
            text = text,
            style = SysuCardType.caption,
            color = foreground,
            maxLines = 1,
        )
    }
}

@Composable
internal fun SysuCountdownPill(host: PluginHost, days: Long?) {
    val colors = deepDeepColors()
    val label = sysuCountdownLabel(host, days) ?: return
    val (fg, bg) = when {
        days == null -> colors.inkSecondary to colors.wash
        days < 0 -> colors.inkTertiary to colors.wash
        days <= 3 -> colors.warning to colors.warning.copy(alpha = if (colors.dark) 0.22f else 0.12f)
        days <= 14 -> colors.accent to colors.accentSoft
        else -> colors.inkSecondary to colors.wash
    }
    SysuPill(text = label, foreground = fg, background = bg)
}

internal fun sysuCountdownLabel(host: PluginHost, days: Long?): String? = when {
    days == null -> null
    days < 0 -> host.t("已结束", "Ended")
    days == 0L -> host.t("今天", "Today")
    days == 1L -> host.t("明天", "Tomorrow")
    days <= 60L -> host.t("${days} 天后", "in $days days")
    else -> host.t("${days} 天后", "in $days days")
}

@Composable
internal fun SysuExpandToggle(
    host: PluginHost,
    hidden: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    if (hidden <= 0) return
    DdExpandToggle(
        expanded = expanded,
        collapsedLabel = host.t("展开其余 $hidden 项", "$hidden more"),
        expandedLabel = host.t("收起", "Show less"),
        onToggle = onToggle,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
internal fun SysuDetailLines(
    lines: List<String>,
    modifier: Modifier = Modifier,
) {
    if (lines.isEmpty()) return
    val colors = deepDeepColors()
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        lines.forEach { line ->
            DdText(
                text = line,
                style = SysuCardType.caption,
                color = colors.inkSecondary,
            )
        }
    }
}

@Composable
internal fun SysuIconTile(
    icon: DdIcon,
    foreground: Color,
    background: Color,
    size: Int = 42,
) {
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(background),
        contentAlignment = Alignment.Center,
    ) {
        DdIcon(icon = icon, tint = foreground, size = (size * 0.42f).dp)
    }
}

@Composable
internal fun SysuSectionRow(
    label: String,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = deepDeepColors()
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DdText(
            text = label,
            modifier = Modifier.weight(1f),
            style = SysuCardType.bodyStrong,
            color = colors.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        trailing?.invoke()
    }
}

@Composable
internal fun SysuFootLine(left: String?, right: String?) {
    if (left.isNullOrBlank() && right.isNullOrBlank()) return
    val colors = deepDeepColors()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = DdSpacing.Md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DdText(
            text = left.orEmpty(),
            modifier = Modifier.weight(1f),
            style = SysuCardType.caption,
            color = colors.inkTertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!right.isNullOrBlank()) {
            DdText(
                text = right,
                style = SysuCardType.caption,
                color = colors.inkTertiary,
                maxLines = 1,
            )
        }
    }
}

@Composable
internal fun SysuLoadingCard(host: PluginHost, model: CliCardModel) {
    val colors = deepDeepColors()
    SysuCardSurface {
        SysuTopLine(
            kicker = model.context,
            title = sysuKindTitle(host, model.kind),
            trailing = null,
        )
        Spacer(Modifier.height(DdSpacing.Lg))
        Column(verticalArrangement = Arrangement.spacedBy(DdSpacing.Md)) {
            Box(
                Modifier
                    .fillMaxWidth(0.7f)
                    .height(26.dp)
                    .clip(DdShapes.Small)
                    .background(colors.wash),
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(14.dp)
                    .clip(DdShapes.Small)
                    .background(colors.wash),
            )
            Box(
                Modifier
                    .fillMaxWidth(0.82f)
                    .height(14.dp)
                    .clip(DdShapes.Small)
                    .background(colors.wash),
            )
            Box(
                Modifier
                    .fillMaxWidth(0.55f)
                    .height(14.dp)
                    .clip(DdShapes.Small)
                    .background(colors.wash),
            )
        }
        Spacer(Modifier.height(DdSpacing.Md))
        DdText(
            text = sysuLoadingText(host, model.kind),
            style = SysuCardType.caption,
            color = colors.inkTertiary,
        )
    }
}

@Composable
internal fun SysuEmptyCard(host: PluginHost, model: CliCardModel) {
    val colors = deepDeepColors()
    SysuCardSurface {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SysuIconTile(
                icon = sysuKindIcon(model.kind),
                foreground = colors.accent,
                background = colors.accentSoft,
            )
            Spacer(Modifier.width(DdSpacing.Md))
            Column(Modifier.weight(1f)) {
                DdText(
                    text = model.note?.takeIf { it.isNotBlank() } ?: sysuEmptyTitle(host, model.kind),
                    style = SysuCardType.bodyStrong,
                    color = colors.ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                DdText(
                    text = sysuEmptyHint(host, model.kind),
                    style = SysuCardType.caption,
                    color = colors.inkSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
internal fun SysuErrorCard(host: PluginHost, model: CliCardModel) {
    val colors = deepDeepColors()
    val message = model.note.orEmpty().ifBlank { host.t("读取失败", "Couldn't load") }
    val needsSignIn = message.contains("插件页") || message.contains("登录") ||
        message.contains("plugin page", ignoreCase = true) || message.contains("sign in", ignoreCase = true)
    SysuCardSurface {
        Row(verticalAlignment = Alignment.Top) {
            SysuIconTile(
                icon = DdIcons.Info,
                foreground = colors.danger,
                background = colors.danger.copy(alpha = if (colors.dark) 0.22f else 0.12f),
            )
            Spacer(Modifier.width(DdSpacing.Md))
            Column(Modifier.weight(1f)) {
                DdText(
                    text = message,
                    style = SysuCardType.bodyStrong,
                    color = colors.ink,
                )
                Spacer(Modifier.height(2.dp))
                DdText(
                    text = if (needsSignIn) {
                        host.t("重新登录后重新查询即可", "Sign in again, then retry the query")
                    } else {
                        host.t("在对话里重新查询一次", "Run the query again in the chat")
                    },
                    style = SysuCardType.caption,
                    color = colors.inkSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (needsSignIn) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = DdSpacing.Sm),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DdTextAction(
                    text = host.t("打开插件页", "Open plugin page"),
                    onClick = { runCatching { host.ui().openPage("main") } },
                )
            }
        }
    }
}

@Composable
internal fun SysuFallbackListCard(host: PluginHost, model: CliCardModel) {
    val colors = deepDeepColors()
    var expanded by remember { mutableStateOf(false) }
    val allRows = model.sections.flatMap { it.items } + model.items
    val rows = if (expanded) allRows else allRows.take(FALLBACK_PREVIEW)
    SysuCardSurface {
        SysuTopLine(
            kicker = model.context,
            title = model.title?.takeIf { it.isNotBlank() } ?: sysuKindTitle(host, model.kind),
            trailing = model.trailing,
        )
        Spacer(Modifier.height(DdSpacing.Sm))
        rows.forEachIndexed { index, item ->
            if (index > 0) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = DdSpacing.Xs)
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
                        text = item.title,
                        style = SysuCardType.bodyStrong,
                        color = colors.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    item.subtitle?.takeIf { it.isNotBlank() }?.let {
                        Spacer(Modifier.height(1.dp))
                        DdText(
                            text = it,
                            style = SysuCardType.caption,
                            color = colors.inkSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                item.meta?.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.width(DdSpacing.Sm))
                    DdText(
                        text = it,
                        style = SysuCardType.caption,
                        color = colors.inkSecondary,
                        maxLines = 1,
                    )
                }
            }
        }
        if (rows.isEmpty()) {
            DdText(
                text = sysuEmptyTitle(host, model.kind),
                style = SysuCardType.caption,
                color = colors.inkSecondary,
            )
        } else {
            SysuExpandToggle(
                host = host,
                hidden = (allRows.size - FALLBACK_PREVIEW).coerceAtLeast(0),
                expanded = expanded,
            ) { expanded = !expanded }
        }
    }
}

@Composable
internal fun SysuWrapRow(
    horizontalGap: Int = 6,
    verticalGap: Int = 6,
    content: @Composable () -> Unit,
) {
    Layout(
        content = { content() },
        modifier = Modifier.fillMaxWidth(),
    ) { measurables, constraints ->
        val hGap = horizontalGap.dp.roundToPx()
        val vGap = verticalGap.dp.roundToPx()
        val maxWidth = constraints.maxWidth
        val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
        var x = 0
        var y = 0
        var rowHeight = 0
        val positions = mutableListOf<Pair<Int, Int>>()
        for (placeable in placeables) {
            if (x > 0 && x + placeable.width > maxWidth) {
                x = 0
                y += rowHeight + vGap
                rowHeight = 0
            }
            positions += x to y
            x += placeable.width + hGap
            rowHeight = maxOf(rowHeight, placeable.height)
        }
        val height = if (placeables.isEmpty()) 0 else y + rowHeight
        layout(maxWidth, height) {
            placeables.forEachIndexed { index, placeable ->
                val (px, py) = positions[index]
                placeable.placeRelative(px, py)
            }
        }
    }
}

internal fun sysuKindIcon(kind: String): DdIcon = when (SysuCardKind.fromWire(kind)) {
    SysuCardKind.Schedule -> DdIcons.Clock
    SysuCardKind.Grades -> DdIcons.Book
    SysuCardKind.Exams -> DdIcons.Clock
    SysuCardKind.Lms -> DdIcons.Book
    SysuCardKind.Todo -> DdIcons.Check
    SysuCardKind.Gym -> DdIcons.Target
    SysuCardKind.Netpay -> DdIcons.Cloud
    null -> DdIcons.Info
}

internal fun sysuKindTitle(host: PluginHost, kind: String): String = when (SysuCardKind.fromWire(kind)) {
    SysuCardKind.Schedule -> host.t("本周课表", "This week")
    SysuCardKind.Grades -> host.t("成绩单", "Grades")
    SysuCardKind.Exams -> host.t("考试安排", "Exams")
    SysuCardKind.Lms -> host.t("在修课程", "Courses")
    SysuCardKind.Todo -> host.t("作业与待办", "Assignments")
    SysuCardKind.Gym -> host.t("可预约时段", "Availability")
    SysuCardKind.Netpay -> host.t("校园网服务", "Campus network")
    null -> host.t("校园服务", "Campus services")
}

private fun sysuLoadingText(host: PluginHost, kind: String): String = when (SysuCardKind.fromWire(kind)) {
    SysuCardKind.Schedule -> host.t("正在读取本周课表…", "Loading this week's timetable…")
    SysuCardKind.Grades -> host.t("正在读取成绩…", "Loading grades…")
    SysuCardKind.Exams -> host.t("正在读取考试安排…", "Loading exams…")
    SysuCardKind.Lms -> host.t("正在读取课程…", "Loading courses…")
    SysuCardKind.Todo -> host.t("正在读取待办…", "Loading assignments…")
    SysuCardKind.Gym -> host.t("正在读取场馆空位…", "Loading venue availability…")
    SysuCardKind.Netpay -> host.t("正在读取网费状态…", "Loading network service status…")
    null -> host.t("正在读取…", "Loading…")
}

private fun sysuEmptyTitle(host: PluginHost, kind: String): String = when (SysuCardKind.fromWire(kind)) {
    SysuCardKind.Schedule -> host.t("本周没有课程", "No classes this week")
    SysuCardKind.Grades -> host.t("成绩尚未公布", "Grades not published")
    SysuCardKind.Exams -> host.t("还没有排期考试", "No exams scheduled")
    SysuCardKind.Lms -> host.t("没有选课记录", "No enrolled courses")
    SysuCardKind.Todo -> host.t("没有待办事项", "No assignments")
    SysuCardKind.Gym -> host.t("没有可约时段", "No available slots")
    SysuCardKind.Netpay -> host.t("名下没有网络服务", "No network services")
    null -> host.t("没有内容", "Nothing here")
}

private const val FALLBACK_PREVIEW = 6

private fun sysuEmptyHint(host: PluginHost, kind: String): String = when (SysuCardKind.fromWire(kind)) {
    SysuCardKind.Schedule -> host.t("可以在对话里换一个周次再查", "Ask for another week in the chat")
    SysuCardKind.Grades -> host.t("出成绩后重新查询即可", "Check again after grades are published")
    SysuCardKind.Exams -> host.t("排期后会显示在这里", "Scheduled exams will show up here")
    SysuCardKind.Lms -> host.t("选课后重新查询即可", "Check again after enrolling")
    SysuCardKind.Todo -> host.t("都完成了，保持这个节奏", "All done — nothing due right now")
    SysuCardKind.Gym -> host.t("换个日期或场馆再查", "Try another date or venue")
    SysuCardKind.Netpay -> host.t("开通校园网后重新查询即可", "Check again after activating the service")
    null -> host.t("换个条件再试", "Try different filters")
}
