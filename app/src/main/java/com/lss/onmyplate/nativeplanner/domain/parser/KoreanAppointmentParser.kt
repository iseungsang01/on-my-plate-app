package com.lss.onmyplate.nativeplanner.domain.parser

import com.lss.onmyplate.nativeplanner.domain.model.AppointmentParseResult
import com.lss.onmyplate.nativeplanner.domain.model.TimeConfidence
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

class KoreanAppointmentParser(
    private val zoneId: ZoneId = ZoneId.of("Asia/Seoul"),
    private val llmParser: GeminiAppointmentParser? = null,
) {
    private val weekdayMap = mapOf(
        "월요일" to DayOfWeek.MONDAY,
        "화요일" to DayOfWeek.TUESDAY,
        "수요일" to DayOfWeek.WEDNESDAY,
        "목요일" to DayOfWeek.THURSDAY,
        "금요일" to DayOfWeek.FRIDAY,
        "토요일" to DayOfWeek.SATURDAY,
        "일요일" to DayOfWeek.SUNDAY,
        "월욜" to DayOfWeek.MONDAY,
        "화욜" to DayOfWeek.TUESDAY,
        "수욜" to DayOfWeek.WEDNESDAY,
        "목욜" to DayOfWeek.THURSDAY,
        "금욜" to DayOfWeek.FRIDAY,
        "토욜" to DayOfWeek.SATURDAY,
        "일욜" to DayOfWeek.SUNDAY,
    )

    suspend fun parse(rawText: String, receivedAt: Long): AppointmentParseResult {
        val local = parseLocally(rawText, receivedAt)
        if (local.startAt != null && local.confidence >= 0.67f) return local
        return llmParser?.parse(rawText, receivedAt)?.mergeFallback(local) ?: local
    }

    private fun parseLocally(rawText: String, receivedAt: Long): AppointmentParseResult {
        val baseDate = Instant.ofEpochMilli(receivedAt).atZone(zoneId).toLocalDate()
        val date = parseDate(rawText, baseDate)
        val timeParse = parseTime(rawText)
        val location = parseLocation(rawText)
        val startAt = if (date != null && timeParse.time != null) {
            LocalDateTime.of(date, timeParse.time).atZone(zoneId).toInstant().toEpochMilli()
        } else {
            null
        }
        val confidence = listOfNotNull(date, timeParse.time, location).size / 3f
        return AppointmentParseResult(
            title = parseTitle(rawText),
            startAt = startAt,
            endAt = null,
            location = location,
            confidence = confidence,
            timeConfidence = timeParse.confidence,
        )
    }

    private fun AppointmentParseResult.mergeFallback(fallback: AppointmentParseResult): AppointmentParseResult =
        copy(
            title = title.ifBlank { fallback.title },
            startAt = startAt ?: fallback.startAt,
            endAt = endAt ?: fallback.endAt,
            location = location ?: fallback.location,
            confidence = confidence.coerceAtLeast(fallback.confidence),
        )

    private fun parseDate(text: String, baseDate: LocalDate): LocalDate? {
        if ("오늘" in text) return baseDate
        if ("내일" in text) return baseDate.plusDays(1)
        if ("모레" in text) return baseDate.plusDays(2)

        Regex("(\\d{1,2})월\\s*(\\d{1,2})일").find(text)?.let {
            val month = it.groupValues[1].toInt()
            val day = it.groupValues[2].toInt()
            val candidate = LocalDate.of(baseDate.year, month, day)
            return if (candidate.isBefore(baseDate)) candidate.plusYears(1) else candidate
        }

        val weekOffset = when {
            "다음 주" in text || "담주" in text -> 1L
            "이번 주" in text -> 0L
            else -> null
        }
        val weekday = weekdayMap.entries.firstOrNull { it.key in text }?.value
        if (weekday != null) {
            val start = if (weekOffset == null) {
                baseDate
            } else {
                baseDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusWeeks(weekOffset)
            }
            val adjusted = start.with(TemporalAdjusters.nextOrSame(weekday))
            return if (weekOffset == null && adjusted.isBefore(baseDate)) adjusted.plusWeeks(1) else adjusted
        }
        return null
    }

    private fun parseTime(text: String): TimeParse {
        val colonTime = Regex("(오전|오후|저녁|밤)?\\s*(\\d{1,2})[:：](\\d{2})").find(text)
        if (colonTime != null) {
            val meridiem = colonTime.groupValues[1]
            var hour = colonTime.groupValues[2].toInt()
            val minute = colonTime.groupValues[3].toInt()
            if ((meridiem == "오후" || meridiem == "저녁" || meridiem == "밤") && hour < 12) hour += 12
            if (meridiem == "오전" && hour == 12) hour = 0
            return TimeParse(LocalTime.of(hour.coerceIn(0, 23), minute.coerceIn(0, 59)), TimeConfidence.High)
        }

        val explicit = Regex("(오전|오후|아침|점심|저녁|밤)?\\s*(\\d{1,2})시(?:\\s*(\\d{1,2})분|\\s*반)?").find(text)
        if (explicit != null) {
            val meridiem = explicit.groupValues[1]
            var hour = explicit.groupValues[2].toInt()
            val minute = when {
                explicit.groupValues[3].isNotBlank() -> explicit.groupValues[3].toInt()
                explicit.value.contains("반") -> 30
                else -> 0
            }
            if ((meridiem == "오후" || meridiem == "저녁" || meridiem == "밤") && hour < 12) hour += 12
            if (meridiem == "오전" && hour == 12) hour = 0
            if (meridiem.isBlank() && hour in 1..7) {
                return TimeParse(LocalTime.of(hour, minute), TimeConfidence.Medium)
            }
            return TimeParse(LocalTime.of(hour.coerceIn(0, 23), minute.coerceIn(0, 59)), TimeConfidence.High)
        }

        return when {
            "아침" in text -> TimeParse(LocalTime.of(8, 0), TimeConfidence.Low)
            "점심" in text -> TimeParse(LocalTime.of(12, 0), TimeConfidence.Low)
            "저녁" in text -> TimeParse(LocalTime.of(19, 0), TimeConfidence.Low)
            else -> TimeParse(null, TimeConfidence.Low)
        }
    }

    private fun parseLocation(text: String): String? {
        Regex("(?:장소|위치)[:：]?\\s*([^\\n,]+)").find(text)?.let {
            return it.groupValues[1].trim().takeIf { value -> value.isNotBlank() }?.take(40)
        }
        val cleaned = text
            .replace(Regex("(오늘|내일|모레|이번 주|다음 주|담주|\\d{1,2}월\\s*\\d{1,2}일)"), "")
            .replace(Regex(weekdayMap.keys.joinToString("|") { Regex.escape(it) }), "")
            .replace(Regex("(오전|오후|아침|점심|저녁|밤)?\\s*\\d{1,2}[:：]\\d{2}"), "")
            .replace(Regex("(오전|오후|아침|점심|저녁|밤)?\\s*\\d{1,2}시(?:\\s*\\d{1,2}분|\\s*반)?"), "")
            .trim()

        val at = Regex("([가-힣A-Za-z0-9\\s]+?)(?:에서|로)\\s*(?:.+?(?:만나|보기|회의|미팅|약속))").find(cleaned)
        if (at != null) {
            return at.groupValues[1].trim().takeIf { it.isNotBlank() }?.take(40)
        }

        Regex("([가-힣A-Za-z0-9\\s]+?)\\s*(?:약속|회의|미팅)$").find(cleaned)?.let {
            return it.groupValues[1].trim().takeIf { value -> value.isNotBlank() }?.take(40)
        }

        return Regex("([가-힣A-Za-z0-9\\s]*(?:병원|카페|식당|홍대|강남)[가-힣A-Za-z0-9\\s]*)")
            .find(cleaned)
            ?.groupValues
            ?.get(1)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.take(40)
    }

    private fun parseTitle(text: String): String {
        val firstLine = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        return firstLine
            .replace(Regex("(오늘|내일|모레|이번 주|다음 주|담주|\\d{1,2}월\\s*\\d{1,2}일)"), "")
            .replace(Regex(weekdayMap.keys.joinToString("|") { Regex.escape(it) }), "")
            .replace(Regex("(오전|오후|아침|점심|저녁|밤)?\\s*\\d{1,2}[:：]\\d{2}"), "")
            .replace(Regex("(오전|오후|아침|점심|저녁|밤)?\\s*\\d{1,2}시(?:\\s*\\d{1,2}분|\\s*반)?"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifBlank { "약속" }
            .take(60)
    }

    private data class TimeParse(val time: LocalTime?, val confidence: TimeConfidence)
}
