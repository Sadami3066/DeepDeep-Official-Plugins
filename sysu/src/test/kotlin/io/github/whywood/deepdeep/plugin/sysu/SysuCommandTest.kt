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
    fun matchesSupportedReadCommands() {
        assertEquals(SysuCommandKind.Schedule, supported("sysu jwxt --json").kind)
        assertEquals(SysuCommandKind.Schedule, supported("sysu jwxt schedule --json").kind)
        assertEquals(SysuCommandKind.Schedule, supported("sysu jwxt --week 8 --json").kind)
        assertEquals(SysuCommandKind.Schedule, supported("sysu jwxt schedule --today --json").kind)
        assertEquals(SysuCommandKind.Scores, supported("sysu jwxt scores --json").kind)
        assertEquals(SysuCommandKind.Scores, supported("sysu jwxt scores --all --json").kind)
        assertEquals(SysuCommandKind.Scores, supported("sysu jwxt scores --semester 2025-2 --json").kind)
        assertEquals(SysuCommandKind.Exams, supported("sysu jwxt exams --json").kind)
        assertEquals(SysuCommandKind.Credits, supported("sysu jwxt credits --json").kind)
        assertEquals(SysuCommandKind.LmsCourses, supported("sysu lms --json").kind)
        assertEquals(SysuCommandKind.LmsCourses, supported("sysu lms courses --json").kind)
        assertEquals(SysuCommandKind.LmsTodo, supported("sysu lms todo --json").kind)
        assertEquals(SysuCommandKind.LmsTodo, supported("sysu lms todo --days 30 --json").kind)
        assertEquals(SysuCommandKind.GymSlots, supported("sysu gym slots 乒乓球 --json").kind)
        assertEquals(SysuCommandKind.GymSlots, supported("sysu gym slots 健身房 --days 2 --json").kind)
        assertEquals(SysuCommandKind.GymSlots, supported("sysu gym slots 健身房 --date 2026-11-16 --json").kind)
        assertEquals(SysuCommandKind.Netpay, supported("sysu netpay --json").kind)
        assertEquals(SysuCommandKind.Netpay, supported("sysu netpay info --json").kind)
    }

    @Test
    fun acceptsQuotedArgumentsAndAbsoluteExecutable() {
        assertEquals(SysuCommandKind.GymSlots, supported("sysu gym slots '乒乓球' --json").kind)
        assertEquals(SysuCommandKind.Schedule, supported("/usr/local/bin/sysu jwxt --json").kind)
        assertEquals(SysuCommandKind.Netpay, supported("  sysu   netpay   --json  ").kind)
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
    fun rejectsCommandsWithoutJson() {
        assertEquals(SysuInvocation.Other("sysu jwxt"), SysuCommandParser.parse("sysu jwxt"))
        assertEquals(SysuInvocation.Other("sysu jwxt schedule"), SysuCommandParser.parse("sysu jwxt schedule"))
        assertEquals(SysuInvocation.Other("sysu lms todo --days 30"), SysuCommandParser.parse("sysu lms todo --days 30"))
        assertEquals(SysuInvocation.Other("sysu gym slots 乒乓球"), SysuCommandParser.parse("sysu gym slots 乒乓球"))
    }

    @Test
    fun keepsUnsupportedReadsAsOther() {
        other("sysu jwxt course LN3015 --json")
        other("sysu jwxt notice --json")
        other("sysu jwxt room --json")
        other("sysu lms course 1005339 --json")
        other("sysu lms files 1005339 --json")
        other("sysu gym --json")
        other("sysu gym types --json")
        other("sysu netpay orders --json")
        other("sysu login --send-code")
        other("sysu gym slots --json")
        other("sysu")
    }

    @Test
    fun rejectsUnknownFlagsAndDanglingValues() {
        assertEquals(SysuInvocation.Other("sysu jwxt schedule --json --future"), SysuCommandParser.parse("sysu jwxt schedule --json --future"))
        assertEquals(SysuInvocation.Other("sysu jwxt schedule --week --json"), SysuCommandParser.parse("sysu jwxt schedule --week --json"))
        assertEquals(SysuInvocation.Other("sysu jwxt exams --all --json"), SysuCommandParser.parse("sysu jwxt exams --all --json"))
        assertEquals(SysuInvocation.Other("sysu lms courses --days 3 --json"), SysuCommandParser.parse("sysu lms courses --days 3 --json"))
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
