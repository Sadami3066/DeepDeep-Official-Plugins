package io.github.whywood.deepdeep.plugin.mail.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.whywood.deepdeep.design.DdDialog
import io.github.whywood.deepdeep.design.DdElevation
import io.github.whywood.deepdeep.design.DdIcon
import io.github.whywood.deepdeep.design.DdIconButton
import io.github.whywood.deepdeep.design.DdIcons
import io.github.whywood.deepdeep.design.DdMotion
import io.github.whywood.deepdeep.design.DdPressable
import io.github.whywood.deepdeep.design.DdRadius
import io.github.whywood.deepdeep.design.DdSpacing
import io.github.whywood.deepdeep.design.DdSpinner
import io.github.whywood.deepdeep.design.DdTextAction
import io.github.whywood.deepdeep.design.DdType
import io.github.whywood.deepdeep.design.deepDeepColors
import io.github.whywood.deepdeep.plugin.api.PluginHost
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

@Composable
internal fun MailAvatar(
    initials: String,
    color: Color,
    size: Dp,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    onClick: (() -> Unit)? = null,
) {
    val colors = deepDeepColors()
    val base = if (color.alpha <= 0f) colors.accent else color
    val background = base.copy(alpha = 0.14f)
    val textStyle = DdType.label.copy(fontSize = (size.value * 0.36f).sp)
    val label = initials.take(2).ifEmpty { "?" }
    if (onClick != null) {
        DdPressable(
            onClick = onClick,
            modifier = modifier.size(size),
            shape = CircleShape,
            background = background,
            contentDescription = contentDescription,
        ) {
            Text(text = label, style = textStyle, color = base)
        }
    } else {
        Box(
            modifier = modifier.size(size).clip(CircleShape).background(background),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = label, style = textStyle, color = base)
        }
    }
}

@Composable
internal fun MailUnreadDot(
    modifier: Modifier = Modifier,
    color: Color = deepDeepColors().accent,
    size: Dp = 7.dp,
) {
    Box(modifier.size(size).clip(CircleShape).background(color))
}

@Composable
internal fun MailSheetHeader(
    host: PluginHost,
    title: String,
    onClose: () -> Unit,
    onBack: (() -> Unit)? = null,
) {
    val colors = deepDeepColors()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .padding(start = if (onBack == null) 24.dp else 8.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            DdIconButton(
                icon = DdIcons.ArrowLeft,
                onClick = onBack,
                contentDescription = host.t("返回", "Back"),
                tint = colors.ink,
                iconSize = 21.dp,
            )
            Spacer(Modifier.width(4.dp))
        }
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            style = DdType.heading,
            color = colors.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        DdIconButton(
            icon = DdIcons.Close,
            onClick = onClose,
            contentDescription = host.t("关闭", "Close"),
            tint = colors.inkSecondary,
            iconSize = 20.dp,
        )
    }
}

@Composable
internal fun MailNoticeBar(
    text: String,
    modifier: Modifier = Modifier,
    tone: Color = deepDeepColors().inkSecondary,
    loading: Boolean = false,
    actionLabel: String? = null,
    actionDescription: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val colors = deepDeepColors()
    val clickable = actionLabel != null && onAction != null
    val bar: @Composable () -> Unit = {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 38.dp)
                .clip(RoundedCornerShape(DdRadius.Medium))
                .background(colors.wash)
                .then(if (clickable) Modifier.padding(horizontal = 12.dp) else Modifier.padding(horizontal = 12.dp, vertical = 8.dp)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (loading) {
                DdSpinner(size = 12.dp, strokeWidth = 1.5.dp, color = colors.inkTertiary)
            } else {
                DdIcon(icon = DdIcons.Info, tint = tone, size = 14.dp)
            }
            Spacer(Modifier.width(DdSpacing.Sm))
            Text(
                text = text,
                modifier = Modifier.weight(1f),
                style = DdType.bodySmall,
                color = tone,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (clickable) {
                Spacer(Modifier.width(DdSpacing.Xs))
                Text(
                    text = actionLabel.orEmpty(),
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 10.dp),
                    style = DdType.label,
                    color = colors.accent,
                    maxLines = 1,
                )
            }
        }
    }
    if (clickable) {
        DdPressable(
            onClick = { onAction() },
            modifier = modifier.fillMaxWidth(),
            shape = RoundedCornerShape(DdRadius.Medium),
            contentDescription = actionDescription ?: actionLabel,
            content = { bar() },
        )
    } else {
        Box(modifier.fillMaxWidth()) { bar() }
    }
}

