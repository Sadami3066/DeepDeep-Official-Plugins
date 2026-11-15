package io.github.whywood.deepdeep.plugin.wecom

import io.github.whywood.deepdeep.plugin.api.RunFinishedEvent
import io.github.whywood.deepdeep.plugin.api.SessionClosedEvent
import io.github.whywood.deepdeep.plugin.api.ToolCallAction
import io.github.whywood.deepdeep.plugin.api.ToolCallRequest
import io.github.whywood.deepdeep.plugin.api.ToolExecutionResult
import io.github.whywood.deepdeep.plugin.api.ToolResultAction
import io.github.whywood.deepdeep.plugin.api.textResult
import io.github.whywood.deepdeep.plugin.cli.card.CliCardCodec
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WecomHooksTest {
    private val bridge = WecomToolBridge()

    @Test
    fun supportedGroupsCommandReplacesResultAndPostsCard() = runBlocking {
        val host = FakeWecomHost()
        val hooks = bridge.hooks(host)
        hooks.beforeToolCall(request("call-1", "wecom-cli chat groups list --begin-time '2026-11-08 00:00:00'"))
        val decision = hooks.afterToolExecution(result("call-1", wecomFixture("chat-groups.json")))
        assertEquals(ToolResultAction.REPLACE, decision.action)
        val text = decision.result!!.text!!
        assertTrue(text.contains("产品讨论群"))
        assertTrue(text.contains("wrOgQhDgAAmockchatid001"))
        assertTrue(text.contains("2026-11-15 10:24:31"))
        assertTrue(text.contains("以卡片展示"))
        assertFalse(text.contains("\"chats\""))
        assertEquals(1, host.posted.size)
        val posted = host.posted.single()
        assertEquals("wecom.chat.groups", posted.customType)
        val card = decode(posted.payload)
        assertNotNull(card)
        assertEquals("wecom.chat.groups", card!!.kind)
        assertEquals(2, card.total)
        assertEquals("42 条", card.items.first().meta)
        assertEquals("产品讨论群", card.items.first().title)
    }

    @Test
    fun everySupportedCommandUsesItsOwnCardSlot() = runBlocking {
        val host = FakeWecomHost()
        val hooks = bridge.hooks(host)
        val cases = listOf(
            Triple("wecom-cli chat groups list", WecomCommandKind.CHAT_GROUPS, "chat-groups.json"),
            Triple("wecom-cli chat messages list --chat-id wrOgQhDgAA001", WecomCommandKind.CHAT_MESSAGES, "chat-messages.json"),
            Triple("wecom-cli doc search --keywords 接口文档", WecomCommandKind.DOC_SEARCH, "doc-search.json"),
            Triple("wecom-cli doc contents get --docid DOC_MOCK_001", WecomCommandKind.DOC_CONTENT, "doc-content.json"),
            Triple("wecom-cli calendar schedules list", WecomCommandKind.CALENDAR_SCHEDULES, "calendar-schedules.json"),
            Triple("wecom-cli todo list", WecomCommandKind.TODO_LIST, "todo-list.json"),
            Triple("wecom-cli contact users search --keywords 张三", WecomCommandKind.CONTACT_USERS, "contact-users.json"),
        )
        cases.forEachIndexed { index, (command, kind, fixture) ->
            val callId = "slot-$index"
            assertEquals(ToolCallAction.CONTINUE, hooks.beforeToolCall(request(callId, command)).action)
            assertEquals(ToolResultAction.REPLACE, hooks.afterToolExecution(result(callId, wecomFixture(fixture))).action)
            assertEquals(kind.customType, host.posted.last().customType)
            assertEquals(kind.customType, decode(host.posted.last().payload)?.kind)
        }
        assertEquals(cases.size, host.posted.size)
    }

    @Test
    fun docContentCardKeepsHeaderTrailingSummaryAndOpenAction() = runBlocking {
        val host = FakeWecomHost()
        val hooks = bridge.hooks(host)
        hooks.beforeToolCall(request("call-1", "wecom-cli doc contents get --docid DOC_MOCK_001"))
        hooks.afterToolExecution(result("call-1", wecomFixture("doc-content.json")))
        val card = decode(host.posted.single().payload)!!
        assertEquals("企业微信接入方案", card.title)
        assertEquals("版本 12", card.trailing)
        assertEquals("DOC_MOCK_001", card.context)
        assertEquals("https://doc.weixin.qq.com/doc/MOCK001", card.openUrl)
        assertTrue(card.summary!!.isNotBlank())
    }

    @Test
    fun rawValuesAreLocalizedInCardRows() = runBlocking {
        val host = FakeWecomHost()
        val hooks = bridge.hooks(host)
        hooks.beforeToolCall(request("call-1", "wecom-cli todo list"))
        hooks.afterToolExecution(result("call-1", wecomFixture("todo-list.json")))
        val todoCard = decode(host.posted.single().payload)!!
        assertEquals("进行中", todoCard.items.first().meta)
        hooks.beforeToolCall(request("call-2", "wecom-cli doc search --keywords 方案"))
        hooks.afterToolExecution(result("call-2", wecomFixture("doc-search.json")))
        val docsCard = decode(host.posted.last().payload)!!
        assertEquals("文档", docsCard.items.first().meta)
    }

    @Test
    fun unsupportedCommandsKeepResultAndPostNoCard() = runBlocking {
        val host = FakeWecomHost()
        val hooks = bridge.hooks(host)
        hooks.beforeToolCall(request("call-1", "wecom-cli chat groups list | head -20"))
        val decision = hooks.afterToolExecution(result("call-1", wecomFixture("chat-groups.json")))
        assertEquals(ToolResultAction.KEEP, decision.action)
        assertTrue(host.posted.isEmpty())
    }

    @Test
    fun nonBashToolsAreIgnored() = runBlocking {
        val host = FakeWecomHost()
        val hooks = bridge.hooks(host)
        hooks.beforeToolCall(
            ToolCallRequest(
                sessionId = SESSION,
                callId = "call-1",
                toolName = "read",
                args = buildJsonObject { put("path", "/tmp/a.txt") },
            ),
        )
        assertEquals(ToolResultAction.KEEP, hooks.afterToolExecution(result("call-1", wecomFixture("chat-groups.json"))).action)
        assertTrue(host.posted.isEmpty())
    }

    @Test
    fun secondAfterForSameCallIdIsIgnored() = runBlocking {
        val host = FakeWecomHost()
        val hooks = bridge.hooks(host)
        hooks.beforeToolCall(request("call-1", "wecom-cli todo list"))
        val first = hooks.afterToolExecution(result("call-1", wecomFixture("todo-list.json")))
        val second = hooks.afterToolExecution(result("call-1", wecomFixture("todo-list.json")))
        assertEquals(ToolResultAction.REPLACE, first.action)
        assertEquals(ToolResultAction.KEEP, second.action)
        assertEquals(1, host.posted.size)
    }

    @Test
    fun unauthorizedFailureBecomesActionableObservation() = runBlocking {
        val host = FakeWecomHost()
        val hooks = bridge.hooks(host)
        hooks.beforeToolCall(request("call-1", "wecom-cli chat groups list --begin-time '2026-11-08 00:00:00'"))
        val decision = hooks.afterToolExecution(
            result("call-1", wecomFixture("error-unauthorized.json"), isError = true),
        )
        assertEquals(ToolResultAction.REPLACE, decision.action)
        assertEquals(UNAUTHORIZED_OBSERVATION, decision.result!!.text)
        assertTrue(decision.result!!.isError)
        assertTrue(host.posted.isEmpty())
    }

    @Test
    fun nonAuthFailureKeepsRawResult() = runBlocking {
        val host = FakeWecomHost()
        val hooks = bridge.hooks(host)
        hooks.beforeToolCall(request("call-1", "wecom-cli todo list"))
        val networkError = """{"error":{"type":"NetworkError","code":893101,"message":"Request failed"}}"""
        val decision = hooks.afterToolExecution(result("call-1", networkError, isError = true))
        assertEquals(ToolResultAction.KEEP, decision.action)
        assertTrue(host.posted.isEmpty())
    }

    @Test
    fun malformedJsonKeepsRawResult() = runBlocking {
        val host = FakeWecomHost()
        val hooks = bridge.hooks(host)
        hooks.beforeToolCall(request("call-1", "wecom-cli todo list"))
        val decision = hooks.afterToolExecution(result("call-1", "not json at all"))
        assertEquals(ToolResultAction.KEEP, decision.action)
        assertNull(decision.result)
        assertTrue(host.posted.isEmpty())
    }

    @Test
    fun wrongPayloadShapeForCommandKeepsRawResult() = runBlocking {
        val host = FakeWecomHost()
        val hooks = bridge.hooks(host)
        hooks.beforeToolCall(request("call-1", "wecom-cli todo list"))
        val decision = hooks.afterToolExecution(result("call-1", wecomFixture("chat-groups.json")))
        assertEquals(ToolResultAction.KEEP, decision.action)
        assertTrue(host.posted.isEmpty())
    }

    @Test
    fun cliNoiseAndExitMarkerStillProduceObservation() = runBlocking {
        val host = FakeWecomHost()
        val hooks = bridge.hooks(host)
        hooks.beforeToolCall(request("call-1", "wecom-cli todo list"))
        val output = "[wecom] json repair: ok\n${wecomFixture("todo-list.json")}\n\n[exit: 0]"
        val decision = hooks.afterToolExecution(result("call-1", output))
        assertEquals(ToolResultAction.REPLACE, decision.action)
        assertTrue(decision.result!!.text!!.contains("整理接口文档"))
        assertEquals(1, host.posted.size)
    }

    @Test
    fun emptyResultBecomesEmptyCard() = runBlocking {
        val host = FakeWecomHost()
        val hooks = bridge.hooks(host)
        hooks.beforeToolCall(request("call-1", "wecom-cli chat groups list"))
        val decision = hooks.afterToolExecution(
            result("call-1", """{"chats":[],"chats_count":0}"""),
        )
        assertEquals(ToolResultAction.REPLACE, decision.action)
        assertTrue(decision.result!!.text!!.contains("没有符合条件的群聊会话"))
        val card = decode(host.posted.single().payload)!!
        assertEquals("empty", card.state.wire)
        assertEquals(0, card.total)
    }

    @Test
    fun beforeToolCallNeverBlocks() = runBlocking {
        val host = FakeWecomHost()
        val hooks = bridge.hooks(host)
        assertEquals(ToolCallAction.CONTINUE, hooks.beforeToolCall(request("call-1", "wecom-cli todo list")).action)
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
        val host = FakeWecomHost()
        val hooks = bridge.hooks(host)
        hooks.beforeToolCall(request("call-1", "wecom-cli todo list"))
        hooks.runFinished(
            object : RunFinishedEvent {
                override val sessionId: String = SESSION
                override val runId: String = "run-1"
                override val error: String? = null
            },
        )
        assertEquals(ToolResultAction.KEEP, hooks.afterToolExecution(result("call-1", wecomFixture("todo-list.json"))).action)

        hooks.beforeToolCall(request("call-2", "wecom-cli todo list"))
        hooks.sessionClosed(
            object : SessionClosedEvent {
                override val sessionId: String = SESSION
            },
        )
        assertEquals(ToolResultAction.KEEP, hooks.afterToolExecution(result("call-2", wecomFixture("todo-list.json"))).action)
        assertTrue(host.posted.isEmpty())
    }

    @Test
    fun cardPostFailureStillReplacesObservationWithoutHint() = runBlocking {
        val host = FakeWecomHost(postSucceeds = false)
        val hooks = bridge.hooks(host)
        hooks.beforeToolCall(request("call-1", "wecom-cli todo list"))
        val decision = hooks.afterToolExecution(result("call-1", wecomFixture("todo-list.json")))
        assertEquals(ToolResultAction.REPLACE, decision.action)
        val text = decision.result!!.text!!
        assertTrue(text.contains("整理接口文档"))
        assertFalse(text.contains("以卡片展示"))
        assertFalse(text.contains("\"items\""))
        assertEquals(1, host.posted.size)
    }

    @Test
    fun oversizedOutputIsNotParsed() = runBlocking {
        val host = FakeWecomHost()
        val hooks = bridge.hooks(host)
        hooks.beforeToolCall(request("call-1", "wecom-cli chat groups list"))
        val huge = wecomFixture("chat-groups.json") + "x".repeat(300 * 1024)
        assertEquals(ToolResultAction.KEEP, hooks.afterToolExecution(result("call-1", huge)).action)
        assertTrue(host.posted.isEmpty())
    }

    private fun request(callId: String, command: String): ToolCallRequest = ToolCallRequest(
        sessionId = SESSION,
        callId = callId,
        toolName = "bash",
        args = buildJsonObject { put("command", command) },
    )

    private fun result(callId: String, text: String, isError: Boolean = false): ToolExecutionResult =
        ToolExecutionResult(
            sessionId = SESSION,
            callId = callId,
            toolName = "bash",
            result = textResult(text, isError = isError),
        )

    private fun decode(payload: kotlinx.serialization.json.JsonObject) =
        CliCardCodec.decode(payload, WecomCommandKind.entries.map { it.customType }.toSet())

    private companion object {
        const val SESSION = "session-1"
    }
}
