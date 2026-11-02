package com.steve.mytvbroadcast.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.steve.mytvbroadcast.data.Channel
import com.steve.mytvbroadcast.data.ChannelDatabase
import com.steve.mytvbroadcast.data.ChannelIntentBuilder
import com.steve.mytvbroadcast.data.MergedChannel
import com.steve.mytvbroadcast.data.SignalSourceManager
import com.steve.mytvbroadcast.databinding.FragmentBrowseBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

interface CategoryCallback {
    fun onCategoriesLoaded(categories: List<String>)
}

data class SourceFilter(
    val id: String?,  // null means "全部"
    val name: String,
    val url: String = ""  // 信号源 URL，用于匹配
)

/** 纯计算结果：分类列表（含「全部」）+ 每个分类一行的频道行 */
private data class RowResult(
    val categories: List<String>,
    val rows: List<ChannelRow>
)

/**
 * P2 全部频道页：索尼式横向行分组，每个分类一行；卡片沿用 319×140 Token。
 * 左侧栏分类选中时滚动定位到对应行。
 */
class BrowseFragment : Fragment() {

    private var _binding: FragmentBrowseBinding? = null
    private val binding get() = _binding!!

    private var rowsRecycler: RecyclerView? = null
    private var rowAdapter: ChannelRowAdapter? = null
    private var sourcesList: RecyclerView? = null
    private var sourcesAdapter: SourceFilterAdapter? = null

    private var mergedChannels: List<MergedChannel> = emptyList()
    private var sortedChannels: List<Channel> = emptyList()
    private var rows: List<ChannelRow> = emptyList()

    private var sourceFilters: List<SourceFilter> = emptyList()
    private var selectedSourceIndex: Int = 0

    private var selectedCategoryIndex: Int = 0

    var categoryCallback: CategoryCallback? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentBrowseBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupSourcesList()
        setupChannelRows()
        loadChannels()
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

    private fun setupChannelRows() {
        rowAdapter = ChannelRowAdapter { channel -> playChannel(channel) }
        rowsRecycler = binding.channelsRecycler
        rowsRecycler?.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.VERTICAL, false)
        rowsRecycler?.adapter = rowAdapter
    }

    private fun loadChannels() {
        viewLifecycleOwner.lifecycleScope.launch {
            // 后台刷新完成回调运行在 IO 线程：重计算就地做，UI 提交切回主线程
            ChannelDatabase.setOnRefreshCompleteListener { refreshed ->
                mergedChannels = refreshed
                val result = recomputeOnWorker()
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
                    if (_binding == null) return@launch
                    applyRows(result)
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
        return name.all { it.code in 65..90 || it.code in 97..122 || it == ' ' }
    }

    /** 纯计算：按源过滤后按分类分行，可在非主线程调用。 */
    private fun recomputeOnWorker(): RowResult {
        sortedChannels = mergedChannels.map { merged ->
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
            mergedChannels.filter { merged -> merged.sources.any { it.signalSourceId == selectedSource.id } }
        }

        val filteredChannels = sourceFiltered.map { merged ->
            Channel(
                name = merged.name,
                logo = merged.logo,
                url = merged.sources.firstOrNull()?.url ?: "",
                group = merged.group,
                tvgId = merged.tvgId
            )
        }.sortedWith(compareBy({ !isEnglishName(it.name) }, { it.name.lowercase() }))

        val grouped = filteredChannels.groupBy { it.group ?: "Ungrouped" }
        val categoryNames = mutableListOf<String>()
        categoryNames.addAll(grouped.keys.filter { it != "Ungrouped" }.sorted())
        if (grouped.containsKey("Ungrouped")) categoryNames.add("Ungrouped")

        val rowList = categoryNames.map { name -> ChannelRow(name, grouped[name].orEmpty()) }
        val categories = mutableListOf("全部")
        categories.addAll(categoryNames)
        return RowResult(categories, rowList)
    }

    /** 重活放 Default 线程算，结果回主线程提交给 RecyclerView，避免频道多时卡主线程。 */
    private fun applyFilters() {
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.Default) { recomputeOnWorker() }
            if (_binding == null) return@launch
            applyRows(result)
        }
    }

    private fun applyRows(result: RowResult) {
        rows = result.rows
        rowAdapter?.submit(result.rows)
        categoryCallback?.onCategoriesLoaded(result.categories)
        // 首次进入若带了目标分类（从首页分类点进来），定位到该行
        val pending = selectedCategoryIndex
        if (pending > 0) scrollToCategory(pending)
    }

    fun reloadChannels() {
        loadChannels()
    }

    /** 进入前预设要定位的分类（0 = 全部），数据就绪后 applyRows 会滚动到该行 */
    fun setInitialCategory(index: Int) {
        selectedCategoryIndex = index
    }

    /** 左侧栏分类选中：0 = 顶部（全部），其余定位到对应行 */
    fun onCategorySelected(index: Int) {
        selectedCategoryIndex = index
        scrollToCategory(index)
    }

    private fun scrollToCategory(index: Int) {
        val targetPosition = index - 1
        if (targetPosition < 0) {
            rowsRecycler?.scrollToPosition(0)
        } else if (targetPosition < rows.size) {
            (rowsRecycler?.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(targetPosition, 0)
        }
    }

    private fun playChannel(channel: Channel) {
        val merged = mergedChannels.find {
            it.name == channel.name &&
                (it.group == channel.group || (it.group.isNullOrEmpty() && channel.group.isNullOrEmpty()))
        } ?: return
        val index = mergedChannels.indexOfFirst { it.id == merged.id }
        startActivity(ChannelIntentBuilder.build(requireContext(), merged, mergedChannels, index))
    }

    override fun onDestroyView() {
        super.onDestroyView()
        rowsRecycler = null
        rowAdapter = null
        sourcesList = null
        sourcesAdapter = null
        _binding = null
    }
}
