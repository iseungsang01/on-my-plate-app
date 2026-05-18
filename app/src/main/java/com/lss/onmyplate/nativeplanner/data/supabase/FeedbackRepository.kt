package com.lss.onmyplate.nativeplanner.data.supabase

import android.content.Context
import com.lss.onmyplate.nativeplanner.BuildConfig
import com.lss.onmyplate.nativeplanner.data.api.PlannerHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class FeedbackRepository(context: Context) {
    private val appContext = context.applicationContext
    private val sessionPrefs = appContext.getSharedPreferences(BuildConfig.PLANNER_SESSION_PREFS_NAME, Context.MODE_PRIVATE)
    private val client = PlannerFeedbackApiClient(BuildConfig.PLANNER_API_BASE_URL)

    fun isConfigured(): Boolean = client.isConfigured()

    suspend fun submitFeedback(message: String, sourceScreen: String = "settings"): Unit = withContext(Dispatchers.IO) {
        check(client.isConfigured()) { "Feedback API is not configured. Set PLANNER_API_BASE_URL in .env." }
        val cleanMessage = message.trim()
        require(cleanMessage.isNotBlank()) { "피드백 내용을 입력하세요." }
        require(cleanMessage.length <= 2000) { "피드백은 2000자 이내로 입력하세요." }
        client.submitFeedback(
            token = sessionToken(),
            message = cleanMessage,
            sourceScreen = sourceScreen,
        )
    }

    private fun sessionToken(): String? =
        sessionPrefs.getString(BuildConfig.PLANNER_SESSION_TOKEN_KEY, null)
            ?.takeIf { it.isNotBlank() }
}

private class PlannerFeedbackApiClient(rawBaseUrl: String) {
    private val http = PlannerHttpClient(
        rawBaseUrl = rawBaseUrl,
        notConfiguredMessage = "Feedback API is not configured.",
    )

    fun isConfigured(): Boolean = http.isConfigured()

    fun submitFeedback(token: String?, message: String, sourceScreen: String) {
        val body = JSONObject()
            .put("message", message)
            .put("sourceScreen", sourceScreen)
            .put("appVersionName", BuildConfig.VERSION_NAME)
            .put("appVersionCode", BuildConfig.VERSION_CODE)
        http.request("POST", "/api/planner/feedback", token = token, body = body)
    }
}
