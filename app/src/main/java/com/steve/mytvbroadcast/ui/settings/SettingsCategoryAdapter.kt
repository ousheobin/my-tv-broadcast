package com.steve.mytvbroadcast.ui.settings

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.steve.mytvbroadcast.R

class SettingsCategoryAdapter(
    private val categories: List<SettingsCategory>,
    private val onCategorySelected: (SettingsCategory) -> Unit
) : RecyclerView.Adapter<SettingsCategoryAdapter.ViewHolder>() {

    private var selectedPosition = 0
    private var focusedPosition = -1

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val title: TextView = itemView.findViewById(R.id.category_title)

        init {
            itemView.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    selectCategory(pos)
                    onCategorySelected(categories[pos])
                }
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_setting_category, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val category = categories[position]
        holder.title.text = category.title
        updateItemAppearance(holder, position)
    }

    private fun updateItemAppearance(holder: ViewHolder, position: Int) {
        val isSelected = position == selectedPosition
        val isFocused = position == focusedPosition

        holder.title.setTextColor(
            when {
                isFocused || isSelected -> Color.WHITE
                else -> Color.parseColor("#888888")
            }
        )
    }

    override fun getItemCount(): Int = categories.size

    fun selectCategory(position: Int) {
        if (position in categories.indices && position != selectedPosition) {
            val oldPosition = selectedPosition
            selectedPosition = position
            notifyItemChanged(oldPosition)
            notifyItemChanged(selectedPosition)
        }
    }

    fun setFocusedPosition(position: Int) {
        if (position != focusedPosition) {
            val oldFocused = focusedPosition
            focusedPosition = position
            if (oldFocused in 0 until itemCount) {
                notifyItemChanged(oldFocused)
            }
            if (position in 0 until itemCount) {
                notifyItemChanged(position)
            }
        }
    }
}
