package io.github.whywood.deepdeep.plugin.sysu

import io.github.whywood.deepdeep.plugin.cli.CliJsonEnvelope
import io.github.whywood.deepdeep.plugin.cli.card.CliCardState
import java.time.ZoneId
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SysuObservationTest {
    private fun payload(fixture: String): JsonElement =
        CliJsonEnvelope.LENIENT.element(SysuFixtures.text(fixture)) ?: error("no payload in $fixture")

    private fun render(kind: SysuCommandKind, fixture: String): SysuRendered =
        SysuObservation.render(kind, payload(fixture), ChineseStrings)
            ?: error("render returned null for $fixture")

    @Test
    fun scheduleObservationKeepsFullDatesAndDedupesDepartmentPrefix() {
        val rendered = render(SysuCommandKind.Schedule, "schedule.json")
        val text = rendered.text
        assertTrue(text.contains("2026-1"))
        assertTrue(text.contains("第 10 周"))
        assertTrue(text.contains("2026-11-09"))
        assertTrue(text.contains("2026-11-10"))
        assertTrue(text.contains("第 9-11 节"))
        assertTrue(text.contains("岭南学院MBA大楼 602"))
        assertFalse(text.contains("岭院MBA"))
        assertFalse(text.contains("卡片"))
        assertFalse(text.contains("TGC"))
        val card = rendered.card
        assertNotNull(card)
        assertEquals(SysuCardKind.Schedule.wire, card!!.kind)
        assertEquals("课表", card.title)
        assertEquals("6 门", card.trailing)
        assertEquals("2026-1 · 第 10 周", card.context)
        assertEquals(listOf("周一 2026-11-09", "周二 2026-11-10", "周三 2026-11-11"), card.sections.map { it.label })
        assertTrue(card.sections.sumOf { it.items.size } <= 8)
        assertTrue(card.note.orEmpty().contains("2026-11-09 ~ 2026-11-15"))
        assertTrue(rendered.summary.contains("课表"))
        assertTrue(rendered.summary.contains("6 门"))
    }

    @Test
    fun emptyScheduleRendersEmptyState() {
        val output = """{"term":"2026-2","week":1,"startTime":"2027-02-22","endTime":"2027-02-28","days":[]}"""
        val element = CliJsonEnvelope.LENIENT.element(output)!!
        val rendered = SysuObservation.render(SysuCommandKind.Schedule, element, ChineseStrings)
        assertNotNull(rendered)
        assertTrue(rendered!!.text.contains("没有课程"))
        assertEquals(CliCardState.EMPTY, rendered.card?.state)
        assertTrue(rendered.card?.note.orEmpty().contains("没有课程"))
    }

    @Test
    fun scoresObservationIncludesCodesAndSelfCalculatedGpa() {
        val rendered = render(SysuCommandKind.Scores, "scores.json")
        val text = rendered.text
        assertTrue(text.contains("2025-2"))
        assertTrue(text.contains("加权绩点 2.433"))
        assertTrue(text.contains("课程甲（LN101）"))
        assertTrue(text.contains("绩点 3.3"))
        assertTrue(text.contains("绩点 —"))
        assertFalse(text.contains("卡片"))
        val card = rendered.card!!
        assertEquals("3 门", card.trailing)
        assertEquals(1, card.sections.size)
        assertTrue(card.sections.first().label.orEmpty().contains("加权绩点 2.433"))
        assertEquals("B · 3 学分 · 绩点 3.3", card.sections.first().items.first().meta)
    }

    @Test
    fun examsObservationAndGroupsUseFullDates() {
        val rendered = render(SysuCommandKind.Exams, "exams.json")
        val text = rendered.text
        assertTrue(text.contains("2026-11-26"))
        assertTrue(text.contains("2026-12-08"))
        assertTrue(text.contains("19:00~21:00"))
        assertTrue(text.contains("第 9-11 节"))
        assertTrue(text.contains("周四"))
        assertTrue(text.contains("教师甲"))
        assertTrue(text.contains("第 12 周"))
        assertTrue(text.contains("考试"))
        val card = rendered.card!!
        assertEquals("2 场", card.trailing)
        assertEquals(listOf("2026-11-26 周四", "2026-12-08 周二"), card.sections.map { it.label })
    }

    @Test
    fun creditsObservationLabelsBothPlansWithoutInventingRelations() {
        val rendered = render(SysuCommandKind.Credits, "credits.json")
        val text = rendered.text
        assertTrue(text.contains("培养方案 1"))
        assertTrue(text.contains("培养方案 2"))
        assertTrue(text.contains("专业代码 n020109T00"))
        assertTrue(text.contains("专业代码 n07010102"))
        assertTrue(text.contains("培养类别 01"))
        assertTrue(text.contains("培养类别 05"))
        assertTrue(text.contains("毕业学分 83.5 / 151（55.3%）"))
        assertTrue(text.contains("毕业学分 0.0 / 12（0%）"))
        assertTrue(text.contains("年级专业代码 24n020109T00"))
        assertTrue(text.contains("教学计划 1916673867646238720"))
        assertTrue(text.contains("公共课下限 0"))
        assertFalse(text.contains("主修"))
        assertFalse(text.contains("辅修"))
        assertNull(rendered.card)
    }

    @Test
    fun lmsCourseObservationIncludesIdProgressAndEndDate() {
        val rendered = render(SysuCommandKind.LmsCourses, "lms_courses.json")
        val text = rendered.text
        assertTrue(text.contains("1005339"))
        assertTrue(text.contains("课程甲-2026-2027-1"))
        assertTrue(text.contains("80%"))
        assertTrue(text.contains("2027-01-31"))
        assertTrue(text.contains("进度 —"))
        assertTrue(text.contains("开课 2026-07-31"))
        assertTrue(text.contains("课程代码 课程甲-2026-2027-1"))
        val card = rendered.card!!
        assertEquals("3 门", card.trailing)
        assertEquals(3, card.items.size)
        assertTrue(card.sections.isEmpty())
        assertEquals("80% · 结课 2027-01-31", card.items.first().meta)
    }

    @Test
    fun lmsTodoObservationConvertsIsoToLocalDateTimeWithYear() {
        assertEquals("2026-12-11 23:00", SysuFormat.dateTime("2026-12-11T15:00:00.000Z", ZoneId.of("Asia/Shanghai")))
        val rendered = render(SysuCommandKind.LmsTodo, "lms_todo.json")
        val text = rendered.text
        assertTrue(text.contains("2026-12-11"))
        assertTrue(text.contains("Assignment II - Homework"))
        assertTrue(text.contains("lms.example.edu.cn"))
        val value = rendered.card?.items?.first()?.meta
        assertNotNull(value)
        assertTrue(value!!.startsWith("2026-12-11 "))
    }

    @Test
    fun gymObservationSeparatesLongWindowsFromSlicedSlots() {
        val rendered = render(SysuCommandKind.GymSlots, "gym_slots.json")
        val text = rendered.text
        assertTrue(text.contains("2026-11-15"))
        assertTrue(text.contains("08:00-09:00 余59"))
        assertTrue(text.contains("长时段 08:00-22:00 余60"))
        assertTrue(text.contains("5 积分/时"))
        assertTrue(text.contains("5/10-学生"))
        assertTrue(text.contains("身份 学生"))
        assertTrue(text.contains("全周 08:00-22:30"))
        assertEquals(2, Regex("08:00-22:00 余60").findAll(text).count())
        assertFalse(text.contains("卡片"))
        val card = rendered.card!!
        assertEquals("3 个场地", card.trailing)
        assertEquals(3, card.sections.sumOf { it.items.size })
        val venueRow = card.sections.flatMap { it.items }.first { it.title == "场地1" }
        assertTrue(venueRow.subtitle!!.contains("08:00-09:00"))
        assertTrue(venueRow.meta!!.startsWith("共 5 个"))
        assertTrue(card.note.orEmpty().contains("预约窗口 2026-11-15 ~ 2026-11-17"))
    }

    @Test
    fun gymEmptyAndFailureStates() {
        val emptyPayload = CliJsonEnvelope.LENIENT.element(
            """{"ok":true,"types":[{"id":"t","name":"乒乓球","window":{"start":"2026-11-16","end":"2026-11-17"},"venues":[],"notice":"维护中"}]}""",
        )!!
        val empty = SysuObservation.render(SysuCommandKind.GymSlots, emptyPayload, ChineseStrings)
        assertNotNull(empty)
        assertTrue(empty!!.text.contains("没有可约时段"))
        assertTrue(empty.text.contains("维护中"))
        val failurePayload = CliJsonEnvelope.LENIENT.element(
            """{"ok":false,"error":{"message":"gym token 失效","code":401}}""",
        )!!
        val failure = SysuObservation.render(SysuCommandKind.GymSlots, failurePayload, ChineseStrings)
        assertNotNull(failure)
        assertTrue(failure!!.isError)
        assertTrue(failure.text.contains("插件页重新登录"))
        assertEquals(CliCardState.ERROR, failure.card?.state)
    }

    @Test
    fun gymPartialFailuresKeepTypesAndErrors() {
        val payload = CliJsonEnvelope.LENIENT.element(
            """{"ok":false,"types":[{"id":"t1","name":"乒乓球","venues":[{"id":"v1","name":"1 号台","slots":[{"date":"2026-11-15","start":"08:00","end":"09:00","available":2}]}]}],"errors":[{"id":"t2","name":"羽毛球","error":"接口回应超时"}]}""",
        )!!
        val rendered = SysuObservation.render(SysuCommandKind.GymSlots, payload, ChineseStrings)
        assertNotNull(rendered)
        assertFalse(rendered!!.isError)
        assertTrue(rendered.text.contains("接口回应超时"))
        assertTrue(rendered.text.contains("羽毛球"))
        assertTrue(rendered.text.contains("1 号台"))
        val card = rendered.card
        assertEquals(CliCardState.READY, card?.state)
        assertEquals(1, card?.sections?.size)
    }

    @Test
    fun netpayExpiredSessionIsActionableAndNeverPrintsUndefined() {
        val rendered = render(SysuCommandKind.Netpay, "netpay_expired.json")
        val text = rendered.text
        assertFalse(text.contains("undefined"))
        assertFalse(text.contains("· ·"))
        assertTrue(text.contains("插件页重新登录"))
        assertTrue(rendered.isError)
        assertEquals(CliCardState.ERROR, rendered.card?.state)
        assertTrue(rendered.card?.note.orEmpty().contains("插件页重新登录"))
    }

    @Test
    fun netpayServicesShowServiceStatusAndExpiryWithoutMac() {
        val rendered = render(SysuCommandKind.Netpay, "netpay_services.json")
        val text = rendered.text
        assertTrue(text.contains("张三"))
        assertTrue(text.contains("学号 00000000"))
        assertTrue(text.contains("校园网-学生宿舍"))
        assertTrue(text.contains("正常"))
        assertTrue(text.contains("到期 2027-06-30"))
        assertTrue(text.contains("剩余 227 天"))
        assertTrue(text.contains("编号 1"))
        assertTrue(text.contains("MAC AA:BB:CC:DD:EE:FF"))
        assertTrue(text.contains("部门 计算机学院"))
        assertTrue(text.contains("用户 张三"))
        assertTrue(text.contains("服务 ID svc-1"))
        assertFalse(rendered.isError)
        val card = rendered.card!!
        assertEquals("1 个服务", card.trailing)
        assertTrue(card.context.orEmpty().contains("张三"))
        assertEquals("校园网-学生宿舍", card.items.single().title)
    }

    @Test
    fun netpayWithoutServicesIsReadable() {
        val rendered = render(SysuCommandKind.Netpay, "netpay_no_services.json")
        assertTrue(rendered.text.contains("名下没有网络服务"))
        assertFalse(rendered.text.contains("undefined"))
        assertEquals(CliCardState.EMPTY, rendered.card?.state)
        assertTrue(rendered.card?.note.orEmpty().contains("名下没有网络服务"))
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
            for (text in listOf(item.text, item.summary)) {
                assertFalse(text.contains("TGC"))
                assertFalse(text.contains("tgc"))
                assertFalse(text.contains("undefined"))
                assertFalse(text.contains("卡片"))
                assertFalse(text.contains("session.json"))
            }
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
