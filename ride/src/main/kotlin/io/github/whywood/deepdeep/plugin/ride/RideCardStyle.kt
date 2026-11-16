package io.github.whywood.deepdeep.plugin.ride

import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.whywood.deepdeep.design.DdAlpha
import io.github.whywood.deepdeep.design.DdHairline
import io.github.whywood.deepdeep.design.DdIcon
import io.github.whywood.deepdeep.design.DdIcon as RideCardIcon
import io.github.whywood.deepdeep.design.DdMotion
import io.github.whywood.deepdeep.design.DdShapes
import io.github.whywood.deepdeep.design.DdSpacing
import io.github.whywood.deepdeep.design.DdSpinner
import io.github.whywood.deepdeep.design.DdText
import io.github.whywood.deepdeep.design.DdType
import io.github.whywood.deepdeep.design.DeepDeepColors
import io.github.whywood.deepdeep.design.LocalDdMotionEnabled
import io.github.whywood.deepdeep.design.deepDeepColors

@Composable
internal fun RideCardEntrance(content: @Composable () -> Unit) {
    val motion = LocalDdMotionEnabled.current
    var shown by remember { mutableStateOf(!motion) }
    LaunchedEffect(Unit) { shown = true }
    val progress by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = DdMotion.spec<Float>(DdMotion.FAST),
        label = "ride-card-entrance",
    )
    Box(
        Modifier.graphicsLayer {
            alpha = progress
            translationY = (1f - progress) * 8.dp.toPx()
        },
    ) {
        content()
    }
}

@Composable
internal fun RideSurface(
    spacing: Dp = DdSpacing.Md,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = deepDeepColors()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(DdShapes.Card)
            .background(colors.surface)
            .padding(horizontal = DdSpacing.Lg, vertical = DdSpacing.Lg),
        verticalArrangement = Arrangement.spacedBy(spacing),
        content = content,
    )
}

@Composable
internal fun RideRouteStrip(
    from: String?,
    to: String?,
    translate: RideTranslate,
    modifier: Modifier = Modifier,
    fromLabel: String? = null,
    toLabel: String? = null,
) {
    val colors = deepDeepColors()
    val fromName = from?.takeIf { it.isNotBlank() } ?: translate("出发地", "Pickup")
    val toName = to?.takeIf { it.isNotBlank() } ?: translate("目的地", "Destination")
    val labelHeight = 18.dp
    val nameHeight = 26.dp
    val stopHeight = 44.dp
    val gap = 6.dp
    val stripHeight = stopHeight * 2 + gap
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Canvas(Modifier.width(16.dp).height(stripHeight)) {
            val x = 8.dp.toPx()
            val dotRadius = 4.5.dp.toPx()
            val firstCenter = labelHeight.toPx() + nameHeight.toPx() / 2f
            val secondCenter = firstCenter + stopHeight.toPx() + gap.toPx()
            drawLine(
                color = colors.hairline,
                start = Offset(x, firstCenter + dotRadius + 3.dp.toPx()),
                end = Offset(x, secondCenter - dotRadius - 3.dp.toPx()),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())),
            )
            drawCircle(color = colors.accent, radius = dotRadius, center = Offset(x, firstCenter))
            drawCircle(color = colors.surface, radius = dotRadius + 1.dp.toPx(), center = Offset(x, secondCenter))
            drawCircle(
                color = colors.inkSecondary,
                radius = dotRadius + 1.dp.toPx(),
                center = Offset(x, secondCenter),
                style = Stroke(width = 2.dp.toPx()),
            )
        }
        Spacer(Modifier.width(DdSpacing.Md))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(gap)) {
            RouteStop(
                label = fromLabel ?: translate("出发", "From"),
                name = fromName,
                nameStyle = DdType.body,
            )
            RouteStop(
                label = toLabel ?: translate("到达", "To"),
                name = toName,
                nameStyle = DdType.body.copy(fontWeight = FontWeight.SemiBold),
            )
        }
    }
}

