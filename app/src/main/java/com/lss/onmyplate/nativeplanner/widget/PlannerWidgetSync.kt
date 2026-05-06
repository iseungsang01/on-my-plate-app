package com.lss.onmyplate.nativeplanner.widget

import android.content.Context
import com.lss.onmyplate.nativeplanner.OnMyPlateApp
import com.lss.onmyplate.nativeplanner.data.db.AppDatabase
import com.lss.onmyplate.nativeplanner.data.entity.ScheduleEntity
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

    fun syncFromPlannerDatabase(context: Context) {
        val appContext = context.applicationContext
        syncScope.launch {
            val appDatabase = (appContext as? OnMyPlateApp)?.database
            val db = appDatabase ?: AppDatabase.create(appContext)
            try {
                saveSnapshot(appContext, db.scheduleDao().getAll())
            } finally {
                if (appDatabase == null) {
                    db.close()
                }
            }
        }
    }

    fun saveSnapshot(context: Context, schedules: List<ScheduleEntity>) {
        val manualEventsByDate = JSONObject()
        schedules
            .sortedBy { it.startAt }
            .forEach { schedule ->
                val start = Instant.ofEpochMilli(schedule.startAt).atZone(zoneId).toLocalDateTime()
                val end = Instant.ofEpochMilli(schedule.endAt ?: (schedule.startAt + 60 * 60 * 1000)).atZone(zoneId).toLocalDateTime()
                val dateKey = start.toLocalDate().toString()
                val items = manualEventsByDate.optJSONArray(dateKey) ?: JSONArray().also { manualEventsByDate.put(dateKey, it) }
                items.put(
                    JSONObject()
                        .put("title", schedule.title)
                        .put("startMinute", start.hour * 60 + start.minute)
                        .put("endMinute", (end.hour * 60 + end.minute).coerceAtLeast(start.hour * 60 + start.minute + 30))
                        .put("source", "manual")
                        .put("isRecurring", false),
                )
            }

        val monday = LocalDate.now(zoneId).with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
        val snapshot = JSONObject()
            .put("weekStart", monday.toString())
            .put("viewportStartMinute", 8 * 60)
            .put("viewportEndMinute", 24 * 60)
            .put("manualEventsByDate", manualEventsByDate)
            .put("autoPlans", JSONArray())

        PlannerWidgetStore.saveSummarySnapshot(context.applicationContext, snapshot.toString())
    }
}
