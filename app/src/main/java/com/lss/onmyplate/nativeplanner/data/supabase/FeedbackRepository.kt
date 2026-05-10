package com.lss.onmyplate.nativeplanner.data.supabase

import android.content.Context
import com.lss.onmyplate.nativeplanner.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

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

private class PlannerFeedbackApiClient(private val rawBaseUrl: String) {
    private val baseUrl = rawBaseUrl.trim().trimEnd('/')

    fun isConfigured(): Boolean = baseUrl.isNotBlank()

    fun submitFeedback(token: String?, message: String, sourceScreen: String) {
        val body = JSONObject()
            .put("message", message)
            .put("sourceScreen", sourceScreen)
            .put("appVersionName", BuildConfig.VERSION_NAME)
            .put("appVersionCode", BuildConfig.VERSION_CODE)
        request("POST", "/api/planner/feedback", token, body)
    }

    private fun request(method: String, path: String, token: String?, body: JSONObject?): String {
        require(isConfigured()) { "Feedback API is not configured." }
        val connection = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15000
            readTimeout = 15000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            if (!token.isNullOrBlank()) {
                setRequestProperty("Authorization", "Bearer $token")
            }
            if (body != null) {
                doOutput = true
                outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
        }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.use { input -> BufferedReader(InputStreamReader(input)).readText() }.orEmpty()
        if (code !in 200..299) error(errorMessage(code, text))
        return text.ifBlank { "{}" }
    }

    private fun errorMessage(code: Int, text: String): String {
        val apiMessage = runCatching {
            val json = JSONObject(text)
            json.optString("message").takeIf { it.isNotBlank() }
                ?: json.optString("error").takeIf { it.isNotBlank() }
        }.getOrNull()
        return apiMessage ?: "Feedback API request failed ($code)"
    }
}
