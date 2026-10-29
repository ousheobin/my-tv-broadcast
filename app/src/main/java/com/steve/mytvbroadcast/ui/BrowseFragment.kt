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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
            // 后台刷新完成回调运行在 IO 线程：重计算就地做，UI 提交切回主线程
            ChannelDatabase.setOnRefreshCompleteListener { refreshed ->
                mergedChannels = refreshed
                val (cats, displayed) = recomputeOnWorker()
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
                    if (_binding == null) return@launch
                    categories = cats
                    displayedChannels = displayed
                    channelAdapter?.submitList(displayed)
                    categoryCallback?.onCategoriesLoaded(cats)
                }
            }

            mergedChannels = ChannelDatabase.loadMergedChannels()

            // 构建 source filters
            val filters = mutableListOf(SourceFilter(null, "全部", ""))
            val sources = SignalSourceManager.getSources()
            filters.addAll(sources.map { SourceFilter(it.id, it.name, it.url) })
            sourceFilters = filters
            sourcesAdapter?.submitList(sourceFilters)

            applyFilters()
        }
    }

    private fun isEnglishName(name: String): Boolean {
        if (name.isEmpty()) return false
        // English names contain only ASCII letters and spaces
        return name.all { it.code in 65..90 || it.code in 97..122 || it == ' ' }
    }

    /** 纯计算：重建 allChannels + 按源/分类过滤，返回(分类列表, 展示频道)。可在非主线程调用。 */
    private fun recomputeOnWorker(): Pair<List<String>, List<Channel>> {
        allChannels = mergedChannels.map { merged ->
            Channel(
                name = merged.name,
                logo = merged.logo,
                url = merged.sources.firstOrNull()?.url ?: "",
                group = merged.group,
                tvgId = merged.tvgId
            )
        }.sortedWith(compareBy({ !isEnglishName(it.name) }, { it.name.lowercase() }))

        val selectedSource = sourceFilters.getOrNull(selectedSourceIndex)
        val sourceFiltered = if (selectedSource?.id == null) {
            mergedChannels
        } else {
            mergedChannels.filter { merged ->
                merged.sources.any { it.signalSourceId == selectedSource.id }
            }
        }

        val filteredChannels = sourceFiltered.map { merged ->
            Channel(
                name = merged.name,
                logo = merged.logo,
                url = merged.sources.firstOrNull()?.url ?: "",
                group = merged.group,
                tvgId = merged.tvgId
            )
        }

        val grouped = filteredChannels.groupBy { it.group ?: "Ungrouped" }
        val categoryList = mutableListOf("全部")
        categoryList.addAll(grouped.keys.filter { it != "Ungrouped" }.sorted())
        if (grouped.containsKey("Ungrouped")) {
            categoryList.add("Ungrouped")
        }

        val categoryToFilter =
            if (selectedCategoryIndex in 1 until categoryList.size) categoryList[selectedCategoryIndex] else null
        val displayed = if (categoryToFilter == null) {
            filteredChannels
        } else {
            grouped[categoryToFilter] ?: filteredChannels
        }
        return categoryList to displayed
    }

    /** 重活放 Default 线程算，结果回主线程提交给 RecyclerView，避免频道多时切分类/刷新卡主线程。 */
    private fun applyFilters() {
        viewLifecycleOwner.lifecycleScope.launch {
            val (cats, displayed) = withContext(Dispatchers.Default) { recomputeOnWorker() }
            if (_binding == null) return@launch
            categories = cats
            displayedChannels = displayed
            channelAdapter?.submitList(displayed)
            categoryCallback?.onCategoriesLoaded(cats)
        }
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
