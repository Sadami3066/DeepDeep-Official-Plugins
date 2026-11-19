package io.github.whywood.deepdeep.plugin.sysu

import io.github.whywood.deepdeep.plugin.cli.CliJsonEnvelope
import io.github.whywood.deepdeep.plugin.cli.card.CliCardItem
import io.github.whywood.deepdeep.plugin.cli.card.CliCardModel
import io.github.whywood.deepdeep.plugin.cli.card.CliCardSection
import io.github.whywood.deepdeep.plugin.cli.card.CliCardState
import java.time.LocalDate
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

    private fun guidanceDoesNotLeakData(rendered: SysuRendered, vararg tokens: String) {
        for (token in tokens) {
            assertFalse("guidance should not contain $token: ${rendered.text}", rendered.text.contains(token))
        }
    }

    @Test
    fun scheduleObservationKeepsFullDataInCardAndOnlyGuidanceInText() {
        val rendered = render(SysuCommandKind.Schedule, "schedule.json")
        assertTrue(rendered.card != null)
        guidanceDoesNotLeakData(rendered, "2026-11-09", "第 9-11 节", "岭南学院", "6 门")
        val card = rendered.card
        assertNotNull(card)
        assertEquals(SysuCardKind.Schedule.wire, card!!.kind)
        assertEquals("课表", card.title)
        assertEquals("6 门", card.trailing)
        assertEquals("2026-1 · 第 10 周", card.context)
        assertEquals(listOf("周一 2026-11-09", "周二 2026-11-10", "周三 2026-11-11"), card.sections.map { it.label })
        assertEquals(6, card.sections.sumOf { it.items.size })
        assertEquals("第 9-11 节", card.sections.first().items.first().meta)
        assertTrue(card.note.orEmpty().contains("2026-11-09 ~ 2026-11-15"))
        assertEquals("课表 · 6 门 · 2026-1 · 第 10 周", rendered.summary)
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
    fun scoresObservationPutsCodesAndGpaInCardOnly() {
        val rendered = render(SysuCommandKind.Scores, "scores.json")
        assertTrue(rendered.card != null)
        guidanceDoesNotLeakData(rendered, "2025-2", "2.433", "课程甲", "3.3")
        val card = rendered.card!!
        assertEquals("3 门", card.trailing)
        assertEquals(1, card.sections.size)
        assertTrue(card.sections.first().label.orEmpty().contains("3 门"))
        assertTrue(card.sections.first().label.orEmpty().contains("加权绩点 2.433"))
        assertEquals("B · 3 学分 · 绩点 3.3", card.sections.first().items.first().meta)
    }

    @Test
    fun scoresWithoutCoursesSayNotPublished() {
        val output = """{"ok":true,"semesters":[{"semester":"2026-1","count":0,"credit":0,"gpa":0,"courses":[]}]}"""
        val rendered = SysuObservation.render(
            SysuCommandKind.Scores,
            CliJsonEnvelope.LENIENT.element(output)!!,
            ChineseStrings,
        )!!
        assertTrue(rendered.text.contains("成绩尚未公布"))
        assertEquals(CliCardState.EMPTY, rendered.card?.state)
        assertTrue(rendered.card?.note.orEmpty().contains("成绩尚未公布"))
        assertEquals("成绩", rendered.summary)
    }

    @Test
    fun examsObservationKeepsDatesAndTeacherDetailsInCardOnly() {
        val rendered = render(SysuCommandKind.Exams, "exams.json")
        assertTrue(rendered.card != null)
        guidanceDoesNotLeakData(rendered, "2026-11-26", "2026-12-08", "教师甲", "第 12 周", "2 场")
        val card = rendered.card!!
        assertEquals("2 场", card.trailing)
        assertEquals(listOf("2026-11-26 周四", "2026-12-08 周二"), card.sections.map { it.label })
        assertEquals("教师 教师甲", card.sections.first().items.first().body?.lineSequence()?.first())
    }

    @Test
    fun examPlanUsesSectionsOnceAndDedupes() {
        val item = CliCardItem(title = "考试甲", subtitle = "19:00~21:00 · 教室", meta = "第 9-11 节")
        val model = CliCardModel(
            kind = SysuCardKind.Exams.wire,
            title = "考试安排",
            context = "2026-1",
            total = 3,
            sections = listOf(
                CliCardSection(label = "2026-11-26 周四", items = listOf(item)),
                CliCardSection(label = "2026-11-26 周四", items = listOf(item)),
            ),
            items = listOf(item),
        )
        val plan = SysuCardFormat.examsPlan(model, today = LocalDate.of(2026, 11, 15))
        assertNotNull(plan)
        assertEquals(1, plan!!.entries.size)
        assertEquals(LocalDate.of(2026, 11, 26), plan.entries.first().date)
    }

    @Test
    fun creditsObservationGivesGuidanceWithoutInventingPlans() {
        val rendered = render(SysuCommandKind.Credits, "credits.json")
        assertTrue(rendered.text.contains("并列"))
        assertTrue(rendered.text.contains("主辅"))
        guidanceDoesNotLeakData(rendered, "n020109T00", "151", "83.5", "专业必修")
        assertNull(rendered.card)
        assertEquals("毕业学分", rendered.summary)
    }

    @Test
    fun lmsCourseObservationKeepsIdProgressAndEndDateInCard() {
        val rendered = render(SysuCommandKind.LmsCourses, "lms_courses.json")
        assertTrue(rendered.card != null)
        guidanceDoesNotLeakData(rendered, "1005339", "80%", "2027-01-31", "课程甲")
        val card = rendered.card!!
        assertEquals("3 门", card.trailing)
        assertEquals(3, card.items.size)
        assertTrue(card.sections.isEmpty())
        assertEquals("80% · 结课 2027-01-31", card.items.first().meta)
        assertEquals("课程 ID 1005339", card.items.first().subtitle)
    }

    @Test
    fun lmsTodoObservationConvertsIsoToShanghaiTime() {
        assertEquals("2026-12-11 23:00", SysuFormat.dateTime("2026-12-11T15:00:00.000Z", ZoneId.of("Asia/Shanghai")))
        val rendered = render(SysuCommandKind.LmsTodo, "lms_todo.json")
        assertTrue(rendered.text.contains("本地时间"))
        guidanceDoesNotLeakData(rendered, "2026-12-11", "Assignment II", "lms.example.edu.cn")
        val value = rendered.card?.items?.first()?.meta
        assertNotNull(value)
        assertEquals("2026-12-11 23:00", value)
    }

    @Test
    fun gymObservationKeepsSlotsAndFeesInCard() {
        val rendered = render(SysuCommandKind.GymSlots, "gym_slots.json")
        assertTrue(rendered.card != null)
        guidanceDoesNotLeakData(rendered, "2026-11-15", "08:00-09:00", "5 积分", "场地1")
        val card = rendered.card!!
        assertEquals("3 个场地", card.trailing)
        assertEquals(3, card.sections.sumOf { it.items.size })
        val venueRow = card.sections.flatMap { it.items }.first { it.title == "场地1" }
        assertTrue(venueRow.subtitle!!.contains("08:00-09:00"))
        assertTrue(venueRow.meta!!.startsWith("共 5 个"))
        assertTrue(card.note.orEmpty().contains("预约窗口 2026-11-15 ~ 2026-11-17"))
    }

    @Test
    fun gymEmptyStateKeepsNoticeInCard() {
        val emptyPayload = CliJsonEnvelope.LENIENT.element(
            """{"ok":true,"types":[{"id":"t","name":"乒乓球","window":{"start":"2026-11-16","end":"2026-11-17"},"venues":[],"notice":"维护中"}]}""",
        )!!
        val empty = SysuObservation.render(SysuCommandKind.GymSlots, emptyPayload, ChineseStrings)
        assertNotNull(empty)
        assertTrue(empty!!.text.contains("没有可预约"))
        guidanceDoesNotLeakData(empty, "维护中")
        assertEquals(CliCardState.EMPTY, empty.card?.state)
        assertTrue(empty.card?.note.orEmpty().contains("维护中"))
    }

    @Test
    fun gymFailureIsActionableWithCleanTitle() {
        val failurePayload = CliJsonEnvelope.LENIENT.element(
            """{"ok":false,"error":{"message":"gym token 失效","code":401}}""",
        )!!
        val failure = SysuObservation.render(SysuCommandKind.GymSlots, failurePayload, ChineseStrings)
        assertNotNull(failure)
        assertTrue(failure!!.isError)
        assertTrue(failure.text.contains("插件页重新登录"))
        assertEquals("体育场馆空位", failure.summary)
        assertEquals(CliCardState.ERROR, failure.card?.state)
    }

    @Test
    fun gymPartialFailuresKeepTypesAndErrorGuidance() {
        val payload = CliJsonEnvelope.LENIENT.element(
            """{"ok":false,"types":[{"id":"t1","name":"乒乓球","venues":[{"id":"v1","name":"1 号台","slots":[{"date":"2026-11-15","start":"08:00","end":"09:00","available":2}]}]}],"errors":[{"id":"t2","name":"羽毛球","error":"接口回应超时"}]}""",
        )!!
        val rendered = SysuObservation.render(SysuCommandKind.GymSlots, payload, ChineseStrings)
        assertNotNull(rendered)
        assertFalse(rendered!!.isError)
        assertTrue(rendered.text.contains("接口回应超时"))
        guidanceDoesNotLeakData(rendered, "羽毛球", "1 号台")
        val card = rendered.card
        assertEquals(CliCardState.READY, card?.state)
        assertEquals(1, card?.sections?.size)
    }

    @Test
    fun netpayExpiredSessionIsActionableAndNeverPrintsUndefined() {
        val rendered = render(SysuCommandKind.Netpay, "netpay_expired.json")
        assertFalse(rendered.text.contains("undefined"))
        assertFalse(rendered.text.contains("· ·"))
        assertTrue(rendered.text.contains("插件页重新登录"))
        assertTrue(rendered.isError)
        assertEquals("校园网服务", rendered.summary)
        assertEquals(CliCardState.ERROR, rendered.card?.state)
        assertTrue(rendered.card?.note.orEmpty().contains("插件页重新登录"))
    }

    @Test
    fun netpayServicesShowServiceStatusAndExpiryInCard() {
        val rendered = render(SysuCommandKind.Netpay, "netpay_services.json")
        assertTrue(rendered.card != null)
        guidanceDoesNotLeakData(rendered, "张三", "学号 00000000", "校园网-学生宿舍", "2027-06-30", "227")
        val card = rendered.card!!
        assertEquals("1 个服务", card.trailing)
        assertTrue(card.context.orEmpty().contains("张三"))
        assertEquals("校园网-学生宿舍", card.items.single().title)
        assertTrue(card.items.single().meta.orEmpty().contains("到期 2027-06-30"))
    }

    @Test
    fun netpayEnglishExpiryStaysVisibleInCardPlan() {
        val output = """{"info":{"success":true},"services":[{"service":"校园网","status":"正常","expire":"Dec 7, 2026","daysLeft":21}]}"""
        val rendered = SysuObservation.render(
            SysuCommandKind.Netpay,
            CliJsonEnvelope.LENIENT.element(output)!!,
            ChineseStrings,
        )!!
        val plan = SysuCardFormat.netpayPlan(rendered.card!!, today = LocalDate.of(2026, 11, 15))
        assertNotNull(plan)
        assertEquals(LocalDate.of(2026, 12, 7), plan!!.earliestExpiry)
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
