package com.sunmi.uhf

import android.content.Intent
import android.widget.ArrayAdapter
import androidx.lifecycle.ViewModelProvider
import com.sunmi.uhf.base.BaseActivity
import com.sunmi.uhf.databinding.ActivityLoginBinding
import com.sunmi.uhf.service.OdooApiClient
import com.sunmi.uhf.utils.SharedPreference
import com.sunmi.uhf.viewmodel.LoginViewModel

class LoginActivity : BaseActivity<ActivityLoginBinding>() {

    private lateinit var viewModel: LoginViewModel

    override fun getLayoutResource() = R.layout.activity_login

    override fun getContainId() = 0

    override fun initVM() {
        viewModel = ViewModelProvider(this).get(LoginViewModel::class.java)
        binding.viewModel = viewModel
    }

    override fun initView() {
        checkExistingSession()

        val pref = App.getPref()
        val savedUrl = pref.getParam("login_url", "")
        val initialUrl = if (savedUrl.isNotBlank()) {
            savedUrl
        } else if (BuildConfig.SERVER_URL.isNotBlank()) {
            BuildConfig.SERVER_URL
        } else {
            "https://bhsglobal.hashmicro.co"
        }

        if (binding.etProjectUrl.text.isNullOrBlank() && initialUrl.isNotBlank()) {
            binding.etProjectUrl.setText(initialUrl)
        }

        val savedUsername = pref.getParam("login_username", "")
        if (binding.etUsername.text.isNullOrBlank() && savedUsername.isNotBlank()) {
            binding.etUsername.setText(savedUsername)
        }

        // Auto fetch databases if URL is already populated
        val currentUrl = binding.etProjectUrl.text.toString().trim()
        if (currentUrl.isNotEmpty()) {
            viewModel.fetchDatabases(currentUrl)
        }

        binding.etProjectUrl.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val url = binding.etProjectUrl.text.toString().trim()
                if (url.isNotEmpty() && (viewModel.databases.value == null || viewModel.databases.value!!.isEmpty())) {
                    viewModel.fetchDatabases(url)
                }
            }
        }

        binding.btnFetchDatabases.setOnClickListener {
            val url = binding.etProjectUrl.text.toString().trim()
            if (url.isEmpty()) {
                showToast("Please enter project URL")
                return@setOnClickListener
            }
            viewModel.fetchDatabases(url)
        }

        binding.btnLogin.setOnClickListener {
            performLogin()
        }
    }

    private fun checkExistingSession() {
        val pref = App.getPref()
        val isLoggedIn = pref.getParam("is_logged_in", false)

        if (isLoggedIn) {
            navigateToMainActivity()
        }
    }

    override fun initData() {
    }

    override fun onPortrait() {
    }

    override fun onLandScape() {
    }

    override fun initBus() {
        viewModel.databases.observe(this) { databases ->
            if (databases.isNotEmpty()) {
                val dbNames = databases.map { it.displayName }
                val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, dbNames)
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                binding.spinnerDatabase.adapter = adapter

                val savedDb = App.getPref().getParam("login_database", "")
                val targetIndex = if (savedDb.isNotBlank()) dbNames.indexOf(savedDb) else 0
                if (targetIndex >= 0) {
                    binding.spinnerDatabase.setSelection(targetIndex)
                }
            }
        }

        viewModel.loginResponse.observe(this) { response ->
            if (response != null) {
                saveLoginSession(response)
                navigateToMainActivity()
            }
        }

        viewModel.errorMessage.observe(this) { error ->
            if (error.isNotEmpty()) {
                binding.tvErrorMessage.text = error
            }
        }

        viewModel.isLoading.observe(this) { isLoading ->
            if (isLoading) {
                showDialog()
            } else {
                hideDialog()
            }
        }
    }

    private fun performLogin() {
        val url = binding.etProjectUrl.text.toString().trim()
        val username = binding.etUsername.text.toString().trim()
        val password = binding.etPassword.text.toString().trim()
        var database = binding.spinnerDatabase.selectedItem?.toString() ?: ""

        if (database.isEmpty()) {
            val savedDb = App.getPref().getParam("login_database", "")
            if (savedDb.isNotEmpty()) {
                database = savedDb
            }
        }

        when {
            url.isEmpty() -> showToast("Please enter project URL")
            username.isEmpty() -> showToast("Please enter username")
            password.isEmpty() -> showToast("Please enter password")
            else -> {
                binding.tvErrorMessage.text = ""
                viewModel.login(url, username, password, database)
            }
        }
    }

    private fun saveLoginSession(response: com.sunmi.uhf.bean.LoginResponse) {
        val pref = App.getPref()
        pref.setParam("login_uid", response.uid)
        pref.setParam("login_session_id", response.sessionId)
        pref.setParam("login_database", response.database)
        pref.setParam("login_username", response.username)
        pref.setParam("login_url", response.url)
        pref.setParam("is_logged_in", true)
        OdooApiClient.refreshClient()
    }

    private fun navigateToMainActivity() {
        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }
}
