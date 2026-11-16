package io.github.whywood.deepdeep.plugin.rail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RailCardFormatTest {
    private val zh: RailTranslate = { value, _ -> value }

    private fun train(
        depart: String = "06:50",
        arrive: String = "13:07",
        duration: String = "06:17",
        date: String = "20261117",
    ) = TrainOption(
        code = "G814",
        fromStation = "杭州东",
        toStation = "北京南",
        depart = depart,
        arrive = arrive,
        duration = duration,
        seats = emptyList(),
        date = date,
    )

    @Test
    fun formatsDurationInHoursAndMinutes() {
        assertEquals("6小时17分", railDurationLabel(zh, "06:17"))
        assertEquals("6小时", railDurationLabel(zh, "06:00"))
        assertEquals("48分", railDurationLabel(zh, "00:48"))
        assertEquals("33小时48分", railDurationLabel(zh, "33:48"))
        assertEquals("未知", railDurationLabel(zh, "未知"))
    }

    @Test
    fun detectsNextDayArrival() {
        assertFalse(railNextDayArrival(train()))
        assertTrue(railNextDayArrival(train(depart = "08:22", arrive = "08:10", duration = "23:48")))
        assertTrue(railNextDayArrival(train(depart = "17:10", arrive = "07:09", duration = "13:59")))
        assertTrue(railNextDayArrival(train(depart = "23:30", arrive = "01:10", duration = "01:40")))
        assertTrue(railNextDayArrival(train(depart = "08:00", arrive = "17:00", duration = "33:00")))
    }

    @Test
    fun compactsTicketDatesAndComparesThem() {
        assertEquals("11-16", railCompactTicketDate("20261116"))
        assertEquals("11-16", railCompactTicketDate("2026-11-16"))
        assertTrue(railSameTicketDate("20261117", "2026-11-17"))
        assertFalse(railSameTicketDate("20261116", "2026-11-17"))
        assertTrue(railSameTicketDate("", "2026-11-17"))
    }
}
