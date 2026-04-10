package com.steve.mytvbroadcast.ui

import android.graphics.Color
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.steve.mytvbroadcast.R
import com.steve.mytvbroadcast.data.SignalSource

class SourceItemAdapter(
    private var sources: List<SignalSource>,
    private var currentSourceId: String?,
    private val onToggleClicked: (SignalSource) -> Unit,
    private val onDeleteClicked: (SignalSource) -> Unit,
    private val onSourceSelected: (SignalSource) -> Unit
) : RecyclerView.Adapter<SourceItemAdapter.ViewHolder>() {

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val name: TextView = itemView.findViewById(R.id.source_name)
        val url: TextView = itemView.findViewById(R.id.source_url)
        val toggleText: TextView = itemView.findViewById(R.id.toggle_text)
        val deleteText: TextView = itemView.findViewById(R.id.delete_text)
        val currentIndicator: TextView = itemView.findViewById(R.id.current_indicator)

        fun bind(source: SignalSource) {
            val isCurrent = source.id == currentSourceId

            name.text = source.name
            url.text = source.url
            currentIndicator.visibility = if (isCurrent) View.VISIBLE else View.GONE
            toggleText.text = if (isCurrent) "当前" else "切换"
            toggleText.setTextColor(
                if (isCurrent) Color.parseColor("#4CAF50") else Color.parseColor("#888888")
            )

            // 整体卡片点击切换启用状态
            itemView.setOnKeyListener { _, keyCode, event ->
                if ((keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
                    && event.action == KeyEvent.ACTION_UP) {
                    if (!isCurrent && source.enabled) {
                        onSourceSelected(source)
                    }
                    true
                } else {
                    false
                }
            }

            // 切换按钮 - 如果是当前源不起作用，否则切换到该源
            toggleText.setOnKeyListener { _, keyCode, event ->
                if ((keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
                    && event.action == KeyEvent.ACTION_UP) {
                    if (!isCurrent && source.enabled) {
                        onSourceSelected(source)
                    }
                    true
                } else {
                    false
                }
            }
            toggleText.setOnClickListener {
                if (!isCurrent && source.enabled) {
                    onSourceSelected(source)
                }
            }

            // 删除按钮
            deleteText.setOnKeyListener { _, keyCode, event ->
                if ((keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
                    && event.action == KeyEvent.ACTION_UP) {
                    onDeleteClicked(source)
                    true
                } else {
                    false
                }
            }
            deleteText.setOnClickListener {
                onDeleteClicked(source)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_source_card, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(sources[position])
    }

    override fun getItemCount(): Int = sources.size

    fun updateSources(newSources: List<SignalSource>, newCurrentSourceId: String?) {
        sources = newSources
        currentSourceId = newCurrentSourceId
        notifyDataSetChanged()
    }
}
