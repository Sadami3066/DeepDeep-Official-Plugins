package io.github.whywood.deepdeep.plugin.sysu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SysuCommandTest {
    private fun supported(command: String): SysuInvocation.Supported {
        val invocation = SysuCommandParser.parse(command)
        assertTrue("expected supported invocation for: $command, got $invocation", invocation is SysuInvocation.Supported)
        return invocation as SysuInvocation.Supported
    }

    private fun other(command: String): SysuInvocation.Other {
        val invocation = SysuCommandParser.parse(command)
        assertTrue("expected other invocation for: $command, got $invocation", invocation is SysuInvocation.Other)
        return invocation as SysuInvocation.Other
    }

    @Test
    fun matchesSupportedReadCommandsWithoutJsonFlag() {
        assertEquals(SysuCommandKind.Schedule, supported("sysu jwxt").kind)
        assertEquals(SysuCommandKind.Schedule, supported("sysu jwxt schedule").kind)
        assertEquals(SysuCommandKind.Schedule, supported("sysu jwxt --week 8").kind)
        assertEquals(SysuCommandKind.Schedule, supported("sysu jwxt schedule --today").kind)
        assertEquals(SysuCommandKind.Scores, supported("sysu jwxt scores").kind)
        assertEquals(SysuCommandKind.Scores, supported("sysu jwxt scores --all").kind)
        assertEquals(SysuCommandKind.Scores, supported("sysu jwxt scores --semester 2025-2").kind)
        assertEquals(SysuCommandKind.Exams, supported("sysu jwxt exams").kind)
        assertEquals(SysuCommandKind.Credits, supported("sysu jwxt credits").kind)
        assertEquals(SysuCommandKind.LmsCourses, supported("sysu lms").kind)
        assertEquals(SysuCommandKind.LmsCourses, supported("sysu lms courses").kind)
        assertEquals(SysuCommandKind.LmsTodo, supported("sysu lms todo").kind)
        assertEquals(SysuCommandKind.LmsTodo, supported("sysu lms todo --days 30").kind)
        assertEquals(SysuCommandKind.GymSlots, supported("sysu gym slots 乒乓球").kind)
        assertEquals(SysuCommandKind.GymSlots, supported("sysu gym slots 健身房 --days 2").kind)
        assertEquals(SysuCommandKind.GymSlots, supported("sysu gym slots 健身房 --date 2026-11-16").kind)
        assertEquals(SysuCommandKind.Netpay, supported("sysu netpay").kind)
        assertEquals(SysuCommandKind.Netpay, supported("sysu netpay info").kind)
    }

    @Test
    fun acceptsLegacyJsonFlagWithoutAppendingIt() {
        assertEquals(SysuCommandKind.Schedule, supported("sysu jwxt --json").kind)
        assertEquals("sysu jwxt --week 8", supported("sysu jwxt --week 8 --json").commandLine)
        assertEquals("sysu gym slots 乒乓球", supported("sysu gym slots 乒乓球 --json").commandLine)
        assertEquals("sysu netpay", supported("sysu netpay --json").commandLine)
    }

    @Test
    fun acceptsQuotedArgumentsAndAbsoluteExecutable() {
        assertEquals(SysuCommandKind.GymSlots, supported("sysu gym slots '乒乓球'").kind)
        assertEquals(SysuCommandKind.Schedule, supported("/usr/local/bin/sysu jwxt").kind)
        assertEquals(SysuCommandKind.Netpay, supported("  sysu   netpay  ").kind)
    }

    @Test
    fun rejectsPipesRedirectionAndCompoundCommands() {
        assertNull(SysuCommandParser.parse("sysu jwxt --json | head -20"))
        assertNull(SysuCommandParser.parse("sysu jwxt --json | grep -A2 周"))
        assertNull(SysuCommandParser.parse("sysu jwxt --json > /tmp/schedule.json"))
        assertNull(SysuCommandParser.parse("cd /root && sysu jwxt --json"))
        assertNull(SysuCommandParser.parse("sysu jwxt --json; sysu lms --json"))
        assertNull(SysuCommandParser.parse("echo \$(sysu jwxt --json)"))
        assertNull(SysuCommandParser.parse("sysu jwxt --json && echo done"))
        assertNull(SysuCommandParser.parse("FOO=1 sysu jwxt --json"))
    }

    @Test
    fun keepsIncompleteOrUnsupportedCommandsAsOther() {
        assertEquals(SysuInvocation.Other("sysu gym slots"), SysuCommandParser.parse("sysu gym slots"))
        assertEquals(SysuInvocation.Other("sysu gym slots 乒乓球 --bogus"), SysuCommandParser.parse("sysu gym slots 乒乓球 --bogus"))
        assertEquals(SysuInvocation.Other("sysu lms course 1005339"), SysuCommandParser.parse("sysu lms course 1005339"))
    }

    @Test
    fun keepsUnsupportedReadsAsOther() {
        other("sysu jwxt course LN3015")
        other("sysu jwxt notice")
        other("sysu jwxt room --sections 9-11")
        other("sysu lms course 1005339")
        other("sysu lms files 1005339")
        other("sysu gym")
        other("sysu gym types")
        other("sysu netpay orders")
        other("sysu login --send-code")
        other("sysu gym slots")
        other("sysu")
    }

    @Test
    fun rejectsUnknownFlagsAndDanglingValues() {
        assertEquals(SysuInvocation.Other("sysu jwxt schedule --future"), SysuCommandParser.parse("sysu jwxt schedule --future"))
        assertEquals(SysuInvocation.Other("sysu jwxt schedule --week"), SysuCommandParser.parse("sysu jwxt schedule --week"))
        assertEquals(SysuInvocation.Other("sysu jwxt exams --all"), SysuCommandParser.parse("sysu jwxt exams --all"))
        assertEquals(SysuInvocation.Other("sysu lms courses --days 3"), SysuCommandParser.parse("sysu lms courses --days 3"))
    }

    @Test
    fun ignoresNonSysuCommands() {
        assertNull(SysuCommandParser.parse("lark-cli im +chat-list --json"))
        assertNull(SysuCommandParser.parse("ls /root"))
        assertNull(SysuCommandParser.parse(""))
        assertNull(SysuCommandParser.parse("   "))
        assertNull(SysuCommandParser.parse("sysu-cli jwxt --json"))
    }
}
