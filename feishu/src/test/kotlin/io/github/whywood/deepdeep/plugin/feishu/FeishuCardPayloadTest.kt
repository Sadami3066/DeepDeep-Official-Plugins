package io.github.whywood.deepdeep.plugin.feishu

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeishuCardPayloadTest {
    private fun decode(raw: String) = decodeFeishuCardPayload(Json.parseToJsonElement(raw).jsonObject)

    @Test
    fun decodesFullPayload() {
        val raw = """
            {
              "kind": "tasks",
              "context": "我的待办",
              "total": 10,
              "items": [
                {"title": "提交报告", "subtitle": "截止 11-15", "meta": "11-15", "badge": "待办",
                 "url": "https://applink.feishu.cn/client/todo/task?guid=1", "body": "成员：ou_1、ou_2"},
                {"title": "整理设计", "badge": "已完成"}
              ],
              "open_url": "https://applink.feishu.cn/client/todo/task?guid=1",
              "note": "正文约 120 字"
            }
        """.trimIndent()
        val model = decode(raw)!!
        assertEquals(FeishuCardTypes.KIND_TASKS, model.kind)
        assertEquals("我的待办", model.context)
        assertEquals(10, model.total)
        assertEquals(2, model.items.size)
        assertEquals("提交报告", model.items[0].title)
        assertEquals("https://applink.feishu.cn/client/todo/task?guid=1", model.items[0].url)
        assertEquals("成员：ou_1、ou_2", model.items[0].body)
        assertEquals("已完成", model.items[1].badge)
        assertEquals("正文约 120 字", model.note)
        assertTrue(model.openUrl!!.contains("guid=1"))
    }

    @Test
    fun keepsAllRowsWithoutCap() {
        val items = (1..12).joinToString(",") { """{"title":"行$it"}""" }
        val model = decode("""{"kind":"chat_list","total":12,"items":[$items]}""")!!
        assertEquals(12, model.items.size)
        assertEquals(12, model.total)
    }

    @Test
    fun decodesBodyAndAvatar() {
        val model = decode(
            """{"kind":"messages","items":[{"title":"预览","avatar":"https://s3.example/a.png","body":"第一行\n第二行"}]}""",
        )!!
        assertEquals("https://s3.example/a.png", model.items.single().avatar)
        assertEquals("第一行\n第二行", model.items.single().body)
    }

    @Test
    fun rejectsUnknownOrMalformedPayloads() {
        assertNull(decode("""{"total":1,"items":[]}"""))
        assertNull(decode("""{"kind":"unknown","items":[]}"""))
        assertTrue(decode("""{"kind":"messages","items":[{"subtitle":"没有标题"}]}""")!!.items.isEmpty())
        assertNull(decode("""{"kind":"messages","items":"not-an-array"}"""))
    }

    @Test
    fun decodesEmptyResultWithTotal() {
        val model = decode("""{"kind":"agenda","total":0,"items":[],"note":"这段时间没有日程"}""")!!
        assertTrue(model.items.isEmpty())
        assertEquals(0, model.total)
        assertEquals("这段时间没有日程", model.note)
    }

    @Test
    fun totalFallsBackToItemCount() {
        val model = decode("""{"kind":"chat_list","items":[{"title":"A"},{"title":"B"}]}""")!!
        assertEquals(2, model.total)
    }
}
