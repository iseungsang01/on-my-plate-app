package com.lss.onmyplate.nativeplanner.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.lss.onmyplate.nativeplanner.data.db.AppDatabase
import com.lss.onmyplate.nativeplanner.domain.model.CandidateStatus
import com.lss.onmyplate.nativeplanner.domain.model.ScheduleStatus
import com.lss.onmyplate.nativeplanner.domain.parser.KoreanAppointmentParser
import kotlinx.coroutines.runBlocking
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlannerRepositoryTest {
    private lateinit var db: AppDatabase
    private lateinit var repository: PlannerRepository
    private val zoneId = ZoneId.of("Asia/Seoul")

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).build()
        repository = PlannerRepository(db, KoreanAppointmentParser())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun createCandidateStoresParsedCandidateWithSourceApp() = runBlocking {
        val receivedAt = 1_779_292_800_000L

        val candidate = repository.createCandidate(
            rawText = "team sync",
            sourceApp = "com.example.chat",
            receivedAt = receivedAt,
        )

        val stored = db.appointmentCandidateDao().get(candidate.id)
        assertNotNull(stored)
        assertEquals("team sync", stored?.rawText)
        assertEquals("com.example.chat", stored?.sourceApp)
        assertEquals("", stored?.extractedTitle)
        assertEquals(CandidateStatus.Pending.dbValue, stored?.status)
        assertEquals(receivedAt, stored?.createdAt)
    }

    @Test
    fun saveFromCandidateRequiresUserTitle() = runBlocking {
        val candidate = repository.createCandidate(
            rawText = "내일 오후 2시 카페",
            sourceApp = "com.example.chat",
            receivedAt = 1_779_292_800_000L,
        )

        val result = repository.saveFromCandidate(
            candidateId = candidate.id,
            selectedStatus = ScheduleStatus.Confirmed,
            titleOverride = null,
        )

        assertEquals(SaveResult.TitleRequired, result)
        assertEquals(0, db.scheduleDao().getAll().size)
        assertEquals(CandidateStatus.Pending.dbValue, db.appointmentCandidateDao().get(candidate.id)?.status)
    }

    @Test
    fun saveFromCandidateCreatesScheduleAndMarksCandidateConfirmed() = runBlocking {
        val candidate = repository.createCandidate(
            rawText = "team sync",
            sourceApp = "com.example.chat",
            receivedAt = 1_779_292_800_000L,
        )
        val startAt = 1_779_318_000_000L
        val endAt = startAt + 30 * 60 * 1_000L
        repository.updateCandidate(
            candidateId = candidate.id,
            title = "Team Sync",
            startAt = startAt,
            endAt = endAt,
            location = "Office",
        )

        val result = repository.saveFromCandidate(
            candidateId = candidate.id,
            selectedStatus = ScheduleStatus.Confirmed,
            titleOverride = null,
        )

        assertTrue(result is SaveResult.Saved)
        val schedules = db.scheduleDao().getAll()
        assertEquals(1, schedules.size)
        assertEquals("Team Sync", schedules.single().title)
        assertEquals(startAt, schedules.single().startAt)
        assertEquals(endAt, schedules.single().endAt)
        assertEquals("Office", schedules.single().location)
        assertEquals("team sync", schedules.single().sourceText)
        assertEquals("com.example.chat", schedules.single().sourceApp)
        assertEquals(CandidateStatus.Confirmed.dbValue, db.appointmentCandidateDao().get(candidate.id)?.status)
    }

    @Test
    fun saveFromCandidateReturnsConflictWithoutChangingCandidateStatus() = runBlocking {
        val first = repository.createCandidate("first", "com.example.chat", receivedAt = 1L)
        val startAt = 10_000L
        repository.updateCandidate(first.id, "First", startAt, startAt + 60_000L, null)
        assertTrue(repository.saveFromCandidate(first.id, ScheduleStatus.Confirmed, null) is SaveResult.Saved)

        val second = repository.createCandidate("second", "com.example.chat", receivedAt = 2L)
        repository.updateCandidate(second.id, "Second", startAt + 30_000L, startAt + 90_000L, null)

        val result = repository.saveFromCandidate(second.id, ScheduleStatus.Confirmed, null)

        assertTrue(result is SaveResult.Conflict)
        assertEquals(1, db.scheduleDao().getAll().size)
        assertEquals(CandidateStatus.Pending.dbValue, db.appointmentCandidateDao().get(second.id)?.status)
    }

    @Test
    fun weeklyRecurrenceExpandsAndCanSkipOneOccurrence() = runBlocking {
        val candidate = repository.createCandidate("weekly class", "internal", receivedAt = 1L)
        val firstStart = epochMillis(2026, 5, 5, 10, 0)
        val firstEnd = epochMillis(2026, 5, 5, 11, 0)
        repository.updateCandidate(candidate.id, "Class", firstStart, firstEnd, "Room")

        val result = repository.saveFromCandidate(
            candidateId = candidate.id,
            selectedStatus = ScheduleStatus.Confirmed,
            titleOverride = null,
            recurrenceInput = RecurrenceInput.Weekly(),
        )

        assertTrue(result is SaveResult.Saved)
        val schedule = (result as SaveResult.Saved).schedule
        val rangeStart = epochMillis(2026, 5, 4, 0, 0)
        val rangeEnd = epochMillis(2026, 5, 18, 0, 0)
        val beforeSkip = repository.getExpandedSchedules(rangeStart, rangeEnd)
        assertEquals(listOf(firstStart, epochMillis(2026, 5, 12, 10, 0)), beforeSkip.map { it.occurrenceStartAt })
        assertTrue(beforeSkip.all { it.isRecurring })

        repository.skipRecurringOccurrence(schedule.id, epochMillis(2026, 5, 12, 10, 0))

        val afterSkip = repository.getExpandedSchedules(rangeStart, rangeEnd)
        assertEquals(listOf(firstStart), afterSkip.map { it.occurrenceStartAt })
    }

    private fun epochMillis(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        LocalDateTime.of(year, month, day, hour, minute).atZone(zoneId).toInstant().toEpochMilli()
}
