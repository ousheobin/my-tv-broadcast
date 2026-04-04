package com.steve.mytvbroadcast.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.steve.mytvbroadcast.data.Channel
import com.steve.mytvbroadcast.data.ChannelDatabase
import com.steve.mytvbroadcast.data.SignalSourceManager
import com.steve.mytvbroadcast.databinding.FragmentBrowseBinding
import kotlinx.coroutines.launch

interface CategoryCallback {
    fun onCategoriesLoaded(categories: List<String>)
}

class BrowseFragment : Fragment() {

    private var _binding: FragmentBrowseBinding? = null
    private val binding get() = _binding!!

    private var channelsRecycler: RecyclerView? = null
    private var channelAdapter: ChannelAdapter? = null
    private var sourcesList: RecyclerView? = null
    private var sourcesAdapter: SourceAdapter? = null

    private var allChannels: List<Channel> = emptyList()
    private var groupedChannels: Map<String, List<Channel>> = emptyMap()
    private var categories: List<String> = emptyList()
    private var selectedCategoryIndex: Int = 0

    var categoryCallback: CategoryCallback? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentBrowseBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupSourcesList()
        setupChannelGrid()
        loadChannels()
    }

    private fun setupSourcesList() {
        sourcesAdapter = SourceAdapter { source ->
            SignalSourceManager.setCurrentSourceId(source.id)
            reloadChannels()
        }
        sourcesList = binding.sourcesList
        sourcesList?.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        sourcesList?.adapter = sourcesAdapter
    }

    private fun setupChannelGrid() {
        channelAdapter = ChannelAdapter { channel ->
            playChannel(channel)
        }
        channelsRecycler = binding.channelsRecycler
        channelsRecycler?.layoutManager = GridLayoutManager(requireContext(), 3)
        channelsRecycler?.adapter = channelAdapter
    }

    private fun loadChannels() {
        viewLifecycleOwner.lifecycleScope.launch {
            allChannels = ChannelDatabase.loadChannels()
            groupedChannels = ChannelDatabase.getGroupedChannels()
            updateSourcesList()
            updateCategoryList()
        }
    }

    private fun updateSourcesList() {
        val sources = SignalSourceManager.getSources()
        val currentSourceId = SignalSourceManager.getCurrentSourceId()
        sourcesAdapter?.submitList(sources)
        sourcesAdapter?.setSelectedSource(currentSourceId)
    }

    private fun updateCategoryList() {
        val categoryList = mutableListOf("全部")
        categoryList.addAll(groupedChannels.keys.filter { it != "Ungrouped" }.sorted())
        if (groupedChannels.containsKey("Ungrouped")) {
            categoryList.add("Ungrouped")
        }
        categories = categoryList
        categoryCallback?.onCategoriesLoaded(categories)
        showGroup(0)
    }

    private fun showGroup(index: Int) {
        val channels = if (index == 0) {
            allChannels
        } else {
            groupedChannels[categories[index]] ?: emptyList()
        }
        channelAdapter?.submitList(channels)
    }

    fun reloadChannels() {
        loadChannels()
    }

    fun onCategorySelected(index: Int) {
        if (index == selectedCategoryIndex) return
        selectedCategoryIndex = index
        categoryCallback?.onCategoriesLoaded(categories)
        showGroup(index)
    }

    private fun playChannel(channel: Channel) {
        val channelIndex = allChannels.indexOf(channel)
        val intent = Intent().apply {
            setClassName(requireContext(), "com.steve.mytvbroadcast.ui.PlaybackActivity")
            putExtra("channel_name", channel.name)
            putExtra("channel_url", channel.url)
            putExtra("channel_index", channelIndex)
            putStringArrayListExtra("channel_urls", ArrayList(allChannels.map { it.url }))
            putStringArrayListExtra("channel_names", ArrayList(allChannels.map { it.name }))
        }
        startActivity(intent)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        channelsRecycler = null
        channelAdapter = null
        sourcesList = null
        sourcesAdapter = null
        _binding = null
    }
}
