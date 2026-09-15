package com.sunmi.uhf.fragment.takeinventory

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.PopupWindow
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.sunmi.rfid.RFIDManager
import com.sunmi.rfid.constant.CMD
import com.sunmi.rfid.constant.ParamCts
import com.sunmi.rfid.entity.DataParameter
import com.sunmi.uhf.App
import com.sunmi.uhf.BuildConfig
import com.sunmi.uhf.R
import com.sunmi.uhf.adapter.LabelInfoAdapter
import com.sunmi.uhf.adapter.TakeModelAdapter
import com.sunmi.uhf.bean.LabelInfoBean
import com.sunmi.uhf.constants.Config
import com.sunmi.uhf.constants.Constant
import com.sunmi.uhf.constants.EventConstant
import com.sunmi.uhf.databinding.FragmentTakeInventoryBinding
import com.sunmi.uhf.dialog.SureBackDialog
import com.sunmi.uhf.event.SimpleViewEvent
import com.sunmi.uhf.fragment.ReadBaseFragment
import com.sunmi.uhf.fragment.deliveryorder.DeliveryMoveItem
import com.sunmi.uhf.fragment.productAsset.ProductAssetItem
import com.sunmi.uhf.fragment.receivingnotes.ReceivingMoveItem
import com.sunmi.uhf.utils.*
import com.sunmi.uhf.service.OdooApiClient
import com.sunmi.uhf.view.RecycleDivider
import com.sunmi.widget.dialog.InputDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

/**
 * @ClassName: TakeInventoryFragment
 * @Description: 盘存 页面
 * @Author: clh
 * @CreateDate: 20-9-9 下午1:38
 * @UpdateDate: 20-9-9 下午1:38
 */