@Composable
private fun RouteStop(label: String, name: String, nameStyle: TextStyle) {
    val colors = deepDeepColors()
    Column {
        DdText(label, style = DdType.chip, color = colors.inkTertiary, maxLines = 1)
        DdText(name, style = nameStyle, color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun RideFactStrip(facts: List<String>) {
    if (facts.isEmpty()) return
    val colors = deepDeepColors()
    DdText(
        text = facts.joinToString(" · "),
        style = DdType.chip,
        color = colors.inkSecondary,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
internal fun RideFieldLabel(text: String) {
    DdText(text, style = DdType.chip, color = deepDeepColors().inkTertiary, maxLines = 1)
}

@Composable
internal fun RideTag(text: String, accent: Boolean = true) {
    val colors = deepDeepColors()
    val tint = if (accent) colors.accent else colors.inkSecondary
    val shape = CircleShape
    Box(
        Modifier
            .clip(shape)
            .background(if (accent) colors.accentSoft else Color.Transparent)
            .border(1.dp, if (accent) colors.accent.copy(alpha = DdAlpha.Border) else colors.hairline, shape)
            .padding(horizontal = DdSpacing.Sm, vertical = 1.dp),
    ) {
        DdText(text, style = DdType.chip, color = tint, maxLines = 1)
    }
}

@Composable
internal fun RidePlate(text: String) {
    val colors = deepDeepColors()
    Box(
        Modifier
            .clip(DdShapes.Medium)
            .background(colors.wash)
            .border(1.dp, colors.hairline, DdShapes.Medium)
            .padding(horizontal = DdSpacing.Md, vertical = 3.dp),
    ) {
        DdText(text, style = DdType.button.copy(letterSpacing = 0.5.sp), color = colors.ink, maxLines = 1)
    }
}

@Composable
internal fun RidePriceBlock(
    price: String?,
    listPrice: String?,
    highlighted: Boolean,
    priceLabel: String? = null,
    listLabel: String? = null,
) {
    val colors = deepDeepColors()
    Column(horizontalAlignment = Alignment.End) {
        listPrice?.let {
            Row(verticalAlignment = Alignment.CenterVertically) {
                listLabel?.let { label ->
                    DdText(label, style = DdType.micro, color = colors.inkTertiary, maxLines = 1)
                    Spacer(Modifier.width(3.dp))
                }
                DdText(
                    text = it,
                    style = DdType.chip.copy(textDecoration = TextDecoration.LineThrough),
                    color = colors.inkTertiary,
                    maxLines = 1,
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            priceLabel?.let { label ->
                DdText(label, style = DdType.chip, color = colors.inkTertiary, maxLines = 1)
                Spacer(Modifier.width(3.dp))
            }
            DdText(
                text = price ?: "—",
                style = DdType.heading,
                color = when {
                    price == null -> colors.inkTertiary
                    highlighted -> colors.accent
                    else -> colors.ink
                },
                maxLines = 1,
            )
        }
    }
}

@Composable
internal fun RideStateBody(
    title: String,
    message: String? = null,
    hint: String? = null,
    loading: Boolean = false,
    icon: RideCardIcon? = null,
    tone: Color? = null,
    skeleton: Boolean = false,
) {
    val colors = deepDeepColors()
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DdSpacing.Sm),
    ) {
        when {
            loading -> DdSpinner(color = colors.accent, size = 16.dp)
            icon != null -> DdIcon(icon = icon, tint = tone ?: colors.inkSecondary, size = 18.dp)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            DdText(title, style = DdType.button, color = colors.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            message?.takeIf { it.isNotBlank() }?.let {
                DdText(it, style = DdType.bodySmall, color = colors.inkSecondary, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    if (skeleton) {
        Column(verticalArrangement = Arrangement.spacedBy(DdSpacing.Sm)) {
            RideSkeletonBar(0.62f)
            RideSkeletonBar(0.44f)
            RideSkeletonBar(0.52f)
        }
    }
    hint?.takeIf { it.isNotBlank() }?.let {
        DdText(it, style = DdType.chip, color = colors.inkTertiary, maxLines = 2)
    }
}

@Composable
internal fun RideSkeletonBar(widthFraction: Float) {
    val colors = deepDeepColors()
    Box(
        Modifier
            .fillMaxWidth(widthFraction)
            .height(12.dp)
            .clip(CircleShape)
            .background(rideTrackColor(colors)),
    )
}

@Composable
internal fun RideRadar(modifier: Modifier = Modifier) {
    val colors = deepDeepColors()
    Canvas(modifier.size(52.dp)) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val outer = size.minDimension / 2f - 0.5.dp.toPx()
        drawCircle(color = colors.hairline, radius = outer, center = center, style = Stroke(width = 1.dp.toPx()))
        drawCircle(
            color = colors.hairline,
            radius = outer * 0.62f,
            center = center,
            style = Stroke(width = 1.dp.toPx()),
        )
        drawCircle(
            color = colors.accent.copy(alpha = 0.45f),
            radius = outer * 0.34f,
            center = center,
            style = Stroke(width = 1.5.dp.toPx()),
        )
        drawArc(
            color = colors.accent.copy(alpha = 0.55f),
            startAngle = -65f,
            sweepAngle = 70f,
            useCenter = false,
            topLeft = Offset(center.x - outer, center.y - outer),
            size = androidx.compose.ui.geometry.Size(outer * 2f, outer * 2f),
            style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round),
        )
        drawCircle(color = colors.accent, radius = 2.5.dp.toPx(), center = center)
    }
}

@Composable
internal fun RideDivider() {
    DdHairline(color = deepDeepColors().hairline.copy(alpha = DdAlpha.Divider))
}

internal fun rideTrackColor(colors: DeepDeepColors): Color =
    colors.ink.copy(alpha = if (colors.dark) DdAlpha.TrackDark else DdAlpha.TrackLight)

internal fun rideStatusToneColor(colors: DeepDeepColors, code: String?): Color = when (code) {
    "0" -> colors.accent
    "1", "2", "4", "5" -> colors.success
    "6", "7", "11", "12" -> colors.danger
    "3", "8", "9", "10" -> colors.warning
    else -> colors.inkTertiary
}
