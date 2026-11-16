package io.github.whywood.deepdeep.plugin.micloud

import io.github.whywood.deepdeep.plugin.api.PluginToolResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
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
        val text = notesListObservation(items, cursor = "tag-2", lastPage = false, t = chineseText)
        assertTrue(text, text.contains("笔记（本页 1 条）"))
        assertTrue(text, text.contains("id=n1"))
        assertTrue(text, text.contains("主题「想法」"))
        assertTrue(text, text.contains("修改时间"))
        assertTrue(text, text.contains("第一行摘要"))
        assertTrue(text, text.contains("下一页用 syncTag=tag-2"))
        assertFalse(text, text.contains("{"))
        assertFalse(text, text.contains("<text"))
    }

    @Test
    fun notesListObservationShowsLastPageAndEmptyState() {
        assertTrue(notesListObservation(emptyList(), "", true, chineseText).contains("没有笔记"))
        val text = notesListObservation(
            listOf(MiCloudNoteItem(id = "n1", subject = "a")),
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
        assertTrue(text, text.contains("待办（本页 2 条，已完成 1 条）"))
        assertTrue(text, text.contains("id=t1"))
        assertTrue(text, text.contains("买牛奶"))
        assertTrue(text, text.contains("未完成"))
        assertTrue(text, text.contains("已完成"))
        assertTrue(text, text.contains("提醒时间"))
        assertTrue(text, text.contains("修改时间"))
        assertTrue(text, text.contains("下一页用 cursor=cursor-2"))
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
        assertTrue(text, text.contains("下一页用 syncTag=tag-2"))
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
        assertTrue(text, text.contains("下一页用 cursor=cursor-2"))
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
        assertTrue(smsText, smsText.contains("下一页用 syncTag=wm-1"))
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

    @Test
    fun notesListToolKeepsAllRowsAndMountsRaw() = runBlocking {
        val entries = buildJsonArray {
            repeat(60) { index ->
                add(
                    buildJsonObject {
                        put("id", "n$index")
                        put("subject", "标题 $index")
                        put("snippet", "摘要 $index")
                        put("modifyDate", 1763200000000L + index)
                    },
                )
            }
        }
        val raw = buildJsonObject {
            put(
                "data",
                buildJsonObject {
                    put("entries", entries)
                    put("syncTag", "tag-60")
                    put("lastPage", false)
                },
            )
        }
        val result = micloudTools(FakeReadApi(listOf("/note/full/page/" to raw)))
            .getValue("mi_cloud_notes")
            .execute(FakeToolCall(buildJsonObject { put("action", "list") }))
        val text = result.text.orEmpty()
        repeat(60) { assertTrue(text, text.contains("id=n$it")) }
        assertTrue(text, text.contains("笔记（本页 60 条）"))
        assertTrue(text, text.contains("下一页用 syncTag=tag-60"))
        val card = MiCloudNotesCardData.fromJson(requireNotNull(result.details))
        assertEquals(60, card.rows.size)
        assertEquals("tag-60", card.cursor)
        assertTrue(result.rawBody().orEmpty().contains("\"syncTag\":\"tag-60\""))
        assertEquals("我的便签（60 条）", result.cardTitle())
    }

    @Test
    fun noteReadToolKeepsFullBodyBeyondFormerLimit() = runBlocking {
        val body = (1..900).joinToString("\n") { "正文行 $it" }
        val raw = buildJsonObject {
            put(
                "data",
                buildJsonObject {
                    put(
                        "entry",
                        buildJsonObject {
                            put("id", "n1")
                            put("subject", "长笔记")
                            put("modifyDate", 1763200000000L)
                            put("content", "<note><text indent=\"0\">$body</text></note>")
                        },
                    )
                },
            )
        }
        val result = micloudTools(FakeReadApi(listOf("/note/note/" to raw)))
            .getValue("mi_cloud_notes")
            .execute(FakeToolCall(buildJsonObject { put("action", "read"); put("id", "n1") }))
        val text = result.text.orEmpty()
        assertTrue(text, text.contains("正文行 1"))
        assertTrue(text, text.contains("正文行 900"))
        val card = MiCloudNotesCardData.fromJson(requireNotNull(result.details))
        assertTrue(card.preview, card.preview.contains("正文行 900"))
        assertTrue(card.preview, card.preview.length > 5000)
        assertTrue(result.rawBody().orEmpty().contains("长笔记"))
    }

    @Test
    fun todoListToolKeepsAllRowsAndSyncToken() = runBlocking {
        val records = buildJsonArray {
            repeat(60) { index ->
                add(
                    buildJsonObject {
                        put("id", "t$index")
                        putJsonObject("contentJson") {
                            put("content", "待办 $index")
                            put("is_finish", index % 2 == 0)
                        }
                    },
                )
            }
        }
        val raw = buildJsonObject {
            put(
                "data",
                buildJsonObject {
                    put("records", records)
                    put("syncToken", "tk-60")
                    put("hasMore", true)
                },
            )
        }
        val result = micloudTools(FakeReadApi(listOf("/todo/v1/user/records" to raw)))
            .getValue("mi_cloud_todo")
            .execute(FakeToolCall(buildJsonObject { put("action", "list") }))
        val text = result.text.orEmpty()
        repeat(60) { assertTrue(text, text.contains("id=t$it")) }
        assertTrue(text, text.contains("待办（本页 60 条，已完成 30 条）"))
        assertTrue(text, text.contains("下一页用 cursor=tk-60"))
        val card = MiCloudTodoCardData.fromJson(requireNotNull(result.details))
        assertEquals(60, card.rows.size)
        assertEquals(30, card.doneCount)
        assertTrue(result.rawBody().orEmpty().contains("\"syncToken\":\"tk-60\""))
    }

    @Test
    fun smsToolKeepsAllEntriesFullBodiesAndWatermarkCursor() = runBlocking {
        val longBody = "完整正文".repeat(40)
        val entries = buildJsonArray {
            repeat(60) { index ->
                add(
                    buildJsonObject {
                        put("id", "s$index")
                        put("address", "10086")
                        put("body", "短信内容 $index $longBody")
                        put("date", 1763200000000L + index)
                    },
                )
            }
        }
        val raw = buildJsonObject {
            put(
                "data",
                buildJsonObject {
                    put("entries", entries)
                    put("watermark", "wm-60")
                },
            )
        }
        val result = micloudTools(FakeReadApi(listOf("/sms/" to raw)))
            .getValue("mi_cloud_sms")
            .execute(FakeToolCall(buildJsonObject {}))
        val text = result.text.orEmpty()
        repeat(60) { assertTrue(text, text.contains("id=s$it")) }
        assertTrue(text, text.contains("短信与通话（本页 60 条）"))
        assertTrue(text, text.contains(longBody))
        assertTrue(text, text.contains("下一页用 syncTag=wm-60"))
        assertTrue(result.rawBody().orEmpty().contains("\"watermark\":\"wm-60\""))
    }

    @Test
    fun contactsToolKeepsDeepArraysAndSyncTags() = runBlocking {
        val phones = buildJsonArray {
            repeat(15) { index ->
                add(
                    buildJsonObject {
                        put("number", "138000000" + index.toString().padStart(2, '0'))
                        put("type", "mobile")
                    },
                )
            }
        }
        val raw = buildJsonObject {
            put(
                "data",
                buildJsonObject {
                    putJsonArray("contacts") {
                        add(
                            buildJsonObject {
                                put("id", "c1")
                                put("name", "张三")
                                put("organization", "小米")
                                put("note", "深层备注")
                                put("phoneNumbers", phones)
                                putJsonArray("emails") {
                                    add(
                                        buildJsonObject {
                                            put("address", "a@example.com")
                                            put("type", "home")
                                        },
                                    )
                                }
                            },
                        )
                    }
                    put("syncTag", "ct-2")
                    put("syncIgnoreTag", "ci-2")
                },
            )
        }
        val result = micloudTools(FakeReadApi(listOf("/contacts/" to raw)))
            .getValue("mi_cloud_contacts")
            .execute(FakeToolCall(buildJsonObject {}))
        val text = result.text.orEmpty()
        assertTrue(text, text.contains("联系人（本页 1 位）"))
        assertTrue(text, text.contains("13800000014"))
        assertTrue(text, text.contains("a@example.com"))
        assertTrue(text, text.contains("下一页用 syncTag=ct-2"))
        assertTrue(text, text.contains("syncIgnoreTag=ci-2"))
        assertTrue(result.rawBody().orEmpty().contains("深层备注"))
        assertTrue(result.rawBody().orEmpty().contains("13800000014"))
    }

    @Test
    fun recordingsToolKeepsAllRowsAndOffsetCursor() = runBlocking {
        val raw = buildJsonObject {
            put(
                "data",
                buildJsonObject {
                    putJsonArray("list") {
                        repeat(55) { index ->
                            add(
                                buildJsonObject {
                                    put("id", "r$index")
                                    put("name", "录音 $index")
                                    put("size", 1024L + index)
                                    put("duration", 60L + index)
                                    put("createTime", 1763200000000L + index)
                                },
                            )
                        }
                    }
                },
            )
        }
        val result = micloudTools(FakeReadApi(listOf("/sfs/" to raw)))
            .getValue("mi_cloud_recordings")
            .execute(
                FakeToolCall(
                    buildJsonObject {
                        put("action", "list")
                        put("offset", 10)
                    },
                ),
            )
        val text = result.text.orEmpty()
        repeat(55) { assertTrue(text, text.contains("id=r$it")) }
        assertTrue(text, text.contains("录音（本页 55 条）"))
        assertTrue(text, text.contains("下一页参数：offset=65"))
        val card = MiCloudFileCardData.fromJson(requireNotNull(result.details))
        assertEquals(55, card.files.size)
        assertTrue(result.rawBody().orEmpty().contains("\"id\":\"r54\""))
        assertEquals("通话录音（55 条）", result.cardTitle())
    }

    @Test
    fun galleryAndAlbumsReturnAllRowsWithoutPagination() = runBlocking {
        val raw = buildJsonObject {
            put(
                "data",
                buildJsonObject {
                    putJsonArray("galleries") {
                        repeat(60) { index ->
                            add(
                                buildJsonObject {
                                    put("id", "g$index")
                                    put("fileName", "IMG_$index.jpg")
                                    put("size", 1024)
                                    put("dateTaken", 1763200000000L)
                                },
                            )
                        }
                    }
                    putJsonArray("albums") {
                        repeat(30) { index ->
                            add(
                                buildJsonObject {
                                    put("id", "a$index")
                                    put("name", "相册 $index")
                                    put("photoCount", index)
                                },
                            )
                        }
                    }
                },
            )
        }
        val tools = micloudTools(FakeReadApi(listOf("/gallery/" to raw)))
        val gallery = tools.getValue("mi_cloud_gallery").execute(FakeToolCall(buildJsonObject {}))
        val galleryText = gallery.text.orEmpty()
        repeat(60) { assertTrue(galleryText, galleryText.contains("id=g$it")) }
        assertTrue(galleryText, galleryText.contains("相册内容（本页 60 项）"))
        assertTrue(galleryText, galleryText.contains("无分页参数"))
        assertTrue(gallery.rawBody().orEmpty().contains("\"id\":\"g59\""))

        val albums = tools.getValue("mi_cloud_gallery").execute(
            FakeToolCall(buildJsonObject { put("action", "albums") }),
        )
        val albumText = albums.text.orEmpty()
        repeat(30) { assertTrue(albumText, albumText.contains("id=a$it")) }
        assertTrue(albumText, albumText.contains("相册（本页 30 个）"))
        assertTrue(albumText, albumText.contains("无分页参数"))
        assertTrue(albums.rawBody().orEmpty().contains("\"id\":\"a29\""))
    }

    @Test
    fun accountToolsMountRawAndCardTitle() = runBlocking {
        val quotaRaw = buildJsonObject {
            put(
                "data",
                buildJsonObject {
                    put("Gift", buildJsonObject { put("total", 1073741824); put("used", 0) })
                    put("FirstLevel", buildJsonObject { put("total", 21474836480L); put("used", 5242880) })
                },
            )
        }
        val result = micloudTools(FakeReadApi(listOf("/status/vip/quota/summary" to quotaRaw)))
            .getValue("mi_cloud_account")
            .execute(FakeToolCall(buildJsonObject { put("action", "quota") }))
        assertTrue(result.rawBody().orEmpty().contains("\"FirstLevel\""))
        assertEquals("小米云空间（2 项）", result.cardTitle())
    }
}

private fun PluginToolResult.rawBody(): String? =
    (details?.get(DEEPDEEP_RAW_KEY) as? JsonPrimitive)?.contentOrNull

private fun PluginToolResult.cardTitle(): String? =
    ((details?.get(DEEPDEEP_CARD_KEY) as? JsonObject)?.get("title") as? JsonPrimitive)?.contentOrNull
