package com.sunmi.uhf.fragment.asset

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.AppCompatEditText
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.sunmi.uhf.BuildConfig
import com.sunmi.uhf.R
import com.sunmi.uhf.fragment.takeinventory.TakeInventoryFragment
import com.sunmi.uhf.service.ApiHelper
import com.sunmi.uhf.service.OdooApiClient
import com.sunmi.uhf.utils.AuthUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

class AssetDetailFragment : Fragment() {

    private var assetId: Int = 0
    private var assetItem: AssetItem? = null
    private lateinit var adapter: AssetMoveAdapter
    private lateinit var recyclerView: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var progressBarMoveLines: ProgressBar
    private lateinit var txtAssetName: TextView
    private lateinit var txtTransferType: TextView
    private lateinit var txtFromHolder: TextView
    private lateinit var txtToEmployee: TextView
    private lateinit var btnAssignOrder: TextView
    private lateinit var txtScheduledDate: TextView
    private lateinit var txtOrigin: TextView
    private lateinit var txtState: TextView
    private lateinit var txtPartner: TextView
    private var shouldRefreshOnResume = false
    private var creatorName: String = ""

    // Cached personnel for assign dialog
    private val employeeList = mutableListOf<Pair<Int, String>>()
    private val customerList = mutableListOf<Pair<Int, String>>()
    private var cachedLineRecords: JSONArray? = null

    companion object {
        fun newInstance(id: Int): AssetDetailFragment {
            val fragment = AssetDetailFragment()
            val args = Bundle()
            args.putInt("asset_id", id)
            fragment.arguments = args
            return fragment
        }

        fun newInstance(item: AssetItem): AssetDetailFragment {
            val fragment = AssetDetailFragment()
            val args = Bundle()
            args.putInt("asset_id", item.id)
            args.putParcelable("asset_item", item)
            fragment.arguments = args
            return fragment
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        assetId = arguments?.getInt("asset_id") ?: 0
        assetItem = arguments?.getParcelable("asset_item")
        creatorName = assetItem?.fromHolder ?: ""
    }

    @SuppressLint("MissingInflatedId")
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_asset_detail, container, false)

        // Init Views
        txtAssetName = view.findViewById(R.id.txtAssetName)
        txtTransferType = view.findViewById(R.id.txtTransferType)
        txtFromHolder = view.findViewById(R.id.txtFromHolder)
        txtToEmployee = view.findViewById(R.id.txtToEmployee)
        btnAssignOrder = view.findViewById(R.id.btnAssignOrder)
        txtScheduledDate = view.findViewById(R.id.txtScheduledDate)
        txtOrigin = view.findViewById(R.id.txtOrigin)
        txtState = view.findViewById(R.id.txtState)
        txtPartner = view.findViewById(R.id.txtPartner)
        recyclerView = view.findViewById(R.id.recyclerViewAssetMove)
        progressBar = view.findViewById(R.id.progressBarAssetDetail)

        progressBarMoveLines = try {
            view.findViewById(R.id.progressBarMoveLines)
        } catch (e: Exception) {
            ProgressBar(requireContext())
        }

        btnAssignOrder.setOnClickListener {
            showAssignDialog(
                title = "Assign Transfer Order",
                lineId = 0,
                equipmentId = 0,
                isOrderLevel = true
            )
        }

        // Floating Scan Button
        view.findViewById<FloatingActionButton>(R.id.btnScanDetail)?.setOnClickListener {
            val args = Bundle().apply {
                putInt(TakeInventoryFragment.ARC_KEY_ASSET_ID, assetId)
            }
            val fragment = TakeInventoryFragment.newInstance(args)
            (activity as? com.sunmi.uhf.base.BaseActivity<*>)?.switchFragment(
                fragment,
                addToBackStack = true,
                clearStack = false
            )
        }

