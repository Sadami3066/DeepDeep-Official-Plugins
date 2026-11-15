package io.github.whywood.deepdeep.plugin.micloud

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.whywood.deepdeep.design.DdAvatar
import io.github.whywood.deepdeep.design.DdAlpha
import io.github.whywood.deepdeep.design.DdIcon
import io.github.whywood.deepdeep.design.DdIcons
import io.github.whywood.deepdeep.design.DdMotion
import io.github.whywood.deepdeep.design.DdShapes
import io.github.whywood.deepdeep.design.DdSpacing
import io.github.whywood.deepdeep.design.DdText
import io.github.whywood.deepdeep.design.DdType
import io.github.whywood.deepdeep.design.LocalDdMotionEnabled
import io.github.whywood.deepdeep.design.deepDeepColors
import io.github.whywood.deepdeep.plugin.api.PluginHost
import io.github.whywood.deepdeep.plugin.api.ToolResultState
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val QUOTA_ENTRY_ROWS = 4

private const val NOTE_ROWS = 3

private const val TODO_ROWS = 5

private const val FILE_ROWS = 5

@Composable
internal fun MiCloudAccountToolCard(host: PluginHost, state: ToolResultState) {
    MiCloudCardReveal {
        val result = state.result
        val details = result?.details
        when {
            result == null -> AccountLoadingCard(host)
            result.isError -> MiCloudErrorCard(
                host = host,
                title = host.t("小米云空间", "Xiaomi Cloud storage"),
                icon = DdIcons.Cloud,
                message = result.text,
            )

            details == null -> MiCloudPlainTextCard(
                host = host,
                title = host.t("小米账号", "Xiaomi account"),
                icon = DdIcons.Cloud,
                text = result.text,
            )

            else -> {
                val data = MiCloudAccountCardData.fromJson(details)
                if (data.mode == ACCOUNT_MODE_QUOTA) AccountQuotaCard(host, data) else AccountProfileCard(host, data)
            }
        }
    }
}

@Composable
internal fun MiCloudNotesToolCard(host: PluginHost, state: ToolResultState) {
    MiCloudCardReveal {
        val result = state.result
        val details = result?.details
        when {
            result == null -> NotesLoadingCard(host)
            result.isError -> MiCloudErrorCard(
                host = host,
                title = host.t("我的便签", "My notes"),
                icon = DdIcons.Pen,
                message = result.text,
            )

            details == null -> MiCloudPlainTextCard(
                host = host,
                title = host.t("我的便签", "My notes"),
                icon = DdIcons.Pen,
                text = result.text,
            )

            else -> {
                val data = MiCloudNotesCardData.fromJson(details)
                when (data.action) {
                    NOTES_ACTION_READ -> NotesReadCard(host, data)
                    NOTES_ACTION_CREATE, NOTES_ACTION_UPDATE, NOTES_ACTION_DELETE -> NotesWriteCard(host, data)
                    else -> NotesListCard(host, data)
                }
            }
        }
    }
}

@Composable
internal fun MiCloudTodoToolCard(host: PluginHost, state: ToolResultState) {
    MiCloudCardReveal {
        val result = state.result
        val details = result?.details
        when {
            result == null -> TodoLoadingCard(host)
            result.isError -> MiCloudErrorCard(
                host = host,
                title = host.t("待办清单", "To-do list"),
                icon = DdIcons.Check,
                message = result.text,
            )

            details == null -> MiCloudPlainTextCard(
                host = host,
                title = host.t("待办清单", "To-do list"),
                icon = DdIcons.Check,
                text = result.text,
            )

            else -> {
                val data = MiCloudTodoCardData.fromJson(details)
                when (data.action) {
                    TODO_ACTION_CREATE, TODO_ACTION_UPDATE, TODO_ACTION_DELETE -> TodoWriteCard(host, data)
                    else -> TodoListCard(host, data)
                }
            }
        }
    }
}

@Composable
internal fun MiCloudFilesToolCard(host: PluginHost, state: ToolResultState) {
    MiCloudCardReveal {
        val result = state.result
        val details = result?.details
        when {
            result == null -> FilesLoadingCard(host)
            result.isError -> MiCloudErrorCard(
                host = host,
                title = host.t("通话录音", "Call recordings"),
                icon = DdIcons.Mic,
                message = result.text,
            )

            details == null -> MiCloudPlainTextCard(
                host = host,
                title = host.t("通话录音", "Call recordings"),
                icon = DdIcons.Mic,
                text = result.text,
            )

            else -> {
                val data = MiCloudFileCardData.fromJson(details)
                if (data.mode == FILES_MODE_DOWNLOAD) FilesDownloadCard(host, data) else FilesListCard(host, data)
            }
        }
    }
}

