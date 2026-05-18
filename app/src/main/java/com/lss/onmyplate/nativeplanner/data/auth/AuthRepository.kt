package com.lss.onmyplate.nativeplanner.data.auth

import android.content.Context
import android.util.Log
import com.lss.onmyplate.nativeplanner.BuildConfig
import com.lss.onmyplate.nativeplanner.data.api.PlannerHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class AuthRepository(context: Context) {
    private val appContext = context.applicationContext
    private val sessionPrefs = appContext.getSharedPreferences(BuildConfig.PLANNER_SESSION_PREFS_NAME, Context.MODE_PRIVATE)
    private val client = PlannerAuthApiClient(BuildConfig.PLANNER_API_BASE_URL)

    fun isConfigured(): Boolean = client.isConfigured()
    fun hasSession(): Boolean = sessionToken() != null
    fun hasAppAccess(): Boolean = hasSession()
    fun sessionToken(): String? = sessionPrefs.getString(BuildConfig.PLANNER_SESSION_TOKEN_KEY, null)?.takeIf { it.isNotBlank() }
    fun debugSessionState(): AuthSessionDebugState {
        val token = sessionToken()
        return AuthSessionDebugState(
            prefsName = BuildConfig.PLANNER_SESSION_PREFS_NAME,
            tokenKey = BuildConfig.PLANNER_SESSION_TOKEN_KEY,
            apiConfigured = client.isConfigured(),
            hasToken = token != null,
            tokenLength = token?.length ?: 0,
        )
    }

    fun clearSession() {
        sessionPrefs.edit().remove(BuildConfig.PLANNER_SESSION_TOKEN_KEY).apply()
    }

    fun clearAccess() {
        sessionPrefs.edit()
            .remove(BuildConfig.PLANNER_SESSION_TOKEN_KEY)
            .apply()
    }

    suspend fun login(identifier: String, password: String): AuthSession = authenticate("/api/auth/login", identifier, password)
    suspend fun signUp(identifier: String, password: String): AuthSession = authenticate("/api/auth/signup", identifier, password)

    suspend fun changePassword(currentPassword: String, newPassword: String): Unit = withContext(Dispatchers.IO) {
        check(client.isConfigured()) { "인증 API가 설정되지 않았습니다. PLANNER_API_BASE_URL을 확인하세요." }
        val token = sessionToken() ?: error("로그인 세션이 필요합니다.")
        client.changePassword(token, currentPassword, newPassword)
    }

    private suspend fun authenticate(path: String, identifier: String, password: String): AuthSession = withContext(Dispatchers.IO) {
        check(client.isConfigured()) { "인증 API가 설정되지 않았습니다. PLANNER_API_BASE_URL을 확인하세요." }
        val session = client.authenticate(path, identifier.trim(), password)
        sessionPrefs.edit()
            .putString(BuildConfig.PLANNER_SESSION_TOKEN_KEY, session.sessionToken)
            .apply()
        Log.i(TAG, "Auth session cached. ${debugSessionState()}")
        session
    }

    companion object {
        private const val TAG = "AuthRepository"
    }
}

data class AuthSession(val sessionToken: String, val userId: String?)

data class AuthSessionDebugState(
    val prefsName: String,
    val tokenKey: String,
    val apiConfigured: Boolean,
    val hasToken: Boolean,
    val tokenLength: Int,
) {
    override fun toString(): String =
        "AuthSessionDebugState(prefsName=$prefsName, tokenKey=$tokenKey, apiConfigured=$apiConfigured, hasToken=$hasToken, tokenLength=$tokenLength)"
}

private class PlannerAuthApiClient(rawBaseUrl: String) {
    private val http = PlannerHttpClient(
        rawBaseUrl = rawBaseUrl,
        notConfiguredMessage = "약속 바구니 API가 설정되지 않았습니다.",
    )

    fun isConfigured(): Boolean = http.isConfigured()

    fun authenticate(path: String, identifier: String, password: String): AuthSession {
        require(identifier.isNotBlank()) { "아이디를 입력하세요." }
        require(password.isNotBlank()) { "비밀번호를 입력하세요." }
        val body = JSONObject()
            .put("id", identifier)
            .put("password", password)
        val json = JSONObject(http.request("POST", path, body = body))
        val token = json.optNullableString("sessionToken", "session_token", "token")
            ?: error("인증 응답에 sessionToken이 없습니다.")
        return AuthSession(
            sessionToken = token,
            userId = json.optNullableString("userId", "user_id", "uid"),
        )
    }

    fun changePassword(sessionToken: String, currentPassword: String, newPassword: String) {
        require(sessionToken.isNotBlank()) { "로그인 세션이 필요합니다." }
        require(currentPassword.isNotBlank()) { "현재 비밀번호를 입력하세요." }
        require(newPassword.isNotBlank()) { "새 비밀번호를 입력하세요." }
        val body = JSONObject()
            .put("currentPassword", currentPassword)
            .put("newPassword", newPassword)
        http.request("POST", "/api/auth/password", token = sessionToken, body = body)
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
