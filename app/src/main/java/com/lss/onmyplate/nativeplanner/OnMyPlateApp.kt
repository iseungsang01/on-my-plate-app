package com.lss.onmyplate.nativeplanner

import android.app.Application
import com.lss.onmyplate.nativeplanner.BuildConfig
import com.lss.onmyplate.nativeplanner.data.auth.AuthRepository
import com.lss.onmyplate.nativeplanner.data.db.AppDatabase
import com.lss.onmyplate.nativeplanner.data.entity.ScheduleEntity
import com.lss.onmyplate.nativeplanner.data.repository.PlannerRepository
import com.lss.onmyplate.nativeplanner.data.supabase.SharingRepository
import com.lss.onmyplate.nativeplanner.domain.parser.GeminiAppointmentParser
import com.lss.onmyplate.nativeplanner.domain.parser.KoreanAppointmentParser
import com.lss.onmyplate.nativeplanner.notification.AppointmentNotificationManager
import com.lss.onmyplate.nativeplanner.widget.PlannerWidgetSync
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

class OnMyPlateApp : Application() {
    val appScope = CoroutineScope(SupervisorJob())
    val database by lazy { AppDatabase.create(this) }
    val parser by lazy {
        KoreanAppointmentParser(
            llmParser = GeminiAppointmentParser(
                apiKey = BuildConfig.GEMINI_API_KEY,
                model = BuildConfig.GEMINI_MODEL,
                baseUrl = BuildConfig.GEMINI_API_BASE_URL,
            ),
            preferLlm = true,
        )
    }
    val repository by lazy { PlannerRepository(database, parser) }
    val authRepository by lazy { AuthRepository(this) }
    val sharingRepository by lazy { SharingRepository(this) }
    val notifications by lazy { AppointmentNotificationManager(this) }

    fun syncScheduleAsync(schedule: ScheduleEntity) {
        appScope.launch {
            runCatching {
                if (sharingRepository.isConfigured() && sharingRepository.hasCachedSession()) {
                    sharingRepository.uploadPersonalSchedule(
                        schedule = schedule,
                        recurrenceRule = repository.getRecurrenceRule(schedule.id),
                        recurrenceExceptions = repository.getRecurrenceExceptions(schedule.id),
                    )
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        notifications.ensureChannels()
        appScope.launch {
            val zone = ZoneId.of("Asia/Seoul")
            val weekStart = LocalDate.now(zone).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            val rangeStart = weekStart.atStartOfDay(zone).toInstant().toEpochMilli()
            val rangeEnd = weekStart.plusDays(7).atStartOfDay(zone).toInstant().toEpochMilli()
            repository.observeExpandedSchedules(rangeStart, rangeEnd).collect { schedules ->
                PlannerWidgetSync.saveSnapshot(this@OnMyPlateApp, schedules)
            }
        }
    }

    companion object {
        lateinit var instance: OnMyPlateApp
            private set
    }
}
