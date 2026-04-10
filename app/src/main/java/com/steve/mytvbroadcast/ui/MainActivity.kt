package com.steve.mytvbroadcast.ui

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.steve.mytvbroadcast.R
import com.steve.mytvbroadcast.data.ChannelCacheManager
import com.steve.mytvbroadcast.data.ChannelDatabase
import com.steve.mytvbroadcast.data.MergedChannel
import com.steve.mytvbroadcast.data.SignalSourceManager

class MainActivity : FragmentActivity(), SignalSourceManager.SourceChangeListener, CategoryCallback {

    private lateinit var browseFragment: BrowseFragment
    private lateinit var navigationDrawer: View
    private lateinit var categoryList: RecyclerView
    private lateinit var btnSettings: LinearLayout

    private lateinit var categoryAdapter: CategoryAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        Log.d("MainActivity", "onCreate called")

        SignalSourceManager.init(this)
        ChannelDatabase.init(this)

        // 检查是否有上次播放的频道
        val lastChannelId = ChannelCacheManager.getLastPlayedChannelId()
        if (lastChannelId != null && savedInstanceState == null) {
            Log.d("MainActivity", "Found last played channel: $lastChannelId, launching PlaybackActivity")
            launchPlaybackForLastChannel()
            return
        }

        if (savedInstanceState == null) {
            browseFragment = BrowseFragment()
            supportFragmentManager.beginTransaction()
                .replace(R.id.main_browse_fragment, browseFragment)
                .commit()
        } else {
            browseFragment = supportFragmentManager.findFragmentById(R.id.main_browse_fragment) as BrowseFragment
        }

        initViews()
        setupNavigationDrawer()
        browseFragment.categoryCallback = this

    }

    private fun launchPlaybackForLastChannel() {
        val lastChannelId = ChannelCacheManager.getLastPlayedChannelId() ?: return
        val lastChannelName = ChannelCacheManager.getLastPlayedChannelName() ?: return

        // 加载缓存的频道数据
        val cachedChannels = ChannelCacheManager.getMergedChannels()
        if (cachedChannels.isNullOrEmpty()) {
            Log.d("MainActivity", "No cached channels, cannot resume playback")
            navigateToBrowseFragment()
            return
        }

        // 找到上次播放的频道
        val targetChannel = cachedChannels.find { it.id == lastChannelId }
        if (targetChannel == null) {
            Log.d("MainActivity", "Last channel not found in cache: $lastChannelId")
            navigateToBrowseFragment()
            return
        }

        Log.d("MainActivity", "Resuming playback for channel: ${targetChannel.name}")

        // 构建频道列表和URL列表
        val allChannelUrls = cachedChannels.mapNotNull { it.sources.firstOrNull()?.url }
        val allChannelNames = cachedChannels.map { it.name }
        val channelIndex = cachedChannels.indexOfFirst { it.id == lastChannelId }

        val mergedChannelJson = org.json.JSONObject().apply {
            put("id", targetChannel.id)
            put("name", targetChannel.name)
            put("logo", targetChannel.logo)
            put("group", targetChannel.group)
            put("tvgId", targetChannel.tvgId)
            put("preferredSourceId", targetChannel.preferredSourceId)
            put("lastPlayedSourceId", targetChannel.lastPlayedSourceId)

            val sourcesArray = org.json.JSONArray()
            targetChannel.sources.forEach { source ->
                val sourceJson = org.json.JSONObject().apply {
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
            setClassName(this@MainActivity, "com.steve.mytvbroadcast.ui.PlaybackActivity")
            putExtra("channel_name", targetChannel.name)
            putExtra("merged_channel", mergedChannelJson.toString())
            putExtra("channel_index", channelIndex.coerceAtLeast(0))
            putStringArrayListExtra("channel_urls", ArrayList(allChannelUrls))
            putStringArrayListExtra("channel_names", ArrayList(allChannelNames))
        }
        startActivity(intent)
    }

    private fun navigateToBrowseFragment() {
        val newBrowseFragment = BrowseFragment()
        supportFragmentManager.beginTransaction()
            .replace(R.id.main_browse_fragment, newBrowseFragment)
            .commit()
        browseFragment = newBrowseFragment
        initViews()
        setupNavigationDrawer()
        browseFragment.categoryCallback = this
    }

    private fun initViews() {
        navigationDrawer = findViewById(R.id.navigation_drawer)
        categoryList = navigationDrawer.findViewById(R.id.category_list)
        btnSettings = navigationDrawer.findViewById(R.id.btn_settings)
    }

    private fun setupNavigationDrawer() {
        categoryAdapter = CategoryAdapter { index ->
            browseFragment.onCategorySelected(index)
            categoryAdapter.setSelectedIndex(index)
        }

        categoryList.layoutManager = LinearLayoutManager(this)
        categoryList.adapter = categoryAdapter

        btnSettings.setOnClickListener {
            Log.d("MainActivity", "btnSettings clicked")
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        Log.d("MainActivity", "onWindowFocusChanged hasFocus=$hasFocus")
        if (hasFocus) {
            window.decorView.isFocusable = true
            window.decorView.isFocusableInTouchMode = true
            window.decorView.requestFocus()

            window.decorView.setOnKeyListener { view, keyCode, event ->
                Log.d("MainActivity", "keyCode=$keyCode, action=${event.action}")
                if (keyCode == KeyEvent.KEYCODE_SETTINGS || keyCode == KeyEvent.KEYCODE_MENU) {
                    if (event.action == KeyEvent.ACTION_UP) {
                        Log.d("MainActivity", "Settings/Menu key pressed")
                        startActivity(Intent(this, SettingsActivity::class.java))
                    }
                    true
                } else {
                    false
                }
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        Log.d("MainActivity", "onKeyDown keyCode=$keyCode")
        if (keyCode == KeyEvent.KEYCODE_SETTINGS || keyCode == KeyEvent.KEYCODE_MENU) {
            Log.d("MainActivity", "onKeyDown settings/menu")
            startActivity(Intent(this, SettingsActivity::class.java))
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // CategoryCallback implementation
    override fun onCategoriesLoaded(categories: List<String>) {
        categoryAdapter.submitList(categories)
    }

    // SourceChangeListener implementation
    override fun onSourcesChanged() {
        runOnUiThread {
            browseFragment.reloadChannels()
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d("MainActivity", "onResume")
        SignalSourceManager.addListener(this)

        // 确保 browseFragment 已初始化
        if (!::browseFragment.isInitialized) {
            val newBrowseFragment = BrowseFragment()
            supportFragmentManager.beginTransaction()
                .replace(R.id.main_browse_fragment, newBrowseFragment)
                .commit()
            browseFragment = newBrowseFragment
            initViews()
            setupNavigationDrawer()
            browseFragment.categoryCallback = this
        } else {
            browseFragment.reloadChannels()
        }
    }

    override fun onPause() {
        super.onPause()
        SignalSourceManager.removeListener(this)
    }
}
