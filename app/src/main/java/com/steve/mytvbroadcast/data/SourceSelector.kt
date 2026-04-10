package com.steve.mytvbroadcast.data

enum class SelectionMode {
    FASTEST,
    PREFERRED,
    LAST_USED,
    MANUAL
}

object SourceSelector {

    fun selectSource(
        channel: MergedChannel,
        mode: SelectionMode = SelectionMode.FASTEST,
        availableOnly: Boolean = true
    ): ChannelSource? {
        val sources = if (availableOnly) {
            channel.sources.filter { it.isAvailable }
        } else {
            channel.sources
        }

        if (sources.isEmpty()) return null

        return when (mode) {
            SelectionMode.FASTEST -> {
                sources.filter { it.speed >= 0 }
                    .minByOrNull { it.speed }
                    ?: sources.firstOrNull()
            }
            SelectionMode.PREFERRED -> {
                val preferredId = channel.preferredSourceId
                if (preferredId != null) {
                    sources.find { it.sourceId == preferredId }
                } else {
                    selectSource(channel, SelectionMode.FASTEST, availableOnly)
                }
            }
            SelectionMode.LAST_USED -> {
                val lastUsedId = channel.lastPlayedSourceId
                if (lastUsedId != null) {
                    sources.find { it.sourceId == lastUsedId }
                } else {
                    sources.firstOrNull()
                }
            }
            SelectionMode.MANUAL -> {
                sources.firstOrNull()
            }
        }
    }

    fun getFallbackChain(
        channel: MergedChannel,
        currentSourceId: String,
        maxFallbacks: Int = 3
    ): List<ChannelSource> {
        val availableSources = channel.sources.filter { it.isAvailable && it.sourceId != currentSourceId }
        val sorted = availableSources
            .filter { it.speed >= 0 }
            .sortedBy { it.speed }

        val unsorted = availableSources.filter { it.speed < 0 }

        return (sorted + unsorted).take(maxFallbacks)
    }

    fun selectNextBest(channel: MergedChannel, triedSourceIds: Set<String>): ChannelSource? {
        val available = channel.sources
            .filter { it.isAvailable && it.sourceId !in triedSourceIds }
            .filter { it.speed >= 0 }
            .sortedBy { it.speed }

        return available.firstOrNull() ?: channel.sources
            .filter { it.isAvailable && it.sourceId !in triedSourceIds }
            .firstOrNull()
    }
}
