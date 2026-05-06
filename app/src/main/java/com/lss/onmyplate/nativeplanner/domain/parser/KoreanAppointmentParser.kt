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

    fun parse(rawText: String, receivedAt: Long): AppointmentParseResult {
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

    private fun parseDate(text: String, baseDate: LocalDate): LocalDate? {
        if ("오늘" in text) return baseDate
        if ("내일" in text) return baseDate.plusDays(1)
        if ("모레" in text) return baseDate.plusDays(2)

        val weekOffset = when {
            "다음 주" in text || "담주" in text -> 1L
            "이번 주" in text -> 0L
            else -> null
        }
        val weekday = weekdayMap.entries.firstOrNull { it.key in text }?.value
        if (weekday != null) {
            val start = if (weekOffset == null) baseDate else baseDate.plusWeeks(weekOffset)
            val adjusted = start.with(TemporalAdjusters.nextOrSame(weekday))
            return if (weekOffset == null && adjusted.isBefore(baseDate)) adjusted.plusWeeks(1) else adjusted
        }
        return null
    }

    private fun parseTime(text: String): TimeParse {
        val explicit = Regex("(오전|오후)?\\s*(\\d{1,2})시\\s*(반)?").find(text)
        if (explicit != null) {
            val meridiem = explicit.groupValues[1]
            var hour = explicit.groupValues[2].toInt()
            val minute = if (explicit.groupValues[3].isNotBlank()) 30 else 0
            if (meridiem == "오후" && hour < 12) hour += 12
            if (meridiem == "오전" && hour == 12) hour = 0
            if (meridiem.isBlank() && hour in 1..7) {
                return TimeParse(LocalTime.of(hour, minute), TimeConfidence.Medium)
            }
            return TimeParse(LocalTime.of(hour.coerceIn(0, 23), minute), TimeConfidence.High)
        }

        return when {
            "아침" in text -> TimeParse(LocalTime.of(8, 0), TimeConfidence.Low)
            "점심" in text -> TimeParse(LocalTime.of(12, 0), TimeConfidence.Low)
            "저녁" in text -> TimeParse(LocalTime.of(19, 0), TimeConfidence.Low)
            else -> TimeParse(null, TimeConfidence.Low)
        }
    }

    private fun parseLocation(text: String): String? {
        val marker = Regex("(장소|위치)[:：]?\\s*([^\\n,]+)").find(text)
        if (marker != null) return marker.groupValues[2].trim().takeIf { it.isNotBlank() }
        val at = Regex("([가-힣A-Za-z0-9\\s]+)(에서|로)\\s*(만나|보자|약속)").find(text)
        return at?.groupValues?.get(1)?.trim()?.take(40)
    }

    private fun parseTitle(text: String): String {
        val firstLine = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        return firstLine
            .replace(Regex("(오늘|내일|모레|이번 주|다음 주|담주)"), "")
            .replace(Regex("(오전|오후)?\\s*\\d{1,2}시\\s*반?"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifBlank { "새 약속" }
            .take(60)
    }

    private data class TimeParse(val time: LocalTime?, val confidence: TimeConfidence)
}
