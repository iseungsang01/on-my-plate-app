package com.lss.onmyplate.nativeplanner.data.repository

import android.content.Context
import android.util.Log
import com.lss.onmyplate.nativeplanner.BuildConfig
import com.lss.onmyplate.nativeplanner.data.entity.AppointmentCandidateEntity
import com.lss.onmyplate.nativeplanner.data.entity.ScheduleEntity
import com.lss.onmyplate.nativeplanner.data.entity.ScheduleRecurrenceExceptionEntity
import com.lss.onmyplate.nativeplanner.data.entity.ScheduleRecurrenceRuleEntity
import com.lss.onmyplate.nativeplanner.domain.conflict.ConflictDetector
import com.lss.onmyplate.nativeplanner.domain.model.CandidateStatus
import com.lss.onmyplate.nativeplanner.domain.model.ScheduleStatus
import com.lss.onmyplate.nativeplanner.domain.model.TimeConfidence
import com.lss.onmyplate.nativeplanner.domain.parser.KoreanAppointmentParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID

class PlannerRepository(
    context: Context,
    private val parser: KoreanAppointmentParser,
) {
    private val appContext = context.applicationContext
    private val sessionPrefs = appContext.getSharedPreferences(BuildConfig.PLANNER_SESSION_PREFS_NAME, Context.MODE_PRIVATE)
    private val client = PlannerApiClient(BuildConfig.PLANNER_API_BASE_URL)
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

    suspend fun getSchedules(): List<ScheduleEntity> = refreshSchedules().map { it.schedule }

    suspend fun getSchedule(id: String): ScheduleEntity? = refreshSchedule(id)?.schedule

    suspend fun getRecurrenceRule(scheduleId: String): ScheduleRecurrenceRuleEntity? =
        scheduleRecords.value.firstOrNull { it.schedule.id == scheduleId }?.recurrence ?: refreshSchedule(scheduleId)?.recurrence

    suspend fun getRecurrenceExceptions(scheduleId: String): List<ScheduleRecurrenceExceptionEntity> =
        scheduleRecords.value.firstOrNull { it.schedule.id == scheduleId }?.exceptions ?: refreshSchedule(scheduleId)?.exceptions.orEmpty()

    suspend fun getExpandedSchedules(rangeStart: Long, rangeEnd: Long): List<ScheduleOccurrence> =
        expandScheduleOccurrences(refreshSchedules(rangeStart, rangeEnd), rangeStart, rangeEnd)

    suspend fun createCandidate(rawText: String, sourceApp: String?, receivedAt: Long): AppointmentCandidateEntity {
        Log.i(TAG, "createCandidate started. textLength=${rawText.length}, sourceApp=$sourceApp, apiConfigured=${client.isConfigured()}, hasSession=${hasCachedSession()}")
        val startAt = receivedAt
        val endAt = startAt + ChronoUnit.HOURS.duration.toMillis()
        val localCandidate = AppointmentCandidateEntity(
            id = UUID.randomUUID().toString(),
            rawText = rawText,
            sourceApp = sourceApp?.takeIf { it.isNotBlank() },
            extractedTitle = "더미 약속 후보",
            extractedStartAt = startAt,
            extractedEndAt = endAt,
            extractedLocation = "더미 장소",
            confidence = 0f,
            timeConfidence = TimeConfidence.Low.dbValue,
            status = CandidateStatus.Pending.dbValue,
            createdAt = receivedAt,
        )
        Log.i(TAG, "createCandidate skipped parser and built dummy candidate. ${localCandidate.diagnosticSummary()}")
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
            throw error
        }
        Log.i(TAG, "createCandidate API save succeeded. candidateId=${saved.id}, status=${saved.status}")
        rememberCandidate(saved)
        refreshPendingCandidates()
        return saved
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
    ): SaveResult {
        val candidate = getCandidate(candidateId) ?: return SaveResult.MissingCandidate
        if (candidate.status != CandidateStatus.Pending.dbValue) return SaveResult.AlreadyHandled
        val title = titleOverride?.trim()?.takeIf { it.isNotBlank() }
            ?: candidate.extractedTitle.takeIf { it.isNotBlank() }
            ?: return SaveResult.TitleRequired
        val titledCandidate = if (candidate.extractedTitle == title) candidate else {
            updateCandidate(candidate.id, title, candidate.extractedStartAt, candidate.extractedEndAt, candidate.extractedLocation)
            getCandidate(candidate.id)?.copy(extractedTitle = title) ?: candidate.copy(extractedTitle = title)
        }

        val startAt = titledCandidate.extractedStartAt
        val scheduleStatus = if (selectedStatus == ScheduleStatus.Uncertain || startAt == null) ScheduleStatus.Uncertain else selectedStatus
        if (scheduleStatus != ScheduleStatus.Uncertain && startAt != null) {
            val endAt = ConflictDetector.newEnd(startAt, titledCandidate.extractedEndAt)
            val conflicts = getSchedules().filter { ConflictDetector.conflicts(startAt, endAt, it) }
            if (conflicts.isNotEmpty() && !force) return SaveResult.Conflict(titledCandidate, conflicts)
        }

        val now = System.currentTimeMillis()
        val schedule = ScheduleEntity(
            id = UUID.randomUUID().toString(),
            title = title,
            startAt = startAt ?: titledCandidate.createdAt,
            endAt = titledCandidate.extractedEndAt,
            location = titledCandidate.extractedLocation,
            memo = null,
            status = scheduleStatus.dbValue,
            sourceText = titledCandidate.rawText,
            sourceApp = titledCandidate.sourceApp,
            createdAt = now,
            updatedAt = now,
        )
        val savedRecord = withContext(Dispatchers.IO) {
            client.createSchedule(sessionToken(), schedule, recurrenceFor(schedule, recurrenceInput), emptyList())
        }
        withContext(Dispatchers.IO) { client.updateCandidateStatus(sessionToken(), candidateId, CandidateStatus.Confirmed.dbValue) }
        rememberCandidate(titledCandidate.copy(status = CandidateStatus.Confirmed.dbValue))
        refreshPendingCandidates()
        refreshSchedules()
        return if (scheduleStatus == ScheduleStatus.Uncertain) SaveResult.SavedAsUncertain(savedRecord.schedule) else SaveResult.Saved(savedRecord.schedule)
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
        return true
    }

    suspend fun skipRecurringOccurrence(scheduleId: String, occurrenceStartAt: Long) {
        withContext(Dispatchers.IO) { client.addRecurrenceException(sessionToken(), scheduleId, occurrenceStartAt) }
        refreshSchedule(scheduleId)
        refreshSchedules()
    }

    suspend fun discardCandidate(candidateId: String) {
        withContext(Dispatchers.IO) { client.discardCandidate(sessionToken(), candidateId) }
        candidateRecords.value = candidateRecords.value + (candidateId to (candidateRecords.value[candidateId]?.copy(status = CandidateStatus.Discarded.dbValue)
            ?: return))
        refreshPendingCandidates()
    }

    suspend fun refreshSchedules(rangeStart: Long? = null, rangeEnd: Long? = null): List<ScheduleRecord> {
        val records = withContext(Dispatchers.IO) { client.listSchedules(sessionToken(), rangeStart, rangeEnd) }
        scheduleRecords.value = mergeScheduleRecords(scheduleRecords.value, records)
        return records
    }

    private suspend fun refreshSchedule(id: String): ScheduleRecord? {
        val record = withContext(Dispatchers.IO) { client.getSchedule(sessionToken(), id) }
        if (record != null) scheduleRecords.value = mergeScheduleRecords(scheduleRecords.value, listOf(record))
        return record
    }

    private suspend fun refreshPendingCandidates(): List<AppointmentCandidateEntity> {
        val candidates = withContext(Dispatchers.IO) { client.listPendingCandidates(sessionToken()) }
        pendingCandidates.value = candidates
        candidateRecords.value = candidateRecords.value + candidates.associateBy { it.id }
        return candidates
    }

    private suspend fun refreshCandidate(id: String): AppointmentCandidateEntity? {
        val candidate = withContext(Dispatchers.IO) { client.getCandidate(sessionToken(), id) }
        if (candidate != null) rememberCandidate(candidate)
        return candidate
    }

    private fun rememberCandidate(candidate: AppointmentCandidateEntity) {
        candidateRecords.value = candidateRecords.value + (candidate.id to candidate)
    }

    private fun AppointmentCandidateEntity.diagnosticSummary(): String =
        "localCandidateId=$id, rawTextLength=${rawText.length}, hasSourceApp=${sourceApp != null}, hasTitle=${extractedTitle.isNotBlank()}, hasStart=${extractedStartAt != null}, hasEnd=${extractedEndAt != null}, hasLocation=${extractedLocation != null}, confidence=$confidence, timeConfidence=$timeConfidence, status=$status, createdAt=$createdAt"

    private fun sessionToken(): String =
        sessionPrefs.getString(BuildConfig.PLANNER_SESSION_TOKEN_KEY, null)?.takeIf { it.isNotBlank() }
            ?: error("Login is required.")

    private fun hasCachedSession(): Boolean =
        sessionPrefs.getString(BuildConfig.PLANNER_SESSION_TOKEN_KEY, null)?.isNotBlank() == true

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
        private val scheduleZone: ZoneId = ZoneId.of("Asia/Seoul")
        private const val RecurrenceFrequencyDaily = "daily"
        private const val RecurrenceFrequencyWeekly = "weekly"
        private const val RecurrenceFrequencyMonthly = "monthly"
    }
}

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

