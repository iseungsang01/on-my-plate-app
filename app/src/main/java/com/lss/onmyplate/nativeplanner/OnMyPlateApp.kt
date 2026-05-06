package com.lss.onmyplate.nativeplanner

import android.app.Application
import com.lss.onmyplate.nativeplanner.BuildConfig
import com.lss.onmyplate.nativeplanner.data.db.AppDatabase
import com.lss.onmyplate.nativeplanner.data.repository.PlannerRepository
import com.lss.onmyplate.nativeplanner.domain.parser.GeminiAppointmentParser
import com.lss.onmyplate.nativeplanner.domain.parser.KoreanAppointmentParser
import com.lss.onmyplate.nativeplanner.notification.AppointmentNotificationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

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
        )
    }
    val repository by lazy { PlannerRepository(database, parser) }
    val notifications by lazy { AppointmentNotificationManager(this) }

    override fun onCreate() {
        super.onCreate()
        instance = this
        notifications.ensureChannels()
    }

    companion object {
        lateinit var instance: OnMyPlateApp
            private set
    }
}
