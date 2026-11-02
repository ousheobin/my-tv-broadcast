package com.steve.mytvbroadcast.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.steve.mytvbroadcast.R
import com.steve.mytvbroadcast.data.Channel

/** 一个分类对应一行频道 */
data class ChannelRow(val title: String, val channels: List<Channel>)

/** P2 全部频道页：外层纵向行列表，每行内嵌横向卡片列表 */
class ChannelRowAdapter(
    private val onChannelClick: (Channel) -> Unit
) : RecyclerView.Adapter<ChannelRowAdapter.RowViewHolder>() {

    private var rows: List<ChannelRow> = emptyList()

    fun submit(rows: List<ChannelRow>) {
        this.rows = rows
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_channel_row, parent, false)
        return RowViewHolder(view, onChannelClick)
    }

    override fun onBindViewHolder(holder: RowViewHolder, position: Int) {
        holder.bind(rows[position])
    }

    override fun getItemCount(): Int = rows.size

    class RowViewHolder(itemView: View, onChannelClick: (Channel) -> Unit) :
        RecyclerView.ViewHolder(itemView) {

        private val titleView: TextView = itemView.findViewById(R.id.row_title)
        private val rowRecycler: RecyclerView = itemView.findViewById(R.id.row_channels)
        private val innerAdapter = ChannelAdapter(onChannelClick)

        init {
            rowRecycler.layoutManager =
                LinearLayoutManager(itemView.context, LinearLayoutManager.HORIZONTAL, false)
            rowRecycler.adapter = innerAdapter
        }

        fun bind(row: ChannelRow) {
            titleView.text = row.title
            innerAdapter.submitList(row.channels)
        }
    }
}
