package io.github.whywood.deepdeep.plugin.feishu

import io.github.whywood.deepdeep.plugin.api.RunFinishedEvent
import io.github.whywood.deepdeep.plugin.api.SessionClosedEvent
import io.github.whywood.deepdeep.plugin.api.ToolCallAction
import io.github.whywood.deepdeep.plugin.api.ToolCallRequest
import io.github.whywood.deepdeep.plugin.api.ToolExecutionResult
import io.github.whywood.deepdeep.plugin.api.ToolResultAction
import io.github.whywood.deepdeep.plugin.api.ToolResultDecision
import io.github.whywood.deepdeep.plugin.api.textResult
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeishuToolBridgeTest {
    private val sessionId = "session-1"

    private fun request(callId: String, command: String) = ToolCallRequest(
        sessionId = sessionId,
        callId = callId,
        toolName = "bash",
        args = buildJsonObject { put("command", command) },
    )

    private fun result(callId: String, output: String, isError: Boolean = false) = ToolExecutionResult(
        sessionId = sessionId,
        callId = callId,
        toolName = "bash",
        result = textResult(output, isError = isError),
    )

    private fun runFinishedEvent() = object : RunFinishedEvent {
        override val sessionId: String = this@FeishuToolBridgeTest.sessionId
        override val runId: String = "run-1"
        override val error: String? = null
    }

    private fun sessionClosedEvent() = object : SessionClosedEvent {
        override val sessionId: String = this@FeishuToolBridgeTest.sessionId
    }

    private fun cardOf(decision: ToolResultDecision): JsonObject? =
        decision.result?.details?.get("deepdeep.card") as? JsonObject

    private fun cardPayload(decision: ToolResultDecision): JsonObject? =
        cardOf(decision)?.get("payload") as? JsonObject

    private fun ToolResultDecision.text(): String = result?.text.orEmpty()

    @Test
    fun supportedCommandAttachesCardAndReplacesModelResult() = runTest {
        val host = FakeFeishuHost()
        val hooks = FeishuToolBridge().hooks(host)
        val command = "LARKSUITE_CLI_NO_UPDATE_NOTIFIER=1 lark-cli im +chat-list --format json"
        assertEquals(
            ToolCallAction.CONTINUE,
            hooks.beforeToolCall(request("call-1", command)).action,
        )
        val decision = hooks.afterToolExecution(result("call-1", loadFixture("chats.json")))
        assertEquals(ToolResultAction.REPLACE, decision.action)
        val text = decision.text()
        assertTrue(text.contains("飞书聊天列表（共 3 个）"))
        assertTrue(text.contains("产品研发群"))
        assertTrue(text.endsWith("卡片 id：call-1"))
        assertFalse(text.contains("{\"ok\":true}"))
        val card = cardOf(decision)
        assertNotNull(card)
        assertEquals(FeishuCardTypes.CHAT_CARD, card?.getValue("customType")?.jsonPrimitive?.content)
        assertEquals("飞书聊天列表（共 3 个）", card?.getValue("title")?.jsonPrimitive?.content)
        val decoded = decodeFeishuCardPayload(cardPayload(decision)!!)
        assertNotNull(decoded)
        assertEquals(FeishuCardTypes.KIND_CHAT_LIST, decoded?.kind)
        assertEquals(3, decoded?.items?.size)
        assertTrue(host.posted.isEmpty())
    }

    @Test
    fun unsupportedCommandLeavesResultUntouched() = runTest {
        val host = FakeFeishuHost()
        val hooks = FeishuToolBridge().hooks(host)
        hooks.beforeToolCall(request("call-2", "lark-cli im +messages-send --chat-id oc_1 --text hi"))
        val decision = hooks.afterToolExecution(result("call-2", loadFixture("chats.json")))
        assertEquals(ToolResultAction.KEEP, decision.action)
        assertNull(cardOf(decision))
    }

    @Test
    fun pipesAndRedirectionAreSkipped() = runTest {
        val host = FakeFeishuHost()
        val hooks = FeishuToolBridge().hooks(host)
        hooks.beforeToolCall(request("call-3", "lark-cli im +chat-list | head -20"))
        val decision = hooks.afterToolExecution(result("call-3", loadFixture("chats.json")))
        assertEquals(ToolResultAction.KEEP, decision.action)
        assertNull(cardOf(decision))
    }

    @Test
    fun failedToolResultIsNotReplaced() = runTest {
        val host = FakeFeishuHost()
        val hooks = FeishuToolBridge().hooks(host)
        hooks.beforeToolCall(request("call-4", "lark-cli im +chat-list"))
        val decision = hooks.afterToolExecution(
            result("call-4", """{"ok":true,"data":{"chats":[]}}""", isError = true),
        )
        assertEquals(ToolResultAction.KEEP, decision.action)
        assertNull(cardOf(decision))
    }

    @Test
    fun errorEnvelopeIsNotReplaced() = runTest {
        val host = FakeFeishuHost()
        val hooks = FeishuToolBridge().hooks(host)
        hooks.beforeToolCall(request("call-5", "lark-cli im +chat-list"))
        val output = """{"ok":false,"identity":"user","error":{"type":"authentication","message":"bad token"}}"""
        val decision = hooks.afterToolExecution(result("call-5", output))
        assertEquals(ToolResultAction.KEEP, decision.action)
        assertNull(cardOf(decision))
    }

    @Test
    fun garbageOutputIsNotReplaced() = runTest {
        val host = FakeFeishuHost()
        val hooks = FeishuToolBridge().hooks(host)
        hooks.beforeToolCall(request("call-6", "lark-cli im +chat-list"))
        val decision = hooks.afterToolExecution(result("call-6", "Error: command not found"))
        assertEquals(ToolResultAction.KEEP, decision.action)
        assertNull(cardOf(decision))
    }

    @Test
    fun duplicateCallIdIsHandledOnlyOnce() = runTest {
        val host = FakeFeishuHost()
        val hooks = FeishuToolBridge().hooks(host)
        val output = loadFixture("chats.json")
        hooks.beforeToolCall(request("call-8", "lark-cli im +chat-list"))
        assertEquals(ToolResultAction.REPLACE, hooks.afterToolExecution(result("call-8", output)).action)
        assertEquals(ToolResultAction.KEEP, hooks.afterToolExecution(result("call-8", output)).action)
    }

    @Test
    fun withoutBeforeCallNoCardIsAttached() = runTest {
        val host = FakeFeishuHost()
        val hooks = FeishuToolBridge().hooks(host)
        val decision = hooks.afterToolExecution(result("call-9", loadFixture("chats.json")))
        assertEquals(ToolResultAction.KEEP, decision.action)
        assertNull(cardOf(decision))
    }

    @Test
    fun runFinishedClearsPendingCommands() = runTest {
        val host = FakeFeishuHost()
        val hooks = FeishuToolBridge().hooks(host)
        hooks.beforeToolCall(request("call-10", "lark-cli im +chat-list"))
        hooks.runFinished(runFinishedEvent())
        val decision = hooks.afterToolExecution(result("call-10", loadFixture("chats.json")))
        assertEquals(ToolResultAction.KEEP, decision.action)
        assertNull(cardOf(decision))
    }

    @Test
    fun sessionClosedClearsPendingCommands() = runTest {
        val host = FakeFeishuHost()
        val hooks = FeishuToolBridge().hooks(host)
        hooks.beforeToolCall(request("call-11", "lark-cli im +chat-list"))
        hooks.sessionClosed(sessionClosedEvent())
        val decision = hooks.afterToolExecution(result("call-11", loadFixture("chats.json")))
        assertEquals(ToolResultAction.KEEP, decision.action)
        assertNull(cardOf(decision))
    }

    @Test
    fun oversizedOutputIsNotParsed() = runTest {
        val host = FakeFeishuHost()
        val hooks = FeishuToolBridge().hooks(host)
        hooks.beforeToolCall(request("call-12", "lark-cli im +chat-list"))
        val huge = loadFixture("chats.json") + "x".repeat(300 * 1024)
        val decision = hooks.afterToolExecution(result("call-12", huge))
        assertEquals(ToolResultAction.KEEP, decision.action)
        assertNull(cardOf(decision))
    }

    @Test
    fun commandWithRedirectionIsSkipped() = runTest {
        val host = FakeFeishuHost()
        val hooks = FeishuToolBridge().hooks(host)
        hooks.beforeToolCall(request("call-13", "lark-cli im +chat-list 2>&1"))
        val decision = hooks.afterToolExecution(result("call-13", loadFixture("chats.json")))
        assertEquals(ToolResultAction.KEEP, decision.action)
        assertNull(cardOf(decision))
    }

    @Test
    fun agendaCommandProducesAgendaCard() = runTest {
        val host = FakeFeishuHost()
        val hooks = FeishuToolBridge().hooks(host)
        hooks.beforeToolCall(request("call-14", "lark-cli calendar +agenda --start 2026-11-16 --end 2026-11-17"))
        val decision = hooks.afterToolExecution(result("call-14", loadFixture("agenda.json")))
        assertEquals(ToolResultAction.REPLACE, decision.action)
        val payload = cardPayload(decision)
        assertNotNull(payload)
        assertTrue(payload.toString().contains("\"agenda\""))
        assertTrue(decision.text().contains("飞书日程"))
    }

    @Test
    fun docsSearchAndTasksProduceCards() = runTest {
        val host = FakeFeishuHost()
        val bridge = FeishuToolBridge()
        val hooks = bridge.hooks(host)
        hooks.beforeToolCall(request("call-15", "lark-cli drive +search --query 周报"))
        val docs = hooks.afterToolExecution(result("call-15", loadFixture("docs-search.json")))
        assertEquals(ToolResultAction.REPLACE, docs.action)
        hooks.beforeToolCall(request("call-16", "lark-cli task +get-my-tasks --complete=false"))
        val tasks = hooks.afterToolExecution(result("call-16", loadFixture("tasks.json")))
        assertEquals(ToolResultAction.REPLACE, tasks.action)
        val kinds = listOf(docs, tasks).mapNotNull { decision ->
            decodeFeishuCardPayload(cardPayload(decision) ?: return@mapNotNull null)?.kind
        }
        assertEquals(listOf(FeishuCardTypes.KIND_DOCS_SEARCH, FeishuCardTypes.KIND_TASKS), kinds)
    }

    @Test
    fun directBashCommandWithoutCliIsIgnored() = runTest {
        val host = FakeFeishuHost()
        val hooks = FeishuToolBridge().hooks(host)
        hooks.beforeToolCall(request("call-17", "ls -la /tmp"))
        val decision = hooks.afterToolExecution(result("call-17", loadFixture("chats.json")))
        assertEquals(ToolResultAction.KEEP, decision.action)
        assertNull(cardOf(decision))
    }
}

