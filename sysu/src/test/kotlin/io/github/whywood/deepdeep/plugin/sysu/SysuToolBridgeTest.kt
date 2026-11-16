package io.github.whywood.deepdeep.plugin.sysu

import io.github.whywood.deepdeep.plugin.api.RunFinishedEvent
import io.github.whywood.deepdeep.plugin.api.ToolCallAction
import io.github.whywood.deepdeep.plugin.api.ToolCallRequest
import io.github.whywood.deepdeep.plugin.api.ToolExecutionResult
import io.github.whywood.deepdeep.plugin.api.ToolResultAction
import io.github.whywood.deepdeep.plugin.api.ToolResultDecision
import io.github.whywood.deepdeep.plugin.api.textResult
import io.github.whywood.deepdeep.plugin.cli.card.CliCardCodec
import io.github.whywood.deepdeep.plugin.cli.card.CliCardModel
import io.github.whywood.deepdeep.plugin.cli.card.CliCardState
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

class SysuToolBridgeTest {
    private val sessionId = "session-1"

    private fun call(command: String, callId: String = "call-1") = ToolCallRequest(
        sessionId = sessionId,
        callId = callId,
        toolName = "bash",
        args = buildJsonObject { put("command", command) },
    )

    private fun result(callId: String = "call-1", output: String, isError: Boolean = false) =
        ToolExecutionResult(
            sessionId = sessionId,
            callId = callId,
            toolName = "bash",
            result = textResult(output, isError = isError),
        )

    private fun hooks(host: SysuFakeHost) = SysuToolBridge().hooks(host)

    private fun cardDetails(decision: ToolResultDecision): JsonObject =
        decision.result?.details?.get("deepdeep.card") as JsonObject

    private fun card(decision: ToolResultDecision): CliCardModel? =
        CliCardCodec.decode(cardDetails(decision).getValue("payload").jsonObject, SYSU_CARD_KINDS)

    @Test
    fun replacesScheduleWithObservationAndCardDetails() = runTest {
        val host = SysuFakeHost()
        val hooks = hooks(host)
        hooks.beforeToolCall(call("sysu jwxt schedule --json"))
        val decision = hooks.afterToolExecution(result(output = SysuFixtures.bashOutput("schedule.json")))
        assertEquals(ToolResultAction.REPLACE, decision.action)
        val text = decision.result?.text.orEmpty()
        assertTrue(text.startsWith("课表 · 2026-1 · 第 10 周"))
        assertFalse(text.contains("卡片"))
        assertFalse(text.contains("TGC"))
        val details = cardDetails(decision)
        assertEquals(SYSU_CARD_TYPE, details.getValue("customType").jsonPrimitive.content)
        assertEquals("课表 · 6 门 · 2026-1 · 第 10 周", details.getValue("title").jsonPrimitive.content)
        val card = card(decision)
        assertNotNull(card)
        assertEquals(SysuCardKind.Schedule.wire, card!!.kind)
        assertEquals("课表", card.title)
        assertEquals("6 门", card.trailing)
        assertEquals("周一 2026-11-09", card.sections.first().label)
        assertEquals(6, card.sections.sumOf { it.items.size })
        val raw = decision.result?.details?.get("deepdeep.raw")?.jsonPrimitive?.content
        assertNotNull(raw)
        assertFalse(raw!!.contains("TGC"))
    }

    @Test
    fun observationRawRedactsTicketsAndSaysSo() = runTest {
        val host = SysuFakeHost()
        val hooks = hooks(host)
        hooks.beforeToolCall(call("sysu jwxt schedule --json"))
        val output = "TGC=abc123def\n" + SysuFixtures.bashOutput("schedule.json")
        val decision = hooks.afterToolExecution(result(output = output))
        assertEquals(ToolResultAction.REPLACE, decision.action)
        val raw = decision.result?.details?.get("deepdeep.raw")?.jsonPrimitive?.content
        assertNotNull(raw)
        assertFalse(raw!!.contains("TGC"))
        assertFalse(raw.contains("abc123def"))
        assertTrue(raw.contains("已隐去"))
    }

    @Test
    fun creditsObservationHasNoCardDetailsOrIdHint() = runTest {
        val host = SysuFakeHost()
        val hooks = hooks(host)
        hooks.beforeToolCall(call("sysu jwxt credits --json"))
        val decision = hooks.afterToolExecution(result(output = SysuFixtures.bashOutput("credits.json")))
        assertEquals(ToolResultAction.REPLACE, decision.action)
        val text = decision.result?.text.orEmpty()
        assertTrue(text.startsWith("毕业学分"))
        assertFalse(text.contains("卡片"))
        val details = decision.result?.details
        assertNotNull(details)
        assertNull(details!!["deepdeep.card"])
        assertNotNull(details["deepdeep.raw"]?.jsonPrimitive?.content)
    }

