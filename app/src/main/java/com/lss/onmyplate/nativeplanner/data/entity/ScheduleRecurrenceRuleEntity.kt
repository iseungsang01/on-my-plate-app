package com.lss.onmyplate.nativeplanner.data.entity

data class ScheduleRecurrenceRuleEntity(
    val scheduleId: String,
    val frequency: String,
    val interval: Int,
    val dayOfWeek: Int?,
    val dayOfMonth: Int?,
    val untilAt: Long?,
    val count: Int?,
    val createdAt: Long,
    val updatedAt: Long,
)
