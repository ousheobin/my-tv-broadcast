package com.steve.mytvbroadcast.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.steve.mytvbroadcast.R
import com.steve.mytvbroadcast.data.ChannelSource
import com.steve.mytvbroadcast.ui.focus.FocusEffects

class SourceSwitchAdapter(
    private val onSourceSelected: (ChannelSource) -> Unit
) : ListAdapter<ChannelSource, SourceSwitchAdapter.SourceViewHolder>(SourceDiffCallback()) {

    private var currentSourceId: String? = null

    fun setCurrentSource(sourceId: String?) {
        android.util.Log.d("SourceSwitchAdapter", "setCurrentSource: oldId=$currentSourceId, newId=$sourceId, listSize=${currentList.size}")
        if (currentSourceId != sourceId) {
            val oldId = currentSourceId
            currentSourceId = sourceId
            android.util.Log.d("SourceSwitchAdapter", "setCurrentSource: changed, notifying oldId=$oldId, newId=$sourceId")
            // 通知旧的和新选中的item更新
            if (oldId != null) {
                val oldIndex = currentList.indexOfFirst { it.sourceId == oldId }
                android.util.Log.d("SourceSwitchAdapter", "setCurrentSource: oldIndex=$oldIndex")
                if (oldIndex >= 0) notifyItemChanged(oldIndex)
            }
            if (sourceId != null) {
                val newIndex = currentList.indexOfFirst { it.sourceId == sourceId }
                android.util.Log.d("SourceSwitchAdapter", "setCurrentSource: newIndex=$newIndex")
                if (newIndex >= 0) notifyItemChanged(newIndex)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SourceViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_source_switch, parent, false)
        return SourceViewHolder(view)
    }

    override fun onBindViewHolder(holder: SourceViewHolder, position: Int) {
        val source = getItem(position)
        val isCurrent = source.sourceId == currentSourceId
        android.util.Log.d("SourceSwitchAdapter", "onBindViewHolder: position=$position, sourceId=${source.sourceId}, currentSourceId=$currentSourceId, isCurrent=$isCurrent")
        holder.bind(source, position, isCurrent, onSourceSelected)
    }

    inner class SourceViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val indexView: TextView = itemView.findViewById(R.id.source_index)
        private val nameView: TextView = itemView.findViewById(R.id.source_name)
        private val qualityView: TextView = itemView.findViewById(R.id.source_quality)
        private val indicator: View = itemView.findViewById(R.id.current_indicator)

        fun bind(source: ChannelSource, position: Int, isCurrent: Boolean, onSourceSelected: (ChannelSource) -> Unit) {
            android.util.Log.d("SourceSwitchAdapter", "bind: position=$position, isCurrent=$isCurrent, sourceId=${source.sourceId}")
            // 编号
            indexView.text = "${position + 1}"

            // 信号源名称
            nameView.text = source.sourceName

            // 信号质量
            if (source.speed >= 0) {
                qualityView.text = when {
                    source.speed < 300 -> "好"
                    else -> "一般"
                }
                qualityView.setTextColor(when {
                    source.speed < 300 -> 0xFF4CAF50.toInt()
                    else -> 0xFFFFEB3B.toInt()
                })
            } else {
                qualityView.text = "未测速"
                qualityView.setTextColor(0xFF888888.toInt())
            }

            indicator.visibility = if (isCurrent) View.VISIBLE else View.GONE
            itemView.isSelected = isCurrent
            android.util.Log.d("SourceSwitchAdapter", "bind: after setSelected(${isCurrent}), itemView.isSelected=${itemView.isSelected}")

            FocusEffects.enableFocusEffect(itemView)

            itemView.setOnClickListener {
                onSourceSelected(source)
            }
        }
    }

    class SourceDiffCallback : DiffUtil.ItemCallback<ChannelSource>() {
        override fun areItemsTheSame(oldItem: ChannelSource, newItem: ChannelSource): Boolean {
            return oldItem.sourceId == newItem.sourceId
        }

        override fun areContentsTheSame(oldItem: ChannelSource, newItem: ChannelSource): Boolean {
            return oldItem == newItem
        }
    }
}
