package io.github.whywood.deepdeep.plugin.wecom

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

internal data class WecomStatus(
    val cliVersion: String? = null,
    val authorized: Boolean = false,
    val botId: String? = null,
    val checkedAt: Long = 0L,
) {
    val installed: Boolean get() = cliVersion != null
}

internal class WecomStatusStore(private val host: PluginHost) {
    private val file = File(host.dataDir, FILE_NAME)

    fun read(): WecomStatus = runCatching {
        val json = Json.parseToJsonElement(file.readText()).jsonObject
        WecomStatus(
            cliVersion = json["cliVersion"]?.jsonPrimitive?.contentOrNull,
            authorized = json["authorized"]?.jsonPrimitive?.booleanOrNull ?: false,
            botId = json["botId"]?.jsonPrimitive?.contentOrNull,
            checkedAt = json["checkedAt"]?.jsonPrimitive?.longOrNull ?: 0L,
        )
    }.getOrDefault(WecomStatus())

    suspend fun write(status: WecomStatus) {
        val json = buildJsonObject {
            put("cliVersion", status.cliVersion)
            put("authorized", status.authorized)
            put("botId", status.botId)
            put("checkedAt", status.checkedAt)
        }
        runCatching { host.files().writeText(FILE_NAME, json.toString()) }
    }

    suspend fun clear() {
        runCatching { host.files().delete(FILE_NAME) }
    }

    companion object {
        const val FILE_NAME = "wecom-status.json"
    }
}