@Composable
private fun MiCloudCardReveal(content: @Composable () -> Unit) {
    val motionEnabled = LocalDdMotionEnabled.current
    if (!motionEnabled) {
        content()
        return
    }
    var started by remember { mutableStateOf(false) }
    val progress by animateFloatAsState(
        targetValue = if (started) 1f else 0f,
        animationSpec = tween(durationMillis = DdMotion.FAST, easing = DdMotion.EaseOut),
    )
    LaunchedEffect(Unit) { started = true }
    Box(
        modifier = Modifier.graphicsLayer {
            alpha = progress
            translationY = (1f - progress) * 6.dp.toPx()
        },
    ) {
        content()
    }
}

@Composable
private fun MiCloudAnswerSurface(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = deepDeepColors()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(DdShapes.Card)
            .background(colors.surface)
            .border(1.dp, colors.hairline.copy(alpha = DdAlpha.Border), DdShapes.Card)
            .padding(DdSpacing.Lg),
        content = content,
    )
}

@Composable
private fun CardLabel(text: String, modifier: Modifier = Modifier) {
    DdText(
        text = text,
        modifier = modifier,
        style = DdType.label.copy(fontWeight = FontWeight.SemiBold),
        color = deepDeepColors().ink,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun MetaText(text: String, modifier: Modifier = Modifier) {
    DdText(
        text = text,
        modifier = modifier,
        style = DdType.chip,
        color = deepDeepColors().inkTertiary,
        maxLines = 1,
    )
}

@Composable
private fun ProgressBar(
    fraction: Float,
    color: Color,
    modifier: Modifier = Modifier,
    height: Dp = 4.dp,
) {
    val colors = deepDeepColors()
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(DdShapes.Pill)
            .background(colors.hairline.copy(alpha = DdAlpha.Hairline)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(height)
                .clip(DdShapes.Pill)
                .background(color),
        )
    }
}

@Composable
private fun SkeletonBar(
    width: Dp? = null,
    widthFraction: Float? = null,
    height: Dp = 10.dp,
) {
    val colors = deepDeepColors()
    val base = when {
        width != null -> Modifier.width(width)
        widthFraction != null -> Modifier.fillMaxWidth(widthFraction)
        else -> Modifier.fillMaxWidth()
    }
    Box(
        modifier = base
            .height(height)
            .clip(DdShapes.Small)
            .background(colors.hairline.copy(alpha = DdAlpha.TrackLight)),
    )
}

@Composable
private fun SkeletonCircle(size: Dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(DdShapes.Pill)
            .background(deepDeepColors().hairline.copy(alpha = DdAlpha.TrackLight)),
    )
}

@Composable
private fun RingGauge(percent: Int?, modifier: Modifier = Modifier) {
    val colors = deepDeepColors()
    Box(modifier = modifier.size(78.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxWidth().height(78.dp)) {
            val stroke = 8.dp.toPx()
            val radius = (size.minDimension - stroke) / 2f
            drawCircle(
                color = colors.hairline.copy(alpha = DdAlpha.TrackDark),
                radius = radius,
                style = Stroke(width = stroke),
            )
            if (percent != null) {
                drawArc(
                    color = colors.accent,
                    startAngle = -90f,
                    sweepAngle = 360f * (percent.coerceIn(0, 100) / 100f),
                    useCenter = false,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
        }
        if (percent != null) {
            DdText(
                text = "$percent%",
                style = DdType.label,
                color = colors.inkSecondary,
            )
        }
    }
}

@Composable
private fun CheckCircle(
    done: Boolean,
    modifier: Modifier = Modifier,
    diameter: Dp = 20.dp,
) {
    val colors = deepDeepColors()
    Canvas(modifier.size(diameter)) {
        val stroke = 1.5.dp.toPx()
        if (done) {
            drawCircle(color = colors.success)
            val width = size.width
            val height = size.height
            val path = Path().apply {
                moveTo(width * 0.28f, height * 0.52f)
                lineTo(width * 0.43f, height * 0.67f)
                lineTo(width * 0.72f, height * 0.36f)
            }
            drawPath(
                path = path,
                color = colors.paper,
                style = Stroke(
                    width = 2.dp.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                ),
            )
        } else {
            drawCircle(
                color = colors.inkSecondary.copy(alpha = DdAlpha.Border),
                radius = (size.minDimension - stroke) / 2f,
                style = Stroke(width = stroke),
            )
        }
    }
}

@Composable
private fun AccountQuotaCard(host: PluginHost, data: MiCloudAccountCardData) {
    val colors = deepDeepColors()
    val summary = quotaSummary(data.entries)
    MiCloudAnswerSurface {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CardLabel(host.t("小米云空间", "Xiaomi Cloud storage"), Modifier.weight(1f))
            if (data.entries.isNotEmpty()) {
                MetaText(host.tf("%d 项", "%d items", data.entries.size))
            }
        }
        if (summary == null) {
            Spacer(Modifier.height(DdSpacing.Md))
            AccountQuotaEmptyBody(host)
        } else {
            Spacer(Modifier.height(DdSpacing.Md))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(DdSpacing.Xxs)) {
                    val heroBytes = summary.usedBytes ?: summary.totalBytes
                    if (heroBytes != null) {
                        val (value, unit) = byteValueAndUnit(heroBytes)
                        Row(verticalAlignment = Alignment.Bottom) {
                            DdText(
                                text = value,
                                style = DdType.heading.copy(fontSize = 30.sp, lineHeight = 34.sp),
                                color = colors.ink,
                            )
                            Spacer(Modifier.width(DdSpacing.Xxs))
                            DdText(
                                text = unit,
                                modifier = Modifier.padding(bottom = 3.dp),
                                style = DdType.label.copy(fontWeight = FontWeight.SemiBold),
                                color = colors.inkSecondary,
                            )
                        }
                    }
                    DdText(
                        text = quotaHeroCaption(host, summary),
                        style = DdType.bodySmall,
                        color = colors.inkSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val free = summary.freeBytes
                    if (free != null) {
                        DdText(
                            text = host.tf("剩余 %s", "%s free", formatBytes(free)),
                            style = DdType.chip,
                            color = colors.inkTertiary,
                            maxLines = 1,
                        )
                    }
                }
                Spacer(Modifier.width(DdSpacing.Md))
                RingGauge(summary.percent)
            }
            val entries = data.entries.take(QUOTA_ENTRY_ROWS)
            if (entries.isNotEmpty()) {
                Spacer(Modifier.height(DdSpacing.Lg))
                Column(verticalArrangement = Arrangement.spacedBy(DdSpacing.Md)) {
                    entries.forEach { entry -> QuotaEntryRow(host, entry) }
                }
            }
        }
        Spacer(Modifier.height(DdSpacing.Md))
        MetaText(host.t("容量数据以小米云为准", "Storage figures come from Xiaomi Cloud"))
    }
}

