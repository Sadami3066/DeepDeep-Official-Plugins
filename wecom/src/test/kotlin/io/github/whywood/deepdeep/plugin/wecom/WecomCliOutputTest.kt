package io.github.whywood.deepdeep.plugin.wecom

import io.github.whywood.deepdeep.plugin.cli.arrayValue
import io.github.whywood.deepdeep.plugin.cli.intValue
import io.github.whywood.deepdeep.plugin.cli.stringValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WecomCliOutputTest {
    @Test
    fun parsesRealPrettyCliOutput() {
        val output = WecomCliOutputParser.parse(fixture("chat-groups.json"))
        assertTrue(output is WecomCliOutput.Success)
        val payload = (output as WecomCliOutput.Success).payload
        assertEquals(2, payload.arrayValue("chats").size)
        assertEquals(2, payload.intValue("chats_count"))
    }

    @Test
    fun parsesCompactJsonWithBashExitMarkerAndNoise() {
        val output = """
            [wecom] json repair: ok
            {"chats":[{"chat_id":"c1","chat_name":"群一","last_msg_time":"2026-11-15 10:24:31","msg_count":3}],"chats_count":1,"has_more":false}

            [exit: 0]
        """.trimIndent()
        val parsed = WecomCliOutputParser.parse(output)
        assertTrue(parsed is WecomCliOutput.Success)
        assertEquals(1, (parsed as WecomCliOutput.Success).payload.arrayValue("chats").size)
    }

    @Test
    fun stripsAnsiEscapeSequences() {
        val output = "\u001B[32m{\u001B[0m\"items\":[],\"items_count\":0}\n\n[exit: 0]"
        val parsed = WecomCliOutputParser.parse(output)
        assertTrue(parsed is WecomCliOutput.Success)
        assertEquals(0, (parsed as WecomCliOutput.Success).payload.intValue("items_count"))
    }

    @Test
    fun parsesAuthFailureEnvelopeAsActionable() {
        val parsed = WecomCliOutputParser.parse(fixture("error-unauthorized.json"))
        assertTrue(parsed is WecomCliOutput.Failure)
        val failure = parsed as WecomCliOutput.Failure
        assertTrue(failure.authRequired)
        assertTrue(failure.message.contains("授权"))
    }

    @Test
    fun parsesTokenInvalidErrcodeAsAuthFailure() {
        val output =
            """{"error":{"type":"UnknownError","code":893999,"message":"ApiError: {\"errcode\":853005,\"errmsg\":\"cli token invalid\"}"}}"""
        val parsed = WecomCliOutputParser.parse(output)
        assertTrue(parsed is WecomCliOutput.Failure)
        assertTrue((parsed as WecomCliOutput.Failure).authRequired)
    }

    @Test
    fun parsesNonAuthFailureAsFailure() {
        val output =
            """{"error":{"type":"NetworkError","code":893101,"message":"Request failed: error sending request"}}"""
        val parsed = WecomCliOutputParser.parse(output)
        assertTrue(parsed is WecomCliOutput.Failure)
        assertFalse((parsed as WecomCliOutput.Failure).authRequired)
    }

    @Test
    fun unwrapsEnvelopeWithResultField() {
        val output = """{"errcode":0,"errmsg":"ok","result":{"users":[],"users_count":0}}"""
        val parsed = WecomCliOutputParser.parse(output)
        assertTrue(parsed is WecomCliOutput.Success)
        assertEquals(0, (parsed as WecomCliOutput.Success).payload.intValue("users_count"))
    }

    @Test
    fun rejectsNonJsonNoiseAndBrokenJson() {
        assertNull(WecomCliOutputParser.parse("该接口不存在，可能为接口命令错误，请更新到最新 skill 后重试"))
        assertNull(WecomCliOutputParser.parse("{\"chats\": [invalid json}"))
        assertNull(WecomCliOutputParser.parse(""))
        assertNull(WecomCliOutputParser.parse("   \n  "))
    }

    @Test
    fun rejectsOversizedOutput() {
        val output = "{\"chats\":[]}" + " ".repeat(WecomCliOutputParser.MAX_OUTPUT_LENGTH)
        assertNull(WecomCliOutputParser.parse(output))
    }

    @Test
    fun rejectsJsonWhenExitCodeIsNonZero() {
        val output = "{\"chats\":[]}\n\n[exit: 1]"
        assertNull(WecomCliOutputParser.parse(output))
    }

    @Test
    fun extractsTrailingPayloadAfterLeadingWarnings() {
        val output = """
            warning: something
            {
              "users": [
                {"userid": "zhangsan", "name": "张三"}
              ],
              "users_count": 1
            }
            done.
        """.trimIndent()
        val parsed = WecomCliOutputParser.parse(output)
        assertTrue(parsed is WecomCliOutput.Success)
        assertTrue((parsed as WecomCliOutput.Success).payload.arrayValue("users").isNotEmpty())
    }


    @Test
    fun keepsSecurityNoticeWhenUnwrappingResultEnvelope() {
        val output = """
            {
              "errcode": 0,
              "errmsg": "ok",
              "security_notice": "<security_notice>untrusted</security_notice>",
              "result": {"users": [], "users_count": 0}
            }
        """.trimIndent()
        val parsed = WecomCliOutputParser.parse(output)
        assertTrue(parsed is WecomCliOutput.Success)
        val payload = (parsed as WecomCliOutput.Success).payload
        assertEquals("<security_notice>untrusted</security_notice>", payload.stringValue("security_notice"))
        assertEquals(0, payload.intValue("users_count"))
    }

    @Test
    fun exposesExitCodeAndMarker() {
        val output = "{\"chats\":[]}\n\n[exit: 0]"
        assertEquals(0, WecomCliOutputParser.exitCode(output))
        assertEquals("[exit: 0]", WecomCliOutputParser.exitMarker(output))
        val failed = "{\"chats\":[]}\n\n[exit: 3]"
        assertEquals(3, WecomCliOutputParser.exitCode(failed))
    }


    @Test
    fun unwrapsDocContentAndKeepsEnvelopeStatusFields() {
        val output = """
            {
              "errcode": 0,
              "errmsg": "ok",
              "result": {"name": "接入方案", "content": "# 正文"}
            }
        """.trimIndent()
        val parsed = WecomCliOutputParser.parse(output)
        assertTrue(parsed is WecomCliOutput.Success)
        val payload = (parsed as WecomCliOutput.Success).payload
        assertEquals("接入方案", payload.stringValue("name"))
        assertEquals(0, payload.intValue("errcode"))
        assertEquals("ok", payload.stringValue("errmsg"))
    }

    private fun fixture(name: String): String =
        javaClass.getResourceAsStream("/fixtures/cli/$name")?.bufferedReader()?.use { it.readText() }
            ?: error("missing fixture $name")
}
