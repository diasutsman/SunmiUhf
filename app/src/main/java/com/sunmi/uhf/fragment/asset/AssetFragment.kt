package com.sunmi.uhf.fragment.asset

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.sunmi.uhf.R
import com.sunmi.uhf.base.BaseActivity
import com.sunmi.uhf.fragment.takeinventory.TakeInventoryFragment
import com.sunmi.uhf.utils.AuthUtils
import com.sunmi.uhf.service.ApiHelper
import android.util.Log
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.appcompat.widget.AppCompatEditText
import android.view.inputmethod.InputMethodManager

class AssetFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var adapter: AssetAdapter
    private lateinit var contentLayout: LinearLayout

    private lateinit var btnScan: FloatingActionButton
    private lateinit var editSearch: AppCompatEditText

    private var shouldRefreshOnResume = false
    private var selectedUserId: Int? = null
    private var selectedUserName: String? = null

    // Pagination state
    private var currentPage = 1
    private val pageSize = 20
    private var isLoading = false
    private var isLastPage = false

    // Track already seen item IDs to prevent duplicates when server returns repeated data
    private val seenIds = mutableSetOf<Int>()

    // Search state
    private var searchJob: Job? = null
    private var currentQuery: String = ""

    // Job for current load
    private var loadJob: Job? = null

    @SuppressLint("MissingInflatedId")
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_asset_order, container, false)
        recyclerView = view.findViewById(R.id.recyclerViewAsset)
        progressBar = view.findViewById(R.id.progressBarAsset)
        contentLayout = view.findViewById(R.id.contentLayoutAsset)
        btnScan = view.findViewById(R.id.btnScan)
        editSearch = view.findViewById(R.id.editSearchAsset)

        adapter = AssetAdapter(mutableListOf()) { item ->
            val fragment = AssetDetailFragment.newInstance(item)
            (activity as? BaseActivity<*>)?.switchFragment(
                fragment,
                addToBackStack = true,
                clearStack = false
            )
        }

        val layoutManager = LinearLayoutManager(requireContext())
        recyclerView.layoutManager = layoutManager
        recyclerView.adapter = adapter

        // pagination scroll listener
        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(rv, dx, dy)
                if (dy <= 0) return

                val visibleItemCount = layoutManager.childCount
                val totalItemCount = layoutManager.itemCount
                val firstVisibleItemPosition = layoutManager.findFirstVisibleItemPosition()

                if (!isLoading && !isLastPage) {
                    if (visibleItemCount + firstVisibleItemPosition >= totalItemCount - 3
                        && firstVisibleItemPosition >= 0
                        && totalItemCount >= pageSize
                    ) {
                        loadAssetOrders(page = currentPage + 1, query = currentQuery)
                    }
                }
            }
        })

        btnScan.setOnClickListener {
            goToTakeInventory()
        }

        // Search listener (debounced) - local filtering + refresh
        editSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val q = s?.toString()?.trim() ?: ""
                searchJob?.cancel()
                searchJob = lifecycleScope.launch {
                    delay(300)
                    if (q != currentQuery) {
                        currentQuery = q
                        // cancel any in-flight load
                        loadJob?.cancel()
                        // reload first page (to refresh data source) then apply local filter
                        loadAssetOrders(page = 1, query = currentQuery)
                    } else {
                        adapter.filter(currentQuery)
                    }
                }
            }

            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        loadAssetOrders(page = 1)
        return view
    }

    override fun onResume() {
        super.onResume()
        // Reset paging to reload and display newly confirmed assignments/transfers
        currentPage = 1
        isLastPage = false
        loadAssetOrders(page = 1, query = currentQuery)
    }

    private fun fetchAndShowUserDialog() {
        lifecycleScope.launch {
            try {
                val userArray = ApiHelper.getJsonArray(
                    "${AuthUtils.getServerUrl()}/get/users",
                    useCache = true,
                    arrayKey = "users"
                )

                val userNames = mutableListOf<String>()
                val userIds = mutableListOf<Int>()

                for (i in 0 until userArray.length()) {
                    val user = userArray.getJSONObject(i)
                    userIds.add(user.getInt("id"))
                    userNames.add(user.getString("name"))
                }

                showUserSelectionDialog(userIds, userNames)
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Error fetching users: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showUserSelectionDialog(userIds: List<Int>, userNames: List<String>) {
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, userNames)
        AlertDialog.Builder(requireContext())
            .setTitle("Select User")
            .setAdapter(adapter) { _, which ->
                selectedUserId = userIds[which]
                selectedUserName = userNames[which]
                goToTakeInventory()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun goToTakeInventory() {
        val args = Bundle().apply {
            selectedUserId?.let { putInt("selected_user_id", it) }
            putInt(TakeInventoryFragment.ARC_KEY_ASSET_ID, 1)
            selectedUserName?.let { putString("selected_user_name", it) }
        }
        val fragment = TakeInventoryFragment.newInstance(args)
        (activity as? BaseActivity<*>)?.switchFragment(
            fragment,
            addToBackStack = true,
            clearStack = false
        )
    }

    private fun loadAssetOrders(page: Int = 1, query: String = "") {
        // cancel previous load
        loadJob?.cancel()

        if (page == 1) {
            val hadFocus = editSearch.hasFocus()
            val selPos = try { editSearch.selectionStart.coerceAtLeast(0) } catch (_: Exception) { -1 }

            contentLayout.visibility = View.VISIBLE

            progressBar.visibility = View.VISIBLE
            contentLayout.isEnabled = false
            contentLayout.alpha = 0.6f

            if (hadFocus || currentQuery.isNotBlank()) {
                editSearch.post {
                    try {
                        editSearch.requestFocus()
                        if (selPos >= 0) {
                            val length = editSearch.text?.length ?: 0
                            editSearch.setSelection(selPos.coerceAtMost(length))
                        }
                        val imm = requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as InputMethodManager
                        imm.showSoftInput(editSearch, InputMethodManager.SHOW_IMPLICIT)
                    } catch (_: Exception) {}
                }
            }
            seenIds.clear()
            isLastPage = false
            currentPage = 1
        } else {
            progressBar.visibility = View.VISIBLE
        }

        isLoading = true
        loadJob = lifecycleScope.launch {
            try {
                val offset = (page - 1) * pageSize
                val AssetList = mutableListOf<AssetItem>()
                var fetchSuccess = false

                // 1. Primary approach: Use Odoo native search_read on employee.asset.transfer
                try {
                    val domain = org.json.JSONArray()
                    val records = ApiHelper.searchRead(
                        model = "employee.asset.transfer",
                        domain = domain,
                        fields = listOf("id", "name", "due_date", "state", "assets_line", "create_uid"),
                        offset = offset,
                        limit = pageSize,
                        sort = "id desc"
                    )

                    val transferIds = mutableListOf<Int>()
                    val creatorMap = mutableMapOf<Int, String>()
                    for (i in 0 until records.length()) {
                        val obj = records.getJSONObject(i)
                        val id = obj.getInt("id")
                        val cField = obj.opt("create_uid")
                        val creator = when (cField) {
                            is org.json.JSONArray -> cField.optString(1, "")
                            is String -> if (cField == "false") "" else cField
                            else -> ""
                        }
                        if (creator.isNotBlank()) {
                            creatorMap[id] = creator
                        }
                        if (!seenIds.contains(id)) {
                            transferIds.add(id)
                        }
                    }

                    // Query employee.asset.transfer.line to get from_holder and to_employee
                    val lineMap = mutableMapOf<Int, Pair<String, String>>()
                    if (transferIds.isNotEmpty()) {
                        try {
                            val linesDomain = org.json.JSONArray().apply {
                                put(org.json.JSONArray().apply {
                                    put("employee_asset_transfer_id")
                                    put("in")
                                    put(org.json.JSONArray(transferIds))
                                })
                            }
                            val linesRecords = ApiHelper.searchRead(
                                model = "employee.asset.transfer.line",
                                domain = linesDomain,
                                fields = listOf("id", "employee_asset_transfer_id", "asset_id", "held_by_id", "employee_id", "asset_category_id", "create_uid"),
                                offset = 0,
                                limit = 100
                            )

                            for (j in 0 until linesRecords.length()) {
                                val lObj = linesRecords.getJSONObject(j)
                                val trField = lObj.opt("employee_asset_transfer_id")
                                val trId = when (trField) {
                                    is org.json.JSONArray -> trField.optInt(0, -1)
                                    is Int -> trField
                                    else -> -1
                                }
                                if (trId != -1 && !lineMap.containsKey(trId)) {
                                    val heldByRaw = when (val h = lObj.opt("held_by_id")) {
                                        is org.json.JSONArray -> h.optString(1, "")
                                        is String -> if (h == "false" || h.isBlank()) "" else h
                                        else -> ""
                                    }
                                    val creator = creatorMap[trId] ?: when (val c = lObj.opt("create_uid")) {
                                        is org.json.JSONArray -> c.optString(1, "")
                                        is String -> if (c == "false") "" else c
                                        else -> ""
                                    }
                                    val heldBy = if (heldByRaw.isNotBlank()) {
                                        heldByRaw
                                    } else if (creator.isNotBlank()) {
                                        creator
                                    } else {
                                        "Storage"
                                    }

                                    val emp = when (val e = lObj.opt("employee_id")) {
                                        is org.json.JSONArray -> e.optString(1, "-")
                                        is String -> if (e == "false" || e.isBlank()) "-" else e
                                        else -> "-"
                                    }
                                    lineMap[trId] = Pair(heldBy, emp)
                                }
                            }
                        } catch (le: Exception) {
                            Log.w(TAG, "Failed fetching lines via search_read: ${le.message}")
                        }
                    }

                    for (i in 0 until records.length()) {
                        val obj = records.getJSONObject(i)
                        val id = obj.getInt("id")
                        if (seenIds.contains(id)) continue
                        seenIds.add(id)

                        val fallbackCreator = creatorMap[id] ?: "Storage"
                        val (fromHolder, toEmployee) = lineMap[id] ?: Pair(fallbackCreator, "-")
                        val dueDateRaw = obj.optString("due_date", "-")
                        val dueDate = if (dueDateRaw == "false" || dueDateRaw.isBlank()) "-" else dueDateRaw
                        val state = obj.optString("state", "draft")

                        AssetList.add(
                            AssetItem(
                                id = id,
                                name = obj.optString("name", "EAT"),
                                dueDate = dueDate,
                                partnerName = "-",
                                state = state,
                                transferType = "Transfer Out",
                                fromHolder = fromHolder,
                                toEmployee = toEmployee
                            )
                        )
                    }
                    fetchSuccess = true
                } catch (se: Exception) {
                    Log.w(TAG, "search_read failed, falling back to /get/asset: ${se.message}")
                }

                // 2. Fallback to /get/asset endpoint if search_read didn't succeed
                if (!fetchSuccess) {
                    val useCache = page == 1
                    val url = "${AuthUtils.getServerUrl()}/get/asset?offset=$offset&limit=$pageSize"
                    Log.d(TAG, "request url=$url useCache=$useCache page=$page offset=$offset query=$query")
                    val jsonObject = ApiHelper.getJsonObject(
                        url,
                        useCache = useCache
                    )

                    if (jsonObject.getString("status") == "success") {
                        val jsonArray = jsonObject.getJSONArray("assets")
                        for (i in 0 until jsonArray.length()) {
                            val obj = jsonArray.getJSONObject(i)
                            val id = obj.getInt("id")
                            if (seenIds.contains(id)) continue
                            seenIds.add(id)

                            val transferType = obj.optString("transfer_type", obj.optString("type", obj.optString("direction", "")))
                            val fromHolder = obj.optString("from_holder", obj.optString("from_employee", obj.optString("held_by", obj.optString("source_location", "-"))))
                            val toEmployee = obj.optString("to_employee", obj.optString("employee", obj.optString("to_holder", obj.optString("dest_location", obj.optString("partner_name", "-")))))

                            AssetList.add(
                                AssetItem(
                                    id = id,
                                    name = obj.getString("name"),
                                    dueDate = obj.optString("due_date", "-"),
                                    partnerName = obj.optString("partner_name", "-"),
                                    state = obj.optString("state", ""),
                                    transferType = transferType,
                                    fromHolder = fromHolder,
                                    toEmployee = toEmployee
                                )
                            )
                        }
                    } else {
                        isLoading = false
                        progressBar.visibility = View.GONE
                        Toast.makeText(requireContext(), "Failed: ${jsonObject.optString("message")}", Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                }

                    progressBar.visibility = View.GONE
                    contentLayout.visibility = View.VISIBLE
                    contentLayout.isEnabled = true
                    contentLayout.alpha = 1f

                    try {
                        if (editSearch.hasFocus()) {
                            val pos = editSearch.selectionStart.coerceAtLeast(0)
                            val length = editSearch.text?.length ?: 0
                            editSearch.setSelection(pos.coerceAtMost(length))
                        }
                    } catch (_: Exception) {}

                    if (AssetList.isEmpty() && page > 1) {
                        isLastPage = true
                        isLoading = false
                        return@launch
                    }

                    if (page == 1) {
                        adapter.updateData(AssetList)
                        if (currentQuery.isNotBlank()) adapter.filter(currentQuery)
                    } else {
                        adapter.appendData(AssetList)
                        if (currentQuery.isNotBlank()) adapter.filter(currentQuery)
                    }

                    isLoading = false
                    if (AssetList.size < pageSize) {
                        isLastPage = true
                    } else {
                        currentPage = page
                    }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) {
                    Log.d(TAG, "loadAssetOrders cancelled")
                    return@launch
                }
                isLoading = false
                progressBar.visibility = View.GONE
                contentLayout.visibility = View.VISIBLE
                contentLayout.isEnabled = true
                contentLayout.alpha = 1f
                try {
                    if (editSearch.hasFocus()) {
                        val pos = editSearch.selectionStart.coerceAtLeast(0)
                        val length = editSearch.text?.length ?: 0
                        editSearch.setSelection(pos.coerceAtMost(length))
                    }
                } catch (_: Exception) {}
                Toast.makeText(requireContext(), "Error loading data: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun handleAssetScanResult(rfids: List<String>) {
        if (rfids.isEmpty()) return
        shouldRefreshOnResume = true
        Toast.makeText(requireContext(), "Asset processed successfully", Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val TAG = "AssetFragment"

        fun newInstance(args: Bundle?) = AssetFragment()
            .apply { arguments = args }
    }
}