@Composable
internal fun MailUndoBar(
    text: String,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = deepDeepColors()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .shadow(DdElevation.Soft, RoundedCornerShape(DdRadius.Medium), clip = false)
            .clip(RoundedCornerShape(DdRadius.Medium))
            .background(colors.raised)
            .heightIn(min = 48.dp)
            .padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            modifier = Modifier.weight(1f),
            style = DdType.bodySmall,
            color = colors.ink,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        DdTextAction(text = actionLabel, onClick = onAction, color = colors.accent)
    }
}

@Composable
internal fun MailConfirmDialog(
    host: PluginHost,
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
) {
    val colors = deepDeepColors()
    DdDialog(
        onDismissRequest = onDismiss,
        title = title,
        content = {
            Text(text = message, style = DdType.bodySmall, color = colors.inkSecondary)
        },
        actions = {
            DdTextAction(text = host.t("取消", "Cancel"), onClick = onDismiss, color = colors.inkSecondary)
            DdTextAction(
                text = confirmLabel,
                onClick = onConfirm,
                color = if (destructive) colors.danger else colors.accent,
            )
        },
    )
}

@Composable
internal fun MailSwipeRow(
    archiveLabel: String?,
    onArchive: (() -> Unit)?,
    deleteLabel: String,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (revealed: Boolean, closeActions: () -> Unit) -> Unit,
) {
    val colors = deepDeepColors()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val actionWidth = 76.dp
    val showArchive = archiveLabel != null && onArchive != null
    val revealPx = with(density) { (actionWidth * (if (showArchive) 2 else 1)).toPx() }
    val offset = remember { Animatable(0f) }
    var revealed by remember { mutableStateOf(false) }
    val closeActions: () -> Unit = {
        scope.launch {
            offset.animateTo(0f, tween(DdMotion.FAST, easing = DdMotion.EaseOut))
        }
        revealed = false
    }
    Box(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.matchParentSize(),
            horizontalArrangement = Arrangement.End,
        ) {
            if (showArchive) {
                MailSwipeAction(
                    icon = DdIcons.Archive,
                    label = archiveLabel.orEmpty(),
                    tint = colors.accent,
                    background = colors.accentSoft,
                    modifier = Modifier.width(actionWidth).fillMaxHeight(),
                ) {
                    closeActions()
                    onArchive()
                }
            }
            MailSwipeAction(
                icon = DdIcons.Trash,
                label = deleteLabel,
                tint = colors.danger,
                background = colors.danger.copy(alpha = 0.12f),
                modifier = Modifier.width(actionWidth).fillMaxHeight(),
            ) {
                closeActions()
                onDelete()
            }
        }
        val dragState = rememberDraggableState { delta ->
            scope.launch {
                offset.snapTo((offset.value + delta).coerceIn(-revealPx, 0f))
            }
        }
        Box(
            modifier = Modifier
                .offset { IntOffset(offset.value.roundToInt(), 0) }
                .clip(RoundedCornerShape(if (revealed) DdRadius.Large else 0.dp))
                .background(colors.paper)
                .draggable(
                    state = dragState,
                    orientation = Orientation.Horizontal,
                    onDragStopped = { velocity ->
                        val target = if (offset.value <= -revealPx * 0.4f || velocity < -800f) -revealPx else 0f
                        revealed = target != 0f
                        offset.animateTo(target, tween(DdMotion.FAST, easing = DdMotion.EaseOut))
                    },
                ),
        ) {
            content(revealed, closeActions)
        }
    }
}

@Composable
private fun MailSwipeAction(
    icon: DdIcon,
    label: String,
    tint: Color,
    background: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier.padding(start = 4.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
    ) {
        DdPressable(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth().fillMaxHeight(),
            shape = RoundedCornerShape(DdRadius.Medium),
            background = background,
            contentDescription = label,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                DdIcon(icon = icon, tint = tint, size = 18.dp)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = label,
                    style = DdType.chip,
                    color = tint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
