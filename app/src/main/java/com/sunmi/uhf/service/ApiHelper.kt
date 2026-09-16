package com.sunmi.uhf.service

import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import org.json.JSONArray

object ApiHelper {
    private val cache = LruCache<String, CachedResponse>(20)
    private const val CACHE_DURATION_MS = 5 * 60 * 1000L

    data class CachedResponse(
        val data: String,
        val timestamp: Long
    ) {
        fun isExpired(): Boolean {
            return System.currentTimeMillis() - timestamp > CACHE_DURATION_MS
        }
    }

    suspend fun getRaw(url: String, useCache: Boolean = true): String = withContext(Dispatchers.IO) {
        val cachedData = if (useCache) {
            val cached = cache[url]
            if (cached != null && !cached.isExpired()) cached else null
        } else null

        val jsonString = cachedData?.data ?: fetchUrlInternal(url).also {
            if (useCache) cache.put(url, CachedResponse(it, System.currentTimeMillis()))
        }
        jsonString
    }

    suspend fun getJsonArray(
        url: String,
        useCache: Boolean = true,
        arrayKey: String = ""
    ): org.json.JSONArray = withContext(Dispatchers.IO) {
        val cachedData = if (useCache) {
            val cached = cache[url]
            if (cached != null && !cached.isExpired()) cached else null
        } else null
        
        val jsonString = cachedData?.data ?: fetchUrlInternal(url).also {
            if (useCache) cache.put(url, CachedResponse(it, System.currentTimeMillis()))
        }

        // Trim to avoid leading/trailing whitespace/BOM
        val trimmed = jsonString.trim()

        // If the server returned a raw JSON array (e.g. "[{...}, {...}]") parse it directly
        if (trimmed.startsWith("[")) {
            return@withContext JSONArray(trimmed)
        }

        // If it's an object, attempt to extract the array by key (or 'data' by default)
        if (trimmed.startsWith("{")) {
            val jsonObject = JSONObject(trimmed)
            if (arrayKey.isEmpty()) {
                // common keys that may contain arrays
                return@withContext jsonObject.optJSONArray("data")
                    ?: jsonObject.optJSONArray("assets")
                    ?: JSONArray()
            } else {
                return@withContext jsonObject.optJSONArray(arrayKey) ?: JSONArray()
            }
        }

        // If response contains extra text around JSON, try to find a JSON array substring
        val arrayStart = jsonString.indexOf('[')
        val arrayEnd = jsonString.lastIndexOf(']')
        if (arrayStart >= 0 && arrayEnd > arrayStart) {
            val sub = jsonString.substring(arrayStart, arrayEnd + 1).trim()
            try {
                return@withContext JSONArray(sub)
            } catch (e: Exception) {
                // fall through to error
            }
        }

        throw Exception("Invalid JSON response: expected array or object containing array")
    }

    suspend fun getJsonObject(
        url: String,
        useCache: Boolean = true
    ): JSONObject = withContext(Dispatchers.IO) {
        val cachedData = if (useCache) {
            val cached = cache[url]
            if (cached != null && !cached.isExpired()) cached else null
        } else null
        
        val jsonString = cachedData?.data ?: fetchUrlInternal(url).also {
            if (useCache) cache.put(url, CachedResponse(it, System.currentTimeMillis()))
        }

        // Trim and try to parse as object first
        val trimmed = jsonString.trim()
        if (trimmed.startsWith("{")) {
            return@withContext JSONObject(trimmed)
        }

        // If response is a plain array but caller expects an object, wrap it into an object under 'data'
        if (trimmed.startsWith("[")) {
            val arr = JSONArray(trimmed)
            val wrapper = JSONObject()
            wrapper.put("data", arr)
            return@withContext wrapper
        }

        // Try to extract a JSON object substring if there is surrounding text
        val start = jsonString.indexOf('{')
        val end = jsonString.lastIndexOf('}')
        if (start >= 0 && end > start) {
            try {
                return@withContext JSONObject(jsonString.substring(start, end + 1))
            } catch (e: Exception) {
                // fall through
            }
        }

        throw Exception("Invalid JSON response: expected JSON object or array")
    }

