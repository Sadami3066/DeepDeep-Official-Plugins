package io.github.whywood.superpi.plugin.ride

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RideKeyStoreTest {
    @Test
    fun migratesLegacySettingsKeyIntoSecretsAndClearsPlaintext() = runTest {
        val settings = FakeSettingsService(mapOf(RideKeyStore.LEGACY_KEY to "legacy-key"))
        val secrets = FakeSecretService()
        var migrated = 0
        val store = RideKeyStore(settings, secrets) { migrated++ }

        assertTrue(store.hasKey())
        assertEquals(1, migrated)
        assertEquals("legacy-key", secrets.snapshotValues[RideKeyStore.SECRET_KEY])
        assertNull(settings.snapshotValues[RideKeyStore.LEGACY_KEY])
    }

    @Test
    fun storedSecretWinsOverLegacyPlaintext() = runTest {
        val settings = FakeSettingsService(mapOf(RideKeyStore.LEGACY_KEY to "legacy-key"))
        val secrets = FakeSecretService(mapOf(RideKeyStore.SECRET_KEY to "secret-key"))
        val store = RideKeyStore(settings, secrets)

        assertEquals("secret-key", store.currentKey())
        assertNull(settings.snapshotValues[RideKeyStore.LEGACY_KEY])
    }

    @Test
    fun saveWritesSecretAndDropsLegacyValue() = runTest {
        val settings = FakeSettingsService(mapOf(RideKeyStore.LEGACY_KEY to "legacy-key"))
        val secrets = FakeSecretService()
        val store = RideKeyStore(settings, secrets)

        store.save(" new-key ")
        assertEquals("new-key", secrets.snapshotValues[RideKeyStore.SECRET_KEY])
        assertNull(settings.snapshotValues[RideKeyStore.LEGACY_KEY])
    }

    @Test
    fun clearRemovesSecretAndLegacyKey() = runTest {
        val settings = FakeSettingsService(mapOf(RideKeyStore.LEGACY_KEY to "legacy-key"))
        val secrets = FakeSecretService(mapOf(RideKeyStore.SECRET_KEY to "secret-key"))
        val store = RideKeyStore(settings, secrets)

        store.clear()
        assertTrue(secrets.snapshotValues.isEmpty())
        assertTrue(settings.snapshotValues.isEmpty())
    }

    @Test
    fun missingKeyIsReportedAsUnconfigured() = runTest {
        val store = RideKeyStore(FakeSettingsService(), FakeSecretService())
        assertFalse(store.hasKey())
        assertNull(store.currentKey())
    }
}
