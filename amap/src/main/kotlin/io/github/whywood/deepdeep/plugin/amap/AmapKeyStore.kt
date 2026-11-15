package io.github.whywood.deepdeep.plugin.amap

import io.github.whywood.deepdeep.plugin.api.PluginSettingsService
import io.github.whywood.deepdeep.plugin.api.SecretService

internal class AmapKeyStore(
    private val settings: PluginSettingsService,
    private val secrets: SecretService,
) {
    suspend fun hasKey(): Boolean = !currentKey().isNullOrBlank()

    suspend fun currentKey(): String? {
        val stored = secrets.get(SECRET_KEY)?.trim().orEmpty()
        if (stored.isNotEmpty()) {
            settings.remove(LEGACY_KEY)
            return stored
        }
        return migrateLegacyKey()
    }

    suspend fun migrateLegacyKey(): String? {
        val legacy = settings.get(LEGACY_KEY)?.trim().orEmpty()
        if (legacy.isEmpty()) return null
        secrets.put(SECRET_KEY, legacy)
        settings.remove(LEGACY_KEY)
        return legacy
    }

    suspend fun save(key: String) {
        secrets.put(SECRET_KEY, key.trim())
        settings.remove(LEGACY_KEY)
    }

    suspend fun clear() {
        secrets.remove(SECRET_KEY)
        settings.remove(LEGACY_KEY)
    }

    companion object {
        const val SECRET_KEY = "amap_mcp_key"
        const val LEGACY_KEY = "amap_key"
    }
}
