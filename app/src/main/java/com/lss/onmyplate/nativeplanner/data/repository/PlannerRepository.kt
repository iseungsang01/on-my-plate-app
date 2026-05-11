package com.lss.onmyplate.nativeplanner.data.repository

import androidx.room.withTransaction
import com.lss.onmyplate.nativeplanner.data.db.AppDatabase
import com.lss.onmyplate.nativeplanner.data.entity.AppointmentCandidateEntity
import com.lss.onmyplate.nativeplanner.data.entity.ScheduleEntity
import com.lss.onmyplate.nativeplanner.data.entity.ScheduleRecurrenceExceptionEntity
import com.lss.onmyplate.nativeplanner.data.entity.ScheduleRecurrenceRuleEntity
import com.lss.onmyplate.nativeplanner.domain.conflict.ConflictDetector
import com.lss.onmyplate.nativeplanner.domain.model.CandidateStatus
import com.lss.onmyplate.nativeplanner.domain.model.ScheduleStatus
import com.lss.onmyplate.nativeplanner.domain.parser.KoreanAppointmentParser
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.Flow
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID

class PlannerRepository(
    private val db: AppDatabase,
    private val parser: KoreanAppointmentParser,
) {
    private val schedules = db.scheduleDao()
    private val candidates = db.appointmentCandidateDao()
    private val recurrence = db.scheduleRecurrenceDao()

    fun observeSchedules(): Flow<List<ScheduleEntity>> = schedules.observeAll()
    fun observeExpandedSchedules(rangeStart: Long, rangeEnd: Long): Flow<List<ScheduleOccurrence>> =
        combine(schedules.observeAll(), recurrence.observeRules(), recurrence.observeExceptions()) { saved, rules, exceptions ->
            expandScheduleOccurrences(saved, rules, exceptions, rangeStart, rangeEnd)
        }

    fun observeSchedule(id: String): Flow<ScheduleEntity?> = schedules.observe(id)
    fun observePendingCandidates(): Flow<List<AppointmentCandidateEntity>> = candidates.observePending()
    fun observeCandidate(id: String): Flow<AppointmentCandidateEntity?> = candidates.observe(id)
    suspend fun getCandidate(id: String): AppointmentCandidateEntity? = candidates.get(id)
    suspend fun getSchedules(): List<ScheduleEntity> = schedules.getAll()
    suspend fun getSchedule(id: String): ScheduleEntity? = schedules.get(id)
    suspend fun getRecurrenceRule(scheduleId: String): ScheduleRecurrenceRuleEntity? = recurrence.getRule(scheduleId)
    suspend fun getRecurrenceExceptions(scheduleId: String): List<ScheduleRecurrenceExceptionEntity> =
        recurrence.getExceptions(scheduleId)

    suspend fun getExpandedSchedules(rangeStart: Long, rangeEnd: Long): List<ScheduleOccurrence> =
        expandScheduleOccurrences(schedules.getAll(), recurrence.getRules(), recurrence.getExceptions(), rangeStart, rangeEnd)

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
        recurrenceInput: RecurrenceInput = RecurrenceInput.None,
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
            val schedule = insertSchedule(titledCandidate, selectedStatus, title, forceStartAt = titledCandidate.createdAt)
            candidates.update(titledCandidate.copy(status = CandidateStatus.Confirmed.dbValue))
            return@withTransaction SaveResult.SavedAsUncertain(schedule)
        }

        val startAt = titledCandidate.extractedStartAt
        val endAt = ConflictDetector.newEnd(startAt, titledCandidate.extractedEndAt)
        val conflicts = schedules.findConflicts(startAt, endAt)
        if (conflicts.isNotEmpty() && !force) {
            return@withTransaction SaveResult.Conflict(titledCandidate, conflicts)
        }

        val schedule = insertSchedule(titledCandidate, selectedStatus, title)
        saveRecurrence(schedule, recurrenceInput)
        candidates.update(titledCandidate.copy(status = CandidateStatus.Confirmed.dbValue))
        SaveResult.Saved(schedule)
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

    suspend fun updateSchedule(
        scheduleId: String,
        title: String,
        startAt: Long?,
        endAt: Long?,
        location: String?,
        memo: String?,
        status: ScheduleStatus,
        recurrenceInput: RecurrenceInput? = null,
    ): Boolean {
        val schedule = schedules.get(scheduleId) ?: return false
        val cleanTitle = title.trim().takeIf { it.isNotBlank() } ?: return false
        db.withTransaction {
            val updatedSchedule = schedule.copy(
                title = cleanTitle,
                startAt = startAt ?: schedule.startAt,
                endAt = endAt,
                location = location?.ifBlank { null },
                memo = memo?.ifBlank { null },
                status = status.dbValue,
                updatedAt = System.currentTimeMillis(),
            )
            schedules.update(updatedSchedule)
            recurrenceInput?.let { saveRecurrence(updatedSchedule, it) }
        }
        return true
    }

    suspend fun skipRecurringOccurrence(scheduleId: String, occurrenceStartAt: Long) {
        val now = System.currentTimeMillis()
        recurrence.upsertException(
            ScheduleRecurrenceExceptionEntity(
                scheduleId = scheduleId,
                occurrenceStartAt = occurrenceStartAt,
                action = RecurrenceExceptionActionSkip,
                createdAt = now,
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
    ): ScheduleEntity {
        val now = System.currentTimeMillis()
        val startAt = forceStartAt ?: candidate.extractedStartAt ?: now
        val scheduleStatus = if (selectedStatus == ScheduleStatus.Uncertain || candidate.extractedStartAt == null) {
            ScheduleStatus.Uncertain
        } else {
            selectedStatus
        }
        val schedule = ScheduleEntity(
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
            )
        schedules.insert(schedule)
        return schedule
    }

    private suspend fun saveRecurrence(schedule: ScheduleEntity, recurrenceInput: RecurrenceInput) {
        when (recurrenceInput) {
            RecurrenceInput.None -> recurrence.deleteRule(schedule.id)
            is RecurrenceInput.Daily -> {
                val now = System.currentTimeMillis()
                recurrence.upsertRule(
                    ScheduleRecurrenceRuleEntity(
                        scheduleId = schedule.id,
                        frequency = RecurrenceFrequencyDaily,
                        interval = recurrenceInput.intervalDays.coerceAtLeast(1),
                        dayOfWeek = null,
                        dayOfMonth = null,
                        untilAt = recurrenceInput.untilAt,
                        count = recurrenceInput.count,
                        createdAt = recurrence.getRule(schedule.id)?.createdAt ?: now,
                        updatedAt = now,
                    ),
                )
            }
            is RecurrenceInput.Weekly -> {
                val now = System.currentTimeMillis()
                val dayOfWeek = Instant.ofEpochMilli(schedule.startAt).atZone(scheduleZone).dayOfWeek.value
                recurrence.upsertRule(
                    ScheduleRecurrenceRuleEntity(
                        scheduleId = schedule.id,
                        frequency = RecurrenceFrequencyWeekly,
                        interval = recurrenceInput.intervalWeeks.coerceAtLeast(1),
                        dayOfWeek = dayOfWeek,
                        dayOfMonth = null,
                        untilAt = recurrenceInput.untilAt,
                        count = recurrenceInput.count,
                        createdAt = recurrence.getRule(schedule.id)?.createdAt ?: now,
                        updatedAt = now,
                    ),
                )
            }
            is RecurrenceInput.Monthly -> {
                val now = System.currentTimeMillis()
                val dayOfMonth = Instant.ofEpochMilli(schedule.startAt).atZone(scheduleZone).dayOfMonth
                recurrence.upsertRule(
                    ScheduleRecurrenceRuleEntity(
                        scheduleId = schedule.id,
                        frequency = RecurrenceFrequencyMonthly,
                        interval = recurrenceInput.intervalMonths.coerceAtLeast(1),
                        dayOfWeek = null,
                        dayOfMonth = dayOfMonth,
                        untilAt = recurrenceInput.untilAt,
                        count = recurrenceInput.count,
                        createdAt = recurrence.getRule(schedule.id)?.createdAt ?: now,
                        updatedAt = now,
                    ),
                )
            }
        }
    }

    private fun expandScheduleOccurrences(
        savedSchedules: List<ScheduleEntity>,
        rules: List<ScheduleRecurrenceRuleEntity>,
        exceptions: List<ScheduleRecurrenceExceptionEntity>,
        rangeStart: Long,
        rangeEnd: Long,
    ): List<ScheduleOccurrence> {
        val rulesByScheduleId = rules.associateBy { it.scheduleId }
        val skipped = exceptions
            .filter { it.action == RecurrenceExceptionActionSkip }
            .map { it.scheduleId to it.occurrenceStartAt }
            .toSet()

        return savedSchedules.flatMap { schedule ->
            val rule = rulesByScheduleId[schedule.id]
            if (rule == null) {
                if (schedule.startAt in rangeStart until rangeEnd) {
                    listOf(ScheduleOccurrence(schedule, schedule.id, schedule.startAt, isRecurring = false))
                } else {
                    emptyList()
                }
            } else {
                expandRecurringSchedule(schedule, rule, skipped, rangeStart, rangeEnd)
            }
        }.sortedWith(compareBy<ScheduleOccurrence> { it.occurrenceStartAt }.thenBy { it.schedule.title })
    }

    private fun expandRecurringSchedule(
        schedule: ScheduleEntity,
        rule: ScheduleRecurrenceRuleEntity,
        skipped: Set<Pair<String, Long>>,
        rangeStart: Long,
        rangeEnd: Long,
    ): List<ScheduleOccurrence> {
        if (rule.interval < 1) return emptyList()
        val firstStart = Instant.ofEpochMilli(schedule.startAt).atZone(scheduleZone)
        var occurrenceIndex = estimateOccurrenceIndex(firstStart.toLocalDateTime(), rangeStart, rule)
        var occurrenceStart = occurrenceStartAt(firstStart.toLocalDateTime(), rule, occurrenceIndex)
        while (occurrenceStart.toInstant().toEpochMilli() < rangeStart) {
            occurrenceIndex += 1
            occurrenceStart = occurrenceStartAt(firstStart.toLocalDateTime(), rule, occurrenceIndex)
        }

        val occurrences = mutableListOf<ScheduleOccurrence>()
        while (occurrenceStart.toInstant().toEpochMilli() < rangeEnd) {
            val occurrenceStartAt = occurrenceStart.toInstant().toEpochMilli()
            if (rule.count != null && occurrenceIndex >= rule.count.toLong()) break
            if (rule.untilAt != null && occurrenceStartAt > rule.untilAt) break
            if (matchesRecurrenceAnchor(occurrenceStart.toLocalDateTime(), rule) && (schedule.id to occurrenceStartAt) !in skipped) {
                val delta = occurrenceStartAt - schedule.startAt
                occurrences += ScheduleOccurrence(
                    schedule = schedule.copy(
                        startAt = occurrenceStartAt,
                        endAt = schedule.endAt?.plus(delta),
                    ),
                    scheduleId = schedule.id,
                    occurrenceStartAt = occurrenceStartAt,
                    isRecurring = true,
                )
            }
            occurrenceIndex += 1
            occurrenceStart = occurrenceStartAt(firstStart.toLocalDateTime(), rule, occurrenceIndex)
        }
        return occurrences
    }

    private fun estimateOccurrenceIndex(firstStart: LocalDateTime, rangeStart: Long, rule: ScheduleRecurrenceRuleEntity): Long {
        val rangeStartDateTime = Instant.ofEpochMilli(rangeStart).atZone(scheduleZone).toLocalDateTime()
        val rawDistance = when (rule.frequency) {
            RecurrenceFrequencyDaily -> ChronoUnit.DAYS.between(firstStart.toLocalDate(), rangeStartDateTime.toLocalDate())
            RecurrenceFrequencyWeekly -> ChronoUnit.WEEKS.between(firstStart.toLocalDate(), rangeStartDateTime.toLocalDate())
            RecurrenceFrequencyMonthly -> ChronoUnit.MONTHS.between(
                YearMonth.from(firstStart),
                YearMonth.from(rangeStartDateTime),
            )
            else -> return 0
        }.coerceAtLeast(0)
        return rawDistance / rule.interval
    }

    private fun occurrenceStartAt(firstStart: LocalDateTime, rule: ScheduleRecurrenceRuleEntity, occurrenceIndex: Long) =
        when (rule.frequency) {
            RecurrenceFrequencyDaily -> firstStart.plusDays(occurrenceIndex * rule.interval.toLong())
            RecurrenceFrequencyWeekly -> firstStart.plusWeeks(occurrenceIndex * rule.interval.toLong())
            RecurrenceFrequencyMonthly -> {
                val targetMonth = YearMonth.from(firstStart).plusMonths(occurrenceIndex * rule.interval.toLong())
                val targetDay = (rule.dayOfMonth ?: firstStart.dayOfMonth).coerceAtMost(targetMonth.lengthOfMonth())
                targetMonth.atDay(targetDay).atTime(firstStart.toLocalTime())
            }
            else -> firstStart
        }.atZone(scheduleZone)

    private fun matchesRecurrenceAnchor(occurrenceStart: LocalDateTime, rule: ScheduleRecurrenceRuleEntity): Boolean =
        when (rule.frequency) {
            RecurrenceFrequencyDaily -> true
            RecurrenceFrequencyWeekly -> rule.dayOfWeek?.let { occurrenceStart.dayOfWeek == DayOfWeek.of(it) } == true
            RecurrenceFrequencyMonthly -> true
            else -> false
        }

    companion object {
        private val scheduleZone: ZoneId = ZoneId.of("Asia/Seoul")
        private const val RecurrenceFrequencyDaily = "daily"
        private const val RecurrenceFrequencyWeekly = "weekly"
        private const val RecurrenceFrequencyMonthly = "monthly"
        private const val RecurrenceExceptionActionSkip = "skip"
    }
}

sealed interface SaveAttempt {
    data object MissingCandidate : SaveAttempt
    data class NeedsUncertain(val candidate: AppointmentCandidateEntity) : SaveAttempt
    data class Ready(val candidate: AppointmentCandidateEntity, val title: String) : SaveAttempt
    data class Conflict(val candidate: AppointmentCandidateEntity, val conflicts: List<ScheduleEntity>) : SaveAttempt
}

sealed interface SaveResult {
    data class Saved(val schedule: ScheduleEntity) : SaveResult
    data class SavedAsUncertain(val schedule: ScheduleEntity) : SaveResult
    data object AlreadyHandled : SaveResult
    data object MissingCandidate : SaveResult
    data object TitleRequired : SaveResult
    data class Conflict(val candidate: AppointmentCandidateEntity, val conflicts: List<ScheduleEntity>) : SaveResult
}
