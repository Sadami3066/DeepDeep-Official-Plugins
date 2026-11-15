package io.github.whywood.deepdeep.plugin.amap

import io.github.whywood.deepdeep.plugin.api.NetworkService
import java.io.File
import java.net.URLEncoder
import java.security.MessageDigest
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal const val AMAP_MAP_WIDTH = 640
internal const val AMAP_MAP_HEIGHT = 360
internal const val AMAP_MAP_SCALE = 2
internal const val AMAP_MAP_MAX_POLYLINE_POINTS = 60

internal data class AmapMapMarker(
    val location: LatLng,
    val label: String,
    val color: String,
)

internal data class AmapMapSpec(
    val center: LatLng,
    val zoom: Int,
    val markers: List<AmapMapMarker>,
    val polyline: List<LatLng>,
    val width: Int = AMAP_MAP_WIDTH,
    val height: Int = AMAP_MAP_HEIGHT,
    val scale: Int = AMAP_MAP_SCALE,
)

internal sealed interface AmapMapLoad {
    data object Loading : AmapMapLoad

    data class Ready(val path: String) : AmapMapLoad

    data class Unavailable(val reason: String, val retryable: Boolean) : AmapMapLoad
}

internal object AmapStaticMap {
    private const val ENDPOINT = "https://restapi.amap.com/v3/staticmap"

    fun params(spec: AmapMapSpec): List<Pair<String, String>> = buildList {
        add("location" to spec.center.wire)
        add("zoom" to spec.zoom.coerceIn(MIN_ZOOM, MAX_ZOOM).toString())
        add("size" to "${spec.width}*${spec.height}")
        add("scale" to spec.scale.coerceIn(1, 2).toString())
        if (spec.markers.isNotEmpty()) {
            add(
                "markers" to spec.markers.joinToString("|") { marker ->
                    "$MARKER_SIZE,${marker.color},${marker.label}:${marker.location.wire}"
                },
            )
        }
        if (spec.polyline.isNotEmpty()) {
            add("paths" to "$PATH_STYLE:" + spec.polyline.joinToString(";") { it.wire })
        }
    }

    fun url(spec: AmapMapSpec, key: String): String {
        val query = params(spec).joinToString("&") { (name, value) -> "$name=${encode(value)}" }
        return "$ENDPOINT?$query&key=${encode(key)}"
    }

    fun cacheFileName(spec: AmapMapSpec): String {
        val canonical = params(spec).joinToString("&") { (name, value) -> "$name=$value" }
        return sha256(canonical) + ".png"
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xFF) }
    }

    private const val PATH_STYLE = "4,0x2563EB,1,,"
    private const val MARKER_SIZE = "mid"
    private const val MIN_ZOOM = 3
    private const val MAX_ZOOM = 17
}

