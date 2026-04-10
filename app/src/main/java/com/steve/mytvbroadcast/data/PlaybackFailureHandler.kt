package com.steve.mytvbroadcast.data

import android.os.Handler
import android.os.Looper

class PlaybackFailureHandler(
    private val maxFallbacks: Int = 3,
    private val fallbackIntervalMs: Long = 2000,
    private val onFallback: (ChannelSource) -> Unit,
    private val onAllFailed: () -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private var fallbackCount = 0
    private var isActive = false
    private val triedSources = mutableSetOf<String>()

    fun handleFailure(source: ChannelSource) {
        if (!isActive) return
        triedSources.add(source.sourceId)

        if (fallbackCount >= maxFallbacks) {
            stop()
            onAllFailed()
            return
        }

        fallbackCount++
        handler.postDelayed({
            if (isActive) {
                onFallback(source)
            }
        }, fallbackIntervalMs)
    }

    fun reset() {
        fallbackCount = 0
        triedSources.clear()
        isActive = true
    }

    fun stop() {
        isActive = false
        handler.removeCallbacksAndMessages(null)
    }

    fun getTriedSources(): Set<String> = triedSources.toSet()
}
