package com.lss.onmyplate.nativeplanner.data.supabase

import android.content.Context
import com.lss.onmyplate.nativeplanner.BuildConfig
import com.lss.onmyplate.nativeplanner.data.entity.ScheduleEntity
import com.lss.onmyplate.nativeplanner.data.entity.ScheduleRecurrenceExceptionEntity
import com.lss.onmyplate.nativeplanner.data.entity.ScheduleRecurrenceRuleEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant

class SharingRepository(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val sessionPrefs = appContext.getSharedPreferences(BuildConfig.PLANNER_SESSION_PREFS_NAME, Context.MODE_PRIVATE)
    private val client = PlannerShareApiClient(BuildConfig.PLANNER_API_BASE_URL)

    fun isConfigured(): Boolean = client.isConfigured()
    fun cachedPublicId(): String? = prefs.getString(KEY_PUBLIC_ID, null)
    fun hasCachedSession(): Boolean = sessionPrefs.getString(BuildConfig.PLANNER_SESSION_TOKEN_KEY, null)?.isNotBlank() == true

    fun clearAccountCache() {
        sessionPrefs.edit().remove(BuildConfig.PLANNER_SESSION_TOKEN_KEY).apply()
        prefs.edit().remove(KEY_PUBLIC_ID).apply()
    }

    suspend fun ensureProfile(): ShareProfile = withContext(Dispatchers.IO) {
        val profile = client.profile(sessionToken())
        prefs.edit().putString(KEY_PUBLIC_ID, profile.publicId).apply()
        profile
    }

    suspend fun createGroupWithPartner(partnerPublicId: String): ShareGroup = withContext(Dispatchers.IO) {
        require(partnerPublicId.isNotBlank()) { "Enter a partner sharing ID." }
        client.createGroup(sessionToken(), partnerPublicId.trim())
    }

    suspend fun listGroups(): List<ShareGroup> = withContext(Dispatchers.IO) {
        client.listGroups(sessionToken())
    }

    suspend fun uploadSchedule(
        groupId: String,
        schedule: ScheduleEntity,
        recurrenceRule: ScheduleRecurrenceRuleEntity? = null,
        recurrenceExceptions: List<ScheduleRecurrenceExceptionEntity> = emptyList(),
    ): Unit = withContext(Dispatchers.IO) {
        client.uploadSchedule(sessionToken(), groupId, schedule, recurrenceRule, recurrenceExceptions)
    }

    suspend fun uploadPersonalSchedule(
        schedule: ScheduleEntity,
        recurrenceRule: ScheduleRecurrenceRuleEntity? = null,
        recurrenceExceptions: List<ScheduleRecurrenceExceptionEntity> = emptyList(),
    ): Unit = withContext(Dispatchers.IO) {
        client.uploadPersonalSchedule(sessionToken(), schedule, recurrenceRule, recurrenceExceptions)
    }

    suspend fun listSharedSchedules(groupId: String, includeDummy: Boolean): List<SharedSchedule> = withContext(Dispatchers.IO) {
        client.listSchedules(sessionToken(), groupId, includeDummy)
    }

    private fun sessionToken(): String {
        check(client.isConfigured()) { "Planner share API is not configured. Set PLANNER_API_BASE_URL in .env." }
        return sessionPrefs.getString(BuildConfig.PLANNER_SESSION_TOKEN_KEY, null)
            ?.takeIf { it.isNotBlank() }
            ?: error("Login is required. Sign in to the app and try again.")
    }

    companion object {
        private const val PREFS_NAME = "planner_share_api"
        private const val KEY_PUBLIC_ID = "public_id"
    }
}

data class ShareProfile(val userId: String, val publicId: String)
data class ShareGroup(val id: String, val name: String)
data class SharedSchedule(
    val id: String,
    val title: String,
    val startAt: Long,
    val endAt: Long?,
    val location: String?,
    val status: String,
    val isDummy: Boolean,
    val recurrence: SharedScheduleRecurrence?,
    val recurrenceExceptions: List<SharedScheduleRecurrenceException>,
)
data class SharedScheduleRecurrence(
    val frequency: String,
    val intervalWeeks: Int,
    val dayOfWeek: Int,
    val untilAt: Long?,
    val count: Int?,
)
data class SharedScheduleRecurrenceException(
    val occurrenceStartAt: Long,
    val action: String,
)

private class PlannerShareApiClient(private val rawBaseUrl: String) {
    private val baseUrl = rawBaseUrl.trim().trimEnd('/')

    fun isConfigured(): Boolean = baseUrl.isNotBlank()

    fun profile(token: String): ShareProfile {
        val json = JSONObject(request("POST", "/api/planner/share/profile", token, JSONObject()))
        return json.toShareProfile()
    }

    fun createGroup(token: String, partnerPublicId: String): ShareGroup {
        val body = JSONObject().put("partnerPublicId", partnerPublicId)
        val json = JSONObject(request("POST", "/api/planner/share/groups", token, body))
        return json.toShareGroup()
    }

    fun listGroups(token: String): List<ShareGroup> {
        val text = request("GET", "/api/planner/share/groups", token, null)
        return parseArrayEnvelope(text, "groups").map { it.toShareGroup() }
    }

    fun uploadSchedule(
        token: String,
        groupId: String,
        schedule: ScheduleEntity,
        recurrenceRule: ScheduleRecurrenceRuleEntity?,
        recurrenceExceptions: List<ScheduleRecurrenceExceptionEntity>,
    ) {
        request(
            "POST",
            "/api/planner/share/groups/${url(groupId)}/schedules",
            token,
            schedule.toApiJson(recurrenceRule, recurrenceExceptions),
        )
    }

