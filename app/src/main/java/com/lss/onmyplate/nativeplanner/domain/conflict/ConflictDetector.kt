package com.lss.onmyplate.nativeplanner.domain.conflict

import com.lss.onmyplate.nativeplanner.data.entity.ScheduleEntity

object ConflictDetector {
    const val DEFAULT_DURATION_MILLIS = 60 * 60 * 1000L

    fun newEnd(startAt: Long, endAt: Long?): Long = endAt ?: startAt + DEFAULT_DURATION_MILLIS

    fun conflicts(newStart: Long, newEnd: Long, existing: ScheduleEntity): Boolean {
        val existingEnd = existing.endAt ?: existing.startAt + DEFAULT_DURATION_MILLIS
        return newStart < existingEnd && newEnd > existing.startAt
    }
}
