package io.github.whywood.superpi.plugin.mail.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.whywood.superpi.design.DdAlpha
import io.github.whywood.superpi.design.DdIcon
import io.github.whywood.superpi.design.DdIcons
import io.github.whywood.superpi.design.DdShapes
import io.github.whywood.superpi.design.DdSpacing
import io.github.whywood.superpi.design.DdText
import io.github.whywood.superpi.design.DdTextAction
import io.github.whywood.superpi.design.DdType
import io.github.whywood.superpi.design.LocalDdMotionEnabled
import io.github.whywood.superpi.design.superPiColors
import io.github.whywood.superpi.plugin.api.CustomMessageSlotState
import io.github.whywood.superpi.plugin.api.PluginHost
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put

@Composable
internal fun MailCardSurface(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = superPiColors()
    val motion = LocalDdMotionEnabled.current
    var shown by remember { mutableStateOf(!motion) }
    LaunchedEffect(Unit) { shown = true }
    val alpha by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(durationMillis = 200),
        label = "mail-card-alpha",
    )
    val rise by animateFloatAsState(
        targetValue = if (shown) 0f else 10f,
        animationSpec = tween(durationMillis = 200),
        label = "mail-card-rise",
    )
    Column(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                this.alpha = alpha
                translationY = rise.dp.toPx()
            }
            .clip(DdShapes.Card)
            .background(colors.raised),
        content = content,
    )
}

@Composable
internal fun MailDashedDivider(modifier: Modifier = Modifier) {
    val colors = superPiColors()
    Canvas(modifier.fillMaxWidth().height(1.dp)) {
        drawLine(
            color = colors.hairline,
            start = Offset(0f, 0.5f),
            end = Offset(size.width, 0.5f),
            strokeWidth = 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())),
        )
    }
}

