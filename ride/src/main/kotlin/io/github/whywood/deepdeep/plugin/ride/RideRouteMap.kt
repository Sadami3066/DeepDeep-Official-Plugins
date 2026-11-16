package io.github.whywood.deepdeep.plugin.ride

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.whywood.deepdeep.design.DdShapes
import io.github.whywood.deepdeep.design.DdType
import io.github.whywood.deepdeep.design.DeepDeepColors
import io.github.whywood.deepdeep.design.deepDeepColors

@Composable
internal fun RideRouteMap(
    route: List<RideGeoPoint>,
    from: RideGeoPoint?,
    to: RideGeoPoint?,
    fromLabel: String,
    toLabel: String,
    modifier: Modifier = Modifier,
) {
    val colors = deepDeepColors()
    val measurer = rememberTextMeasurer()
    val labelStyle = DdType.chip.copy(color = colors.inkSecondary)
    Box(
        modifier
            .fillMaxWidth()
            .height(ROUTE_MAP_HEIGHT)
            .clip(DdShapes.Medium)
            .background(colors.wash),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val inset = 18.dp.toPx()
            drawMapGrid(colors = colors)
            val sampled = ridePolylineSample(route, RIDE_MAP_SAMPLE_POINTS)
            val endpoints = listOfNotNull(from, to)
            val projected = rideMapProjection(sampled + endpoints, size.width, size.height, inset)
            val routePoints = projected.take(sampled.size)
            val fromPoint = from?.let { projected.getOrNull(sampled.size) }
            val toOffset = if (from == null) 0 else 1
            val toPoint = to?.let { projected.getOrNull(sampled.size + toOffset) }
            if (routePoints.size >= 2) {
                val path = Path().apply {
                    moveTo(routePoints.first().x, routePoints.first().y)
                    routePoints.drop(1).forEach { point -> lineTo(point.x, point.y) }
                }
                drawPath(
                    path = path,
                    color = colors.accent.copy(alpha = 0.14f),
                    style = Stroke(width = 9.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
                drawPath(
                    path = path,
                    color = colors.accent,
                    style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            } else if (fromPoint != null && toPoint != null) {
                drawLine(
                    color = colors.accent,
                    start = Offset(fromPoint.x, fromPoint.y),
                    end = Offset(toPoint.x, toPoint.y),
                    strokeWidth = 3.dp.toPx(),
                    cap = StrokeCap.Round,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx())),
                )
            }
            fromPoint?.let { point ->
                drawCircle(colors.surface, radius = 7.dp.toPx(), center = Offset(point.x, point.y))
                drawCircle(colors.accent, radius = 4.5.dp.toPx(), center = Offset(point.x, point.y))
                drawMapLabel(measurer, fromLabel, point, size.width, labelStyle)
            }
            toPoint?.let { point ->
                drawCircle(colors.surface, radius = 7.dp.toPx(), center = Offset(point.x, point.y))
                drawCircle(colors.ink, radius = 4.5.dp.toPx(), center = Offset(point.x, point.y))
                drawMapLabel(measurer, toLabel, point, size.width, labelStyle)
            }
        }
    }
}

@Composable
internal fun RidePlaceMap(
    points: List<RideGeoPoint>,
    labels: List<String>,
    modifier: Modifier = Modifier,
) {
    val colors = deepDeepColors()
    val measurer = rememberTextMeasurer()
    val labelStyle = DdType.chip.copy(color = colors.inkSecondary)
    Box(
        modifier
            .fillMaxWidth()
            .height(PLACE_MAP_HEIGHT)
            .clip(DdShapes.Medium)
            .background(colors.wash),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val inset = 16.dp.toPx()
            drawMapGrid(colors = colors)
            val projected = rideMapProjection(points, size.width, size.height, inset)
            val labeled = rideMapLabelIndexes(projected, 26.dp.toPx()).toSet()
            projected.forEachIndexed { index, point ->
                val center = Offset(point.x, point.y)
                drawCircle(colors.surface, radius = 5.5.dp.toPx(), center = center)
                drawCircle(colors.accent, radius = 3.5.dp.toPx(), center = center)
                if (index in labeled) {
                    val text = labels.getOrNull(index) ?: (index + 1).toString()
                    drawMapLabel(measurer, text, point, size.width, labelStyle)
                }
            }
        }
    }
}

private fun DrawScope.drawMapGrid(colors: DeepDeepColors) {
    val color = colors.hairline.copy(alpha = 0.55f)
    val width = 1.dp.toPx()
    for (index in 1..3) {
        val x = size.width * index / 4f
        drawLine(color, Offset(x, 0f), Offset(x, size.height), width)
    }
    for (index in 1..2) {
        val y = size.height * index / 3f
        drawLine(color, Offset(0f, y), Offset(size.width, y), width)
    }
}

private fun DrawScope.drawMapLabel(
    measurer: TextMeasurer,
    text: String,
    point: RideMapPoint,
    canvasWidth: Float,
    style: TextStyle,
) {
    val layout = measurer.measure(text, style = style)
    val rawX = if (point.x > canvasWidth * 0.72f) {
        point.x - layout.size.width - 9.dp.toPx()
    } else {
        point.x + 9.dp.toPx()
    }
    val offsetX = rawX.coerceIn(2.dp.toPx(), (canvasWidth - layout.size.width - 2.dp.toPx()).coerceAtLeast(2.dp.toPx()))
    val offsetY = (point.y - layout.size.height / 2f).coerceIn(2.dp.toPx(), (size.height - layout.size.height - 2.dp.toPx()).coerceAtLeast(2.dp.toPx()))
    drawText(layout, topLeft = Offset(offsetX, offsetY))
}

private val ROUTE_MAP_HEIGHT: Dp = 136.dp
private val PLACE_MAP_HEIGHT: Dp = 124.dp