    fun uploadPersonalSchedule(
        token: String,
        schedule: ScheduleEntity,
        recurrenceRule: ScheduleRecurrenceRuleEntity?,
        recurrenceExceptions: List<ScheduleRecurrenceExceptionEntity>,
    ) {
        request("POST", "/api/planner/schedules", token, schedule.toApiJson(recurrenceRule, recurrenceExceptions))
    }

    fun listSchedules(token: String, groupId: String, includeDummy: Boolean): List<SharedSchedule> {
        val text = request(
            "GET",
            "/api/planner/share/groups/${url(groupId)}/schedules?includeDummy=$includeDummy",
            token,
            null,
        )
        return parseArrayEnvelope(text, "schedules")
            .map { it.toSharedSchedule() }
            .sortedBy { it.startAt }
    }

    private fun ScheduleEntity.toApiJson(
        recurrenceRule: ScheduleRecurrenceRuleEntity?,
        recurrenceExceptions: List<ScheduleRecurrenceExceptionEntity>,
    ): JSONObject = JSONObject()
        .put("localScheduleId", id)
        .put("title", title)
        .put("startAt", Instant.ofEpochMilli(startAt).toString())
        .put("endAt", endAt?.let { Instant.ofEpochMilli(it).toString() } ?: JSONObject.NULL)
        .put("location", location ?: JSONObject.NULL)
        .put("memo", memo ?: JSONObject.NULL)
        .put("status", status)
        .put("sourceText", sourceText ?: JSONObject.NULL)
        .put("sourceApp", sourceApp ?: JSONObject.NULL)
        .put("recurrence", recurrenceRule?.toApiJson() ?: JSONObject.NULL)
        .put(
            "recurrenceExceptions",
            JSONArray().also { array ->
                recurrenceExceptions.forEach { array.put(it.toApiJson()) }
            },
        )

    private fun request(method: String, path: String, token: String, body: JSONObject?): String {
        require(isConfigured()) { "Planner share API is not configured." }
        val connection = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15000
            readTimeout = 15000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
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
        return apiMessage ?: "Planner share API request failed ($code)"
    }

    private fun parseArrayEnvelope(text: String, key: String): List<JSONObject> {
        val trimmed = text.trim()
        val array = if (trimmed.startsWith("[")) JSONArray(trimmed) else JSONObject(trimmed).optJSONArray(key) ?: JSONArray()
        return buildList {
            for (i in 0 until array.length()) add(array.getJSONObject(i))
        }
    }

    private fun JSONObject.toShareProfile(): ShareProfile = ShareProfile(
        userId = optRequiredString("userId", "user_id"),
        publicId = optRequiredString("publicId", "public_id"),
    )

    private fun JSONObject.toShareGroup(): ShareGroup = ShareGroup(
        id = optRequiredString("groupId", "id"),
        name = optString("name", "Shared group"),
    )

    private fun JSONObject.toSharedSchedule(): SharedSchedule = SharedSchedule(
        id = optRequiredString("id"),
        title = optRequiredString("title"),
        startAt = parseInstantMillis(optRequiredString("startAt", "start_at")),
        endAt = optNullableString("endAt", "end_at")?.let { parseInstantMillis(it) },
        location = optNullableString("location"),
        status = optString("status", "planned"),
        isDummy = optBoolean("isDummy", optBoolean("is_dummy", false)),
        recurrence = optJSONObject("recurrence")?.toSharedScheduleRecurrence(),
        recurrenceExceptions = optJSONArray("recurrenceExceptions", "recurrence_exceptions").toRecurrenceExceptions(),
    )

    private fun ScheduleRecurrenceRuleEntity.toApiJson(): JSONObject = JSONObject()
        .put("frequency", frequency)
        .put("intervalWeeks", intervalWeeks)
        .put("dayOfWeek", dayOfWeek)
        .put("untilAt", untilAt?.let { Instant.ofEpochMilli(it).toString() } ?: JSONObject.NULL)
        .put("count", count ?: JSONObject.NULL)

    private fun ScheduleRecurrenceExceptionEntity.toApiJson(): JSONObject = JSONObject()
        .put("occurrenceStartAt", Instant.ofEpochMilli(occurrenceStartAt).toString())
        .put("action", action)

    private fun JSONObject.toSharedScheduleRecurrence(): SharedScheduleRecurrence = SharedScheduleRecurrence(
        frequency = optString("frequency", "weekly"),
        intervalWeeks = optInt("intervalWeeks", optInt("interval_weeks", 1)),
        dayOfWeek = optInt("dayOfWeek", optInt("day_of_week", 1)),
        untilAt = optNullableString("untilAt", "until_at")?.let { parseInstantMillis(it) },
        count = if (has("count") && !isNull("count")) optInt("count") else null,
    )

    private fun JSONArray?.toRecurrenceExceptions(): List<SharedScheduleRecurrenceException> {
        if (this == null) return emptyList()
        return buildList {
            for (i in 0 until length()) {
                val item = getJSONObject(i)
                add(
                    SharedScheduleRecurrenceException(
                        occurrenceStartAt = parseInstantMillis(item.optRequiredString("occurrenceStartAt", "occurrence_start_at")),
                        action = item.optString("action", "skip"),
                    ),
                )
            }
        }
    }

    private fun JSONObject.optRequiredString(vararg names: String): String {
        names.forEach { name ->
            optNullableString(name)?.let { return it }
        }
        error("Planner share API response is missing ${names.first()}.")
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

    private fun JSONObject.optJSONArray(vararg names: String): JSONArray? {
        names.forEach { name ->
            if (has(name) && !isNull(name)) return optJSONArray(name)
        }
        return null
    }

    private fun parseInstantMillis(value: String): Long = Instant.parse(value).toEpochMilli()
    private fun url(value: String): String = URLEncoder.encode(value, "UTF-8")
}

