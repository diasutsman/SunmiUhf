package com.sunmi.uhf.service

import android.util.Log
import com.sunmi.uhf.utils.AuthUtils
import okhttp3.Cookie
import okhttp3.Interceptor
import okhttp3.Response

class OdooHttpInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        
        val loginInfo = AuthUtils.getLoginInfo()
        val sessionId = loginInfo["sessionId"] ?: ""
        
        Log.d("OdooHttpInterceptor", "URL: ${originalRequest.url}")
        Log.d("OdooHttpInterceptor", "Session: ${sessionId.take(10)}...")
        
        val requestBuilder = originalRequest.newBuilder()
        
        if (originalRequest.body != null) {
            requestBuilder.addHeader("Content-Type", "application/json")
        }
        
        val urlString = originalRequest.url.toString()
        val isAuthEndpoint = urlString.contains("/web/session/authenticate") ||
                             urlString.contains("/web/database/list") ||
                             urlString.contains("/xmlrpc/2/db")

        if (sessionId.isNotEmpty() && !isAuthEndpoint) {
            requestBuilder.addHeader("Cookie", "session_id=$sessionId")
            Log.d("OdooHttpInterceptor", "Session cookie injected")
        }
        
        return chain.proceed(requestBuilder.build())
    }
}
