package com.sunmi.uhf.utils

import android.content.Intent
import com.sunmi.uhf.App
import com.sunmi.uhf.BuildConfig
import com.sunmi.uhf.LoginActivity

object AuthUtils {

    const val DEFAULT_SERVER_URL = "https://bhsglobal.hashmicro.co"
    const val DEFAULT_DATABASE = "bhs-live"

    fun logout() {
        val pref = App.getPref()
        pref.clearPreference("login_uid")
        pref.clearPreference("login_session_id")
        pref.clearPreference("login_username")
        pref.clearPreference("is_logged_in")
        // Always ensure default URL & database remain set
        pref.setParam("login_url", DEFAULT_SERVER_URL)
        pref.setParam("login_database", DEFAULT_DATABASE)
    }

    fun isLoggedIn(): Boolean {
        return App.getPref().getParam("is_logged_in", false)
    }

    fun getLoginInfo(): Map<String, String> {
        val pref = App.getPref()
        val url = pref.getParam("login_url", "").ifBlank { DEFAULT_SERVER_URL }
        val db = pref.getParam("login_database", "").ifBlank { DEFAULT_DATABASE }
        return mapOf(
            "uid" to pref.getParam("login_uid", 0).toString(),
            "sessionId" to (pref.getParam("login_session_id", "") ?: ""),
            "database" to db,
            "username" to (pref.getParam("login_username", "") ?: ""),
            "url" to url
        )
    }

    fun getApiBaseUrl(): String {
        return "${getServerUrl()}/api"
    }

    fun getServerUrl(): String {
        val pref = App.getPref()
        val savedUrl = pref.getParam("login_url", "")
        return if (savedUrl.isNotBlank() && savedUrl != "false" && savedUrl != "-") {
            savedUrl
        } else {
            DEFAULT_SERVER_URL
        }
    }

    fun getDatabase(): String {
        val pref = App.getPref()
        val savedDb = pref.getParam("login_database", "")
        return if (savedDb.isNotBlank() && savedDb != "false" && savedDb != "-") {
            savedDb
        } else {
            DEFAULT_DATABASE
        }
    }

    fun goToLoginActivity() {
        logout()
        val intent = Intent(App.mContext, LoginActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        App.mContext.startActivity(intent)
    }
}
