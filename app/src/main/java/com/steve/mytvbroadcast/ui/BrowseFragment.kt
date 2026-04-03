package com.steve.mytvbroadcast.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.steve.mytvbroadcast.R
import com.steve.mytvbroadcast.data.Channel
import com.steve.mytvbroadcast.data.ChannelDatabase
import com.steve.mytvbroadcast.databinding.FragmentBrowseBinding
import com.steve.mytvbroadcast.ui.focus.FocusEffects
import kotlinx.coroutines.launch

class BrowseFragment : Fragment() {

    private var _binding: FragmentBrowseBinding? = null
    private val binding get() = _binding!!

    private var channelsRecycler: RecyclerView? = null
    private var channelAdapter: ChannelAdapter? = null
    private var categoryTabsInner: LinearLayout? = null

    private var allChannels: List<Channel> = emptyList()
    private var groupedChannels: Map<String, List<Channel>> = emptyMap()
    private var categories: List<String> = emptyList()
    private var selectedCategoryIndex: Int = 0

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentBrowseBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        categoryTabsInner = binding.categoryTabsInner
        setupChannelGrid()
        loadChannels()
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
            updateCategoryList()
        }
    }

    private fun updateCategoryList() {
        val categoryList = mutableListOf("全部")
        categoryList.addAll(groupedChannels.keys.filter { it != "Ungrouped" }.sorted())
        if (groupedChannels.containsKey("Ungrouped")) {
            categoryList.add("Ungrouped")
        }
        categories = categoryList

        setupCategoryTabs()
        showGroup(0)
    }

    private fun setupCategoryTabs() {
        categoryTabsInner?.removeAllViews()

        categories.forEachIndexed { index, category ->
            val tabView = createCategoryTabView(category, index)
            categoryTabsInner?.addView(tabView)
        }

        // 设置RecyclerView的上按钮跳转 - 跳转到分类栏
        updateRecyclerViewFocusJump()
    }

    private fun updateRecyclerViewFocusJump() {
        val firstTab = categoryTabsInner?.getChildAt(0)
        if (firstTab != null && firstTab.id != View.NO_ID) {
            binding.channelsRecycler.nextFocusUpId = firstTab.id
            binding.channelsRecycler.nextFocusLeftId = firstTab.id
            binding.channelsRecycler.nextFocusRightId = firstTab.id
        }
    }

    private fun createCategoryTabView(category: String, index: Int): View {
        val tabView = LayoutInflater.from(requireContext())
            .inflate(R.layout.item_category_tab, categoryTabsInner, false)

        val tabText = tabView.findViewById<TextView>(R.id.tab_text)
        tabText.text = category

        // 设置初始选中状态
        tabView.isSelected = index == selectedCategoryIndex

        // 应用焦点效果
        FocusEffects.enableScaleFocusEffect(tabView)

        // 设置焦点跳转 - 频道卡片的下一焦点向上跳转到这里
        tabView.id = View.generateViewId()
        tabView.nextFocusUpId = tabView.id // 自己指向自己，形成循环

        tabView.setOnClickListener {
            selectCategory(index)
        }

        return tabView
    }

    private fun selectCategory(index: Int) {
        if (index == selectedCategoryIndex) return

        // 更新选中状态
        selectedCategoryIndex = index
        updateTabSelection()
        showGroup(index)
    }

    private fun updateTabSelection() {
        val container = categoryTabsInner ?: return
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            child.isSelected = i == selectedCategoryIndex
        }
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
        selectCategory(index)
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
        categoryTabsInner = null
        _binding = null
    }
}
