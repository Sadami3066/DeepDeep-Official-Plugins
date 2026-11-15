package io.github.whywood.deepdeep.plugin.sysu

import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SysuObservationTest {
    private fun render(kind: SysuCommandKind, fixture: String): SysuRendered =
        SysuObservation.render(kind, SysuFixtures.bashOutput(fixture), ChineseStrings)
            ?: error("render returned null for $fixture")

    @Test
    fun scheduleObservationKeepsFullDatesAndDedupesDepartmentPrefix() {
        val rendered = render(SysuCommandKind.Schedule, "schedule.json")
        val text = rendered.observation
        assertTrue(text.contains("2026-1"))
        assertTrue(text.contains("第 10 周"))
        assertTrue(text.contains("2026-11-09"))
        assertTrue(text.contains("2026-11-10"))
        assertTrue(text.contains("第 9-11 节"))
        assertTrue(text.contains("岭南学院MBA大楼 602"))
        assertFalse(text.contains("岭院MBA"))
        assertFalse(text.contains("卡片"))
        assertFalse(text.contains("TGC"))
        assertNotNull(rendered.card)
        val card = rendered.card!!
        assertEquals(SysuCardKind.Schedule.wire, card.kind)
        assertTrue(card.groups.isNotEmpty())
        assertEquals("周一 2026-11-09", card.groups.first().label)
        assertTrue(card.groups.sumOf { it.rows.size } <= MAX_CARD_ROWS)
    }

    @Test
    fun emptyScheduleRendersEmptyState() {
        val output = """{"term":"2026-2","week":1,"startTime":"2027-02-22","endTime":"2027-02-28","days":[]}"""
        val rendered = SysuObservation.render(SysuCommandKind.Schedule, output, ChineseStrings)
        assertNotNull(rendered)
        assertTrue(rendered!!.observation.contains("没有课程"))
        assertEquals(SysuCardState.EMPTY, rendered.card?.state)
    }

    @Test
    fun scoresObservationIncludesCodesAndSelfCalculatedGpa() {
        val rendered = render(SysuCommandKind.Scores, "scores.json")
        val text = rendered.observation
        assertTrue(text.contains("2025-2"))
        assertTrue(text.contains("加权绩点 2.433"))
        assertTrue(text.contains("课程甲（LN101）"))
        assertTrue(text.contains("绩点 3.3"))
        assertTrue(text.contains("绩点 —"))
        assertFalse(text.contains("卡片"))
        assertEquals("3 门", rendered.card?.count)
    }

    @Test
    fun examsObservationAndGroupsUseFullDates() {
        val rendered = render(SysuCommandKind.Exams, "exams.json")
        val text = rendered.observation
        assertTrue(text.contains("2026-11-26"))
        assertTrue(text.contains("2026-12-08"))
        assertTrue(text.contains("19:00~21:00"))
        assertTrue(text.contains("第 9-11 节"))
        assertTrue(text.contains("周四"))
        assertEquals(listOf("2026-11-26 周四", "2026-12-08 周二"), rendered.card?.groups?.map { it.label })
    }

    @Test
    fun creditsObservationLabelsBothPlansWithoutInventingRelations() {
        val rendered = render(SysuCommandKind.Credits, "credits.json")
        val text = rendered.observation
        assertTrue(text.contains("培养方案 1"))
        assertTrue(text.contains("培养方案 2"))
        assertTrue(text.contains("专业代码 n020109T00"))
        assertTrue(text.contains("专业代码 n07010102"))
        assertTrue(text.contains("培养类别 01"))
        assertTrue(text.contains("培养类别 05"))
        assertTrue(text.contains("毕业学分 83.5 / 151（55.3%）"))
        assertTrue(text.contains("毕业学分 0.0 / 12（0%）"))
        assertFalse(text.contains("主修"))
        assertFalse(text.contains("辅修"))
        assertNull(rendered.card)
    }

    @Test
    fun lmsCourseObservationIncludesIdProgressAndEndDate() {
        val rendered = render(SysuCommandKind.LmsCourses, "lms_courses.json")
        val text = rendered.observation
        assertTrue(text.contains("1005339"))
        assertTrue(text.contains("课程甲-2026-2027-1"))
        assertTrue(text.contains("80%"))
        assertTrue(text.contains("2027-01-31"))
        assertTrue(text.contains("进度 —"))
        assertEquals("3 门", rendered.card?.count)
    }

    @Test
    fun lmsTodoObservationConvertsIsoToLocalDateTimeWithYear() {
        assertEquals("2026-12-11 23:00", SysuFormat.dateTime("2026-12-11T15:00:00.000Z", ZoneId.of("Asia/Shanghai")))
        val rendered = render(SysuCommandKind.LmsTodo, "lms_todo.json")
        val text = rendered.observation
        assertTrue(text.contains("2026-12-11"))
        assertTrue(text.contains("Assignment II - Homework"))
        assertTrue(text.contains("lms.example.edu.cn"))
        val value = rendered.card?.groups?.first()?.rows?.first()?.value
        assertNotNull(value)
        assertTrue(value!!.startsWith("2026-12-11 "))
    }

    @Test
    fun gymObservationSeparatesLongWindowsFromSlicedSlots() {
        val rendered = render(SysuCommandKind.GymSlots, "gym_slots.json")
        val text = rendered.observation
        assertTrue(text.contains("2026-11-15"))
        assertTrue(text.contains("08:00-09:00 余59"))
        assertTrue(text.contains("长时段 08:00-22:00 余60"))
        assertTrue(text.contains("5 积分/时（5/10-学生）"))
        assertEquals(2, Regex("08:00-22:00 余60").findAll(text).count())
        assertFalse(text.contains("卡片"))
        assertNotNull(rendered.card)
        val venueRow = rendered.card!!.groups.flatMap { it.rows }.first { it.title == "场地1" }
        assertTrue(venueRow.subtitle!!.contains("08:00-09:00"))
        assertEquals(2, venueRow.subtitleMaxLines)
    }

    @Test
    fun gymEmptyAndFailureStates() {
        val empty = SysuObservation.render(
            SysuCommandKind.GymSlots,
            """{"ok":true,"types":[{"id":"t","name":"乒乓球","window":{"start":"2026-11-16","end":"2026-11-17"},"venues":[],"notice":"维护中"}]}""",
            ChineseStrings,
        )
        assertNotNull(empty)
        assertTrue(empty!!.observation.contains("没有可约时段"))
        assertTrue(empty.observation.contains("维护中"))
        val failure = SysuObservation.render(
            SysuCommandKind.GymSlots,
            """{"ok":false,"error":{"message":"gym token 失效","code":401}}""",
            ChineseStrings,
        )
        assertNotNull(failure)
        assertTrue(failure!!.isError)
        assertTrue(failure.observation.contains("插件页重新登录"))
        assertEquals(SysuCardState.ERROR, failure.card?.state)
    }

    @Test
    fun netpayExpiredSessionIsActionableAndNeverPrintsUndefined() {
        val rendered = render(SysuCommandKind.Netpay, "netpay_expired.json")
        val text = rendered.observation
        assertFalse(text.contains("undefined"))
        assertFalse(text.contains("· ·"))
        assertTrue(text.contains("插件页重新登录"))
        assertTrue(rendered.isError)
        assertEquals(SysuCardState.ERROR, rendered.card?.state)
    }

    @Test
    fun netpayServicesShowServiceStatusAndExpiryWithoutMac() {
        val rendered = render(SysuCommandKind.Netpay, "netpay_services.json")
        val text = rendered.observation
        assertTrue(text.contains("张三"))
        assertTrue(text.contains("学号 00000000"))
        assertTrue(text.contains("校园网-学生宿舍"))
        assertTrue(text.contains("正常"))
        assertTrue(text.contains("到期 2027-06-30"))
        assertTrue(text.contains("剩余 227 天"))
        assertFalse(text.contains("AA:BB"))
        assertFalse(rendered.isError)
        assertEquals("1 个服务", rendered.card?.count)
    }

    @Test
    fun netpayWithoutServicesIsReadable() {
        val rendered = render(SysuCommandKind.Netpay, "netpay_no_services.json")
        assertTrue(rendered.observation.contains("名下没有网络服务"))
        assertFalse(rendered.observation.contains("undefined"))
        assertEquals(SysuCardState.EMPTY, rendered.card?.state)
    }

    @Test
    fun allObservationsAvoidSensitiveTokensAndCardWording() {
        val rendered = listOf(
            render(SysuCommandKind.Schedule, "schedule.json"),
            render(SysuCommandKind.Scores, "scores.json"),
            render(SysuCommandKind.Exams, "exams.json"),
            render(SysuCommandKind.Credits, "credits.json"),
            render(SysuCommandKind.LmsCourses, "lms_courses.json"),
            render(SysuCommandKind.LmsTodo, "lms_todo.json"),
            render(SysuCommandKind.GymSlots, "gym_slots.json"),
            render(SysuCommandKind.Netpay, "netpay_expired.json"),
            render(SysuCommandKind.Netpay, "netpay_services.json"),
        )
        for (item in rendered) {
            assertFalse(item.observation.contains("TGC"))
            assertFalse(item.observation.contains("tgc"))
            assertFalse(item.observation.contains("undefined"))
            assertFalse(item.observation.contains("卡片"))
            assertFalse(item.observation.contains("session.json"))
        }
    }

    @Test
    fun placeDedupeLeavesUnrelatedPlacesAlone() {
        assertEquals("第六教学楼(392栋)6201", SysuFormat.place("第六教学楼(392栋)6201"))
        assertEquals("广州校区南校园第二教学楼2116", SysuFormat.place("广州校区南校园第二教学楼2116"))
        assertEquals("岭南学院MBA大楼 602", SysuFormat.place("岭南学院MBA大楼岭院MBA 602"))
        assertEquals("管理学院A楼管院A 101", SysuFormat.place("管理学院A楼管院A 101"))
    }
}
