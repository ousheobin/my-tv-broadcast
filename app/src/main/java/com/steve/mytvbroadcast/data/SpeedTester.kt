package com.steve.mytvbroadcast.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

object SpeedTester {

    private const val MAX_PARALLEL_TESTS = 10

    private val client: OkHttpClient by lazy {
        try {
            java.security.Security.setProperty("ocsp.enable", "false")
            System.setProperty("com.sun.security.enableCRLDP", "false")
        } catch (_: Exception) {}

        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, trustAllCerts, SecureRandom())
        }
        OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    // 线程池，并行测速，使用固定大小避免内存溢出
    private val threadPool: ExecutorService = Executors.newFixedThreadPool(MAX_PARALLEL_TESTS)

    suspend fun speedTestSources(sources: List<ChannelSource>, timeoutMs: Long = 5000): List<Pair<ChannelSource, SpeedTestResult>> = withContext(Dispatchers.IO) {
        val results = mutableListOf<Pair<ChannelSource, SpeedTestResult>>()

        // 分批处理，每批最多 MAX_PARALLEL_TESTS 个
        sources.chunked(MAX_PARALLEL_TESTS).forEach { batch ->
            val futures = batch.map { source ->
                threadPool.submit<Pair<ChannelSource, SpeedTestResult>> {
                    val result = speedTestSource(source, timeoutMs)
                    source to result
                }
            }

            futures.forEach { future ->
                try {
                    val pair = future.get(timeoutMs + 1000, TimeUnit.MILLISECONDS)
                    results.add(pair)
                } catch (e: Exception) {
                    // 超时或异常，忽略
                }
            }
        }
        results
    }

    private fun speedTestSource(source: ChannelSource, timeoutMs: Long): SpeedTestResult {
        val startTime = System.currentTimeMillis()
        return try {
            // HLS流不支持HEAD请求，改用GET请求并读取响应体前1024字节验证内容
            val request = Request.Builder()
                .url(source.url)
                .get()
                .build()

            val response = client.newCall(request).execute()
            val latency = System.currentTimeMillis() - startTime

            // 检查HTTP状态码
            if (response.code != 200) {
                response.close()
                return SpeedTestResult(
                    sourceId = source.sourceId,
                    url = source.url,
                    latencyMs = latency,
                    success = false,
                    errorMessage = "HTTP ${response.code}"
                )
            }

            // 读取响应体前1024字节，检查是否是有效的HLS内容
            val body = response.body
            val content = if (body != null) {
                val buffer = okio.Buffer()
                body.source().request(1024)
                buffer.readUtf8()
            } else {
                ""
            }
            response.close()

            // 检查是否包含HLS playlist标记
            val isValidHls = content.contains("#EXTM3U") || content.contains(".m3u8") || content.startsWith("#EXTINF")
            android.util.Log.d("SpeedTester", "speedTestSource: ${source.sourceId}, latency=$latency, contentLength=${content.length}, isValidHls=$isValidHls")

            if (isValidHls) {
                SpeedTestResult(
                    sourceId = source.sourceId,
                    url = source.url,
                    latencyMs = latency,
                    success = true
                )
            } else {
                SpeedTestResult(
                    sourceId = source.sourceId,
                    url = source.url,
                    latencyMs = latency,
                    success = false,
                    errorMessage = "Invalid HLS content"
                )
            }
        } catch (e: Exception) {
            val latency = System.currentTimeMillis() - startTime
            SpeedTestResult(
                sourceId = source.sourceId,
                url = source.url,
                latencyMs = latency,
                success = false,
                errorMessage = e.message
            )
        }
    }

    // 对频道的所有信号源并行测速，返回保持原始顺序的源列表（只更新speed数据）
    suspend fun speedTestChannel(channelKey: String, sources: List<ChannelSource>): List<ChannelSource> = withContext(Dispatchers.IO) {
        val results = speedTestSources(sources)

        val speedMap = results.mapNotNull { (source, result) ->
            if (result.success) source.sourceId to result.latencyMs else null
        }.toMap()
        ChannelCacheManager.saveSpeedCache(speedMap)

        // 保持原始顺序，只更新speed数据
        sources.map { source ->
            val result = results.find { it.first.sourceId == source.sourceId }
            if (result != null && result.second.success) {
                source.copy(speed = result.second.latencyMs, lastTested = System.currentTimeMillis(), isAvailable = true)
            } else {
                source
            }
        }
    }

    // 并行测速，返回第一个成功的源（最快的），2秒超时后返回null
    suspend fun speedTestUntilFirstSuccess(sources: List<ChannelSource>, timeoutMs: Long = 2000): ChannelSource? = withContext(Dispatchers.IO) {
        if (sources.isEmpty()) return@withContext null

        val startTime = System.currentTimeMillis()
        val results = mutableListOf<Pair<ChannelSource, SpeedTestResult>>()

        // 所有源并行测速
        val futures = sources.map { source ->
            threadPool.submit<Pair<ChannelSource, SpeedTestResult>> {
                val result = speedTestSource(source, timeoutMs)
                source to result
            }
        }

        // 等待第一个成功的或者超时
        val deadline = startTime + timeoutMs
        for (future in futures) {
            val remainingTime = deadline - System.currentTimeMillis()
            if (remainingTime <= 0) break

            try {
                val pair = future.get(remainingTime, TimeUnit.MILLISECONDS)
                results.add(pair)
                if (pair.second.success) {
                    // 找到成功的，取消其他任务
                    futures.forEach { it.cancel(false) }
                    android.util.Log.d("SpeedTester", "speedTestUntilFirstSuccess: found successful source=${pair.first.sourceId}, latency=${pair.second.latencyMs}")
                    return@withContext pair.first.copy(
                        speed = pair.second.latencyMs,
                        lastTested = System.currentTimeMillis(),
                        isAvailable = true
                    )
                }
            } catch (_: Exception) {
                // 忽略，继续等下一个
            }
        }

        // 没有成功的，返回null
        android.util.Log.d("SpeedTester", "speedTestUntilFirstSuccess: no successful source found")
        null
    }

    suspend fun speedTestInBackground(mergedChannels: List<MergedChannel>, onComplete: ((List<MergedChannel>) -> Unit)? = null) {
        withContext(Dispatchers.IO) {
            // 收集所有频道的所有信号源
            val allSources = mergedChannels.flatMap { channel ->
                channel.sources.map { source -> source to channel.id }
            }

            // 并行测速所有源
            val allResults = speedTestSources(allSources.map { it.first })

            // 按频道 ID 分组
            val resultsByChannel = allResults.groupBy { (source, _) ->
                allSources.find { it.first.sourceId == source.sourceId }?.second
            }

            // 更新每个频道的信号源速度
            val updatedChannels = mergedChannels.map { channel ->
                val channelResults = resultsByChannel[channel.id] ?: emptyList()
                val updatedSources = channel.sources.map { source ->
                    val result = channelResults.find { it.first.sourceId == source.sourceId }
                    if (result != null && result.second.success) {
                        source.copy(
                            speed = result.second.latencyMs,
                            lastTested = System.currentTimeMillis(),
                            isAvailable = true
                        )
                    } else {
                        source.copy(isAvailable = result?.second?.success ?: false)
                    }
                }  // 保持原始顺序
                channel.copy(sources = updatedSources)
            }

            ChannelCacheManager.saveMergedChannels(updatedChannels)
            onComplete?.invoke(updatedChannels)
        }
    }

    fun shutdown() {
        threadPool.shutdown()
    }
}
