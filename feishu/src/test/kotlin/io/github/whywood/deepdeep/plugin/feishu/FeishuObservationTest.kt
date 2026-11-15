package io.github.whywood.deepdeep.plugin.feishu

import io.github.whywood.deepdeep.plugin.cli.CliJsonEnvelope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeishuObservationTest {
    private val zhHost = FakeFeishuHost(language = "zh")

    private fun envelope(fixture: String) = CliJsonEnvelope.DEFAULT.find(loadFixture(fixture))!!

    @Test
    fun chatListObservationCarriesNamesIdsCountsAndLinks() {
        val observation = buildFeishuObservation(zhHost, FeishuCommand(FeishuCommandKind.CHAT_LIST), envelope("chats.json"))
        assertNotNull(observation)
        val text = observation!!.text
        assertTrue(text.contains("飞书聊天列表（共 3 个）"))
        assertTrue(text.contains("产品研发群"))
        assertTrue(text.contains("oc_5ce6d572455d361153b7cb51da133945"))
        assertTrue(text.contains("https://applink.feishu.cn/client/chat/open"))
        assertEquals(FeishuCardTypes.KIND_CHAT_LIST, observation.card.kind)
        assertEquals(3, observation.card.items.size)
        assertFalse(text.contains("卡片"))
    }

    @Test
    fun messagesObservationCarriesSendersTimesIdsAndLinks() {
        val command = FeishuCommand(
            FeishuCommandKind.CHAT_MESSAGES,
            chatId = "oc_5ce6d572455d361153b7cb51da133945",
        )
        val observation = buildFeishuObservation(zhHost, command, envelope("messages.json"))
        assertNotNull(observation)
        val text = observation!!.text
        assertTrue(text.contains("飞书消息（共 3 条）"))
        assertTrue(text.contains("李雷"))
        assertTrue(text.contains("2025-11-15 09:40"))
        assertTrue(text.contains("om_aa11bb22cc33dd44ee55ff6677889900"))
        assertTrue(text.contains("message_app_link", ignoreCase = false).not())
        assertTrue(text.contains("https://applink.feishu.cn/client/chat/open"))
        assertEquals(FeishuCardTypes.KIND_MESSAGES, observation.card.kind)
        assertEquals(3, observation.card.items.size)
        assertFalse(text.contains("卡片"))
    }

    @Test
    fun longInteractiveMessageShowsShortSummaryAndLinkOnly() {
        val payload = """
            {"ok":true,"identity":"user","data":{"messages":[{"message_id":"om_long1",
            "msg_type":"interactive","content":"${"很长的交互内容".repeat(80)}",
            "sender":{"id":"ou_1","name":"张三"},"create_time":"2025-11-15 09:40",
            "message_app_link":"https://applink.feishu.cn/client/chat/open?openChatId=oc_1"}],"total":1}}
        """.trimIndent()
        val envelope = CliJsonEnvelope.DEFAULT.find(payload)!!
        val command = FeishuCommand(FeishuCommandKind.CHAT_MESSAGES, chatId = "oc_1")
        val observation = buildFeishuObservation(zhHost, command, envelope)!!
        assertTrue(observation.text.contains("交互消息"))
        assertTrue(observation.text.contains("内容较长"))
        assertTrue(observation.text.contains("https://applink.feishu.cn/client/chat/open"))
        assertTrue(observation.card.items.single().title.length <= 61)
        assertEquals("https://applink.feishu.cn/client/chat/open?openChatId=oc_1", observation.card.items.single().url)
    }

    @Test
    fun agendaObservationCarriesTimesLocationsAndEventIds() {
        val command = FeishuCommand(FeishuCommandKind.CALENDAR_AGENDA, start = "2026-11-16", end = "2026-11-17")
        val observation = buildFeishuObservation(zhHost, command, envelope("agenda.json"))!!
        val text = observation.text
        assertTrue(text.contains("飞书日程（共 2 个）"))
        assertTrue(text.contains("产品周会"))
        assertTrue(text.contains("11-15 09:40"))
        assertTrue(text.contains("3F 会议室 A"))
        assertTrue(text.contains("event_id: e1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d"))
        assertTrue(text.contains("团队建设"))
        assertTrue(text.contains("全天"))
        assertEquals(FeishuCardTypes.KIND_AGENDA, observation.card.kind)
        assertFalse(text.contains("卡片"))
    }

    @Test
    fun emptyAgendaIsAValidEmptyResult() {
        val envelope = CliJsonEnvelope.DEFAULT.find("""{"ok":true,"identity":"user","data":[],"meta":{"count":0}}""")!!
        val observation = buildFeishuObservation(
            zhHost,
            FeishuCommand(FeishuCommandKind.CALENDAR_AGENDA, start = "2026-11-16", end = "2026-11-16"),
            envelope,
        )!!
        assertTrue(observation.text.contains("飞书日程（共 0 个）"))
        assertTrue(observation.text.contains("正常的空结果"))
        assertTrue(observation.card.items.isEmpty())
        assertEquals(0, observation.card.total)
    }

    @Test
    fun emptyTasksIsAValidEmptyResult() {
        val envelope = CliJsonEnvelope.DEFAULT.find("""{"ok":true,"identity":"user","data":{"items":[],"has_more":false}}""")!!
        val observation = buildFeishuObservation(zhHost, FeishuCommand(FeishuCommandKind.TASKS), envelope)!!
        assertTrue(observation.text.contains("飞书任务（共 0 条，未完成 0 条）"))
        assertTrue(observation.text.contains("正常的空结果"))
    }

    @Test
    fun docsSearchObservationCarriesTitleTypeTimeAndToken() {
        val command = FeishuCommand(FeishuCommandKind.DRIVE_SEARCH, query = "周报")
        val observation = buildFeishuObservation(zhHost, command, envelope("docs-search.json"))!!
        val text = observation.text
        assertTrue(text.contains("飞书云文档（共 2 个）"))
        assertTrue(text.contains("Q3 产品方案"))
        assertTrue(text.contains("DOCX"))
        assertTrue(text.contains("token: doccnAbc123"))
        assertTrue(text.contains("链接：https://example.feishu.cn/docx/doccnAbc123"))
        assertEquals(FeishuCardTypes.KIND_DOCS_SEARCH, observation.card.kind)
        assertFalse(text.contains("卡片"))
    }

    @Test
    fun docsReadObservationCarriesDocumentIdExcerptAndLink() {
        val command = FeishuCommand(FeishuCommandKind.DOCS_FETCH, doc = "doccnAbc123", docFormat = "markdown")
        val observation = buildFeishuObservation(zhHost, command, envelope("docs-fetch.json"))!!
        val text = observation.text
        assertTrue(text.contains("Q3 产品方案"))
        assertTrue(text.contains("doccnAbc123"))
        assertTrue(text.contains("插件体验升级"))
        assertTrue(text.contains("链接：https://feishu.cn/docx/doccnAbc123"))
        assertTrue(observation.card.items.isNotEmpty())
        assertFalse(text.contains("<title>"))
        assertFalse(text.contains("卡片"))
    }

    @Test
    fun tasksObservationCarriesGuidDueAndCompletion() {
        val observation = buildFeishuObservation(zhHost, FeishuCommand(FeishuCommandKind.TASKS), envelope("tasks.json"))!!
        val text = observation.text
        assertTrue(text.contains("飞书任务（共 2 条，未完成 1 条）"))
        assertTrue(text.contains("提交 1.1.2 插件验收报告"))
        assertTrue(text.contains("guid: guid_7f3a1b2c4d5e6f7a8b9c0d1e2f3a4b5c"))
        assertTrue(text.contains("截止：2025-11-15 17:46"))
        assertTrue(text.contains("已完成"))
        assertEquals(FeishuCardTypes.KIND_TASKS, observation.card.kind)
        assertFalse(text.contains("不要重复"))
    }

    @Test
    fun englishHostGetsEnglishObservation() {
        val host = FakeFeishuHost(language = "en")
        val observation = buildFeishuObservation(host, FeishuCommand(FeishuCommandKind.TASKS), envelope("tasks.json"))!!
        assertTrue(observation.text.startsWith("Feishu tasks (2 total, 1 open)"))
        assertTrue(observation.text.contains("guid: guid_7f3a"))
    }

    @Test
    fun malformedDataReturnsNoObservation() {
        val broken = CliJsonEnvelope.DEFAULT.find(loadFixture("chats.json"))!!
        assertNull(buildFeishuObservation(zhHost, FeishuCommand(FeishuCommandKind.CHAT_LIST), broken.removeTest("data")))
        assertNull(buildFeishuObservation(zhHost, FeishuCommand(FeishuCommandKind.DOCS_FETCH), broken))
    }

    private fun kotlinx.serialization.json.JsonObject.removeTest(key: String): kotlinx.serialization.json.JsonObject =
        kotlinx.serialization.json.JsonObject(filterKeys { it != key })

    @Test
    fun cardPayloadRoundTripsThroughJson() {
        val model = FeishuCardModel(
            kind = FeishuCardTypes.KIND_MESSAGES,
            context = "会话：oc_1",
            total = 3,
            items = listOf(FeishuCardItem(title = "标题", subtitle = "李雷", meta = "09:40", url = "https://example.com")),
            openUrl = "https://example.com/open",
            note = "还有 1 条未显示",
        )
        val encoded = encodeFeishuCardPayload(model)
        val parsed = Json.parseToJsonElement(encoded.toString()).jsonObject
        val decoded = decodeFeishuCardPayload(parsed)!!
        assertEquals(model.kind, decoded.kind)
        assertEquals(model.context, decoded.context)
        assertEquals(3, decoded.total)
        assertEquals(model.items, decoded.items)
        assertEquals(model.openUrl, decoded.openUrl)
        assertEquals(model.note, decoded.note)
    }
}
