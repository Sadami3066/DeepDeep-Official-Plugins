package io.github.whywood.deepdeep.plugin.sysu

import io.github.whywood.deepdeep.plugin.api.PluginHost
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

internal data class SysuStatus(
    val linuxAvailable: Boolean = false,
    val nodeVersion: String? = null,
    val installedVersion: String? = null,
    val recordedVersion: String? = null,
    val account: String? = null,
    val loggedIn: Boolean = false,
) {
    val nodeTooOld: Boolean
        get() = nodeVersion?.let { (SysuCli.parseNodeMajor(it) ?: 0) < SysuCli.MIN_NODE_MAJOR } == true

    val installed: Boolean get() = installedVersion != null

    val outdated: Boolean get() = installedVersion != null && installedVersion != SysuCli.VERSION
}

internal data class SysuStoredStatus(
    val installedVersion: String? = null,
    val nodeVersion: String? = null,
    val account: String? = null,
    val loggedIn: Boolean = false,
    val checkedAt: Long = 0L,
)

internal object SysuStatusStore {
    const val FILE_NAME = "status.json"
    private const val KEY = "status"

    suspend fun read(host: PluginHost): SysuStoredStatus? {
        val stored = runCatching { host.settings().getJson(KEY) }.getOrNull()
        if (stored != null) return runCatching { decode(stored) }.getOrNull()
        val legacy = File(host.dataDir, FILE_NAME)
        if (!legacy.isFile) return null
        val migrated = runCatching {
            decode(Json.parseToJsonElement(legacy.readText()).jsonObject)
        }.getOrNull()
        if (migrated != null) {
            write(host, migrated)
            runCatching { legacy.delete() }
        }
        return migrated
    }

    suspend fun write(host: PluginHost, stored: SysuStoredStatus) {
        runCatching { host.settings().putJson(KEY, encode(stored)) }
    }

    suspend fun clear(host: PluginHost) {
        runCatching { host.settings().remove(KEY) }
        runCatching { File(host.dataDir, FILE_NAME).delete() }
    }

    private fun encode(stored: SysuStoredStatus): JsonObject = buildJsonObject {
        stored.installedVersion?.let { put("installedVersion", it) }
        stored.nodeVersion?.let { put("nodeVersion", it) }
        stored.account?.let { put("account", it) }
        put("loggedIn", stored.loggedIn)
        put("checkedAt", stored.checkedAt)
    }

    private fun decode(root: JsonObject): SysuStoredStatus = SysuStoredStatus(
        installedVersion = (root["installedVersion"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() },
        nodeVersion = (root["nodeVersion"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() },
        account = (root["account"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() },
        loggedIn = (root["loggedIn"] as? JsonPrimitive)?.booleanOrNull ?: false,
        checkedAt = (root["checkedAt"] as? JsonPrimitive)?.longOrNull ?: 0L,
    )
}
