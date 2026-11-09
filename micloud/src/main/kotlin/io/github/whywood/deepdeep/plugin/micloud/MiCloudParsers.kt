package io.github.whywood.deepdeep.plugin.micloud

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

data class MiCloudProfile(
    val nickname: String?,
    val userId: String?,
    val icon: String?,
)

internal fun parseCookie(cookie: String): Map<String, String> = buildMap {
    cookie.split(';').forEach { part ->
        val separator = part.indexOf('=')
        if (separator <= 0) return@forEach
        val name = part.substring(0, separator).trim()
        if (name.isEmpty()) return@forEach
        put(name, part.substring(separator + 1).trim())
    }
}

internal fun miCloudResponseData(raw: JsonElement): JsonObject? {
    val root = raw as? JsonObject ?: return null
    (root["data"] as? JsonObject)?.let { return it }
    (root["D"] as? JsonObject)?.let { return it }
    return root
}

internal fun isMiCloudAuthFailure(raw: JsonElement): Boolean {
    val root = raw as? JsonObject ?: return false
    val code = root.stringOrNull("R") ?: root.stringOrNull("code") ?: return false
    return code == "401" || code == "403"
}

internal fun parseProfile(raw: JsonElement): MiCloudProfile? {
    val data = miCloudResponseData(raw) ?: return null
    val nickname = data.stringOrNull("nickname")?.takeIf { it.isNotBlank() }
    val userId = data.stringOrNull("userId")
    if (nickname == null && userId == null) return null
    return MiCloudProfile(nickname, userId, data.stringOrNull("icon"))
}
