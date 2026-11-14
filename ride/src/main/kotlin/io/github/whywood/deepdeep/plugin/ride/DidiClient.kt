package io.github.whywood.deepdeep.plugin.ride

import io.github.whywood.deepdeep.plugin.api.PluginHost
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URLEncoder

internal class DidiException(message: String) : Exception(message)

internal class DidiClient(private val host: PluginHost) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private var counter = 1

    suspend fun call(tool: String, arguments: JsonObject): JsonElement {
        val key = host.settings().get(KEY_SETTING).orEmpty().trim()
        if (key.isEmpty()) throw DidiException(host.t("还没有配置滴滴 Key", "Didi key is not configured"))
        val environment = host.settings().get(ENV_SETTING).orEmpty().ifBlank { "sandbox" }
        val base = if (environment == "production") PRODUCTION else SANDBOX
        val url = "$base?key=${URLEncoder.encode(key, "UTF-8")}"
        val request = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", counter++)
            put("method", "tools/call")
            put("params", buildJsonObject {
                put("name", tool)
                put("arguments", arguments)
            })
        }
        val response = host.network().request(
            method = "POST",
            url = url,
            headers = mapOf(
                "Content-Type" to "application/json",
                "Accept" to "application/json, text/event-stream",
            ),
            body = request.toString(),
            timeoutMs = 30_000,
        )
        val body = response.body.trim()
        if (response.statusCode !in 200..299 || body.isEmpty()) {
            throw DidiException(host.t("滴滴服务返回 ${response.statusCode}", "Didi returned ${response.statusCode}"))
        }
        val envelope = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
            ?: throw DidiException(host.t("滴滴返回数据不完整", "Didi returned incomplete data"))
        val error = envelope["error"] as? JsonObject
        if (error != null) {
            throw DidiException((error["message"] as? JsonPrimitive)?.contentOrNull ?: host.t("滴滴调用失败", "Didi call failed"))
        }
        val result = envelope["result"] as? JsonObject
            ?: throw DidiException(host.t("滴滴返回数据不完整", "Didi returned incomplete data"))
        if ((result["isError"] as? JsonPrimitive)?.contentOrNull == "true") {
            val text = result.textContent()
            throw DidiException(text.ifBlank { host.t("滴滴调用失败", "Didi call failed") })
        }
        result["structuredContent"]?.let { return it }
        val text = result.textContent()
        if (text.isBlank()) return JsonObject(emptyMap())
        return runCatching { json.parseToJsonElement(text) }.getOrDefault(JsonObject(mapOf("text" to JsonPrimitive(text))))
    }

    private fun JsonObject.textContent(): String =
        (this["content"] as? JsonArray)?.firstOrNull()
            ?.let { it as? JsonObject }
            ?.get("text")
            ?.let { (it as? JsonPrimitive)?.contentOrNull }
            .orEmpty()

    companion object {
        private const val BASE = "https://mcp.didichuxing.com"
        private const val SANDBOX = "$BASE/mcp-servers-sandbox"
        private const val PRODUCTION = "$BASE/mcp-servers"
        const val KEY_SETTING = "didi_api_key"
        const val ENV_SETTING = "didi_environment"
    }
}
