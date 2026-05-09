package com.lss.onmyplate.nativeplanner.data.repository

import androidx.room.withTransaction
import com.lss.onmyplate.nativeplanner.data.db.AppDatabase
import com.lss.onmyplate.nativeplanner.data.entity.AppointmentCandidateEntity
import com.lss.onmyplate.nativeplanner.data.entity.ScheduleEntity
import com.lss.onmyplate.nativeplanner.domain.conflict.ConflictDetector
import com.lss.onmyplate.nativeplanner.domain.model.CandidateStatus
import com.lss.onmyplate.nativeplanner.domain.model.ScheduleStatus
import com.lss.onmyplate.nativeplanner.domain.parser.KoreanAppointmentParser
import kotlinx.coroutines.flow.Flow
import java.util.UUID

class PlannerRepository(
    private val db: AppDatabase,
    private val parser: KoreanAppointmentParser,
) {
    private val schedules = db.scheduleDao()
    private val candidates = db.appointmentCandidateDao()

    fun observeSchedules(): Flow<List<ScheduleEntity>> = schedules.observeAll()
    fun observePendingCandidates(): Flow<List<AppointmentCandidateEntity>> = candidates.observePending()
    fun observeCandidate(id: String): Flow<AppointmentCandidateEntity?> = candidates.observe(id)
    suspend fun getCandidate(id: String): AppointmentCandidateEntity? = candidates.get(id)
    suspend fun getSchedules(): List<ScheduleEntity> = schedules.getAll()

    suspend fun createCandidate(rawText: String, sourceApp: String?, receivedAt: Long): AppointmentCandidateEntity {
        val parsed = parser.parse(rawText, receivedAt)
        val entity = AppointmentCandidateEntity(
            id = UUID.randomUUID().toString(),
            rawText = rawText,
            sourceApp = sourceApp?.takeIf { it.isNotBlank() },
            extractedTitle = parsed.title,
            extractedStartAt = parsed.startAt,
            extractedEndAt = parsed.endAt,
            extractedLocation = parsed.location,
            confidence = parsed.confidence,
            timeConfidence = parsed.timeConfidence.dbValue,
            status = CandidateStatus.Pending.dbValue,
            createdAt = receivedAt,
        )
        candidates.insert(entity)
        return entity
    }

    suspend fun conflictsForCandidate(candidateId: String, titleOverride: String? = null): SaveAttempt {
        val candidate = candidates.get(candidateId) ?: return SaveAttempt.MissingCandidate
        val startAt = candidate.extractedStartAt ?: return SaveAttempt.NeedsUncertain(candidate)
        val endAt = ConflictDetector.newEnd(startAt, candidate.extractedEndAt)
        val conflicts = schedules.findConflicts(startAt, endAt)
        return if (conflicts.isEmpty()) {
            SaveAttempt.Ready(candidate, titleOverride?.trim()?.takeIf { it.isNotBlank() } ?: candidate.extractedTitle)
        } else {
            SaveAttempt.Conflict(candidate, conflicts)
        }
    }

    suspend fun saveFromCandidate(
        candidateId: String,
        selectedStatus: ScheduleStatus,
        titleOverride: String?,
        force: Boolean = false,
    ): SaveResult = db.withTransaction {
        val candidate = candidates.get(candidateId) ?: return@withTransaction SaveResult.MissingCandidate
        if (candidate.status != CandidateStatus.Pending.dbValue) {
            return@withTransaction SaveResult.AlreadyHandled
        }
        val title = titleOverride?.trim()?.takeIf { it.isNotBlank() }
            ?: candidate.extractedTitle.takeIf { it.isNotBlank() }
            ?: return@withTransaction SaveResult.TitleRequired
        val titledCandidate = if (candidate.extractedTitle == title) {
            candidate
        } else {
            candidate.copy(extractedTitle = title).also { candidates.update(it) }
        }

        if (selectedStatus == ScheduleStatus.Uncertain || titledCandidate.extractedStartAt == null) {
            insertSchedule(titledCandidate, selectedStatus, title, forceStartAt = titledCandidate.createdAt)
            candidates.update(titledCandidate.copy(status = CandidateStatus.Confirmed.dbValue))
            return@withTransaction SaveResult.SavedAsUncertain
        }

        val startAt = titledCandidate.extractedStartAt
        val endAt = ConflictDetector.newEnd(startAt, titledCandidate.extractedEndAt)
        val conflicts = schedules.findConflicts(startAt, endAt)
        if (conflicts.isNotEmpty() && !force) {
            return@withTransaction SaveResult.Conflict(titledCandidate, conflicts)
        }

        insertSchedule(titledCandidate, selectedStatus, title)
        candidates.update(titledCandidate.copy(status = CandidateStatus.Confirmed.dbValue))
        SaveResult.Saved
    }

    suspend fun updateCandidate(
        candidateId: String,
        title: String,
        startAt: Long?,
        endAt: Long?,
        location: String?,
    ) {
        val candidate = candidates.get(candidateId) ?: return
        candidates.update(
            candidate.copy(
                extractedTitle = title.trim(),
                extractedStartAt = startAt,
                extractedEndAt = endAt,
                extractedLocation = location?.ifBlank { null },
            ),
        )
    }

    suspend fun discardCandidate(candidateId: String) {
        val candidate = candidates.get(candidateId) ?: return
        if (candidate.status == CandidateStatus.Pending.dbValue) {
            candidates.update(candidate.copy(status = CandidateStatus.Discarded.dbValue))
        }
    }

    private suspend fun insertSchedule(
        candidate: AppointmentCandidateEntity,
        selectedStatus: ScheduleStatus,
        titleOverride: String?,
        forceStartAt: Long? = null,
    ) {
        val now = System.currentTimeMillis()
        val startAt = forceStartAt ?: candidate.extractedStartAt ?: now
        val scheduleStatus = if (selectedStatus == ScheduleStatus.Uncertain || candidate.extractedStartAt == null) {
            ScheduleStatus.Uncertain
        } else {
            selectedStatus
        }
        schedules.insert(
            ScheduleEntity(
                id = UUID.randomUUID().toString(),
                title = titleOverride?.takeIf { it.isNotBlank() } ?: candidate.extractedTitle,
                startAt = startAt,
                endAt = candidate.extractedEndAt,
                location = candidate.extractedLocation,
                memo = null,
                status = scheduleStatus.dbValue,
                sourceText = candidate.rawText,
                sourceApp = candidate.sourceApp,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }
}

sealed interface SaveAttempt {
    data object MissingCandidate : SaveAttempt
    data class NeedsUncertain(val candidate: AppointmentCandidateEntity) : SaveAttempt
    data class Ready(val candidate: AppointmentCandidateEntity, val title: String) : SaveAttempt
    data class Conflict(val candidate: AppointmentCandidateEntity, val conflicts: List<ScheduleEntity>) : SaveAttempt
}

sealed interface SaveResult {
    data object Saved : SaveResult
    data object SavedAsUncertain : SaveResult
    data object AlreadyHandled : SaveResult
    data object MissingCandidate : SaveResult
    data object TitleRequired : SaveResult
    data class Conflict(val candidate: AppointmentCandidateEntity, val conflicts: List<ScheduleEntity>) : SaveResult
}