internal class AmapMapLoader(
    private val dataDir: File,
    private val network: NetworkService?,
    private val keyProvider: suspend () -> String?,
    private val localizer: AmapLocalizer,
) {
    suspend fun load(spec: AmapMapSpec, force: Boolean = false): AmapMapLoad {
        val directory = File(dataDir, CACHE_DIR)
        val target = File(directory, AmapStaticMap.cacheFileName(spec))
        if (!force && target.isFile && target.length() > 0L) return AmapMapLoad.Ready(target.absolutePath)
        if (force) target.delete()

        val key = keyProvider()?.trim().orEmpty()
        if (key.isEmpty()) {
            return AmapMapLoad.Unavailable(
                localizer.t(
                    "配置高德 Key 后可以显示地图预览。",
                    "Add an Amap key to show the map preview.",
                ),
                retryable = false,
            )
        }
        val service = network ?: return AmapMapLoad.Unavailable(
            localizer.t("地图预览暂时不可用。", "The map preview is not available right now."),
            retryable = false,
        )

        runCatching { directory.mkdirs() }
        prune(directory)
        val temporary = File(directory, target.name + ".part")
        temporary.delete()
        val downloaded = runCatching {
            service.download(AmapStaticMap.url(spec, key), temporary.absolutePath)
        }.getOrNull()
        if (downloaded == null || downloaded.size <= 0L || !temporary.isFile || temporary.length() <= 0L) {
            temporary.delete()
            return AmapMapLoad.Unavailable(
                localizer.t(
                    "地图预览加载失败，请检查网络后重试。",
                    "The map preview failed to load. Check your connection and retry.",
                ),
                retryable = true,
            )
        }
        if (!isImage(temporary)) {
            val errorText = runCatching { temporary.readText().take(500) }.getOrDefault("")
            temporary.delete()
            val failure = classifyAmapFailure(0, errorText)
            return when (failure) {
                AmapFailure.INVALID_KEY -> AmapMapLoad.Unavailable(
                    localizer.t(
                        "地图预览需要在开放平台为这个高德 Key 开启静态地图服务。",
                        "The map preview needs the static map service enabled for this Amap key.",
                    ),
                    retryable = false,
                )
                AmapFailure.QUOTA -> AmapMapLoad.Unavailable(
                    localizer.t(
                        "地图预览调用额度已用完，可以稍后再试。",
                        "The map preview quota is used up. Try again later.",
                    ),
                    retryable = true,
                )
                else -> AmapMapLoad.Unavailable(
                    localizer.t(
                        "地图预览暂时无法加载，可以稍后重试。",
                        "The map preview could not be loaded. Try again later.",
                    ),
                    retryable = true,
                )
            }
        }
        if (!temporary.renameTo(target)) {
            if (!target.isFile) {
                temporary.delete()
                return AmapMapLoad.Unavailable(
                    localizer.t(
                        "地图预览暂时无法加载，可以稍后重试。",
                        "The map preview could not be loaded. Try again later.",
                    ),
                    retryable = true,
                )
            }
            temporary.delete()
        }
        if (!target.isFile || target.length() <= 0L) {
            return AmapMapLoad.Unavailable(
                localizer.t(
                    "地图预览暂时无法加载，可以稍后重试。",
                    "The map preview could not be loaded. Try again later.",
                ),
                retryable = true,
            )
        }
        return AmapMapLoad.Ready(target.absolutePath)
    }

    private fun isImage(file: File): Boolean {
        if (!file.isFile || file.length() < 12L) return false
        val header = ByteArray(12)
        val read = runCatching { file.inputStream().use { it.read(header) } }.getOrDefault(0)
        if (read < 12) return false
        fun byteAt(index: Int): Int = header[index].toInt() and 0xFF
        val png = byteAt(0) == 0x89 && byteAt(1) == 0x50 && byteAt(2) == 0x4E && byteAt(3) == 0x47
        val jpeg = byteAt(0) == 0xFF && byteAt(1) == 0xD8
        val gif = byteAt(0) == 0x47 && byteAt(1) == 0x49 && byteAt(2) == 0x46
        val webp = byteAt(0) == 0x52 && byteAt(1) == 0x49 && byteAt(2) == 0x46 && byteAt(3) == 0x46 &&
            byteAt(8) == 0x57 && byteAt(9) == 0x45 && byteAt(10) == 0x42 && byteAt(11) == 0x50
        return png || jpeg || gif || webp
    }

    private fun prune(directory: File) {
        val files = directory.listFiles()?.filter { it.isFile } ?: return
        val now = System.currentTimeMillis()
        files.filter { it.name.endsWith(".part") && now - it.lastModified() > PART_TTL_MS }
            .forEach { it.delete() }
        var total = files.filterNot { it.name.endsWith(".part") }.sumOf { it.length() }
        if (total <= MAX_CACHE_BYTES) return
        files.filterNot { it.name.endsWith(".part") }
            .sortedBy { it.lastModified() }
            .forEach { file ->
                if (total <= MAX_CACHE_BYTES) return
                total -= file.length()
                file.delete()
            }
    }

    private companion object {
        const val CACHE_DIR = "map-cache"
        const val MAX_CACHE_BYTES = 8L * 1024L * 1024L
        const val PART_TTL_MS = 60L * 60L * 1000L
    }
}

