package io.github.whywood.superpi.plugin.wecom

import io.github.whywood.superpi.plugin.api.PluginHost
import io.github.whywood.superpi.plugin.cli.boolValue
import io.github.whywood.superpi.plugin.cli.longValue
import io.github.whywood.superpi.plugin.cli.stringValue
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

internal data class WecomStatus(
    val cliVersion: String? = null,
    val authorized: Boolean = false,
    val botId: String? = null,
    val checkedAt: Long = 0L,
) {
    val installed: Boolean get() = cliVersion != null
}

internal class WecomStatusStore(private val host: PluginHost) {
    suspend fun read(): WecomStatus {
        val stored = runCatching { host.settings().getJson(KEY) }.getOrNull()
        if (stored != null) return runCatching { decode(stored) }.getOrDefault(WecomStatus())
        val legacy = File(host.dataDir, FILE_NAME)
        if (!legacy.isFile) return WecomStatus()
        val migrated = runCatching {
            decode(Json.parseToJsonElement(legacy.readText()).jsonObject)
        }.getOrDefault(WecomStatus())
        write(migrated)
        runCatching { legacy.delete() }
        return migrated
    }

    suspend fun write(status: WecomStatus) {
        runCatching { host.settings().putJson(KEY, encode(status)) }
    }

    suspend fun clear() {
        runCatching { host.settings().remove(KEY) }
        runCatching { File(host.dataDir, FILE_NAME).delete() }
    }

    private fun encode(status: WecomStatus): JsonObject = buildJsonObject {
        status.cliVersion?.let { put("cliVersion", it) }
        put("authorized", status.authorized)
        status.botId?.let { put("botId", it) }
        put("checkedAt", status.checkedAt)
    }

    private fun decode(root: JsonObject): WecomStatus = WecomStatus(
        cliVersion = root.stringValue("cliVersion"),
        authorized = root.boolValue("authorized") ?: false,
        botId = root.stringValue("botId"),
        checkedAt = root.longValue("checkedAt") ?: 0L,
    )

    companion object {
        const val FILE_NAME = "wecom-status.json"
        private const val KEY = "status"
    }
}