@Composable
internal fun MailCardChip(text: String, accent: Boolean = false) {
    val colors = superPiColors()
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(if (accent) colors.accentSoft else colors.wash)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        DdText(
            text = text,
            style = DdType.chip.copy(fontSize = 11.sp, lineHeight = 16.sp),
            color = if (accent) colors.accent else colors.inkSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun MailEnvelopeGlyph(tint: Color, modifier: Modifier = Modifier) {
    val stroke = 1.4.dp
    Canvas(modifier.size(24.dp)) {
        val left = size.width * 0.13f
        val right = size.width * 0.87f
        val top = size.height * 0.29f
        val bottom = size.height * 0.76f
        drawRoundRect(
            color = tint,
            topLeft = Offset(left, top),
            size = Size(right - left, bottom - top),
            cornerRadius = CornerRadius(size.width * 0.08f),
            style = Stroke(width = stroke.toPx()),
        )
        val flap = Path().apply {
            moveTo(left + size.width * 0.06f, top + size.height * 0.06f)
            lineTo(size.width * 0.5f, size.height * 0.55f)
            lineTo(right - size.width * 0.06f, top + size.height * 0.06f)
        }
        drawPath(
            path = flap,
            color = tint,
            style = Stroke(width = stroke.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

@Composable
internal fun MailEmptyEnvelopeGlyph(modifier: Modifier = Modifier) {
    val colors = superPiColors()
    Canvas(modifier.size(52.dp)) {
        val stroke = 1.6.dp
        val left = size.width * 0.19f
        val right = size.width * 0.81f
        val top = size.height * 0.29f
        val bottom = size.height * 0.71f
        drawRoundRect(
            color = colors.inkTertiary,
            topLeft = Offset(left, top),
            size = Size(right - left, bottom - top),
            cornerRadius = CornerRadius(size.width * 0.08f),
            style = Stroke(width = stroke.toPx()),
        )
        val flap = Path().apply {
            moveTo(left + size.width * 0.03f, top + size.height * 0.04f)
            lineTo(size.width * 0.5f, size.height * 0.54f)
            lineTo(right - size.width * 0.03f, top + size.height * 0.04f)
        }
        drawPath(
            path = flap,
            color = colors.inkTertiary,
            style = Stroke(width = stroke.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
        drawCircle(
            color = colors.wash,
            radius = size.width * 0.085f,
            center = Offset(size.width * 0.68f, size.height * 0.33f),
        )
        drawCircle(
            color = colors.inkTertiary,
            radius = size.width * 0.085f,
            center = Offset(size.width * 0.68f, size.height * 0.33f),
            style = Stroke(width = 1.4.dp.toPx()),
        )
    }
}

@Composable
internal fun MailAttachmentPill(name: String, sizeText: String?) {
    val colors = superPiColors()
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(colors.wash)
            .padding(start = 9.dp, end = 10.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DdIcon(icon = DdIcons.Paperclip, tint = colors.inkSecondary, size = 11.dp)
        Spacer(Modifier.width(5.dp))
        DdText(
            text = name.ifBlank { "附件" },
            modifier = Modifier.widthIn(max = 150.dp),
            style = DdType.chip.copy(fontSize = 11.sp, lineHeight = 16.sp),
            color = colors.inkSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!sizeText.isNullOrBlank()) {
            Spacer(Modifier.width(6.dp))
            DdText(
                text = sizeText,
                style = DdType.chip.copy(fontSize = 10.sp, lineHeight = 16.sp),
                color = colors.inkTertiary,
                maxLines = 1,
            )
        }
    }
}

@Composable
internal fun MailRecipientChip(recipient: String, accent: Boolean, modifier: Modifier = Modifier) {
    val colors = superPiColors()
    val background = if (accent) colors.accentSoft else colors.wash
    val foreground = if (accent) colors.accent else colors.inkSecondary
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(background)
            .padding(start = 3.dp, end = 9.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(17.dp)
                .clip(CircleShape)
                .background(foreground.copy(alpha = DdAlpha.IndicatorDark)),
            contentAlignment = Alignment.Center,
        ) {
            DdText(
                text = mailInitial(recipient, recipient),
                style = DdType.chip.copy(fontSize = 9.sp, lineHeight = 12.sp),
                color = foreground,
                maxLines = 1,
            )
        }
        Spacer(Modifier.width(5.dp))
        DdText(
            text = recipient,
            modifier = Modifier.widthIn(max = 190.dp),
            style = DdType.chip.copy(fontSize = 11.sp, lineHeight = 16.sp),
            color = foreground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun MailLetterRules(modifier: Modifier = Modifier) {
    val colors = superPiColors()
    Column(modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.hairline))
        Spacer(Modifier.height(3.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.hairline))
    }
}

@Composable
internal fun MailUnavailableCard(
    host: PluginHost,
    title: String,
    hint: String,
    onOpenMail: () -> Unit,
) {
    val colors = superPiColors()
    MailCardSurface {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = DdSpacing.Lg, end = DdSpacing.Lg, top = DdSpacing.Lg, bottom = DdSpacing.Sm),
            verticalAlignment = Alignment.Top,
        ) {
            Spacer(Modifier.width(DdSpacing.Xxs))
            DdIcon(icon = DdIcons.Message, tint = colors.inkSecondary, size = 18.dp)
            Spacer(Modifier.width(DdSpacing.Sm))
            Column(Modifier.weight(1f)) {
                DdText(
                    text = title,
                    style = DdType.body.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(DdSpacing.Xs))
                DdText(
                    text = hint,
                    style = DdType.bodySmall,
                    color = colors.inkSecondary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        MailCardActionRow(actionLabel = host.t("打开邮箱", "Open Mail"), onAction = onOpenMail)
    }
}

@Composable
internal fun MailCardActionRow(
    actionLabel: String,
    onAction: () -> Unit,
    note: String? = null,
) {
    val colors = superPiColors()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = DdSpacing.Lg, end = DdSpacing.Xs, top = 2.dp, bottom = DdSpacing.Xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!note.isNullOrBlank()) {
            DdText(
                text = note,
                modifier = Modifier.weight(1f),
                style = DdType.chip.copy(fontSize = 11.sp),
                color = colors.inkTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        DdTextAction(text = actionLabel, onClick = onAction)
    }
}

internal fun PluginHost.openMailPage() {
    runCatching { ui().openPage("mail") }.onFailure {
        runCatching { ui().toast(t("无法打开邮箱页面", "Couldn't open the Mail page")) }
    }
}

internal fun PluginHost.openMailThreadSheet(accountId: String, messageId: String, threadId: String) {
    if (accountId.isBlank() || messageId.isBlank() || threadId.isBlank()) return
    runCatching {
        ui().openPageSheet(
            "mail",
            buildJsonObject {
                put("accountId", accountId)
                put("messageId", messageId)
                put("threadId", threadId)
            },
        )
    }.onFailure {
        runCatching { ui().toast(t("无法打开这封邮件", "Couldn't open this message")) }
    }
}

internal fun PluginHost.openMailDraftPage(draftId: String) {
    if (draftId.isBlank()) return
    runCatching {
        ui().openPageWithArguments(
            "mail",
            buildJsonObject { put("draftId", draftId) },
        )
    }.onFailure {
        runCatching { ui().toast(t("无法打开邮箱页面", "Couldn't open the Mail page")) }
    }
}

internal val mailChatCardJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
}

internal fun <T> CustomMessageSlotState.decodeCardPayload(
    deserializer: DeserializationStrategy<T>,
): T? = runCatching { mailChatCardJson.decodeFromJsonElement(deserializer, payload) }.getOrNull()
