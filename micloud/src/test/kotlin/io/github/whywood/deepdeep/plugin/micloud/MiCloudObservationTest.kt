package io.github.whywood.deepdeep.plugin.micloud

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MiCloudObservationTest {
    @Test
    fun capabilityOffTextUsesExactChineseGalleryMessage() {
        assertEquals(
            "相册读取未开启，请在插件页打开相册开关",
            capabilityOffText(MiCloudFeature.GALLERY, chineseText),
        )
        assertEquals(
            "笔记读取未开启，请在插件页打开笔记开关",
            capabilityOffText(MiCloudFeature.NOTES, chineseText),
        )
    }

    @Test
    fun notesListObservationCarriesIdSubjectTimeCountAndCursor() {
        val items = listOf(
            MiCloudNoteItem(id = "n1", subject = "想法", snippet = "第一行摘要", modifyTime = 1763200000000L),
        )
        val text = notesListObservation(items, total = 3, cursor = "tag-2", lastPage = false, t = chineseText)
        assertTrue(text, text.contains("笔记（共 3 条）"))
        assertTrue(text, text.contains("id=n1"))
        assertTrue(text, text.contains("主题「想法」"))
        assertTrue(text, text.contains("修改时间"))
        assertTrue(text, text.contains("第一行摘要"))
        assertTrue(text, text.contains("下一页游标：tag-2"))
        assertFalse(text, text.contains("{"))
        assertFalse(text, text.contains("<text"))
    }

    @Test
    fun notesListObservationShowsLastPageAndEmptyState() {
        assertTrue(notesListObservation(emptyList(), 0, "", true, chineseText).contains("没有笔记"))
        val text = notesListObservation(
            listOf(MiCloudNoteItem(id = "n1", subject = "a")),
            total = 1,
            cursor = "",
            lastPage = true,
            t = chineseText,
        )
        assertTrue(text, text.contains("已到最后一页"))
    }

    @Test
    fun noteReadObservationContainsPlainBodyWithoutXml() {
        val text = noteReadObservation(
            noteId = "n1",
            item = MiCloudNoteItem(id = "n1", subject = "想法", modifyTime = 1763200000000L),
            plainText = "第一段\n第二段",
            t = chineseText,
        )
        assertTrue(text, text.contains("笔记 id=n1"))
        assertTrue(text, text.contains("主题：想法"))
        assertTrue(text, text.contains("第一段\n第二段"))
        assertFalse(text, text.contains("<text"))
    }

    @Test
    fun todoListObservationCarriesContentDoneReminderCountAndCursor() {
        val items = listOf(
            MiCloudTodoItem(
                id = "t1",
                content = "买牛奶",
                done = false,
                remindTime = 1763200000000L,
                modifyTime = 1763210000000L,
            ),
            MiCloudTodoItem(id = "t2", content = "写周报", done = true),
        )
        val text = todoListObservation(items, cursor = "cursor-2", hasMore = true, t = chineseText)
        assertTrue(text, text.contains("待办（共 2 条，已完成 1 条）"))
        assertTrue(text, text.contains("id=t1"))
        assertTrue(text, text.contains("买牛奶"))
        assertTrue(text, text.contains("未完成"))
        assertTrue(text, text.contains("已完成"))
        assertTrue(text, text.contains("提醒时间"))
        assertTrue(text, text.contains("修改时间"))
        assertTrue(text, text.contains("下一页游标：cursor-2"))
        assertFalse(text, text.contains("contentJson"))
        assertFalse(text, text.contains("{"))
    }

    @Test
    fun accountQuotaToolShowsGiftAndFirstLevelAndCardDetails() = runBlocking {
        val raw = Json.parseToJsonElement(
            """
            {
              "data": {
                "Gift": {"total": 1073741824, "used": 0},
                "FirstLevel": {"total": 21474836480, "used": 5242880}
              }
            }
            """.trimIndent(),
        )
        val tools = micloudTools(FakeReadApi(listOf("/status/vip/quota/summary" to raw)))
        val result = tools.getValue("mi_cloud_account").execute(
            FakeToolCall(buildJsonObject { put("action", "quota") }),
        )
        assertFalse(result.text.orEmpty(), result.isError)
        val text = result.text.orEmpty()
        assertTrue(text, text.contains("赠送空间"))
        assertTrue(text, text.contains("基础空间"))
        assertTrue(text, text.contains("20.0 GB"))
        assertTrue(text, text.contains("5.0 MB"))
        assertFalse(text, text.contains("{"))
        val card = MiCloudAccountCardData.fromJson(requireNotNull(result.details))
        assertEquals(ACCOUNT_MODE_QUOTA, card.mode)
        assertEquals(2, card.entries.size)
        assertEquals(listOf("Gift", "FirstLevel"), card.entries.map { it.label })
    }

    @Test
    fun accountProfileToolTextAndCardDetails() = runBlocking {
        val raw = Json.parseToJsonElement("""{"data":{"nickname":"小明","userId":"12345","email":"a@example.com"}}""")
        val tools = micloudTools(FakeReadApi(listOf("/status/lite/profile" to raw)))
        val result = tools.getValue("mi_cloud_account").execute(FakeToolCall(buildJsonObject {}))
        assertFalse(result.text.orEmpty(), result.isError)
        val text = result.text.orEmpty()
        assertTrue(text, text.contains("小明"))
        assertTrue(text, text.contains("12345"))
        assertTrue(text, text.contains("a@example.com"))
        val card = MiCloudAccountCardData.fromJson(requireNotNull(result.details))
        assertEquals(ACCOUNT_MODE_PROFILE, card.mode)
        assertEquals("小明", card.nickname)
    }

    @Test
    fun notesListToolTextUsesReadableFieldsAndAttachesCard() = runBlocking {
        val raw = Json.parseToJsonElement(
            """
            {"data":{"entries":[{"id":"n1","subject":"想法","snippet":"摘要内容","modifyDate":1763200000000}],
            "syncTag":"tag-2","lastPage":false}}
            """.trimIndent(),
        )
        val tools = micloudTools(FakeReadApi(listOf("/note/full/page/" to raw)))
        val result = tools.getValue("mi_cloud_notes").execute(
            FakeToolCall(buildJsonObject { put("action", "list") }),
        )
        assertFalse(result.text.orEmpty(), result.isError)
        val text = result.text.orEmpty()
        assertTrue(text, text.contains("id=n1"))
        assertTrue(text, text.contains("主题「想法」"))
        assertTrue(text, text.contains("下一页游标：tag-2"))
        assertFalse(text, text.contains("{"))
        val card = MiCloudNotesCardData.fromJson(requireNotNull(result.details))
        assertEquals(NOTES_ACTION_LIST, card.action)
        assertEquals(1, card.total)
        assertEquals("tag-2", card.cursor)
        assertEquals("n1", card.rows.single().id)
    }

    @Test
    fun noteReadToolTextIsPlainAndAttachesCard() = runBlocking {
        val raw = Json.parseToJsonElement(
            """
            {"data":{"entry":{"id":"n1","subject":"想法","modifyDate":1763200000000,
            "content":"<note><text indent=\"0\">第一段</text><text indent=\"1\">第二段</text></note>"}}}
            """.trimIndent(),
        )
        val tools = micloudTools(FakeReadApi(listOf("/note/note/" to raw)))
        val result = tools.getValue("mi_cloud_notes").execute(
            FakeToolCall(buildJsonObject { put("action", "read"); put("id", "n1") }),
        )
        assertFalse(result.text.orEmpty(), result.isError)
        val text = result.text.orEmpty()
        assertTrue(text, text.contains("第一段\n第二段"))
        assertFalse(text, text.contains("<text"))
        val card = MiCloudNotesCardData.fromJson(requireNotNull(result.details))
        assertEquals(NOTES_ACTION_READ, card.action)
        assertEquals("n1", card.noteId)
        assertTrue(card.preview, card.preview.contains("第一段"))
    }

    @Test
    fun notesCreateToolReportsIdAndContentAndAttachesCard() = runBlocking {
        val tools = micloudTools(
            FakeReadApi(emptyList()),
            writer = FakeWriteApi("""{"data":{"id":"created-note"}}"""),
        )
        val result = tools.getValue("mi_cloud_notes").execute(
            FakeToolCall(buildJsonObject { put("action", "create"); put("subject", "新想法"); put("content", "内容") }),
        )
        assertFalse(result.text.orEmpty(), result.isError)
        val text = result.text.orEmpty()
        assertTrue(text, text.contains("已新建笔记（id=created-note）"))
        assertTrue(text, text.contains("主题：新想法"))
        val card = MiCloudNotesCardData.fromJson(requireNotNull(result.details))
        assertEquals(NOTES_ACTION_CREATE, card.action)
        assertEquals("created-note", card.noteId)
    }

    @Test
    fun todoListToolParsesStringContentJsonAndAttachesCard() = runBlocking {
        val raw = Json.parseToJsonElement(
            """
            {"data":{"records":[
              {"id":"t1","eTag":"e1","contentJson":"{\"content\":\"买牛奶\",\"isFinish\":0,\"remindTime\":1763200000000,\"lastModifiedTime\":1763210000000}"},
              {"id":"t2","eTag":"e2","contentJson":{"content":"写周报","is_finish":true}}
            ],"syncToken":"cursor-2","hasMore":true}}
            """.trimIndent(),
        )
        val tools = micloudTools(FakeReadApi(listOf("/todo/v1/user/records" to raw)))
        val result = tools.getValue("mi_cloud_todo").execute(
            FakeToolCall(buildJsonObject { put("action", "list") }),
        )
        assertFalse(result.text.orEmpty(), result.isError)
        val text = result.text.orEmpty()
        assertTrue(text, text.contains("买牛奶"))
        assertTrue(text, text.contains("写周报"))
        assertTrue(text, text.contains("未完成"))
        assertTrue(text, text.contains("已完成"))
        assertTrue(text, text.contains("提醒时间"))
        assertTrue(text, text.contains("修改时间"))
        assertTrue(text, text.contains("下一页游标：cursor-2"))
        assertFalse(text, text.contains("contentJson"))
        val card = MiCloudTodoCardData.fromJson(requireNotNull(result.details))
        assertEquals(2, card.total)
        assertEquals(1, card.doneCount)
        assertEquals("cursor-2", card.cursor)
        assertEquals("买牛奶", card.rows.first().content)
        assertTrue(card.rows.last().done)
    }

    @Test
    fun todoCreateToolReportsIdAndContentAndAttachesCard() = runBlocking {
        val tools = micloudTools(
            FakeReadApi(emptyList()),
            writer = FakeWriteApi("""{"data":{"id":"created-todo"}}"""),
        )
        val result = tools.getValue("mi_cloud_todo").execute(
            FakeToolCall(
                buildJsonObject {
                    put("action", "create")
                    put("content", "买牛奶")
                    put("remindAt", 1763200000000L)
                },
            ),
        )
        assertFalse(result.text.orEmpty(), result.isError)
        val text = result.text.orEmpty()
        assertTrue(text, text.contains("已新建待办（id=created-todo）"))
        assertTrue(text, text.contains("内容：买牛奶"))
        assertTrue(text, text.contains("提醒时间"))
        val card = MiCloudTodoCardData.fromJson(requireNotNull(result.details))
        assertEquals(TODO_ACTION_CREATE, card.action)
        assertEquals("created-todo", card.todoId)
    }

    @Test
    fun galleryToolWhenSwitchOffReturnsExactChineseMessage() = runBlocking {
        val tools = micloudTools(
            FakeReadApi(emptyList()),
            settings = FakeSettings(allCapabilities().copy(galleryRead = false)),
        )
        val result = tools.getValue("mi_cloud_gallery").execute(FakeToolCall(buildJsonObject {}))
        assertTrue(result.isError)
        assertEquals("相册读取未开启，请在插件页打开相册开关", result.text)
    }

    @Test
    fun galleryAndContactsAndSmsObservationAvoidRawJson() = runBlocking {
        val galleryRaw = Json.parseToJsonElement(
            """{"data":{"galleries":[{"id":"g1","fileName":"IMG_001.jpg","size":2048,"mimeType":"image/jpeg","dateTaken":1763200000000}]}}""",
        )
        val contactsRaw = Json.parseToJsonElement(
            """{"data":{"contacts":[{"id":"c1","name":"张三","phone":"13800000000"}]}}""",
        )
        val smsRaw = Json.parseToJsonElement(
            """{"data":{"entries":[{"id":"s1","address":"10086","body":"流量提醒","date":1763200000000,"unread":1}],"watermark":"wm-1"}}""",
        )
        val tools = micloudTools(
            FakeReadApi(
                listOf(
                    "/gallery/user/galleries" to galleryRaw,
                    "/contacts/" to contactsRaw,
                    "/sms/" to smsRaw,
                ),
            ),
        )
        val gallery = tools.getValue("mi_cloud_gallery").execute(FakeToolCall(buildJsonObject {}))
        val galleryText = gallery.text.orEmpty()
        assertTrue(galleryText, galleryText.contains("IMG_001.jpg"))
        assertTrue(galleryText, galleryText.contains("g1"))
        assertFalse(galleryText, galleryText.contains("{"))

        val contacts = tools.getValue("mi_cloud_contacts").execute(FakeToolCall(buildJsonObject {}))
        val contactsText = contacts.text.orEmpty()
        assertTrue(contactsText, contactsText.contains("张三"))
        assertTrue(contactsText, contactsText.contains("13800000000"))
        assertFalse(contactsText, contactsText.contains("{"))

        val sms = tools.getValue("mi_cloud_sms").execute(FakeToolCall(buildJsonObject {}))
        val smsText = sms.text.orEmpty()
        assertTrue(smsText, smsText.contains("10086"))
        assertTrue(smsText, smsText.contains("流量提醒"))
        assertTrue(smsText, smsText.contains("下一页 watermark：wm-1"))
        assertFalse(smsText, smsText.contains("{"))
    }

    @Test
    fun accountQuotaToolTextHasNoCardWording() = runBlocking {
        val raw = Json.parseToJsonElement("""{"data":{"FirstLevel":{"total":1024,"used":512}}}""")
        val tools = micloudTools(FakeReadApi(listOf("/status/vip/quota/summary" to raw)))
        val result = tools.getValue("mi_cloud_account").execute(
            FakeToolCall(buildJsonObject { put("action", "quota") }),
        )
        val text = result.text.orEmpty()
        assertFalse(text, text.contains("卡片"))
        assertFalse(text, text.contains("card"))
    }

    @Test
    fun todoContentJsonStringBugFixPreservesNonEmptyContent() {
        val record = Json.parseToJsonElement(
            """{"id":"t1","contentJson":"{\"content\":\"真实内容\",\"isFinish\":0}"}""",
        ).jsonObject
        val item = todoItem(record)
        assertEquals("真实内容", item.content)
    }
}
