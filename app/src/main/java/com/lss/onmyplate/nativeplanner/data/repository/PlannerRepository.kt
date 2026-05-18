package com.lss.onmyplate.nativeplanner.data.repository

import android.content.Context
import android.util.Log
import com.lss.onmyplate.nativeplanner.BuildConfig
import com.lss.onmyplate.nativeplanner.data.api.PlannerHttpClient
import com.lss.onmyplate.nativeplanner.data.entity.AppointmentCandidateEntity
import com.lss.onmyplate.nativeplanner.data.entity.ScheduleEntity
import com.lss.onmyplate.nativeplanner.data.entity.ScheduleRecurrenceExceptionEntity
import com.lss.onmyplate.nativeplanner.data.entity.ScheduleRecurrenceRuleEntity
import com.lss.onmyplate.nativeplanner.domain.conflict.ConflictDetector
import com.lss.onmyplate.nativeplanner.domain.model.AppointmentParseOutcome
import com.lss.onmyplate.nativeplanner.domain.model.AppointmentParseResult
import com.lss.onmyplate.nativeplanner.domain.model.AppointmentParseSource
import com.lss.onmyplate.nativeplanner.domain.model.CandidateStatus
import com.lss.onmyplate.nativeplanner.domain.model.ScheduleStatus
import com.lss.onmyplate.nativeplanner.domain.model.TimeConfidence
import com.lss.onmyplate.nativeplanner.domain.model.toStoredValue
import com.lss.onmyplate.nativeplanner.domain.parser.KoreanAppointmentParser
import com.lss.onmyplate.nativeplanner.widget.PlannerWidgetSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID

private data class RecentCandidateCreate(
    val rawText: String,
    val sourceApp: String?,
    val savedAt: Long,
    val candidate: AppointmentCandidateEntity,
)

data class PlannerRuntimeState(
    val loading: Boolean = false,
    val errorMessage: String? = null,
)

