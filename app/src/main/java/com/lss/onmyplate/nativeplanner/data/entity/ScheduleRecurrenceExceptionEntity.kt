package com.lss.onmyplate.nativeplanner.data.entity

data class ScheduleRecurrenceExceptionEntity(
    val scheduleId: String,
    val occurrenceStartAt: Long,
    val action: String,
    val createdAt: Long,
)
