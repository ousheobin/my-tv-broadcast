package com.steve.mytvbroadcast.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.media3.common.util.UnstableApi
import com.steve.mytvbroadcast.util.MediaHttp

/**
 * 首页 hero 的轻量预览播放器：静音、保守缓冲、只试当前源。
 * 出首帧前或加载失败时通过 [Listener] 通知切回氛围底，不做多源轮切以免抢带宽。
 */
@UnstableApi
class ChannelPreviewPlayer(private val context: Context) {

    interface Listener {
        /** 视频画面是否应显示；false 表示回退到氛围底 */
        fun onPreviewVisible(visible: Boolean)
    }

    var listener: Listener? = null

    private var player: ExoPlayer? = null
    private var playerView: PlayerView? = null
    private var dataSourceFactory: DataSource.Factory? = null
    private val handler = Handler(Looper.getMainLooper())
    private var currentUrl: String? = null
    private var ready = false

    private val watchdog = Runnable { if (!ready) listener?.onPreviewVisible(false) }

    fun attach(view: PlayerView) {
        playerView = view
        view.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        // 预览额外放大，把信号帧自带的黑边推出画面
        view.scaleX = OVERSCAN
        view.scaleY = OVERSCAN
    }

    fun play(url: String?) {
        handler.removeCallbacks(watchdog)
        if (url.isNullOrEmpty()) {
            stop()
            return
        }
        if (url == currentUrl && ready) return
        val exo = ensurePlayer() ?: run { listener?.onPreviewVisible(false); return }
        ready = false
        currentUrl = url
        listener?.onPreviewVisible(false)
        exo.stop()
        exo.setMediaSource(buildMediaSource(url))
        exo.prepare()
        exo.playWhenReady = true
        handler.postDelayed(watchdog, PREVIEW_TIMEOUT_MS)
    }

    fun stop() {
        handler.removeCallbacks(watchdog)
        currentUrl = null
        ready = false
        player?.stop()
        listener?.onPreviewVisible(false)
    }

    fun release() {
        handler.removeCallbacks(watchdog)
        player?.release()
        player = null
        playerView?.player = null
        currentUrl = null
        ready = false
    }

    private fun ensurePlayer(): ExoPlayer? {
        player?.let { return it }
        val view = playerView ?: return null
        val client = MediaHttp.tlsCompatibleClient
        val dsFactory = OkHttpDataSource.Factory(client)
        dataSourceFactory = dsFactory

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 8000,
                /* maxBufferMs = */ 20000,
                /* bufferForPlaybackMs = */ 1500,
                /* bufferForPlaybackAfterRebufferMs = */ 3000
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        return ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dsFactory))
            .build().also { exo ->
                exo.volume = 0f
                exo.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        when (state) {
                            Player.STATE_READY -> {
                                handler.removeCallbacks(watchdog)
                                ready = true
                                listener?.onPreviewVisible(true)
                            }
                            Player.STATE_IDLE -> {
                                if (currentUrl != null) listener?.onPreviewVisible(false)
                            }
                            else -> {}
                        }
                    }

                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        ready = false
                        listener?.onPreviewVisible(false)
                    }
                })
                view.player = exo
                player = exo
            }
    }

    private fun buildMediaSource(url: String): MediaSource {
        val factory = dataSourceFactory ?: OkHttpDataSource.Factory(MediaHttp.tlsCompatibleClient)
        val isHls = url.contains(".m3u8") ||
            url.contains("/stream/") ||
            url.contains("/hls/") ||
            url.contains("/live/") ||
            !url.contains(".") ||
            (url.startsWith("http") && !url.contains(".mpd") && !url.contains(".mp4") && !url.contains(".ts"))
        return if (isHls) {
            HlsMediaSource.Factory(factory).createMediaSource(MediaItem.fromUri(url))
        } else {
            DefaultMediaSourceFactory(factory).createMediaSource(MediaItem.fromUri(url))
        }
    }

    private companion object {
        const val PREVIEW_TIMEOUT_MS = 6000L
        const val OVERSCAN = 1.06f
    }
}