private fun quotaHeroCaption(host: PluginHost, summary: MiCloudQuotaSummary): String {
    val total = summary.totalBytes
    val used = summary.usedBytes
    return when {
        used != null && total != null -> host.tf("已用 / 共 %s", "Used of %s", formatBytes(total))
        used != null -> host.t("已用空间", "Used space")
        total != null -> host.t("总空间", "Total space")
        else -> ""
    }
}

@Composable
private fun QuotaEntryRow(host: PluginHost, entry: MiCloudQuotaEntry) {
    val colors = deepDeepColors()
    val total = entry.totalBytes
    val used = entry.usedBytes
    val fraction = if (total != null && total > 0L && used != null) {
        (used.toDouble() / total.toDouble()).toFloat()
    } else {
        0f
    }
    Column(verticalArrangement = Arrangement.spacedBy(DdSpacing.Xxs)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DdText(
                text = host.t(quotaLabelZh(entry.label), quotaLabelEn(entry.label)),
                modifier = Modifier.weight(1f),
                style = DdType.chip.copy(fontWeight = FontWeight.Medium),
                color = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            MetaText(quotaEntryUsage(host, entry))
        }
        if (used != null) {
            ProgressBar(fraction = fraction, color = colors.accent)
        }
    }
}

private fun quotaEntryUsage(host: PluginHost, entry: MiCloudQuotaEntry): String {
    val total = entry.totalBytes
    val used = entry.usedBytes
    return when {
        used != null && total != null -> host.tf("%s / %s", "%s of %s", formatBytes(used), formatBytes(total))
        used != null -> host.tf("已用 %s", "used %s", formatBytes(used))
        total != null -> host.tf("共 %s", "total %s", formatBytes(total))
        else -> host.t("暂无数据", "No data")
    }
}

@Composable
private fun AccountQuotaEmptyBody(host: PluginHost) {
    val colors = deepDeepColors()
    Row(verticalAlignment = Alignment.CenterVertically) {
        DdIcon(icon = DdIcons.Cloud, tint = colors.inkTertiary, size = 22.dp)
        Spacer(Modifier.width(DdSpacing.Md))
        Column {
            DdText(
                text = host.t("暂时读不到空间用量", "Storage usage is unavailable"),
                style = DdType.bodySmall,
                color = colors.inkSecondary,
            )
            DdText(
                text = host.t("稍后重新读取即可", "Try reading it again in a moment"),
                style = DdType.chip,
                color = colors.inkTertiary,
            )
        }
    }
}

