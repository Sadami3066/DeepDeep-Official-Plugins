package io.github.whywood.deepdeep.plugin.sysu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SysuFailuresTest {
    @Test
    fun tgcFailureBecomesActionableLoginMessage() {
        val message = SysuFailures.actionable(
            "会话失效，自动续期失败（TGC 已失效）。重新登录：sysu login",
            ChineseStrings,
        )
        assertNotNullMessage(message)
        assertTrue(message!!.contains("插件页重新登录"))
        assertFalse(message.contains("TGC"))
        assertFalse(message.contains("tgc"))
    }

    @Test
    fun verificationCodeFailureMentionsCodePage() {
        val message = SysuFailures.actionable("二次验证没过：企微验证码没发出去", ChineseStrings)
        assertNotNullMessage(message)
        assertTrue(message!!.contains("验证码"))
        assertTrue(message.contains("插件页"))
        assertFalse(message.contains("TGC"))
    }

    @Test
    fun unrelatedFailureHasNoActionMapping() {
        assertNull(SysuFailures.actionable("HTTP 500 · /timetable", ChineseStrings))
        assertNull(SysuFailures.actionable("", ChineseStrings))
        assertNull(SysuFailures.actionable(null, ChineseStrings))
    }

    @Test
    fun legacyUndefinedNetpayOutputGetsJsonHint() {
        val hint = SysuFailures.legacyHint(
            "undefined  ·    ·  \n名下没有网络服务",
            ChineseStrings,
        )
        assertNotNullMessage(hint)
        assertTrue(hint!!.contains("sysu netpay --json"))
        assertFalse(hint.contains("undefined"))
    }

    @Test
    fun legacyHintIgnoresValidNetpayText() {
        assertNull(
            SysuFailures.legacyHint(
                "张三  ·  00000000  ·  计算机学院\n校园网-学生宿舍  正常  到期 2027-06-30",
                ChineseStrings,
            ),
        )
    }

    @Test
    fun redactsTicketsAndSessionData() {
        val safe = SysuFailures.redactSensitive(
            "ticket=TICKET-123\nTGC=abc123def\n其它无关输出",
            ChineseStrings,
        )
        assertNotNullMessage(safe)
        assertFalse(safe!!.contains("TGC"))
        assertFalse(safe.contains("tgc"))
        assertFalse(safe.contains("abc123def"))
        assertFalse(safe.contains("TICKET-123"))
        assertTrue(safe.contains("其它无关输出"))
    }

    @Test
    fun cleanOutputIsNotRewritten() {
        assertNull(SysuFailures.redactSensitive("课表 · 2026-1 · 第 10 周", ChineseStrings))
        assertNull(SysuFailures.redactSensitive("", ChineseStrings))
    }

    private fun assertNotNullMessage(message: String?) {
        assertTrue("expected a mapped message", message != null)
    }
}
