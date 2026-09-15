package com.sunmi.uhf.fragment.asset

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.recyclerview.widget.RecyclerView
import com.sunmi.uhf.R

class AssetAdapter(
    initialList: MutableList<AssetItem>,
    private val onItemClick: (AssetItem) -> Unit
) : RecyclerView.Adapter<AssetAdapter.ViewHolder>() {

    // Keep master and display lists for filtering
    private val fullList: MutableList<AssetItem> = mutableListOf()
    private val displayList: MutableList<AssetItem> = mutableListOf()

    init {
        fullList.addAll(initialList)
        displayList.addAll(initialList)
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val card: CardView = view.findViewById(R.id.cardAsset)
        val name: TextView = view.findViewById(R.id.txtAssetName)
        val state: TextView = view.findViewById(R.id.txtState)
        val transferType: TextView = view.findViewById(R.id.txtTransferType)
        val fromHolder: TextView = view.findViewById(R.id.txtFromHolder)
        val toEmployee: TextView = view.findViewById(R.id.txtToEmployee)
        val date: TextView = view.findViewById(R.id.txtScheduledDate)
        val partner: TextView = view.findViewById(R.id.txtPartner)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_asset, parent, false)
        return ViewHolder(view)
    }

    override fun getItemCount(): Int = displayList.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = displayList[position]
        holder.name.text = item.name
        holder.state.text = item.state.uppercase()

        // Transfer type text and color
        val displayType = item.displayTransferType
        holder.transferType.text = displayType
        when {
            displayType.contains("Out", ignoreCase = true) -> {
                holder.transferType.setTextColor(Color.parseColor("#E65100")) // Amber/Orange
            }
            displayType.contains("In", ignoreCase = true) || displayType.contains("Return", ignoreCase = true) -> {
                holder.transferType.setTextColor(Color.parseColor("#2E7D32")) // Green
            }
            else -> {
                holder.transferType.setTextColor(Color.parseColor("#216eff")) // Blue
            }
        }

        // From Holder and To Employee
        val fromText = if (item.fromHolder.isNotBlank() && item.fromHolder != "false") item.fromHolder else "-"
        val toText = if (item.toEmployee.isNotBlank() && item.toEmployee != "false") item.toEmployee else "-"
        holder.fromHolder.text = "From: $fromText"
        holder.toEmployee.text = "To: $toText"

        // Scheduled Date
        val dateText = if (item.dueDate.isNotBlank() && item.dueDate != "false") item.dueDate else "-"
        holder.date.text = "Scheduled Date: $dateText"

        holder.partner.text = item.partnerName
        holder.card.setOnClickListener { onItemClick(item) }
    }

    // Replace the whole dataset (master + display)
    fun updateData(newList: List<AssetItem>) {
        fullList.clear()
        fullList.addAll(newList)

        displayList.clear()
        displayList.addAll(newList)
        notifyDataSetChanged()
    }

    // Append page
    fun appendData(newList: List<AssetItem>) {
        if (newList.isEmpty()) return
        val startFull = fullList.size
        fullList.addAll(newList)
        if (displayList.size == startFull) {
            val start = displayList.size
            displayList.addAll(newList)
            notifyItemRangeInserted(start, newList.size)
        } else {
            notifyDataSetChanged()
        }
    }

    /**
     * Filter by name, transfer type, from holder, to employee, or state.
     */
    fun filter(query: String) {
        val q = query.trim()
        if (q.isEmpty()) {
            displayList.clear()
            displayList.addAll(fullList)
            notifyDataSetChanged()
            return
        }

        val lower = q.lowercase()
        val filtered = fullList.filter { item ->
            item.name.lowercase().contains(lower)
                    || item.displayTransferType.lowercase().contains(lower)
                    || item.fromHolder.lowercase().contains(lower)
                    || item.toEmployee.lowercase().contains(lower)
                    || item.partnerName.lowercase().contains(lower)
                    || item.state.lowercase().contains(lower)
        }
        displayList.clear()
        displayList.addAll(filtered)
        notifyDataSetChanged()
    }
}

