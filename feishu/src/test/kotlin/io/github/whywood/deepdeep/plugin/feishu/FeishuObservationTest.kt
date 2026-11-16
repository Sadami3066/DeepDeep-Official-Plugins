package io.github.whywood.deepdeep.plugin.feishu

import io.github.whywood.deepdeep.plugin.cli.CliJsonEnvelope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FeishuObservationTest {
    private val zhHost = FakeFeishuHost(language = "zh")

    @Before
    fun clearLinks() {
        FeishuLinks.clear()
    }

    private fun envelope(fixture: String) = CliJsonEnvelope.DEFAULT.find(loadFixture(fixture))!!

    private fun envelopeOf(json: String) = CliJsonEnvelope.DEFAULT.find(json.trimIndent())!!

    @Test
    fun chatListGuidancePointsAtMessagesAndCardCarriesRows() {
        val observation = buildFeishuObservation(zhHost, FeishuCommand(FeishuCommandKind.CHAT_LIST), envelope("chats.json"))!!
        assertTrue(observation.text.contains("lark-cli im +chat-messages-list"))
        assertFalse(observation.text.contains("产品研发群"))
        assertFalse(observation.text.contains("oc_5ce6d572455d361153b7cb51da133945"))
        assertEquals("飞书聊天列表（共 3 个）", observation.summary)
        assertEquals(FeishuCardTypes.KIND_CHAT_LIST, observation.card!!.kind)
        assertEquals(3, observation.card!!.items.size)
        assertEquals("产品研发群", observation.card!!.items.first().title)
        assertEquals(
            "产品与研发日常沟通 · 群主 ID：ou_7d8a6e6df7621556ce0d21922b676706",
            observation.card!!.items.first().subtitle,
        )
        assertEquals("群聊", observation.card!!.items.first().meta)
        assertTrue(observation.card!!.items.first().url!!.contains("applink.feishu.cn"))
        assertNull(observation.card!!.items.first().avatar)
        assertFalse(observation.text.contains("卡片"))
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
        assertEquals(12, observation.card!!.items.size)
        assertEquals(12, observation.card!!.total)
        assertEquals("https://s3.example/avatar1.png", observation.card!!.items.first().avatar)
        assertEquals("外部", observation.card!!.items[1].badge)
        assertFalse(observation.text.contains("会话1"))
        assertFalse(observation.text.contains("未显示"))
    }

    @Test
    fun chatListMarksDissolvedChatsAndOwnerWithoutName() {
        val observation = buildFeishuObservation(
            zhHost,
            FeishuCommand(FeishuCommandKind.CHAT_LIST),
            envelopeOf(
                """{"ok":true,"data":{"chats":[{"chat_id":"oc_1","name":"旧群","chat_status":"dissolved","owner_id":"ou_owner"}],"has_more":false}}""",
            ),
        )!!
        val item = observation.card!!.items.single()
        assertEquals("已解散", item.badge)
        assertEquals("群主 ID：ou_owner", item.subtitle)
    }

    @Test
    fun messagesGuidanceAndCardUseSenderI18nName() {
        val observation = buildFeishuObservation(
            zhHost,
            FeishuCommand(FeishuCommandKind.CHAT_MESSAGES, chatId = "oc_5ce6d572455d361153b7cb51da133945"),
            envelope("messages-rich.json"),
        )!!
        assertEquals("飞书消息（共 5 条）", observation.summary)
        assertTrue(observation.text.contains("--page-size"))
        assertFalse(observation.text.contains("林小满"))
        assertFalse(observation.text.contains("计划"))
        val items = observation.card!!.items
        assertEquals(5, items.size)
        assertEquals("林小满 · 文本", items[0].subtitle)
        assertEquals("https://example.feishu.cn/docx/doc_paragraphed", items[0].body)
        assertEquals("计划：\n1. 收集 SDK\n2. 数据入库", items[1].body)
        assertEquals("普通文本消息", items[2].body)
        assertFalse(items[0].body!!.contains("<p>"))
        assertFalse(items[1].body!!.contains("<card>"))
        assertFalse(items[1].body!!.contains("</card>"))
    }

    @Test
    fun systemRenamePlaceholderIsExplainedWithoutInventingNames() {
        val observation = buildFeishuObservation(
            zhHost,
            FeishuCommand(FeishuCommandKind.CHAT_MESSAGES, chatId = "oc_5ce6d572455d361153b7cb51da133945"),
            envelope("messages-rich.json"),
        )!!
        val rename = observation.card!!.items[3]
        assertEquals("系统消息", rename.subtitle)
        assertEquals("群名变更（旧名与新名上游未返回）", rename.body)
        assertFalse(rename.body!!.contains("{"))
        val invite = observation.card!!.items[4]
        assertEquals("用户000001 invited 用户000004 to the group.", invite.body)
    }

    @Test
    fun messagesFixtureStillCarriesRecalledMentionsAndThread() {
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
        val item = observation.card!!.items.single()
        assertTrue(item.badge!!.contains("已撤回"))
        assertTrue(item.badge!!.contains("话题"))
        assertTrue(item.badge!!.contains("@李四"))
        assertTrue(item.body!!.contains("周会"))
        assertTrue(item.body!!.contains("请 @李四 补充"))
        assertEquals("张三 · 富文本", item.subtitle)
    }

    @Test
    fun paginationAddsPageTokenGuidanceOnly() {
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
        assertTrue(observation.text.endsWith("还有更多结果：用 --page-token pt_next 继续拉取。"))
        assertFalse(observation.text.contains("消息1"))
        assertEquals(3, observation.card!!.items.size)
    }

    @Test
    fun agendaGuidanceAndCardCarryTimesLocationsDescriptionAndTimezone() {
        val command = FeishuCommand(FeishuCommandKind.CALENDAR_AGENDA, start = "2026-11-16", end = "2026-11-17")
        val observation = buildFeishuObservation(zhHost, command, envelope("agenda.json"))!!
        assertTrue(observation.text.contains("--start/--end"))
        assertFalse(observation.text.contains("产品周会"))
        assertEquals("飞书日程（共 2 个）", observation.summary)
        assertEquals(FeishuCardTypes.KIND_AGENDA, observation.card!!.kind)
        assertEquals("讨论 1.1.2 版本节奏", observation.card!!.items[1].body)
        assertEquals("2026-11-16", observation.card!!.items[0].meta)
        assertEquals("全天", observation.card!!.items[0].subtitle)
        assertEquals("11-15 09:40-11-15 10:40 · 3F 会议室 A", observation.card!!.items[1].subtitle)
    }

    @Test
    fun emptyAgendaIsAValidEmptyResult() {
        val payload = envelopeOf("""{"ok":true,"identity":"user","data":[],"meta":{"count":0}}""")
        val observation = buildFeishuObservation(
            zhHost,
            FeishuCommand(FeishuCommandKind.CALENDAR_AGENDA, start = "2026-11-16", end = "2026-11-16"),
            payload,
        )!!
        assertTrue(observation.text.contains("正常的空结果"))
        assertFalse(observation.text.contains("飞书日程（共 0 个）"))
        assertEquals("飞书日程（共 0 个）", observation.summary)
        assertTrue(observation.card!!.items.isEmpty())
        assertEquals(0, observation.card!!.total)
    }

    @Test
    fun emptyTasksIsAValidEmptyResult() {
        val payload = envelopeOf("""{"ok":true,"identity":"user","data":{"items":[],"has_more":false}}""")
        val observation = buildFeishuObservation(zhHost, FeishuCommand(FeishuCommandKind.TASKS), payload)!!
        assertTrue(observation.text.contains("这是正常的空结果"))
        assertTrue(observation.card!!.items.isEmpty())
    }

    @Test
    fun nullTaskItemsIsTreatedAsEmptyResult() {
        val payload = envelopeOf("""{"ok":true,"identity":"user","data":{"items":null,"has_more":false,"page_token":""}}""")
        val observation = buildFeishuObservation(zhHost, FeishuCommand(FeishuCommandKind.TASKS), payload)!!
        assertTrue(observation.text.contains("items 为空或 null"))
        assertTrue(observation.text.contains("这是正常的空结果"))
        assertEquals("飞书任务（共 0 条，未完成 0 条）", observation.summary)
        assertEquals(0, observation.card!!.total)
        assertTrue(observation.card!!.items.isEmpty())
    }

    @Test
    fun docsSearchUsesHighlightedTitleAndSummaryWithoutSpaces() {
        val command = FeishuCommand(FeishuCommandKind.DRIVE_SEARCH, query = "周报")
        val observation = buildFeishuObservation(zhHost, command, envelope("docs-search.json"))!!
        assertEquals("飞书云文档（共 2 个）", observation.summary)
        assertTrue(observation.text.contains("docs +fetch"))
        assertFalse(observation.text.contains("Q3 产品方案"))
        assertEquals(FeishuCardTypes.KIND_DOCS_SEARCH, observation.card!!.kind)
        val first = observation.card!!.items.first()
        assertEquals("Q3 产品方案与大创记录", first.title)
        assertTrue(first.body!!.contains("3个大模块"))
        assertFalse(first.body!!.contains("3个 大 模块"))
        assertTrue(first.body!!.startsWith("写完了入库脚本"))
        assertFalse(first.title.contains("<h>"))
        assertEquals("DOCX · 李雷 · 编辑于 2025-11-13 10:13", first.subtitle)
    }

    @Test
    fun docsSearchReadsTokenUrlOwnerAndTypesFromResultMeta() {
        val observation = buildFeishuObservation(
            zhHost,
            FeishuCommand(FeishuCommandKind.DRIVE_SEARCH, query = "周报"),
            envelope("docs-search.json"),
        )!!
        val items = observation.card!!.items
        assertEquals("https://example.feishu.cn/docx/doccnAbc123", items[0].url)
        assertEquals("doccnAbc123", items[0].badge)
        assertEquals("shtcnXyz789", items[1].badge)
        assertEquals("李雷", items[0].meta)
        assertEquals("https://example.feishu.cn/sheets/shtcnXyz789", items[1].url)
        assertEquals("SHEET · 韩梅梅 · 编辑于 2025-11-11 02:40", items[1].subtitle)
        assertEquals("https://example.feishu.cn/docx/doccnAbc123", observation.card!!.openUrl)
        assertEquals(FeishuLinks.resolve("doccnAbc123"), observation.card!!.openUrl)
    }

    @Test
    fun emptyDocsSearchIsAValidEmptyResult() {
        val observation = buildFeishuObservation(
            zhHost,
            FeishuCommand(FeishuCommandKind.DRIVE_SEARCH, query = "不存在"),
            envelopeOf("""{"ok":true,"data":{"results":[],"has_more":false,"page_token":"","total":0}}"""),
        )!!
        assertTrue(observation.text.contains("正常的空结果"))
        assertTrue(observation.card!!.items.isEmpty())
        assertNull(observation.card!!.openUrl)
    }

    @Test
    fun docsReadCarriesFullBodyButNoFabricatedLinkWithoutTenant() {
        val observation = buildFeishuObservation(
            zhHost,
            FeishuCommand(FeishuCommandKind.DOCS_FETCH, doc = "doccnAbc123", docFormat = "markdown"),
            envelope("docs-fetch.json"),
        )!!
        assertEquals("已读取文档「Q3 产品方案」", observation.summary)
        assertTrue(observation.text.contains("正文已全部返回"))
        assertTrue(observation.text.contains("不要自行拼接链接"))
        assertNull(observation.card!!.openUrl)
        val body = observation.card!!.items.single().body!!
        assertTrue(body.contains("本季度完成 1.1.2 插件体验升级"))
        assertTrue(body.contains("11 月完成卡片桥接与更新检查"))
        assertFalse(body.contains("<title>"))
        assertFalse(observation.text.contains("插件体验升级"))
    }

    @Test
    fun docsReadUsesTenantUrlRememberedFromSearch() {
        buildFeishuObservation(
            zhHost,
            FeishuCommand(FeishuCommandKind.DRIVE_SEARCH, query = "方案"),
            envelope("docs-search.json"),
        )
        val observation = buildFeishuObservation(
            zhHost,
            FeishuCommand(FeishuCommandKind.DOCS_FETCH, doc = "doccnAbc123", docFormat = "markdown"),
            envelope("docs-fetch.json"),
        )!!
        assertEquals("https://example.feishu.cn/docx/doccnAbc123", observation.card!!.openUrl)
        assertFalse(observation.text.contains("不要自行拼接链接"))
    }

    @Test
    fun docsReadKeepsContentBeyondThreeParagraphs() {
        val paragraphs = (1..8).joinToString("") { "<p>第 $it 段正文内容</p>" }
        val observation = buildFeishuObservation(
            zhHost,
            FeishuCommand(FeishuCommandKind.DOCS_FETCH, doc = "doc_x"),
            envelopeOf("""{"ok":true,"data":{"document":{"content":"<title>长文档</title>$paragraphs","document_id":"doc_x","revision_id":3}}}"""),
        )!!
        val body = observation.card!!.items.single().body!!
        for (index in 1..8) {
            assertTrue(body.contains("第 $index 段正文内容"))
        }
        assertFalse(observation.text.contains("…"))
    }

    @Test
    fun tasksCardCarriesGuidDueMembersCreatedAndStart() {
        val observation = buildFeishuObservation(zhHost, FeishuCommand(FeishuCommandKind.TASKS), envelope("tasks.json"))!!
        assertEquals("飞书任务（共 2 条，未完成 1 条）", observation.summary)
        assertTrue(observation.text.contains("--complete=true"))
        assertFalse(observation.text.contains("提交 1.1.2 插件验收报告"))
        assertEquals(FeishuCardTypes.KIND_TASKS, observation.card!!.kind)
        val first = observation.card!!.items.first()
        assertEquals("提交 1.1.2 插件验收报告", first.title)
        assertEquals("截止 2025-11-15 17:46", first.subtitle)
        assertTrue(first.body!!.startsWith("成员："))
        assertEquals("已完成", observation.card!!.items[1].badge)
        assertFalse(observation.text.contains("不要重复"))
    }

    @Test
    fun englishHostGetsEnglishGuidanceAndSummary() {
        val host = FakeFeishuHost(language = "en")
        val observation = buildFeishuObservation(host, FeishuCommand(FeishuCommandKind.TASKS), envelope("tasks.json"))!!
        assertEquals("Feishu tasks (2 total, 1 open)", observation.summary)
        assertTrue(observation.text.contains("--complete=true"))
        assertTrue(observation.card!!.items.first().body!!.startsWith("members:"))
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
    fun mailListObservationCarriesRowsUnreadAndPaging() {
        val observation = buildFeishuObservation(
            zhHost,
            FeishuCommand(FeishuCommandKind.MAIL_TRIAGE),
            envelope("mail-triage.json"),
        )!!
        assertEquals("邮箱（3 封，2 未读）", observation.summary)
        val card = observation.card!!
        assertEquals(3, card.items.size)
        assertEquals("周会改到周四", card.items.first().title)
        assertEquals("Alice", card.items.first().subtitle)
        assertEquals("未读", card.items.first().badge)
        assertEquals("03-21 11:40", card.items.first().meta)
        assertNull(card.context)
        assertTrue(observation.text.contains("--page-token list:FfccvoqPd_loLhtcRx8cx"))
    }

    @Test
    fun mailListContextShowsFolderAndUnreadFilter() {
        val command = FeishuCommand(FeishuCommandKind.MAIL_TRIAGE, folder = "inbox", unreadOnly = true)
        val observation = buildFeishuObservation(zhHost, command, envelope("mail-triage.json"))!!
        assertEquals("收件箱 · 未读", observation.card!!.context)
    }

    @Test
    fun emptyMailListIsNormalEmptyResult() {
        val payload = envelopeOf("""{"ok":true,"identity":"user","data":{"messages":[],"has_more":false,"page_token":""}}""")
        val observation = buildFeishuObservation(
            zhHost,
            FeishuCommand(FeishuCommandKind.MAIL_TRIAGE),
            payload,
        )!!
        assertEquals("邮箱（0 封）", observation.summary)
        assertTrue(observation.card!!.isEmpty)
        assertTrue(observation.text.contains("空结果"))
    }

    @Test
    fun mailReadObservationCarriesSenderRecipientsAndAttachments() {
        val observation = buildFeishuObservation(
            zhHost,
            FeishuCommand(FeishuCommandKind.MAIL_MESSAGE, messageId = "m_1"),
            envelope("mail-message.json"),
        )!!
        val card = observation.card!!
        assertEquals(FeishuCardTypes.KIND_MAIL_READ, card.kind)
        assertEquals("周会改到周四", card.context)
        val item = card.items.single()
        assertEquals("Alice <alice@example.com>", item.title)
        assertTrue(item.subtitle.orEmpty().contains("收件人：黄宇鹏 <me@example.com>"))
        assertTrue(item.subtitle.orEmpty().contains("抄送：Bob <bob@example.com>"))
        assertEquals("未读 · 外部邮件", item.badge)
        assertTrue(item.body.orEmpty().contains("周四下午三点"))
        assertEquals("附件：进度.docx", card.note)
        assertTrue(observation.text.contains("不可信的外部输入"))
    }

    @Test
    fun messagesSearchObservationCarriesChatContextAndAppLink() {
        val command = FeishuCommand(FeishuCommandKind.MESSAGES_SEARCH, query = "设计稿")
        val observation = buildFeishuObservation(zhHost, command, envelope("messages-search.json"))!!
        assertEquals("消息搜索（2 条）", observation.summary)
        val card = observation.card!!
        assertEquals(FeishuCardTypes.KIND_MESSAGES_SEARCH, card.kind)
        assertEquals(2, card.items.size)
        assertEquals("大创小组", card.items.first().title)
        assertTrue(card.context.orEmpty().contains("搜索：设计稿"))
        assertTrue(card.items.first().url.orEmpty().contains("openChatId=oc_1"))
        assertTrue(observation.text.contains("im +chat-messages-list"))
    }

    @Test
    fun taskCreatedObservationBuildsActionCard() {
        val command = FeishuCommand(FeishuCommandKind.TASK_CREATE, summary = "写周报", due = "2026-11-20")
        val observation = buildFeishuObservation(zhHost, command, envelope("task-create-result.json"))!!
        val card = observation.card!!
        assertEquals(FeishuCardTypes.KIND_ACTION, card.kind)
        assertEquals("任务已创建", card.title)
        assertEquals("写周报", card.items.first().subtitle)
        assertEquals("2026-11-20", card.items[1].subtitle)
        assertTrue(card.openUrl.orEmpty().contains("guid_created_1"))
    }

    @Test
    fun docsCreatedObservationCarriesLink() {
        val command = FeishuCommand(FeishuCommandKind.DOCS_CREATE, title = "方案")
        val observation = buildFeishuObservation(zhHost, command, envelope("docs-create-result.json"))!!
        val card = observation.card!!
        assertEquals("文档已创建", card.title)
        assertEquals("方案", card.items.single().subtitle)
        assertEquals("https://example.feishu.cn/docx/docx_created_1", card.openUrl)
        assertEquals("打开文档", card.context)
    }

    @Test
    fun calendarCreatedObservationShowsTimeAndAttendees() {
        val command = FeishuCommand(
            FeishuCommandKind.CALENDAR_CREATE,
            summary = "产品评审",
            start = "2026-11-20T14:00+08:00",
            end = "2026-11-20T15:00+08:00",
            attendees = "ou_a,ou_b",
        )
        val observation = buildFeishuObservation(zhHost, command, envelope("calendar-create-result.json"))!!
        val card = observation.card!!
        assertEquals("日程已创建", card.title)
        assertEquals("产品评审", card.items.first().subtitle)
        assertEquals("11-20 14:00–11-20 15:00", card.items[1].subtitle)
        assertEquals("2 人", card.items[2].subtitle)
    }

    @Test
    fun mailComposeDraftKeepsSendInstructions() {
        val command = FeishuCommand(
            FeishuCommandKind.MAIL_COMPOSE,
            recipients = "alice@example.com",
            subject = "Q1 报告",
            preview = "请查收附件中的报告。",
        )
        val observation = buildFeishuObservation(zhHost, command, envelope("mail-send-draft-result.json"))!!
        val card = observation.card!!
        assertEquals("草稿已保存", card.title)
        assertEquals("alice@example.com", card.items.first().subtitle)
        assertEquals("https://example.feishu.cn/mail/draft/draft_1", card.openUrl)
        assertEquals("草稿未发送", card.note)
        assertTrue(observation.text.contains("drafts send"))
        assertTrue(observation.text.contains("draft_1"))
    }

    @Test
    fun mailComposeSentReportsSentOnlyWhenConfirmed() {
        val command = FeishuCommand(
            FeishuCommandKind.MAIL_COMPOSE,
            recipients = "alice@example.com",
            subject = "Q1 报告",
            confirmedSend = true,
        )
        val observation = buildFeishuObservation(zhHost, command, envelope("mail-send-sent-result.json"))!!
        assertEquals("邮件已发送", observation.card!!.title)
        assertNull(observation.card!!.note)
        assertTrue(observation.text.contains("邮件已发送"))
    }

    @Test
    fun mailComposeWithUnknownShapeIsLeftToRawOutput() {
        val observation = buildFeishuObservation(
            zhHost,
            FeishuCommand(FeishuCommandKind.MAIL_COMPOSE, confirmedSend = true),
            envelopeOf("""{"ok":true,"identity":"user","data":{}}"""),
        )
        assertNull(observation)
    }

    @Test
    fun tasksRelatedObservationUsesContext() {
        val observation = buildFeishuObservation(
            zhHost,
            FeishuCommand(FeishuCommandKind.TASKS_RELATED),
            envelope("tasks.json"),
        )!!
        assertEquals("我参与的任务", observation.card!!.context)
        assertEquals(FeishuCardTypes.KIND_TASKS, observation.card!!.kind)
    }

    @Test
    fun authStatusObservationHasNoCard() {
        val payload = CliJsonEnvelope.DEFAULT.element(loadFixture("auth-status.json"))!!.jsonObject
        val observation = buildFeishuObservation(
            zhHost,
            FeishuCommand(FeishuCommandKind.AUTH_STATUS),
            payload,
        )!!
        assertNull(observation.card)
        assertTrue(observation.text.contains("黄宇鹏"))
        assertTrue(observation.text.contains("5 项权限"))
        assertFalse(observation.text.contains("calendar:calendar:read"))
    }

    @Test
    fun failedEnvelopeIsNotObserved() {
        val failed = envelopeOf("""{"ok":false,"error":{"message":"nope"}}""")
        assertNull(buildFeishuObservation(zhHost, FeishuCommand(FeishuCommandKind.CHAT_LIST), failed))
        assertNull(buildFeishuObservation(zhHost, FeishuCommand(FeishuCommandKind.MAIL_TRIAGE), failed))
    }

    @Test
    fun newCardKindsRoundTrip() {
        listOf(
            FeishuCardTypes.KIND_MAIL_LIST,
            FeishuCardTypes.KIND_MAIL_READ,
            FeishuCardTypes.KIND_MESSAGES_SEARCH,
            FeishuCardTypes.KIND_ACTION,
        ).forEach { kind ->
            val model = FeishuCardModel(
                kind = kind,
                title = "标题",
                context = "上下文",
                items = listOf(FeishuCardItem(title = "行", subtitle = "副标题", body = "正文")),
                openUrl = "https://example.com/open",
                note = "备注",
            )
            val decoded = decodeFeishuCardPayload(encodeFeishuCardPayload(model))!!
            assertEquals(kind, decoded.kind)
            assertEquals("标题", decoded.title)
            assertEquals("上下文", decoded.context)
            assertEquals("备注", decoded.note)
            assertEquals("https://example.com/open", decoded.openUrl)
            assertEquals(1, decoded.items.size)
        }
    }

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
