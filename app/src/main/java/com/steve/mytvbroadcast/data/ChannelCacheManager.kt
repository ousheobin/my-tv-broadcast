package com.steve.mytvbroadcast.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

object ChannelCacheManager {

    private const val PREFS_NAME = "channel_cache"

    // 恢复播放的三个字段单独存一份小文件：冷启动主线程只读几 KB，不必等整份几 MB 合并缓存的 XML 解完
    private const val STATE_PREFS_NAME = "playback_state"
    private const val KEY_SPEED_CACHE = "speed_cache"
    private const val KEY_MERGED_CACHE = "merged_cache"
    private const val KEY_MERGED_CACHE_TIME = "merged_cache_time"
    private const val KEY_PREFERRED_SOURCES = "preferred_sources"
    private const val KEY_LAST_PLAYED = "last_played"
    private const val KEY_LAST_PLAYED_CHANNEL_ID = "last_played_channel_id"
    private const val KEY_LAST_PLAYED_CHANNEL_NAME = "last_played_channel_name"
    private const val KEY_LAST_PLAYED_SNAPSHOT = "last_played_snapshot"
    private const val KEY_CACHE_VERSION = "cache_version"

    private const val SPEED_CACHE_EXPIRY_MS = 5 * 60 * 1000L
    private const val MERGED_CACHE_EXPIRY_MS = 24 * 60 * 60 * 1000L
    private const val CURRENT_CACHE_VERSION = 3  // 每次修改数据结构时递增

