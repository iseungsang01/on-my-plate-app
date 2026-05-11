package com.lss.onmyplate.nativeplanner.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "schedule_recurrence_rules",
    foreignKeys = [
        ForeignKey(
            entity = ScheduleEntity::class,
            parentColumns = ["id"],
            childColumns = ["scheduleId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("scheduleId")],
)
data class ScheduleRecurrenceRuleEntity(
    @PrimaryKey val scheduleId: String,
    val frequency: String,
    val interval: Int,
    val dayOfWeek: Int?,
    val dayOfMonth: Int?,
    val untilAt: Long?,
    val count: Int?,
    val createdAt: Long,
    val updatedAt: Long,
)
