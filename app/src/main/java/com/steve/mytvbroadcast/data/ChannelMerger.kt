package com.steve.mytvbroadcast.data

import java.security.MessageDigest

object ChannelMerger {

    fun mergeAllSources(sourceChannels: Map<SignalSource, List<Channel>>): List<MergedChannel> {
        val channelKeyMap = mutableMapOf<String, MutableList<Pair<SignalSource, Channel>>>()

        sourceChannels.forEach { (source, channels) ->
            channels.forEach { channel ->
                val key = generateChannelKey(channel)
                val list = channelKeyMap.getOrPut(key) { mutableListOf() }
                list.add(source to channel)
            }
        }

        return channelKeyMap.map { (key, channelPairs) ->
            val firstPair = channelPairs.first()
            // 先按 URL 去重，避免同一频道有多个相同的 source
            val uniqueSources = channelPairs
                .distinctBy { it.second.url }
                .map { (source, channel) ->
                    // sourceId 必须唯一：source.id + channel.url 组合后 MD5
                    val combined = "${source.id}|${channel.url}"
                    ChannelSource(
                        sourceId = md5(combined),
                        signalSourceId = source.id,
                        sourceName = source.name,
                        url = channel.url,
                        speed = -1,
                        lastTested = 0,
                        isAvailable = true
                    )
                }

            val preferredSourceId = ChannelCacheManager.getPreferredSource(key)
            val lastPlayedSourceId = ChannelCacheManager.getLastPlayedSource(key)

            MergedChannel(
                id = key,
                name = firstPair.second.name,
                logo = firstPair.second.logo,
                group = firstPair.second.group,
                tvgId = firstPair.second.tvgId,
                sources = uniqueSources,
                preferredSourceId = preferredSourceId,
                lastPlayedSourceId = lastPlayedSourceId
            )
        }.sortedBy { it.name }
    }

    fun generateChannelKey(channel: Channel): String {
        val name = channel.name ?: "Unknown"
        val group = channel.group ?: ""
        val tvgId = channel.tvgId ?: ""
        val combined = "$name|$group|$tvgId"
        return md5(combined)
    }

    private fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
