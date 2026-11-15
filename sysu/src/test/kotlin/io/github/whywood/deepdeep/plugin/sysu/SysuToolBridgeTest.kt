package io.github.whywood.deepdeep.plugin.sysu

import io.github.whywood.deepdeep.plugin.api.RunFinishedEvent
import io.github.whywood.deepdeep.plugin.api.ToolCallAction
import io.github.whywood.deepdeep.plugin.api.ToolCallRequest
import io.github.whywood.deepdeep.plugin.api.ToolExecutionResult
import io.github.whywood.deepdeep.plugin.api.ToolResultAction
import io.github.whywood.deepdeep.plugin.api.textResult
import io.github.whywood.deepdeep.plugin.cli.bridge.CliBridgeConfig
import io.github.whywood.deepdeep.plugin.cli.card.CliCardCodec
import io.github.whywood.deepdeep.plugin.cli.card.CliCardState
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
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

    private val hint: String = CliBridgeConfig.DEFAULT_CARD_HINT.resolve("zh")!!

    @Test
    fun replacesScheduleWithObservationAndCardAndHint() = runTest {
        val host = SysuFakeHost()
        val hooks = hooks(host)
        hooks.beforeToolCall(call("sysu jwxt schedule --json"))
        val decision = hooks.afterToolExecution(result(output = SysuFixtures.bashOutput("schedule.json")))
        assertEquals(ToolResultAction.REPLACE, decision.action)
        val text = decision.result?.text.orEmpty()
        assertTrue(text.startsWith("课表 · 2026-1 · 第 10 周"))
        assertTrue(text.endsWith(hint))
        assertFalse(text.contains("TGC"))
        assertEquals(1, host.posted.size)
        val post = host.posted.single()
        assertEquals("sysu.card", post.customType)
        assertEquals(sessionId, post.sessionId)
        val card = CliCardCodec.decode(post.payload, SYSU_CARD_KINDS)
        assertNotNull(card)
        assertEquals(SysuCardKind.Schedule.wire, card!!.kind)
        assertEquals("课表", card.title)
        assertEquals("6 门", card.trailing)
        assertEquals("周一 2026-11-09", card.sections.first().label)
        assertTrue(card.sections.sumOf { it.items.size } <= CliCardCodec.MAX_ROWS)
    }

    @Test
    fun replacesTextWithoutHintWhenCardPostFails() = runTest {
        val host = SysuFakeHost(cardSucceeds = false)
        val hooks = hooks(host)
        hooks.beforeToolCall(call("sysu jwxt scores --json"))
        val decision = hooks.afterToolExecution(result(output = SysuFixtures.bashOutput("scores.json")))
        assertEquals(ToolResultAction.REPLACE, decision.action)
        val text = decision.result?.text.orEmpty()
        assertTrue(text.startsWith("成绩 · 2025-2"))
        assertFalse(text.endsWith(hint))
        assertFalse(text.contains("{\"semester\""))
        assertEquals(1, host.posted.size)
    }

    @Test
    fun netpayExpiredSessionIsErrorWithoutHintButWithErrorCard() = runTest {
        val host = SysuFakeHost()
        val hooks = hooks(host)
        hooks.beforeToolCall(call("sysu netpay --json"))
        val decision = hooks.afterToolExecution(result(output = SysuFixtures.bashOutput("netpay_expired.json")))
        assertEquals(ToolResultAction.REPLACE, decision.action)
        assertTrue(decision.result?.isError == true)
        val text = decision.result?.text.orEmpty()
        assertTrue(text.contains("插件页重新登录"))
        assertFalse(text.contains("undefined"))
        assertFalse(text.endsWith(hint))
        val card = CliCardCodec.decode(host.posted.single().payload, SYSU_CARD_KINDS)
        assertEquals(CliCardState.ERROR, card?.state)
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
        assertTrue(text.contains("插件页重新登录"))
        assertFalse(text.contains("TGC"))
        assertFalse(text.contains("tgc"))
        assertTrue(host.posted.isEmpty())
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
        assertEquals(
            ToolResultAction.KEEP,
            hooks.afterToolExecution(result(output = "课程信息：LN3015\n\n[exit: 0]")).action,
        )
        assertEquals(ToolCallAction.CONTINUE, hooks.beforeToolCall(call("lark-cli im +chat-list", "call-2")).action)
        assertEquals(
            ToolResultAction.KEEP,
            hooks.afterToolExecution(result("call-2", output = """{"ok":true,"data":[]}""")).action,
        )
        assertEquals(host.posted.size, 0)
    }

    @Test
    fun keepsSupportedCommandWhenPayloadIsMissing() = runTest {
        val host = SysuFakeHost()
        val hooks = hooks(host)
        hooks.beforeToolCall(call("sysu jwxt scores --json"))
        val decision = hooks.afterToolExecution(result(output = "（无输出）\n\n[exit: 0]"))
        assertEquals(ToolResultAction.KEEP, decision.action)
        assertTrue(host.posted.isEmpty())
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
        assertTrue(host.posted.isEmpty())
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
        assertTrue(text.endsWith(hint))
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
        assertEquals(ToolResultAction.KEEP, hooks.afterToolExecution(nonBash).action)
        assertTrue(host.posted.isEmpty())
    }
}
