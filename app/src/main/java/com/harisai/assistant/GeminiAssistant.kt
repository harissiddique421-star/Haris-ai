package com.harisai.assistant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object GeminiAssistant {
    suspend fun reply(text: String): String = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL("${BuildConfig.API_BASE_URL}/api/assistant/chat").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 35_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
            }
            val payload = JSONObject().put("text", text.take(1500)).toString()
            connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = JSONObject(response.ifBlank { "{}" })
            if (code !in 200..299) {
                throw IllegalStateException(json.optString("error", "Server error ($code)"))
            }
            json.optString("reply").ifBlank { "Mujhe jawab nahi mila. Dobara koshish karo." }
        } catch (error: Exception) {
            "Gemini se connection nahi ho saka: ${error.message ?: "network error"}. Internet aur server setup check karo."
        } finally {
            connection?.disconnect()
        }
    }
}
