package io.github.whywood.deepdeep.plugin.ride

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RideErrorSummaryTest {
    @Test
    fun removesSecretKeyAndUrls() {
        val summary = summarizeDidiError(
            "request failed https://mcp.didichuxing.com?key=abc123 key=abc123 secret",
            secret = "abc123",
        )
        assertFalse(summary.contains("abc123"))
        assertFalse(summary.contains("https://"))
        assertTrue(summary.contains("***"))
    }

    @Test
    fun redactsKeyValuePairsEvenWithoutSecret() {
        val summary = summarizeDidiError("""{"error":"bad token=xyz&key=abc","detail":"no access"}""")
        assertFalse(summary.contains("xyz"))
        assertFalse(summary.contains("abc"))
        assertTrue(summary.contains("token=***"))
    }

    @Test
    fun truncatesLongMessages() {
        val summary = summarizeDidiError("x".repeat(500), maxLength = 60)
        assertTrue(summary.length <= 60)
        assertTrue(summary.endsWith("…"))
    }

    @Test
    fun blankMessageStaysBlank() {
        assertEquals("", summarizeDidiError(null))
        assertEquals("", summarizeDidiError("   "))
    }
}
