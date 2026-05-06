package com.lss.onmyplate.nativeplanner.domain.parser

import com.lss.onmyplate.nativeplanner.domain.model.TimeConfidence
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KoreanAppointmentParserTest {
    private val zoneId = ZoneId.of("Asia/Seoul")
    private val parser = KoreanAppointmentParser(zoneId = zoneId)
    private val receivedAt = epochMillis(2026, 5, 7, 10, 0)

    @Test
    fun parsesRelativeDatesFromReceivedAt() = runBlocking {
        assertEquals(epochMillis(2026, 5, 7, 19, 0), parser.parse("오늘 저녁 강남에서 회의", receivedAt).startAt)
        assertEquals(epochMillis(2026, 5, 8, 14, 0), parser.parse("내일 오후 2시 카페에서 만나", receivedAt).startAt)
        assertEquals(epochMillis(2026, 5, 9, 8, 0), parser.parse("모레 아침 병원", receivedAt).startAt)
    }

    @Test
    fun parsesThisWeekAndNextWeekWeekdays() = runBlocking {
        assertEquals(epochMillis(2026, 5, 4, 10, 0), parser.parse("이번 주 월요일 오전 10시 병원", receivedAt).startAt)
        assertEquals(epochMillis(2026, 5, 15, 15, 0), parser.parse("다음 주 금요일 오후 3시 회의", receivedAt).startAt)
    }

    @Test
    fun parsesMeridiemMealAndPartOfDayTimes() = runBlocking {
        assertEquals(epochMillis(2026, 5, 8, 8, 0), parser.parse("내일 아침 운동", receivedAt).startAt)
        assertEquals(epochMillis(2026, 5, 8, 12, 0), parser.parse("내일 점심 식당 약속", receivedAt).startAt)
        assertEquals(epochMillis(2026, 5, 8, 19, 0), parser.parse("내일 저녁 강남에서 약속", receivedAt).startAt)
        assertEquals(epochMillis(2026, 5, 8, 11, 30), parser.parse("내일 오전 11시 반 미팅", receivedAt).startAt)
        assertEquals(epochMillis(2026, 5, 8, 21, 0), parser.parse("내일 밤 9시 통화", receivedAt).startAt)
    }

    @Test
    fun parsesColonTimeAndHalfHour() = runBlocking {
        assertEquals(epochMillis(2026, 5, 20, 18, 30), parser.parse("5월 20일 18:30 홍대 약속", receivedAt).startAt)
        assertEquals(epochMillis(2026, 5, 8, 7, 30), parser.parse("내일 7시 반 산책", receivedAt).startAt)
    }

    @Test
    fun extractsLocation() = runBlocking {
        assertEquals("강남", parser.parse("오늘 저녁 7시 강남에서 회의", receivedAt).location)
        assertEquals("홍대", parser.parse("5월 20일 18:30 홍대 약속", receivedAt).location)
        assertEquals("서울역", parser.parse("내일 오후 2시 장소: 서울역", receivedAt).location)
    }

    @Test
    fun keepsFallbackTitleWhenDateAndTimeAreMissing() = runBlocking {
        val result = parser.parse("팀 회의", receivedAt)

        assertEquals("팀 회의", result.title)
        assertNull(result.startAt)
        assertEquals(TimeConfidence.Low, result.timeConfidence)
    }

    private fun epochMillis(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        LocalDateTime.of(year, month, day, hour, minute).atZone(zoneId).toInstant().toEpochMilli()
}
