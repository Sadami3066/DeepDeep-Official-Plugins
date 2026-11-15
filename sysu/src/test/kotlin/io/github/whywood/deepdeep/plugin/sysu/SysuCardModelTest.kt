package io.github.whywood.deepdeep.plugin.sysu

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SysuCardModelTest {
    private fun row(index: Int): SysuCardRow = SysuCardRow(
        title = "课程 $index",
        subtitle = "教师 $index · 教室 $index",
        value = "第 $index 节",
    )

    @Test
    fun capsRowsAtEightAndReportsOverflow() {
        val groups = listOf(
            SysuCardGroup(label = "星期一", rows = (1..5).map(::row)),
            SysuCardGroup(label = "星期二", rows = (6..10).map(::row)),
        )
        val (limited, dropped) = SysuCards.capRows(groups)
        assertEquals(8, limited.sumOf { it.rows.size })
        assertEquals(2, dropped)
        assertEquals("第 10 周 · 还有 2 项未显示", SysuCards.footerWithOverflow("第 10 周", dropped, ChineseStrings))
    }

    @Test
    fun encodesAndDecodesPayloadRoundTrip() {
        val payload = SysuCards.ready(
            kind = SysuCardKind.Schedule,
            title = "课表",
            subtitle = "2026-1 · 第 10 周",
            count = "6 门",
            groups = listOf(SysuCardGroup(label = "周一 2026-11-09", rows = listOf(row(1)))),
            footer = "2026-11-09 ~ 2026-11-15",
        )
        val decoded = SysuCards.decode(SysuCards.encode(payload))
        assertEquals(payload, decoded)
        assertEquals("课表 · 6 门 · 2026-1 · 第 10 周", SysuCards.summaryText(payload))
    }

    @Test
    fun decodesEmptyErrorAndLoadingStates() {
        val empty = SysuCards.empty(SysuCardKind.Todo, "作业与待办", null, "没有待办事项。")
        assertEquals(SysuCardState.EMPTY, SysuCards.decode(SysuCards.encode(empty))?.state)
        val error = SysuCards.error(SysuCardKind.Netpay, "校园网服务", "登录已失效。")
        assertEquals(SysuCardState.ERROR, SysuCards.decode(SysuCards.encode(error))?.state)
        val loading = SysuCards.loading(SysuCardKind.Grades, "成绩", "正在读取…")
        assertEquals(SysuCardState.LOADING, SysuCards.decode(SysuCards.encode(loading))?.state)
        assertNull(SysuCards.decode(buildJsonObject { put("unexpected", "value") })?.groups?.firstOrNull())
    }

    @Test
    fun decodesLegacyPayloadAndNormalisesUnknownState() {
        val decoded = SysuCards.decode(
            buildJsonObject {
                put("kind", "unknown-kind")
                put("state", "something-else")
                put("title", "旧卡片")
                put("body", null)
            },
        )
        assertNotNull(decoded)
        val normalized = SysuCards.normalized(decoded!!)
        assertEquals(SysuCardState.READY, normalized.state)
        assertTrue(normalized.groups.isEmpty())
    }

    @Test
    fun normalisationCapsRowsForRendering() {
        val payload = SysuCardPayload(
            kind = SysuCardKind.Grades.wire,
            title = "成绩",
            groups = listOf(SysuCardGroup(rows = (1..12).map(::row))),
        )
        val normalized = SysuCards.normalized(payload)
        assertEquals(MAX_CARD_ROWS, normalized.groups.sumOf { it.rows.size })
    }

    @Test
    fun summaryTextNeverMentionsCards() {
        val payload = SysuCards.ready(SysuCardKind.Gym, "体育场馆空位", count = "2 个场地")
        assertFalse(SysuCards.summaryText(payload).contains("卡片"))
    }

    @Test
    fun malformedPayloadDecodesToNull() {
        assertNull(SysuCards.decode(buildJsonObject { put("groups", 5) }))
    }
}