    @Test
    fun netpayExpiredSessionIsErrorCardWithoutIdHint() = runTest {
        val host = SysuFakeHost()
        val hooks = hooks(host)
        hooks.beforeToolCall(call("sysu netpay --json"))
        val decision = hooks.afterToolExecution(result(output = SysuFixtures.bashOutput("netpay_expired.json")))
        assertEquals(ToolResultAction.REPLACE, decision.action)
        assertTrue(decision.result?.isError == true)
        val text = decision.result?.text.orEmpty()
        assertTrue(text.contains("插件页"))
        assertTrue(text.contains("sysu netpay login"))
        assertFalse(text.contains("undefined"))
        assertFalse(text.contains("卡片"))
        val card = card(decision)
        assertEquals(CliCardState.ERROR, card?.state)
        assertNotNull(decision.result?.details?.get("deepdeep.raw"))
    }

    @Test
    fun tgcFailureBecomesActionableWithoutTicketLeak() = runTest {
        val host = SysuFakeHost()
        val hooks = hooks(host)
        hooks.beforeToolCall(call("sysu jwxt schedule --json"))
        val decision = hooks.afterToolExecution(
            result(
                output = "会话失效，自动续期失败（TGC 已失效）。重新登录：sysu login\n\n[exit: 1]",
                isError = true,
            ),
        )
        assertEquals(ToolResultAction.REPLACE, decision.action)
        assertTrue(decision.result?.isError == true)
        val text = decision.result?.text.orEmpty()
        assertTrue(text.contains("插件页"))
        assertTrue(text.contains("sysu login"))
        assertFalse(text.contains("TGC"))
        assertFalse(text.contains("tgc"))
        val raw = decision.result?.details?.get("deepdeep.raw")?.jsonPrimitive?.content
        assertNotNull(raw)
        assertFalse(raw!!.contains("TGC"))
    }

    @Test
    fun verificationCodeFailureMentionsCodePage() = runTest {
        val host = SysuFakeHost()
        val hooks = hooks(host)
        hooks.beforeToolCall(call("sysu login --code 1234"))
        val decision = hooks.afterToolExecution(
            result(output = "二次验证没过：企微验证码没发出去\n\n[exit: 1]", isError = true),
        )
        assertEquals(ToolResultAction.REPLACE, decision.action)
        val text = decision.result?.text.orEmpty()
        assertTrue(text.contains("验证码"))
        assertTrue(text.contains("插件页"))
        assertFalse(text.contains("TGC"))
    }

    @Test
    fun gymErrorJsonBecomesReadableError() = runTest {
        val host = SysuFakeHost()
        val hooks = hooks(host)
        hooks.beforeToolCall(call("sysu gym slots 健身房 --json"))
        val decision = hooks.afterToolExecution(
            result(
                output = """{"ok":false,"error":{"message":"接口回应超时","code":500}}""" + "\n\n[exit: 1]",
                isError = true,
            ),
        )
        assertEquals(ToolResultAction.REPLACE, decision.action)
        assertTrue(decision.result?.isError == true)
        assertTrue(decision.result?.text.orEmpty().contains("场馆查询失败：接口回应超时"))
    }

    @Test
    fun keepsUnsupportedOrNonSysuCommands() = runTest {
        val host = SysuFakeHost()
        val hooks = hooks(host)
        assertEquals(ToolCallAction.CONTINUE, hooks.beforeToolCall(call("sysu jwxt course LN3015 --json")).action)
        val unsupported = hooks.afterToolExecution(result(output = "课程信息：LN3015\n\n[exit: 0]"))
        assertEquals(ToolResultAction.REPLACE, unsupported.action)
        assertEquals("课程信息：LN3015\n\n[exit: 0]", unsupported.result?.text)
        assertNotNull(unsupported.result?.details?.get("deepdeep.raw"))
        assertEquals(ToolCallAction.CONTINUE, hooks.beforeToolCall(call("lark-cli im +chat-list", "call-2")).action)
        val otherTool = hooks.afterToolExecution(result("call-2", output = """{"ok":true,"data":[]}"""))
        assertEquals(ToolResultAction.KEEP, otherTool.action)
        assertNull(otherTool.result)
    }

    @Test
    fun gymTokenFailureKeepsOriginalAndSuggestsSiteLogin() = runTest {
        val host = SysuFakeHost()
        val hooks = hooks(host)
        hooks.beforeToolCall(call("sysu gym slots 健身房 --json"))
        val decision = hooks.afterToolExecution(
            result(
                output = """{"ok":false,"error":{"message":"gym token 失效","code":401}}""" + "\n\n[exit: 1]",
                isError = true,
            ),
        )
        assertEquals(ToolResultAction.REPLACE, decision.action)
        val text = decision.result?.text.orEmpty()
        assertTrue(text.contains("gym token 失效"))
        assertTrue(text.contains("sysu gym login"))
        assertTrue(text.contains("插件页"))
        assertFalse(text.contains("TGC"))
        assertNotNull(decision.result?.details?.get("deepdeep.raw"))
    }

