package com.steve.mytvbroadcast.ui.settings

data class SettingsCategory(
    val id: String,
    val title: String
)

object SettingsCategories {
    val SERVER = SettingsCategory("server", "HTTP 服务")
    val SOURCES = SettingsCategory("sources", "信号源管理")

    val ALL = listOf(SERVER, SOURCES)

    fun getById(id: String): SettingsCategory? = ALL.find { it.id == id }
}
