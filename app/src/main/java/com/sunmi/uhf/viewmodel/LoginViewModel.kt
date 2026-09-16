package com.sunmi.uhf.viewmodel

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.sunmi.uhf.base.BaseViewModel
import com.sunmi.uhf.bean.LoginRequest
import com.sunmi.uhf.bean.LoginResponse
import com.sunmi.uhf.bean.OdooDatabase
import com.sunmi.uhf.service.OdooAuthService
import com.sunmi.uhf.utils.LogUtils

class LoginViewModel : BaseViewModel() {

    private val authService = OdooAuthService()

    private val _databases = MutableLiveData<List<OdooDatabase>>()
    val databases: LiveData<List<OdooDatabase>> get() = _databases

    private val _loginResponse = MutableLiveData<LoginResponse>()
    val loginResponse: LiveData<LoginResponse> get() = _loginResponse

    private val _isLoading = MutableLiveData<Boolean>()
    val isLoading: LiveData<Boolean> get() = _isLoading

    private val _errorMessage = MutableLiveData<String>()
    val errorMessage: LiveData<String> get() = _errorMessage

    fun fetchDatabases(url: String, silent: Boolean = false) {
        launch {
            try {
                if (!silent) _isLoading.postValue(true)
                val dbList = authService.getDatabases(url)
                _databases.postValue(dbList)
                if (!silent) _errorMessage.postValue("")
            } catch (e: Exception) {
                LogUtils.e("LoginVM", "Error fetching databases: ${e.message}")
                if (!silent) {
                    _errorMessage.postValue(e.message ?: "Failed to fetch databases")
                }
                _databases.postValue(emptyList())
            } finally {
                if (!silent) _isLoading.postValue(false)
            }
        }
    }

    fun login(url: String, username: String, password: String, database: String) {
        launch {
            try {
                _isLoading.postValue(true)
                var db = database.trim()
                if (db.isEmpty()) {
                    val dbList = authService.getDatabases(url)
                    if (dbList.isNotEmpty()) {
                        _databases.postValue(dbList)
                        db = dbList[0].name
                    }
                }
                if (db.isEmpty()) {
                    throw Exception("Please select a database")
                }
                val request = LoginRequest(
                    url = url,
                    username = username,
                    password = password,
                    database = db
                )
                val response = authService.login(request)
                _loginResponse.postValue(response)
                _errorMessage.postValue("")
            } catch (e: Exception) {
                LogUtils.e("LoginVM", "Error logging in: ${e.message}")
                _errorMessage.postValue(e.message ?: "Login failed")
                _loginResponse.postValue(null)
            } finally {
                _isLoading.postValue(false)
            }
        }
    }
}
