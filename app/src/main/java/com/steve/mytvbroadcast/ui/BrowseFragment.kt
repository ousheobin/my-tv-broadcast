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
import com.steve.mytvbroadcast.data.MergedChannel
import com.steve.mytvbroadcast.data.SignalSource
import com.steve.mytvbroadcast.data.SignalSourceManager
import com.steve.mytvbroadcast.databinding.FragmentBrowseBinding
import kotlinx.coroutines.launch
import org.json.JSONObject

interface CategoryCallback {
    fun onCategoriesLoaded(categories: List<String>)
}

data class SourceFilter(
    val id: String?,  // null means "全部"
    val name: String,
    val url: String = ""  // 信号源 URL，用于匹配
)

class BrowseFragment : Fragment() {

    private var _binding: FragmentBrowseBinding? = null
    private val binding get() = _binding!!

    private var channelsRecycler: RecyclerView? = null
    private var channelAdapter: ChannelAdapter? = null
    private var sourcesList: RecyclerView? = null
    private var sourcesAdapter: SourceFilterAdapter? = null

    private var mergedChannels: List<MergedChannel> = emptyList()
    private var allChannels: List<Channel> = emptyList()
    private var displayedChannels: List<Channel> = emptyList()

    private var sourceFilters: List<SourceFilter> = emptyList()
    private var selectedSourceIndex: Int = 0

    private var categories: List<String> = emptyList()
    private var selectedCategoryIndex: Int = 0

    private var isSpeedTestRunning = false

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

    override fun onResume() {
        super.onResume()
        // 不再每次都强制刷新，保留现有数据
        // loadChannels() 已在 onViewCreated 中调用
    }

    private fun setupSourcesList() {
        sourcesAdapter = SourceFilterAdapter { index ->
            selectedSourceIndex = index
            sourcesAdapter?.setSelectedIndex(index)
            applyFilters()
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
            // 先设置监听器，再加载数据（避免后台刷新完成时监听器还未设置）
            ChannelDatabase.setOnRefreshCompleteListener { refreshed ->
                mergedChannels = refreshed
                rebuildChannelList()
                applyFilters()
            }

            mergedChannels = ChannelDatabase.loadMergedChannels()

            // 构建 source filters
            val filters = mutableListOf(SourceFilter(null, "全部", ""))
            val sources = SignalSourceManager.getSources()
            filters.addAll(sources.map { SourceFilter(it.id, it.name, it.url) })
            sourceFilters = filters
            sourcesAdapter?.submitList(sourceFilters)

            // 构建频道列表
            rebuildChannelList()

            applyFilters()
        }
    }

    private fun rebuildChannelList() {
        allChannels = mergedChannels.map { merged ->
            Channel(
                name = merged.name,
                logo = merged.logo,
                url = merged.sources.firstOrNull()?.url ?: "",
                group = merged.group,
                tvgId = merged.tvgId
            )
        }.sortedWith(compareBy({ !isEnglishName(it.name) }, { it.name.lowercase() }))
    }

    private fun isEnglishName(name: String): Boolean {
        if (name.isEmpty()) return false
        // English names contain only ASCII letters and spaces
        return name.all { it.code in 65..90 || it.code in 97..122 || it == ' ' }
    }

    private fun applyFilters() {
        val selectedSource = if (selectedSourceIndex >= 0 && selectedSourceIndex < sourceFilters.size) {
            sourceFilters[selectedSourceIndex]
        } else {
            null
        }

        // 按信号源筛选
        val sourceFiltered = if (selectedSource?.id == null) {
            mergedChannels
        } else {
            mergedChannels.filter { merged ->
                merged.sources.any { it.signalSourceId == selectedSource.id }
            }
        }

        // 构建筛选后的频道列表
        val filteredChannels = sourceFiltered.map { merged ->
            Channel(
                name = merged.name,
                logo = merged.logo,
                url = merged.sources.firstOrNull()?.url ?: "",
                group = merged.group,
                tvgId = merged.tvgId
            )
        }

        // 按分类筛选
        val grouped = filteredChannels.groupBy { it.group ?: "Ungrouped" }
        val categoryList = mutableListOf("全部")
        categoryList.addAll(grouped.keys.filter { it != "Ungrouped" }.sorted())
        if (grouped.containsKey("Ungrouped")) {
            categoryList.add("Ungrouped")
        }
        categories = categoryList

        val categoryToFilter = if (selectedCategoryIndex == 0) {
            null
        } else if (selectedCategoryIndex > 0 && selectedCategoryIndex < categories.size) {
            categories[selectedCategoryIndex]
        } else {
            null
        }

        displayedChannels = if (categoryToFilter == null) {
            filteredChannels
        } else {
            grouped[categoryToFilter] ?: filteredChannels
        }

        channelAdapter?.submitList(displayedChannels)
        categoryCallback?.onCategoriesLoaded(categories)
    }

    fun reloadChannels() {
        loadChannels()
    }

    fun onCategorySelected(index: Int) {
        if (index == selectedCategoryIndex) return
        selectedCategoryIndex = index
        applyFilters()
    }

    private fun playChannel(channel: Channel) {
        val mergedChannel = mergedChannels.find {
            it.name == channel.name && (it.group == channel.group || (it.group.isNullOrEmpty() && channel.group.isNullOrEmpty()))
        }

        val channelIndex = displayedChannels.indexOf(channel)

        if (mergedChannel != null) {
            val mergedChannelJson = JSONObject().apply {
                put("id", mergedChannel.id)
                put("name", mergedChannel.name)
                put("logo", mergedChannel.logo)
                put("group", mergedChannel.group)
                put("tvgId", mergedChannel.tvgId)
                put("preferredSourceId", mergedChannel.preferredSourceId)
                put("lastPlayedSourceId", mergedChannel.lastPlayedSourceId)

                val sourcesArray = org.json.JSONArray()
                mergedChannel.sources.forEach { source ->
                    val sourceJson = JSONObject().apply {
                        put("sourceId", source.sourceId)
                        put("signalSourceId", source.signalSourceId)
                        put("sourceName", source.sourceName)
                        put("url", source.url)
                        put("speed", source.speed)
                        put("lastTested", source.lastTested)
                        put("isAvailable", source.isAvailable)
                    }
                    sourcesArray.put(sourceJson)
                }
                put("sources", sourcesArray)
            }

            val intent = Intent().apply {
                setClassName(requireContext(), "com.steve.mytvbroadcast.ui.PlaybackActivity")
                putExtra("channel_name", channel.name)
                putExtra("merged_channel", mergedChannelJson.toString())
                putExtra("channel_index", channelIndex)
                putStringArrayListExtra("channel_urls", ArrayList(allChannels.map { it.url }))
                putStringArrayListExtra("channel_names", ArrayList(allChannels.map { it.name }))
            }
            startActivity(intent)
        } else {
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