internal fun searchMapSpec(places: List<AmapPlace>): AmapMapSpec? {
    val located = places.mapNotNull { place -> place.location?.let { it to place } }.take(MAX_SEARCH_MARKERS)
    if (located.isEmpty()) return null
    val points = located.map { it.first }
    return AmapMapSpec(
        center = boundsCenter(points) ?: points.first(),
        zoom = fitMapZoom(points, singleZoom = 14),
        markers = located.mapIndexed { index, (location, _) ->
            AmapMapMarker(location, markerLabel(index), MARKER_BLUE)
        },
        polyline = emptyList(),
    )
}

internal fun aroundMapSpec(
    center: LatLng,
    places: List<AmapPlace>,
    radiusMeters: Int,
): AmapMapSpec {
    val located = places.mapNotNull { it.location }.take(MAX_AROUND_MARKERS)
    return AmapMapSpec(
        center = center,
        zoom = radiusZoom(center, radiusMeters),
        markers = located.mapIndexed { index, location ->
            AmapMapMarker(location, markerLabel(index), MARKER_BLUE)
        },
        polyline = emptyList(),
    )
}

internal fun regeoMapSpec(location: LatLng): AmapMapSpec = AmapMapSpec(
    center = location,
    zoom = 16,
    markers = listOf(AmapMapMarker(location, markerLabel(0), MARKER_BLUE)),
    polyline = emptyList(),
)

internal fun routeMapSpec(
    origin: LatLng,
    destination: LatLng,
    polyline: List<LatLng>,
): AmapMapSpec {
    val points = polyline.ifEmpty { listOf(origin, destination) }
    val markers = buildList {
        add(AmapMapMarker(origin, "A", MARKER_ORIGIN))
        if (destination != origin) add(AmapMapMarker(destination, "B", MARKER_DESTINATION))
    }
    return AmapMapSpec(
        center = boundsCenter(points) ?: origin,
        zoom = fitMapZoom(points, singleZoom = 15),
        markers = markers,
        polyline = samplePolyline(polyline),
    )
}

internal fun routePolyline(route: AmapRoute): List<LatLng> = buildList {
    route.steps.forEach { step ->
        step.polyline.forEach { point ->
            if (lastOrNull() != point) add(point)
        }
    }
}

internal fun samplePolyline(points: List<LatLng>, maxPoints: Int = AMAP_MAP_MAX_POLYLINE_POINTS): List<LatLng> {
    if (maxPoints <= 0 || points.size <= maxPoints) return points
    if (points.size <= 2) return points
    val step = (points.size - 1).toDouble() / (maxPoints - 1)
    return (0 until maxPoints).map { index ->
        points[(index * step).toInt().coerceIn(0, points.lastIndex)]
    }
}

internal fun boundsCenter(points: List<LatLng>): LatLng? {
    if (points.isEmpty()) return null
    var minLongitude = points.first().longitude
    var maxLongitude = minLongitude
    var minLatitude = points.first().latitude
    var maxLatitude = minLatitude
    points.forEach { point ->
        minLongitude = min(minLongitude, point.longitude)
        maxLongitude = max(maxLongitude, point.longitude)
        minLatitude = min(minLatitude, point.latitude)
        maxLatitude = max(maxLatitude, point.latitude)
    }
    return LatLng((minLongitude + maxLongitude) / 2.0, (minLatitude + maxLatitude) / 2.0)
}