private class PlannerApiClient(private val rawBaseUrl: String) {
    private val baseUrl = rawBaseUrl.trim().trimEnd('/')

    fun isConfigured(): Boolean = baseUrl.isNotBlank()

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
        require(isConfigured()) { "Planner API is not configured." }
        Log.i(TAG, "Planner API request started. method=$method, path=$path, tokenLength=${token.length}, hasBody=${body != null}")
        try {
            val connection = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 15000
                readTimeout = 15000
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
                if (body != null) {
                    doOutput = true
                    outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.use { input -> BufferedReader(InputStreamReader(input)).readText() }.orEmpty()
            if (code !in 200..299) {
                Log.e(
                    TAG,
                    "Planner API request failed. method=$method, path=$path, status=$code, error=${apiErrorMessage(code, text)}, responseLength=${text.length}, responseSnippet=${text.safeSnippet()}",
                )
                error(apiErrorMessage(code, text))
            }
            Log.i(TAG, "Planner API request succeeded. method=$method, path=$path, status=$code, responseLength=${text.length}")
            return text.ifBlank {
                Log.w(TAG, "Planner API returned an empty body. method=$method, path=$path")
                "{}"
            }
        } catch (error: java.io.IOException) {
            Log.e(TAG, "Planner API request threw before a usable response. method=$method, path=$path, tokenLength=${token.length}", error)
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
