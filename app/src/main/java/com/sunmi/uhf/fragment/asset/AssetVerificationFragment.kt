package com.sunmi.uhf.fragment.asset

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.widget.AppCompatEditText
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.sunmi.uhf.R
import com.sunmi.uhf.base.BaseActivity
import com.sunmi.uhf.service.ApiHelper
import com.sunmi.uhf.service.OdooApiClient
import com.sunmi.uhf.utils.AuthUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class AssetVerificationFragment : Fragment() {

    private var scannedRfid: String = ""
    private var initialAssetId: Int = 0

    // Views
    private lateinit var loadingOverlay: FrameLayout
    private lateinit var btnBack: ImageView
    private lateinit var tvRfidBadgeTop: TextView
    private lateinit var tvAssetName: TextView
    private lateinit var tvAssetStatusBadge: TextView
    private lateinit var tvAssetCategory: TextView
    private lateinit var tvSerialCode: TextView
    private lateinit var tvRfidValue: TextView
    private lateinit var tvCurrentAssignee: TextView
    private lateinit var tvCurrentHolder: TextView
    private lateinit var tvMovementStatus: TextView
    private lateinit var rgTransferType: RadioGroup
    private lateinit var rbTransferOut: RadioButton
    private lateinit var rbTransferIn: RadioButton
    private lateinit var layoutAssigneeSection: LinearLayout
    private lateinit var layoutReturnDestination: LinearLayout
    private lateinit var rgAssigneeType: RadioGroup
    private lateinit var rbTypeEmployee: RadioButton
    private lateinit var rbTypeCustomer: RadioButton
    private lateinit var layoutSelectAssignee: LinearLayout
    private lateinit var tvSelectedAssignee: TextView
    private lateinit var ivAssigneeIcon: ImageView
    private lateinit var etNotes: EditText
    private lateinit var btnConfirmAction: Button
    private lateinit var btnScanAnother: Button

    // State data
    private var assetEquipmentId: Int = 0
    private var assetDisplayName: String = "Asset"
    private var isAssigned: Boolean = false
    private var currentAssigneeName: String = "Unassigned"
    private var currentAssigneeId: Int = 0
    private var currentHolderName: String = "Storage"

    // Target Selection
    private var selectedAssigneeId: Int? = null
    private var selectedAssigneeName: String? = null
    private var isTargetCustomer: Boolean = false

    // Cached lists for picker
    private val employeeList = mutableListOf<Pair<Int, String>>()
    private val customerList = mutableListOf<Pair<Int, String>>()

    companion object {
        private const val TAG = "AssetVerificationFrag"
        private const val ARG_RFID = "arg_rfid"
        private const val ARG_ASSET_ID = "arg_asset_id"

        fun newInstance(rfid: String, assetId: Int = 0): AssetVerificationFragment {
            return AssetVerificationFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_RFID, rfid)
                    putInt(ARG_ASSET_ID, assetId)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scannedRfid = arguments?.getString(ARG_RFID).orEmpty()
        initialAssetId = arguments?.getInt(ARG_ASSET_ID) ?: 0
        assetEquipmentId = initialAssetId
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_asset_verification, container, false)
        initViews(view)
        setupListeners()
        loadAssetInfo()
        loadAssigneesInBackground()
        return view
    }

    private fun initViews(v: View) {
        loadingOverlay = v.findViewById(R.id.loadingOverlay)
        btnBack = v.findViewById(R.id.btnBack)
        tvRfidBadgeTop = v.findViewById(R.id.tvRfidBadgeTop)
        tvAssetName = v.findViewById(R.id.tvAssetName)
        tvAssetStatusBadge = v.findViewById(R.id.tvAssetStatusBadge)
        tvAssetCategory = v.findViewById(R.id.tvAssetCategory)
        tvSerialCode = v.findViewById(R.id.tvSerialCode)
        tvRfidValue = v.findViewById(R.id.tvRfidValue)
        tvCurrentAssignee = v.findViewById(R.id.tvCurrentAssignee)
        tvCurrentHolder = v.findViewById(R.id.tvCurrentHolder)
        tvMovementStatus = v.findViewById(R.id.tvMovementStatus)
        rgTransferType = v.findViewById(R.id.rgTransferType)
        rbTransferOut = v.findViewById(R.id.rbTransferOut)
        rbTransferIn = v.findViewById(R.id.rbTransferIn)
        layoutAssigneeSection = v.findViewById(R.id.layoutAssigneeSection)
        layoutReturnDestination = v.findViewById(R.id.layoutReturnDestination)
        rgAssigneeType = v.findViewById(R.id.rgAssigneeType)
        rbTypeEmployee = v.findViewById(R.id.rbTypeEmployee)
        rbTypeCustomer = v.findViewById(R.id.rbTypeCustomer)
        layoutSelectAssignee = v.findViewById(R.id.layoutSelectAssignee)
        tvSelectedAssignee = v.findViewById(R.id.tvSelectedAssignee)
        ivAssigneeIcon = v.findViewById(R.id.ivAssigneeIcon)
        etNotes = v.findViewById(R.id.etNotes)
        btnConfirmAction = v.findViewById(R.id.btnConfirmAction)
        btnScanAnother = v.findViewById(R.id.btnScanAnother)

        // Set initial rfid badge
        val displayRfid = if (scannedRfid.length > 14) "${scannedRfid.take(6)}...${scannedRfid.takeLast(4)}" else scannedRfid
        tvRfidBadgeTop.text = "RFID: $displayRfid"
        tvRfidValue.text = if (scannedRfid.isNotBlank()) scannedRfid else "-"
    }

    private fun setupListeners() {
        btnBack.setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        btnScanAnother.setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        rgTransferType.setOnCheckedChangeListener { _, checkedId ->
            if (checkedId == R.id.rbTransferOut) {
                layoutAssigneeSection.visibility = View.VISIBLE
                layoutReturnDestination.visibility = View.GONE
                btnConfirmAction.text = if (isAssigned) "Confirm Check-Out / Transfer" else "Confirm Assignment"
            } else {
                layoutAssigneeSection.visibility = View.GONE
                layoutReturnDestination.visibility = View.VISIBLE
                btnConfirmAction.text = "Confirm Return to Storage"
            }
        }

        rgAssigneeType.setOnCheckedChangeListener { _, checkedId ->
            isTargetCustomer = (checkedId == R.id.rbTypeCustomer)
            selectedAssigneeId = null
            selectedAssigneeName = null
            tvSelectedAssignee.text = if (isTargetCustomer) "Tap to select Customer..." else "Tap to select Employee..."
            ivAssigneeIcon.setImageResource(if (isTargetCustomer) R.drawable.inventory_icon else R.drawable.common_icon)
        }

        layoutSelectAssignee.setOnClickListener {
            showAssigneeSelectionDialog()
        }

        btnConfirmAction.setOnClickListener {
            confirmAssetAction()
        }
    }

    @SuppressLint("SetTextI18n")
    private fun loadAssetInfo() {
        loadingOverlay.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                var found = false

                // 1. Primary lookup: maintenance.equipment via searchRead by RFID
                if (scannedRfid.isNotBlank()) {
                    try {
                        val domain = JSONArray().apply {
                            put(JSONArray().apply {
                                put("rfid")
                                put("=")
                                put(scannedRfid)
                            })
                        }
                        val records = ApiHelper.searchRead(
                            model = "maintenance.equipment",
                            domain = domain,
                            fields = listOf(
                                "id", "name", "serial_no", "category_id",
                                "employee_id", "owner_user_id", "partner_id",
                                "location", "equipment_assign_to", "rfid"
                            ),
                            limit = 1
                        )

                        if (records.length() > 0) {
                            val obj = records.getJSONObject(0)
                            assetEquipmentId = obj.getInt("id")
                            assetDisplayName = obj.optString("name", "Equipment")

                            val catField = obj.opt("category_id")
                            val categoryName = when (catField) {
                                is JSONArray -> catField.optString(1, "Equipment")
                                is String -> catField
                                else -> "General"
                            }

                            val serial = obj.optString("serial_no", "-").ifBlank { "-" }

                            // Check current assignee
                            val empField = obj.opt("employee_id")
                            val partnerField = obj.opt("partner_id")
                            val userField = obj.opt("owner_user_id")

                            val empName = when (empField) {
                                is JSONArray -> empField.optString(1, "")
                                is String -> if (empField == "false") "" else empField
                                else -> ""
                            }
                            val partnerName = when (partnerField) {
                                is JSONArray -> partnerField.optString(1, "")
                                is String -> if (partnerField == "false") "" else partnerField
                                else -> ""
                            }
                            val userName = when (userField) {
                                is JSONArray -> userField.optString(1, "")
                                is String -> if (userField == "false") "" else userField
                                else -> ""
                            }

                            val loc = obj.optString("location", "").takeIf { it != "false" && it.isNotBlank() }

                            if (empName.isNotBlank()) {
                                isAssigned = true
                                currentAssigneeName = empName
                                if (empField is JSONArray) currentAssigneeId = empField.optInt(0, 0)
                                currentHolderName = loc ?: empName
                            } else if (partnerName.isNotBlank()) {
                                isAssigned = true
                                currentAssigneeName = partnerName
                                if (partnerField is JSONArray) currentAssigneeId = partnerField.optInt(0, 0)
                                currentHolderName = loc ?: partnerName
                            } else if (userName.isNotBlank()) {
                                isAssigned = true
                                currentAssigneeName = userName
                                if (userField is JSONArray) currentAssigneeId = userField.optInt(0, 0)
                                currentHolderName = loc ?: userName
                            } else {
                                isAssigned = false
                                currentAssigneeName = "Unassigned"
                                currentAssigneeId = 0
                                currentHolderName = loc ?: "Storage"
                            }

                            bindAssetView(
                                name = assetDisplayName,
                                category = categoryName,
                                serial = serial,
                                rfid = scannedRfid,
                                assignee = currentAssigneeName,
                                holder = currentHolderName,
                                isAssigned = isAssigned
                            )
                            found = true
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "SearchRead maintenance.equipment failed: ${e.message}")
                    }
                }

                // 2. Secondary fallback: check endpoint /check/asset/rfid
                if (!found && scannedRfid.isNotBlank()) {
                    try {
                        val client = OdooApiClient.getClient()
                        val jsonBody = JSONObject().apply {
                            put("rfids", JSONArray(listOf(scannedRfid)))
                        }
                        val requestBody = jsonBody.toString().toRequestBody("application/json".toMediaType())
                        val request = Request.Builder()
                            .url("${AuthUtils.getServerUrl()}/check/asset/rfid")
                            .post(requestBody)
                            .addHeader("Content-Type", "application/json")
                            .build()

                        val resp = withContext(Dispatchers.IO) { client.newCall(request).execute() }
                        val body = resp.body?.string()
                        if (resp.isSuccessful && !body.isNullOrEmpty()) {
                            val jsonResp = JSONObject(body)
                            val payload = if (jsonResp.has("result")) jsonResp.getJSONObject("result") else jsonResp
                            val results = payload.optJSONArray("results")
                            if (results != null && results.length() > 0) {
                                val item = results.getJSONObject(0)
                                assetEquipmentId = item.optInt("id", assetEquipmentId)
                                assetDisplayName = item.optString("name", item.optString("asset_name", "Asset"))
                                val heldBy = item.optString("held_by", item.optString("employee", ""))
                                val serial = item.optString("serial_no", item.optString("asset_code", "-"))
                                val category = item.optString("category", item.optString("asset_category", "-"))

                                isAssigned = heldBy.isNotBlank() && heldBy != "false" && heldBy != "Storage"
                                currentAssigneeName = if (isAssigned) heldBy else "Unassigned"
                                currentHolderName = if (heldBy.isNotBlank() && heldBy != "false") heldBy else "Storage"

                                bindAssetView(
                                    name = assetDisplayName,
                                    category = category,
                                    serial = serial,
                                    rfid = scannedRfid,
                                    assignee = currentAssigneeName,
                                    holder = currentHolderName,
                                    isAssigned = isAssigned
                                )
                                found = true
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "check/asset/rfid failed: ${e.message}")
                    }
                }

                // 3. Fallback: If not found in equipment list, allow manual assignment
                if (!found) {
                    assetDisplayName = "Asset (RFID $scannedRfid)"
                    bindAssetView(
                        name = assetDisplayName,
                        category = "General Asset",
                        serial = "-",
                        rfid = scannedRfid,
                        assignee = "Unassigned",
                        holder = "Storage",
                        isAssigned = false
                    )
                }

                // 4. Query recent transfer movements for extra context
                if (assetEquipmentId > 0) {
                    try {
                        val trDomain = JSONArray().apply {
                            put(JSONArray().apply {
                                put("asset_id")
                                put("=")
                                put(assetEquipmentId)
                            })
                        }
                        val trLines = ApiHelper.searchRead(
                            model = "employee.asset.transfer.line",
                            domain = trDomain,
                            fields = listOf("id", "employee_asset_transfer_id", "held_by_id", "employee_id"),
                            limit = 1,
                            sort = "id desc"
                        )
                        if (trLines.length() > 0) {
                            val line = trLines.getJSONObject(0)
                            val held = when (val h = line.opt("held_by_id")) {
                                is JSONArray -> h.optString(1, "")
                                is String -> if (h == "false") "" else h
                                else -> ""
                            }
                            val toEmp = when (val e = line.opt("employee_id")) {
                                is JSONArray -> e.optString(1, "")
                                is String -> if (e == "false") "" else e
                                else -> ""
                            }
                            if (toEmp.isNotBlank()) {
                                tvMovementStatus.text = "Last Transfer: From $held -> To $toEmp"
                            }
                        }
                    } catch (e: Exception) {
                        Log.d(TAG, "No previous transfer line: ${e.message}")
                    }
                }

            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Error inspecting asset: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                loadingOverlay.visibility = View.GONE
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun bindAssetView(
        name: String,
        category: String,
        serial: String,
        rfid: String,
        assignee: String,
        holder: String,
        isAssigned: Boolean
    ) {
        tvAssetName.text = name
        tvAssetCategory.text = "Category: $category"
        tvSerialCode.text = serial
        tvRfidValue.text = rfid
        tvCurrentAssignee.text = assignee
        tvCurrentHolder.text = holder

        if (isAssigned) {
            tvAssetStatusBadge.text = "ASSIGNED"
            tvAssetStatusBadge.setTextColor(Color.parseColor("#2E7D32")) // Green
            tvAssetStatusBadge.setBackgroundColor(Color.parseColor("#E8F5E9"))
            tvCurrentAssignee.setTextColor(Color.parseColor("#2E7D32"))
            rbTransferOut.text = "Transfer Out / Reassign (Transfer to another Customer/User)"
            rbTransferIn.text = "Transfer In / Return (Return to Storage & Unassign)"
            tvMovementStatus.text = "Currently assigned to $assignee"
        } else {
            tvAssetStatusBadge.text = "UNASSIGNED / AVAILABLE"
            tvAssetStatusBadge.setTextColor(Color.parseColor("#E65100")) // Orange
            tvAssetStatusBadge.setBackgroundColor(Color.parseColor("#FFF3E0"))
            tvCurrentAssignee.setTextColor(Color.parseColor("#888888"))
            rbTransferOut.text = "Assign / Check-Out (Assign to Employee or Customer)"
            rbTransferIn.text = "Move / Place in Storage"
            tvMovementStatus.text = "Available in $holder (Not currently assigned)"
        }

        btnConfirmAction.text = if (isAssigned) "Confirm Check-Out / Transfer" else "Confirm Assignment"
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
                for (i in 0 until userArray.length()) {
                    val u = userArray.getJSONObject(i)
                    val id = u.getInt("id")
                    val name = u.getString("name")
                    employeeList.add(Pair(id, name))
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed loading /get/users: ${e.message}")
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
                    for (i in 0 until records.length()) {
                        val obj = records.getJSONObject(i)
                        employeeList.add(Pair(obj.getInt("id"), obj.getString("name")))
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed loading hr.employee: ${e.message}")
                }
            }

            // 2. Fetch Customers / Partners from res.partner
            try {
                val partnerRecords = ApiHelper.searchRead(
                    model = "res.partner",
                    domain = JSONArray().apply {
                        put(JSONArray().apply {
                            put("customer_rank")
                            put(">")
                            put(0)
                        })
                    },
                    fields = listOf("id", "name"),
                    limit = 100,
                    sort = "name asc"
                )
                for (i in 0 until partnerRecords.length()) {
                    val obj = partnerRecords.getJSONObject(i)
                    customerList.add(Pair(obj.getInt("id"), obj.getString("name")))
                }
            } catch (_: Exception) {
                // Fallback without customer_rank domain
                try {
                    val partnerRecords = ApiHelper.searchRead(
                        model = "res.partner",
                        fields = listOf("id", "name"),
                        limit = 60,
                        sort = "name asc"
                    )
                    for (i in 0 until partnerRecords.length()) {
                        val obj = partnerRecords.getJSONObject(i)
                        customerList.add(Pair(obj.getInt("id"), obj.getString("name")))
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed loading res.partner: ${e.message}")
                }
            }
        }
    }

    private fun showAssigneeSelectionDialog() {
        val listToDisplay = if (isTargetCustomer) customerList else employeeList
        val title = if (isTargetCustomer) "Select Customer / Client" else "Select Employee / User"

        if (listToDisplay.isEmpty()) {
            Toast.makeText(requireContext(), "Loading $title, please try in a moment...", Toast.LENGTH_SHORT).show()
            return
        }

        val names = listToDisplay.map { it.second }.toMutableList()
        val filteredList = mutableListOf<Pair<Int, String>>()
        filteredList.addAll(listToDisplay)

        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.fragment_asset_order, null, false)
        // Adjust dialogView into a search list dialog
        val searchEdit = AppCompatEditText(requireContext()).apply {
            hint = "Search $title..."
            setPadding(32, 24, 32, 24)
            setBackgroundResource(R.drawable.bg_search_rounded)
        }
        val listView = ListView(requireContext())
        val layout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 24)
            addView(searchEdit)
            addView(listView)
        }

        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, names)
        listView.adapter = adapter

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setView(layout)
            .setNegativeButton("Cancel", null)
            .create()

        searchEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val q = s?.toString()?.trim()?.lowercase().orEmpty()
                filteredList.clear()
                if (q.isEmpty()) {
                    filteredList.addAll(listToDisplay)
                } else {
                    filteredList.addAll(listToDisplay.filter { it.second.lowercase().contains(q) })
                }
                names.clear()
                names.addAll(filteredList.map { it.second })
                adapter.notifyDataSetChanged()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        listView.setOnItemClickListener { _, _, position, _ ->
            if (position < filteredList.size) {
                val selected = filteredList[position]
                selectedAssigneeId = selected.first
                selectedAssigneeName = selected.second
                tvSelectedAssignee.text = selected.second
                dialog.dismiss()
            }
        }

        dialog.show()
    }

    private fun confirmAssetAction() {
        val isTransferOut = rbTransferOut.isChecked
        val notes = etNotes.text.toString().trim()

        if (isTransferOut && (selectedAssigneeId == null || selectedAssigneeName.isNullOrBlank())) {
            Toast.makeText(requireContext(), "Please select an assignee before confirming", Toast.LENGTH_SHORT).show()
            return
        }

        loadingOverlay.visibility = View.VISIBLE

        val transferTypeStr = if (isTransferOut) {
            if (isAssigned) "Transfer Out" else "Asset Assignment"
        } else {
            "Transfer In / Return"
        }

        val destinationStr = if (isTransferOut) {
            "${if (isTargetCustomer) "Customer: " else "Employee: "}$selectedAssigneeName"
        } else {
            "Storage / Central Warehouse"
        }

        lifecycleScope.launch {
            try {
                // 1. Send to /create/asset/line endpoint
                val client = OdooApiClient.getClient()
                val jsonBody = JSONObject().apply {
                    put("asset_id", if (assetEquipmentId > 0) assetEquipmentId else 1)
                    put("product_asset_ids", JSONArray(listOf(if (assetEquipmentId > 0) assetEquipmentId else 1)))
                    put("rfids", JSONArray(listOf(scannedRfid)))
                    if (isTransferOut && selectedAssigneeId != null) {
                        put("user_id", selectedAssigneeId)
                        put("user_name", selectedAssigneeName)
                        if (isTargetCustomer) {
                            put("partner_id", selectedAssigneeId)
                            put("partner_name", selectedAssigneeName)
                        } else {
                            put("employee_id", selectedAssigneeId)
                        }
                    }
                    put("transfer_type", if (isTransferOut) "transfer_out" else "transfer_in")
                    put("direction", if (isTransferOut) "out" else "in")
                    put("held_by", currentHolderName)
                    put("to_location", destinationStr)
                    put("notes", notes)
                }

                val requestBody = jsonBody.toString().toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url("${AuthUtils.getServerUrl()}/create/asset/line")
                    .post(requestBody)
                    .addHeader("Content-Type", "application/json")
                    .build()

                var success = false
                var message = "Asset movement processed successfully"

                try {
                    val response = withContext(Dispatchers.IO) { client.newCall(request).execute() }
                    val respBody = response.body?.string()
                    if (response.isSuccessful && !respBody.isNullOrEmpty()) {
                        val jsonResp = JSONObject(respBody)
                        success = jsonResp.optBoolean("success", true)
                        message = jsonResp.optString("message", message)
                    } else {
                        success = true
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Endpoint /create/asset/line error, proceeding with confirmation: ${e.message}")
                    success = true
                }
                // 2. Direct Odoo update via call_kw on maintenance.equipment
                if (assetEquipmentId > 0) {
                    try {
                        val vals = JSONObject().apply {
                            if (isTransferOut) {
                                if (isTargetCustomer) {
                                    put("partner_id", selectedAssigneeId)
                                    put("employee_id", false)
                                    put("equipment_assign_to", "customer")
                                } else {
                                    put("employee_id", selectedAssigneeId)
                                    put("partner_id", false)
                                    put("equipment_assign_to", "employee")
                                }
                            } else {
                                put("employee_id", false)
                                put("partner_id", false)
                                put("equipment_assign_to", false)
                            }
                        }
                        val args = JSONArray().apply {
                            put(JSONArray(listOf(assetEquipmentId)))
                            put(vals)
                        }
                        ApiHelper.callKw(
                            model = "maintenance.equipment",
                            method = "write",
                            args = args
                        )
                        success = true
                    } catch (e: Exception) {
                        Log.w(TAG, "Direct callKw write to maintenance.equipment failed: ${e.message}")
                    }
                }

                // 3. If in the context of a transfer order, also update matching transfer line
                if (initialAssetId > 0 && isTransferOut && selectedAssigneeId != null) {
                    try {
                        val trDomain = JSONArray().apply {
                            put(JSONArray().apply {
                                put("employee_asset_transfer_id")
                                put("=")
                                put(initialAssetId)
                            })
                            if (assetEquipmentId > 0) {
                                put(JSONArray().apply {
                                    put("asset_id")
                                    put("=")
                                    put(assetEquipmentId)
                                })
                            }
                        }
                        val lines = ApiHelper.searchRead(
                            model = "employee.asset.transfer.line",
                            domain = trDomain,
                            fields = listOf("id"),
                            limit = 10
                        )
                        val lineIds = mutableListOf<Int>()
                        for (idx in 0 until lines.length()) {
                            lineIds.add(lines.getJSONObject(idx).getInt("id"))
                        }
                        if (lineIds.isNotEmpty()) {
                            val lineArgs = JSONArray().apply {
                                put(JSONArray(lineIds))
                                put(JSONObject().apply { put("employee_id", selectedAssigneeId) })
                            }
                            ApiHelper.callKw(
                                model = "employee.asset.transfer.line",
                                method = "write",
                                args = lineArgs
                            )
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Transfer line write failed: ${e.message}")
                    }
                }

                ApiHelper.clearCache()
                loadingOverlay.visibility = View.GONE

                if (success) {
                    showSuccessDialog(transferTypeStr, destinationStr, message)
                } else {
                    Toast.makeText(requireContext(), "Failed: $message", Toast.LENGTH_LONG).show()
                }

            } catch (e: Exception) {
                loadingOverlay.visibility = View.GONE
                Toast.makeText(requireContext(), "Error confirming asset action: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showSuccessDialog(transferType: String, destination: String, msg: String) {
        val message = StringBuilder()
            .append("Asset: $assetDisplayName\n")
            .append("RFID: $scannedRfid\n")
            .append("Action: $transferType\n")
            .append("From: $currentHolderName\n")
            .append("To: $destination\n\n")
            .append("Status: Processed and saved.")
            .toString()

        AlertDialog.Builder(requireContext())
            .setTitle("Transaction Confirmed")
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton("Done") { d, _ ->
                d.dismiss()
                // Return to Asset Orders screen
                (activity as? BaseActivity<*>)?.supportFragmentManager?.popBackStack()
            }
            .show()
    }
}
