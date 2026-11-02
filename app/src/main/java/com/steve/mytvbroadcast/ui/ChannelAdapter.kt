package com.steve.mytvbroadcast.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.steve.mytvbroadcast.R
import com.steve.mytvbroadcast.data.Channel
import com.steve.mytvbroadcast.ui.focus.FocusEffects

class ChannelAdapter(
    private val onChannelClick: (Channel) -> Unit,
    private val onChannelFocus: ((Channel) -> Unit)? = null,
    private val onAllChannelsClick: (() -> Unit)? = null,
    var showAllChannelsEntry: Boolean = false
) : ListAdapter<Channel, RecyclerView.ViewHolder>(ChannelDiffCallback()) {

    private val viewTypeChannel = 0
    private val viewTypeAll = 1

    override fun getItemCount(): Int =
        if (showAllChannelsEntry) super.getItemCount() + 1 else super.getItemCount()

    override fun getItemViewType(position: Int): Int =
        if (showAllChannelsEntry && position == itemCount - 1) viewTypeAll else viewTypeChannel

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == viewTypeAll) {
            AllChannelsViewHolder(inflater.inflate(R.layout.item_all_channels_entry, parent, false))
        } else {
            ChannelViewHolder(inflater.inflate(R.layout.item_channel_card, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        // 横向行里卡片宽度不能是 match_parent，按 token 固定为 channel_card_width
        applyCardWidth(holder)
        when (holder) {
            is ChannelViewHolder -> holder.bind(getItem(position))
            is AllChannelsViewHolder -> holder.bind()
        }
    }

    private fun applyCardWidth(holder: RecyclerView.ViewHolder) {
        val width = holder.itemView.context.resources.getDimensionPixelSize(R.dimen.channel_card_width)
        val lp = holder.itemView.layoutParams
        if (lp != null && lp.width != width) {
            lp.width = width
            holder.itemView.layoutParams = lp
        }
    }

    inner class ChannelViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val logoView: ImageView = itemView.findViewById(R.id.channel_logo)
        private val nameView: TextView = itemView.findViewById(R.id.channel_name)
        private val playIcon: ImageView = itemView.findViewById(R.id.play_icon)

        fun bind(channel: Channel) {
            nameView.text = channel.name

            if (!channel.logo.isNullOrEmpty()) {
                Glide.with(itemView.context)
                    .load(channel.logo)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .placeholder(R.drawable.ic_channel_placeholder)
                    .error(R.drawable.ic_channel_placeholder)
                    .into(logoView)
            } else {
                logoView.setImageResource(R.drawable.ic_channel_placeholder)
            }

            playIcon.visibility = View.INVISIBLE
            playIcon.alpha = 0f

            itemView.isFocusable = true
            itemView.isFocusableInTouchMode = true

            itemView.setOnClickListener {
                onChannelClick(channel)
            }

            itemView.setOnFocusChangeListener { v, hasFocus ->
                // 焦点效果：缩放 + 阴影发光 + 播放图标淡入；标题恒为白色(设计规范要求)
                FocusEffects.animateFocus(v, hasFocus)
                if (hasFocus) onChannelFocus?.invoke(channel)
                playIcon.animate()
                    .alpha(if (hasFocus) 1f else 0f)
                    .setDuration(200)
                    .withStartAction { if (hasFocus) playIcon.visibility = View.VISIBLE }
                    .withEndAction { if (!hasFocus) playIcon.visibility = View.INVISIBLE }
                    .start()
            }
        }
    }

    inner class AllChannelsViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        fun bind() {
            itemView.isFocusable = true
            itemView.isFocusableInTouchMode = true
            itemView.setOnClickListener { onAllChannelsClick?.invoke() }
            itemView.setOnFocusChangeListener { v, hasFocus -> FocusEffects.animateFocus(v, hasFocus) }
        }
    }

    class ChannelDiffCallback : DiffUtil.ItemCallback<Channel>() {
        override fun areItemsTheSame(oldItem: Channel, newItem: Channel): Boolean {
            return oldItem.url == newItem.url
        }

        override fun areContentsTheSame(oldItem: Channel, newItem: Channel): Boolean {
            return oldItem == newItem
        }
    }
}