@Composable
private fun AccountProfileCard(host: PluginHost, data: MiCloudAccountCardData) {
    val colors = deepDeepColors()
    val rows = buildList {
        if (data.email.isNotBlank()) add(host.t("邮箱", "Email") to data.email)
        if (data.phone.isNotBlank()) add(host.t("手机", "Phone") to data.phone)
    }
    MiCloudAnswerSurface {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val initial = data.nickname.trim().take(1)
            DdAvatar(
                initials = initial.ifBlank { null },
                size = 52.dp,
                placeholderIcon = DdIcons.Cloud,
                contentDescription = data.nickname.ifBlank { null },
            )
            Spacer(Modifier.width(DdSpacing.Lg))
            Column(Modifier.weight(1f)) {
                DdText(
                    text = data.nickname.ifBlank { host.t("小米账号", "Xiaomi account") },
                    style = DdType.heading.copy(fontSize = 20.sp, lineHeight = 26.sp),
                    color = colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                DdText(
                    text = host.t("已登录 · 小米账号", "Signed in · Xiaomi account"),
                    style = DdType.chip,
                    color = colors.inkSecondary,
                    maxLines = 1,
                )
            }
        }
        if (rows.isNotEmpty()) {
            Spacer(Modifier.height(DdSpacing.Lg))
            Column(verticalArrangement = Arrangement.spacedBy(DdSpacing.Sm)) {
                rows.forEach { (label, value) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        DdText(
                            text = label,
                            modifier = Modifier.width(56.dp),
                            style = DdType.chip,
                            color = colors.inkTertiary,
                            maxLines = 1,
                        )
                        DdText(
                            text = value,
                            style = DdType.bodySmall,
                            color = colors.ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        if (data.userId.isNotBlank()) {
            Spacer(Modifier.height(DdSpacing.Lg))
            MetaText(host.tf("用户 ID %s", "User ID %s", data.userId))
        }
    }
}

@Composable
private fun StickySheet(
    modifier: Modifier = Modifier,
    tint: Color,
    foldColor: Color,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(DdShapes.Large)
            .background(tint),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 13.dp),
            content = content,
        )
        Canvas(Modifier.align(Alignment.BottomEnd).size(18.dp)) {
            val path = Path().apply {
                moveTo(0f, size.height)
                lineTo(size.width, 0f)
                lineTo(size.width, size.height)
                close()
            }
            drawPath(path, foldColor)
        }
    }
}

@Composable
private fun stickyTint(): Color {
    val colors = deepDeepColors()
    return colors.warning.copy(alpha = if (colors.dark) 0.12f else 0.09f)
}

@Composable
private fun NotesListCard(host: PluginHost, data: MiCloudNotesCardData) {
    val colors = deepDeepColors()
    val shown = data.rows.take(NOTE_ROWS)
    val remaining = (data.total - shown.size).coerceAtLeast(0)
    MiCloudAnswerSurface {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CardLabel(host.t("我的便签", "My notes"), Modifier.weight(1f))
            MetaText(host.tf("共 %d 条", "%d notes", data.total))
        }
        if (shown.isEmpty()) {
            Spacer(Modifier.height(DdSpacing.Md))
            NotesEmptySheet(host)
        } else {
            Spacer(Modifier.height(DdSpacing.Md))
            Column(verticalArrangement = Arrangement.spacedBy(DdSpacing.Sm)) {
                shown.forEach { item -> NoteSheetRow(host, item) }
            }
            if (remaining > 0) {
                Spacer(Modifier.height(DdSpacing.Md))
                MetaText(host.tf("还有 %d 条", "%d more", remaining))
            }
        }
        if (data.lastPage) {
            Spacer(Modifier.height(DdSpacing.Sm))
            DdText(
                text = host.t("已到最后一页", "This is the last page"),
                style = DdType.micro,
                color = colors.inkTertiary,
            )
        }
    }
}

