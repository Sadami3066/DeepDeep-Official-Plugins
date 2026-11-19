package io.github.whywood.deepdeep.plugin.sysu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SysuCliTest {
    @Test
    fun bundledCliVersionIsCurrent() {
        assertEquals("3.5.1", SysuCli.VERSION)
    }

    private val statusOutput = """
        @@node@@
        v24.16.0
        @@cli@@
        3.4.3
        @@session@@
        {"cookies":[{"name":"LYSESSIONID","value":"a"}],"tgc":"t"}
        @@lms@@
        {"cookies":[{"name":"MoodleSession","value":"b"}]}
        @@gym@@
        {"access_token":"token","at":1700000000000}
        @@netpay@@
        {"cookies":[]}
        @@credentials@@
        netid=zhangsan
        password=secret
        @@end@@
    """.trimIndent()

    @Test
    fun parsesFourSiteStatusSectionsWithoutBleeding() {
        val raw = SysuCli.parseStatus(statusOutput)
        assertEquals("v24.16.0", raw.node)
        assertEquals("3.4.3", raw.cli)
        assertEquals("netid=zhangsan\npassword=secret", raw.credentials)
        assertTrue(raw.connected(SysuSite.Jwxt))
        assertTrue(raw.connected(SysuSite.Lms))
        assertTrue(raw.connected(SysuSite.Gym))
        assertFalse(raw.connected(SysuSite.Netpay))
    }

    @Test
    fun missingSiteFilesReadAsDisconnected() {
        val raw = SysuCli.parseStatus("")
        assertFalse(raw.connected(SysuSite.Jwxt))
        assertFalse(raw.connected(SysuSite.Lms))
        assertFalse(raw.connected(SysuSite.Gym))
        assertFalse(raw.connected(SysuSite.Netpay))
        assertFalse(SysuCli.hasCookies(""))
        assertFalse(SysuCli.hasCookies("{}"))
        assertFalse(SysuCli.hasGymToken("{}"))
        assertTrue(SysuCli.hasGymToken("""{"access_token":"x"}"""))
    }

    @Test
    fun statusCommandReadsAllSiteSessions() {
        val command = SysuCli.statusCommand()
        assertTrue(command.contains(SysuCli.SESSION_PATH))
        assertTrue(command.contains(SysuCli.LMS_PATH))
        assertTrue(command.contains(SysuCli.GYM_PATH))
        assertTrue(command.contains(SysuCli.NETPAY_PATH))
    }

    @Test
    fun logoutClearsAllSiteSessions() {
        val command = SysuCli.logoutCommand()
        assertTrue(command.contains(SysuCli.SESSION_PATH))
        assertTrue(command.contains(SysuCli.MFA_PATH))
        assertTrue(command.contains(SysuCli.LMS_PATH))
        assertTrue(command.contains(SysuCli.GYM_PATH))
        assertTrue(command.contains(SysuCli.GYM_JAR_PATH))
        assertTrue(command.contains(SysuCli.NETPAY_PATH))
    }

    @Test
    fun loginSequenceCoversAllFourSites() {
        assertEquals(
            listOf(
                "sysu login --send-code --json",
                "sysu lms login --json",
                "sysu gym login --json",
                "sysu netpay login --json",
            ),
            SysuCli.siteLoginSequence(),
        )
        assertEquals("sysu login --code 1234 --json", SysuSite.Jwxt.loginCodeCommand("1234"))
        assertEquals("sysu lms login --code 1234 --json", SysuSite.Lms.loginCodeCommand("1234"))
        assertEquals("sysu gym login --code 1234 --json", SysuSite.Gym.loginCodeCommand("1234"))
        assertEquals("sysu netpay login --code 1234 --json", SysuSite.Netpay.loginCodeCommand("1234"))
    }

    @Test
    fun parsesLoginJsonForMfaAndSuccess() {
        val needCode = """
            {
              "ok": false,
              "needMfa": true,
              "hint": "验证码已发到企业微信，10 分钟内执行：sysu login --code <4 位>"
            }

            [exit: 0]
        """.trimIndent()
        assertTrue(SysuCli.needsCode(needCode))
        assertFalse(SysuCli.loginSucceeded(needCode))

        val success = "{\"ok\":true,\"needMfa\":false,\"already\":false,\"jwxt\":true,\"lms\":true,\"lmsNote\":null}\n\n[exit: 0]"
        assertFalse(SysuCli.needsCode(success))
        assertTrue(SysuCli.loginSucceeded(success))

        val failure = "{\"ok\":false,\"error\":{\"message\":\"二次验证没过：验证码错误\",\"code\":null}}\n\n[exit: 1]"
        assertFalse(SysuCli.needsCode(failure))
        assertFalse(SysuCli.loginSucceeded(failure))
        assertEquals("二次验证没过：验证码错误", SysuCli.loginErrorText(failure))
    }

    @Test
    fun detectsSiteLoginMfaJson() {
        val output = "{\"ok\":false,\"needMfa\":true,\"hint\":\"验证码已发到企业微信\",\"error\":{\"message\":\"这个站点需要企业微信二次验证\"}}\n\n[exit: 1]"
        assertTrue(SysuCli.needsCode(output))
        assertFalse(SysuCli.loginSucceeded(output))
    }

    @Test
    fun ignoresHumanReadableLoginText() {
        assertFalse(SysuCli.needsCode("✓ 登录态正常"))
        assertFalse(SysuCli.needsCode("✓ 不需要验证码"))
        assertFalse(SysuCli.loginSucceeded("✓ 登录态正常"))
    }

}
