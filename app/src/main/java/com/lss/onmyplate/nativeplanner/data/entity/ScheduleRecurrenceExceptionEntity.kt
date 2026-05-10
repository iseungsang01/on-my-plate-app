package com.lss.onmyplate.nativeplanner.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "schedule_recurrence_exceptions",
    primaryKeys = ["scheduleId", "occurrenceStartAt"],
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
data class ScheduleRecurrenceExceptionEntity(
    val scheduleId: String,
    val occurrenceStartAt: Long,
    val action: String,
    val createdAt: Long,
)
