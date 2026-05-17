package com.lss.onmyplate.nativeplanner.data.repository

import com.lss.onmyplate.nativeplanner.domain.model.AppointmentParseOutcome
import com.lss.onmyplate.nativeplanner.domain.model.AppointmentParseResult
import com.lss.onmyplate.nativeplanner.domain.model.AppointmentParseSource
import com.lss.onmyplate.nativeplanner.domain.model.AppointmentParseSourceValues
import com.lss.onmyplate.nativeplanner.domain.model.CandidateStatus
import com.lss.onmyplate.nativeplanner.domain.model.ScheduleStatus
import com.lss.onmyplate.nativeplanner.domain.model.TimeConfidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CandidateFromParseOutcomeTest {
    private val receivedAt = 1_778_000_000_000L

    @Test
    fun nonFallbackOutcomeKeepsParsedFieldsAndBlankTitle() {
        val candidate = candidateFromParseOutcome(
            id = "candidate-1",
            rawText = "raw appointment",
            sourceApp = "internal",
            receivedAt = receivedAt,
            parseOutcome = outcome(
                source = AppointmentParseSource.LlmSuccess,
                startAt = receivedAt + 1_000L,
                endAt = receivedAt + 3_601_000L,
                location = "Gangnam",
            ),
        )

        assertEquals("", candidate.extractedTitle)
        assertEquals(receivedAt + 1_000L, candidate.extractedStartAt)
        assertEquals(receivedAt + 3_601_000L, candidate.extractedEndAt)
        assertEquals("Gangnam", candidate.extractedLocation)
        assertEquals(AppointmentParseSourceValues.LlmSuccess, candidate.parseSource)
        assertEquals(CandidateStatus.Pending.dbValue, candidate.status)
    }

    @Test
    fun localFallbackKeepsParsedLocationAndParseSource() {
        val candidate = candidateFromParseOutcome(
            id = "candidate-2",
            rawText = "raw appointment",
            sourceApp = "internal",
            receivedAt = receivedAt,
            parseOutcome = outcome(
                source = AppointmentParseSource.LocalFallback,
                startAt = receivedAt + 1_000L,
                endAt = receivedAt + 3_601_000L,
                location = "Gangnam",
            ),
        )

        assertEquals("Gangnam", candidate.extractedLocation)
        assertEquals(AppointmentParseSourceValues.LocalFallback, candidate.parseSource)
    }

    @Test
    fun parserErrorFallbackHasNoMisleadingCurrentTimeOrLocationPlaceholder() {
        val candidate = candidateFromParseOutcome(
            id = "candidate-3",
            rawText = "raw appointment",
            sourceApp = "internal",
            receivedAt = receivedAt,
            parseOutcome = parserErrorOutcome(),
        )

        assertEquals("", candidate.extractedTitle)
        assertNull(candidate.extractedStartAt)
        assertNull(candidate.extractedEndAt)
        assertNull(candidate.extractedLocation)
        assertEquals(AppointmentParseSourceValues.ParserError, candidate.parseSource)
        assertEquals(0f, candidate.confidence)
        assertEquals(TimeConfidence.Low.dbValue, candidate.timeConfidence)
    }

    @Test
    fun uncertainSaveTitleUsesSafeFallbackWithoutChangingCandidateTitle() {
        val candidate = candidateFromParseOutcome(
            id = "candidate-4",
            rawText = "  fallback 5/17 1400-1600 강남\nextra",
            sourceApp = "internal",
            receivedAt = receivedAt,
            parseOutcome = outcome(
                source = AppointmentParseSource.LocalFallback,
                startAt = receivedAt + 1_000L,
                endAt = receivedAt + 3_601_000L,
                location = "강남",
            ),
        )

        val title = candidateScheduleTitle(candidate, ScheduleStatus.Uncertain, titleOverride = null)

        assertEquals("fallback 5/17 1400-1600 강남", title)
        assertEquals("", candidate.extractedTitle)

        val schedule = scheduleFromCandidateSave(
            id = "schedule-1",
            candidate = candidate,
            title = title!!,
            scheduleStatus = ScheduleStatus.Uncertain,
            memoOverride = "상대가 아직 장소만 보냄",
            now = receivedAt + 9_000L,
        )
        assertEquals(ScheduleStatus.Uncertain.dbValue, schedule.status)
        assertEquals("상대가 아직 장소만 보냄", schedule.memo)
        assertEquals("강남", schedule.location)
        assertEquals("", candidate.extractedTitle)
    }

    @Test
    fun uncertainSaveTitlePrefersExistingCandidateTitleAndConfirmedStillRequiresTitle() {
        val candidate = candidateFromParseOutcome(
            id = "candidate-5",
            rawText = "raw appointment",
            sourceApp = "internal",
            receivedAt = receivedAt,
            parseOutcome = outcome(
                source = AppointmentParseSource.LlmSuccess,
                startAt = receivedAt + 1_000L,
                endAt = receivedAt + 3_601_000L,
                location = "Gangnam",
            ),
        )

        assertEquals("미정 일정", candidateScheduleTitle(candidate.copy(rawText = "   "), ScheduleStatus.Uncertain, null))
        assertEquals("기존 제목", candidateScheduleTitle(candidate.copy(extractedTitle = "기존 제목"), ScheduleStatus.Uncertain, null))
        assertNull(candidateScheduleTitle(candidate, ScheduleStatus.Confirmed, null))
    }

    private fun outcome(
        source: AppointmentParseSource,
        startAt: Long?,
        endAt: Long?,
        location: String?,
    ): AppointmentParseOutcome = AppointmentParseOutcome(
        result = AppointmentParseResult(
            title = "ignored title",
            startAt = startAt,
            endAt = endAt,
            location = location,
            confidence = 0.9f,
            timeConfidence = TimeConfidence.High,
        ),
        source = source,
    )
}
