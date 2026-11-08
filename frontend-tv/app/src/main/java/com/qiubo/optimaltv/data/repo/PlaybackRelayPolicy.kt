package com.qiubo.optimaltv.data.repo

import com.qiubo.optimaltv.data.model.LineInfo

/**
 * Resolved official HLS links have a direct URL and no playRef. They still benefit
 * from the local relay's manifest rewrite and segment prefetch, just like lazy lines.
 */
internal object PlaybackRelayPolicy {
    /**
     * Stable key for "this exact selected line failed through the relay". Official
     * lines have no playRef, so using playRef as the key made their failures retry
     * the same broken relay forever. Keep episode-specific playRef suffixes out of
     * the key so the next episode also goes direct on the same selected line.
     */
    fun relayFailureIdentity(line: LineInfo?): String? {
        if (line == null) return null
        line.officialStableLineId
            .takeIf(String::isNotBlank)
            ?.let { return "official:$it" }
        val playLineId =
            line.playRef.split('|')
                .getOrNull(1)
                ?.trim()
                ?.takeIf(String::isNotBlank)
        if (playLineId != null) {
            return "playref:${line.label.trim()}|$playLineId"
        }
        val official = line.official?.line
        if (official != null) {
            return "official-line:${line.vodId.trim()}|${line.label.trim()}|" +
                official.providerGroupKey.trim()
        }
        return "line:${line.vodId.trim()}|${line.label.trim()}"
    }

    fun shouldPreferRelay(
        line: LineInfo?,
        relayBroken: Set<String>,
    ): Boolean {
        val identity = relayFailureIdentity(line) ?: return true
        return identity !in relayBroken
    }

    /**
     * Official direct/parsed HLS URLs are safe to relay because the selected
     * official line remains the only origin. The relay adds manifest-aware
     * segment prefetch; it does not choose another provider.
     */
    fun shouldRelayOfficialUrl(
        url: String,
        preferRelay: Boolean,
    ): Boolean =
        preferRelay &&
            url.trim().startsWith("https://", ignoreCase = true)

    fun shouldRelayDirectLine(
        line: LineInfo,
        preferRelay: Boolean,
    ): Boolean =
        preferRelay &&
            line.official == null &&
            line.playRef.isBlank() &&
            line.url.startsWith("https://") &&
            line.vodId.substringAfterLast('|').startsWith("official:")
}
