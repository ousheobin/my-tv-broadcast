package com.steve.mytvbroadcast.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.steve.mytvbroadcast.R
import com.steve.mytvbroadcast.ui.focus.FocusEffects

class SourceFilterAdapter(
    private val onSourceSelected: (Int) -> Unit
) : ListAdapter<SourceFilter, SourceFilterAdapter.SourceFilterViewHolder>(SourceFilterDiffCallback()) {

    private var selectedIndex: Int = 0

    fun setSelectedIndex(index: Int) {
        val oldIndex = selectedIndex
        selectedIndex = index
        if (oldIndex >= 0 && oldIndex < itemCount) {
            notifyItemChanged(oldIndex)
        }
        if (index >= 0 && index < itemCount) {
            notifyItemChanged(index)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SourceFilterViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_source_filter, parent, false)
        return SourceFilterViewHolder(view)
    }

    override fun onBindViewHolder(holder: SourceFilterViewHolder, position: Int) {
        val sourceFilter = getItem(position)
        val isSelected = position == selectedIndex
        holder.bind(sourceFilter, isSelected, position, onSourceSelected)
    }

    inner class SourceFilterViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val nameView: TextView = itemView.findViewById(R.id.source_filter_name)

        fun bind(sourceFilter: SourceFilter, isSelected: Boolean, index: Int, onSourceSelected: (Int) -> Unit) {
            nameView.text = sourceFilter.name

            FocusEffects.enableFocusEffect(itemView)

            itemView.isSelected = isSelected
            nameView.isSelected = isSelected

            itemView.setOnClickListener {
                onSourceSelected(index)
            }
        }
    }

    class SourceFilterDiffCallback : DiffUtil.ItemCallback<SourceFilter>() {
        override fun areItemsTheSame(oldItem: SourceFilter, newItem: SourceFilter): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: SourceFilter, newItem: SourceFilter): Boolean {
            return oldItem == newItem
        }
    }
}
