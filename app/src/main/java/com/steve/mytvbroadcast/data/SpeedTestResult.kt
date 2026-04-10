package com.steve.mytvbroadcast.data

data class SpeedTestResult(
    val sourceId: String,
    val url: String,
    val latencyMs: Long,
    val success: Boolean,
    val errorMessage: String? = null
)
