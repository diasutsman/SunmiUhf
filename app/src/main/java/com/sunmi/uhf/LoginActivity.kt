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
        val forcedUrl = com.sunmi.uhf.utils.AuthUtils.DEFAULT_SERVER_URL // "https://bhsglobal.hashmicro.co"
        val forcedDb = com.sunmi.uhf.utils.AuthUtils.DEFAULT_DATABASE   // "bhs-live"

        val savedUrl = pref.getParam("login_url", "")
        val initialUrl = if (savedUrl.isNotBlank() && savedUrl != "false" && savedUrl.contains("http")) {
            savedUrl
        } else {
            forcedUrl
        }

        binding.etProjectUrl.setText(initialUrl)
        binding.etProjectUrl.setSelection(binding.etProjectUrl.text?.length ?: 0)

        val savedDb = pref.getParam("login_database", "")
        val initialDb = if (savedDb.isNotBlank() && savedDb != "false") {
            savedDb
        } else {
            forcedDb
        }

        binding.etDatabase.setText(initialDb)
        binding.etDatabase.setSelection(binding.etDatabase.text?.length ?: 0)

        // Always ensure preferences hold the valid initial values
        pref.setParam("login_url", initialUrl)
        pref.setParam("login_database", initialDb)

        // Pre-populate database dropdown adapter
        val initialAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, listOf(initialDb))
        binding.etDatabase.setAdapter(initialAdapter)

        // Silent auto-fetch databases in the background without blocking screen
        val currentUrl = binding.etProjectUrl.text.toString().trim()
        if (currentUrl.isNotEmpty()) {
            viewModel.fetchDatabases(currentUrl, silent = true)
        }

        binding.etProjectUrl.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val url = binding.etProjectUrl.text.toString().trim()
                if (url.isNotEmpty() && (viewModel.databases.value == null || viewModel.databases.value!!.isEmpty())) {
                    viewModel.fetchDatabases(url, silent = true)
                }
            }
        }

        binding.btnFetchDatabases.setOnClickListener {
            val url = binding.etProjectUrl.text.toString().trim()
            if (url.isEmpty()) {
                binding.etProjectUrl.setText(forcedUrl)
                viewModel.fetchDatabases(forcedUrl, silent = false)
                return@setOnClickListener
            }
            viewModel.fetchDatabases(url, silent = false)
        }

        binding.etDatabase.setOnClickListener {
            binding.etDatabase.showDropDown()
        }

        var isPasswordVisible = false
        binding.btnTogglePassword.setOnClickListener {
            isPasswordVisible = !isPasswordVisible
            if (isPasswordVisible) {
                binding.etPassword.transformationMethod = android.text.method.HideReturnsTransformationMethod.getInstance()
                binding.btnTogglePassword.setImageResource(R.drawable.ic_visibility)
            } else {
                binding.etPassword.transformationMethod = android.text.method.PasswordTransformationMethod.getInstance()
                binding.btnTogglePassword.setImageResource(R.drawable.ic_visibility_off)
            }
            binding.etPassword.setSelection(binding.etPassword.text?.length ?: 0)
        }

        binding.btnLogin.setOnClickListener {
            performLogin()
        }
    }

    override fun onResume() {
        super.onResume()
        if (binding.etProjectUrl.text.isNullOrBlank()) {
            binding.etProjectUrl.setText(com.sunmi.uhf.utils.AuthUtils.DEFAULT_SERVER_URL)
        }
        if (binding.etDatabase.text.isNullOrBlank()) {
            binding.etDatabase.setText(com.sunmi.uhf.utils.AuthUtils.DEFAULT_DATABASE)
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
                val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, dbNames)
                binding.etDatabase.setAdapter(adapter)

                val currentDb = binding.etDatabase.text.toString().trim()
                if (currentDb.isEmpty() || !dbNames.contains(currentDb)) {
                    val savedDb = App.getPref().getParam("login_database", "")
                    val preferredDb = if (savedDb.isNotBlank() && dbNames.contains(savedDb)) {
                        savedDb
                    } else if (dbNames.contains(com.sunmi.uhf.utils.AuthUtils.DEFAULT_DATABASE)) {
                        com.sunmi.uhf.utils.AuthUtils.DEFAULT_DATABASE
                    } else {
                        dbNames.firstOrNull() ?: com.sunmi.uhf.utils.AuthUtils.DEFAULT_DATABASE
                    }
                    if (preferredDb.isNotEmpty()) {
                        binding.etDatabase.setText(preferredDb)
                        binding.etDatabase.setSelection(binding.etDatabase.text?.length ?: 0)
                    }
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
        var url = binding.etProjectUrl.text.toString().trim()
        val username = binding.etUsername.text.toString().trim()
        val password = binding.etPassword.text.toString().trim()
        var database = binding.etDatabase.text.toString().trim()

        if (url.isEmpty()) {
            url = com.sunmi.uhf.utils.AuthUtils.DEFAULT_SERVER_URL
            binding.etProjectUrl.setText(url)
        }

        if (database.isEmpty()) {
            database = com.sunmi.uhf.utils.AuthUtils.DEFAULT_DATABASE
            binding.etDatabase.setText(database)
        }

        when {
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
