package com.steve.mytvbroadcast.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Xml
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID
import java.util.zip.GZIPInputStream

/** EPG 节目信息（XMLTV programme） */
data class EpgProgramme(
    val channelId: String,
    val title: String,
    val startMs: Long,
    val stopMs: Long
) : java.io.Serializable

/** 解析后的节目索引：channel key（id/显示名归一化） -> 按时间排序的节目列表 */
data class EpgIndex(
    val programsByChannel: Map<String, List<EpgProgramme>>,
    /** 归一化 EPG channel id -> XMLTV display-name，用于校验 tvgId 匹配是否真的是本台 */
    val displayNameById: Map<String, String> = emptyMap(),
    /** 归一化 display-name -> 归一化 EPG channel id，供无 tvgId/填错时按台名反查 */
    val idByNormalizedName: Map<String, String> = emptyMap(),
    val fetchedAt: Long,
    val sourceUrl: String
) : java.io.Serializable

/** EPG 信号源（有序，按顺序拉取，成功的第一个为准） */
data class EpgSource(
    val id: String,
    val url: String
)

object EpgManager {

    private const val PREFS_NAME = "epg_prefs"
    private const val KEY_SOURCES = "epg_sources"
    private const val KEY_ENABLED = "epg_enabled"
    // 默认源按顺序检索，第一个解析成功的为准：先大陆台、再港股台
    private val DEFAULT_SOURCE_URLS = listOf(
        "https://epg.pw/xmltv/epg_CN.xml.gz",
        "https://epg.pw/xmltv/epg_HK.xml.gz"
    )
    // 旧版单源默认值，仅用于识别并迁移存量配置
    private const val LEGACY_DEFAULT_SOURCE_URL = "https://epg.pw/xmltv/epg_lite.xml.gz"
    // 多源合并后，缓存的 sourceUrl 字段存各源 URL，用此分隔符拼接
    private const val SOURCE_URL_SEPARATOR = "|"
    private const val CACHE_FILE_NAME = "epg_index_v4.cache"
    private const val REFRESH_INTERVAL_MS = 6 * 60 * 60 * 1000L // 6 小时

    private const val CONNECT_TIMEOUT_MS = 15000
    private const val READ_TIMEOUT_MS = 30000
    private const val MAX_DOWNLOAD_BYTES = 64 * 1024 * 1024

    private lateinit var prefs: SharedPreferences
    private lateinit var cacheFile: File
    @Volatile
    private var cachedIndex: EpgIndex? = null
    @Volatile
    private var refreshing = false
    @Volatile
    private var initialized = false

    fun init(context: Context) {
        // 单例：进程内 prefs / cacheFile 为应用级且稳定，重复 init（如打开设置页）无需再做任何事，
        // 尤其避免再次反序列化整个 EPG 缓存阻塞主线程导致明显卡顿。
        if (initialized) return
        initialized = true
        val appContext = context.applicationContext
        prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        cacheFile = File(appContext.filesDir, CACHE_FILE_NAME)
        loadSourcesIfEmpty()
        // 缓存可能达数 MB，ObjectInputStream 反序列化耗时，放后台线程加载，避免阻塞主线程。
        // 需要时 ensureMemoryCache() 仍会同步兜底加载，保证正确性。
        Thread { loadCacheFromDisk() }.start()
    }

    // ==================== 设置：开关与源列表 ====================