class PlannerRepository(
    context: Context,
    private val parser: KoreanAppointmentParser,
) {
    private val appContext = context.applicationContext
    private val createCandidateMutex = Mutex()
    private val saveCandidateMutex = Mutex()
    private val _runtimeState = MutableStateFlow(PlannerRuntimeState())
    val runtimeState: StateFlow<PlannerRuntimeState> = _runtimeState
    private var recentCandidateCreate: RecentCandidateCreate? = null
    private val sessionPrefs = appContext.getSharedPreferences(BuildConfig.PLANNER_SESSION_PREFS_NAME, Context.MODE_PRIVATE)
    private val client = PlannerApiClient(BuildConfig.PLANNER_API_BASE_URL) { clearCachedSession() }
    private val scheduleRecords = MutableStateFlow<List<ScheduleRecord>>(emptyList())
    private val pendingCandidates = MutableStateFlow<List<AppointmentCandidateEntity>>(emptyList())
    private val candidateRecords = MutableStateFlow<Map<String, AppointmentCandidateEntity>>(emptyMap())

    fun observeSchedules(): Flow<List<ScheduleEntity>> =
        scheduleRecords.map { records -> records.map { it.schedule } }.onStart { runCatching { refreshSchedules() } }

    fun observeExpandedSchedules(rangeStart: Long, rangeEnd: Long): Flow<List<ScheduleOccurrence>> =
        scheduleRecords
            .map { records -> expandScheduleOccurrences(records, rangeStart, rangeEnd) }
            .onStart { runCatching { refreshSchedules(rangeStart, rangeEnd) } }

    fun observeSchedule(id: String): Flow<ScheduleEntity?> =
        scheduleRecords.map { records -> records.firstOrNull { it.schedule.id == id }?.schedule }
            .onStart { runCatching { refreshSchedule(id) } }

    fun observePendingCandidates(): Flow<List<AppointmentCandidateEntity>> =
        pendingCandidates.onStart { runCatching { refreshPendingCandidates() } }

    fun observeCandidate(id: String): Flow<AppointmentCandidateEntity?> =
        combine(candidateRecords, pendingCandidates) { records, pending ->
            records[id] ?: pending.firstOrNull { it.id == id }
        }.onStart { runCatching { refreshCandidate(id) } }

    suspend fun getCandidate(id: String): AppointmentCandidateEntity? =
        refreshCandidate(id) ?: candidateRecords.value[id] ?: pendingCandidates.value.firstOrNull { it.id == id }

    fun clearRuntimeError() {
        _runtimeState.value = _runtimeState.value.copy(errorMessage = null)
    }

    suspend fun getSchedules(): List<ScheduleEntity> = refreshSchedules().map { it.schedule }

    suspend fun getSchedule(id: String): ScheduleEntity? = refreshSchedule(id)?.schedule

    suspend fun getRecurrenceRule(scheduleId: String): ScheduleRecurrenceRuleEntity? =
        scheduleRecords.value.firstOrNull { it.schedule.id == scheduleId }?.recurrence ?: refreshSchedule(scheduleId)?.recurrence

    suspend fun getRecurrenceExceptions(scheduleId: String): List<ScheduleRecurrenceExceptionEntity> =
        scheduleRecords.value.firstOrNull { it.schedule.id == scheduleId }?.exceptions ?: refreshSchedule(scheduleId)?.exceptions.orEmpty()

    suspend fun getExpandedSchedules(rangeStart: Long, rangeEnd: Long): List<ScheduleOccurrence> =
        expandScheduleOccurrences(refreshSchedules(rangeStart, rangeEnd), rangeStart, rangeEnd)

    suspend fun createCandidate(rawText: String, sourceApp: String?, receivedAt: Long): AppointmentCandidateEntity = createCandidateMutex.withLock {
        val cleanRawText = rawText.trim()
        val now = System.currentTimeMillis()
        recentCandidateCreate
            ?.takeIf { it.rawText == cleanRawText && it.sourceApp == sourceApp && now - it.savedAt <= DuplicateCreateWindowMillis }
            ?.let { recent ->
                Log.i(TAG, "createCandidate ignored duplicate rapid request. candidateId=${recent.candidate.id}, textLength=${cleanRawText.length}, sourceApp=$sourceApp")
                return@withLock recent.candidate
            }
        Log.i(TAG, "createCandidate started. textLength=${rawText.length}, sourceApp=$sourceApp, apiConfigured=${client.isConfigured()}, hasSession=${hasCachedSession()}")
        val parseOutcome = runCatching { parser.parseWithOutcome(rawText, receivedAt) }
            .getOrElse { error ->
                Log.w(TAG, "createCandidate parser failed; using parser-error fallback. textLength=${rawText.length}", error)
                parserErrorOutcome()
            }
        val localCandidate = candidateFromParseOutcome(
            id = UUID.randomUUID().toString(),
            rawText = rawText,
            sourceApp = sourceApp,
            receivedAt = receivedAt,
            parseOutcome = parseOutcome,
        )
        Log.i(TAG, "createCandidate built parser-backed candidate. parseSource=${parseOutcome.source}, ${localCandidate.diagnosticSummary()}")
        val saved = try {
            withContext(Dispatchers.IO) {
                val token = sessionToken()
                Log.i(
                    TAG,
                    "createCandidate sending API request. ${localCandidate.diagnosticSummary()}, tokenLength=${token.length}",
                )
                client.createCandidate(token, localCandidate)
            }
        } catch (error: Throwable) {
            Log.e(TAG, "createCandidate API save failed. ${localCandidate.diagnosticSummary()}", error)
            recordRuntimeError(error)
            throw error
        }
        Log.i(TAG, "createCandidate API save succeeded. candidateId=${saved.id}, status=${saved.status}")
        rememberCandidate(saved)
        refreshPendingCandidates()
        recentCandidateCreate = RecentCandidateCreate(cleanRawText, sourceApp, System.currentTimeMillis(), saved)
        return@withLock saved
    }

    suspend fun createScheduleFromInput(rawText: String, sourceApp: String?, receivedAt: Long): ScheduleEntity {
        Log.i(TAG, "createScheduleFromInput started. textLength=${rawText.length}, sourceApp=$sourceApp, apiConfigured=${client.isConfigured()}, hasSession=${hasCachedSession()}")
        val cleanText = rawText.trim()
        val startAt = receivedAt
        val now = System.currentTimeMillis()
        val schedule = ScheduleEntity(
            id = UUID.randomUUID().toString(),
            title = cleanText.lineSequence().firstOrNull()?.take(40)?.ifBlank { null } ?: "새 약속",
            startAt = startAt,
            endAt = startAt + ChronoUnit.HOURS.duration.toMillis(),
            location = null,
            memo = null,
            status = ScheduleStatus.Planned.dbValue,
            sourceText = cleanText,
            sourceApp = sourceApp?.takeIf { it.isNotBlank() },
            createdAt = now,
            updatedAt = now,
        )
        val savedRecord = try {
            withContext(Dispatchers.IO) {
                val token = sessionToken()
                Log.i(TAG, "createScheduleFromInput sending API request. scheduleId=${schedule.id}, titleLength=${schedule.title.length}, tokenLength=${token.length}")
                client.createSchedule(token, schedule, null, emptyList())
            }
        } catch (error: Throwable) {
            Log.e(TAG, "createScheduleFromInput API save failed. scheduleId=${schedule.id}, titleLength=${schedule.title.length}", error)
            throw error
        }
        refreshSchedules()
        refreshCurrentWeekWidgetSnapshotFromCache()
        return savedRecord.schedule
    }

    suspend fun conflictsForCandidate(candidateId: String, titleOverride: String? = null): SaveAttempt {
        val candidate = getCandidate(candidateId) ?: return SaveAttempt.MissingCandidate
        val startAt = candidate.extractedStartAt ?: return SaveAttempt.NeedsUncertain(candidate)
        val endAt = ConflictDetector.newEnd(startAt, candidate.extractedEndAt)
        val conflicts = getSchedules().filter { ConflictDetector.conflicts(startAt, endAt, it) }
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
        memoOverride: String? = null,
    ): SaveResult = saveCandidateMutex.withLock {
        val candidate = getCandidate(candidateId) ?: return@withLock SaveResult.MissingCandidate
        if (candidate.status != CandidateStatus.Pending.dbValue) return@withLock SaveResult.AlreadyHandled
        val title = candidateScheduleTitle(candidate, selectedStatus, titleOverride) ?: return@withLock SaveResult.TitleRequired
        val shouldPersistTitle = selectedStatus != ScheduleStatus.Uncertain && candidate.extractedTitle != title
        val titledCandidate = if (shouldPersistTitle) {
            updateCandidate(candidate.id, title, candidate.extractedStartAt, candidate.extractedEndAt, candidate.extractedLocation)
            getCandidate(candidate.id)?.copy(extractedTitle = title) ?: candidate.copy(extractedTitle = title)
        } else {
            candidate
        }

        val startAt = titledCandidate.extractedStartAt
        val scheduleStatus = if (selectedStatus == ScheduleStatus.Uncertain || startAt == null) ScheduleStatus.Uncertain else selectedStatus
        if (scheduleStatus != ScheduleStatus.Uncertain && startAt != null) {
            val endAt = ConflictDetector.newEnd(startAt, titledCandidate.extractedEndAt)
            val conflicts = getSchedules().filter { ConflictDetector.conflicts(startAt, endAt, it) }
            if (conflicts.isNotEmpty() && !force) return@withLock SaveResult.Conflict(titledCandidate, conflicts)
        }

        val now = System.currentTimeMillis()
        val schedule = scheduleFromCandidateSave(
            id = UUID.randomUUID().toString(),
            candidate = titledCandidate,
            title = title,
            scheduleStatus = scheduleStatus,
            memoOverride = memoOverride,
            now = now,
        )
        val savedRecord = withContext(Dispatchers.IO) {
            client.createSchedule(sessionToken(), schedule, recurrenceFor(schedule, recurrenceInput), emptyList())
        }
        withContext(Dispatchers.IO) { client.updateCandidateStatus(sessionToken(), candidateId, CandidateStatus.Confirmed.dbValue) }
        rememberCandidate(titledCandidate.copy(status = CandidateStatus.Confirmed.dbValue))
        try {
            refreshPendingCandidates()
            refreshSchedules()
            refreshCurrentWeekWidgetSnapshotFromCache()
        } catch (error: Throwable) {
            Log.w(TAG, "saveFromCandidate succeeded but post-save refresh failed. candidateId=$candidateId", error)
            clearRuntimeError()
        }
        return@withLock if (scheduleStatus == ScheduleStatus.Uncertain) SaveResult.SavedAsUncertain(savedRecord.schedule) else SaveResult.Saved(savedRecord.schedule)
    }

    suspend fun updateCandidate(candidateId: String, title: String, startAt: Long?, endAt: Long?, location: String?) {
        val updated = withContext(Dispatchers.IO) {
            client.patchCandidate(sessionToken(), candidateId, title.trim(), startAt, endAt, location?.ifBlank { null })
        }
        rememberCandidate(updated)
        refreshPendingCandidates()
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
        val current = getSchedule(scheduleId) ?: return false
        val cleanTitle = title.trim().takeIf { it.isNotBlank() } ?: return false
        val updated = current.copy(
            title = cleanTitle,
            startAt = startAt ?: current.startAt,
            endAt = endAt,
            location = location?.ifBlank { null },
            memo = memo?.ifBlank { null },
            status = status.dbValue,
            updatedAt = System.currentTimeMillis(),
        )
        val rule = recurrenceInput?.let { recurrenceFor(updated, it) } ?: getRecurrenceRule(scheduleId)
        val exceptions = getRecurrenceExceptions(scheduleId)
        withContext(Dispatchers.IO) { client.patchSchedule(sessionToken(), updated, rule, exceptions) }
        refreshSchedule(scheduleId)
        refreshSchedules()
        refreshCurrentWeekWidgetSnapshotFromCache()
        return true
    }

    suspend fun skipRecurringOccurrence(scheduleId: String, occurrenceStartAt: Long) {
        withContext(Dispatchers.IO) { client.addRecurrenceException(sessionToken(), scheduleId, occurrenceStartAt) }
        refreshSchedule(scheduleId)
        refreshSchedules()
        refreshCurrentWeekWidgetSnapshotFromCache()
    }

    suspend fun deleteSchedule(scheduleId: String) {
        withContext(Dispatchers.IO) { client.deleteSchedule(sessionToken(), scheduleId) }
        scheduleRecords.value = scheduleRecords.value.filterNot { it.schedule.id == scheduleId }
        try {
            refreshSchedules()
            refreshCurrentWeekWidgetSnapshotFromCache()
        } catch (error: Throwable) {
            Log.w(TAG, "deleteSchedule succeeded but refreshSchedules failed. scheduleId=$scheduleId", error)
        }
    }

    suspend fun discardCandidate(candidateId: String) {
        withContext(Dispatchers.IO) { client.discardCandidate(sessionToken(), candidateId) }
        candidateRecords.value = candidateRecords.value + (candidateId to (candidateRecords.value[candidateId]?.copy(status = CandidateStatus.Discarded.dbValue)
            ?: return))
        refreshPendingCandidates()
    }

    suspend fun refreshSchedules(rangeStart: Long? = null, rangeEnd: Long? = null): List<ScheduleRecord> {
        beginRuntimeLoading()
        return try {
            val records = withContext(Dispatchers.IO) { client.listSchedules(sessionToken(), rangeStart, rangeEnd) }
            scheduleRecords.value = mergeScheduleRecords(scheduleRecords.value, records)
            clearRuntimeLoading()
            records
        } catch (error: Throwable) {
            recordRuntimeError(error)
            throw error
        }
    }

    private suspend fun refreshSchedule(id: String): ScheduleRecord? {
        beginRuntimeLoading()
        return try {
            val record = withContext(Dispatchers.IO) { client.getSchedule(sessionToken(), id) }
            if (record != null) scheduleRecords.value = mergeScheduleRecords(scheduleRecords.value, listOf(record))
            clearRuntimeLoading()
            record
        } catch (error: Throwable) {
            recordRuntimeError(error)
            throw error
        }
    }

    private suspend fun refreshPendingCandidates(): List<AppointmentCandidateEntity> {
        beginRuntimeLoading()
        return try {
            val candidates = withContext(Dispatchers.IO) { client.listPendingCandidates(sessionToken()) }
            pendingCandidates.value = candidates
            candidateRecords.value = candidateRecords.value + candidates.associateBy { it.id }
            clearRuntimeLoading()
            candidates
        } catch (error: Throwable) {
            recordRuntimeError(error)
            throw error
        }
    }

    private suspend fun refreshCandidate(id: String): AppointmentCandidateEntity? {
        beginRuntimeLoading()
        return try {
            val candidate = withContext(Dispatchers.IO) { client.getCandidate(sessionToken(), id) }
            if (candidate != null) rememberCandidate(candidate)
            clearRuntimeLoading()
            candidate
        } catch (error: Throwable) {
            recordRuntimeError(error)
            throw error
        }
    }

    private fun rememberCandidate(candidate: AppointmentCandidateEntity) {
        candidateRecords.value = candidateRecords.value + (candidate.id to candidate)
    }

    private fun refreshCurrentWeekWidgetSnapshotFromCache() {
        val weekStart = java.time.LocalDate.now(scheduleZone)
            .with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val rangeStart = weekStart.atStartOfDay(scheduleZone).toInstant().toEpochMilli()
        val rangeEnd = weekStart.plusDays(7).atStartOfDay(scheduleZone).toInstant().toEpochMilli()
        PlannerWidgetSync.saveSnapshot(
            appContext,
            expandScheduleOccurrences(scheduleRecords.value, rangeStart, rangeEnd),
        )
    }

    private fun beginRuntimeLoading() {
        _runtimeState.value = _runtimeState.value.copy(loading = true)
    }

    private fun clearRuntimeLoading() {
        _runtimeState.value = PlannerRuntimeState()
    }

    private fun recordRuntimeError(error: Throwable) {
        _runtimeState.value = PlannerRuntimeState(
            loading = false,
            errorMessage = userFacingError(error),
        )
    }

    private fun userFacingError(error: Throwable): String {
        val raw = error.message.orEmpty()
        return when {
            raw.contains("Login is required", ignoreCase = true) ||
                raw.contains("로그인", ignoreCase = true) ||
                raw.contains("401") -> "로그인이 만료되었습니다. 다시 로그인해 주세요."
            raw.contains("timeout", ignoreCase = true) ||
                raw.contains("timed out", ignoreCase = true) -> "요청 시간이 초과되었습니다. 네트워크 상태를 확인해 주세요."
            raw.contains("Unable to resolve host", ignoreCase = true) ||
                raw.contains("Failed to connect", ignoreCase = true) ||
                raw.contains("Connection", ignoreCase = true) -> "서버에 연결할 수 없습니다. 네트워크를 확인해 주세요."
            raw.isNotBlank() -> raw.take(180)
            else -> "요청 처리 중 오류가 발생했습니다."
        }
    }

    private fun AppointmentCandidateEntity.diagnosticSummary(): String =
        "localCandidateId=$id, rawTextLength=${rawText.length}, hasSourceApp=${sourceApp != null}, hasTitle=${extractedTitle.isNotBlank()}, hasStart=${extractedStartAt != null}, hasEnd=${extractedEndAt != null}, hasLocation=${extractedLocation != null}, parseSource=$parseSource, confidence=$confidence, timeConfidence=$timeConfidence, status=$status, createdAt=$createdAt"

    private fun sessionToken(): String =
        sessionPrefs.getString(BuildConfig.PLANNER_SESSION_TOKEN_KEY, null)?.takeIf { it.isNotBlank() }
            ?: error("Login is required.")

    private fun hasCachedSession(): Boolean =
        sessionPrefs.getString(BuildConfig.PLANNER_SESSION_TOKEN_KEY, null)?.isNotBlank() == true

    private fun clearCachedSession() {
        sessionPrefs.edit().remove(BuildConfig.PLANNER_SESSION_TOKEN_KEY).apply()
    }

    private fun recurrenceFor(schedule: ScheduleEntity, recurrenceInput: RecurrenceInput): ScheduleRecurrenceRuleEntity? {
        val now = System.currentTimeMillis()
        return when (recurrenceInput) {
            RecurrenceInput.None -> null
            is RecurrenceInput.Daily -> ScheduleRecurrenceRuleEntity(
                scheduleId = schedule.id,
                frequency = RecurrenceFrequencyDaily,
                interval = recurrenceInput.intervalDays.coerceAtLeast(1),
                dayOfWeek = null,
                dayOfMonth = null,
                untilAt = recurrenceInput.untilAt,
                count = recurrenceInput.count,
                createdAt = getCachedRule(schedule.id)?.createdAt ?: now,
                updatedAt = now,
            )
            is RecurrenceInput.Weekly -> ScheduleRecurrenceRuleEntity(
                scheduleId = schedule.id,
                frequency = RecurrenceFrequencyWeekly,
                interval = recurrenceInput.intervalWeeks.coerceAtLeast(1),
                dayOfWeek = Instant.ofEpochMilli(schedule.startAt).atZone(scheduleZone).dayOfWeek.value,
                dayOfMonth = null,
                untilAt = recurrenceInput.untilAt,
                count = recurrenceInput.count,
                createdAt = getCachedRule(schedule.id)?.createdAt ?: now,
                updatedAt = now,
            )
            is RecurrenceInput.Monthly -> ScheduleRecurrenceRuleEntity(
                scheduleId = schedule.id,
                frequency = RecurrenceFrequencyMonthly,
                interval = recurrenceInput.intervalMonths.coerceAtLeast(1),
                dayOfWeek = null,
                dayOfMonth = Instant.ofEpochMilli(schedule.startAt).atZone(scheduleZone).dayOfMonth,
                untilAt = recurrenceInput.untilAt,
                count = recurrenceInput.count,
                createdAt = getCachedRule(schedule.id)?.createdAt ?: now,
                updatedAt = now,
            )
        }
    }

    private fun getCachedRule(scheduleId: String): ScheduleRecurrenceRuleEntity? =
        scheduleRecords.value.firstOrNull { it.schedule.id == scheduleId }?.recurrence

    private fun expandScheduleOccurrences(records: List<ScheduleRecord>, rangeStart: Long, rangeEnd: Long): List<ScheduleOccurrence> {
        val skipped = records
            .flatMap { record -> record.exceptions.map { it.scheduleId to it.occurrenceStartAt } }
            .toSet()
        return records.flatMap { record ->
            val schedule = record.schedule
            val rule = record.recurrence
            if (rule == null) {
                if (schedule.startAt in rangeStart until rangeEnd) listOf(ScheduleOccurrence(schedule, schedule.id, schedule.startAt, isRecurring = false)) else emptyList()
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
                    schedule = schedule.copy(startAt = occurrenceStartAt, endAt = schedule.endAt?.plus(delta)),
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
            RecurrenceFrequencyMonthly -> ChronoUnit.MONTHS.between(YearMonth.from(firstStart), YearMonth.from(rangeStartDateTime))
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
        private const val TAG = "PlannerRepository"
        private const val DuplicateCreateWindowMillis = 2_000L
        private val scheduleZone: ZoneId = ZoneId.of("Asia/Seoul")
        private const val RecurrenceFrequencyDaily = "daily"
        private const val RecurrenceFrequencyWeekly = "weekly"
        private const val RecurrenceFrequencyMonthly = "monthly"
    }
}

internal fun candidateFromParseOutcome(
    id: String,
    rawText: String,
    sourceApp: String?,
    receivedAt: Long,
    parseOutcome: AppointmentParseOutcome,
): AppointmentCandidateEntity {
    val parsed = parseOutcome.result
    return AppointmentCandidateEntity(
        id = id,
        rawText = rawText,
        sourceApp = sourceApp?.takeIf { it.isNotBlank() },
        extractedTitle = "",
        extractedStartAt = parsed.startAt,
        extractedEndAt = parsed.endAt,
        extractedLocation = parsed.location?.ifBlank { null },
        confidence = parsed.confidence,
        timeConfidence = parsed.timeConfidence.dbValue,
        status = CandidateStatus.Pending.dbValue,
        createdAt = receivedAt,
        parseSource = parseOutcome.source.toStoredValue(),
    )
}

internal fun parserErrorOutcome(): AppointmentParseOutcome = AppointmentParseOutcome(
    result = AppointmentParseResult(
        title = "",
        startAt = null,
        endAt = null,
        location = null,
        confidence = 0f,
        timeConfidence = TimeConfidence.Low,
    ),
    source = AppointmentParseSource.ParserError,
)

internal fun candidateScheduleTitle(
    candidate: AppointmentCandidateEntity,
    selectedStatus: ScheduleStatus,
    titleOverride: String?,
): String? {
    val explicitTitle = titleOverride?.trim()?.takeIf { it.isNotBlank() }
    val candidateTitle = candidate.extractedTitle.takeIf { it.isNotBlank() }
    return if (selectedStatus == ScheduleStatus.Uncertain) {
        explicitTitle ?: candidateTitle ?: candidate.rawText.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() }
            ?.take(40)
            ?: "미정 일정"
    } else {
        explicitTitle ?: candidateTitle
    }
}

internal fun scheduleFromCandidateSave(
    id: String,
    candidate: AppointmentCandidateEntity,
    title: String,
    scheduleStatus: ScheduleStatus,
    memoOverride: String?,
    now: Long,
): ScheduleEntity = ScheduleEntity(
    id = id,
    title = title,
    startAt = candidate.extractedStartAt ?: candidate.createdAt,
    endAt = candidate.extractedEndAt,
    location = candidate.extractedLocation,
    memo = memoOverride?.ifBlank { null },
    status = scheduleStatus.dbValue,
    sourceText = candidate.rawText,
    sourceApp = candidate.sourceApp,
    createdAt = now,
    updatedAt = now,
)

data class ScheduleRecord(
    val schedule: ScheduleEntity,
    val recurrence: ScheduleRecurrenceRuleEntity?,
    val exceptions: List<ScheduleRecurrenceExceptionEntity>,
)

private fun mergeScheduleRecords(existing: List<ScheduleRecord>, updates: List<ScheduleRecord>): List<ScheduleRecord> {
    val byId = existing.associateBy { it.schedule.id }.toMutableMap()
    updates.forEach { byId[it.schedule.id] = it }
    return byId.values.sortedBy { it.schedule.startAt }
}

private class PlannerApiClient(
    rawBaseUrl: String,
    onUnauthorized: () -> Unit,
) {
    private val http = PlannerHttpClient(
        rawBaseUrl = rawBaseUrl,
        notConfiguredMessage = "Planner API is not configured.",
        onUnauthorized = onUnauthorized,
    )

    fun isConfigured(): Boolean = http.isConfigured()

    fun listSchedules(token: String, rangeStart: Long?, rangeEnd: Long?): List<ScheduleRecord> {
        val query = buildList {
            rangeStart?.let { add("rangeStart=${url(Instant.ofEpochMilli(it).toString())}") }
            rangeEnd?.let { add("rangeEnd=${url(Instant.ofEpochMilli(it).toString())}") }
        }.joinToString("&").let { if (it.isBlank()) "" else "?$it" }
        return parseArrayEnvelope(request("GET", "/api/planner/schedules$query", token, null), "schedules").map { it.toScheduleRecord() }
    }

    fun getSchedule(token: String, id: String): ScheduleRecord? {
        val json = JSONObject(request("GET", "/api/planner/schedules/${url(id)}", token, null))
        return json.optJSONObject("schedule")?.toScheduleRecord()
    }

    fun createSchedule(
        token: String,
        schedule: ScheduleEntity,
        recurrence: ScheduleRecurrenceRuleEntity?,
        exceptions: List<ScheduleRecurrenceExceptionEntity>,
    ): ScheduleRecord {
        val json = JSONObject(request("POST", "/api/planner/schedules", token, schedule.toApiJson(recurrence, exceptions)))
        return json.getJSONObject("schedule").toScheduleRecord()
    }

    fun patchSchedule(
        token: String,
        schedule: ScheduleEntity,
        recurrence: ScheduleRecurrenceRuleEntity?,
        exceptions: List<ScheduleRecurrenceExceptionEntity>,
    ): ScheduleRecord {
        val json = JSONObject(request("PATCH", "/api/planner/schedules/${url(schedule.id)}", token, schedule.toApiJson(recurrence, exceptions)))
        return json.getJSONObject("schedule").toScheduleRecord()
    }

    fun deleteSchedule(token: String, scheduleId: String) {
        request("DELETE", "/api/planner/schedules/${url(scheduleId)}", token, null)
    }

    fun addRecurrenceException(token: String, scheduleId: String, occurrenceStartAt: Long) {
        val body = JSONObject()
            .put("occurrenceStartAt", Instant.ofEpochMilli(occurrenceStartAt).toString())
            .put("action", "skip")
        request("POST", "/api/planner/schedules/${url(scheduleId)}/recurrence-exceptions", token, body)
    }

    fun createCandidate(token: String, candidate: AppointmentCandidateEntity): AppointmentCandidateEntity {
        val response = request("POST", "/api/planner/candidates", token, candidate.toApiJson())
        val json = try {
            JSONObject(response)
        } catch (error: Throwable) {
            Log.e(TAG, "Create candidate response is not valid JSON. responseLength=${response.length}", error)
            throw error
        }
        val envelope = json.optJSONObject("candidate")
        if (envelope == null) {
            Log.e(TAG, "Create candidate response is missing candidate envelope. keys=${json.keys().asSequence().toList()}")
            error("Planner API response is missing candidate.")
        }
        return envelope.toCandidate()
    }

    fun listPendingCandidates(token: String): List<AppointmentCandidateEntity> =
        parseArrayEnvelope(request("GET", "/api/planner/candidates?status=pending", token, null), "candidates").map { it.toCandidate() }

    fun getCandidate(token: String, id: String): AppointmentCandidateEntity? {
        val json = JSONObject(request("GET", "/api/planner/candidates/${url(id)}", token, null))
        return json.optJSONObject("candidate")?.toCandidate()
    }

    fun patchCandidate(token: String, id: String, title: String, startAt: Long?, endAt: Long?, location: String?): AppointmentCandidateEntity {
        val body = JSONObject()
            .put("extractedTitle", title)
            .putNullable("extractedStartAt", startAt?.let { Instant.ofEpochMilli(it).toString() })
            .putNullable("extractedEndAt", endAt?.let { Instant.ofEpochMilli(it).toString() })
            .putNullable("extractedLocation", location)
        val json = JSONObject(request("PATCH", "/api/planner/candidates/${url(id)}", token, body))
        return json.getJSONObject("candidate").toCandidate()
    }

    fun updateCandidateStatus(token: String, id: String, status: String): AppointmentCandidateEntity {
        val json = JSONObject(request("PATCH", "/api/planner/candidates/${url(id)}", token, JSONObject().put("status", status)))
        return json.getJSONObject("candidate").toCandidate()
    }

    fun discardCandidate(token: String, id: String) {
        request("POST", "/api/planner/candidates/${url(id)}/discard", token, JSONObject())
    }

    private fun request(method: String, path: String, token: String, body: JSONObject?): String {
        Log.i(TAG, "Planner API request started. method=$method, path=$path, tokenLength=${token.length}, hasBody=${body != null}")
        return try {
            val text = http.request(method, path, token = token, body = body)
            Log.i(TAG, "Planner API request succeeded. method=$method, path=$path, responseLength=${text.length}")
            text
        } catch (error: java.io.IOException) {
            Log.e(TAG, "Planner API request threw before a usable response. method=$method, path=$path, tokenLength=${token.length}", error)
            throw error
        } catch (error: Throwable) {
            Log.e(TAG, "Planner API request failed. method=$method, path=$path, message=${error.message?.safeSnippet()}", error)
            throw error
        }
    }

    private fun ScheduleEntity.toApiJson(
        recurrenceRule: ScheduleRecurrenceRuleEntity?,
        recurrenceExceptions: List<ScheduleRecurrenceExceptionEntity>,
    ): JSONObject = JSONObject()
        .put("localScheduleId", id)
        .put("title", title)
        .put("startAt", Instant.ofEpochMilli(startAt).toString())
        .putNullable("endAt", endAt?.let { Instant.ofEpochMilli(it).toString() })
        .putNullable("location", location)
        .putNullable("memo", memo)
        .put("status", status)
        .putNullable("sourceText", sourceText)
        .putNullable("sourceApp", sourceApp)
        .putNullable("recurrence", recurrenceRule?.toApiJson())
        .put("recurrenceExceptions", JSONArray().also { array -> recurrenceExceptions.forEach { array.put(it.toApiJson()) } })

    private fun AppointmentCandidateEntity.toApiJson(): JSONObject = JSONObject()
        .put("localCandidateId", id)
        .put("rawText", rawText)
        .putNullable("sourceApp", sourceApp)
        .put("extractedTitle", extractedTitle)
        .putNullable("extractedStartAt", extractedStartAt?.let { Instant.ofEpochMilli(it).toString() })
        .putNullable("extractedEndAt", extractedEndAt?.let { Instant.ofEpochMilli(it).toString() })
        .putNullable("extractedLocation", extractedLocation)
        .put("confidence", confidence.toDouble())
        .put("timeConfidence", timeConfidence)
        .put("parseSource", parseSource)
        .put("status", status)
        .put("createdAt", Instant.ofEpochMilli(createdAt).toString())

    private fun ScheduleRecurrenceRuleEntity.toApiJson(): JSONObject = JSONObject()
        .put("frequency", frequency)
        .put("interval", interval)
        .put("intervalWeeks", if (frequency == "weekly") interval else JSONObject.NULL)
        .putNullable("dayOfWeek", dayOfWeek)
        .putNullable("dayOfMonth", dayOfMonth)
        .putNullable("untilAt", untilAt?.let { Instant.ofEpochMilli(it).toString() })
        .putNullable("count", count)

    private fun ScheduleRecurrenceExceptionEntity.toApiJson(): JSONObject = JSONObject()
        .put("occurrenceStartAt", Instant.ofEpochMilli(occurrenceStartAt).toString())
        .put("action", action)

    private fun JSONObject.toScheduleRecord(): ScheduleRecord {
        val schedule = ScheduleEntity(
            id = optRequiredString("id"),
            title = optRequiredString("title"),
            startAt = parseInstantMillis(optRequiredString("startAt", "start_at")),
            endAt = optNullableString("endAt", "end_at")?.let { parseInstantMillis(it) },
            location = optNullableString("location"),
            memo = optNullableString("memo"),
            status = optString("status", ScheduleStatus.Planned.dbValue),
            sourceText = optNullableString("sourceText", "source_text"),
            sourceApp = optNullableString("sourceApp", "source_app"),
            createdAt = optNullableString("createdAt", "created_at")?.let { parseInstantMillis(it) } ?: 0L,
            updatedAt = optNullableString("updatedAt", "updated_at")?.let { parseInstantMillis(it) } ?: 0L,
        )
        return ScheduleRecord(
            schedule = schedule,
            recurrence = optJSONObject("recurrence")?.toRecurrenceRule(schedule.id),
            exceptions = optJSONArray("recurrenceExceptions", "recurrence_exceptions").toRecurrenceExceptions(schedule.id),
        )
    }

    private fun JSONObject.toCandidate(): AppointmentCandidateEntity = AppointmentCandidateEntity(
        id = optRequiredString("id"),
        rawText = optRequiredString("rawText", "raw_text"),
        sourceApp = optNullableString("sourceApp", "source_app"),
        extractedTitle = optString("extractedTitle", optString("extracted_title", "")),
        extractedStartAt = optNullableString("extractedStartAt", "extracted_start_at")?.let { parseInstantMillis(it) },
        extractedEndAt = optNullableString("extractedEndAt", "extracted_end_at")?.let { parseInstantMillis(it) },
        extractedLocation = optNullableString("extractedLocation", "extracted_location"),
        confidence = optDouble("confidence", 0.0).toFloat(),
        timeConfidence = optString("timeConfidence", optString("time_confidence", "")),
        status = optString("status", CandidateStatus.Pending.dbValue),
        createdAt = optNullableString("createdAt", "created_at")?.let { parseInstantMillis(it) } ?: 0L,
        parseSource = optString("parseSource", optString("parse_source", "unknown")),
    )

    private fun JSONObject.toRecurrenceRule(scheduleId: String): ScheduleRecurrenceRuleEntity = ScheduleRecurrenceRuleEntity(
        scheduleId = scheduleId,
        frequency = optString("frequency", "weekly"),
        interval = optInt("interval", optInt("intervalWeeks", optInt("interval_weeks", 1))),
        dayOfWeek = optNullableInt("dayOfWeek", "day_of_week"),
        dayOfMonth = optNullableInt("dayOfMonth", "day_of_month"),
        untilAt = optNullableString("untilAt", "until_at")?.let { parseInstantMillis(it) },
        count = optNullableInt("count"),
        createdAt = optNullableString("createdAt", "created_at")?.let { parseInstantMillis(it) } ?: 0L,
        updatedAt = optNullableString("updatedAt", "updated_at")?.let { parseInstantMillis(it) } ?: 0L,
    )

    private fun JSONArray?.toRecurrenceExceptions(scheduleId: String): List<ScheduleRecurrenceExceptionEntity> {
        if (this == null) return emptyList()
        return buildList {
            for (i in 0 until length()) {
                val item = getJSONObject(i)
                add(
                    ScheduleRecurrenceExceptionEntity(
                        scheduleId = scheduleId,
                        occurrenceStartAt = parseInstantMillis(item.optRequiredString("occurrenceStartAt", "occurrence_start_at")),
                        action = item.optString("action", "skip"),
                        createdAt = item.optNullableString("createdAt", "created_at")?.let { parseInstantMillis(it) } ?: 0L,
                    ),
                )
            }
        }
    }

    private fun parseArrayEnvelope(text: String, key: String): List<JSONObject> {
        val trimmed = text.trim()
        val array = if (trimmed.startsWith("[")) JSONArray(trimmed) else JSONObject(trimmed).optJSONArray(key) ?: JSONArray()
        return buildList {
            for (i in 0 until array.length()) add(array.getJSONObject(i))
        }
    }

    private fun apiErrorMessage(code: Int, text: String): String {
        val apiMessage = runCatching {
            val json = JSONObject(text)
            json.optString("message").takeIf { it.isNotBlank() } ?: json.optString("error").takeIf { it.isNotBlank() }
        }.getOrNull()
        return apiMessage ?: "Planner API request failed ($code)"
    }

    private fun String.safeSnippet(maxLength: Int = 500): String =
        replace(Regex("\\s+"), " ").take(maxLength)

    private fun JSONObject.optRequiredString(vararg names: String): String {
        names.forEach { name -> optNullableString(name)?.let { return it } }
        error("Planner API response is missing ${names.first()}.")
    }

    private fun JSONObject.optNullableString(vararg names: String): String? {
        names.forEach { name ->
            if (has(name) && !isNull(name)) {
                val value = optString(name).takeIf { it.isNotBlank() && it != "null" }
                if (value != null) return value
            }
        }
        return null
    }

    private fun JSONObject.optJSONArray(vararg names: String): JSONArray? {
        names.forEach { name -> if (has(name) && !isNull(name)) return optJSONArray(name) }
        return null
    }

    private fun JSONObject.optNullableInt(vararg names: String): Int? {
        names.forEach { name -> if (has(name) && !isNull(name)) return optInt(name) }
        return null
    }

    private fun JSONObject.putNullable(name: String, value: Any?): JSONObject = put(name, value ?: JSONObject.NULL)
    private fun parseInstantMillis(value: String): Long = Instant.parse(value).toEpochMilli()
    private fun url(value: String): String = URLEncoder.encode(value, "UTF-8")

    companion object {
        private const val TAG = "PlannerApiClient"
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
