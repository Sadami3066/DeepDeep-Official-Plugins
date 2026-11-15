package io.github.whywood.deepdeep.plugin.cli.update

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CliUpdateCheckControllerTest {
    private val release = CliRelease("1.0.100", "archive.tgz", listOf("https://example.com/archive"), "a".repeat(64))

    @Test
    fun reportsResultAfterSuccessfulCheck() = runTest {
        val source = CliUpdateSource { _, installed -> CliUpdateCheck("1.0.100", release, failed = false) }
        val controller = CliUpdateCheckController(source)
        var started = 0
        var result: CliUpdateCheck? = null
        controller.request(
            scope = this,
            network = null,
            installedVersion = "1.0.98",
            lastCheckedAt = 0,
            force = false,
            onStart = { started++ },
            onResult = { result = it },
        )
        assertEquals(1, started)
        advanceUntilIdle()
        assertEquals("1.0.100", result?.latestVersion)
        assertEquals(release, result?.release)
        assertFalse(result!!.failed)
        assertFalse(controller.isRunning())
    }

    @Test
    fun skipsCheckWithinThrottleWindowUnlessForced() = runTest {
        val source = CliUpdateSource { _, _ -> CliUpdateCheck(null, null, failed = true) }
        val controller = CliUpdateCheckController(source)
        var started = 0
        controller.request(
            scope = this,
            network = null,
            installedVersion = "1.0.98",
            lastCheckedAt = System.currentTimeMillis(),
            force = false,
            onStart = { started++ },
            onResult = {},
        )
        assertEquals(0, started)
        controller.request(
            scope = this,
            network = null,
            installedVersion = "1.0.98",
            lastCheckedAt = System.currentTimeMillis(),
            force = true,
            onStart = { started++ },
            onResult = {},
        )
        assertEquals(1, started)
        advanceUntilIdle()
    }

    @Test
    fun ignoresRequestWithoutInstalledVersion() = runTest {
        val source = CliUpdateSource { _, _ -> CliUpdateCheck(null, null, failed = false) }
        val controller = CliUpdateCheckController(source)
        var started = 0
        controller.request(
            scope = this,
            network = null,
            installedVersion = null,
            lastCheckedAt = 0,
            force = true,
            onStart = { started++ },
            onResult = {},
        )
        assertEquals(0, started)
    }

    @Test
    fun runsSingleCheckAtATime() = runTest {
        var calls = 0
        val source = CliUpdateSource { _, _ ->
            calls++
            delay(1_000)
            CliUpdateCheck("1.0.100", release, failed = false)
        }
        val controller = CliUpdateCheckController(source)
        var started = 0
        controller.request(this, null, "1.0.98", 0, force = true, onStart = { started++ }, onResult = {})
        controller.request(this, null, "1.0.98", 0, force = true, onStart = { started++ }, onResult = {})
        assertEquals(1, started)
        advanceUntilIdle()
        assertEquals(1, calls)
    }

    @Test
    fun failedDiscoveryBecomesFailedResult() = runTest {
        val source = CliUpdateSource { _, _ -> throw IllegalStateException("offline") }
        val controller = CliUpdateCheckController(source)
        var result: CliUpdateCheck? = null
        controller.request(this, null, "1.0.98", 0, force = true, onStart = {}, onResult = { result = it })
        advanceUntilIdle()
        assertTrue(result!!.failed)
        assertNull(result!!.latestVersion)
    }

    @Test
    fun cancelDropsRunningCheck() = runTest {
        val source = CliUpdateSource { _, _ ->
            delay(1_000)
            CliUpdateCheck("1.0.100", release, failed = false)
        }
        val controller = CliUpdateCheckController(source)
        var result: CliUpdateCheck? = null
        controller.request(this, null, "1.0.98", 0, force = true, onStart = {}, onResult = { result = it })
        controller.cancel()
        advanceUntilIdle()
        assertNull(result)
        assertFalse(controller.isRunning())
    }
}
