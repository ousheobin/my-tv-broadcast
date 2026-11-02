package com.steve.mytvbroadcast.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.steve.mytvbroadcast.R
import com.steve.mytvbroadcast.data.Channel
import com.steve.mytvbroadcast.data.ChannelCacheManager
import com.steve.mytvbroadcast.data.ChannelDatabase
import com.steve.mytvbroadcast.data.ChannelIntentBuilder
import com.steve.mytvbroadcast.data.EpgManager
import com.steve.mytvbroadcast.data.MergedChannel
import com.steve.mytvbroadcast.data.SelectionMode
import com.steve.mytvbroadcast.data.SourceSelector
import com.steve.mytvbroadcast.databinding.FragmentHomeBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * P1 首页预览：顶部 hero 展示当前/首个频道的实时信号，失败回退氛围光；
 * 下方继续观看频道行，聚焦即切换预览，点击进全屏播放，行末「全部频道」进入 P2。
 */
@UnstableApi
class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private var homeChannels: RecyclerView? = null
    private var channelAdapter: ChannelAdapter? = null
    private var previewPlayer: ChannelPreviewPlayer? = null

    private var mergedChannels: List<MergedChannel> = emptyList()
    private var sortedChannels: List<Channel> = emptyList()

    private var previewChannel: MergedChannel? = null
    private var pendingPreview: MergedChannel? = null

    private val handler = Handler(Looper.getMainLooper())
    private val previewDebounce = Runnable { pendingPreview?.let { preview(it) } }
    private val epgTicker = object : Runnable {
        override fun run() {
            updateHeroEpg()
            handler.postDelayed(this, EPG_TICK_MS)
        }
    }

    var categoryCallback: CategoryCallback? = null

    /** 由 MainActivity 注入：请求进入 P2，参数为目标分类序号（0 = 全部） */
    var onRequestAllChannels: ((Int) -> Unit)? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        previewPlayer = ChannelPreviewPlayer(requireContext()).apply {
            attach(binding.heroPlayer)
            listener = object : ChannelPreviewPlayer.Listener {
                override fun onPreviewVisible(visible: Boolean) {
                    if (_binding == null) return
                    binding.heroPlayer.animate()
                        .alpha(if (visible) 1f else 0f)
                        .setDuration(220)
                        .start()
                    binding.heroStatus.text =
                        getString(if (visible) R.string.live_now else R.string.hero_connecting)
                }
            }
        }
        // hero 即当前预览频道：按下直接进全屏播放
        binding.heroContainer.setOnClickListener { previewChannel?.let { playMerged(it) } }
        setupChannelRow()
        loadChannels()

        // hero 当前节目依赖 EPG：缓存就绪后补一次刷新，避免首次进入显示空白
        EpgManager.ensureLoadedAsync()
        EpgManager.whenReady {
            view?.post { if (_binding != null) updateHeroEpg() }
        }
    }

    private fun setupChannelRow() {
        channelAdapter = ChannelAdapter(
            onChannelClick = { playChannel(it) },
            onChannelFocus = { onChannelFocused(it) },
            onAllChannelsClick = { onRequestAllChannels?.invoke(0) },
            showAllChannelsEntry = true
        )
        homeChannels = binding.homeChannels
        homeChannels?.layoutManager =
            LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        homeChannels?.adapter = channelAdapter
    }

    private fun loadChannels() {
        viewLifecycleOwner.lifecycleScope.launch {
            ChannelDatabase.setOnRefreshCompleteListener { refreshed ->
                mergedChannels = refreshed
                val (cats, channels) = recomputeOnWorker()
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
                    if (_binding == null) return@launch
                    applyChannels(cats, channels)
                }
            }
            mergedChannels = ChannelDatabase.loadMergedChannels()
            val (cats, channels) = withContext(Dispatchers.Default) { recomputeOnWorker() }
            if (_binding == null) return@launch
            applyChannels(cats, channels)
        }
    }

    /** 纯计算：构造继续观看行 + 分类列表，可在非主线程调用。 */
    private fun recomputeOnWorker(): Pair<List<String>, List<Channel>> {
        sortedChannels = mergedChannels.map { merged ->
            Channel(
                name = merged.name,
                logo = merged.logo,
                url = merged.sources.firstOrNull()?.url ?: "",
                group = merged.group,
                tvgId = merged.tvgId
            )
        }.sortedWith(compareBy({ !isEnglishName(it.name) }, { it.name.lowercase() }))

        // 继续观看：最近播放频道排最前，其余按默认排序
        val lastId = ChannelCacheManager.getLastPlayedChannelId()
        val lastChannel = lastId?.let { id -> mergedChannels.find { it.id == id } }
        val ordered = buildList {
            lastChannel?.let { last ->
                sortedChannels.firstOrNull { it.name == last.name && it.group == last.group }
                    ?.let { add(it) }
            }
            sortedChannels.forEach { if (none { existing -> existing.url == it.url }) add(it) }
        }

        val grouped = sortedChannels.groupBy { it.group ?: "Ungrouped" }
        val categories = mutableListOf("全部")
        categories.addAll(grouped.keys.filter { it != "Ungrouped" }.sorted())
        if (grouped.containsKey("Ungrouped")) categories.add("Ungrouped")
        return categories to ordered
    }

    private fun applyChannels(categories: List<String>, channels: List<Channel>) {
        // 置顶频道变化时 RecyclerView 会保留旧锚点，把首卡推到内容线之外，需回到行首
        val previousHead = channelAdapter?.currentList?.firstOrNull()?.url
        channelAdapter?.submitList(channels) {
            if (channels.firstOrNull()?.url != previousHead) homeChannels?.scrollToPosition(0)
        }
        categoryCallback?.onCategoriesLoaded(categories)

        // hero 初始预览：上次播放频道，否则排序后的首个频道
        if (previewChannel == null) {
            val lastId = ChannelCacheManager.getLastPlayedChannelId()
            val target = lastId?.let { id -> mergedChannels.find { it.id == id } }
                ?: sortedChannels.firstOrNull()?.let { findMerged(it) }
            target?.let { preview(it) }
        }
    }

    private fun isEnglishName(name: String): Boolean {
        if (name.isEmpty()) return false
        return name.all { it.code in 65..90 || it.code in 97..122 || it == ' ' }
    }

    private fun onChannelFocused(channel: Channel) {
        val merged = findMerged(channel) ?: return
        pendingPreview = merged
        handler.removeCallbacks(previewDebounce)
        handler.postDelayed(previewDebounce, PREVIEW_DEBOUNCE_MS)
    }

    private fun findMerged(channel: Channel): MergedChannel? =
        mergedChannels.find {
            it.name == channel.name &&
                (it.group == channel.group || (it.group.isNullOrEmpty() && channel.group.isNullOrEmpty()))
        }

    private fun preview(channel: MergedChannel) {
        previewChannel = channel
        binding.heroChannelName.text = channel.name
        updateHeroEpg()
        val source = SourceSelector.selectSource(channel, SelectionMode.FASTEST)
            ?: channel.sources.firstOrNull()
        previewPlayer?.play(source?.url)
    }

    private fun updateHeroEpg() {
        if (_binding == null) return
        val channel = previewChannel ?: return
        val now = System.currentTimeMillis()
        val current = EpgManager.getCurrentProgramme(channel, now)
        binding.heroProgram.text = current?.title.orEmpty()
    }

    private fun playChannel(channel: Channel) {
        val merged = findMerged(channel) ?: return
        playMerged(merged)
    }

    private fun playMerged(merged: MergedChannel) {
        val index = mergedChannels.indexOfFirst { it.id == merged.id }
        startActivity(ChannelIntentBuilder.build(requireContext(), merged, mergedChannels, index))
    }

    fun openAllChannels(categoryIndex: Int = 0) {
        onRequestAllChannels?.invoke(categoryIndex)
    }

    fun reloadChannels() {
        loadChannels()
    }

    /** 左侧栏选中分类：首页本身只有一行，选中即进入 P2 并定位到该分类 */
    fun onCategorySelected(index: Int) {
        onRequestAllChannels?.invoke(index)
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(epgTicker)
        handler.post(epgTicker)
        // 从播放页返回：hero 预览同步为最新上次播放频道
        val lastId = ChannelCacheManager.getLastPlayedChannelId()
        val last = lastId?.let { id -> mergedChannels.find { it.id == id } }
        if (last != null) preview(last) else previewChannel?.let { preview(it) }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(epgTicker)
        handler.removeCallbacks(previewDebounce)
        previewPlayer?.stop()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        handler.removeCallbacksAndMessages(null)
        previewPlayer?.release()
        previewPlayer = null
        homeChannels = null
        channelAdapter = null
        binding.heroPlayer.player = null
        _binding = null
    }

    private companion object {
        const val PREVIEW_DEBOUNCE_MS = 600L
        const val EPG_TICK_MS = 30 * 1000L
    }
}
