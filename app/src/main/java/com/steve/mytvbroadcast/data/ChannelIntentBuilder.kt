package com.steve.mytvbroadcast.data

import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject

/** 统一构造进入 PlaybackActivity 的 Intent，避免首页/频道页/恢复逻辑各写一份 JSON 拼装。 */
object ChannelIntentBuilder {

    private const val TARGET = "com.steve.mytvbroadcast.ui.PlaybackActivity"

    fun toJson(channel: MergedChannel): String = JSONObject().apply {
        put("id", channel.id)
        put("name", channel.name)
        put("logo", channel.logo)
        put("group", channel.group)
        put("tvgId", channel.tvgId)
        put("preferredSourceId", channel.preferredSourceId)
        put("lastPlayedSourceId", channel.lastPlayedSourceId)
        val sourcesArray = JSONArray()
        channel.sources.forEach { source ->
            sourcesArray.put(JSONObject().apply {
                put("sourceId", source.sourceId)
                put("signalSourceId", source.signalSourceId)
                put("sourceName", source.sourceName)
                put("url", source.url)
                put("speed", source.speed)
                put("lastTested", source.lastTested)
                put("isAvailable", source.isAvailable)
            })
        }
        put("sources", sourcesArray)
    }.toString()

    /**
     * @param channel 要播放的频道
     * @param allChannels 供播放页上下切台用的完整列表
     * @param index channel 在 allChannels 中的序号；未知传 -1
     */
    fun build(
        context: Context,
        channel: MergedChannel,
        allChannels: List<MergedChannel>,
        index: Int = -1
    ): Intent {
        val resolvedIndex = if (index >= 0) index else allChannels.indexOfFirst { it.id == channel.id }
        return Intent().apply {
            setClassName(context, TARGET)
            putExtra("channel_name", channel.name)
            putExtra("merged_channel", toJson(channel))
            putExtra("channel_index", resolvedIndex.coerceAtLeast(0))
            putStringArrayListExtra("channel_urls", ArrayList(allChannels.mapNotNull { it.sources.firstOrNull()?.url }))
            putStringArrayListExtra("channel_names", ArrayList(allChannels.map { it.name }))
        }
    }
}
