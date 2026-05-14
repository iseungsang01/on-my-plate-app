package com.lss.onmyplate.nativeplanner.domain.parser

import com.lss.onmyplate.nativeplanner.domain.model.AppointmentParseResult
import com.lss.onmyplate.nativeplanner.domain.model.TimeConfidence
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.ZoneId

class GeminiAppointmentParser(
    private val apiKey: String,
    private val model: String,
    private val baseUrl: String,
    private val zoneId: ZoneId = ZoneId.of("Asia/Seoul"),
    private val diagnostics: ((String, Throwable?) -> Unit)? = null,
) : AppointmentLlmParser {
    override suspend fun parse(rawText: String, receivedAt: Long): AppointmentParseResult? {
        if (apiKey.isBlank()) {
            diagnostics?.invoke("Gemini parser skipped because apiKey is blank.", null)
            return null
        }
        return runCatching {
            withContext(Dispatchers.IO) {
                val responseText = post(rawText, receivedAt)
                parseResponse(responseText)
            }
        }.onFailure { error ->
            diagnostics?.invoke("Gemini parser failed. textLength=${rawText.length}, model=$model, baseUrlConfigured=${baseUrl.isNotBlank()}", error)
        }.getOrNull()
    }

    private fun post(rawText: String, receivedAt: Long): String {
        val endpoint = "${baseUrl.trimEnd('/')}/models/$model:generateContent?key=$apiKey"
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 20_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }

        val payload = JSONObject()
            .put(
                "contents",
                JSONArray().put(
                    JSONObject().put(
                        "parts",
                        JSONArray().put(JSONObject().put("text", prompt(rawText, receivedAt))),
                    ),
                ),
            )
            .put(
                "generationConfig",
                JSONObject()
                    .put("temperature", 0)
                    .put("responseMimeType", "application/json"),
            )
            .toString()

        connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
        val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
        val body = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        if (connection.responseCode !in 200..299) error("Gemini parse failed: ${connection.responseCode} $body")
        return body
    }

    private fun prompt(rawText: String, receivedAt: Long): String {
        val received = Instant.ofEpochMilli(receivedAt).atZone(zoneId)
        return """
            You extract one calendar appointment from Korean shared text.
            Current received time is $received in Asia/Seoul. Resolve relative dates from it.
            Return only JSON with this shape:
            {"title":"string|null","start_at_epoch_millis":number|null,"end_at_epoch_millis":number|null,"location":"string|null","confidence":0.0}
            Rules:
            - Extract exactly one appointment. If multiple appointments appear, choose the most concrete one.
            - If month/day has no year, choose the next upcoming date from the received time.
            - Interpret Korean afternoon, evening, dinner, and night wording as PM when appropriate.
            - If a start time exists and an end time is not explicit, set end_at_epoch_millis to exactly one hour after start_at_epoch_millis.
            - Do not invent location if absent.
            - Title must summarize parsed date, time range, and location only. Format: M/d HHmm-HHmm location. Omit the trailing location when absent.
            - If start_at_epoch_millis is null, title must be null.
            - Use confidence 0.0 to 1.0 based on how explicit the appointment details are.

            Examples:
            Text: 5/13 16:00-17:00 Gangnam meeting
            JSON: {"title":"5/13 1600-1700 Gangnam","start_at_epoch_millis":1778655600000,"end_at_epoch_millis":1778659200000,"location":"Gangnam","confidence":0.95}

            Text: tomorrow 2 PM cafe
            JSON: {"title":"5/8 1400-1500 cafe","start_at_epoch_millis":1778216400000,"end_at_epoch_millis":1778220000000,"location":"cafe","confidence":0.9}

            Text: next Friday 10 AM call
            JSON: {"title":"5/15 1000-1100","start_at_epoch_millis":1778781600000,"end_at_epoch_millis":1778785200000,"location":null,"confidence":0.8}

            Text:
            $rawText
        """.trimIndent()
    }

    private fun parseResponse(responseText: String): AppointmentParseResult? {
        val root = JSONObject(responseText)
        val text = root
            .optJSONArray("candidates")
            ?.optJSONObject(0)
            ?.optJSONObject("content")
            ?.optJSONArray("parts")
            ?.optJSONObject(0)
            ?.optString("text")
            ?.trim()
            ?.removePrefix("```json")
            ?.removePrefix("```")
            ?.removeSuffix("```")
            ?.trim()
            ?: return null
        val json = JSONObject(text)
        val startAt = json.optNullableLong("start_at_epoch_millis")
        val location = json.optString("location").takeIf { it.isNotBlank() && it != "null" }
        val endAt = startAt?.let { AppointmentTitleFormatter.defaultEnd(it, json.optNullableLong("end_at_epoch_millis")) }
        val title = if (startAt == null) {
            ""
        } else {
            json.optString("title")
                .takeIf { it.isNotBlank() && it != "null" }
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

    private fun JSONObject.optNullableLong(name: String): Long? =
        if (isNull(name) || !has(name)) null else optLong(name)
}
