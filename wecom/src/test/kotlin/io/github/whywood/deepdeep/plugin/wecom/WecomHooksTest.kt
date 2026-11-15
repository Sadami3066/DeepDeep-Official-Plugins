package io.github.whywood.deepdeep.plugin.wecom

import io.github.whywood.deepdeep.plugin.api.PluginHooks
import io.github.whywood.deepdeep.plugin.api.RunFinishedEvent
import io.github.whywood.deepdeep.plugin.api.SessionClosedEvent
import io.github.whywood.deepdeep.plugin.api.ToolCallAction
import io.github.whywood.deepdeep.plugin.api.ToolCallRequest
import io.github.whywood.deepdeep.plugin.api.ToolExecutionResult
import io.github.whywood.deepdeep.plugin.api.ToolResultAction
import io.github.whywood.deepdeep.plugin.api.textResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WecomHooksTest {
    private val posted = mutableListOf<PostedCard>()
    private val tracker = WecomCallTracker()
    private val hooks: PluginHooks = WecomHooks(tracker) { sessionId, kind, payload, observation ->
        posted += PostedCard(sessionId, kind, payload, observation)
    }

    @Test
    fun bashBashResultIsReplacedWithObservationAndCard() = runBlocking {
        hooks.beforeToolCall(bashCall("call-1", "wecom-cli chat groups list --begin-time '2026-11-08 00:00:00'"))
        val decision = hooks.afterToolExecution(result("call-1", fixture("chat-groups.json")))
        assertEquals(ToolResultAction.REPLACE, decision.action)
        val text = decision.result!!.text!!
        assertTrue(text.contains("产品讨论群"))
        assertTrue(text.contains("wrOgQhDgAAmockchatid001"))
        assertFalse(text.contains("卡片"))
        assertEquals(1, posted.size)
        assertEquals(WecomCommandKind.CHAT_GROUPS, posted[0].kind)
        assertEquals("wecom.chat.groups", posted[0].kind.customType)
        assertEquals(2, posted[0].payload.count)
    }

    @Test
    fun unsupportedCommandsKeepResultAndPostNoCard() = runBlocking {
        hooks.beforeToolCall(bashCall("call-1", "wecom-cli chat groups list | head -20"))
        val decision = hooks.afterToolExecution(result("call-1", fixture("chat-groups.json")))
        assertEquals(ToolResultAction.KEEP, decision.action)
        assertTrue(posted.isEmpty())
    }

    @Test
    fun nonBashToolsAreIgnored() = runBlocking {
        hooks.beforeToolCall(
            ToolCallRequest(
                sessionId = SESSION,
                callId = "call-1",
                toolName = "read",
                args = buildJsonObject { put("path", "/tmp/a.txt") },
            ),
        )
        assertEquals(0, tracker.size())
        val decision = hooks.afterToolExecution(result("call-1", fixture("chat-groups.json")))
        assertEquals(ToolResultAction.KEEP, decision.action)
    }

    @Test
    fun secondAfterForSameCallIdIsIgnored() = runBlocking {
        hooks.beforeToolCall(bashCall("call-1", "wecom-cli todo list"))
        val first = hooks.afterToolExecution(result("call-1", fixture("todo-list.json")))
        val second = hooks.afterToolExecution(result("call-1", fixture("todo-list.json")))
        assertEquals(ToolResultAction.REPLACE, first.action)
        assertEquals(ToolResultAction.KEEP, second.action)
        assertEquals(1, posted.size)
    }

    @Test
    fun unauthorizedFailureBecomesActionableObservation() = runBlocking {
        hooks.beforeToolCall(bashCall("call-1", "wecom-cli chat groups list --begin-time '2026-11-08 00:00:00'"))
        val decision = hooks.afterToolExecution(
            result("call-1", fixture("error-unauthorized.json"), isError = true),
        )
        assertEquals(ToolResultAction.REPLACE, decision.action)
        assertEquals(UNAUTHORIZED_OBSERVATION, decision.result!!.text)
        assertTrue(decision.result!!.isError)
        assertTrue(posted.isEmpty())
    }

    @Test
    fun nonAuthFailureKeepsRawResult() = runBlocking {
        hooks.beforeToolCall(bashCall("call-1", "wecom-cli todo list"))
        val networkError = """{"error":{"type":"NetworkError","code":893101,"message":"Request failed"}}"""
        val decision = hooks.afterToolExecution(result("call-1", networkError, isError = true))
        assertEquals(ToolResultAction.KEEP, decision.action)
        assertTrue(posted.isEmpty())
    }

    @Test
    fun malformedJsonKeepsRawResult() = runBlocking {
        hooks.beforeToolCall(bashCall("call-1", "wecom-cli todo list"))
        val decision = hooks.afterToolExecution(result("call-1", "not json at all"))
        assertEquals(ToolResultAction.KEEP, decision.action)
        assertEquals(ToolResultAction.KEEP, decision.action)
        assertNull(decision.result)
    }

    @Test
    fun wrongPayloadShapeForCommandKeepsRawResult() = runBlocking {
        hooks.beforeToolCall(bashCall("call-1", "wecom-cli todo list"))
        val decision = hooks.afterToolExecution(result("call-1", fixture("chat-groups.json")))
        assertEquals(ToolResultAction.KEEP, decision.action)
        assertTrue(posted.isEmpty())
    }

    @Test
    fun beforeToolCallNeverBlocks() = runBlocking {
        val decision = hooks.beforeToolCall(bashCall("call-1", "wecom-cli todo list"))
        assertEquals(ToolCallAction.CONTINUE, decision.action)
        val comment = hooks.beforeToolCall(
            ToolCallRequest(
                sessionId = SESSION,
                callId = "call-2",
                toolName = "bash",
                args = buildJsonObject { put("command", "") },
            ),
        )
        assertEquals(ToolCallAction.CONTINUE, comment.action)
    }

    @Test
    fun runFinishedAndSessionClosedClearTrackedCalls() = runBlocking {
        hooks.beforeToolCall(bashCall("call-1", "wecom-cli todo list"))
        hooks.runFinished(
            object : RunFinishedEvent {
                override val sessionId: String = SESSION
                override val runId: String = "run-1"
                override val error: String? = null
            },
        )
        assertEquals(ToolResultAction.KEEP, hooks.afterToolExecution(result("call-1", fixture("todo-list.json"))).action)

        hooks.beforeToolCall(bashCall("call-2", "wecom-cli todo list"))
        hooks.sessionClosed(
            object : SessionClosedEvent {
                override val sessionId: String = SESSION
            },
        )
        assertEquals(ToolResultAction.KEEP, hooks.afterToolExecution(result("call-2", fixture("todo-list.json"))).action)
    }

    @Test
    fun cardPostFailureDoesNotBreakModelResult() = runBlocking {
        val failing = WecomHooks(tracker) { _, _, _, _ -> error("chat unavailable") }
        failing.beforeToolCall(bashCall("call-1", "wecom-cli todo list"))
        val decision = failing.afterToolExecution(result("call-1", fixture("todo-list.json")))
        assertEquals(ToolResultAction.REPLACE, decision.action)
        assertTrue(decision.result!!.text!!.contains("整理接口文档"))
    }

    private fun bashCall(callId: String, command: String): ToolCallRequest = ToolCallRequest(
        sessionId = SESSION,
        callId = callId,
        toolName = BASH_TOOL_NAME,
        args = buildJsonObject { put("command", command) },
    )

    private fun result(callId: String, text: String, isError: Boolean = false): ToolExecutionResult =
        ToolExecutionResult(
            sessionId = SESSION,
            callId = callId,
            toolName = BASH_TOOL_NAME,
            result = textResult(text, isError = isError),
        )

    private fun fixture(name: String): String =
        javaClass.getResourceAsStream("/fixtures/cli/$name")?.bufferedReader()?.use { it.readText() }
            ?: error("missing fixture $name")

    private data class PostedCard(
        val sessionId: String,
        val kind: WecomCommandKind,
        val payload: WecomCardPayload,
        val observation: String,
    )

    private companion object {
        const val SESSION = "session-1"
    }
}
