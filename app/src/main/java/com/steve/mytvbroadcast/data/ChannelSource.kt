package com.steve.mytvbroadcast.data

data class ChannelSource(
    val sourceId: String,
    val signalSourceId: String,  // 原始信号源ID，用于筛选
    val sourceName: String,
    val url: String,
    val speed: Long = -1,
    val lastTested: Long = 0,
    val isAvailable: Boolean = true
)
