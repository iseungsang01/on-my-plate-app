package com.lss.onmyplate.nativeplanner.domain.parser

import com.lss.onmyplate.nativeplanner.domain.model.TimeConfidence
import com.lss.onmyplate.nativeplanner.domain.model.AppointmentParseResult
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
        assertEquals(epochMillis(2026, 5, 8, 14, 0), parser.parse("내일 오후 두시에 보자", receivedAt).startAt)
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
    fun doesNotParseAppointmentTitle() = runBlocking {
        val result = parser.parse("팀 회의", receivedAt)

        assertEquals("", result.title)
        assertNull(result.startAt)
        assertEquals(TimeConfidence.Low, result.timeConfidence)
    }

    @Test
    fun preferLlmUsesLlmResultForShareTextEvenWhenLocalParserSucceeds() = runBlocking {
        val llmStartAt = epochMillis(2026, 5, 8, 20, 0)
        val llmParser = AppointmentLlmParser { _, _ ->
            AppointmentParseResult(
                title = "LLM dinner",
                startAt = llmStartAt,
                endAt = null,
                location = "LLM place",
                confidence = 0.92f,
                timeConfidence = TimeConfidence.High,
            )
        }
        val parser = KoreanAppointmentParser(zoneId = zoneId, llmParser = llmParser, preferLlm = true)

        val result = parser.parse("내일 저녁 7시 강남에서 약속", receivedAt)

        assertEquals("LLM dinner", result.title)
        assertEquals(llmStartAt, result.startAt)
        assertEquals(llmStartAt + 60 * 60 * 1_000L, result.endAt)
        assertEquals("LLM place", result.location)
        assertEquals(1.0f, result.confidence)
    }

    @Test
    fun fillsDefaultTitleAndEndWhenLlmOmitsThem() = runBlocking {
        val llmStartAt = epochMillis(2026, 5, 13, 16, 0)
        val llmParser = AppointmentLlmParser { _, _ ->
            AppointmentParseResult(
                title = "",
                startAt = llmStartAt,
                endAt = null,
                location = "Gangnam",
                confidence = 0.9f,
                timeConfidence = TimeConfidence.High,
            )
        }
        val parser = KoreanAppointmentParser(zoneId = zoneId, llmParser = llmParser, preferLlm = true)

        val result = parser.parse("appointment text", receivedAt)

        assertEquals("5/13 1600-1700 Gangnam", result.title)
        assertEquals(llmStartAt + 60 * 60 * 1_000L, result.endAt)
    }

    @Test
    fun omitsLocationFromDefaultTitleWhenLocationIsMissing() = runBlocking {
        val llmStartAt = epochMillis(2026, 5, 13, 16, 0)
        val llmParser = AppointmentLlmParser { _, _ ->
            AppointmentParseResult(
                title = "",
                startAt = llmStartAt,
                endAt = epochMillis(2026, 5, 13, 18, 0),
                location = null,
                confidence = 0.9f,
                timeConfidence = TimeConfidence.High,
            )
        }
        val parser = KoreanAppointmentParser(zoneId = zoneId, llmParser = llmParser, preferLlm = true)

        val result = parser.parse("appointment text", receivedAt)

        assertEquals("5/13 1600-1800", result.title)
    }

    @Test
    fun preferLlmFallsBackToLocalParserWhenLlmReturnsNull() = runBlocking {
        val parser = KoreanAppointmentParser(
            zoneId = zoneId,
            llmParser = AppointmentLlmParser { _, _ -> null },
            preferLlm = true,
        )

        val result = parser.parse("내일 오후 2시 카페에서 만나", receivedAt)

        assertEquals(epochMillis(2026, 5, 8, 14, 0), result.startAt)
        assertEquals("카페", result.location)
    }

    private fun epochMillis(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        LocalDateTime.of(year, month, day, hour, minute).atZone(zoneId).toInstant().toEpochMilli()
}
