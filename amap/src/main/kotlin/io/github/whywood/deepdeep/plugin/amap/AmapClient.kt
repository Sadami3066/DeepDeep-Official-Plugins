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

internal class AmapException(message: String, val raw: String? = null) : Exception(message)

internal class AmapSessionExpiredException : Exception()

internal data class AmapCall(val payload: JsonElement, val raw: String)

internal data class AmapMcpTool(val name: String, val inputs: Set<String>)

internal fun sanitizeAmapText(raw: String?, key: String?): String {
    val text = raw.orEmpty()
    if (key.isNullOrBlank()) return text
    val encoded = runCatching { URLEncoder.encode(key, "UTF-8") }.getOrDefault(key)
    return text.replace(key, "***").replace(encoded, "***")
}

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

    fun toolDefinitions(envelope: JsonObject): List<AmapMcpTool> =
        envelope["result"]?.asObjectOrNull()
            ?.arrayValue("tools")
            ?.mapNotNull { element ->
                val tool = element.asObjectOrNull() ?: return@mapNotNull null
                val name = tool.textValue("name") ?: return@mapNotNull null
                val schema = tool.objectValue("inputSchema", "input_schema")
                val inputs = schema?.objectValue("properties")?.keys.orEmpty()
                AmapMcpTool(name, inputs)
            }
            .orEmpty()

    fun tools(envelope: JsonObject): List<String> = toolDefinitions(envelope).map { it.name }

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
        if (tools.isEmpty() || tools.none { it.name in KNOWN_TOOLS }) {
            throw AmapException(
                host.t(
                    "高德服务没有返回可用的地图能力，请稍后重试",
                    "Amap did not return usable map tools. Try again later.",
                ),
            )
        }
        return tools.map { it.name }
    }

    suspend fun call(tool: String, arguments: JsonObject): AmapCall {
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

    suspend fun supportsArgument(tool: String, argument: String): Boolean = runCatching {
        val key = keyStore.currentKey() ?: return@runCatching false
        val current = ensureSession(key)
        val tools = availableTools(key, current)
        val name = resolveToolName(tools, tool)
        tools.firstOrNull { it.name == name }?.inputs?.contains(argument) == true
    }.getOrDefault(false)

    private suspend fun availableTools(key: String, session: McpSession): List<AmapMcpTool> {
        toolsCache?.takeIf { it.key == key }?.let { return it.tools }
        return sessionLock.withLock {
            toolsCache?.takeIf { it.key == key }?.tools
                ?: listTools(session).also { toolsCache = ToolsCache(key, it) }
        }
    }

    private suspend fun resolveTool(session: McpSession, tool: String): String {
        val tools = runCatching { availableTools(session.key, session) }.getOrDefault(emptyList())
        return resolveToolName(tools, tool)
    }

    private fun resolveToolName(tools: List<AmapMcpTool>, tool: String): String {
        val names = tools.map { it.name }
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
            ?: throw AmapException(failureMessage(AmapFailure.UNKNOWN, response.body, key), rawOf(response.body, key))
        envelope["error"]?.asObjectOrNull()?.let { error ->
            throw AmapException(toolFailureMessage(response.body, key), rawOf(response.body, key))
        }
        val initResult = envelope["result"]?.asObjectOrNull()
            ?: throw AmapException(failureMessage(AmapFailure.UNKNOWN, response.body, key), rawOf(response.body, key))
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

    private suspend fun listTools(session: McpSession): List<AmapMcpTool> {
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
            ?: throw AmapException(failureMessage(AmapFailure.UNKNOWN, response.body, session.key), rawOf(response.body, session.key))
        if (AmapMcpProtocol.isSessionExpired(envelope)) throw AmapSessionExpiredException()
        envelope["error"]?.asObjectOrNull()?.let {
            throw AmapException(toolFailureMessage(response.body, session.key), rawOf(response.body, session.key))
        }
        return AmapMcpProtocol.toolDefinitions(envelope)
    }

    private suspend fun executeCall(session: McpSession, tool: String, arguments: JsonObject): AmapCall {
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
        val raw = sanitizeAmapText(response.body, session.key)
        val envelope = AmapMcpProtocol.envelope(response.body)
            ?: throw AmapException(failureMessage(AmapFailure.UNKNOWN, response.body, session.key), raw)
        if (AmapMcpProtocol.isSessionExpired(envelope)) throw AmapSessionExpiredException()
        envelope["error"]?.asObjectOrNull()?.let {
            throw AmapException(toolFailureMessage(response.body, session.key), raw)
        }
        val result = envelope["result"]?.asObjectOrNull()
            ?: throw AmapException(failureMessage(AmapFailure.UNKNOWN, response.body, session.key), raw)
        val text = AmapMcpProtocol.contentText(result)
        if ((result["isError"] as? JsonPrimitive)?.booleanOrNull == true) {
            throw AmapException(toolFailureMessage(text.ifBlank { response.body }, session.key), raw)
        }
        val structured = result["structuredContent"]
        if (structured != null && structured !is JsonNull) return AmapCall(structured, raw)
        parseJsonText(text)?.let { return AmapCall(it, raw) }
        if (text.isBlank()) return AmapCall(JsonObject(emptyMap()), raw)
        return AmapCall(JsonObject(mapOf("text" to JsonPrimitive(text))), raw)
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
            throw AmapException(
                failureMessage(AmapFailure.INVALID_KEY, response.body, key),
                rawOf(response.body, key),
            )
        }
        if (response.statusCode == 404 && sessionId != null) throw AmapSessionExpiredException()
        if (response.statusCode !in 200..299) {
            val failure = classifyAmapFailure(response.statusCode, response.body)
            throw AmapException(
                failureMessage(if (failure == AmapFailure.UNKNOWN) AmapFailure.SERVICE else failure, response.body, key),
                rawOf(response.body, key),
            )
        }
        if (!expectBody) return HttpResult(response.headers, response.body)
        if (response.body.isBlank()) {
            throw AmapException(failureMessage(AmapFailure.UNKNOWN, response.body, key), rawOf(response.body, key))
        }
        restFailure(response.body)?.let { failure ->
            throw AmapException(failureMessage(failure, response.body, key), rawOf(response.body, key))
        }
        return HttpResult(response.headers, response.body)
    }

    private fun restFailure(body: String): AmapFailure? {
        val root = parseJsonText(body).asObjectOrNull() ?: return null
        if (root.textValue("status") != "0") return null
        return classifyAmapFailure(0, root.textValue("info", "infocode"))
    }

    private fun failureMessage(failure: AmapFailure, raw: String? = null, key: String? = null): String {
        val suggestion = when (failure) {
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
        return withRawDetail(suggestion, raw, key)
    }

    private fun toolFailureMessage(raw: String?, key: String?): String {
        val text = sanitizeAmapText(raw, key).trim()
        val suggestion = when (classifyAmapFailure(0, text)) {
            AmapFailure.INVALID_KEY -> failureMessage(AmapFailure.INVALID_KEY)
            AmapFailure.QUOTA -> failureMessage(AmapFailure.QUOTA)
            AmapFailure.SERVICE -> failureMessage(AmapFailure.SERVICE)
            else -> host.t(
                "高德没有返回可用结果，请稍后重试",
                "Amap did not return a usable result. Try again later.",
            )
        }
        return withRawDetail(suggestion, text, null)
    }

    private fun withRawDetail(suggestion: String, raw: String?, key: String?): String {
        val detail = sanitizeAmapText(raw, key).trim()
        return if (detail.isEmpty()) suggestion else "${suggestion}\n上游返回：$detail"
    }

    private fun rawOf(raw: String?, key: String): String? =
        sanitizeAmapText(raw, key).trim().takeIf { it.isNotEmpty() }

    private data class McpSession(val key: String, val id: String?, val protocolVersion: String)

    private data class ToolsCache(val key: String, val tools: List<AmapMcpTool>)

    private data class HttpResult(val headers: Map<String, String>, val body: String)

    private companion object {
        const val ENDPOINT = "https://mcp.amap.com/mcp"
        const val PROTOCOL_VERSION = "2025-03-26"
        const val CLIENT_NAME = "deepdeep-amap"
        const val CLIENT_VERSION = "1.3.0"
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
