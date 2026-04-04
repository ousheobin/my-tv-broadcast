package com.steve.mytvbroadcast.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.steve.mytvbroadcast.R
import com.steve.mytvbroadcast.data.SignalSource
import com.steve.mytvbroadcast.ui.focus.FocusEffects

class SourceAdapter(
    private val onSourceSelected: (SignalSource) -> Unit
) : ListAdapter<SignalSource, SourceAdapter.SourceViewHolder>(SourceDiffCallback()) {

    private var selectedSourceId: String? = null

    fun setSelectedSource(sourceId: String?) {
        val oldId = selectedSourceId
        selectedSourceId = sourceId
        if (oldId != null) {
            val oldIndex = currentList.indexOfFirst { it.id == oldId }
            if (oldIndex >= 0) notifyItemChanged(oldIndex)
        }
        if (sourceId != null) {
            val newIndex = currentList.indexOfFirst { it.id == sourceId }
            if (newIndex >= 0) notifyItemChanged(newIndex)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SourceViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_source_horizontal, parent, false)
        return SourceViewHolder(view)
    }

    override fun onBindViewHolder(holder: SourceViewHolder, position: Int) {
        val source = getItem(position)
        val isSelected = source.id == selectedSourceId
        holder.bind(source, isSelected, onSourceSelected)
    }

    inner class SourceViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val nameView: TextView = itemView.findViewById(R.id.source_name)

        fun bind(source: SignalSource, isSelected: Boolean, onSourceSelected: (SignalSource) -> Unit) {
            nameView.text = source.name

            // Enable focus effect
            FocusEffects.enableFocusEffect(itemView)

            // Update selection state
            itemView.isSelected = isSelected

            // Handle click
            itemView.setOnClickListener {
                onSourceSelected(source)
            }
        }
    }

    class SourceDiffCallback : DiffUtil.ItemCallback<SignalSource>() {
        override fun areItemsTheSame(oldItem: SignalSource, newItem: SignalSource): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: SignalSource, newItem: SignalSource): Boolean {
            return oldItem == newItem
        }
    }
}
