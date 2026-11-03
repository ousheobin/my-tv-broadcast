package com.steve.mytvbroadcast.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.doOnLayout
import androidx.core.view.doOnPreDraw
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
import com.steve.mytvbroadcast.data.SignalSourceManager
import com.steve.mytvbroadcast.data.SourceSelector
import com.steve.mytvbroadcast.databinding.FragmentHomeBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 频道流就绪：把分类交给侧栏导航，并通知落地页可以退场。 */
interface CategoryCallback {
    fun onCategoriesLoaded(categories: List<String>)

    /** 流滚到哪个分类，侧栏锚点就该停在哪个分类。 */
    fun onLaneSelected(categoryIndex: Int)
}

/** 顶部信号源胶囊：id 为 null 表示不过滤的「全部」 */
data class SourceFilter(
    val id: String?,
    val name: String,
    val url: String = ""
)

/**
 * 单页连续流：hero 实时预览在最上，往下依次是「继续观看」与各分类频道行。
 * 浏览频道不再换页，侧栏分类只负责把这条流滚到对应行。
 */
@UnstableApi
class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private var rowsRecycler: RecyclerView? = null
    private var rowAdapter: ChannelRowAdapter? = null
    private var sourcesList: RecyclerView? = null
    private var sourcesAdapter: SourceFilterAdapter? = null
    private var previewPlayer: ChannelPreviewPlayer? = null

    private var mergedChannels: List<MergedChannel> = emptyList()
    private var rows: List<ChannelRow> = emptyList()
    private var laneTitles: List<String> = emptyList()

    private var sourceFilters: List<SourceFilter> = emptyList()
    private var selectedSourceIndex: Int = 0

    private var previewChannel: MergedChannel? = null
    private var heroHeight = 0
    private var heroFocused = false

    private val handler = Handler(Looper.getMainLooper())
    private val epgTicker = object : Runnable {
        override fun run() {
            updateHeroEpg()
            handler.postDelayed(this, EPG_TICK_MS)
        }
    }

    /** 侧栏锚点跟着这条流走，画面也陪着一起滚。 */
    private val laneSpy = object : RecyclerView.OnScrollListener() {
        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
            offsetHero()
            reportLane()
        }

        override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
            if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                offsetHero()
                reportLane()
            }
        }
    }

    var categoryCallback: CategoryCallback? = null

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

        setupSourcesList()
        setupChannelRows()
        // hero 高度依赖信号源行的实测高度，首帧布局后与它每次变化都重算
        binding.root.doOnLayout { layoutHero() }
        binding.sourcesContainer.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> layoutHero() }

        loadChannels()

        // hero 当前节目依赖 EPG：缓存就绪后补一次刷新，避免首次进入显示空白
        EpgManager.ensureLoadedAsync()
        EpgManager.whenReady {
            view?.post { if (_binding == null) return@post; updateHeroEpg() }
        }
    }

    /**
     * hero 高度以档位 token 为准（token 已按可用宽度缩过，本身就是该屏的正确比例），
     * 只在视口装不下「画面 + 一整条频道行」时才让位：首屏的卡片必须完整可见，不许被屏底裁掉。
     */
    private fun layoutHero() {
        val binding = _binding ?: return
        val reserve = resources.getDimensionPixelSize(R.dimen.ui_hero_lane_reserve)
        val preferred = resources.getDimensionPixelSize(R.dimen.ui_hero_height)
        val floor = (preferred * HERO_MIN_RATIO).toInt()
        val target = minOf(preferred, binding.root.height - binding.sourcesContainer.height - reserve)
            .coerceAtLeast(floor)
        if (target == heroHeight) return
        heroHeight = target
        binding.heroContainer.layoutParams = binding.heroContainer.layoutParams.apply { height = target }
        // 列表顶部的留白就是 hero 的位置：两者一起滚，谁也不会压在谁身上
        val recycler = binding.channelsRecycler
        recycler.setPadding(recycler.paddingLeft, target, recycler.paddingRight, recycler.paddingBottom)
        offsetHero()
    }

    /** hero 是这条流的开头：列表滚多少它就走多少，滚到出屏为止。 */
    private fun offsetHero() {
        val recycler = rowsRecycler ?: return
        val binding = _binding ?: return
        binding.heroContainer.translationY =
            -minOf(recycler.computeVerticalScrollOffset(), heroHeight).toFloat()
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
        rowAdapter = ChannelRowAdapter(
            onChannelClick = { playChannel(it) },
            onChannelFocus = { onCardFocused() },
            onLaneHop = { row, card, delta -> hopLane(row, card, delta) }
        )
        rowsRecycler = binding.channelsRecycler
        rowsRecycler?.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.VERTICAL, false)
        rowsRecycler?.adapter = rowAdapter
        rowsRecycler?.addOnScrollListener(laneSpy)
    }

    /** 焦点落在哪一行，侧栏就选中哪个分类；还在画面或「继续观看」上就是「全部」。 */
    private fun reportLane() {
        val recycler = rowsRecycler ?: return
        val manager = recycler.layoutManager as? LinearLayoutManager ?: return
        val position = focusedLanePosition()
            ?: manager.findFirstVisibleItemPosition().takeIf { it != RecyclerView.NO_POSITION }
            ?: return
        val title = rows.getOrNull(position)?.title ?: return
        val lane = laneTitles.indexOf(title)
        categoryCallback?.onLaneSelected(if (lane < 0) 0 else lane + 1)
    }

    /** 从当前焦点往上找到它所属的那一行；焦点不在频道流里时返回 null。 */
    private fun focusedLanePosition(): Int? {
        val recycler = rowsRecycler ?: return null
        var view = recycler.findFocus()
        while (view != null && view.parent !== recycler) view = view.parent as? View
        val rowView = view ?: return null
        val position = recycler.getChildViewHolder(rowView).bindingAdapterPosition
        return position.takeIf { it != RecyclerView.NO_POSITION }
    }

    private fun loadChannels() {
        viewLifecycleOwner.lifecycleScope.launch {
            // 后台刷新完成回调运行在 IO 线程：重计算就地做，UI 提交切回主线程
            ChannelDatabase.setOnRefreshCompleteListener { refreshed ->
                mergedChannels = refreshed
                val title = getString(R.string.continue_watching)
                val result = recomputeOnWorker(title)
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
                    if (_binding == null) return@launch
                    applyRows(result)
                }
            }

            mergedChannels = ChannelDatabase.loadMergedChannels()

            val filters = mutableListOf(SourceFilter(null, "全部", ""))
            filters.addAll(SignalSourceManager.getSources().map { SourceFilter(it.id, it.name, it.url) })
            sourceFilters = filters
            sourcesAdapter?.submitList(sourceFilters)

            applyFilters()
        }
    }

    /** 纯计算：按源过滤后分行，「继续观看」占首行，其后每个分类一行。可在非主线程调用。 */
    private fun recomputeOnWorker(continueTitle: String): RowResult {
        val sortedChannels = mergedChannels.map { merged ->
            Channel(
                name = merged.name,
                logo = merged.logo,
                url = merged.sources.firstOrNull()?.url ?: "",
                group = merged.group,
                tvgId = merged.tvgId
            )
        }.sortedWith(compareBy({ !isEnglishName(it.name) }, { it.name.lowercase() }))

        val selectedSource = sourceFilters.getOrNull(selectedSourceIndex)
        val cards = filterBySource(sortedChannels, selectedSource)
        val grouped = cards.groupBy { it.group ?: "Ungrouped" }
        val categoryNames = grouped.keys.filter { it != "Ungrouped" }.sorted()
            .toMutableList().apply { if (grouped.containsKey("Ungrouped")) add("Ungrouped") }

        val laneRows = categoryNames.map { name -> ChannelRow(name, grouped[name].orEmpty()) }
        val continueRow = ChannelRow(continueTitle, continueWatching(cards))
        val rows = buildList {
            if (continueRow.channels.isNotEmpty()) add(continueRow)
            addAll(laneRows.filter { it.channels.isNotEmpty() })
        }
        return RowResult(listOf("全部") + categoryNames, rows, categoryNames)
    }

    /** 按源过滤卡片；「全部」信号源不过滤。靠频道名+分类回到合并频道，不重复建一份模型。 */
    private fun filterBySource(sortedChannels: List<Channel>, selectedSource: SourceFilter?): List<Channel> {
        if (selectedSource?.id == null) return sortedChannels
        val allowed = mergedChannels
            .filter { m -> m.sources.any { it.signalSourceId == selectedSource.id } }
            .map { it.name to (it.group ?: "Ungrouped") }
            .toSet()
        return sortedChannels.filter { it.name to (it.group ?: "Ungrouped") in allowed }
    }

    /** 继续观看：上次播放的频道置顶，其余按默认排序。 */
    private fun continueWatching(channels: List<Channel>): List<Channel> {
        val lastId = ChannelCacheManager.getLastPlayedChannelId()
        val lastChannel = lastId?.let { id -> mergedChannels.find { it.id == id } }
        return buildList {
            lastChannel?.let { last ->
                channels.firstOrNull { it.name == last.name && it.group == last.group }?.let { add(it) }
            }
            channels.forEach { if (none { existing -> existing.url == it.url }) add(it) }
        }
    }

    /** 重活放 Default 线程算，结果回主线程提交给 RecyclerView，避免频道多时卡主线程。 */
    private fun applyFilters() {
        val title = getString(R.string.continue_watching)
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.Default) { recomputeOnWorker(title) }
            if (_binding == null) return@launch
            applyRows(result)
        }
    }

    private fun applyRows(result: RowResult) {
        val previousHead = rows.firstOrNull()?.title
        rows = result.rows
        laneTitles = result.laneTitles
        rowAdapter?.submit(rows)
        categoryCallback?.onCategoriesLoaded(result.categories)

        // 置顶频道变化会让 RecyclerView 保留旧锚点，把首卡推到内容线之外
        if (rows.firstOrNull()?.title != previousHead) rowsRecycler?.scrollToPosition(0)
        rowsRecycler?.doOnPreDraw { offsetHero() }

        // hero 钉在「上次播放，否则首个频道」上，不随焦点换画面
        if (previewChannel == null) {
            val lastId = ChannelCacheManager.getLastPlayedChannelId()
            val target = lastId?.let { id -> mergedChannels.find { it.id == id } }
                ?: rows.firstOrNull()?.channels?.firstOrNull()?.let { findMerged(it) }
            target?.let { preview(it) }
        }
        focusHeroOnFirstFrame()
    }

    /** 遥控器一进页面就该落在画面上，而不是困在左侧栏里。 */
    private fun focusHeroOnFirstFrame() {
        if (heroFocused) return
        heroFocused = true
        binding.heroContainer.post { if (_binding != null) binding.heroContainer.requestFocus() }
    }

    /** 先把目标行拉进布局（停在留白之下），否则这一行的卡片还不存在。 */
    private fun bringLaneIntoLayout(position: Int) {
        (rowsRecycler?.layoutManager as? LinearLayoutManager)
            ?.scrollToPositionWithOffset(position, 0)
    }

    /** 再把行按像素钉到流的顶端：那段留白是 hero 的位置，hero 跟着滑出去。 */
    private fun pinLaneToTop(position: Int) {
        val recycler = rowsRecycler ?: return
        val child = (recycler.layoutManager as? LinearLayoutManager)
            ?.findViewByPosition(position) ?: return
        if (child.top != 0) recycler.smoothScrollBy(0, child.top)
    }

    /** 回到流的开头：hero 重新完整入画并接住焦点。返回是否真的动了。 */
    private fun backToHero(): Boolean {
        val recycler = rowsRecycler ?: return false
        val offset = recycler.computeVerticalScrollOffset()
        if (offset <= 0) return false
        recycler.smoothScrollBy(0, -offset)
        binding.heroContainer.requestFocus()
        return true
    }

    /** 上下键在行与行之间显式跳：把目标行对齐到列表顶部，焦点交给同一列的卡片。 */
    private fun hopLane(from: Int, card: Int, delta: Int): Boolean {
        val recycler = rowsRecycler ?: return false
        val target = from + delta
        // 首行往上：流还滚着就先滑回画面，已经到顶才交给系统几何搜索；末行往下没内容了，吞掉按键，别让焦点漏进侧栏
        if (target < 0) return backToHero()
        if (target >= rows.size) return true
        // 先把行拉进布局，再交焦点：反过来会被焦点的「刚好把卡片拖进屏」覆盖，留下被切成半截的上一行
        bringLaneIntoLayout(target)
        recycler.post {
            if (_binding == null) return@post
            rowAdapter?.focusCardAt(target, card)
            pinLaneToTop(target)
            reportLane()
        }
        return true
    }

    private fun isEnglishName(name: String): Boolean {
        if (name.isEmpty()) return false
        return name.all { it.code in 65..90 || it.code in 97..122 || it == ' ' }
    }

    /** 焦点在流里移动只带动侧栏锚点；画面归画面，列表归列表。 */
    private fun onCardFocused() {
        reportLane()
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
        val current = EpgManager.getCurrentProgramme(channel, System.currentTimeMillis())
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

    fun reloadChannels() {
        loadChannels()
    }

    /** 侧栏分类：0 回到流顶部，其余把这条流滚到对应行；不换页，所以画面不会整体塌掉重建。 */
    fun onCategorySelected(index: Int) {
        if (index <= 0) {
            backToHero()
            _binding?.heroContainer?.requestFocus()
            return
        }
        val title = laneTitles.getOrNull(index - 1) ?: return
        val position = rows.indexOfFirst { it.title == title }
        if (position < 0) return
        bringLaneIntoLayout(position)
        rowsRecycler?.post {
            if (_binding == null) return@post
            rowAdapter?.focusCardAt(position, 0)
            pinLaneToTop(position)
            reportLane()
        }
    }

    /** 返回键先收起浏览位置回到画面，再交给系统退出：让「回顶」成为可见的一步，而不是直接闪出页面。 */
    fun collapseToTop(): Boolean = backToHero()

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
        previewPlayer?.stop()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        handler.removeCallbacksAndMessages(null)
        previewPlayer?.release()
        previewPlayer = null
        rowsRecycler = null
        rowAdapter = null
        sourcesList = null
        sourcesAdapter = null
        binding.heroPlayer.player = null
        _binding = null
    }

    /** 分类列表（含「全部」）+ 连续流的分层行 */
    data class RowResult(
        val categories: List<String>,
        val rows: List<ChannelRow>,
        val laneTitles: List<String>
    )

    private companion object {
        const val EPG_TICK_MS = 30 * 1000L
        const val HERO_MIN_RATIO = 0.55f
    }
}
