package io.github.whywood.deepdeep.plugin.feishu

import io.github.whywood.deepdeep.plugin.api.PluginHost
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
    private const val KEY = "snapshot"

    suspend fun load(host: PluginHost): FeishuSnapshot? {
        val stored = runCatching { host.settings().getJson(KEY) }.getOrNull()
        if (stored != null) return runCatching { decode(stored) }.getOrNull()
        val legacy = File(host.dataDir, STATE_FILE)
        if (!legacy.isFile) return null
        val migrated = runCatching { decode(Json.parseToJsonElement(legacy.readText()).jsonObject) }.getOrNull()
        if (migrated != null) {
            save(host, migrated)
            runCatching { legacy.delete() }
        }
        return migrated
    }

    suspend fun save(host: PluginHost, snapshot: FeishuSnapshot) {
        runCatching { host.settings().putJson(KEY, encode(snapshot)) }
    }

    suspend fun delete(host: PluginHost) {
        runCatching { host.settings().remove(KEY) }
        runCatching { File(host.dataDir, STATE_FILE).delete() }
    }

    private fun encode(snapshot: FeishuSnapshot): JsonObject = buildJsonObject {
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

    private fun decode(root: JsonObject): FeishuSnapshot = FeishuSnapshot(
        installedVersion = root.string("installedVersion"),
        appId = root.string("appId"),
        appConfigured = root.boolean("appConfigured") ?: false,
        connected = root.boolean("connected") ?: false,
        account = root.string("account"),
        scopeCount = root.integer("scopeCount") ?: 0,
        missingScopes = root.strings("missingScopes"),
        checkedAt = root.number("checkedAt") ?: 0,
    )

    private fun JsonObject.string(name: String): String? =
        (this[name] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun JsonObject.boolean(name: String): Boolean? =
        (this[name] as? JsonPrimitive)?.booleanOrNull

    private fun JsonObject.integer(name: String): Int? =
        (this[name] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.number(name: String): Long? =
        (this[name] as? JsonPrimitive)?.longOrNull

    private fun JsonObject.strings(name: String): List<String> {
        val element = this[name] ?: return emptyList()
        return runCatching { element.jsonArray }.getOrNull()
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf { value -> value.isNotBlank() } }
            .orEmpty()
    }
}
