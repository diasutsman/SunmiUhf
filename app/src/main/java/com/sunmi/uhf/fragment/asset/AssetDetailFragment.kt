package com.sunmi.uhf.fragment.asset

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.sunmi.uhf.BuildConfig
import com.sunmi.uhf.R
import android.widget.Toast
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.sunmi.uhf.fragment.takeinventory.TakeInventoryFragment
import com.sunmi.uhf.utils.AuthUtils
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.sunmi.uhf.service.ApiHelper
import com.sunmi.uhf.service.OdooApiClient
import okhttp3.*
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
    private lateinit var txtScheduledDate: TextView
    private lateinit var txtOrigin: TextView
    private lateinit var txtState: TextView
    private lateinit var txtPartner: TextView
    private var shouldRefreshOnResume = false
    private var creatorName: String = ""

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

        // RecyclerView
        adapter = AssetMoveAdapter(emptyList())
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

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
        if (shouldRefreshOnResume) {
            shouldRefreshOnResume = false
            loadMoveLines()
        }
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
                                asset = assetName,
                                category = categoryName,
                                heldBy = heldByName,
                                employee = employeeName,
                                rfid = rfid
                            )
                        )
                    }

                    // Update header direction
                    txtTransferType.text = "Transfer Type: Transfer Out"
                    txtFromHolder.text = "From: $firstHeldBy"
                    txtToEmployee.text = "To: $firstEmployee"

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
                        val rfidVal = line.optString("rfid", "-")
                        val heldByVal = line.optString("held_by", "")
                        val heldBy = if (heldByVal.isNotBlank() && heldByVal != "false") heldByVal else creatorName.ifBlank { "Storage" }
                        list.add(
                            AssetMoveItem(
                                asset = line.optString("asset_id", line.optString("name", "-")),
                                category = line.optString("category", "-"),
                                heldBy = heldBy,
                                employee = line.optString("employee", "-"),
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
}
