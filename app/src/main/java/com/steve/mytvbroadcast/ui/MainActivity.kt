package com.steve.mytvbroadcast.ui

import android.animation.ObjectAnimator
import android.content.Intent
import android.graphics.drawable.Animatable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.PathInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.doOnPreDraw
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
    private var landingWordmark: ImageView? = null
    private var landingCaption: TextView? = null
    private var landingBreathe: ObjectAnimator? = null
    private var motionPending = false
    private var keyHookInstalled = false
    private var epgWatchRegistered = false
    private var contentReady = false
    private var firstResume = true

    private val handler = Handler(Looper.getMainLooper())
    private val revealLandingTask = Runnable { revealLanding() }
    private val landingTimeoutTask = Runnable { hideLanding() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        landing = findViewById(R.id.landing)
        landingWordmark = findViewById(R.id.landing_wordmark)
        landingCaption = findViewById(R.id.landing_caption)

        Log.d("MainActivity", "onCreate called")

        SignalSourceManager.init(this)
        ChannelDatabase.init(this)
        EpgManager.init(this)

        // 检查是否有上次播放的频道
        val lastChannelId = ChannelCacheManager.getLastPlayedChannelId()
        val shouldResumeLastChannel = lastChannelId != null

        if (savedInstanceState == null) {
            showHome()
        }

        initViews()
        setupNavigationDrawer()

        // 恢复上次频道：先把首页搭好作为底层再启动播放页，这样播放页起播前不会露出空白黑底，
        // 且返回时能回到已填充的首页。
        if (shouldResumeLastChannel) {
            Log.d("MainActivity", "Found last played channel: $lastChannelId, launching PlaybackActivity")
            showLanding(delayed = false)
            launchPlaybackForLastChannel()
        } else if (savedInstanceState == null) {
            // 冷启动首页要解析整份合并缓存，慢机器上会空屏一会儿，过宽限期才亮出落地页
            showLanding(delayed = true)
        }
    }

    /** 内容区唯一页面：单页连续流。浏览频道在这条流里滚动，不再换页。 */
    private fun currentContent(): Fragment? =
        supportFragmentManager.findFragmentById(R.id.main_browse_fragment)

    private fun bindCallbacks(fragment: Fragment?) {
        (fragment as? HomeFragment)?.categoryCallback = this
    }

    private fun showHome() {
        val home = HomeFragment()
        bindCallbacks(home)
        supportFragmentManager.beginTransaction()
            .replace(R.id.main_browse_fragment, home)
            .commitNow()
    }

    /** 落地页：恢复播放立即亮出，冷启动先等宽限期，秒开时不露脸。 */
    private fun showLanding(delayed: Boolean) {
        handler.removeCallbacks(revealLandingTask)
        if (delayed) handler.postDelayed(revealLandingTask, LANDING_GRACE_MS) else revealLanding()
    }

    /** 字标逐笔描线生成后转入缓慢呼吸，下方一行说明当前在等什么；不转圈。 */
    private fun revealLanding() {
        val root = landing ?: return
        val wordmark = landingWordmark ?: return
        handler.removeCallbacks(landingTimeoutTask)
        root.animate().cancel()
        root.alpha = 1f
        root.visibility = View.VISIBLE
        landingCaption?.setText(R.string.landing_loading_channels)
        // 已在动效中（淡出被打断）则只续上展示，不重播描线
        if (landingBreathe == null && !motionPending) {
            motionPending = true
            // 描线要等到落地页真的上屏才起算：否则系统启动页还没退场，1500ms 已在后台跑完，看到的就是静止字标
            wordmark.doOnPreDraw { startLandingMotion(wordmark) }
        }
        // 页面被回收等异常路径等不到内容回调时兜底收场，避免落地页常驻
        handler.postDelayed(landingTimeoutTask, LANDING_TIMEOUT_MS)
    }

    private fun startLandingMotion(wordmark: ImageView) {
        motionPending = false
        if (landing?.visibility != View.VISIBLE) return
        wordmark.alpha = 1f
        (wordmark.drawable as? Animatable)?.start()
        landingBreathe = ObjectAnimator.ofFloat(wordmark, View.ALPHA, 1f, 0.45f).apply {
            startDelay = WORDMARK_DRAW_MS
            duration = WORDMARK_BREATHE_MS
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    /** 内容就绪：停掉动效再淡出，让首页自然接上。 */
    private fun hideLanding() {
        handler.removeCallbacks(revealLandingTask)
        handler.removeCallbacks(landingTimeoutTask)
        val root = landing ?: return
        if (root.visibility != View.VISIBLE) return
        stopLandingMotion()
        root.animate()
            .alpha(0f)
            .setDuration(LANDING_EXIT_MS)
            .setInterpolator(PathInterpolator(0.4f, 0f, 1f, 1f))
            .withEndAction { hideLandingComplete(root) }
            .start()
    }

    /** 淡出收尾必须置 GONE：留下一层透明但仍在的最上层，看不见却会把输入吞掉。 */
    private fun hideLandingComplete(root: View) {
        root.visibility = View.GONE
        root.alpha = 1f
    }

    /** 立刻收起且不播动画：本页即将被播放页覆盖或已离开前台。 */
    private fun dismissLanding() {
        handler.removeCallbacks(revealLandingTask)
        handler.removeCallbacks(landingTimeoutTask)
        stopLandingMotion()
        landing?.apply {
            animate().cancel()
            alpha = 1f
            visibility = View.GONE
        }
    }

    private fun stopLandingMotion() {
        motionPending = false
        epgWatchRegistered = false
        landingBreathe?.cancel()
        landingBreathe = null
        (landingWordmark?.drawable as? Animatable)?.stop()
    }

    private fun launchPlaybackForLastChannel() {
        val lastChannelId = ChannelCacheManager.getLastPlayedChannelId()
        val lastChannelName = ChannelCacheManager.getLastPlayedChannelName()
        if (lastChannelId == null || lastChannelName == null) {
            hideLanding()
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
                hideLanding()
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
            (currentContent() as? HomeFragment)?.onCategorySelected(index)
            categoryAdapter.setSelectedIndex(index)
        }

        categoryList.layoutManager = LinearLayoutManager(this)
        categoryList.adapter = categoryAdapter

        // 设置项与分类项同一行式语言：聚焦靠白底反色，不做缩放
        btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || keyHookInstalled) return
        keyHookInstalled = true
        // 只在根层拦下遥控器上的设置/菜单键；把焦点抢到 decorView 会让第一下方向键没有落点
        window.decorView.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_SETTINGS || keyCode == KeyEvent.KEYCODE_MENU) {
                if (event.action == KeyEvent.ACTION_UP) {
                    startActivity(Intent(this, SettingsActivity::class.java))
                }
                true
            } else {
                false
            }
        }
    }

    override fun onBackPressed() {
        // 频道流已滚开时先回到画面：一次返回只做一件看得见的事
        if ((currentContent() as? HomeFragment)?.collapseToTop() == true) return
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_SETTINGS || keyCode == KeyEvent.KEYCODE_MENU) {
            startActivity(Intent(this, SettingsActivity::class.java))
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // CategoryCallback implementation
    override fun onCategoriesLoaded(categories: List<String>) {
        categoryAdapter.submitList(categories)
        contentReady = true
        // 频道到位但节目表还没解完：换一句文案再等一会儿，但绝不拿它扣住用户
        if (landing?.visibility == View.VISIBLE && EpgManager.isEpgEnabled() && !EpgManager.isReady()) {
            landingCaption?.setText(R.string.landing_loading_epg)
            handler.removeCallbacks(landingTimeoutTask)
            handler.postDelayed(landingTimeoutTask, EPG_PHASE_CAP_MS)
            if (!epgWatchRegistered) {
                epgWatchRegistered = true
                EpgManager.whenReady { runOnUiThread { hideLanding() } }
            }
        } else {
            hideLanding()
        }
    }

    // SourceChangeListener implementation
    override fun onSourcesChanged() {
        runOnUiThread {
            (currentContent() as? HomeFragment)?.reloadChannels()
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
            // 系统重建后重建回调绑定
            bindCallbacks(content)
            // 冷启动首个 resume：首页建视图时已经解过一遍缓存，不再重复读第二遍
            if (!firstResume) {
                (content as? HomeFragment)?.reloadChannels()
                // 上次离开时首页仍未填充过，回来继续等，慢就再亮落地页
                if (!contentReady) showLanding(delayed = true)
            }
        }
        firstResume = false
    }

    override fun onPause() {
        super.onPause()
        SignalSourceManager.removeListener(this)
        // 播放页即将覆盖首页（或离开本页）：撤掉落地页，
        // 这样从播放页按返回回到 MainActivity 时直接显示首页而不是落地页。
        dismissLanding()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        stopLandingMotion()
    }

    private companion object {
        const val LANDING_GRACE_MS = 450L
        const val WORDMARK_DRAW_MS = 1500L
        const val WORDMARK_BREATHE_MS = 1200L
        const val LANDING_EXIT_MS = 260L
        const val LANDING_TIMEOUT_MS = 6000L
        const val EPG_PHASE_CAP_MS = 900L
    }
}
