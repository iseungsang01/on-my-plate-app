package com.lss.onmyplate.nativeplanner.domain.model

enum class ScheduleStatus(val dbValue: String) {
    Confirmed("confirmed"),
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

enum class AppointmentParseSource {
    LlmSuccess,
    LlmWithLocalSupplement,
    LocalFallback,
    ParserError,
    LocalOnly,
}

object AppointmentParseSourceValues {
    const val LlmSuccess = "llm_success"
    const val LlmWithLocalSupplement = "llm_with_local_supplement"
    const val LocalFallback = "local_fallback"
    const val ParserError = "parser_error"
    const val LocalOnly = "local_only"
    const val Unknown = "unknown"
}

fun AppointmentParseSource.toStoredValue(): String = when (this) {
    AppointmentParseSource.LlmSuccess -> AppointmentParseSourceValues.LlmSuccess
    AppointmentParseSource.LlmWithLocalSupplement -> AppointmentParseSourceValues.LlmWithLocalSupplement
    AppointmentParseSource.LocalFallback -> AppointmentParseSourceValues.LocalFallback
    AppointmentParseSource.ParserError -> AppointmentParseSourceValues.ParserError
    AppointmentParseSource.LocalOnly -> AppointmentParseSourceValues.LocalOnly
}

data class AppointmentParseOutcome(
    val result: AppointmentParseResult,
    val source: AppointmentParseSource,
)
