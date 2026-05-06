package com.lss.onmyplate.nativeplanner.domain.model

enum class ScheduleStatus(val dbValue: String) {
    Confirmed("confirmed"),
    Planned("planned"),
    Uncertain("uncertain");
}

enum class CandidateStatus(val dbValue: String) {
    Pending("pending"),
    Confirmed("confirmed"),
    Discarded("discarded");
}

enum class TimeConfidence(val dbValue: String) {
    High("high"),
    Medium("medium"),
    Low("low");
}

data class AppointmentParseResult(
    val title: String,
    val startAt: Long?,
    val endAt: Long?,
    val location: String?,
    val confidence: Float,
    val timeConfidence: TimeConfidence,
)
