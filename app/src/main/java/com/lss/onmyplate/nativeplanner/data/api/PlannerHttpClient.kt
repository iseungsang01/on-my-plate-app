package com.lss.onmyplate.nativeplanner.data.api

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

class PlannerHttpClient(
    rawBaseUrl: String,
    private val notConfiguredMessage: String = "Planner API is not configured.",
    private val onUnauthorized: (() -> Unit)? = null,
) {
    private val baseUrl = rawBaseUrl.trim().trimEnd('/')

    fun isConfigured(): Boolean = baseUrl.isNotBlank()

    fun request(
        method: String,
        path: String,
        token: String? = null,
        body: JSONObject? = null,
    ): String {
        require(isConfigured()) { notConfiguredMessage }
        val connection = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MILLIS
            readTimeout = READ_TIMEOUT_MILLIS
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

        if (code !in 200..299) {
            if (code == HttpURLConnection.HTTP_UNAUTHORIZED) onUnauthorized?.invoke()
            error(apiErrorMessage(code, text))
        }

        return text.ifBlank { "{}" }
    }

    fun parseArrayEnvelope(text: String, key: String): List<JSONObject> {
        val trimmed = text.trim()
        val array = if (trimmed.startsWith("[")) JSONArray(trimmed) else JSONObject(trimmed).optJSONArray(key) ?: JSONArray()
        return buildList {
            for (index in 0 until array.length()) add(array.getJSONObject(index))
        }
    }

    private fun apiErrorMessage(code: Int, text: String): String {
        val apiMessage = runCatching {
            val json = JSONObject(text)
            json.optString("message").takeIf { it.isNotBlank() }
                ?: json.optString("error").takeIf { it.isNotBlank() }
        }.getOrNull()
        return apiMessage ?: "Planner API request failed ($code)"
    }

    companion object {
        const val CONNECT_TIMEOUT_MILLIS = 15_000
        const val READ_TIMEOUT_MILLIS = 15_000
    }
}