@Composable
private fun NoteSheetRow(host: PluginHost, item: MiCloudNoteItem) {
    val colors = deepDeepColors()
    StickySheet(tint = stickyTint(), foldColor = colors.surface) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DdText(
                text = item.subject.ifBlank { host.t("（无主题）", "(No subject)") },
                modifier = Modifier.weight(1f),
                style = DdType.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                color = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val modified = formatShortTime(item.modifyTime)
            if (modified.isNotBlank()) {
                Spacer(Modifier.width(DdSpacing.Sm))
                MetaText(modified)
            }
        }
        if (item.snippet.isNotBlank()) {
            Spacer(Modifier.height(DdSpacing.Xxs))
            DdText(
                text = item.snippet,
                style = DdType.chip,
                color = colors.inkSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun NotesEmptySheet(host: PluginHost) {
    val colors = deepDeepColors()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(DdShapes.Large)
            .border(1.dp, colors.hairline, DdShapes.Large)
            .padding(horizontal = 14.dp, vertical = 16.dp),
    ) {
        Column {
            DdText(
                text = host.t("还没有笔记", "No notes yet"),
                style = DdType.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                color = colors.inkSecondary,
            )
            Spacer(Modifier.height(DdSpacing.Xxs))
            DdText(
                text = host.t("在对话里说“记一条便签”，内容会保存在小米云。", "Say “save a note” in chat and it will be kept in Xiaomi Cloud."),
                style = DdType.chip,
                color = colors.inkTertiary,
            )
        }
    }
}

@Composable
private fun NotesReadCard(host: PluginHost, data: MiCloudNotesCardData) {
    val colors = deepDeepColors()
    StickySheet(
        modifier = Modifier.border(1.dp, colors.hairline.copy(alpha = DdAlpha.Divider), DdShapes.Large),
        tint = stickyTint(),
        foldColor = colors.paper,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DdText(
                text = data.subject.ifBlank { host.t("（无主题）", "(No subject)") },
                modifier = Modifier.weight(1f),
                style = DdType.body.copy(fontWeight = FontWeight.SemiBold),
                color = colors.ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(DdSpacing.Sm))
            DdIcon(
                icon = DdIcons.Pin,
                tint = colors.accent,
                size = 14.dp,
                contentDescription = host.t("置顶便签", "Pinned note"),
            )
        }
        if (data.preview.isNotBlank()) {
            Spacer(Modifier.height(DdSpacing.Sm))
            DdText(
                text = data.preview,
                style = DdType.bodySmall,
                color = colors.ink,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(DdSpacing.Md))
        Row(verticalAlignment = Alignment.CenterVertically) {
            val modified = formatShortTime(data.modifyTime)
            if (modified.isNotBlank()) {
                MetaText(host.tf("修改于 %s", "edited %s", modified))
            }
            if (data.noteId.isNotBlank()) {
                Spacer(Modifier.width(DdSpacing.Sm))
                MetaText(host.tf("id %s", "id %s", data.noteId))
            }
        }
    }
}

@Composable
private fun NotesWriteCard(host: PluginHost, data: MiCloudNotesCardData) {
    val colors = deepDeepColors()
    val (label, tint) = when (data.action) {
        NOTES_ACTION_CREATE -> host.t("已新建", "Created") to colors.success
        NOTES_ACTION_UPDATE -> host.t("已修改", "Updated") to colors.success
        else -> host.t("已删除", "Deleted") to colors.danger
    }
    StickySheet(
        modifier = Modifier.border(1.dp, colors.hairline.copy(alpha = DdAlpha.Divider), DdShapes.Large),
        tint = stickyTint(),
        foldColor = colors.paper,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ActionBadge(text = label, tint = tint)
            Spacer(Modifier.weight(1f))
            val modified = formatShortTime(data.modifyTime)
            if (modified.isNotBlank()) MetaText(modified)
        }
        Spacer(Modifier.height(DdSpacing.Sm))
        DdText(
            text = data.subject.ifBlank { host.t("（无主题）", "(No subject)") },
            style = DdType.body.copy(fontWeight = FontWeight.SemiBold),
            color = colors.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (data.preview.isNotBlank()) {
            Spacer(Modifier.height(DdSpacing.Xxs))
            DdText(
                text = data.preview,
                style = DdType.chip,
                color = colors.inkSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (data.noteId.isNotBlank()) {
            Spacer(Modifier.height(DdSpacing.Md))
            MetaText(host.tf("id %s", "id %s", data.noteId))
        }
    }
}

@Composable
private fun ActionBadge(text: String, tint: Color) {
    DdText(
        text = text,
        modifier = Modifier
            .clip(DdShapes.Pill)
            .background(tint.copy(alpha = if (deepDeepColors().dark) 0.2f else 0.12f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        style = DdType.micro.copy(fontWeight = FontWeight.SemiBold),
        color = tint,
        maxLines = 1,
    )
}

@Composable
private fun TodoListCard(host: PluginHost, data: MiCloudTodoCardData) {
    val colors = deepDeepColors()
    val total = data.total.coerceAtLeast(data.rows.size)
    val done = data.doneCount.coerceAtMost(total)
    val shown = data.rows.take(TODO_ROWS)
    val remaining = (total - shown.size).coerceAtLeast(0)
    MiCloudAnswerSurface {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CardLabel(host.t("待办清单", "To-do list"), Modifier.weight(1f))
            MetaText(host.tf("%d / %d 已完成", "%d of %d done", done, total))
        }
        if (total > 0) {
            Spacer(Modifier.height(DdSpacing.Sm))
            ProgressBar(fraction = done.toFloat() / total.toFloat(), color = colors.success)
        }
        if (shown.isEmpty()) {
            Spacer(Modifier.height(DdSpacing.Md))
            TodoEmptyBody(host)
        } else {
            Spacer(Modifier.height(DdSpacing.Md))
            Column(verticalArrangement = Arrangement.spacedBy(DdSpacing.Md)) {
                shown.forEach { item -> TodoCheckRow(host, item) }
            }
            if (remaining > 0) {
                Spacer(Modifier.height(DdSpacing.Md))
                MetaText(host.tf("还有 %d 条", "%d more", remaining))
            }
        }
    }
}

@Composable
private fun TodoCheckRow(host: PluginHost, item: MiCloudTodoItem) {
    val colors = deepDeepColors()
    Row(verticalAlignment = Alignment.Top) {
        CheckCircle(done = item.done, modifier = Modifier.padding(top = 1.dp))
        Spacer(Modifier.width(DdSpacing.Md))
        DdText(
            text = item.content.ifBlank { host.t("（无内容）", "(No content)") },
            modifier = Modifier.weight(1f),
            style = if (item.done) {
                DdType.bodySmall.copy(textDecoration = TextDecoration.LineThrough)
            } else {
                DdType.bodySmall
            },
            color = if (item.done) colors.inkSecondary else colors.ink,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        TodoTrailing(host, item)
    }
}

@Composable
private fun TodoTrailing(host: PluginHost, item: MiCloudTodoItem) {
    val colors = deepDeepColors()
    if (item.done) {
        val modified = formatShortTime(item.modifyTime)
        if (modified.isNotBlank()) {
            Spacer(Modifier.width(DdSpacing.Sm))
            MetaText(modified)
        }
        return
    }
    val due = todoDueLabel(host, item.remindTime) ?: return
    Spacer(Modifier.width(DdSpacing.Sm))
    DdText(
        text = due.first,
        modifier = Modifier.padding(top = 2.dp),
        style = DdType.micro,
        color = if (due.second) colors.warning else colors.inkTertiary,
        maxLines = 1,
    )
}

private fun todoDueLabel(host: PluginHost, millis: Long): Pair<String, Boolean>? {
    if (millis <= 0L) return null
    val zone = ZoneId.systemDefault()
    val due = Instant.ofEpochMilli(millis).atZone(zone)
    val today = LocalDate.now(zone)
    val clock = due.format(DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT))
    return when (due.toLocalDate()) {
        today -> host.t("今天", "Today") + " " + clock to true
        today.plusDays(1) -> host.t("明天", "Tomorrow") + " " + clock to false
        else -> formatShortTime(millis) to false
    }
}

@Composable
private fun TodoEmptyBody(host: PluginHost) {
    val colors = deepDeepColors()
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(DdShapes.Pill)
                .border(1.5.dp, colors.hairline, DdShapes.Pill),
            contentAlignment = Alignment.Center,
        ) {
            DdIcon(icon = DdIcons.Check, tint = colors.inkTertiary, size = 19.dp)
        }
        Spacer(Modifier.height(DdSpacing.Sm))
        DdText(
            text = host.t("清单是空的", "The list is empty"),
            style = DdType.bodySmall,
            color = colors.inkSecondary,
        )
        Spacer(Modifier.height(DdSpacing.Xxs))
        DdText(
            text = host.t("在对话里说“加一条待办”就能记下来", "Say “add a to-do” in chat to keep one here"),
            style = DdType.chip,
            color = colors.inkTertiary,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun TodoWriteCard(host: PluginHost, data: MiCloudTodoCardData) {
    val colors = deepDeepColors()
    val (label, tint) = when (data.action) {
        TODO_ACTION_CREATE -> host.t("已新建", "Created") to colors.success
        TODO_ACTION_UPDATE -> host.t("已修改", "Updated") to colors.success
        else -> host.t("已删除", "Deleted") to colors.danger
    }
    MiCloudAnswerSurface {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ActionBadge(text = label, tint = tint)
            Spacer(Modifier.weight(1f))
            val modified = formatShortTime(data.modifyTime)
            if (modified.isNotBlank()) MetaText(modified)
        }
        Spacer(Modifier.height(DdSpacing.Md))
        Row(verticalAlignment = Alignment.Top) {
            CheckCircle(done = data.done, diameter = 24.dp, modifier = Modifier.padding(top = 1.dp))
            Spacer(Modifier.width(DdSpacing.Md))
            DdText(
                text = data.content.ifBlank { host.t("（无内容）", "(No content)") },
                modifier = Modifier.weight(1f),
                style = if (data.done) {
                    DdType.body.copy(textDecoration = TextDecoration.LineThrough)
                } else {
                    DdType.body
                },
                color = if (data.done) colors.inkSecondary else colors.ink,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val meta = buildList {
            val remind = formatShortTime(data.remindTime)
            if (remind.isNotBlank()) add(host.tf("提醒 %s", "remind %s", remind))
            val modified = formatShortTime(data.modifyTime)
            if (modified.isNotBlank()) add(host.tf("修改 %s", "edited %s", modified))
        }.joinToString(" · ")
        if (meta.isNotBlank()) {
            Spacer(Modifier.height(DdSpacing.Sm))
            MetaText(meta)
        }
        if (data.todoId.isNotBlank()) {
            Spacer(Modifier.height(DdSpacing.Sm))
            DdText(
                text = host.tf("id %s", "id %s", data.todoId),
                style = DdType.micro,
                color = colors.inkTertiary,
            )
        }
    }
}

@Composable
private fun FilesListCard(host: PluginHost, data: MiCloudFileCardData) {
    val colors = deepDeepColors()
    val shown = data.files.take(FILE_ROWS)
    val remaining = (data.files.size - shown.size).coerceAtLeast(0)
    MiCloudAnswerSurface {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CardLabel(host.t("通话录音", "Call recordings"), Modifier.weight(1f))
            MetaText(host.tf("%d 个", "%d files", data.files.size))
        }
        if (shown.isEmpty()) {
            Spacer(Modifier.height(DdSpacing.Md))
            FilesEmptyBody(host)
        } else {
            Spacer(Modifier.height(DdSpacing.Sm))
            Column(verticalArrangement = Arrangement.spacedBy(DdSpacing.Md)) {
                shown.forEach { item -> FileRow(host, item, tint = colors.inkSecondary) }
            }
            if (remaining > 0) {
                Spacer(Modifier.height(DdSpacing.Md))
                MetaText(host.tf("还有 %d 个", "%d more", remaining))
            }
        }
    }
}

@Composable
private fun FilesDownloadCard(host: PluginHost, data: MiCloudFileCardData) {
    val colors = deepDeepColors()
    val file = data.files.firstOrNull()
    MiCloudAnswerSurface {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ActionBadge(text = host.t("已保存", "Saved"), tint = colors.success)
            Spacer(Modifier.weight(1f))
            if (file != null) MetaText(host.t("录音", "Recording"))
        }
        if (file == null) {
            Spacer(Modifier.height(DdSpacing.Md))
            FilesEmptyBody(host)
        } else {
            Spacer(Modifier.height(DdSpacing.Md))
            FileRow(host, file, tint = colors.accent)
            Spacer(Modifier.height(DdSpacing.Md))
            DdText(
                text = host.t("文件已保存到手机，可直接打开或分享。", "Saved to your phone; open or share it right away."),
                style = DdType.micro,
                color = colors.inkTertiary,
            )
        }
    }
}

@Composable
private fun FileRow(host: PluginHost, item: MiCloudFileItem, tint: Color) {
    val colors = deepDeepColors()
    Row(verticalAlignment = Alignment.CenterVertically) {
        DdIcon(
            icon = DdIcons.Mic,
            tint = tint,
            size = 21.dp,
            contentDescription = host.t("录音", "Recording"),
        )
        Spacer(Modifier.width(DdSpacing.Md))
        Column(Modifier.weight(1f)) {
            DdText(
                text = item.name.ifBlank { host.t("未命名文件", "Untitled file") },
                style = DdType.bodySmall.copy(fontWeight = FontWeight.Medium),
                color = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val meta = fileMeta(host, item)
            if (meta.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                DdText(
                    text = meta,
                    style = DdType.chip,
                    color = colors.inkTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun fileMeta(host: PluginHost, item: MiCloudFileItem): String {
    val parts = mutableListOf<String>()
    if (item.sizeBytes > 0L) parts += formatBytes(item.sizeBytes)
    if (item.durationSeconds > 0L) parts += formatDuration(item.durationSeconds)
    val time = formatShortTime(item.timeMs)
    if (time.isNotBlank()) parts += time
    return parts.joinToString(host.t(" · ", " · "))
}

private fun formatDuration(seconds: Long): String {
    val minutes = seconds / 60
    val rest = seconds % 60
    return String.format(Locale.ROOT, "%02d:%02d", minutes, rest)
}

@Composable
private fun FilesEmptyBody(host: PluginHost) {
    val colors = deepDeepColors()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(DdShapes.Large)
            .border(1.dp, colors.hairline, DdShapes.Large)
            .padding(horizontal = 14.dp, vertical = 16.dp),
    ) {
        Column {
            DdText(
                text = host.t("还没有录音", "No recordings yet"),
                style = DdType.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                color = colors.inkSecondary,
            )
            Spacer(Modifier.height(DdSpacing.Xxs))
            DdText(
                text = host.t("小米云里的通话录音会显示在这里。", "Call recordings saved in Xiaomi Cloud will appear here."),
                style = DdType.chip,
                color = colors.inkTertiary,
            )
        }
    }
}

@Composable
private fun AccountLoadingCard(host: PluginHost) {
    MiCloudAnswerSurface {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CardLabel(host.t("小米云空间", "Xiaomi Cloud storage"), Modifier.weight(1f))
            MetaText(host.t("读取中…", "Loading…"))
        }
        Spacer(Modifier.height(DdSpacing.Md))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(DdSpacing.Sm),
            ) {
                SkeletonBar(width = 96.dp, height = 26.dp)
                SkeletonBar(width = 140.dp)
                SkeletonBar(width = 88.dp)
            }
            Spacer(Modifier.width(DdSpacing.Md))
            SkeletonCircle(78.dp)
        }
        Spacer(Modifier.height(DdSpacing.Lg))
        Column(verticalArrangement = Arrangement.spacedBy(DdSpacing.Sm)) {
            SkeletonBar()
            SkeletonBar(widthFraction = 0.72f)
        }
    }
}

@Composable
private fun NotesLoadingCard(host: PluginHost) {
    MiCloudAnswerSurface {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CardLabel(host.t("我的便签", "My notes"), Modifier.weight(1f))
            MetaText(host.t("读取中…", "Loading…"))
        }
        Spacer(Modifier.height(DdSpacing.Md))
        Column(verticalArrangement = Arrangement.spacedBy(DdSpacing.Sm)) {
            repeat(2) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(DdShapes.Large)
                        .background(deepDeepColors().hairline.copy(alpha = DdAlpha.TrackLight))
                        .padding(horizontal = 14.dp, vertical = 13.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(DdSpacing.Sm)) {
                        SkeletonBar(widthFraction = 0.5f, height = 12.dp)
                        SkeletonBar(widthFraction = 0.85f, height = 9.dp)
                    }
                }
            }
        }
    }
}

@Composable
private fun TodoLoadingCard(host: PluginHost) {
    MiCloudAnswerSurface {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CardLabel(host.t("待办清单", "To-do list"), Modifier.weight(1f))
            MetaText(host.t("读取中…", "Loading…"))
        }
        Spacer(Modifier.height(DdSpacing.Sm))
        SkeletonBar(height = 5.dp)
        Spacer(Modifier.height(DdSpacing.Md))
        Column(verticalArrangement = Arrangement.spacedBy(DdSpacing.Md)) {
            repeat(3) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SkeletonCircle(20.dp)
                    Spacer(Modifier.width(DdSpacing.Md))
                    SkeletonBar(widthFraction = 0.7f)
                }
            }
        }
    }
}

@Composable
private fun FilesLoadingCard(host: PluginHost) {
    MiCloudAnswerSurface {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CardLabel(host.t("通话录音", "Call recordings"), Modifier.weight(1f))
            MetaText(host.t("读取中…", "Loading…"))
        }
        Spacer(Modifier.height(DdSpacing.Md))
        Column(verticalArrangement = Arrangement.spacedBy(DdSpacing.Md)) {
            repeat(2) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SkeletonCircle(21.dp)
                    Spacer(Modifier.width(DdSpacing.Md))
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(DdSpacing.Xs),
                    ) {
                        SkeletonBar(widthFraction = 0.68f)
                        SkeletonBar(widthFraction = 0.4f)
                    }
                }
            }
        }
    }
}