    // 两份文件都懒加载：getSharedPreferences 一旦调用就会后台读完整个 XML，几 MB 的合并缓存
    // 不能在主线程搭建启动页时抢 I/O 与 CPU，只有真正要频道列表时（IO 线程）才碰它
    private lateinit var appContext: Context
    private val prefs: SharedPreferences by lazy {
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    private val statePrefs: SharedPreferences by lazy {
        appContext.getSharedPreferences(STATE_PREFS_NAME, Context.MODE_PRIVATE)
    }

    // 解析后的合并频道列表内存镜像：避免每次 getMergedChannels() 都在主线程重解几 MB JSON
    private var mergedMemoryCache: List<MergedChannel>? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun saveSpeedCache(speedMap: Map<String, Long>) {
        val json = JSONObject()
        speedMap.forEach { (sourceId, speed) ->
            json.put(sourceId, speed)
        }
        prefs.edit().putString(KEY_SPEED_CACHE, json.toString()).apply()
    }

    fun getSpeedCache(): Map<String, Long> {
        val jsonStr = prefs.getString(KEY_SPEED_CACHE, null) ?: return emptyMap()
        return try {
            val json = JSONObject(jsonStr)
            val map = mutableMapOf<String, Long>()
            json.keys().forEach { key ->
                map[key] = json.getLong(key)
            }
            map
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun isSpeedCacheValid(sourceId: String): Boolean {
        val cache = getSpeedCache()
        val timestamp = cache[sourceId] ?: return false
        return System.currentTimeMillis() - timestamp < SPEED_CACHE_EXPIRY_MS
    }

    fun saveMergedChannels(mergedChannels: List<MergedChannel>) {
        val jsonArray = JSONArray()
        mergedChannels.forEach { channel ->
            val jsonChannel = JSONObject().apply {
                put("id", channel.id)
                put("name", channel.name)
                put("logo", channel.logo)
                put("group", channel.group)
                put("tvgId", channel.tvgId)
                put("preferredSourceId", channel.preferredSourceId)
                put("lastPlayedSourceId", channel.lastPlayedSourceId)

                val sourcesArray = JSONArray()
                channel.sources.forEach { source ->
                    val sourceJson = JSONObject().apply {
                        put("sourceId", source.sourceId)
                        put("signalSourceId", source.signalSourceId)
                        put("sourceName", source.sourceName)
                        put("url", source.url)
                        put("speed", source.speed)
                        put("lastTested", source.lastTested)
                        put("isAvailable", source.isAvailable)
                    }
                    sourcesArray.put(sourceJson)
                }
                put("sources", sourcesArray)
            }
            jsonArray.put(jsonChannel)
        }
        prefs.edit()
            .putString(KEY_MERGED_CACHE, jsonArray.toString())
            .putLong(KEY_MERGED_CACHE_TIME, System.currentTimeMillis())
            .putInt(KEY_CACHE_VERSION, CURRENT_CACHE_VERSION)
            .apply()
        mergedMemoryCache = mergedChannels
    }

    fun getMergedChannels(): List<MergedChannel>? {
        // 检查缓存版本，如果版本不匹配则清除缓存
        val cachedVersion = prefs.getInt(KEY_CACHE_VERSION, 0)
        if (cachedVersion != CURRENT_CACHE_VERSION) {
            clearMergedCache()
            prefs.edit().putInt(KEY_CACHE_VERSION, CURRENT_CACHE_VERSION).apply()
        }

        val cacheTime = prefs.getLong(KEY_MERGED_CACHE_TIME, 0)
        if (System.currentTimeMillis() - cacheTime > MERGED_CACHE_EXPIRY_MS) {
            mergedMemoryCache = null
            return null
        }

        // 命中内存镜像直接返回，避免主线程重复反序列化大 JSON
        mergedMemoryCache?.let { return it }

        val jsonStr = prefs.getString(KEY_MERGED_CACHE, null) ?: return null
        return try {
            val jsonArray = JSONArray(jsonStr)
            val channels = mutableListOf<MergedChannel>()
            for (i in 0 until jsonArray.length()) {
                val jsonChannel = jsonArray.getJSONObject(i)
                val sourcesArray = jsonChannel.getJSONArray("sources")
                val sources = mutableListOf<ChannelSource>()
                for (j in 0 until sourcesArray.length()) {
                    val sourceJson = sourcesArray.getJSONObject(j)
                    sources.add(ChannelSource(
                        sourceId = sourceJson.getString("sourceId"),
                        signalSourceId = sourceJson.optString("signalSourceId", sourceJson.getString("sourceId")),
                        sourceName = sourceJson.getString("sourceName"),
                        url = sourceJson.getString("url"),
                        speed = sourceJson.optLong("speed", -1),
                        lastTested = sourceJson.optLong("lastTested", 0),
                        isAvailable = sourceJson.optBoolean("isAvailable", true)
                    ))
                }
                channels.add(MergedChannel(
                    id = jsonChannel.getString("id"),
                    name = jsonChannel.getString("name"),
                    logo = jsonChannel.optString("logo", null),
                    group = jsonChannel.optString("group", null),
                    tvgId = jsonChannel.optString("tvgId", null),
                    sources = sources,
                    preferredSourceId = jsonChannel.optString("preferredSourceId", null),
                    lastPlayedSourceId = jsonChannel.optString("lastPlayedSourceId", null)
                ))
            }
            mergedMemoryCache = channels
            channels
        } catch (_: Exception) {
            null
        }
    }

    fun clearMergedCache() {
        mergedMemoryCache = null
        prefs.edit().remove(KEY_MERGED_CACHE).remove(KEY_MERGED_CACHE_TIME).apply()
    }

    // 更新单个频道到缓存
    fun updateMergedChannelInCache(updatedChannel: MergedChannel) {
        val cached = getMergedChannels() ?: return
        val updatedList = cached.map { channel ->
            if (channel.id == updatedChannel.id) updatedChannel else channel
        }
        saveMergedChannels(updatedList)
    }

    fun setPreferredSource(channelId: String, sourceId: String) {
        val prefs = prefs
        val allPrefs = prefs.getString(KEY_PREFERRED_SOURCES, null) ?: "{}"
        try {
            val json = JSONObject(allPrefs)
            json.put(channelId, sourceId)
            prefs.edit().putString(KEY_PREFERRED_SOURCES, json.toString()).apply()
        } catch (_: Exception) {}
    }

    fun getPreferredSource(channelId: String): String? {
        val allPrefs = prefs.getString(KEY_PREFERRED_SOURCES, null) ?: return null
        return try {
            val json = JSONObject(allPrefs)
            json.optString(channelId, null)
        } catch (_: Exception) {
            null
        }
    }

    fun setLastPlayedSource(channelId: String, sourceId: String) {
        val allPrefs = prefs.getString(KEY_LAST_PLAYED, null) ?: "{}"
        try {
            val json = JSONObject(allPrefs)
            json.put(channelId, sourceId)
            prefs.edit().putString(KEY_LAST_PLAYED, json.toString()).apply()
        } catch (_: Exception) {}
    }

    fun getLastPlayedSource(channelId: String): String? {
        val allPrefs = prefs.getString(KEY_LAST_PLAYED, null) ?: return null
        return try {
            val json = JSONObject(allPrefs)
            json.optString(channelId, null)
        } catch (_: Exception) {
            null
        }
    }

    fun setLastPlayedChannel(channelId: String, channelName: String) {
        statePrefs.edit()
            .putString(KEY_LAST_PLAYED_CHANNEL_ID, channelId)
            .putString(KEY_LAST_PLAYED_CHANNEL_NAME, channelName)
            .apply()
    }

    fun getLastPlayedChannelId(): String? = readState(KEY_LAST_PLAYED_CHANNEL_ID)

    fun getLastPlayedChannelName(): String? = readState(KEY_LAST_PLAYED_CHANNEL_NAME)

    /** 小文件里没有就读一次旧的大缓存并搬过来（仅升级后的首次启动会付这次解析）。 */
    private fun readState(key: String): String? {
        statePrefs.getString(key, null)?.let { return it }
        val legacy = prefs.getString(key, null) ?: return null
        statePrefs.edit().putString(key, legacy).apply()
        return legacy
    }

    // 上次播放频道的完整快照（单个 MergedChannel 的 JSON，含其所有信号源），格式与 merged 缓存/intent 一致。
    // 冷启动恢复时直接读它秒起播，无需解析整份几 MB 合并列表；列表在后台加载完再定位切台序号。
    fun setLastPlayedSnapshot(channel: MergedChannel) {
        try {
            val json = channelToJson(channel)
            statePrefs.edit().putString(KEY_LAST_PLAYED_SNAPSHOT, json.toString()).apply()
        } catch (_: Exception) {}
    }

    fun getLastPlayedSnapshot(): String? = readState(KEY_LAST_PLAYED_SNAPSHOT)

    private fun channelToJson(channel: MergedChannel): JSONObject = JSONObject().apply {
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
    }

    fun clearLastPlayedChannel() {
        prefs.edit()
            .remove(KEY_LAST_PLAYED_CHANNEL_ID)
            .remove(KEY_LAST_PLAYED_CHANNEL_NAME)
            .apply()
    }
}
