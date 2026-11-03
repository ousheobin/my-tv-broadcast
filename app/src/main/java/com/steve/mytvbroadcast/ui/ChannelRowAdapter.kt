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

/**
 * 单页连续流的外层纵向行列表：每行内嵌横向卡片列表，聚焦卡片只带动侧栏锚点。
 * 上下跳行由卡片拦下后交给 [onLaneHop]：下一行的卡片在屏外时几何焦点搜索会直接失败，遥控器就卡死在本行。
 */
class ChannelRowAdapter(
    private val onChannelClick: (Channel) -> Unit,
    private val onChannelFocus: ((Channel) -> Unit)? = null,
    private val onLaneHop: (row: Int, card: Int, delta: Int) -> Boolean = { _, _, _ -> false }
) : RecyclerView.Adapter<ChannelRowAdapter.RowViewHolder>() {

    private var rows: List<ChannelRow> = emptyList()
    private var list: RecyclerView? = null

    fun submit(rows: List<ChannelRow>) {
        this.rows = rows
        notifyDataSetChanged()
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        list = recyclerView
    }

    /** 跳行后把焦点交给目标行同一列的卡片：上下移动不该把用户在行内的位置抹掉。 */
    fun focusCardAt(rowPosition: Int, cardIndex: Int) {
        (list?.findViewHolderForAdapterPosition(rowPosition) as? RowViewHolder)?.focusCard(cardIndex)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_channel_row, parent, false)
        return RowViewHolder(view)
    }

    override fun onBindViewHolder(holder: RowViewHolder, position: Int) {
        holder.bind(rows[position])
    }

    override fun getItemCount(): Int = rows.size

    inner class RowViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        private val titleView: TextView = itemView.findViewById(R.id.row_title)
        private val rowRecycler: RecyclerView = itemView.findViewById(R.id.row_channels)
        private val innerAdapter = ChannelAdapter(
            onChannelClick = onChannelClick,
            onChannelFocus = onChannelFocus,
            onLaneKey = { card, delta -> onLaneHop(bindingAdapterPosition, card, delta) }
        )

        init {
            rowRecycler.layoutManager =
                LinearLayoutManager(itemView.context, LinearLayoutManager.HORIZONTAL, false)
            rowRecycler.adapter = innerAdapter
        }

        fun bind(row: ChannelRow) {
            titleView.text = row.title
            innerAdapter.submitList(row.channels)
        }

        fun focusCard(cardIndex: Int) {
            val last = innerAdapter.itemCount - 1
            if (last < 0) return
            val manager = rowRecycler.layoutManager as LinearLayoutManager
            // 这一行没有同一列时，落到最接近的一列，而不是跳回行首
            val child = manager.findViewByPosition(cardIndex.coerceIn(0, last))
                ?: rowRecycler.getChildAt(rowRecycler.childCount - 1)
            child?.requestFocus()
        }
    }
}
