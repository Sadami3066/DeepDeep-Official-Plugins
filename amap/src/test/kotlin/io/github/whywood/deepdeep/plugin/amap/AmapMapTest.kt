package io.github.whywood.deepdeep.plugin.amap

import java.io.File
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AmapMapTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun routeSpec(): AmapMapSpec = routeMapSpec(
        origin = LatLng(120.1, 30.2),
        destination = LatLng(120.3, 30.4),
        polyline = listOf(LatLng(120.1, 30.2), LatLng(120.2, 30.3), LatLng(120.3, 30.4)),
    )

    private fun loader(
        dataDir: File,
        network: io.github.whywood.deepdeep.plugin.api.NetworkService?,
        key: String? = "test-key",
    ): AmapMapLoader = AmapMapLoader(
        dataDir = dataDir,
        network = network,
        keyProvider = { key },
        localizer = zhLocalizer,
    )

    @Test
    fun buildsStaticMapUrlAndCacheNameWithoutLeakingKey() {
        val spec = routeSpec()

        val url = AmapStaticMap.url(spec, "secret-key-123")

        assertTrue(url.startsWith("https://restapi.amap.com/v3/staticmap?"))
        assertTrue(url.contains("location=120.2%2C30.3"))
        assertTrue(url.contains("zoom="))
        assertTrue(url.contains("size=640*360"))
        assertTrue(url.contains("scale=2"))
        assertTrue(url.contains("markers=mid%2C0x188038%2CA%3A120.1%2C30.2"))
        assertTrue(url.contains("paths=4%2C0x2563EB%2C1%2C%2C%3A120.1%2C30.2%3B120.2%2C30.3%3B120.3%2C30.4"))
        assertTrue(url.contains("key=secret-key-123"))

        val cacheName = AmapStaticMap.cacheFileName(spec)
        assertTrue(cacheName.matches(Regex("[0-9a-f]{64}\\.png")))
        assertFalse(cacheName.contains("secret"))
        assertFalse(cacheName.contains("key"))
    }

    @Test
    fun cacheNameIsStableForSameParamsAndDiffersForOtherSpecs() {
        val first = AmapStaticMap.cacheFileName(routeSpec())
        val second = AmapStaticMap.cacheFileName(routeSpec())
        val other = AmapStaticMap.cacheFileName(
            routeMapSpec(LatLng(121.0, 31.0), LatLng(121.1, 31.1), polyline = emptyList()),
        )

        assertEquals(first, second)
        assertFalse(first == other)
    }

    @Test
    fun downloadsRenderedMapIntoCacheAndReusesIt() = runTest {
        val dataDir = folder.newFolder("data")
        val network = FakeNetworkService { pngBytes() }
        val loader = loader(dataDir, network)
        val spec = routeSpec()

        val first = loader.load(spec)
        assertTrue(first is AmapMapLoad.Ready)
        val path = (first as AmapMapLoad.Ready).path
        val file = File(path)
        assertTrue(file.isFile)
        assertEquals("map-cache", file.parentFile?.name)
        assertEquals(AmapStaticMap.cacheFileName(spec), file.name)
        assertEquals(1, network.requests.size)

        val second = loader.load(spec)
        assertEquals(first, second)
        assertEquals(1, network.requests.size)
    }

    @Test
    fun forceReloadDownloadsAgain() = runTest {
        val network = FakeNetworkService { pngBytes() }
        val loader = loader(folder.newFolder("data"), network)
        val spec = routeSpec()

        loader.load(spec)
        loader.load(spec, force = true)

        assertEquals(2, network.requests.size)
    }

    @Test
    fun staticMapPermissionErrorFallsBackWithoutCaching() = runTest {
        val dataDir = folder.newFolder("data")
        val network = FakeNetworkService { """{"status":"0","info":"SERVICE_NOT_AVAILABLE_FOR_USER"}""".toByteArray() }
        val load = loader(dataDir, network).load(routeSpec())

        assertTrue(load is AmapMapLoad.Unavailable)
        val unavailable = load as AmapMapLoad.Unavailable
        assertFalse(unavailable.retryable)
        assertTrue(unavailable.reason.isNotBlank())
        val cacheDir = File(dataDir, "map-cache")
        assertTrue(cacheDir.listFiles().orEmpty().none { it.name.endsWith(".png") })
        assertTrue(cacheDir.listFiles().orEmpty().none { it.name.endsWith(".part") })
    }

    @Test
    fun networkFailureIsRetryable() = runTest {
        val network = FakeNetworkService { error("offline") }

        val load = loader(folder.newFolder("data"), network).load(routeSpec())

        assertTrue(load is AmapMapLoad.Unavailable)
        assertTrue((load as AmapMapLoad.Unavailable).retryable)
    }

    @Test
    fun missingKeyDoesNotDownload() = runTest {
        val network = FakeNetworkService { pngBytes() }

        val load = loader(folder.newFolder("data"), network, key = null).load(routeSpec())

        assertTrue(load is AmapMapLoad.Unavailable)
        assertFalse((load as AmapMapLoad.Unavailable).retryable)
        assertTrue(network.requests.isEmpty())
    }

    @Test
    fun samplesLongPolylineKeepingEndpoints() {
        val points = (0 until 240).map { index -> LatLng(120.0 + index * 0.001, 30.0 + index * 0.001) }

        val sampled = samplePolyline(points, 20)

        assertEquals(20, sampled.size)
        assertEquals(points.first(), sampled.first())
        assertEquals(points.last(), sampled.last())
        assertTrue(sampled.all { it in points })
    }

    @Test
    fun keepsShortPolylineUntouched() {
        val points = listOf(LatLng(120.1, 30.2), LatLng(120.3, 30.4))

        assertEquals(points, samplePolyline(points, 60))
    }

    @Test
    fun parsesPolylineStrings() {
        val parsed = parsePolyline("120.1,30.2;120.3,30.4")

        assertEquals(2, parsed.size)
        assertEquals("120.3,30.4", parsed[1].wire)
        assertTrue(parsePolyline(null).isEmpty())
        assertTrue(parsePolyline("bad;120.1,30.2").size == 1)
    }

    @Test
    fun joinsStepPolylinesForRouteMap() {
        val joined = routePolyline(routeSample())

        assertEquals(
            listOf(LatLng(120.1, 30.2), LatLng(120.11, 30.21), LatLng(120.3, 30.4)),
            joined,
        )
    }

    @Test
    fun searchSpecCentersOnLocatedPlaces() {
        val spec = searchMapSpec(listOf(placeSample()))!!

        assertEquals(1, spec.markers.size)
        assertEquals("A", spec.markers[0].label)
        assertEquals(LatLng(120.145, 30.256), spec.markers[0].location)
        assertEquals(LatLng(120.145, 30.256), spec.center)
    }

    @Test
    fun searchSpecSkipsPlacesWithoutCoordinates() {
        val withoutLocation = placeSample().copy(location = null)

        assertNull(searchMapSpec(listOf(withoutLocation)))
    }

    @Test
    fun transitSpecUsesEndpointMarkersWithoutPolyline() {
        val spec = routeMapSpec(LatLng(120.1, 30.2), LatLng(120.3, 30.4), polyline = emptyList())

        assertEquals(2, spec.markers.size)
        assertEquals("A", spec.markers[0].label)
        assertEquals("B", spec.markers[1].label)
        assertTrue(spec.polyline.isEmpty())
        assertEquals("120.2, 30.3", spec.center.display)
    }

    @Test
    fun routeSpecSamplesLongPolyline() {
        val points = (0 until 300).map { index -> LatLng(120.0 + index * 0.0005, 30.0 + index * 0.0005) }

        val spec = routeMapSpec(points.first(), points.last(), points)

        assertEquals(AMAP_MAP_MAX_POLYLINE_POINTS, spec.polyline.size)
    }

    @Test
    fun mapSpecRoundTripsThroughDetailsJson() {
        val spec = routeSpec()
        val details = buildJsonObject { put("map", spec.toJson()) }

        val decoded = decodeMapSpec(details)

        assertNotNull(decoded)
        assertEquals(spec.center, decoded!!.center)
        assertEquals(spec.zoom, decoded.zoom)
        assertEquals(spec.markers, decoded.markers)
        assertEquals(spec.polyline, decoded.polyline)
        assertEquals(spec.width, decoded.width)
        assertEquals(spec.height, decoded.height)
        assertEquals(spec.scale, decoded.scale)
    }

    @Test
    fun decodeMapSpecIgnoresMissingMap() {
        assertNull(decodeMapSpec(null))
        assertNull(decodeMapSpec(buildJsonObject { put("kind", "route") }))
    }
}
