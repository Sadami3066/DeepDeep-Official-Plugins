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

internal class AmapException(
    val failure: AmapFailure,
    val raw: String? = null,
    message: String,
) : Exception(message)

internal class AmapSessionExpiredException : Exception()

internal data class AmapCall(val payload: JsonElement, val raw: String)

internal data class AmapMcpTool(val name: String)

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
                AmapMcpTool(name)
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
    private val callLock = Mutex()

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
            throw AmapException(
                AmapFailure.INVALID_KEY,
                null,
                host.t("请先填写高德 Key", "Enter your Amap key first"),
            )
        }
        val opened = openSession(key)
        val tools = listTools(opened)
        if (tools.isEmpty() || tools.none { it.name in KNOWN_TOOLS }) {
            throw AmapException(
                AmapFailure.PROTOCOL,
                null,
                host.t(
                    "高德服务没有返回可用的地图能力，请稍后重试",
                    "Amap did not return usable map tools. Try again later.",
                ),
            )
        }
        return tools.map { it.name }
    }

    suspend fun call(tool: String, arguments: JsonObject): AmapCall = callLock.withLock {
        val key = keyStore.currentKey() ?: throw AmapException(
            AmapFailure.INVALID_KEY,
            null,
            host.t(
                "还没有配置高德 Key，请先在插件页填写并验证",
                "Amap key is not configured. Add and verify it on the plugin page first.",
            ),
        )
        var current = ensureSession(key)
        try {
            executeCall(current, resolveTool(current, tool), arguments)
        } catch (expired: AmapSessionExpiredException) {
            current = reinitialize(key)
            executeCall(current, resolveTool(current, tool), arguments)
        }
    }

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
            ?: throw AmapException(
                AmapFailure.UNKNOWN,
                rawOf(response.body, key),
                failureMessage(AmapFailure.UNKNOWN),
            )
        envelope["error"]?.asObjectOrNull()?.let { error ->
            val failure = failureFromJsonRpc(error)
            throw AmapException(failure, rawOf(response.body, key), failureMessage(failure))
        }
        val initResult = envelope["result"]?.asObjectOrNull()
            ?: throw AmapException(
                AmapFailure.PROTOCOL,
                rawOf(response.body, key),
                failureMessage(AmapFailure.PROTOCOL),
            )
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
            ?: throw AmapException(
                AmapFailure.UNKNOWN,
                rawOf(response.body, session.key),
                failureMessage(AmapFailure.UNKNOWN),
            )
        if (AmapMcpProtocol.isSessionExpired(envelope)) throw AmapSessionExpiredException()
        envelope["error"]?.asObjectOrNull()?.let { error ->
            val failure = failureFromJsonRpc(error)
            throw AmapException(failure, rawOf(response.body, session.key), failureMessage(failure))
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
        val envelope = AmapMcpProtocol.envelope(response.body)
            ?: throw AmapException(
                AmapFailure.UNKNOWN,
                rawOf(response.body, session.key),
                failureMessage(AmapFailure.UNKNOWN),
            )
        if (AmapMcpProtocol.isSessionExpired(envelope)) throw AmapSessionExpiredException()
        envelope["error"]?.asObjectOrNull()?.let { error ->
            val failure = failureFromJsonRpc(error)
            throw AmapException(failure, rawOf(response.body, session.key), failureMessage(failure))
        }
        val result = envelope["result"]?.asObjectOrNull()
            ?: throw AmapException(
                AmapFailure.PROTOCOL,
                rawOf(response.body, session.key),
                failureMessage(AmapFailure.PROTOCOL),
            )
        val text = AmapMcpProtocol.contentText(result)
        val structured = result["structuredContent"]?.takeIf { it !is JsonNull }
        val raw = contentRaw(text, structured, session.key)
        if ((result["isError"] as? JsonPrimitive)?.booleanOrNull == true) {
            val failure = classifyAmapFailure(0, text.ifBlank { raw })
            throw AmapException(failure, raw, failureMessage(failure))
        }
        val payload = (structured as? JsonObject)
            ?: parseJsonText(text)
            ?: structured
            ?: JsonObject(emptyMap())
        payload.asObjectOrNull()?.let { root ->
            failureFromPayload(root)?.let { failure ->
                throw AmapException(failure, raw, failureMessage(failure))
            }
        }
        return AmapCall(payload, raw)
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
        } catch (error: Exception) {
            val detail = "${error::class.java.simpleName}: ${error.message.orEmpty()}".trim()
            throw AmapException(
                AmapFailure.NETWORK,
                rawOf(detail, key),
                failureMessage(AmapFailure.NETWORK),
            )
        }
        if (response.statusCode == 401 || response.statusCode == 403) {
            throw AmapException(
                AmapFailure.INVALID_KEY,
                rawOf(response.body, key) ?: "HTTP ${response.statusCode}",
                failureMessage(AmapFailure.INVALID_KEY),
            )
        }
        if (response.statusCode == 404 && sessionId != null) throw AmapSessionExpiredException()
        if (response.statusCode !in 200..299) {
            val classified = classifyAmapFailure(response.statusCode, response.body)
            val failure = if (classified == AmapFailure.UNKNOWN) AmapFailure.SERVICE else classified
            throw AmapException(
                failure,
                rawOf(response.body, key) ?: "HTTP ${response.statusCode}",
                failureMessage(failure),
            )
        }
        if (!expectBody) return HttpResult(response.headers, response.body)
        if (response.body.isBlank()) {
            throw AmapException(
                AmapFailure.PROTOCOL,
                null,
                failureMessage(AmapFailure.PROTOCOL),
            )
        }
        restFailure(response.body)?.let { failure ->
            throw AmapException(failure, rawOf(response.body, key), failureMessage(failure))
        }
        return HttpResult(response.headers, response.body)
    }

    private fun restFailure(body: String): AmapFailure? {
        val root = parseJsonText(body).asObjectOrNull() ?: return null
        if (root.textValue("status") != "0") return null
        return classifyAmapFailure(0, root.textValue("info", "infocode")).takeIf {
            it != AmapFailure.UNKNOWN
        }
    }

    private fun failureFromJsonRpc(error: JsonObject): AmapFailure {
        val code = (error["code"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
        val message = error.textValue("message").orEmpty()
        return when (code) {
            -32700, -32600, -32601 -> AmapFailure.PROTOCOL
            -32602 -> AmapFailure.PARAMETER
            -32603 -> AmapFailure.SERVICE
            else -> classifyAmapFailure(0, message).takeIf { it != AmapFailure.UNKNOWN }
                ?: AmapFailure.PROTOCOL
        }
    }

    private fun failureFromPayload(root: JsonObject): AmapFailure? {
        val status = root.textValue("status") ?: return null
        if (status == "1" || status.equals("ok", ignoreCase = true)) return null
        return classifyAmapFailure(0, root.textValue("info", "infocode", "message", "errmsg", "error"))
            .takeIf { it != AmapFailure.UNKNOWN }
            ?: AmapFailure.SERVICE
    }

    private fun contentRaw(text: String, structured: JsonElement?, key: String): String {
        val content = text.trim()
        val raw = when {
            content.isNotEmpty() && structured != null && structured !is JsonNull ->
                "$content\n\nstructuredContent:\n$structured"
            content.isNotEmpty() -> content
            structured != null && structured !is JsonNull -> structured.toString()
            else -> ""
        }
        return sanitizeAmapText(raw, key).trim()
    }

    private fun failureMessage(failure: AmapFailure): String = when (failure) {
        AmapFailure.INVALID_KEY -> host.t(
            "高德 Key 无效、过期或没有开通这项服务，请到插件页重新验证",
            "The Amap key is invalid, expired, or not enabled for this service. Verify it on the plugin page.",
        )
        AmapFailure.QUOTA -> host.t(
            "高德调用太频繁或今日额度已用完，请稍后再试",
            "Amap is receiving too many requests or today's quota is used up. Try again later.",
        )
        AmapFailure.PARAMETER -> host.t(
            "高德拒绝了这次请求参数，请按上游提示修正后重试",
            "Amap rejected the request parameters. Fix them as the upstream message says and retry.",
        )
        AmapFailure.SERVICE -> host.t(
            "高德服务暂时不可用，请稍后重试",
            "Amap is temporarily unavailable. Try again later.",
        )
        AmapFailure.NETWORK -> host.t(
            "请求没有到达高德，请检查网络后重试",
            "The request did not reach Amap. Check your connection and try again.",
        )
        AmapFailure.PROTOCOL -> host.t(
            "高德返回的协议响应无法识别，请稍后重试",
            "Amap returned an unrecognized protocol response. Try again later.",
        )
        AmapFailure.INTERNAL -> host.t(
            "插件处理高德返回时出错，请稍后重试",
            "The plugin failed while processing the Amap response. Try again later.",
        )
        AmapFailure.UNKNOWN -> host.t(
            "高德返回了无法识别的结果，请稍后重试",
            "Amap returned an unexpected result. Try again later.",
        )
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
        const val CLIENT_VERSION = "1.4.0"
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
