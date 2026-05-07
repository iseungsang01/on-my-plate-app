package com.lss.onmyplate.nativeplanner.domain.conflict

import com.lss.onmyplate.nativeplanner.data.entity.ScheduleEntity
import com.lss.onmyplate.nativeplanner.domain.model.ScheduleStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConflictDetectorTest {
    @Test
    fun newEndUsesExplicitEndOrDefaultDuration() {
        assertEquals(2_000L, ConflictDetector.newEnd(startAt = 1_000L, endAt = 2_000L))
        assertEquals(
            1_000L + ConflictDetector.DEFAULT_DURATION_MILLIS,
            ConflictDetector.newEnd(startAt = 1_000L, endAt = null),
        )
    }

    @Test
    fun conflictsDetectsOverlapsButAllowsTouchingBoundaries() {
        val existing = schedule(startAt = 10_000L, endAt = 20_000L)

        assertTrue(ConflictDetector.conflicts(newStart = 9_000L, newEnd = 11_000L, existing = existing))
        assertTrue(ConflictDetector.conflicts(newStart = 12_000L, newEnd = 18_000L, existing = existing))
        assertTrue(ConflictDetector.conflicts(newStart = 19_000L, newEnd = 21_000L, existing = existing))
        assertFalse(ConflictDetector.conflicts(newStart = 8_000L, newEnd = 10_000L, existing = existing))
        assertFalse(ConflictDetector.conflicts(newStart = 20_000L, newEnd = 22_000L, existing = existing))
    }

    @Test
    fun conflictsUsesDefaultEndForOpenEndedExistingSchedule() {
        val existing = schedule(startAt = 10_000L, endAt = null)
        val defaultEnd = 10_000L + ConflictDetector.DEFAULT_DURATION_MILLIS

        assertTrue(ConflictDetector.conflicts(newStart = defaultEnd - 1L, newEnd = defaultEnd + 1L, existing = existing))
        assertFalse(ConflictDetector.conflicts(newStart = defaultEnd, newEnd = defaultEnd + 1_000L, existing = existing))
    }

    private fun schedule(startAt: Long, endAt: Long?): ScheduleEntity =
        ScheduleEntity(
            id = "schedule-$startAt",
            title = "Existing",
            startAt = startAt,
            endAt = endAt,
            location = null,
            memo = null,
            status = ScheduleStatus.Confirmed.dbValue,
            sourceText = null,
            sourceApp = null,
            createdAt = 1L,
            updatedAt = 1L,
        )
}
