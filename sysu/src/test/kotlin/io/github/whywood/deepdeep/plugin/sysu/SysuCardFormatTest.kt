package io.github.whywood.deepdeep.plugin.sysu

import io.github.whywood.deepdeep.plugin.cli.CliJsonEnvelope
import io.github.whywood.deepdeep.plugin.cli.card.CliCardModel
import java.time.LocalDate
import java.time.LocalDateTime
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SysuCardFormatTest {
    private fun payload(fixture: String): JsonElement =
        CliJsonEnvelope.LENIENT.element(SysuFixtures.text(fixture)) ?: error("no payload in $fixture")

    private fun card(
        kind: SysuCommandKind,
        fixture: String,
        strings: SysuStrings = ChineseStrings,
    ): CliCardModel {
        val rendered = SysuObservation.render(kind, payload(fixture), strings)
            ?: error("no observation for $fixture")
        return rendered.card ?: error("no card for $fixture")
    }

    private val EnglishStrings: SysuStrings = SysuStrings { _, en -> en }

    @Test
    fun todoPlanKeepsDueMomentAndSortsByDeadline() {
        val plan = SysuCardFormat.todoPlan(
            card(SysuCommandKind.LmsTodo, "lms_todo.json"),
            today = LocalDate.of(2026, 11, 15),
        )
        assertNotNull(plan)
        val entry = plan!!.single()
        assertEquals("Assignment II - Homework 即将到期", entry.name)
        assertEquals("课程甲-2026-2027-1", entry.course)
        assertEquals(LocalDateTime.of(2026, 12, 11, 23, 0), entry.due)
        assertTrue(entry.hasDueTime)
        assertEquals(26L, entry.daysUntil)
    }

    @Test
    fun gymPlanGroupsTypesVenuesAndDatedSlots() {
        val plan = SysuCardFormat.gymPlan(card(SysuCommandKind.GymSlots, "gym_slots.json"))
        assertNotNull(plan)
        assertEquals(2, plan!!.types.size)
        assertEquals("2026-11-15 ~ 2026-11-17", plan.window)
        val running = plan.types.first()
        assertEquals("南校园健身房跑步机", running.name)
        assertEquals("5 积分/时", running.fee)
        assertEquals("机器1", running.venues.first().name)
        val slot = running.venues.first().slots.first()
        assertEquals("2026-11-15", slot.date)
        assertEquals("10:30-11:00", slot.time)
        assertEquals(1, slot.remaining)
        val venue = plan.types[1].venues.single()
        assertEquals(3, venue.slots.size)
        assertEquals(2, venue.moreSlots)
        assertEquals(setOf("2026-11-15"), venue.slots.mapNotNull { it.date }.toSet())
        assertTrue(venue.slots.any { it.longWindow })
        assertEquals(60, venue.slots.first { it.longWindow }.remaining)
    }

    @Test
    fun gradesPlanParsesEnglishObservation() {
        val plan = SysuCardFormat.gradesPlan(card(SysuCommandKind.Scores, "scores.json", EnglishStrings))
        assertNotNull(plan)
        assertEquals(2.433, plan!!.gpa!!, 0.0001)
        assertEquals(6.0, plan.credits!!, 0.0001)
        val first = plan.rows.first()
        assertEquals("B", first.score)
        assertEquals(3.0, first.credits!!, 0.0001)
    }

    @Test
    fun gymPlanParsesEnglishAvailabilityAndWindow() {
        val plan = SysuCardFormat.gymPlan(card(SysuCommandKind.GymSlots, "gym_slots.json", EnglishStrings))
        assertNotNull(plan)
        assertEquals("5 points/hour", plan!!.types.first().fee)
        assertEquals("2026-11-15 ~ 2026-11-17", plan.window)
        assertEquals(1, plan.types.first().venues.first().slots.first().remaining)
        val venue = plan.types[1].venues.single()
        assertEquals(60, venue.slots.first { it.longWindow }.remaining)
    }

    @Test
    fun netpayPlanParsesEnglishDaysLeft() {
        val plan = SysuCardFormat.netpayPlan(
            card(SysuCommandKind.Netpay, "netpay_services.json", EnglishStrings),
            today = LocalDate.of(2026, 11, 15),
        )
        assertNotNull(plan)
        assertEquals(227L, plan!!.services.single().daysLeft)
        assertEquals(227L, plan.earliestDaysLeft)
    }

    @Test
    fun netpayPlanKeepsIdentityAndEarliestExpiry() {
        val plan = SysuCardFormat.netpayPlan(
            card(SysuCommandKind.Netpay, "netpay_services.json"),
            today = LocalDate.of(2026, 11, 15),
        )
        assertNotNull(plan)
        assertEquals("张三 · 学号 00000000 · 计算机学院", plan!!.identity)
        val service = plan.services.single()
        assertEquals("校园网-学生宿舍", service.name)
        assertEquals("正常", service.status)
        assertEquals("东校园明德园 1 号", service.address)
        assertEquals(LocalDate.of(2027, 6, 30), service.expiry)
        assertEquals(227L, service.daysLeft)
        assertEquals(LocalDate.of(2027, 6, 30), plan.earliestExpiry)
        assertEquals(227L, plan.earliestDaysLeft)
        assertNull(SysuCardFormat.netpayPlan(
            SysuObservation.render(SysuCommandKind.Netpay, payload("netpay_no_services.json"), ChineseStrings)!!.card!!,
        ))
    }
}
