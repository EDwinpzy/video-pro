package com.qiubo.optimaltv.data.source

import com.qiubo.optimaltv.data.model.OfficialPlaybackBinding
import com.qiubo.optimaltv.data.model.OfficialPlaybackLine
import java.net.URLEncoder
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject

/**
 * Official Web playback contract used by the migrated client.
 *
 * The protocol deliberately keeps all line options. The official Web player drops
 * lines without a URL, which loses unresolved parse lines before their ticket can
 * be consumed.
 */
internal object DongpianPlaybackProtocol {
    const val RESOLVE_LINE_PATH = "/v1/playback/resolve-line?view=compact"
    const val RESOLVED_FALLBACK_TTL_MS = 60_000L
    private val imagePathExtension = Regex(
        """\.(?:avif|bmp|gif|heic|heif|ico|jpe?g|png|svg|tiff?|webp)(?=$|[~!@#$%^&*()_+={}\[\];:'",<>/?\\|])""",
        RegexOption.IGNORE_CASE,
    )

    private val ticketExpiredCodes = setOf(
        "playback_resolve_ticket_expired",
        "playback_line_ticket_expired",
        "playback_ticket_expired",
        "playback_url_expired",
    )

    val refreshExhaustedCodes = setOf(
        "playback_resolve_ticket_refresh_exhausted",
        "playback_line_ticket_expired",
        "playback_resolve_ticket_expired",
        "playback_ticket_expired",
        "playback_url_expired",
        "playback_line_ticket_missing",
        "playback_line_resolve_incomplete",
    )

    fun parseLine(
        raw: JSONObject,
        index: Int,
        fallback: OfficialPlaybackLine? = null,
    ): OfficialPlaybackLine? {
        fun text(vararg keys: String): String =
            keys.firstNotNullOfOrNull { key ->
                if (raw.has(key) && !raw.isNull(key)) raw.optString(key).trim().takeIf(String::isNotBlank)
                else null
            }.orEmpty()

        val id = text("id").ifBlank { fallback?.id.orEmpty() }
        val playbackSourceId =
            text("playback_source_id", "playbackSourceId")
                .ifBlank { fallback?.playbackSourceId.orEmpty() }
                // Compact official responses use id as the playback source alias.
                .ifBlank { id }
        val providerId = text("provider_id").ifBlank { fallback?.providerId.orEmpty() }
        val providerName = text("provider_name").ifBlank { fallback?.providerName.orEmpty() }
        val playFrom = text("play_from").ifBlank { fallback?.playFrom.orEmpty() }
        val sourceVodId = text("source_vod_id").ifBlank { fallback?.sourceVodId.orEmpty() }
        val label = text("label").ifBlank { fallback?.label.orEmpty() }
        val displayLabel = text("display_label").ifBlank { fallback?.displayLabel.orEmpty() }
        val url = text("url").ifBlank { fallback?.url.orEmpty() }
        val urlKind = text("url_kind").ifBlank { fallback?.urlKind.orEmpty() }
        val resolveMode = text("resolve_mode").ifBlank { fallback?.resolveMode.orEmpty() }
        val resolveTicket = text("resolve_ticket").ifBlank { fallback?.resolveTicket.orEmpty() }

        if (
            id.isBlank() &&
            playbackSourceId.isBlank() &&
            label.isBlank() &&
            displayLabel.isBlank() &&
            url.isBlank() &&
            resolveTicket.isBlank()
        ) {
            return null
        }

        return OfficialPlaybackLine(
            id = id,
            playbackSourceId = playbackSourceId,
            providerId = providerId,
            providerName = providerName,
            playFrom = playFrom,
            sourceVodId = sourceVodId,
            label = label,
            displayLabel = displayLabel,
            url = url,
            urlKind = urlKind,
            resolveMode = normalizeMode(resolveMode),
            resolveTicket = resolveTicket,
            resolved = optionalBoolean(raw, "resolved"),
            resolveRequired = optionalBoolean(raw, "resolve_required"),
            expiresAt = nullableText(raw, "expires_at"),
            resolvedAt =
                parseTimestamp(nullableText(raw, "resolved_at"))
                    ?: fallback?.resolvedAt,
            selected = optionalBoolean(raw, "selected"),
            defaultPriority = optionalBoolean(raw, "default_priority"),
            preferenceWeight = optionalInt(raw, "preference_weight"),
            tokenScenario = text("token_scenario"),
            baseTokens = optionalLong(raw, "base_tokens"),
            requiredTokens = optionalLong(raw, "required_tokens"),
            priceMultiplierBps = optionalInt(raw, "price_multiplier_bps"),
            minimumUserLevel = optionalInt(raw, "minimum_user_level"),
            headers = parseHeaders(raw.opt("headers")),
            userAgent = text("user_agent", "userAgent"),
            mimeType = text("mime_type", "mimeType"),
            latencyMs = optionalLong(raw, "latency_ms"),
            probeStatus = text("probe_status"),
            probeCached = optionalBoolean(raw, "probe_cached"),
            sourceIndex = optionalInt(raw, "source_index") ?: index,
            rawJson = raw.toString(),
        )
    }

