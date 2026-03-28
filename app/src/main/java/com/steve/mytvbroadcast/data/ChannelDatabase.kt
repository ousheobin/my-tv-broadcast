package com.steve.mytvbroadcast.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object ChannelDatabase {

    private const val PREFS_NAME = "channel_prefs"
    private const val KEY_SELECTED_SOURCE = "selected_source"

    private var channels: List<Channel> = emptyList()
    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        SignalSourceManager.init(context)
    }

    suspend fun loadChannels(): List<Channel> = withContext(Dispatchers.IO) {
        channels = emptyList()
        val currentSourceId = SignalSourceManager.getCurrentSourceId()
        val sources = SignalSourceManager.getSources()
        println("ChannelDatabase: getSources returned ${sources.size} sources")
        println("ChannelDatabase: currentSourceId = $currentSourceId")

        // Find the current source, fallback to first enabled source
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

    fun getChannels(): List<Channel> = channels

    fun getGroupedChannels(): Map<String, List<Channel>> {
        return channels.groupBy { it.group ?: "Ungrouped" }
    }

    fun getSelectedSourceId(): String? = prefs.getString(KEY_SELECTED_SOURCE, null)

    fun setSelectedSourceId(id: String) {
        prefs.edit().putString(KEY_SELECTED_SOURCE, id).apply()
    }
}
