package io.github.whywood.deepdeep.plugin.sysu

import io.github.whywood.deepdeep.plugin.cli.CliJsonEnvelope
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SysuJsonTest {
    private fun payload(fixture: String): JsonElement =
        CliJsonEnvelope.LENIENT.element(SysuFixtures.text(fixture)) ?: error("no payload in $fixture")

    private fun bashPayload(fixture: String): JsonElement =
        CliJsonEnvelope.LENIENT.element(SysuFixtures.bashOutput(fixture)) ?: error("no payload in $fixture")

    @Test
    fun parsesScheduleFixtureWithExitMarker() {
        val data = SysuJson.schedule(bashPayload("schedule.json"))
        assertNotNull(data)
        assertEquals("2026-1", data!!.term)
        assertEquals("10", data.week.scalarText())
        assertEquals(3, data.days.size)
        assertEquals("星期一", data.days.first().weekday)
        assertEquals("9-11", data.days.first().items.first().sections)
        assertEquals("岭南学院MBA大楼岭院MBA 602", data.days.first().items.first().place)
    }

    @Test
    fun parsesScoresFixtureWithoutPoint() {
        val semesters = SysuJson.scores(payload("scores.json"))
        assertNotNull(semesters)
        val semester = semesters!!.single()
        assertEquals("2025-2", semester.semester)
        assertEquals(3, semester.courses.size)
        assertEquals("B", semester.courses[0].scoFinalScore.scalarText())
        assertEquals(3.3, semester.courses[0].scoPoint.doubleOrNull()!!, 0.0001)
        assertNull(semester.courses[2].scoPoint)
        assertEquals(2.433333333333333, semester.gpa.doubleOrNull()!!, 0.0001)
    }

    @Test
    fun parsesExamsFixtureWithFullDates() {
        val data = SysuJson.exams(payload("exams.json"))
        assertNotNull(data)
        assertEquals(2, data!!.exams.size)
        assertEquals("2026-11-26", data.exams.first().date)
        assertEquals("19:00~21:00", data.exams.first().time)
        assertEquals("12", data.exams.first().week.scalarText())
        assertEquals("exam", data.exams.first().type)
        assertEquals("教师甲", data.exams.first().teacher)
    }

    @Test
    fun parsesCreditsFixtureWithNullableFinishCredit() {
        val plans = SysuJson.credits(payload("credits.json"))
        assertNotNull(plans)
        assertEquals(2, plans!!.size)
        assertEquals("n020109T00", plans[0].professionNumber)
        assertEquals("24n020109T00", plans[0].gradeProfessionCode)
        assertEquals("1916673867646238720", plans[0].teachPlanNumber)
        assertEquals(0.0, plans[1].publicMinCredit.doubleOrNull()!!, 0.0001)
        assertEquals("01", plans[0].trainCateCode)
        assertEquals(83.5, plans[0].finishCredit.doubleOrNull()!!, 0.0001)
        assertNull(plans[1].finishCredit)
        assertEquals(12.0, plans[1].minGraduationCredit.doubleOrNull()!!, 0.0001)
    }

    @Test
    fun parsesLmsFixtures() {
        val courses = SysuJson.lmsCourses(payload("lms_courses.json"))
        assertNotNull(courses)
        assertEquals(3, courses!!.size)
        assertEquals("1005339", courses[0].id.scalarText())
        assertNull(courses[2].progress)
        val todo = SysuJson.lmsTodo(payload("lms_todo.json"))
        assertNotNull(todo)
        assertEquals("2026-12-11T15:00:00.000Z", todo!!.single().at)
    }

    @Test
    fun parsesGymFixtureWithCapitalisedFeeKeys() {
        val data = SysuJson.gymSlots(payload("gym_slots.json"))
        assertNotNull(data)
        assertTrue(data!!.ok == true)
        assertEquals(2, data.types.size)
        val fee = data.types.first().fee
        assertNotNull(fee)
        assertEquals("5/10-学生", fee!!.name)
        assertEquals("fee-1", fee.identity)
        assertEquals("学生", fee.userRole)
        assertEquals("08:00", fee.timeslot?.startTime)
        assertEquals("22:30", fee.timeslot?.endTime)
        assertEquals(5.0, fee.creditFee.doubleOrNull()!!, 0.0001)
        assertEquals(10.0, fee.cashFee.doubleOrNull()!!, 0.0001)
        assertEquals("机器1", data.types.first().venues.first().shortName)
        assertEquals(5, data.types[1].venues.first().slots.size)
    }

    @Test
    fun parsesNetpayVariants() {
        val expired = SysuJson.netpay(payload("netpay_expired.json"))
        assertNotNull(expired)
        assertEquals("false", expired!!.info?.get("success").scalarText())
        assertTrue(expired.services.isEmpty())
        val services = SysuJson.netpay(payload("netpay_services.json"))
        assertNotNull(services)
        val service = services!!.services.single()
        assertEquals("校园网-学生宿舍", service.service)
        assertEquals("1", service.no)
        assertEquals("AA:BB:CC:DD:EE:FF", service.mac)
        assertEquals("计算机学院", service.dept)
        assertEquals("张三", service.user)
        assertEquals("svc-1", service.id)
        assertEquals("2027-06-30", service.expire)
        assertEquals(227, service.daysLeft.intOrNull())
    }

    @Test
    fun toleratesLeadingNoticeBeforeJson() {
        val output = "（会话已用 TGC 静默续期）\n" + SysuFixtures.bashOutput("exams.json")
        val element = CliJsonEnvelope.LENIENT.element(output)
        assertNotNull(element)
        val data = SysuJson.exams(element!!)
        assertNotNull(data)
        assertEquals(2, data!!.exams.size)
    }

    @Test
    fun returnsNullForInvalidJson() {
        assertNull(CliJsonEnvelope.LENIENT.element("boom"))
        assertNull(CliJsonEnvelope.LENIENT.element(""))
        assertNull(CliJsonEnvelope.LENIENT.element("[exit: 0]"))
        assertNull(SysuJson.schedule(JsonNull))
        assertNull(SysuJson.gymSlots(JsonNull))
    }

    @Test
    fun extractsJsonFromAnsiAndExitMarker() {
        val output = "\u001B[2m{\"term\":\"2026-2\",\"week\":1,\"days\":[]}\u001B[0m\n\n[exit: 0]"
        val element = CliJsonEnvelope.LENIENT.element(output)
        assertNotNull(element)
        val data = SysuJson.schedule(element!!)
        assertNotNull(data)
        assertEquals("2026-2", data!!.term)
    }
}