    fun isEpgEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, true)

    fun setEpgEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun getSources(): List<EpgSource> {
        val json = prefs.getString(KEY_SOURCES, null) ?: return defaultSources()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                EpgSource(id = obj.getString("id"), url = obj.getString("url"))
            }
        } catch (e: Exception) {
            defaultSources()
        }
    }

    fun saveSources(sources: List<EpgSource>) {
        val array = JSONArray()
        sources.forEach { obj ->
            array.put(JSONObject().apply {
                put("id", obj.id)
                put("url", obj.url)
            })
        }
        prefs.edit().putString(KEY_SOURCES, array.toString()).apply()
    }

    fun addSource(url: String): EpgSource {
        val source = EpgSource(id = UUID.randomUUID().toString(), url = url)
        saveSources(getSources() + source)
        return source
    }

    fun updateSource(id: String, url: String) {
        saveSources(getSources().map { if (it.id == id) it.copy(url = url) else it })
    }

    fun removeSource(id: String) {
        saveSources(getSources().filterNot { it.id == id })
    }

    private fun defaultSources(): List<EpgSource> =
        DEFAULT_SOURCE_URLS.map { EpgSource(id = UUID.randomUUID().toString(), url = it) }

    private fun loadSourcesIfEmpty() {
        val json = prefs.getString(KEY_SOURCES, null)
        if (json == null) {
            saveSources(defaultSources())
            return
        }
        // 存量配置若仍是旧版单源默认值（或已清空），迁移为新的 CN→HK 双默认源；
        // 用户自己增删过的列表不动
        try {
            val array = JSONArray(json)
            val urls = (0 until array.length()).map { array.getJSONObject(it).getString("url") }
            if (urls.isEmpty() || (urls.size == 1 && urls[0] == LEGACY_DEFAULT_SOURCE_URL)) {
                saveSources(defaultSources())
            }
        } catch (_: Exception) {}
    }

    // ==================== 拉取与缓存 ====================

    fun isStale(): Boolean {
        val index = ensureMemoryCache() ?: return true
        if (System.currentTimeMillis() - index.fetchedAt > REFRESH_INTERVAL_MS) return true
        // 当前源列表里有源未被缓存覆盖（新增源/默认源改版），视为过期触发重拉
        val cachedUrls = index.sourceUrl.split(SOURCE_URL_SEPARATOR).toSet()
        return getSources().any { it.url !in cachedUrls }
    }

    /** 后台刷新：拉取并解析全部源，按源顺序合并为一份索引。非阻塞，重复调用会被忽略。 */
    fun refreshAsync(onDone: ((Boolean) -> Unit)? = null) {
        if (refreshing) { onDone?.invoke(false); return }
        refreshing = true
        Thread {
            val success = try {
                fetchAndMergeAll() != null
            } catch (e: Exception) {
                android.util.Log.e("EpgManager", "refresh failed: ${e.message}")
                false
            }
            refreshing = false
            onDone?.invoke(success)
        }.start()
    }

    /**
     * 依次拉取解析所有源，合并成单一索引：同一频道 id/台名以排在前面的源为准，
     * 不同源各自补充自己的频道（如 CN 源补大陆台、HK 源补港股台）。
     * 单个源失败不影响其余源，全部失败才返回 null。
     */
    private fun fetchAndMergeAll(): EpgIndex? {
        val mergedPrograms = mutableMapOf<String, MutableList<EpgProgramme>>()
        val mergedDisplayNames = mutableMapOf<String, String>()
        val mergedNameToId = mutableMapOf<String, String>()
        val fetchedUrls = mutableListOf<String>()

        for (source in getSources()) {
            val index = try {
                val bytes = download(source.url)
                EpgXmlTvParser.parse(bytes).takeIf { it.programsByChannel.isNotEmpty() }
            } catch (e: Exception) {
                android.util.Log.w("EpgManager", "source failed ${source.url}: ${e.message}")
                null
            } ?: continue

            fetchedUrls.add(source.url)
            index.programsByChannel.forEach { (key, list) ->
                // 前面的源优先，后面的源只补缺
                if (!mergedPrograms.containsKey(key)) mergedPrograms[key] = list.toMutableList()
            }
            index.displayNameById.forEach { (idKey, name) ->
                if (!mergedDisplayNames.containsKey(idKey)) mergedDisplayNames[idKey] = name
            }
            index.idByNormalizedName.forEach { (nameKey, idKey) ->
                if (!mergedNameToId.containsKey(nameKey)) mergedNameToId[nameKey] = idKey
            }
            android.util.Log.d("EpgManager", "merged source ${source.url}: ${index.programsByChannel.size} channels")
        }

        if (fetchedUrls.isEmpty()) return null
        mergedPrograms.values.forEach { list -> list.sortBy { it.startMs } }
        val merged = EpgIndex(
            programsByChannel = mergedPrograms.toMap(),
            displayNameById = mergedDisplayNames.toMap(),
            idByNormalizedName = mergedNameToId.toMap(),
            fetchedAt = System.currentTimeMillis(),
            sourceUrl = fetchedUrls.joinToString(SOURCE_URL_SEPARATOR)
        )
        saveCacheToDisk(merged)
        cachedIndex = merged
        return merged
    }

    private fun download(url: String): ByteArray {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (AndroidTV)")
            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw IllegalStateException("HTTP $responseCode")
            }
            val raw = BufferedInputStream(connection.inputStream)
            // .gz 后缀或 gzip 响应头都要解压
            val gzip = url.endsWith(".gz") || "gzip".equals(connection.contentEncoding, ignoreCase = true)
            val stream = if (gzip) GZIPInputStream(raw) else raw
            stream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                var n: Int
                while (input.read(buffer).also { n = it } > 0) {
                    total += n
                    if (total > MAX_DOWNLOAD_BYTES) throw IllegalStateException("EPG 文件过大")
                    output.write(buffer, 0, n)
                }
                return output.toByteArray()
            }
        } finally {
            connection.disconnect()
        }
    }

    // ==================== 查询与频道匹配 ====================

    /** 当前时段节目；EPG 关闭/无数据/未匹配时返回 null */
    fun getCurrentProgramme(channel: MergedChannel, nowMs: Long = System.currentTimeMillis()): EpgProgramme? {
        if (!isEpgEnabled()) return null
        val programs = matchChannel(channel, nowMs) ?: return null
        return programs.firstOrNull { it.startMs <= nowMs && nowMs < it.stopMs }
    }

    /** 当前节目之后的下一个节目 */
    fun getNextProgramme(channel: MergedChannel, nowMs: Long = System.currentTimeMillis()): EpgProgramme? {
        if (!isEpgEnabled()) return null
        val programs = matchChannel(channel, nowMs) ?: return null
        return programs.firstOrNull { it.stopMs <= nowMs || it.startMs > nowMs }
            ?.takeIf { it.startMs > nowMs }
    }

    private fun matchChannel(channel: MergedChannel, nowMs: Long): List<EpgProgramme>? {
        val index = ensureMemoryCache() ?: return null
        // 1) tvgId 精确匹配：但第三方 M3U 的 tvg-id 经常填错，必须用 EPG 侧的
        // display-name 校验确实是本台，否则宁可不用，防止匹配到别的频道节目单
        for (rawId in listOfNotNull(channel.tvgId)) {
            val key = normalizeKey(rawId)
            if (key.isEmpty()) continue
            val programs = index.programsByChannel[key] ?: continue
            if (displayNameCompatible(channel.name, index.displayNameById[key])) return programs
        }
        // 2) 按台名匹配：epg.pw 这类源频道 id 是纯数字，tvg-id 精确匹配失效；M3U 台名又常是
        // EPG display-name 的前缀（"CCTV1" vs "cctv1综合"），且同一基础台常有多个变体：
        // 综合/高清/标清、CN 真表 vs HK 占位表（如 "CCTV-1" 只有 1 条"未能提供节目表"占位）。
        // 因此不能命中精确名就立即返回，而是在所有 display-name 中找以本台名开头者
        // （containsLoosely 数字边界保护避免 cctv1 误配 cctv10），优先选节目最多（真实完整节目单）
        // 者，并列取归一化台名最短（最贴近基础名）者——从而避开占位变体、命中 CN 真表。
        val nameKey = normalizeForMatch(channel.name)
        if (nameKey.length >= 2) {
            var bestIdKey: String? = null
            var bestCount = -1
            var bestLen = Int.MAX_VALUE
            for ((idKey, displayName) in index.displayNameById) {
                val dispKey = normalizeForMatch(displayName)
                if (!containsLoosely(nameKey, dispKey)) continue
                val programs = index.programsByChannel[idKey] ?: continue
                val count = programs.size
                if (count > bestCount || (count == bestCount && dispKey.length < bestLen)) {
                    bestIdKey = idKey
                    bestCount = count
                    bestLen = dispKey.length
                }
            }
            bestIdKey?.let { return index.programsByChannel[it] }
        }
        return null
    }

    /** 频道名与 EPG display-name 是否同一台：相等或包含，但数字边界不能粘连（cctv1 ≠ cctv10） */
    private fun displayNameCompatible(channelName: String, epgDisplayName: String?): Boolean {
        if (epgDisplayName.isNullOrBlank()) return true // EPG 侧无显示名，只能信任 id 匹配
        val a = normalizeKey(channelName)
        val b = normalizeKey(epgDisplayName)
        if (a.isEmpty() || b.isEmpty()) return true
        if (a == b) return true
        return containsLoosely(a, b) || containsLoosely(b, a)
    }

    private fun containsLoosely(shorter: String, longer: String): Boolean {
        if (shorter.isEmpty() || shorter.length > longer.length) return false
        val idx = longer.indexOf(shorter)
        if (idx < 0) return false
        val charAfter = longer.getOrNull(idx + shorter.length)
        val charBefore = longer.getOrNull(idx - 1)
        if (shorter.last().isDigit() && charAfter?.isDigit() == true) return false
        if (shorter.first().isDigit() && charBefore?.isDigit() == true) return false
        return true
    }

    private fun normalizeKey(name: String): String =
        name.lowercase(Locale.ROOT).replace(Regex("[\\s\\-_·•（）()【】\\[\\]{}#*]+"), "")

    // 画质/分辨率后缀（如 "(720p)"、"1080P"、"HD"、"SD"）常被写进 M3U 台名，会让归一化名
    // 与 EPG display-name 对不上（"cctv10720p" vs "cctv10科教"）。匹配前先把这些标记从原始
    // 台名剔除再归一化。注意不剔除 4K/8K（那是真实频道名，如 CCTV4K/CCTV-8K）。
    private val qualityTagRegex = Regex("""\b(?:\d{3,4}[pi]|fhd|uhd|hd|sd)\b""", RegexOption.IGNORE_CASE)

    private fun normalizeForMatch(name: String): String =
        normalizeKey(qualityTagRegex.replace(name, " "))

    // ==================== 磁盘缓存 ====================

    private fun ensureMemoryCache(): EpgIndex? {
        if (cachedIndex == null && cacheFile.exists()) {
            loadCacheFromDisk()
        }
        return cachedIndex?.takeIf {
            System.currentTimeMillis() - it.fetchedAt <= REFRESH_INTERVAL_MS || it.sourceUrl.isNotEmpty()
        }
    }

    @Synchronized
    private fun loadCacheFromDisk() {
        if (cachedIndex != null) return
        try {
            if (!cacheFile.exists()) return
            ObjectInputStream(cacheFile.inputStream()).use { ois ->
                cachedIndex = ois.readObject() as? EpgIndex
            }
        } catch (e: Exception) {
            android.util.Log.w("EpgManager", "cache load failed: ${e.message}")
        }
    }

    private fun saveCacheToDisk(index: EpgIndex) {
        try {
            ObjectOutputStream(cacheFile.outputStream()).use { it.writeObject(index) }
        } catch (e: Exception) {
            android.util.Log.w("EpgManager", "cache save failed: ${e.message}")
        }
    }
}

