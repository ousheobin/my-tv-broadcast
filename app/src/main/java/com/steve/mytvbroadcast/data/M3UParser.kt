package com.steve.mytvbroadcast.data

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

object M3UParser {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    // Match any attribute like tvg-name="value" or group-title="value"
    private val attrPattern = Pattern.compile("([\\w-]+)=\"([^\"]*)\"")

    fun parse(content: String): List<Channel> {
        return parse(content, null)
    }

    fun parse(content: String, baseUrl: String?): List<Channel> {
        // Remove BOM if present
        val cleanContent = content.removePrefix("\uFEFF")
        val channels = mutableListOf<Channel>()
        val lines = cleanContent.lines()
        println("M3UParser: Parsing ${lines.size} lines")

        var currentInfo: ChannelInfo? = null

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed == "#EXTM3U") continue

            when {
                trimmed.startsWith("#EXTINF:") -> {
                    currentInfo = parseExtInf(trimmed)
                }
                trimmed.startsWith("#") -> continue
                currentInfo != null && trimmed.isNotEmpty() -> {
                    // Resolve relative URL if baseUrl is provided
                    val resolvedUrl = resolveUrl(baseUrl, trimmed)
                    channels.add(Channel(
                        name = currentInfo.name,
                        logo = currentInfo.logo?.let { resolveUrl(baseUrl, it) },
                        url = resolvedUrl,
                        group = currentInfo.group,
                        tvgId = currentInfo.tvgId
                    ))
                    currentInfo = null
                }
            }
        }
        println("M3UParser: Found ${channels.size} channels")
        return channels
    }

    private fun resolveUrl(baseUrl: String?, url: String): String {
        if (url.startsWith("http://") || url.startsWith("https://")) {
            return url
        }
        if (baseUrl == null) {
            return url
        }
        // Handle relative URLs
        return try {
            val base = java.net.URL(baseUrl)
            java.net.URL(base, url).toString()
        } catch (e: Exception) {
            url
        }
    }

    fun parseFromUrl(url: String): List<Channel> {
        return try {
            println("M3UParser: Fetching URL: $url")
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            println("M3UParser: Response code: ${response.code}")

            // Get the final URL after redirects
            val finalUrl = response.request.url.toString()
            if (finalUrl != url) {
                println("M3UParser: Final URL after redirects: $finalUrl")
            }

            println("M3UParser: Content-Type: ${response.header("Content-Type")}")
            if (!response.isSuccessful) {
                println("M3UParser: HTTP error ${response.code} for URL: $url")
                return emptyList()
            }
            val body = response.body?.string() ?: return emptyList()
            println("M3UParser: Received ${body.length} chars from $url")
            // Print first 500 chars for debugging
            println("M3UParser: First 500 chars: ${body.take(500)}")
            // Pass the final URL as base URL to resolve relative URLs
            parse(body, finalUrl)
        } catch (e: Exception) {
            println("M3UParser: Error fetching $url - ${e.message}")
            e.printStackTrace()
            emptyList()
        }
    }

    private fun parseExtInf(line: String): ChannelInfo {
        // Find the comma that separates attributes from display name
        // Format: #EXTINF:-1 tvg-name="..." tvg-logo="..." group-title="...",Display Name
        val commaIndex = line.lastIndexOf(',')
        if (commaIndex < 0 || commaIndex >= line.length - 1) {
            return ChannelInfo(name = "Unknown", logo = null, group = null, tvgId = null)
        }

        // Everything before the last comma is attributes, after is display name
        val attrPart = line.substring(0, commaIndex)
        val displayName = line.substring(commaIndex + 1).trim()

        // Parse all attributes
        val attrMatcher = attrPattern.matcher(attrPart)
        var tvgName: String? = null
        var tvgLogo: String? = null
        var tvgId: String? = null
        var groupTitle: String? = null

        while (attrMatcher.find()) {
            val attrName = attrMatcher.group(1)
            val attrValue = attrMatcher.group(2)
            when (attrName) {
                "tvg-name" -> tvgName = attrValue
                "tvg-logo" -> tvgLogo = attrValue
                "tvg-id" -> tvgId = attrValue
                "group-title" -> groupTitle = attrValue
            }
        }

        // Priority: tvg-name > displayName (clean displayName without brackets) > tvg-id > Unknown
        // Clean displayName by removing bracketed prefixes like [HD], [VGA]
        val cleanDisplayName = displayName.replace(Regex("^\\[[^]]+\\]"), "").trim()
        val name = tvgName?.takeIf { it.isNotBlank() }
            ?: cleanDisplayName.takeIf { it.isNotBlank() }
            ?: tvgId?.takeIf { it.isNotBlank() }
            ?: "Unknown"

        return ChannelInfo(
            name = name,
            logo = tvgLogo,
            group = groupTitle,
            tvgId = tvgId
        )
    }

    private data class ChannelInfo(
        val name: String,
        val logo: String?,
        val group: String?,
        val tvgId: String?
    )
}
