package io.github.whywood.deepdeep.plugin.cli.card

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CliCardCodecTest {
    private val kinds = setOf("chat_list", "messages", "agenda", "tasks")

    private fun decode(raw: String) = CliCardCodec.decode(Json.parseToJsonElement(raw).jsonObject, kinds)

    @Test
    fun decodesFullPayload() {
        val raw = """
            {
              "kind": "tasks",
              "context": "我的待办",
              "total": 10,
              "items": [
                {"title": "提交报告", "subtitle": "截止 11-15", "meta": "11-15", "badge": "待办",
                 "url": "https://example.com/task/1"},
                {"title": "整理设计", "badge": "已完成"}
              ],
              "open_url": "https://example.com/task/1",
              "note": "还有 8 条未显示"
            }
        """.trimIndent()
        val model = decode(raw)!!
        assertEquals("tasks", model.kind)
        assertEquals(CliCardState.READY, model.state)
        assertEquals("我的待办", model.context)
        assertEquals(10, model.total)
        assertEquals(2, model.items.size)
        assertEquals("提交报告", model.items[0].title)
        assertEquals("https://example.com/task/1", model.items[0].url)
        assertEquals("已完成", model.items[1].badge)
        assertEquals("还有 8 条未显示", model.note)
        assertTrue(model.openUrl!!.contains("/task/1"))
    }

    @Test
    fun capsRowsAtEight() {
        val items = (1..12).joinToString(",") { """{"title":"行$it"}""" }
        val model = decode("""{"kind":"chat_list","total":12,"items":[$items]}""")!!
        assertEquals(8, model.items.size)
        assertEquals(12, model.total)
    }

    @Test
    fun rejectsUnknownOrMalformedPayloads() {
        assertNull(decode("""{"total":1,"items":[]}"""))
        assertNull(decode("""{"kind":"unknown","items":[]}"""))
        assertTrue(decode("""{"kind":"messages","items":[{"subtitle":"没有标题"}]}""")!!.items.isEmpty())
        assertNull(decode("""{"kind":"messages","items":"not-an-array"}"""))
    }

    @Test
    fun totalFallsBackToItemCount() {
        val model = decode("""{"kind":"chat_list","items":[{"title":"A"},{"title":"B"}]}""")!!
        assertEquals(2, model.total)
    }

    @Test
    fun roundTripsAndKeepsFeishuWireKeys() {
        val model = CliCardModel(
            kind = "messages",
            context = "会话：oc_1",
            total = 3,
            items = listOf(CliCardItem(title = "标题", subtitle = "李雷", meta = "09:40", url = "https://example.com")),
            openUrl = "https://example.com/open",
            note = "还有 1 条未显示",
        )
        val encoded = CliCardCodec.encode(model)
        assertEquals("messages", encoded["kind"]!!.toString().trim('"'))
        assertNull(encoded["state"])
        assertNull(encoded["kind2"])
        val decoded = CliCardCodec.decode(encoded, kinds)!!
        assertEquals(model, decoded.copy(state = CliCardState.READY))
    }

    @Test
    fun encodesExplicitStateAndBuildsEmptyCards() {
        val empty = CliCardModel(kind = "agenda", state = CliCardState.EMPTY, total = 0)
        assertTrue(empty.isEmpty)
        val encoded = CliCardCodec.encode(empty)
        assertEquals("empty", encoded["state"]!!.toString().trim('"'))
        val decoded = CliCardCodec.decode(encoded, kinds)!!
        assertEquals(CliCardState.EMPTY, decoded.state)
        assertTrue(decoded.isEmpty)
    }

    @Test
    fun errorStateSurvivesRoundTrip() {
        val error = CliCardModel(kind = "messages", state = CliCardState.ERROR, note = "读取失败")
        val decoded = CliCardCodec.decode(CliCardCodec.encode(error), kinds)!!
        assertEquals(CliCardState.ERROR, decoded.state)
        assertEquals("读取失败", decoded.note)
    }

    @Test
    fun headerDetailTrailingAndSummaryRoundTrip() {
        val model = CliCardModel(
            kind = "doc_content",
            title = "Q3 产品方案",
            trailing = "版本 12",
            context = "doc_1",
            total = 1,
            summary = "正文摘要",
            openUrl = "https://example.com/doc",
        )
        val encoded = CliCardCodec.encode(model)
        assertEquals("Q3 产品方案", encoded["title"]!!.toString().trim('"'))
        assertEquals("版本 12", encoded["trailing"]!!.toString().trim('"'))
        assertEquals("正文摘要", encoded["summary"]!!.toString().trim('"'))
        assertNull(encoded["sections"])
        val decoded = CliCardCodec.decode(encoded, setOf("doc_content"))!!
        assertEquals("Q3 产品方案", decoded.title)
        assertEquals("版本 12", decoded.trailing)
        assertEquals("正文摘要", decoded.summary)
        assertEquals("https://example.com/doc", decoded.openUrl)
    }

    @Test
    fun sectionsRoundTripAndKeepFlatItemsFallback() {
        val model = CliCardModel(
            kind = "schedule",
            total = 4,
            sections = listOf(
                CliCardSection("周一", listOf(CliCardItem(title = "高数"), CliCardItem(title = "英语"))),
                CliCardSection("周二", listOf(CliCardItem(title = "物理"), CliCardItem(title = "体育"))),
            ),
        )
        val encoded = CliCardCodec.encode(model)
        val sectionsJson = encoded["sections"]!!.jsonArray
        assertEquals(2, sectionsJson.size)
        assertEquals(4, encoded["items"]!!.jsonArray.size)
        val decoded = CliCardCodec.decode(encoded, setOf("schedule"))!!
        assertEquals(2, decoded.sections.size)
        assertEquals("周一", decoded.sections[0].label)
        assertEquals("物理", decoded.sections[1].items[0].title)
        assertEquals(4, decoded.flatItems.size)
        assertEquals(4, decoded.dataRowCount)
        assertEquals(4, decoded.items.size)
    }

    @Test
    fun decodesFlatItemsWhenSectionsAreAbsent() {
        val model = CliCardCodec.decode(
            Json.parseToJsonElement("""{"kind":"schedule","total":2,"items":[{"title":"A"},{"title":"B"}]}""").jsonObject,
            setOf("schedule"),
        )!!
        assertTrue(model.sections.isEmpty())
        assertEquals(2, model.items.size)
        assertEquals(2, model.dataRowCount)
    }

    @Test
    fun capsDataRowsAcrossSectionsWithoutCountingLabels() {
        val rows = (1..5).joinToString(",") { """{"title":"行$it"}""" }
        val raw = """{"kind":"schedule","sections":[{"label":"第一天","items":[$rows]},{"label":"第二天","items":[$rows]}]}"""
        val model = CliCardCodec.decode(Json.parseToJsonElement(raw).jsonObject, setOf("schedule"))!!
        assertEquals(2, model.sections.size)
        assertEquals(CliCardCodec.MAX_ROWS, model.dataRowCount)
        assertEquals("第一天", model.sections[0].label)
        assertEquals(5, model.sections[0].items.size)
        assertEquals(3, model.sections[1].items.size)
    }
}
