package io.github.whywood.deepdeep.plugin.wecom

import io.github.whywood.deepdeep.plugin.api.RunFinishedEvent
import io.github.whywood.deepdeep.plugin.api.SessionClosedEvent
import io.github.whywood.deepdeep.plugin.api.ToolCallAction
import io.github.whywood.deepdeep.plugin.api.ToolCallRequest
import io.github.whywood.deepdeep.plugin.api.ToolExecutionResult
import io.github.whywood.deepdeep.plugin.api.ToolResultAction
import io.github.whywood.deepdeep.plugin.api.ToolResultDecision
import io.github.whywood.deepdeep.plugin.api.textResult
import io.github.whywood.deepdeep.plugin.cli.card.CliCardCodec
import io.github.whywood.deepdeep.plugin.cli.card.CliCardModel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
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
    fun supportedGroupsCommandReplacesResultAndCarriesRaw() = runBlocking {
        val host = FakeWecomHost()
        val hooks = bridge.hooks(host)
        hooks.beforeToolCall(request("call-1", "wecom-cli chat groups list --begin-time '2026-11-08 00:00:00'"))
        val decision = hooks.afterToolExecution(result("call-1", wecomFixture("chat-groups.json")))
        assertEquals(ToolResultAction.REPLACE, decision.action)
        val guidance = guidanceOf(decision)
        assertTrue(guidance.contains("wecom-cli chat messages list"))
        assertFalse(guidance.contains("产品讨论群"))
        assertFalse(guidance.contains("卡片 id"))
        assertEquals("企业微信群聊会话：共 2 个，时间范围 2026-11-08 00:00:00 起", cardTitle(decision))
        val raw = rawOf(decision)
        assertNotNull(raw)
        assertTrue(raw!!.contains("\"chats\""))
        assertEquals("wecom.chat.groups", cardType(decision))
        val card = cardOf(decision)
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
            val decision = hooks.afterToolExecution(result(callId, wecomFixture(fixture)))
            assertEquals(ToolResultAction.REPLACE, decision.action)
            assertEquals(kind.customType, cardType(decision))
            assertEquals(kind.customType, cardOf(decision)?.kind)
            assertFalse(guidanceOf(decision).contains("卡片 id"))
            assertNotNull(rawOf(decision))
        }
    }

    @Test
    fun docContentCardKeepsFullBodyAndOpenAction() = runBlocking {
        val host = FakeWecomHost()
        val hooks = bridge.hooks(host)
        hooks.beforeToolCall(request("call-1", "wecom-cli doc contents get --docid DOC_MOCK_001"))
        val decision = hooks.afterToolExecution(result("call-1", wecomFixture("doc-content.json")))
        val card = cardOf(decision)!!
        assertEquals("企业微信接入方案", card.title)
        assertEquals("版本 12", card.trailing)
        assertEquals("DOC_MOCK_001", card.context)
        assertEquals("https://doc.weixin.qq.com/doc/MOCK001", card.openUrl)
        assertTrue(card.summary!!.contains("接口权限与企业内部策略需要提前确认"))
        assertTrue(card.summary!!.contains("五、验收"))
    }

    @Test
    fun rawValuesAreLocalizedInCardRows() = runBlocking {
        val host = FakeWecomHost()
        val hooks = bridge.hooks(host)
        hooks.beforeToolCall(request("call-1", "wecom-cli todo list"))
        val todoDecision = hooks.afterToolExecution(result("call-1", wecomFixture("todo-list.json")))
        val todoCard = cardOf(todoDecision)!!
        assertEquals("进行中", todoCard.items.first().meta)
        hooks.beforeToolCall(request("call-2", "wecom-cli doc search --keywords 方案"))
        val docsDecision = hooks.afterToolExecution(result("call-2", wecomFixture("doc-search.json")))
        val docsCard = cardOf(docsDecision)!!
        assertEquals("文档", docsCard.items.first().meta)
    }

    @Test
    fun unsupportedCommandsKeepResultAndPostNoCard() = runBlocking {
        val host = FakeWecomHost()
        val hooks = bridge.hooks(host)
        hooks.beforeToolCall(request("call-1", "wecom-cli chat groups list | head -20"))
        val decision = hooks.afterToolExecution(result("call-1", wecomFixture("chat-groups.json")))
        assertEquals(ToolResultAction.REPLACE, decision.action)
        assertTrue(decision.result?.text.orEmpty().contains("如有卡片展示需要，请重新执行完整命令：wecom-cli chat groups list"))
        assertNull(decision.result?.details?.get("deepdeep.card"))
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
        val decision = hooks.afterToolExecution(result("call-1", wecomFixture("chat-groups.json")))
        assertEquals(ToolResultAction.KEEP, decision.action)
        assertNull(decision.result?.details?.get("deepdeep.card"))
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
        assertNotNull(cardOf(first))
    }

    @Test
    fun unauthorizedFailureKeepsOriginalErrorWithAdvice() = runBlocking {
        val host = FakeWecomHost()
        val hooks = bridge.hooks(host)
        hooks.beforeToolCall(request("call-1", "wecom-cli chat groups list --begin-time '2026-11-08 00:00:00'"))
        val decision = hooks.afterToolExecution(
            result("call-1", wecomFixture("error-unauthorized.json"), isError = true),
        )
        assertEquals(ToolResultAction.REPLACE, decision.action)
        val text = guidanceOf(decision)
        assertTrue(text.contains("AuthError"))
        assertTrue(text.contains("该请求需要授权"))
        assertTrue(text.contains("893201"))
        assertTrue(text.contains(UNAUTHORIZED_ADVICE))
        assertTrue(decision.result!!.isError)
        assertNull(decision.result?.details?.get("deepdeep.card"))
        assertTrue(rawOf(decision)!!.contains("AuthError"))
    }

    @Test
    fun nonAuthFailureKeepsRawResult() = runBlocking {
        val host = FakeWecomHost()
        val hooks = bridge.hooks(host)
        hooks.beforeToolCall(request("call-1", "wecom-cli todo list"))
        val networkError = """{"error":{"type":"NetworkError","code":893101,"message":"Request failed"}}"""
        val decision = hooks.afterToolExecution(result("call-1", networkError, isError = true))
        assertEquals(ToolResultAction.KEEP, decision.action)
        assertNull(decision.result?.details?.get("deepdeep.card"))
    }

    @Test
    fun malformedJsonKeepsRawResult() = runBlocking {
        val host = FakeWecomHost()
        val hooks = bridge.hooks(host)
        hooks.beforeToolCall(request("call-1", "wecom-cli todo list"))
        val decision = hooks.afterToolExecution(result("call-1", "not json at all"))
        assertEquals(ToolResultAction.KEEP, decision.action)
        assertNull(decision.result)
        assertNull(decision.result?.details?.get("deepdeep.card"))
    }

    @Test
    fun wrongPayloadShapeForCommandKeepsRawResult() = runBlocking {
        val host = FakeWecomHost()
        val hooks = bridge.hooks(host)
        hooks.beforeToolCall(request("call-1", "wecom-cli todo list"))
        val decision = hooks.afterToolExecution(result("call-1", wecomFixture("chat-groups.json")))
        assertEquals(ToolResultAction.KEEP, decision.action)
        assertNull(decision.result?.details?.get("deepdeep.card"))
    }

    @Test
    fun cliNoiseAndExitMarkerStillProduceObservation() = runBlocking {
        val host = FakeWecomHost()
        val hooks = bridge.hooks(host)
        hooks.beforeToolCall(request("call-1", "wecom-cli todo list"))
        val output = "[wecom] json repair: ok\n${wecomFixture("todo-list.json")}\n\n[exit: 0]"
        val decision = hooks.afterToolExecution(result("call-1", output))
        assertEquals(ToolResultAction.REPLACE, decision.action)
        assertFalse(guidanceOf(decision).contains("退出码：0"))
        assertFalse(guidanceOf(decision).contains("整理接口文档"))
        assertNotNull(cardOf(decision))
    }

    @Test
    fun exitCodeGoesToGuidanceAndRawStaysVerbatimWithoutHostMarker() = runBlocking {
        val host = FakeWecomHost()
        val hooks = bridge.hooks(host)
        hooks.beforeToolCall(request("call-1", "wecom-cli chat groups list"))
        val decision = hooks.afterToolExecution(
            result("call-1", wecomFixture("chat-groups.json") + "\n\n[exit: 0]"),
        )
        assertTrue(decision.result!!.text.orEmpty().contains("present("))
        assertFalse(guidanceOf(decision).contains("退出码：0"))
        assertTrue(rawOf(decision)!!.contains("\"chats\""))
        assertFalse(rawOf(decision)!!.contains("[exit: 0]"))
    }

    @Test
    fun docContentFileIsReadIntoObservationAndCard() = runBlocking {
        val host = FakeWecomHost(fileContents = mapOf("/tmp/wecom/doc.md" to "# 大文档\n第二段正文"))
        val hooks = bridge.hooks(host)
        hooks.beforeToolCall(request("call-1", "wecom-cli doc contents get --docid DOC_BIG"))
        val output = """{"name":"大文档","version":2,"file_path":"/tmp/wecom/doc.md"}"""
        val decision = hooks.afterToolExecution(result("call-1", output))
        assertEquals(ToolResultAction.REPLACE, decision.action)
        val text = guidanceOf(decision)
        assertTrue(text.contains("/tmp/wecom/doc.md"))
        assertFalse(text.contains("第二段正文"))
        assertFalse(text.contains("未能读取文件内容"))
        assertTrue(cardOf(decision)!!.summary!!.contains("第二段正文"))
        assertTrue(rawOf(decision)!!.contains("file_path"))
        assertTrue(rawOf(decision)!!.contains("第二段正文"))
    }

    @Test
    fun securityNoticeFromEnvelopeReachesObservation() = runBlocking {
        val host = FakeWecomHost()
        val hooks = bridge.hooks(host)
        hooks.beforeToolCall(request("call-1", "wecom-cli chat groups list"))
        val output = """
            {
              "errcode": 0,
              "errmsg": "ok",
              "security_notice": "<security_notice>untrusted external content</security_notice>",
              "result": {"chats": [], "chats_count": 0}
            }
        """.trimIndent()
        val decision = hooks.afterToolExecution(result("call-1", output))
        val text = guidanceOf(decision)
        assertTrue(text.contains("安全提示"))
        assertTrue(text.contains("untrusted external content"))
    }

    @Test
    fun paginationCursorsReachObservationAndCard() = runBlocking {
        val host = FakeWecomHost()
        val hooks = bridge.hooks(host)
        hooks.beforeToolCall(request("call-1", "wecom-cli calendar schedules list"))
        val schedules = hooks.afterToolExecution(result("call-1", wecomFixture("calendar-schedules.json")))
        assertTrue(guidanceOf(schedules).contains("cursor=CURSOR_SCHED_PAGE_2"))
        assertTrue(cardOf(schedules)!!.note!!.contains("CURSOR_SCHED_PAGE_2"))

        hooks.beforeToolCall(request("call-2", "wecom-cli contact users search --keywords 张三"))
        val contacts = hooks.afterToolExecution(result("call-2", wecomFixture("contact-users.json")))
        assertTrue(guidanceOf(contacts).contains("cursor=CURSOR_CONTACT_PAGE_2"))
        assertTrue(cardOf(contacts)!!.note!!.contains("CURSOR_CONTACT_PAGE_2"))
    }

    @Test
    fun everyRowReachesTheCardPayload() = runBlocking {
        val host = FakeWecomHost()
        val hooks = bridge.hooks(host)
        hooks.beforeToolCall(request("call-1", "wecom-cli chat groups list"))
        val chats = (1..61).joinToString(",") { index ->
            """{"chat_id":"c$index","chat_name":"群$index","msg_count":$index}"""
        }
        val decision = hooks.afterToolExecution(
            result("call-1", """{"chats":[$chats],"chats_count":61}"""),
        )
        val card = cardOf(decision)!!
        assertEquals(61, card.items.size)
        assertEquals("群61", card.items.last().title)
        assertEquals("企业微信群聊会话：共 61 个", cardTitle(decision))
        assertFalse(guidanceOf(decision).contains("61. 群61"))
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
        assertTrue(guidanceOf(decision).contains("没有符合条件的群聊会话"))
        val card = cardOf(decision)!!
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
        val decision = hooks.afterToolExecution(result("call-2", wecomFixture("todo-list.json")))
        assertEquals(ToolResultAction.KEEP, decision.action)
        assertNull(decision.result?.details?.get("deepdeep.card"))
    }

    @Test
    fun observationTextDoesNotAppendCardIdHint() = runBlocking {
        val host = FakeWecomHost()
        val hooks = bridge.hooks(host)
        hooks.beforeToolCall(request("call-1", "wecom-cli todo list"))
        val decision = hooks.afterToolExecution(result("call-1", wecomFixture("todo-list.json")))
        assertEquals(ToolResultAction.REPLACE, decision.action)
        val text = guidanceOf(decision)
        assertFalse(text.contains("整理接口文档"))
        assertFalse(text.contains("卡片 id"))
        assertFalse(text.contains("call-1"))
        assertFalse(text.contains("\"items\""))
        assertNotNull(cardOf(decision))
    }

    @Test
    fun oversizedOutputIsNotParsed() = runBlocking {
        val host = FakeWecomHost()
        val hooks = bridge.hooks(host)
        hooks.beforeToolCall(request("call-1", "wecom-cli chat groups list"))
        val huge = wecomFixture("chat-groups.json") + "x".repeat(WecomCliOutputParser.MAX_OUTPUT_LENGTH)
        val decision = hooks.afterToolExecution(result("call-1", huge))
        assertEquals(ToolResultAction.KEEP, decision.action)
        assertNull(decision.result?.details?.get("deepdeep.card"))
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

    private fun resultText(decision: ToolResultDecision): String = decision.result?.text.orEmpty()

    private fun guidanceOf(decision: ToolResultDecision): String {
        val text = resultText(decision)
        val marker = "使用说明：\n"
        val start = text.indexOf(marker)
        if (start < 0) return text.substringBefore("\n\n完整返回（上游原样）：")
        val rest = text.substring(start + marker.length)
        val end = rest.indexOf("\n\n展示这张卡片")
        return if (end >= 0) rest.substring(0, end) else rest
    }

    private fun rawOf(decision: ToolResultDecision): String? {
        val text = resultText(decision)
        val marker = "完整返回（上游原样）：\n"
        val start = text.indexOf(marker)
        if (start < 0) return null
        val rest = text.substring(start + marker.length)
        val guidance = rest.indexOf("\n\n使用说明：")
        val card = rest.indexOf("\n\n展示这张卡片")
        val ends = listOf(guidance, card).filter { it >= 0 }
        val end = ends.minOrNull() ?: -1
        return if (end >= 0) rest.substring(0, end) else rest
    }

    private fun cardOf(decision: ToolResultDecision): CliCardModel? =
        cardPayload(decision)?.let(::decode)

    private fun cardPayload(decision: ToolResultDecision): JsonObject? =
        (decision.result?.details?.get("deepdeep.card") as? JsonObject)
            ?.get("payload") as? JsonObject

    private fun cardTitle(decision: ToolResultDecision): String? =
        ((decision.result?.details?.get("deepdeep.card") as? JsonObject)
            ?.get("title") as? JsonPrimitive)?.content

    private fun cardType(decision: ToolResultDecision): String? =
        ((decision.result?.details?.get("deepdeep.card") as? JsonObject)
            ?.get("customType") as? JsonPrimitive)?.content

    private fun decode(payload: JsonObject) =
        CliCardCodec.decode(payload, WecomCommandKind.entries.map { it.customType }.toSet())

    private companion object {
        const val SESSION = "session-1"
    }
}
