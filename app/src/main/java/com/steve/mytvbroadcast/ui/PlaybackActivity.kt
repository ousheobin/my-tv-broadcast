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
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.steve.mytvbroadcast.R
import com.steve.mytvbroadcast.data.ChannelCacheManager
import com.steve.mytvbroadcast.data.ChannelSource
import com.steve.mytvbroadcast.data.MergedChannel
import com.steve.mytvbroadcast.data.SelectionMode
import com.steve.mytvbroadcast.data.SpeedTester
import com.steve.mytvbroadcast.data.SourceSelector
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
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
        private const val BUFFERING_TIMEOUT_MS = 10000L

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
    private lateinit var networkSpeedText: TextView
    private lateinit var channelIndexText: TextView
    private lateinit var topOverlay: LinearLayout
    private lateinit var bottomOverlay: LinearLayout
    private lateinit var sourcePanel: LinearLayout
    private lateinit var sourceSwitchList: RecyclerView

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

    private inner class ByteTrackingDataSource(private val upstream: DataSource) : DataSource by upstream {
        @Volatile
        var totalBytes: Long = 0L

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val bytesRead = upstream.read(buffer, offset, length)
            if (bytesRead > 0) {
                totalBytes += bytesRead
                totalBytesRead = totalBytes
            }
            return bytesRead
        }
    }

    private inner class ByteTrackingDataSourceFactory(private val upstreamFactory: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource {
            return ByteTrackingDataSource(upstreamFactory.createDataSource())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_playback)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        playerView = findViewById(R.id.player_view)
        channelNameText = findViewById(R.id.channel_name)
        playbackInfoText = findViewById(R.id.playback_info)
        loadingIndicator = findViewById(R.id.loading_indicator)
        networkSpeedText = findViewById(R.id.network_speed)
        channelIndexText = findViewById(R.id.channel_index)
        topOverlay = findViewById(R.id.top_overlay)
        bottomOverlay = findViewById(R.id.bottom_overlay)
        sourcePanel = findViewById(R.id.source_panel)
        sourceSwitchList = findViewById(R.id.source_switch_list)

        setupSourceSwitchList()

        val mergedChannelJson = intent.getStringExtra(EXTRA_MERGED_CHANNEL)
        if (mergedChannelJson != null) {
            parseMergedChannel(mergedChannelJson)
        } else {
            setupLegacyChannelList()
        }

        updateChannelInfo()

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
        if (channelNames.isNotEmpty() && currentIndex < channelNames.size) {
            channelNameText.text = channelNames[currentIndex]
        }
        channelIndexText.text = "${currentIndex + 1} / ${allChannelUrls.size}"
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
            } else {
                playbackInfoText.text = "无可用信号源"
            }
        } else {
            // 没有测速数据，进行测速（只测一次）
            playbackInfoText.text = "正在测速..."
            loadingIndicator.visibility = View.VISIBLE

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
        playbackInfoText.text = "正在切换到 ${source.sourceName}..."
        showControls()
        resetRetryState()

        val httpDataSourceFactory = OkHttpDataSource.Factory(tlsCompatibleClient)
        val byteTrackingFactory = ByteTrackingDataSourceFactory(httpDataSourceFactory)

        getScope().launch {
            val resolvedUrl = resolveRedirectWithFallback(source.url)
            if (isFinishing || isDestroyed) return@launch

            val currentPlayer = player
            if (resolvedUrl == null) {
                handleFetchFailure()
            } else if (currentPlayer != null) {
                val mediaSource = createMediaSource(resolvedUrl, byteTrackingFactory)
                currentPlayer.setMediaSource(mediaSource)
                currentPlayer.playWhenReady = true
                currentPlayer.prepare()
            }
        }
    }

    private suspend fun resolveRedirectWithFallback(url: String): String? = withContext(Dispatchers.IO) {
        try {
            println("PlaybackActivity: Resolving redirect for: $url")
            val client = tlsCompatibleClient.newBuilder().build()

            var currentUrl = url
            var redirectCount = 0
            val maxRedirects = 10

            while (redirectCount < maxRedirects) {
                if (isFinishing || isDestroyed) return@withContext null
                println("PlaybackActivity: Requesting: $currentUrl")
                val request = okhttp3.Request.Builder().url(currentUrl).build()
                val response = client.newCall(request).execute()

                val newUrl = response.request.url.toString()
                println("PlaybackActivity: Response code: ${response.code}, URL: $newUrl")

                if (newUrl == currentUrl) {
                    println("PlaybackActivity: Final URL (no more redirects): $newUrl")
                    response.close()
                    return@withContext newUrl
                }

                currentUrl = newUrl
                redirectCount++
                response.close()
            }

            println("PlaybackActivity: Max redirects reached: $currentUrl")
            currentUrl
        } catch (e: Exception) {
            println("PlaybackActivity: Error resolving redirect: ${e.message}")
            e.printStackTrace()
            null
        }
    }

    private fun handleFetchFailure() {
        if (isFinishing || isDestroyed) return
        val src = currentSource ?: return

        android.util.Log.d("PlaybackActivity", "handleFetchFailure: current=${src.sourceId}")
        loadingIndicator.visibility = View.GONE
        playbackInfoText.text = "${src.sourceName} 无法访问"
        showControls()
    }

    private fun switchToSource(source: ChannelSource, hidePanel: Boolean = true) {
        if (isFinishing || isDestroyed) return
        if (currentMergedChannel == null) return

        android.util.Log.d("PlaybackActivity", "switchToSource: ENTRY switching to ${source.sourceId}, url=${source.url}, hidePanel=$hidePanel, currentSource currently=${currentSource?.sourceId}")
        attemptedSources.add(source.sourceId)
        currentSource = source
        ChannelCacheManager.setLastPlayedSource(currentMergedChannel!!.id, source.sourceId)
        if (hidePanel) hideSourcePanel()
        resetRetryState()
        playSource(source)
    }

    private fun switchToPreviousSource() {
        if (isFinishing || isDestroyed) return
        val mergedChannel = currentMergedChannel ?: return
        val current = currentSource ?: return

        val sources = mergedChannel.sources
        val currentIndex = sources.indexOfFirst { it.sourceId == current.sourceId }
        if (currentIndex <= 0) {
            // 已到第一个，切换到最后一个
            val prevSource = sources.lastOrNull() ?: return
            switchToSource(prevSource)
        } else {
            val prevSource = sources[currentIndex - 1]
            switchToSource(prevSource)
        }
    }

    private fun switchToNextSource() {
        if (isFinishing || isDestroyed) return
        val mergedChannel = currentMergedChannel ?: return
        val current = currentSource ?: return

        val sources = mergedChannel.sources
        val currentIndex = sources.indexOfFirst { it.sourceId == current.sourceId }
        if (currentIndex < 0 || currentIndex >= sources.size - 1) {
            // 已到最后，切换到第一个
            val nextSource = sources.firstOrNull() ?: return
            switchToSource(nextSource)
        } else {
            val nextSource = sources[currentIndex + 1]
            switchToSource(nextSource)
        }
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
        playbackInfoText.text = "加载超时，正在重试..."
        handlePlaybackStall()
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
            playbackInfoText.text = "播放卡顿"
            showControls()
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
                switchToPreviousSource()
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
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
            // 通过 URL 匹配找到当前播放的信号源（sourceId 可能因缓存刷新而变化）
            val currentUrl = currentSource?.url
            android.util.Log.d("PlaybackActivity", "toggleSourcePanel OPEN: currentSource=${currentSource?.sourceId}, currentUrl=$currentUrl, mergedChannel=${mergedChannel.name}, mergedChannel.sources.size=${mergedChannel.sources.size}")
            selectedSourceIndex = mergedChannel.sources.indexOfFirst { it.url == currentUrl }
            android.util.Log.d("PlaybackActivity", "toggleSourcePanel: matched index=$selectedSourceIndex")
            if (selectedSourceIndex < 0) {
                android.util.Log.d("PlaybackActivity", "toggleSourcePanel: no match found, default to 0")
                selectedSourceIndex = 0
            }
            sourceSwitchAdapter.setCurrentSource(mergedChannel.sources[selectedSourceIndex].sourceId)
            // 请求焦点到信号源列表
            sourceSwitchList.requestFocus()
            hideControls()
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

        android.util.Log.d("PlaybackActivity", "moveSourceSelection: selectedSourceIndex=$selectedSourceIndex, currentSource=${currentSource?.sourceId}")
        val selectedSource = sources[selectedSourceIndex]
        android.util.Log.d("PlaybackActivity", "moveSourceSelection: selectedSource=${selectedSource.sourceId}, isAvailable=${selectedSource.isAvailable}")

        // 如果选中的源不可用，则自动选最快的可用源
        val sourceToSet = if (selectedSource.isAvailable) {
            selectedSource
        } else {
            android.util.Log.d("PlaybackActivity", "moveSourceSelection: selected source not available, auto-selecting fastest")
            SourceSelector.selectSource(mergedChannel, SelectionMode.FASTEST) ?: selectedSource
        }

        currentSource = sourceToSet
        sourceSwitchAdapter.setCurrentSource(sourceToSet.sourceId)

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

        // 根据 allChannelUrls[index] 找到对应的 MergedChannel
        if (allMergedChannels.isNotEmpty()) {
            android.util.Log.d("PlaybackActivity", "switchToChannel: looking for url in ${allMergedChannels.size} channels")
            currentMergedChannel = allMergedChannels.find { mc ->
                mc.sources.any { it.url == currentUrl }
            }
            // 保存最后播放的频道
            saveLastPlayedChannel()
            // 更新 channelUrls 为新频道的信号源
            channelUrls = currentMergedChannel?.sources?.map { it.url } ?: listOf()
            android.util.Log.d("PlaybackActivity", "switchToChannel: found mergedChannel=${currentMergedChannel?.name}(id=${currentMergedChannel?.id}), channelUrls.size=${channelUrls.size}")
            // 切换频道时重置 currentSource，避免旧频道的数据影响新频道
            currentSource = null
            attemptedSources.clear()
            playMergedChannel()
        } else if (currentMergedChannel != null) {
            android.util.Log.d("PlaybackActivity", "switchToChannel: allMergedChannels is empty, using currentMergedChannel")
            playMergedChannel()
        } else {
            val name = if (index < channelNames.size) channelNames[index] else "Unknown"
            channelNameText.text = name
            channelIndexText.text = "${currentIndex + 1} / ${allChannelUrls.size}"
            playbackInfoText.text = "正在切换..."
            showControls()
            scheduleHideControls()

            val httpDataSourceFactory = OkHttpDataSource.Factory(tlsCompatibleClient)
            val byteTrackingFactory = ByteTrackingDataSourceFactory(httpDataSourceFactory)

            getScope().launch {
                val resolvedUrl = resolveRedirectWithFallback(allChannelUrls[index])
                if (isFinishing || isDestroyed) return@launch

                if (resolvedUrl == null) {
                    handleFetchFailure()
                } else {
                    player?.let { exoPlayer ->
                        val mediaSource = createMediaSource(resolvedUrl, byteTrackingFactory)
                        exoPlayer.setMediaSource(mediaSource)
                        exoPlayer.playWhenReady = true
                        exoPlayer.prepare()
                    }
                }
            }
        }
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

    private fun scheduleHideControls() {
        handler.removeCallbacks(hideControlsRunnable)
        if (!isFinishing && !isDestroyed) {
            handler.postDelayed({
                if (!isFinishing && !isDestroyed && player?.isPlaying == true && !isSourcePanelVisible) {
                    topOverlay.visibility = View.GONE
                }
            }, 2000)
        }
    }

    private fun initializePlayer() {
        if (isFinishing || isDestroyed) return
        if (channelUrls.isEmpty()) {
            playbackInfoText.text = "无可用频道"
            return
        }

        val name = if (currentIndex < channelNames.size) channelNames[currentIndex] else "Unknown"
        channelNameText.text = name
        android.util.Log.d("PlaybackActivity", "initializePlayer: channel=$name, index=$currentIndex")
        playbackInfoText.text = "正在测速..."
        loadingIndicator.visibility = View.VISIBLE

        val httpDataSourceFactory = OkHttpDataSource.Factory(tlsCompatibleClient)
        val byteTrackingFactory = ByteTrackingDataSourceFactory(httpDataSourceFactory)

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(byteTrackingFactory))
            .build().also { exoPlayer ->
                playerView.player = exoPlayer
                // 不在这里设置 playWhenReady，等 playSource 统一控制

                exoPlayer.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        if (isFinishing || isDestroyed) return
                        when (state) {
                            Player.STATE_BUFFERING -> {
                                loadingIndicator.visibility = View.VISIBLE
                                networkSpeedText.visibility = View.VISIBLE
                                playbackInfoText.text = "缓冲中..."
                                resetSpeedTracking()
                                startSpeedTracking()
                                bufferingStartTime = System.currentTimeMillis()
                                handler.removeCallbacks(bufferingTimeoutRunnable)
                                handler.postDelayed(bufferingTimeoutRunnable, BUFFERING_TIMEOUT_MS)
                            }
                            Player.STATE_READY -> {
                                loadingIndicator.visibility = View.GONE
                                networkSpeedText.visibility = View.GONE
                                playbackInfoText.text = "播放中"
                                stopSpeedTracking()
                                hideBottomControls()
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
                                loadingIndicator.visibility = View.GONE
                                networkSpeedText.visibility = View.GONE
                                playbackInfoText.text = "信号不可用"
                            }
                        }
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        if (isFinishing || isDestroyed) return
                        if (isPlaying) {
                            playbackInfoText.text = "播放中"
                            scheduleHideControls()
                        } else {
                            playbackInfoText.text = "已暂停"
                            showControls()
                        }
                    }

                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        if (isFinishing || isDestroyed) return
                        loadingIndicator.visibility = View.GONE
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
                            playbackInfoText.text = "信号不稳定"
                            showControls()
                        }
                    }
                })
            }

        if (currentMergedChannel != null) {
            playMergedChannel()
        }
    }

    private fun createMediaSource(url: String, dataSourceFactory: DataSource.Factory): MediaSource {
        println("PlaybackActivity: Creating media source for: $url")

        val isHls = url.contains(".m3u8") ||
                   url.contains("/stream/") ||
                   url.contains("/hls/") ||
                   url.contains("/live/") ||
                   !url.contains(".") ||
                   (url.startsWith("http") && !url.contains(".mpd") && !url.contains(".mp4") && !url.contains(".ts"))

        println("PlaybackActivity: isHls=$isHls, url=$url")

        return if (isHls) {
            println("PlaybackActivity: Using HLS media source")
            HlsMediaSource.Factory(dataSourceFactory)
                .createMediaSource(MediaItem.fromUri(url))
        } else {
            println("PlaybackActivity: Using default media source")
            DefaultMediaSourceFactory(dataSourceFactory)
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
        val currentPlayer = player ?: return

        playbackInfoText.text = "正在重试..."
        loadingIndicator.visibility = View.VISIBLE

        val url = if (currentMergedChannel != null && currentSource != null) {
            currentSource!!.url
        } else {
            channelUrls[currentIndex]
        }

        val httpDataSourceFactory = OkHttpDataSource.Factory(tlsCompatibleClient)
        val byteTrackingFactory = ByteTrackingDataSourceFactory(httpDataSourceFactory)

        getScope().launch {
            val resolvedUrl = resolveRedirectWithFallback(url)
            if (isFinishing || isDestroyed) return@launch

            if (resolvedUrl == null) {
                handleFetchFailure()
            } else {
                currentPlayer.let { exoPlayer ->
                    val mediaSource = createMediaSource(resolvedUrl, byteTrackingFactory)
                    exoPlayer.setMediaSource(mediaSource)
                    exoPlayer.playWhenReady = true
                    exoPlayer.prepare()
                }
            }
        }
    }

    private fun hideBottomControls() {
        bottomOverlay.visibility = View.GONE
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
