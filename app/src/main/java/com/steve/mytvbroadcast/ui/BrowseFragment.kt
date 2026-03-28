package com.steve.mytvbroadcast.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.steve.mytvbroadcast.R
import com.steve.mytvbroadcast.data.Channel
import com.steve.mytvbroadcast.data.ChannelDatabase
import kotlinx.coroutines.launch

class BrowseFragment : Fragment() {

    private lateinit var categoriesRecycler: RecyclerView
    private lateinit var channelsRecycler: RecyclerView
    private lateinit var channelAdapter: ChannelAdapter
    private lateinit var categoryAdapter: CategoryAdapter

    private var allChannels: List<Channel> = emptyList()
    private var groupedChannels: Map<String, List<Channel>> = emptyMap()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_browse, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        categoriesRecycler = view.findViewById(R.id.categories_recycler)
        channelsRecycler = view.findViewById(R.id.channels_recycler)

        setupCategoryList()
        setupChannelGrid()

        loadChannels()
    }

    private fun setupCategoryList() {
        categoryAdapter = CategoryAdapter { groupName ->
            showGroup(groupName)
        }
        categoriesRecycler.layoutManager = LinearLayoutManager(requireContext())
        categoriesRecycler.adapter = categoryAdapter
    }

    private fun setupChannelGrid() {
        channelAdapter = ChannelAdapter { channel ->
            playChannel(channel)
        }
        channelsRecycler.layoutManager = GridLayoutManager(requireContext(), 4)
        channelsRecycler.adapter = channelAdapter
    }

    private fun loadChannels() {
        viewLifecycleOwner.lifecycleScope.launch {
            allChannels = ChannelDatabase.loadChannels()
            groupedChannels = ChannelDatabase.getGroupedChannels()
            updateCategoryList()
            showGroup("推荐")
        }
    }

    private fun updateCategoryList() {
        val categories = mutableListOf("推荐")
        categories.addAll(groupedChannels.keys.filter { it != "Ungrouped" })
        if (groupedChannels.containsKey("Ungrouped")) {
            categories.add("Ungrouped")
        }
        categoryAdapter.submitList(categories)
    }

    private fun showGroup(groupName: String) {
        val channels = when (groupName) {
            "推荐" -> allChannels.take(6)
            else -> groupedChannels[groupName] ?: emptyList()
        }
        channelAdapter.submitList(channels)
        categoryAdapter.setSelected(groupName)
    }

    fun reloadChannels() {
        loadChannels()
    }

    private fun playChannel(channel: Channel) {
        val channelIndex = allChannels.indexOf(channel)
        val intent = Intent(requireContext(), PlaybackActivity::class.java).apply {
            putExtra(PlaybackActivity.EXTRA_CHANNEL_NAME, channel.name)
            putExtra(PlaybackActivity.EXTRA_CHANNEL_URL, channel.url)
            putExtra(PlaybackActivity.EXTRA_CHANNEL_INDEX, channelIndex)
            putStringArrayListExtra(PlaybackActivity.EXTRA_CHANNEL_URLS, ArrayList(allChannels.map { it.url }))
            putStringArrayListExtra(PlaybackActivity.EXTRA_CHANNEL_NAMES, ArrayList(allChannels.map { it.name }))
        }
        startActivity(intent)
    }
}

class CategoryAdapter(
    private val onCategoryClick: (String) -> Unit
) : RecyclerView.Adapter<CategoryAdapter.ViewHolder>() {

    private var categories = listOf<String>()
    private var selectedPosition = 0

    fun submitList(list: List<String>) {
        categories = list
        notifyDataSetChanged()
    }

    fun setSelected(name: String) {
        val pos = categories.indexOf(name)
        if (pos >= 0) {
            val old = selectedPosition
            selectedPosition = pos
            notifyItemChanged(old)
            notifyItemChanged(selectedPosition)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_category, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(categories[position], position == selectedPosition)
    }

    override fun getItemCount() = categories.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val nameView: TextView = itemView.findViewById(R.id.category_name)

        fun bind(name: String, selected: Boolean) {
            nameView.text = name
            val bgColor = when {
                selected -> 0x40FFFFFF.toInt()  // 25% white for selected
                else -> 0x00000000
            }
            nameView.setTextColor(if (selected) 0xFFFFFFFF.toInt() else 0xFF888888.toInt())
            nameView.setBackgroundColor(bgColor)
            itemView.isFocusable = true
            itemView.isClickable = true
            itemView.nextFocusUpId = R.id.source_bar
            itemView.nextFocusRightId = R.id.channels_recycler
            itemView.setOnClickListener {
                onCategoryClick(name)
            }
            itemView.setOnFocusChangeListener { _, hasFocus ->
                val focusBgColor = when {
                    hasFocus && selected -> 0x60FFFFFF.toInt()  // 37% white for both
                    hasFocus -> 0x40FFFFFF.toInt()  // 25% white for focus only
                    selected -> 0x40FFFFFF.toInt()  // 25% white for selected only
                    else -> 0x00000000
                }
                nameView.setTextColor(if (hasFocus || selected) 0xFFFFFFFF.toInt() else 0xFF888888.toInt())
                nameView.setBackgroundColor(focusBgColor)
            }
        }
    }
}
