package com.lss.onmyplate.nativeplanner.widget

import androidx.test.core.app.ApplicationProvider
import com.lss.onmyplate.nativeplanner.data.entity.ScheduleEntity
import com.lss.onmyplate.nativeplanner.data.repository.ScheduleOccurrence
import com.lss.onmyplate.nativeplanner.domain.model.ScheduleStatus
import java.time.LocalDateTime
import java.time.ZoneId
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlannerWidgetSyncTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val zoneId = ZoneId.of("Asia/Seoul")

    @Before
    fun clearPrefs() {
        PlannerWidgetStore.getPrefs(context).edit().clear().commit()
    }

    @Test
    fun saveSnapshotStoresNativeManualEventsGroupedByDate() {
        val firstStart = epochMillis(2026, 5, 7, 9, 15)
        val secondStart = epochMillis(2026, 5, 7, 11, 0)

        PlannerWidgetSync.saveSnapshot(
            context,
            listOf(
                occurrence(schedule(id = "later", title = "Later", startAt = secondStart, endAt = secondStart + 45 * 60 * 1_000L)),
                occurrence(schedule(id = "earlier", title = "Earlier", startAt = firstStart, endAt = firstStart + 60 * 60 * 1_000L)),
            ),
        )

        val snapshot = JSONObject(
            requireNotNull(
                PlannerWidgetStore.getPrefs(context)
                    .getString(PlannerWidgetStore.KEY_SUMMARY_SNAPSHOT, null),
            ),
        )
        assertEquals("native-supabase-schedules-v1", snapshot.getString("schema"))
        assertTrue(snapshot.has("generatedAt"))
        assertTrue(snapshot.has("weekStart"))
        assertEquals(8 * 60, snapshot.getInt("viewportStartMinute"))
        assertEquals(24 * 60, snapshot.getInt("viewportEndMinute"))
        assertFalse(snapshot.has("autoPlans"))

        val dateKey = java.time.Instant.ofEpochMilli(firstStart).atZone(zoneId).toLocalDate().toString()
        val items = snapshot
            .getJSONObject("manualEventsByDate")
            .getJSONArray(dateKey)
        assertEquals(2, items.length())
        assertEquals("Earlier", items.getJSONObject(0).getString("title"))
        assertEquals(9 * 60 + 15, items.getJSONObject(0).getInt("startMinute"))
        assertEquals(10 * 60 + 15, items.getJSONObject(0).getInt("endMinute"))
        assertEquals("manual", items.getJSONObject(0).getString("source"))
        assertFalse(items.getJSONObject(0).getBoolean("isRecurring"))
        assertEquals("Later", items.getJSONObject(1).getString("title"))
    }

    @Test
    fun saveSnapshotUsesDefaultOneHourEndWithMinimumThirtyMinutesAfterMidnightRollover() {
        val startAt = epochMillis(2026, 5, 8, 23, 45)

        PlannerWidgetSync.saveSnapshot(
            context,
            listOf(occurrence(schedule(id = "open-ended", title = "Open Ended", startAt = startAt, endAt = null))),
        )

        val event = JSONObject(
            requireNotNull(
                PlannerWidgetStore.getPrefs(context)
                    .getString(PlannerWidgetStore.KEY_SUMMARY_SNAPSHOT, null),
            ),
        ).getJSONObject("manualEventsByDate")
            .getJSONArray("2026-05-08")
            .getJSONObject(0)

        assertEquals(23 * 60 + 45, event.getInt("startMinute"))
        assertEquals(23 * 60 + 75, event.getInt("endMinute"))
    }

    private fun schedule(id: String, title: String, startAt: Long, endAt: Long?): ScheduleEntity =
        ScheduleEntity(
            id = id,
            title = title,
            startAt = startAt,
            endAt = endAt,
            location = null,
            memo = null,
            status = ScheduleStatus.Confirmed.dbValue,
            sourceText = null,
            sourceApp = null,
            createdAt = 1L,
            updatedAt = 1L,
        )

    private fun occurrence(schedule: ScheduleEntity, isRecurring: Boolean = false): ScheduleOccurrence =
        ScheduleOccurrence(
            schedule = schedule,
            scheduleId = schedule.id,
            occurrenceStartAt = schedule.startAt,
            isRecurring = isRecurring,
        )

    private fun epochMillis(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        LocalDateTime.of(year, month, day, hour, minute).atZone(zoneId).toInstant().toEpochMilli()
}
