package io.issuetracker.sdk.internal

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Thin wrapper around HttpURLConnection that matches the Firebase
 * Functions callable wire format — request body wrapped in `{"data": …}`,
 * response body wrapped in `{"result": …}`. Unauthenticated; SDK calls
 * use the API key as their only auth.
 */
internal object ApiClient {
    suspend fun call(
        endpoint: String,
        function: String,
        payload: JSONObject,
    ): JSONObject = withContext(Dispatchers.IO) {
        val url = URL("${endpoint.trimEnd('/')}/$function")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            connectTimeout = 30_000
            readTimeout = 60_000
        }
        try {
            val body = JSONObject().put("data", payload).toString().toByteArray(Charsets.UTF_8)
            conn.outputStream.use { it.write(body) }
            val status = conn.responseCode
            val stream = if (status >= 400) conn.errorStream else conn.inputStream
            val responseText = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            if (status >= 400) {
                val errMsg = try {
                    JSONObject(responseText).getJSONObject("error").getString("message")
                } catch (_: Exception) {
                    "HTTP $status"
                }
                throw ApiException(status, errMsg)
            }
            JSONObject(responseText).getJSONObject("result")
        } finally {
            conn.disconnect()
        }
    }
}

internal class ApiException(val status: Int, message: String) : RuntimeException(message)
