package io.github.whywood.deepdeep.plugin.amap

import kotlinx.coroutines.test.runTest
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