    fun parseLines(array: JSONArray?): List<OfficialPlaybackLine> =
        (0 until (array?.length() ?: 0)).mapNotNull { index ->
            array?.optJSONObject(index)?.let { parseLine(it, index) }
        }

    /** Empty playback IDs are retained; only non-empty IDs participate in stable de-duplication. */
    fun deduplicate(lines: List<OfficialPlaybackLine>): List<OfficialPlaybackLine> {
        val seen = HashSet<String>()
        return lines.filter { line ->
            val id = line.playbackSourceId.trim()
            id.isEmpty() || seen.add(id)
        }
    }

    fun ticketOf(line: OfficialPlaybackLine): String {
        line.resolveTicket.trim().takeIf(String::isNotBlank)?.let { return it }
        val url = line.url.trim()
        if (url.startsWith("resolve://")) return url.removePrefix("resolve://").trim()
        return if (line.urlKind == "resolve_ticket") url else ""
    }

    /**
     * An explicit direct mode wins over malformed auxiliary parse hints. Otherwise,
     * preserve and resolve lines that only advertise parse through legacy/runtime fields.
     */
    fun isParseLine(line: OfficialPlaybackLine): Boolean {
        val mode = normalizeMode(line.resolveMode)
        if (mode == "direct") return false
        return mode == "parse" ||
            line.resolveRequired == true ||
            line.url.trim().startsWith("resolve://", ignoreCase = true) ||
            line.urlKind.trim().equals("resolve_ticket", ignoreCase = true) ||
            line.resolveTicket.isNotBlank()
    }

    fun needsResolution(
        line: OfficialPlaybackLine,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        if (!isParseLine(line)) return false
        if (line.resolved != true) return true
        val expiresAt = line.expiresAt.trim()
        if (expiresAt.isNotEmpty()) {
            val expiry = parseTimestamp(expiresAt) ?: return true
            return expiry <= now
        }
        val resolvedAt = line.resolvedAt ?: return true
        return now - resolvedAt >= RESOLVED_FALLBACK_TTL_MS
    }

    /** A direct line or a fresh parse result can be sent to the player without spending a ticket. */
    fun canPlayDirectly(
        line: OfficialPlaybackLine,
        forceRefresh: Boolean = false,
        now: Long = System.currentTimeMillis(),
    ): Boolean =
        isPlayableMediaLine(line) &&
            !(isParseLine(line) && (forceRefresh || needsResolution(line, now)))

    fun initialResolvePath(
        episodeToken: String,
        sequenceIndex: Int?,
        episodeCount: Int?,
        contentKind: String,
    ): String {
        val encodedToken = URLEncoder.encode(episodeToken.trim(), "UTF-8").replace("+", "%20")
        val query = linkedMapOf(
            "view" to "compact",
            "episode_index" to sequenceIndex?.takeIf { it >= 0 }?.toString(),
            "episode_count" to episodeCount?.takeIf { it > 0 }?.toString(),
            "content_kind" to contentKind.trim().takeIf(String::isNotBlank),
        )
        return withQuery("/v1/playback/resolve/$encodedToken", query)
    }

    /** Used only after resolve-line reports an expired ticket. */
    fun refreshResolvePath(binding: OfficialPlaybackBinding): String {
        val encodedToken =
            URLEncoder.encode(binding.episodeToken.trim(), "UTF-8").replace("+", "%20")
        val line = binding.line
        val query = linkedMapOf(
            "view" to "compact",
            "line" to line.playbackSourceId.trim().takeIf(String::isNotBlank),
            "provider_id" to line.providerId.trim().takeIf(String::isNotBlank),
            "play_from" to line.playFrom.trim().takeIf(String::isNotBlank),
            "source_vod_id" to line.sourceVodId.trim().takeIf(String::isNotBlank),
            "episode_index" to binding.sequenceIndex?.takeIf { it >= 0 }?.toString(),
            "episode_count" to binding.episodeCount?.takeIf { it > 0 }?.toString(),
            "content_kind" to binding.contentKind.trim().takeIf(String::isNotBlank),
        )
        return withQuery("/v1/playback/resolve/$encodedToken", query)
    }

    fun matchingRefreshedLine(
        original: OfficialPlaybackLine,
        refreshed: List<OfficialPlaybackLine>,
    ): OfficialPlaybackLine? {
        val playbackId = original.playbackSourceId.trim()
        if (playbackId.isNotEmpty()) {
            return refreshed.firstOrNull { it.playbackSourceId.trim() == playbackId }
        }
        return null
    }

