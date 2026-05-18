package com.lss.onmyplate.nativeplanner.widget

import android.content.Context
import com.lss.onmyplate.nativeplanner.OnMyPlateApp
import com.lss.onmyplate.nativeplanner.data.entity.ScheduleEntity
import com.lss.onmyplate.nativeplanner.data.repository.ScheduleOccurrence
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

object PlannerWidgetSync {
    private val zoneId: ZoneId = ZoneId.of("Asia/Seoul")
    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun syncFromPlannerApiSnapshot(context: Context) {
        val appContext = context.applicationContext
        syncScope.launch {
            val app = appContext as? OnMyPlateApp
            if (app == null) {
                return@launch
            }
            if (!app.authRepository.hasSession()) {
                clearSnapshot(appContext)
                return@launch
            }
            runCatching {
                val monday = currentWeekStart()
                val rangeStart = monday.atStartOfDay(zoneId).toInstant().toEpochMilli()
                val rangeEnd = monday.plusDays(7).atStartOfDay(zoneId).toInstant().toEpochMilli()
                saveSnapshot(appContext, app.repository.getExpandedSchedules(rangeStart, rangeEnd))
            }.onFailure {
                // Keep the last valid snapshot on transient API/network failure.
            }
        }
    }

    fun clearSnapshot(context: Context) {
        saveSnapshot(context, emptyList())
    }

    fun saveSnapshot(context: Context, schedules: List<ScheduleOccurrence>, refreshWidgets: Boolean = true) {
        val snapshot = buildSnapshot(schedules)
        PlannerWidgetStore.saveSummarySnapshot(context.applicationContext, snapshot.toString(), refreshWidgets)
    }

    internal fun buildSnapshot(schedules: List<ScheduleOccurrence>): JSONObject {
        val manualEventsByDate = JSONObject()

        schedules
            .sortedBy { it.occurrenceStartAt }
            .forEach { occurrence ->
                putManualEvent(manualEventsByDate, occurrence)
            }

        return JSONObject()
            .put("schema", "native-supabase-schedules-v1")
            .put("generatedAt", Instant.now().toString())
            .put("weekStart", currentWeekStart().toString())
            .put("viewportStartMinute", 8 * 60)
            .put("viewportEndMinute", 24 * 60)
            .put("manualEventsByDate", manualEventsByDate)
    }

    private fun putManualEvent(manualEventsByDate: JSONObject, occurrence: ScheduleOccurrence) {
        val schedule = occurrence.schedule
        val startMillis = occurrence.occurrenceStartAt
        val durationMillis = schedule.durationMillis()
        val endMillis = startMillis + durationMillis
        val start = Instant.ofEpochMilli(startMillis).atZone(zoneId).toLocalDateTime()
        val end = Instant.ofEpochMilli(endMillis).atZone(zoneId).toLocalDateTime()
        val startMinute = start.hour * 60 + start.minute
        val endMinute = (end.hour * 60 + end.minute).coerceAtLeast(startMinute + 30)
        val dateKey = start.toLocalDate().toString()
        val items = manualEventsByDate.optJSONArray(dateKey)
            ?: JSONArray().also { manualEventsByDate.put(dateKey, it) }

        items.put(
            JSONObject()
                .put("title", schedule.title)
                .put("startMinute", startMinute)
                .put("endMinute", endMinute)
                .put("source", "manual")
                .put("isRecurring", occurrence.isRecurring),
        )
    }

    private fun ScheduleEntity.durationMillis(): Long {
        val fallback = 60 * 60 * 1000L
        val explicitEnd = endAt ?: return fallback
        return (explicitEnd - startAt).takeIf { it > 0L } ?: fallback
    }

    private fun currentWeekStart(): LocalDate =
        LocalDate.now(zoneId).with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
}
