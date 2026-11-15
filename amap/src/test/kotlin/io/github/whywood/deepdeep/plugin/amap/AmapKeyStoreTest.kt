package io.github.whywood.deepdeep.plugin.amap

import io.github.whywood.deepdeep.plugin.api.PluginSettingsService
import io.github.whywood.deepdeep.plugin.api.SecretService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AmapKeyStoreTest {
    @Test
    fun migratesLegacyPlaintextKeyIntoSecrets() = runTest {
        val settings = FakeSettings(mapOf(AmapKeyStore.LEGACY_KEY to "legacy-key"))
        val secrets = FakeSecrets()
        val store = AmapKeyStore(settings, secrets)

        assertTrue(store.hasKey())
        assertEquals("legacy-key", secrets.values[AmapKeyStore.SECRET_KEY])
        assertNull(settings.values[AmapKeyStore.LEGACY_KEY])
    }

    @Test
    fun storedSecretWinsOverLegacyPlaintext() = runTest {
        val settings = FakeSettings(mapOf(AmapKeyStore.LEGACY_KEY to "legacy-key"))
        val secrets = FakeSecrets(mapOf(AmapKeyStore.SECRET_KEY to "secret-key"))
        val store = AmapKeyStore(settings, secrets)

        assertEquals("secret-key", store.currentKey())
        assertNull(settings.values[AmapKeyStore.LEGACY_KEY])
    }

    @Test
    fun saveTrimsAndDropsLegacyValue() = runTest {
        val settings = FakeSettings(mapOf(AmapKeyStore.LEGACY_KEY to "legacy-key"))
        val secrets = FakeSecrets()
        val store = AmapKeyStore(settings, secrets)

        store.save(" new-key ")

        assertEquals("new-key", secrets.values[AmapKeyStore.SECRET_KEY])
        assertNull(settings.values[AmapKeyStore.LEGACY_KEY])
    }

    @Test
    fun clearRemovesEverything() = runTest {
        val settings = FakeSettings(mapOf(AmapKeyStore.LEGACY_KEY to "legacy-key"))
        val secrets = FakeSecrets(mapOf(AmapKeyStore.SECRET_KEY to "secret-key"))
        val store = AmapKeyStore(settings, secrets)

        store.clear()

        assertTrue(secrets.values.isEmpty())
        assertTrue(settings.values.isEmpty())
    }

    @Test
    fun missingKeyIsUnconfigured() = runTest {
        val store = AmapKeyStore(FakeSettings(), FakeSecrets())

        assertFalse(store.hasKey())
        assertNull(store.currentKey())
    }
}

private class FakeSettings(initial: Map<String, String> = emptyMap()) : PluginSettingsService {
    var values = initial.toMap()
        private set

    override suspend fun get(key: String): String? = values[key]

    override suspend fun put(key: String, value: String) {
        values = values + (key to value)
    }

    override suspend fun remove(key: String) {
        values = values - key
    }

    override suspend fun clear() {
        values = emptyMap()
    }

    override suspend fun keys(): Set<String> = values.keys

    override suspend fun snapshot(): Map<String, String> = values

    override suspend fun getJson(key: String): JsonObject? = null

    override suspend fun putJson(key: String, value: JsonObject) = Unit

    override fun observe(key: String?): Flow<Map<String, String>> =
        if (key == null) MutableStateFlow(values) else MutableStateFlow(values).map { map -> map.filterKeys { it == key } }
}

private class FakeSecrets(initial: Map<String, String> = emptyMap()) : SecretService {
    var values = initial.toMutableMap()
        private set

    override suspend fun get(key: String): String? = values[key]

    override suspend fun put(key: String, value: String) {
        values[key] = value
    }

    override suspend fun remove(key: String) {
        values.remove(key)
    }

    override suspend fun clear() {
        values.clear()
    }
}
