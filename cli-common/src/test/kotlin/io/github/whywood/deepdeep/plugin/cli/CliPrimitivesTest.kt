package io.github.whywood.superpi.plugin.cli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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

