package com.steve.mytvbroadcast.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.steve.mytvbroadcast.R
import com.steve.mytvbroadcast.data.SignalSource

class SourceButtonAdapter(
    private val onSourceClick: (SignalSource) -> Unit
) : RecyclerView.Adapter<SourceButtonAdapter.ViewHolder>() {

    private var sources = listOf<SignalSource>()
    private var currentSourceId: String? = null

    fun setSources(sources: List<SignalSource>, currentId: String?) {
        this.sources = sources
        this.currentSourceId = currentId
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_source_button, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val source = sources[position]
        holder.bind(source, source.id == currentSourceId)
    }

    override fun getItemCount() = sources.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val nameView: TextView = itemView.findViewById(R.id.source_name)

        fun bind(source: SignalSource, isSelected: Boolean) {
            nameView.text = source.name
            nameView.setTextColor(if (isSelected) 0xFFFFFFFF.toInt() else 0xFF888888.toInt())
            itemView.isSelected = isSelected

            itemView.setOnClickListener {
                onSourceClick(source)
            }

            itemView.setOnFocusChangeListener { _, hasFocus ->
                nameView.setTextColor(when {
                    hasFocus -> 0xFFFFFFFF.toInt()
                    isSelected -> 0xFFFFFFFF.toInt()
                    else -> 0xFF888888.toInt()
                })
            }
        }
    }
}
