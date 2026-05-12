package com.lss.onmyplate.nativeplanner.data.entity

data class ScheduleEntity(
    val id: String,
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
