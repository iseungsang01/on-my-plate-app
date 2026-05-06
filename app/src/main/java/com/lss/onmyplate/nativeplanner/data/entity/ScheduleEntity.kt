package com.lss.onmyplate.nativeplanner.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "schedules")
data class ScheduleEntity(
    @PrimaryKey val id: String,
    val title: String,
    val startAt: Long,
    val endAt: Long?,
    val location: String?,
    val memo: String?,
    val status: String,
    val sourceText: String?,
    val sourceApp: String?,
    val createdAt: Long,
    val updatedAt: Long,
)