    @Test
    fun keepsSupportedCommandWhenPayloadIsMissing() = runTest {
        val host = SysuFakeHost()
        val hooks = hooks(host)
        hooks.beforeToolCall(call("sysu jwxt scores --json"))
        val decision = hooks.afterToolExecution(result(output = "（无输出）\n\n[exit: 0]"))
        assertEquals(ToolResultAction.REPLACE, decision.action)
        assertNotNull(decision.result?.details?.get("deepdeep.raw"))
    }

    @Test
    fun deduplicatesSameCallId() = runTest {
        val host = SysuFakeHost()
        val hooks = hooks(host)
        hooks.beforeToolCall(call("sysu jwxt scores --json"))
        assertEquals(ToolResultAction.REPLACE, hooks.afterToolExecution(result(output = SysuFixtures.bashOutput("scores.json"))).action)
        assertEquals(ToolResultAction.KEEP, hooks.afterToolExecution(result(output = SysuFixtures.bashOutput("scores.json"))).action)
    }

    @Test
    fun clearsPendingCallsOnRunFinished() = runTest {
        val host = SysuFakeHost()
        val hooks = hooks(host)
        hooks.beforeToolCall(call("sysu jwxt --json"))
        hooks.runFinished(
            object : RunFinishedEvent {
                override val sessionId: String = "session-1"
                override val runId: String = "run-1"
                override val error: String? = null
            },
        )
        val decision = hooks.afterToolExecution(result(output = SysuFixtures.bashOutput("schedule.json")))
        assertEquals(ToolResultAction.KEEP, decision.action)
        assertNull(decision.result)
    }

    @Test
    fun redactsSensitiveOutputWhenBridgeKeepsResult() = runTest {
        val host = SysuFakeHost()
        val hooks = hooks(host)
        hooks.beforeToolCall(call("sysu sso https://example.sysu.edu.cn/cas --json"))
        val decision = hooks.afterToolExecution(
            result(output = "ticket=TICKET-123\nTGC=abc123def\n其它无关输出\n\n[exit: 0]"),
        )
        assertEquals(ToolResultAction.REPLACE, decision.action)
        val text = decision.result?.text.orEmpty()
        assertFalse(text.contains("TGC"))
        assertFalse(text.contains("tgc"))
        assertFalse(text.contains("abc123def"))
        assertTrue(text.contains("其它无关输出"))
        assertFalse(text.contains("卡片"))
        val raw = decision.result?.details?.get("deepdeep.raw")?.jsonPrimitive?.content
        assertNotNull(raw)
        assertFalse(raw!!.contains("TGC"))
        assertFalse(raw.contains("abc123def"))
    }

    @Test
    fun legacyNetpayUndefinedOutputBecomesJsonHint() = runTest {
        val host = SysuFakeHost()
        val hooks = hooks(host)
        hooks.beforeToolCall(call("sysu netpay"))
        val decision = hooks.afterToolExecution(
            result(output = "\u001B[1mundefined\u001B[0m  ·    ·  \n\u001B[31m名下没有网络服务\u001B[0m\n\n[exit: 0]"),
        )
        assertEquals(ToolResultAction.REPLACE, decision.action)
        val text = decision.result?.text.orEmpty()
        assertTrue(text.contains("sysu netpay --json"))
        assertFalse(text.contains("undefined"))
        assertFalse(text.contains("·  ·"))
        assertNotNull(decision.result?.details?.get("deepdeep.raw")?.jsonPrimitive?.content)
    }

    @Test
    fun replacesObservationEvenWhenSessionOutputHasRenewalNoise() = runTest {
        val host = SysuFakeHost()
        val hooks = hooks(host)
        hooks.beforeToolCall(call("sysu jwxt schedule --json"))
        val output = "（会话已用 TGC 静默续期）\n" + SysuFixtures.bashOutput("schedule.json")
        val decision = hooks.afterToolExecution(result(output = output))
        assertEquals(ToolResultAction.REPLACE, decision.action)
        val text = decision.result?.text.orEmpty()
        assertTrue(text.startsWith("课表"))
        assertFalse(text.contains("TGC"))
        assertFalse(text.contains("卡片"))
        assertEquals(6, card(decision)?.sections?.sumOf { it.items.size })
    }

    @Test
    fun ignoresOtherTools() = runTest {
        val host = SysuFakeHost()
        val hooks = hooks(host)
        val nonBash = ToolExecutionResult(
            sessionId = sessionId,
            callId = "call-2",
            toolName = "read",
            result = textResult("sysu jwxt --json"),
        )
        val decision = hooks.afterToolExecution(nonBash)
        assertEquals(ToolResultAction.KEEP, decision.action)
        assertNull(decision.result)
    }
}
