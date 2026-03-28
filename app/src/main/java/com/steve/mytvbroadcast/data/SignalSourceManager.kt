package com.steve.mytvbroadcast.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

object SignalSourceManager {

    private const val PREFS_NAME = "signal_source_prefs"
    private const val KEY_SOURCES = "sources"
    private const val KEY_SERVER_PORT = "server_port"
    private const val KEY_SERVER_ENABLED = "server_enabled"
    private const val KEY_CURRENT_SOURCE_ID = "current_source_id"
    private const val DEFAULT_PORT = 8080

    private lateinit var prefs: SharedPreferences

    // 观察者模式
    private val listeners = mutableListOf<SourceChangeListener>()

    interface SourceChangeListener {
        fun onSourcesChanged()
    }

    fun addListener(listener: SourceChangeListener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
    }

    fun removeListener(listener: SourceChangeListener) {
        listeners.remove(listener)
    }

    private fun notifyListeners() {
        listeners.forEach { it.onSourcesChanged() }
    }

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getSources(): List<SignalSource> {
        val json = prefs.getString(KEY_SOURCES, null) ?: return getDefaultSources()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                SignalSource(
                    id = obj.getString("id"),
                    name = obj.getString("name"),
                    url = obj.getString("url"),
                    enabled = obj.optBoolean("enabled", true)
                )
            }
        } catch (e: Exception) {
            getDefaultSources()
        }
    }

    fun saveSources(sources: List<SignalSource>) {
        val array = JSONArray()
        sources.forEach { source ->
            val obj = JSONObject().apply {
                put("id", source.id)
                put("name", source.name)
                put("url", source.url)
                put("enabled", source.enabled)
            }
            array.put(obj)
        }
        prefs.edit().putString(KEY_SOURCES, array.toString()).apply()
        notifyListeners()
    }

    fun addSource(name: String, url: String): SignalSource {
        val source = SignalSource(
            id = UUID.randomUUID().toString(),
            name = name,
            url = url,
            enabled = true
        )
        val sources = getSources().toMutableList()
        sources.add(source)

        // 如果当前没有选择任何信号源，自动选择新添加的
        if (getCurrentSourceId() == null) {
            setCurrentSourceId(source.id)
        }

        saveSources(sources)
        return source
    }

    fun updateSource(source: SignalSource) {
        val sources = getSources().toMutableList()
        val index = sources.indexOfFirst { it.id == source.id }
        if (index >= 0) {
            sources[index] = source
            saveSources(sources)
        }
    }

    fun removeSource(id: String) {
        val sources = getSources().toMutableList()
        sources.removeAll { it.id == id }
        saveSources(sources)
    }

    fun getEnabledSources(): List<SignalSource> = getSources().filter { it.enabled }

    fun getCurrentSourceId(): String? = prefs.getString(KEY_CURRENT_SOURCE_ID, null)

    fun setCurrentSourceId(id: String) {
        prefs.edit().putString(KEY_CURRENT_SOURCE_ID, id).apply()
    }

    fun getServerPort(): Int = prefs.getInt(KEY_SERVER_PORT, DEFAULT_PORT)

    fun setServerPort(port: Int) {
        prefs.edit().putInt(KEY_SERVER_PORT, port).apply()
    }

    fun isServerEnabled(): Boolean = prefs.getBoolean(KEY_SERVER_ENABLED, false)

    fun setServerEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SERVER_ENABLED, enabled).apply()
    }

    private fun getDefaultSources(): List<SignalSource> {
        return emptyList()
    }
}
