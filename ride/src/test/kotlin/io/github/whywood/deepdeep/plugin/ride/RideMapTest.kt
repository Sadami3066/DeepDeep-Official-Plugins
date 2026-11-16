package io.github.whywood.deepdeep.plugin.ride

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RideMapTest {
    @Test
    fun samplesLongRouteKeepingEndpoints() {
        val points = (0 until 1000).map { index -> RideGeoPoint(23.0 + index * 0.0001, 113.0 + index * 0.0001) }

        val sampled = ridePolylineSample(points)

        assertEquals(RIDE_MAP_SAMPLE_POINTS, sampled.size)
        assertEquals(points.first(), sampled.first())
        assertEquals(points.last(), sampled.last())
        assertEquals(points.take(10), ridePolylineSample(points.take(10)))
        assertTrue(ridePolylineSample(emptyList()).isEmpty())
    }

    @Test
    fun projectionFillsPaddedCanvasAndKeepsAspect() {
        val points = listOf(RideGeoPoint(23.0, 113.0), RideGeoPoint(23.01, 113.02))

        val projected = rideMapProjection(points, width = 300f, height = 120f, inset = 10f)

        assertEquals(2, projected.size)
        projected.forEach { point ->
            assertTrue(point.x in 10f..290f)
            assertTrue(point.y in 10f..110f)
        }
        assertTrue(projected[0].x < projected[1].x)
        assertTrue(projected[0].y > projected[1].y)
        val spanX = projected[1].x - projected[0].x
        val spanY = projected[0].y - projected[1].y
        assertTrue(spanX > 0f && spanY > 0f)
    }

    @Test
    fun singlePointSitsInTheCenterAndEmptyInputStaysEmpty() {
        val projected = rideMapProjection(listOf(RideGeoPoint(23.1, 113.3)), 200f, 100f, 10f)

        assertEquals(1, projected.size)
        assertEquals(100f, projected[0].x, 0.01f)
        assertEquals(50f, projected[0].y, 0.01f)
        assertTrue(rideMapProjection(emptyList(), 200f, 100f, 10f).isEmpty())
        assertTrue(rideMapProjection(listOf(RideGeoPoint(23.1, 113.3)), 0f, 100f, 10f).isEmpty())
    }

    @Test
    fun labelsSkipMarkersThatAreTooClose() {
        val points = listOf(
            RideMapPoint(10f, 10f),
            RideMapPoint(14f, 12f),
            RideMapPoint(200f, 100f),
            RideMapPoint(80f, 60f),
        )

        assertEquals(listOf(0, 2, 3), rideMapLabelIndexes(points, minGap = 20f))
        assertTrue(rideMapLabelIndexes(emptyList(), 20f).isEmpty())
    }

    @Test
    fun placeAndEndpointCoordinatesBecomeGeoPoints() {
        val place = RidePlace("广州塔", "阅江西路222号", "23.10649", "113.32449", "广州市")
        val endpoint = RideEndpoint("广州塔", "阅江西路222号", "23.10649", "113.32449")

        assertEquals(RideGeoPoint(23.10649, 113.32449), place.geoPoint())
        assertEquals(RideGeoPoint(23.10649, 113.32449), endpoint.geoPoint())
        assertNull(RidePlace("无坐标", null, null, null).geoPoint())
        assertNull(RidePlace("坏坐标", null, "abc", "113.3").geoPoint())
        assertNull(RidePlace("超范围", null, "95", "113.3").geoPoint())
        assertNotNull(place.geoPoint())
    }
}
