package com.lss.onmyplate.nativeplanner

import android.app.Application
import android.util.Log
import com.lss.onmyplate.nativeplanner.BuildConfig
import com.lss.onmyplate.nativeplanner.data.auth.AuthRepository
import com.lss.onmyplate.nativeplanner.data.repository.AvailabilityGroupRepository
import com.lss.onmyplate.nativeplanner.data.repository.PlannerRepository
import com.lss.onmyplate.nativeplanner.data.supabase.FeedbackRepository
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
    val parser by lazy {
        KoreanAppointmentParser(
            llmParser = GeminiAppointmentParser(
                apiBaseUrl = BuildConfig.PLANNER_API_BASE_URL,
                sessionTokenProvider = {
                    getSharedPreferences(BuildConfig.PLANNER_SESSION_PREFS_NAME, MODE_PRIVATE)
                        .getString(BuildConfig.PLANNER_SESSION_TOKEN_KEY, null)
                },
                diagnostics = { message, error ->
                    if (error == null) Log.w(TAG, message) else Log.w(TAG, message, error)
                },
            ),
            preferLlm = true,
        )
    }
    val repository by lazy { PlannerRepository(this, parser) }
    val availabilityGroupRepository by lazy { AvailabilityGroupRepository(this) }
    val authRepository by lazy { AuthRepository(this) }
    val feedbackRepository by lazy { FeedbackRepository(this) }
    val sharingRepository by lazy { SharingRepository(this) }
    val notifications by lazy { AppointmentNotificationManager(this) }

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
        private const val TAG = "OnMyPlateApp"
        lateinit var instance: OnMyPlateApp
            private set
    }
}
