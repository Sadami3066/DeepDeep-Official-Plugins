package io.github.whywood.deepdeep.plugin.ride

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min

internal const val RIDE_MAP_SAMPLE_POINTS = 240

internal data class RideMapPoint(
    val x: Float,
    val y: Float,
)

internal fun RidePlace.geoPoint(): RideGeoPoint? = rideGeoPoint(latitude, longitude)

internal fun RideEndpoint.geoPoint(): RideGeoPoint? = rideGeoPoint(latitude, longitude)

private fun rideGeoPoint(latitude: String?, longitude: String?): RideGeoPoint? {
    val latitudeValue = latitude?.toDoubleOrNull() ?: return null
    val longitudeValue = longitude?.toDoubleOrNull() ?: return null
    if (latitudeValue !in -90.0..90.0 || longitudeValue !in -180.0..180.0) return null
    return RideGeoPoint(latitudeValue, longitudeValue)
}

internal fun ridePolylineSample(points: List<RideGeoPoint>, maxPoints: Int = RIDE_MAP_SAMPLE_POINTS): List<RideGeoPoint> {
    if (maxPoints < 2 || points.size <= maxPoints) return points
    val step = (points.size - 1).toDouble() / (maxPoints - 1)
    return (0 until maxPoints).map { index ->
        points[(index * step).toInt().coerceAtMost(points.lastIndex)]
    }
}

internal fun rideMapProjection(
    points: List<RideGeoPoint>,
    width: Float,
    height: Float,
    inset: Float,
): List<RideMapPoint> {
    if (points.isEmpty() || width <= 0f || height <= 0f) return emptyList()
    val scaleLatitude = cos(Math.toRadians(points.sumOf { it.latitude } / points.size)).coerceAtLeast(0.1)
    val xs = points.map { it.longitude * scaleLatitude }
    val ys = points.map { it.latitude }
    val minX = xs.min()
    val maxX = xs.max()
    val minY = ys.min()
    val maxY = ys.max()
    val spanX = maxX - minX
    val spanY = maxY - minY
    val usableWidth = (width - inset * 2f).coerceAtLeast(1f)
    val usableHeight = (height - inset * 2f).coerceAtLeast(1f)
    val scale = when {
        spanX <= 1e-9 && spanY <= 1e-9 -> 0.0
        spanX <= 1e-9 -> usableHeight / spanY
        spanY <= 1e-9 -> usableWidth / spanX
        else -> min(usableWidth / spanX, usableHeight / spanY)
    }
    val centerX = (minX + maxX) / 2.0
    val centerY = (minY + maxY) / 2.0
    return points.mapIndexed { index, _ ->
        val x = if (scale <= 0.0) width / 2f else (width / 2f + (xs[index] - centerX) * scale).toFloat()
        val y = if (scale <= 0.0) height / 2f else (height / 2f - (ys[index] - centerY) * scale).toFloat()
        RideMapPoint(
            x = x.coerceIn(inset, width - inset),
            y = y.coerceIn(inset, height - inset),
        )
    }
}

internal fun rideMapLabelIndexes(
    points: List<RideMapPoint>,
    minGap: Float,
): List<Int> {
    val chosen = mutableListOf<Int>()
    points.forEachIndexed { index, point ->
        val close = chosen.any { other ->
            val chosenPoint = points[other]
            hypot(point.x - chosenPoint.x, point.y - chosenPoint.y) < minGap
        }
        if (!close) chosen += index
    }
    return chosen
}

