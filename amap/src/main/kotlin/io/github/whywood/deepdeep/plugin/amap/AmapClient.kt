package io.github.whywood.deepdeep.plugin.amap

import io.github.whywood.deepdeep.plugin.api.PluginHost
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

internal class AmapException(message: String) : Exception(message)

internal class AmapSessionExpiredException : Exception()

internal object AmapMcpProtocol {
    fun envelope(body: String?): JsonObject? {
        val raw = body?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val candidates = mutableListOf<String>()
        if (raw.startsWith("{")) candidates += raw
        if (raw.contains("data:")) {
            val event = StringBuilder()
            raw.lineSequence().forEach { line ->
                val trimmed = line.trim()
                if (trimmed.startsWith("data:")) {
                    if (event.isNotEmpty()) event.append('\n')
                    event.append(trimmed.removePrefix("data:").trim())
                } else if (trimmed.isEmpty() && event.isNotEmpty()) {
                    candidates += event.toString()
                    event.clear()
                }
            }
            if (event.isNotEmpty()) candidates += event.toString()
        }
        for (candidate in candidates) {
            val element = runCatching { AmapJson.parseToJsonElement(candidate) }.getOrNull() as? JsonObject ?: continue
            if (element.containsKey("result") || element.containsKey("error") || element.containsKey("jsonrpc")) return element
        }
        return null
    }

