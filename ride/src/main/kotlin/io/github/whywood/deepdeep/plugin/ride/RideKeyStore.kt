package io.github.whywood.superpi.plugin.ride

import io.github.whywood.superpi.plugin.api.PluginSettingsService
import io.github.whywood.superpi.plugin.api.SecretService

internal class RideKeyStore(
    private val settings: PluginSettingsService,
    private val secrets: SecretService,
    private val onMigrated: () -> Unit = {},
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
        onMigrated()
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
        const val SECRET_KEY = "didi_api_key"
        const val LEGACY_KEY = "didi_api_key"
    }
}
