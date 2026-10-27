package com.steve.mytvbroadcast.ui

import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.steve.mytvbroadcast.R
import com.steve.mytvbroadcast.data.EpgSource

/** EPG 源列表：按顺序展示序号与 URL，支持编辑/删除 */
class EpgSourceAdapter(
    private var sources: List<EpgSource>,
    private val onEditClicked: (EpgSource) -> Unit,
    private val onDeleteClicked: (EpgSource) -> Unit
) : RecyclerView.Adapter<EpgSourceAdapter.ViewHolder>() {

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val index: TextView = itemView.findViewById(R.id.epg_source_index)
        val url: TextView = itemView.findViewById(R.id.epg_source_url)
        val edit: TextView = itemView.findViewById(R.id.epg_source_edit)
        val delete: TextView = itemView.findViewById(R.id.epg_source_delete)

        fun bind(source: EpgSource, position: Int) {
            index.text = "${position + 1}"
            url.text = source.url

            edit.setOnKeyListener { _, keyCode, event ->
                if ((keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
                    && event.action == KeyEvent.ACTION_UP) {
                    onEditClicked(source)
                    true
                } else {
                    false
                }
            }
            edit.setOnClickListener { onEditClicked(source) }

            delete.setOnKeyListener { _, keyCode, event ->
                if ((keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
                    && event.action == KeyEvent.ACTION_UP) {
                    onDeleteClicked(source)
                    true
                } else {
                    false
                }
            }
            delete.setOnClickListener { onDeleteClicked(source) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_epg_source, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(sources[position], position)
    }

    override fun getItemCount(): Int = sources.size

    fun updateSources(newSources: List<EpgSource>) {
        sources = newSources
        notifyDataSetChanged()
    }
}
