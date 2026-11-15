package io.github.whywood.deepdeep.plugin.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CliJsonEnvelopeTest {
    @Test
    fun extractsEnvelopeWithAnsiAndDotenvNoise() {
        val output = "\u001B[32mINFO\u001B[0m log line\nFOO=1\n" +
            """{"ok":true,"data":{"chats":[1,2,3]}}""" + "\n[exit: 0]"
        val envelope = CliJsonEnvelope.DEFAULT.find(output)
        assertNotNull(envelope)
        assertTrue(CliJsonEnvelope.DEFAULT.isSuccess(envelope!!))
        assertEquals(3, (envelope["data"] as JsonObject)["chats"]!!.jsonArray.size)
    }

    @Test
    fun extractsEnvelopeWhenContentHasBraces() {
        val body = """{"ok":true,"data":{"messages":[{"content":"评价 {不是 JSON} 也要能解析"}]}}"""
        val envelope = CliJsonEnvelope.DEFAULT.find("command output:\n$body\n[exit: 0]")
        assertNotNull(envelope)
        val data = envelope!!["data"] as JsonObject
        assertEquals(1, data["messages"]!!.jsonArray.size)
    }

    @Test
    fun stripsNoticeField() {
        val envelope = CliJsonEnvelope.DEFAULT.find("""{"ok":true,"_notice":"x","data":{}}""")
        assertNotNull(envelope)
        assertTrue(CliJsonEnvelope.DEFAULT.hasNotice(envelope!!))
        val stripped = CliJsonEnvelope.DEFAULT.stripNotice(envelope)
        assertNull(stripped["_notice"])
        assertTrue(CliJsonEnvelope.DEFAULT.isSuccess(stripped))
    }

    @Test
    fun malformedOrErrorOutputIsIgnored() {
        assertNull(CliJsonEnvelope.DEFAULT.find(""))
        assertNull(CliJsonEnvelope.DEFAULT.find("not json at all"))
        assertNull(CliJsonEnvelope.DEFAULT.find("""{"ok":"""))
        val error = CliJsonEnvelope.DEFAULT.find("""{"ok":false,"error":{"message":"invalid token"}}""")
        assertNotNull(error)
        assertFalse(CliJsonEnvelope.DEFAULT.isSuccess(error!!))
    }

    @Test
    fun customSuccessFieldIsHonored() {
        val envelope = CliJsonEnvelope(successField = "success")
        val parsed = envelope.find("""{"success":true,"data":{}}""")
        assertNotNull(parsed)
        assertTrue(envelope.isSuccess(parsed!!))
        assertNull(envelope.find("""{"ok":true}"""))
    }
}

class CliJsonEnvelopeElementTest {
    @Test
    fun extractsBareObjectWithoutSuccessField() {
        val element = CliJsonEnvelope.DEFAULT.element("log line\n{\"items\":[{\"id\":1}]}\n[exit: 0]")
        assertNotNull(element)
        assertTrue(element is JsonObject)
        assertEquals(1, (element as JsonObject).objectList("items").size)
    }

    @Test
    fun extractsTopLevelArrayAfterNoiseAndDotenv() {
        val output = "\u001B[32mINFO\u001B[0m\nFOO=1\n" +
            """[{"id":1},{"id":2}]""" + "\n[exit: 0]"
        val element = CliJsonEnvelope.DEFAULT.element(output)
        assertNotNull(element)
        assertTrue(element is JsonArray)
        assertEquals(2, (element as JsonArray).size)
    }

    @Test
    fun skipsMalformedCandidateAndFindsNextElement() {
        val element = CliJsonEnvelope.DEFAULT.element("""prefix {"broken": [} then {"ok":true}""")
        assertNotNull(element)
        assertEquals(true, (element as JsonObject).boolValue("ok"))
    }

    @Test
    fun returnsNullForNonJsonOutput() {
        assertNull(CliJsonEnvelope.DEFAULT.element(""))
        assertNull(CliJsonEnvelope.DEFAULT.element("not json at all"))
    }

    @Test
    fun lenientEnvelopeTreatsBareObjectAsSuccess() {
        val envelope = CliJsonEnvelope.LENIENT
        assertNull(envelope.successField)
        val found = envelope.find("""{"items":[]}""")
        assertNotNull(found)
        assertTrue(envelope.isSuccess(found!!))
        assertNull(envelope.find("""[1,2]"""))
    }

    @Test
    fun defaultEnvelopeStillRequiresSuccessField() {
        assertNull(CliJsonEnvelope.DEFAULT.find("""{"items":[]}"""))
        val ok = CliJsonEnvelope.DEFAULT.find("""{"ok":true}""")
        assertNotNull(ok)
    }
}

class CliJsonExtensionsTest {
    @Test
    fun readsPrimitivesAndContainersSafely() {
        val root = Json.parseToJsonElement(
            """{"text":"a","blank":"  ","number":3,"flag":true,"object":{"x":1},"items":[{"y":2},"bad"]}""",
        ).jsonObject
        assertEquals("a", root.stringValue("text"))
        assertNull(root.stringValue("blank"))
        assertEquals(3, root.intValue("number"))
        assertEquals(true, root.boolValue("flag"))
        assertEquals(1, root.objValue("object")!!.intValue("x"))
        assertEquals(1, root.objectList("items").size)
        assertEquals(2, root.arrayValue("items").size)
        assertNull(root.objValue("missing"))
    }
}
