package io.github.whywood.superpi.plugin.ride

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RideErrorSummaryTest {
    @Test
    fun keepsUrlButRedactsSecretKey() {
        val summary = summarizeDidiError(
            "request failed https://mcp.didichuxing.com?key=abc123 key=abc123 secret",
            secret = "abc123",
        )
        assertFalse(summary.contains("abc123"))
        assertTrue(summary.contains("https://mcp.didichuxing.com"))
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
    fun keepsLongErrorsWhole() {
        val raw = "上游错误 " + "x".repeat(500)
        val summary = summarizeDidiError(raw)
        assertEquals(raw, summary)
        assertFalse(summary.endsWith("…"))
    }

    @Test
    fun keepsUpstreamJsonAndOnlyRedactsQuotedSecrets() {
        val raw = """{"code":-32011,"message":"订单不存在","key":"secret-value","detail":"trailing"}"""
        val summary = summarizeDidiError(raw)
        assertTrue(summary.contains("\"code\":-32011"))
        assertTrue(summary.contains("订单不存在"))
        assertFalse(summary.contains("secret-value"))
        assertTrue(summary.contains("\"key\":\"***\""))
    }

    @Test
    fun blankMessageStaysBlank() {
        assertEquals("", summarizeDidiError(null))
        assertEquals("", summarizeDidiError("   "))
    }
}