@Composable
private fun MiCloudErrorCard(
    host: PluginHost,
    title: String,
    icon: DdIcon,
    message: String?,
) {
    val colors = deepDeepColors()
    MiCloudAnswerSurface {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DdIcon(icon = icon, tint = colors.inkTertiary, size = 15.dp)
            Spacer(Modifier.width(DdSpacing.Sm))
            CardLabel(title, Modifier.weight(1f))
        }
        Spacer(Modifier.height(DdSpacing.Md))
        Row(verticalAlignment = Alignment.Top) {
            DdIcon(icon = DdIcons.Info, tint = colors.danger, size = 20.dp)
            Spacer(Modifier.width(DdSpacing.Md))
            Column {
                DdText(
                    text = message?.takeIf { it.isNotBlank() }
                        ?: host.t("读取失败，请稍后再试。", "Couldn't read this. Try again in a moment."),
                    style = DdType.bodySmall,
                    color = colors.ink,
                )
                Spacer(Modifier.height(DdSpacing.Xxs))
                DdText(
                    text = host.t("稍后重新读取即可", "Try reading it again in a moment"),
                    style = DdType.chip,
                    color = colors.inkTertiary,
                )
            }
        }
    }
}

@Composable
private fun MiCloudPlainTextCard(
    host: PluginHost,
    title: String,
    icon: DdIcon,
    text: String?,
) {
    val colors = deepDeepColors()
    MiCloudAnswerSurface {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DdIcon(icon = icon, tint = colors.inkTertiary, size = 15.dp)
            Spacer(Modifier.width(DdSpacing.Sm))
            CardLabel(title, Modifier.weight(1f))
        }
        if (!text.isNullOrBlank()) {
            Spacer(Modifier.height(DdSpacing.Sm))
            DdText(
                text = text,
                style = DdType.bodySmall,
                color = colors.inkSecondary,
            )
        }
    }
}
