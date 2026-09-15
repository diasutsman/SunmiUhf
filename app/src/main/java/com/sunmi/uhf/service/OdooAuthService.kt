package com.sunmi.uhf.service

import com.sunmi.uhf.bean.LoginRequest
import com.sunmi.uhf.bean.LoginResponse
import com.sunmi.uhf.bean.OdooDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.apache.xmlrpc.client.XmlRpcClient
import org.apache.xmlrpc.client.XmlRpcClientConfigImpl
import org.json.JSONArray
import org.json.JSONObject

class OdooAuthService {

    private fun normalizeUrl(rawUrl: String): String {
        var clean = rawUrl.trim().removeSuffix("/")
        if (!clean.startsWith("http://") && !clean.startsWith("https://")) {
            clean = "https://$clean"
        }
        return clean
    }

    suspend fun getDatabases(url: String): List<OdooDatabase> = withContext(Dispatchers.IO) {
        val cleanUrl = normalizeUrl(url)

        // 1. Try JSON-RPC /web/database/list first (standard in Odoo Web / inventory_app)
        try {
            val jsonRpcBody = JSONObject().apply {
                put("jsonrpc", "2.0")
                put("method", "call")
                put("params", JSONObject().apply {
                    put("service", "db")
                    put("method", "list")
                    put("args", JSONArray())
                })
                put("id", 1)
            }
            val requestBody = jsonRpcBody.toString().toRequestBody("application/json".toMediaType())
            val httpRequest = Request.Builder()
                .url("$cleanUrl/web/database/list")
                .post(requestBody)
                .build()

            val response = OdooApiClient.getClient().newCall(httpRequest).execute()
            val body = response.body?.string()
            if (response.isSuccessful && !body.isNullOrEmpty()) {
                val jsonResp = JSONObject(body)
                val result = jsonResp.optJSONArray("result")
                if (result != null && result.length() > 0) {
                    val dbs = mutableListOf<OdooDatabase>()
                    for (i in 0 until result.length()) {
                        val db = result.getString(i)
                        dbs.add(OdooDatabase(db, db))
                    }
                    return@withContext dbs
                }
            }
        } catch (_: Exception) {
            // Fall through to XML-RPC
        }

        // 2. Fallback to XML-RPC
        return@withContext try {
            val config = XmlRpcClientConfigImpl()
            config.serverURL = java.net.URL("$cleanUrl/xmlrpc/2/db")
            val client = XmlRpcClient()
            client.setConfig(config)

            val result = client.execute("list", arrayOf()) as Array<*>
            result.mapNotNull { dbName ->
                if (dbName is String) {
                    OdooDatabase(dbName, dbName)
                } else null
            }
        } catch (e: Exception) {
            throw Exception("Failed to fetch databases: ${e.message}")
        }
    }

    suspend fun login(request: LoginRequest): LoginResponse = withContext(Dispatchers.IO) {
        return@withContext try {
            val cleanUrl = normalizeUrl(request.url)

            // Clear old client state & cookies before authenticating
            OdooApiClient.refreshClient()
            val httpClient = OdooApiClient.getClient()
            val loginUrl = "$cleanUrl/web/session/authenticate"

            val params = JSONObject().apply {
                put("jsonrpc", "2.0")
                put("method", "call")
                put("params", JSONObject().apply {
                    put("db", request.database)
                    put("login", request.username)
                    put("password", request.password)
                })
                put("id", 1)
            }

            val requestBody = params.toString().toRequestBody("application/json".toMediaType())

            val httpRequest = Request.Builder()
                .url(loginUrl)
                .post(requestBody)
                .build()

            val response = httpClient.newCall(httpRequest).execute()
            val responseBody = response.body?.string() ?: throw Exception("Empty response body")

            val jsonResponse = JSONObject(responseBody)
            if (jsonResponse.has("error")) {
                val errorObj = jsonResponse.optJSONObject("error")
                val errorData = errorObj?.optJSONObject("data")
                val arguments = errorData?.optJSONArray("arguments")
                val argMsg = if (arguments != null && arguments.length() > 0) arguments.optString(0) else null
                val msg = argMsg
                    ?: errorData?.optString("message")
                    ?: errorObj?.optString("message")
                    ?: "Authentication failed: Invalid credentials"
                throw Exception(msg)
            }

            val result = jsonResponse.optJSONObject("result")
                ?: throw Exception("Authentication failed: Invalid credentials")

            val uid = result.optInt("uid", -1)
            if (uid <= 0) {
                throw Exception("Authentication failed: Invalid credentials")
            }

            val sessionIdFromResponse = result.optString("session_id", "")
            val sessionId = if (sessionIdFromResponse.isNotEmpty() && sessionIdFromResponse != "null") {
                sessionIdFromResponse
            } else {
                extractSessionIdFromCookie(response)
                    ?: throw Exception("No session ID received from server")
            }

            LoginResponse(
                uid = uid,
                sessionId = sessionId,
                database = request.database,
                username = request.username,
                url = cleanUrl
            )
        } catch (e: Exception) {
            throw Exception(e.message ?: "Login error")
        }
    }

    private fun extractSessionIdFromCookie(response: okhttp3.Response): String? {
        val cookies = response.headers("Set-Cookie")
        for (cookie in cookies) {
            val sessionMatch = Regex("session_id=([^;]+)").find(cookie)
            if (sessionMatch != null) {
                return sessionMatch.groupValues[1]
            }
        }
        val single = response.header("Set-Cookie") ?: return null
        val sessionMatch = Regex("session_id=([^;]+)").find(single)
        return sessionMatch?.groupValues?.get(1)
    }
}

