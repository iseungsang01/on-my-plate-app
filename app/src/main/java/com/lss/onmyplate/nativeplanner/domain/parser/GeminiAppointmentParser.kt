package com.lss.onmyplate.nativeplanner.domain.parser

import com.lss.onmyplate.nativeplanner.domain.model.AppointmentParseResult
import com.lss.onmyplate.nativeplanner.data.api.PlannerHttpClient
import com.lss.onmyplate.nativeplanner.domain.model.TimeConfidence
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.ZoneId

class GeminiAppointmentParser(
    private val apiBaseUrl: String,
    private val sessionTokenProvider: () -> String?,
    private val zoneId: ZoneId = ZoneId.of("Asia/Seoul"),
    private val diagnostics: ((String, Throwable?) -> Unit)? = null,
) : AppointmentLlmParser {
    override suspend fun parse(rawText: String, receivedAt: Long): AppointmentParseResult? {
        val cleanBaseUrl = apiBaseUrl.trim().trimEnd('/')
        val token = sessionTokenProvider()?.takeIf { it.isNotBlank() }
        if (cleanBaseUrl.isBlank()) {
            diagnostics?.invoke("Gemini proxy parser skipped because apiBaseUrl is blank.", null)
            return null
        }
        if (token == null) {
            diagnostics?.invoke("Gemini proxy parser skipped because session token is blank.", null)
            return null
        }

        return runCatching {
            withContext(Dispatchers.IO) {
                val responseText = post(cleanBaseUrl, token, rawText, receivedAt)
                parseProxyResponse(responseText)
            }
        }.onFailure { error ->
            diagnostics?.invoke(
                "Gemini proxy parser failed. textLength=${rawText.length}, apiBaseUrlConfigured=${cleanBaseUrl.isNotBlank()}",
                error,
            )
        }.getOrNull()
    }

    private fun post(baseUrl: String, token: String, rawText: String, receivedAt: Long): String {
        val payload = JSONObject()
            .put("rawText", rawText)
            .put("receivedAt", receivedAt)

        return PlannerHttpClient(
            rawBaseUrl = baseUrl,
            notConfiguredMessage = "Gemini proxy parser API is not configured.",
        ).request(
            method = "POST",
            path = "/api/parser/appointment",
            token = token,
            body = payload,
        )
    }

    private fun parseProxyResponse(responseText: String): AppointmentParseResult? {
        val json = JSONObject(responseText)
        val startAt = json.optNullableLong("start_at_epoch_millis", "startAt")
        val location = json.optNullableString("location")
        val endAt = startAt?.let {
            AppointmentTitleFormatter.defaultEnd(
                it,
                json.optNullableLong("end_at_epoch_millis", "endAt"),
            )
        }
        val title = if (startAt == null) {
            ""
        } else {
            json.optNullableString("title")
                ?: AppointmentTitleFormatter.format(startAt, endAt, location, zoneId)
        }
        return AppointmentParseResult(
            title = title,
            startAt = startAt,
            endAt = endAt,
            location = location,
            confidence = json.optDouble("confidence", if (startAt != null) 0.85 else 0.5).toFloat().coerceIn(0f, 1f),
            timeConfidence = if (startAt != null) TimeConfidence.High else TimeConfidence.Low,
        )
    }

    private fun JSONObject.optNullableLong(vararg names: String): Long? {
        names.forEach { name ->
            if (has(name) && !isNull(name)) {
                return when (val value = get(name)) {
                    is Number -> value.toLong()
                    is String -> value.toLongOrNull()
                    else -> null
                }
            }
        }
        return null
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
