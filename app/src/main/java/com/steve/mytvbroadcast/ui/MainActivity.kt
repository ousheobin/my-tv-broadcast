package com.steve.mytvbroadcast.ui

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.steve.mytvbroadcast.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.steve.mytvbroadcast.data.ChannelCacheManager
import com.steve.mytvbroadcast.data.ChannelDatabase
import com.steve.mytvbroadcast.data.EpgManager
import com.steve.mytvbroadcast.data.MergedChannel
import com.steve.mytvbroadcast.data.SignalSourceManager

class MainActivity : FragmentActivity(), SignalSourceManager.SourceChangeListener, CategoryCallback {

    private lateinit var browseFragment: BrowseFragment
    private lateinit var navigationDrawer: View
    private lateinit var categoryList: RecyclerView
    private lateinit var btnSettings: LinearLayout

    private lateinit var categoryAdapter: CategoryAdapter
    private var resumeLoading: View? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        resumeLoading = findViewById(R.id.resume_loading)

        Log.d("MainActivity", "onCreate called")

        SignalSourceManager.init(this)
        ChannelDatabase.init(this)
        EpgManager.init(this)

        // 检查是否有上次播放的频道
        val lastChannelId = ChannelCacheManager.getLastPlayedChannelId()
        val shouldResumeLastChannel = lastChannelId != null && savedInstanceState == null

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

        // 恢复上次频道：先把首页搭好作为底层再启动播放页，这样播放页起播前不会露出空白黑底，
        // 且返回时能回到已填充的首页。
        if (shouldResumeLastChannel) {
            Log.d("MainActivity", "Found last played channel: $lastChannelId, launching PlaybackActivity")
            // 恢复走 IO 线程异步解析，期间首页网格还是空的；先盖上加载遮罩，
            // 避免"空 MyTV 主界面闪一下"。播放页拉起后自然覆盖遮罩，失败则在
            // navigateToBrowseFragment 里撤掉。
            resumeLoading?.visibility = View.VISIBLE
            launchPlaybackForLastChannel()
        }
    }

    private fun launchPlaybackForLastChannel() {
        val lastChannelId = ChannelCacheManager.getLastPlayedChannelId()
        val lastChannelName = ChannelCacheManager.getLastPlayedChannelName()
        if (lastChannelId == null || lastChannelName == null) {
            navigateToBrowseFragment()
            return
        }
        // 优先用单频道快照秒起播：快照就是一个频道的 JSON（很小），主线程直接读即可，
        // 不必等整份几 MB 合并列表解析完。合并列表交给 PlaybackActivity 后台加载后再定位切台序号。
        val snapshot = ChannelCacheManager.getLastPlayedSnapshot()
        if (snapshot != null) {
            Log.d("MainActivity", "Resuming from snapshot: $lastChannelName")
            val intent = Intent().apply {
                setClassName(this@MainActivity, "com.steve.mytvbroadcast.ui.PlaybackActivity")
                putExtra("channel_name", lastChannelName)
                putExtra("merged_channel", snapshot)
                putExtra("resume", true)  // 告知播放页：后台补全频道列表并定位 index
            }
            startActivity(intent)
            return
        }
        // 无快照（旧数据/首次）：回退到在 IO 线程解析整份合并列表，避免卡主线程
        lifecycleScope.launch {
            val intent = withContext(Dispatchers.IO) { buildResumeIntent(lastChannelId) }
            if (isFinishing || isDestroyed) return@launch
            if (intent != null) {
                startActivity(intent)
            } else {
                navigateToBrowseFragment()
            }
        }
    }

    /** 在 IO 线程读取合并缓存并构建恢复播放的 Intent；数据不可用时返回 null。 */
    private fun buildResumeIntent(lastChannelId: String): Intent? {
        val cachedChannels = ChannelCacheManager.getMergedChannels()
        if (cachedChannels.isNullOrEmpty()) {
            Log.d("MainActivity", "No cached channels, cannot resume playback")
            return null
        }

        // 找到上次播放的频道
        val targetChannel = cachedChannels.find { it.id == lastChannelId }
        if (targetChannel == null) {
            Log.d("MainActivity", "Last channel not found in cache: $lastChannelId")
            return null
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
        return intent
    }

    private fun navigateToBrowseFragment() {
        // 恢复失败回到首页：撤掉加载遮罩，露出已加载/正在加载的首页
        resumeLoading?.visibility = View.GONE
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
        // 播放页即将覆盖首页（或离开本页）：撤掉恢复遮罩，
        // 这样从播放页按返回回到 MainActivity 时直接显示首页而不是遮罩。
        resumeLoading?.visibility = View.GONE
    }
}
