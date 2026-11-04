package com.qiubo.optimaltv.data.source

import com.qiubo.optimaltv.data.model.OfficialPlaybackBinding
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DongpianPlaybackProtocolTest {
    @Test
    fun allLineOptionsSurviveBeforeSelectedParseLineIsResolved() {
        val lines =
            DongpianPlaybackProtocol.parseLines(
                JSONArray(
                    """
                    [
                      {
                        "id": "internal-direct",
                        "playback_source_id": "ps-direct",
                        "provider_id": "provider-a",
                        "provider_name": "Provider A",
                        "url": "https://media.example.invalid/direct.m3u8",
                        "url_kind": "m3u8",
                        "resolve_mode": "direct",
                        "resolved": true
                      },
                      {
                        "id": "internal-parse",
                        "playback_source_id": "ps-parse",
                        "provider_id": "provider-b",
                        "provider_name": "Provider B",
                        "resolve_mode": "parse",
                        "resolve_required": true,
                        "resolve_ticket": "ticket-parse",
                        "resolved": false
                      },
                      {
                        "id": "internal-parse-duplicate",
                        "playback_source_id": "ps-parse",
                        "provider_id": "provider-b",
                        "url": "resolve://newer-ticket",
                        "resolve_mode": "parse"
                      },
                      {
                        "id": "internal-no-playback-id",
                        "provider_id": "provider-c",
                        "resolve_mode": "parse",
                        "resolve_ticket": "ticket-without-playback-id"
                      }
                    ]
                    """.trimIndent(),
                ),
            )

        val deduplicated = DongpianPlaybackProtocol.deduplicate(lines)

        assertEquals(4, lines.size)
        assertEquals(listOf("ps-direct", "ps-parse", "internal-no-playback-id"), deduplicated.map { it.playbackSourceId })
        assertEquals("parse", deduplicated[1].resolveMode)
        assertTrue(DongpianPlaybackProtocol.needsResolution(deduplicated[1]))
        assertFalse(DongpianPlaybackProtocol.needsResolution(deduplicated[0]))
    }

    @Test
    fun completeLineMetadataIsPreservedForPlaybackHeadersAndAudit() {
        val line =
            DongpianPlaybackProtocol.parseLine(
                JSONObject(
                    """
                    {
                      "id": "internal",
                      "playback_source_id": "source-1",
                      "provider_id": "provider-1",
                      "provider_name": "Provider One",
                      "play_from": "web",
                      "source_vod_id": "vod-1",
                      "label": "Label",
                      "display_label": "Display",
                      "url": "https://media.example.invalid/index.m3u8",
                      "url_kind": "m3u8",
                      "resolve_mode": "direct",
                      "resolved": true,
                      "expires_at": "2026-11-01T12:00:00Z",
                      "resolved_at": 1760000000000,
                      "selected": true,
                      "default_priority": true,
                      "preference_weight": 42,
                      "token_scenario": "movie_official",
                      "base_tokens": 3,
                      "required_tokens": 2,
                      "price_multiplier_bps": 150,
                      "minimum_user_level": 2,
                      "headers": {"Referer": "https://example.invalid/player"},
                      "user_agent": "ExamplePlayer/1.0",
                      "mime_type": "application/vnd.apple.mpegurl",
                      "latency_ms": 88,
                      "probe_status": "ok",
                      "probe_cached": true,
                      "source_index": 7,
                      "future_field": {"kept": true}
                    }
                    """.trimIndent(),
                ),
                index = 0,
            )

        assertNotNull(line)
        requireNotNull(line)
        assertEquals("source-1", line.playbackSourceId)
        assertEquals("provider-1", line.providerId)
        assertEquals("Provider One", line.providerName)
        assertEquals("web", line.playFrom)
        assertEquals("vod-1", line.sourceVodId)
        assertEquals("https://example.invalid/player", line.headers["Referer"])
        assertEquals("ExamplePlayer/1.0", line.userAgent)
        assertEquals("application/vnd.apple.mpegurl", line.mimeType)
        assertEquals(42, line.preferenceWeight)
        assertEquals(150, line.priceMultiplierBps)
        assertEquals(7, line.sourceIndex)
        assertTrue(line.rawJson.contains("future_field"))
    }

    @Test
    fun parseTicketsUseOfficialPrecedenceAndExpiryRules() {
        val explicit =
            DongpianPlaybackProtocol.parseLine(
                JSONObject(
                    """
                    {
                      "id": "1",
                      "playback_source_id": "1",
                      "resolve_mode": "parse",
                      "resolve_ticket": "explicit",
                      "url": "resolve://url-ticket",
                      "url_kind": "resolve_ticket"
                    }
                    """.trimIndent(),
                ),
                0,
            )!!
        val urlTicket =
            explicit.copy(resolveTicket = "", url = "resolve://url-ticket")
        val kindTicket =
            explicit.copy(resolveTicket = "", url = "kind-ticket", urlKind = "resolve_ticket")

        assertEquals("explicit", DongpianPlaybackProtocol.ticketOf(explicit))
        assertEquals("url-ticket", DongpianPlaybackProtocol.ticketOf(urlTicket))
        assertEquals("kind-ticket", DongpianPlaybackProtocol.ticketOf(kindTicket))

        val now = 2_000_000L
        assertTrue(
            DongpianPlaybackProtocol.needsResolution(
                explicit.copy(resolved = true, resolvedAt = now - 60_000L, expiresAt = ""),
                now,
            ),
        )
        assertFalse(
            DongpianPlaybackProtocol.needsResolution(
                explicit.copy(resolved = true, expiresAt = "1970-01-01T00:00:10Z"),
                now = 9_000L,
            ),
        )
        assertTrue(
            DongpianPlaybackProtocol.needsResolution(
                explicit.copy(resolved = true, expiresAt = "1970-01-01T00:00:01Z"),
                now = 2_000L,
            ),
        )
    }

    @Test
    fun directAndFreshResolvedLinesBypassResolveLine() {
        val direct =
            DongpianPlaybackProtocol.parseLine(
                JSONObject(
                    """
                    {
                      "id": "direct",
                      "playback_source_id": "source-direct",
                      "url": "https://media.example.invalid/direct.m3u8",
                      "resolve_mode": "direct",
                      "resolved": true
                    }
                    """.trimIndent(),
                ),
                0,
            )!!
        val freshParse =
            DongpianPlaybackProtocol.parseLine(
                JSONObject(
                    """
                    {
                      "id": "parse",
                      "playback_source_id": "source-parse",
                      "url": "https://media.example.invalid/parsed.m3u8",
                      "resolve_mode": "parse",
                      "resolve_ticket": "ticket",
                      "resolved": true,
                      "expires_at": "1970-01-01T00:01:40Z"
                    }
                    """.trimIndent(),
                ),
                0,
            )!!

        assertFalse(DongpianPlaybackProtocol.isParseLine(direct))
        assertTrue(DongpianPlaybackProtocol.canPlayDirectly(direct, now = 2_000L))
        assertTrue(DongpianPlaybackProtocol.isParseLine(freshParse))
        assertFalse(DongpianPlaybackProtocol.needsResolution(freshParse, now = 2_000L))
        assertTrue(DongpianPlaybackProtocol.canPlayDirectly(freshParse, now = 2_000L))
        assertFalse(
            DongpianPlaybackProtocol.canPlayDirectly(
                freshParse,
                forceRefresh = true,
                now = 2_000L,
            ),
        )
        assertTrue(
            DongpianPlaybackProtocol.canPlayDirectly(
                direct,
                forceRefresh = true,
                now = 2_000L,
            ),
        )
    }

    @Test
    fun unresolvedLegacyParseMarkersRequireResolutionBeforePlayback() {
        val line =
            DongpianPlaybackProtocol.parseLine(
                JSONObject(
                    """
                    {
                      "id": "legacy-parse",
                      "playback_source_id": "source-legacy",
                      "url": "resolve://ticket",
                      "resolve_required": true
                    }
                    """.trimIndent(),
                ),
                0,
            )!!

        assertTrue(DongpianPlaybackProtocol.isParseLine(line))
        assertTrue(DongpianPlaybackProtocol.needsResolution(line))
        assertFalse(DongpianPlaybackProtocol.canPlayDirectly(line))
    }

    @Test
    fun refreshUsesOriginalSelectionAndOnlyAcceptsTheSamePlaybackSourceId() {
        val line =
            DongpianPlaybackProtocol.parseLine(
                JSONObject(
                    """
                    {
                      "id": "internal",
                      "playback_source_id": "source/one",
                      "provider_id": "provider",
                      "play_from": "web",
                      "source_vod_id": "vod",
                      "resolve_mode": "parse",
                      "resolve_ticket": "old"
                    }
                    """.trimIndent(),
                ),
                0,
            )!!
        val binding =
            OfficialPlaybackBinding(
                episodeToken = "episode/token",
                sequenceIndex = 2,
                episodeCount = 12,
                contentKind = "series",
                line = line,
            )

        val path = DongpianPlaybackProtocol.refreshResolvePath(binding)
        assertTrue(path.startsWith("/v1/playback/resolve/episode%2Ftoken?"))
        assertTrue(path.contains("view=compact"))
        assertTrue(path.contains("line=source%2Fone"))
        assertTrue(path.contains("provider_id=provider"))
        assertTrue(path.contains("play_from=web"))
        assertTrue(path.contains("source_vod_id=vod"))
        assertTrue(path.contains("episode_index=2"))
        assertTrue(path.contains("episode_count=12"))
        assertTrue(path.contains("content_kind=series"))

        val same = line.copy(resolveTicket = "new")
        assertEquals(
            same,
            DongpianPlaybackProtocol.matchingRefreshedLine(line, listOf(line.copy(playbackSourceId = "other"), same)),
        )
        assertNull(
            DongpianPlaybackProtocol.matchingRefreshedLine(
                line.copy(playbackSourceId = ""),
                listOf(same),
            ),
        )
        assertNull(
            DongpianPlaybackProtocol.matchingRefreshedLine(
                line.copy(
                    id = "",
                    playbackSourceId = "",
                    providerId = "",
                    playFrom = "",
                    sourceVodId = "",
                ),
                listOf(same),
            ),
        )
    }

    @Test
    fun resolvedParseLineKeepsTicketAndIsMarkedFresh() {
        val original =
            DongpianPlaybackProtocol.parseLine(
                JSONObject(
                    """
                    {
                      "id": "internal",
                      "playback_source_id": "source",
                      "provider_id": "provider",
                      "resolve_mode": "parse",
                      "resolve_ticket": "ticket",
                      "resolved": false
                    }
                    """.trimIndent(),
                ),
                0,
            )!!
        val merged =
            DongpianPlaybackProtocol.mergeResolvedLine(
                original,
                JSONObject(
                    """
                    {
                      "url": "https://media.example.invalid/index.m3u8",
                      "url_kind": "m3u8",
                      "resolved": true,
                      "expires_at": "1970-01-01T00:01:40Z"
                    }
                    """.trimIndent(),
                ),
                now = 1_000L,
            )

        assertEquals("source", merged.playbackSourceId)
        assertEquals("ticket", merged.resolveTicket)
        assertEquals(1_000L, merged.resolvedAt)
        assertTrue(merged.resolved == true)
        assertFalse(DongpianPlaybackProtocol.needsResolution(merged, now = 2_000L))
    }

    @Test
    fun onlyTheOfficialExpiredTicketCodeRefreshes() {
        assertTrue(
            DongpianPlaybackProtocol.isTicketExpired(
                404,
                "playback_resolve_ticket_expired",
            ),
        )
        assertFalse(
            DongpianPlaybackProtocol.isTicketExpired(
                503,
                "playback_resolve_ticket_expired",
            ),
        )
        assertFalse(
            DongpianPlaybackProtocol.isTicketExpired(
                404,
                "playback_line_unavailable",
            ),
        )
    }

    @Test
    fun imageEndpointsAndImageMimeTypesAreNotPlayableMedia() {
        assertFalse(
            DongpianPlaybackProtocol.isPlayableMediaUrl(
                "https://media.example.invalid/poster.jpg~tplv-image.image?mime_type=video_mp4",
            ),
        )
        assertFalse(
            DongpianPlaybackProtocol.isPlayableMediaUrl(
                "https://media.example.invalid/poster.webp",
            ),
        )
        assertTrue(
            DongpianPlaybackProtocol.isPlayableMediaUrl(
                "https://media.example.invalid/video.mp4?poster=cover.jpg",
            ),
        )

        val line =
            DongpianPlaybackProtocol.parseLine(
                JSONObject()
                    .put("id", "image-mime")
                    .put("url", "https://media.example.invalid/opaque")
                    .put("mime_type", "image/jpeg")
                    .put("resolve_mode", "direct"),
                0,
            )!!
        assertFalse(DongpianPlaybackProtocol.isPlayableMediaLine(line))
    }
}
