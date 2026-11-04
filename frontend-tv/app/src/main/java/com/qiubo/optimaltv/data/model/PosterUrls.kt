package com.qiubo.optimaltv.data.model

internal fun isUsablePosterUrl(url: String): Boolean {
    val trimmed = url.trim()
    if (
        !trimmed.startsWith("https://", ignoreCase = true) &&
        !trimmed.startsWith("http://", ignoreCase = true)
    ) {
        return false
    }
    val parsed = runCatching { java.net.URI(trimmed) }.getOrNull() ?: return false
    if (parsed.host.isNullOrBlank()) return false
    val normalized = trimmed.lowercase()
    return "favicon.ico" !in normalized &&
        "placeholder-poster" !in normalized &&
        "placeholder_poster" !in normalized &&
        "/placeholder." !in normalized &&
        !Regex("""placeholder[:_-]""").containsMatchIn(normalized)
}

internal fun usablePosterUrls(vararg candidates: String?): List<String> =
    candidates
        .asSequence()
        .mapNotNull { candidate ->
            candidate?.trim()?.takeIf(::isUsablePosterUrl)
        }
        .distinct()
        .toList()

internal fun firstUsablePosterUrl(vararg candidates: String?): String {
    val usable = usablePosterUrls(*candidates)
    rememberPosterCandidates(usable)
    return usable.firstOrNull().orEmpty()
}

/**
 * Resolves the fallback chain captured by [firstUsablePosterUrl].
 *
 * [com.qiubo.optimaltv.data.model.VodItem] intentionally keeps one URL so catalogue,
 * history, and wire payloads stay compatible. The lookup is keyed by that primary URL,
 * which lets every existing poster call site use the chain without changing its shape.
 */
internal fun posterCandidatesFor(url: String): List<String> {
    val primary = url.trim()
    val registered = PosterCandidateRegistry.candidates(primary)
    if (registered.isNotEmpty()) return registered
    return listOfNotNull(primary.takeIf(::isUsablePosterUrl)).also(::rememberPosterCandidates)
}

private fun rememberPosterCandidates(candidates: List<String>) {
    if (candidates.isEmpty()) return
    PosterCandidateRegistry.remember(candidates)
}

private object PosterCandidateRegistry {
    private const val MAX_ENTRIES = 1_024

    private val entries =
        object : LinkedHashMap<String, List<String>>(64, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, List<String>>?,
            ): Boolean = size > MAX_ENTRIES
        }

    fun remember(candidates: List<String>) {
        val primary = candidates.firstOrNull() ?: return
        synchronized(entries) {
            val merged =
                (entries[primary].orEmpty() + candidates)
                    .distinct()
            entries[primary] = merged
        }
    }

    fun candidates(primary: String): List<String> {
        if (primary.isBlank()) return emptyList()
        synchronized(entries) {
            return entries[primary].orEmpty()
        }
    }
}
