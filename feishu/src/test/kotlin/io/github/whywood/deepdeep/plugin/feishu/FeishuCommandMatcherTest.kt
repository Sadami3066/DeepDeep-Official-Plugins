package io.github.whywood.deepdeep.plugin.feishu

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeishuCommandMatcherTest {
    private val env = "LARKSUITE_CLI_NO_UPDATE_NOTIFIER=1 LARKSUITE_CLI_NO_SKILLS_NOTIFIER=1"

    @Test
    fun matchesChatListWithEnvPrefixAndExplicitJson() {
        val command = parseLarkCliCommand("$env lark-cli im +chat-list --page-size 50 --format json")
        assertNotNull(command)
        assertEquals(FeishuCommandKind.CHAT_LIST, command?.kind)
    }

    @Test
    fun matchesInstalledBinaryPath() {
        val command = parseLarkCliCommand("/usr/local/bin/lark-cli calendar +agenda --start 2026-11-16 --end 2026-11-17")
        assertEquals(FeishuCommandKind.CALENDAR_AGENDA, command?.kind)
        assertEquals("2026-11-16", command?.start)
        assertEquals("2026-11-17", command?.end)
    }

    @Test
    fun matchesQuotedMessageListFlags() {
        val command = parseLarkCliCommand(
            """lark-cli im +chat-messages-list --chat-id 'oc_5ce6d572455d361153b7cb51da133945' --page-size 50""",
        )
        assertEquals(FeishuCommandKind.CHAT_MESSAGES, command?.kind)
        assertEquals("oc_5ce6d572455d361153b7cb51da133945", command?.chatId)
    }

    @Test
    fun matchesEqualsFormFlags() {
        val command = parseLarkCliCommand("lark-cli drive +search --query=周报 --format=json")
        assertEquals(FeishuCommandKind.DRIVE_SEARCH, command?.kind)
        assertEquals("周报", command?.query)
    }

    @Test
    fun matchesDocsFetchAndTasks() {
        assertEquals(
            FeishuCommandKind.DOCS_FETCH,
            parseLarkCliCommand("lark-cli docs +fetch --doc doccnAbc123 --doc-format markdown")?.kind,
        )
        assertEquals(
            FeishuCommandKind.TASKS,
            parseLarkCliCommand("lark-cli task +get-my-tasks --complete=false")?.kind,
        )
    }

    @Test
    fun rejectsPipesRedirectionAndCommandChains() {
        assertNull(parseLarkCliCommand("lark-cli im +chat-list | head -20"))
        assertNull(parseLarkCliCommand("lark-cli im +chat-list > /tmp/out.json"))
        assertNull(parseLarkCliCommand("lark-cli im +chat-list 2>&1"))
        assertNull(parseLarkCliCommand("lark-cli im +chat-list && lark-cli task +get-my-tasks"))
        assertNull(parseLarkCliCommand("lark-cli im +chat-list; lark-cli task +get-my-tasks"))
        assertNull(parseLarkCliCommand("echo \$(lark-cli im +chat-list)"))
    }

    @Test
    fun rejectsUnsupportedOrUnsafeVariants() {
        assertNull(parseLarkCliCommand("lark-cli im +messages-send --chat-id oc_1 --text hi"))
        assertNull(parseLarkCliCommand("lark-cli im +chat-list --dry-run"))
        assertNull(parseLarkCliCommand("lark-cli im +chat-list --jq .data"))
        assertNull(parseLarkCliCommand("lark-cli im +chat-list --format table"))
        assertNull(parseLarkCliCommand("lark-cli im +chat-list --concise"))
        assertNull(parseLarkCliCommand("lark-cli im +chat-messages-list --page-size 20"))
        assertNull(parseLarkCliCommand("lark-cli docs +fetch --doc-format markdown"))
        assertNull(parseLarkCliCommand("curl https://example.com"))
    }
}

class FeishuCliOutputTest {
    @Test
    fun extractsEnvelopeFromRealCliOutput() {
        val envelope = FeishuCliOutput.findEnvelope(loadFixture("chats.json"))
        assertNotNull(envelope)
        assertTrue(FeishuCliOutput.isSuccess(envelope!!))
        val data = envelope["data"] as JsonObject
        assertEquals(3, data["chats"]!!.jsonArray.size)
    }

    @Test
    fun extractsEnvelopeWithAnsiGarbageAndDotenvPrefix() {
        val noisy = "\u001B[32mINFO\u001B[0m some log line\nLARKSUITE_CLI_NO_UPDATE_NOTIFIER=1\n" +
            loadFixture("chats.json") + "\n[exit: 0]"
        val envelope = FeishuCliOutput.findEnvelope(noisy)
        assertNotNull(envelope)
        assertTrue(FeishuCliOutput.isSuccess(envelope!!))
    }

    @Test
    fun extractsEnvelopeWhenContentContainsBraces() {
        val body = """{"ok":true,"identity":"user","data":{"messages":[{"message_id":"om_1",""" +
            """"content":"评价 {不是 JSON} 也要能解析","msg_type":"text"}]}}"""
        val envelope = FeishuCliOutput.findEnvelope("command output:\n$body\n[exit: 0]")
        assertNotNull(envelope)
        val data = envelope!!["data"] as JsonObject
        assertEquals(1, data["messages"]!!.jsonArray.size)
    }

    @Test
    fun stripsNoticeField() {
        val envelope = FeishuCliOutput.findEnvelope(loadFixture("chats-with-notice.json"))
        assertNotNull(envelope)
        assertNotNull(envelope!!["_notice"])
        val stripped = FeishuCliOutput.stripNotice(envelope)
        assertNull(stripped["_notice"])
        assertTrue(FeishuCliOutput.isSuccess(stripped))
    }

    @Test
    fun malformedOrErrorOutputIsIgnored() {
        assertNull(FeishuCliOutput.findEnvelope(""))
        assertNull(FeishuCliOutput.findEnvelope("not json at all"))
        assertNull(FeishuCliOutput.findEnvelope("""{"ok":"""))
        val error = FeishuCliOutput.findEnvelope(
            """{"ok":false,"identity":"user","error":{"type":"authentication","message":"invalid token"}}""",
        )
        assertNotNull(error)
        assertFalse(FeishuCliOutput.isSuccess(error!!))
    }
}

class FeishuBridgeDiagnosticsTest {
    @Test
    fun diagnosticSummaryKeepsShapeAndMasksFlagValues() {
        val summary = larkCliDiagnosticSummary(
            "LARKSUITE_CLI_NO_UPDATE_NOTIFIER=1 lark-cli im +chat-list --page-size 50 --chat-id oc_5ce6d572455d361153b7cb51da133945",
        )
        assertTrue(summary.contains("cmd=cli"))
        assertTrue(summary.contains("domain=im"))
        assertTrue(summary.contains("shortcut=+chat-list"))
        assertTrue(summary.contains("--page-size"))
        assertFalse(summary.contains("oc_5ce6d572455d361153b7cb51da133945"))
        assertFalse(summary.contains("--page-size 50"))
        assertFalse(summary.contains("LARKSUITE_CLI_NO_UPDATE_NOTIFIER"))
    }

    @Test
    fun diagnosticSummaryDescribesNonCliCommands() {
        assertEquals("cmd=empty", larkCliDiagnosticSummary(""))
        assertTrue(larkCliDiagnosticSummary("ls -la /tmp").startsWith("cmd=not-cli"))
    }
}
