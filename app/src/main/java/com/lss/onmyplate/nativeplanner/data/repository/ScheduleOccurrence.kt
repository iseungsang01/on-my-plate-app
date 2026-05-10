package com.lss.onmyplate.nativeplanner.data.repository

import com.lss.onmyplate.nativeplanner.data.entity.ScheduleEntity

data class ScheduleOccurrence(
    val schedule: ScheduleEntity,
    val scheduleId: String,
    val occurrenceStartAt: Long,
    val isRecurring: Boolean,
)

sealed interface RecurrenceInput {
    data object None : RecurrenceInput
    data class Weekly(
        val untilAt: Long? = null,
        val count: Int? = null,
    ) : RecurrenceInput
}
