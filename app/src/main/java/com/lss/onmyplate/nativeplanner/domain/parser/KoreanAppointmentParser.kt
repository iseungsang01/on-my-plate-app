package com.lss.onmyplate.nativeplanner.domain.parser

import com.lss.onmyplate.nativeplanner.domain.model.AppointmentParseOutcome
import com.lss.onmyplate.nativeplanner.domain.model.AppointmentParseResult
import com.lss.onmyplate.nativeplanner.domain.model.AppointmentParseSource
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
    private val llmParser: AppointmentLlmParser? = null,
    private val preferLlm: Boolean = false,
) {
    private companion object {
        const val LOCAL_CONFIDENCE_THRESHOLD = 0.66f
    }

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

    suspend fun parse(rawText: String, receivedAt: Long): AppointmentParseResult =
        parseWithOutcome(rawText, receivedAt).result

    suspend fun parseWithOutcome(rawText: String, receivedAt: Long): AppointmentParseOutcome {
        val localParsed = parseLocally(rawText, receivedAt)
        if (isDeterministicCompactFallback(rawText, localParsed)) {
            val local = localParsed.withAppointmentDefaults()
            return AppointmentParseOutcome(local, AppointmentParseSource.LocalOnly)
        }
        val local = localParsed.withAppointmentDefaults()
        if (preferLlm) {
            return parseWithLlm(rawText, receivedAt, local)
        }
        if (local.startAt != null && local.confidence >= LOCAL_CONFIDENCE_THRESHOLD) {
            return AppointmentParseOutcome(local, AppointmentParseSource.LocalOnly)
        }
        return parseWithLlm(rawText, receivedAt, local)
    }

    private suspend fun parseWithLlm(
        rawText: String,
        receivedAt: Long,
        local: AppointmentParseResult,
    ): AppointmentParseOutcome {
        val llm = runCatching { llmParser?.parse(rawText, receivedAt) }.getOrNull()
            ?: return AppointmentParseOutcome(local, AppointmentParseSource.LocalFallback)
        val source = if (llm.needsLocalSupplement(local)) {
            AppointmentParseSource.LlmWithLocalSupplement
        } else {
            AppointmentParseSource.LlmSuccess
        }
        return AppointmentParseOutcome(llm.mergeFallback(local).withAppointmentDefaults(), source)
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
        val endAt = if (date != null && timeParse.time != null && timeParse.endTime != null) {
            val endDate = if (timeParse.endTime.isAfter(timeParse.time)) date else date.plusDays(1)
            LocalDateTime.of(endDate, timeParse.endTime).atZone(zoneId).toInstant().toEpochMilli()
        } else {
            null
        }
        val confidence = listOfNotNull(date, timeParse.time, location).size / 3f
        return AppointmentParseResult(
            title = "",
            startAt = startAt,
            endAt = endAt,
            location = location,
            confidence = confidence,
            timeConfidence = timeParse.confidence,
        )
    }

    private fun isDeterministicCompactFallback(rawText: String, local: AppointmentParseResult): Boolean =
        rawText.trimStart().startsWith("fallback", ignoreCase = true) &&
            local.startAt != null &&
            local.endAt != null &&
            !local.location.isNullOrBlank()

    private fun AppointmentParseResult.needsLocalSupplement(fallback: AppointmentParseResult): Boolean =
        (startAt == null && fallback.startAt != null) ||
            (endAt == null && startAt == null && fallback.endAt != null) ||
            (location == null && fallback.location != null)

    private fun AppointmentParseResult.mergeFallback(fallback: AppointmentParseResult): AppointmentParseResult {
        val mergedStartAt = startAt ?: fallback.startAt
        val mergedEndAt = endAt ?: fallback.endAt.takeIf { startAt == null || startAt == fallback.startAt }
        return copy(
            title = title.ifBlank { fallback.title },
            startAt = mergedStartAt,
            endAt = mergedEndAt,
            location = location ?: fallback.location,
            confidence = confidence.coerceAtLeast(fallback.confidence),
        )
    }

    private fun AppointmentParseResult.withAppointmentDefaults(): AppointmentParseResult {
        val defaultedEndAt = startAt?.let { AppointmentTitleFormatter.defaultEnd(it, endAt) }
        val defaultedTitle = title.ifBlank { AppointmentTitleFormatter.format(startAt, defaultedEndAt, location, zoneId) }
        return copy(title = defaultedTitle, endAt = defaultedEndAt)
    }

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

        Regex("\\b(\\d{1,2})/(\\d{1,2})\\b").find(text)?.let {
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
        val normalizedText = normalizeKoreanHourNumbers(text)
        parseCompactTimeRange(normalizedText)?.let { return it }

        val colonTime = Regex("(오전|오후|저녁|밤)?\\s*(\\d{1,2})[:：](\\d{2})").find(normalizedText)
        if (colonTime != null) {
            val meridiem = colonTime.groupValues[1]
            var hour = colonTime.groupValues[2].toInt()
            val minute = colonTime.groupValues[3].toInt()
            if ((meridiem == "오후" || meridiem == "저녁" || meridiem == "밤") && hour < 12) hour += 12
            if (meridiem == "오전" && hour == 12) hour = 0
            return TimeParse(LocalTime.of(hour.coerceIn(0, 23), minute.coerceIn(0, 59)), null, TimeConfidence.High)
        }

        val explicit = Regex("(오전|오후|아침|점심|저녁|밤)?\\s*(\\d{1,2})시(?:\\s*(\\d{1,2})분|\\s*반)?").find(normalizedText)
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
                return TimeParse(LocalTime.of(hour, minute), null, TimeConfidence.Medium)
            }
            return TimeParse(LocalTime.of(hour.coerceIn(0, 23), minute.coerceIn(0, 59)), null, TimeConfidence.High)
        }

        return when {
            "아침" in text -> TimeParse(LocalTime.of(8, 0), null, TimeConfidence.Low)
            "점심" in text -> TimeParse(LocalTime.of(12, 0), null, TimeConfidence.Low)
            "저녁" in text -> TimeParse(LocalTime.of(19, 0), null, TimeConfidence.Low)
            else -> TimeParse(null, null, TimeConfidence.Low)
        }
    }

    private fun parseCompactTimeRange(text: String): TimeParse? {
        val match = Regex("\\b(\\d{1,2})(?::?(\\d{2}))\\s*[-~]\\s*(\\d{1,2})(?::?(\\d{2}))\\b").find(text)
            ?: return null
        val start = compactTime(match.groupValues[1], match.groupValues[2]) ?: return null
        val end = compactTime(match.groupValues[3], match.groupValues[4]) ?: return null
        return TimeParse(start, end, TimeConfidence.High)
    }

    private fun compactTime(hourPart: String, minutePart: String): LocalTime? {
        val hour: Int
        val minute: Int
        if (minutePart.isNotBlank()) {
            hour = hourPart.toInt()
            minute = minutePart.toInt()
        } else if (hourPart.length in 3..4) {
            hour = hourPart.dropLast(2).toInt()
            minute = hourPart.takeLast(2).toInt()
        } else {
            return null
        }
        if (hour !in 0..23 || minute !in 0..59) return null
        return LocalTime.of(hour, minute)
    }

    private fun normalizeKoreanHourNumbers(text: String): String {
        val hourWords = listOf(
            "열두" to 12,
            "열한" to 11,
            "열" to 10,
            "아홉" to 9,
            "여덟" to 8,
            "일곱" to 7,
            "여섯" to 6,
            "다섯" to 5,
            "네" to 4,
            "세" to 3,
            "두" to 2,
            "한" to 1,
        )
        return hourWords.fold(text) { current, (word, value) ->
            current.replace(Regex("${Regex.escape(word)}\\s*시"), "${value}시")
        }
    }

    private fun parseLocation(text: String): String? {
        val normalizedText = normalizeKoreanHourNumbers(text).replace(Regex("^\\s*fallback\\b", RegexOption.IGNORE_CASE), "")
        Regex("(?:장소|위치)[:：]?\\s*([^\\n,]+)").find(text)?.let {
            return it.groupValues[1].trim().takeIf { value -> value.isNotBlank() }?.take(40)
        }
        val cleaned = normalizedText
            .replace(Regex("(오늘|내일|모레|이번 주|다음 주|담주|\\d{1,2}월\\s*\\d{1,2}일|\\b\\d{1,2}/\\d{1,2}\\b)"), "")
            .replace(Regex("\\b\\d{1,2}:?\\d{2}\\s*[-~]\\s*\\d{1,2}:?\\d{2}\\b"), "")
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

    private data class TimeParse(val time: LocalTime?, val endTime: LocalTime?, val confidence: TimeConfidence)
}
