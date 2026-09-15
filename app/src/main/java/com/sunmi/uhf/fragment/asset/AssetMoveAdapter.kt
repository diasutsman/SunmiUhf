package com.sunmi.uhf.fragment.asset

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.sunmi.uhf.R

class AssetMoveAdapter(
    private var items: List<AssetMoveItem>,
    private val onItemClick: ((AssetMoveItem) -> Unit)? = null
) : RecyclerView.Adapter<AssetMoveAdapter.ViewHolder>() {

    fun updateData(newItems: List<AssetMoveItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val root: View = view
        val txtProduct: TextView = view.findViewById(R.id.txtProduct)
        val txtLot: TextView = view.findViewById(R.id.txtLot)
        val txtCategory: TextView = view.findViewById(R.id.txtCategory)
        val txtEmployee: TextView = view.findViewById(R.id.txtEmployee)
        val txtHeldBy: TextView = view.findViewById(R.id.txtHeldBy)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_asset_move, parent, false)
        return ViewHolder(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.txtProduct.text = if (item.asset.isNotBlank() && item.asset != "false") item.asset else "-"
        
        val rfidVal = if (item.rfid.isNotBlank() && item.rfid != "false") item.rfid else "-"
        holder.txtLot.visibility = View.VISIBLE
        holder.txtLot.text = "RFID: $rfidVal"

        holder.txtCategory.text = "Category: ${if (item.category.isNotBlank() && item.category != "false") item.category else "-"}"
        val fromText = if (item.heldBy.isNotBlank() && item.heldBy != "false") item.heldBy else "-"
        val toText = if (item.employee.isNotBlank() && item.employee != "false") item.employee else "-"
        holder.txtHeldBy.text = "From: $fromText"
        holder.txtEmployee.text = "To: $toText"

        holder.root.setOnClickListener {
            onItemClick?.invoke(item)
        }
    }
}