    fun mergeResolvedLine(
        original: OfficialPlaybackLine,
        response: JSONObject,
        now: Long = System.currentTimeMillis(),
    ): OfficialPlaybackLine {
        val parsed =
            parseLine(response, original.sourceIndex ?: 0, fallback = original)
                ?: original
        return parsed.copy(
            id = parsed.id.ifBlank { original.id },
            playbackSourceId =
                parsed.playbackSourceId.ifBlank { original.playbackSourceId },
            providerId = parsed.providerId.ifBlank { original.providerId },
            providerName = parsed.providerName.ifBlank { original.providerName },
            playFrom = parsed.playFrom.ifBlank { original.playFrom },
            sourceVodId = parsed.sourceVodId.ifBlank { original.sourceVodId },
            resolveTicket = original.resolveTicket,
            resolved = true,
            resolvedAt = now,
            sourceIndex = original.sourceIndex ?: parsed.sourceIndex,
        )
    }

    fun isPlayableMediaUrl(url: String): Boolean {
        val normalized = url.trim()
        val path = normalized.substringBefore('?').substringBefore('#')
        return normalized.isNotEmpty() &&
            !normalized.startsWith("resolve://", ignoreCase = true) &&
            !imagePathExtension.containsMatchIn(path) &&
            (
                normalized.startsWith("http://", ignoreCase = true) ||
                    normalized.startsWith("https://", ignoreCase = true)
                )
    }

    /** Image endpoints are common bad parse results and must never reach ExoPlayer. */
    fun isPlayableMediaLine(line: OfficialPlaybackLine): Boolean =
        isPlayableMediaUrl(line.url) &&
            !line.mimeType.trim().startsWith("image/", ignoreCase = true)

    fun isTicketExpired(statusCode: Int, wireCode: String): Boolean =
        statusCode == 404 && wireCode.trim() == "playback_resolve_ticket_expired"

    fun isRefreshExhausted(statusCode: Int, wireCode: String): Boolean =
        wireCode.trim() in refreshExhaustedCodes ||
            (statusCode == 404 && wireCode.trim() in ticketExpiredCodes)

    private fun withQuery(
        path: String,
        values: LinkedHashMap<String, String?>,
    ): String {
        val query =
            values.entries.mapNotNull { (key, value) ->
                value?.takeIf(String::isNotBlank)?.let {
                    "$key=${URLEncoder.encode(it, "UTF-8").replace("+", "%20")}"
                }
            }.joinToString("&")
        return if (query.isBlank()) path else "$path?$query"
    }

    private fun normalizeMode(value: String): String =
        value.trim().lowercase().takeIf { it == "parse" || it == "direct" }.orEmpty()

    private fun nullableText(obj: JSONObject, key: String): String =
        if (obj.has(key) && !obj.isNull(key)) obj.optString(key).trim() else ""

    private fun optionalBoolean(obj: JSONObject, key: String): Boolean? =
        if (obj.has(key) && !obj.isNull(key)) obj.optBoolean(key) else null

    private fun optionalInt(obj: JSONObject, key: String): Int? =
        if (obj.has(key) && !obj.isNull(key)) obj.optInt(key) else null

    private fun optionalLong(obj: JSONObject, key: String): Long? =
        if (obj.has(key) && !obj.isNull(key)) obj.optLong(key) else null

    private fun parseTimestamp(raw: String): Long? {
        val value = raw.trim()
        if (value.isEmpty()) return null
        value.toLongOrNull()?.let { numeric ->
            return if (numeric in 1..9_999_999_999L) numeric * 1_000 else numeric
        }
        return runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()
            ?: runCatching { java.time.OffsetDateTime.parse(value).toInstant().toEpochMilli() }
                .getOrNull()
    }

    private fun parseHeaders(value: Any?): Map<String, String> {
        val result = linkedMapOf<String, String>()
        when (value) {
            is JSONObject -> {
                value.keys().forEach { key ->
                    val itemValue = value.opt(key)
                    if (itemValue != null && itemValue != JSONObject.NULL) {
                        putHeader(result, key, itemValue.toString())
                    }
                }
            }
            is JSONArray -> {
                (0 until value.length()).forEach { index ->
                    val item = value.optJSONObject(index) ?: return@forEach
                    val name =
                        item.optString("name")
                            .ifBlank { item.optString("key") }
                            .trim()
                    val headerValue =
                        item.optString("value")
                            .ifBlank { item.optString("val") }
                    putHeader(result, name, headerValue)
                }
            }
        }
        return result
    }

    private fun putHeader(
        target: MutableMap<String, String>,
        rawName: String,
        rawValue: String,
    ) {
        val name = rawName.trim()
        val value = rawValue.trim()
        if (
            name.isEmpty() ||
            value.isEmpty() ||
            !name.matches(Regex("^[A-Za-z0-9!#$%&'*+.^_`|~-]+$")) ||
            '\r' in value ||
            '\n' in value
        ) {
            return
        }
        target[name] = value
    }
}
