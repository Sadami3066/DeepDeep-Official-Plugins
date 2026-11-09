package io.github.whywood.deepdeep.plugin.sysu

import io.github.whywood.deepdeep.plugin.api.PluginHost
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

    fun read(host: PluginHost): SysuStoredStatus? = runCatching {
        val file = File(host.dataDir, FILE_NAME)
        if (!file.isFile) return null
        val json = Json.parseToJsonElement(file.readText()).jsonObject
        SysuStoredStatus(
            installedVersion = json.string("installedVersion"),
            nodeVersion = json.string("nodeVersion"),
            account = json.string("account"),
            loggedIn = json["loggedIn"]?.jsonPrimitive?.booleanOrNull ?: false,
            checkedAt = json["checkedAt"]?.jsonPrimitive?.longOrNull ?: 0L,
        )
    }.getOrNull()

    suspend fun write(host: PluginHost, stored: SysuStoredStatus) {
        val json = buildJsonObject {
            stored.installedVersion?.let { put("installedVersion", it) }
            stored.nodeVersion?.let { put("nodeVersion", it) }
            stored.account?.let { put("account", it) }
            put("loggedIn", stored.loggedIn)
            put("checkedAt", stored.checkedAt)
        }
        runCatching { host.files().writeText(FILE_NAME, json.toString()) }
    }

    suspend fun clear(host: PluginHost) {
        runCatching { host.files().delete(FILE_NAME) }
    }

    private fun kotlinx.serialization.json.JsonObject.string(name: String): String? =
        (this[name] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
}
