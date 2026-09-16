package com.sunmi.uhf.fragment.asset

data class AssetMoveItem(
    val lineId: Int = 0,
    val equipmentId: Int = 0,
    val asset: String,
    val category: String,
    val heldBy: String,
    val employee: String,
    val employeeId: Int = 0,
    val rfid: String,
    val isAssigned: Boolean = (employee.isNotBlank() && employee != "-" && employee != "false" && employee != "Unassigned")
)