        // RecyclerView with assignment & inspection callbacks
        adapter = AssetMoveAdapter(
            items = emptyList(),
            onItemClick = { moveItem ->
                val rfid = moveItem.rfid.takeIf { it.isNotBlank() && it != "-" && it != "false" }
                if (!rfid.isNullOrBlank()) {
                    val verificationFragment = AssetVerificationFragment.newInstance(rfid, assetId)
                    (activity as? com.sunmi.uhf.base.BaseActivity<*>)?.switchFragment(
                        verificationFragment,
                        addToBackStack = true,
                        clearStack = false
                    )
                } else {
                    showAssignDialog(
                        title = if (moveItem.isAssigned) "Reassign: ${moveItem.asset}" else "Assign: ${moveItem.asset}",
                        lineId = moveItem.lineId,
                        equipmentId = moveItem.equipmentId,
                        isOrderLevel = false
                    )
                }
            },
            onAssignClick = { moveItem ->
                showAssignDialog(
                    title = if (moveItem.isAssigned) "Reassign: ${moveItem.asset}" else "Assign: ${moveItem.asset}",
                    lineId = moveItem.lineId,
                    equipmentId = moveItem.equipmentId,
                    isOrderLevel = false
                )
            }
        )
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        loadAssigneesInBackground()

        if (assetItem != null) {
            displayHeaderImmediately()
            loadMoveLines()
        } else {
            loadAssetDetail()
        }
        return view
    }

    override fun onResume() {
        super.onResume()
        loadMoveLines()
    }

    @SuppressLint("SetTextI18n")
    private fun displayHeaderImmediately() {
        assetItem?.let {
            txtAssetName.text = "Employee Asset Transfer: ${it.name}"
            txtTransferType.text = "Transfer Type: ${it.displayTransferType}"
            val fromText = if (it.fromHolder.isNotBlank() && it.fromHolder != "false") it.fromHolder else "-"
            val toText = if (it.toEmployee.isNotBlank() && it.toEmployee != "false") it.toEmployee else "-"
            val dateText = if (it.dueDate.isNotBlank() && it.dueDate != "false") it.dueDate else "-"
            txtFromHolder.text = "From: $fromText"
            txtToEmployee.text = "To: $toText"
            txtScheduledDate.text = "Scheduled Date: $dateText"
            txtOrigin.text = "Origin: -"
            txtState.text = "State: ${it.state.uppercase()}"

            btnAssignOrder.visibility = View.VISIBLE
            if (toText == "-" || toText.equals("unassigned", ignoreCase = true)) {
                btnAssignOrder.text = "+ Assign Order"
            } else {
                btnAssignOrder.text = "Reassign Order"
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun updateHeaderFromJsonObject(assetObj: JSONObject) {
        val rawType = assetObj.optString("transfer_type", assetObj.optString("type", assetObj.optString("direction", "")))
        val displayType = when {
            rawType.contains("out", ignoreCase = true) -> "Transfer Out"
            rawType.contains("in", ignoreCase = true) || rawType.contains("return", ignoreCase = true) -> "Transfer In / Return"
            rawType.isNotBlank() -> rawType.replace("_", " ").split(" ")
                .filter { it.isNotEmpty() }
                .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
            assetItem != null && assetItem?.displayTransferType != "-" -> assetItem?.displayTransferType ?: "Transfer Out"
            else -> "Transfer Out"
        }

        val fromHolder = assetObj.optString(
            "from_holder",
            assetObj.optString(
                "from_employee",
                assetObj.optString("held_by", assetObj.optString("source_location", assetItem?.fromHolder ?: "-"))
            )
        )
        val toEmployee = assetObj.optString(
            "to_employee",
            assetObj.optString(
                "employee",
                assetObj.optString("to_holder", assetObj.optString("dest_location", assetItem?.toEmployee ?: "-"))
            )
        )

        val fromText = if (fromHolder.isNotBlank() && fromHolder != "false") fromHolder else "-"
        val toText = if (toEmployee.isNotBlank() && toEmployee != "false") toEmployee else "-"
        val dateText = assetObj.optString("due_date", assetItem?.dueDate ?: "-")

        txtAssetName.text = "Employee Asset Transfer: ${assetObj.optString("name", assetItem?.name ?: "-")}"
        txtTransferType.text = "Transfer Type: $displayType"
        txtFromHolder.text = "From: $fromText"
        txtToEmployee.text = "To: $toText"
        txtScheduledDate.text = "Scheduled Date: $dateText"
        txtOrigin.text = "Origin: ${assetObj.optString("origin", "-")}"
        txtState.text = "State: ${assetObj.optString("state", assetItem?.state ?: "").uppercase()}"

        btnAssignOrder.visibility = View.VISIBLE
        if (toText == "-" || toText.equals("unassigned", ignoreCase = true)) {
            btnAssignOrder.text = "+ Assign Order"
        } else {
            btnAssignOrder.text = "Reassign Order"
        }
    }

    private fun loadMoveLines() {
        progressBar.visibility = View.VISIBLE
        progressBarMoveLines.visibility = View.VISIBLE

        lifecycleScope.launch {
            var searchReadSuccess = false
            // 1. Try search_read on employee.asset.transfer.line
            try {
                val linesDomain = org.json.JSONArray().apply {
                    put(org.json.JSONArray().apply {
                        put("employee_asset_transfer_id")
                        put("=")
                        put(assetId)
                    })
                }
                val lineRecords = ApiHelper.searchRead(
                    model = "employee.asset.transfer.line",
                    domain = linesDomain,
                    fields = listOf("id", "asset_id", "held_by_id", "employee_id", "asset_category_id", "notes"),
                    limit = 100
                )
                cachedLineRecords = lineRecords

                if (lineRecords.length() > 0) {
                    val list = mutableListOf<AssetMoveItem>()
                    var firstHeldBy = "Storage"
                    var firstEmployee = "-"

                    // Fetch equipment RFID for each asset from maintenance.equipment
                    val equipIds = mutableListOf<Int>()
                    for (i in 0 until lineRecords.length()) {
                        val line = lineRecords.getJSONObject(i)
                        when (val a = line.opt("asset_id")) {
                            is org.json.JSONArray -> {
                                val aId = a.optInt(0, -1)
                                if (aId != -1) equipIds.add(aId)
                            }
                            is Int -> equipIds.add(a)
                        }
                    }

                    val rfidMap = mutableMapOf<Int, String>()
                    if (equipIds.isNotEmpty()) {
                        try {
                            val equipDomain = org.json.JSONArray().apply {
                                put(org.json.JSONArray().apply {
                                    put("id")
                                    put("in")
                                    put(org.json.JSONArray(equipIds))
                                })
                            }
                            val equipRecords = ApiHelper.searchRead(
                                model = "maintenance.equipment",
                                domain = equipDomain,
                                fields = listOf("id", "rfid"),
                                limit = equipIds.size
                            )
                            for (k in 0 until equipRecords.length()) {
                                val eq = equipRecords.getJSONObject(k)
                                val eqId = eq.optInt("id", -1)
                                val rfidVal = eq.optString("rfid", "")
                                if (eqId != -1 && rfidVal != "false" && rfidVal.isNotBlank()) {
                                    rfidMap[eqId] = rfidVal
                                }
                            }
                        } catch (e: Exception) {
                            android.util.Log.w("AssetDetailFragment", "Failed fetching equipment rfid: ${e.message}")
                        }
                    }

                    for (i in 0 until lineRecords.length()) {
                        val line = lineRecords.getJSONObject(i)
                        val lineId = line.optInt("id", 0)
                        val assetName = when (val a = line.opt("asset_id")) {
                            is org.json.JSONArray -> a.optString(1, "-")
                            is String -> a
                            else -> "-"
                        }
                        val categoryName = when (val c = line.opt("asset_category_id")) {
                            is org.json.JSONArray -> c.optString(1, "-")
                            is String -> c
                            else -> "-"
                        }
                        val heldByRaw = when (val h = line.opt("held_by_id")) {
                            is org.json.JSONArray -> h.optString(1, "")
                            is String -> if (h == "false" || h.isBlank()) "" else h
                            else -> ""
                        }
                        val creator = if (creatorName.isNotBlank() && creatorName != "Storage") creatorName else when (val c = line.opt("create_uid")) {
                            is org.json.JSONArray -> c.optString(1, "")
                            is String -> if (c == "false") "" else c
                            else -> ""
                        }
                        val heldByName = if (heldByRaw.isNotBlank()) heldByRaw else if (creator.isNotBlank()) creator else "Storage"

                        val employeeName = when (val e = line.opt("employee_id")) {
                            is org.json.JSONArray -> e.optString(1, "-")
                            is String -> if (e == "false" || e.isBlank()) "-" else e
                            else -> "-"
                        }

                        val empId = when (val e = line.opt("employee_id")) {
                            is org.json.JSONArray -> e.optInt(0, 0)
                            is Int -> e
                            else -> 0
                        }

                        val eqId = when (val a = line.opt("asset_id")) {
                            is org.json.JSONArray -> a.optInt(0, -1)
                            is Int -> a
                            else -> -1
                        }
                        val rfidVal = rfidMap[eqId] ?: line.optString("rfid", "-")
                        val rfid = if (rfidVal == "false" || rfidVal.isBlank()) "-" else rfidVal

                        if (i == 0) {
                            firstHeldBy = heldByName
                            firstEmployee = employeeName
                        }

                        list.add(
                            AssetMoveItem(
                                lineId = lineId,
                                equipmentId = eqId,
                                asset = assetName,
                                category = categoryName,
                                heldBy = heldByName,
                                employee = employeeName,
                                employeeId = empId,
                                rfid = rfid
                            )
                        )
                    }

                    // Update header direction
                    txtTransferType.text = "Transfer Type: Transfer Out"
                    txtFromHolder.text = "From: $firstHeldBy"
                    txtToEmployee.text = "To: $firstEmployee"

                    btnAssignOrder.visibility = View.VISIBLE
                    if (firstEmployee == "-" || firstEmployee.equals("unassigned", ignoreCase = true)) {
                        btnAssignOrder.text = "+ Assign Order"
                    } else {
                        btnAssignOrder.text = "Reassign Order"
                    }

                    adapter.updateData(list)
                    progressBar.visibility = View.GONE
                    progressBarMoveLines.visibility = View.GONE
                    searchReadSuccess = true
                }
            } catch (e: Exception) {
                android.util.Log.w("AssetDetailFragment", "search_read lines error: ${e.message}")
            }

            if (!searchReadSuccess) {
                fallbackLoadMoveLinesHttp()
            }
        }
    }

    private fun fallbackLoadMoveLinesHttp() {
        progressBar.visibility = View.VISIBLE
        progressBarMoveLines.visibility = View.VISIBLE

        val client = OdooApiClient.getClient()
        val request = Request.Builder()
            .url("${AuthUtils.getServerUrl()}/get/asset/detail/$assetId")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                activity?.runOnUiThread {
                    progressBar.visibility = View.GONE
                    progressBarMoveLines.visibility = View.GONE
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val jsonData = response.body?.string() ?: return
                try {
                    val jsonObj = JSONObject(jsonData)
                    val assetObj = jsonObj.getJSONObject("asset")

                    val moveLines = assetObj.optJSONArray("assets_line") ?: org.json.JSONArray()
                    val list = mutableListOf<AssetMoveItem>()

                    for (i in 0 until moveLines.length()) {
                        val line = moveLines.getJSONObject(i)
                        val lineId = line.optInt("id", 0)
                        val eqId = line.optInt("asset_id_int", line.optInt("equipment_id", 0))
                        val rfidVal = line.optString("rfid", "-")
                        val heldByVal = line.optString("held_by", "")
                        val heldBy = if (heldByVal.isNotBlank() && heldByVal != "false") heldByVal else creatorName.ifBlank { "Storage" }
                        val employeeName = line.optString("employee", "-")
                        list.add(
                            AssetMoveItem(
                                lineId = lineId,
                                equipmentId = eqId,
                                asset = line.optString("asset_id", line.optString("name", "-")),
                                category = line.optString("category", "-"),
                                heldBy = heldBy,
                                employee = employeeName,
                                rfid = if (rfidVal == "false" || rfidVal.isBlank()) "-" else rfidVal,
                            )
                        )
                    }

                    activity?.runOnUiThread {
                        updateHeaderFromJsonObject(assetObj)
                        adapter.updateData(list)
                        progressBar.visibility = View.GONE
                        progressBarMoveLines.visibility = View.GONE
                    }
                } catch (e: Exception) {
                    activity?.runOnUiThread {
                        progressBar.visibility = View.GONE
                        progressBarMoveLines.visibility = View.GONE
                    }
                }
            }
        })
    }

    private fun loadAssetDetail() {
        progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                // Fetch transfer header
                val headerDomain = org.json.JSONArray().apply {
                    put(org.json.JSONArray().apply {
                        put("id")
                        put("=")
                        put(assetId)
                    })
                }
                val transferRecords = ApiHelper.searchRead(
                    model = "employee.asset.transfer",
                    domain = headerDomain,
                    fields = listOf("id", "name", "due_date", "state", "description", "create_uid"),
                    limit = 1
                )

                if (transferRecords.length() > 0) {
                    val tObj = transferRecords.getJSONObject(0)
                    val name = tObj.optString("name", "EAT")
                    val dueDate = tObj.optString("due_date", "-")
                    val state = tObj.optString("state", "draft")
                    val cField = tObj.opt("create_uid")
                    val creator = when (cField) {
                        is org.json.JSONArray -> cField.optString(1, "")
                        is String -> if (cField == "false") "" else cField
                        else -> ""
                    }
                    if (creator.isNotBlank()) {
                        creatorName = creator
                    }

                    txtAssetName.text = "Employee Asset Transfer: $name"
                    txtScheduledDate.text = "Scheduled Date: ${if (dueDate != "false") dueDate else "-"}"
                    txtState.text = "State: ${state.uppercase()}"
                    txtOrigin.text = "Origin: -"
                }
            } catch (e: Exception) {
                android.util.Log.w("AssetDetailFragment", "search_read transfer header error: ${e.message}")
            }
            loadMoveLines()
        }
    }

    private fun loadAssigneesInBackground() {
        lifecycleScope.launch(Dispatchers.IO) {
            // 1. Fetch Employees / Users from /get/users endpoint
            try {
                val userArray = ApiHelper.getJsonArray(
                    "${AuthUtils.getServerUrl()}/get/users",
                    useCache = true,
                    arrayKey = "users"
                )
                val list = mutableListOf<Pair<Int, String>>()
                for (i in 0 until userArray.length()) {
                    val u = userArray.getJSONObject(i)
                    val id = u.getInt("id")
                    val name = u.getString("name")
                    list.add(Pair(id, name))
                }
                if (list.isNotEmpty()) {
                    synchronized(employeeList) {
                        employeeList.clear()
                        employeeList.addAll(list)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("AssetDetailFragment", "Failed loading /get/users: ${e.message}")
            }

            // Fallback for employees via searchRead hr.employee if list empty
            if (employeeList.isEmpty()) {
                try {
                    val records = ApiHelper.searchRead(
                        model = "hr.employee",
                        fields = listOf("id", "name"),
                        limit = 100,
                        sort = "name asc"
                    )
                    val list = mutableListOf<Pair<Int, String>>()
                    for (i in 0 until records.length()) {
                        val obj = records.getJSONObject(i)
                        list.add(Pair(obj.getInt("id"), obj.getString("name")))
                    }
                    if (list.isNotEmpty()) {
                        synchronized(employeeList) {
                            employeeList.clear()
                            employeeList.addAll(list)
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.w("AssetDetailFragment", "Failed loading hr.employee: ${e.message}")
                }
            }

            // 2. Fetch Customers / Partners from res.partner
            try {
                val partnerRecords = ApiHelper.searchRead(
                    model = "res.partner",
                    domain = org.json.JSONArray().apply {
                        put(org.json.JSONArray().apply {
                            put("customer_rank")
                            put(">")
                            put(0)
                        })
                    },
                    fields = listOf("id", "name"),
                    limit = 100,
                    sort = "name asc"
                )
                val list = mutableListOf<Pair<Int, String>>()
                for (i in 0 until partnerRecords.length()) {
                    val obj = partnerRecords.getJSONObject(i)
                    list.add(Pair(obj.getInt("id"), obj.getString("name")))
                }
                if (list.isNotEmpty()) {
                    synchronized(customerList) {
                        customerList.clear()
                        customerList.addAll(list)
                    }
                }
            } catch (_: Exception) {
                try {
                    val partnerRecords = ApiHelper.searchRead(
                        model = "res.partner",
                        fields = listOf("id", "name"),
                        limit = 60,
                        sort = "name asc"
                    )
                    val list = mutableListOf<Pair<Int, String>>()
                    for (i in 0 until partnerRecords.length()) {
                        val obj = partnerRecords.getJSONObject(i)
                        list.add(Pair(obj.getInt("id"), obj.getString("name")))
                    }
                    if (list.isNotEmpty()) {
                        synchronized(customerList) {
                            customerList.clear()
                            customerList.addAll(list)
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.w("AssetDetailFragment", "Failed loading res.partner: ${e.message}")
                }
            }
        }
    }

    private fun showAssignDialog(title: String, lineId: Int, equipmentId: Int, isOrderLevel: Boolean) {
        if (!isAdded) return
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_assign_person, null, false)
        val txtTitle = dialogView.findViewById<TextView>(R.id.txtDialogTitle)
        val txtSubtitle = dialogView.findViewById<TextView>(R.id.txtDialogSubtitle)
        val rgType = dialogView.findViewById<RadioGroup>(R.id.rgAssignType)
        val rbEmp = dialogView.findViewById<RadioButton>(R.id.rbEmployee)
        val rbCust = dialogView.findViewById<RadioButton>(R.id.rbCustomer)
        val searchEdit = dialogView.findViewById<AppCompatEditText>(R.id.editSearchPerson)
        val listView = dialogView.findViewById<ListView>(R.id.listPersons)
        val btnCancel = dialogView.findViewById<TextView>(R.id.btnCancelAssign)

        txtTitle.text = title
        txtSubtitle.text = if (isOrderLevel) "Select an assignee to assign to this transfer order" else "Select an assignee for this asset line"

        var isCustomer = rbCust.isChecked
        val activeList = mutableListOf<Pair<Int, String>>()
        val displayNames = mutableListOf<String>()
        val listAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, displayNames)
        listView.adapter = listAdapter

        fun updateList(query: String = "") {
            val source = if (isCustomer) customerList else employeeList
            activeList.clear()
            if (query.isBlank()) {
                activeList.addAll(source)
            } else {
                val q = query.lowercase().trim()
                activeList.addAll(source.filter { it.second.lowercase().contains(q) })
            }
            displayNames.clear()
            displayNames.addAll(activeList.map { it.second })
            listAdapter.notifyDataSetChanged()
        }

        updateList()

        if (employeeList.isEmpty() && customerList.isEmpty()) {
            Toast.makeText(requireContext(), "Loading personnel list...", Toast.LENGTH_SHORT).show()
            lifecycleScope.launch {
                loadAssigneesInBackground()
                delay(600)
                updateList(searchEdit.text.toString())
            }
        }

        rgType.setOnCheckedChangeListener { _, checkedId ->
            isCustomer = (checkedId == R.id.rbCustomer)
            searchEdit.hint = if (isCustomer) "Search customer name..." else "Search employee name..."
            updateList(searchEdit.text.toString())
        }

        searchEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                updateList(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        val dialog = AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .create()

        btnCancel.setOnClickListener { dialog.dismiss() }

        listView.setOnItemClickListener { _, _, position, _ ->
            if (position < activeList.size) {
                val selected = activeList[position]
                dialog.dismiss()
                performAssignment(
                    selectedId = selected.first,
                    selectedName = selected.second,
                    isCustomer = isCustomer,
                    lineId = lineId,
                    equipmentId = equipmentId,
                    isOrderLevel = isOrderLevel
                )
            }
        }

        dialog.show()
    }

    private fun performAssignment(
        selectedId: Int,
        selectedName: String,
        isCustomer: Boolean,
        lineId: Int,
        equipmentId: Int,
        isOrderLevel: Boolean
    ) {
        progressBar.visibility = View.VISIBLE
        progressBarMoveLines.visibility = View.VISIBLE

        lifecycleScope.launch {
            var success = false
            var errorMsg = ""
            try {
                if (isOrderLevel) {
                    val lineIds = mutableListOf<Int>()
                    val equipIds = mutableListOf<Int>()
                    cachedLineRecords?.let { arr ->
                        for (i in 0 until arr.length()) {
                            val line = arr.getJSONObject(i)
                            val lId = line.optInt("id", 0)
                            if (lId > 0) lineIds.add(lId)
                            val eqId = when (val a = line.opt("asset_id")) {
                                is org.json.JSONArray -> a.optInt(0, 0)
                                is Int -> a
                                else -> 0
                            }
                            if (eqId > 0) equipIds.add(eqId)
                        }
                    }

                    // Update employee.asset.transfer.line in batch
                    if (lineIds.isNotEmpty()) {
                        try {
                            val vals = JSONObject().apply {
                                put("employee_id", selectedId)
                            }
                            val args = JSONArray().apply {
                                put(JSONArray(lineIds))
                                put(vals)
                            }
                            ApiHelper.callKw(
                                model = "employee.asset.transfer.line",
                                method = "write",
                                args = args
                            )
                            success = true
                        } catch (e: Exception) {
                            android.util.Log.w("AssetDetailFragment", "Failed to write transfer lines: ${e.message}")
                        }
                    }

                    // Update maintenance.equipment in batch
                    if (equipIds.isNotEmpty()) {
                        try {
                            val vals = JSONObject().apply {
                                if (isCustomer) {
                                    put("partner_id", selectedId)
                                    put("employee_id", false)
                                    put("equipment_assign_to", "customer")
                                } else {
                                    put("employee_id", selectedId)
                                    put("partner_id", false)
                                    put("equipment_assign_to", "employee")
                                }
                            }
                            val args = JSONArray().apply {
                                put(JSONArray(equipIds))
                                put(vals)
                            }
                            ApiHelper.callKw(
                                model = "maintenance.equipment",
                                method = "write",
                                args = args
                            )
                        } catch (e: Exception) {
                            android.util.Log.w("AssetDetailFragment", "Failed to write equipment: ${e.message}")
                        }
                    }

                    // Notify /create/asset/line endpoint
                    try {
                        val jsonBody = JSONObject().apply {
                            put("asset_id", if (equipIds.isNotEmpty()) equipIds.first() else assetId)
                            put("product_asset_ids", JSONArray(if (equipIds.isNotEmpty()) equipIds else listOf(assetId)))
                            put("employee_asset_transfer_id", assetId)
                            put("user_id", selectedId)
                            put("user_name", selectedName)
                            if (isCustomer) {
                                put("partner_id", selectedId)
                                put("partner_name", selectedName)
                            } else {
                                put("employee_id", selectedId)
                            }
                            put("transfer_type", "transfer_out")
                            put("direction", "out")
                            put("held_by", if (creatorName.isNotBlank()) creatorName else "Storage")
                            put("to_location", "${if (isCustomer) "Customer: " else "Employee: "}$selectedName")
                            put("notes", "Assigned order via UHF Mobile App")
                        }
                        val requestBody = jsonBody.toString().toRequestBody("application/json".toMediaType())
                        val request = Request.Builder()
                            .url("${AuthUtils.getServerUrl()}/create/asset/line")
                            .post(requestBody)
                            .addHeader("Content-Type", "application/json")
                            .build()
                        withContext(Dispatchers.IO) {
                            OdooApiClient.getClient().newCall(request).execute()
                        }
                        success = true
                    } catch (e: Exception) {
                        android.util.Log.w("AssetDetailFragment", "create/asset/line logging error: ${e.message}")
                    }

                } else {
                    // Line-level assignment
                    if (lineId > 0) {
                        try {
                            val vals = JSONObject().apply {
                                put("employee_id", selectedId)
                            }
                            val args = JSONArray().apply {
                                put(JSONArray().apply { put(lineId) })
                                put(vals)
                            }
                            ApiHelper.callKw(
                                model = "employee.asset.transfer.line",
                                method = "write",
                                args = args
                            )
                            success = true
                        } catch (e: Exception) {
                            android.util.Log.w("AssetDetailFragment", "Failed to write line $lineId: ${e.message}")
                        }
                    }

                    if (equipmentId > 0) {
                        try {
                            val vals = JSONObject().apply {
                                if (isCustomer) {
                                    put("partner_id", selectedId)
                                    put("employee_id", false)
                                    put("equipment_assign_to", "customer")
                                } else {
                                    put("employee_id", selectedId)
                                    put("partner_id", false)
                                    put("equipment_assign_to", "employee")
                                }
                            }
                            val args = JSONArray().apply {
                                put(JSONArray().apply { put(equipmentId) })
                                put(vals)
                            }
                            ApiHelper.callKw(
                                model = "maintenance.equipment",
                                method = "write",
                                args = args
                            )
                            success = true
                        } catch (e: Exception) {
                            android.util.Log.w("AssetDetailFragment", "Failed to write equipment $equipmentId: ${e.message}")
                        }
                    }

                    // Notify /create/asset/line
                    try {
                        val jsonBody = JSONObject().apply {
                            put("asset_id", if (equipmentId > 0) equipmentId else assetId)
                            put("product_asset_ids", JSONArray(listOf(if (equipmentId > 0) equipmentId else assetId)))
                            put("line_id", lineId)
                            put("employee_asset_transfer_id", assetId)
                            put("user_id", selectedId)
                            put("user_name", selectedName)
                            if (isCustomer) {
                                put("partner_id", selectedId)
                                put("partner_name", selectedName)
                            } else {
                                put("employee_id", selectedId)
                            }
                            put("transfer_type", "transfer_out")
                            put("direction", "out")
                            put("held_by", if (creatorName.isNotBlank()) creatorName else "Storage")
                            put("to_location", "${if (isCustomer) "Customer: " else "Employee: "}$selectedName")
                            put("notes", "Assigned line via UHF Mobile App")
                        }
                        val requestBody = jsonBody.toString().toRequestBody("application/json".toMediaType())
                        val request = Request.Builder()
                            .url("${AuthUtils.getServerUrl()}/create/asset/line")
                            .post(requestBody)
                            .addHeader("Content-Type", "application/json")
                            .build()
                        withContext(Dispatchers.IO) {
                            OdooApiClient.getClient().newCall(request).execute()
                        }
                        success = true
                    } catch (e: Exception) {
                        android.util.Log.w("AssetDetailFragment", "create/asset/line call error: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                errorMsg = e.message ?: "Unknown error"
                android.util.Log.e("AssetDetailFragment", "Error performing assignment: $errorMsg", e)
            } finally {
                progressBar.visibility = View.GONE
                progressBarMoveLines.visibility = View.GONE
                if (success) {
                    Toast.makeText(requireContext(), "Assigned to $selectedName successfully!", Toast.LENGTH_SHORT).show()
                    ApiHelper.clearCache()
                    loadMoveLines()
                } else if (errorMsg.isNotBlank()) {
                    Toast.makeText(requireContext(), "Assignment failed: $errorMsg", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(requireContext(), "Assignment updated for $selectedName", Toast.LENGTH_SHORT).show()
                    loadMoveLines()
                }
            }
        }
    }
}