    fun headerValue(headers: Map<String, String>, name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.trim()?.takeIf { it.isNotEmpty() }

    fun sessionId(headers: Map<String, String>): String? = headerValue(headers, "mcp-session-id")

    fun isSessionExpired(envelope: JsonObject): Boolean {
        val error = envelope["error"] as? JsonObject ?: return false
        val code = (error["code"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
        val message = error.textValue("message").orEmpty()
        return code == -32001 || message.contains("session", ignoreCase = true)
    }

    fun tools(envelope: JsonObject): List<String> =
        envelope["result"]?.asObjectOrNull()
            ?.arrayValue("tools")
            ?.mapNotNull { it.asObjectOrNull()?.textValue("name") }
            .orEmpty()

    fun contentText(result: JsonObject): String =
        (result["content"] as? JsonArray)?.joinToString("\n") { part ->
            (part as? JsonObject)?.textValue("text").orEmpty()
        }.orEmpty()
}

internal class AmapClient(private val host: PluginHost) {
    private val counter = AtomicInteger(1)
    private val keyStore = AmapKeyStore(host.settings(), host.secrets())
    private val sessionLock = Mutex()

    @Volatile
    private var session: McpSession? = null

    @Volatile
    private var toolsCache: ToolsCache? = null

    suspend fun hasKey(): Boolean = keyStore.hasKey()

    suspend fun currentKey(): String? = keyStore.currentKey()

    suspend fun migrateLegacyKey() {
        keyStore.migrateLegacyKey()
    }

    suspend fun saveKey(key: String) {
        keyStore.save(key)
        session = null
        toolsCache = null
    }

    suspend fun clearKey() {
        keyStore.clear()
        session = null
        toolsCache = null
    }

    suspend fun probe(candidateKey: String? = null): List<String> {
        val key = candidateKey?.trim().takeUnless { it.isNullOrEmpty() } ?: keyStore.currentKey()
        if (key.isNullOrBlank()) {
            throw AmapException(host.t("请先填写高德 Key", "Enter your Amap key first"))
        }
        val opened = openSession(key)
        val tools = listTools(opened)
        if (tools.isEmpty() || tools.none { it in KNOWN_TOOLS }) {
            throw AmapException(
                host.t(
                    "高德服务没有返回可用的地图能力，请稍后重试",
                    "Amap did not return usable map tools. Try again later.",
                ),
            )
        }
        return tools
    }

    suspend fun call(tool: String, arguments: JsonObject): JsonElement {
        val key = keyStore.currentKey() ?: throw AmapException(
            host.t(
                "还没有配置高德 Key，请先在插件页填写并验证",
                "Amap key is not configured. Add and verify it on the plugin page first.",
            ),
        )
        var current = ensureSession(key)
        return try {
            executeCall(current, resolveTool(current, tool), arguments)
        } catch (expired: AmapSessionExpiredException) {
            current = reinitialize(key)
            executeCall(current, resolveTool(current, tool), arguments)
        }
    }

    private suspend fun availableTools(key: String, session: McpSession): List<String> {
        toolsCache?.takeIf { it.key == key }?.let { return it.names }
        return sessionLock.withLock {
            toolsCache?.takeIf { it.key == key }?.names
                ?: listTools(session).also { toolsCache = ToolsCache(key, it) }
        }
    }

    private suspend fun resolveTool(session: McpSession, tool: String): String {
        val names = runCatching { availableTools(session.key, session) }.getOrDefault(emptyList())
        if (names.isEmpty() || tool in names) return tool
        return TOOL_ALIASES[tool]?.firstOrNull { it in names } ?: tool
    }

    private suspend fun ensureSession(key: String): McpSession {
        session?.takeIf { it.key == key }?.let { return it }
        return sessionLock.withLock {
            session?.takeIf { it.key == key } ?: openSession(key).also { session = it }
        }
    }

    private suspend fun reinitialize(key: String): McpSession = sessionLock.withLock {
        openSession(key).also { session = it }
    }

    private suspend fun openSession(key: String): McpSession {
        val id = counter.getAndIncrement()
        val response = requestJson(
            key = key,
            id = id,
            method = "initialize",
            params = buildJsonObject {
                put("protocolVersion", PROTOCOL_VERSION)
                put("capabilities", JsonObject(emptyMap()))
                put("clientInfo", buildJsonObject {
                    put("name", CLIENT_NAME)
                    put("version", CLIENT_VERSION)
                })
            },
            sessionId = null,
            timeoutMs = INIT_TIMEOUT_MS,
        )
        val envelope = AmapMcpProtocol.envelope(response.body)
            ?: throw AmapException(failureMessage(AmapFailure.UNKNOWN))
        envelope["error"]?.asObjectOrNull()?.let { error ->
            throw AmapException(toolFailureMessage(error.textValue("message"), key))
        }
        val initResult = envelope["result"]?.asObjectOrNull()
            ?: throw AmapException(failureMessage(AmapFailure.UNKNOWN))
        val protocolVersion = initResult.textValue("protocolVersion") ?: PROTOCOL_VERSION
        val opened = McpSession(key, AmapMcpProtocol.sessionId(response.headers), protocolVersion)
        runCatching {
            requestJson(
                key = key,
                id = null,
                method = "notifications/initialized",
                params = null,
                sessionId = opened.id,
                protocolVersion = opened.protocolVersion,
                timeoutMs = INIT_TIMEOUT_MS,
                expectBody = false,
            )
        }
        return opened
    }

    private suspend fun listTools(session: McpSession): List<String> {
        val response = requestJson(
            key = session.key,
            id = counter.getAndIncrement(),
            method = "tools/list",
            params = null,
            sessionId = session.id,
            protocolVersion = session.protocolVersion,
            timeoutMs = LIST_TIMEOUT_MS,
        )
        val envelope = AmapMcpProtocol.envelope(response.body)
            ?: throw AmapException(failureMessage(AmapFailure.UNKNOWN))
        if (AmapMcpProtocol.isSessionExpired(envelope)) throw AmapSessionExpiredException()
        envelope["error"]?.asObjectOrNull()?.let { error ->
            throw AmapException(toolFailureMessage(error.textValue("message"), session.key))
        }
        return AmapMcpProtocol.tools(envelope)
    }

    private suspend fun executeCall(session: McpSession, tool: String, arguments: JsonObject): JsonElement {
        val response = requestJson(
            key = session.key,
            id = counter.getAndIncrement(),
            method = "tools/call",
            params = buildJsonObject {
                put("name", tool)
                put("arguments", arguments)
            },
            sessionId = session.id,
            protocolVersion = session.protocolVersion,
            timeoutMs = CALL_TIMEOUT_MS,
        )
        val envelope = AmapMcpProtocol.envelope(response.body)
            ?: throw AmapException(failureMessage(AmapFailure.UNKNOWN))
        if (AmapMcpProtocol.isSessionExpired(envelope)) throw AmapSessionExpiredException()
        envelope["error"]?.asObjectOrNull()?.let { error ->
            throw AmapException(toolFailureMessage(error.textValue("message"), session.key))
        }
        val result = envelope["result"]?.asObjectOrNull()
            ?: throw AmapException(failureMessage(AmapFailure.UNKNOWN))
        val structured = result["structuredContent"]
        if (structured != null && structured !is JsonNull) return structured
        val text = AmapMcpProtocol.contentText(result)
        if ((result["isError"] as? JsonPrimitive)?.booleanOrNull == true) {
            throw AmapException(toolFailureMessage(text, session.key))
        }
        parseJsonText(text)?.let { return it }
        if (text.isBlank()) return JsonObject(emptyMap())
        return JsonObject(mapOf("text" to JsonPrimitive(text)))
    }

    private suspend fun requestJson(
        key: String,
        id: Int?,
        method: String,
        params: JsonObject?,
        sessionId: String?,
        protocolVersion: String? = null,
        timeoutMs: Long,
        expectBody: Boolean = true,
    ): HttpResult {
        val body = buildJsonObject {
            put("jsonrpc", "2.0")
            if (id != null) put("id", id)
            put("method", method)
            if (params != null) put("params", params)
        }
        val headers = buildMap {
            put("Content-Type", "application/json")
            put("Accept", "application/json, text/event-stream")
            if (sessionId != null) {
                put("Mcp-Session-Id", sessionId)
                put("MCP-Protocol-Version", protocolVersion ?: PROTOCOL_VERSION)
            }
        }
        val url = "$ENDPOINT?key=${URLEncoder.encode(key, "UTF-8")}"
        val response = try {
            host.network().request(
                method = "POST",
                url = url,
                headers = headers,
                body = body.toString(),
                timeoutMs = timeoutMs,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            throw AmapException(failureMessage(AmapFailure.NETWORK))
        }
        if (response.statusCode == 401 || response.statusCode == 403) {
            throw AmapException(failureMessage(AmapFailure.INVALID_KEY))
        }
        if (response.statusCode == 404 && sessionId != null) throw AmapSessionExpiredException()
        if (response.statusCode !in 200..299) {
            val failure = classifyAmapFailure(response.statusCode, response.body)
            throw AmapException(failureMessage(if (failure == AmapFailure.UNKNOWN) AmapFailure.SERVICE else failure))
        }
        if (!expectBody) return HttpResult(response.headers, response.body)
        if (response.body.isBlank()) throw AmapException(failureMessage(AmapFailure.UNKNOWN))
        restFailure(response.body)?.let { failure -> throw AmapException(failureMessage(failure)) }
        return HttpResult(response.headers, response.body)
    }

    private fun restFailure(body: String): AmapFailure? {
        val root = parseJsonText(body).asObjectOrNull() ?: return null
        if (root.textValue("status") != "0") return null
        return classifyAmapFailure(0, root.textValue("info", "infocode"))
    }

    private fun failureMessage(failure: AmapFailure): String = when (failure) {
        AmapFailure.INVALID_KEY -> host.t(
            "高德 Key 无效或没有权限，请检查后重试",
            "The Amap key is invalid or unauthorized. Check it and try again.",
        )
        AmapFailure.QUOTA -> host.t(
            "高德调用太频繁或今日额度已用完，请稍后再试",
            "Amap is receiving too many requests or today's quota is used up. Try again later.",
        )
        AmapFailure.SERVICE -> host.t(
            "高德服务暂时不可用，请稍后重试",
            "Amap is temporarily unavailable. Try again later.",
        )
        AmapFailure.NETWORK -> host.t(
            "网络连接失败，请检查网络后重试",
            "Network unavailable. Check your connection and try again.",
        )
        AmapFailure.UNKNOWN -> host.t(
            "高德返回了无法识别的结果，请稍后重试",
            "Amap returned an unexpected result. Try again later.",
        )
    }

    private fun toolFailureMessage(raw: String?, key: String?): String {
        val text = sanitize(raw, key)
        return when (classifyAmapFailure(0, text)) {
            AmapFailure.INVALID_KEY -> failureMessage(AmapFailure.INVALID_KEY)
            AmapFailure.QUOTA -> failureMessage(AmapFailure.QUOTA)
            AmapFailure.SERVICE -> failureMessage(AmapFailure.SERVICE)
            else -> host.t(
                "高德没有返回可用结果，请稍后重试",
                "Amap did not return a usable result. Try again later.",
            )
        }
    }

    private fun sanitize(raw: String?, key: String?): String {
        val text = raw.orEmpty().take(500)
        return if (key.isNullOrBlank()) text else text.replace(key, "***")
    }

    private data class McpSession(val key: String, val id: String?, val protocolVersion: String)

    private data class ToolsCache(val key: String, val names: List<String>)

    private data class HttpResult(val headers: Map<String, String>, val body: String)

    private companion object {
        const val ENDPOINT = "https://mcp.amap.com/mcp"
        const val PROTOCOL_VERSION = "2025-03-26"
        const val CLIENT_NAME = "deepdeep-amap"
        const val CLIENT_VERSION = "1.0.0"
        const val INIT_TIMEOUT_MS = 20_000L
        const val LIST_TIMEOUT_MS = 20_000L
        const val CALL_TIMEOUT_MS = 30_000L

        val TOOL_ALIASES = mapOf(
            "maps_text_search" to listOf("maps_search_text"),
            "maps_around_search" to listOf("maps_search_around"),
            "maps_regeocode" to listOf("maps_geocode_regeo", "maps_regeo"),
            "maps_direction_driving" to listOf("maps_driving"),
            "maps_direction_transit_integrated" to listOf("maps_transit_integrated"),
            "maps_direction_walking" to listOf("maps_walking"),
            "maps_bicycling" to listOf("maps_direction_bicycling", "maps_cycling"),
            "maps_weather" to listOf("maps_weather_forecast"),
        )

        val KNOWN_TOOLS = setOf(
            "maps_text_search",
            "maps_around_search",
            "maps_regeocode",
            "maps_direction_driving",
            "maps_direction_transit_integrated",
            "maps_direction_walking",
            "maps_bicycling",
            "maps_weather",
        )
    }
}
