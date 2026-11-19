package io.github.whywood.superpi.plugin.amap

import io.github.whywood.superpi.plugin.api.DownloadResult
import io.github.whywood.superpi.plugin.api.LocalizedText
import io.github.whywood.superpi.plugin.api.LogService
import io.github.whywood.superpi.plugin.api.NetworkResponse
import io.github.whywood.superpi.plugin.api.NetworkService
import io.github.whywood.superpi.plugin.api.PluginHost
import io.github.whywood.superpi.plugin.api.PluginLogLevel
import io.github.whywood.superpi.plugin.api.PluginRunSignal
import io.github.whywood.superpi.plugin.api.PluginServiceUnavailable
import io.github.whywood.superpi.plugin.api.PluginSettingsService
import io.github.whywood.superpi.plugin.api.PluginToolCall
import io.github.whywood.superpi.plugin.api.PluginToolResult
import io.github.whywood.superpi.plugin.api.SecretService
import java.io.File
import java.lang.reflect.Proxy
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal val zhLocalizer = AmapLocalizer { zh, _, args ->
    if (args.isEmpty()) zh else String.format(Locale.US, zh, *args.toTypedArray())
}

internal val knownMcpTools = setOf(
    "maps_text_search",
    "maps_around_search",
    "maps_geocode",
    "maps_search_detail",
    "maps_ip_location",
    "maps_regeocode",
    "maps_direction_driving",
    "maps_direction_transit_integrated",
    "maps_direction_walking",
    "maps_bicycling",
    "maps_weather",
)

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
    paths = listOf(
        AmapRoutePath(
            distanceMeters = 12_345,
            durationSeconds = 1_500,
            steps = listOf(
                AmapRouteStep(
                    instruction = "向北行驶",
                    road = "文一西路",
                    distanceMeters = 500,
                    durationSeconds = 60,
                    polyline = listOf(LatLng(120.1, 30.2), LatLng(120.11, 30.21)),
                ),
                AmapRouteStep(
                    instruction = "右转进入学院路",
                    road = "学院路",
                    distanceMeters = 1_200,
                    durationSeconds = 180,
                    polyline = listOf(LatLng(120.11, 30.21), LatLng(120.3, 30.4)),
                ),
            ),
        ),
    ),
)

internal fun transitRouteSample(): AmapRoute = AmapRoute(
    mode = AmapRouteMode.TRANSIT,
    origin = LatLng(120.1, 30.2),
    destination = LatLng(120.3, 30.4),
    paths = listOf(
        AmapRoutePath(
            distanceMeters = 8_600,
            durationSeconds = 2_400,
            walkingDistanceMeters = 600,
            transitLines = listOf(
                AmapTransitLine(
                    lineName = "地铁1号线(湘湖--萧山机场)",
                    walkingMeters = 300,
                    walkingSeconds = 240,
                    departureStop = "龙翔桥",
                    arrivalStop = "武林广场",
                    entrance = "龙翔桥站B口",
                    exit = "武林广场站A口",
                    viaStops = listOf("龙翔桥", "凤起路", "武林广场"),
                    viaCount = 3,
                    buslineNames = listOf("地铁1号线(湘湖--萧山机场)"),
                    lineDistanceMeters = 8_300,
                    lineDurationSeconds = 2_100,
                    startTime = "06:00",
                    endTime = "22:30",
                    options = listOf(
                        AmapTransitOption(
                            name = "地铁1号线(湘湖--萧山机场)",
                            departureStop = "龙翔桥",
                            arrivalStop = "武林广场",
                            viaStops = listOf("龙翔桥", "凤起路", "武林广场"),
                            viaCount = 3,
                            distanceMeters = 8_300,
                            durationSeconds = 2_100,
                            startTime = "06:00",
                            endTime = "22:30",
                        ),
                        AmapTransitOption(
                            name = "公交 12 路",
                            departureStop = "龙翔桥",
                            arrivalStop = "武林广场",
                            viaCount = 5,
                        ),
                    ),
                ),
                AmapTransitLine(lineName = "公交 12 路", walkingMeters = 200),
            ),
        ),
    ),
)

internal fun placeSample(
    id: String? = "B000A8UIN8",
    name: String = "西湖风景名胜区",
    address: String? = "龙井路1号",
    typecode: String? = "110000",
    typeName: String? = "风景名胜",
    location: LatLng? = LatLng(120.145, 30.256),
    photos: List<String> = emptyList(),
): AmapPlace = AmapPlace(
    id = id,
    name = name,
    address = address,
    typecode = typecode,
    typeName = typeName,
    location = location,
    photos = photos,
)

internal class FakeSettings(initial: Map<String, String> = emptyMap()) : PluginSettingsService {
    var values = initial.toMap()
        private set

    override suspend fun get(key: String): String? = values[key]

    override suspend fun put(key: String, value: String) {
        values = values + (key to value)
    }

