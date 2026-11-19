package io.github.whywood.superpi.plugin.micloud

import io.github.whywood.superpi.plugin.api.PluginTool
import io.github.whywood.superpi.plugin.api.PluginToolResult
import java.net.SocketTimeoutException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MiCloudObservationTest {
    @Test
    fun capabilityOffReturnsGuidanceOnly() {
        val result = tools(
            FakeReadApi(emptyList()),
            settings = FakeSettings(allCapabilities().copy(galleryRead = false)),
        ).run("mi_cloud_gallery")

        assertTrue(result.isError)
        assertEquals("相册读取未开启，请在插件页打开相册开关", result.guidance())
        assertNull(result.cardTitle())
        assertNull(result.rawBody())
    }

    @Test
    fun accountProfileReturnsRawAndFullCardPayload() {
        val raw = json("""{"result":"ok","data":{"nickname":"小明","userId":"12345","email":"a@example.com"}}""")
        val result = tools(FakeReadApi(listOf("/status/lite/profile" to raw)))
            .run("mi_cloud_account")

        assertFalse(result.isError)
        assertEquals(raw.toString(), result.rawBody())
        val card = MiCloudAccountCardData.fromJson(requireNotNull(result.payload()))
        assertEquals(ACCOUNT_MODE_PROFILE, card.mode)
        assertEquals("小明", card.nickname)
        assertEquals("12345", card.userId)
        assertEquals("a@example.com", card.email)
        assertEquals("a@example.com", card.data?.stringOrNull("email"))
        assertEquals("小米账号", result.cardTitle())
        assertNull(result.guidance())
    }

    @Test
    fun accountQuotaCardKeepsRawBucketFields() {
        val raw = json(
            """
            {"result":"ok","data":{"list":[
              {"size":5368709120,"level":"None","category":"Gift","status":"running"},
              {"size":53687091200,"level":"FirstLevel","category":"Buy","status":"running"}
            ]}}
            """.trimIndent(),
        )
        val result = tools(FakeReadApi(listOf("/status/vip/quota/summary" to raw)))
            .run("mi_cloud_account", buildJsonObject { put("action", "quota") })

        assertFalse(result.isError)
        val card = MiCloudAccountCardData.fromJson(requireNotNull(result.payload()))
        assertEquals(ACCOUNT_MODE_QUOTA, card.mode)
        assertEquals(2, card.entries.size)
        assertEquals(5368709120L, card.entries.first().totalBytes)
        assertEquals("gift", card.entries.first().label)
        assertEquals("paid", card.entries.last().label)
        assertEquals("Gift", card.entries.first().source?.stringOrNull("category"))
        assertTrue(card.data?.containsKey("list") == true)
        assertEquals("小米云空间", result.cardTitle())
    }

    @Test
    fun notesListReturnsCursorGuidanceAndFullRows() {
        val raw = json(
            """
            {"result":"ok","data":{
              "entries":[
                {"id":"n1","subject":"想法","snippet":"<text indent=\"1\">摘要内容</text>","modifyDate":1763200000000},
                {"id":"n2","subject":"","snippet":"第二行","modifyDate":1763210000000}
              ],
              "syncTag":"tag-2","lastPage":false}}
            """.trimIndent(),
        )
        val result = tools(FakeReadApi(listOf("/note/full/page/" to raw)))
            .run("mi_cloud_notes", buildJsonObject { put("action", "list") })

        assertFalse(result.isError)
        assertTrue(result.guidance().orEmpty().contains("syncTag=tag-2"))
        assertFalse(result.guidance().orEmpty().contains("本页"))
        val rawBody = requireNotNull(result.rawBody())
        assertTrue(rawBody.contains("<text"))
        val card = MiCloudNotesCardData.fromJson(requireNotNull(result.payload()))
        assertEquals(2, card.total)
        assertEquals("tag-2", card.cursor)
        assertFalse(card.lastPage)
        val first = card.rows.first()
        assertEquals("n1", first.id)
        assertEquals("摘要内容", first.snippet)
        assertTrue(first.source?.stringOrNull("snippet").orEmpty().contains("<text"))
        assertEquals("我的便签", result.cardTitle())
    }

    @Test
    fun notesLastPageWithSyncTagHasNoPagingGuidance() {
        val raw = json(
            """{"data":{"entries":[{"id":"n1","subject":"a"}],"syncTag":"52264873542819904","lastPage":true}}""",
        )
        val result = tools(FakeReadApi(listOf("/note/full/page/" to raw)))
            .run("mi_cloud_notes", buildJsonObject { put("action", "list") })
        assertTrue(result.guidance().orEmpty().contains("原始数据"))
        assertEquals("52264873542819904", MiCloudNotesCardData.fromJson(requireNotNull(result.payload())).cursor)
    }

    @Test
    fun noteReadKeepsFullOriginalInCardPayload() {
        val raw = json(
            """
            {"data":{"entry":{"id":"n1","subject":"想法","modifyDate":1763200000000,"tag":"t1",
            "content":"<note><text indent=\"0\">第一段</text><text indent=\"1\">第二段</text></note>"}}}
            """.trimIndent(),
        )
        val result = tools(FakeReadApi(listOf("/note/note/" to raw)))
            .run("mi_cloud_notes", buildJsonObject { put("action", "read"); put("id", "n1") })

        assertFalse(result.isError)
        assertTrue(result.rawBody().orEmpty().contains("<note>"))
        val card = MiCloudNotesCardData.fromJson(requireNotNull(result.payload()))
        assertEquals(NOTES_ACTION_READ, card.action)
        assertEquals("第一段\n第二段", card.preview)
        assertTrue(card.rows.single().source?.stringOrNull("content").orEmpty().contains("<note>"))
    }

    @Test
    fun todoIncrementalSyncTokenIsNotTreatedAsNextPage() {
        val raw = json(
            """
            {"data":{"records":[
              {"id":"t1","contentJson":{"content":"买牛奶","isFinish":0,"remindTime":1763200000000}}
            ],"syncToken":{"syncExtraInfo":"FgAWwITQ","watermark":"12486722662367392"},"hasMore":true}}
            """.trimIndent(),
        )
        val result = tools(FakeReadApi(listOf("/todo/v1/user/records" to raw)))
            .run("mi_cloud_todo", buildJsonObject { put("action", "list") })

        assertFalse(result.isError)
        val guidance = result.guidance().orEmpty()
        assertTrue(guidance.contains("增量同步"))
        assertTrue(guidance.contains("没有下一页"))
        assertFalse(guidance.contains("cursor"))
        val payload = requireNotNull(result.payload())
        assertFalse(payload.containsKey("cursor"))
        assertFalse(payload.containsKey("hasMore"))
        assertFalse(payload.containsKey("syncToken"))
        val card = MiCloudTodoCardData.fromJson(payload)
        assertTrue(card.rows.single().source?.containsKey("contentJson") == true)
        assertEquals("待办清单", result.cardTitle())
    }

    @Test
    fun todoDoneStateAndRowsComeFromEntity() {
        val raw = json(
            """
            {"data":{"records":[{"id":"t1","contentJson":{"entity":{"content":"写周报","is_finish":true}}}]}}
            """.trimIndent(),
        )
        val result = tools(FakeReadApi(listOf("/todo/v1/user/records" to raw)))
            .run("mi_cloud_todo", buildJsonObject { put("action", "list") })

        val card = MiCloudTodoCardData.fromJson(requireNotNull(result.payload()))
        assertEquals(1, card.doneCount)
        assertTrue(card.rows.single().done)
    }

    @Test
    fun smsUnwrapsRealEntryShapeAndWatermarkIntoCard() {
        val raw = json(
            """
            {"result":"ok","data":{"entries":[
              {"entry":{"snippet":"【向量空间】您的验证码为：532576","threadId":"107987868728198528",
               "localTime":1792939674826,"total":2,"folder":0,"unread":2,"recipients":"106939502054958",
               "tag":"227639059700810496","id":"dd53d57e"},"operation":"opNew"},
              {"entry":{"snippet":"流量提醒","threadId":"90727345956882624","localTime":1792916260114,
               "total":1402,"folder":0,"unread":0,"recipients":"10086","id":"aaba99d2"},"operation":"opNew"}
            ],"watermark":{"syncThreadTag":"1792643390601_108035480590761984","syncTag":"227645047649680384"},
            "phonecall_view":{"entries":[{"date":1794568092995,"type":"missed","duration":67,
             "number":"02137490026","id":"call1"}]}}}
            """.trimIndent(),
        )
        val result = tools(FakeReadApi(listOf("/sms/" to raw)))
            .run("mi_cloud_sms", buildJsonObject { put("limit", 20) })

        assertFalse(result.isError)
        val card = MiCloudSmsCardData.fromJson(requireNotNull(result.payload()))
        assertEquals(2, card.entries.size)
        val first = card.entries.first()
        assertEquals("106939502054958", first.number)
        assertTrue(first.snippet, first.snippet.contains("532576"))
        assertEquals(1792939674826L, first.timeMs)
        assertEquals(2, first.unread)
        assertEquals("opNew", first.operation)
        assertEquals("227645047649680384", card.nextSyncTag)
        assertEquals("1792643390601_108035480590761984", card.nextSyncThreadTag)
        assertEquals("227645047649680384", card.watermark?.stringOrNull("syncTag"))
        assertEquals(1, card.calls.size)
        assertEquals("missed", card.calls.single().type)
        assertTrue(first.source?.stringOrNull("snippet").orEmpty().contains("验证码"))
        assertTrue(result.guidance().orEmpty().contains("syncTag=227645047649680384"))
        assertTrue(result.guidance().orEmpty().contains("syncThreadTag=1792643390601_108035480590761984"))
        assertEquals("短信与通话（2 条）", result.cardTitle())
    }

    @Test
    fun contactsReadNestedContentMapAndKeepCursorsAndGroups() {
        val raw = json(
            """
            {"result":"ok","data":{"lastPage":false,"syncIgnoreTag":"105124175168178688",
              "letterIndex":{"C":["90193736209827072"]},"syncTag":"104804719951383040",
              "content":{
                "90193736209827072":{
                  "sortkey":"c   he 车 j   ia 佳 y   i 燚 ",
                  "pinyin":"chejiayi","createTime":1737548370000,"updateTime":1793284849000,
                  "spinyin":"cjy","type":"contact",
                  "content":{"starred":false,"displayName":"车佳燚",
                    "name":{"formatted":"车佳燚","familyName":"车","givenName":"佳燚"},
                    "organizations":[{"company":"1"}],"id":"90193736209827072","tag":"t1",
                    "phoneNumbers":[{"type":"mobile","value":"13825368542","primary":false}],
                    "status":"custom"}
                }
              },
              "group":[{"sortkey":"g   ao 高 z   hong 中","pinyin":"gaozhong","type":"group",
                "content":{"id":"g1","tag":"g1","title":"高中同学","status":"custom"}}]}}
            """.trimIndent(),
        )
        val result = tools(FakeReadApi(listOf("/contacts/" to raw)))
            .run("mi_cloud_contacts", buildJsonObject { put("action", "list") })

        assertFalse(result.isError)
        val card = MiCloudContactsCardData.fromJson(requireNotNull(result.payload()))
        assertEquals(1, card.contacts.size)
        val contact = card.contacts.single()
        assertEquals("车佳燚", contact.name)
        assertEquals("chejiayi", contact.pinyin)
        assertEquals(listOf("13825368542"), contact.phones)
        assertTrue(contact.source?.stringOrNull("sortkey").orEmpty().contains("车"))
        assertEquals(1, card.groups.size)
        assertTrue(result.guidance().orEmpty().contains("增量同步"))
        assertTrue(result.guidance().orEmpty().contains("没有下一页"))
        assertFalse(result.guidance().orEmpty().contains("syncTag="))
        assertFalse(result.guidance().orEmpty().contains("sortkey"))
        assertEquals("联系人", result.cardTitle())
    }

    @Test
    fun galleryAlbumsCardKeepsNameCountAndThumbnail() {
        val raw = json(
            """
            {"result":"ok","data":{"albums":[
              {"mediaCount":210,"name":"微信","albumId":"22485176458480032",
               "namePinyin":{"sortkey":"w   ei 微 x   in 信 ","pinyin":"weixin"},
               "thumbnails":[{"orientation":0,"url":"https://example.test/thumb.jpg"}],
               "userId":2959544619,"lastUpdateTime":1794752051296},
              {"mediaCount":0,"name":"知乎","albumId":"22816311495501792",
               "userId":2959544619,"lastUpdateTime":1729443877106}
            ],"isLastPage":true,"indexHash":-1056692843}}
            """.trimIndent(),
        )
        val result = tools(FakeReadApi(listOf("/gallery/" to raw)))
            .run("mi_cloud_gallery", buildJsonObject { put("action", "albums") })

        assertFalse(result.isError)
        val card = MiCloudGalleryCardData.fromJson(requireNotNull(result.payload()))
        assertEquals(GALLERY_MODE_ALBUMS, card.mode)
        assertEquals(2, card.items.size)
        assertEquals("微信", card.items.first().name)
        assertEquals(210, card.items.first().itemCount)
        assertEquals("https://example.test/thumb.jpg", card.items.first().thumbUrl)
        assertEquals("22485176458480032", card.items.first().source?.stringOrNull("albumId"))
        assertEquals("相册接口无分页参数，本次全部返回。 正文是上游原始数据：签名链接、拼音/排序索引和内部 id 不需要复述给用户。", result.guidance())
        assertEquals("我的相册", result.cardTitle())
    }

    @Test
    fun galleryTimeoutKeepsRawAndGivesRetryGuidance() {
        val result = tools(
            FakeReadApi(
                responses = emptyList(),
                failures = mapOf("/gallery/" to SocketTimeoutException("timeout")),
            ),
        ).run("mi_cloud_gallery", buildJsonObject { put("action", "list") })

        assertTrue(result.isError)
        assertEquals("timeout", result.rawBody())
        assertEquals("小米云相册请求超时，可重试", result.guidance())
        assertNull(result.cardTitle())
    }

    @Test
    fun galleryHttpErrorKeepsUpstreamBody() {
        val result = tools(
            FakeReadApi(
                responses = emptyList(),
                failures = mapOf("/gallery/" to MiCloudHttpException(status = 500, body = "upstream boom")),
            ),
        ).run("mi_cloud_gallery", buildJsonObject { put("action", "albums") })

        assertTrue(result.isError)
        assertEquals("upstream boom", result.rawBody())
        assertTrue(result.guidance().orEmpty().contains("HTTP 500"))
        assertNull(result.cardTitle())
    }

    @Test
    fun recordingsCardKeepsSourceRowsAndOffset() {
        val raw = json(
            """
            {"data":{"list":[{"id":"r1","name":"录音 1","size":1024,"duration":60,
              "createTime":1763200000000}]}}
            """.trimIndent(),
        )
        val result = tools(FakeReadApi(listOf("/sfs/" to raw)))
            .run(
                "mi_cloud_recordings",
                buildJsonObject {
                    put("action", "list")
                    put("offset", 10)
                },
            )

        assertFalse(result.isError)
        val card = MiCloudFileCardData.fromJson(requireNotNull(result.payload()))
        assertEquals(1, card.files.size)
        assertEquals(10, card.offset)
        assertEquals(11, card.nextOffset)
        assertEquals(60L, card.files.single().durationSeconds)
        assertEquals("r1", card.files.single().source?.stringOrNull("id"))
        assertTrue(result.guidance().orEmpty().contains("offset=11"))
        assertEquals("通话录音", result.cardTitle())
    }

    @Test
    fun cardPayloadSupportsNewDescriptorAndLegacyFlatDetails() {
        val payload = buildJsonObject {
            put("mode", ACCOUNT_MODE_QUOTA)
            put("entries", Json.parseToJsonElement("""[{"label":"Gift","totalBytes":10}]"""))
        }
        val details = toolResultDetails(raw = "{}", card = MiCloudCard("小米云空间（1 项）", payload))
        assertEquals("quota", cardPayload(details)?.stringOrNull("mode"))
        assertEquals(setOf(SUPERPI_RAW_KEY, SUPERPI_CARD_KEY), details.keys)
        val descriptor = details[SUPERPI_CARD_KEY] as JsonObject
        assertEquals("小米云空间（1 项）", descriptor.stringOrNull("title"))
        assertNull(descriptor["customType"])
        assertTrue(descriptor["payload"] is JsonObject)
        val legacy = buildJsonObject {
            put("mode", ACCOUNT_MODE_PROFILE)
            put("nickname", "小明")
            put(SUPERPI_CARD_KEY, buildJsonObject { put("title", "小米账号") })
        }
        assertEquals("小明", cardPayload(legacy)?.stringOrNull("nickname"))
    }

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).let { it as JsonObject }

    private fun tools(
        client: MiCloudReadApi,
        settings: MiCloudSettings = FakeSettings(allCapabilities()),
        writer: MiCloudWriteApi = FakeWriteApi(),
    ): Map<String, PluginTool> = micloudTools(client, settings, writer)

    private fun Map<String, PluginTool>.run(
        name: String,
        args: JsonObject = JsonObject(emptyMap()),
    ): PluginToolResult = runBlocking { getValue(name).execute(FakeToolCall(args)) }

    private fun PluginToolResult.rawBody(): String? {
        val text = text.orEmpty()
        val marker = "完整返回（上游原样）：\n"
        val start = text.indexOf(marker)
        if (start < 0) return null
        val rest = text.substring(start + marker.length)
        val guidance = rest.indexOf("\n\n使用说明：")
        val card = rest.indexOf("\n\n展示这张卡片")
        val ends = listOf(guidance, card).filter { it >= 0 }
        val end = ends.minOrNull() ?: -1
        return if (end >= 0) rest.substring(0, end) else rest
    }

    private fun PluginToolResult.guidance(): String? {
        val text = text.orEmpty()
        val marker = "使用说明：\n"
        val start = text.indexOf(marker)
        if (start < 0) return null
        val rest = text.substring(start + marker.length)
        val end = rest.indexOf("\n\n展示这张卡片")
        return if (end >= 0) rest.substring(0, end) else rest
    }

    private fun PluginToolResult.payload(): JsonObject? = cardPayload(details)

    private fun PluginToolResult.cardTitle(): String? =
        ((details?.get(SUPERPI_CARD_KEY) as? JsonObject)?.get("title") as? JsonPrimitive)?.contentOrNull

    @Test
    fun todoRealEntityShapeYieldsContentDoneReminderAndTimes() {
        val raw = json(
            """
            {"data":{"records":[
              {"id":"14448093760717088","type":"entity","eTag":"14448093760717088",
               "contentJson":{"assets":[],"entity":{"content":"中微 Part II 讲义：10-29 期中考前看完",
                 "plainText":"中微 Part II 讲义：10-29 期中考前看完","isFinish":0,"is_finish":false,
                 "remindTime":1763200000000,"firstRemindTime":0,"lastModifiedTime":1793317173036,
                 "syncId":"14448093760717088","id":63}}}
            ],"syncToken":{"syncExtraInfo":"FgAWwITQ","watermark":"14183766482157632"},"hasMore":true}}
            """.trimIndent(),
        )
        val result = tools(FakeReadApi(listOf("/todo/v1/user/records" to raw)))
            .run("mi_cloud_todo", buildJsonObject { put("action", "list") })

        assertFalse(result.isError)
        val card = MiCloudTodoCardData.fromJson(requireNotNull(result.payload()))
        val item = card.rows.single()
        assertEquals("中微 Part II 讲义：10-29 期中考前看完", item.content)
        assertFalse(item.done)
        assertEquals(1763200000000L, item.remindTime)
        assertEquals(1793317173036L, item.modifyTime)
        val entity = ((item.source?.get("contentJson") as? JsonObject)?.get("entity") as? JsonObject)
        assertEquals("中微 Part II 讲义：10-29 期中考前看完", entity?.stringOrNull("content"))
    }

    @Test
    fun todoDuplicateRowsProduceDuplicateGuidanceAndKeepIds() {
        val raw = json(
            """
            {"data":{"records":[
              {"id":"t1","contentJson":{"entity":{"content":"同一件事","is_finish":false}}},
              {"id":"t2","contentJson":{"entity":{"content":"同一件事","is_finish":false}}},
              {"id":"t3","contentJson":{"entity":{"content":"另一件事","is_finish":false}}}
            ],"hasMore":false}}
            """.trimIndent(),
        )
        val result = tools(FakeReadApi(listOf("/todo/v1/user/records" to raw)))
            .run("mi_cloud_todo", buildJsonObject { put("action", "list") })

        assertTrue(result.guidance().orEmpty().contains("1 条内容重复"))
        val card = MiCloudTodoCardData.fromJson(requireNotNull(result.payload()))
        assertEquals(3, card.total)
        assertEquals(1, card.duplicateCount)
        assertEquals(2, card.rows.size)
        assertEquals(listOf("t1", "t3"), card.rows.map { it.id })
        assertEquals(listOf("t2"), card.rows.first().duplicateIds)
    }

    @Test
    fun contactsSearchFiltersCardWhileRawStaysUpstream() {
        val raw = json(
            """
            {"result":"ok","data":{"content":{
              "c1":{"pinyin":"chejiayi","spinyin":"cjy","type":"contact",
                "content":{"displayName":"车佳燚","phoneNumbers":[{"value":"13825368542"}],"id":"c1"}},
              "c2":{"pinyin":"shitang","spinyin":"st","type":"contact",
                "content":{"displayName":"食堂经理","phoneNumbers":[{"value":"13700000000"}],"id":"c2"}}
            },"syncTag":"104804719951383040","hasMore":true}}
            """.trimIndent(),
        )
        val result = tools(FakeReadApi(listOf("/contacts/" to raw)))
            .run(
                "mi_cloud_contacts",
                buildJsonObject {
                    put("action", "search")
                    put("query", "食堂")
                },
            )

        assertFalse(result.isError)
        val card = MiCloudContactsCardData.fromJson(requireNotNull(result.payload()))
        assertEquals(1, card.contacts.size)
        assertEquals("食堂经理", card.contacts.single().name)
        val body = requireNotNull(result.rawBody())
        assertTrue(body.contains("食堂经理"))
        assertTrue(body.contains("车佳燚"))
        val guidance = result.guidance().orEmpty()
        assertTrue(guidance.contains("增量同步"))
        assertFalse(guidance.contains("syncTag="))
    }

    @Test
    fun noteDeleteFormCarriesTagAndPurge() {
        assertEquals(mapOf("tag" to "t1", "purge" to "1"), noteDeleteForm("t1", true))
        assertEquals(mapOf("tag" to "t1", "purge" to "0"), noteDeleteForm("t1", false))
    }
}
