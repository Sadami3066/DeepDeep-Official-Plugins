package io.github.whywood.deepdeep.plugin.wecom

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WecomCardPayloadsTest {
    @Test
    fun groupsPayloadRoundTripsThroughJson() {
        val parsed = parsedOf(WecomCommandKind.CHAT_GROUPS, "chat-groups.json")
        val payload = WecomCardPayloads.build(WecomCommand(WecomCommandKind.CHAT_GROUPS), parsed)
        assertEquals(WecomCardState.READY, payload.state)
        assertEquals(2, payload.count)
        assertEquals("产品讨论群", payload.rows.first().title)
        assertTrue(payload.rows.first().subtitle!!.contains("最近消息"))
        assertEquals("42", payload.rows.first().value)
        val decoded = WecomCardPayloads.decode(WecomCardPayloads.encode(payload))
        assertNotNull(decoded)
        assertEquals(payload.count, decoded!!.count)
        assertEquals(payload.rows.size, decoded.rows.size)
        assertEquals("count", decoded.rows.first().valueKind)
    }

    @Test
    fun docContentPayloadKeepsSummaryAndOpenAction() {
        val parsed = parsedOf(WecomCommandKind.DOC_CONTENT, "doc-content.json")
        val payload = WecomCardPayloads.build(
            WecomCommand(WecomCommandKind.DOC_CONTENT, docId = "DOC_MOCK_001"),
            parsed,
        )
        assertEquals("企业微信接入方案", payload.headerDetail)
        assertEquals("版本 12", payload.headerTrailing)
        assertEquals("https://doc.weixin.qq.com/doc/MOCK001", payload.actionUrl)
        assertTrue(payload.summary!!.length <= 241)
        assertTrue(payload.rows.isEmpty())
        assertEquals("DOC_MOCK_001", payload.context)
    }

    @Test
    fun docsPayloadRowsOpenOfficialLinks() {
        val parsed = parsedOf(WecomCommandKind.DOC_SEARCH, "doc-search.json")
        val payload = WecomCardPayloads.build(WecomCommand(WecomCommandKind.DOC_SEARCH), parsed)
        assertEquals("https://doc.weixin.qq.com/doc/MOCK001", payload.rows.first().url)
        assertEquals("doc_type", payload.rows.first().valueKind)
        assertEquals("doc", payload.rows.first().value)
    }

    @Test
    fun emptyResultBecomesEmptyState() {
        val parsed = WecomParsedParser.parse(WecomCommandKind.TODO_LIST, json("""{"items":[],"items_count":0}"""))!!
        val payload = WecomCardPayloads.build(WecomCommand(WecomCommandKind.TODO_LIST), parsed)
        assertEquals(WecomCardState.EMPTY, payload.state)
        assertEquals(0, payload.count)
        assertTrue(payload.rows.isEmpty())
    }

    @Test
    fun paginationNoteCarriesCursor() {
        val parsed = parsedOf(WecomCommandKind.CHAT_MESSAGES, "chat-messages.json")
        val payload = WecomCardPayloads.build(WecomCommand(WecomCommandKind.CHAT_MESSAGES), parsed)
        assertTrue(payload.note!!.contains("CURSOR_MOCK_PAGE_2"))
    }

    @Test
    fun invalidPayloadDecodesToNull() {
        assertNull(WecomCardPayloads.decode(json("""{"state":"ready"}""")))
        assertNull(WecomCardPayloads.decode(json("""{"state":"unknown","count":1}""")))
        assertNull(WecomCardPayloads.decode(json("""{"count":1}""")))
        assertNull(WecomCardPayloads.decode(json("""{}""")))
    }

    @Test
    fun errorAndLoadingPayloadsRenderable() {
        val error = WecomCardPayloads.decode(WecomCardPayloads.encode(WecomCardPayloads.errorPayload("未授权")))
        assertEquals(WecomCardState.ERROR, error!!.state)
        assertEquals("未授权", error.errorMessage)
        val loading = WecomCardPayloads.decode(WecomCardPayloads.encode(WecomCardPayloads.loadingPayload("群聊")))
        assertEquals(WecomCardState.LOADING, loading!!.state)
        val empty = WecomCardPayloads.decode(WecomCardPayloads.encode(WecomCardPayloads.emptyPayload("群聊")))
        assertEquals(WecomCardState.EMPTY, empty!!.state)
    }

    private fun parsedOf(kind: WecomCommandKind, fixture: String): WecomParsed {
        val body = javaClass.getResourceAsStream("/fixtures/cli/$fixture")?.bufferedReader()?.use { it.readText() }
            ?: error("missing fixture $fixture")
        return WecomParsedParser.parse(kind, json(body)) ?: error("parse failed")
    }

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text) as JsonObject
}
