package io.github.whywood.deepdeep.plugin.amap

import io.github.whywood.deepdeep.plugin.api.DownloadResult
import io.github.whywood.deepdeep.plugin.api.NetworkResponse
import io.github.whywood.deepdeep.plugin.api.NetworkService
import java.io.File
import java.util.Locale

internal val zhLocalizer = AmapLocalizer { zh, _, args ->
    if (args.isEmpty()) zh else String.format(Locale.US, zh, *args.toTypedArray())
}

internal class FakeNetworkService(
    private val body: (String) -> ByteArray = { error("unexpected request") },
) : NetworkService {
    val requests = mutableListOf<String>()

    override suspend fun request(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String?,
        timeoutMs: Long,
    ): NetworkResponse = throw UnsupportedOperationException("request is not used by map previews")

    override suspend fun download(
        url: String,
        targetPath: String,
        expectedSha256: String?,
        onProgress: ((Int?) -> Unit)?,
    ): DownloadResult {
        requests += url
        val bytes = body(url)
        val file = File(targetPath)
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        return DownloadResult(path = file.absolutePath, size = bytes.size.toLong(), sha256 = "")
    }
}

internal fun pngBytes(size: Int = 32): ByteArray = ByteArray(size).also { bytes ->
    bytes[0] = 0x89.toByte()
    bytes[1] = 'P'.code.toByte()
    bytes[2] = 'N'.code.toByte()
    bytes[3] = 'G'.code.toByte()
    bytes[4] = 0x0D
    bytes[5] = 0x0A
    bytes[6] = 0x1A
    bytes[7] = 0x0A
}

internal fun routeSample(): AmapRoute = AmapRoute(
    mode = AmapRouteMode.DRIVING,
    origin = LatLng(120.1, 30.2),
    destination = LatLng(120.3, 30.4),
    distanceMeters = 12_345,
    durationSeconds = 1_500,
    steps = listOf(
        AmapRouteStep(
            instruction = "向北行驶",
            road = "文一西路",
            distanceMeters = 500,
            polyline = listOf(LatLng(120.1, 30.2), LatLng(120.11, 30.21)),
        ),
        AmapRouteStep(
            instruction = "右转进入学院路",
            road = "学院路",
            distanceMeters = 1_200,
            polyline = listOf(LatLng(120.11, 30.21), LatLng(120.3, 30.4)),
        ),
    ),
    transitLines = emptyList(),
)

internal fun transitRouteSample(): AmapRoute = AmapRoute(
    mode = AmapRouteMode.TRANSIT,
    origin = LatLng(120.1, 30.2),
    destination = LatLng(120.3, 30.4),
    distanceMeters = 8_600,
    durationSeconds = 2_400,
    steps = emptyList(),
    transitLines = listOf(
        AmapTransitLine(lineName = "地铁1号线(湘湖--萧山机场)", walkingMeters = 300),
        AmapTransitLine(lineName = "公交 12 路", walkingMeters = 200),
    ),
)

internal fun placeSample(
    name: String = "西湖风景名胜区",
    address: String? = "龙井路1号",
    typeName: String? = "风景名胜",
    distanceMeters: Int? = 850,
): AmapPlace = AmapPlace(
    name = name,
    address = address,
    typeName = typeName,
    distanceMeters = distanceMeters,
    location = LatLng(120.145, 30.256),
    cityName = "杭州市",
    tel = null,
)
