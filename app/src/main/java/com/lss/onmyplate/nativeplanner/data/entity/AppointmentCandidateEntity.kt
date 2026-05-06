package com.lss.onmyplate.nativeplanner.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "appointment_candidates")
data class AppointmentCandidateEntity(
    @PrimaryKey val id: String,
    val rawText: String,
    val sourceApp: String?,
    val extractedTitle: String,
    val extractedStartAt: Long?,
    val extractedEndAt: Long?,
    val extractedLocation: String?,
    val confidence: Float,
    val timeConfidence: String,
    val status: String,
    val createdAt: Long,
)