class FeishuNoticeStrippingTest {
    @Test
    fun cliNoticeIsStrippedFromObservationAndStillProducesCard() = runTest {
        val host = FakeFeishuHost()
        val hooks = FeishuToolBridge().hooks(host)
        hooks.beforeToolCall(
            ToolCallRequest(
                sessionId = "session-1",
                callId = "notice-1",
                toolName = "bash",
                args = buildJsonObject { put("command", "lark-cli im +chat-list --page-size 50") },
            ),
        )
        val decision = hooks.afterToolExecution(
            ToolExecutionResult(
                sessionId = "session-1",
                callId = "notice-1",
                toolName = "bash",
                result = textResult(loadFixture("chats-with-notice.json")),
            ),
        )
        assertEquals(ToolResultAction.REPLACE, decision.action)
        val text = decision.result?.text.orEmpty()
        assertTrue(text.contains("飞书聊天列表"))
        assertFalse(text.contains("lark-cli update"))
        assertFalse(text.contains("_notice"))
        val card = decision.result?.details?.get("deepdeep.card") as? JsonObject
        val payload = card?.get("payload")?.jsonObject
        assertNotNull(payload)
        val decoded = decodeFeishuCardPayload(payload!!)
        assertEquals(FeishuCardTypes.KIND_CHAT_LIST, decoded?.kind)
    }
}
