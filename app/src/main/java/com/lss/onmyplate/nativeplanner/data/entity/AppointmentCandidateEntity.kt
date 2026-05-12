package com.lss.onmyplate.nativeplanner.data.entity

data class AppointmentCandidateEntity(
    val id: String,
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
