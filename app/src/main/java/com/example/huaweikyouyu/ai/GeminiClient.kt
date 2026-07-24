package com.example.huaweikyouyu.ai

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

object GeminiClient {
    private const val TAG = "GeminiClient"
    private const val MODEL_NAME = "gemini-3.5-flash"
    private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models/$MODEL_NAME:generateContent"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Generate content from Gemini API
     *
     * @param apiKey Gemini API Key
     * @param prompt The prompt to send to Gemini
     * @param callback Callback function with result (Success, Result string / Error message)
     */
    fun generateContent(apiKey: String, prompt: String, callback: (Boolean, String) -> Unit) {
        if (apiKey.isEmpty()) {
            callback(false, "API Key is empty")
            return
        }

        val url = "$BASE_URL?key=$apiKey"
        val mediaType = "application/json; charset=utf-8".toMediaType()

        try {
            // Build Request Body using org.json
            val textPart = JSONObject().put("text", prompt)
            val partsArray = JSONArray().put(textPart)
            val contentObj = JSONObject().put("parts", partsArray)
            val contentsArray = JSONArray().put(contentObj)
            val requestBodyJson = JSONObject().put("contents", contentsArray)

            val body = requestBodyJson.toString().toRequestBody(mediaType)
            val request = Request.Builder()
                .url(url)
                .post(body)
                .build()

            client.newCall(request).enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: IOException) {
                    Log.e(TAG, "Request failed", e)
                    callback(false, "Network error: ${e.localizedMessage}")
                }

                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    response.use {
                        if (!response.isSuccessful) {
                            val errorBody = response.body?.string() ?: "Unknown error"
                            Log.e(TAG, "Unsuccessful response: $errorBody")
                            callback(false, "API error (${response.code}): $errorBody")
                            return
                        }

                        val bodyString = response.body?.string()
                        if (bodyString.isNullOrEmpty()) {
                            callback(false, "Empty response body")
                            return
                        }

                        try {
                            val jsonResponse = JSONObject(bodyString)
                            val candidates = jsonResponse.getJSONArray("candidates")
                            if (candidates.length() > 0) {
                                val candidate = candidates.getJSONObject(0)
                                val content = candidate.getJSONObject("content")
                                val parts = content.getJSONArray("parts")
                                if (parts.length() > 0) {
                                    val text = parts.getJSONObject(0).getString("text")
                                    callback(true, text)
                                    return
                                }
                            }
                            callback(false, "Could not parse response structure")
                        } catch (e: Exception) {
                            Log.e(TAG, "JSON Parsing error", e)
                            callback(false, "Parsing error: ${e.localizedMessage}")
                        }
                    }
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Error initiating request", e)
            callback(false, "Initialization error: ${e.localizedMessage}")
        }
    }
}
