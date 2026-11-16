package io.github.whywood.deepdeep.plugin.feishu

import io.github.whywood.deepdeep.plugin.cli.CliJsonEnvelope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeishuObservationTest {
    private val zhHost = FakeFeishuHost(language = "zh")

    private fun envelope(fixture: String) = CliJsonEnvelope.DEFAULT.find(loadFixture(fixture))!!

    private fun envelopeOf(json: String) = CliJsonEnvelope.DEFAULT.find(json.trimIndent())!!

    @Test
    fun chatListObservationCarriesNamesIdsCountsAndLinks() {
        val observation = buildFeishuObservation(zhHost, FeishuCommand(FeishuCommandKind.CHAT_LIST), envelope("chats.json"))!!
        val text = observation.text
        assertTrue(text.contains("飞书聊天列表（共 3 个）"))
        assertTrue(text.contains("产品研发群"))
        assertTrue(text.contains("oc_5ce6d572455d361153b7cb51da133945"))
        assertTrue(text.contains("群主：ou_7d8a6e6df7621556ce0d21922b676706"))
        assertTrue(text.contains("状态：正常（normal）"))
        assertTrue(text.contains("外部群：否（external: false）"))
        assertTrue(text.contains("单聊对象：ou_2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d7e（user）"))
        assertTrue(text.contains("https://applink.feishu.cn/client/chat/open"))
        assertEquals(FeishuCardTypes.KIND_CHAT_LIST, observation.card.kind)
        assertEquals(3, observation.card.items.size)
        assertNull(observation.card.items.first().avatar)
        assertFalse(text.contains("卡片"))
        assertFalse(text.contains("还有"))
    }

    @Test
    fun chatListCarriesAllRowsAndUpstreamAvatar() {
        val chats = (1..12).joinToString(",") { index ->
            """{"chat_id":"oc_$index","name":"会话$index","chat_mode":"group","avatar":"https://s3.example/avatar$index.png","external":${index % 2 == 0},"chat_status":"normal"}"""
        }
        val observation = buildFeishuObservation(
            zhHost,
            FeishuCommand(FeishuCommandKind.CHAT_LIST),
            envelopeOf("""{"ok":true,"identity":"user","data":{"chats":[$chats],"has_more":false,"page_token":""}}"""),
        )!!
        assertEquals(12, observation.card.items.size)
        assertEquals(12, observation.card.total)
        assertEquals("https://s3.example/avatar1.png", observation.card.items.first().avatar)
        assertEquals("外部", observation.card.items[1].badge)
        for (index in 1..12) {
            assertTrue(observation.text.contains("会话$index"))
        }
        assertFalse(observation.text.contains("未显示"))
    }

    @Test
    fun chatListMarksDissolvedChats() {
        val observation = buildFeishuObservation(
            zhHost,
            FeishuCommand(FeishuCommandKind.CHAT_LIST),
            envelopeOf(
                """{"ok":true,"data":{"chats":[{"chat_id":"oc_1","name":"旧群","chat_status":"dissolved"}],"has_more":false}}""",
            ),
        )!!
        assertEquals("已解散", observation.card.items.single().badge)
        assertTrue(observation.text.contains("状态：已解散（dissolved）"))
    }

    @Test
    fun messagesObservationCarriesSendersTimesIdsAndLinks() {
        val command = FeishuCommand(
            FeishuCommandKind.CHAT_MESSAGES,
            chatId = "oc_5ce6d572455d361153b7cb51da133945",
        )
        val observation = buildFeishuObservation(zhHost, command, envelope("messages.json"))!!
        val text = observation.text
        assertTrue(text.contains("飞书消息（共 3 条）"))
        assertTrue(text.contains("发送者：李雷；ID：ou_7d8a6e6df7621556ce0d21922b676706（open_id）；身份：user"))
        assertTrue(text.contains("2025-11-15 09:40"))
        assertTrue(text.contains("om_aa11bb22cc33dd44ee55ff6677889900"))
        assertTrue(text.contains("正文："))
        assertTrue(text.contains("明天上午10点评审，麻烦准备一下设计稿"))
        assertTrue(text.contains("状态：已编辑（updated: true；更新时间：2025-11-15 08:45）"))
        assertTrue(text.contains("评审安排"))
        assertTrue(text.contains("评审时间改到周四下午三点"))
        assertTrue(text.contains("文件：Q3方案.pdf"))
        assertTrue(text.contains("https://applink.feishu.cn/client/chat/open"))
        assertEquals(FeishuCardTypes.KIND_MESSAGES, observation.card.kind)
        assertEquals(3, observation.card.items.size)
        assertEquals("已编辑", observation.card.items[1].badge)
        assertTrue(observation.card.items[1].body!!.contains("评审时间改到周四下午三点"))
        assertEquals("李雷 · 文件 · Q3方案.pdf", observation.card.items[2].subtitle)
        assertTrue(observation.card.items[2].body!!.isEmpty())
        assertFalse(text.contains("卡片"))
        assertFalse(text.contains("内容较长"))
    }

    @Test
    fun longMessageKeepsFullBody() {
        val longText = "评审要点与结论补充。".repeat(40)
        val observation = buildFeishuObservation(
            zhHost,
            FeishuCommand(FeishuCommandKind.CHAT_MESSAGES, chatId = "oc_1"),
            envelopeOf(
                """{"ok":true,"data":{"messages":[{"message_id":"om_long","msg_type":"text","content":"$longText","create_time":"2025-11-15 09:40","sender":{"id":"ou_1","name":"张三","id_type":"open_id","sender_type":"user"},"deleted":false,"updated":false}],"has_more":false}}""",
            ),
        )!!
        assertEquals(longText, observation.card.items.single().body)
        assertTrue(observation.text.contains(longText))
        assertFalse(observation.text.contains("…"))
    }

    @Test
    fun messagesCarryRecalledMentionsAndThread() {
        val content = """{\"title\":\"周会\",\"content\":[[{\"tag\":\"text\",\"text\":\"请 @_user_1 补充\"}]]}"""
        val observation = buildFeishuObservation(
            zhHost,
            FeishuCommand(FeishuCommandKind.CHAT_MESSAGES, chatId = "oc_1"),
            envelopeOf(
                """
                {"ok":true,"data":{"messages":[{"message_id":"om_1","msg_type":"post","content":"$content","create_time":"2025-11-15 09:40","sender":{"id":"ou_1","name":"张三","id_type":"open_id","sender_type":"user"},"deleted":true,"updated":false,"thread_id":"omt_1","mentions":[{"id":"ou_2","key":"@_user_1","name":"李四"}]}],"has_more":false}}
                """,
            ),
        )!!
        val text = observation.text
        assertTrue(text.contains("状态：已撤回（deleted: true）"))
        assertTrue(text.contains("提及：@李四（ou_2）"))
        assertTrue(text.contains("话题：omt_1"))
        assertTrue(text.contains("请 @李四 补充"))
        val item = observation.card.items.single()
        assertTrue(item.badge!!.contains("已撤回"))
        assertTrue(item.badge!!.contains("话题"))
        assertTrue(item.badge!!.contains("@李四"))
        assertTrue(item.body!!.contains("周会"))
    }

    @Test
    fun paginationAddsPageTokenHint() {
        val messages = (1..3).joinToString(",") { index ->
            """{"message_id":"om_$index","msg_type":"text","content":"消息$index","create_time":"2025-11-15 09:40","sender":{"name":"张三"}}"""
        }
        val observation = buildFeishuObservation(
            zhHost,
            FeishuCommand(FeishuCommandKind.CHAT_MESSAGES, chatId = "oc_1"),
            envelopeOf(
                """{"ok":true,"data":{"messages":[$messages],"has_more":true,"page_token":"pt_next"},"meta":{"pagination":{"complete":false,"next_token":"pt_next"}}}""",
            ),
        )!!
        assertTrue(observation.text.endsWith("本页 3 条，还有更多：用 --page-token pt_next 继续"))
        assertEquals(3, observation.card.items.size)
    }

    @Test
    fun agendaObservationCarriesTimesLocationsDescriptionAndTimezone() {
        val command = FeishuCommand(FeishuCommandKind.CALENDAR_AGENDA, start = "2026-11-16", end = "2026-11-17")
        val observation = buildFeishuObservation(zhHost, command, envelope("agenda.json"))!!
        val text = observation.text
        assertTrue(text.contains("飞书日程（共 2 个）"))
        assertTrue(text.contains("产品周会"))
        assertTrue(text.contains("时间：2025-11-15 09:40 至 2025-11-15 10:40（时区：Asia/Shanghai）"))
        assertTrue(text.contains("3F 会议室 A"))
        assertTrue(text.contains("event_id：e1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d"))
        assertTrue(text.contains("描述：讨论 1.1.2 版本节奏"))
        assertTrue(text.contains("团队建设"))
        assertTrue(text.contains("时间：全天，开始日期：2026-11-16，结束日期：2026-11-15"))
        assertEquals(FeishuCardTypes.KIND_AGENDA, observation.card.kind)
        assertEquals("讨论 1.1.2 版本节奏", observation.card.items[1].body)
        assertEquals("2026-11-16", observation.card.items[0].meta)
        assertFalse(text.contains("卡片"))
    }

    @Test
    fun emptyAgendaIsAValidEmptyResult() {
        val payload = envelopeOf("""{"ok":true,"identity":"user","data":[],"meta":{"count":0}}""")
        val observation = buildFeishuObservation(
            zhHost,
            FeishuCommand(FeishuCommandKind.CALENDAR_AGENDA, start = "2026-11-16", end = "2026-11-16"),
            payload,
        )!!
        assertTrue(observation.text.contains("飞书日程（共 0 个）"))
        assertTrue(observation.text.contains("正常的空结果"))
        assertTrue(observation.card.items.isEmpty())
        assertEquals(0, observation.card.total)
    }

    @Test
    fun emptyTasksIsAValidEmptyResult() {
        val payload = envelopeOf("""{"ok":true,"identity":"user","data":{"items":[],"has_more":false}}""")
        val observation = buildFeishuObservation(zhHost, FeishuCommand(FeishuCommandKind.TASKS), payload)!!
        assertTrue(observation.text.contains("飞书任务（共 0 条，未完成 0 条）"))
        assertTrue(observation.text.contains("正常的空结果"))
    }

    @Test
    fun docsSearchObservationCarriesTimesSummaryAndResultMeta() {
        val command = FeishuCommand(FeishuCommandKind.DRIVE_SEARCH, query = "周报")
        val observation = buildFeishuObservation(zhHost, command, envelope("docs-search.json"))!!
        val text = observation.text
        assertTrue(text.contains("飞书云文档（共 2 个）"))
        assertTrue(text.contains("Q3 产品方案"))
        assertTrue(text.contains("DOCX"))
        assertTrue(text.contains("创建时间：2025-11-01 20:26"))
        assertTrue(text.contains("编辑时间：2025-11-13 10:13"))
        assertTrue(text.contains("摘要：Q3 产品方案与里程碑拆解"))
        assertTrue(text.contains("result_meta：doc_types=DOCX"))
        assertTrue(text.contains("token：doccnAbc123"))
        assertTrue(text.contains("链接：https://example.feishu.cn/docx/doccnAbc123"))
        assertEquals(FeishuCardTypes.KIND_DOCS_SEARCH, observation.card.kind)
        assertEquals("Q3 产品方案与里程碑拆解", observation.card.items.first().body)
        assertFalse(text.contains("卡片"))
    }

    @Test
    fun docsReadObservationCarriesFullDocumentBody() {
        val command = FeishuCommand(FeishuCommandKind.DOCS_FETCH, doc = "doccnAbc123", docFormat = "markdown")
        val observation = buildFeishuObservation(zhHost, command, envelope("docs-fetch.json"))!!
        val text = observation.text
        assertTrue(text.contains("Q3 产品方案"))
        assertTrue(text.contains("doccnAbc123"))
        assertTrue(text.contains("插件体验升级"))
        assertTrue(text.contains("11 月完成卡片桥接与更新检查，12 月完成真机验收。"))
        assertTrue(text.contains("链接：https://feishu.cn/docx/doccnAbc123"))
        assertFalse(text.contains("<title>"))
        val body = observation.card.items.single().body!!
        assertTrue(body.contains("本季度完成 1.1.2 插件体验升级"))
        assertTrue(body.contains("11 月完成卡片桥接与更新检查"))
        assertFalse(text.contains("节选"))
    }

    @Test
    fun docsReadKeepsContentBeyondThreeParagraphs() {
        val paragraphs = (1..8).joinToString("") { "<p>第 $it 段正文内容</p>" }
        val observation = buildFeishuObservation(
            zhHost,
            FeishuCommand(FeishuCommandKind.DOCS_FETCH, doc = "doc_x"),
            envelopeOf("""{"ok":true,"data":{"document":{"content":"<title>长文档</title>$paragraphs","document_id":"doc_x","revision_id":3}}}"""),
        )!!
        val body = observation.card.items.single().body!!
        for (index in 1..8) {
            assertTrue(body.contains("第 $index 段正文内容"))
            assertTrue(observation.text.contains("第 $index 段正文内容"))
        }
        assertFalse(observation.text.contains("…"))
    }

    @Test
    fun tasksObservationCarriesGuidDueMembersCreatedAndStart() {
        val observation = buildFeishuObservation(zhHost, FeishuCommand(FeishuCommandKind.TASKS), envelope("tasks.json"))!!
        val text = observation.text
        assertTrue(text.contains("飞书任务（共 2 条，未完成 1 条）"))
        assertTrue(text.contains("提交 1.1.2 插件验收报告"))
        assertTrue(text.contains("guid: guid_7f3a1b2c4d5e6f7a8b9c0d1e2f3a4b5c"))
        assertTrue(text.contains("创建：2025-11-01 20:26"))
        assertTrue(text.contains("截止：2025-11-15 17:46"))
        assertTrue(text.contains("开始：2025-11-14 16:46"))
        assertTrue(text.contains("成员：ou_7d8a6e6df7621556ce0d21922b676706（assignee，user）"))
        assertTrue(text.contains("已完成"))
        assertTrue(observation.card.items.first().body!!.startsWith("成员："))
        assertEquals(FeishuCardTypes.KIND_TASKS, observation.card.kind)
        assertFalse(text.contains("不要重复"))
    }

    @Test
    fun englishHostGetsEnglishObservation() {
        val host = FakeFeishuHost(language = "en")
        val observation = buildFeishuObservation(host, FeishuCommand(FeishuCommandKind.TASKS), envelope("tasks.json"))!!
        assertTrue(observation.text.startsWith("Feishu tasks (2 total, 1 open)"))
        assertTrue(observation.text.contains("guid: guid_7f3a"))
        assertTrue(observation.text.contains("members: ou_7d8a"))
    }

    @Test
    fun malformedDataReturnsNoObservation() {
        val broken = envelope("chats.json")
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
            items = listOf(
                FeishuCardItem(
                    title = "标题",
                    subtitle = "李雷",
                    meta = "09:40",
                    url = "https://example.com",
                    body = "第一行\n第二行",
                    avatar = "https://example.com/avatar.png",
                ),
            ),
            openUrl = "https://example.com/open",
        )
        val encoded = encodeFeishuCardPayload(model)
        val parsed = Json.parseToJsonElement(encoded.toString()).jsonObject
        val decoded = decodeFeishuCardPayload(parsed)!!
        assertEquals(model.kind, decoded.kind)
        assertEquals(model.context, decoded.context)
        assertEquals(3, decoded.total)
        assertEquals(model.items, decoded.items)
        assertEquals(model.openUrl, decoded.openUrl)
        assertNull(decoded.note)
    }
}
