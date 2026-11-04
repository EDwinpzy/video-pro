package com.qiubo.optimaltv.ui.player

import com.qiubo.optimaltv.data.model.Episode

internal fun episodeIdentityKeys(episodes: List<Episode>): List<String> {
    val occurrences = mutableMapOf<String, Int>()
    return episodes.map { episode ->
        val identity = episode.url.takeIf { it.isNotBlank() } ?: episode.name.trim()
        val occurrence = occurrences.getOrDefault(identity, 0)
        occurrences[identity] = occurrence + 1
        "$identity\u0000$occurrence"
    }
}

/** A refresh may insert a numbered episode before extras; never reuse the old ordinal. */
internal fun refreshedEpisodeIndex(
    selected: Episode,
    refreshed: List<Episode>,
    stableTokenIdentity: Boolean,
): Int? {
    if (selected.url.isNotBlank()) {
        val tokenMatches = refreshed.indices.filter { refreshed[it].url == selected.url }
        if (tokenMatches.size == 1) return tokenMatches.single()
        if (stableTokenIdentity) return null
    }
    val name = selected.name.trim()
    if (name.isBlank()) return null
    return refreshed.indices.filter { refreshed[it].name.trim() == name }.singleOrNull()
}
