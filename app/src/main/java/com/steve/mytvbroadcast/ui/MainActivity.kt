package com.steve.mytvbroadcast.ui

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.steve.mytvbroadcast.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.steve.mytvbroadcast.data.ChannelCacheManager
import com.steve.mytvbroadcast.data.ChannelDatabase
import com.steve.mytvbroadcast.data.ChannelIntentBuilder
import com.steve.mytvbroadcast.data.EpgManager
import com.steve.mytvbroadcast.data.SignalSourceManager

@UnstableApi
class MainActivity : FragmentActivity(), SignalSourceManager.SourceChangeListener, CategoryCallback {

    private lateinit var navigationDrawer: View
    private lateinit var categoryList: RecyclerView
    private lateinit var btnSettings: LinearLayout

    private lateinit var categoryAdapter: CategoryAdapter
    private var landing: View? = null
    private var landingWordmark: View? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        landing = findViewById(R.id.landing)
        landingWordmark = findViewById(R.id.landing_wordmark)

        Log.d("MainActivity", "onCreate called")

        SignalSourceManager.init(this)
        ChannelDatabase.init(this)
        EpgManager.init(this)

        // 检查是否有上次播放的频道
        val lastChannelId = ChannelCacheManager.getLastPlayedChannelId()
        val shouldResumeLastChannel = lastChannelId != null && savedInstanceState == null

        if (savedInstanceState == null) {
            showHome()
        }

        // 返回栈变化（从 P2 返回 P1）后同步回调绑定与抽屉选中态
        supportFragmentManager.addOnBackStackChangedListener {
            bindCallbacks(currentContent())
        }

        initViews()
        setupNavigationDrawer()

        // 恢复上次频道：先把首页搭好作为底层再启动播放页，这样播放页起播前不会露出空白黑底，
        // 且返回时能回到已填充的首页。
        if (shouldResumeLastChannel) {
            Log.d("MainActivity", "Found last played channel: $lastChannelId, launching PlaybackActivity")
            showLanding()
            launchPlaybackForLastChannel()
        }
    }

    /** 当前承载在内容区的页面：HomeFragment（P1）或 BrowseFragment（P2）。 */
    private fun currentContent(): Fragment? =
        supportFragmentManager.findFragmentById(R.id.main_browse_fragment)

    private fun bindCallbacks(fragment: Fragment?) {
        when (fragment) {
            is HomeFragment -> {
                fragment.categoryCallback = this
                fragment.onRequestAllChannels = { index -> showAllChannels(index) }
            }
            is BrowseFragment -> fragment.categoryCallback = this
        }
    }

    private fun showHome() {
        val home = HomeFragment()
        bindCallbacks(home)
        supportFragmentManager.beginTransaction()
            .replace(R.id.main_browse_fragment, home)
            .commitNow()
    }

    private fun showAllChannels(categoryIndex: Int) {
        val browse = BrowseFragment()
        bindCallbacks(browse)
        browse.setInitialCategory(categoryIndex)
        supportFragmentManager.beginTransaction()
            .replace(R.id.main_browse_fragment, browse)
            .addToBackStack("browse")
            .commit()
    }

    /** 落地页动效：品牌字标缓入渐显，不转圈也不写加载文案。 */
    private fun showLanding() {
        val root = landing ?: return
        root.visibility = View.VISIBLE
        landingWordmark?.apply {
            alpha = 0f
            animate()
                .alpha(1f)
                .setDuration(520)
                .setInterpolator(PathInterpolator(0f, 0f, 0.2f, 1f))
                .start()
        }
    }

    private fun launchPlaybackForLastChannel() {
        val lastChannelId = ChannelCacheManager.getLastPlayedChannelId()
        val lastChannelName = ChannelCacheManager.getLastPlayedChannelName()
        if (lastChannelId == null || lastChannelName == null) {
            landing?.visibility = View.GONE
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
                landing?.visibility = View.GONE
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
        val targetChannel = cachedChannels.find { it.id == lastChannelId }
        if (targetChannel == null) {
            Log.d("MainActivity", "Last channel not found in cache: $lastChannelId")
            return null
        }
        Log.d("MainActivity", "Resuming playback for channel: ${targetChannel.name}")
        return ChannelIntentBuilder.build(this, targetChannel, cachedChannels)
    }

    private fun initViews() {
        navigationDrawer = findViewById(R.id.navigation_drawer)
        categoryList = navigationDrawer.findViewById(R.id.category_list)
        btnSettings = navigationDrawer.findViewById(R.id.btn_settings)
    }

    private fun setupNavigationDrawer() {
        categoryAdapter = CategoryAdapter { index ->
            when (val content = currentContent()) {
                is HomeFragment -> content.onCategorySelected(index)
                is BrowseFragment -> content.onCategorySelected(index)
                else -> Unit
            }
            categoryAdapter.setSelectedIndex(index)
        }

        categoryList.layoutManager = LinearLayoutManager(this)
        categoryList.adapter = categoryAdapter

        // 设置项与分类项同一行式语言：聚焦靠白底反色，不做缩放
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
            when (val content = currentContent()) {
                is HomeFragment -> content.reloadChannels()
                is BrowseFragment -> content.reloadChannels()
                else -> Unit
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d("MainActivity", "onResume")
        SignalSourceManager.addListener(this)

        val content = currentContent()
        if (content == null) {
            showHome()
            initViews()
            setupNavigationDrawer()
        } else {
            // 系统重建/返回栈切换后重建回调绑定
            bindCallbacks(content)
            when (content) {
                is HomeFragment -> content.reloadChannels()
                is BrowseFragment -> content.reloadChannels()
                else -> Unit
            }
        }
    }

    override fun onPause() {
        super.onPause()
        SignalSourceManager.removeListener(this)
        // 播放页即将覆盖首页（或离开本页）：撤掉落地页，
        // 这样从播放页按返回回到 MainActivity 时直接显示首页而不是落地页。
        landing?.visibility = View.GONE
    }
}
