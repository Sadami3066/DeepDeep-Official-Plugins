package io.github.whywood.deepdeep.plugin.micloud

import java.time.ZoneId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MiCloudParserTest {
    @Test
    fun todoContentJsonAsStringYieldsContentDoneReminderAndTimes() {
        val record = Json.parseToJsonElement(
            """
            {
              "id": "todo-1",
              "eTag": "etag-1",
              "contentJson": "{\"content\":\"买牛奶\",\"isFinish\":0,\"remindTime\":1763200000000,\"lastModifiedTime\":1763210000000}"
            }
            """.trimIndent(),
        ).jsonObject
        val item = todoItem(record)
        assertEquals("todo-1", item.id)
        assertEquals("买牛奶", item.content)
        assertFalse(item.done)
        assertEquals(1763200000000L, item.remindTime)
        assertEquals(1763210000000L, item.modifyTime)
    }

    @Test
    fun todoContentJsonAsObjectYieldsContentAndDoneState() {
        val record = buildJsonObject {
            put("id", "todo-2")
            putJsonObject("contentJson") {
                put("content", "写周报")
                put("is_finish", true)
                put("lastModifiedTime", 1763220000000L)
            }
        }
        val item = todoItem(record)
        assertEquals("写周报", item.content)
        assertTrue(item.done)
        assertEquals(1763220000000L, item.modifyTime)
    }

    @Test
    fun todoContentJsonFallsBackToPlainTextField() {
        val record = buildJsonObject {
            put("id", "todo-3")
            putJsonObject("contentJson") {
                put("plainText", "打电话给客户")
            }
        }
        assertEquals("打电话给客户", todoItem(record).content)
    }

    @Test
    fun todoWithoutContentJsonStillParsesId() {
        val record = buildJsonObject { put("id", "todo-4") }
        val item = todoItem(record)
        assertEquals("todo-4", item.id)
        assertEquals("", item.content)
        assertFalse(item.done)
    }

    @Test
    fun noteContentXmlWithIndentBecomesPlainText() {
        val xml = """
            <note>
              <text indent="0">第一段</text>
              <text indent="1">缩进的第二段</text>
              <text indent="0">第三段 &amp; 结尾 &lt;ok&gt;</text>
            </note>
        """.trimIndent()
        assertEquals("第一段\n缩进的第二段\n第三段 & 结尾 <ok>", notePlainText(xml))
    }

    @Test
    fun noteContentWithoutMarkupIsUnchanged() {
        assertEquals("纯文本笔记", notePlainText("纯文本笔记"))
        assertEquals("", notePlainText(""))
    }

    @Test
    fun noteItemUsesSnippetOrContent() {
        val item = noteItem(
            buildJsonObject {
                put("id", "note-1")
                put("subject", "想法")
                put("content", "<note><text indent=\"0\">正文内容</text></note>")
                put("modifyDate", 1763230000000L)
            },
        )
        assertEquals("note-1", item.id)
        assertEquals("想法", item.subject)
        assertEquals("正文内容", item.snippet)
        assertEquals(1763230000000L, item.modifyTime)
    }

    @Test
    fun quotaParsesGiftAndFirstLevelEntries() {
        val raw = Json.parseToJsonElement(
            """
            {
              "code": 0,
              "data": {
                "Gift": {"total": 1073741824, "used": 0},
                "FirstLevel": {"total": 21474836480, "used": 5242880}
              }
            }
            """.trimIndent(),
        )
        val entries = parseQuotaEntries(raw)
        assertEquals(2, entries.size)
        assertEquals("Gift", entries[0].label)
        assertEquals(1073741824L, entries[0].totalBytes)
        assertEquals("FirstLevel", entries[1].label)
        assertEquals(21474836480L, entries[1].totalBytes)
        assertEquals(5242880L, entries[1].usedBytes)
        assertEquals(21474836480L - 5242880L, entries[1].freeBytes)
    }

    @Test
    fun quotaParsesNestedQuotaContainerAndIgnoresOtherEntries() {
        val raw = Json.parseToJsonElement(
            """
            {
              "data": {
                "quota": {
                  "Gift": {"quota": {"total": 536870912, "used": 1024}},
                  "FirstLevel": {"quota": {"total": 1073741824, "used": 2048}}
                },
                "vipInfo": {"total": 999}
              }
            }
            """.trimIndent(),
        )
        val entries = parseQuotaEntries(raw)
        assertEquals(2, entries.size)
        assertEquals(listOf("Gift", "FirstLevel"), entries.map { it.label })
        assertEquals(536870912L, entries[0].totalBytes)
    }

    @Test
    fun quotaFallsBackToGenericBucketsWhenNamesMissing() {
        val raw = Json.parseToJsonElement(
            """
            {"data": {"storage": {"total": 1024, "used": 512}}}
            """.trimIndent(),
        )
        val entries = parseQuotaEntries(raw)
        assertEquals(1, entries.size)
        assertEquals(1024L, entries[0].totalBytes)
        assertEquals(512L, entries[0].usedBytes)
    }

    @Test
    fun quotaEntriesRequireCapacityFields() {
        val raw = Json.parseToJsonElement("""{"data": {"Gift": {"label": "Gift"}}}""")
        assertTrue(parseQuotaEntries(raw).isEmpty())
    }

    @Test
    fun byteFormattingIsHumanReadable() {
        assertEquals("0 B", formatBytes(0))
        assertEquals("512 B", formatBytes(512))
        assertEquals("1.0 KB", formatBytes(1024))
        assertEquals("5.0 MB", formatBytes(5L * 1024 * 1024))
        assertEquals("20.0 GB", formatBytes(21474836480L))
    }

    @Test
    fun timeFormattingUsesReadablePattern() {
        val millis = 1763200000000L
        val formatted = formatShortTime(millis, ZoneId.of("Asia/Shanghai"))
        assertTrue(formatted, Regex("\\d{2}-\\d{2} \\d{2}:\\d{2}").matches(formatted))
        assertEquals("", formatShortTime(0))
    }

    @Test
    fun noteTitleComesFromExtraInfoAndFallsBackToFirstSnippetLine() {
        val titled = Json.parseToJsonElement(
            """
            {"id":"n1","subject":"","snippet":"<text indent=\"1\">第一行</text>\n<text indent=\"1\">第二行</text>",
             "modifyDate":1763200000000,
             "extraInfo":"{\"title\":\"世界地图\",\"note_content_type\":\"common\"}"}
            """.trimIndent(),
        ).jsonObject
        val item = noteItem(titled)
        assertEquals("世界地图", item.subject)
        assertEquals("第一行\n第二行", item.snippet)

        val untitled = Json.parseToJsonElement(
            """
            {"id":"n2","subject":"","snippet":"首行标题\n正文第二行",
             "extraInfo":"{\"title\":\"\"}"}
            """.trimIndent(),
        ).jsonObject
        val fallback = noteItem(untitled)
        assertEquals("首行标题", fallback.subject)
        assertEquals("正文第二行", fallback.snippet)
    }

    @Test
    fun quotaRealShapeYieldsGiftPaidLabelsAndExpiry() {
        val raw = Json.parseToJsonElement(
            """
            {"list":[
              {"size":5368709120,"level":"None","category":"Gift","status":"running"},
              {"size":53687091200,"level":"FirstLevel","category":"Buy","status":"running",
               "expireTime":1820851199999}
            ]}
            """.trimIndent(),
        )
        val entries = parseQuotaEntries(raw)
        assertEquals(2, entries.size)
        assertEquals("gift", entries.first().label)
        assertEquals(5368709120L, entries.first().totalBytes)
        assertEquals("paid", entries.last().label)
        assertEquals(1820851199999L, entries.last().expireTime)
    }

    @Test
    fun recordingDisplayNameStripsInternalIdsAndTimeFallsBack() {
        assertEquals(
            "企业微信录音_20261026215215.aac",
            recordingDisplayName("企业微信录音_20261026215215.aac_2137_3_1793024873201_1793024907391"),
        )
        assertEquals("普通录音.aac", recordingDisplayName("普通录音.aac"))
        assertTrue(recordingDisplayName("").isEmpty())
    }

    @Test
    fun galleryAlbumFallbackAndContactGroupTitles() {
        assertEquals("系统相册", galleryAlbumLabel("  ", "系统相册"))
        assertEquals("微信", galleryAlbumLabel("微信", "系统相册"))
        val groups = Json.parseToJsonElement(
            """[{"content":{"id":"g1","title":"高中同学"}},{"content":{"title":""}}]""",
        ).jsonArray
        assertEquals(listOf("高中同学"), contactGroupTitles(groups))
    }
}
