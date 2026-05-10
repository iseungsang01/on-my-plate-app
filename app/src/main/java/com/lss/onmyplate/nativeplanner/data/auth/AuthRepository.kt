package com.lss.onmyplate.nativeplanner.data.auth

import android.content.Context
import com.lss.onmyplate.nativeplanner.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

class AuthRepository(context: Context) {
    private val appContext = context.applicationContext
    private val sessionPrefs = appContext.getSharedPreferences(BuildConfig.PLANNER_SESSION_PREFS_NAME, Context.MODE_PRIVATE)
    private val client = PlannerAuthApiClient(BuildConfig.PLANNER_API_BASE_URL)

    fun isConfigured(): Boolean = client.isConfigured()
    fun hasSession(): Boolean = sessionToken() != null
    fun isGuestMode(): Boolean = sessionPrefs.getBoolean(KEY_GUEST_MODE, false)
    fun hasAppAccess(): Boolean = hasSession() || isGuestMode()
    fun sessionToken(): String? = sessionPrefs.getString(BuildConfig.PLANNER_SESSION_TOKEN_KEY, null)?.takeIf { it.isNotBlank() }

    fun clearSession() {
        sessionPrefs.edit().remove(BuildConfig.PLANNER_SESSION_TOKEN_KEY).apply()
    }

    fun enterGuestMode() {
        sessionPrefs.edit()
            .remove(BuildConfig.PLANNER_SESSION_TOKEN_KEY)
            .putBoolean(KEY_GUEST_MODE, true)
            .apply()
    }

    fun clearAccess() {
        sessionPrefs.edit()
            .remove(BuildConfig.PLANNER_SESSION_TOKEN_KEY)
            .remove(KEY_GUEST_MODE)
            .apply()
    }

    suspend fun login(identifier: String, password: String): AuthSession = authenticate("/api/auth/login", identifier, password)
    suspend fun signUp(identifier: String, password: String): AuthSession = authenticate("/api/auth/signup", identifier, password)

    private suspend fun authenticate(path: String, identifier: String, password: String): AuthSession = withContext(Dispatchers.IO) {
        check(client.isConfigured()) { "인증 API가 설정되지 않았습니다. PLANNER_API_BASE_URL을 확인하세요." }
        val session = client.authenticate(path, identifier.trim(), password)
        sessionPrefs.edit()
            .putString(BuildConfig.PLANNER_SESSION_TOKEN_KEY, session.sessionToken)
            .remove(KEY_GUEST_MODE)
            .apply()
        session
    }

    companion object {
        private const val KEY_GUEST_MODE = "guest_mode"
    }
}

data class AuthSession(val sessionToken: String, val userId: String?)

private class PlannerAuthApiClient(private val rawBaseUrl: String) {
    private val baseUrl = rawBaseUrl.trim().trimEnd('/')

    fun isConfigured(): Boolean = baseUrl.isNotBlank()

    fun authenticate(path: String, identifier: String, password: String): AuthSession {
        require(identifier.isNotBlank()) { "아이디를 입력하세요." }
        require(password.isNotBlank()) { "비밀번호를 입력하세요." }
        val body = JSONObject()
            .put("id", identifier)
            .put("password", password)
        val json = JSONObject(request("POST", path, body))
        val token = json.optNullableString("sessionToken", "session_token", "token")
            ?: error("인증 응답에 sessionToken이 없습니다.")
        return AuthSession(
            sessionToken = token,
            userId = json.optNullableString("userId", "user_id", "uid"),
        )
    }

    private fun request(method: String, path: String, body: JSONObject): String {
        require(isConfigured()) { "Planner API is not configured." }
        val connection = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15000
            readTimeout = 15000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            doOutput = true
            outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
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
        return apiMessage ?: "인증 요청에 실패했습니다. ($code)"
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
}