    override suspend fun remove(key: String) {
        values = values - key
    }

    override suspend fun clear() {
        values = emptyMap()
    }

    override suspend fun keys(): Set<String> = values.keys

    override suspend fun snapshot(): Map<String, String> = values

    override suspend fun getJson(key: String): JsonObject? = null

    override suspend fun putJson(key: String, value: JsonObject) = Unit

    override fun observe(key: String?): Flow<Map<String, String>> =
        if (key == null) MutableStateFlow(values) else MutableStateFlow(values).map { map -> map.filterKeys { it == key } }
}

internal class FakeSecrets(initial: Map<String, String> = emptyMap()) : SecretService {
    var values = initial.toMutableMap()
        private set

    override suspend fun get(key: String): String? = values[key]

    override suspend fun put(key: String, value: String) {
        values[key] = value
    }

    override suspend fun remove(key: String) {
        values.remove(key)
    }

    override suspend fun clear() {
        values.clear()
    }
}

internal class FakeAmapHost(
    val network: NetworkService = FakeNetworkService(),
    val settings: FakeSettings = FakeSettings(),
    val secrets: FakeSecrets = FakeSecrets(),
    val dataDir: File = File("build/amap-test-host"),
) {
    val logMessages = mutableListOf<String>()

    val pluginHost: PluginHost = Proxy.newProxyInstance(
        PluginHost::class.java.classLoader,
        arrayOf(PluginHost::class.java),
    ) { proxy, method, args ->
        when (method.name) {
            "text" -> {
                val source = args?.getOrNull(0) as? LocalizedText
                val values = (args?.getOrNull(1) as? Array<*>)?.filterNotNull().orEmpty()
                val template = source?.resolve("zh") ?: source?.resolve().orEmpty()
                if (values.isEmpty()) template else String.format(Locale.US, template, *values.toTypedArray())
            }
            "settings" -> settings
            "secrets" -> secrets
            "network" -> network
            "logs" -> object : LogService {
                override fun log(level: PluginLogLevel, message: String, error: Throwable?) {
                    logMessages += message
                }
            }
            "dataDir" -> dataDir
            "scope" -> CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            "manifest", "context", "assetsDir" -> throw PluginServiceUnavailable(method.name)
            "toString" -> "FakeAmapHost"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.getOrNull(0)
            else -> throw PluginServiceUnavailable(method.name)
        }
    } as PluginHost

    fun host(): PluginHost = pluginHost
}

internal class FakeMcpNetwork(
    private val payload: (String) -> String = { """{"pois":[]}""" },
    private val tools: Set<String> = knownMcpTools,
    private val failure: NetworkResponse? = null,
    private val includeStructuredContent: Boolean = false,
) : NetworkService {
    val toolCalls = mutableListOf<Pair<String, JsonObject>>()

    override suspend fun request(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String?,
        timeoutMs: Long,
    ): NetworkResponse {
        val request = AmapJson.parseToJsonElement(body.orEmpty()) as JsonObject
        return when (request.textValue("method")) {
            "initialize" -> NetworkResponse(
                statusCode = 200,
                body = """{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-03-26"}}""",
                headers = mapOf("mcp-session-id" to "test-session"),
            )
            "notifications/initialized" -> NetworkResponse(statusCode = 202, body = "")
            "tools/list" -> NetworkResponse(statusCode = 200, body = toolsEnvelope())
            "tools/call" -> {
                val params = request.objectValue("params")
                val name = params?.textValue("name").orEmpty()
                toolCalls += name to (params?.objectValue("arguments") ?: JsonObject(emptyMap()))
                failure ?: NetworkResponse(
                    statusCode = 200,
                    body = callEnvelope(payload(name), includeStructuredContent),
                )
            }
            else -> NetworkResponse(statusCode = 400, body = """{"error":"unsupported"}""")
        }
    }

    override suspend fun download(
        url: String,
        targetPath: String,
        expectedSha256: String?,
        onProgress: ((Int?) -> Unit)?,
    ): DownloadResult = throw UnsupportedOperationException("download is not used by the MCP client")

    private fun toolsEnvelope(): String = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", 2)
        put("result", buildJsonObject {
            put("tools", buildJsonArray {
                tools.forEach { name ->
                    add(buildJsonObject { put("name", name) })
                }
            })
        })
    }.toString()

    private fun callEnvelope(payload: String, includeStructuredContent: Boolean): String = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", 3)
        put("result", buildJsonObject {
            put("content", buildJsonArray {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", payload)
                })
            })
            if (includeStructuredContent) {
                put("structuredContent", AmapJson.parseToJsonElement(payload))
            }
        })
    }.toString()
}

internal class FakeToolCall(
    override val args: JsonObject,
    override val sessionId: String = "session-1",
) : PluginToolCall {
    override val callId: String = "call-1"
    override val signal: PluginRunSignal? = null

    override fun partial(result: PluginToolResult) = Unit
}
