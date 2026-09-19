package com.example.nfcwritter.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.nfcwritter.R

data class RowItem(val id: Long, val primary: String, val secondary: String)

class RowAdapter(private val onClick: (Long) -> Unit) : RecyclerView.Adapter<RowAdapter.Holder>() {
    private var items: List<RowItem> = emptyList()

    fun submit(newItems: List<RowItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
        LayoutInflater.from(parent.context).inflate(R.layout.item_list_row, parent, false)
    )
    override fun getItemCount() = items.size
    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val primary = view.findViewById<TextView>(R.id.primary)
        private val secondary = view.findViewById<TextView>(R.id.secondary)
        fun bind(item: RowItem) {
            primary.text = item.primary
            secondary.text = item.secondary
            itemView.setOnClickListener { onClick(item.id) }
        }
    }
}
