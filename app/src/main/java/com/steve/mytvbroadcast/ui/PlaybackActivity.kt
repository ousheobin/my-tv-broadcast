package com.steve.mytvbroadcast.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.ui.PlayerView
import com.steve.mytvbroadcast.R
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

        // TLS 1.2 compatible client for servers with certificate chain issues
        private val tlsCompatibleClient: OkHttpClient by lazy {
            try {
                // Disable revocation checking system-wide for this client
                // This bypasses stale OCSP responses that cause "Response is unreliable" errors
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

    private lateinit var channelUrls: List<String>
    private lateinit var channelNames: List<String>
    private var currentIndex = 0

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var isControlsVisible = true
    private val hideControlsRunnable = Runnable { hideControls() }

    private var retryCount = 0
    private val maxRetries = 3
    private var isRetrying = false

    // Network speed tracking
    private var totalBytesRead = 0L
    private var lastBytesRead = 0L
    private var lastSpeedUpdateTime = 0L
    private var currentSpeedBps = 0L
    private val speedUpdateInterval = 1000L // Update every 1 second
    private val speedHandler = Handler(Looper.getMainLooper())
    private val updateSpeedRunnable = Runnable { updateNetworkSpeedDisplay() }
    private var dataSource: DataSource? = null
    // Wrapper to track bytes transferred
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

    // Factory that creates ByteTrackingDataSource
    private inner class ByteTrackingDataSourceFactory(private val upstreamFactory: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource {
            return ByteTrackingDataSource(upstreamFactory.createDataSource())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_playback)

        // Keep screen on / prevent TV from sleeping
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        playerView = findViewById(R.id.player_view)
        channelNameText = findViewById(R.id.channel_name)
        playbackInfoText = findViewById(R.id.playback_info)
        loadingIndicator = findViewById(R.id.loading_indicator)
        networkSpeedText = findViewById(R.id.network_speed)
        channelIndexText = findViewById(R.id.channel_index)
        topOverlay = findViewById(R.id.top_overlay)
        bottomOverlay = findViewById(R.id.bottom_overlay)

        // Get channel list from intent
        channelUrls = intent.getStringArrayListExtra(EXTRA_CHANNEL_URLS) ?: listOf()
        channelNames = intent.getStringArrayListExtra(EXTRA_CHANNEL_NAMES) ?: listOf()
        currentIndex = intent.getIntExtra(EXTRA_CHANNEL_INDEX, 0)

        // If no channel list, use single channel mode
        if (channelUrls.isEmpty()) {
            val singleUrl = intent.getStringExtra(EXTRA_CHANNEL_URL)
            if (singleUrl != null) {
                channelUrls = listOf(singleUrl)
                channelNames = listOf(intent.getStringExtra(EXTRA_CHANNEL_NAME) ?: "Unknown")
            }
        }

        // Update UI with channel info
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
        releasePlayer()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        speedHandler.removeCallbacks(updateSpeedRunnable)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                playPreviousChannel()
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                playNextChannel()
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                player?.let {
                    if (it.isPlaying) it.pause() else it.play()
                }
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun updateChannelInfo() {
        if (channelNames.isNotEmpty() && currentIndex < channelNames.size) {
            channelNameText.text = channelNames[currentIndex]
        }
        channelIndexText.text = "${currentIndex + 1} / ${channelUrls.size}"
    }

    private fun playPreviousChannel() {
        if (channelUrls.isEmpty()) return
        currentIndex = if (currentIndex <= 0) channelUrls.size - 1 else currentIndex - 1
        updateChannelInfo()
        switchToChannel(currentIndex)
    }

    private fun playNextChannel() {
        if (channelUrls.isEmpty()) return
        currentIndex = if (currentIndex >= channelUrls.size - 1) 0 else currentIndex + 1
        updateChannelInfo()
        switchToChannel(currentIndex)
    }

    private fun switchToChannel(index: Int) {
        if (index < 0 || index >= channelUrls.size) return
        currentIndex = index
        retryCount = 0
        val name = if (index < channelNames.size) channelNames[index] else "Unknown"
        channelNameText.text = name
        channelIndexText.text = "${currentIndex + 1} / ${channelUrls.size}"
        playbackInfoText.text = "正在切换..."
        showControls()
        scheduleHideControls()

        // Resolve redirect and create media source
        val httpDataSourceFactory = OkHttpDataSource.Factory(tlsCompatibleClient)
        val byteTrackingFactory = ByteTrackingDataSourceFactory(httpDataSourceFactory)

        scope.launch {
            val resolvedUrl = resolveRedirect(channelUrls[index])
            player?.let { exoPlayer ->
                val mediaSource = createMediaSource(resolvedUrl, byteTrackingFactory)
                exoPlayer.setMediaSource(mediaSource)
                exoPlayer.playWhenReady = true
                exoPlayer.prepare()
            }
        }
    }

    private suspend fun resolveRedirect(url: String): String = withContext(Dispatchers.IO) {
        try {
            println("PlaybackActivity: Resolving redirect for: $url")
            val client = tlsCompatibleClient.newBuilder().build()

            var currentUrl = url
            var redirectCount = 0
            val maxRedirects = 10

            while (redirectCount < maxRedirects) {
                println("PlaybackActivity: Requesting: $currentUrl")
                val request = okhttp3.Request.Builder().url(currentUrl).build()
                val response = client.newCall(request).execute()

                val newUrl = response.request.url.toString()
                println("PlaybackActivity: Response code: ${response.code}, URL: $newUrl")

                if (newUrl == currentUrl) {
                    // No more redirects
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
            url
        }
    }

    private fun showControls() {
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
        // Only schedule if not finishing
        if (!isFinishing && !isDestroyed) {
            handler.postDelayed({
                if (!isFinishing && !isDestroyed && player?.isPlaying == true) {
                    // Only hide channel name overlay when actually playing
                    topOverlay.visibility = View.GONE
                }
            }, 2000)
        }
    }

    private fun initializePlayer() {
        if (channelUrls.isEmpty()) {
            playbackInfoText.text = "无可用频道"
            return
        }

        val url = channelUrls[currentIndex]
        val name = if (currentIndex < channelNames.size) channelNames[currentIndex] else "Unknown"
        channelNameText.text = name
        playbackInfoText.text = "正在加载..."

        // Create HTTP data source that follows redirects and tracks bytes
        val httpDataSourceFactory = OkHttpDataSource.Factory(tlsCompatibleClient)

        val byteTrackingFactory = ByteTrackingDataSourceFactory(httpDataSourceFactory)

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(byteTrackingFactory))
            .build().also { exoPlayer ->
                playerView.player = exoPlayer
                exoPlayer.playWhenReady = true

                // First resolve redirect, then play
                scope.launch {
                    val resolvedUrl = resolveRedirect(url)
                    // Create media source based on URL type
                    val mediaSource = createMediaSource(resolvedUrl, httpDataSourceFactory)
                    exoPlayer.setMediaSource(mediaSource)
                    exoPlayer.seekTo(playbackPosition)
                    exoPlayer.prepare()
                }

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
                            }
                            Player.STATE_READY -> {
                                loadingIndicator.visibility = View.GONE
                                networkSpeedText.visibility = View.GONE
                                playbackInfoText.text = "播放中"
                                stopSpeedTracking()
                                // Keep channel name visible, only hide bottom controls
                                hideBottomControls()
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
                        if (retryCount < maxRetries && !isRetrying) {
                            isRetrying = true
                            retryCount++
                            playbackInfoText.text = "信号不稳定，正在重试 ($retryCount/$maxRetries)..."
                            handler.postDelayed({
                                if (!isFinishing && !isDestroyed) {
                                    isRetrying = false
                                    retryPlay()
                                }
                            }, 2000)
                        } else {
                            playbackInfoText.text = "Sorry，当前信号不稳定，稍后再试噢"
                            showControls()
                            retryCount = 0
                        }
                    }
                })
            }
    }

    private fun createMediaSource(url: String, dataSourceFactory: DataSource.Factory): MediaSource {
        println("PlaybackActivity: Creating media source for: $url")

        // Check if this is an HLS stream
        // HLS indicators: URL contains .m3u8, or known streaming paths, or simply HTTP stream (most TV streams are HLS)
        val isHls = url.contains(".m3u8") ||
                   url.contains("/stream/") ||
                   url.contains("/hls/") ||
                   url.contains("/live/") ||
                   !url.contains(".") ||
                   // Default to HLS for HTTP streams that don't have clear non-HLS extensions
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

        val url = channelUrls[currentIndex]
        val httpDataSourceFactory = OkHttpDataSource.Factory(tlsCompatibleClient)
        val byteTrackingFactory = ByteTrackingDataSourceFactory(httpDataSourceFactory)

        scope.launch {
            val resolvedUrl = resolveRedirect(url)
            if (!isFinishing && !isDestroyed) {
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
        val currentTime = System.currentTimeMillis()
        val timeDelta = currentTime - lastSpeedUpdateTime
        if (timeDelta >= speedUpdateInterval) {
            val bytesDelta = totalBytesRead - lastBytesRead
            currentSpeedBps = if (timeDelta > 0) (bytesDelta * 1000 / timeDelta) else 0L
            val speedText = formatSpeed(currentSpeedBps)
            networkSpeedText.text = speedText
            lastBytesRead = totalBytesRead
            lastSpeedUpdateTime = currentTime
            // Schedule next update
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
