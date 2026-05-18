package com.lss.onmyplate.nativeplanner.data.supabase

import android.content.Context
import com.lss.onmyplate.nativeplanner.BuildConfig
import com.lss.onmyplate.nativeplanner.data.api.PlannerHttpClient
import com.lss.onmyplate.nativeplanner.data.entity.ScheduleEntity
import com.lss.onmyplate.nativeplanner.data.entity.ScheduleRecurrenceExceptionEntity
import com.lss.onmyplate.nativeplanner.data.entity.ScheduleRecurrenceRuleEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
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
    val interval: Int,
    val dayOfWeek: Int?,
    val dayOfMonth: Int?,
    val untilAt: Long?,
    val count: Int?,
)
data class SharedScheduleRecurrenceException(
    val occurrenceStartAt: Long,
    val action: String,
)

private class PlannerShareApiClient(rawBaseUrl: String) {
    private val http = PlannerHttpClient(
        rawBaseUrl = rawBaseUrl,
        notConfiguredMessage = "Planner share API is not configured.",
    )

    fun isConfigured(): Boolean = http.isConfigured()

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

    private fun request(method: String, path: String, token: String, body: JSONObject?): String =
        http.request(method, path, token = token, body = body)

    private fun parseArrayEnvelope(text: String, key: String): List<JSONObject> {
        return http.parseArrayEnvelope(text, key)
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
        .put("interval", interval)
        .put("intervalWeeks", if (frequency == "weekly") interval else JSONObject.NULL)
        .put("dayOfWeek", dayOfWeek ?: JSONObject.NULL)
        .put("dayOfMonth", dayOfMonth ?: JSONObject.NULL)
        .put("untilAt", untilAt?.let { Instant.ofEpochMilli(it).toString() } ?: JSONObject.NULL)
        .put("count", count ?: JSONObject.NULL)

    private fun ScheduleRecurrenceExceptionEntity.toApiJson(): JSONObject = JSONObject()
        .put("occurrenceStartAt", Instant.ofEpochMilli(occurrenceStartAt).toString())
        .put("action", action)

    private fun JSONObject.toSharedScheduleRecurrence(): SharedScheduleRecurrence = SharedScheduleRecurrence(
        frequency = optString("frequency", "weekly"),
        interval = optInt("interval", optInt("intervalWeeks", optInt("interval_weeks", 1))),
        dayOfWeek = optNullableInt("dayOfWeek", "day_of_week"),
        dayOfMonth = optNullableInt("dayOfMonth", "day_of_month"),
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

    private fun JSONObject.optNullableInt(vararg names: String): Int? {
        names.forEach { name ->
            if (has(name) && !isNull(name)) return optInt(name)
        }
        return null
    }

    private fun parseInstantMillis(value: String): Long = Instant.parse(value).toEpochMilli()
    private fun url(value: String): String = URLEncoder.encode(value, "UTF-8")
}

