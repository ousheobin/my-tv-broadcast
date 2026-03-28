package com.steve.mytvbroadcast.data

data class SignalSource(
    val id: String,
    val name: String,
    val url: String,
    val enabled: Boolean = true
)
