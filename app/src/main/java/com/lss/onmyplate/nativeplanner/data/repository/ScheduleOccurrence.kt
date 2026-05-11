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
    data class Daily(
        val intervalDays: Int = 1,
        val untilAt: Long? = null,
        val count: Int? = null,
    ) : RecurrenceInput

    data class Weekly(
        val intervalWeeks: Int = 1,
        val untilAt: Long? = null,
        val count: Int? = null,
    ) : RecurrenceInput

    data class Monthly(
        val intervalMonths: Int = 1,
        val untilAt: Long? = null,
        val count: Int? = null,
    ) : RecurrenceInput
}
