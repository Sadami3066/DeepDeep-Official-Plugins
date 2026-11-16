package io.github.whywood.deepdeep.plugin.cli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CliCommandLineTest {
    @Test
    fun tokenizesQuotesAndEscapes() {
        assertEquals(
            listOf("lark-cli", "im", "+chat-messages-list", "--chat-id", "oc_5ce6"),
            CliCommandLine.tokenize("lark-cli im +chat-messages-list --chat-id 'oc_5ce6'"),
        )
        assertEquals(
            listOf("--query=周报", "--format=json"),
            CliCommandLine.tokenize("""--query=周报 --format=json"""),
        )
        assertEquals(listOf("a", "b c"), CliCommandLine.tokenize("""a "b c""""))
        assertEquals(listOf("a b"), CliCommandLine.tokenize("""a\ b"""))
    }

    @Test
    fun detectsShellOperators() {
        assertFalse(CliCommandLine.hasShellOperators("lark-cli im +chat-list --page-size 50"))
        assertTrue(CliCommandLine.hasShellOperators("lark-cli im +chat-list | head"))
        assertTrue(CliCommandLine.hasShellOperators("lark-cli im +chat-list > out.json"))
        assertTrue(CliCommandLine.hasShellOperators("lark-cli im +chat-list && echo hi"))
        assertTrue(CliCommandLine.hasShellOperators("echo \$(lark-cli im +chat-list)"))
        assertTrue(CliCommandLine.hasShellOperators("lark-cli im +chat-list; echo"))
        assertTrue(CliCommandLine.hasShellOperators("lark-cli im +chat-list\nhead"))
    }

    @Test
    fun ignoresShellOperatorsInsideQuotes() {
        assertFalse(CliCommandLine.hasShellOperators("lark-cli mail +send --body '<p>hi there</p>'"))
        assertFalse(CliCommandLine.hasShellOperators("lark-cli mail +send --body \"a | b; c > d\""))
        assertTrue(CliCommandLine.hasShellOperators("lark-cli mail +send --body \"$(whoami)\""))
        assertFalse(CliCommandLine.hasShellOperators("lark-cli im +chat-list 'unterminated"))
        assertFalse(CliCommandLine.hasShellOperators("lark-cli im +chat-list --query 修复'bug'"))
    }

    @Test
    fun findsBinaryAfterEnvironmentAssignments() {
        val tokens = CliCommandLine.tokenize(
            "LARKSUITE_CLI_NO_UPDATE_NOTIFIER=1 LARKSUITE_CLI_NO_SKILLS_NOTIFIER=1 lark-cli im +chat-list",
        )
        assertEquals(2, CliCommandLine.envPrefixLength(tokens))
        assertTrue(CliCommandLine.isBinary(tokens[2], "lark-cli"))
        assertTrue(CliCommandLine.isBinary("/usr/local/bin/lark-cli", "lark-cli"))
        assertFalse(CliCommandLine.isBinary("lark-cli-other", "lark-cli"))
    }

    @Test
    fun readsFlagFormsAndRejectsFlagValuesWithPrefix() {
        val flags = CliCommandLine.readFlags(
            listOf("--format", "json", "--query=周报", "--complete=false", "--verbose"),
        )
        assertEquals("json", flags["--format"])
        assertEquals("周报", flags["--query"])
        assertEquals("false", flags["--complete"])
        assertEquals("", flags["--verbose"])
    }

    @Test
    fun diagnosticSummaryMaskFlagValuesAndEnv() {
        val summary = CliCommandLine.diagnosticSummary(
            "LARKSUITE_CLI_NO_UPDATE_NOTIFIER=1 lark-cli im +chat-list --page-size 50 --chat-id oc_5ce6",
            "lark-cli",
        )
        assertTrue(summary.contains("cmd=cli"))
        assertTrue(summary.contains("domain=im"))
        assertTrue(summary.contains("shortcut=+chat-list"))
        assertTrue(summary.contains("--page-size"))
        assertFalse(summary.contains("oc_5ce6"))
        assertFalse(summary.contains("--page-size 50"))
        assertFalse(summary.contains("LARKSUITE_CLI_NO_UPDATE_NOTIFIER"))
    }

    @Test
    fun diagnosticSummaryDescribesNonCliCommands() {
        assertEquals("cmd=empty", CliCommandLine.diagnosticSummary("", "lark-cli"))
        assertEquals("cmd=empty", CliCommandLine.diagnosticSummary("   ", "lark-cli"))
        assertTrue(CliCommandLine.diagnosticSummary("ls -la /tmp", "lark-cli").startsWith("cmd=not-cli"))
    }

    @Test
    fun diagnosticSummaryHandlesEnvOnlyCommand() {
        assertEquals("cmd=no-cli-token", CliCommandLine.diagnosticSummary("FOO=1 BAR=2", "lark-cli"))
    }
}

class CliTextTest {
    @Test
    fun stripsAnsiAndSanitizesHtml() {
        val raw = "\u001B[32m\u001B[0m <p>Hello&nbsp;<b>world</b></p>"
        assertEquals("Hello world", CliText.sanitize(raw))
        assertEquals("&<>", CliText.decodeEntities("&amp;&lt;&gt;"))
        assertFalse(CliText.sanitize("<title>T</title>").contains("<"))
    }

    @Test
    fun cleansDocumentContentLineByLine() {
        val cleaned = CliText.cleanDocContent("<title>T</title><p>A &amp; B</p><h1>H</h1>")
        assertEquals("T A & B", cleaned.lineSequence().first())
        assertEquals(listOf("T A & B", "H"), cleaned.lines())
    }

    @Test
    fun clipsWithEllipsis() {
        assertEquals("abc", CliText.clip("abc", 10))
        assertEquals("abc…", CliText.clip("abcdef", 3))
        assertEquals("abcdef", CliText.multiLine("abcdef", 10))
    }

    @Test
    fun detectsDotenvLines() {
        assertTrue(CliText.isDotenvLine("LARKSUITE_CLI_NO_UPDATE_NOTIFIER=1"))
        assertFalse(CliText.isDotenvLine("some log line"))
    }

    @Test
    fun stripsAnsiAndExitMarker() {
        val raw = "\u001B[2m  file.pdf\u001B[0m\n\n[exit: 0]"
        assertEquals("  file.pdf", CliText.stripHostNotices(CliText.stripAnsi(raw)).trimEnd())
    }

    @Test
    fun preservesFirstLineIndentWhenStrippingHostNotices() {
        val raw = "  LN336E_Lec1.pdf\n  LN336E_Lec2.pdf\n\n[exit: 0]"
        assertEquals("  LN336E_Lec1.pdf\n  LN336E_Lec2.pdf", CliText.stripHostNotices(raw))
    }

    @Test
    fun stripsTruncationNoticesAndTimeoutLines() {
        val raw = "tail line\n\n[Showing lines 10-20 of 40]\n\n[Full output: /tmp/log]\n\n[exit: 1]"
        assertEquals("tail line", CliText.stripHostNotices(raw))
        assertEquals("out", CliText.stripHostNotices("out\n\nCommand terminated without an exit code"))
    }

    @Test
    fun leavesInnerBracketsAlone() {
        val raw = "line\n[1]\nline2"
        assertEquals(raw, CliText.stripHostNotices(raw))
    }
}

class CliTimeTest {
    @Test
    fun formatsIsoTimes() {
        assertEquals("11-15 09:40", CliTime.shortDateTime("2025-11-15T09:40:00+08:00"))
        assertEquals("2025-11-15 09:40", CliTime.fullDateTime("2025-11-15T09:40:00+08:00"))
        assertEquals("11-15 09:40", CliTime.shortDateTime("2025-11-15 09:40:00"))
        assertEquals("2025-11-15 09:40", CliTime.fullDateTime("2025-11-15 09:40"))
    }

    @Test
    fun returnsNullForBlankInput() {
        assertNull(CliTime.shortDateTime(null))
        assertNull(CliTime.shortDateTime("  "))
        assertNull(CliTime.fullDateTime(""))
    }

    @Test
    fun formatsEpochSeconds() {
        val formatted = CliTime.shortEpochSeconds("1763170800")
        assertTrue(formatted!!.matches(Regex("""\d{2}-\d{2} \d{2}:\d{2}""")))
    }
}