internal fun fitMapZoom(points: List<LatLng>, singleZoom: Int = 16): Int {
    if (points.isEmpty()) return singleZoom
    if (points.size == 1) return singleZoom.coerceIn(3, 17)
    var minLongitude = points.first().longitude
    var maxLongitude = minLongitude
    var minLatitude = points.first().latitude
    var maxLatitude = minLatitude
    points.forEach { point ->
        minLongitude = min(minLongitude, point.longitude)
        maxLongitude = max(maxLongitude, point.longitude)
        minLatitude = min(minLatitude, point.latitude)
        maxLatitude = max(maxLatitude, point.latitude)
    }
    val centerLatitude = (minLatitude + maxLatitude) / 2.0
    val latitudeScale = cos(Math.toRadians(centerLatitude))
    val spanX = max((maxLongitude - minLongitude) * 111_320.0 * latitudeScale, 1.0)
    val spanY = max((maxLatitude - minLatitude) * 110_540.0, 1.0)
    val metersPerPixelAtZoomZero = 156_543.03392 * latitudeScale
    val neededMetersPerPixel = max(spanX / (AMAP_MAP_WIDTH * 0.8), spanY / (AMAP_MAP_HEIGHT * 0.8))
    if (neededMetersPerPixel <= 0.0) return 17
    val zoom = floor(ln(metersPerPixelAtZoomZero / neededMetersPerPixel) / ln(2.0)).toInt()
    return zoom.coerceIn(3, 17)
}

internal fun radiusZoom(center: LatLng, radiusMeters: Int): Int {
    val latitudeScale = cos(Math.toRadians(center.latitude)).coerceAtLeast(0.1)
    val metersPerPixelAtZoomZero = 156_543.03392 * latitudeScale
    val visibleMeters = max(radiusMeters.toDouble() * 2.0, 300.0)
    val neededMetersPerPixel = visibleMeters / AMAP_MAP_WIDTH
    val zoom = floor(ln(metersPerPixelAtZoomZero / neededMetersPerPixel) / ln(2.0)).toInt()
    return zoom.coerceIn(10, 17)
}

internal fun AmapMapSpec.toJson(): JsonObject = buildJsonObject {
    put("center", center.toJson())
    put("zoom", zoom)
    put("width", width)
    put("height", height)
    put("scale", scale)
    put("markers", buildJsonArray {
        markers.forEach { marker ->
            add(buildJsonObject {
                put("longitude", marker.location.longitude)
                put("latitude", marker.location.latitude)
                put("label", marker.label)
                put("color", marker.color)
            })
        }
    })
    put("polyline", buildJsonArray {
        polyline.forEach { add(it.toJson()) }
    })
}

internal fun decodeMapSpec(details: JsonObject?): AmapMapSpec? {
    val root = details?.objectValue("map") ?: return null
    val center = latLngFrom(root.objectValue("center") ?: return null) ?: return null
    val markers = root.arrayValue("markers")?.mapNotNull { element ->
        val marker = element.asObjectOrNull() ?: return@mapNotNull null
        val location = latLngFrom(marker) ?: return@mapNotNull null
        AmapMapMarker(
            location = location,
            label = marker.textValue("label") ?: markerLabel(0),
            color = marker.textValue("color") ?: MARKER_BLUE,
        )
    }.orEmpty()
    val polyline = root.arrayValue("polyline")?.mapNotNull { element ->
        latLngFrom(element.asObjectOrNull() ?: return@mapNotNull null)
    }.orEmpty()
    return AmapMapSpec(
        center = center,
        zoom = root.intValue("zoom") ?: 14,
        markers = markers,
        polyline = polyline,
        width = root.intValue("width") ?: AMAP_MAP_WIDTH,
        height = root.intValue("height") ?: AMAP_MAP_HEIGHT,
        scale = root.intValue("scale") ?: AMAP_MAP_SCALE,
    )
}

private fun LatLng.toJson(): JsonObject = buildJsonObject {
    put("longitude", longitude)
    put("latitude", latitude)
}

private fun markerLabel(index: Int): String = ('A' + index.coerceIn(0, 25)).toString()

private const val MAX_SEARCH_MARKERS = 6
private const val MAX_AROUND_MARKERS = 3
private const val MARKER_BLUE = "0x2563EB"
private const val MARKER_ORIGIN = "0x188038"
private const val MARKER_DESTINATION = "0xD93025"
