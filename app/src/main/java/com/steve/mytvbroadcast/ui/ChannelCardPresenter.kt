package com.steve.mytvbroadcast.ui

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import androidx.leanback.widget.BaseCardView
import androidx.leanback.widget.Presenter
import com.steve.mytvbroadcast.data.Channel
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import android.widget.ImageView
import android.widget.TextView
import com.steve.mytvbroadcast.R

class ChannelCardPresenter : Presenter() {

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_channel_card, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any) {
        val channel = item as Channel
        val cardView = viewHolder.view as BaseCardView

        val titleView = cardView.findViewById<TextView>(R.id.channel_name)
        val logoView = cardView.findViewById<ImageView>(R.id.channel_logo)
        val playIcon = cardView.findViewById<ImageView>(R.id.play_icon)

        titleView.text = channel.name
        cardView.setBackgroundColor(0xFF2D2D2D.toInt())

        if (!channel.logo.isNullOrEmpty()) {
            Glide.with(cardView.context)
                .load(channel.logo)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .placeholder(R.drawable.ic_channel_placeholder)
                .error(R.drawable.ic_channel_placeholder)
                .into(logoView)
        } else {
            logoView.setImageResource(R.drawable.ic_channel_placeholder)
        }

        cardView.scaleX = 1f
        cardView.scaleY = 1f
        playIcon.visibility = View.INVISIBLE
        playIcon.alpha = 0f

        cardView.onFocusChangeListener = View.OnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                animateFocusIn(v, playIcon)
            } else {
                animateFocusOut(v, playIcon)
            }
        }
    }

    private fun animateFocusIn(view: View, playIcon: ImageView) {
        ObjectAnimator.ofFloat(view, "scaleX", 1f, 1.08f).apply {
            duration = 200
            start()
        }
        ObjectAnimator.ofFloat(view, "scaleY", 1f, 1.08f).apply {
            duration = 200
            start()
        }
        ObjectAnimator.ofFloat(playIcon, "alpha", 0f, 1f).apply {
            duration = 200
            start()
        }
        playIcon.visibility = View.VISIBLE
    }

    private fun animateFocusOut(view: View, playIcon: ImageView) {
        ObjectAnimator.ofFloat(view, "scaleX", 1.08f, 1f).apply {
            duration = 200
            start()
        }
        ObjectAnimator.ofFloat(view, "scaleY", 1.08f, 1f).apply {
            duration = 200
            start()
        }
        ObjectAnimator.ofFloat(playIcon, "alpha", 1f, 0f).apply {
            duration = 200
            start()
        }
        playIcon.visibility = View.INVISIBLE
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {}
}
