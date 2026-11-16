package io.github.whywood.deepdeep.plugin.sysu

import io.github.whywood.deepdeep.plugin.cli.CliJsonEnvelope
import io.github.whywood.deepdeep.plugin.cli.card.CliCardCodec
import io.github.whywood.deepdeep.plugin.cli.card.CliCardItem
import io.github.whywood.deepdeep.plugin.cli.card.CliCardModel
import io.github.whywood.deepdeep.plugin.cli.card.CliCardSection
import io.github.whywood.deepdeep.plugin.cli.card.CliCardState
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SysuCardCodecTest {
    private fun payload(fixture: String): JsonElement =
        CliJsonEnvelope.LENIENT.element(SysuFixtures.text(fixture)) ?: error("no payload in $fixture")

    private fun rendered(kind: SysuCommandKind, fixture: String): SysuRendered =
        SysuObservation.render(kind, payload(fixture), ChineseStrings) ?: error("no observation")

    private fun roundTrip(card: CliCardModel): CliCardModel? =
        CliCardCodec.decode(CliCardCodec.encode(card), SYSU_CARD_KINDS)

    @Test
    fun scheduleCardRoundTripsThroughSharedCodec() {
        val card = rendered(SysuCommandKind.Schedule, "schedule.json").card!!
        val decoded = roundTrip(card)
        assertNotNull(decoded)
        assertEquals(card.kind, decoded!!.kind)
        assertEquals("课表", decoded.title)
        assertEquals("6 门", decoded.trailing)
        assertEquals("2026-1 · 第 10 周", decoded.context)
        assertEquals(card.sections.map { it.label }, decoded.sections.map { it.label })
        assertEquals(card.sections.flatMap { it.items }.map { it.title }, decoded.sections.flatMap { it.items }.map { it.title })
        assertTrue(decoded.note.orEmpty().contains("2026-11-09"))
    }

    @Test
    fun allSectionsAndRowsSurviveRoundTrip() {
        val sections = (1..3).map { index ->
            CliCardSection(
                label = "第 $index 组",
                items = (1..4).map { row -> CliCardItem(title = "条目 $index-$row", body = "详情 $row") },
            )
        }
        val model = CliCardModel(
            kind = SysuCardKind.Schedule.wire,
            title = "课表",
            trailing = "12 门",
            sections = sections,
        )
        val decoded = roundTrip(model)
        assertNotNull(decoded)
        assertEquals(3, decoded!!.sections.size)
        assertEquals(12, decoded.sections.sumOf { it.items.size })
        assertEquals(12, decoded.dataRowCount)
        assertEquals("详情 1", decoded.sections.first().items.first().body)
    }

    @Test
    fun emptyAndErrorStatesSurviveRoundTrip() {
        val empty = rendered(SysuCommandKind.LmsTodo, "lms_courses.json").let {
            SysuObservation.render(
                SysuCommandKind.LmsTodo,
                CliJsonEnvelope.LENIENT.element("[]")!!,
                ChineseStrings,
            )!!
        }
        val emptyDecoded = roundTrip(empty.card!!)
        assertEquals(CliCardState.EMPTY, emptyDecoded?.state)
        assertTrue(emptyDecoded?.note.orEmpty().contains("没有待办"))
        val error = rendered(SysuCommandKind.Netpay, "netpay_expired.json").card!!
        val errorDecoded = roundTrip(error)
        assertEquals(CliCardState.ERROR, errorDecoded?.state)
        assertTrue(errorDecoded?.note.orEmpty().contains("插件页重新登录"))
    }

    @Test
    fun decodesLegacyFlatItemsWithoutSections() {
        val legacy = buildJsonObject {
            put("kind", SysuCardKind.Grades.wire)
            put("title", "成绩")
            put("trailing", "2 门")
            put(
                "items",
                kotlinx.serialization.json.buildJsonArray {
                    add(
                        buildJsonObject {
                            put("title", "课程甲")
                            put("subtitle", "LN101")
                            put("meta", "B")
                        },
                    )
                },
            )
        }
        val decoded = CliCardCodec.decode(legacy, SYSU_CARD_KINDS)
        assertNotNull(decoded)
        assertEquals("成绩", decoded!!.title)
        assertEquals(1, decoded.items.size)
        assertTrue(decoded.sections.isEmpty())
        assertEquals("LN101", decoded.items.single().subtitle)
    }

    @Test
    fun rejectsUnknownKindAndMalformedPayload() {
        assertNull(CliCardCodec.decode(buildJsonObject { put("kind", "unknown") }, SYSU_CARD_KINDS))
        assertNull(CliCardCodec.decode(buildJsonObject { put("kind", 3) }, SYSU_CARD_KINDS))
        assertNull(CliCardCodec.decode(buildJsonObject { put("items", 5) }, SYSU_CARD_KINDS))
    }

    @Test
    fun wirePayloadDoesNotContainCardWordingOrSensitiveTokens() {
        val cards = listOf(
            rendered(SysuCommandKind.Schedule, "schedule.json").card!!,
            rendered(SysuCommandKind.GymSlots, "gym_slots.json").card!!,
            rendered(SysuCommandKind.Netpay, "netpay_services.json").card!!,
        )
        for (card in cards) {
            val encoded = CliCardCodec.encode(card).toString()
            assertFalse(encoded.contains("卡片"))
            assertFalse(encoded.contains("TGC"))
            assertFalse(encoded.contains("undefined"))
        }
    }

    @Test
    fun cardSummaryUsesTitleCountAndContext() {
        val schedule = rendered(SysuCommandKind.Schedule, "schedule.json")
        assertEquals("课表 · 6 门 · 2026-1 · 第 10 周", schedule.summary)
        val netpay = rendered(SysuCommandKind.Netpay, "netpay_services.json")
        assertEquals("校园网服务 · 1 个服务 · 张三 · 学号 00000000 · 计算机学院", netpay.summary)
    }
}
