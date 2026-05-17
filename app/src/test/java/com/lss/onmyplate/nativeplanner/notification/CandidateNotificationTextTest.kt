package com.lss.onmyplate.nativeplanner.notification

import com.lss.onmyplate.nativeplanner.data.entity.AppointmentCandidateEntity
import com.lss.onmyplate.nativeplanner.domain.model.CandidateStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class CandidateNotificationTextTest {
    private val zoneId = ZoneId.of("Asia/Seoul")
    private val formatter = DateTimeFormatter.ofPattern("M/d HH:mm").withZone(zoneId)

    @Test
    fun detailsShowsExplicitRowsAndGuidance() {
        val candidate = candidate(
            startAt = epochMillis(2026, 5, 13, 16, 0),
            endAt = epochMillis(2026, 5, 13, 17, 0),
            location = "Gangnam",
        )

        val details = CandidateNotificationText.details(candidate, formatter)

        assertTrue(details.contains("\uC2DC\uC791 \uC2DC\uAC04 : 5/13 16:00"))
        assertTrue(details.contains("\uC885\uB8CC \uC2DC\uAC04 : 5/13 17:00"))
        assertTrue(details.contains("\uC7A5\uC18C : Gangnam"))
        assertTrue(details.contains("\uC77C\uC815 \uC81C\uBAA9\uC744 \uC785\uB825\uD558\uACE0 \uD655\uC815\uD558\uBA74 \uC2DC\uAC04\uD45C\uC5D0 \uC800\uC7A5\uB429\uB2C8\uB2E4"))
    }

    @Test
    fun detailsShowsUnknownTimesAndFallbackLocation() {
        val details = CandidateNotificationText.details(
            candidate(startAt = null, endAt = null, location = "fallback"),
            formatter,
        )

        assertTrue(details.contains("\uC2DC\uC791 \uC2DC\uAC04 : \uBBF8\uC815"))
        assertTrue(details.contains("\uC885\uB8CC \uC2DC\uAC04 : \uBBF8\uC815"))
        assertTrue(details.contains("\uC7A5\uC18C : fallback"))
    }

    @Test
    fun detailsKeepsUnknownLocationBlank() {
        val details = CandidateNotificationText.details(
            candidate(startAt = null, endAt = null, location = null),
            formatter,
        )

        assertTrue(details.contains("\uC7A5\uC18C : "))
    }

    @Test
    fun summaryUsesStartTimeRowLabel() {
        val summary = CandidateNotificationText.summary(
            candidate(startAt = epochMillis(2026, 5, 13, 16, 0), endAt = null, location = "fallback"),
            formatter,
        )

        assertEquals("\uC2DC\uC791 \uC2DC\uAC04 : 5/13 16:00 \u00B7 fallback", summary)
    }


    @Test
    fun notificationTitleInputLabelsMatchCopy() {
        assertEquals("일정 제목 입력", CANDIDATE_TITLE_INPUT_ACTION_LABEL)
        assertEquals("일정 제목", CANDIDATE_REMOTE_INPUT_LABEL)
    }

    private fun candidate(startAt: Long?, endAt: Long?, location: String?): AppointmentCandidateEntity =
        AppointmentCandidateEntity(
            id = "candidate-id",
            rawText = "raw",
            sourceApp = null,
            extractedTitle = "",
            extractedStartAt = startAt,
            extractedEndAt = endAt,
            extractedLocation = location,
            confidence = 0.9f,
            timeConfidence = "high",
            status = CandidateStatus.Pending.dbValue,
            createdAt = epochMillis(2026, 5, 7, 10, 0),
        )

    private fun epochMillis(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        LocalDateTime.of(year, month, day, hour, minute).atZone(zoneId).toInstant().toEpochMilli()
}
