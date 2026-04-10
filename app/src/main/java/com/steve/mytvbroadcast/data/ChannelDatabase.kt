package com.steve.mytvbroadcast.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object ChannelDatabase {

    private const val PREFS_NAME = "channel_prefs"
    private const val KEY_SELECTED_SOURCE = "selected_source"

    private var channels: List<Channel> = emptyList()
    private var mergedChannels: List<MergedChannel> = emptyList()
    private lateinit var prefs: SharedPreferences

    // 刷新完成回调
    private var onRefreshCompleteListener: ((List<MergedChannel>) -> Unit)? = null

    fun setOnRefreshCompleteListener(listener: ((List<MergedChannel>) -> Unit)?) {
        onRefreshCompleteListener = listener
    }

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        SignalSourceManager.init(context)
        ChannelCacheManager.init(context)
    }

    suspend fun loadChannels(): List<Channel> = withContext(Dispatchers.IO) {
        channels = emptyList()
        val currentSourceId = SignalSourceManager.getCurrentSourceId()
        val sources = SignalSourceManager.getSources()
        println("ChannelDatabase: getSources returned ${sources.size} sources")
        println("ChannelDatabase: currentSourceId = $currentSourceId")

        val currentSource = if (currentSourceId != null) {
            sources.find { it.id == currentSourceId && it.enabled }
        } else {
            sources.find { it.enabled }
        }

        if (currentSource == null) {
            println("ChannelDatabase: No available source (currentSource is null)")
            println("ChannelDatabase: sources = ${sources.map { "${it.name} (enabled=${it.enabled})" }}")
            return@withContext emptyList()
        }

        println("ChannelDatabase: Loading from source: ${currentSource.name}, url: ${currentSource.url}")
        try {
            channels = M3UParser.parseFromUrl(currentSource.url)
            println("ChannelDatabase: Loaded ${channels.size} channels from ${currentSource.name}")
        } catch (e: Exception) {
            println("ChannelDatabase: Error loading from ${currentSource.name}: ${e.message}")
            e.printStackTrace()
            channels = emptyList()
        }

        channels
    }

    suspend fun loadMergedChannels(): List<MergedChannel> = withContext(Dispatchers.IO) {
        val cached = ChannelCacheManager.getMergedChannels()
        if (cached != null) {
            println("ChannelDatabase: Loaded ${cached.size} merged channels from cache")
            mergedChannels = cached
            // 后台刷新频道数据（会通知 listener）
            refreshMergedChannelsInBackground()
        } else {
            // 没有缓存，先加载再刷新
            refreshMergedChannelsInBackground()
        }

        mergedChannels
    }

    private fun refreshMergedChannelsInBackground(onComplete: ((List<MergedChannel>) -> Unit)? = null) {
        CoroutineScope(Dispatchers.IO).launch {
            val enabledSources = SignalSourceManager.getEnabledSources()
            println("ChannelDatabase: Refreshing merged channels from ${enabledSources.size} sources")

            val sourceChannelsMap = mutableMapOf<SignalSource, List<Channel>>()
            enabledSources.forEach { source ->
                try {
                    val channels = M3UParser.parseFromUrl(source.url)
                    sourceChannelsMap[source] = channels
                    println("ChannelDatabase: Loaded ${channels.size} channels from ${source.name}")
                } catch (e: Exception) {
                    println("ChannelDatabase: Error loading from ${source.name}: ${e.message}")
                    sourceChannelsMap[source] = emptyList()
                }
            }

            val refreshedChannels = ChannelMerger.mergeAllSources(sourceChannelsMap)
            ChannelCacheManager.saveMergedChannels(refreshedChannels)
            mergedChannels = refreshedChannels
            println("ChannelDatabase: Refreshed and saved ${refreshedChannels.size} merged channels")

            // 通知监听器
            onRefreshCompleteListener?.invoke(refreshedChannels)

            // 不再在后台对所有频道测速，测速只在用户进入指定频道页面时进行
            // 且只测一次，不重复测

            onComplete?.invoke(refreshedChannels)
        }
    }

    fun getMergedChannels(): List<MergedChannel> = mergedChannels

    fun getChannels(): List<Channel> = channels

    fun getGroupedChannels(): Map<String, List<Channel>> {
        return channels.groupBy { it.group ?: "Ungrouped" }
    }

    fun getSelectedSourceId(): String? = prefs.getString(KEY_SELECTED_SOURCE, null)

    fun setSelectedSourceId(id: String) {
        prefs.edit().putString(KEY_SELECTED_SOURCE, id).apply()
    }

    fun updateMergedChannel(channel: MergedChannel) {
        mergedChannels = mergedChannels.map {
            if (it.id == channel.id) channel else it
        }
        ChannelCacheManager.saveMergedChannels(mergedChannels)
    }

    fun refreshSpeedsInBackground(mergedChannels: List<MergedChannel>, onComplete: ((List<MergedChannel>) -> Unit)? = null) {
        CoroutineScope(Dispatchers.IO).launch {
            SpeedTester.speedTestInBackground(mergedChannels, onComplete)
        }
    }
}
