package io.github.whywood.deepdeep.plugin.sysu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SysuJsonTest {
    @Test
    fun parsesScheduleFixtureWithExitMarker() {
        val data = SysuJson.schedule(SysuFixtures.bashOutput("schedule.json"))
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
        val semesters = SysuJson.scores(SysuFixtures.bashOutput("scores.json"))
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
        val data = SysuJson.exams(SysuFixtures.bashOutput("exams.json"))
        assertNotNull(data)
        assertEquals(2, data!!.exams.size)
        assertEquals("2026-11-26", data.exams.first().date)
        assertEquals("19:00~21:00", data.exams.first().time)
    }

    @Test
    fun parsesCreditsFixtureWithNullableFinishCredit() {
        val plans = SysuJson.credits(SysuFixtures.bashOutput("credits.json"))
        assertNotNull(plans)
        assertEquals(2, plans!!.size)
        assertEquals("n020109T00", plans[0].professionNumber)
        assertEquals("01", plans[0].trainCateCode)
        assertEquals(83.5, plans[0].finishCredit.doubleOrNull()!!, 0.0001)
        assertNull(plans[1].finishCredit)
        assertEquals(12.0, plans[1].minGraduationCredit.doubleOrNull()!!, 0.0001)
    }

    @Test
    fun parsesLmsFixtures() {
        val courses = SysuJson.lmsCourses(SysuFixtures.bashOutput("lms_courses.json"))
        assertNotNull(courses)
        assertEquals(3, courses!!.size)
        assertEquals("1005339", courses[0].id.scalarText())
        assertNull(courses[2].progress)
        val todo = SysuJson.lmsTodo(SysuFixtures.bashOutput("lms_todo.json"))
        assertNotNull(todo)
        assertEquals("2026-12-11T15:00:00.000Z", todo!!.single().at)
    }

    @Test
    fun parsesGymFixtureWithCapitalisedFeeKeys() {
        val data = SysuJson.gymSlots(SysuFixtures.bashOutput("gym_slots.json"))
        assertNotNull(data)
        assertTrue(data!!.ok == true)
        assertEquals(2, data.types.size)
        val fee = data.types.first().fee
        assertNotNull(fee)
        assertEquals("5/10-学生", fee!!.name)
        assertEquals(5.0, fee.creditFee.doubleOrNull()!!, 0.0001)
        assertEquals(10.0, fee.cashFee.doubleOrNull()!!, 0.0001)
        assertEquals("机器1", data.types.first().venues.first().shortName)
        assertEquals(5, data.types[1].venues.first().slots.size)
    }

    @Test
    fun parsesNetpayVariants() {
        val expired = SysuJson.netpay(SysuFixtures.bashOutput("netpay_expired.json"))
        assertNotNull(expired)
        assertEquals("false", expired!!.info?.get("success").scalarText())
        assertTrue(expired.services.isEmpty())
        val services = SysuJson.netpay(SysuFixtures.bashOutput("netpay_services.json"))
        assertNotNull(services)
        assertEquals("校园网-学生宿舍", services!!.services.single().service)
        assertEquals("2027-06-30", services.services.single().expire)
        assertEquals(227, services.services.single().daysLeft.intOrNull())
    }

    @Test
    fun toleratesLeadingNoticeBeforeJson() {
        val output = "（会话已用 TGC 静默续期）\n" + SysuFixtures.bashOutput("exams.json")
        val data = SysuJson.exams(output)
        assertNotNull(data)
        assertEquals(2, data!!.exams.size)
    }

    @Test
    fun returnsNullForInvalidJson() {
        assertNull(SysuJson.schedule("boom"))
        assertNull(SysuJson.schedule(""))
        assertNull(SysuJson.gymSlots("[exit: 0]"))
        assertNull(SysuJson.netpay("not json"))
    }

    @Test
    fun stripsAnsiAndExitMarker() {
        val clean = SysuToolOutput.clean("\u001B[2m2026-1\u001B[0m\n\n[exit: 0]")
        assertEquals("2026-1", clean)
    }
}
