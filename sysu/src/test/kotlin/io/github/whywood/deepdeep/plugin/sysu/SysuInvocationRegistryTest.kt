package io.github.whywood.deepdeep.plugin.sysu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SysuInvocationRegistryTest {
    private fun invocation(command: String): SysuInvocation =
        SysuCommandParser.parse(command) ?: error("not a sysu command: $command")

    @Test
    fun recordsThenTakesByCallId() {
        val registry = SysuInvocationRegistry()
        registry.record("session-1", "call-1", invocation("sysu jwxt --json"))
        assertEquals(1, registry.size())
        assertEquals(SysuCommandKind.Schedule, (registry.take("session-1", "call-1") as SysuInvocation.Supported).kind)
        assertEquals(0, registry.size())
        assertNull(registry.take("session-1", "call-1"))
    }

    @Test
    fun duplicateCallIdKeepsLatestInvocation() {
        val registry = SysuInvocationRegistry()
        registry.record("session-1", "call-1", invocation("sysu jwxt --json"))
        registry.record("session-1", "call-1", invocation("sysu lms --json"))
        assertEquals(1, registry.size())
        assertEquals(SysuCommandKind.LmsCourses, (registry.take("session-1", "call-1") as SysuInvocation.Supported).kind)
    }

    @Test
    fun ignoresBlankCallIds() {
        val registry = SysuInvocationRegistry()
        registry.record("session-1", " ", invocation("sysu jwxt --json"))
        assertEquals(0, registry.size())
    }

    @Test
    fun keepsSessionsApart() {
        val registry = SysuInvocationRegistry()
        registry.record("session-1", "call-1", invocation("sysu jwxt --json"))
        registry.record("session-2", "call-1", invocation("sysu lms --json"))
        assertEquals(2, registry.size())
        registry.clearSession("session-1")
        assertEquals(1, registry.size())
        assertNull(registry.take("session-1", "call-1"))
        assertEquals(SysuCommandKind.LmsCourses, (registry.take("session-2", "call-1") as SysuInvocation.Supported).kind)
    }

    @Test
    fun boundsPendingCalls() {
        val registry = SysuInvocationRegistry(maxEntries = 64)
        repeat(80) { index ->
            registry.record("session-1", "call-$index", invocation("sysu jwxt --json"))
        }
        assertEquals(64, registry.size())
        assertNull(registry.take("session-1", "call-0"))
        assertEquals(64, registry.size())
        assertNull(registry.take("session-1", "call-15"))
        assertEquals(SysuCommandKind.Schedule, (registry.take("session-1", "call-16") as SysuInvocation.Supported).kind)
        assertEquals(SysuCommandKind.Schedule, (registry.take("session-1", "call-79") as SysuInvocation.Supported).kind)
        assertEquals(62, registry.size())
    }
}