    suspend fun postJson(
        url: String,
        body: String,
        clearRelatedCache: String = ""
    ): JSONObject = withContext(Dispatchers.IO) {
        val requestBody = body.toRequestBody("application/json".toMediaType())
        
        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .build()
        
        val response = OdooApiClient.getClient().newCall(request).execute()
        val responseBody = response.body?.string() ?: throw Exception("Empty response body")
        
        if (clearRelatedCache.isNotEmpty()) {
            clearCache(clearRelatedCache)
        }
        
        JSONObject(responseBody)
    }

    private suspend fun fetchUrlInternal(url: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .build()

        val response = OdooApiClient.getClient().newCall(request).execute()
        val bodyStr = response.body?.string() ?: throw Exception("Empty response body from $url")
        // Log truncated response to help debugging in Logcat (avoid huge output)
        try {
            android.util.Log.d("ApiHelper", "fetchUrlInternal url=$url response=${bodyStr.take(1000)}${if (bodyStr.length > 1000) "..." else ""}")
        } catch (_: Exception) {
            // ignore logging failure
        }
        bodyStr
    }

    suspend fun searchRead(
        model: String,
        domain: JSONArray = JSONArray(),
        fields: List<String> = emptyList(),
        offset: Int = 0,
        limit: Int = 20,
        sort: String = "id desc"
    ): JSONArray = withContext(Dispatchers.IO) {
        val serverUrl = com.sunmi.uhf.utils.AuthUtils.getServerUrl().trim().removeSuffix("/")
        val url = "$serverUrl/web/dataset/search_read"

        val fieldsArray = JSONArray()
        fields.forEach { fieldsArray.put(it) }

        val params = JSONObject().apply {
            put("model", model)
            put("domain", domain)
            put("fields", fieldsArray)
            put("offset", offset)
            put("limit", limit)
            put("sort", sort)
        }

        val jsonRpcBody = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("method", "call")
            put("params", params)
            put("id", System.currentTimeMillis())
        }

        val requestBody = jsonRpcBody.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .build()

        val response = OdooApiClient.getClient().newCall(request).execute()
        val responseBody = response.body?.string() ?: throw Exception("Empty response body from $url")

        val jsonObject = JSONObject(responseBody)
        if (jsonObject.has("error")) {
            val errorObj = jsonObject.optJSONObject("error")
            val errorData = errorObj?.optJSONObject("data")
            val message = errorData?.optString("message", errorObj?.optString("message", "search_read failed"))
            throw Exception(message)
        }

        val result = jsonObject.optJSONObject("result")
        return@withContext result?.optJSONArray("records") ?: JSONArray()
    }

    suspend fun callKw(
        model: String,
        method: String,
        args: JSONArray = JSONArray(),
        kwargs: JSONObject = JSONObject()
    ): Any? = withContext(Dispatchers.IO) {
        val serverUrl = com.sunmi.uhf.utils.AuthUtils.getServerUrl().trim().removeSuffix("/")
        val url = "$serverUrl/web/dataset/call_kw"

        val params = JSONObject().apply {
            put("model", model)
            put("method", method)
            put("args", args)
            put("kwargs", kwargs)
        }

        val jsonRpcBody = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("method", "call")
            put("params", params)
            put("id", System.currentTimeMillis())
        }

        val requestBody = jsonRpcBody.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .build()

        val response = OdooApiClient.getClient().newCall(request).execute()
        val responseBody = response.body?.string() ?: throw Exception("Empty response body from $url")

        val jsonObject = JSONObject(responseBody)
        if (jsonObject.has("error")) {
            val errorObj = jsonObject.optJSONObject("error")
            val errorData = errorObj?.optJSONObject("data")
            val message = errorData?.optString("message", errorObj?.optString("message", "call_kw failed"))
            throw Exception(message)
        }

        return@withContext jsonObject.opt("result")
    }

    fun clearCache(pattern: String = "") {
        if (pattern.isEmpty()) {
            cache.evictAll()
        } else {
            cache.remove(pattern)
        }
    }
}
