package com.steve.mytvbroadcast.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.steve.mytvbroadcast.R
import com.steve.mytvbroadcast.data.ChannelCacheManager
import com.steve.mytvbroadcast.data.ChannelSource
import com.steve.mytvbroadcast.data.EpgManager
import com.steve.mytvbroadcast.data.MergedChannel
import com.steve.mytvbroadcast.data.SelectionMode
import com.steve.mytvbroadcast.data.SpeedTester
import com.steve.mytvbroadcast.data.SourceSelector
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import java.util.concurrent.atomic.AtomicLong
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

@UnstableApi
class PlaybackActivity : FragmentActivity() {

    companion object {
        const val EXTRA_CHANNEL_NAME = "channel_name"
        const val EXTRA_CHANNEL_URL = "channel_url"
        const val EXTRA_CHANNEL_INDEX = "channel_index"
        const val EXTRA_CHANNEL_URLS = "channel_urls"
        const val EXTRA_CHANNEL_NAMES = "channel_names"
        const val EXTRA_MERGED_CHANNEL = "merged_channel"
        const val EXTRA_ALL_MERGED_CHANNELS = "all_merged_channels"

        private const val MAX_RETRIES = 3
        private const val BUFFERING_TIMEOUT_MS = 8000L
        private const val SOURCE_RETEST_INTERVAL_MS = 10000L
        private const val MANUAL_SWITCH_RESPECT_MS = 3 * 60 * 1000L
        private const val EPG_TICK_INTERVAL_MS = 30 * 1000L
        // 开始播放后控制条（含频道名+EPG 底条）的自动收起延时
        private const val CONTROLS_HIDE_DELAY_MS = 3000L

        private val tlsCompatibleClient: OkHttpClient by lazy {
            try {
                java.security.Security.setProperty("ocsp.enable", "false")
                System.setProperty("com.sun.security.enableCRLDP", "false")
            } catch (_: Exception) {}

            val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            })
            val sslContext = SSLContext.getInstance("TLS").apply {
                init(null, trustAllCerts, SecureRandom())
            }
            OkHttpClient.Builder()
                .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
                .hostnameVerifier { _, _ -> true }
                .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
        }
    }

    private var player: ExoPlayer? = null
    private var playWhenReady = true
    private var playbackPosition = 0L

    private lateinit var playerView: PlayerView
    private lateinit var channelNameText: TextView
    private lateinit var playbackInfoText: TextView
    private lateinit var loadingIndicator: ProgressBar
    private var loadingContainer: View? = null
    private lateinit var networkSpeedText: TextView
    private lateinit var channelIndexText: TextView
    private lateinit var topOverlay: LinearLayout
    private lateinit var bottomOverlay: LinearLayout
    private lateinit var sourcePanel: LinearLayout
    private lateinit var sourceSwitchList: RecyclerView

    // EPG 节目表
    private lateinit var epgCurrentProgramText: TextView
    private lateinit var epgProgressRow: View
    private lateinit var epgStartTimeText: TextView
    private lateinit var epgEndTimeText: TextView
    private lateinit var epgNextProgramText: TextView
    private lateinit var epgProgressFill: View
    private lateinit var epgProgressSpacer: View
    private val epgTickerRunnable = Runnable { scheduleEpgTick() }

    private fun scheduleEpgTick() {
        handler.removeCallbacks(epgTickerRunnable)
        handler.postDelayed(epgTickerRunnable, EPG_TICK_INTERVAL_MS)
        updateEpgDisplay()
    }

    private var currentMergedChannel: MergedChannel? = null
    private var currentSource: ChannelSource? = null
    private lateinit var sourceSwitchAdapter: SourceSwitchAdapter
    private var attemptedSources = mutableSetOf<String>()
    private var allMergedChannels = mutableListOf<MergedChannel>()
    private var allChannelUrls = listOf<String>()

    private lateinit var channelUrls: List<String>
    private lateinit var channelNames: List<String>
    private var currentIndex = 0

    private val handler = Handler(Looper.getMainLooper())
    private var scope: CoroutineScope? = null
    private val job = SupervisorJob()
    private var isControlsVisible = true
    private val hideControlsRunnable = Runnable { hideControls() }

    // 重试相关
    private var retryCount = 0
    private var isRetrying = false

    // buffering 超时相关
    private var bufferingStartTime = 0L
    private var isBufferingTimedOut = false
    private val bufferingTimeoutRunnable = Runnable { onBufferingTimeout() }

    private var isSourcePanelVisible = false
    private lateinit var byteTrackingFactory: DataSource.Factory

    // 切流过程中 stop() 会同步回调 STATE_IDLE，用它屏蔽“信号不可用”提示
    private var isSwitchingStream = false

    // 后台测速选优 / 手动选源
    private var lastManualSwitchTime = 0L
    private var isSourceSpeedTesting = false
    private val sourceMonitorRunnable = Runnable { scheduleSourceMonitorTick() }

    private var totalBytesRead = 0L
    private var lastBytesRead = 0L
    private var lastSpeedUpdateTime = 0L
    private var currentSpeedBps = 0L
    private val speedUpdateInterval = 1000L
    private val speedHandler = Handler(Looper.getMainLooper())
    private val updateSpeedRunnable = Runnable { updateNetworkSpeedDisplay() }

    private fun getScope(): CoroutineScope {
        if (scope == null || scope!!.isActive == false) {
            scope = CoroutineScope(Dispatchers.Main + job)
        }
        return scope!!
    }

    private inner class ByteTrackingDataSourceFactory(private val upstreamFactory: DataSource.Factory) : DataSource.Factory {
        // 每个分片都会创建新的 DataSource，累计值需保存在工厂级别
        private val accumulator = AtomicLong(0L)

        private inner class Tracking(private val upstreamSource: DataSource) : DataSource by upstreamSource {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                val bytesRead = upstreamSource.read(buffer, offset, length)
                if (bytesRead > 0) {
                    totalBytesRead = accumulator.addAndGet(bytesRead.toLong())
                }
                return bytesRead
            }
        }

        override fun createDataSource(): DataSource = Tracking(upstreamFactory.createDataSource())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_playback)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        playerView = findViewById(R.id.player_view)
        channelNameText = findViewById(R.id.channel_name)
        playbackInfoText = findViewById(R.id.playback_info)
        loadingIndicator = findViewById(R.id.loading_indicator)
        loadingContainer = findViewById(R.id.loading_container)
        networkSpeedText = findViewById(R.id.network_speed)
        channelIndexText = findViewById(R.id.channel_index)
        topOverlay = findViewById(R.id.top_overlay)
        bottomOverlay = findViewById(R.id.bottom_overlay)
        sourcePanel = findViewById(R.id.source_panel)
        sourceSwitchList = findViewById(R.id.source_switch_list)
        epgCurrentProgramText = findViewById(R.id.epg_current_program)
        epgProgressRow = findViewById(R.id.epg_progress_row)
        epgStartTimeText = findViewById(R.id.epg_start_time)
        epgEndTimeText = findViewById(R.id.epg_end_time)
        epgNextProgramText = findViewById(R.id.epg_next_program)
        epgProgressFill = findViewById(R.id.epg_progress_fill)
        epgProgressSpacer = findViewById(R.id.epg_progress_spacer)

        setupSourceSwitchList()

        val mergedChannelJson = intent.getStringExtra(EXTRA_MERGED_CHANNEL)
        if (mergedChannelJson != null) {
            parseMergedChannel(mergedChannelJson)
        } else {
            setupLegacyChannelList()
        }

        updateChannelInfo()
        initEpg()

        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_FULLSCREEN
        )
    }

    private fun parseMergedChannel(json: String) {
        try {
            val jsonObj = org.json.JSONObject(json)
            val sourcesArray = jsonObj.getJSONArray("sources")
            val sources = mutableListOf<ChannelSource>()
            for (i in 0 until sourcesArray.length()) {
                val sourceJson = sourcesArray.getJSONObject(i)
                sources.add(ChannelSource(
                    sourceId = sourceJson.getString("sourceId"),
                    signalSourceId = sourceJson.optString("signalSourceId", sourceJson.getString("sourceId")),
                    sourceName = sourceJson.getString("sourceName"),
                    url = sourceJson.getString("url"),
                    speed = sourceJson.optLong("speed", -1),
                    lastTested = sourceJson.optLong("lastTested", 0),
                    isAvailable = sourceJson.optBoolean("isAvailable", true)
                ))
            }
            currentMergedChannel = MergedChannel(
                id = jsonObj.getString("id"),
                name = jsonObj.getString("name"),
                logo = jsonObj.optString("logo", null),
                group = jsonObj.optString("group", null),
                tvgId = jsonObj.optString("tvgId", null),
                sources = sources,
                preferredSourceId = jsonObj.optString("preferredSourceId", null),
                lastPlayedSourceId = jsonObj.optString("lastPlayedSourceId", null)
            )
            channelNames = intent.getStringArrayListExtra(EXTRA_CHANNEL_NAMES) ?: listOf(currentMergedChannel!!.name)
            // channelUrls 是当前频道的所有信号源（用于播放）
            channelUrls = currentMergedChannel!!.sources.map { it.url }
            // allChannelUrls 是所有频道的 URL（用于频道切换）
            allChannelUrls = intent.getStringArrayListExtra(EXTRA_CHANNEL_URLS) ?: listOf()
            // 起播序号必须与 allChannelUrls 对齐，否则上下切台会从错误位置开始
            currentIndex = intent.getIntExtra(EXTRA_CHANNEL_INDEX, 0).coerceIn(0, maxOf(allChannelUrls.size - 1, 0))

            // 从缓存加载所有 MergedChannel
            val cached = ChannelCacheManager.getMergedChannels()
            android.util.Log.d("PlaybackActivity", "parseMergedChannel: cached channels = ${cached?.size}")
            if (cached != null) {
                allMergedChannels.clear()
                allMergedChannels.addAll(cached)
                android.util.Log.d("PlaybackActivity", "parseMergedChannel: allMergedChannels.size = ${allMergedChannels.size}")
            }
            // 注意：测速只在 playMergedChannel 中进行，且只有频道没有测速数据时才测
        } catch (e: Exception) {
            println("PlaybackActivity: Error parsing merged channel: ${e.message}")
            setupLegacyChannelList()
        }
    }

    private fun setupLegacyChannelList() {
        channelUrls = intent.getStringArrayListExtra(EXTRA_CHANNEL_URLS) ?: listOf()
        channelNames = intent.getStringArrayListExtra(EXTRA_CHANNEL_NAMES) ?: listOf()
        currentIndex = intent.getIntExtra(EXTRA_CHANNEL_INDEX, 0)

        if (channelUrls.isEmpty()) {
            val singleUrl = intent.getStringExtra(EXTRA_CHANNEL_URL)
            if (singleUrl != null) {
                channelUrls = listOf(singleUrl)
                channelNames = listOf(intent.getStringExtra(EXTRA_CHANNEL_NAME) ?: "Unknown")
            }
        }
    }

    private fun setupSourceSwitchList() {
        sourceSwitchAdapter = SourceSwitchAdapter { source ->
            switchToSource(source)
        }
        sourceSwitchList.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        sourceSwitchList.adapter = sourceSwitchAdapter
    }

    private fun updateChannelInfo() {
        resolveCurrentChannelName()?.let { channelNameText.text = it }
        channelIndexText.text = "${currentIndex + 1} / ${allChannelUrls.size}"
        updateEpgDisplay()
    }

    // 名称优先级：当前 MergedChannel > 随 intent 传入的 channel_names > 上次解析出的旧名称，
    // 避免 URL/缓存匹配失败时频道名和 EPG 一起消失
    private fun resolveCurrentChannelName(): String? =
        currentMergedChannel?.name
            ?: channelNames.getOrNull(currentIndex)
            ?: channelNameText.text?.takeIf { it.isNotEmpty() }?.toString()

    // ==================== EPG 节目表 ====================

    private fun initEpg() {
        if (!EpgManager.isEpgEnabled()) return
        if (EpgManager.isStale()) {
            EpgManager.refreshAsync { success ->
                if (success && !isFinishing && !isDestroyed) {
                    handler.post { updateEpgDisplay() }
                }
            }
        }
        scheduleEpgTick()
    }

    private fun updateEpgDisplay() {
        val channel = currentMergedChannel
        val now = System.currentTimeMillis()
        val current = if (channel != null) EpgManager.getCurrentProgramme(channel, now) else null
        if (channel == null || current == null) {
            epgCurrentProgramText.visibility = View.GONE
            epgProgressRow.visibility = View.GONE
            channelIndexText.visibility = View.VISIBLE
            return
        }

        channelIndexText.visibility = View.GONE
        epgCurrentProgramText.visibility = View.VISIBLE
        epgCurrentProgramText.text = current.title
        epgStartTimeText.text = formatEpgTime(current.startMs)
        epgEndTimeText.text = formatEpgTime(current.stopMs)

        val next = EpgManager.getNextProgramme(channel, now)
        if (next != null) {
            epgNextProgramText.visibility = View.VISIBLE
            epgNextProgramText.text = "接下来 ${formatEpgTime(next.startMs)} ${next.title}"
        } else {
            epgNextProgramText.visibility = View.GONE
        }

        epgProgressRow.visibility = View.VISIBLE
        val duration = current.stopMs - current.startMs
        val fraction = if (duration > 0) ((now - current.startMs).toFloat() / duration).coerceIn(0f, 1f) else 0f
        // 轨道内用 fill + spacer 权重分配进度，无需等待测量
        (epgProgressFill.layoutParams as android.widget.LinearLayout.LayoutParams).apply {
            weight = fraction
            epgProgressFill.layoutParams = this
        }
        (epgProgressSpacer.layoutParams as android.widget.LinearLayout.LayoutParams).apply {
            weight = 1f - fraction
            epgProgressSpacer.layoutParams = this
        }
    }

    private fun formatEpgTime(timeMs: Long): String {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = timeMs }
        return String.format("%02d:%02d", cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE))
    }

    private fun playMergedChannel() {
        if (isFinishing || isDestroyed) return
        val mergedChannel = currentMergedChannel ?: return

        android.util.Log.d("PlaybackActivity", "playMergedChannel ENTER: channel=${mergedChannel.name}(id=${mergedChannel.id}), currentSource=${currentSource?.sourceId}")
        android.util.Log.d("PlaybackActivity", "playMergedChannel: sources=${mergedChannel.sources.map { "${it.sourceId}(speed=${it.speed}, lastTested=${it.lastTested})" }}")

        // 保存最后播放的频道
        saveLastPlayedChannel()

        // 优先使用上次播放的信号源
        val lastPlayedSourceId = ChannelCacheManager.getLastPlayedSource(mergedChannel.id)
        val lastPlayedSource = if (lastPlayedSourceId != null) {
            mergedChannel.sources.find { it.sourceId == lastPlayedSourceId && it.isAvailable }
        } else null

        if (lastPlayedSource != null) {
            // 有上次播放的信号源且可用，直接使用
            android.util.Log.d("PlaybackActivity", "playMergedChannel: using last played source ${lastPlayedSource.sourceId}")
            currentSource = lastPlayedSource
            attemptedSources.add(currentSource!!.sourceId)
            updateSourcePanel()
            playSource(currentSource!!)
            startSourceMonitor()
            return
        }

        // 检查是否所有源都已经有测速数据（lastTested > 0 表示已测速过）
        val hasSpeedData = mergedChannel.sources.any { it.lastTested > 0 && it.speed > 0 }

        if (hasSpeedData) {
            // 有测速数据，使用缓存的speed值找最快的源
            android.util.Log.d("PlaybackActivity", "playMergedChannel: using cached speed data, no speed test needed")
            val fastestSource = SourceSelector.selectSource(mergedChannel, SelectionMode.FASTEST)
            if (fastestSource != null) {
                currentSource = fastestSource
                attemptedSources.add(currentSource!!.sourceId)
                ChannelCacheManager.setLastPlayedSource(mergedChannel.id, currentSource!!.sourceId)
                updateSourcePanel()
                playSource(currentSource!!)
                startSourceMonitor()
            } else {
                playbackInfoText.text = "无可用信号源"
            }
        } else {
            // 没有测速数据，进行测速（只测一次）
            playbackInfoText.text = "正在测速..."
            setLoadingVisible(true)

            getScope().launch {
                android.util.Log.d("PlaybackActivity", "playMergedChannel COROUTINE: channel=${mergedChannel.name}, speed testing ${mergedChannel.sources.size} sources")
                val fastestSource = SpeedTester.speedTestUntilFirstSuccess(mergedChannel.sources, timeoutMs = 2000)

                if (isFinishing || isDestroyed) return@launch

                if (fastestSource != null) {
                    android.util.Log.d("PlaybackActivity", "playMergedChannel: speed test found fastest=${fastestSource.sourceId}(speed=${fastestSource.speed}ms)")
                    currentSource = fastestSource
                    val updatedMergedChannel = updateChannelSpeed(mergedChannel, fastestSource)
                    // 保存测速结果到缓存
                    ChannelCacheManager.updateMergedChannelInCache(updatedMergedChannel)
                    // 同时更新 allMergedChannels 中的对应频道
                    val index = allMergedChannels.indexOfFirst { it.id == updatedMergedChannel.id }
                    if (index >= 0) {
                        allMergedChannels[index] = updatedMergedChannel
                    }
                } else {
                    android.util.Log.d("PlaybackActivity", "playMergedChannel: speed test timeout, using source 0")
                    currentSource = mergedChannel.sources.firstOrNull()
                }

                if (currentSource != null) {
                    attemptedSources.add(currentSource!!.sourceId)
                    ChannelCacheManager.setLastPlayedSource(mergedChannel.id, currentSource!!.sourceId)
                    android.util.Log.d("PlaybackActivity", "playMergedChannel: NOW PLAYING channel=${mergedChannel.name}, source=${currentSource!!.sourceId}")
                    updateSourcePanel()
                    playSource(currentSource!!)
                    startSourceMonitor()
                } else {
                    playbackInfoText.text = "无可用信号源"
                }
            }
        }
    }

    // 更新频道中对应源的测速数据
    private fun updateChannelSpeed(mergedChannel: MergedChannel, fastestSource: ChannelSource): MergedChannel {
        val updatedSources = mergedChannel.sources.map { source ->
            if (source.sourceId == fastestSource.sourceId) {
                fastestSource
            } else {
                source
            }
        }
        val updatedMergedChannel = mergedChannel.copy(sources = updatedSources)
        currentMergedChannel = updatedMergedChannel
        android.util.Log.d("PlaybackActivity", "updateChannelSpeed: updated speed for ${fastestSource.sourceId}")
        return updatedMergedChannel
    }

    private fun saveLastPlayedChannel() {
        currentMergedChannel?.let { channel ->
            ChannelCacheManager.setLastPlayedChannel(channel.id, channel.name)
            android.util.Log.d("PlaybackActivity", "saveLastPlayedChannel: saved ${channel.name}(id=${channel.id})")
        }
    }

    private fun playSource(source: ChannelSource) {
        if (isFinishing || isDestroyed) return
        channelNameText.text = currentMergedChannel?.name ?: channelNames.getOrNull(currentIndex) ?: "Unknown"
        updateSourceInfo("加载中")
        showControls()
        resetRetryState()

        val exoPlayer = player ?: return
        // 直接起播，重定向由 OkHttp 数据源自动跟随，不再阻塞预下载；
        // switchStream 会先释放旧流视频层，避免切台时新旧画面重叠
        switchStream(source.url)
        android.util.Log.d("PlaybackActivity", "playSource: playing with player active=${exoPlayer.isPlaying || exoPlayer.playbackState == Player.STATE_BUFFERING}")
    }

    // 统一切流：先摘除 surface 并 stop，释放旧流的硬解视频层，
    // 避免部分 TV 上新旧两路画面叠成“画中画”残留
    private fun switchStream(url: String) {
        val exoPlayer = player ?: return
        isSwitchingStream = true
        try {
            playerView.player = null
            exoPlayer.stop()
            val mediaSource = buildMediaSource(url)
            exoPlayer.setMediaSource(mediaSource)
            exoPlayer.playWhenReady = true
            exoPlayer.prepare()
        } finally {
            isSwitchingStream = false
            playerView.player = exoPlayer
        }
    }

    private fun switchToSource(source: ChannelSource, hidePanel: Boolean = true) {
        if (isFinishing || isDestroyed) return
        if (currentMergedChannel == null) return

        android.util.Log.d("PlaybackActivity", "switchToSource: ENTRY switching to ${source.sourceId}, url=${source.url}, hidePanel=$hidePanel, currentSource currently=${currentSource?.sourceId}")
        attemptedSources.add(source.sourceId)
        currentSource = source
        lastManualSwitchTime = System.currentTimeMillis()
        handler.removeCallbacks(sourceMonitorRunnable)
        ChannelCacheManager.setLastPlayedSource(currentMergedChannel!!.id, source.sourceId)
        if (hidePanel) hideSourcePanel()
        resetRetryState()
        selectedSourceIndex = findSourceByUrl(currentMergedChannel!!.sources, source.url)
        playSource(source)
        // 切换后恢复后台健康监控
        startSourceMonitor()
    }

    private fun findSourceByUrl(sources: List<ChannelSource>, url: String?): Int =
        if (url == null) -1 else sources.indexOfFirst { it.url == url }

    // 当前频道正在播放的信号源序号，如 "#2/3"；无法确定时返回 null
    private fun currentSourceBadge(): String? {
        val sources = currentMergedChannel?.sources ?: return null
        if (sources.isEmpty()) return null
        val pos = findSourceByUrl(sources, currentSource?.url)
        if (pos < 0) return null
        return "信号源 #${pos + 1}/${sources.size}"
    }

    // 信息行统一显示当前信号源序号，可附带一个短状态（如"播放中"），不再显示切换提示
    private fun updateSourceInfo(state: String? = null) {
        val badge = currentSourceBadge()
        playbackInfoText.text = when {
            badge != null && state != null -> "$badge · $state"
            badge != null -> badge
            state != null -> state
            else -> ""
        }
    }

    private fun switchToPreviousSource() {
        if (isFinishing || isDestroyed) return
        val mergedChannel = currentMergedChannel ?: return
        val sources = mergedChannel.sources
        if (sources.isEmpty()) return

        // 按 URL 匹配当前位置（sourceId 可能因缓存刷新而变化）
        val pos = findSourceByUrl(sources, currentSource?.url)
        val prevSource = if (pos <= 0) sources.last() else sources[pos - 1]
        switchToSource(prevSource)
    }

    private fun switchToNextSource() {
        if (isFinishing || isDestroyed) return
        val mergedChannel = currentMergedChannel ?: return
        val sources = mergedChannel.sources
        if (sources.isEmpty()) return

        val pos = findSourceByUrl(sources, currentSource?.url)
        val nextSource = if (pos < 0 || pos >= sources.size - 1) sources.first() else sources[pos + 1]
        switchToSource(nextSource)
    }

    private fun resetRetryState() {
        retryCount = 0
        isRetrying = false
        isBufferingTimedOut = false
        bufferingStartTime = 0L
        handler.removeCallbacks(bufferingTimeoutRunnable)
    }

    private fun onBufferingTimeout() {
        if (isFinishing || isDestroyed) return
        isBufferingTimedOut = true
        switchToNextBestSource("加载超时")
    }

    private fun handlePlaybackStall() {
        if (isFinishing || isDestroyed) return
        if (retryCount < MAX_RETRIES && !isRetrying) {
            isRetrying = true
            retryCount++
            playbackInfoText.text = "播放卡顿，重试 ($retryCount/$MAX_RETRIES)..."
            handler.postDelayed({
                if (!isFinishing && !isDestroyed) {
                    isRetrying = false
                    retryPlay()
                }
            }, 2000)
        } else {
            // 重试次数用尽，自动切换到下一个最优信号源
            switchToNextBestSource("播放卡顿")
        }
    }

    override fun onStart() {
        super.onStart()
        initializePlayer()
    }

    override fun onResume() {
        super.onResume()
        hideSystemUi()
        if (player == null) {
            initializePlayer()
        }
        scheduleHideControls()
    }

    override fun onPause() {
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(hideControlsRunnable)
        handler.removeCallbacks(bufferingTimeoutRunnable)
        handler.removeCallbacks(sourceMonitorRunnable)
        handler.removeCallbacks(epgTickerRunnable)
        releasePlayer()
    }

    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
        scope?.cancel()
        scope = null
        speedHandler.removeCallbacks(updateSpeedRunnable)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        android.util.Log.d("PlaybackActivity", "onKeyDown: keyCode=$keyCode, isSourcePanelVisible=$isSourcePanelVisible")

        // 控制条已收起时，任意按键先重新唤出（showControls 内部会重新排 3s 收起）
        if (!isControlsVisible && !isSourcePanelVisible) {
            showControls()
        }

        // 面板打开时，UP/DOWN 切换频道，LEFT/RIGHT 导航信号源列表
        if (isSourcePanelVisible) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> {
                    playPreviousChannel()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    playNextChannel()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    moveSourceSelection(-1)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    moveSourceSelection(1)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    confirmSourceSelection()
                    return true
                }
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                    hideSourcePanel()
                    return true
                }
                KeyEvent.KEYCODE_SETTINGS, KeyEvent.KEYCODE_MENU -> {
                    hideSourcePanel()
                    return true
                }
            }
            return true
        }

        // 面板关闭时，正常按键行为
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                playPreviousChannel()
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                playNextChannel()
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                showSourcePanelAndHighlight()
                switchToPreviousSource()
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                showSourcePanelAndHighlight()
                switchToNextSource()
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                player?.let {
                    if (it.isPlaying) it.pause() else it.play()
                }
                return true
            }
            KeyEvent.KEYCODE_SETTINGS, KeyEvent.KEYCODE_MENU -> {
                toggleSourcePanel()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun toggleSourcePanel() {
        if (isFinishing || isDestroyed) return
        val mergedChannel = currentMergedChannel ?: return

        isSourcePanelVisible = !isSourcePanelVisible
        sourcePanel.visibility = if (isSourcePanelVisible) View.VISIBLE else View.GONE

        if (isSourcePanelVisible) {
            sourceSwitchAdapter.submitList(mergedChannel.sources)
            selectedSourceIndex = findSourceByUrl(mergedChannel.sources, currentSource?.url)
            android.util.Log.d("PlaybackActivity", "toggleSourcePanel: matched index=$selectedSourceIndex")
            if (selectedSourceIndex < 0) {
                android.util.Log.d("PlaybackActivity", "toggleSourcePanel: no match found, default to 0")
                selectedSourceIndex = 0
            }
            sourceSwitchAdapter.setCurrentSource(mergedChannel.sources[selectedSourceIndex].sourceId)
            // 请求焦点到信号源列表
            sourceSwitchList.requestFocus()
            // 只收顶部小字；底条保留（信号源面板不透明背景本身会盖住底条区域）
            topOverlay.visibility = View.GONE
        } else {
            scheduleHideControls()
        }
    }

    private fun hideSourcePanel() {
        isSourcePanelVisible = false
        sourcePanel.visibility = View.GONE
        scheduleHideControls()
    }

    private var selectedSourceIndex: Int = 0

    private fun moveSourceSelection(direction: Int) {
        android.util.Log.d("PlaybackActivity", "moveSourceSelection: called with direction=$direction")
        val mergedChannel = currentMergedChannel ?: run {
            android.util.Log.d("PlaybackActivity", "moveSourceSelection: early return - no mergedChannel")
            return
        }
        val sources = mergedChannel.sources
        if (sources.isEmpty()) return

        selectedSourceIndex += direction
        if (selectedSourceIndex < 0) selectedSourceIndex = sources.size - 1
        if (selectedSourceIndex >= sources.size) selectedSourceIndex = 0

        // 面板内左右导航只做纯选中移动，按确认键才切换播放
        android.util.Log.d("PlaybackActivity", "moveSourceSelection: selectedSourceIndex=$selectedSourceIndex")
        sourceSwitchAdapter.setCurrentSource(sources[selectedSourceIndex].sourceId)

        // 滚动到可见位置
        sourceSwitchList.smoothScrollToPosition(selectedSourceIndex)
    }

    private fun confirmSourceSelection() {
        val mergedChannel = currentMergedChannel ?: return
        val sources = mergedChannel.sources
        android.util.Log.d("PlaybackActivity", "confirmSourceSelection: selectedSourceIndex=$selectedSourceIndex, sources.size=${sources.size}")
        if (selectedSourceIndex < 0 || selectedSourceIndex >= sources.size) return

        val selectedSource = sources[selectedSourceIndex]
        android.util.Log.d("PlaybackActivity", "confirmSourceSelection: selectedSource=${selectedSource.sourceId}")
        switchToSource(selectedSource)
        hideSourcePanel()
    }

    private fun updateSourcePanel() {
        if (isFinishing || isDestroyed) return
        currentMergedChannel?.let { channel ->
            sourceSwitchAdapter.submitList(channel.sources)
            sourceSwitchAdapter.setCurrentSource(currentSource?.sourceId)
            val pos = findSourceByUrl(channel.sources, currentSource?.url)
            if (pos >= 0) selectedSourceIndex = pos
        }
    }

    private fun playPreviousChannel() {
        android.util.Log.d("PlaybackActivity", "playPreviousChannel: before currentIndex=$currentIndex, allChannelUrls.size=${allChannelUrls.size}")
        if (allChannelUrls.isEmpty()) return
        currentIndex = if (currentIndex <= 0) allChannelUrls.size - 1 else currentIndex - 1
        android.util.Log.d("PlaybackActivity", "playPreviousChannel: after currentIndex=$currentIndex")
        updateChannelInfo()
        switchToChannel(currentIndex)
    }

    private fun playNextChannel() {
        android.util.Log.d("PlaybackActivity", "playNextChannel: before currentIndex=$currentIndex, allChannelUrls.size=${allChannelUrls.size}")
        if (allChannelUrls.isEmpty()) return
        currentIndex = if (currentIndex >= allChannelUrls.size - 1) 0 else currentIndex + 1
        android.util.Log.d("PlaybackActivity", "playNextChannel: after currentIndex=$currentIndex")
        updateChannelInfo()
        switchToChannel(currentIndex)
    }

    private fun switchToChannel(index: Int) {
        android.util.Log.d("PlaybackActivity", "switchToChannel: index=$index, allChannelUrls.size=${allChannelUrls.size}, channelNames=${channelNames}")
        if (index < 0 || index >= allChannelUrls.size) return
        if (isFinishing || isDestroyed) return
        currentIndex = index
        resetRetryState()
        attemptedSources.clear()

        val currentUrl = allChannelUrls[index]
        val expectedName = channelNames.getOrNull(index) ?: "Unknown"
        android.util.Log.d("PlaybackActivity", "switchToChannel: currentUrl=$currentUrl, expectedChannelName=$expectedName")

        // 频道名先按名称即时更新，不依赖后面的缓存匹配结果（匹配失败时名称/EPG 不能空着）
        resolveCurrentChannelName()?.let { channelNameText.text = it }

        // 根据 allChannelUrls[index] 找到对应的 MergedChannel：先按名称（与 intent 同一数据源，最可靠），
        // 再按任意信号源 URL 兜底
        if (allMergedChannels.isNotEmpty()) {
            android.util.Log.d("PlaybackActivity", "switchToChannel: looking for url in ${allMergedChannels.size} channels")
            val matched = allMergedChannels.find { it.name == expectedName }
                ?: allMergedChannels.find { mc -> mc.sources.any { it.url == currentUrl } }
            if (matched != null) {
                currentMergedChannel = matched
                // 切换频道时重置 currentSource，避免旧频道的数据影响新频道
                currentSource = null
                attemptedSources.clear()
                // 更新 channelUrls 为新频道的信号源
                channelUrls = matched.sources.map { it.url }
            } else {
                android.util.Log.d("PlaybackActivity", "switchToChannel: no MergedChannel matched name=$expectedName url=$currentUrl, keep name and hide EPG")
                currentMergedChannel = null
                channelUrls = listOf(currentUrl)
            }
            // 保存最后播放的频道
            saveLastPlayedChannel()
            android.util.Log.d("PlaybackActivity", "switchToChannel: found mergedChannel=${currentMergedChannel?.name}(id=${currentMergedChannel?.id}), channelUrls.size=${channelUrls.size}")
            // currentMergedChannel 已更新为新频道，按新频道刷新节目表
            // （updateChannelInfo 里的刷新发生在赋值之前，用的是旧频道）
            updateEpgDisplay()
            if (currentMergedChannel != null) {
                playMergedChannel()
            } else {
                // 缓存里找不到对应频道时，直接用 URL 兜底播放，不能停在旧频道
                playUrlDirectly(currentUrl)
            }
        } else if (currentMergedChannel != null) {
            android.util.Log.d("PlaybackActivity", "switchToChannel: allMergedChannels is empty, using currentMergedChannel")
            val source = currentSource ?: currentMergedChannel!!.sources.firstOrNull()
            if (source != null) {
                currentSource = source
                attemptedSources.add(source.sourceId)
                updateSourcePanel()
                playSource(source)
                startSourceMonitor()
            } else {
                playbackInfoText.text = "无可用信号源"
            }
        } else {
            playUrlDirectly(currentUrl)
        }
    }

    // 无 MergedChannel 信息时的兜底播放：直接播 intent 里的 URL
    private fun playUrlDirectly(url: String) {
        playbackInfoText.text = "正在切换..."
        showControls()
        scheduleHideControls()

        switchStream(url)
    }

    private fun showControls() {
        if (isFinishing || isDestroyed) return
        isControlsVisible = true
        topOverlay.visibility = View.VISIBLE
        bottomOverlay.visibility = View.VISIBLE
        handler.removeCallbacks(hideControlsRunnable)
        scheduleHideControls()
    }

    private fun hideControls() {
        isControlsVisible = false
        topOverlay.visibility = View.GONE
        bottomOverlay.visibility = View.GONE
    }

    // 加载圆圈与 88dp 圆形背景同进同出，避免背景残留
    private fun setLoadingVisible(visible: Boolean) {
        val visibility = if (visible) View.VISIBLE else View.GONE
        loadingContainer?.visibility = visibility
        loadingIndicator.visibility = visibility
    }

    private fun scheduleHideControls() {
        handler.removeCallbacks(hideControlsRunnable)
        if (!isFinishing && !isDestroyed) {
            handler.postDelayed({
                if (!isFinishing && !isDestroyed && player?.isPlaying == true && !isSourcePanelVisible) {
                    // 起播 3s 后顶底两条一起收起；暂停/缓冲/切台时由 showControls 重新显示
                    hideControls()
                }
            }, CONTROLS_HIDE_DELAY_MS)
        }
    }

    private fun initializePlayer() {
        if (isFinishing || isDestroyed) return
        if (channelUrls.isEmpty()) {
            playbackInfoText.text = "无可用频道"
            return
        }

        resolveCurrentChannelName()?.let { channelNameText.text = it }
        val name = channelNameText.text
        android.util.Log.d("PlaybackActivity", "initializePlayer: channel=$name, index=$currentIndex")
        playbackInfoText.text = "正在测速..."
        setLoadingVisible(true)

        val httpDataSourceFactory = OkHttpDataSource.Factory(tlsCompatibleClient)
        byteTrackingFactory = ByteTrackingDataSourceFactory(httpDataSourceFactory)

        // 加大缓冲，多缓冲一些再开始播（保持保守值，避免短窗口直播永远缓冲不满而不启播）
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 15000,
                /* maxBufferMs = */ 50000,
                /* bufferForPlaybackMs = */ 5000,
                /* bufferForPlaybackAfterRebufferMs = */ 8000
            )
            .build()

        player = ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(DefaultMediaSourceFactory(byteTrackingFactory))
            .build().also { exoPlayer ->
                playerView.player = exoPlayer
                // 不在这里设置 playWhenReady，等 playSource 统一控制

                exoPlayer.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        if (isFinishing || isDestroyed) return
                        when (state) {
                            Player.STATE_BUFFERING -> {
                                setLoadingVisible(true)
                                networkSpeedText.visibility = View.VISIBLE
                                updateSourceInfo("缓冲中")
                                resetSpeedTracking()
                                startSpeedTracking()
                                bufferingStartTime = System.currentTimeMillis()
                                handler.removeCallbacks(bufferingTimeoutRunnable)
                                handler.postDelayed(bufferingTimeoutRunnable, BUFFERING_TIMEOUT_MS)
                            }
                            Player.STATE_READY -> {
                                setLoadingVisible(false)
                                networkSpeedText.visibility = View.GONE
                                updateSourceInfo("播放中")
                                stopSpeedTracking()
                                handler.removeCallbacks(bufferingTimeoutRunnable)
                                bufferingStartTime = 0L
                                isBufferingTimedOut = false
                                retryCount = 0
                            }
                            Player.STATE_ENDED -> {
                                playbackInfoText.text = "播放结束"
                                showControls()
                            }
                            Player.STATE_IDLE -> {
                                if (isSwitchingStream) return
                                setLoadingVisible(false)
                                networkSpeedText.visibility = View.GONE
                                playbackInfoText.text = "信号不可用"
                            }
                        }
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        if (isFinishing || isDestroyed) return
                        if (isPlaying) {
                            updateSourceInfo("播放中")
                            scheduleHideControls()
                        } else {
                            updateSourceInfo("已暂停")
                            showControls()
                        }
                    }

                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        if (isFinishing || isDestroyed) return
                        setLoadingVisible(false)
                        handler.removeCallbacks(bufferingTimeoutRunnable)

                        if (retryCount < MAX_RETRIES && !isRetrying) {
                            isRetrying = true
                            retryCount++
                            playbackInfoText.text = "信号不稳定，重试 ($retryCount/$MAX_RETRIES)..."
                            handler.postDelayed({
                                if (!isFinishing && !isDestroyed) {
                                    isRetrying = false
                                    retryPlay()
                                }
                            }, 2000)
                        } else {
                            isRetrying = false
                            // 重试次数用尽，自动切换到下一个最优信号源
                            switchToNextBestSource("信号不稳定")
                        }
                    }
                })
            }

        if (currentMergedChannel != null) {
            playMergedChannel()
        }
    }

    private fun buildMediaSource(url: String): MediaSource {
        val isHls = url.contains(".m3u8") ||
                   url.contains("/stream/") ||
                   url.contains("/hls/") ||
                   url.contains("/live/") ||
                   !url.contains(".") ||
                   (url.startsWith("http") && !url.contains(".mpd") && !url.contains(".mp4") && !url.contains(".ts"))

        // 不对直播点强加 min/max offset：很多短窗口直播会导致目标偏移超出窗口而永不启播
        return if (isHls) {
            HlsMediaSource.Factory(byteTrackingFactory)
                .createMediaSource(MediaItem.fromUri(url))
        } else {
            DefaultMediaSourceFactory(byteTrackingFactory)
                .createMediaSource(MediaItem.fromUri(url))
        }
    }

    private fun releasePlayer() {
        player?.let { exoPlayer ->
            playbackPosition = exoPlayer.currentPosition
            playWhenReady = exoPlayer.playWhenReady
            exoPlayer.release()
        }
        player = null
    }

    private fun retryPlay() {
        if (isFinishing || isDestroyed) return
        if (channelUrls.isEmpty()) return
        if (player == null) return

        playbackInfoText.text = "正在重试..."

        val url = if (currentMergedChannel != null && currentSource != null) {
            currentSource!!.url
        } else {
            channelUrls.getOrNull(currentIndex) ?: return
        }

        switchStream(url)
    }

    // ==================== 后台测速与信号源健康监控 ====================

    private fun markManualSwitch() {
        lastManualSwitchTime = System.currentTimeMillis()
    }

    private fun startSourceMonitor() {
        handler.removeCallbacks(sourceMonitorRunnable)
        handler.postDelayed(sourceMonitorRunnable, SOURCE_RETEST_INTERVAL_MS)
    }

    private fun stopSourceMonitor() {
        handler.removeCallbacks(sourceMonitorRunnable)
    }

    private fun scheduleSourceMonitorTick() {
        if (isFinishing || isDestroyed) return
        val p = player
        // 播放不健康（卡顿/缓冲超时）时，后台复测其他源并自动切到最优
        if (p != null && !isSourceSpeedTesting && currentMergedChannel != null &&
            (p.playbackState == Player.STATE_BUFFERING ||
             (p.playbackState == Player.STATE_READY && !p.isPlaying))) {
            retestSourcesAndAutoSwitch { startSourceMonitor() }
        } else {
            startSourceMonitor()
        }
    }

    // 后台并行测速当前频道的所有信号源，发现明显更快的源时自动切换；
    // 用户手动选源后的尊重窗口内、或当前播放顺畅时不打断
    private fun retestSourcesAndAutoSwitch(onDone: (() -> Unit)? = null) {
        val mergedChannel = currentMergedChannel ?: run { onDone?.invoke(); return }
        if (isSourceSpeedTesting || mergedChannel.sources.size < 2) { onDone?.invoke(); return }
        isSourceSpeedTesting = true

        getScope().launch {
            val results = SpeedTester.speedTestSources(mergedChannel.sources, timeoutMs = 3000)
            if (!isFinishing && !isDestroyed) {
                val speedMap = results.filter { it.second.success }
                    .associate { it.first.sourceId to it.second.latencyMs }
                val updated = mergedChannel.copy(sources = mergedChannel.sources.map { source ->
                    val latency = speedMap[source.sourceId]
                    if (latency != null) source.copy(speed = latency, lastTested = System.currentTimeMillis(), isAvailable = true)
                    else if (!results.any { it.first.sourceId == source.sourceId && it.second.success }) source.copy(isAvailable = false)
                    else source
                })
                currentMergedChannel = updated
                ChannelCacheManager.updateMergedChannelInCache(updated)
                updateSourcePanel()

                val manualRecently = System.currentTimeMillis() - lastManualSwitchTime < MANUAL_SWITCH_RESPECT_MS
                val healthy = player?.isPlaying == true
                if (!manualRecently || !healthy) {
                    val best = SourceSelector.selectSource(updated, SelectionMode.FASTEST) ?: SourceSelector.selectSource(updated, SelectionMode.PREFERRED)
                    val currentPos = findSourceByUrl(updated.sources, currentSource?.url)
                    if (best != null && currentPos >= 0 && best.sourceId != updated.sources[currentPos].sourceId) {
                        android.util.Log.d("PlaybackActivity", "autoSwitch: ${currentSource?.sourceName}(${currentSource?.speed}ms) -> ${best.sourceName}(${best.speed}ms)")
                        attemptedSources.clear()
                        currentSource = best
                        selectedSourceIndex = currentPos
                        ChannelCacheManager.setLastPlayedSource(updated.id, best.sourceId)
                        resetRetryState()
                        playSource(best)
                    }
                }
            }
            isSourceSpeedTesting = false
            onDone?.invoke()
        }
    }

    // 切换到下一个最优的未试用信号源；全部失败则重置后重试最优源
    private fun switchToNextBestSource(reason: String) {
        if (isFinishing || isDestroyed) return
        val mergedChannel = currentMergedChannel ?: run {
            playbackInfoText.text = "$reason，无其他信号源"
            showControls()
            return
        }
        val next = SourceSelector.selectNextBest(mergedChannel, attemptedSources)
        if (next != null) {
            android.util.Log.d("PlaybackActivity", "switchToNextBestSource: $reason -> ${next.sourceName}(speed=${next.speed})")
            attemptedSources.add(next.sourceId)
            currentSource = next
            selectedSourceIndex = findSourceByUrl(mergedChannel.sources, next.url).coerceAtLeast(0)
            markManualSwitch()
            ChannelCacheManager.setLastPlayedSource(mergedChannel.id, next.sourceId)
            resetRetryState()
            updateSourcePanel()
            playSource(next)
        } else {
            attemptedSources.clear()
            if (retryCount < MAX_RETRIES) {
                retryCount++
                playbackInfoText.text = "$reason，所有信号源重试 ($retryCount/$MAX_RETRIES)..."
                handler.postDelayed({
                    if (!isFinishing && !isDestroyed) retryPlay()
                }, 2000)
            } else {
                playbackInfoText.text = "$reason，所有信号源均不可用"
                showControls()
            }
        }
    }

    private fun showSourcePanelAndHighlight() {
        if (!isSourcePanelVisible) toggleSourcePanel()
    }

    private fun resetSpeedTracking() {
        totalBytesRead = 0L
        lastBytesRead = 0L
        lastSpeedUpdateTime = System.currentTimeMillis()
        currentSpeedBps = 0L
    }

    private fun startSpeedTracking() {
        speedHandler.removeCallbacks(updateSpeedRunnable)
        speedHandler.post(updateSpeedRunnable)
    }

    private fun stopSpeedTracking() {
        speedHandler.removeCallbacks(updateSpeedRunnable)
    }

    private fun updateNetworkSpeedDisplay() {
        if (isFinishing || isDestroyed) return
        val currentTime = System.currentTimeMillis()
        val timeDelta = currentTime - lastSpeedUpdateTime
        if (timeDelta >= speedUpdateInterval) {
            val bytesDelta = totalBytesRead - lastBytesRead
            currentSpeedBps = if (timeDelta > 0) (bytesDelta * 1000 / timeDelta) else 0L
            val speedText = formatSpeed(currentSpeedBps)
            networkSpeedText.text = speedText
            lastBytesRead = totalBytesRead
            lastSpeedUpdateTime = currentTime
            speedHandler.postDelayed(updateSpeedRunnable, speedUpdateInterval)
        } else {
            speedHandler.postDelayed(updateSpeedRunnable, speedUpdateInterval - timeDelta)
        }
    }

    private fun formatSpeed(bytesPerSecond: Long): String {
        return when {
            bytesPerSecond >= 1024 * 1024 -> String.format("%.1f MB/s", bytesPerSecond / (1024.0 * 1024.0))
            bytesPerSecond >= 1024 -> String.format("%.1f KB/s", bytesPerSecond / 1024.0)
            bytesPerSecond > 0 -> String.format("%d B/s", bytesPerSecond)
            else -> "..."
        }
    }

    private fun hideSystemUi() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_FULLSCREEN
        )
    }
}
