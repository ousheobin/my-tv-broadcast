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

class ChannelAdapter(
    private val onChannelClick: (Channel) -> Unit
) : ListAdapter<Channel, ChannelAdapter.ChannelViewHolder>(ChannelDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChannelViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_channel_card, parent, false)
        return ChannelViewHolder(view)
    }

    override fun onBindViewHolder(holder: ChannelViewHolder, position: Int) {
        holder.bind(getItem(position))
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

            itemView.isFocusable = true
            itemView.isClickable = true
            itemView.nextFocusLeftId = R.id.categories_recycler
            itemView.nextFocusUpId = R.id.source_bar

            itemView.setOnClickListener {
                onChannelClick(channel)
            }

            itemView.setOnFocusChangeListener { _, hasFocus ->
                itemView.isSelected = hasFocus
                if (hasFocus) {
                    itemView.scaleX = 1.0f
                    itemView.scaleY = 1.0f
                    itemView.alpha = 1.0f
                    playIcon.visibility = View.VISIBLE
                } else {
                    itemView.scaleX = 1f
                    itemView.scaleY = 1f
                    itemView.alpha = 0.7f
                    playIcon.visibility = View.INVISIBLE
                }
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
