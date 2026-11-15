package io.github.whywood.deepdeep.plugin.wecom

import io.github.whywood.deepdeep.plugin.cli.card.CliCardCodec
import io.github.whywood.deepdeep.plugin.cli.card.CliCardState
import io.github.whywood.deepdeep.plugin.cli.stringValue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WecomChatCardsTest {
    private val host = FakeWecomHost()

    @Test
    fun groupsCardRoundTripsThroughSharedCodec() {
        val parsed = parsedOf(WecomCommandKind.CHAT_GROUPS, "chat-groups.json")
        val card = WecomChatCards.build(host, WecomCommand(WecomCommandKind.CHAT_GROUPS), parsed)
        assertEquals(CliCardState.READY, card.state)
        assertEquals("wecom.chat.groups", card.kind)
        assertEquals(2, card.total)
        assertEquals("产品讨论群", card.items.first().title)
        assertTrue(card.items.first().subtitle!!.contains("最近消息"))
        assertEquals("42 条", card.items.first().meta)
        val decoded = CliCardCodec.decode(CliCardCodec.encode(card), supportedKinds())
        assertEquals(card.kind, decoded?.kind)
        assertEquals(card.total, decoded?.total)
        assertEquals(card.items.size, decoded?.items?.size)
        assertEquals("42 条", decoded?.items?.first()?.meta)
    }

    @Test
    fun docContentCardKeepsHeaderTrailingAndSummary() {
        val parsed = parsedOf(WecomCommandKind.DOC_CONTENT, "doc-content.json")
        val card = WecomChatCards.build(
            host,
            WecomCommand(WecomCommandKind.DOC_CONTENT, docId = "DOC_MOCK_001"),
            parsed,
        )
        assertEquals("企业微信接入方案", card.title)
        assertEquals("版本 12", card.trailing)
        assertEquals("DOC_MOCK_001", card.context)
        assertEquals("https://doc.weixin.qq.com/doc/MOCK001", card.openUrl)
        assertTrue(card.summary!!.length <= 241)
        assertTrue(card.items.isEmpty())
    }

    @Test
    fun docsCardRowsOpenOfficialLinksAndLabelDocType() {
        val parsed = parsedOf(WecomCommandKind.DOC_SEARCH, "doc-search.json")
        val card = WecomChatCards.build(host, WecomCommand(WecomCommandKind.DOC_SEARCH), parsed)
        assertEquals("https://doc.weixin.qq.com/doc/MOCK001", card.items.first().url)
        assertEquals("文档", card.items.first().meta)
    }

    @Test
    fun messageScheduleAndTodoFieldsDriveTheirOwnLayouts() {
        val messages = WecomChatCards.build(
            host,
            WecomCommand(WecomCommandKind.CHAT_MESSAGES, chatId = "wrOgQhDgAA001"),
            parsedOf(WecomCommandKind.CHAT_MESSAGES, "chat-messages.json"),
        )
        assertEquals("2026-11-15 10:24:31", messages.items.first().meta)
        assertEquals("[文件] 接口文档-v2.pdf", messages.items.last().subtitle)
        assertEquals("text", messages.items.first().badge)

        val schedules = WecomChatCards.build(
            host,
            WecomCommand(WecomCommandKind.CALENDAR_SCHEDULES),
            parsedOf(WecomCommandKind.CALENDAR_SCHEDULES, "calendar-schedules.json"),
        )
        assertEquals("2026-11-16", schedules.items.first().badge)
        assertEquals("10:00 - 11:00 · 3F 会议室 A", schedules.items.first().subtitle)
        assertEquals("+2", schedules.items.first().meta)

        val todos = WecomChatCards.build(
            host,
            WecomCommand(WecomCommandKind.TODO_LIST),
            parsedOf(WecomCommandKind.TODO_LIST, "todo-list.json"),
        )
        assertEquals("2026-11-16 18:00:00", todos.items.first().badge)
        assertEquals("李四", todos.items.first().subtitle)
        assertEquals("进行中", todos.items.first().meta)
    }

    @Test
    fun emptyResultBecomesEmptyState() {
        val parsed = WecomParsedParser.parse(
            WecomCommandKind.TODO_LIST,
            json("""{"items":[],"items_count":0}"""),
        )!!
        val card = WecomChatCards.build(host, WecomCommand(WecomCommandKind.TODO_LIST), parsed)
        assertEquals(CliCardState.EMPTY, card.state)
        assertEquals(0, card.total)
        assertTrue(card.items.isEmpty())
    }

    @Test
    fun paginationNoteCarriesCursor() {
        val parsed = parsedOf(WecomCommandKind.CHAT_MESSAGES, "chat-messages.json")
        val card = WecomChatCards.build(host, WecomCommand(WecomCommandKind.CHAT_MESSAGES), parsed)
        assertTrue(card.note!!.contains("CURSOR_MOCK_PAGE_2"))
    }

    @Test
    fun invalidPayloadDecodesToNull() {
        val kind = WecomCommandKind.CHAT_GROUPS
        assertNull(WecomChatCards.decode(host, json("""{"state":"ready"}"""), kind))
        assertNull(WecomChatCards.decode(host, json("""{"state":"unknown","count":1}"""), kind))
        assertNull(WecomChatCards.decode(host, json("""{"count":1}"""), kind))
        assertNull(WecomChatCards.decode(host, json("""{}"""), kind))
    }

    @Test
    fun legacyVersion230CardsStillRender() {
        val legacyGroups = json(
            """
            {
              "state": "ready",
              "count": 2,
              "context": "2026-11-08 至 2026-11-15",
              "note": null,
              "rows": [
                {
                  "title": "产品讨论群",
                  "subtitle": "最近消息 2026-11-15 10:24:31 · wrOgQhDgAAmockchatid001",
                  "value": "42",
                  "valueKind": "count"
                }
              ]
            }
            """.trimIndent(),
        )
        val groups = WecomChatCards.decode(host, legacyGroups, WecomCommandKind.CHAT_GROUPS)
        assertEquals(CliCardState.READY, groups?.state)
        assertEquals(2, groups?.total)
        assertEquals("产品讨论群", groups?.items?.first()?.title)
        assertEquals("42 条", groups?.items?.first()?.meta)
        assertEquals("2026-11-08 至 2026-11-15", groups?.context)

        val legacyDoc = json(
            """
            {
              "state": "ready",
              "count": 1,
              "headerDetail": "企业微信接入方案",
              "headerTrailing": "版本 12",
              "context": "DOC_MOCK_001",
              "summary": "企业微信接入方案摘要",
              "actionUrl": "https://doc.weixin.qq.com/doc/MOCK001",
              "rows": []
            }
            """.trimIndent(),
        )
        val doc = WecomChatCards.decode(host, legacyDoc, WecomCommandKind.DOC_CONTENT)
        assertEquals("企业微信接入方案", doc?.title)
        assertEquals("版本 12", doc?.trailing)
        assertEquals("企业微信接入方案摘要", doc?.summary)
        assertEquals("https://doc.weixin.qq.com/doc/MOCK001", doc?.openUrl)
    }

    @Test
    fun legacyErrorCardKeepsMessage() {
        val legacyError = json("""{"state":"error","count":0,"errorMessage":"未授权"}""")
        val card = WecomChatCards.decode(host, legacyError, WecomCommandKind.CHAT_GROUPS)
        assertEquals(CliCardState.ERROR, card?.state)
        assertEquals("未授权", card?.summary)
    }

    private fun parsedOf(kind: WecomCommandKind, fixture: String): WecomParsed {
        val body = wecomFixture(fixture)
        return WecomParsedParser.parse(kind, json(body)) ?: error("parse failed")
    }

    private fun supportedKinds(): Set<String> = WecomCommandKind.entries.map { it.customType }.toSet()

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text) as JsonObject
}