/** XMLTV 解析器：XmlPullParser，兼容 .xml 与已解压字节 */
private object EpgXmlTvParser {

    fun parse(bytes: ByteArray): EpgIndex {
        val programs = mutableMapOf<String, MutableList<EpgProgramme>>()
        val displayNameById = mutableMapOf<String, String>()
        val idByNormalizedName = mutableMapOf<String, String>()
        val ambiguousNameKeys = mutableSetOf<String>()
        val parser = Xml.newPullParser()
        parser.setInput(BufferedInputStream(ByteArrayInputStream(bytes)), null)

        var channelId: String? = null
        var title: String? = null
        var startRaw: String? = null
        var stopRaw: String? = null
        var inProgramme = false
        var inTitle = false
        // <channel> 元素：id + 首个 display-name，用于建立台名→id 反查表
        var inChannel = false
        var channelElemId: String? = null
        var channelDisplayName: String? = null
        var inDisplayName = false
        var event = parser.eventType

        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "programme" -> {
                            inProgramme = true
                            channelId = parser.getAttributeValue(null, "channel")
                            startRaw = parser.getAttributeValue(null, "start")
                            stopRaw = parser.getAttributeValue(null, "stop")
                            title = null
                        }
                        "title" -> if (inProgramme) inTitle = true
                        "channel" -> {
                            inChannel = true
                            channelElemId = parser.getAttributeValue(null, "id")
                            channelDisplayName = null
                        }
                        "display-name" -> if (inChannel) inDisplayName = true
                    }
                }
                XmlPullParser.TEXT -> {
                    val text = parser.text?.trim()
                    if (inTitle && title == null && !text.isNullOrEmpty()) title = text
                    if (inDisplayName && channelDisplayName == null && !text.isNullOrEmpty()) {
                        channelDisplayName = text
                    }
                }
                XmlPullParser.END_TAG -> {
                    when (parser.name) {
                        "title" -> inTitle = false
                        "display-name" -> inDisplayName = false
                        "channel" -> {
                            inChannel = false
                            inDisplayName = false
                            val id = channelElemId
                            val name = channelDisplayName
                            if (id != null && name != null) {
                                val idKey = normalizeChannelKey(id)
                                if (!displayNameById.containsKey(idKey)) {
                                    displayNameById[idKey] = name
                                }
                                val nameKey = normalizeChannelKey(name)
                                val existing = idByNormalizedName[nameKey]
                                when {
                                    nameKey.isEmpty() -> {}
                                    existing == null -> idByNormalizedName[nameKey] = idKey
                                    existing != idKey -> ambiguousNameKeys.add(nameKey) // 同名多台，不做猜测
                                }
                            }
                            channelElemId = null
                            channelDisplayName = null
                        }
                        "programme" -> {
                            inProgramme = false
                            val id = channelId
                            val t = title
                            val start = parseXmlTvTime(startRaw)
                            val stop = parseXmlTvTime(stopRaw)
                            if (id != null && t != null && start != null && stop != null && stop > start) {
                                val key = normalizeChannelKey(id)
                                programs.getOrPut(key) { mutableListOf() }
                                    .add(EpgProgramme(channelId = id, title = t, startMs = start, stopMs = stop))
                            }
                        }
                    }
                }
            }
            event = parser.next()
        }

        programs.values.forEach { list -> list.sortBy { it.startMs } }
        ambiguousNameKeys.forEach { idByNormalizedName.remove(it) }
        return EpgIndex(
            programsByChannel = programs.toMap(),
            displayNameById = displayNameById.toMap(),
            idByNormalizedName = idByNormalizedName.toMap(),
            fetchedAt = 0L,
            sourceUrl = ""
        )
    }

    // 与 EpgManager.normalizeKey 保持一致的归一化规则
    private fun normalizeChannelKey(name: String): String =
        name.lowercase(Locale.ROOT).replace(Regex("[\\s\\-_·•（）()【】\\[\\]{}#*]+"), "")

    private fun parseXmlTvTime(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        // 很多中文源（如 epg.pw）的时间数字本身就是本地墙钟时间，尾部 " +0000" 只是占位、
        // 并不代表 UTC。若按偏移量换算会把整体时间推到错误时区，所以只取前导数字按本地时区解析。
        val digits = raw.trim().takeWhile { it.isDigit() }
        val pattern = when {
            digits.length >= 14 -> "yyyyMMddHHmmss"
            digits.length >= 12 -> "yyyyMMddHHmm"
            digits.length >= 8 -> "yyyyMMdd"
            else -> return null
        }
        return try {
            val sdf = SimpleDateFormat(pattern, Locale.US)
            sdf.isLenient = false
            sdf.parse(digits)?.time
        } catch (_: Exception) {
            null
        }
    }
}