class TakeInventoryFragment : ReadBaseFragment<FragmentTakeInventoryBinding>() {
    private var dialog: SureBackDialog? = null
    lateinit var vm: TakeInventoryModel
    private var receivingItem: ReceivingMoveItem? = null
    private var receivingScanResultListener: ((Int, String) -> Unit)? = null
    private var pickingId: Int? = null
    private var assetId: Int? = null
    private var productAssetItem: ProductAssetItem? = null
    // store asset id passed via arguments (some callers only pass the id, not the full Parcelable)
    private var productAssetArgId: Int? = null
    private var selectedUserId: Int? = null
    private var selectedUserName: String? = null
    private var deliveryScanResultListener: ((List<String>) -> Unit)? = null
    private var productAssetScanResultListener: ((Int, String) -> Unit)? = null
    private var assetScanResultListener: ((List<String>) -> Unit)? = null
    private var isLoop = false
    private var allCount = 0
    private val list = mutableListOf<LabelInfoBean>()
    private lateinit var adapter: LabelInfoAdapter
    private var takeModelPw: PopupWindow? = null
    private var modelAdapter: TakeModelAdapter? = null
    private var exportExcelType = 0
    private var mode = Constant.INT_BALANCE_MODE
    private var tagFocus = Config.DEF_TAKE_TAG_FOCUS
    private var seesion = Config.DEF_TAKE_SESSION
    private var tagFlag = Config.DEF_TAKE_FLAG
    private var link = Config.DEF_TAKE_LINK
    private var power = 30
    private var rate = -1
    private var autoPower = Config.DEF_TAKE_AUTO_POWER
    private val br = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ParamCts.BROADCAST_SN -> {
                    val sn = intent.getStringExtra(ParamCts.SN)
                    handleSN(sn)
                }
                ParamCts.BROADCAST_BATTERY_REMAINING_PERCENTAGE,
                ParamCts.BROADCAST_BATTER_LOW_ELEC -> {
                    val elec = intent.getIntExtra(ParamCts.BATTERY_REMAINING_PERCENT, 100)
                    LogUtils.d("darren", "BroadcastReceiver battery-remaining-percent:$elec%")
                    if (elec <= Config.LOW_ELEC) {
                        showShort(getString(R.string.hint_please_charge, elec))
                    }
                }
                ParamCts.BROADCAST_ON_CONNECT,
                ParamCts.BROADCAST_READER_BOOT -> {
                    handleData()
                }
            }
        }
    }

    override fun getLayoutResource() = R.layout.fragment_take_inventory

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        receivingItem = arguments?.getSerializable(ARG_KEY_RECEIVING_ITEM) as? ReceivingMoveItem
        pickingId = arguments?.getInt(ARG_KEY_PICKING_ID)
        assetId = arguments?.getInt(ARC_KEY_ASSET_ID)
        // productAssetItem may be passed as a Parcelable; try getting it safely
        productAssetItem = arguments?.getParcelable("productAsset_item")
        // but sometimes only an Int id is passed (from ProductAssetDetailFragment). Capture that too.
        productAssetArgId = arguments?.getInt(ARC_KEY_PRODUCT_ASSET_ID)
        selectedUserId = arguments?.getInt("selected_user_id")
        selectedUserName = arguments?.getString("selected_user_name")
    }

    override fun initVM() {
        vm = getViewModel(TakeInventoryModel::class.java)
        binding.vm = vm
    }

    override fun initView() {
        adapter = LabelInfoAdapter()
        binding.labelRv.layoutManager = LinearLayoutManager(activity)
        binding.labelRv.adapter = adapter
        binding.labelRv.addItemDecoration(
            RecycleDivider(
                activity,
                RecycleDivider.HORIZONTAL_LIST,
                resources.getDimensionPixelSize(R.dimen.sunmi_1px),
                ContextCompat.getColor(App.mContext, R.color.dividerColor)
            )
        )
    }

    override fun initData() {
        super.initData()
        vm.receivingVisible.value = false
        vm.deliveryVisible.value = false
        vm.assetVisible.value = false
        vm.productAssetVisible.value = false
        vm.editModel.value = false
        when {
            receivingItem != null -> {
                val it = receivingItem!!
                vm.receivingVisible.value = true
                vm.productAssetVisible.value = false
                vm.deliveryVisible.value = false
                vm.assetVisible.value = false

                vm.receivingProduct.value = it.productName
                vm.receivingLot.value = "Lot: ${if (it.lotName.isEmpty()) "-" else it.lotName}"
                vm.receivingQty.value = "Qty: ${it.quantityDone}/${it.productUomQty}"
                vm.receivingUom.value = "UoM: ${it.uomName}"

                val currentRfid = when {
                    !it.pendingRfid.isNullOrEmpty() -> it.pendingRfid
                    it.rfid.isNotEmpty() -> it.rfid
                    else -> null
                }
                vm.receivingRfid.value = currentRfid?.let { v -> "RFID: $v" } ?: "RFID: -"
                vm.editModel.value = true
            }
            pickingId != null && pickingId != 0 -> {
                vm.deliveryVisible.value = true
                vm.receivingVisible.value = false
                vm.productAssetVisible.value = false
                vm.assetVisible.value = false
                vm.editModel.value = true
            }
            productAssetArgId != null && productAssetArgId != 0 -> {
                vm.productAssetVisible.value = true
                vm.deliveryVisible.value = false
                vm.receivingVisible.value = false
                vm.assetVisible.value = false
                vm.editModel.value = true
            }
            (assetId != null && assetId != 0) || (selectedUserId != null && selectedUserId != 0) -> {
                vm.assetVisible.value = true
                vm.deliveryVisible.value = false
                vm.receivingVisible.value = false
                vm.productAssetVisible.value = false
                vm.editModel.value = true
            }


        }

        adapter.setNewInstance(list)
        vm.topSearchEn.value = !list.isNullOrEmpty()
        vm.start.observe(viewLifecycleOwner, Observer { startStop(it) })
        vm.editModel.observe(viewLifecycleOwner, Observer {
            adapter.editable = it
            adapter.notifyDataSetChanged()
        })
        vm.selectModel.observe(viewLifecycleOwner, Observer {
            modelAdapter?.selected = it
            modelAdapter?.notifyDataSetChanged()
        })
        vm.selectAll.observe(viewLifecycleOwner, Observer {
            if (adapter.selectAll == it) return@Observer
            adapter.selectAll = it
            if (it) {
                if (list.size != adapter.selectData.size) {
                    for (b in list) {
                        adapter.selectData[b.epc!!] = b
                    }
                }
            } else {
                adapter.selectData.clear()
            }
            adapter.notifyDataSetChanged()
            handleBottomStatus()
        })
        adapter.selectAllCall = object : ((Boolean) -> Unit) {
            override fun invoke(en: Boolean) {
                if (isVisible) {
                    vm.selectAll.value = en
                }
            }
        }
        adapter.clickCall = object : (() -> Unit) {
            override fun invoke() {
                if (isVisible) {
                    handleBottomStatus()
                }
            }
        }
        registerBr()
        App.getPref().apply {
            mode = getParam(Config.KEY_TAKE_MODE, Config.DEF_TAKE_MODE)
            tagFocus = getParam(Config.KEY_TAKE_TAG_FOCUS, Config.DEF_TAKE_TAG_FOCUS)
            seesion = getParam(Config.KEY_TAKE_SESSION, Config.DEF_TAKE_SESSION)
            tagFlag = getParam(Config.KEY_TAKE_FLAG, Config.DEF_TAKE_FLAG)
            link = getParam(Config.KEY_TAKE_LINK, Config.DEF_TAKE_LINK)
            autoPower = getParam(Config.KEY_TAKE_AUTO_POWER, Config.DEF_TAKE_AUTO_POWER)
            handleData()
        }
        vm.selectModel.value = vm.modelList[mode - 1]
        RFIDManager.getInstance().apply {
            if (isConnect()) {
                when (getHelper()?.getScanModel()) {
                    RFIDManager.UHF_R2000, RFIDManager.UHF_S7100 -> {
                        vm.labelVisibility.postValue(true)
                    }
                    RFIDManager.INNER -> {
                        vm.labelVisibility.postValue(false)
                    }
                }
            }
        }
    }

    override fun onSimpleViewEvent(event: SimpleViewEvent) {
        super.onSimpleViewEvent(event)
        when (event.event) {
            EventConstant.EVENT_BACK -> {
                if (isLoop) {
                    showSureDialog()
                } else {
                    performBackClick()
                }
            }
            EventConstant.EVENT_TAKE_MODEL -> {
                showModelPopupWindow()
            }
            EventConstant.EVENT_TAKE_MODEL_SEARCH -> {
                if (list.size == 0) {
                    mainScope.launch { showShort(getString(R.string.please_take_inventory_before_proceeding)) }
                    return
                }
                val bundle = Bundle().apply {
                    putParcelableArrayList(Constant.KEY_TAG_LIST, ArrayList<LabelInfoBean>(list))
                }
                switchFragment(
                    SearchModelFragment.newInstance(bundle),
                    addToBackStack = true,
                    clearStack = false
                )
            }
            EventConstant.EVENT_INVENTORY_COPY_EPC -> {
                copyEpcToClipboard()
            }
            EventConstant.EVENT_INVENTORY_SHARE -> {
                shareToApp()
            }
            EventConstant.EVENT_INVENTORY_EXPORT_EXCEL -> {
                exportExcelType = 1
                if (adapter.selectData.size == 0) {
                    mainScope.launch { showShort(getString(R.string.please_take_select_before_proceeding)) }
                    return
                }
                exportExcel()
            }
            EventConstant.EVENT_INVENTORY_EXPORT_EXCEL_ALL -> {
                exportExcelType = 0
                if (list.size == 0) {
                    mainScope.launch { showShort(getString(R.string.please_take_inventory_before_proceeding)) }
                    return
                }
                exportExcel()
            }
            EventConstant.EVENT_RECEIVING_PROCESS -> {
                processReceivingSelection()
            }
            EventConstant.EVENT_DELIVERY_PROCESS -> {
                processDeliverySelection()
            }
            EventConstant.EVENT_ASSET_PROCESS -> {
                processAssetSelection()
            }

            EventConstant.EVENT_PRODUCT_ASSET_PROCESS -> {
                processProductAssetSelection()
            }
            EventConstant.EVENT_TAKE_LABEL_INFO -> {

            }
        }
    }

    fun setReceivingScanResultListener(listener: (Int, String) -> Unit) {
        receivingScanResultListener = listener
    }

    fun handleBottomStatus() {
        vm.editEnExport.postValue(adapter.selectData.size > 0)
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun processReceivingSelection() {
        val item = receivingItem ?: return
        if (adapter.selectData.size == 0) {
            mainScope.launch { showShort(getString(R.string.please_take_select_before_proceeding)) }
            return
        }
        if (adapter.selectData.size > 1) {
            mainScope.launch { showShort(getString(R.string.please_select_single_tag)) }
            return
        }
        val rfidValue = adapter.selectData.values.firstOrNull()?.epc.orEmpty()
        if (rfidValue.isEmpty()) {
            mainScope.launch { showShort(getString(R.string.hint_unknow_error)) }
            return
        }
        vm.receivingRfid.value = "RFID: $rfidValue"
        receivingScanResultListener?.invoke(item.moveId, rfidValue)
        receivingItem = receivingItem?.let { current ->
            val pendingValue = if (rfidValue == current.rfid) null else rfidValue
            current.copy(pendingRfid = pendingValue)
        }
        adapter.selectData.clear()
        adapter.selectAll = false
        adapter.notifyDataSetChanged()
        vm.editEnExport.postValue(false)
        vm.selectAll.postValue(false)
        performBackClick()
    }

    private fun processDeliverySelection() {
        val item = pickingId ?: return
        val rfids = tidList.toList() // Get all scanned RFIDs
        if (rfids.isEmpty()) {
            mainScope.launch { showShort(getString(R.string.please_take_inventory_before_proceeding)) }
            return
        }
        // Query server for product info for each RFID
        checkRfidsAndCreateMoves(item, rfids)
    }

    private fun processAssetSelection() {
        val selectedRfids = adapter.selectData.values.mapNotNull { it.epc }.ifEmpty {
            tidList.toList()
        }
        if (selectedRfids.isEmpty()) {
            mainScope.launch { showShort(getString(R.string.please_take_inventory_before_proceeding)) }
            return
        }

        val rfidToInspect = selectedRfids.first()
        val currentAssetId = assetId ?: 0

        val verificationFragment = com.sunmi.uhf.fragment.asset.AssetVerificationFragment.newInstance(
            rfid = rfidToInspect,
            assetId = currentAssetId
        )
        (activity as? com.sunmi.uhf.base.BaseActivity<*>)?.switchFragment(
            verificationFragment,
            addToBackStack = true,
            clearStack = false
        )
    }

    private fun processProductAssetSelection() {
        // Accept either a full ProductAssetItem or just an asset id passed in args
        val item = productAssetItem
        val itemId = item?.id ?: productAssetArgId ?: return
        if (adapter.selectData.size == 0) {
            mainScope.launch { showShort(getString(R.string.please_take_select_before_proceeding)) }
            return
        }
        if (adapter.selectData.size > 1) {
            mainScope.launch { showShort(getString(R.string.please_select_single_tag)) }
            return
        }
        val rfidValue = adapter.selectData.values.firstOrNull()?.epc.orEmpty()
        if (rfidValue.isEmpty()) {
            mainScope.launch { showShort(getString(R.string.hint_unknow_error)) }
            return
        }

        // Perform server-side check to ensure RFID is available for assignment to product asset
        val client = OdooApiClient.getClient()
        val jsonBody = JSONObject().apply {
            put("rfids", JSONArray().put(rfidValue))
        }
        val requestBody = jsonBody.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("${AuthUtils.getServerUrl()}/check/product/asset/rfid")
            .post(requestBody)
            .addHeader("Content-Type", "application/json")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                mainScope.launch {
                    showShort("Failed to check RFID: ${e.message}")
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string()
                if (response.isSuccessful && body != null) {
                    try {
                        val jsonResponse = JSONObject(body)

                        // If Odoo returns JSON-RPC wrapper, extract result
                        val payload = if (jsonResponse.has("result")) jsonResponse.getJSONObject("result") else jsonResponse

                        val success = payload.optBoolean("success", false)
                        if (success) {
                            val results = payload.optJSONArray("results") ?: JSONArray()
                            if (results.length() > 0) {
                                val first = results.optJSONObject(0)
                                val status = first?.optString("status", "") ?: ""

                                if (status == "available") {
                                    // Proceed with assignment on main thread
                                    mainScope.launch {
                                        vm.receivingRfid.value = "RFID: $rfidValue"
                                        // invoke listener with the resolved id
                                        productAssetScanResultListener?.invoke(itemId, rfidValue)
                                        // update local productAssetItem if we have one
                                        productAssetItem = productAssetItem?.let { current ->
                                            val pendingValue = if (rfidValue == current.rfid) null else rfidValue
                                            current.copy(pendingRfid = pendingValue)
                                        }
                                        adapter.selectData.clear()
                                        adapter.selectAll = false
                                        adapter.notifyDataSetChanged()
                                        vm.editEnExport.postValue(false)
                                        vm.selectAll.postValue(false)
                                        performBackClick()
                                    }
                                } else {
                                    // Server reported RFID already used (ValidationError) or other status
                                    val errorMsg = payload.optString("error", "RFID is not available: $status")
                                    mainScope.launch { showShort(errorMsg) }
                                }
                            } else {
                                mainScope.launch { showShort("No result from server") }
                            }
                        } else {
                            val error = payload.optString("error", "RFID check failed")
                            mainScope.launch { showShort(error) }
                        }
                    } catch (e: Exception) {
                        mainScope.launch {
                            showShort("Error parsing response: ${e.message}")
                        }
                    }
                } else {
                    mainScope.launch {
                        showShort("Server error: ${response.code}")
                    }
                }
            }
        })
    }

    private fun checkRfidsAndCreateMoves(pickingId: Int, rfids: List<String>) {
        val client = OdooApiClient.getClient()
        val jsonBody = JSONObject().apply {
            put("rfids", JSONArray(rfids))
        }
        val requestBody = jsonBody.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("${AuthUtils.getServerUrl()}/check/rfid")
            .post(requestBody)
            .addHeader("Content-Type", "application/json")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                mainScope.launch {
                    showShort("Failed to check RFIDs: ${e.message}")
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string()
                if (response.isSuccessful && body != null) {
                    try {
                        val jsonResponse = JSONObject(body)

                        // Jika Odoo mengembalikan JSON-RPC wrapper, ambil objek result
                        val payload = if (jsonResponse.has("result")) {
                            jsonResponse.getJSONObject("result")
                        } else {
                            jsonResponse
                        }

                        // Ambil success dengan aman
                        val success = payload.optBoolean("success", false)
                        if (success) {
                            val results = payload.optJSONArray("results") ?: JSONArray()
                            val validRfids = mutableListOf<String>()
                            val pendingMoves = AtomicInteger(0)

                            for (i in 0 until results.length()) {
                                val result = results.optJSONObject(i) ?: continue
                                val rfid = result.optString("rfid", "")
                                val productId = result.optInt("product_id", -1)
                                val lotId = result.optInt("lot_id", -1)
                                val status = result.optString("status", "")

                                if (status == "found" && productId != -1) {
                                    validRfids.add(rfid)
                                    pendingMoves.incrementAndGet()
                                    // panggil fungsi createStockMove di background / worker — sesuai implementasimu
                                    createStockMove(pickingId, productId, lotId, rfid) {
                                        if (pendingMoves.decrementAndGet() == 0) {
                                            mainScope.launch { performBackClick() }
                                        }
                                    }
                                }
                            }

                            mainScope.launch {
                                if (validRfids.isNotEmpty()) {
                                    showShort("Processed ${validRfids.size} valid RFIDs")
                                    deliveryScanResultListener?.invoke(validRfids)
                                } else {
                                    showShort("No valid RFIDs found")
                                    performBackClick()
                                }
                            }
                        } else {
                            val error = payload.optString("error", "Unknown error")
                            mainScope.launch {
                                showShort("Check RFID failed: $error")
                                performBackClick()
                            }
                        }
                    } catch (e: Exception) {
                        mainScope.launch {
                            showShort("Error parsing response: ${e.message}")
                            performBackClick()
                        }
                    }
                } else {
                    mainScope.launch {
                        showShort("Server error: ${response.code}")
                        performBackClick()
                    }
                }
            }
        })
    }

    private fun createStockMove(pickingId: Int, productId: Int, lotId: Int?, rfid: String, onComplete: (() -> Unit)? = null) {
        val client = OdooApiClient.getClient()
        val jsonBody = JSONObject().apply {
            put("picking_id", pickingId)
            put("product_id", productId)
            put("quantity", 1)
            put("rfid", rfid)
            if (lotId != null && lotId != -1) {
                put("lot_id", lotId)
            }
        }
        val requestBody = jsonBody.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("${AuthUtils.getServerUrl()}/create/stock/move/line")
            .post(requestBody)
            .addHeader("Content-Type", "application/json")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                // Log error, but don't show to user as it's async
                LogUtils.e("createStockMove", "Failed: ${e.message}")
                onComplete?.invoke()
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string()
                if (response.isSuccessful && body != null) {
                    try {
                        val jsonResponse = JSONObject(body)
                        val success = jsonResponse.getBoolean("success")
                        if (!success) {
                            val error = jsonResponse.optString("error", "Unknown error")
                            LogUtils.e("createStockMove", "Failed: $error")
                        } else {
                            val message = jsonResponse.optString("message", "Move line created")
                            LogUtils.d("createStockMove", message)
                        }
                    } catch (e: Exception) {
                        LogUtils.e("createStockMove", "Error parsing response: ${e.message}")
                    }
                } else {
                    LogUtils.e("createStockMove", "Server error: ${response.code}")
                }
                onComplete?.invoke()
            }
        })
    }

    private fun checkRfidsAndAsset(rfids: List<String>) {
        val client = OdooApiClient.getClient()
        val jsonBody = JSONObject().apply {
            put("rfids", JSONArray(rfids))
        }
        val requestBody = jsonBody.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("${AuthUtils.getServerUrl()}/check/asset/rfid")
            .post(requestBody)
            .addHeader("Content-Type", "application/json")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                mainScope.launch {
                    showShort("Failed to check RFIDs: ${e.message}")
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string()
                if (response.isSuccessful && body != null) {
                    try {
                        val jsonResponse = JSONObject(body)

                        // Jika Odoo mengembalikan JSON-RPC wrapper, ambil objek result
                        val payload = if (jsonResponse.has("result")) {
                            jsonResponse.getJSONObject("result")
                        } else {
                            jsonResponse
                        }

                        // Ambil success dengan aman
                        val success = payload.optBoolean("success", false)
                        if (success) {
                            val results = payload.optJSONArray("results") ?: JSONArray()
                            val validRfids = mutableListOf<String>()
                            val productAssetItems = mutableListOf<Int>()

                            for (i in 0 until results.length()) {
                                val result = results.optJSONObject(i) ?: continue
                                val rfid = result.optString("rfid", "")
                                val productAssetItem = result.optInt("id", -1)
                                val status = result.optString("status", "")

                                if (status == "found" && productAssetItem != -1) {
                                    validRfids.add(rfid)
                                    productAssetItems.add(productAssetItem)
                                }
                            }

                            if (validRfids.isNotEmpty()) {
                                createAsset(productAssetItems, validRfids, selectedUserId, selectedUserName) {
                                    mainScope.launch {
                                        showShort("Processed ${validRfids.size} valid RFIDs")
                                        assetScanResultListener?.invoke(validRfids)
                                        performBackClick()
                                    }
                                }
                            } else {
                                mainScope.launch {
                                    showShort("No valid RFIDs found")
                                    performBackClick()
                                }
                            }
                        } else {
                            val error = payload.optString("error", "Unknown error")
                            mainScope.launch {
                                showShort("Check RFID failed: $error")
                                performBackClick()
                            }
                        }
                    } catch (e: Exception) {
                        mainScope.launch {
                            showShort("Error parsing response: ${e.message}")
                            performBackClick()
                        }
                    }
                } else {
                    mainScope.launch {
                        showShort("Server error: ${response.code}")
                        performBackClick()
                    }
                }
            }
        })
    }

    private fun createAsset(productAssetItems: List<Int>, rfids: List<String>, userId: Int?, userName: String?, onComplete: (() -> Unit)? = null) {
        val client = OdooApiClient.getClient()
        val jsonBody = JSONObject().apply {
            put("asset_id", assetId)
            put("product_asset_ids", JSONArray(productAssetItems))
            put("rfids", JSONArray(rfids))
            if (userId != null) {
                put("user_id", userId)
            }
            if (!userName.isNullOrEmpty()) {
                put("user_name", userName)
            }
        }
        val requestBody = jsonBody.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("${AuthUtils.getServerUrl()}/create/asset/line")
            .post(requestBody)
            .addHeader("Content-Type", "application/json")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                // Log error, but don't show to user as it's async
                LogUtils.e("CreateAsset", "Failed: ${e.message}")
                onComplete?.invoke()
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string()
                if (response.isSuccessful && body != null) {
                    try {
                        val jsonResponse = JSONObject(body)
                        val success = jsonResponse.getBoolean("success")
                        if (!success) {
                            val error = jsonResponse.optString("error", "Unknown error")
                            LogUtils.e("CreateAsset", "Failed: $error")
                        } else {
                            val message = jsonResponse.optString("message", "Asset line created")
                            LogUtils.d("CreateAsset", message)
                        }
                    } catch (e: Exception) {
                        LogUtils.e("CreateAsset", "Error parsing response: ${e.message}")
                    }
                } else {
                    LogUtils.e("CreateAsset", "Server error: ${response.code}")
                }
                onComplete?.invoke()
            }
        })
    }

    /**
     * 弹出显示 盘存模式列表
     */
    private fun showModelPopupWindow() {
        if (takeModelPw == null) {
            val root = View.inflate(context, R.layout.pop_take_model, null)
            val recyclerView = root.findViewById<RecyclerView>(R.id.take_mode_rv)
            modelAdapter = TakeModelAdapter()
            takeModelPw = PopupWindow(
                root,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                this.isOutsideTouchable = true
                modelAdapter?.setNewInstance(vm.createModel())
                recyclerView.layoutManager = LinearLayoutManager(activity)
                recyclerView.adapter = modelAdapter
                modelAdapter?.setOnItemClickListener { _, _, position ->
                    vm.selectModel.value = modelAdapter?.data?.get(position)
                    if (position in 0..3) {
                        mode = position + 1
                        rate = -1
                        handleData()
                    }
                    dismiss()
                }
            }
        }
        takeModelPw?.showAsDropDown(
            binding.filterLl.takeInventoryModelValueTv, 0,
            resources.getDimensionPixelSize(R.dimen.sunmi_8px)
        )
    }

    /**
     * 复制EPC到剪贴板
     */
    private fun copyEpcToClipboard() {
        mainScope.launch(Dispatchers.IO) {
            if (adapter.selectData.size == 0) {
                mainScope.launch { showShort(getString(R.string.please_take_select_before_proceeding)) }
                return@launch
            }
            val info = StringBuffer()
            for (epc in adapter.selectData.keys) {
                if (info.isNotEmpty()) {
                    info.append("\n")
                }
                info.append(epc)
            }
            LogUtils.i("darren", "copy to clipboard: $info")
            mainScope.launch {
                ClipboardUtils.copyStrToClipboard(context, info.toString())
                showShort(getString(R.string.hint_copy_epc_clipboard))
            }
        }
    }

    /**
     * 分享到其他App
     */
    private fun shareToApp() {
        mainScope.launch(Dispatchers.IO) {
            if (adapter.selectData.size == 0) {
                mainScope.launch { showShort(getString(R.string.please_take_select_before_proceeding)) }
                return@launch
            }
            var dir = App.mContext.externalCacheDir ?: App.mContext.cacheDir
            val data = ArrayList<LabelInfoBean>(adapter.selectData.values)
            var file = ExcelUtils.writeTagToExcel("${dir.absolutePath}/tagList", data)
            mainScope.launch {
                ShareUtils.shareFile(activity, file)
            }
        }
    }

    /**
     *  导出Excel 权限/文件名
     *
     *  @param type 类型 0：全部，1：选择的
     */
    private fun exportExcel() {
        context?.let {
            if (ActivityCompat.checkSelfPermission(
                    it,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQUEST_PERMISSION_ID)
                showShort(R.string.please_allow_read_write_sd_card)
                return
            }
        }
        val dialog = InputDialog.Builder()
            .setTitle(getString(R.string.please_input_file_name))
            .setHint(getString(R.string.please_input_file_name))
            .setEditType(true)
            .setLeftText(getString(R.string.cancel_text))
            .setRightText(getString(R.string.sure_text))
            .build(context)
        dialog.setCallback(object : InputDialog.DialogOnClickCallback {
            override fun left(text: String?) {
                dialog.cancel()
            }

            override fun middle(text: String?) {
            }

            override fun right(text: String?) {
                LogUtils.i("darren", "file name: $text")
                if (text != null) {
                    if (text.trim().isEmpty()) {
                        dialog.inputError()
                        return
                    } else {
                        //exportExcel(text, Environment.getExternalStorageDirectory().absolutePath)
                        exportExcel(text, context?.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS).toString())
                        dialog.dismiss()
                    }
                } else {
                    dialog.inputError()
                }
            }
        })
        dialog.show()
    }

    /**
     * 保存Excel文件到SD卡
     */
    private fun exportExcel(fileName: String, path: String) {
        mainScope.launch(Dispatchers.IO) {
            LogUtils.i("darren", "export Excel dir:$path")
            val file = "$path/$fileName"
            val data = ArrayList<LabelInfoBean>()
            if (exportExcelType == 0) {
                data.addAll(list)
            } else if (exportExcelType == 1) {
                data.addAll(adapter.selectData.values)
            }
            if (data.size == 0) {
                return@launch
            }
            ExcelUtils.writeTagToExcel(file, data)
            mainScope.launch {
                showShort(getString(R.string.hint_excel_save_to_sd))
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSION_ID) {
            if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                exportExcel()
            } else {
                showShort(R.string.please_allow_read_write_sd_card)
            }
        }
    }

    /**
     * 点击返回健后，弹出二次确认弹窗
     * 点击退出 ，退出页面
     */
    private fun showSureDialog() {
        val showing = (dialog?.isAdded ?: false || dialog?.dialog?.isShowing ?: false)
        if (showing) dialog?.dismiss()
        if (dialog == null) {
            dialog = SureBackDialog.newInstance(null)
        }
        dialog?.listener = object : (() -> Unit) {
            override fun invoke() {
                dialog?.dismiss()
                stop()
                handler.post(Runnable { performBackClick() })
            }
        }
        dialog?.show(parentFragmentManager, SureBackDialog::class.java.name)
    }

    override fun onBackPress(): Boolean {
        if (isLoop) {
            showSureDialog()
            return true
        }
        return super.onBackPress()
    }

    private fun registerBr() {
        context?.registerReceiver(br, IntentFilter().apply {
            addAction(ParamCts.BROADCAST_SN)
            addAction(ParamCts.BROADCAST_BATTERY_REMAINING_PERCENTAGE)
            addAction(ParamCts.BROADCAST_BATTER_LOW_ELEC)
            addAction(ParamCts.BROADCAST_ON_CONNECT)
            addAction(ParamCts.BROADCAST_READER_BOOT)
        })
        RFIDManager.getInstance().apply {
            if (isConnect()) getHelper()?.getReaderSN()
        }
    }

    private fun unregisterBr() {
        context?.unregisterReceiver(br)
    }

    override fun onPause() {
        startStop(false)
        vm.start.postValue(false)
        super.onPause()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        dialog?.dismiss()
        takeModelPw?.dismiss()
        unregisterBr()
        receivingScanResultListener = null
    }


    private fun startStop(en: Boolean) {
        if (isLoop == en) return
//        vm.start.postValue(en)
        if (en) {
            tidList.clear()
            tagList.clear()
            list.clear()
            allCount = 0
            rate = -1
            vm.labelNum.value = 0
            vm.totalNum.value = 0
            vm.speed.value = 0
            binding.basicLl.timeValueTv.base = SystemClock.elapsedRealtime()
            binding.basicLl.timeValueTv.start()
            notifyTagDataChange()
            start()
        } else {
            binding.basicLl.timeValueTv.stop()
            stop()
        }
    }

    private fun getPowerSave(): Byte {
        try {
            val time = (SystemClock.elapsedRealtime() - binding.basicLl.timeValueTv.base) / 1000 / 60
            return if (autoPower && time > 10) {
                min((time - 10) * 5, 100).toByte()
            } else {
                0.toByte()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return 0.toByte()
    }

    override fun handleBottomStart() {
        vm.start.value = true
    }

    override fun handleBottomStop() {
        vm.start.value = false
    }

    override fun start() {
        super.start()
        if (!isLoop) {
            RFIDManager.getInstance().getHelper()?.apply {
                when (App.getPref().getParam(Config.KEY_LABEL, Config.DEF_LABEL)) {
                    1 -> {
                        // 6C标签盘存
                        registerReaderCall(call)
                        when (mode) {
                            Constant.INT_BALANCE_MODE -> {
                                customizedSessionTargetInventory(
                                    0x01.toByte(),
                                    0x00.toByte(),
                                    0x00.toByte(),
                                    0x00.toByte(),
                                    getPowerSave(),
                                    1
                                )
                            }
                            Constant.INT_SPEED_MODE -> {
                                realTimeInventory(1)
                            }
                            Constant.INT_ITERATOR_MODE -> {
                                customizedSessionTargetInventory(
                                    0x02.toByte(),
                                    0x00.toByte(),
                                    0x00.toByte(),
                                    0x00.toByte(),
                                    getPowerSave(),
                                    1
                                )
                            }
                            Constant.INT_CUSTOM_MODE -> {
                                setOutputAllPower(App.getPref().getParam(Config.KEY_RF_POWER, Config.DEF_INNER_POWER_MAX).toByte())
                                customizedSessionTargetInventory(
                                    seesion.toByte(),
                                    tagFlag.toByte(),
                                    0x00.toByte(),
                                    0x00.toByte(),
                                    getPowerSave(),
                                    1
                                )
                            }
                        }
                        isLoop = true
                    }
                    else -> {
                        LogUtils.e("darren", "error label index")
                    }
                }
            }
        }
    }

    override fun stop() {
        super.stop()
        if (isLoop) {
            RFIDManager.getInstance().getHelper()?.apply {
                inventory(1)
                unregisterReaderCall()
                isLoop = false
            }
        }
    }

    override fun onCallSuccess(cmd: Byte, params: DataParameter?) {
        when (cmd) {
            CMD.REAL_TIME_INVENTORY,
            CMD.CUSTOMIZED_SESSION_TARGET_INVENTORY -> {
                isLoop = false
                if (state) {
                    start()
                }
                if (params != null) {
                    rate = params.getInt(ParamCts.READ_RATE, -1)
                    rate = if (rate == 0) -1 else rate
                    notifyTagDataChange()
                }
            }
            /*CMD.ISO18000_6B_INVENTORY -> {
                isLoop = false
                if (state) {
                    start()
                }
            }*/
            CMD.SET_OUTPUT_POWER -> {
                LogUtils.i("darren", "set out power success.")
            }
            CMD.SET_AND_SAVE_IMPINJ_FAST_TID_TAG_FOCUS -> {
                LogUtils.i("darren", "set tag focus success.")
            }
            CMD.SET_RF_LINK_PROFILE -> {
                LogUtils.i("darren", "set rf link profile success.")
            }
            else -> {
                LogUtils.d("darren", "other success.")
            }
        }
    }

    override fun onCallTag(cmd: Byte, state: Byte, tag: DataParameter?) {
        if (tag == null) return
        playTips()
        when (cmd) {
            CMD.REAL_TIME_INVENTORY,
            CMD.CUSTOMIZED_SESSION_TARGET_INVENTORY -> {
                // 6C标签盘存
                allCount++
                // ANT_ID、TAG_PC、TAG_EPC、TAG_RSSI、TAG_READ_COUNT、TAG_FREQ、TAG_TIME
                val epc = tag.getString(ParamCts.TAG_EPC) ?: ""
                val pc = tag.getString(ParamCts.TAG_PC) ?: ""
                val rssi = "${(Integer.parseInt(tag.getString(ParamCts.TAG_RSSI, "129")) - 129)}"
                val freq = tag.getString(ParamCts.TAG_FREQ) ?: ""
                LogUtils.i("darren", "found tag:$epc => $tag")
                val index = tidList.indexOf(epc)
                if (index != -1) {
                    val c = tagList[index].getInt(ParamCts.TAG_READ_COUNT, 1) + 1
                    tag.put(ParamCts.TAG_READ_COUNT, c)
                    tagList[index] = tag
                    list[index] = LabelInfoBean(epc, pc, c, rssi, freq)
                } else {
                    tidList.add(0, epc)
                    tagList.add(0, tag)
                    list.add(0, LabelInfoBean(epc, pc, 1, rssi, freq))
                }
                notifyTagDataChange()
            }
            /*CMD.ISO18000_6B_INVENTORY -> {
                allCount++
                val uid = tag.getString(ParamCts.TAG_UID) ?: ""
                LogUtils.i("darren", "found tag:$uid")
                val index: Int = tidList.indexOf(uid)
                if (index != -1) {
                    tagList[index] = tag
                } else {
                    tidList.add(0, uid)
                    tagList.add(0, tag)
                }
                notifyTagDataChange()
            }*/
            else -> {
                LogUtils.d("darren", "other found tag.")
            }
        }
    }

    override fun onCallFailed(cmd: Byte, errorCode: Byte, msg: String?) {
        when (cmd) {
            CMD.REAL_TIME_INVENTORY,
            CMD.CUSTOMIZED_SESSION_TARGET_INVENTORY -> {
                isLoop = false
                if (state) {
                    start()
                }
            }
            /*CMD.ISO18000_6B_INVENTORY -> {
                isLoop = false
                if (state) {
                    start()
                }
            }*/
            CMD.SET_OUTPUT_POWER -> {
                LogUtils.i("darren", "set out power failed.")
            }
            CMD.SET_AND_SAVE_IMPINJ_FAST_TID_TAG_FOCUS -> {
                LogUtils.i("darren", "set tag focus failed.")
            }
            CMD.SET_RF_LINK_PROFILE -> {
                LogUtils.i("darren", "set rf link profile failed.")
            }
            else -> {
                LogUtils.d("darren", "other failed.")
            }
        }
    }

    private fun handleSN(sn: String?) {
        val rfBand = ParamCts.getRFFrequencyBand(sn ?: "")
        when (rfBand[3]) {
            1 -> {
                power = 30
            }
            2 -> {
                power = 28
            }
            3 -> {
                power = 29
            }
        }
        RFIDManager.getInstance().apply {
            if (isConnect() && rfBand[0] == 1) {
                getHelper()?.setOutputAllPower(power.toByte())
            }
        }
    }

    private fun handleData() {
        RFIDManager.getInstance().apply {
            if (isConnect()) {
                getHelper()?.apply {
                    registerReaderCall(call)
                    when (mode) {
                        Constant.INT_CUSTOM_MODE -> {
                            setRfLinkProfile((0xD0 + link).toByte())
                            setImpinjFastTid(seesion == 1 && tagFocus, false)
                        }
                        else -> {
                            setRfLinkProfile(0xD1.toByte())
                            setImpinjFastTid(mode == Constant.INT_BALANCE_MODE, false)
                        }
                    }
                    setMask1Tag()
                    setMask2Tag()
                }
            }
        }
    }

    private fun setMask1Tag() {
        var en: Boolean = App.getPref().getParam(Config.KEY_FILTER_ENABLE_1, false)
        if (!en) return
        App.getPref().setParam(Config.KEY_FILTER_ENABLE_1, en)
        RFIDManager.getInstance().apply {
            if (isConnect()) {
                if (en) {
                    val info = App.getPref().getParam(Config.KEY_FILTER_INFO_1, Config.DEF_FILTER_INFO)
                    val area = App.getPref().getParam(Config.KEY_FILTER_AREA_1, Config.DEF_FILTER_AREA)
                    val startAdd = App.getPref().getParam(Config.KEY_FILTER_START_ADD_1, Config.DEF_FILTER_START_ADD)
                    val rule = App.getPref().getParam(Config.KEY_FILTER_RULE_1, Config.DEF_FILTER_RULE)
                    val target = App.getPref().getParam(Config.KEY_FILTER_TARGET_1, Config.DEF_FILTER_TARGET)
                    val infoList = StrUtils.stringToStringArray(info, 2)
                    val maskValue = StrUtils.stringArrayToByteArray(infoList, infoList?.size ?: 0)
                    getHelper()?.setTagMask(
                        0x01,
                        target.toByte(),
                        rule.toByte(),
                        area.toByte(),
                        startAdd.toByte(),
                        ((maskValue?.size ?: 0) * 8).toByte(),
                        maskValue
                    )
                }
            }
        }
    }

    private fun setMask2Tag() {
        var en: Boolean = App.getPref().getParam(Config.KEY_FILTER_ENABLE_2, false)
        if (!en) return
        RFIDManager.getInstance().apply {
            if (isConnect()) {
                if (en) {
                    val info = App.getPref().getParam(Config.KEY_FILTER_INFO_2, Config.DEF_FILTER_INFO)
                    val area = App.getPref().getParam(Config.KEY_FILTER_AREA_2, Config.DEF_FILTER_AREA)
                    val startAdd = App.getPref().getParam(Config.KEY_FILTER_START_ADD_2, Config.DEF_FILTER_START_ADD)
                    val rule = App.getPref().getParam(Config.KEY_FILTER_RULE_2, Config.DEF_FILTER_RULE)
                    val target = App.getPref().getParam(Config.KEY_FILTER_TARGET_2, Config.DEF_FILTER_TARGET)
                    val infoList = StrUtils.stringToStringArray(info, 2)
                    val maskValue = StrUtils.stringArrayToByteArray(infoList, infoList?.size ?: 0)
                    getHelper()?.setTagMask(
                        0x02,
                        target.toByte(),
                        rule.toByte(),
                        area.toByte(),
                        startAdd.toByte(),
                        ((maskValue?.size ?: 0) * 8).toByte(),
                        maskValue
                    )
                }
            }
        }
    }


    private fun notifyTagDataChange() {
        mainScope.launch {
            vm.labelNum.value = tidList.size
            vm.totalNum.value = allCount
            vm.topSearchEn.value = !list.isNullOrEmpty()
            if (rate == -1) {
                val time = (SystemClock.elapsedRealtime() - binding.basicLl.timeValueTv.base) / 1000
                if (time < 1) {
                    vm.speed.value = allCount
                } else {
                    vm.speed.value = (allCount / time).toInt()
                }
            } else {
                vm.speed.value = rate
            }
            adapter.notifyDataSetChanged()
        }
    }

    fun setDeliveryScanResultListener(listener: (List<String>) -> Unit) {
        deliveryScanResultListener = listener
    }

    fun setProductAssetScanResultListener(listener: (Int, String) -> Unit) {
        productAssetScanResultListener = listener
    }

    fun setAssetScanResultListener(listener: (List<String>) -> Unit) {
        assetScanResultListener = listener
    }

    companion object {
        fun newInstance(args: Bundle?) = TakeInventoryFragment()
            .apply { arguments = args }

        const val ARG_KEY_RECEIVING_ITEM = "arg_receiving_item"
        const val ARG_KEY_PICKING_ID = "arg_picking_id"
        const val ARC_KEY_ASSET_ID = "arc_asset_id"
        const val ARC_KEY_PRODUCT_ASSET_ID = "arc_product_asset_id"
        const val REQUEST_PERMISSION_ID = 101
    }
}

