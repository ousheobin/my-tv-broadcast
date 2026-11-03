package com.steve.mytvbroadcast.ui

import android.view.KeyEvent
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
    private val onLaneKey: ((card: Int, delta: Int) -> Boolean)? = null
) : ListAdapter<Channel, ChannelAdapter.ChannelViewHolder>(ChannelDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChannelViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_channel_card, parent, false)
        return ChannelViewHolder(view)
    }

    override fun onBindViewHolder(holder: ChannelViewHolder, position: Int) {
        // 横向行里卡片宽度不能是 match_parent，按 token 固定为 channel_card_width
        applyCardWidth(holder)
        holder.bind(getItem(position))
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

        init {
            // 方向键在卡片自己的 onKeyDown 里就会被 moveFocus 吃掉，外层 RecyclerView 的监听根本收不到；
            // 跳行只能在这一层拦下来。
            itemView.setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                val delta = when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_DOWN -> 1
                    KeyEvent.KEYCODE_DPAD_UP -> -1
                    else -> return@setOnKeyListener false
                }
                onLaneKey?.invoke(bindingAdapterPosition, delta) == true
            }
        }

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

    class ChannelDiffCallback : DiffUtil.ItemCallback<Channel>() {
        override fun areItemsTheSame(oldItem: Channel, newItem: Channel): Boolean {
            return oldItem.url == newItem.url
        }

        override fun areContentsTheSame(oldItem: Channel, newItem: Channel): Boolean {
            return oldItem == newItem
        }
    }
}
