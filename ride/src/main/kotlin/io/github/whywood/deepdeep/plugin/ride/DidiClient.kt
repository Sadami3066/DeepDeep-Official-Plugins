package io.github.whywood.superpi.plugin.ride

import io.github.whywood.superpi.plugin.api.PluginHost
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicInteger

internal class DidiException(
    message: String,
    val code: Int? = null,
    val raw: String? = null,
) : Exception(message)

internal data class RideResponse(
    val payload: JsonElement,
    val raw: String,
)

internal enum class RideEnvironment(val wire: String) {
    PRODUCTION("production"),
    SANDBOX("sandbox"),
    ;

    companion object {
        fun fromWire(value: String?): RideEnvironment =
            entries.firstOrNull { it.wire == value } ?: PRODUCTION
    }
}

internal class DidiClient(private val host: PluginHost) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val counter = AtomicInteger(1)
    private val keyStore = RideKeyStore(host.settings(), host.secrets())

    suspend fun hasKey(): Boolean = keyStore.hasKey()

    suspend fun migrateLegacyKey() {
        keyStore.migrateLegacyKey()
    }

    suspend fun saveKey(key: String) {
        keyStore.save(key)
    }

    suspend fun environment(): RideEnvironment =
        RideEnvironment.fromWire(host.settings().get(ENV_SETTING))

    suspend fun saveEnvironment(environment: RideEnvironment) {
        host.settings().put(ENV_SETTING, environment.wire)
    }

    suspend fun probe(candidateKey: String? = null) {
        val key = candidateKey?.trim().takeUnless { it.isNullOrEmpty() } ?: keyStore.currentKey()
        if (key.isNullOrBlank()) throw DidiException(host.t("请先填写滴滴 Key", "Enter your Didi key first"))
        val body = post(
            method = "tools/list",
            params = null,
            key = key,
            timeoutMs = PROBE_TIMEOUT_MS,
        )
        if (!parseDidiToolsList(body)) {
            throw DidiException(host.t("连接失败，请检查 Key 是否正确", "Connection failed. Check the key and try again."))
        }
    }

    suspend fun call(tool: String, arguments: JsonObject): RideResponse {
        val key = keyStore.currentKey()
        if (key.isNullOrBlank()) throw DidiException(host.t("还没有配置滴滴 Key", "Didi key is not configured"))
        val body = post(
            method = "tools/call",
            params = buildJsonObject {
                put("name", tool)
                put("arguments", arguments)
            },
            key = key,
            timeoutMs = CALL_TIMEOUT_MS,
        )
        val raw = redactDidiError(body, key)
        val envelope = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
            ?: throw DidiException(host.t("滴滴返回数据不完整", "Didi returned incomplete data"), raw = raw)
        val error = envelope["error"] as? JsonObject
        if (error != null) {
            val message = (error["message"] as? JsonPrimitive)?.contentOrNull
            val code = (error["code"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
            throw DidiException(
                summarizeDidiError(message, secret = key).ifBlank {
                    host.t("滴滴调用失败", "Didi call failed")
                },
                code ?: parseDidiErrorCode(message),
                raw = didiRaw(envelope, raw),
            )
        }
        val result = envelope["result"] as? JsonObject
            ?: throw DidiException(host.t("滴滴返回数据不完整", "Didi returned incomplete data"), raw = didiRaw(envelope, raw))
        val resultRaw = didiRaw(envelope, raw)
        if ((result["isError"] as? JsonPrimitive)?.booleanOrNull == true) {
            val text = result.textContent()
            throw DidiException(
                summarizeDidiError(text, secret = key).ifBlank {
                    host.t("滴滴调用失败", "Didi call failed")
                },
                parseDidiErrorCode(text),
                raw = resultRaw,
            )
        }
        val payload = result["structuredContent"] ?: run {
            val text = result.textContent()
            if (text.isBlank()) JsonObject(emptyMap())
            else parseDidiPayload(text) ?: JsonObject(mapOf("text" to JsonPrimitive(text)))
        }
        return RideResponse(payload = payload, raw = resultRaw)
    }

    private suspend fun post(
        method: String,
        params: JsonObject?,
        key: String,
        timeoutMs: Long,
    ): String {
        val environment = environment()
        val base = if (environment == RideEnvironment.SANDBOX) SANDBOX else PRODUCTION
        val url = "$base?key=${URLEncoder.encode(key, "UTF-8")}"
        val request = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", counter.getAndIncrement())
            put("method", method)
            if (params != null) put("params", params)
        }
        val response = runCatching {
            host.network().request(
                method = "POST",
                url = url,
                headers = mapOf(
                    "Content-Type" to "application/json",
                    "Accept" to "application/json, text/event-stream",
                ),
                body = request.toString(),
                timeoutMs = timeoutMs,
            )
        }.getOrElse {
            throw DidiException(host.t("网络连接失败，请稍后重试", "Network unavailable. Try again."))
        }
        val body = response.body.trim()
        if (response.statusCode !in 200..299 || body.isEmpty()) {
            throw DidiException(
                when (response.statusCode) {
                    401, 403 -> host.t("Key 无效或没有权限", "The key is invalid or unauthorized")
                    else -> host.t(
                        "滴滴服务暂时不可用（${response.statusCode}）",
                        "Didi is unavailable (${response.statusCode})",
                    )
                },
                raw = redactDidiError(body, key).takeIf { it.isNotEmpty() },
            )
        }
        return body
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
        private const val PROBE_TIMEOUT_MS = 15_000L
        private const val CALL_TIMEOUT_MS = 30_000L
        const val ENV_SETTING = "didi_environment"
    }
}
