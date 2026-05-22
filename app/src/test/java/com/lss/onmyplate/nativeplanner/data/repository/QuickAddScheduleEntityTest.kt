package com.lss.onmyplate.nativeplanner.data.repository

import com.lss.onmyplate.nativeplanner.domain.model.ScheduleStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QuickAddScheduleEntityTest {
    @Test
    fun quickAddScheduleUsesRawInputAsConfirmedTitle() {
        val schedule = quickAddScheduleEntity(
            id = "schedule-1",
            rawText = "내일 7시 강남에서 저녁",
            startAt = 1_000L,
            endAt = 2_000L,
            location = "강남",
            sourceApp = "quick_add",
            now = 500L,
        )

        assertEquals("내일 7시 강남에서 저녁", schedule.title)
        assertEquals(ScheduleStatus.Confirmed.dbValue, schedule.status)
        assertEquals("내일 7시 강남에서 저녁", schedule.sourceText)
        assertEquals("quick_add", schedule.sourceApp)
        assertEquals(1_000L, schedule.startAt)
        assertEquals(2_000L, schedule.endAt)
        assertEquals("강남", schedule.location)
    }

    @Test
    fun quickAddScheduleDefaultsMissingEndToOneHourAndBlanksLocation() {
        val schedule = quickAddScheduleEntity(
            id = "schedule-1",
            rawText = "점심",
            startAt = 10_000L,
            endAt = null,
            location = "   ",
            sourceApp = "",
            now = 500L,
        )

        assertEquals(10_000L + 3_600_000L, schedule.endAt)
        assertNull(schedule.location)
        assertNull(schedule.sourceApp)
    }
}
