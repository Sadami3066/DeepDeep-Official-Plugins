package io.github.whywood.deepdeep.plugin.sysu

import io.github.whywood.deepdeep.plugin.api.textResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SysuBridgePlanTest {
    private fun plan(command: String, output: String, isError: Boolean = false): SysuDecision {
        val invocation = SysuCommandParser.parse(command) ?: error("not a sysu command: $command")
        return SysuBridgePlanner.plan(invocation, textResult(output, isError = isError), ChineseStrings)
    }

    private fun replaced(command: String, output: String, isError: Boolean = false): SysuDecision.Replace {
        val decision = plan(command, output, isError)
        assertTrue("expected replacement, got $decision", decision is SysuDecision.Replace)
        return decision as SysuDecision.Replace
    }

    @Test
    fun replacesSuccessfulScheduleWithObservationAndCard() {
        val decision = replaced("sysu jwxt --json", SysuFixtures.bashOutput("schedule.json"))
        assertFalse(decision.isError)
        assertTrue(decision.text.contains("2026-11-09"))
        assertNotNull(decision.card)
        assertEquals(SysuCardState.READY, decision.card?.state)
        assertFalse(decision.text.contains("TGC"))
        assertFalse(decision.text.contains("卡片"))
        assertFalse(decision.text.contains("undefined"))
    }

    @Test
    fun keepsUnsupportedSuccessfulCommandUntouched() {
        assertEquals(SysuDecision.Keep, plan("sysu jwxt course LN3015 --json", "课程信息：LN3015\n\n[exit: 0]"))
        assertEquals(SysuDecision.Keep, plan("sysu netpay orders --json", "[]\n\n[exit: 0]"))
    }

    @Test
    fun keepsSupportedCommandWhenJsonIsUnparsable() {
        assertEquals(SysuDecision.Keep, plan("sysu jwxt scores --json", "（无输出）\n\n[exit: 0]"))
    }

    @Test
    fun turnsTgcFailureIntoActionableLoginMessage() {
        val decision = replaced(
            "sysu jwxt --json",
            "会话失效，自动续期失败（TGC 已失效）。重新登录：sysu login\n\n[exit: 1]",
            isError = true,
        )
        assertTrue(decision.isError)
        assertTrue(decision.text.contains("插件页重新登录"))
        assertFalse(decision.text.contains("TGC"))
        assertFalse(decision.text.contains("tgc"))
        assertNull(decision.card)
    }

    @Test
    fun turnsVerificationCodeFailureIntoActionableMessage() {
        val decision = replaced(
            "sysu login --code 1234",
            "二次验证没过：企微验证码没发出去\n\n[exit: 1]",
            isError = true,
        )
        assertTrue(decision.isError)
        assertTrue(decision.text.contains("验证码"))
        assertTrue(decision.text.contains("插件页"))
        assertFalse(decision.text.contains("TGC"))
    }

    @Test
    fun businessAuthFailureInNetpayDoesNotLeakWebMessage() {
        val decision = replaced("sysu netpay --json", SysuFixtures.bashOutput("netpay_expired.json"))
        assertTrue(decision.isError)
        assertTrue(decision.text.contains("插件页重新登录"))
        assertFalse(decision.text.contains("undefined"))
        assertFalse(decision.text.contains("登陆信息"))
        assertEquals(SysuCardState.ERROR, decision.card?.state)
    }

    @Test
    fun keepsUnrelatedFailureText() {
        assertEquals(SysuDecision.Keep, plan("sysu jwxt exams --json", "HTTP 500 · /timetable\n\n[exit: 1]", isError = true))
    }

    @Test
    fun redactsSensitiveOutputForOtherCommands() {
        val decision = replaced(
            "sysu sso https://example.sysu.edu.cn/cas --json",
            "ticket=TICKET-123\nTGC=abc123def\n其它无关输出\n\n[exit: 0]",
        )
        assertFalse(decision.isError)
        assertFalse(decision.text.contains("TGC"))
        assertFalse(decision.text.contains("tgc"))
        assertFalse(decision.text.contains("abc123def"))
        assertTrue(decision.text.contains("其它无关输出"))
        assertFalse(decision.text.contains("卡片"))
    }

    @Test
    fun redactsSensitiveContentInSuccessfulSupportedOutput() {
        val output = "{\"unexpected\":\"TGC=abc\"}\n\n[exit: 0]"
        val decision = replaced("sysu jwxt credits --json", output)
        assertFalse(decision.text.contains("TGC"))
        assertFalse(decision.text.contains("tgc"))
    }

    @Test
    fun keepsOtherCommandWithPlainOutput() {
        assertEquals(SysuDecision.Keep, plan("sysu gym mine --json", "{\"ok\":true,\"bookings\":[]}\n\n[exit: 0]"))
    }

    @Test
    fun turnsLegacyNetpayUndefinedOutputIntoJsonHint() {
        val decision = replaced(
            "sysu netpay",
            "\u001B[1mundefined\u001B[0m  ·    ·  \n\u001B[31m名下没有网络服务\u001B[0m\n\n[exit: 0]",
        )
        assertTrue(decision.text.contains("sysu netpay --json"))
        assertFalse(decision.text.contains("undefined"))
        assertFalse(decision.text.contains("·  ·"))
        assertNull(decision.card)
    }

    @Test
    fun leavesValidLegacyNetpayTextAlone() {
        assertEquals(SysuDecision.Keep, plan("sysu netpay", "张三  ·  00000000  ·  计算机学院\n校园网-学生宿舍  正常  到期 2027-06-30\n\n[exit: 0]"))
    }
}
