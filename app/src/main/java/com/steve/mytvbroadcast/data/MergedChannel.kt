package com.steve.mytvbroadcast.data

data class MergedChannel(
    val id: String,
    val name: String,
    val logo: String?,
    val group: String?,
    val tvgId: String?,
    val sources: List<ChannelSource>,
    val preferredSourceId: String? = null,
    val lastPlayedSourceId: String? = null
)
