package com.qiubo.optimaltv.ui.player

import com.qiubo.optimaltv.data.model.LineInfo

object PlaybackPolicy { const val SEEK_STEP_MS = 10_000L }

/** Remembers a VOD source by a stable provider line id instead of its mutable display index. */
object VodSourceSelectionPolicy {
    fun identity(line: LineInfo?): String? {
        if (line == null) return null
        line.official?.line?.stableId?.takeIf(String::isNotBlank)?.let {
            return "official:$it"
        }
        val sourceLineId = line.playRef.split('|').getOrNull(1)?.takeIf(String::isNotBlank)
        if (sourceLineId != null) return "playRef:${line.label.trim()}|$sourceLineId"
        return line.vodId.takeIf(String::isNotBlank)?.let { "vodId:$it" }
    }

    /** Episode-list identity used by the player; kept separate for stored preference compatibility. */
    fun episodeIdentity(line: LineInfo?): String? {
        if (line == null) return null
        line.official?.line?.stableId?.takeIf(String::isNotBlank)?.let {
            return "official:$it"
        }
        val sourceLineId = line.playRef.split('|').getOrNull(1)?.takeIf(String::isNotBlank)
        if (sourceLineId != null) return "${line.label}|$sourceLineId"
        return line.vodId.takeIf(String::isNotBlank)
    }

    /** Official lines group by provider_id first and provider_name only when the id is absent. */
    fun providerGroupKey(line: LineInfo?): String? {
        if (line == null) return null
        val official = line.official?.line
        if (official != null) return official.providerGroupKey.takeIf(String::isNotBlank)
        val provider = PlayerSourceGroupingPolicy.groups(listOf(line)).singleOrNull()?.provider
        return provider?.takeIf(String::isNotBlank)?.let { "legacy:$it" }
    }

    fun isAvailable(line: LineInfo): Boolean =
        line.url.isNotBlank() || line.playRef.isNotBlank() || line.official?.line != null

    /**
     * Select the refreshed episode line without waiting for a vanished canonical source.
     * Stable identity wins, then the previous provider group, then any available line.
     */
    fun selectEpisodeIndex(
        lines: List<LineInfo>,
        preferredIdentities: Iterable<String?> = emptyList(),
        preferredProviderGroupKey: String? = null,
        available: (LineInfo) -> Boolean = ::isAvailable,
    ): Int? {
        val availableIndices = lines.indices.filter { available(lines[it]) }
        if (availableIndices.isEmpty()) return null
        val identities = preferredIdentities
            .mapNotNull { it?.trim()?.takeIf(String::isNotBlank) }
            .toSet()
        if (identities.isNotEmpty()) {
            availableIndices.firstOrNull { index ->
                val line = lines[index]
                identity(line) in identities || episodeIdentity(line) in identities
            }?.let { return it }
        }
        preferredProviderGroupKey?.takeIf(String::isNotBlank)?.let { provider ->
            availableIndices.firstOrNull { providerGroupKey(lines[it]) == provider }?.let { return it }
        }
        return availableIndices.first()
    }

    fun matchingIndex(lines: List<LineInfo>, sourceIdentity: String?): Int? {
        if (sourceIdentity.isNullOrBlank()) return null
        return lines.indexOfFirst { identity(it) == sourceIdentity }.takeIf { it >= 0 }
    }

    fun initialIndex(lines: List<LineInfo>, rememberedIdentity: String?, preferredIndex: Int): Int =
        matchingIndex(lines, rememberedIdentity)
            ?: preferredIndex.takeIf { it in lines.indices }
            ?: 0
}

/** Decide whether a completed item should continue with the next VOD episode. */
object AutoPlayPolicy {
    fun nextEpisodeIndex(isLive: Boolean, isMovie: Boolean, currentIndex: Int, episodeCount: Int): Int? =
        if (!isLive && !isMovie && currentIndex >= 0 && currentIndex + 1 < episodeCount) currentIndex + 1 else null
}

object EpisodeMenuPolicy {
    const val RANGE_SIZE = 10
    fun hasRanges(count: Int) = count >= 20
    fun rangeLabels(count: Int): List<String> = if (!hasRanges(count)) emptyList() else
        (0 until (count + RANGE_SIZE - 1) / RANGE_SIZE).map { page ->
            val from = page * RANGE_SIZE + 1
            val to = minOf(count, from + RANGE_SIZE - 1)
            "第${from}-${to}集"
        }
    fun episodeLabel(index: Int) = (index + 1).toString()

    /** 面板选集网格的展示范围（2026-09-19 集数不全修复）：<20 集全量展示——
     *  此时分页 chip 不渲染，若仍按 10 集/页切片，落在第 2 页时前 10 集永远
     *  够不着（《深渊无间》16 集只见 11-16 的报障）；≥20 集才按页切片。 */
    fun visibleRange(count: Int, page: Int): IntRange =
        if (hasRanges(count)) {
            val start = page * RANGE_SIZE
            start until minOf(count, start + RANGE_SIZE)
        } else {
            0 until count
        }
}

object VisibleVodLinePolicy {
    fun apply(lines: List<LineInfo>): List<LineInfo> = lines.mapIndexed { index, line ->
        line.copy(label = line.label.trim().ifBlank { "信号源${index + 1}" })
    }
}
