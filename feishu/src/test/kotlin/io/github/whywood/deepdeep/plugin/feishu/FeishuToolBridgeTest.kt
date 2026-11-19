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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FeishuToolBridgeTest {
    private val sessionId = "session-1"

    @Before
    fun clearLinks() {
        FeishuLinks.clear()
    }

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

    private fun rawText(decision: ToolResultDecision): String? {
        val text = decision.result?.text.orEmpty()
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
        assertTrue(guidanceOf(decision).contains("lark-cli im +chat-messages-list"))
        assertFalse(guidanceOf(decision).contains("产品研发群"))
        assertFalse(text.contains("卡片 id"))
        assertFalse(text.contains("{\"ok\":true}"))
        val raw = rawText(decision)
        assertNotNull(raw)
        assertTrue(raw!!.contains("\"产品研发群\""))
        assertTrue(raw.contains("\"ok\": true"))
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
        hooks.beforeToolCall(request("call-2", "lark-cli im +chat-search --query 大创"))
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
        assertEquals(ToolResultAction.REPLACE, decision.action)
        assertTrue(decision.result?.text.orEmpty().contains("请重新执行完整命令：lark-cli im +chat-list"))
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
        assertEquals(ToolResultAction.REPLACE, decision.action)
        assertTrue(decision.result?.text.orEmpty().contains("请重新执行完整命令：lark-cli im +chat-list"))
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
        assertTrue(guidanceOf(decision).contains("--start/--end"))
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
    fun authenticationErrorKeepsRawAndAddsGuidance() = runTest {
        val host = FakeFeishuHost()
        val hooks = FeishuToolBridge().hooks(host)
        hooks.beforeToolCall(request("call-18", "lark-cli im +chat-list"))
        val output = """{"ok":false,"error":{"type":"authentication","message":"invalid token"}}"""
        val decision = hooks.afterToolExecution(result("call-18", output, isError = true))
        assertEquals(ToolResultAction.REPLACE, decision.action)
        assertTrue(decision.text().startsWith(""))
        assertTrue(guidanceOf(decision).contains("重新登录"))
        assertTrue(rawText(decision)!!.contains("invalid token"))
        assertNull(cardOf(decision))
    }

    @Test
    fun mailTriageMissingMailboxMapsToGuidanceWithoutRetry() = runTest {
        val host = FakeFeishuHost()
        val hooks = FeishuToolBridge().hooks(host)
        hooks.beforeToolCall(request("call-19", "lark-cli mail +triage"))
        val decision = hooks.afterToolExecution(
            result("call-19", loadFixture("mail-triage-error.json"), isError = true),
        )
        assertEquals(ToolResultAction.REPLACE, decision.action)
        val guidance = guidanceOf(decision)
        assertTrue(guidance.contains("没有开通邮箱"))
        assertTrue(guidance.contains("不要重试"))
        assertTrue(guidance.contains("不是权限或登录问题"))
        assertFalse(guidance.contains("重新登录"))
        assertTrue(decision.result!!.isError)
        assertTrue(rawText(decision)!!.contains("15180001"))
        assertNull(cardOf(decision))
    }

    @Test
    fun mailTriageAttachesMailListCard() = runTest {
        val host = FakeFeishuHost()
        val hooks = FeishuToolBridge().hooks(host)
        hooks.beforeToolCall(request("call-20", "lark-cli mail +triage --max 20 --format json"))
        val decision = hooks.afterToolExecution(result("call-20", loadFixture("mail-triage.json")))
        assertEquals(ToolResultAction.REPLACE, decision.action)
        assertTrue(guidanceOf(decision).contains("mail +message"))
        assertTrue(guidanceOf(decision).contains("--page-token list:FfccvoqPd_loLhtcRx8cx"))
        assertNotNull(rawText(decision))
        val card = cardOf(decision)
        assertEquals("邮箱（3 封，2 未读）", card?.get("title")?.jsonPrimitive?.content)
        val decoded = decodeFeishuCardPayload(cardPayload(decision)!!)!!
        assertEquals(FeishuCardTypes.KIND_MAIL_LIST, decoded.kind)
        assertEquals(3, decoded.items.size)
        assertEquals("周会改到周四", decoded.items.first().title)
        assertEquals("Alice", decoded.items.first().subtitle)
    }

    @Test
    fun mailMessageAttachesMailReadCard() = runTest {
        val host = FakeFeishuHost()
        val hooks = FeishuToolBridge().hooks(host)
        hooks.beforeToolCall(request("call-22", "lark-cli mail +message --message-id m_1 --format json"))
        val decision = hooks.afterToolExecution(result("call-22", loadFixture("mail-message.json")))
        assertEquals(ToolResultAction.REPLACE, decision.action)
        assertTrue(guidanceOf(decision).contains("不可信的外部输入"))
        val decoded = decodeFeishuCardPayload(cardPayload(decision)!!)!!
        assertEquals(FeishuCardTypes.KIND_MAIL_READ, decoded.kind)
        assertEquals("周会改到周四", decoded.context)
        assertEquals("Alice <alice@example.com>", decoded.items.first().title)
        assertTrue(decoded.items.first().subtitle.orEmpty().contains("收件人：黄宇鹏"))
        assertTrue(decoded.items.first().body.orEmpty().contains("周四下午三点"))
        assertTrue(decoded.note.orEmpty().contains("进度.docx"))
    }

    @Test
    fun messagesSearchAttachesSearchCard() = runTest {
        val host = FakeFeishuHost()
        val hooks = FeishuToolBridge().hooks(host)
        hooks.beforeToolCall(request("call-24", "lark-cli im +messages-search --query 设计稿 --page-size 50"))
        val decision = hooks.afterToolExecution(result("call-24", loadFixture("messages-search.json")))
        assertEquals(ToolResultAction.REPLACE, decision.action)
        assertTrue(guidanceOf(decision).contains("im +chat-messages-list"))
        val decoded = decodeFeishuCardPayload(cardPayload(decision)!!)!!
        assertEquals(FeishuCardTypes.KIND_MESSAGES_SEARCH, decoded.kind)
        assertEquals(2, decoded.items.size)
        assertEquals("大创小组", decoded.items.first().title)
        assertTrue(decoded.context.orEmpty().contains("搜索：设计稿"))
    }

    @Test
    fun messageSendAttachesActionCard() = runTest {
        val host = FakeFeishuHost()
        val hooks = FeishuToolBridge().hooks(host)
        hooks.beforeToolCall(request("call-25", "lark-cli im +messages-send --chat-id oc_1 --text hello"))
        val decision = hooks.afterToolExecution(result("call-25", loadFixture("messages-send-result.json")))
        assertEquals(ToolResultAction.REPLACE, decision.action)
        assertTrue(guidanceOf(decision).contains("消息已发送"))
        val decoded = decodeFeishuCardPayload(cardPayload(decision)!!)!!
        assertEquals(FeishuCardTypes.KIND_ACTION, decoded.kind)
        assertEquals("消息已发送", decoded.title)
        assertEquals("hello", decoded.items.first().body)
        assertTrue(decoded.openUrl.orEmpty().contains("openChatId=oc_1"))
    }

    @Test
    fun authStatusReplacesTextWithoutCard() = runTest {
        val host = FakeFeishuHost()
        val hooks = FeishuToolBridge().hooks(host)
        hooks.beforeToolCall(request("call-26", "lark-cli auth status --json"))
        val decision = hooks.afterToolExecution(result("call-26", loadFixture("auth-status.json")))
        assertEquals(ToolResultAction.REPLACE, decision.action)
        assertNull(cardOf(decision))
        assertTrue(guidanceOf(decision).contains("黄宇鹏"))
        assertTrue(guidanceOf(decision).contains("5 项权限"))
        assertFalse(guidanceOf(decision).contains("calendar:calendar:read"))
        assertNotNull(rawText(decision))
    }

    @Test
    fun docsSearchCommandReusesDocsCard() = runTest {
        val host = FakeFeishuHost()
        val hooks = FeishuToolBridge().hooks(host)
        hooks.beforeToolCall(request("call-27", "lark-cli docs +search --query 大创"))
        val decision = hooks.afterToolExecution(result("call-27", loadFixture("docs-search.json")))
        assertEquals(ToolResultAction.REPLACE, decision.action)
        val decoded = decodeFeishuCardPayload(cardPayload(decision)!!)!!
        assertEquals(FeishuCardTypes.KIND_DOCS_SEARCH, decoded.kind)
    }

    @Test
    fun mailProfileCommandPassesThroughUnchanged() = runTest {
        val host = FakeFeishuHost()
        val hooks = FeishuToolBridge().hooks(host)
        val command = "lark-cli mail user_mailboxes profile --params '{\"user_mailbox_id\":\"me\"}'"
        assertEquals(ToolCallAction.CONTINUE, hooks.beforeToolCall(request("call-21", command)).action)
        val output = """{"ok":true,"identity":"user","data":{"not_found_reason":"mailbox address not found","primary_email_address":""}}"""
        val decision = hooks.afterToolExecution(result("call-21", output))
        assertEquals(ToolResultAction.KEEP, decision.action)
        assertNull(cardOf(decision))
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
        assertFalse(guidanceOf(decision).contains("lark-cli update"))
        assertFalse(guidanceOf(decision).contains("_notice"))
        val card = decision.result?.details?.get("deepdeep.card") as? JsonObject
        val payload = card?.get("payload")?.jsonObject
        assertNotNull(payload)
        val decoded = decodeFeishuCardPayload(payload!!)
        assertEquals(FeishuCardTypes.KIND_CHAT_LIST, decoded?.kind)
    }
}

private fun guidanceOf(decision: ToolResultDecision): String {
    val text = decision.result?.text.orEmpty()
    val marker = "使用说明：\n"
    val start = text.indexOf(marker)
    if (start < 0) return text.substringBefore("\n\n完整返回（上游原样）：")
    val rest = text.substring(start + marker.length)
    val end = rest.indexOf("\n\n展示这张卡片")
    return if (end >= 0) rest.substring(0, end) else rest
}
