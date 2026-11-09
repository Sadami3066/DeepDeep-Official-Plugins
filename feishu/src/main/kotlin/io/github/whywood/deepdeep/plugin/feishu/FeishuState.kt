package io.github.whywood.deepdeep.plugin.feishu

import io.github.whywood.deepdeep.plugin.api.PluginHost
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

internal data class FeishuSnapshot(
    val installedVersion: String? = null,
    val appId: String? = null,
    val appConfigured: Boolean = false,
    val connected: Boolean = false,
    val account: String? = null,
    val scopeCount: Int = 0,
    val missingScopes: List<String> = emptyList(),
    val checkedAt: Long = 0,
    val message: String? = null,
)

internal object FeishuStateStore {
    fun load(host: PluginHost): FeishuSnapshot? = runCatching {
        val file = File(host.dataDir, STATE_FILE)
        if (!file.isFile) return null
        val root = Json.parseToJsonElement(file.readText()).jsonObject
        FeishuSnapshot(
            installedVersion = root.string("installedVersion"),
            appId = root.string("appId"),
            appConfigured = root.boolean("appConfigured") ?: false,
            connected = root.boolean("connected") ?: false,
            account = root.string("account"),
            scopeCount = root.integer("scopeCount") ?: 0,
            missingScopes = root.strings("missingScopes"),
            checkedAt = root.number("checkedAt") ?: 0,
        )
    }.getOrNull()

    fun save(host: PluginHost, snapshot: FeishuSnapshot) = runCatching {
        val json = buildJsonObject {
            snapshot.installedVersion?.let { put("installedVersion", it) }
            snapshot.appId?.let { put("appId", it) }
            put("appConfigured", snapshot.appConfigured)
            put("connected", snapshot.connected)
            snapshot.account?.let { put("account", it) }
            put("scopeCount", snapshot.scopeCount)
            put(
                "missingScopes",
                buildJsonArray { snapshot.missingScopes.forEach { add(JsonPrimitive(it)) } },
            )
            put("checkedAt", snapshot.checkedAt)
        }
        val file = File(host.dataDir, STATE_FILE)
        file.parentFile?.mkdirs()
        file.writeText(json.toString())
    }

    fun delete(host: PluginHost) {
        runCatching { File(host.dataDir, STATE_FILE).delete() }
    }

    private fun kotlinx.serialization.json.JsonObject.string(name: String): String? =
        (this[name] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun kotlinx.serialization.json.JsonObject.boolean(name: String): Boolean? =
        (this[name] as? JsonPrimitive)?.booleanOrNull

    private fun kotlinx.serialization.json.JsonObject.integer(name: String): Int? =
        (this[name] as? JsonPrimitive)?.intOrNull

    private fun kotlinx.serialization.json.JsonObject.number(name: String): Long? =
        (this[name] as? JsonPrimitive)?.longOrNull

    private fun kotlinx.serialization.json.JsonObject.strings(name: String): List<String> {
        val element = this[name] ?: return emptyList()
        return runCatching { element.jsonArray }.getOrNull()
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf { value -> value.isNotBlank() } }
            .orEmpty()
    }
}
