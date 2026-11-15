package io.github.whywood.deepdeep.plugin.wecom

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WecomObservationTest {
    private fun command(kind: WecomCommandKind): WecomCommand = WecomCommand(kind)

    private fun observation(kind: WecomCommandKind, fixture: String): String {
        val payload = payloadOf(fixture)
        val parsed = WecomParsedParser.parse(kind, payload) ?: error("parse failed for $kind")
        return WecomObservation.build(command(kind), parsed)
    }

    @Test
    fun groupsObservationCarriesIdsTimesCountsAndCursor() {
        val text = observation(WecomCommandKind.CHAT_GROUPS, "chat-groups.json")
        assertTrue(text.contains("产品讨论群"))
        assertTrue(text.contains("wrOgQhDgAAmockchatid001"))
        assertTrue(text.contains("2026-11-15 10:24:31"))
        assertTrue(text.contains("42 条消息"))
        assertTrue(text.contains("已返回全部结果"))
        assertFalse(text.contains("卡片"))
    }

    @Test
    fun messagesObservationCarriesSendersTimesContentAndMediaIds() {
        val text = observation(WecomCommandKind.CHAT_MESSAGES, "chat-messages.json")
        assertTrue(text.contains("张三"))
        assertTrue(text.contains("2026-11-15 10:24:31"))
        assertTrue(text.contains("中午前把接口文档发一下"))
        assertTrue(text.contains("接口文档-v2.pdf"))
        assertTrue(text.contains("MEDIA_MOCK_001"))
        assertTrue(text.contains("cursor=CURSOR_MOCK_PAGE_2"))
        assertFalse(text.contains("卡片"))
    }

    @Test
    fun docsObservationCarriesIdsCreatorTimesAndLinks() {
        val text = observation(WecomCommandKind.DOC_SEARCH, "doc-search.json")
        assertTrue(text.contains("企业微信接入方案"))
        assertTrue(text.contains("DOC_MOCK_001"))
        assertTrue(text.contains("张三"))
        assertTrue(text.contains("2026-11-14 16:40:00"))
        assertTrue(text.contains("https://doc.weixin.qq.com/doc/MOCK001"))
        assertFalse(text.contains("卡片"))
    }

    @Test
    fun docContentObservationCarriesVersionUrlAndBody() {
        val text = observation(WecomCommandKind.DOC_CONTENT, "doc-content.json")
        assertTrue(text.contains("企业微信接入方案"))
        assertTrue(text.contains("版本 12"))
        assertTrue(text.contains("https://doc.weixin.qq.com/doc/MOCK001"))
        assertTrue(text.contains("企业微信接入方案"))
        assertTrue(text.contains("接口权限与企业内部策略需要提前确认"))
    }

    @Test
    fun schedulesObservationCarriesTimesAttendeesAndIds() {
        val text = observation(WecomCommandKind.CALENDAR_SCHEDULES, "calendar-schedules.json")
        assertTrue(text.contains("周会"))
        assertTrue(text.contains("2026-11-16 10:00:00"))
        assertTrue(text.contains("3F 会议室 A"))
        assertTrue(text.contains("张三、李四"))
        assertTrue(text.contains("SCHED_MOCK_001"))
    }

    @Test
    fun todosObservationCarriesStatusDeadlineFollowers() {
        val text = observation(WecomCommandKind.TODO_LIST, "todo-list.json")
        assertTrue(text.contains("整理接口文档"))
        assertTrue(text.contains("[进行中]"))
        assertTrue(text.contains("TODO_MOCK_001"))
        assertTrue(text.contains("2026-11-16 18:00:00"))
        assertTrue(text.contains("张三"))
        assertTrue(text.contains("李四"))
    }

    @Test
    fun contactsObservationCarriesUserIdsDepartmentsAndEmail() {
        val text = observation(WecomCommandKind.CONTACT_USERS, "contact-users.json")
        assertTrue(text.contains("张三"))
        assertTrue(text.contains("zhangsan"))
        assertTrue(text.contains("产品部"))
        assertTrue(text.contains("产品经理"))
        assertTrue(text.contains("zhangsan@example.com"))
    }

    @Test
    fun emptyResultsAreReadable() {
        val parsed = WecomParsedParser.parse(WecomCommandKind.CHAT_GROUPS, json("""{"chats":[],"chats_count":0}"""))
        val text = WecomObservation.build(command(WecomCommandKind.CHAT_GROUPS), parsed!!)
        assertTrue(text.contains("没有符合条件的群聊会话"))
        assertFalse(text.contains("卡片"))
    }

    @Test
    fun missingArrayIsTreatedAsEmptyButWrongTypeIsRejected() {
        val empty = WecomParsedParser.parse(WecomCommandKind.TODO_LIST, json("""{"items_count":0}"""))
        assertTrue(empty is WecomParsed.Todos)
        assertTrue((empty as WecomParsed.Todos).items.isEmpty())
        val malformed = WecomParsedParser.parse(WecomCommandKind.TODO_LIST, json("""{"items":42}"""))
        org.junit.Assert.assertNull(malformed)
    }

    @Test
    fun todoStatusLabelsAreLocalizedWithoutRawCodes() {
        assertTrue(WecomObservation.todoStatusLabel("proceed") == "进行中")
        assertTrue(WecomObservation.todoStatusLabel("finished") == "已完成")
        assertTrue(WecomObservation.docTypeLabel("smartsheet") == "智能表格")
    }

    private fun payloadOf(fixture: String): kotlinx.serialization.json.JsonObject {
        val body = javaClass.getResourceAsStream("/fixtures/cli/$fixture")?.bufferedReader()?.use { it.readText() }
            ?: error("missing fixture $fixture")
        return json(body)
    }

    private fun json(text: String): kotlinx.serialization.json.JsonObject =
        kotlinx.serialization.json.Json.parseToJsonElement(text) as kotlinx.serialization.json.JsonObject
}
