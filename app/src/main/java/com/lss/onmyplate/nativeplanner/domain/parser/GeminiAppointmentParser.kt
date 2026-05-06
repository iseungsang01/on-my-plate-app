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
) {
    suspend fun parse(rawText: String, receivedAt: Long): AppointmentParseResult? {
        if (apiKey.isBlank()) return null
        return runCatching {
            withContext(Dispatchers.IO) {
                val responseText = post(rawText, receivedAt)
                parseResponse(responseText)
            }
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
            {"title":"string","start_at_epoch_millis":number|null,"end_at_epoch_millis":number|null,"location":"string|null","confidence":0.0}
            Rules:
            - If month/day has no year, choose the next upcoming date from the received time.
            - For Korean evening/저녁, use PM. Example 저녁 7시 = 19:00.
            - Keep title short and human readable.
            - Do not invent location if absent.

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
        return AppointmentParseResult(
            title = json.optString("title").takeIf { it.isNotBlank() } ?: "약속",
            startAt = startAt,
            endAt = json.optNullableLong("end_at_epoch_millis"),
            location = json.optString("location").takeIf { it.isNotBlank() && it != "null" },
            confidence = json.optDouble("confidence", if (startAt != null) 0.85 else 0.5).toFloat().coerceIn(0f, 1f),
            timeConfidence = if (startAt != null) TimeConfidence.High else TimeConfidence.Low,
        )
    }

    private fun JSONObject.optNullableLong(name: String): Long? =
        if (isNull(name) || !has(name)) null else optLong(name)
}
